package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.flights.source.Aircraft;
import me.jxl.kiosk.plugins.flights.source.FetchResult;
import me.jxl.kiosk.plugins.flights.source.SourceAdapter;
import me.jxl.kiosk.plugins.flights.source.SourceException;
import org.junit.jupiter.api.Test;

class EngineTest {
  static final Config CFG = PipelineTest.CFG;
  static final long REFRESH = 10_000;

  static final class Fake implements SourceAdapter {
    final String id;
    boolean fail;
    int retryAfterS;
    int calls;
    double dNorth = 0.02;

    Fake(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }

    public FetchResult fetch(double lat, double lon, double r) throws SourceException {
      calls++;
      if (fail) throw new SourceException(id + " source unreachable", null, retryAfterS);
      List<Aircraft> l = new ArrayList<>();
      l.add(PipelineTest.ac("a", "DAL1", null, "A3", dNorth, 0, false));
      return new FetchResult(l, 0);
    }
  }

  static final class Sink implements Engine.Publisher {
    final List<String> out = new ArrayList<>();

    public void publish(String json, long gen) {
      out.add(json);
    }

    String last() {
      return out.get(out.size() - 1);
    }
  }

  @Test
  void publishesFirstThenOnlyOnChange() {
    Fake p = new Fake("local");
    Sink s = new Sink();
    Engine e = new Engine(CFG, p, null, s, REFRESH);
    assertEquals(REFRESH, e.tick(0));
    e.tick(10_000);
    e.tick(20_000); // unchanged
    assertEquals(1, s.out.size());
    p.dNorth = 0.05;
    e.tick(30_000);
    assertEquals(2, s.out.size());
    assertTrue(!s.out.get(0).contains("\"pn\""), "a first publish has nothing to animate from");
    assertTrue(s.last().contains("\"pn\":0") && s.last().contains("\"pv\":0"), s.last());
  }

  /** Reports one aircraft with no callsign, observed at the time it is asked (a real clock). */
  static final class Mute implements SourceAdapter {
    long clock;

    public String id() {
      return "local";
    }

    public FetchResult fetch(double lat, double lon, double r) {
      List<Aircraft> l = new ArrayList<>();
      l.add(PipelineTest.ac("m", null, null, "A3", 0.02, 0, false));
      return new FetchResult(l, clock);
    }
  }

  @Test
  void doesNotPublishAnEmptyListWhileEveryAircraftIsStillWaiting() {
    Mute p = new Mute();
    Sink s = new Sink();
    Engine e = new Engine(CFG, p, null, s, REFRESH);
    p.clock = 1000;
    e.tick(1000); // first sight: held back, so there is nothing worth showing yet
    assertEquals(0, s.out.size());
    p.clock = 1000 + Selector.NEW_HOLD_MS;
    e.tick(p.clock); // the hold is over: it appears
    assertEquals(1, s.out.size());
    assertTrue(s.last().contains("\"id\":\"m\""), s.last());
  }

  @Test
  void demoQuietPublishesAnEmptySkyWhateverIsOverhead() {
    Config demo =
        new Config(
            PipelineTest.LAT,
            PipelineTest.LON,
            15 * 1852.0,
            "av",
            4,
            false,
            Config.Widen.HARD,
            15 * 1852.0,
            true);
    Sink s = new Sink();
    new Engine(demo, new Fake("local"), null, s, REFRESH).tick(0);
    assertTrue(s.last().contains("\"ac\":[]"), s.last());
    assertTrue(s.last().contains("\"st\":false"), "health still reflects the real feed");
  }

  @Test
  void heartbeatKeepsAnUnchangingFeedFresh() {
    Fake p = new Fake("local");
    Sink s = new Sink();
    Engine e = new Engine(CFG, p, null, s, REFRESH);
    for (long t = 0; t <= 130_000; t += 10_000) e.tick(t);
    assertTrue(s.out.size() >= 3, "heartbeat publishes about once a minute");
    assertTrue(s.last().contains("\"fa\":0") || s.last().contains("\"st\":false"));
  }

