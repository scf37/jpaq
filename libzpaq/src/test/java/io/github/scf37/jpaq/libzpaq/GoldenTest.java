package io.github.scf37.jpaq.libzpaq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compares the compressed output with that of the reference C++ libzpaq 7.15: the golden file holds
 * the size and SHA-256 of the output of {@code libzpaq::compress()} for each input and method.
 */
class GoldenTest {
  record Case(String input, String method, String filename, String comment, int size,
      String sha256) {
    @Override
    public String toString() {
      return input + " -m" + method + (filename.isEmpty() ? "" : " " + filename);
    }
  }

  static Stream<Case> cases() throws IOException {
    List<Case> r = new ArrayList<>();
    try (InputStream in = GoldenTest.class.getResourceAsStream("/golden/libzpaq-golden.txt");
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        String[] f = line.split("\\|", -1);
        r.add(new Case(f[0], f[1], f[2], f[3], Integer.parseInt(f[4]), f[5]));
      }
    }
    return r.stream();
  }

  private static byte[] input(String name) {
    if (name.equals("big")) return TestData.big();
    Map<String, byte[]> m = TestData.inputs();
    return m.get(name);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void outputMatchesReference(Case c) throws Exception {
    byte[] in = input(c.input());
    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
    Libzpaq.compress(ZpaqReader.of(in), ZpaqWriter.of(bo), c.method(),
        c.filename().isEmpty() ? null : c.filename(),
        c.comment().isEmpty() ? null : c.comment(), true);
    byte[] out = bo.toByteArray();
    assertEquals(c.size(), out.length, "compressed size");
    assertEquals(c.sha256(),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out)));
    // and it must decompress to the input
    assertArrayEquals(in, Libzpaq.decompress(out));
  }
}
