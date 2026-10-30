// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AirlinesTest {
  @Test
  void namesCarriersSeenOverTheHomeArea() {
    assertEquals("Breeze Airways", Airlines.name(Airlines.prefix("MXY2424")));
    assertEquals("Discover Airlines", Airlines.name(Airlines.prefix("OCN642")));
    assertEquals("Viva Aerobus", Airlines.name(Airlines.prefix("VIV701")));
    assertEquals("flyExclusive", Airlines.name(Airlines.prefix("JRE774")));
    assertEquals(
        "Air Canada Rouge",
        Airlines.name(Airlines.prefix("ROU1633"))); // adsbdb says a Chilean carrier
  }

  @Test
  void tidyCutsLegalTailsAndNothingElse() {
    assertEquals(
        "Superior Transportation Associates",
        Airlines.tidy("Superior Transportation Associates, Inc d/b/a Superior Aviation"));
    assertEquals("Delta Private Jets", Airlines.tidy("Delta Private Jets LLC"));
    assertEquals("Air Wisconsin Airlines", Airlines.tidy("Air Wisconsin Airlines Corp"));
    assertEquals(
        "Corporate Air", Airlines.tidy("Corporate Air")); // a word that only starts with one
    assertEquals("Delta Air Lines", Airlines.tidy("Delta Air Lines"));
    assertEquals("Inc", Airlines.tidy("Inc")); // never empty
    assertEquals(null, Airlines.tidy(null));
  }
}
