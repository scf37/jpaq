package io.github.scf37.jpaq;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A storage made of three shell commands, as simple as a backup script: each is run with {@code
 * sh -c} and gets the blob name as {@code $1}.
 *
 * <ul>
 *   <li>read: writes the blob to its stdout, e.g. {@code cat "/backup/$1"}
 *   <li>write: reads the new blob from its stdin, e.g. {@code cat > "/backup/$1"} or {@code aws s3
 *       cp - "s3://bucket/$1"}
 *   <li>delete: removes the blob, e.g. {@code rm -f "/backup/$1"}
 * </ul>
 *
 * A command that fails (exit code not 0) makes the operation fail; the reason is not known, which
 * is all the archiver needs. No capabilities beyond streaming.
 */
public final class ShellStorage implements Storage {
  private final String readCmd;
  private final String writeCmd;
  private final String deleteCmd;
  private final Path dir;

  public ShellStorage(String readCmd, String writeCmd, String deleteCmd) {
    this(readCmd, writeCmd, deleteCmd, null);
  }

  /** @param dir working directory of the commands, null for the current one */
  public ShellStorage(String readCmd, String writeCmd, String deleteCmd, Path dir) {
    this.readCmd = readCmd;
    this.writeCmd = writeCmd;
    this.deleteCmd = deleteCmd;
    this.dir = dir;
  }

  private Process start(String cmd, String name, boolean stdin) throws IOException {
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd, "sh", name);
    if (dir != null) pb.directory(dir.toFile());
    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
    if (!stdin) pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
    return pb.start();
  }

  private static void check(Process p, String what, String name) throws IOException {
    try {
      int rc = p.waitFor();
      if (rc != 0) throw new IOException(what + " " + name + " failed (exit code " + rc + ")");
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException(what + " " + name + " interrupted", e);
    }
  }

  @Override
  public InputStream read(String name) throws IOException {
    Process p = start(readCmd, name, false);
    p.getOutputStream().close();
    return new FilterInputStream(p.getInputStream()) {
      private boolean closed;
      private boolean eof;

      @Override
      public int read() throws IOException {
        int c = super.read();
        if (c < 0) eof = true;
        return c;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n < 0) eof = true;
        return n;
      }

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        super.close();
        if (eof) {
          check(p, "read", name);  // read to the end: the exit code tells whether it all arrived
        } else {
          p.destroy();  // stopped early on purpose
        }
      }
    };
  }

  @Override
  public OutputStream write(String name) throws IOException {
    Process p = start(writeCmd, name, true);
    p.getInputStream().close();
    return new FilterOutputStream(p.getOutputStream()) {
      private boolean closed;

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
      }

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
          out.close();
        } finally {
          check(p, "write", name);
        }
      }
    };
  }

  @Override
  public void delete(String name) throws IOException {
    Process p = start(deleteCmd, name, false);
    p.getInputStream().close();
    p.getOutputStream().close();
    try {
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("delete " + name + " timed out");
      }
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("delete " + name + " interrupted", e);
    }
    if (p.exitValue() != 0) throw new IOException("delete " + name + " failed");
  }
}
