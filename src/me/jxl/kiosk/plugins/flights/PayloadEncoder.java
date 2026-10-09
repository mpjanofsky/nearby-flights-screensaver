// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import me.jxl.kiosk.plugins.flights.enrich.NNumber;
import me.jxl.kiosk.plugins.flights.source.Aircraft;

/**
 * Encodes payload v2 (docs/payload.md) as compact JSON with a fixed key order, so equal content
 * gives equal strings. Hand-written: a dozen lines beat a dependency, and org.json's key order
 * differs between the JVM and Android.
 */
public final class PayloadEncoder {
  private PayloadEncoder() {}

  /** Connection state at encode time. `fetchAgeS` is NaN when nothing has ever been fetched. */
  public static final class Health {
    final double fetchAgeS;
    final boolean stale;
    final String sourceId;

    public Health(double fetchAgeS, boolean stale, String sourceId) {
      this.fetchAgeS = fetchAgeS;
      this.stale = stale;
      this.sourceId = sourceId;
    }
  }

  /**
   * A payload with a one-line message and no aircraft, for a problem retrying cannot fix (invalid
   * settings). Not stale: the screen should say what is wrong, not count minutes.
   */
  public static String message(String text, long genMs) {
    // Hand-built, like the rest of the encoder: the device's org.json throws checked exceptions,
    // and `str` keeps `<` from closing the page's script tag.
    return "{\"v\":2,\"gen\":"
        + genMs
        + ",\"fa\":null,\"st\":false,\"src\":\"local\",\"r\":27780,\"u\":\"av\",\"ac\":[],\"ap\":[],\"msg\":"
        + str(text)
        + "}";
  }

  /**
   * The screen left behind while fetching is paused (screensaver hidden): an empty sky the renderer
   * shows as the quiet sky and never as stale, so the first frame after a wake is not old flights.
   */
  public static String idle(long genMs) {
    return "{\"v\":2,\"gen\":"
        + genMs
        + ",\"fa\":null,\"st\":false,\"src\":\"local\",\"r\":27780,\"u\":\"av\",\"ac\":[],\"ap\":[],\"idle\":true}";
  }

  /** `observedAtMs` is when the rows' positions were fetched; ages are relative to `genMs`. */
  public static String encode(
      List<Tracked> rows, Config c, Health h, long genMs, long observedAtMs) {
    return encode(rows, c, h, genMs, observedAtMs, null);
  }

  /** `featuredId` is the aircraft the top band shows; omitted when null. */
  public static String encode(
      List<Tracked> rows, Config c, Health h, long genMs, long observedAtMs, String featuredId) {
    return encode(rows, c, h, genMs, observedAtMs, featuredId, Collections.emptyList());
  }

  /** `airports` are the scope markers (already limited to the radius and to 8 entries). */
  public static String encode(
      List<Tracked> rows,
      Config c,
      Health h,
      long genMs,
      long observedAtMs,
      String featuredId,
      List<Airports.Marker> airports) {
    return encode(rows, c, h, genMs, observedAtMs, featuredId, airports, null);
  }

  /**
   * `change` describes how this differs from the previous publish (null: nothing to animate from).
   */
  static String encode(
      List<Tracked> rows,
      Config c,
      Health h,
      long genMs,
      long observedAtMs,
      String featuredId,
      List<Airports.Marker> airports,
      Transition change) {
    StringBuilder s = new StringBuilder(1024);
    s.append("{\"v\":2,\"gen\":").append(genMs);
    s.append(",\"fa\":").append(Double.isNaN(h.fetchAgeS) ? "null" : num(h.fetchAgeS, 1));
    s.append(",\"st\":").append(h.stale);
    s.append(",\"src\":").append(str(h.sourceId));
    s.append(",\"r\":").append(Math.round(c.radiusM));
    s.append(",\"u\":").append(str(c.units));
    if (c.clockFormat != null) s.append(",\"clock\":").append(str(c.clockFormat));
    if (featuredId != null) s.append(",\"ft\":").append(str(featuredId));
    if (change != null) s.append(",\"pn\":").append(change.previousListRows);
    s.append(",\"ac\":[");
    for (int i = 0; i < rows.size(); i++) {
      if (i > 0) s.append(',');
      aircraft(s, rows.get(i), genMs, observedAtMs, change, true);
    }
    s.append("],\"ap\":[");
    for (int i = 0; i < airports.size(); i++) {
      Airports.Marker m = airports.get(i);
      if (i > 0) s.append(',');
      s.append("{\"c\":").append(str(m.code));
      s.append(",\"x\":").append(Math.round(m.x)).append(",\"y\":").append(Math.round(m.y));
      s.append('}');
    }
    s.append(']');
    if (change != null && !change.gone.isEmpty()) {
      s.append(",\"gone\":[");
      for (int i = 0; i < change.gone.size(); i++) {
        if (i > 0) s.append(',');
        aircraft(s, change.gone.get(i), genMs, observedAtMs, change, false); // no trail: not drawn
      }
      s.append(']');
    }
    s.append('}');
    return s.toString();
  }

