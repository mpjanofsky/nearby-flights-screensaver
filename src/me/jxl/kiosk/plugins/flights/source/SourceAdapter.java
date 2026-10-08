// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

/**
 * A place aircraft come from. The renderer never knows which one is active; the plugin only
 * publishes an id for the footer indicator. Implementations block, so call from a background
 * thread: the host's lifecycle callbacks must return within 3 s.
 */
public interface SourceAdapter {
  /** Short id for the payload's `src`: "local" for a LAN feed, "net" for an internet one. */
  String id();

  /**
   * Fetches the aircraft within `radiusM` of the home point. Adapters that cannot filter
   * server-side (a LAN feed returns everything) filter here, so callers always get a radius-bounded
   * list. Adapters that send the location off the LAN must round it first (2 decimals, radius +1
   * nm) and must never log it.
   *
   * @throws SourceException on any network, HTTP or parse failure
   */
  FetchResult fetch(double homeLat, double homeLon, double radiusM) throws SourceException;
}
