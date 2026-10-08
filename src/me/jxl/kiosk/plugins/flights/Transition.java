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

    Snapshot(List<Tracked> rows, String featuredId) {
      this.rows = rows;
      this.featuredId = effectiveFeatured(rows, featuredId);
    }
  }

  /** Rows in the previous list (not counting the featured aircraft). */
  final int previousListRows;

  /** Previous slot by hex; an aircraft that was not shown has no entry. */
  final Map<String, Integer> previousSlot = new HashMap<>();

  /** Previous rows no longer shown, in their previous slot order. */
  final List<Tracked> gone = new ArrayList<>();

  private Transition(int previousListRows) {
    this.previousListRows = previousListRows;
  }

  /** Null when there is no previous publish to animate from. */
  static Transition between(Snapshot prev, List<Tracked> rows) {
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
    for (Tracked r : rows) now.add(r.aircraft.hex);
    ordered.sort((a, b) -> t.previousSlot.get(a.aircraft.hex) - t.previousSlot.get(b.aircraft.hex));
    for (Tracked r : ordered) if (!now.contains(r.aircraft.hex)) t.gone.add(r);
    return t;
  }

  static String effectiveFeatured(List<Tracked> rows, String featuredId) {
    if (rows.isEmpty()) return null;
    if (featuredId != null) {
      for (Tracked r : rows) if (r.aircraft.hex.equals(featuredId)) return featuredId;
    }
    return rows.get(0).aircraft.hex;
  }
}
