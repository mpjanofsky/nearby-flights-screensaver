// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SubToShowTest {
  private static final Model.Measure TEN = s -> s.length() * 10f;

  private static Model.Row row(String an, String ty, String typeName) {
    Model.Ac a = new Model.Ac();
    a.an = an;
    a.ty = ty;
    Model.Row r = new Model.Row();
    r.ac = a;
    r.type = typeName;
    return r;
  }

  @Test
  void fullTypeNameWhenItFits() {
    Model.Row r = row("Allegiant Air", "A320", "Airbus A320");
    assertEquals("Allegiant Air · Airbus A320", Model.subToShow(r, 400, TEN));
  }

  @Test
  void typeCodeWhenTheNameDoesNot() {
    Model.Row r = row("Transportes Aereos I.R. Crusoe", "A319", "Airbus A319");
    // full is 42 chars (420 px), with the code 36 chars (360 px)
    assertEquals("Transportes Aereos I.R. Crusoe · A319", Model.subToShow(r, 380, TEN));
  }

  @Test
  void airlineCutBeforeTheTypeIsLost() {
    Model.Row r = row("Transportes Aereos I.R. Crusoe", "A319", "Airbus A319");
    String s = Model.subToShow(r, 250, TEN);
    assertEquals(true, s.endsWith("… · A319"));
    assertEquals(true, TEN.width(s) <= 250);
  }

  @Test
  void rowsWithOnlyOnePartAreLeftAlone() {
    assertEquals("Allegiant Air", Model.subToShow(row("Allegiant Air", null, ""), 50, TEN));
    assertEquals("Airbus A320", Model.subToShow(row(null, "A320", "Airbus A320"), 50, TEN));
  }
}
