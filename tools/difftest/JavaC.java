import io.github.scf37.jpaq.libzpaq.*;
import java.io.*;
import java.nio.file.*;
public class JavaC {
  public static void main(String[] a) throws Exception {
    if (a[0].equals("c")) {
      byte[] data = Files.readAllBytes(Path.of(a[2]));
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      ZpaqReader r = new ZpaqReader() { int p = 0; public int get() { return p < data.length ? data[p++] & 255 : -1; }
        public int read(byte[] b, int off, int n) { n = Math.min(n, data.length - p); System.arraycopy(data, p, b, off, n); p += n; return n; } };
      ZpaqWriter w = new ZpaqWriter() { public void put(int c) { bo.write(c); } public void write(byte[] b, int off, int n) { bo.write(b, off, n); } };
      Libzpaq.compress(r, w, a[1], a.length > 4 ? a[4] : null, a.length > 5 ? a[5] : null, true);
      Files.write(Path.of(a[3]), bo.toByteArray());
    } else {
      byte[] data = Files.readAllBytes(Path.of(a[1]));
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      ZpaqReader r = new ZpaqReader() { int p = 0; public int get() { return p < data.length ? data[p++] & 255 : -1; }
        public int read(byte[] b, int off, int n) { n = Math.min(n, data.length - p); System.arraycopy(data, p, b, off, n); p += n; return n; } };
      ZpaqWriter w = new ZpaqWriter() { public void put(int c) { bo.write(c); } public void write(byte[] b, int off, int n) { bo.write(b, off, n); } };
      Libzpaq.decompress(r, w);
      Files.write(Path.of(a[2]), bo.toByteArray());
    }
  }
}
