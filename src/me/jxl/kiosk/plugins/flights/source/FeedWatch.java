// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

/**
 * Notices a LAN feed that still answers but has stopped updating (readsb hung, or the SDR died
 * while the web server lives on). Without this, every such fetch counts as a success: blips drift
 * and snap back, or the screen shows "No aircraft" for a sky that is not empty.
 *
 * <p>Two signals, both only compared with the feed's own earlier values (units never matter): `now`
 * frozen for {@link #FROZEN_NOW_FETCHES} fetches, or the cumulative `messages` counter frozen for
 * {@link #FROZEN_MESSAGES_MS}. The counter gets a long window because a receiver in a very quiet
 * sky can legitimately hear nothing for a while.
 */
final class FeedWatch {
  static final int FROZEN_NOW_FETCHES = 3;
  static final long FROZEN_MESSAGES_MS = 90_000;

  private double lastNow = Double.NaN, lastMessages = Double.NaN;
  private int sameNow;
  private long messagesSinceMs;

  /**
   * @throws SourceException when the feed has stopped advancing
   */
  void check(double now, double messages, long nowMs) throws SourceException {
    if (!Double.isNaN(now)) {
      sameNow = now == lastNow ? sameNow + 1 : 0;
      lastNow = now;
      if (sameNow >= FROZEN_NOW_FETCHES - 1) {
        throw new SourceException("local feed is not updating (timestamp frozen)");
      }
    }
    if (!Double.isNaN(messages)) {
      if (messages != lastMessages || Double.isNaN(lastMessages)) messagesSinceMs = nowMs;
      lastMessages = messages;
      if (nowMs - messagesSinceMs >= FROZEN_MESSAGES_MS) {
        throw new SourceException("local feed is not updating (no new messages)");
      }
    }
  }
}
