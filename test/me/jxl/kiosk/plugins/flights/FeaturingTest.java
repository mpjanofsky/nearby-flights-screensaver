// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.flights.source.Aircraft;
import org.junit.jupiter.api.Test;

class FeaturingTest {
  /** Aircraft `hex` at `distM` metres north of home, as the selector would hand it over. */
  static Tracked at(String hex, double distM) {
    Aircraft a = PipelineTest.ac(hex, hex, null, "A3", 0, 0, false);
    return new Tracked(a, 0, distM, new ArrayList<>());
  }

  static List<Tracked> byDistance(Tracked... t) {
    List<Tracked> l = new ArrayList<>(List.of(t));
    l.sort((x, y) -> Double.compare(x.distM, y.distM));
    return l;
  }

  static String hexes(Featuring.Result r) {
    StringBuilder s = new StringBuilder();
    for (Tracked t : r.rows) s.append(t.aircraft.hex);
    return s.toString();
  }

  @Test
  void startsWithTheClosest() {
    Featuring f = new Featuring();
    assertEquals("a", f.apply(byDistance(at("b", 9000), at("a", 4000)), 0).featuredId);
  }

  @Test
  void noAircraftMeansNoFeatured() {
    assertEquals(null, new Featuring().apply(new ArrayList<>(), 0).featuredId);
  }

  @Test
  void holdsAgainstACloserAircraftUntilMinHoldAndClearlyCloser() {
    Featuring f = new Featuring();
    f.apply(byDistance(at("a", 5000), at("b", 9000)), 0);
    // b is now much closer, but a has not been held long enough
    assertEquals(
        "a",
        f.apply(byDistance(at("a", 5000), at("b", 1000)), Featuring.MIN_HOLD_MS - 1).featuredId);
    // held long enough, but b is only slightly closer: not "clearly" closer
    assertEquals(
        "a", f.apply(byDistance(at("a", 5000), at("b", 4000)), Featuring.MIN_HOLD_MS).featuredId);
    // both conditions met
    assertEquals(
        "b", f.apply(byDistance(at("a", 5000), at("b", 3000)), Featuring.MIN_HOLD_MS).featuredId);
  }

  @Test
  void switchesImmediatelyWhenTheFeaturedLeaves() {
    Featuring f = new Featuring();
    f.apply(byDistance(at("a", 1000), at("b", 2000)), 0);
    assertEquals("b", f.apply(byDistance(at("b", 2000), at("c", 3000)), 1000).featuredId);
  }

  @Test
  void timeCapRotatesToAnotherAircraft() {
    Featuring f = new Featuring();
    f.apply(byDistance(at("a", 1000), at("b", 2000)), 0);
    assertEquals(
        "a",
        f.apply(byDistance(at("a", 1000), at("b", 2000)), Featuring.MAX_HOLD_MS - 1).featuredId);
    assertEquals(
        "b", f.apply(byDistance(at("a", 1000), at("b", 2000)), Featuring.MAX_HOLD_MS).featuredId);
  }

  @Test
  void aloneItStaysPastTheCap() {
    Featuring f = new Featuring();
    f.apply(byDistance(at("a", 1000)), 0);
    assertEquals("a", f.apply(byDistance(at("a", 1000)), Featuring.MAX_HOLD_MS * 2).featuredId);
  }

  @Test
  void listKeepsItsOrderBetweenResortsAndSlotsNewcomersByDistance() {
    Featuring f = new Featuring();
    assertEquals("ab", hexes(f.apply(byDistance(at("a", 1000), at("b", 2000)), 0)));
    // b passes a: the order holds until the re-sort interval
    assertEquals("ab", hexes(f.apply(byDistance(at("a", 3000), at("b", 2000)), 5000)));
    // a newcomer lands ahead of the first row that is farther
    assertEquals(
        "acb", hexes(f.apply(byDistance(at("a", 3000), at("b", 8000), at("c", 4000)), 6000)));
    assertEquals(
        "ba", hexes(f.apply(byDistance(at("a", 3000), at("b", 2000)), Featuring.RESORT_MS)));
  }

  @Test
  void prefersTheClosestCompleteAircraftForTheBand() {
    Featuring f = new Featuring();
    java.util.function.Predicate<Tracked> complete = t -> t.aircraft.hex.equals("far");
    List<Tracked> l = byDistance(at("near", 1000), at("far", 5000));
    assertEquals("far", f.apply(l, 0, complete).featuredId);
    // None complete: the closest is featured anyway, so the band is never empty.
    assertEquals("near", new Featuring().apply(l, 0, t -> false).featuredId);
  }

  @Test
  void anIncompleteFeaturedYieldsToACompleteOneAfterTheMinimumHold() {
    Featuring f = new Featuring();
    boolean[] done = {false};
    java.util.function.Predicate<Tracked> complete =
        t -> t.aircraft.hex.equals("b") || (done[0] && t.aircraft.hex.equals("a"));
    List<Tracked> l = byDistance(at("a", 4000), at("b", 5000));
    assertEquals("b", f.apply(l, 0, complete).featuredId); // b is complete, a is not
    done[0] = true; // a gets its data: it is closer and complete, but only clearly-closer switches
    assertEquals("b", f.apply(l, Featuring.MIN_HOLD_MS, complete).featuredId);
    Featuring g = new Featuring();
    done[0] = false;
    assertEquals("b", g.apply(l, 0, t -> t.aircraft.hex.equals("b")).featuredId);
    // The featured one is incomplete and a closer complete one exists: yields after the hold.
    Featuring h = new Featuring();
    assertEquals("a", h.apply(l, 0, t -> false).featuredId);
    assertEquals(
        "a", h.apply(l, Featuring.MIN_HOLD_MS - 1, t -> t.aircraft.hex.equals("b")).featuredId);
    assertEquals(
        "b", h.apply(l, Featuring.MIN_HOLD_MS, t -> t.aircraft.hex.equals("b")).featuredId);
  }
}
