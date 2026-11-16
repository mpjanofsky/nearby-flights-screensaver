// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.IOException;

/** The network side of enrichment. A null result is a normal miss; an IOException is a failure. */
public interface Lookup {
  /** Registration and ICAO type for a hex id. Returns Info with only those fields set. */
  Info aircraft(String hex) throws IOException;

  /** Airline and route for a callsign. Returns Info with only those fields set. */
  Info route(String callsign) throws IOException;

  /**
   * Routes for several callsigns in one go, keyed by callsign. A callsign with no route is absent
   * (a miss). The default asks one at a time; a source with a batch API overrides it.
   */
  default java.util.Map<String, Info> routes(java.util.List<String> callsigns) throws IOException {
    java.util.Map<String, Info> out = new java.util.HashMap<>();
    for (String cs : callsigns) {
      Info i = route(cs);
      if (i != null) out.put(cs, i);
    }
    return out;
  }

  /**
   * The airline for an ICAO operator code (name only, in {@link Info#airlineName}). The default is
   * a miss, so a source that cannot answer needs no code.
   */
  default Info airline(String icao) throws IOException {
    return null;
  }
}
