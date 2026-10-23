// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

import java.util.function.LongSupplier;

/**
 * One "slow down" shared by everything that talks to adsb.lol: position polls, the wider Soft
 * radius probes and the hex lookups. The server limits per client, not per endpoint, so a 429 on
 * one request means every other request is also wasted (and keeps the limit tripped). Honours
 * Retry-After; without it waits at least {@link #MIN_MS}, doubling on consecutive 429s up to {@link
 * #MAX_MS}. Any success resets the doubling.
 */
public final class NetCooldown {
  static final long MIN_MS = 60_000, MAX_MS = 300_000;

  private final LongSupplier clock;
  private long untilMs;
  private int strikes;

  public NetCooldown() {
    this(System::currentTimeMillis);
  }

  public NetCooldown(LongSupplier clock) {
    this.clock = clock;
  }

  /** Seconds left to wait (rounded up), or 0 when requests may go. */
  public synchronized int remainingS() {
    long left = untilMs - clock.getAsLong();
    return left <= 0 ? 0 : (int) ((left + 999) / 1000);
  }

  /** The server refused with 429 (or 503): wait out its Retry-After, or our own minimum. */
  public synchronized void trip(int retryAfterS) {
    long own = Math.min(MAX_MS, MIN_MS << Math.min(strikes, 3));
    strikes++;
    long waitMs = Math.max(retryAfterS * 1000L, own);
    untilMs = Math.max(untilMs, clock.getAsLong() + waitMs);
  }

  /** A request succeeded: forget the doubling. */
  public synchronized void ok() {
    strikes = 0;
  }
}
