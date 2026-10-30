// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The payload (docs/payload.md) parsed for the native view, plus the lib.js view-model maths. */
final class Model {
  static final double DR_CAP_S = 60;
  static final double STALE_FETCH_S = 120;
  static final double ALT_TOP_M = 12192;
  private static final String[] CARD = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

  static final class Ac {
    String id,
        name,
        cs,
        rg,
        ty,
        al,
        an,
        o,
        d,
        oc,
        dc; // oc/dc: city names, only when the setting is on
    double x, y, age, gs = Double.NaN, trk = Double.NaN, alt = Double.NaN, vr = Double.NaN;
    double[][] trail = new double[0][];

    boolean unidentified() {
      return id.startsWith("~");
    }
  }

  /** One aircraft as the list, band and scope show it right now (lib.js makeRow). */
  static final class Row {
    Ac ac;
    double x, y, dist, brg, over;
    boolean stale, inRange;
    String name, type, alt, distText;
    int trend; // -1 down, 0 level, 1 up
  }

  long gen;
  double r = 15 * 1852, fa = Double.NaN;
  String u = "av", src = "net", clock, msg, ft;
  boolean stale, idle, radarBottom;
  int textPct = 100, maxRows = 4;
  List<Ac> ac = new ArrayList<>();
  List<Object[]> ap = new ArrayList<>(); // {code, x, y}

  /** Weather (payload `wx`): condition or null, degrees C (NaN: none), shown in F when wxF. */
  String wxCond;

  double wxTempC = Double.NaN;
  boolean wxF;

  /** Pieces to show (payload `wx.sh`; absent: temperature, condition word and icon, no wind). */
  boolean wxShowTemp = true, wxShowWord = true, wxShowIcon = true, wxShowWind, wxShowDir;

  /** Wind (payload `wx.w`, `wd`, `wu`): m/s and degrees it blows from (NaN: none), unit to show. */
  double wxWindMs = Double.NaN, wxWindDeg = Double.NaN;

  String wxWindUnit;

  static Model parse(String json, long receivedMs) throws JSONException {
    JSONObject o = new JSONObject(json);
    Model m = new Model();
    m.gen = o.optLong("gen", receivedMs);
    m.r = o.optDouble("r", m.r);
    m.fa = o.isNull("fa") ? Double.NaN : o.optDouble("fa", Double.NaN);
    m.u = o.optString("u", "av");
    m.src = o.optString("src", "net");
    m.clock = o.has("clock") ? o.optString("clock") : null;
    m.textPct = o.optInt("ts", 100);
    m.maxRows = o.optInt("mr", 4);
    m.radarBottom = "b".equals(o.optString("rp", ""));
    m.stale = o.optBoolean("st");
    m.idle = o.optBoolean("idle");
    m.msg = o.has("msg") ? o.optString("msg") : null;
    m.ft = o.has("ft") ? o.optString("ft") : null;
    JSONObject wx = o.optJSONObject("wx");
    if (wx != null && wx.has("t")) {
      m.wxTempC = wx.optDouble("t", Double.NaN);
      m.wxCond = wx.has("c") ? wx.optString("c") : null;
      m.wxF = "F".equals(wx.optString("tu"));
      if (wx.has("sh")) {
        String sh = wx.optString("sh");
        m.wxShowTemp = sh.indexOf('t') >= 0;
        m.wxShowWord = sh.indexOf('c') >= 0;
        m.wxShowIcon = sh.indexOf('i') >= 0;
        m.wxShowWind = sh.indexOf('w') >= 0;
        m.wxShowDir = sh.indexOf('d') >= 0;
      }
      if (wx.has("w") && wx.has("wu")) {
        m.wxWindMs = wx.optDouble("w", Double.NaN);
        m.wxWindDeg = wx.has("wd") ? wx.optDouble("wd", Double.NaN) : Double.NaN;
        m.wxWindUnit = wx.optString("wu");
      }
    }
    JSONArray ac = o.optJSONArray("ac");
    for (int i = 0; ac != null && i < ac.length(); i++) {
      JSONObject j = ac.getJSONObject(i);
      Ac a = new Ac();
      a.id = j.optString("id");
      a.cs = str(j, "cs");
      a.rg = str(j, "rg");
      a.ty = str(j, "ty");
      a.al = str(j, "al");
      a.an = str(j, "an");
      a.o = str(j, "o");
      a.d = str(j, "d");
      a.oc = str(j, "oc");
      a.dc = str(j, "dc");
      a.name = a.cs != null ? a.cs : a.rg != null ? a.rg : a.id.toUpperCase();
      a.x = j.optDouble("x");
      a.y = j.optDouble("y");
      a.age = j.optDouble("a", 0);
      a.gs = j.optDouble("gs", Double.NaN);
      a.trk = j.optDouble("trk", Double.NaN);
      a.alt = j.isNull("alt") ? Double.NaN : j.optDouble("alt", Double.NaN);
      a.vr = j.isNull("vr") ? Double.NaN : j.optDouble("vr", Double.NaN);
      JSONArray tr = j.optJSONArray("tr");
      a.trail = new double[tr == null ? 0 : tr.length()][];
      for (int k = 0; k < a.trail.length; k++) {
        JSONArray t = tr.getJSONArray(k);
        a.trail[k] = new double[] {t.getDouble(1), t.getDouble(2)};
      }
      m.ac.add(a);
    }
    JSONArray ap = o.optJSONArray("ap");
    for (int i = 0; ap != null && i < ap.length(); i++) {
      JSONObject j = ap.getJSONObject(i);
      m.ap.add(new Object[] {j.optString("c"), j.optDouble("x"), j.optDouble("y")});
    }
    return m;
  }

