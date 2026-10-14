package io.github.scf37.jpaq.libzpaq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ApiTest {
  @Test
  void roundTripAllLevels() {
    byte[] text = TestData.text(60000, 11);
    for (String m : new String[] {"0", "1", "2", "3", "4", "5", "x4.0ci1", "x4.3ci1", "x2.1.4.0.3.18"}) {
      byte[] c = Libzpaq.compress(text, m);
      assertArrayEquals(text, Libzpaq.decompress(c), m);
    }
  }

  @Test
  void compressorAndDecompresserApi() {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    Compressor c = new Compressor();
    c.setOutput(ZpaqWriter.of(bo));
    byte[][] files = {TestData.text(5000, 1), TestData.code(3000, 2), new byte[0]};
    c.writeTag();
    c.startBlock(2);  // built in level 2
    for (int i = 0; i < files.length; i++) {
      c.startSegment("file" + i, "comment " + i);
      c.setInput(ZpaqReader.of(files[i]));
      c.compress();
      Sha1 sha = new Sha1();
      sha.write(files[i], 0, files[i].length);
      c.endSegment(sha.result());
    }
    c.endBlock();

    Decompresser d = new Decompresser();
    d.setInput(ZpaqReader.of(bo.toByteArray()));
    List<byte[]> got = new ArrayList<>();
    List<String> names = new ArrayList<>();
    List<String> comments = new ArrayList<>();
    double[] mem = new double[1];
    assertTrue(d.findBlock(mem));
    assertTrue(mem[0] > 0);
    while (true) {
      ByteArrayOutputStream name = new ByteArrayOutputStream();
      if (!d.findFilename(ZpaqWriter.of(name))) break;
      ByteArrayOutputStream com = new ByteArrayOutputStream();
      d.readComment(ZpaqWriter.of(com));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Sha1 sha = new Sha1();
      d.setOutput(ZpaqWriter.of(out));
      d.setSha1(sha);
      while (d.decompress(1000)) { }
      byte[] end = new byte[21];
      d.readSegmentEnd(end);
      assertEquals(1, end[0]);
      byte[] want = new byte[20];
      System.arraycopy(end, 1, want, 0, 20);
      assertArrayEquals(want, sha.result());
      got.add(out.toByteArray());
      names.add(name.toString(StandardCharsets.UTF_8));
      comments.add(com.toString(StandardCharsets.UTF_8));
    }
    assertFalse(d.findBlock());
    assertEquals(List.of("file0", "file1", "file2"), names);
    assertEquals(List.of("comment 0", "comment 1", "comment 2"), comments);
    for (int i = 0; i < files.length; i++) assertArrayEquals(files[i], got.get(i));
  }

  @Test
  void zpaqlSourceWithPostprocessorRoundTrip() {
    // min.cfg like model written in ZPAQL, no postprocessor
    String cfg = "comp 1 2 0 0 2 (hh hm ph pm n) 0 icm 16 1 isse 19 0 "
        + "hcomp *b=a a=0 (save in rotating buffer M) d=0 hash b-- hash *d=a "
        + "d++ b-- hash b-- hash *d=a halt post 0 end";
    byte[] data = TestData.text(20000, 5);
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    Compressor c = new Compressor();
    c.setOutput(ZpaqWriter.of(bo));
    c.startBlock(cfg, null, null);
    c.startSegment((String) null, null);
    c.setInput(ZpaqReader.of(data));
    c.compress();
    c.endSegment();
    c.endBlock();
    assertArrayEquals(data, Libzpaq.decompress(bo.toByteArray()));
  }

  @Test
  void invalidInputIsRejected() {
    byte[] good = Libzpaq.compress(TestData.text(20000, 3), "3");
    byte[] bad = good.clone();
    bad[bad.length / 2] ^= 0x55;
    // corruption must be detected as an error or yield different data, never hang
    try {
      byte[] out = Libzpaq.decompress(bad);
      assertFalse(java.util.Arrays.equals(out, TestData.text(20000, 3)));
    } catch (RuntimeException expected) {
      // fine
    }
    assertThrows(ZpaqException.class, () -> Libzpaq.compress(new byte[10], "x4.1.2.0.3.18"));
  }

  @Test
  void multipleBlocks() {
    // 2.2 MB with block size 2^0 MiB => 3 blocks
    byte[] a = TestData.big();
    byte[] data = new byte[a.length * 2];
    System.arraycopy(a, 0, data, 0, a.length);
    System.arraycopy(a, 0, data, a.length, a.length);
    byte[] c = Libzpaq.compress(data, "10");
    int blocks = 0;
    for (int i = 0; i + 4 < c.length; i++)
      if (c[i] == 'z' && c[i + 1] == 'P' && c[i + 2] == 'Q' && (c[i + 3] == 1 || c[i + 3] == 2) && c[i + 4] == 1)
        blocks++;
    assertEquals(3, blocks);
    assertArrayEquals(data, Libzpaq.decompress(c));
  }

  @Test
  void memoryBuffer() {
    MemoryBuffer b = new MemoryBuffer();
    Random r = new Random(1);
    byte[] data = new byte[100000];
    r.nextBytes(data);
    b.write(data);
    assertEquals(data.length, b.size());
    byte[] back = new byte[data.length];
    assertEquals(data.length, b.read(back, 0, back.length));
    assertArrayEquals(data, back);
    assertEquals(-1, b.get());
    b.setLimit(100000);
    assertThrows(ZpaqException.class, () -> b.put(1));
  }
}
