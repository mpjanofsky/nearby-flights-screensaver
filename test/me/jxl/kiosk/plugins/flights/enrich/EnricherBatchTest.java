// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.flights.source.Aircraft;
import org.junit.jupiter.api.Test;

/** Route lookups go out in batches, at most one request per window, and back off when refused. */
class EnricherBatchTest {
  static final class Batching implements Lookup {
    final List<List<String>> requests = new ArrayList<>();
    final List<Long> times = new ArrayList<>();
    long clock;
    IOException failure;

    public Info aircraft(String hex) {
      return null;
    }

    public Info route(String cs) {
      throw new AssertionError("a per-callsign request must not be made: " + cs);
    }

    @Override
    public Map<String, Info> routes(List<String> callsigns) throws IOException {
      requests.add(new ArrayList<>(callsigns));
      times.add(clock);
      if (failure != null) throw failure;
      Map<String, Info> m = new HashMap<>();
      for (String cs : callsigns) {
        if (!cs.equals("DAL999")) m.put(cs, new Info(null, null, "DAL", null, "DEN", "ATL"));
      }
      return m;
    }
  }

  static Aircraft ac(String hex, String cs) {
    return EnricherTest.ac(hex, cs, "N1", "B738");
  }

  @Test
  void callsignsWaitForTheWindowThenGoOutTogether() {
    Batching b = new Batching();
    Enricher e = new Enricher(b); // the shipped 30 s window
    e.infoFor(ac("a1", "DAL1"), 0);
    e.infoFor(ac("a2", "DAL2"), 10_000);
    e.infoFor(ac("a3", "DAL999"), 20_000);
    assertTrue(e.step(29_999) > 0, "nothing goes out before the window ends");
    assertTrue(b.requests.isEmpty());
    b.clock = 30_000;
    e.step(30_000);
    assertEquals(1, b.requests.size(), "one request for all three");
    assertEquals(3, b.requests.get(0).size());
    assertEquals("DEN", e.infoFor(ac("a1", "DAL1"), 30_001).origin);
    assertNull(e.infoFor(ac("a3", "DAL999"), 30_001).origin); // a miss is cached as a miss
    assertTrue(e.notReady(ac("a9", "DAL9")), "an unseen callsign is still not answered");
  }

  @Test
  void aSecondRequestIsAFullWindowAfterTheFirst() {
    Batching b = new Batching();
    Enricher e = new Enricher(b);
    e.infoFor(ac("a1", "DAL1"), 0);
    e.step(30_000);
    e.infoFor(ac("a2", "DAL2"), 31_000);
    e.step(40_000);
    e.step(59_999);
    assertEquals(1, b.requests.size(), "less than 30 s after the first request");
    e.step(61_000);
    assertEquals(2, b.requests.size());
  }

  @Test
  void aBatchIsCappedAndTheRestFollowsInTheNextWindow() {
    Batching b = new Batching();
    Enricher e = new Enricher(b);
    for (int i = 0; i < Enricher.MAX_BATCH + 5; i++) e.infoFor(ac("h" + i, "DAL" + i), 0);
    e.step(30_000);
    assertEquals(Enricher.MAX_BATCH, b.requests.get(0).size());
    e.step(31_000);
    assertEquals(1, b.requests.size());
    e.step(60_000);
    assertEquals(5, b.requests.get(1).size());
  }

  @Test
  void aKnownRouteIsNotAskedAgainWithin12Hours() {
    Batching b = new Batching();
    Enricher e = new Enricher(b);
    e.infoFor(ac("a1", "DAL1"), 0);
    e.step(30_000);
    for (long t = 60_000; t < Enricher.TTL_MS; t += 600_000) {
      e.infoFor(ac("a1", "DAL1"), t);
      e.step(t);
    }
    assertEquals(1, b.requests.size());
  }

  @Test
  void aFailureBacksOffAndKeepsTheCallsignsForLater() {
    Batching b = new Batching();
    b.failure = new IOException("down");
    Enricher e = new Enricher(b);
    e.infoFor(ac("a1", "DAL1"), 0);
    e.step(30_000);
    assertEquals(1, b.requests.size());
    assertNull(e.infoFor(ac("a1", "DAL1"), 30_500).origin, "no route is shown");
    assertTrue(e.hasPending(), "still wanted");
    long now = 30_000;
    for (int i = 0; i < 40; i++) { // however often the worker wakes, requests stay spaced out
      b.clock = now;
      long w = e.step(now);
      now += Math.max(w, 1_000);
    }
    for (int i = 1; i < b.times.size(); i++) {
      long gap = b.times.get(i) - b.times.get(i - 1);
      assertTrue(gap >= 60_000, "gap " + gap + " at request " + i);
    }
    b.failure = null; // the service recovers
    now += 2 * Enricher.ROUTE_MAX_BACKOFF_MS;
    e.step(now);
    assertNotNull(e.infoFor(ac("a1", "DAL1"), now + 1).origin);
  }

  @Test
  void aRefusalLeavesTheServiceAloneForAnHour() {
    Batching b = new Batching();
    b.failure = new Http.StatusException("adsb.im", 403, 0);
    Enricher e = new Enricher(b);
    e.infoFor(ac("a1", "DAL1"), 0);
    e.step(30_000);
    assertEquals(1, b.requests.size());
    for (long t = 31_000; t < 30_000 + Enricher.BLOCKED_BACKOFF_MS; t += 30_000) {
      e.infoFor(ac("a1", "DAL1"), t);
      e.step(t);
    }
    assertEquals(1, b.requests.size(), "one request, then nothing for an hour");
    assertNull(e.infoFor(ac("a1", "DAL1"), 40_000).origin);
    e.step(30_000 + Enricher.BLOCKED_BACKOFF_MS);
    assertEquals(2, b.requests.size(), "then one more try");
  }

  @Test
  void hexLookupsAreNotDelayedByTheRouteWindow() {
    Batching b = new Batching();
    Enricher e = new Enricher(b);
    e.infoFor(ac("a1", null), 0); // no callsign: needs a hex lookup
    long w = e.step(0);
    assertTrue(w <= Enricher.MIN_GAP_MS || w == -1, "the hex lookup ran at once: " + w);
  }
}
