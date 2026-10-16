package io.github.scf37.jpaq.engine;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

/**
 * File system access with byte string names, relative to the working directory of a run. The
 * helpers that do not depend on it are static.
 */
final class Fs {
  /** True on systems with unix style permissions (the 'u' attributes). */
  static final boolean UNIX = FileSystems.getDefault().supportedFileAttributeViews().contains("unix");
  static final boolean WINDOWS = !UNIX && System.getProperty("os.name", "").startsWith("Windows");

  private final Ctx ctx;
  private final Path base;  // directory against which relative names are resolved, or null

  Fs(Ctx ctx, Path base) {
    this.ctx = ctx;
    this.base = base;
  }

  /** Converts a byte string (UTF-8) to a Path. */
  Path path(String name) {
    String s = Strs.fromBytes(name);
    if (WINDOWS) s = s.replace('/', '\\');
    Path p = Path.of(s);
    return base != null ? base.resolve(p) : p;
  }

  /** Converts a file name from the file system to a byte string with '/' separators. */
  static String bytes(String javaName) {
    if (WINDOWS) javaName = javaName.replace('\\', '/');
    return Strs.toBytes(javaName);
  }

  /** Returns true if a file or directory (without trailing /) exists. */
  boolean exists(String filename) {
    int len = filename.length();
    if (len < 1) return false;
    if (filename.charAt(len - 1) == '/') filename = filename.substring(0, len - 1);
    if (filename.isEmpty()) return false;
    try {
      return Files.exists(path(filename), LinkOption.NOFOLLOW_LINKS);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Deletes a file, returns true if successful. */
  boolean deleteFile(String filename) {
    try {
      Files.delete(path(filename));
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** Message corresponding to the C strerror() of a failed file operation. */
  static String strerror(Exception e) {
    if (e instanceof NoSuchFileException) return "No such file or directory";
    if (e instanceof AccessDeniedException) return "Permission denied";
    if (e instanceof FileAlreadyExistsException) return "File exists";
    if (e instanceof NotDirectoryException) return "Not a directory";
    if (e instanceof DirectoryNotEmptyException) return "Directory not empty";
    if (e instanceof FileSystemException fe && fe.getReason() != null) return fe.getReason();
    String m = e.getMessage();
    if (m != null) {
      int i = m.lastIndexOf(" (");
      if (m.endsWith(")") && i >= 0) return m.substring(i + 2, m.length() - 1);
      return m;
    }
    return e.toString();
  }

  /** Prints "filename: error message" to stderr (perror). */
  void printerr(String filename, Exception e) {
    String reason = strerror(e);
    ctx.emit(new io.github.scf37.jpaq.ErrorLine.FileError(filename, reason, e,
        filename + ": " + reason + "\n"));
  }

  /** Prints "filename: error message" for the most recent failure. */
  void printerr(String filename, String message) {
    ctx.emit(new io.github.scf37.jpaq.ErrorLine.FileError(filename, message, null,
        filename + ": " + message + "\n"));
  }

  /** File status as returned by lstat(). */
  record Stat(boolean regular, boolean directory, long mode, long size, long mtime) {}

  /** lstat(): returns null on failure after storing the failure in {@code err[0]}. */
  Stat lstat(String filename, Exception[] err) {
    try {
      Path p = path(filename);
      if (UNIX) {
        Map<String, Object> m = Files.readAttributes(p, "unix:mode,size,lastModifiedTime",
            LinkOption.NOFOLLOW_LINKS);
        int mode = (Integer) m.get("mode");
        long size = (Long) m.get("size");
        long mtime = ((FileTime) m.get("lastModifiedTime")).toInstant().getEpochSecond();
        int fmt = mode & 0170000;
        return new Stat(fmt == 0100000, fmt == 0040000, mode, size, mtime);
      } else {
        var a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        long attr = 0;
        try {
          var d = Files.readAttributes(p, java.nio.file.attribute.DosFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS);
          if (d.isReadOnly()) attr |= 1;
          if (d.isHidden()) attr |= 2;
          if (d.isSystem()) attr |= 4;
          if (d.isDirectory()) attr |= 0x10;
          if (d.isArchive()) attr |= 0x20;
        } catch (UnsupportedOperationException | IOException e) {
          if (a.isDirectory()) attr |= 0x10;
        }
        return new Stat(a.isRegularFile(), a.isDirectory(), attr, a.size(),
            a.lastModifiedTime().toInstant().getEpochSecond());
      }
    } catch (IOException | RuntimeException e) {
      err[0] = e;
      return null;
    }
  }

  /** Sets the modification date (decimal) and attributes of an existing file unless 0. */
  void setDateAttr(String filename, long date, long attr) {
    Path p = path(filename);
    if (date > 0) {
      try {
        Files.setLastModifiedTime(p, FileTime.from(Instant.ofEpochSecond(Dates.unixTime(date))));
      } catch (IOException | RuntimeException e) {
        // ignored like utime() failures
      }
    }
    if (UNIX) {
      if ((attr & 255) == 'u') {
        try {
          Files.setAttribute(p, "unix:mode", (int) (attr >> 8));
        } catch (IOException | RuntimeException e) {
          // ignored like chmod() failures
        }
      }
    } else if (WINDOWS && (attr & 255) == 'w') {
      try {
        DosFileAttributeView v = Files.getFileAttributeView(p, DosFileAttributeView.class);
        long a = attr >> 8;
        v.setReadOnly((a & 1) != 0);
        v.setHidden((a & 2) != 0);
        v.setSystem((a & 4) != 0);
        v.setArchive((a & 0x20) != 0);
      } catch (IOException | RuntimeException e) {
        // ignored
      }
    }
  }

  /**
   * Creates directories as needed. For example if path="/tmp/foo/bar" then create directories /,
   * /tmp, and /tmp/foo unless they exist. Set date and attributes of the last component unless 0.
   */
  void makepath(String path, long date, long attr) {
    StringBuilder p = new StringBuilder(path);
    for (int i = 0; i < p.length(); ++i) {
      if (p.charAt(i) == '\\' || p.charAt(i) == '/') {
        String dir = p.substring(0, i);
        if (!dir.isEmpty()) {
          try {
            Files.createDirectory(path(dir));
          } catch (IOException | RuntimeException e) {
            // already exists or cannot create; ignored like mkdir()
          }
        }
        p.setCharAt(i, '/');
      }
    }
    String filename = p.toString();
    if (!filename.isEmpty() && filename.charAt(filename.length() - 1) == '/')
      filename = filename.substring(0, filename.length() - 1);
    if (!filename.isEmpty()) setDateAttr(filename, date, attr);
  }

  void makepath(String path) {
    makepath(path, 0, 0);
  }
}
