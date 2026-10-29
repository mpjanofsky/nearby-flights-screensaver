// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import android.graphics.Color;
import java.util.Locale;

/** Display formatting and colours, ported from assets/flights/lib.js for the native view. */
final class Fmt {
  private Fmt() {}

  private static double[] units(String u) {
    // {distance metres, altitude metres, step}
    switch (u) {
      case "met":
        return new double[] {1000, 1, 10};
      case "imp":
        return new double[] {1609.344, 0.3048, 100};
      default:
        return new double[] {1852, 0.3048, 100};
    }
  }

  private static String distUnit(String u) {
    return "met".equals(u) ? "km" : "imp".equals(u) ? "mi" : "nm";
  }

  private static String altUnit(String u) {
    return "met".equals(u) ? "m" : "ft";
  }

  static String dist(double m, String u) {
    double v = m / units(u)[0];
    return (v < 10 ? String.format(Locale.US, "%.1f", v) : String.valueOf(Math.round(v)))
        + " "
        + distUnit(u);
  }

  /** Altitude to the nearest 100 ft (10 m), with thousands separators; null when unknown. */
  static String alt(double m, String u) {
    if (Double.isNaN(m)) return null;
    double[] k = units(u);
    long v = Math.round(m / k[1] / k[2]) * (long) k[2];
    return String.format(Locale.US, "%,d", v) + " " + altUnit(u);
  }

  static String typeLabel(String ty, int max) {
    if (ty == null) return "";
    String n = TypeNames.NAMES.get(ty.toUpperCase(Locale.US));
    return n != null && n.length() <= max ? n : ty;
  }

  /** Altitude ramp, the same stops as lib.js: cool when low, warm then violet when high. */
  static int altColor(double altM) {
    if (Double.isNaN(altM)) return 0xFF8FA3B8;
    double[][] stops = {
      {0, 79, 209, 197}, {914, 123, 211, 106}, {1829, 229, 216, 74},
      {3048, 255, 154, 60}, {6096, 255, 92, 122}, {Model.ALT_TOP_M, 194, 107, 255}
    };
    double a = Math.max(0, Math.min(Model.ALT_TOP_M, altM));
    int k = 1;
    while (a > stops[k][0]) k++;
    double[] s0 = stops[k - 1], s1 = stops[k];
    double t = (a - s0[0]) / (s1[0] - s0[0]);
    return Color.rgb(
        (int) Math.round(s0[1] + (s1[1] - s0[1]) * t),
        (int) Math.round(s0[2] + (s1[2] - s0[2]) * t),
        (int) Math.round(s0[3] + (s1[3] - s0[3]) * t));
  }

  private static final String[][] BRAND = {
    {"DAL", "C01933"}, {"AAL", "0078D2"}, {"UAL", "0033A0"}, {"SWA", "304CB2"}, {"JBU", "003876"},
    {"ASA", "01426A"}, {"NKS", "FFEC00"}, {"FFT", "248B68"}, {"HAL", "5B2C83"}, {"SKW", "00539B"},
    {"ENY", "0078D2"}, {"PDT", "0078D2"}, {"JIA", "0078D2"}, {"EDV", "C01933"}, {"FDX", "4D148C"},
    {"UPS", "351C15"}, {"ACA", "F01428"}, {"WJA", "00A3AD"}, {"BAW", "075AAA"}, {"VIR", "E10A0A"},
    {"DLH", "05164D"}, {"AFR", "002157"}, {"KLM", "00A1DE"}, {"UAE", "D71920"}, {"QTR", "5C0632"},
    {"SIA", "00266B"}, {"ANA", "13448F"}, {"JAL", "CC0000"}, {"QFA", "E40000"}, {"EZY", "FF6600"},
    {"RYR", "073590"}, {"AMX", "0B2343"}, {"CLX", "E4002B"}, {"GTI", "00529B"}, {"CMP", "0072CE"},
    {"AVA", "E20612"}, {"LAN", "1B0088"}, {"LPE", "1B0088"}, {"AZU", "003DA5"}, {"GLO", "FF7020"},
    {"ICE", "003B6F"}, {"MXY", "00B5E2"},
  };

  private static final String[][] HUE = {
    {"DAL", "352"}, {"AAL", "205"}, {"UAL", "225"}, {"SWA", "45"}, {"JBU", "200"}, {"ASA", "165"},
    {"NKS", "58"}, {"FFT", "140"}, {"FDX", "275"}, {"UPS", "30"}, {"AAY", "25"}, {"HAL", "320"},
    {"SKW", "190"}, {"RPA", "215"}, {"ACA", "0"}, {"WJA", "170"},
  };

  /** Brand colour for an airline code, else a stable hue from the code (hsl 75 %, 62 %). */
  static int airlineColor(String code) {
    if (code == null) return 0xFF3A4A5C;
    for (String[] b : BRAND) if (b[0].equals(code)) return 0xFF000000 | Integer.parseInt(b[1], 16);
    int h = 0;
    boolean found = false;
    for (String[] e : HUE) {
      if (e[0].equals(code)) {
        h = Integer.parseInt(e[1]);
        found = true;
      }
    }
    if (!found) for (char ch : code.toCharArray()) h = (h * 31 + ch) % 360;
    return Color.HSVToColor(hslToHsv(h, 0.75f, 0.62f));
  }

  private static float[] hslToHsv(float h, float s, float l) {
    float v = l + s * Math.min(l, 1 - l);
    float sv = v == 0 ? 0 : 2 * (1 - l / v);
    return new float[] {h, sv, v};
  }

  /**
   * Relative luminance as lib.js has it: computed for hex brand colours, 0.6 for the hashed hues.
   */
  static double luminance(String code, int color) {
    boolean brand = false;
    if (code != null) for (String[] b : BRAND) if (b[0].equals(code)) brand = true;
    if (!brand) return 0.6;
    double r = lin(Color.red(color) / 255.0),
        g = lin(Color.green(color) / 255.0),
        b = lin(Color.blue(color) / 255.0);
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  private static double lin(double v) {
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  }
}
