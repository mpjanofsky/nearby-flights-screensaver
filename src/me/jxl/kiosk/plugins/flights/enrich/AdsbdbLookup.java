// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * adsbdb.com. Requests carry only a hex id or a callsign, never the home location. A 404 is how it
 * says "unknown", which is routine for general aviation.
 */
public final class AdsbdbLookup implements Lookup {
  private static final String BASE = "https://api.adsbdb.com/v0/";
  private final String userAgent;

  public AdsbdbLookup(String userAgent) {
    this.userAgent = userAgent;
  }

  @Override
  public Info aircraft(String hex) throws IOException {
    String body = get(BASE + "aircraft/" + hex);
    return body == null ? null : parseAircraft(body);
  }

  @Override
  public Info route(String callsign) throws IOException {
    String body = get(BASE + "callsign/" + callsign);
    return body == null ? null : parseRoute(body);
  }

  @Override
  public Info airline(String icao) throws IOException {
    String body = get(BASE + "airline/" + icao);
    return body == null ? null : parseAirline(body, icao);
  }

  /** Pure. The response is a list of matches; the first with a name wins. Null when none. */
  static Info parseAirline(String body, String icao) throws IOException {
    try {
      JSONArray list = new JSONObject(body).optJSONArray("response");
      for (int i = 0; list != null && i < list.length(); i++) {
        String name = text(list.getJSONObject(i), "name");
        if (name != null) return new Info(null, null, icao, name, null, null);
      }
      return null;
    } catch (JSONException e) {
      throw new IOException("adsbdb response is not JSON", e);
    }
  }

  /** Pure. Null when the response has no aircraft object. */
  static Info parseAircraft(String body) throws IOException {
    JSONObject a = inner(body, "aircraft");
    if (a == null) return null;
    // The registered owner is the fallback airline for a flight whose callsign does not name one
    // (an airline tail flying as its own registration). Only a plain three-letter operator code
    // is trusted as a code (military records carry junk such as the type code). Without a code
    // the owner's name is kept only for a military or government owner, which is useful for an
    // aircraft with no callsign; a private owner's name is never shown.
    String flag = text(a, "registered_owner_operator_flag_code");
    if (flag != null && !flag.matches("[A-Z]{3}")) flag = null;
    return new Info(
        text(a, "registration"), text(a, "icao_type"), flag, ownerName(a, flag), null, null);
  }

  private static final java.util.regex.Pattern PUBLIC_OWNER =
      java.util.regex.Pattern.compile(
          "(?i)\\b(air force|navy|army|marine|coast guard|national guard|department of"
              + " defense)\\b");

  private static String ownerName(JSONObject a, String flag) {
    String owner = text(a, "registered_owner");
    if (owner == null || flag != null) return owner;
    return PUBLIC_OWNER.matcher(owner).find() ? owner : null;
  }

  /** Pure. Null when the response has no flightroute object. Airports use IATA, else ICAO. */
  static Info parseRoute(String body) throws IOException {
    JSONObject r = inner(body, "flightroute");
    if (r == null) return null;
    JSONObject al = r.optJSONObject("airline");
    JSONObject o = r.optJSONObject("origin"), d = r.optJSONObject("destination");
    return new Info(
        null,
        null,
        al == null ? null : text(al, "icao"),
        al == null ? null : text(al, "name"),
        airport(o),
        airport(d),
        o == null ? Double.NaN : o.optDouble("latitude", Double.NaN),
        o == null ? Double.NaN : o.optDouble("longitude", Double.NaN),
        d == null ? Double.NaN : d.optDouble("latitude", Double.NaN),
        d == null ? Double.NaN : d.optDouble("longitude", Double.NaN));
  }

  private static String airport(JSONObject o) {
    if (o == null) return null;
    String iata = text(o, "iata_code");
    return iata != null ? iata : text(o, "icao_code");
  }

  private static JSONObject inner(String body, String key) throws IOException {
    try {
      JSONObject resp = new JSONObject(body).optJSONObject("response");
      return resp == null ? null : resp.optJSONObject(key);
    } catch (JSONException e) {
      throw new IOException("adsbdb response is not JSON", e);
    }
  }

  private static String text(JSONObject o, String key) {
    String v = o.isNull(key) ? null : o.optString(key, null);
    return v == null || v.trim().isEmpty() ? null : v.trim();
  }

  private String get(String url) throws IOException {
    return Http.get(url, userAgent, "adsbdb");
  }
}
