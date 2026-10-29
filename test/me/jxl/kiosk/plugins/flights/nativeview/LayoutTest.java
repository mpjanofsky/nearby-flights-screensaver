package me.jxl.kiosk.plugins.flights.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Expected values are the HTML page's boxes in units (private/scripts/measure-layout.mjs). */
class LayoutTest {
  static final double EPS = 0.6; // the page rounds to 0.1 and its band height follows its text

  @Test
  void landscapeShow5() {
    Layout l = Layout.of(960, 480, 100, 4, false);
    assertEquals(Layout.Shape.LAND, l.shape);
    assertEquals(1, l.u, 1e-9);
    assertEquals(88, l.bandH, EPS);
    assertEquals(592, l.scopeX, EPS);
    assertEquals(112, l.scopeY, EPS);
    assertEquals(352, l.scopeSize, EPS);
    assertEquals(436, l.listBottom, EPS); // the third row ends at 436 (362 + 74)
    assertEquals(442, l.footerY, EPS);
    assertFalse(l.listFromTop);
    assertEquals(74, l.rowH, 1e-9);
  }

  @Test
  void tallPortrait() {
    Layout l = Layout.of(540, 960, 100, 4, false);
    assertEquals(Layout.Shape.TALL, l.shape);
    assertEquals(193, l.bandH, EPS);
    assertEquals(375, l.scopeSize, EPS);
    assertEquals(82.5, l.scopeX, EPS);
    assertEquals(217, l.scopeY, EPS);
    assertEquals(600, l.listTop, EPS);
    assertEquals(922, l.footerY, EPS);
    assertTrue(l.listFromTop);
  }

  @Test
  void tallPortraitRadarBottom() {
    Layout l = Layout.of(540, 960, 100, 4, true);
    assertEquals(217, l.listTop, EPS);
    assertEquals(539, l.scopeY, EPS);
  }

  @Test
  void tallPortraitBigText() {
    Layout l = Layout.of(540, 960, 130, 4, false);
    assertEquals(0.8929, l.u, 0.0005);
    assertEquals(232, l.bandH, EPS);
    assertEquals(444.3, l.scopeSize, EPS);
    assertEquals(256, l.scopeY, EPS);
    assertEquals(708.3, l.listTop, EPS);
    assertEquals(1.3, l.ts, 1e-9);
    assertEquals(1.15, l.tsc, 1e-9);
  }

  @Test
  void squarish() {
    Layout l = Layout.of(800, 800, 100, 4, false);
    assertEquals(Layout.Shape.SQ, l.shape);
    assertEquals(1.0256, l.u, 0.0005);
    assertEquals(105.1, l.bandH, EPS);
    assertEquals(260, l.scopeSize, EPS);
    assertEquals(129.1, l.scopeY, EPS);
    assertEquals(397, l.listTop, EPS);
  }

  @Test
  void squarishBandGrowsWithTextSize() {
    // Measured on the HTML page at 800 x 800 (private/scripts/measure-layout.mjs): 108.8 and 113.7.
    assertEquals(108.8, Layout.of(800, 800, 105, 4, false).bandH, 0.7);
    assertEquals(113.7, Layout.of(800, 800, 110, 4, false).bandH, 0.1);
  }

  @Test
  void squarishBigTextFallsBackToTall() {
    assertEquals(Layout.Shape.SQ, Layout.of(800, 800, 110, 4, false).shape);
    assertEquals(Layout.Shape.TALL, Layout.of(800, 800, 120, 4, false).shape);
  }

  @Test
  void fewerRowsGiveTheScopeMoreRoom() {
    assertTrue(
        Layout.of(540, 960, 100, 2, false).scopeSize
            > Layout.of(540, 960, 100, 4, false).scopeSize);
  }
}
