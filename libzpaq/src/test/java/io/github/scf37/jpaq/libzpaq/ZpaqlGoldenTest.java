package io.github.scf37.jpaq.libzpaq;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * User supplied ZPAQL (COMP, HCOMP and PCOMP programs including loops and long jumps, plus
 * randomly generated programs covering all instructions): compressed output and the output of the
 * post-processor must equal that of the reference C++ library's interpreter.
 */
class ZpaqlGoldenTest {
  record Case(String file, int csize, String chash, int dsize, String dhash) { }

  static Stream<Case> cases() throws IOException {
    List<Case> r = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(
        ZpaqlGoldenTest.class.getResourceAsStream("/zpaql/golden.txt"), StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        String[] f = line.split("\\|");
        r.add(new Case(f[0], Integer.parseInt(f[1]), f[2], Integer.parseInt(f[3]), f[4]));
      }
    }
    return r.stream();
  }

  private static String sha256(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void matchesReference(Case c) throws Exception {
    String cfg = new String(
        ZpaqlGoldenTest.class.getResourceAsStream("/zpaql/" + c.file()).readAllBytes(),
        StandardCharsets.ISO_8859_1);
    byte[] in = TestData.text(5000, 21);
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    Compressor co = new Compressor();
    co.setOutput(ZpaqWriter.of(bo));
    co.writeTag();
    co.startBlock(cfg, new int[9], new MemoryBuffer());
    co.startSegment("f", "c");
    co.setInput(ZpaqReader.of(in));
    co.compress();
    co.endSegment();
    co.endBlock();
    byte[] packed = bo.toByteArray();
    assertEquals(c.csize(), packed.length);
    assertEquals(c.chash(), sha256(packed));
    byte[] out = Libzpaq.decompress(packed);
    assertEquals(c.dsize(), out.length);
    assertEquals(c.dhash(), sha256(out));
  }
}
