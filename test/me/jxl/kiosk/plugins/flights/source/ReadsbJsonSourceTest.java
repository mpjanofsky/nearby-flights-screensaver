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

  private static Aircraft one(String fields) throws Exception {
    String body =
        "{\"aircraft\":[{\"hex\":\"abc123\",\"lat\":40.65,\"lon\":-73.78," + fields + "}]}";
    return ReadsbJsonSource.parse(body, 1000L, LAT, LON, R).aircraft.get(0);
  }

  @Test
  void altitudeComesFromAnyDump1090Spelling() throws Exception {
    double ft = 0.3048;
    assertEquals(1225 * ft, one("\"alt_baro\":1225").altitudeM, 1e-6);
    assertEquals(1225 * ft, one("\"alt_baro\":\"1225\"").altitudeM, 1e-6);
    assertEquals(1225 * ft, one("\"altitude\":1225").altitudeM, 1e-6); // classic dump1090
    assertEquals(1300 * ft, one("\"alt_geom\":1300").altitudeM, 1e-6);
    assertEquals(1225 * ft, one("\"alt_baro\":1225,\"alt_geom\":1300").altitudeM, 1e-6);
    assertTrue(Double.isNaN(one("\"flight\":\"X\"").altitudeM));
  }

  @Test
  void groundIsFlaggedAndNeverFallsBackToAnotherAltitude() throws Exception {
    Aircraft a = one("\"alt_baro\":\"ground\",\"alt_geom\":-50");
    assertTrue(a.onGround);
    assertTrue(Double.isNaN(a.altitudeM));
  }

  @Test
  void classicDump1090SpeedAndVerticalRateAreRead() throws Exception {
    Aircraft a = one("\"altitude\":1225,\"speed\":120,\"vert_rate\":-600");
    assertEquals(120 * 1852.0 / 3600.0, a.groundSpeedMs, 1e-6);
    assertEquals(-600 * 0.3048 / 60.0, a.verticalRateMs, 1e-6);
  }

  @Test
  void issue12RecordFromClassicDump1090Parses() throws Exception {
    // Record from public issue #12 (Echo Show 8, dump1090 data.json), position shifted to the test
    // point.
    Aircraft a =
        one(
            "\"nucp\":7,\"seen_pos\":38.4,\"altitude\":1225,\"vert_rate\":0,\"track\":195,"
                + "\"speed\":101,\"mlat\":[],\"tisb\":[],\"messages\":22,\"seen\":29.6,\"rssi\":-22.8");
    assertEquals(1225 * 0.3048, a.altitudeM, 1e-6);
    assertEquals(101 * 1852.0 / 3600.0, a.groundSpeedMs, 1e-6);
    assertEquals(0.0, a.verticalRateMs, 1e-9);
    assertFalse(a.onGround);
  }
}
