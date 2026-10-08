// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * A local receiver often reports an aircraft with no callsign; adsb.lol's hex lookup supplies it.
 */
class CallsignBackfillTest {
  static final String LOL =
      "{\"ac\":[{\"hex\":\"ad44bc\",\"flight\":\"AAL436 "
          + " \",\"r\":\"N954NN\",\"t\":\"B738\"}],\"total\":1}";

  static final Lookup OWNER =
      new Lookup() {
        public Info aircraft(String hex) {
          return new Info("N954NN", "B738", "AAL", "American Airlines", null, null);
        }

        public Info route(String cs) {
          return null;
        }
      };

  static Lookup routes(java.util.List<String> asked) {
    return new Lookup() {
      public Info aircraft(String hex) {
        return null;
      }

      public Info route(String cs) {
        asked.add(cs);
        return new Info(null, null, "AAL", null, "DFW", "MCO");
      }
    };
  }

  @Test
  void parsesTheCallsignAndTrimsIt() throws IOException {
    Info i = AdsbLolHex.parse(LOL);
    assertEquals("AAL436", i.callsign);
    assertEquals("N954NN", i.registration);
    assertNull(AdsbLolHex.parse("{\"ac\":[],\"total\":0}"));
  }

  @Test
  void aCallsignFromTheHexLookupLeadsToTheRoute() {
    java.util.List<String> asked = new java.util.ArrayList<>();
    Lookup live =
        new Lookup() {
          public Info aircraft(String hex) throws IOException {
            return AdsbLolHex.parse(LOL);
          }

          public Info route(String cs) {
            return null;
          }
        };
    Enricher e = new Enricher(new MergedHexLookup(OWNER, live, routes(asked)));
    me.jxl.kiosk.plugins.flights.source.Aircraft a = EnricherTest.ac("ad44bc", null, null, null);
    assertNull(e.infoFor(a, 0).callsign);
    long now = EnricherTest.drain(e, 0); // the hex lookup
    e.infoFor(a, now); // the next tick sees the callsign and queues its route
    now = EnricherTest.drain(e, now);
    Info i = e.infoFor(a, now);
    assertEquals("AAL436", i.callsign);
    assertEquals("DFW", i.origin);
    assertEquals(java.util.List.of("AAL436"), asked);
  }

  @Test
  void aDeadSecondarySourceStillGivesTheOwnersAnswer() throws IOException {
    Lookup down =
        new Lookup() {
          public Info aircraft(String hex) throws IOException {
            throw new IOException("adsb.lol HTTP 429");
          }

          public Info route(String cs) {
            return null;
          }
        };
    Info i =
        new MergedHexLookup(OWNER, down, routes(new java.util.ArrayList<>())).aircraft("ad44bc");
    assertEquals("AAL", i.airlineIcao);
    assertNull(i.callsign);
  }

  static Lookup unknownRoute() {
    return new Lookup() {
      public Info aircraft(String hex) {
        return null;
      }

      public Info route(String cs) {
        return null;
      }
    };
  }
}
