// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AirportsTest {
  // Three points 0, ~11 and ~111 km north of the origin.
  private static final Airports SYNTH =
      new Airports("AAA 0.000 0.000 1;BBB 0.100 0.000 2;CCC 1.000 0.000 2");

  @Test
  void filtersToRadiusAndProjectsFromHome() {
    List<Airports.Marker> m = SYNTH.within(0, 0, 20000);
    assertEquals(2, m.size());
    assertEquals("BBB", m.get(0).code); // large before medium
    assertEquals(0, m.get(0).x, 1);
    assertEquals(11119, m.get(0).y, 30);
  }

  @Test
  void capsAtEightPreferringLargeThenNear() {
    StringBuilder r = new StringBuilder();
    for (int i = 0; i < 12; i++)
      r.append(i == 0 ? "" : ";")
          .append("M")
          .append(i)
          .append(" 0.0")
          .append(i)
          .append("0 0.000 1");
    r.append(";BIG 0.500 0.000 2");
    List<Airports.Marker> m = new Airports(r.toString()).within(0, 0, 100000);
    assertEquals(8, m.size());
    assertEquals("BIG", m.get(0).code);
    assertEquals("M0", m.get(1).code);
  }

  @Test
  void handlesDateLineAndEmptyList() {
    assertEquals(1, new Airports("ZZZ 0.000 -179.950 1").within(0, 179.950, 20000).size());
    assertTrue(new Airports("").within(0, 0, 50000).isEmpty());
  }

  @Test
  void bundledDataFindsDenverFromNearby() {
    List<Airports.Marker> m = new Airports().within(39.80, -104.70, 50000);
    assertTrue(m.stream().anyMatch(a -> a.code.equals("DEN")), "DEN within 50 km");
    assertTrue(m.size() <= 8);
  }

  @Test
  void encoderWritesMarkers() {
    Config c = new Config(0, 0, 20000, "av", 4, false);
    String json =
        PayloadEncoder.encode(
            List.of(),
            c,
            new PayloadEncoder.Health(1, false, "local"),
            1000,
            1000,
            null,
            SYNTH.within(0, 0, 20000));
    assertTrue(
        json.contains(
            "\"ap\":[{\"c\":\"BBB\",\"x\":0,\"y\":11120},{\"c\":\"AAA\",\"x\":0,\"y\":0}]"),
        json);
  }
}
