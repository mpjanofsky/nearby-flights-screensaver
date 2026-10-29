// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the native view animates, with no drawing and no Android classes so a desktop JVM can test
 * it. Every aircraft is one persistent {@link Craft} that owns its scope position, list slot, band
 * role and fades; a new payload only retargets those, so nothing is ever swapped, only moved or
 * faded. Time is always passed in (wall clock for positions, the monotonic clock for animation),
 * and list geometry is in the page's layout units.
 */
final class Scene {
  /** Sweep period; the angle comes from the monotonic clock so a wall-clock step cannot jump it. */
  static final long SWEEP_MS = 15000;

  static final double POS_OMEGA = 5,
      SLOT_OMEGA = 6.5,
      ROLE_OMEGA = 7; // 1/s; settles in ~1 s, ~0.8 s
  static final double FADE_IN_MS = 1000, FADE_OUT_MS = 1500;
  static final double SCOPE_FADE_IN_MS = 1800; // blips arrive slower than rows, so they are noticed
  static final double LIST_FADE_START = 0.45; // the page's fade: hidden for the first 45 %
  static final long FIELD_MS = 300;
  static final double HEADING_MS = 400; // time constant of the icon turning to a new track

  /**
   * A critically damped spring: reaches its target without overshoot, velocity stays continuous.
   */
  static final class Spring {
    double x, v, target;

    void step(double dtMs) {
      step(dtMs, POS_OMEGA);
    }

    void step(double dtMs, double omega) {
      double t = dtMs / 1000, d = x - target, e = Math.exp(-omega * t), c = v + omega * d;
      x = target + (d + c * t) * e;
      v = (v - omega * c * t) * e;
    }
  }

  /** A text field that cross-fades when its value changes, so a late route does not pop in. */
  static final class Fader {
    String cur, prev = "";
    long at;

    Fader(String s, long tick) {
      cur = s == null ? "" : s;
      at = tick - 2 * FIELD_MS; // already settled
    }

    void set(String s, long tick) {
      if (s == null) s = "";
      if (s.equals(cur)) return;
      prev = cur;
      cur = s;
      at = tick;
    }

    /** Opacity of the current text, 0..1. */
    float in(long tick) {
      return (float) smooth((tick - at) / (double) FIELD_MS);
    }

    /** Opacity of the text being replaced; 0 when there was none or the fade is over. */
    float out(long tick) {
      return prev.isEmpty() ? 0 : 1 - in(tick);
    }
  }

  static final class Craft {
    final String id;
    Model.Ac ac; // latest data; kept after the aircraft leaves so it fades with its last text
    Model src; // the payload that ac came from, for dead-reckoning
    Model.Row row; // as of the last frame
    boolean present = true; // in the newest payload
    double p; // presence progress 0..1, moves with time
    double sp; // the blip's own presence progress: slower in than p
    float listA, scopeA; // opacity of its rows/band text and of its blip
    final Spring ex = new Spring(), ey = new Spring(); // easing offset, metres, target 0
    final Spring slot = new Spring(); // top of its list row, units; band position when not listed
    final Spring feat = new Spring(); // 0 listed .. 1 featured in the band

    /**
     * 1 while a climb/descent arrow takes its slot after the altitude, 0 when level (slot closed).
     */
    final Spring arrow = new Spring();

    int trendDir; // last non-level direction, so the arrow keeps its shape while it fades
    boolean slotInit;

    /** The scope's declutter nudge as a fraction of the scope size, eased toward its target. */
    float nudgeX, nudgeY;

    boolean nudgeInit;

    /**
     * The scope label's left edge and baseline-centre y in pixels, eased toward the declutter pick.
     */
    float labelX, labelY;

    boolean labelInit;

    /**
     * The label's last side (1 left, 0 right, -1 none) and line, so the pick has some hysteresis.
     */
    int labelSide = -1, labelStep;

    /** The heading drawn, degrees: eases to the track of each new fix instead of snapping. */
    float heading;

    boolean headingInit;

    /** For a row fading out beside a newcomer that took its place: it holds its slot, uncounted. */
    Craft partner;

    final Fader route, alt, sub;

