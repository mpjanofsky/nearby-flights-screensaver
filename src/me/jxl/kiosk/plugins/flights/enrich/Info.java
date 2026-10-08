// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

/** What enrichment knows about one aircraft. Every field is null when unknown. */
public final class Info {
  public static final Info EMPTY = new Info(null, null, null, null, null, null);

  public final String registration, type, airlineIcao, airlineName, origin, destination;

  /**
   * Callsign found by a hex lookup, for aircraft the feed reports without one (a local receiver
   * often has no ident message yet). Null when the feed's own callsign is the one to use.
   */
  public final String callsign;

  /**
   * Every airport of a multi-stop route as lat, lon pairs, so the plausibility check can test all
   * legs. Null for a plain two-airport route (origin and destination coordinates suffice).
   */
  public final double[] path;

  /** The airport codes behind {@link #path}, in order (same length / 2). Null for a plain route. */
  public final String[] stops;

  /**
   * Airport positions behind origin/destination, NaN when the source gave none. Used only to
   * sanity-check the route against the aircraft's position; never published and not in signature.
   */
  public final double originLat, originLon, destLat, destLon;

  public Info(
      String registration,
      String type,
      String airlineIcao,
      String airlineName,
      String origin,
      String destination) {
    this(
        registration,
        type,
        airlineIcao,
        airlineName,
        origin,
        destination,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN);
  }

  public Info(
      String registration,
      String type,
      String airlineIcao,
      String airlineName,
      String origin,
      String destination,
      double originLat,
      double originLon,
      double destLat,
      double destLon) {
    this(
        registration,
        type,
        airlineIcao,
        airlineName,
        origin,
        destination,
        originLat,
        originLon,
        destLat,
        destLon,
        null,
        null,
        null);
  }

  private Info(
      String registration,
      String type,
      String airlineIcao,
      String airlineName,
      String origin,
      String destination,
      double originLat,
      double originLon,
      double destLat,
      double destLon,
      String callsign,
      double[] path,
      String[] stops) {
    this.stops = stops;
    this.path = path;
    this.callsign = callsign;
    this.originLat = originLat;
    this.originLon = originLon;
    this.destLat = destLat;
    this.destLon = destLon;
    this.registration = registration;
    this.type = type;
    this.airlineIcao = airlineIcao;
    this.airlineName = airlineName;
    this.origin = origin;
    this.destination = destination;
  }

  /** A copy that also carries a callsign. */
  public Info withCallsign(String callsign) {
    return new Info(
        registration,
        type,
        airlineIcao,
        airlineName,
        origin,
        destination,
        originLat,
        originLon,
        destLat,
        destLon,
        callsign,
        path,
        stops);
  }

  /** A copy that also carries every airport (code and lat/lon pair) of a multi-stop route. */
  public Info withStops(String[] stops, double[] path) {
    return new Info(
        registration,
        type,
        airlineIcao,
        airlineName,
        origin,
        destination,
        originLat,
        originLon,
        destLat,
        destLon,
        callsign,
        path,
        stops);
  }

  /** A copy reduced to one leg of a multi-stop route (the leg's own coordinates are not needed). */
  public Info leg(String origin, String destination) {
    return new Info(
        registration,
        type,
        airlineIcao,
        airlineName,
        origin,
        destination,
        originLat,
        originLon,
        destLat,
        destLon,
        callsign,
        null,
        null);
  }

  /** Stable text for the diff gate: a change here must trigger a publish. */
  public String signature() {
    return registration
        + ","
        + type
        + ","
        + airlineIcao
        + ","
        + airlineName
        + ","
        + origin
        + ","
        + destination
        + ","
        + callsign;
  }
}
