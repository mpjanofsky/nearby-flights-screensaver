// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

/** User settings the pipeline needs. Home is exact; it must never be logged or published. */
public final class Config {
  public final double homeLat, homeLon, radiusM;

  /** Clock override: "12", "24", or null for the device locale. */
  public final String clockFormat;

  public final String units; // "av" | "met" | "imp"
  public final int maxRows; // 1..4: list rows, not counting the featured aircraft
  public final boolean excludeGa;

  /** Demo switch: publish an empty sky whatever is overhead, to show the quiet-sky screen. */
  public final boolean demoQuiet;

  /** Hard or soft radius; see {@link Widen}. */
  public final Widen widen;

  /** Upper bound for the widened radius; never below {@link #radiusM}. */
  public final double maxRadiusM;

  /** Whether the configured radius is a ceiling or only a preference. */
  public enum Widen {
    /** Never show aircraft beyond the radius. */
    HARD,
    /** Prefer the radius, but reach farther (up to maxRadiusM) while the list is not full. */
    SOFT
  }

  public Config(
      double homeLat,
      double homeLon,
      double radiusM,
      String units,
      int maxRows,
      boolean excludeGa) {
    this(homeLat, homeLon, radiusM, units, maxRows, excludeGa, Widen.HARD, radiusM);
  }

  public Config(
      double homeLat,
      double homeLon,
      double radiusM,
      String units,
      int maxRows,
      boolean excludeGa,
      Widen widen,
      double maxRadiusM) {
    this(homeLat, homeLon, radiusM, units, maxRows, excludeGa, widen, maxRadiusM, false);
  }

  public Config(
      double homeLat,
      double homeLon,
      double radiusM,
      String units,
      int maxRows,
      boolean excludeGa,
      Widen widen,
      double maxRadiusM,
      boolean demoQuiet) {
    this(homeLat, homeLon, radiusM, units, maxRows, excludeGa, widen, maxRadiusM, demoQuiet, null);
  }

  public Config(
      double homeLat,
      double homeLon,
      double radiusM,
      String units,
      int maxRows,
      boolean excludeGa,
      Widen widen,
      double maxRadiusM,
      boolean demoQuiet,
      String clockFormat) {
    this.clockFormat = clockFormat;
    this.demoQuiet = demoQuiet;
    this.homeLat = homeLat;
    this.homeLon = homeLon;
    this.radiusM = radiusM;
    this.units = units;
    this.maxRows = Math.max(1, Math.min(4, maxRows));
    this.excludeGa = excludeGa;
    this.widen = widen;
    this.maxRadiusM = Math.max(radiusM, maxRadiusM);
  }

  /** Same settings at another radius (the widened one); the widen limit is unchanged. */
  public Config withRadius(double newRadiusM) {
    return new Config(
        homeLat,
        homeLon,
        newRadiusM,
        units,
        maxRows,
        excludeGa,
        widen,
        maxRadiusM,
        demoQuiet,
        clockFormat);
  }

  /** Aircraft to publish: the featured one (shown in the top band) plus the list rows. */
  public int aircraftWanted() {
    return maxRows + 1;
  }

  /** True when `count` eligible aircraft are enough, so no further widening is needed. */
  public boolean satisfied(int count) {
    return widen == Widen.HARD || count >= aircraftWanted();
  }
}
