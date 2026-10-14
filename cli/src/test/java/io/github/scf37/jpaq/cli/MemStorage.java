package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.api.Capability;
import io.github.scf37.jpaq.api.Storage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Archive storage in memory that only offers what a shell script could: streaming read, write and
 * delete (plus capabilities on request). Every call is recorded; a failed read says nothing about
 * the reason.
 */
final class MemStorage implements Storage {
  final Map<String, byte[]> files = Collections.synchronizedMap(new TreeMap<>());
  final List<String> log = Collections.synchronizedList(new ArrayList<>());
  private final Set<Capability> caps;
  /** Names whose write has been started, to catch overwrites. */
  final Set<String> written = Collections.synchronizedSet(new java.util.HashSet<>());
  boolean allowOverwrite;
  long bytesRead;

  MemStorage(Capability... caps) {
    this.caps = caps.length == 0 ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(List.of(caps));
  }

  @Override
  public Set<Capability> capabilities() {
    return caps;
  }

  @Override
  public InputStream read(String name) throws IOException {
    log.add("read " + name);
    byte[] b = files.get(name);
    if (b == null) throw new IOException("read failed");  // no reason given
    return counting(new ByteArrayInputStream(b));
  }

  private InputStream counting(InputStream in) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        int c = in.read();
        if (c >= 0) bytesRead++;
        return c;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) bytesRead += n;
        return n;
      }
    };
  }

  @Override
  public InputStream read(String name, long offset) throws IOException {
    log.add("read " + name + " @" + offset);
    byte[] b = files.get(name);
    if (b == null) throw new IOException("read failed");
    return counting(new ByteArrayInputStream(b, (int) Math.min(offset, b.length), b.length));
  }

  @Override
  public OutputStream write(String name) throws IOException {
    log.add("write " + name);
    if (!written.add(name) && !allowOverwrite)
      throw new IOException("blob written twice: " + name);
    return new ByteArrayOutputStream() {
      private boolean closed;

      @Override
      public void close() {
        if (closed) return;
        closed = true;
        files.put(name, toByteArray());  // visible when closed
        log.add("commit " + name);
      }
    };
  }

  @Override
  public void delete(String name) {
    log.add("delete " + name);
    files.remove(name);
  }

  @Override
  public boolean exists(String name) {
    log.add("exists " + name);
    return files.containsKey(name);
  }

  @Override
  public long length(String name) throws IOException {
    byte[] b = files.get(name);
    if (b == null) throw new IOException("no blob");
    return b.length;
  }
}
