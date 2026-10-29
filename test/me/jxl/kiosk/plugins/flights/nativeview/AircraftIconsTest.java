package me.jxl.kiosk.plugins.flights.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AircraftIconsTest {
  @Test
  void typesMapAsTheHtmlPageDoes() {
    assertEquals("b737", AircraftIcons.keyFor("b738"));
    assertEquals("cessna", AircraftIcons.keyFor("C172"));
    assertEquals("a7", AircraftIcons.keyFor("EC35")); // helicopter by prefix
    assertEquals("a0", AircraftIcons.keyFor("ZZZZ")); // generic jet
    assertEquals("a0", AircraftIcons.keyFor(null));
    assertEquals("a0", AircraftIcons.keyFor(""));
  }

  @Test
  void everyKeyTheLookupCanReturnHasAPath() {
    Set<String> baked = new HashSet<>();
    for (int i = 0; i + 2 < AircraftIcons.PATHS.length; i += 3) {
      baked.add(AircraftIcons.PATHS[i]);
      // IconPaths.parse reads only M, L, C and Z, each glued to its first number.
      assertTrue(AircraftIcons.PATHS[i + 2].matches("[MLCZ0-9. \\-]+"), AircraftIcons.PATHS[i]);
    }
    for (String ty : new String[] {"A320", "B77F", "GLF5", "DH8D", "C130", "ZZZZ", "R44"})
      assertTrue(baked.contains(AircraftIcons.keyFor(ty)), ty);
  }
}