  private static String str(JSONObject j, String k) {
    return j.isNull(k) || !j.has(k) ? null : j.optString(k);
  }

  String featuredId() {
    if (ac.isEmpty()) return null;
    for (Ac a : ac) if (a.id.equals(ft)) return ft;
    return ac.get(0).id;
  }

  /** Dead-reckoned position, metres E/N of home, at wall-clock {@code nowMs}. */
  double[] position(Ac a, long nowMs) {
    double age = Math.max(0, a.age + (nowMs - gen) / 1000.0);
    double dt = Double.isNaN(a.gs) || Double.isNaN(a.trk) ? 0 : Math.min(age, DR_CAP_S);
    double rad = Math.toRadians(Double.isNaN(a.trk) ? 0 : a.trk);
    double gs = Double.isNaN(a.gs) ? 0 : a.gs;
    return new double[] {a.x + gs * dt * Math.sin(rad), a.y + gs * dt * Math.cos(rad)};
  }

  Row row(Ac a, long nowMs) {
    double age = Math.max(0, a.age + (nowMs - gen) / 1000.0);
    double[] p = position(a, nowMs);
    Row w = new Row();
    w.ac = a;
    w.x = p[0];
    w.y = p[1];
    w.stale = age > DR_CAP_S;
    w.over = Math.max(0, age - DR_CAP_S);
    w.dist = Math.hypot(p[0], p[1]);
    w.brg = (Math.toDegrees(Math.atan2(p[0], p[1])) + 360) % 360;
    w.inRange = w.dist <= r;
    w.name = a.name;
    w.type = Fmt.typeLabel(a.ty, 24);
    String alt = Fmt.alt(a.alt, u);
    w.alt = alt == null ? "" : alt;
    w.trend = Double.isNaN(a.vr) || Math.abs(a.vr) < 1.5 ? 0 : a.vr > 0 ? 1 : -1;
    w.distText = Fmt.dist(w.dist, u) + " " + CARD[(int) Math.round(w.brg / 45) % 8];
    return w;
  }

  List<Row> rows(long nowMs) {
    List<Row> out = new ArrayList<>();
    for (Ac a : ac) out.add(row(a, nowMs));
    return out;
  }

  /** lib.js status: kind is "nodata", "msg", "stale", "empty" or "ok". */
  String[] status(long nowMs) {
    if (msg != null) return new String[] {"msg", msg};
    if (idle) return new String[] {"empty", "Looking for aircraft…"};
    double fetchAge = Double.isNaN(fa) ? Double.POSITIVE_INFINITY : fa + (nowMs - gen) / 1000.0;
    if (stale || fetchAge > STALE_FETCH_S) {
      long min = Math.max(1, Math.round(fetchAge / 60));
      return new String[] {
        "stale", Double.isInfinite(fetchAge) ? "No data yet" : "No new data · " + min + " min"
      };
    }
    if (ac.isEmpty()) {
      return new String[] {"empty", "No aircraft within " + Fmt.dist(r, u).replace(".0 ", " ")};
    }
    return new String[] {"ok", ""};
  }

