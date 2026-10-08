package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.flights.source.Aircraft;
import me.jxl.kiosk.plugins.flights.source.FetchResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PipelineTest {
  static final double LAT = 40.0, LON = -73.0; // a made-up home
  static final Config CFG = new Config(LAT, LON, 15 * 1852.0, "av", 4, false);

  /** An airborne aircraft `dEast`/`dNorth` degrees from home. */
  static Aircraft ac(
      String hex, String cs, String reg, String cat, double dNorth, double dEast, boolean ground) {
    return new Aircraft(
        hex,
        cs,
        reg,
        "B738",
        cat,
        LAT + dNorth,
        LON + dEast,
        ground ? Double.NaN : 3000,
        150,
        90,
        2.5,
        1.0,
        ground);
  }

  static FetchResult fetch(long at, Aircraft... a) {
    List<Aircraft> l = new ArrayList<>();
    for (Aircraft x : a) l.add(x);
    return new FetchResult(l, at);
  }

  @Test
  void ranksByDistanceDropsGroundAndOutOfRange() {
    FetchResult r =
        fetch(
            1000,
            ac("far", "DAL1", null, "A3", 0.15, 0, false), // ~9 nm
            ac("near", "SWA2", null, "A3", 0.02, 0, false),
            ac("gnd", "UAL3", null, "A3", 0.01, 0, true),
            ac("out", "AAL4", null, "A3", 1.0, 0, false)); // 60 nm
    List<Tracked> rows = new Selector().select(r, CFG);
    assertEquals(2, rows.size());
    assertEquals("near", rows.get(0).aircraft.hex);
    assertEquals("far", rows.get(1).aircraft.hex);
  }

  @Test
  void holdsANewAircraftWithoutACallsignUntilItIdentifiesOrTimesOut() {
    Selector sel = new Selector();
    Aircraft named = ac("named", "DAL1", null, "A3", 0.05, 0, false);
    Aircraft mute = ac("mute", null, null, "A3", 0.02, 0, false);
    // First sight: the mute one waits (and is queued for lookup) while the named one shows.
    List<Tracked> rows = sel.select(fetch(1000, named, mute), CFG);
    assertEquals(1, rows.size());
    assertEquals("named", rows.get(0).aircraft.hex);
    assertEquals("mute", sel.upNext().get(0).hex);
    // It identifies itself: shown at once, and nearest first.
    Aircraft spoke = ac("mute", "DAL2", null, "A3", 0.02, 0, false);
    rows = sel.select(fetch(4000, named, spoke), CFG);
    assertEquals("mute", rows.get(0).aircraft.hex);
    // One that never does appears once the hold is over.
    Selector sel2 = new Selector();
    assertEquals(0, sel2.select(fetch(1000, mute), CFG).size());
    assertEquals(0, sel2.select(fetch(1000 + Selector.NEW_HOLD_MS - 1, mute), CFG).size());
    assertEquals(1, sel2.select(fetch(1000 + Selector.NEW_HOLD_MS, mute), CFG).size());
  }

  @Test
  void aVanishedAircraftKeepsItsSlotWhileANewcomerWaitsThenGoes() {
    Selector sel = new Selector();
    Aircraft gone = ac("gone", "DAL1", null, "A3", 0.02, 0, false);
    Aircraft stay = ac("stay", "DAL2", null, "A3", 0.04, 0, false);
    Aircraft mute = ac("mute", null, null, "A3", 0.03, 0, false); // no callsign: held back
    assertEquals(2, sel.select(fetch(0, gone, stay), CFG).size());
    // `gone` leaves the feed while `mute` is new and waiting: its slot is kept, one tick.
    List<Tracked> rows = sel.select(fetch(5000, stay, mute), CFG);
    assertEquals(2, rows.size());
    assertEquals("gone", rows.get(0).aircraft.hex);
    assertTrue(rows.get(0).aircraft.positionAgeS >= 5, "its fix is aged by the time kept");
    // Still within the hold: kept. Once it is over the newcomer is shown and `gone` is not.
    rows = sel.select(fetch(5000 + Selector.DEPART_HOLD_MS - 1, stay, mute), CFG);
    assertEquals("gone", rows.get(0).aircraft.hex);
    rows = sel.select(fetch(5000 + Selector.DEPART_HOLD_MS, stay, mute), CFG);
    assertEquals(2, rows.size());
    assertEquals("mute", rows.get(0).aircraft.hex);
    assertEquals("stay", rows.get(1).aircraft.hex);
    // With nobody waiting, a departure is immediate.
    Selector sel2 = new Selector();
    sel2.select(fetch(0, gone, stay), CFG);
    assertEquals(1, sel2.select(fetch(5000, stay), CFG).size());
  }

  @Test
  void dropsAircraftWhosePositionFixIsAMinuteOld() {
    Aircraft fresh = ac("fresh", "DAL1", null, "A3", 0.02, 0, false);
    Aircraft old = ac("old", "SWA2", null, "A3", 0.03, 0, false);
    Aircraft ghost =
        new Aircraft(
            "ghost",
            "UAL3",
            null,
            "B738",
            "A3",
            LAT + 0.01,
            LON,
            3000,
            150,
            90,
            2.5,
            Selector.MAX_POSITION_AGE_S + 1,
            false);
    FetchResult r = fetch(1000, fresh, old, ghost);
    assertEquals(2, new Selector().select(r, CFG).size());
    assertEquals(2, Selector.eligible(r, CFG)); // the widening probe agrees
  }

  @Test
  void upNextHoldsTheClosestAircraftThatAreNotShown() {
    Selector sel = new Selector();
    Config c = new Config(LAT, LON, 15 * 1852.0, "av", 1, false); // shows 2
    sel.select(
        fetch(
            1,
            ac("a", "DAL1", null, "A3", 0.01, 0, false),
            ac("b", "DAL2", null, "A3", 0.02, 0, false),
            ac("c", "DAL3", null, "A3", 0.03, 0, false), // past the row cap
            ac("out", "DAL4", null, "A3", 0.40, 0, false), // beyond the radius
            ac("gnd", "DAL5", null, "A3", 0.04, 0, true)), // on the ground: never
        c);
    List<String> next = new ArrayList<>();
    for (Aircraft a : sel.upNext()) next.add(a.hex);
    assertEquals(List.of("c", "out"), next);
  }

  static String hexes(List<Tracked> rows) {
    StringBuilder b = new StringBuilder();
    for (Tracked t : rows) b.append(t.aircraft.hex);
    return b.toString();
  }

  @Test
  void aShownAircraftKeepsItsPlaceForAMinimumTime() {
    Selector sel = new Selector();
    Config c = new Config(LAT, LON, 15 * 1852.0, "av", 1, false); // shows 2
    assertEquals(
        "ab",
        hexes(
            sel.select(
                fetch(
                    0,
                    ac("a", "DAL1", null, "A3", 0.01, 0, false),
                    ac("b", "DAL2", null, "A3", 0.02, 0, false)),
                c)));
    Aircraft[] withCloser = {
      ac("a", "DAL1", null, "A3", 0.01, 0, false),
      ac("b", "DAL2", null, "A3", 0.02, 0, false),
      ac("c", "DAL3", null, "A3", 0.005, 0, false) // closest of all, but new
    };
    assertEquals("ab", hexes(sel.select(fetch(10_000, withCloser), c))); // c waits
    assertEquals("ca", hexes(sel.select(fetch(25_000, withCloser), c)));
  }

  @Test
  void aShownAircraftThatLeavesTheRadiusStaysForAGracePeriod() {
    Selector sel = new Selector();
    Config c = new Config(LAT, LON, 15 * 1852.0, "av", 3, false);
    sel.select(fetch(0, ac("a", "DAL1", null, "A3", 0.01, 0, false)), c);
    Aircraft outAndAbout = ac("a", "DAL1", null, "A3", 0.5, 0, false); // ~30 nm out
    assertEquals("a", hexes(sel.select(fetch(10_000, outAndAbout), c)));
    assertEquals("", hexes(sel.select(fetch(25_000, outAndAbout), c))); // grace is over
    // never shown, so no grace: outside the radius it is simply not listed
    assertEquals("", hexes(new Selector().select(fetch(0, outAndAbout), c)));
  }

  @Test
  void keepsTheListRowsPlusTheFeaturedAircraft() {
    Aircraft[] many = new Aircraft[6];
    for (int i = 0; i < 6; i++)
      many[i] = ac("h" + i, "DAL" + i, null, "A3", 0.01 * (i + 1), 0, false);
    assertEquals(
        3, // 2 rows + the featured aircraft
        new Selector()
            .select(fetch(1, many), new Config(LAT, LON, 15 * 1852.0, "av", 2, false))
            .size());
  }

  @Test
  void excludeGaUsesCategoryAndBareRegistrationsOnly() {
    assertTrue(Selector.isGeneralAviation(ac("a", "N512QX", "N512QX", null, 0, 0, false)));
    assertTrue(Selector.isGeneralAviation(ac("a", "CALLER", "G-ABCD", "A1", 0, 0, false)));
    assertTrue(Selector.isGeneralAviation(ac("a", "XYZ", null, "A7", 0, 0, false)));
    // A listed carrier's light aircraft stays (DAL is in the airline table).
    assertFalse(Selector.isGeneralAviation(ac("a", "DAL1234", null, "A1", 0, 0, false)));
    // An airliner flying under its tail number (N9006, an A319) is not general aviation.
    assertFalse(Selector.isGeneralAviation(ac("a", "N9006", "N9006", "A3", 0, 0, false)));
    assertTrue(Selector.isGeneralAviation(ac("a", "N9006", "N9006", "A2", 0, 0, false)));
    assertFalse(Selector.isGeneralAviation(ac("a", "DAL1234", "N371DA", "A3", 0, 0, false)));
    assertFalse(
        Selector.isGeneralAviation(ac("a", null, null, null, 0, 0, false))); // no evidence: keep
    Config ga = new Config(LAT, LON, 15 * 1852.0, "av", 4, true);
    assertEquals(
        1,
        new Selector()
            .select(
                fetch(
                    1,
                    ac("g", "N512QX", "N512QX", "A1", 0.01, 0, false),
                    ac("d", "DAL1", null, "A3", 0.02, 0, false)),
                ga)
            .size());
  }

  @Test
  void trailsGrowAcrossFetchesAndForgetGoneAircraft() {
    Selector s = new Selector();
    s.select(fetch(1000, ac("a", "DAL1", null, "A3", 0.01, 0.00, false)), CFG);
    s.select(fetch(11000, ac("a", "DAL1", null, "A3", 0.01, 0.01, false)), CFG);
    List<Tracked> rows =
        s.select(fetch(21000, ac("a", "DAL1", null, "A3", 0.01, 0.02, false)), CFG);
    assertEquals(3, rows.get(0).trail.size());
    assertTrue(rows.get(0).trail.get(2).x > rows.get(0).trail.get(0).x);
    s.select(fetch(31000), CFG); // aircraft gone
    assertEquals(
        1,
        s.select(fetch(41000, ac("a", "DAL1", null, "A3", 0.01, 0.03, false)), CFG)
            .get(0)
            .trail
            .size());
  }

  @Test
  void encodedPayloadMatchesTheContract() throws Exception {
    Selector s = new Selector();
    // The bare-hex aircraft is seen first at 1000, so by 11000 it is past the new-aircraft hold.
    Aircraft bareHex =
        new Aircraft(
            "zz9999",
            null,
            null,
            null,
            null,
            LAT + 0.05,
            LON,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            0,
            false);
    s.select(fetch(1000, ac("a1b2c3", "DAL1234", "N371DA", "A3", 0.03, 0.01, false), bareHex), CFG);
    List<Tracked> rows =
        s.select(
            fetch(11000, ac("a1b2c3", "DAL1234", "N371DA", "A3", 0.031, 0.012, false), bareHex),
            CFG);
    String json =
        PayloadEncoder.encode(
            rows, CFG, new PayloadEncoder.Health(2.5, false, "local"), 12000, 11000);
    JSONObject p = new JSONObject(json);
    assertEquals(2, p.getInt("v"));
    assertFalse(p.has("pn") || p.has("gone")); // nothing to animate from on a first publish
    assertEquals(12000L, p.getLong("gen"));
    assertEquals("local", p.getString("src"));
    assertEquals(27780, p.getInt("r"));
    JSONArray ac = p.getJSONArray("ac");
    assertEquals(2, ac.length());
    JSONObject a = ac.getJSONObject(0);
    assertEquals("a1b2c3", a.getString("id"));
    assertEquals("DAL1234", a.getString("cs"));
    assertEquals(3000, a.getInt("alt"));
    assertEquals(2.0, a.getDouble("a"), 1e-9); // 1.0 s position age + 1 s since the fetch
    assertEquals(2, a.getJSONArray("tr").length());
    assertEquals(3, a.getJSONArray("tr").getJSONArray(0).length());
    JSONObject bare = ac.getJSONObject(1); // unknown fields are omitted, never null or NaN
    assertFalse(
        bare.has("cs") || bare.has("alt") || bare.has("gs") || bare.has("trk") || bare.has("vr"));
    assertFalse(json.contains("NaN") || json.contains("null,\"st\""));
    Files.createDirectories(Paths.get("build"));
    Files.write(
        Paths.get("build", "java-payload.json"),
        json.getBytes("UTF-8")); // checked by scripts/check.sh
  }

  @Test
  void neverFetchedEncodesNullAndEscapesScriptClosers() {
    String json =
        PayloadEncoder.encode(
            new ArrayList<Tracked>(),
            CFG,
            new PayloadEncoder.Health(Double.NaN, true, "net"),
            5,
            5);
    assertTrue(
        json.contains("\"fa\":null") && json.contains("\"st\":true") && json.contains("\"ac\":[]"));
    assertEquals("\"\\u003c/script>\"", PayloadEncoder.str("</script>"));
  }

  @Test
  void gatePublishesOnChangeAndOnHeartbeatOnly() {
    PublishGate g = new PublishGate();
    List<Tracked> rows =
        new Selector().select(fetch(1, ac("a", "DAL1", null, "A3", 0.02, 0, false)), CFG);
    assertTrue(g.changed(rows, null, CFG, false, "local", 0));
    assertFalse(g.changed(rows, null, CFG, false, "local", 1000)); // same content
    assertTrue(g.changed(rows, null, CFG, true, "local", 2000)); // went stale
    assertFalse(g.changed(rows, null, CFG, true, "local", 3000));
    assertTrue(
        g.changed(rows, null, CFG, true, "local", 3000 + PublishGate.HEARTBEAT_MS)); // heartbeat
    List<Tracked> moved =
        new Selector().select(fetch(1, ac("a", "DAL1", null, "A3", 0.03, 0, false)), CFG);
    assertTrue(g.changed(moved, null, CFG, true, "local", 3500 + PublishGate.HEARTBEAT_MS));
  }
}
