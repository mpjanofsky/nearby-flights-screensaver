package me.jxl.kiosk.plugins.flights.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** The native view's animation state, driven with explicit clocks (no Android, no drawing). */
class SceneTest {
  static final long WALL = 1760000000000L;
  static final double BOTTOM = 436, PITCH = 80, BAND = 14;

  /** The golden normal-4 fixture, with an optional edit of its aircraft list and featured id. */
  static Model payload(String ft, String... keepIds) throws Exception {
    JSONObject o = new JSONObject(Files.readString(Path.of("fixtures/payload/normal-4.json")));
    JSONArray all = o.getJSONArray("ac"), kept = new JSONArray();
    for (int i = 0; i < all.length(); i++) {
      String id = all.getJSONObject(i).getString("id");
      if (keepIds.length == 0 || java.util.Arrays.asList(keepIds).contains(id))
        kept.put(all.get(i));
    }
    o.put("ac", kept);
    if (ft == null) o.remove("ft");
    else o.put("ft", ft);
    return Model.parse(o.toString(), WALL);
  }

  /** Runs frames every 33 ms from {@code from} for {@code ms}; returns the last tick. */
  static long run(Scene s, long from, long ms) {
    long t = from;
    for (; t <= from + ms; t += 33) s.frame(WALL + t, t, BOTTOM, PITCH, BAND);
    return t - 33;
  }

  @Test
  void sweepFollowsTheMonotonicClockOnly() {
    assertEquals(-90f, Scene.sweepDeg(0), 1e-3);
    assertEquals(90f, Scene.sweepDeg(Scene.SWEEP_MS / 2), 1e-3);
    assertEquals(-90f, Scene.sweepDeg(Scene.SWEEP_MS * 7), 1e-3);
    // A frame later it has advanced by a frame's worth, nothing else.
    float step = Scene.sweepDeg(1033) - Scene.sweepDeg(1000);
    assertEquals(33 * 360f / Scene.SWEEP_MS, step, 1e-3);
  }

  @Test
  void springReachesItsTargetWithoutOvershootAndKeepsVelocity() {
    Scene.Spring sp = new Scene.Spring();
    sp.x = 100;
    double last = sp.x;
    for (int i = 0; i < 120; i++) {
      sp.step(33);
      assertTrue(sp.x <= last + 1e-9 && sp.x >= -1e-9, "monotone, no overshoot");
      last = sp.x;
    }
    assertEquals(0, sp.x, 0.5);
    // Splitting a step in two lands in the same place (it is the exact solution, not Euler).
    Scene.Spring a = new Scene.Spring(), b = new Scene.Spring();
    a.x = b.x = 50;
    a.v = b.v = -30;
    a.step(100);
    b.step(40);
    b.step(60);
    assertEquals(a.x, b.x, 1e-6);
    assertEquals(a.v, b.v, 1e-6);
  }

  @Test
  void aNewAircraftFadesInAndANewPayloadDoesNotMoveWhatIsDrawn() throws Exception {
    Scene s = new Scene();
    s.accept(payload("a1b2c3"), WALL, 0);
    s.frame(WALL, 0, BOTTOM, PITCH, BAND);
    assertEquals(0, s.crafts.get("a1b2c3").scopeA, 1e-6);
    long t = run(s, 33, 2000);
    assertEquals(1, s.crafts.get("a1b2c3").scopeA, 1e-6);
    assertEquals(1, s.crafts.get("a1b2c3").listA, 1e-6);

    // A payload that puts the aircraft elsewhere: at the instant it arrives it is drawn where it
    // was.
    Scene.Craft c = s.crafts.get("a1b2c3");
    double[] before = {c.row.x + c.ex.x, c.row.y + c.ey.x};
    Model next = payload("a1b2c3");
    next.ac.get(0).x += 900;
    next.ac.get(0).y -= 700;
    s.accept(next, WALL + t, t);
    s.frame(WALL + t, t, BOTTOM, PITCH, BAND);
    double[] after = {c.row.x + c.ex.x, c.row.y + c.ey.x};
    assertEquals(before[0], after[0], 1e-6);
    assertEquals(before[1], after[1], 1e-6);
    run(s, t + 33, 3000);
    assertEquals(0, c.ex.x, 1, "the easing offset has run out");
    assertEquals(0, c.ey.x, 1);
  }

