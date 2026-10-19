package me.jxl.kiosk.plugins.flights;

import static me.jxl.kiosk.plugins.flights.PipelineTest.CFG;
import static me.jxl.kiosk.plugins.flights.PipelineTest.ac;
import static me.jxl.kiosk.plugins.flights.PipelineTest.fetch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class TransitionTest {
  static List<Tracked> rows(String... hexes) {
    // Distances grow with the position in the argument list, so the order is the argument order.
    me.jxl.kiosk.plugins.flights.source.Aircraft[] a =
        new me.jxl.kiosk.plugins.flights.source.Aircraft[hexes.length];
    for (int i = 0; i < a.length; i++)
      a[i] = ac(hexes[i], "DAL" + (100 + i), null, "A3", 0.01 * (i + 1), 0, false);
    return new Selector().select(fetch(1000, a), CFG);
  }

  @Test
  void slotsAreFeaturedZeroThenTheListWithoutIt() {
    Transition.Snapshot prev = new Transition.Snapshot(rows("a", "b", "c"), "b", 1000);
    Transition t = Transition.between(prev, rows("b", "c", "d"), 2000, 2000);
    assertEquals(2, t.previousListRows); // a and c were listed; b was in the band
    assertEquals(0, t.previousSlot.get("b"));
    assertEquals(1, t.previousSlot.get("a"));
    assertEquals(2, t.previousSlot.get("c"));
    assertEquals(1, t.gone.size()); // a left
    assertEquals("a", t.gone.get(0).aircraft.hex);
  }

  @Test
  void aMissingFeaturedIdFallsBackToTheFirstRowLikeTheRenderer() {
    Transition.Snapshot prev = new Transition.Snapshot(rows("a", "b"), null, 1000);
    Transition t = Transition.between(prev, rows("a", "b"), 2000, 2000);
    assertEquals(0, t.previousSlot.get("a"));
    assertEquals(1, t.previousListRows);
  }

  @Test
  void noDescriptionWithoutAPreviousPublish() {
    assertNull(Transition.between(null, rows("a"), 2000, 2000));
  }

  @Test
  void encodesPnPvAndGoneWithoutATrail() throws Exception {
    List<Tracked> before = rows("a", "b", "c");
    List<Tracked> now = rows("b", "c", "d");
    Transition t = Transition.between(new Transition.Snapshot(before, "a", 1000), now, 2000, 2000);
    String json =
        PayloadEncoder.encode(
            now,
            CFG,
            new PayloadEncoder.Health(1, false, "local"),
            2000,
            2000,
            "b",
            java.util.Collections.emptyList(),
            t);
    JSONObject p = new JSONObject(json);
    assertEquals(2, p.getInt("pn"));
    JSONArray ac = p.getJSONArray("ac");
    assertEquals(1, ac.getJSONObject(0).getInt("pv")); // b was list slot 1
    assertEquals(2, ac.getJSONObject(1).getInt("pv"));
    assertFalse(ac.getJSONObject(2).has("pv")); // d is new
    JSONObject gone = p.getJSONArray("gone").getJSONObject(0);
    assertEquals("a", gone.getString("id"));
    assertEquals(0, gone.getInt("pv")); // a was featured
    assertFalse(gone.has("tr"));
    assertFalse(json.contains("</"));
  }

  @Test
  void statesWhereTheScopeDrewABlipMinusWhereItWillDrawIt() throws Exception {
    // Same fix both times; the old page kept extrapolating (11 s old at the new gen, 150 m/s east),
    // the new fix is 2 s old. The blip must glide back 9 s * 150 m/s = 1350 m.
    List<Tracked> before = rows("a");
    List<Tracked> now = rows("a");
    Transition t =
        Transition.between(new Transition.Snapshot(before, "a", 1000), now, 11000, 10000);
    assertEquals(1350, t.jump.get("a")[0], 1);
    assertEquals(0, t.jump.get("a")[1], 1);
    JSONObject a =
        new JSONObject(
                PayloadEncoder.encode(
                    now,
                    CFG,
                    new PayloadEncoder.Health(1, false, "local"),
                    11000,
                    10000,
                    "a",
                    java.util.Collections.emptyList(),
                    t))
            .getJSONArray("ac")
            .getJSONObject(0);
    assertEquals(1350, a.getInt("jx"), 1);
    assertEquals(0, a.getInt("jy"), 1);
  }

  @Test
  void noOffsetForAnInvisibleMoveOrANewAircraft() {
    List<Tracked> same = rows("a");
    // Both fixes agree at the same instant: nothing to ease.
    Transition t =
        Transition.between(new Transition.Snapshot(same, "a", 1000), rows("a"), 1000, 1000);
    assertFalse(t.jump.containsKey("a"));
    Transition u =
        Transition.between(new Transition.Snapshot(same, "a", 1000), rows("b"), 2000, 2000);
    assertFalse(u.jump.containsKey("b"));
  }
}