    Craft(String id, Model.Ac ac, Model src, Model.Row r, long tick) {
      this.id = id;
      this.ac = ac;
      this.src = src;
      row = r;
      route = new Fader(Model.route(ac), tick);
      alt = new Fader(r.alt, tick);
      sub = new Fader(subText(r), tick);
      trendDir = r.trend;
      arrow.x = arrow.target = r.trend == 0 ? 0 : 1;
    }
  }

  /** Airline and type, the list row's second line. */
  static String subText(Model.Row r) {
    StringBuilder sb = new StringBuilder();
    if (r.ac.an != null && !r.ac.an.isEmpty()) sb.append(r.ac.an);
    if (!r.type.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(r.type);
    if (sb.length() == 0 && r.ac.unidentified()) return "Position only";
    return sb.toString();
  }

  final Map<String, Craft> crafts = new LinkedHashMap<>();

  /** Listed aircraft top to bottom, including ones fading out in place. */
  final List<Craft> listOrder = new ArrayList<>();

  Model model;
  String featId;
  private long lastTick = -1;

  static double smooth(double t) {
    t = Math.min(1, Math.max(0, t));
    return t * t * (3 - 2 * t);
  }

  static float sweepDeg(long tickMs) {
    return (float) ((tickMs % SWEEP_MS) / (double) SWEEP_MS * 360.0) - 90f;
  }

  /** The aircraft the band features now, or null. */
  Craft featured() {
    Craft c = featId == null ? null : crafts.get(featId);
    return c != null && c.present ? c : null;
  }

  /** A new payload: retarget everything, keeping what is drawn where it is. */
  void accept(Model m, long wall, long tick) {
    if (m == model) return;
    // Where each aircraft is drawn right now, before its data is replaced.
    Map<String, double[]> drawn = new HashMap<>();
    for (Craft c : crafts.values()) {
      double[] p = c.src.position(c.ac, wall);
      drawn.put(c.id, new double[] {p[0] + c.ex.x, p[1] + c.ey.x});
    }
    featId = m.featuredId();
    Set<String> inNew = new HashSet<>();
    for (Model.Ac a : m.ac) {
      inNew.add(a.id);
      Model.Row r = m.row(a, wall);
      Craft c = crafts.get(a.id);
      boolean fresh = c == null;
      if (fresh) {
        c = new Craft(a.id, a, m, r, tick);
        crafts.put(a.id, c);
        c.feat.x = a.id.equals(featId) ? 1 : 0;
      } else {
        double[] o = drawn.get(a.id);
        c.ex.x = o[0] - r.x;
        c.ey.x = o[1] - r.y;
        c.ac = a;
        c.src = m;
        c.row = r;
        c.route.set(Model.route(a), tick);
        c.alt.set(r.alt, tick);
        c.sub.set(subText(r), tick);
      }
      c.present = true;
      c.partner = null;
      c.feat.target = a.id.equals(featId) ? 1 : 0;
    }
    for (Craft c : crafts.values()) if (!inNew.contains(c.id)) c.present = false;

    // The list: payload order, with departing rows keeping their place until they have faded.
    List<Craft> next = new ArrayList<>();
    for (Model.Ac a : m.ac) if (!a.id.equals(featId)) next.add(crafts.get(a.id));
    // A newcomer takes the place of a row that is leaving at the same time, so the list does not
    // grow for a moment and shove the rows above it up and back. The leaving row holds its slot.
    Set<Craft> arrivals = new HashSet<>(next);
    arrivals.removeAll(listOrder);
    for (int i = 0; i < listOrder.size(); i++) {
      Craft c = listOrder.get(i);
      if (c.present || c.listA <= 0) continue;
      Craft best = null;
      for (Craft a : arrivals)
        if (best == null || Math.abs(next.indexOf(a) - i) < Math.abs(next.indexOf(best) - i))
          best = a;
      if (best != null) {
        arrivals.remove(best);
        c.partner = best;
      } else {
        next.add(Math.min(i, next.size()), c);
      }
    }
    listOrder.clear();
    listOrder.addAll(next);
    model = m;
  }

  /**
   * Advances every spring and fade to {@code tick} and refreshes each row. {@code bottom} and
   * {@code pitch} place the list (bottom aligned) and {@code bandTop} is where a featured
   * aircraft's row form sits, all in layout units.
   */
  void frame(long wall, long tick, double bottom, double pitch, double bandTop) {
    frame(wall, tick, bottom, pitch, bandTop, false);
  }

  /**
   * As above; with {@code fromTop} the list hangs from {@code anchor} (its first row's top) instead
   * of standing on it, as in the portrait layouts.
   */
  void frame(long wall, long tick, double anchor, double pitch, double bandTop, boolean fromTop) {
    double bottom = anchor;
    double dt = lastTick < 0 ? 0 : Math.max(0, tick - lastTick);
    lastTick = tick;
    for (Craft c : crafts.values()) {
      c.row = c.src.row(c.ac, wall);
      c.p = c.present ? Math.min(1, c.p + dt / FADE_IN_MS) : Math.max(0, c.p - dt / FADE_OUT_MS);
      c.listA = (float) smooth((c.p - LIST_FADE_START) / (1 - LIST_FADE_START));
      c.sp = c.present ? Math.min(1, c.sp + dt / SCOPE_FADE_IN_MS) : Math.min(c.sp, c.p);
      c.scopeA = (float) smooth(c.sp);
      if (!Double.isNaN(c.ac.trk)) {
        if (!c.headingInit) {
          c.heading = (float) c.ac.trk;
          c.headingInit = true;
        } else {
          double delta =
              ((c.ac.trk - c.heading + 540) % 360 + 360) % 360 - 180; // shortest way round
          c.heading = (float) ((c.heading + delta * (1 - Math.exp(-dt / HEADING_MS)) + 360) % 360);
        }
      }
      if (c.row.trend != 0) c.trendDir = c.row.trend;
      c.arrow.target = c.row.trend == 0 ? 0 : 1;
      c.arrow.step(dt, ROLE_OMEGA);
      c.arrow.x = Math.min(1, Math.max(0, c.arrow.x));
      c.ex.step(dt);
      c.ey.step(dt);
      if (c.present) {
        c.feat.step(dt, ROLE_OMEGA);
        c.feat.x = Math.min(1, Math.max(0, c.feat.x));
      }
    }
    // A row that has faded out collapses: drop it so the rows below slide up together.
    for (int i = listOrder.size() - 1; i >= 0; i--) {
      Craft c = listOrder.get(i);
      if (!c.present && c.listA <= 0) listOrder.remove(i);
    }
    for (Craft c : crafts.values()) if (!c.present && c.listA <= 0) c.partner = null;
    int n = listOrder.size();
    Set<Craft> listed = new HashSet<>(listOrder);
    for (Craft c : crafts.values()) {
      double target = bandTop;
      if (listed.contains(c))
        target =
            fromTop
                ? anchor + listOrder.indexOf(c) * pitch
                : bottom - (n - listOrder.indexOf(c)) * pitch + 6;
      else if (isFadingInPlace(c)) target = c.slot.target;
      if (!c.slotInit) {
        c.slot.x = target;
        c.slotInit = true;
      }
      c.slot.target = target;
      c.slot.step(dt, SLOT_OMEGA);
    }
    Set<String> gone = new HashSet<>();
    for (Craft c : crafts.values()) if (!c.present && c.p <= 0) gone.add(c.id);
    crafts.keySet().removeAll(gone);
  }

  /** True while the aircraft is a list row: placed in the list, or fading where it was. */
  boolean isListed(Craft c) {
    return listOrder.contains(c) || isFadingInPlace(c);
  }

  private static boolean isFadingInPlace(Craft c) {
    return c.partner != null && !c.present;
  }

  /** Aircraft for the scope, highest last so the high ones draw on top. */
  List<Craft> byAltitudeDescending() {
    List<Craft> out = new ArrayList<>(crafts.values());
    Collections.sort(
        out,
        (p, q) ->
            Double.compare(
                Double.isNaN(q.ac.alt) ? -1 : q.ac.alt, Double.isNaN(p.ac.alt) ? -1 : p.ac.alt));
    return out;
  }

  /** How many aircraft the newest payload holds. */
  int presentCount() {
    int n = 0;
    for (Craft c : crafts.values()) if (c.present) n++;
    return n;
  }
}
