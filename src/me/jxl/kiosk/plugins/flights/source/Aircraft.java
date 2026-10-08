// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

/**
 * One normalized aircraft observation, raw SI units. Missing numeric values are NaN and missing
 * strings are null, so adapters never invent data and the payload can omit it.
 */
public final class Aircraft {
  public final String hex;
  public final String callsign; // trimmed, null when absent
  public final String registration;
  public final String type;
  public final String category; // ADS-B emitter category, e.g. "A3"
  public final double lat;
  public final double lon;
  public final double altitudeM; // barometric; NaN on the ground or unknown
  public final double groundSpeedMs;
  public final double trackDeg;
  public final double verticalRateMs;
  public final double
      positionAgeS; // seconds between the position fix and `FetchResult.observedAtMs`
  public final boolean onGround;

  public Aircraft(
      String hex,
      String callsign,
      String registration,
      String type,
      String category,
      double lat,
      double lon,
      double altitudeM,
      double groundSpeedMs,
      double trackDeg,
      double verticalRateMs,
      double positionAgeS,
      boolean onGround) {
    this.hex = hex;
    this.callsign = callsign;
    this.registration = registration;
    this.type = type;
    this.category = category;
    this.lat = lat;
    this.lon = lon;
    this.altitudeM = altitudeM;
    this.groundSpeedMs = groundSpeedMs;
    this.trackDeg = trackDeg;
    this.verticalRateMs = verticalRateMs;
    this.positionAgeS = positionAgeS;
    this.onGround = onGround;
  }

  /** The same observation, `extraS` seconds older: a fix kept past the fetch it came from. */
  public Aircraft withAge(double extraS) {
    return new Aircraft(
        hex,
        callsign,
        registration,
        type,
        category,
        lat,
        lon,
        altitudeM,
        groundSpeedMs,
        trackDeg,
        verticalRateMs,
        positionAgeS + extraS,
        onGround);
  }
}
