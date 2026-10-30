// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RouteToShowTest {
  // Ten pixels a character, so the widths are easy to read in the assertions.
  private static final Model.Measure TEN = s -> s.length() * 10f;

  private static Model.Ac ac(String o, String d, String oc, String dc) {
    Model.Ac a = new Model.Ac();
    a.o = o;
    a.d = d;
    a.oc = oc;
    a.dc = dc;
    return a;
  }

  @Test
  void citiesWhenTheyFitElseCodes() {
    Model.Ac a = ac("LGW", "AGP", "London", "Málaga");
    String codes = Model.route(a);
    // 60 + 60 text + one arrow of 1.55 x 20 = 151
    assertEquals("London → Málaga", Model.routeToShow(a, codes, 20, 152, TEN).text);
    assertEquals(150f / 151f, Model.routeToShow(a, codes, 20, 150, TEN).scale, 0.001);
  }

  @Test
  void shrinksToFitDownToTheFloorThenFallsBackToCodes() {
    Model.Ac a = ac("LGW", "AGP", "London", "M\u00e1laga"); // 151 px wide at size 20
    String codes = Model.route(a);
    Model.RouteFit tight = Model.routeToShow(a, codes, 20, 135, TEN); // 135 / 151 = 0.894
    assertEquals("London \u2192 M\u00e1laga", tight.text);
    assertEquals(135f / 151f, tight.scale, 0.001);
    Model.RouteFit tooSmall = Model.routeToShow(a, codes, 20, 120, TEN); // 0.795, under 0.85
    assertEquals(codes, tooSmall.text);
    assertEquals(1f, tooSmall.scale, 0);
  }

  @Test
  void noCityDataMeansCodes() {
    Model.Ac a = ac("LGW", "AGP", null, null);
    assertEquals("LGW → AGP", Model.routeToShow(a, Model.route(a), 20, 1000, TEN).text);
  }

  @Test
  void oneEndWithACityMixesCityAndCode() {
    Model.Ac a = ac("LGW", "XYZ", "London", null);
    assertEquals("London → XYZ", Model.routeToShow(a, Model.route(a), 20, 1000, TEN).text);
  }

  @Test
  void openRouteKeepsItsOpenArrow() {
    Model.Ac a = ac("LGW", null, "London", null);
    assertEquals("London → ", Model.routeToShow(a, Model.route(a), 20, 1000, TEN).text);
  }

  @Test
  void aFadingOldRouteStaysAsCodes() {
    Model.Ac a = ac("LGW", "AGP", "London", "Málaga");
    assertEquals("ATL → MCO", Model.routeToShow(a, "ATL → MCO", 20, 1000, TEN).text);
  }
}
