package me.jxl.kiosk.plugins.flights.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The quiet-sky scene is a pure function of the clock, shaped as the page's drift() is. */
class SkySimTest {
  @Test
  void eachRoundOfFiveHoldsEverySceneOnceAndNeverRepeatsBackToBack() {
    int prev = -1;
    for (int rd = 0; rd < 200; rd++) {
      boolean[] seen = new boolean[5];
      for (int i = 0; i < 5; i++) {
        int k = SkySim.sceneAt(rd * 5L + i);
        assertFalse(seen[k]);
        seen[k] = true;
        assertNotEquals(prev, k);
        prev = k;
      }
    }
  }

  @Test
  void sameClockSameFrame() {
    SkySim.Frame a = SkySim.at(123456789L, 480), b = SkySim.at(123456789L, 480);
    assertEquals(a.px, b.px);
    assertEquals(a.trail.size(), b.trail.size());
    assertEquals(a.clouds.size(), b.clouds.size());
  }

  @Test
  void cloudOnlySceneHasNoPlaneAndThreeOrFourClouds() {
    for (long idx = 0; idx < 100; idx++) {
      if (SkySim.sceneAt(idx) != 4) continue;
      SkySim.Frame f = SkySim.at(idx * SkySim.SCENE_MS + 1000, 480);
      assertFalse(f.plane);
      assertTrue(f.clouds.size() >= 3 && f.clouds.size() <= 4);
      return;
    }
  }

  @Test
  void planeCrossesTheWholePanelAndTrailStaysBehindIt() {
    long idx = 0;
    while (SkySim.sceneAt(idx) == 4) idx++;
    long t0 = idx * SkySim.SCENE_MS;
    SkySim.Frame start = SkySim.at(t0, 480), end = SkySim.at(t0 + SkySim.SCENE_MS - 1, 480);
    assertTrue(start.plane && end.plane);
    assertTrue(Math.abs(start.px - end.px) > 400, "crosses most of the width");
    SkySim.Frame mid = SkySim.at(t0 + 20000, 480);
    assertEquals(70, mid.trail.size());
    assertTrue(mid.trail.get(0).alpha > mid.trail.get(69).alpha);
    assertTrue(SkySim.at(t0 + 100, 480).trail.size() < 70, "trail grows from nothing");
  }

  @Test
  void nothingPopsAtASceneChange() {
    for (long idx = 0; idx < 30; idx++) {
      long edge = (idx + 1) * SkySim.SCENE_MS;
      for (long t : new long[] {edge - 1, edge}) {
        SkySim.Frame f = SkySim.at(t, 480);
        for (SkySim.Cloud c : f.clouds) assertTrue(c.alpha < 0.002, "cloud at " + t);
        for (SkySim.Dot d : f.trail) assertTrue(d.alpha < 0.02, "trail at " + t);
      }
    }
  }

  @Test
  void planeFadesBeforeThePanelEdgeAndKeepsFullStrengthInTheMiddle() {
    boolean sawDive = false;
    for (long idx = 0; idx < 100; idx++) {
      if (SkySim.sceneAt(idx) == 4) continue;
      for (long ms = 0; ms < SkySim.SCENE_MS; ms += 100) {
        SkySim.Frame f = SkySim.at(idx * SkySim.SCENE_MS + ms, 480);
        if (f.py <= 0 || f.py >= SkySim.H) assertEquals(0, f.planeAlpha, 1e-9);
        if (f.py > 25 && f.py < SkySim.H - 25) assertEquals(1, f.planeAlpha, 1e-9);
        if (f.py >= SkySim.H) sawDive = true;
      }
    }
    assertTrue(sawDive, "some scene leaves through the bottom");
  }
}
