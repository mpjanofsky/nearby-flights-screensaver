// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.jxl.kiosk.plugins.flights.Geo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reads the readsb aircraft structure from either a local tar1090/readsb feed
 * (`/data/aircraft.json`, array `aircraft`) or adsb.lol's `/v2/point` (array `ac`). The two differ
 * in envelope key and in `now` units (seconds vs ms), so `now` is never compared with the device
 * clock: ages are measured from the device clock at arrival, plus the per-aircraft `seen_pos`. A
 * LAN feed's `now` and `messages` are only watched for progress (see {@link FeedWatch}).
 */
public final class ReadsbJsonSource implements SourceAdapter {
  private static final double FT_M = 0.3048, KT_MS = 1852.0 / 3600.0, FPM_MS = FT_M / 60.0;
  private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
  private static final int TIMEOUT_MS = 8000;

  private final String id;
  private final String localUrl; // null for adsb.lol
  private final String userAgent;
  // Only a LAN feed is watched: the server answering says nothing about the receiver behind it.
  private final FeedWatch watch = new FeedWatch();

  private ReadsbJsonSource(String id, String localUrl, String userAgent) {
    this.id = id;
    this.localUrl = localUrl;
    this.userAgent = userAgent;
  }

  /** A LAN feed. The URL points at `aircraft.json`; the location never leaves the LAN. */
  public static ReadsbJsonSource local(String aircraftJsonUrl, String userAgent) {
    return new ReadsbJsonSource("local", aircraftJsonUrl, userAgent);
  }

  /** adsb.lol. The query carries a rounded location (see {@link #adsbLolUrl}). */
  public static ReadsbJsonSource adsbLol(String userAgent) {
    return new ReadsbJsonSource("net", null, userAgent);
  }

  @Override
  public String id() {
    return id;
  }

  /**
   * Home rounded to 2 decimals (~1 km) and the radius padded by 1 nm, capped at the API's 250 nm.
   */
  static String adsbLolUrl(double homeLat, double homeLon, double radiusM) {
    long nm = Math.min(250, (long) Math.ceil(radiusM / 1852.0) + 1);
    return String.format(
        Locale.US, "https://api.adsb.lol/v2/point/%.2f/%.2f/%d", homeLat, homeLon, nm);
  }

  @Override
  public FetchResult fetch(double homeLat, double homeLon, double radiusM) throws SourceException {
    String url = localUrl != null ? localUrl : adsbLolUrl(homeLat, homeLon, radiusM);
    String body = get(url);
    long nowMs = System.currentTimeMillis();
    FetchResult r = parse(body, nowMs, homeLat, homeLon, radiusM);
    if (localUrl != null) watch.check(r.feedNow, r.feedMessages, nowMs);
    return r;
  }

  /** Pure: normalise a response body and keep aircraft with a position inside the radius. */
  static FetchResult parse(
      String body, long observedAtMs, double homeLat, double homeLon, double radiusM)
      throws SourceException {
    JSONArray array;
    double feedNow, feedMessages;
    try {
      JSONObject root = new JSONObject(body);
      array = root.has("aircraft") ? root.getJSONArray("aircraft") : root.getJSONArray("ac");
      feedNow = root.optDouble("now", Double.NaN);
      feedMessages = root.optDouble("messages", Double.NaN);
    } catch (JSONException e) {
      // The message names the problem, not the body: a body can hold location-bearing data.
      throw new SourceException("response is not a readsb aircraft list", e, 0);
    }
    List<Aircraft> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject o = array.optJSONObject(i);
      if (o == null || !o.has("lat") || !o.has("lon") || !o.has("hex"))
        continue; // no position fix yet
      double lat = o.optDouble("lat", Double.NaN), lon = o.optDouble("lon", Double.NaN);
      if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
      if (Geo.distanceM(homeLat, homeLon, lat, lon) > radiusM) continue;
      Object alt = o.opt("alt_baro");
      boolean ground = "ground".equals(alt);
      double altM = alt instanceof Number ? ((Number) alt).doubleValue() * FT_M : Double.NaN;
      double rate =
          o.has("baro_rate")
              ? o.optDouble("baro_rate", Double.NaN)
              : o.optDouble("geom_rate", Double.NaN);
      double age = o.has("seen_pos") ? o.optDouble("seen_pos", 0) : o.optDouble("seen", 0);
      out.add(
          new Aircraft(
              o.optString("hex"),
              text(o, "flight"),
              text(o, "r"),
              text(o, "t"),
              text(o, "category"),
              lat,
              lon,
              altM,
              o.optDouble("gs", Double.NaN) * KT_MS,
              o.optDouble("track", Double.NaN),
              rate * FPM_MS,
              age,
              ground));
    }
    return new FetchResult(out, observedAtMs, feedNow, feedMessages);
  }

  private static String text(JSONObject o, String key) {
    String s = o.optString(key, "").trim();
    return s.isEmpty() ? null : s;
  }

  private String get(String url) throws SourceException {
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL(url).openConnection();
      c.setConnectTimeout(TIMEOUT_MS);
      c.setReadTimeout(TIMEOUT_MS);
      c.setRequestProperty("User-Agent", userAgent);
      c.setRequestProperty("Accept", "application/json");
      int code = c.getResponseCode();
      if (code != 200) {
        int retry = parseRetryAfter(c.getHeaderField("Retry-After"));
        throw new SourceException(id + " source answered HTTP " + code, null, retry);
      }
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) > 0; ) {
          buf.write(chunk, 0, n);
          if (buf.size() > MAX_BODY_BYTES) throw new SourceException(id + " response too large");
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      // Class name only: IOException messages can embed the request URL, which carries a location.
      throw new SourceException(
          id + " source unreachable (" + e.getClass().getSimpleName() + ")", null, 0);
    } finally {
      if (c != null) c.disconnect();
    }
  }

  static int parseRetryAfter(String header) {
    if (header == null) return 0;
    try {
      return Math.max(0, Math.min(3600, Integer.parseInt(header.trim())));
    } catch (NumberFormatException e) {
      return 0; // an HTTP-date form: ignore it and let the normal backoff apply
    }
  }
}
