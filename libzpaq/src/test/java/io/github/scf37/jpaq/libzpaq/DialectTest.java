package io.github.scf37.jpaq.libzpaq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class DialectTest {
  private static byte[] block(Dialect d, byte[] data, String method) {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    MemoryBuffer in = new MemoryBuffer();
    in.write(data, 0, data.length);
    Libzpaq.compressBlock(in, ZpaqWriter.of(bo), method, "name".getBytes(), "c".getBytes(), true, d);
    return bo.toByteArray();
  }

  /** The block finder of every other ZPAQ program: only finds tag + "zPQ". */
  private static boolean stockFindsBlock(byte[] s) {
    int h1 = 0x3D49B113;
    int h2 = 0x29EB7F93;
    int h3 = 0x2614BE13;
    int h4 = 0x3828EB13;
    for (byte b : s) {
      int c = b & 255;
      h1 = h1 * 12 + c;
      h2 = h2 * 20 + c;
      h3 = h3 * 28 + c;
      h4 = h4 * 44 + c;
      if (h1 == 0xB16B88F1 && h2 == 0xFF5376F1 && h3 == 0x72AC5BF1 && h4 == 0x2F909AF1) return true;
    }
    return false;
  }

  @Test
  void standardIsUnchangedAndJpaqDiffersInOneByte() {
    byte[] data = TestData.text(30000, 5);
    for (String m : new String[] {"0", "1", "2", "3", "4", "5"}) {
      byte[] std = block(Dialect.STANDARD, data, m);
      byte[] jp = block(Dialect.JPAQ, data, m);
      assertEquals(std.length, jp.length, m);
      int diff = 0;
      for (int i = 0; i < std.length; i++) {
        if (std[i] != jp[i]) {
          diff++;
          assertEquals(13, i);  // right after the locator tag
          assertEquals('z', std[i]);
          assertEquals('j', jp[i]);
        }
      }
      assertEquals(1, diff, m);
      assertTrue(stockFindsBlock(std));
      assertFalse(stockFindsBlock(jp), m);
    }
  }

  @Test
  void readerFindsBothAndReportsDialect() {
    byte[] data = TestData.text(20000, 9);
    for (Dialect d : Dialect.values()) {
      byte[] b = block(d, data, "2");
      // Surround with junk and concatenate a block of the other dialect
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      bo.writeBytes(new byte[] {1, 2, 3, 4, 5});
      bo.writeBytes(b);
      Dialect other = d == Dialect.STANDARD ? Dialect.JPAQ : Dialect.STANDARD;
      bo.writeBytes(block(other, data, "1"));
      Decompresser dec = new Decompresser();
      dec.setInput(ZpaqReader.of(bo.toByteArray()));
      Dialect[] want = {d, other};
      for (Dialect w : want) {
        assertTrue(dec.findBlock());
        assertEquals(w, dec.dialect());
        assertTrue(dec.findFilename(null));
        dec.readComment(null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dec.setOutput(ZpaqWriter.of(out));
        while (dec.decompress(1 << 14)) { }
        dec.readSegmentEnd();
        assertArrayEquals(data, out.toByteArray());
        assertFalse(dec.findFilename(null));
      }
      assertFalse(dec.findBlock());
    }
  }
}
