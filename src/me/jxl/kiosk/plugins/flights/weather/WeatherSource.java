// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.weather;

import java.util.Map;

/**
 * A place the current weather comes from. Unlike a {@code SourceAdapter} it pushes: the source
 * tells the listener whenever the weather changes, so there is no polling and no blocking call. The
 * renderer never knows which source is active.
 */
public interface WeatherSource {
  interface Listener {
    /** The latest weather, or null when there is none to show (unavailable, missing, unusable). */
    void onWeather(Weather w);
  }

  /** Starts following the weather; the first answer arrives through the listener. */
  void start(Listener listener);

  /**
   * Stops following (the setting changed or was turned off). Not for the plugin's own stop(), where
   * the host has already revoked its subscriptions.
   */
  void close();

  /** Passes a host event to the source; true when it was the source's own. */
  boolean onEvent(String event, Map<String, Object> payload);
}
