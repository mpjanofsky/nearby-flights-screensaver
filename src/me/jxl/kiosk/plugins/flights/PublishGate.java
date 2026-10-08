// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.List;

/**
 * Decides whether a refresh needs a publish. Time-dependent fields (`gen`, ages, fetch age) are
 * left out of the comparison: the renderer derives staleness from the last publish plus the clock,
 * so an unchanged sky needs no new document. Positions compare at 10 m resolution. A heartbeat
 * republish every {@link #HEARTBEAT_MS} keeps the renderer's fetch-age rule (stale after 120 s,
 * docs/payload.md) from flagging healthy but unchanging data.
 */
public final class PublishGate {
  static final long HEARTBEAT_MS = 60_000;
  private String last;
  private long lastPublishMs = Long.MIN_VALUE;

  /** Forgets the last publish so the next refresh publishes (after the screen was replaced). */
  public synchronized void reset() {
    last = null;
  }

  /**
   * True when the content differs from the last publish or the heartbeat is due. Records it when
   * true.
   */
  public synchronized boolean changed(
      List<Tracked> rows, String featuredId, Config c, boolean stale, String sourceId, long nowMs) {
    StringBuilder s = new StringBuilder();
    s.append(featuredId)
        .append('|')
        .append(stale)
        .append('|')
        .append(sourceId)
        .append('|')
        .append(Math.round(c.radiusM))
        .append(c.units);
    for (Tracked t : rows) {
      s.append('|')
          .append(t.aircraft.hex)
          .append(',')
          .append(Math.round(t.x / 10))
          .append(',')
          .append(Math.round(t.y / 10))
          .append(',')
          .append(Math.round(t.aircraft.altitudeM / 10))
          .append(',')
          .append(t.aircraft.callsign)
          .append(',')
          .append(t.info.signature());
    }
    String sig = s.toString();
    boolean quiet = sig.equals(last) && nowMs - lastPublishMs < HEARTBEAT_MS;
    if (quiet) return false;
    last = sig;
    lastPublishMs = nowMs;
    return true;
  }
}
