package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.flights.source.Aircraft;
import org.junit.jupiter.api.Test;

class EnricherTest {
  static final class Fake implements Lookup {
    final List<String> calls = new ArrayList<>();
    boolean fail, unknown;

    public Info aircraft(String hex) throws IOException {
      calls.add("h:" + hex);
      if (fail) throw new IOException("down");
      return unknown ? null : new Info("N507DZ", "A21N", null, null, null, null);
    }

    public Info route(String cs) throws IOException {
      calls.add("c:" + cs);
      if (fail) throw new IOException("down");
      return unknown ? null : new Info(null, null, "DAL", "Delta Air Lines", "DEN", "ATL");
    }
  }

  static Aircraft ac(String hex, String cs, String reg, String type) {
    return new Aircraft(hex, cs, reg, type, "A3", 0, 0, 1000, 200, 90, 0, 0, false);
  }

  /** Runs the queue to idle, advancing the clock past each rate-limit gap. */
  static long drain(Enricher e, long now) {
    for (int i = 0; i < 50 && e.hasPending(); i++) {
      long w = e.step(now);
      if (w < 0) break;
      now += w;
    }
    return now;
  }

  @Test
  void registrationCallsignTakesTheAirlineFromTheRegisteredOwner() {
    Lookup owned =
        new Lookup() {
          public Info aircraft(String hex) {
            return new Info("N9006", "A319", "AAL", "American Airlines", null, null);
          }

          public Info route(String cs) {
            return null;
          }
        };
    Enricher e = new Enricher(owned);
    Aircraft a = ac("ac7256", "N9006", null, null);
    assertNull(e.infoFor(a, 0).airlineIcao); // nothing known before the lookup
    drain(e, 0);
    Info i = e.infoFor(a, 5_000);
    assertEquals("AAL", i.airlineIcao);
    assertEquals("American Airlines", i.airlineName);
  }

  @Test
  void aMilitaryAirframeWithNoCallsignShowsItsOwnerButNoAirlineCode() {
    Lookup mil =
        new Lookup() {
          public Info aircraft(String hex) {
            return new Info("58-0042", "K35T", null, "United States Air Force", null, null);
          }

          public Info route(String cs) {
            return null;
          }
        };
    Enricher e = new Enricher(mil);
    Aircraft a = ac("ae0375", null, null, null);
    e.infoFor(a, 0);
    drain(e, 0);
    Info i = e.infoFor(a, 5_000);
    assertNull(i.airlineIcao);
    assertEquals("United States Air Force", i.airlineName);
    assertEquals("K35T", i.type);
  }

  @Test
  void aCarrierNotInTheTableIsNamedByALookupThenCachedAndBeatsTheOwnerName() {
    List<String> asked = new ArrayList<>();
    Lookup l =
        new Lookup() {
          public Info aircraft(String hex) {
            return new Info("N427MJ", "F2TS", "ZZQ", "Some Owner Inc", null, null);
          }

          public Info route(String cs) {
            return null;
          }

          public Info airline(String icao) {
            asked.add(icao);
            return new Info(null, null, icao, "Zed Air", null, null);
          }
        };
    Enricher e = new Enricher(l);
    Aircraft a = ac("a516bc", "ZZQ27", null, null);
    assertNull(e.infoFor(a, 0).airlineName); // nothing known yet
    long now = drain(e, 0);
    assertEquals("Zed Air", e.infoFor(a, now + 1).airlineName);
    drain(e, now);
    e.infoFor(a, now + 2);
    assertEquals(List.of("ZZQ"), asked); // asked once, then served from the cache
  }

  @Test
  void copaIsNamedFromTheTableBeforeAnyLookup() {
    Info i = new Enricher(new Fake()).infoFor(ac("0c2f1e", "CMP393", null, null), 0);
    assertEquals("CMP", i.airlineIcao);
    assertEquals("Copa Airlines", i.airlineName);
  }

  @Test
  void lookupsRunFeaturedFirstThenListThenPrefetch() {
    Fake f = new Fake();
    Enricher e = new Enricher(f);
    e.infoFor(ac("late", null, "N1", "C172"), 0, 12); // prefetch candidate, queued first
    e.infoFor(ac("row", null, "N2", "C172"), 0, 2);
    e.infoFor(ac("feat", null, "N3", "C172"), 0, 0);
    e.infoFor(ac("late", null, "N1", "C172"), 0, 3); // asked again, better priority wins
    drain(e, 0);
    assertEquals(List.of("h:feat", "h:row", "h:late"), f.calls);
  }

