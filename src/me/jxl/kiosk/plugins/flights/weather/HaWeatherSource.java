// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.weather;

import java.util.Map;
import java.util.regex.Pattern;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Weather from a Home Assistant `weather.*` entity picked in settings, followed through Kiosk
 * Satellite's `ha.entity.` subscription (capability `host.read`): the host delivers the current
 * snapshot at once and every change after it. Nothing is sent anywhere by us.
 */
public final class HaWeatherSource implements WeatherSource {
  private static final Pattern ENTITY = Pattern.compile("weather\\.[a-z0-9_]{1,200}");

  private final PluginHost host;
  private final String entityId;
  private Listener listener;

  public HaWeatherSource(PluginHost host, String entityId) {
    this.host = host;
    this.entityId = entityId;
  }

  /** True for an entity id this source can follow (the picker lists every domain). */
  public static boolean valid(String entityId) {
    return entityId != null && ENTITY.matcher(entityId).matches();
  }

  @Override
  public synchronized void start(Listener l) {
    listener = l;
    if (!valid(entityId)) return;
    try {
      host.subscribe("ha.entity." + entityId);
    } catch (RuntimeException e) {
      // Safe: without the subscription there is no weather line, and the rest of the screen is
      // unaffected. The host refuses when host.read is not granted or Home Assistant is not set up.
      host.log("weather unavailable: " + e.getClass().getSimpleName());
    }
  }

  @Override
  public synchronized void close() {
    listener = null;
    if (!valid(entityId)) return;
    try {
      host.unsubscribe("ha.entity." + entityId);
    } catch (RuntimeException e) {
      // Safe: the host drops every subscription when the plugin stops; this one only lingers
      // until then, and its events are ignored because the listener is gone.
      host.log("weather unsubscribe failed: " + e.getClass().getSimpleName());
    }
  }

  @Override
  public synchronized boolean onEvent(String event, Map<String, Object> payload) {
    if (!("ks.ha.entity." + entityId).equals(event)) return false;
    if (listener != null) listener.onWeather(Weather.fromSnapshot(payload));
    return true;
  }
}
