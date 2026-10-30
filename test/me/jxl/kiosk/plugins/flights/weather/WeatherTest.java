// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeatherTest {
  private static Map<String, Object> snap(String status, String state, Object temp, String unit) {
    Map<String, Object> attrs = new HashMap<>();
    if (temp != null) attrs.put("temperature", temp);
    if (unit != null) attrs.put("temperature_unit", unit);
    Map<String, Object> m = new HashMap<>();
    m.put("status", status);
    m.put("state", state);
    m.put("attributes", attrs);
    return m;
  }

  @Test
  void fahrenheitIsConvertedToCelsiusAndRemembered() {
    Weather w = Weather.fromSnapshot(snap("available", "partlycloudy", 72, "°F"));
    assertEquals(22.2, w.tempC, 0.05);
    assertTrue(w.fahrenheit);
    assertEquals("partlycloudy", w.condition);
  }

  @Test
  void celsiusAndKelvinPassThrough() {
    assertEquals(18.5, Weather.fromSnapshot(snap("available", "sunny", 18.5, "°C")).tempC, 1e-9);
    assertEquals(0, Weather.fromSnapshot(snap("available", "sunny", 273.15, "K")).tempC, 1e-9);
  }

  @Test
  void anUnavailableOrMissingEntityIsNeverZeroDegrees() {
    assertNull(Weather.fromSnapshot(snap("unavailable", "unavailable", null, null)));
    assertNull(Weather.fromSnapshot(snap("missing", null, null, null)));
    assertNull(Weather.fromSnapshot(null));
    assertNull(Weather.fromSnapshot(Collections.<String, Object>emptyMap()));
  }

  @Test
  void noTemperatureOrAnUnknownUnitGivesNothingRatherThanAGuess() {
    assertNull(Weather.fromSnapshot(snap("available", "sunny", null, "°C")));
    assertNull(Weather.fromSnapshot(snap("available", "sunny", 20, "furlongs")));
    assertNull(Weather.fromSnapshot(snap("available", "sunny", "warm", "°C")));
  }

  @Test
  void anUnknownConditionKeepsTheTemperature() {
    Weather w = Weather.fromSnapshot(snap("available", "volcanic-ash", 20, "°C"));
    assertNull(w.condition);
    assertEquals(20, w.tempC, 1e-9);
  }

  private static Map<String, Object> withWind(Object speed, Object unit, Object bearing) {
    Map<String, Object> s = snap("available", "sunny", 20, "°C");
    @SuppressWarnings("unchecked")
    Map<String, Object> attrs = (Map<String, Object>) s.get("attributes");
    if (speed != null) attrs.put("wind_speed", speed);
    if (unit != null) attrs.put("wind_speed_unit", unit);
    if (bearing != null) attrs.put("wind_bearing", bearing);
    return s;
  }

  @Test
  void windIsConvertedToMetresPerSecondWithItsUnitAndBearingKept() {
    Weather w = Weather.fromSnapshot(withWind(18, "km/h", 315));
    assertEquals(5.0, w.windMs, 1e-9);
    assertEquals("km/h", w.windUnit);
    assertEquals(315, w.windDeg, 1e-9);
    assertEquals(4.47, Weather.fromSnapshot(withWind(10, "mph", 0)).windMs, 0.01);
    assertEquals(5.14, Weather.fromSnapshot(withWind(10, "kn", 0)).windMs, 0.01);
  }

  @Test
  void aBearingMayBeACompassPointOrANumericString() {
    assertEquals(315, Weather.fromSnapshot(withWind(5, "m/s", "NW")).windDeg, 1e-9);
    assertEquals(22.5, Weather.fromSnapshot(withWind(5, "m/s", "nne")).windDeg, 1e-9);
    assertEquals(90, Weather.fromSnapshot(withWind(5, "m/s", "90")).windDeg, 1e-9);
    assertEquals(10, Weather.fromSnapshot(withWind(5, "m/s", -350)).windDeg, 1e-9);
    assertTrue(Double.isNaN(Weather.fromSnapshot(withWind(5, "m/s", "upwind")).windDeg));
    assertTrue(Double.isNaN(Weather.fromSnapshot(withWind(5, "m/s", null)).windDeg));
  }

  @Test
  void noSpeedNegativeSpeedOrAnUnknownUnitLeavesWindOutAndKeepsTheTemperature() {
    for (Map<String, Object> s :
        new Map[] {
          withWind(null, "mph", 90),
          withWind(-1, "mph", 90),
          withWind(5, "furlongs", 90),
          withWind(5, null, 90),
          withWind("fast", "mph", 90)
        }) {
      Weather w = Weather.fromSnapshot(s);
      assertEquals(20, w.tempC, 1e-9);
      assertTrue(Double.isNaN(w.windMs));
      assertNull(w.windUnit);
    }
  }

  @Test
  void onlyWeatherEntityIdsAreFollowed() {
    assertTrue(HaWeatherSource.valid("weather.openweathermap"));
    assertFalse(HaWeatherSource.valid("sensor.outdoor_temperature"));
    assertFalse(HaWeatherSource.valid("weather."));
    assertFalse(HaWeatherSource.valid(""));
    assertFalse(HaWeatherSource.valid(null));
  }
}