  @Test
  void answersFromCacheAndNeverBlocks() {
    Fake f = new Fake();
    Enricher e = new Enricher(f);
    Info first = e.infoFor(ac("a65454", "DAL800", null, null), 0);
    assertNull(first.origin);
    assertEquals("DAL", first.airlineIcao); // from the bundled table before any lookup
    assertEquals("Delta Air Lines", first.airlineName);
    assertTrue(f.calls.isEmpty(), "infoFor must not touch the network");
    drain(e, 0);
    Info later = e.infoFor(ac("a65454", "DAL800", null, null), 5_000);
    assertEquals("DEN", later.origin);
    assertEquals("ATL", later.destination);
    assertEquals("N507DZ", later.registration);
    assertEquals("A21N", later.type);
  }

  @Test
  void neverLooksUpARouteForNonAirlineCallsigns() {
    Fake f = new Fake();
    Enricher e = new Enricher(f);
    e.infoFor(
        ac("a1", "N68GW", "N68GW", "C56X"), 0); // callsign is a tail number: owner lookup only
    e.infoFor(ac("a2", null, null, null), 0); // no callsign: only the hex lookup
    drain(e, 0);
    assertEquals(List.of("h:a1", "h:a2"), f.calls); // no "c:" request for either
  }

  @Test
  void oneRequestAtATimeWithAGap() {
    Fake f = new Fake();
    Enricher e = new Enricher(f);
    e.infoFor(ac("a1", "DAL1", null, null), 0);
    assertTrue(e.step(0) == 1_000 || e.step(0) >= 0);
    assertEquals(1, f.calls.size());
    assertEquals(1_000 - 500, e.step(500), "second request waits out the gap");
    assertEquals(1, f.calls.size());
  }

  @Test
  void missesAreCachedForAnHourThenRetried() {
    Fake f = new Fake();
    f.unknown = true;
    Enricher e = new Enricher(f);
    Aircraft a = ac("a1", "DAL1", "N1", "B738");
    e.infoFor(a, 0);
    long now = drain(e, 0);
    int n = f.calls.size();
    e.infoFor(a, now + 1_000);
    assertFalse(e.hasPending(), "a fresh miss is not looked up again");
    e.infoFor(a, now + Enricher.MISS_TTL_MS + 1);
    assertTrue(e.hasPending());
    assertEquals(1, n);
  }

  @Test
  void failuresBackOffAndKeepTheRequest() {
    Fake f = new Fake();
    f.fail = true;
    Enricher e = new Enricher(f);
    e.infoFor(ac("a1", "DAL1", "N1", "B738"), 0);
    long w1 = e.step(0);
    assertTrue(w1 >= 2_000, "backoff grows from the first failure");
    assertTrue(e.hasPending(), "the request is kept");
    assertEquals(w1 - 100, e.step(100));
    assertEquals(1, f.calls.size(), "no request during backoff");
    f.fail = false;
    drain(e, w1);
    assertFalse(e.hasPending());
  }

  @Test
  void aFailingServiceDoesNotHoldUpTheOthers() {
    List<String> calls = new ArrayList<>();
    Lookup hexDown =
        new Lookup() {
          public Info aircraft(String hex) throws IOException {
            calls.add("h:" + hex);
            throw new IOException("hex service slow");
          }

          public Info route(String cs) {
            calls.add("c:" + cs);
            return new Info(null, null, "DAL", "Delta Air Lines", "DEN", "ATL");
          }
        };
    Enricher e = new Enricher(hexDown);
    e.infoFor(ac("a1", null, null, null), 0); // no callsign: wants a hex lookup, which fails
    e.infoFor(ac("a2", "DAL2", "N2", "B738"), 0); // wants a route lookup, which works
    long now = 0;
    for (int i = 0; i < 4; i++) {
      long w = e.step(now);
      now += Math.max(w, 1_000);
    }
    assertTrue(calls.contains("c:DAL2"), "the route went out despite the hex failure: " + calls);
    for (int i = 0; i < 12; i++) {
      long w = e.step(now);
      assertTrue(w <= Enricher.MAX_BACKOFF_MS, "the wait never exceeds the cap: " + w);
      now += Math.max(w, 1);
    }
  }

