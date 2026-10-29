// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import java.util.ArrayList;
import java.util.List;

/**
 * The quiet-sky scene: a port of {@code sceneAt}/{@code drift} in render.js. Every 40 s a new
 * little scene (an airliner on one of four paths with a fading dotted trail, or clouds only),
 * derived from the clock alone so a rebuild resumes it. Pure arithmetic in layout units; {@link
 * ScopeView} draws the result.
 */
final class SkySim {
  static final long SCENE_MS = 40000;
  static final int N_SCENES = 5;

  /** The panel's height in layout units. */
  static final double H = 110;

  /**
   * Beyond the page: nothing pops at a hard edge. Clouds fade in and out over the first and last
   * {@code CLOUD_EDGE} of a scene, the trail evaporates over the last {@code TRAIL_OUT} (it is 42 %
   * of the path long, so cutting it at the scene change was a visible jump), and the plane and its
   * trail fade over the last {@code EDGE_UNITS} before the panel's top or bottom, where the clip
   * would otherwise cut a diving or climbing plane off against the title.
   */
  static final double CLOUD_EDGE = 0.10, TRAIL_OUT = 0.07, EDGE_UNITS = 18;

  /** A faint cloud: four circles (dx, dy, radius) around (x, y) scaled by {@code s}. */
  static final class Cloud {
    double x, y, s, alpha;
  }

  static final class Dot {
    double x, y, radius, alpha;
  }

  static final class Frame {
    final List<Cloud> clouds = new ArrayList<>();
    final List<Dot> trail = new ArrayList<>();
    boolean plane;
    double planeAlpha = 1; // 0..1, fades near the panel's top and bottom
    double px, py, angle; // radians, for a nose-up icon
  }

  /** The cloud's four lobes, as the page draws them in one path. */
  static final double[][] PUFF = {{0, 0, 16}, {18, -6, 20}, {40, 0, 15}, {22, 6, 17}};

  static double hash(double a, double b) {
    double x = Math.sin(a * 127.1 + b * 311.7 + 7.7) * 43758.5453;
    return x - Math.floor(x);
  }

  private static Integer[] order(long round) {
    Integer[] o = {0, 1, 2, 3, 4};
    java.util.Arrays.sort(o, (p, q) -> Double.compare(hash(round, p), hash(round, q)));
    return o;
  }

  /**
   * Scene kind for scene number {@code idx}: shuffled in rounds of five, never the same twice
   * running.
   */
  static int sceneAt(long idx) {
    long rd = idx / N_SCENES;
    Integer[] o = order(rd);
    if (rd > 0 && o[0].equals(order(rd - 1)[N_SCENES - 1])) {
      Integer t = o[0];
      o[0] = o[1];
      o[1] = t;
    }
    return o[(int) (idx % N_SCENES)];
  }

  /** Path position in the panel: fractions x, y plus an offset in units. */
  private static double[] path(int kind, double u, double a, double b) {
    switch (kind) {
      case 0:
        return new double[] {u, 0.3 + 0.4 * a, 0, 0};
      case 1:
        return new double[] {u, 0.3 + 0.4 * a + 0.2 * Math.sin(u * (4 + 3 * b) * Math.PI), 0, 0};
      case 2:
        {
          double s = Math.min(1, Math.max(0, (u - (0.3 + 0.2 * a)) / 0.22));
          double th = (s * s * (3 - 2 * s)) * 2 * Math.PI;
          return new double[] {u, 0.88, 30 * Math.sin(th), -30 * (1 - Math.cos(th))};
        }
      default:
        {
          double s = Math.max(0, (u - 0.45) / 0.55), q = 1.3 * s * s;
          return new double[] {u, b > 0.5 ? 0.8 - q : 0.2 + q, 0, 0};
        }
    }
  }

  /** The scene at wall-clock {@code now} (ms) in a panel {@code w} units wide. */
  static Frame at(long now, double w) {
    Frame f = new Frame();
    long idx = Math.floorDiv(now, SCENE_MS);
    int kind = sceneAt(idx);
    double u = (double) Math.floorMod(now, SCENE_MS) / SCENE_MS;
    double a = hash(idx, 1), b = hash(idx, 2);
    boolean flip = hash(idx, 3) < 0.5;
    int clouds =
        kind == 4 ? 3 + (int) Math.floor(hash(idx, 4) * 2) : (int) Math.floor(hash(idx, 4) * 4);
    for (int i = 0; i < clouds; i++) {
      double ph = (u + (double) i / clouds) % 1, al = Math.pow(Math.sin(Math.PI * ph), 2);
      Cloud c = new Cloud();
      c.s = 0.7 + 0.7 * hash(idx, 5 + i);
      c.x = 30 + ((i + hash(idx, 7 + i)) / clouds) * (w - 130) + (ph - 0.5) * 24;
      c.y = 20 + hash(idx, 9 + i) * 50;
      c.alpha = 0.24 * al * Scene.smooth(Math.min(u, 1 - u) / CLOUD_EDGE);
      f.clouds.add(c);
    }
    if (kind == 4) return f;
    double[] here = pt(kind, u, a, b, flip, w),
        before = pt(kind, Math.max(0, u - 0.002), a, b, flip, w);
    double[] after = pt(kind, Math.min(1, u + 0.002), a, b, flip, w);
    double tailOut = Scene.smooth((1 - u) / TRAIL_OUT);
    for (int i = 1; i <= 70; i++) {
      double v = u - i * 0.006;
      if (v < 0) break;
      double[] q = pt(kind, v, a, b, flip, w);
      double fd = 1 - i / 71.0;
      Dot d = new Dot();
      d.x = q[0];
      d.y = q[1];
      d.radius = 0.8 + 1.5 * fd;
      d.alpha = 0.55 * fd * fd * edge(q[1]) * tailOut;
      f.trail.add(d);
    }
    f.plane = true;
    f.planeAlpha = edge(here[1]);
    f.px = here[0];
    f.py = here[1];
    f.angle = Math.atan2(after[1] - before[1], after[0] - before[0]) + Math.PI / 2;
    return f;
  }

  /** 1 well inside the panel, 0 at its top or bottom edge. */
  private static double edge(double y) {
    return Scene.smooth(Math.min(y, H - y) / EDGE_UNITS);
  }

  private static double[] pt(int kind, double v, double a, double b, boolean flip, double w) {
    double[] q = path(kind, v, a, b);
    double fx = flip ? 1 - q[0] : q[0];
    return new double[] {-30 + fx * (w + 60) + (flip ? -q[2] : q[2]), 10 + q[1] * (H - 20) + q[3]};
  }
}
