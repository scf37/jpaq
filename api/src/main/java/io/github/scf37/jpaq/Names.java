package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Strs;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Names in archives are bytes (UTF-8 by convention). The API gives them as strings: valid UTF-8
 * is decoded, other bytes become the chars U+DC80..U+DCFF, so that every name converts back to
 * the bytes it came from.
 */
public final class Names {
  private Names() {}

  /** The string for a name given as bytes. */
  public static String decode(byte[] name) {
    return Strs.fromBytes(new String(name, java.nio.charset.StandardCharsets.ISO_8859_1));
  }

  /** The bytes of a name. */
  public static byte[] encode(String name) {
    return Strs.toBytes(name).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  static String bytes(String name) {
    return Strs.toBytes(name);
  }

  static Predicate<String> wrap(Predicate<String> p) {
    return n -> p.test(Strs.fromBytes(n));
  }

  static UnaryOperator<String> wrap(UnaryOperator<String> f) {
    return n -> {
      String r = f.apply(Strs.fromBytes(n));
      return r == null ? null : Strs.toBytes(r);
    };
  }
}
