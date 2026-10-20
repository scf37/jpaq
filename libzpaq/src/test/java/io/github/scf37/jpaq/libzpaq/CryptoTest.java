package io.github.scf37.jpaq.libzpaq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CryptoTest {
  private static byte[] hex(String s) {
    return HexFormat.of().parseHex(s);
  }

  private static byte[] scrypt(String pw, String salt, int n, int r, int p, int len) {
    byte[] pwb = pw.getBytes(StandardCharsets.US_ASCII);
    byte[] sb = salt.getBytes(StandardCharsets.US_ASCII);
    byte[] out = new byte[len];
    Crypto.scrypt(pwb, pwb.length, sb, sb.length, n, r, p, out, len);
    return out;
  }

  @Test
  void scryptRfc7914Vectors() {
    assertArrayEquals(hex("77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442"
        + "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906"),
        scrypt("", "", 16, 1, 1, 64));
    assertArrayEquals(hex("fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162"
        + "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640"),
        scrypt("password", "NaCl", 1024, 8, 16, 64));
    assertArrayEquals(hex("7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2"
        + "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887"),
        scrypt("pleaseletmein", "SodiumChloride", 16384, 8, 1, 64));
  }

  @Test
  void stretchKey() {
    byte[] key = new byte[32];
    byte[] salt = new byte[32];
    for (int i = 0; i < 32; i++) {
      key[i] = (byte) i;
      salt[i] = (byte) (100 + i);
    }
    assertArrayEquals(hex("a06e74eab2af1c72233693164949c8d9774e7ae21468441c418bc11bf29248dc"),
        Crypto.stretchKey(key, salt));
  }

  @Test
  void stretchKeySequential() {
    byte[] key = new byte[32];
    byte[] salt = new byte[32];
    for (int i = 0; i < 32; i++) {
      key[i] = (byte) i;
      salt[i] = (byte) (100 + i);
    }
    // N=131072, r=8, p=1; expected value computed with OpenSSL's scrypt
    assertArrayEquals(hex("9d7538579bc8262c0517a672d176543b838ffcd24d52d186b6c247c3b34f8bd4"),
        Crypto.stretchKeySequential(key, salt));
  }

  @Test
  void aesCtrMatchesJce() throws Exception {
    Random rnd = new Random(1);
    for (int keylen : new int[] {16, 24, 32}) {
      byte[] key = new byte[keylen];
      byte[] iv = new byte[8];
      rnd.nextBytes(key);
      rnd.nextBytes(iv);
      byte[] data = new byte[5000];
      rnd.nextBytes(data);
      byte[] iv16 = new byte[16];
      System.arraycopy(iv, 0, iv16, 0, 8);
      Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv16));
      byte[] expected = c.doFinal(data);

      AesCtr aes = new AesCtr(key, keylen, iv);
      byte[] all = data.clone();
      aes.encrypt(all, 0, all.length, 0);
      assertArrayEquals(expected, all);

      // random access slices
      for (int t = 0; t < 50; t++) {
        int off = rnd.nextInt(data.length);
        int len = rnd.nextInt(data.length - off + 1);
        byte[] slice = java.util.Arrays.copyOfRange(data, off, off + len);
        aes.encrypt(slice, 0, len, off);
        assertArrayEquals(java.util.Arrays.copyOfRange(expected, off, off + len), slice);
      }
    }
  }

  @Test
  void aesCtrDefaultIv() throws Exception {
    byte[] key = new byte[16];
    AesCtr aes = new AesCtr(key, 16, null);
    byte[] z = new byte[16];
    aes.encrypt(z, 0, 16, 0);
    // AES-128 of the zero block with the zero key
    assertArrayEquals(hex("66e94bd4ef8a2c3b884cfa59ca342b2e"), z);
  }

  @Test
  void randomAvoidsArchiveStartBytes() {
    for (int i = 0; i < 2000; i++) {
      byte[] b = new byte[32];
      Crypto.random(b, 32);
      assertNotEquals('z', b[0]);
      assertNotEquals('7', b[0]);
    }
    Crypto.setRandomSource(buf -> java.util.Arrays.fill(buf, (byte) 'z'));
    try {
      byte[] b = new byte[4];
      Crypto.random(b, 4);
      assertEquals((byte) ('z' ^ 0x80), b[0]);
      assertEquals((byte) 'z', b[1]);
    } finally {
      Crypto.setRandomSource(null);
    }
  }

  @Test
  void sha1AndSha256() throws Exception {
    Sha1 s1 = new Sha1();
    for (byte b : "abc".getBytes(StandardCharsets.US_ASCII)) s1.put(b);
    assertEquals(3, s1.usize());
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", HexFormat.of().formatHex(s1.result()));
    assertEquals(0, s1.usize());
    byte[] big = new byte[100000];
    new Random(2).nextBytes(big);
    s1.write(big, 0, 70000);
    s1.write(big, 70000, 30000);
    assertArrayEquals(java.security.MessageDigest.getInstance("SHA-1").digest(big), s1.result());

    Sha256 s2 = new Sha256();
    s2.write("abc".getBytes(StandardCharsets.US_ASCII), 0, 3);
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        HexFormat.of().formatHex(s2.result()));
  }
}
