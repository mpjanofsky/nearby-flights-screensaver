package me.jxl.kiosk.plugins.flights.source;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FeedWatchTest {
  @Test
  void anAdvancingFeedIsHealthy() {
    FeedWatch w = new FeedWatch();
    for (int i = 0; i < 20; i++) {
      final int n = i;
      assertDoesNotThrow(() -> w.check(1000 + n, 500 + n, n * 5000L));
    }
  }

  @Test
  void aFrozenTimestampFailsOnTheThirdIdenticalFetch() throws Exception {
    FeedWatch w = new FeedWatch();
    w.check(1000, 1, 0);
    w.check(1001, 2, 5000);
    w.check(1001, 3, 10000);
    assertThrows(SourceException.class, () -> w.check(1001, 4, 15000));
  }

  @Test
  void aFrozenCounterFailsOnlyAfterTheLongWindow() throws Exception {
    FeedWatch w = new FeedWatch();
    w.check(1, 700, 0);
    for (int i = 1; i < 18; i++) {
      final int n = i;
      assertDoesNotThrow(() -> w.check(1 + n, 700, n * 5000L));
    }
    assertThrows(SourceException.class, () -> w.check(100, 700, 90_000));
  }

  @Test
  void recoveryClearsTheFailure() throws Exception {
    FeedWatch w = new FeedWatch();
    w.check(1, 1, 0);
    w.check(1, 1, 5000);
    assertThrows(SourceException.class, () -> w.check(1, 1, 10000));
    assertDoesNotThrow(() -> w.check(2, 2, 15000));
  }

  @Test
  void feedsWithoutTheFieldsAreNotWatched() {
    FeedWatch w = new FeedWatch();
    for (int i = 0; i < 50; i++) {
      final int n = i;
      assertDoesNotThrow(() -> w.check(Double.NaN, Double.NaN, n * 5000L));
    }
  }
}
