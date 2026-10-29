package me.jxl.kiosk.plugins.flights.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeclutterTest {
  @Test
  void closePointsAreSeparatedButStayNearTheirTrueSpot() {
    float[][] in = {{100, 100}, {104, 100}};
    float[][] out = Declutter.spread(in, 40, 12);
    assertTrue(Math.hypot(out[0][0] - out[1][0], out[0][1] - out[1][1]) > 20);
    for (int i = 0; i < 2; i++)
      assertTrue(Math.hypot(out[i][0] - in[i][0], out[i][1] - in[i][1]) <= 12.01);
  }

  @Test
  void farPointsDoNotMove() {
    float[][] in = {{0, 0}, {300, 300}};
    float[][] out = Declutter.spread(in, 40, 12);
    assertEquals(0, out[0][0], 1e-6);
    assertEquals(300, out[1][1], 1e-6);
  }

  @Test
  void aLabelStepsAsideFromATakenOne() {
    Declutter.Labels l = new Declutter.Labels();
    float[] first = l.place(100, 100, 40, 60, 16, 352, 0);
    float[] second = l.place(100, 100, 40, 60, 16, 352, 1);
    assertEquals(0, first[0], 0); // right of the icon
    assertEquals(100 + 40 * 0.58f, first[1], 1e-3);
    boolean moved = second[0] != first[0] || Math.abs(second[2] - first[2]) >= 16;
    assertTrue(moved, "the second label must not sit on the first");
  }

  @Test
  void aLabelNearTheRightEdgeFlipsLeft() {
    Declutter.Labels l = new Declutter.Labels();
    float[] r = l.place(300, 100, 40, 60, 16, 352, 0);
    assertEquals(1, r[0], 0);
  }

  @Test
  void aLabelKeepsItsPlaceWhileThatSpotIsClear() {
    Declutter.Labels l = new Declutter.Labels();
    float[] fresh = l.place(100, 100, 40, 60, 16, 352, 0);
    assertEquals(0f, fresh[0]); // right side is the natural pick
    Declutter.Labels again = new Declutter.Labels();
    float[] kept = again.place(100, 100, 40, 60, 16, 352, 0, 1, 0);
    assertEquals(1f, kept[0], "stays on the left it had");
  }
}
