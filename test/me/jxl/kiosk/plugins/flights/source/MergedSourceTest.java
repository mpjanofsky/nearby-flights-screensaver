package me.jxl.kiosk.plugins.flights.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MergedSourceTest {
  static Aircraft ac(String hex, String cs, String type, double age) {
    return new Aircraft(hex, cs, null, type, null, 27.0, -82.0, 1000, 100, 90, 0, age, false);
  }

  static Aircraft ac(String hex, String cs, String type, double age, double lat) {
    return new Aircraft(hex, cs, null, type, null, lat, -82.0, 1000, 100, 90, 0, age, false);
  }

  /** A source that returns a fixed list, or throws, and counts the calls it got. */
  static final class Fake implements SourceAdapter {
    final List<Aircraft> list;
    final SourceException err;
    int calls;

    Fake(List<Aircraft> list, SourceException err) {
      this.list = list;
      this.err = err;
    }

    @Override
    public String id() {
      return "fake";
    }

    @Override
    public FetchResult fetch(double a, double b, double r) throws SourceException {
      calls++;
      if (err != null) throw err;
      return new FetchResult(new ArrayList<>(list), 1000L);
    }
  }

  static List<Aircraft> list(Aircraft... a) {
    return java.util.Arrays.asList(a);
  }

  @Test
  void unionsByHexAndFillsGapsFromTheOtherCopy() throws Exception {
    Fake local = new Fake(list(ac("aaa", "DAL1", null, 2), ac("bbb", null, null, 1)), null);
    Fake net = new Fake(list(ac("aaa", "DAL1", "B739", 2), ac("ccc", "SWA2", "B38M", 5)), null);
    FetchResult r = new MergedSource(local, net).fetch(27, -82, 20000);
    assertEquals(3, r.aircraft.size());
    Aircraft a = r.aircraft.stream().filter(x -> x.hex.equals("aaa")).findFirst().get();
    assertEquals("B739", a.type); // the local feed has no type; the net copy fills it
  }

  @Test
  void theFresherFixWinsAndTiesGoToTheLocalFeed() throws Exception {
    Fake local =
        new Fake(list(ac("aaa", "L", null, 6, 27.10), ac("bbb", "L", null, 3, 27.20)), null);
    Fake net =
        new Fake(list(ac("aaa", "N", "B739", 1, 27.11), ac("bbb", "N", "B38M", 2.5, 27.21)), null);
    FetchResult r = new MergedSource(local, net).fetch(27, -82, 20000);
    Aircraft a = r.aircraft.stream().filter(x -> x.hex.equals("aaa")).findFirst().get();
    Aircraft b = r.aircraft.stream().filter(x -> x.hex.equals("bbb")).findFirst().get();
    assertEquals(27.11, a.lat, 1e-9); // net is 5 s fresher
    assertEquals(27.20, b.lat, 1e-9); // within 1 s: keep the LAN copy
    assertEquals("B38M", b.type); // and still take the missing type
  }

  @Test
  void oneFeedFailingStillGivesTheOther() throws Exception {
    SourceException down = new SourceException("local source unreachable");
    Fake local = new Fake(null, down);
    Fake net = new Fake(list(ac("ccc", "SWA2", "B38M", 5)), null);
    assertEquals(1, new MergedSource(local, net).fetch(27, -82, 20000).aircraft.size());
    Fake local2 = new Fake(list(ac("aaa", "L", null, 1)), null);
    Fake net2 = new Fake(null, new SourceException("net down"));
    assertEquals(1, new MergedSource(local2, net2).fetch(27, -82, 20000).aircraft.size());
  }

  @Test
  void bothFailingThrowsAndNamesBothWithoutAUrl() {
    Fake local = new Fake(null, new SourceException("local source unreachable"));
    Fake net = new Fake(null, new SourceException("net source answered HTTP 429", null, 30));
    SourceException e =
        assertThrows(SourceException.class, () -> new MergedSource(local, net).fetch(27, -82, 1));
    assertTrue(e.getMessage().contains("local") && e.getMessage().contains("429"));
    assertEquals(30, e.retryAfterS);
  }

  @Test
  void aRateLimitedNetFeedIsLeftAloneUntilItsWaitEnds() throws Exception {
    long[] now = {0};
    Fake local = new Fake(list(ac("aaa", "L", null, 1)), null);
    Fake net = new Fake(null, new SourceException("net source answered HTTP 429", null, 30));
    MergedSource m = new MergedSource(local, net, () -> now[0]);
    assertEquals(1, m.fetch(27, -82, 1).aircraft.size());
    assertEquals(1, net.calls);
    now[0] = 10_000;
    m.fetch(27, -82, 1);
    assertEquals(1, net.calls, "net not asked during its Retry-After");
    now[0] = 31_000;
    m.fetch(27, -82, 1);
    assertEquals(2, net.calls);
    assertEquals("mix", m.id());
  }
}
