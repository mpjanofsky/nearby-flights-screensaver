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
}
