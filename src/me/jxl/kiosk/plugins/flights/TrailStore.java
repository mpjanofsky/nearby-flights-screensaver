// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Short per-aircraft position history, kept in the home plane (metres), so no coordinates are
 * retained.
 */
public final class TrailStore {
  static final long KEEP_MS = 120_000;
  static final int MAX_POINTS = 8;

  /** A past position: observation time (epoch ms) and metres east/north of home. */
  public static final class Point {
    public final long tMs;
    public final double x, y;

    Point(long tMs, double x, double y) {
      this.tMs = tMs;
      this.x = x;
      this.y = y;
    }
  }

  private final Map<String, ArrayDeque<Point>> byHex = new HashMap<>();

  /**
   * Records an observation (ignored when it is not newer than the last one) and returns the trail.
   */
  List<Point> record(String hex, long tMs, double x, double y) {
    ArrayDeque<Point> q = byHex.get(hex);
    if (q == null) {
      q = new ArrayDeque<>();
      byHex.put(hex, q);
    }
    if (q.isEmpty() || tMs > q.peekLast().tMs) q.addLast(new Point(tMs, x, y));
    while (q.size() > MAX_POINTS || (!q.isEmpty() && tMs - q.peekFirst().tMs > KEEP_MS))
      q.removeFirst();
    return new ArrayList<>(q);
  }

  /** Drops aircraft that were not seen this round, so the map cannot grow without bound. */
  void retainOnly(java.util.Set<String> seen) {
    for (Iterator<String> it = byHex.keySet().iterator(); it.hasNext(); )
      if (!seen.contains(it.next())) it.remove();
  }
}
