// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NNumberTest {
  @Test
  void matchesRegistrationsSeenInRealFeeds() {
    // hex/registration pairs from fixtures/feed/*.json (the feed's own `r` field)
    String[][] seen = {
      {"AA92FB", "N78004"}, {"ACC3B1", "N921UP"}, {"A506D1", "N423AN"}, {"A44477", "N374UP"},
      {"A83876", "N629JB"}, {"A44963", "N37548"}, {"A062EC", "N124DU"}, {"AB4F5D", "N828JB"},
    };
    for (String[] p : seen) assertEquals(p[1], NNumber.fromHex(p[0]), p[0]);
  }

  @Test
  void blockEndsAreN1AndN99999() {
    assertEquals("N1", NNumber.fromHex("A00001"));
    assertEquals("N99999", NNumber.fromHex("ADF7C7"));
  }

  @Test
  void outsideTheUsBlockIsNull() {
    assertNull(NNumber.fromHex("A00000"));
    assertNull(NNumber.fromHex("ADF7C8"));
    assertNull(NNumber.fromHex("4CA7B5")); // an Irish address
    assertNull(NNumber.fromHex("~123456"));
    assertNull(NNumber.fromHex(null));
  }

  @Test
  void everyAddressGetsADistinctWellFormedNumber() {
    Set<String> all = new HashSet<>();
    for (int h = 0xA00001; h <= 0xADF7C7; h++) {
      String n = NNumber.fromHex(Integer.toHexString(h).toUpperCase());
      assertTrue(
          n.matches(
              "N[1-9][0-9]{0,4}|N[1-9][0-9]{0,3}[A-HJ-NP-Z]|N[1-9][0-9]{0,2}[A-HJ-NP-Z]{1,2}"),
          n);
      assertTrue(all.add(n), "duplicate " + n);
    }
    assertEquals(915399, all.size());
  }
}
