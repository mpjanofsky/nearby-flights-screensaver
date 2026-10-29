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
    String id, name, cs, rg, ty, al, an, o, d;
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
