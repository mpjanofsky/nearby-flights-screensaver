// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.Map;

/**
 * Turns the host's settings map into a Config and source choices, or an error to show the
 * installer.
 */
public final class PluginSettings {
  public static final String LOCAL = "Local feed",
      NET = "adsb.lol",
      BOTH = "Local + adsb.lol",
      NONE = "None";

  public final Config config;
  public final String primary, fallback, localUrl;
  public final long refreshMs;

  /** Null when the settings are usable; otherwise a message safe to show (no values echoed). */
  public final String error;

  private PluginSettings(
      Config config,
      String primary,
      String fallback,
      String localUrl,
      long refreshMs,
      String error) {
    this.config = config;
    this.primary = primary;
    this.fallback = fallback;
    this.localUrl = localUrl;
    this.refreshMs = refreshMs;
    this.error = error;
  }

  public static PluginSettings parse(Map<String, Object> m) {
    Double lat = coord(m.get("homeLat"), 90), lon = coord(m.get("homeLon"), 180);
    String primary = str(m.get("primarySource"), LOCAL),
        fallback = str(m.get("fallbackSource"), NET);
    String url = str(m.get("localUrl"), "").trim();
    String err = null;
    if (BOTH.equals(primary)) {
      // Both feeds are asked every cycle, so there is nothing left to fall back to. Without a
      // local URL it is simply adsb.lol.
      fallback = NONE;
      if (!url.startsWith("http")) primary = NET;
    }
    if (lat == null || lon == null || (lat == 0 && lon == 0))
      err = "Set your home latitude and longitude";
    else if ((LOCAL.equals(primary) || LOCAL.equals(fallback)) && !url.startsWith("http")) {
      // A missing local URL is only fatal when nothing else can supply data.
      if (LOCAL.equals(primary) && (NONE.equals(fallback) || LOCAL.equals(fallback)))
        err = "Set the local feed URL (http://...)";
      if (LOCAL.equals(primary) && NET.equals(fallback)) primary = NET; // run on adsb.lol alone
      if (LOCAL.equals(fallback)) fallback = NONE;
    }
    if (primary.equals(fallback)) fallback = NONE;
    double radiusNm = num(m.get("radiusNm"), 15, 5, 50);
    Config.Widen widen = widenMode(str(m.get("widenMode"), "Hard"));
    double maxRadiusNm = num(m.get("maxRadiusNm"), 60, 5, 250);
    String units = unitsCode(str(m.get("units"), "Aviation"));
    Config c =
        new Config(
            lat == null ? 0 : lat,
            lon == null ? 0 : lon,
            radiusNm * 1852.0,
            units,
            (int) num(m.get("maxRows"), 4, 1, 4),
            Boolean.TRUE.equals(m.get("excludeGa")),
            widen,
            maxRadiusNm * 1852.0,
            Boolean.TRUE.equals(m.get("demoQuiet")),
            clockCode(str(m.get("clockFormat"), "Device default")));
    return new PluginSettings(
        c, primary, fallback, url, (long) num(m.get("refreshSec"), 10, 5, 60) * 1000L, err);
  }

  private static String clockCode(String label) {
    if ("12 hour".equals(label)) return "12";
    if ("24 hour".equals(label)) return "24";
    return null;
  }

  private static Config.Widen widenMode(String label) {
    return "Soft".equals(label) ? Config.Widen.SOFT : Config.Widen.HARD;
  }

  private static String unitsCode(String label) {
    if ("Metric".equals(label)) return "met";
    if ("Imperial".equals(label)) return "imp";
    return "av";
  }

  private static Double coord(Object o, double limit) {
    try {
      double v = Double.parseDouble(String.valueOf(o).trim());
      return Math.abs(v) <= limit ? v : null;
    } catch (NumberFormatException e) {
      return null; // empty or typo: reported as the "set your home" message, without the value
    }
  }

  private static String str(Object o, String dflt) {
    return o instanceof String && !((String) o).isEmpty() ? (String) o : dflt;
  }

  private static double num(Object o, double dflt, double lo, double hi) {
    double v = o instanceof Number ? ((Number) o).doubleValue() : dflt;
    return Math.max(lo, Math.min(hi, v));
  }
}
