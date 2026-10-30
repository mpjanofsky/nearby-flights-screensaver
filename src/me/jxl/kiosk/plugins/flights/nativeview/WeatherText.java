// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import java.util.Locale;

/**
 * What the weather line says and which icon goes with it (payload `wx`), kept free of Android so it
 * can be tested. The drawing is in ScopeView.
 */
final class WeatherText {
  private WeatherText() {}

  /** Icon kinds ScopeView knows how to draw. */
  static final String SUN = "sun",
      MOON = "moon",
      CLOUD = "cloud",
      PARTLY = "partly",
      RAIN = "rain",
      SNOW = "snow",
      STORM = "storm",
      FOG = "fog",
      WIND = "wind";

  /** The short condition name, or null for a condition we have no word for. */
  static String label(String cond) {
    if (cond == null) return null;
    switch (cond) {
      case "sunny":
        return "Sunny";
      case "clear-night":
        return "Clear";
      case "partlycloudy":
        return "Partly cloudy";
      case "cloudy":
        return "Cloudy";
      case "rainy":
        return "Rain";
      case "pouring":
        return "Heavy rain";
      case "snowy":
        return "Snow";
      case "snowy-rainy":
        return "Sleet";
      case "hail":
        return "Hail";
      case "lightning":
        return "Thunderstorm";
      case "lightning-rainy":
        return "Thunderstorms";
      case "fog":
        return "Fog";
      case "windy":
      case "windy-variant":
        return "Windy";
      case "exceptional":
        return "Severe weather";
      default:
        return null;
    }
  }

  /** The icon kind for a condition, or null (no icon). */
  static String icon(String cond) {
    if (cond == null) return null;
    switch (cond) {
      case "sunny":
        return SUN;
      case "clear-night":
        return MOON;
      case "partlycloudy":
        return PARTLY;
      case "cloudy":
        return CLOUD;
      case "rainy":
      case "pouring":
        return RAIN;
      case "snowy":
      case "snowy-rainy":
      case "hail":
        return SNOW;
      case "lightning":
      case "lightning-rainy":
      case "exceptional":
        return STORM;
      case "fog":
        return FOG;
      case "windy":
      case "windy-variant":
        return WIND;
      default:
        return null;
    }
  }

  /** Whole degrees in the unit HA reports, e.g. {@code 72°}. */
  static String temp(double tempC, boolean fahrenheit) {
    long v = Math.round(fahrenheit ? tempC * 9 / 5 + 32 : tempC);
    return String.format(Locale.US, "%d°", v == 0 ? 0 : v); // never "-0°"
  }

  /** Metres per second in one unit HA reports wind in; an unknown unit gives no wind text. */
  private static double perUnit(String unit) {
    if (unit == null) return Double.NaN;
    switch (unit) {
      case "m/s":
        return 1;
      case "km/h":
        return 1 / 3.6;
      case "mph":
        return 0.44704;
      case "kn":
        return 1852.0 / 3600;
      case "ft/s":
        return 0.3048;
      default:
        return Double.NaN;
    }
  }

  /** The eight compass words, with 0° as N: the direction the wind blows from. */
  static String compass(double deg) {
    String[] p = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    return p[(int) (Math.round(((deg % 360) + 360) % 360 / 45) % 8)];
  }

  /**
   * The wind as {@code NW 8 mph} (or {@code 8 mph} without direction), in whole numbers of HA's
   * unit; {@code Calm} when it rounds to zero. Null when wind is not chosen or there is none.
   */
  static String wind(Model m, boolean withDirection) {
    double f = perUnit(m.wxWindUnit);
    if (!m.wxShowWind || Double.isNaN(m.wxWindMs) || Double.isNaN(f)) return null;
    long v = Math.round(m.wxWindMs / f);
    if (v == 0) return "Calm";
    String speed = v + " " + m.wxWindUnit;
    return withDirection && m.wxShowDir && !Double.isNaN(m.wxWindDeg)
        ? compass(m.wxWindDeg) + " " + speed
        : speed;
  }

  /** The wind after the rest, set off with a dot only when it follows the condition word. */
  private static String withWind(String head, boolean afterWord, String wind) {
    if (wind == null) return head;
    return head.isEmpty() ? wind : head + (afterWord ? " \u00b7 " : " ") + wind;
  }

  private static String join(String... parts) {
    StringBuilder s = new StringBuilder();
    for (String p : parts) if (p != null) s.append(s.length() > 0 ? " " : "").append(p);
    return s.toString();
  }

  /**
   * The text to try, longest first. The chosen pieces go together in the order temperature,
   * condition word, wind. What gives way first is the wind's direction, then the wind, then the
   * word; the temperature stays. With only the icon chosen the text is empty. Null when there is
   * nothing to draw.
   */
  static String[] candidates(Model m) {
    if (Double.isNaN(m.wxTempC)) return null;
    String t = m.wxShowTemp ? temp(m.wxTempC, m.wxF) : null;
    String word = m.wxShowWord ? label(m.wxCond) : null;
    String full = wind(m, true), speed = wind(m, false);
    java.util.List<String> steps = new java.util.ArrayList<>();
    steps.add(withWind(join(t, word), word != null, full));
    steps.add(withWind(join(t, word), word != null, speed));
    steps.add(join(t, word));
    // With a word chosen, the last step drops it too, so the temperature stays on its own.
    if (word != null) steps.add(join(t));
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String s : steps) if (!s.isEmpty() && !out.contains(s)) out.add(s);
    if (out.isEmpty()) return hasIcon(m) ? new String[] {""} : null;
    return out.toArray(new String[0]);
  }

  /** Whether the icon is chosen and the condition has one. */
  static boolean hasIcon(Model m) {
    return m.wxShowIcon && icon(m.wxCond) != null;
  }
}
