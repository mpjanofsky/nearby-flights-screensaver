// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.flights.weather.Weather;
import org.junit.jupiter.api.Test;

class PublishGateWeatherTest {
  private static Weather of(String state, int tempF) {
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("temperature", tempF);
    attrs.put("temperature_unit", "°F");
    Map<String, Object> snap = new HashMap<>();
    snap.put("status", "available");
    snap.put("state", state);
    snap.put("attributes", attrs);
    return Weather.fromSnapshot(snap);
  }

  @Test
  void aChangeInTheWeatherNeedsAPublishAndTheSameWeatherDoesNot() {
    Config c = PluginSettings.parse(PluginSettingsTest.ok()).config;
    PublishGate g = new PublishGate();
    Weather w = of("sunny", 72);
    assertTrue(g.changed(Collections.<Tracked>emptyList(), null, c, false, "net", 0, w));
    assertFalse(
        g.changed(Collections.<Tracked>emptyList(), null, c, false, "net", 1, of("sunny", 72)));
    assertTrue(
        g.changed(Collections.<Tracked>emptyList(), null, c, false, "net", 2, of("sunny", 73)));
    assertTrue(g.changed(Collections.<Tracked>emptyList(), null, c, false, "net", 3, null));
  }
}
