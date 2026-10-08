// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

/**
 * US aircraft hex (ICAO 24-bit address) to N-number. The US block A00001..ADF7C7 is allocated to
 * N-numbers in a fixed order, so the registration follows from the hex by arithmetic: no lookup, no
 * network, no data file. Letters skip I and O. Pure; null outside the block.
 *
 * <p>Layout of the block: N1..N9 each own a bucket holding every N-number that starts with that
 * digit, in the order "N1", its letter suffixes (N1A, N1AA, N1AB..), then N10..N19 and so on; the
 * sizes below are those buckets' sizes at each depth.
 */
public final class NNumber {
  private NNumber() {}

  private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"; // 24: no I, no O
  private static final int FIRST = 0xA00001, LAST = 0xADF7C7;
  private static final int SUFFIX = 1 + 24 * (1 + 24); // "", then A, AA..AZ, B, BA.. = 601
  private static final int B4 = 1 + 24 + 10; // after four digits: none, a letter, or a digit
  private static final int B3 = 10 * B4 + SUFFIX;
  private static final int B2 = 10 * B3 + SUFFIX;
  private static final int B1 = 10 * B2 + SUFFIX;

  /** "N12345" style registration for a US hex, or null if the hex is not in the US block. */
  public static String fromHex(String hex) {
    if (hex == null || hex.length() != 6) return null;
    int i;
    try {
      i = Integer.parseInt(hex, 16);
    } catch (NumberFormatException e) {
      return null; // not a hex address (e.g. a non-ICAO "~" address): no registration to derive
    }
    if (i < FIRST || i > LAST) return null;
    i -= FIRST;
    StringBuilder out = new StringBuilder("N").append(i / B1 + 1);
    i %= B1;
    if (i < SUFFIX) return out.append(suffix(i)).toString();
    i -= SUFFIX;
    out.append(i / B2);
    i %= B2;
    if (i < SUFFIX) return out.append(suffix(i)).toString();
    i -= SUFFIX;
    out.append(i / B3);
    i %= B3;
    if (i < SUFFIX) return out.append(suffix(i)).toString();
    i -= SUFFIX;
    out.append(i / B4);
    i %= B4;
    if (i == 0) return out.toString();
    i -= 1;
    return out.append(i < 24 ? String.valueOf(LETTERS.charAt(i)) : String.valueOf(i - 24))
        .toString();
  }

  /** The letter suffix at an offset: 0 is none, then A, AA..AZ, B, BA..; at most two letters. */
  private static String suffix(int offset) {
    if (offset == 0) return "";
    char first = LETTERS.charAt((offset - 1) / 25);
    int rest = (offset - 1) % 25;
    return rest == 0 ? String.valueOf(first) : "" + first + LETTERS.charAt(rest - 1);
  }
}
