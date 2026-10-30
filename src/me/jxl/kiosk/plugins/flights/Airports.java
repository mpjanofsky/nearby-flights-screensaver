// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bundled airport list (OurAirports subset, see scripts/gen-airports.py), filtered to the radius
 * around home for the scope's markers. Any home location works with no per-area build.
 */
public final class Airports {
  /** One marker: code and metres east/north of home. */
  public static final class Marker {
    public final String code;
    public final double x, y;

    Marker(String code, double x, double y) {
      this.code = code;
      this.x = x;
      this.y = y;
    }
  }

  private static final int MAX = 8; // payload contract: ap is at most 8 entries

  private final String[] codes;
  private final double[] lat, lon;
  private final int[] size;
  private final Map<String, String> cities = new HashMap<>();

  private static Airports shared;

  /** The bundled table, parsed once; the encoder and the engine both read it. */
  public static synchronized Airports shared() {
    if (shared == null) shared = new Airports();
    return shared;
  }

  public Airports() {
    this(AirportData.records());
  }

  /** Records are "CODE lat lon size" or "CODE lat lon size|City", joined by ';'. */
  Airports(String records) {
    String[] rec = records.isEmpty() ? new String[0] : records.split(";");
    codes = new String[rec.length];
    lat = new double[rec.length];
    lon = new double[rec.length];
    size = new int[rec.length];
    for (int i = 0; i < rec.length; i++) {
      int bar = rec[i].indexOf('|');
      String[] f = (bar < 0 ? rec[i] : rec[i].substring(0, bar)).split(" ");
      codes[i] = f[0];
      lat[i] = Double.parseDouble(f[1]);
      lon[i] = Double.parseDouble(f[2]);
      size[i] = Integer.parseInt(f[3]);
      if (bar >= 0) cities.put(codes[i], rec[i].substring(bar + 1));
    }
  }

  /** The city an airport code serves, or null when the table has none. */
  public String city(String code) {
    return code == null ? null : cities.get(code);
  }

  /**
   * A route string ("LGW" or "LGW → AGP", with stops "A → B → C") with each code replaced by its
   * city. A code with no city stays as it is. Null when no code in the route has a city, so the
   * caller sends nothing and the renderer keeps the codes.
   */
  public String cityRoute(String route) {
    if (route == null) return null;
    String[] parts = route.split(" \u2192 ");
    StringBuilder out = new StringBuilder();
    boolean any = false;
    for (int i = 0; i < parts.length; i++) {
      String city = city(parts[i]);
      any |= city != null;
      if (i > 0) out.append(" \u2192 ");
      out.append(city != null ? city : parts[i]);
    }
    return any ? out.toString() : null;
  }

  /**
   * Airports within the radius of the exact home. When more than {@value #MAX} qualify, large ones
   * win, then the nearer, so the scope stays readable and the choice is stable between calls.
   */
  public List<Marker> within(double homeLat, double homeLon, double radiusM) {
    List<Marker> found = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    for (int i = 0; i < codes.length; i++) {
      // Cheap latitude reject before the projection; 111 km per degree is a safe lower bound.
      if (Math.abs(lat[i] - homeLat) * 111000 > radiusM) continue;
      double[] xy = Geo.eastNorthM(homeLat, homeLon, lat[i], lon[i]);
      if (Math.hypot(xy[0], xy[1]) > radiusM) continue;
      found.add(new Marker(codes[i], xy[0], xy[1]));
      sizes.add(size[i]);
    }
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < found.size(); i++) order.add(i);
    order.sort(
        Comparator.<Integer>comparingInt(i -> -sizes.get(i))
            .thenComparingDouble(i -> Math.hypot(found.get(i).x, found.get(i).y)));
    List<Marker> out = new ArrayList<>();
    for (int i = 0; i < Math.min(MAX, order.size()); i++) out.add(found.get(order.get(i)));
    return out;
  }
}
