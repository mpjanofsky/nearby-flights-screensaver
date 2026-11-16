// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * adsb.im routes by callsign: far closer to real positions than adsbdb's. Uses its batch endpoint
 * (POST /api/0/routeset), which the API description asks feeders to use: callsigns are grouped by
 * the {@link Enricher} for at least 30 s per request. A request carries only callsigns. An unknown
 * callsign is a null or "unknown" entry, which is a miss. It has no hex lookup, so {@link
 * MergedHexLookup} pairs it with adsbdb and adsb.lol for that.
 */
public final class AdsbImRoutes implements Lookup {
  // Plain http on purpose: adsb.im's API description asks for it ("only available over http and
  // not https to reduce server load") and answers 403 to the per-callsign GET. Only callsigns go
  // out (no lat/lng, no location), so there is nothing private to protect on the wire.
  static final String URL = "http://adsb.im/api/0/routeset";
  private static final int TIMEOUT_MS = 15_000;
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
    return routes(java.util.Collections.singletonList(callsign)).get(callsign);
  }

  @Override
  public java.util.Map<String, Info> routes(java.util.List<String> callsigns) throws IOException {
    java.util.Map<String, Info> out = new java.util.HashMap<>();
    if (callsigns.isEmpty()) return out;
    String body = Http.postJson(URL, requestBody(callsigns), userAgent, "adsb.im", TIMEOUT_MS);
    if (body == null) throw new IOException("adsb.im routeset not found");
    parseRoutes(body, callsigns, out);
    return out;
  }

  /** Pure. The JSON for a batch: callsigns only, never a position. */
  static String requestBody(java.util.List<String> callsigns) {
    JSONArray planes = new JSONArray();
    for (String cs : callsigns) {
      try {
        planes.put(new JSONObject().put("callsign", cs));
      } catch (JSONException e) {
        throw new IllegalStateException(e); // a plain string key and value cannot fail
      }
    }
    try {
      return new JSONObject().put("planes", planes).toString();
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Pure. The answer is an array in the order asked. A length mismatch means we cannot tell which
   * answer belongs to which callsign, so it fails the whole batch (the caller backs off).
   */
  static void parseRoutes(
      String body, java.util.List<String> asked, java.util.Map<String, Info> out)
      throws IOException {
    JSONArray arr;
    try {
      arr = new JSONArray(body);
    } catch (JSONException e) {
      throw new IOException("adsb.im routeset response is not a JSON array", e);
    }
    if (arr.length() != asked.size()) {
      throw new IOException("adsb.im routeset answered " + arr.length() + " for " + asked.size());
    }
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i); // null or "unknown" is a miss
      if (o == null) continue;
      Info info = parseRoute(o);
      if (info != null) out.put(asked.get(i), info);
    }
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
    return parseRoute(r);
  }

  /** As {@link #parseRoute(String)}, for one entry of a batch answer. */
  static Info parseRoute(JSONObject r) {
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
