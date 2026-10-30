// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.flights.weather.Weather;
import org.junit.jupiter.api.Test;

class PayloadWeatherTest {
  private static Weather of(String state, Object temp, String unit) {
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("temperature", temp);
    attrs.put("temperature_unit", unit);
    Map<String, Object> snap = new HashMap<>();
    snap.put("status", "available");
    snap.put("state", state);
    snap.put("attributes", attrs);
    return Weather.fromSnapshot(snap);
  }

  private static Weather of(
      String state, Object temp, String unit, Object wind, String windUnit, Object bearing) {
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("temperature", temp);
    attrs.put("temperature_unit", unit);
    attrs.put("wind_speed", wind);
    attrs.put("wind_speed_unit", windUnit);
    if (bearing != null) attrs.put("wind_bearing", bearing);
    Map<String, Object> snap = new HashMap<>();
    snap.put("status", "available");
    snap.put("state", state);
    snap.put("attributes", attrs);
    return Weather.fromSnapshot(snap);
  }

  @Test
  void wxCarriesConditionDegreesCelsiusAndTheDisplayUnit() {
    assertEquals(
        "{\"c\":\"partlycloudy\",\"t\":22.2,\"tu\":\"F\"}",
        PayloadEncoder.wx(of("partlycloudy", 72, "°F")));
    assertEquals(
        "{\"c\":\"sunny\",\"t\":18.5,\"tu\":\"C\"}", PayloadEncoder.wx(of("sunny", 18.5, "°C")));
  }

  @Test
  void shListsThePiecesOnlyWhenNotAll() {
    Weather w = of("sunny", 18.5, "°C");
    assertEquals(
        "{\"c\":\"sunny\",\"t\":18.5,\"tu\":\"C\",\"sh\":\"tc\"}",
        PayloadEncoder.wx(w.showing("tc")));
    assertEquals("{\"c\":\"sunny\",\"t\":18.5,\"tu\":\"C\"}", PayloadEncoder.wx(w.showing("tci")));
  }

  @Test
  void windCarriesMetresPerSecondTheUnitAndTheBearingWhenKnown() {
    Weather w = of("sunny", 18.5, "°C", 18, "km/h", "NW");
    assertEquals(
        "{\"c\":\"sunny\",\"t\":18.5,\"tu\":\"C\",\"w\":5,\"wu\":\"km/h\",\"wd\":315}",
        PayloadEncoder.wx(w));
    assertEquals(
        "{\"c\":\"sunny\",\"t\":18.5,\"tu\":\"C\",\"w\":5,\"wu\":\"km/h\"," + "\"sh\":\"tcwd\"}",
        PayloadEncoder.wx(of("sunny", 18.5, "°C", 18, "km/h", null).showing("tcwd")));
  }

  @Test
  void anUnknownConditionLeavesOutC() {
    assertEquals("{\"t\":20,\"tu\":\"C\"}", PayloadEncoder.wx(of("volcanic-ash", 20, "°C")));
  }
}
