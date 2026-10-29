// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import java.util.ArrayList;
import java.util.List;

/**
 * The scope's declutter, ported from {@code spread} in assets/flights/lib.js and the label search
 * in render.js: close aircraft are nudged apart (by a little, so an icon stays on its true spot),
 * and each label takes the nearest clear place beside its icon. Pure arithmetic, so it is tested.
 */
final class Declutter {
  private Declutter() {}

  /**
   * Pushes points closer than {@code gap} apart, each staying within {@code max} of where it was.
   */
  static float[][] spread(float[][] pts, float gap, float max) {
    int n = pts.length;
    float[][] out = new float[n][];
    for (int i = 0; i < n; i++) out[i] = new float[] {pts[i][0], pts[i][1]};
    for (int it = 0; it < 24; it++) {
      for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
          float[] a = out[i], b = out[j];
          float dx = b[0] - a[0], dy = b[1] - a[1], d = (float) Math.hypot(dx, dy);
          if (d >= gap) continue;
          if (d < 1e-6f) {
            double t = (i * 2.399 + j) % (2 * Math.PI);
            dx = (float) Math.cos(t);
            dy = (float) Math.sin(t);
            d = 1;
          }
          float push = (gap - (float) Math.hypot(b[0] - a[0], b[1] - a[1])) / 2 + 0.01f;
          a[0] -= dx / d * push;
          a[1] -= dy / d * push;
          b[0] += dx / d * push;
          b[1] += dy / d * push;
        }
      }
      for (int k = 0; k < n; k++) {
        float dx = out[k][0] - pts[k][0], dy = out[k][1] - pts[k][1];
        float d = (float) Math.hypot(dx, dy);
        if (d > max) {
          out[k][0] = pts[k][0] + dx / d * max;
          out[k][1] = pts[k][1] + dy / d * max;
        }
      }
    }
    return out;
  }

  /** Label boxes already taken, and the search for a clear place for the next one. */
  static final class Labels {
    private static final float PAD = 6;
    private final List<float[]> placed = new ArrayList<>(); // x0, x1, y, h, own (-1 none)

    /** A box from {@code x0} to {@code x1}, centred on {@code y}, half-height {@code h}. */
    void block(float x0, float x1, float y, float h, int own) {
      placed.add(new float[] {x0, x1, y, h, own});
    }

    /**
     * Where the label of aircraft {@code own} goes. Returns {leftFlag (1 = text ends at x), x, y}:
     * the usual side first (right of the icon, left near the right edge), then the other, each at
     * the natural height and then a line down or up; the nearest clear spot wins, else the natural
     * one. The result is recorded as taken.
     */
    float[] place(float x, float y, float iconBox, float lw, float lh, float size, int own) {
      return place(x, y, iconBox, lw, lh, size, own, -1, 0);
    }

    /**
     * As above, but a label that was at {@code prevSide} (1 left, 0 right, -1 none) and line {@code
     * prevStep} stays there while that spot is still clear, so it does not hop between two nearly
     * equal places as neighbours drift. Returns {leftFlag, x, y, step}.
     */
    float[] place(
        float x,
        float y,
        float iconBox,
        float lw,
        float lh,
        float size,
        int own,
        int prevSide,
        int prevStep) {
      float y0 = y - iconBox * 0.1f;
      boolean usual = x > size * 0.6f;
      boolean left = usual;
      float ly = y0;
      int chosen = 0;
      if (prevSide >= 0 && !hits(prevSide == 1, y0 + prevStep * lh, x, iconBox, lw, lh, own)) {
        left = prevSide == 1;
        ly = y0 + prevStep * lh;
        chosen = prevStep;
        return record(left, x, ly, iconBox, lw, lh, chosen);
      }
      search:
      for (int step : new int[] {0, 1, -1, 2, -2}) {
        for (int side = 0; side < 2; side++) {
          boolean lf = side == 0 ? usual : !usual;
          if (!hits(lf, y0 + step * lh, x, iconBox, lw, lh, own)) {
            left = lf;
            ly = y0 + step * lh;
            chosen = step;
            break search;
          }
        }
      }
      return record(left, x, ly, iconBox, lw, lh, chosen);
    }

    private float[] record(
        boolean left, float x, float ly, float iconBox, float lw, float lh, int step) {
      float lx = x + (left ? -1 : 1) * iconBox * 0.58f;
      placed.add(new float[] {left ? lx - lw : lx, left ? lx : lx + lw, ly, lh / 2, -1});
      return new float[] {left ? 1 : 0, lx, ly, step};
    }

    private boolean hits(
        boolean lf, float yy, float x, float iconBox, float lw, float lh, int own) {
      float lx = x + (lf ? -1 : 1) * iconBox * 0.58f;
      for (float[] b : placed) {
        if ((int) b[4] == own && own >= 0) continue;
        boolean apart =
            lf ? lx + PAD < b[0] || lx - lw - PAD > b[1] : lx + lw + PAD < b[0] || lx - PAD > b[1];
        if (!apart && Math.abs(yy - b[2]) < lh / 2 + (b[3] > 0 ? b[3] : lh / 2)) return true;
      }
      return false;
    }
  }
}
