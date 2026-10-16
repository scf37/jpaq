package io.github.scf37.jpaq;

import java.time.Instant;

/** Decimal dates (YYYYMMDDHHMMSS, UT) as zpaq stores them. */
final class Dates {
  private Dates() {}

  static Instant instant(long date) {
    return Instant.ofEpochSecond(io.github.scf37.jpaq.engine.Dates.unixTime(date));
  }
}
