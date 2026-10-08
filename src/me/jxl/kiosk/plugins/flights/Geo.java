// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

/** Geometry on a sphere. Home stays exact here; nothing in this class logs or stores it. */
public final class Geo {
  static final double EARTH_RADIUS_M = 6371008.8;

  private Geo() {}

  /** Great-circle distance in metres (haversine). */
  public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
    double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
    double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
    double a =
        Math.sin(dp / 2) * Math.sin(dp / 2)
            + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  /**
   * Metres east and north of home on the local tangent plane. Accurate to well under 1 % inside the
   * 50 nm maximum radius, which is all the scope needs. Index 0 is east (x), 1 is north (y).
   */
  public static double[] eastNorthM(double homeLat, double homeLon, double lat, double lon) {
    double dLon = lon - homeLon;
    if (dLon > 180) dLon -= 360;
    if (dLon < -180) dLon += 360;
    double x = Math.toRadians(dLon) * Math.cos(Math.toRadians(homeLat)) * EARTH_RADIUS_M;
    double y = Math.toRadians(lat - homeLat) * EARTH_RADIUS_M;
    return new double[] {x, y};
  }

  /**
   * Distance in metres from a point to the great-circle segment a-b. Beyond either end the nearest
   * endpoint counts, so an aircraft past the destination is not "on" the route.
   */
  public static double distanceToSegmentM(
      double lat, double lon, double aLat, double aLon, double bLat, double bLon) {
    double dAB = distanceM(aLat, aLon, bLat, bLon);
    if (dAB < 1) return distanceM(lat, lon, aLat, aLon);
    double dAP = distanceM(aLat, aLon, lat, lon) / EARTH_RADIUS_M;
    double brgAB = bearingRad(aLat, aLon, bLat, bLon), brgAP = bearingRad(aLat, aLon, lat, lon);
    double xt = Math.asin(Math.sin(dAP) * Math.sin(brgAP - brgAB));
    double at = Math.acos(Math.max(-1, Math.min(1, Math.cos(dAP) / Math.cos(xt))));
    if (Math.cos(brgAP - brgAB) < 0) return distanceM(lat, lon, aLat, aLon); // behind A
    if (at * EARTH_RADIUS_M > dAB) return distanceM(lat, lon, bLat, bLon); // beyond B
    return Math.abs(xt) * EARTH_RADIUS_M;
  }

  private static double bearingRad(double lat1, double lon1, double lat2, double lon2) {
    double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
    return Math.atan2(
        Math.sin(dl) * Math.cos(p2),
        Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl));
  }
}