  @Test
  void aDepartingRowFadesInPlaceThenTheRowAboveSlidesDown() throws Exception {
    Scene s = new Scene();
    s.accept(payload("a1b2c3"), WALL, 0);
    long t = run(s, 0, 2000);
    assertEquals(3, s.listOrder.size());
    Scene.Craft leaving = s.listOrder.get(1), stay = s.listOrder.get(0);
    double leavingSlot = leaving.slot.x, staySlot = stay.slot.x;

    // The middle row is gone from the next payload. The list is bottom aligned, so the row above
    // it slides down to close the gap.
    java.util.List<String> keep = new java.util.ArrayList<>();
    for (Scene.Craft c : s.listOrder) if (c != leaving) keep.add(c.id);
    keep.add("a1b2c3");
    s.accept(payload("a1b2c3", keep.toArray(new String[0])), WALL + t, t);
    assertFalse(leaving.present);
    assertTrue(s.listOrder.contains(leaving), "keeps its place while it fades");
    t = run(s, t + 33, 200);
    assertEquals(leavingSlot, leaving.slot.x, 1e-6, "holds its slot");
    assertEquals(staySlot, stay.slot.x, 1e-6, "the row above does not move yet");
    assertTrue(leaving.listA > 0 && leaving.listA < 1, "fading");
    assertTrue(leaving.scopeA > 0, "blip still there");

    t = run(s, t + 33, 1200);
    assertFalse(s.listOrder.contains(leaving), "collapsed once faded");
    t = run(s, t + 33, 2000);
    assertTrue(stay.slot.x > staySlot, "the row above slid down");
    assertNull(s.crafts.get(leaving.id), "gone from the scope too");
  }

  @Test
  void theFeaturedAircraftChangesRoleByMovingNotBySwapping() throws Exception {
    Scene s = new Scene();
    s.accept(payload("a1b2c3"), WALL, 0);
    long t = run(s, 0, 2000);
    Scene.Craft oldF = s.crafts.get("a1b2c3"), newF = s.crafts.get("a4d5e6");
    assertEquals(1, oldF.feat.x, 1e-9);
    assertEquals(0, newF.feat.x, 1e-9);
    assertNotNull(s.featured());

    s.accept(payload("a4d5e6"), WALL + t, t);
    double lastOld = 1, lastNew = 0;
    for (int i = 0; i < 60; i++) {
      t += 33;
      s.frame(WALL + t, t, BOTTOM, PITCH, BAND);
      assertTrue(oldF.feat.x <= lastOld + 1e-9 && newF.feat.x >= lastNew - 1e-9, "monotone");
      lastOld = oldF.feat.x;
      lastNew = newF.feat.x;
      if (i == 5) {
        assertTrue(oldF.feat.x > 0 && oldF.feat.x < 1, "mid-move, not an instant swap");
        assertTrue(oldF.slot.x < BOTTOM && oldF.slot.x > BAND, "old one slides down the screen");
      }
    }
    assertEquals(0, oldF.feat.x, 0.01);
    assertEquals(1, newF.feat.x, 0.01);
    assertEquals(newF, s.featured());
  }

  @Test
  void aLateRouteFadesInAndAnUnchangedFieldDoesNot() throws Exception {
    Scene s = new Scene();
    Model first = payload("a1b2c3");
    first.ac.get(1).d = null;
    first.ac.get(1).o = null;
    s.accept(first, WALL, 0);
    long t = run(s, 0, 1500);
    Scene.Craft c = s.crafts.get(first.ac.get(1).id);
    assertEquals("", c.route.cur);
    assertEquals(1, c.alt.in(t), 1e-6);

    s.accept(payload("a1b2c3"), WALL + t, t);
    assertFalse(c.route.cur.isEmpty());
    assertEquals(0, c.route.in(t), 1e-6, "starts transparent");
    assertEquals(0, c.route.out(t), 1e-6, "nothing to fade out");
    assertEquals(1, c.route.in(t + Scene.FIELD_MS), 1e-6);
    assertEquals(1, c.alt.in(t), 1e-6, "altitude text did not change");
  }

