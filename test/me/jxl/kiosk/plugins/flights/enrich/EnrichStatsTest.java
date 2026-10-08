// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EnrichStatsTest {
  @Test
  void countsLookupsAndRowCompleteness() {
    EnrichStats s = new EnrichStats();
    s.lookup(true, 800, EnrichStats.Outcome.HIT, true);
    s.lookup(false, 1200, EnrichStats.Outcome.MISS, false);
    s.lookup(false, 5000, EnrichStats.Outcome.ERROR, false);
    s.rows(4, 4, 2, 3, 4, 3);
    s.queue(5);
    String b = s.brief();
    assertTrue(
        b.contains("route 50%") && b.contains("airline 75%") && b.contains("callsign 75%"), b);
    assertTrue(b.contains("lookups 3 (err: hex") && b.contains("queue 5"), b);
    String full = s.summary();
    assertTrue(
        full.contains("hex lookups=1 (hit 1, miss 0, err 0") && full.contains("callsign filled 1"),
        full);
    assertTrue(full.contains("route lookups=2 (hit 0, miss 1, err 1"), full);
  }

  @Test
  void recordsWhyAnAircraftLeftWithoutARoute() {
    EnrichStats s = new EnrichStats();
    s.routeResolved(14_000);
    s.left("abc123", 60_000, 14_000, true);
    s.left("def456", 40_000, -1, false);
    List<String> ev = s.drainEvents();
    assertEquals(2, ev.size());
    assertEquals("enrich left hex=abc123 shown=60s route=14s callsign=yes", ev.get(0));
    assertEquals("enrich left hex=def456 shown=40s route=never callsign=no", ev.get(1));
    assertTrue(s.summary().contains("left without route: callsign known 0, no callsign 1"));
    assertTrue(s.drainEvents().isEmpty());
    assertFalse(s.summary().contains("http"));
  }

  @Test
  void aRouteIsOnlyExpectedOfKnownAirlineCallsigns() {
    assertTrue(Enricher.routeExpected("DAL123"));
    assertTrue(!Enricher.routeExpected("N512QX")); // a tail number
    assertTrue(!Enricher.routeExpected("RCH345")); // military: a pattern match, not a known airline
    assertTrue(!Enricher.routeExpected(null));
  }
}
