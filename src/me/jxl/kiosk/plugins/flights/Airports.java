// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

  public Airports() {
    this(AirportData.records());
  }

  /** Records are "CODE lat lon size" joined by ';'. */
  Airports(String records) {
    String[] rec = records.isEmpty() ? new String[0] : records.split(";");
    codes = new String[rec.length];
    lat = new double[rec.length];
    lon = new double[rec.length];
    size = new int[rec.length];
    for (int i = 0; i < rec.length; i++) {
      String[] f = rec[i].split(" ");
      codes[i] = f[0];
      lat[i] = Double.parseDouble(f[1]);
      lon[i] = Double.parseDouble(f[2]);
      size[i] = Integer.parseInt(f[3]);
    }
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
