// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.Map;
import me.jxl.kiosk.plugins.flights.weather.HaWeatherSource;

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

  /** The `weather.*` entity to show, or empty when "Show weather" is off or no entity is chosen. */
  public final String weatherEntity;

  /** Null when weather is fine or off; otherwise why the line will not show (no values echoed). */
  public final String weatherProblem;

  /** Weather pieces to show ({@link Weather#show} letters); never empty when an entity is set. */
  public final String weatherShow;

  /** Null when the settings are usable; otherwise a message safe to show (no values echoed). */
  public final String error;

  private PluginSettings(
      Config config,
      String primary,
      String fallback,
      String localUrl,
      long refreshMs,
      String weatherEntity,
      String weatherProblem,
      String weatherShow,
      String error) {
    this.config = config;
    this.primary = primary;
    this.fallback = fallback;
    this.localUrl = localUrl;
    this.refreshMs = refreshMs;
    this.weatherEntity = weatherEntity;
    this.weatherProblem = weatherProblem;
    this.weatherShow = weatherShow;
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
            clockCode(str(m.get("clockFormat"), "Device default")),
            (int) num(m.get("textSize"), 100, 80, 125),
            (int) num(m.get("featureHoldSec"), 90, 10, 300),
            "Bottom".equals(str(m.get("radarPosition"), "Top")),
            Boolean.TRUE.equals(m.get("cityNames")));
    String entity = str(m.get("weatherEntity"), "").trim(), wxProblem = null;
    String wxShow =
        (flag(m.get("weatherTemp"), true) ? "t" : "")
            + conditionPieces(str(m.get("weatherConditions"), "Icon and word"))
            + windPieces(str(m.get("weatherWind"), "Off"));
    if (!Boolean.TRUE.equals(m.get("showWeather"))) {
      entity = "";
    } else if (wxShow.isEmpty()) {
      entity = "";
      wxProblem = "Show weather is on but temperature, conditions and wind are all off";
    } else if (entity.isEmpty()) {
      wxProblem = "Show weather is on but no weather entity is chosen";
    } else if (!HaWeatherSource.valid(entity)) {
      entity = "";
      wxProblem = "The weather entity must be a weather.* entity";
    }
    return new PluginSettings(
        c,
        primary,
        fallback,
        url,
        (long) num(m.get("refreshSec"), 10, 5, 60) * 1000L,
        entity,
        wxProblem,
        wxShow,
        err);
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

  /** The `Conditions` select as {@link Weather#show} letters: c (word), i (icon). */
  private static String conditionPieces(String choice) {
    switch (choice) {
      case "Word only":
        return "c";
      case "Icon only":
        return "i";
      case "Off":
        return "";
      default: // "Icon and word", and anything unrecognised (a value from another version)
        return "ci";
    }
  }

  /** The `Wind` select as {@link Weather#show} letters: w (speed), d (direction). */
  private static String windPieces(String choice) {
    switch (choice) {
      case "Speed":
        return "w";
      case "Speed and direction":
        return "wd";
      default: // "Off", and anything unrecognised
        return "";
    }
  }

  /** A boolean setting; the default when the host sent nothing (a build from before it existed). */
  private static boolean flag(Object o, boolean dflt) {
    return o instanceof Boolean ? (Boolean) o : dflt;
  }

  private static String str(Object o, String dflt) {
    return o instanceof String && !((String) o).isEmpty() ? (String) o : dflt;
  }

  private static double num(Object o, double dflt, double lo, double hi) {
    double v = o instanceof Number ? ((Number) o).doubleValue() : dflt;
    return Math.max(lo, Math.min(hi, v));
  }
}
