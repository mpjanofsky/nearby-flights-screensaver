// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * adsb.lol's per-aircraft lookup, used only for what a local receiver often lacks: the callsign.
 * The request carries just the hex id, never the home location. Registration and type come along
 * for free. A 404, or an empty list, is a miss.
 */
public final class AdsbLolHex implements Lookup {
  private static final String BASE = "https://api.adsb.lol/v2/hex/";
  private final String userAgent;

  public AdsbLolHex(String userAgent) {
    this.userAgent = userAgent;
  }

  @Override
  public Info aircraft(String hex) throws IOException {
    String body = Http.get(BASE + hex, userAgent, "adsb.lol");
    return body == null ? null : parse(body);
  }

  @Override
  public Info route(String callsign) {
    throw new UnsupportedOperationException("adsb.lol is used for hex lookups only");
  }

  /** Pure. Null when no aircraft is listed. */
  static Info parse(String body) throws IOException {
    JSONArray ac;
    try {
      ac = new JSONObject(body).optJSONArray("ac");
    } catch (JSONException e) {
      throw new IOException("adsb.lol response is not JSON", e);
    }
    if (ac == null || ac.length() == 0) return null;
    JSONObject a = ac.optJSONObject(0);
    if (a == null) return null;
    return new Info(text(a, "r"), text(a, "t"), null, null, null, null)
        .withCallsign(text(a, "flight"));
  }

  private static String text(JSONObject o, String key) {
    String v = o.isNull(key) ? null : o.optString(key, null);
    return v == null || v.trim().isEmpty() ? null : v.trim();
  }
}