  @Test
  void expiredEntriesAreServedWhileRefreshing() {
    Fake f = new Fake();
    Enricher e = new Enricher(f);
    Aircraft a = ac("a1", "DAL1", "N1", "B738");
    e.infoFor(a, 0);
    drain(e, 0);
    Info stale = e.infoFor(a, Enricher.TTL_MS + 1);
    assertEquals("DEN", stale.origin);
    assertTrue(e.hasPending());
  }

  @Test
  void cacheIsBounded() {
    Fake f = new Fake();
    Enricher e = new Enricher(f);
    long now = 0;
    for (int i = 0; i < Enricher.MAX_ENTRIES + 20; i++) {
      e.infoFor(ac("h" + i, null, null, null), now);
      now = drain(e, now);
    }
    e.infoFor(ac("h0", null, null, null), now);
    assertTrue(e.hasPending(), "the oldest entry was evicted");
  }

  /** Fake whose route has airport positions (FLL to LAX), like adsb.im. */
  static final class Positioned implements Lookup {
    public Info aircraft(String hex) {
      return null;
    }

    public Info route(String cs) {
      return new Info(null, null, "DAL", null, "FLL", "LAX", 26.0726, -80.1527, 33.9425, -118.408);
    }
  }

  static Aircraft at(double lat, double lon) {
    return new Aircraft("a1", "DAL800", null, null, "A3", lat, lon, 10000, 230, 270, 0, 0, false);
  }

  /** ATL-EYW-ATL, like a callsign flown out and back. */
  static final class Chain implements Lookup {
    public Info aircraft(String hex) {
      return null;
    }

    public Info route(String cs) {
      return new Info(
              null, null, "DAL", null, "ATL", "EYW \u2192 ATL", 33.64, -84.43, 33.64, -84.43)
          .withStops(
              new String[] {"ATL", "EYW", "ATL"},
              new double[] {33.64, -84.43, 24.56, -81.76, 33.64, -84.43});
    }
  }

  static Aircraft flying(double lat, double lon, double track) {
    return new Aircraft("a1", "DAL800", null, null, "A3", lat, lon, 10000, 230, track, 0, 0, false);
  }

  static Info chainFor(Lookup l, Aircraft a) {
    Enricher e = new Enricher(l);
    e.infoFor(a, 0);
    drain(e, 0);
    return e.infoFor(a, 5_000);
  }

  @Test
  void anOutAndBackIsNarrowedToTheLegTheAircraftIsFlying() {
    Info south = chainFor(new Chain(), flying(29.0, -83.0, 180)); // heading to Key West
    assertEquals("ATL", south.origin);
    assertEquals("EYW", south.destination);
    Info north = chainFor(new Chain(), flying(29.0, -83.0, 0)); // coming home
    assertEquals("EYW", north.origin);
    assertEquals("ATL", north.destination);
  }

  @Test
  void anAmbiguousChainWithNoTrackIsKeptWholeAndOffRouteIsDropped() {
    Info whole = chainFor(new Chain(), flying(29.0, -83.0, Double.NaN));
    assertEquals("ATL", whole.origin);
    assertEquals("EYW \u2192 ATL", whole.destination);
    assertNull(chainFor(new Chain(), flying(47.0, -122.0, 90)).origin); // Seattle: on no leg
  }

  @Test
  void aTwoAirportRouteFlownBackwardsIsDroppedButNotNearAnAirport() {
    // FLL to LAX: over Texas heading west is right, heading east is the reused return leg.
    assertEquals("FLL", chainFor(new Positioned(), flying(32.0, -97.0, 270)).origin);
    assertNull(chainFor(new Positioned(), flying(32.0, -97.0, 90)).origin);
    // Close to LAX the course says nothing (a go-around or pattern turn): the route stays.
    assertEquals("FLL", chainFor(new Positioned(), flying(34.0, -118.0, 90)).origin);
    // No track: nothing to judge by.
    assertEquals("FLL", chainFor(new Positioned(), flying(32.0, -97.0, Double.NaN)).origin);
  }

  /** PSP-DEN-TPA-ORD: four airports, three different legs. */
  static final class Four implements Lookup {
    public Info aircraft(String hex) {
      return null;
    }

    public Info route(String cs) {
      return new Info(
              null,
              null,
              "UAL",
              null,
              "PSP",
              "DEN \u2192 TPA \u2192 ORD",
              33.83,
              -116.51,
              41.98,
              -87.9)
          .withStops(
              new String[] {"PSP", "DEN", "TPA", "ORD"},
              new double[] {33.83, -116.51, 39.86, -104.67, 27.98, -82.53, 41.98, -87.9});
    }
  }

