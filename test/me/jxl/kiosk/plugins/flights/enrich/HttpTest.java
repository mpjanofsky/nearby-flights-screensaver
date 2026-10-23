package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HttpTest {
  @Test
  void retryAfterIsWholeSecondsAndAnythingElseIsZero() {
    assertEquals(30, Http.retryAfterS(" 30 "));
    assertEquals(3600, Http.retryAfterS("99999"));
    assertEquals(0, Http.retryAfterS(null));
    assertEquals(0, Http.retryAfterS("Wed, 21 Oct 2026 07:28:00 GMT"));
  }
}
