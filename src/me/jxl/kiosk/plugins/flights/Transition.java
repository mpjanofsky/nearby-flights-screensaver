// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What changed between two consecutive publishes, so the renderer can glide rows instead of
 * jumping. The page is sandboxed and recreated on every publish, so it cannot remember the last
 * layout; Java does, and states it (docs/payload.md, `pn`, `pv`, `gone`).
 *
 * <p>A slot is a place in the display: 0 is the featured band, 1.. are the list rows in order (the
 * featured aircraft is not among them). Mirrors the renderer's rule: the featured aircraft is `ft`
 * when it is listed, else the first row.
 */
final class Transition {
  /** The previous publish's rows, kept by the Engine to build the next transition. */
  static final class Snapshot {
    final List<Tracked> rows;
    final String featuredId;
    final long observedAtMs;

    Snapshot(List<Tracked> rows, String featuredId, long observedAtMs) {
      this.rows = rows;
      this.featuredId = effectiveFeatured(rows, featuredId);
      this.observedAtMs = observedAtMs;
    }
  }

  /** Rows in the previous list (not counting the featured aircraft). */
  final int previousListRows;

  /** Previous slot by hex; an aircraft that was not shown has no entry. */
  final Map<String, Integer> previousSlot = new HashMap<>();

  /**
   * Where the scope drew each still-shown aircraft just before this publish, minus where it will
   * draw it now, as {dx, dy} metres at `gen`. The renderer eases the difference out so a blip
   * glides to its fresh fix. Only aircraft that moved by a visible amount have an entry.
   */
  final Map<String, double[]> jump = new HashMap<>();

  /** Previous rows no longer shown, in their previous slot order. */
  final List<Tracked> gone = new ArrayList<>();

  private Transition(int previousListRows) {
    this.previousListRows = previousListRows;
  }

  /** Dead-reckoning stops after this long, as in the renderer (`DR_CAP_S` in lib.js). */
  private static final double DR_CAP_S = 60;

  /**
   * Smaller offsets are invisible on the scope; larger ones are a different aircraft or a long gap.
   */
  private static final double MIN_JUMP_M = 30, MAX_JUMP_M = 10_000;

  /**
   * Null when there is no previous publish to animate from. `genMs` and `observedAtMs` belong to
   * the publish being built.
   */
  static Transition between(Snapshot prev, List<Tracked> rows, long genMs, long observedAtMs) {
    if (prev == null) return null;
    int listRows = prev.rows.size() - (prev.featuredId == null ? 0 : 1);
    Transition t = new Transition(listRows);
    int slot = 1;
    List<Tracked> ordered = new ArrayList<>();
    for (Tracked r : prev.rows) {
      boolean featured = r.aircraft.hex.equals(prev.featuredId);
      t.previousSlot.put(r.aircraft.hex, featured ? 0 : slot++);
      ordered.add(r);
    }
    java.util.Set<String> now = new java.util.HashSet<>();
    Map<String, Tracked> before = new HashMap<>();
    for (Tracked r : prev.rows) before.put(r.aircraft.hex, r);
    for (Tracked r : rows) {
      now.add(r.aircraft.hex);
      Tracked old = before.get(r.aircraft.hex);
      if (old == null) continue;
      double[] was = reckon(old, genMs, prev.observedAtMs);
      double[] is = reckon(r, genMs, observedAtMs);
      double dx = was[0] - is[0], dy = was[1] - is[1], d = Math.hypot(dx, dy);
      if (d >= MIN_JUMP_M && d <= MAX_JUMP_M) t.jump.put(r.aircraft.hex, new double[] {dx, dy});
    }
    ordered.sort((a, b) -> t.previousSlot.get(a.aircraft.hex) - t.previousSlot.get(b.aircraft.hex));
    for (Tracked r : ordered) if (!now.contains(r.aircraft.hex)) t.gone.add(r);
    return t;
  }

  /**
   * Dead-reckoned {x, y} in metres at `genMs`: the same rule as the renderer's `position` (lib.js),
   * which Java must mirror to state what the previous page was showing.
   */
  private static double[] reckon(Tracked t, long genMs, long observedAtMs) {
    double age = Math.max(0, t.aircraft.positionAgeS + (genMs - observedAtMs) / 1000.0);
    double gs = t.aircraft.groundSpeedMs, trk = t.aircraft.trackDeg;
    if (Double.isNaN(gs) || Double.isNaN(trk)) return new double[] {t.x, t.y};
    double dt = Math.min(age, DR_CAP_S), rad = Math.toRadians(trk);
    return new double[] {t.x + gs * dt * Math.sin(rad), t.y + gs * dt * Math.cos(rad)};
  }

  static String effectiveFeatured(List<Tracked> rows, String featuredId) {
    if (rows.isEmpty()) return null;
    if (featuredId != null) {
      for (Tracked r : rows) if (r.aircraft.hex.equals(featuredId)) return featuredId;
    }
    return rows.get(0).aircraft.hex;
  }
}