  @Test
  void aThroughFlightShowsOnlyTheLegItIsOn() {
    Info i = chainFor(new Four(), flying(34.0, -92.0, 100)); // between Denver and Tampa
    assertEquals("DEN", i.origin);
    assertEquals("TPA", i.destination);
    Info j = chainFor(new Four(), flying(37.1, -110.0, 60)); // on the Palm Springs to Denver line
    assertEquals("PSP", j.origin);
    assertEquals("DEN", j.destination);
  }

  @Test
  void routeIsKeptWhenTheAircraftIsOnIt() {
    Enricher e = new Enricher(new Positioned());
    e.infoFor(at(30.0, -95.0), 0);
    drain(e, 0);
    Info i = e.infoFor(at(30.0, -95.0), 5_000); // near Houston, close to the FLL-LAX arc
    assertEquals("FLL", i.origin);
    assertEquals("LAX", i.destination);
  }

  @Test
  void routeIsDroppedWhenTheAircraftIsFarOffIt() {
    Enricher e = new Enricher(new Positioned());
    e.infoFor(at(47.0, -122.0), 0);
    drain(e, 0);
    Info i = e.infoFor(at(47.0, -122.0), 5_000); // Seattle: callsign reused for another leg
    assertNull(i.origin);
    assertNull(i.destination);
    assertEquals("DAL", i.airlineIcao); // the airline survives from the bundled prefix table
  }

  @Test
  void nearAJunctionAirportArrivingAndDepartingPickDifferentLegs() {
    // Seen live 2026-10-07: UAL1010 (PSP-DEN-TPA-ORD) 7 km south of Tampa, flying north, descending
    // on final. FR24 had it on DEN-TPA; matching the leg bearings alone picked TPA-ORD.
    Info arriving = chainFor(new Four(), flying(27.912, -82.533, 1));
    assertEquals("DEN", arriving.origin);
    assertEquals("TPA", arriving.destination);
    Info departing =
        chainFor(new Four(), flying(28.05, -82.56, 340)); // just out of Tampa, to Chicago
    assertEquals("TPA", departing.origin);
    assertEquals("ORD", departing.destination);
  }

  @Test
  void aRouteThatDoesNotFitTheAircraftIsDroppedWithoutAskingAgain() {
    Seattle l = new Seattle();
    Enricher e = new Enricher(l);
    e.infoFor(at(30.0, -95.0), 0); // Texas: nowhere near Seattle-Portland
    drain(e, 0);
    Info i = e.infoFor(at(30.0, -95.0), 5_000);
    assertNull(i.origin);
    assertEquals(List.of("c:DAL800"), l.calls);
  }

  @Test
  void aRouteThatFitsIsShown() {
    Seattle l = new Seattle();
    Enricher e = new Enricher(l);
    e.infoFor(at(46.5, -122.4), 0); // between Seattle and Portland
    drain(e, 0);
    assertEquals("SEA", e.infoFor(at(46.5, -122.4), 5_000).origin);
  }

  /** A service that names a Seattle-Portland leg. */
  static final class Seattle implements Lookup {
    final List<String> calls = new ArrayList<>();

    public Info aircraft(String hex) {
      return null;
    }

    public Info route(String cs) {
      calls.add("c:" + cs);
      return new Info(null, null, "DAL", null, "SEA", "PDX", 47.45, -122.31, 45.59, -122.6);
    }
  }

  /** Allegiant-style answer: the origin is current, the destination is last week's rotation. */
  static final class Allegiant implements Lookup {
    public Info aircraft(String hex) {
      return null;
    }

    public Info route(String cs) {
      return new Info(null, null, "AAY", "Allegiant Air", "CVG", "PIE");
    }
  }

  @Test
  void anAirlineWithStaleDestinationsShowsItsOriginOnly() {
    Aircraft a = new Aircraft("a1", "AAY928", null, null, "A3", 0, 0, 10000, 230, 90, 0, 0, false);
    Info i = chainFor(new Allegiant(), a);
    assertEquals("CVG", i.origin);
    assertNull(i.destination);
    assertEquals("ATL", chainFor(new Fake(), ac("a2", "DAL1", null, null)).destination);
  }
}
