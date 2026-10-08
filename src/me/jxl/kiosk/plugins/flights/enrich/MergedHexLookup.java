// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.IOException;

/**
 * Hex lookups from two services: the primary says who owns the airframe (registration, type,
 * operator); the secondary adds a live callsign. The secondary is a bonus: if it is down the
 * primary's answer stands, so the owner-based airline fallback never waits on it. If the primary
 * fails, the secondary's answer stands in only when it carries a callsign (the part we most need,
 * and cached for just 20 min, so the owner fields are asked again soon); otherwise the primary's
 * failure fails the lookup and the caller backs off and retries.
 */
public final class MergedHexLookup implements Lookup {
  private final Lookup primary, callsigns, route;

  public MergedHexLookup(Lookup primary, Lookup callsigns, Lookup route) {
    this.primary = primary;
    this.callsigns = callsigns;
    this.route = route;
  }

  @Override
  public Info aircraft(String hex) throws IOException {
    // mlat/TIS-B targets carry a "~xxxxxx" id with no real address. adsbdb answers those with
    // HTTP 400, which would count as an error and back the whole hex service off, so skip them.
    if (!hex.matches("[0-9a-fA-F]{6}")) return null;
    Info owner;
    IOException primaryFailure = null;
    try {
      owner = primary.aircraft(hex);
    } catch (IOException e) {
      owner = null;
      primaryFailure = e;
    }
    Info live;
    try {
      live = callsigns.aircraft(hex);
    } catch (IOException e) {
      // Safe: callsign is optional; without it this aircraft just shows no route this time.
      live = null;
    }
    if (primaryFailure != null) {
      if (live != null && live.callsign != null) return live;
      throw primaryFailure;
    }
    if (live == null) return owner;
    if (owner == null) return live;
    return new Info(
            owner.registration != null ? owner.registration : live.registration,
            owner.type != null ? owner.type : live.type,
            owner.airlineIcao,
            owner.airlineName,
            null,
            null)
        .withCallsign(live.callsign);
  }

  @Override
  public Info airline(String icao) throws IOException {
    return primary.airline(icao);
  }

  @Override
  public Info route(String callsign) throws IOException {
    return route.route(callsign);
  }
}
