// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

/**
 * Where everything goes, in layout units, for one screen size and the text, rows and radar-position
 * settings. A port of {@code layout()} in assets/flights/render.js and the grid rules in style.css,
 * kept free of Android so it can be tested; private/scripts/measure-layout.mjs measures the HTML
 * page for the expected values.
 *
 * <p>One unit is {@code u} screen pixels. Three shapes by aspect ratio (width / height): landscape
 * (at least 1.2), squarish (0.7 to 1.2, text at most 110 %) and tall.
 */
final class Layout {
  enum Shape {
    LAND,
    SQ,
    TALL
  }

  static final double PAD = 16, GAP = 8, FOOTER_H = 22, ROW_GAP = 6, SHIFT_U = 16, SCOPE_MIN = 260;

  final Shape shape;

  /** Screen pixels per unit. */
  final double u;

  /** The screen in units. */
  final double wU, hU;

  /**
   * Text scale: the setting times the aspect bonus (at most 1.3), and the clock's half-strength
   * one.
   */
  final double ts, tsc;

  /** List row height in units; a row takes {@code rowH + ROW_GAP} with its gap. */
  final double rowH;

  /** Left edge and width of everything that spans the screen (band, footer, portrait list). */
  double left, inner;

  /** The band box (the clock on its right in landscape and squarish, on top in tall). */
  double bandY, bandH;

  double scopeX, scopeY, scopeSize;

  /** Where the list sits: a left column (landscape) or full width (portrait). */
  double listX, listW;

  /**
   * Landscape lists are bottom aligned to {@code listBottom}; portrait lists start at {@code
   * listTop}.
   */
  boolean listFromTop;

  double listTop, listBottom;
  double footerY;

  private Layout(Shape shape, double u, double wU, double hU, double ts, double tsc, double rowH) {
    this.shape = shape;
    this.u = u;
    this.wU = wU;
    this.hU = hU;
    this.ts = ts;
    this.tsc = tsc;
    this.rowH = rowH;
  }

  double pitch() {
    return rowH + ROW_GAP;
  }

  boolean portrait() {
    return shape != Shape.LAND;
  }

  /**
   * @param wPx screen width in pixels, @param hPx height
   * @param tsPct the Text size setting (percent), @param mr the Rows setting (1 to 4)
   * @param radarBottom the Radar position setting "Bottom" (portrait shapes only)
   */
  static Layout of(double wPx, double hPx, int tsPct, int mr, boolean radarBottom) {
    double ts = tsPct / 100.0;
    mr = Math.max(1, Math.min(4, mr));
    double a = wPx / hPx;
    Shape shape = a >= 1.2 ? Shape.LAND : a >= 0.7 && ts <= 1.1 ? Shape.SQ : Shape.TALL;
    double px, v = 1, bonus = 0, rowH = 74, scope;
    if (shape == Shape.LAND) {
      px = Math.min(hPx / 480, wPx / 960);
      double hu = hPx / px;
      v = Math.max(1, Math.min(1.8, hu / 480));
      bonus = (v - 1) * 0.15;
      if (v > 1.001) {
        double avail = hu - 32 - 88 * (1 + (v - 1) * 0.25) - 8 - 34;
        rowH = Math.max(74, Math.min(74 * 1.5, (avail - (mr - 1) * 6) / mr));
      }
      scope = 352 * (1 + (v - 1) * 0.25);
    } else {
      double dw = (shape == Shape.SQ ? 600 : 540) * (1 + (ts - 1) * 0.4);
      double rows = mr * 80 - 6;
      double fixed = (shape == Shape.SQ ? 190 : 255) * (1 + (ts - 1) * 0.6) + rows + SHIFT_U;
      px = Math.min(wPx / dw, hPx / (fixed + SCOPE_MIN));
      double spare = hPx / px - fixed;
      scope = Math.max(SCOPE_MIN, Math.min(Math.min(spare, wPx / px - 32), 520));
      rowH = 74 * (1 + Math.min(0.25, Math.max(0, spare - scope) / (mr * 74)));
    }
    double eff = Math.max(ts, Math.min(ts * (1 + bonus), 1.3));
    Layout l = new Layout(shape, px, wPx / px, hPx / px, eff, 1 + (eff - 1) * 0.5, rowH);
    l.arrange(ts, radarBottom, scope, 1 + (v - 1) * 0.25);
    return l;
  }

  private void arrange(double tsSetting, boolean radarBottom, double scope, double g) {
    footerY = hU - PAD - FOOTER_H;
    bandY = PAD;
    scopeSize = scope;
    if (shape == Shape.LAND) {
      double cw = Math.min(wU, 1000);
      left = (wU - cw) / 2 + PAD;
      inner = cw - 2 * PAD;
      bandH = 88 * g;
      double top = bandY + bandH + GAP;
      scopeX = left + inner - scopeSize;
      scopeY = top + (hU - PAD - top - scopeSize) / 2;
      listX = left;
      listW = inner - scopeSize - 24;
      listFromTop = false;
      listBottom = footerY - ROW_GAP;
      listTop = top;
      return;
    }
    left = PAD;
    inner = wU - 2 * PAD;
    // Tall: a fixed band (clock above the aircraft). Squarish: as tall as its text, measured on the
    // HTML page (105.1 units at 100 %, 108.8 at 105 %, 113.7 at 110 %; the line is a fit within
    // 0.6).
    bandH = shape == Shape.TALL ? 193 + (tsSetting - 1) * 130 : 105.1 + (tsSetting - 1) * 86;
    double below = bandY + bandH + GAP;
    scopeX = (wU - scopeSize) / 2;
    if (radarBottom) {
      scopeY = footerY - GAP - scopeSize;
      listTop = below;
    } else {
      scopeY = below;
      listTop = below + scopeSize + GAP;
    }
    listX = left;
    listW = inner;
    listFromTop = true;
    listBottom = footerY - ROW_GAP;
  }
}