  private static void aircraft(
      StringBuilder s,
      Tracked t,
      long genMs,
      long observedAtMs,
      Transition change,
      boolean withTrail) {
    Aircraft a = t.aircraft;
    double ageS = a.positionAgeS + (genMs - observedAtMs) / 1000.0;
    s.append("{\"id\":").append(str(a.hex));
    s.append(",\"x\":").append(Math.round(t.x)).append(",\"y\":").append(Math.round(t.y));
    s.append(",\"a\":").append(num(Math.max(0, ageS), 1));
    if (change != null && change.previousSlot.containsKey(a.hex))
      s.append(",\"pv\":").append(change.previousSlot.get(a.hex));
    opt(s, "cs", a.callsign != null ? a.callsign : t.info.callsign);
    opt(s, "rg", registration(t));
    opt(s, "ty", a.type != null ? a.type : t.info.type);
    opt(s, "al", t.info.airlineIcao);
    opt(s, "an", t.info.airlineName);
    opt(s, "o", t.info.origin);
    opt(s, "d", t.info.destination);
    optNum(s, "alt", a.altitudeM, 0);
    optNum(s, "gs", a.groundSpeedMs, 1);
    optNum(s, "trk", a.trackDeg, 0);
    optNum(s, "vr", a.verticalRateMs, 1);
    if (!withTrail) {
      s.append('}');
      return;
    }
    s.append(",\"tr\":[");
    for (int i = 0; i < t.trail.size(); i++) {
      TrailStore.Point p = t.trail.get(i);
      if (i > 0) s.append(',');
      s.append('[')
          .append(num(Math.max(0, (genMs - p.tMs) / 1000.0), 1))
          .append(',')
          .append(Math.round(p.x))
          .append(',')
          .append(Math.round(p.y))
          .append(']');
    }
    s.append("]}");
  }

  private static void opt(StringBuilder s, String key, String v) {
    if (v != null) s.append(",\"").append(key).append("\":").append(str(v));
  }

  private static void optNum(StringBuilder s, String key, double v, int decimals) {
    if (!Double.isNaN(v) && !Double.isInfinite(v))
      s.append(",\"").append(key).append("\":").append(num(v, decimals));
  }

  /** Whole numbers print without a decimal point; others keep `decimals` places. */
  static String num(double v, int decimals) {
    if (decimals == 0) return Long.toString(Math.round(v));
    String t = String.format(Locale.US, "%." + decimals + "f", v);
    return t.indexOf('.') < 0 ? t : t.replaceAll("0+$", "").replaceAll("\\.$", "");
  }

  /** JSON string, with `<` escaped so a value can never close the page's script tag. */
  /**
   * Feed, then lookup, then (for a US hex) the N-number worked out from the address, so an aircraft
   * with nothing else still has a name. Display only: the general-aviation filter does not see it.
   */
  private static String registration(Tracked t) {
    if (t.aircraft.registration != null) return t.aircraft.registration;
    if (t.info.registration != null) return t.info.registration;
    return NNumber.fromHex(t.aircraft.hex);
  }

  static String str(String v) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < v.length(); i++) {
      char ch = v.charAt(i);
      switch (ch) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '<':
          b.append("\\u003c");
          break;
        default:
          if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
          else b.append(ch);
      }
    }
    return b.append('"').toString();
  }
}
