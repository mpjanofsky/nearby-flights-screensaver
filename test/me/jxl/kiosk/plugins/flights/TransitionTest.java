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
    Transition.Snapshot prev = new Transition.Snapshot(rows("a", "b", "c"), "b");
    Transition t = Transition.between(prev, rows("b", "c", "d"));
    assertEquals(2, t.previousListRows); // a and c were listed; b was in the band
    assertEquals(0, t.previousSlot.get("b"));
    assertEquals(1, t.previousSlot.get("a"));
    assertEquals(2, t.previousSlot.get("c"));
    assertEquals(1, t.gone.size()); // a left
    assertEquals("a", t.gone.get(0).aircraft.hex);
  }

  @Test
  void aMissingFeaturedIdFallsBackToTheFirstRowLikeTheRenderer() {
    Transition.Snapshot prev = new Transition.Snapshot(rows("a", "b"), null);
    Transition t = Transition.between(prev, rows("a", "b"));
    assertEquals(0, t.previousSlot.get("a"));
    assertEquals(1, t.previousListRows);
  }

  @Test
  void noDescriptionWithoutAPreviousPublish() {
    assertNull(Transition.between(null, rows("a")));
  }

  @Test
  void encodesPnPvAndGoneWithoutATrail() throws Exception {
    List<Tracked> before = rows("a", "b", "c");
    List<Tracked> now = rows("b", "c", "d");
    Transition t = Transition.between(new Transition.Snapshot(before, "a"), now);
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
}