  @Test
  void fallsBackImmediatelyAndRetriesThePrimaryPeriodically() {
    Fake p = new Fake("local"), f = new Fake("net");
    Sink s = new Sink();
    Engine e = new Engine(CFG, p, f, s, REFRESH);
    p.fail = true;
    e.tick(0);
    assertTrue(s.last().contains("\"src\":\"net\"") && s.last().contains("\"st\":false"));
    int primaryCalls = p.calls;
    for (int i = 1; i < Engine.PRIMARY_RETRY_EVERY; i++) e.tick(i * 10_000L);
    assertEquals(primaryCalls, p.calls, "primary is left alone between retries");
    p.fail = false;
    e.tick(Engine.PRIMARY_RETRY_EVERY * 10_000L);
    assertTrue(s.last().contains("\"src\":\"local\""), "primary takes over again once it works");
  }

  @Test
  void aRetryAfterFromOneSourceDoesNotStallTheOther() {
    Fake net = new Fake("net"), local = new Fake("local");
    net.fail = true;
    net.retryAfterS = 600;
    Sink s = new Sink();
    Engine e = new Engine(CFG, net, local, s, REFRESH);
    assertEquals(REFRESH, e.tick(0)); // the fallback answered: normal cadence
    int netCalls = net.calls;
    assertEquals(REFRESH, e.tick(10_000));
    assertEquals(netCalls, net.calls, "the primary is not asked again before its Retry-After");
    local.fail = true;
    long wait = e.tick(20_000); // both down: wait for the soonest source, not the longest
    assertTrue(wait >= 20_000 && wait <= 600_000, "wait was " + wait);
  }

  @Test
  void goesStaleAfterThreeFailuresWithBackoffAndRetryAfter() {
    Fake p = new Fake("local");
    Sink s = new Sink();
    Engine e = new Engine(CFG, p, null, s, REFRESH);
    e.tick(0);
    p.fail = true;
    long d1 = e.tick(10_000), d2 = e.tick(20_000);
    assertEquals(20_000, d1);
    assertEquals(40_000, d2);
    assertFalse(e.isStale());
    p.retryAfterS = 90;
    long d3 = e.tick(60_000);
    assertTrue(e.isStale());
    assertEquals(90_000, d3); // Retry-After wins over the 80 s backoff
    assertTrue(s.last().contains("\"st\":true"));
    assertTrue(
        s.last().contains("\"ac\":[{"),
        "last good aircraft stay for the renderer's stale treatment");
    p.fail = false;
    p.retryAfterS = 0;
    assertEquals(REFRESH, e.tick(150_000));
    assertFalse(e.isStale());
    assertTrue(s.last().contains("\"st\":false"));
  }

  @Test
  void neverFetchedPublishesAWaitingState() {
    Fake p = new Fake("local");
    p.fail = true;
    Sink s = new Sink();
    new Engine(CFG, p, null, s, REFRESH).tick(0);
    assertTrue(s.last().contains("\"fa\":null") && s.last().contains("\"st\":true"));
  }

  @Test
  void statusTextNeverCarriesALocation() {
    Fake p = new Fake("local");
    p.fail = true;
    Engine e = new Engine(CFG, p, null, new Sink(), REFRESH);
    for (int i = 0; i < 3; i++) e.tick(i * 1000L);
    assertTrue(e.statusText().startsWith("Stale: local source unreachable"));
    assertFalse(e.statusText().contains("40.0"));
  }

