package me.jxl.kiosk.plugins.flights.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import me.jxl.kiosk.plugins.flights.enrich.AdsbLolHex;
import org.junit.jupiter.api.Test;

class NetCooldownTest {
  private final AtomicLong now = new AtomicLong(1_000_000);
  private final NetCooldown c = new NetCooldown(now::get);

  @Test
  void startsOpen() {
    assertEquals(0, c.remainingS());
  }

  @Test
  void aBare429WaitsTheMinimumAndThenReopens() {
    c.trip(0);
    assertEquals(60, c.remainingS());
    now.addAndGet(59_000);
    assertEquals(1, c.remainingS());
    now.addAndGet(1_000);
    assertEquals(0, c.remainingS());
  }

  @Test
  void aLongerRetryAfterWins() {
    c.trip(200);
    assertEquals(200, c.remainingS());
  }

  @Test
  void aShortRetryAfterIsRaisedToTheMinimum() {
    c.trip(2);
    assertEquals(60, c.remainingS());
  }

  @Test
  void repeatedTripsDoubleUpToTheCapAndASuccessResets() {
    long[] want = {60, 120, 240, 300, 300};
    for (long w : want) {
      c.trip(0);
      assertEquals(w, c.remainingS());
      now.addAndGet(w * 1000);
    }
    c.ok();
    c.trip(0);
    assertEquals(60, c.remainingS());
  }

  @Test
  void aTrippedCooldownStopsPositionAndHexRequestsWithoutAnyNetwork() {
    c.trip(0);
    SourceException e =
        assertThrows(
            SourceException.class, () -> ReadsbJsonSource.adsbLol("test", c).fetch(1, 2, 20000));
    assertTrue(e.retryAfterS > 0);
    assertThrows(IOException.class, () -> new AdsbLolHex("test", c).aircraft("abc123"));
  }
}
