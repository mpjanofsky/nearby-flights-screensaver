// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * adsb.im route-by-callsign: far closer to real positions than adsbdb's. A request carries only the
 * callsign. An unknown callsign is a 200 with the string "unknown", which is a miss. It has no hex
 * lookup, so {@link MergedHexLookup} pairs it with adsbdb and adsb.lol for that.
 */
public final class AdsbImRoutes implements Lookup {
  private static final String BASE = "https://adsb.im/api/0/route/";
  private final String userAgent;

  public AdsbImRoutes(String userAgent) {
    this.userAgent = userAgent;
  }

  @Override
  public Info aircraft(String hex) {
    throw new UnsupportedOperationException("adsb.im has no hex lookup");
  }

  @Override
  public Info route(String callsign) throws IOException {
    String body = Http.get(BASE + callsign, userAgent, "adsb.im");
    return body == null ? null : parseRoute(body);
  }

  /** Longest chain shown in full; a longer one is shortened to its first and last airport. */
  static final int MAX_SHOWN = 4;

  /**
   * Pure. Null for "unknown" or fewer than two airports. A multi-stop callsign (a flight number
   * flown out and back, or reused along a chain of legs) comes back as the whole chain: origin is
   * the first airport and destination the rest joined with arrows, with every airport kept in
   * {@link Info#stops}. {@link Enricher} narrows it to the aircraft's own leg when it can.
   */
  static Info parseRoute(String body) throws IOException {
    JSONObject r;
    try {
      r = new JSONObject(body);
    } catch (JSONException e) {
      throw new IOException("adsb.im response is not JSON", e);
    }
    JSONArray ap = r.optJSONArray("_airports");
    if (ap == null || ap.length() < 2) return null;
    int n = ap.length();
    String[] code = new String[n];
    double[] path = new double[2 * n];
    for (int i = 0; i < n; i++) {
      JSONObject o = ap.optJSONObject(i);
      if (o == null || !o.has("lat")) return null;
      code[i] = airport(o);
      if (code[i] == null) return null;
      path[2 * i] = o.optDouble("lat", Double.NaN);
      path[2 * i + 1] = o.optDouble("lon", Double.NaN);
    }
    java.util.List<String> shown = new java.util.ArrayList<>();
    if (n <= MAX_SHOWN) shown.addAll(java.util.Arrays.asList(code));
    else {
      shown.add(code[0]);
      shown.add(code[n - 1]);
    }
    String rest = String.join(" \u2192 ", shown.subList(1, shown.size()));
    String airline = r.optString("airline_code", "");
    Info info =
        new Info(
            null,
            null,
            airline.isEmpty() || airline.equals("unknown") ? null : airline,
            null, // adsb.im has no airline name; Airlines.name() fills it from the ICAO code
            shown.get(0),
            rest,
            path[0],
            path[1],
            path[2 * n - 2],
            path[2 * n - 1]);
    return n > 2 ? info.withStops(code, path) : info;
  }

  /** IATA, else ICAO, matching what adsbdb showed. */
  private static String airport(JSONObject o) {
    for (String k : new String[] {"iata", "icao"}) {
      String v = o.isNull(k) ? "" : o.optString(k, "").trim();
      if (!v.isEmpty()) return v;
    }
    return null;
  }
}
