package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeoTest {
  @Test
  void oneDegreeOfLatitudeIsAbout111Km() {
    assertEquals(111195, Geo.distanceM(40, -73, 41, -73), 50);
  }

  @Test
  void eastNorthMatchesGreatCircleInsideTheScope() {
    double[] p = Geo.eastNorthM(40.0, -73.0, 40.1, -72.9);
    assertEquals(Geo.distanceM(40.0, -73.0, 40.1, -72.9), Math.hypot(p[0], p[1]), 30);
    assertEquals(true, p[0] > 0 && p[1] > 0);
  }

  @Test
  void longitudeWrapsAcrossTheAntimeridian() {
    double[] p = Geo.eastNorthM(0, 179.9, 0, -179.9);
    assertEquals(22239, p[0], 5);
  }

  // FLL (26.07, -80.15) to LAX (33.94, -118.41).
  static final double[] FLL = {26.0726, -80.1527}, LAX = {33.9425, -118.4080};

  @Test
  void aircraftOnTheSegmentIsNearZero() {
    // Midpoint of the great circle, via the spherical interpolation of the two endpoints.
    double[] m = mid(FLL, LAX);
    assertEquals(0, Geo.distanceToSegmentM(m[0], m[1], FLL[0], FLL[1], LAX[0], LAX[1]), 500);
  }

  @Test
  void offsetFromTheSegmentIsTheCrossTrackDistance() {
    double[] m = mid(FLL, LAX);
    double d = Geo.distanceToSegmentM(m[0] + 1.0, m[1], FLL[0], FLL[1], LAX[0], LAX[1]);
    assertEquals(true, d > 80_000 && d < 115_000, "about a degree off: " + d);
  }

  @Test
  void beyondEitherEndCountsFromTheNearestAirport() {
    double d = Geo.distanceToSegmentM(26.07, -75.0, FLL[0], FLL[1], LAX[0], LAX[1]);
    assertEquals(Geo.distanceM(26.07, -75.0, FLL[0], FLL[1]), d, 1);
  }

  private static double[] mid(double[] a, double[] b) {
    double p1 = Math.toRadians(a[0]), l1 = Math.toRadians(a[1]);
    double p2 = Math.toRadians(b[0]), l2 = Math.toRadians(b[1]);
    double x = Math.cos(p1) * Math.cos(l1) + Math.cos(p2) * Math.cos(l2);
    double y = Math.cos(p1) * Math.sin(l1) + Math.cos(p2) * Math.sin(l2);
    double z = Math.sin(p1) + Math.sin(p2);
    return new double[] {
      Math.toDegrees(Math.atan2(z, Math.hypot(x, y))), Math.toDegrees(Math.atan2(y, x))
    };
  }
}
