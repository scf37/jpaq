import io.github.scf37.jpaq.libzpaq.*;
import java.io.*;
import java.nio.file.*;
public class JavaZ {
  public static void main(String[] a) throws Exception {
    if (a[0].equals("c")) {
      String cfg = Files.readString(Path.of(a[1]), java.nio.charset.StandardCharsets.ISO_8859_1);
      byte[] data = Files.readAllBytes(Path.of(a[2]));
      int[] args = new int[9];
      for (int i = 4; i < a.length && i < 13; i++) args[i - 4] = Integer.parseInt(a[i]);
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      Compressor co = new Compressor();
      co.setOutput(ZpaqWriter.of(bo));
      co.writeTag();
      MemoryBuffer pc = new MemoryBuffer();
      co.startBlock(cfg, args, pc);
      co.startSegment("f", "c");
      co.setInput(ZpaqReader.of(data));
      co.compress();
      co.endSegment();
      co.endBlock();
      Files.write(Path.of(a[3]), bo.toByteArray());
    } else {
      Files.write(Path.of(a[2]), Libzpaq.decompress(Files.readAllBytes(Path.of(a[1]))));
    }
  }
}
