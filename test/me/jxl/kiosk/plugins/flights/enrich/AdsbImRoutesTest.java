package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class AdsbImRoutesTest {
  // Shapes recorded from adsb.im/api/0/route on 2026-10-06 (D2), trimmed to the fields we read.
  static final String ROUTE =
      "{\"_airports\":[{\"iata\":\"FLL\",\"icao\":\"KFLL\",\"lat\":26.072599,\"lon\":-80.152702},"
          + "{\"iata\":\"LAX\",\"icao\":\"KLAX\",\"lat\":33.942501,\"lon\":-118.407997}],"
          + "\"airline_code\":\"DAL\",\"airport_codes\":\"KFLL-KLAX\",\"callsign\":\"DAL800\"}";
  static final String UNKNOWN =
      "{\"_airports\":[],\"airline_code\":\"unknown\",\"airport_codes\":\"unknown\","
          + "\"callsign\":\"NOSUCH99\"}";

  @Test
  void parsesARouteWithAirportPositions() throws IOException {
    Info i = AdsbImRoutes.parseRoute(ROUTE);
    assertEquals("FLL", i.origin);
    assertEquals("LAX", i.destination);
    assertEquals("DAL", i.airlineIcao);
    assertNull(i.airlineName);
    assertEquals(26.072599, i.originLat, 1e-6);
    assertEquals(-118.407997, i.destLon, 1e-6);
  }

  @Test
  void unknownCallsignIsAMiss() throws IOException {
    assertNull(AdsbImRoutes.parseRoute(UNKNOWN));
  }

  @Test
  void multiStopRoutesAreShownAsTheChain() throws IOException {
    String three =
        ROUTE.replace(
            "],\"airline_code", ",{\"iata\":\"SFO\",\"lat\":37.6,\"lon\":-122.4}],\"airline_code");
    Info i = AdsbImRoutes.parseRoute(three);
    assertEquals("FLL", i.origin);
    assertEquals("LAX \u2192 SFO", i.destination);
    assertEquals(6, i.path.length);
    assertEquals(3, i.stops.length);
    assertNull(AdsbImRoutes.parseRoute(ROUTE).path); // a plain route needs none
  }

  @Test
  void aChainOfFiveOrMoreIsShortenedToItsEnds() throws IOException {
    String stop = ",{\"iata\":\"XXX\",\"lat\":30.0,\"lon\":-90.0}";
    String five = ROUTE.replace("],\"airline_code", stop + stop + stop + "],\"airline_code");
    Info i = AdsbImRoutes.parseRoute(five);
    assertEquals("FLL", i.origin);
    assertEquals("XXX", i.destination); // last of the five
    assertEquals(10, i.path.length); // but every airport still counts for plausibility
    assertEquals(5, i.stops.length);
  }

  @Test
  void airportWithoutIataFallsBackToIcao() throws IOException {
    Info i = AdsbImRoutes.parseRoute(ROUTE.replace("\"iata\":\"FLL\"", "\"iata\":\"\""));
    assertEquals("KFLL", i.origin);
  }

  @Test
  void nonJsonIsAFailureNotAMiss() {
    assertThrows(IOException.class, () -> AdsbImRoutes.parseRoute("<html>"));
  }

  @Test
  void batchRequestCarriesCallsignsOnly() throws Exception {
    String body = AdsbImRoutes.requestBody(java.util.Arrays.asList("DAL800", "UAL12"));
    org.json.JSONObject o = new org.json.JSONObject(body);
    org.json.JSONArray planes = o.getJSONArray("planes");
    assertEquals(2, planes.length());
    assertEquals("DAL800", planes.getJSONObject(0).getString("callsign"));
    assertEquals(1, planes.getJSONObject(1).length(), "no lat/lng or anything else is sent");
    assertEquals("http://adsb.im/api/0/routeset", AdsbImRoutes.URL);
  }

  @Test
  void batchAnswerIsMatchedByPositionAndMissesAreSkipped() throws IOException {
    java.util.Map<String, Info> out = new java.util.HashMap<>();
    AdsbImRoutes.parseRoutes(
        "[" + ROUTE + ",null,\"unknown\"," + UNKNOWN + "]",
        java.util.Arrays.asList("DAL800", "A1", "A2", "NOSUCH99"),
        out);
    assertEquals(1, out.size());
    assertEquals("FLL", out.get("DAL800").origin);
  }

  @Test
  void batchAnswerOfTheWrongLengthOrShapeFailsTheBatch() {
    java.util.Map<String, Info> out = new java.util.HashMap<>();
    assertThrows(
        IOException.class,
        () -> AdsbImRoutes.parseRoutes("[" + ROUTE + "]", java.util.Arrays.asList("A", "B"), out));
    assertThrows(
        IOException.class,
        () -> AdsbImRoutes.parseRoutes("{\"detail\":\"x\"}", java.util.Arrays.asList("A"), out));
  }
}
