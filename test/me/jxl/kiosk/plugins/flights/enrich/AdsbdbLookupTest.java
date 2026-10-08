package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class AdsbdbLookupTest {
  // Shapes recorded from api.adsbdb.com on 2026-10-06, trimmed.
  static final String ROUTE =
      "{\"response\":{\"flightroute\":{\"callsign\":\"DAL800\",\"airline\":{\"name\":\"Delta Air"
          + " Lines\","
          + "\"icao\":\"DAL\",\"iata\":\"DL\"},\"origin\":{\"iata_code\":\"DEN\",\"icao_code\":\"KDEN\"},"
          + "\"destination\":{\"iata_code\":\"ATL\",\"icao_code\":\"KATL\"}}}}";
  static final String AIRCRAFT =
      "{\"response\":{\"aircraft\":{\"type\":\"A321 271NXSL\",\"icao_type\":\"A21N\","
          + "\"registration\":\"N507DZ\",\"registered_owner\":\"Delta Air Lines\"}}}";

  @Test
  void parsesARoute() throws IOException {
    Info i = AdsbdbLookup.parseRoute(ROUTE);
    assertEquals("DAL", i.airlineIcao);
    assertEquals("Delta Air Lines", i.airlineName);
    assertEquals("DEN", i.origin);
    assertEquals("ATL", i.destination);
  }

  @Test
  void routeFallsBackToIcaoWhenNoIata() throws IOException {
    Info i =
        AdsbdbLookup.parseRoute(
            "{\"response\":{\"flightroute\":{\"origin\":{\"iata_code\":null,\"icao_code\":\"KXYZ\"}}}}");
    assertEquals("KXYZ", i.origin);
    assertNull(i.destination);
    assertNull(i.airlineIcao);
  }

  @Test
  void parsesAnAircraft() throws IOException {
    Info i = AdsbdbLookup.parseAircraft(AIRCRAFT);
    assertEquals("N507DZ", i.registration);
    assertEquals("A21N", i.type);
  }

  @Test
  void anAirframeOwnedByAnAirlineYieldsItsOperatorCode() throws IOException {
    Info i =
        AdsbdbLookup.parseAircraft(
            "{\"response\":{\"aircraft\":{\"icao_type\":\"A319\",\"registration\":\"N9006\",\"registered_owner_operator_flag_code\":\"AAL\",\"registered_owner\":\"American"
                + " Airlines\"}}}");
    assertEquals("AAL", i.airlineIcao);
    assertEquals("American Airlines", i.airlineName);
    Info odd =
        AdsbdbLookup.parseAircraft(
            "{\"response\":{\"aircraft\":{\"registered_owner_operator_flag_code\":\"\",\"registered_owner\":\"A"
                + " Person\"}}}");
    assertNull(odd.airlineIcao); // no operator code: the owner is not an airline
    assertNull(odd.airlineName); // a private owner's name is never kept
    Info mil =
        AdsbdbLookup.parseAircraft(
            "{\"response\":{\"aircraft\":{\"icao_type\":\"K35T\",\"registration\":\"58-0042\",\"registered_owner_operator_flag_code\":\"K35T\",\"registered_owner\":\"United"
                + " States Air Force\"}}}");
    assertNull(mil.airlineIcao); // the type code is not an operator
    assertEquals("United States Air Force", mil.airlineName);
  }

  @Test
  void anUnknownResponseIsAMissAndGarbageIsAFailure() throws IOException {
    assertNull(AdsbdbLookup.parseRoute("{\"response\":\"unknown callsign\"}"));
    assertThrows(IOException.class, () -> AdsbdbLookup.parseAircraft("<html>"));
  }

  @Test
  void anAirlineAnswerIsAListAndTheFirstNamedEntryWins() throws IOException {
    Info i =
        AdsbdbLookup.parseAirline(
            "{\"response\":[{\"name\":\"VivaAerobus\",\"icao\":\"VIV\"}]}", "VIV");
    assertEquals("VIV", i.airlineIcao);
    assertEquals("VivaAerobus", i.airlineName);
    assertNull(AdsbdbLookup.parseAirline("{\"response\":[]}", "ZZZ"));
    assertNull(AdsbdbLookup.parseAirline("{\"response\":\"unknown airline\"}", "ZZZ"));
  }
}
