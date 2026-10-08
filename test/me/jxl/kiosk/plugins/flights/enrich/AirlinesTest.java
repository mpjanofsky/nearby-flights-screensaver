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
  }
}
