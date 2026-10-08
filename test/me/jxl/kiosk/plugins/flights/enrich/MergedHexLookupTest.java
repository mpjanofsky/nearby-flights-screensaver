// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class MergedHexLookupTest {
  static Lookup returning(Info i) {
    return new Lookup() {
      public Info aircraft(String hex) {
        return i;
      }

      public Info route(String cs) {
        return null;
      }
    };
  }

  @Test
  void anAirframeUnknownToTheOwnerDatabaseStillGetsTheLiveCallsignRegistrationAndType()
      throws IOException {
    Info live = new Info("N774JS", "C25B", null, null, null, null).withCallsign("JRE774");
    MergedHexLookup m = new MergedHexLookup(returning(null), returning(live), null);
    Info i = m.aircraft("aa78bc");
    assertEquals("JRE774", i.callsign);
    assertEquals("N774JS", i.registration);
    assertEquals("C25B", i.type);
    assertNull(i.airlineIcao);
  }

  static Lookup failing() {
    return new Lookup() {
      public Info aircraft(String hex) throws IOException {
        throw new IOException("adsbdb timed out");
      }

      public Info route(String cs) {
        return null;
      }
    };
  }

  @Test
  void whenTheOwnerDatabaseFailsTheLiveAnswerWithACallsignStandsIn() throws IOException {
    Info live = new Info("N774JS", "C25B", null, null, null, null).withCallsign("JRE774");
    Info i = new MergedHexLookup(failing(), returning(live), null).aircraft("aa78bc");
    assertEquals("JRE774", i.callsign);
    assertEquals("N774JS", i.registration);
  }

  @Test
  void whenTheOwnerDatabaseFailsAndTheLiveAnswerHasNoCallsignTheLookupFails() {
    Info live = new Info("N774JS", "C25B", null, null, null, null);
    assertThrows(
        IOException.class,
        () -> new MergedHexLookup(failing(), returning(live), null).aircraft("aa78bc"));
    assertThrows(
        IOException.class,
        () -> new MergedHexLookup(failing(), failing(), null).aircraft("aa78bc"));
  }

  @Test
  void anIdThatIsNotARealHexAddressIsNeverLookedUp() throws IOException {
    assertNull(new MergedHexLookup(failing(), failing(), null).aircraft("~3ea5f2"));
  }
}