  /** The normal-4 aircraft with an extra copy of the first one under a new id. */
  static Model withArrival(String ft, java.util.List<String> keep, String newId) throws Exception {
    JSONObject o = new JSONObject(Files.readString(Path.of("fixtures/payload/normal-4.json")));
    JSONArray all = o.getJSONArray("ac"), kept = new JSONArray();
    for (int i = 0; i < all.length(); i++)
      if (keep.contains(all.getJSONObject(i).getString("id"))) kept.put(all.get(i));
    JSONObject extra = new JSONObject(all.getJSONObject(0).toString());
    extra.put("id", newId).put("cs", "NEW123");
    kept.put(extra);
    o.put("ac", kept).put("ft", ft);
    return Model.parse(o.toString(), WALL);
  }

  @Test
  void anArrivalTakingADepartingRowsPlaceDoesNotShiftTheOtherRows() throws Exception {
    Scene s = new Scene();
    s.accept(payload("a1b2c3"), WALL, 0);
    long t = run(s, 0, 2500);
    assertEquals(3, s.listOrder.size());
    Scene.Craft top = s.listOrder.get(0), mid = s.listOrder.get(1), bottom = s.listOrder.get(2);
    double topSlot = top.slot.x, midSlot = mid.slot.x;

    // The bottom row is replaced by a new aircraft in the same payload.
    s.accept(
        withArrival("a1b2c3", java.util.List.of("a1b2c3", top.id, mid.id), "n3w000"), WALL + t, t);
    for (int i = 0; i < 90; i++) {
      t += 33;
      s.frame(WALL + t, t, BOTTOM, PITCH, BAND);
      assertEquals(topSlot, top.slot.x, 1e-6, "top row never moves");
      assertEquals(midSlot, mid.slot.x, 1e-6, "middle row never moves");
    }
    assertFalse(bottom.present);
    assertEquals(0, bottom.listA, 1e-6, "the old row has faded out");
    assertEquals(1, s.crafts.get("n3w000").listA, 1e-6);
    assertEquals(3, s.listOrder.size());
  }

  @Test
  void theArrowSlotClosesForLevelAircraftAndOpensSmoothly() throws Exception {
    Scene s = new Scene();
    s.accept(payload("a4d5e6"), WALL, 0);
    long t = run(s, 0, 1500);
    Scene.Craft level = s.crafts.get("0c7f81"), climbing = s.crafts.get("a1b2c3");
    assertEquals(0, level.arrow.x, 1e-9, "level: no slot");
    assertEquals(1, climbing.arrow.x, 1e-9, "descending: slot open");
    assertEquals(-1, climbing.trendDir);

    Model next = payload("a4d5e6");
    for (Model.Ac a : next.ac) if (a.id.equals("0c7f81")) a.vr = 8; // starts climbing
    s.accept(next, WALL + t, t);
    t = run(s, t + 33, 100);
    assertTrue(level.arrow.x > 0 && level.arrow.x < 1, "opening, not instant");
    run(s, t + 33, 2000);
    assertEquals(1, level.arrow.x, 0.01);
    assertEquals(1, level.trendDir);
  }

  @Test
  void theDrawnHeadingTurnsToANewTrackTheShortWayRoundInsteadOfSnapping() throws Exception {
    Scene s = new Scene();
    Model first = payload("a1b2c3");
    first.ac.get(0).trk = 350;
    s.accept(first, WALL, 0);
    s.frame(WALL, 0, BOTTOM, PITCH, BAND);
    Scene.Craft c = s.crafts.get("a1b2c3");
    assertEquals(350, c.heading, 1e-3, "the first fix is drawn as it is");
    Model next = payload("a1b2c3");
    next.ac.get(0).trk = 10;
    s.accept(next, WALL + 100, 100);
    s.frame(WALL + 100, 100, BOTTOM, PITCH, BAND);
    assertTrue(c.heading > 350 && c.heading < 356, "a part of the turn, not a snap to 10");
    s.frame(WALL + 233, 233, BOTTOM, PITCH, BAND);
    assertTrue(c.heading > 354 || c.heading < 10, "turns through north, not back through 180");
    run(s, 266, 4000);
    assertEquals(10, c.heading, 0.5);
  }
}