  @Test
  void lateEnrichmentTriggersARepublish() {
    Fake p = new Fake("local");
    Sink s = new Sink();
    me.jxl.kiosk.plugins.flights.enrich.Enricher en =
        new me.jxl.kiosk.plugins.flights.enrich.Enricher(
            new me.jxl.kiosk.plugins.flights.enrich.Lookup() {
              public me.jxl.kiosk.plugins.flights.enrich.Info aircraft(String hex) {
                return new me.jxl.kiosk.plugins.flights.enrich.Info(
                    "N507DZ", "A21N", null, null, null, null);
              }

              public me.jxl.kiosk.plugins.flights.enrich.Info route(String cs) {
                return new me.jxl.kiosk.plugins.flights.enrich.Info(
                    null, null, "DAL", "Delta Air Lines", "DEN", "ATL");
              }
            });
    Engine e = new Engine(CFG, p, null, s, REFRESH, en);
    e.tick(0); // a new airline flight waits for its route lookup rather than showing without one
    assertEquals(
        0, s.out.size()); // and nothing is published meanwhile: an empty screen helps no one
    for (long t = 0; en.hasPending(); t += 1_000) en.step(t);
    e.tick(10_000); // the route arrived: the row appears complete
    assertEquals(1, s.out.size());
    assertTrue(
        s.last().contains("\"o\":\"DEN\"")
            && s.last().contains("\"d\":\"ATL\"")
            && s.last().contains("\"rg\":\"N507DZ\"")
            && s.last().contains("\"ty\":\"B738\"")); // the feed's type wins over the lookup
  }

  /** Serves aircraft at fixed distances north of home, filtered to the requested radius. */
  static final class Ranged implements SourceAdapter {
    final String id;
    final double[] nm;
    final List<Double> asked = new ArrayList<>();

    Ranged(String id, double... nm) {
      this.id = id;
      this.nm = nm;
    }

    public String id() {
      return id;
    }

    public FetchResult fetch(double lat, double lon, double r) {
      asked.add(r / 1852.0);
      List<Aircraft> l = new ArrayList<>();
      for (int i = 0; i < nm.length; i++) {
        if (nm[i] * 1852.0 <= r)
          l.add(PipelineTest.ac("h" + i, "DAL" + i, null, "A3", nm[i] / 60.0, 0, false));
      }
      return new FetchResult(l, 0);
    }
  }

  static Config widening(Config.Widen w, int rows) {
    return new Config(
        PipelineTest.LAT, PipelineTest.LON, 15 * 1852.0, "av", rows, false, w, 100 * 1852.0);
  }

  @Test
  void softWidensThenSnapsBack() {
    Ranged src = new Ranged("local", 35, 38); // 1 row + the featured aircraft = 2 wanted
    Sink s = new Sink();
    Engine e = new Engine(widening(Config.Widen.SOFT, 1), src, null, s, REFRESH);
    e.tick(0);
    assertTrue(s.last().contains("\"r\":" + Math.round(40 * 1852.0)), s.last());
    src.nm[0] = 10; // enough enter the base radius
    src.nm[1] = 12;
    e.tick(10_000);
    assertTrue(s.last().contains("\"r\":" + Math.round(15 * 1852.0)), s.last());
  }

  @Test
  void softWidensUntilFullOrLimit() {
    Ranged src = new Ranged("local", 10, 30, 50, 70, 90);
    Sink s = new Sink();
    Engine e = new Engine(widening(Config.Widen.SOFT, 2), src, null, s, REFRESH); // wants 3
    e.tick(0);
    assertTrue(s.last().contains("\"r\":" + Math.round(60 * 1852.0)), s.last()); // 10, 30, 50
    Ranged sparse = new Ranged("local", 95);
    Sink s2 = new Sink();
    new Engine(widening(Config.Widen.SOFT, 3), sparse, null, s2, REFRESH).tick(0);
    assertTrue(s2.last().contains("\"r\":" + Math.round(100 * 1852.0)), s2.last());
  }

  @Test
  void widenedStepsAskTheOtherSourceBeyondTheLocalReach() {
    Ranged local = new Ranged("local", 12);
    local.nm[0] = 200; // nothing within reach
    Ranged net = new Ranged("net", 55);
    Sink s = new Sink();
    Engine e = new Engine(widening(Config.Widen.SOFT, 4), local, net, s, REFRESH);
    e.tick(0);
    assertTrue(s.last().contains("\"src\":\"net\""), s.last());
    assertFalse(net.asked.isEmpty());
  }

  @Test
  void hardNeverWidens() {
    Ranged src = new Ranged("local", 40);
    Engine e = new Engine(widening(Config.Widen.HARD, 4), src, null, new Sink(), REFRESH);
    e.tick(0);
    assertEquals(1, src.asked.size());
  }
}