  /** lib.js routeText: "ORG → DST", "ORG → " when only the origin is known, else null. */
  static String route(Ac a) {
    if (a.o != null && a.d != null) return a.o + " → " + a.d;
    return a.o != null ? a.o + " → " : null;
  }

  /** Text width in the caller's current paint; lets {@link #routeToShow} stay free of Android. */
  interface Measure {
    float width(String s);
  }

  /** What to draw for a route: the text and the share of the normal size to draw it at. */
  static final class RouteFit {
    final String text;
    final float scale;

    RouteFit(String text, float scale) {
      this.text = text;
      this.scale = scale;
    }
  }

  /** The smallest a city route is drawn, as a share of the normal size; below it the codes show. */
  static final float ROUTE_MIN_SCALE = 0.85f;

  /**
   * The route as city names ("London → Málaga") when the payload has them: at full size when it
   * fits in `maxW`, else shrunk to fit, down to {@link #ROUTE_MIN_SCALE}; below that, `codeRoute`
   * at full size. Only the route that matches the aircraft's current codes is swapped; a fading
   * previous route stays as the codes it was drawn with. `size` is the text size in pixels, for the
   * arrows, which are drawn rather than typed; `m` measures text at that size.
   */
  static RouteFit routeToShow(Ac a, String codeRoute, float size, float maxW, Measure m) {
    RouteFit codes = new RouteFit(codeRoute, 1f);
    if ((a.oc == null && a.dc == null) || !codeRoute.equals(route(a))) return codes;
    String city =
        (a.oc != null ? a.oc : a.o) + " → " + (a.d == null ? "" : a.dc != null ? a.dc : a.d);
    if (maxW <= 0) return new RouteFit(city, 1f);
    String[] parts = city.split(" → ", -1);
    float w = (parts.length - 1) * ARROW_EM * size;
    for (String part : parts) w += m.width(part);
    float scale = Math.min(1f, maxW / w); // text and arrows both grow with the size
    return scale >= ROUTE_MIN_SCALE ? new RouteFit(city, scale) : codes;
  }

  /**
   * The list row's second line, "airline · type", fitted to `maxW` without losing the type: the
   * full type name if that fits, else the type code, else the airline name cut with an ellipsis.
   * Rows with only one of the two are returned as they are (the caller's ellipsis handles those).
   */
  static String subToShow(Row r, float maxW, Measure m) {
    String an = r.ac.an == null ? "" : r.ac.an;
    String code = r.ac.ty == null ? "" : r.ac.ty;
    String full = Scene.subText(r);
    if (an.isEmpty() || r.type.isEmpty() || maxW <= 0 || m.width(full) <= maxW) return full;
    String byCode = an + " · " + code;
    if (m.width(byCode) <= maxW) return byCode;
    String tail = " · " + code;
    int n = an.length();
    while (n > 4 && m.width(an.substring(0, n) + "…" + tail) > maxW) n--;
    return an.substring(0, n).trim() + "…" + tail;
  }

  /** Width of one drawn arrow with its spacing, in text sizes (see ScopeView.route). */
  static final float ARROW_EM = 1.55f;

  /** lib.js spotlight facts line (the part after the callsign and route). */
  static String facts(Row row) {
    Ac a = row.ac;
    List<String> rest = new ArrayList<>();
    if (a.rg != null) rest.add(a.rg);
    rest.add(row.distText);
    if (!row.alt.isEmpty()) rest.add(row.alt);
    List<String> withAirline = new ArrayList<>();
    if (a.an != null) withAirline.add(a.an);
    withAirline.addAll(rest);
    int room = 68 - String.join(" · ", withAirline).length() - 3 - (row.trend == 0 ? 0 : 2);
    List<String> who = new ArrayList<>();
    if (a.an != null) who.add(a.an);
    else if (a.unidentified()) who.add("Position only");
    String t = Fmt.typeLabel(a.ty, room);
    if (!t.isEmpty()) who.add(t);
    List<String> all = new ArrayList<>(who);
    all.addAll(rest);
    return String.join(" · ", all);
  }
}
