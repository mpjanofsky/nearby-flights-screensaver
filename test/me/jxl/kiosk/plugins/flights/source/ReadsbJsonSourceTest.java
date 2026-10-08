package me.jxl.kiosk.plugins.flights.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadsbJsonSourceTest {
  // The fixtures were recorded around this public point; it is not anyone's home.
  static final double LAT = 40.64, LON = -73.78, R = 40 * 1852.0;

  static String fixture(String name) throws Exception {
    return new String(Files.readAllBytes(Paths.get("fixtures/feed", name)), "UTF-8");
  }

  static FetchResult parse(String name, double radiusM) throws Exception {
    return ReadsbJsonSource.parse(fixture(name), 1000L, LAT, LON, radiusM);
  }

  @Test
  void bothEnvelopesGiveTheSameAircraft() throws Exception {
    FetchResult net = parse("adsblol-point.json", R),
        local = parse("readsb-local-synthetic.json", R);
    assertEquals(9, net.aircraft.size());
    assertEquals(net.aircraft.size(), local.aircraft.size()); // the position-less extra is dropped
    for (int i = 0; i < net.aircraft.size(); i++) {
      assertEquals(net.aircraft.get(i).hex, local.aircraft.get(i).hex);
      assertEquals(net.aircraft.get(i).altitudeM, local.aircraft.get(i).altitudeM, 1e-9);
    }
  }

  @Test
  void groundTrafficHasNoAltitudeButIsFlagged() throws Exception {
    List<Aircraft> ground = new java.util.ArrayList<>();
    for (Aircraft a : parse("adsblol-point.json", R).aircraft) if (a.onGround) ground.add(a);
    assertEquals(2, ground.size());
    assertTrue(Double.isNaN(ground.get(0).altitudeM));
  }

  @Test
  void unitsAreSiAndTextIsTrimmed() throws Exception {
    for (Aircraft a : parse("adsblol-point.json", R).aircraft) {
      if (a.onGround) continue;
      assertTrue(a.altitudeM > 0 && a.altitudeM < 20000, "altitude in metres");
      assertTrue(a.groundSpeedMs < 400, "ground speed in m/s");
      if (a.callsign != null) assertEquals(a.callsign.trim(), a.callsign);
    }
  }

  @Test
  void aircraftWithoutAPositionAreSkipped() throws Exception {
    for (Aircraft a : parse("readsb-local-synthetic.json", R).aircraft)
      assertFalse(a.hex.equals("abcdef"));
  }

  @Test
  void localFeedIsFilteredToTheRadius() throws Exception {
    FetchResult all = parse("readsb-local-synthetic.json", R),
        near = parse("readsb-local-synthetic.json", 5 * 1852.0);
    assertTrue(near.aircraft.size() < all.aircraft.size());
    for (Aircraft a : near.aircraft)
      assertTrue(me.jxl.kiosk.plugins.flights.Geo.distanceM(LAT, LON, a.lat, a.lon) <= 5 * 1852.0);
  }

  @Test
  void positionAgeFallsBackAndNowIsIgnored() throws Exception {
    // adsb.lol `now` is ms and the local one is seconds; neither may affect the result.
    FetchResult net = parse("adsblol-point.json", R),
        local = parse("readsb-local-synthetic.json", R);
    assertEquals(1000L, net.observedAtMs);
    assertEquals(net.aircraft.get(0).positionAgeS, local.aircraft.get(0).positionAgeS, 1e-9);
  }

  @Test
  void badBodiesFailWithoutEchoingTheBody() {
    SourceException e =
        assertThrows(
            SourceException.class,
            () -> ReadsbJsonSource.parse("{\"lat\": 40.64123}", 0, LAT, LON, R));
    assertFalse(e.getMessage().contains("40.64"));
    assertThrows(SourceException.class, () -> ReadsbJsonSource.parse("<html>", 0, LAT, LON, R));
  }

  @Test
  void adsbLolQueryIsRoundedAndPadded() {
    assertEquals(
        "https://api.adsb.lol/v2/point/40.64/-73.78/16",
        ReadsbJsonSource.adsbLolUrl(40.63993, -73.77869, 15 * 1852.0));
    assertTrue(ReadsbJsonSource.adsbLolUrl(0, 0, 400 * 1852.0).endsWith("/250"));
  }

  @Test
  void retryAfterIsClamped() {
    assertEquals(30, ReadsbJsonSource.parseRetryAfter(" 30 "));
    assertEquals(0, ReadsbJsonSource.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
    assertEquals(3600, ReadsbJsonSource.parseRetryAfter("99999"));
    assertNull(null);
  }

  @Test
  void realLocalFeedHasNoRegistrationOrTypeAndStillParses() throws Exception {
    // Real capture (coordinates shifted): the local feed carries no `r`/`t`, only `flight`.
    FetchResult r =
        ReadsbJsonSource.parse(
            fixture("readsb-local-real.json"), 1000L, -10.51, 58.59, 250 * 1852.0);
    assertEquals(18, r.aircraft.size()); // 12 of the 30 had no position yet
    for (Aircraft a : r.aircraft) {
      assertNull(a.registration);
      assertNull(a.type);
      if (a.callsign != null) assertEquals(a.callsign.trim(), a.callsign);
      assertFalse(a.onGround);
    }
    int withAlt = 0;
    for (Aircraft a : r.aircraft) if (!Double.isNaN(a.altitudeM)) withAlt++;
    assertTrue(withAlt > 0 && withAlt <= 18);
  }
}
