package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {
  static Map<String, Object> ok() {
    Map<String, Object> m = new HashMap<>();
    m.put("homeLat", "27.5");
    m.put("homeLon", "-82.5");
    m.put("primarySource", "adsb.lol");
    m.put("fallbackSource", "None");
    return m;
  }

  @Test
  void defaultsAndUnits() {
    PluginSettings s = PluginSettings.parse(ok());
    assertNull(s.error);
    assertEquals(15 * 1852.0, s.config.radiusM, 1e-6);
    assertEquals("av", s.config.units);
    assertEquals(4, s.config.maxRows);
    assertEquals(10_000, s.refreshMs);
    Map<String, Object> m = ok();
    m.put("units", "Metric");
    m.put("radiusNm", 99.0);
    m.put("refreshSec", 1.0);
    s = PluginSettings.parse(m);
    assertEquals("met", s.config.units);
    assertEquals(50 * 1852.0, s.config.radiusM, 1e-6); // clamped
    assertEquals(5_000, s.refreshMs);
  }

  @Test
  void missingOrBadHomeIsAnErrorThatDoesNotEchoTheValue() {
    Map<String, Object> m = ok();
    m.put("homeLat", "27.5abc");
    assertNotNull(PluginSettings.parse(m).error);
    assertFalse(PluginSettings.parse(m).error.contains("27.5"));
    m.put("homeLat", "95");
    assertNotNull(PluginSettings.parse(m).error);
    m.put("homeLat", "0");
    m.put("homeLon", "0");
    assertNotNull(PluginSettings.parse(m).error); // unset default
    assertNotNull(PluginSettings.parse(new HashMap<String, Object>()).error);
  }

  @Test
  void localWithoutAUrlFallsBackToAdsbLolAloneOrErrors() {
    Map<String, Object> m = ok();
    m.put("primarySource", "Local feed");
    m.put("fallbackSource", "adsb.lol");
    PluginSettings s = PluginSettings.parse(m);
    assertNull(s.error);
    assertEquals("adsb.lol", s.primary);
    assertEquals("None", s.fallback);
    m.put("fallbackSource", "None");
    assertTrue(PluginSettings.parse(m).error.contains("local feed URL"));
    m.put("localUrl", "http://host:8080/data/aircraft.json");
    m.put("fallbackSource", "adsb.lol");
    s = PluginSettings.parse(m);
    assertEquals("Local feed", s.primary);
    assertEquals("adsb.lol", s.fallback);
  }

  @Test
  void mergedPrimaryHasNoFallbackAndNeedsALocalUrlElseRunsOnAdsbLol() {
    Map<String, Object> m = ok();
    m.put("primarySource", "Local + adsb.lol");
    m.put("fallbackSource", "adsb.lol");
    m.put("localUrl", "http://host:8080/data/aircraft.json");
    PluginSettings s = PluginSettings.parse(m);
    assertNull(s.error);
    assertEquals("Local + adsb.lol", s.primary);
    assertEquals("None", s.fallback);
    m.put("localUrl", "");
    s = PluginSettings.parse(m);
    assertNull(s.error);
    assertEquals("adsb.lol", s.primary);
    assertEquals("None", s.fallback);
  }

  @Test
  void identicalPrimaryAndFallbackCollapse() {
    Map<String, Object> m = ok();
    m.put("fallbackSource", "adsb.lol");
    assertEquals("None", PluginSettings.parse(m).fallback);
  }

  @Test
  void widenSettingsParseAndClamp() {
    Map<String, Object> m = ok();
    m.put("widenMode", "Soft");
    m.put("maxRadiusNm", 999.0);
    PluginSettings s = PluginSettings.parse(m);
    assertEquals(Config.Widen.SOFT, s.config.widen);
    assertEquals(250 * 1852.0, s.config.maxRadiusM, 1e-6);
    assertEquals(Config.Widen.HARD, PluginSettings.parse(ok()).config.widen);
  }

  @Test
  void aSettingsMessagePayloadMatchesTheGoldenFixture() throws Exception {
    org.json.JSONObject m =
        new org.json.JSONObject(PayloadEncoder.message("Set your home latitude and longitude", 1L));
    org.json.JSONObject g =
        new org.json.JSONObject(
            new String(
                java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("fixtures/payload/settings-error.json")),
                "UTF-8"));
    for (String k : new String[] {"v", "st", "src", "r", "u", "msg"}) {
      org.junit.jupiter.api.Assertions.assertEquals(g.get(k), m.get(k), k);
    }
    org.junit.jupiter.api.Assertions.assertEquals(0, m.getJSONArray("ac").length());
  }
}
