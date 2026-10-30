// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.weather;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The current weather at home, in SI. Built from a Home Assistant weather entity snapshot; the
 * renderer only ever sees these values (payload `wx`), never the entity.
 */
public final class Weather {
  /** Home Assistant's standard weather conditions; anything else is shown as temperature alone. */
  static final Set<String> CONDITIONS =
      new HashSet<>(
          Arrays.asList(
              "clear-night",
              "cloudy",
              "exceptional",
              "fog",
              "hail",
              "lightning",
              "lightning-rainy",
              "partlycloudy",
              "pouring",
              "rainy",
              "snowy",
              "snowy-rainy",
              "sunny",
              "windy",
              "windy-variant"));

  /** One of {@link #CONDITIONS}, or null when the entity reported something else. */
  public final String condition;

  public final double tempC;

  /** True when the entity reports in Fahrenheit: the display follows the HA setting. */
  public final boolean fahrenheit;

  /**
   * Which pieces chosen to show, as letters: t (temperature), c (condition word), i (icon), w (wind
   * speed), d (wind direction, with w). The renderer lays out only these.
   */
  public final String show;

  /** What the payload means when it says nothing: wind is opt-in, the rest is on. */
  public static final String ALL = "tci";

  /** Wind speed in m/s (NaN: none) and the direction it blows from in degrees (NaN: unknown). */
  public final double windMs, windDeg;

  /** The unit HA reports the wind in (one of {@link #WIND_UNITS}), which the display follows. */
  public final String windUnit;

  /** Units HA uses for wind speed, each with its size in metres per second. */
  static final Map<String, Double> WIND_UNITS = new HashMap<>();

  static {
    WIND_UNITS.put("m/s", 1.0);
    WIND_UNITS.put("km/h", 1 / 3.6);
    WIND_UNITS.put("mph", 0.44704);
    WIND_UNITS.put("kn", 1852.0 / 3600);
    WIND_UNITS.put("ft/s", 0.3048);
  }

  Weather(String condition, double tempC, boolean fahrenheit) {
    this(condition, tempC, fahrenheit, ALL, Double.NaN, Double.NaN, null);
  }

  private Weather(
      String condition,
      double tempC,
      boolean fahrenheit,
      String show,
      double windMs,
      double windDeg,
      String windUnit) {
    this.condition = condition;
    this.tempC = tempC;
    this.fahrenheit = fahrenheit;
    this.show = show;
    this.windMs = windMs;
    this.windDeg = windDeg;
    this.windUnit = windUnit;
  }

  /** The same weather with only the chosen pieces ({@link #show} letters) to be shown. */
  public Weather showing(String pieces) {
    return new Weather(condition, tempC, fahrenheit, pieces, windMs, windDeg, windUnit);
  }

  /** The 16 compass points HA may use for `wind_bearing`, in order from north. */
  private static final String[] POINTS = {
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW",
    "NNW"
  };

  /** Degrees from `wind_bearing`, which HA gives as a number or a compass point; NaN if neither. */
  static double bearing(Object o) {
    if (o instanceof Number) {
      double d = ((Number) o).doubleValue();
      return Double.isNaN(d) || Double.isInfinite(d) ? Double.NaN : ((d % 360) + 360) % 360;
    }
    if (!(o instanceof String)) return Double.NaN;
    String s = ((String) o).trim().toUpperCase(java.util.Locale.ROOT);
    for (int i = 0; i < POINTS.length; i++) if (POINTS[i].equals(s)) return i * 22.5;
    try {
      return bearing(Double.valueOf(s));
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /**
   * Reads one `getHaEntityState` snapshot (or `ks.ha.entity.*` event payload). Returns null unless
   * the entity is available and has a usable temperature: an unavailable or missing entity must
   * never show as 0 degrees. An unrecognised unit also gives null rather than a guess.
   */
  public static Weather fromSnapshot(Map<?, ?> snap) {
    if (snap == null || !"available".equals(snap.get("status"))) return null;
    Object attrs = snap.get("attributes");
    if (!(attrs instanceof Map)) return null;
    Map<?, ?> a = (Map<?, ?>) attrs;
    Object t = a.get("temperature");
    if (!(t instanceof Number)) return null;
    double v = ((Number) t).doubleValue();
    if (Double.isNaN(v) || Double.isInfinite(v)) return null;
    Object unit = a.get("temperature_unit");
    double c;
    boolean f = false;
    if ("°C".equals(unit)) {
      c = v;
    } else if ("°F".equals(unit)) {
      c = (v - 32) * 5 / 9;
      f = true;
    } else if ("K".equals(unit)) {
      c = v - 273.15;
    } else {
      return null;
    }
    Object state = snap.get("state");
    String cond = state instanceof String && CONDITIONS.contains(state) ? (String) state : null;
    // Wind is optional: no speed, or a unit we do not know, leaves it out rather than guessing.
    double windMs = Double.NaN, windDeg = Double.NaN;
    String windUnit = null;
    Object ws = a.get("wind_speed"), wu = a.get("wind_speed_unit");
    if (ws instanceof Number && wu instanceof String && WIND_UNITS.containsKey(wu)) {
      double sp = ((Number) ws).doubleValue();
      if (sp >= 0 && !Double.isInfinite(sp)) {
        windMs = sp * WIND_UNITS.get(wu);
        windUnit = (String) wu;
        windDeg = bearing(a.get("wind_bearing"));
      }
    }
    return new Weather(cond, c, f, ALL, windMs, windDeg, windUnit);
  }
}
