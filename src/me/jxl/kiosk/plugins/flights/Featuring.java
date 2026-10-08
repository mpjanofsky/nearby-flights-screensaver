// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Chooses the featured aircraft and keeps the list order calm. Plain distance ranking makes both
 * flicker when two aircraft are about equally far, so each has to earn a change:
 *
 * <ul>
 *   <li>The featured aircraft stays until it leaves, or another is clearly closer (under {@link
 *       #SWITCH_RATIO} of its distance) after {@link #MIN_HOLD_MS}, or {@link #MAX_HOLD_MS} passes
 *       and someone else is available.
 *   <li>The list keeps its order, newcomers slotting in by distance, and is fully re-sorted by
 *       distance at most every {@link #RESORT_MS}.
 * </ul>
 *
 * A "complete" aircraft (what the caller says has the data worth showcasing) is preferred for the
 * band: the closest complete one is featured, and an incomplete featured aircraft yields to one
 * after {@link #MIN_HOLD_MS} so its lookups get a chance first. When none is complete the closest
 * is featured anyway, so the band is never empty.
 *
 * <p>Time is passed in, never read, so tests control it.
 */
final class Featuring {
  static final long MIN_HOLD_MS = 20_000;
  static final long MAX_HOLD_MS = 90_000;
  static final long RESORT_MS = 30_000;
  static final double SWITCH_RATIO = 0.7;

  /** The ordered rows and the featured aircraft's id (null when there are none). */
  static final class Result {
    final List<Tracked> rows;
    final String featuredId;

    Result(List<Tracked> rows, String featuredId) {
      this.rows = rows;
      this.featuredId = featuredId;
    }
  }

  private List<String> order = new ArrayList<>();
  private boolean sorted; // false until the first full sort
  private long lastResortMs;
  private String featured;
  private long featuredSinceMs;

  /** `byDistance` is the selector's output, closest first. */
  Result apply(List<Tracked> byDistance, long nowMs) {
    return apply(byDistance, nowMs, t -> true);
  }

  Result apply(List<Tracked> byDistance, long nowMs, Predicate<Tracked> complete) {
    if (byDistance.isEmpty()) {
      order = new ArrayList<>();
      featured = null;
      return new Result(byDistance, null);
    }
    List<Tracked> rows = order(byDistance, nowMs);
    Tracked cur = find(byDistance, featured);
    Tracked closest =
        best(byDistance, complete, null); // the closest complete one, else the closest
    if (cur == null) {
      choose(closest, nowMs);
    } else {
      long held = nowMs - featuredSinceMs;
      if (held >= MAX_HOLD_MS && byDistance.size() > 1) {
        choose(best(byDistance, complete, cur), nowMs);
      } else if (held >= MIN_HOLD_MS
          && closest != cur
          && (closest.distM < cur.distM * SWITCH_RATIO
              || (!complete.test(cur) && complete.test(closest)))) {
        choose(closest, nowMs);
      }
    }
    return new Result(rows, featured);
  }

  /**
   * The closest (list is distance-ordered) complete aircraft other than `not`, else the closest.
   */
  private static Tracked best(List<Tracked> byDistance, Predicate<Tracked> complete, Tracked not) {
    Tracked fallback = null;
    for (Tracked t : byDistance) {
      if (t == not) continue;
      if (complete.test(t)) return t;
      if (fallback == null) fallback = t;
    }
    return fallback;
  }

  private void choose(Tracked t, long nowMs) {
    featured = t.aircraft.hex;
    featuredSinceMs = nowMs;
  }

  private List<Tracked> order(List<Tracked> byDistance, long nowMs) {
    List<Tracked> out = new ArrayList<>(byDistance.size());
    if (!sorted || nowMs - lastResortMs >= RESORT_MS) {
      sorted = true;
      lastResortMs = nowMs;
      out.addAll(byDistance);
    } else {
      for (String hex : order) {
        Tracked t = find(byDistance, hex);
        if (t != null) out.add(t);
      }
      for (Tracked t :
          byDistance) { // newcomers, closest first, each ahead of the first farther row
        if (find(out, t.aircraft.hex) != null) continue;
        int at = 0;
        while (at < out.size() && out.get(at).distM <= t.distM) at++;
        out.add(at, t);
      }
    }
    order = new ArrayList<>(out.size());
    for (Tracked t : out) order.add(t.aircraft.hex);
    return out;
  }

  private static Tracked find(List<Tracked> l, String hex) {
    if (hex == null) return null;
    for (Tracked t : l) if (t.aircraft.hex.equals(hex)) return t;
    return null;
  }
}
