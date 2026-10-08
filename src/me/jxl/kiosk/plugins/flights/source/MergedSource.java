// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Both feeds at once. A home antenna hears only a fraction of the nearby traffic (measured
 * 2026-10-07: 8% within 15 nm), and a "list is full" test on that fraction would never ask the
 * internet feed, so the closest aircraft could be missing. This asks both every cycle, unions them
 * by hex, and keeps whichever copy of an aircraft has the fresher position, filling its gaps (the
 * local feed has no registration or type) from the other copy.
 *
 * <p>Either feed may fail on its own; the cycle fails only when both do. A rate-limited internet
 * feed is left alone until its Retry-After runs out, so the local feed keeps the screen going.
 */
public final class MergedSource implements SourceAdapter {
  private final SourceAdapter local, net;
  private final Clock clock;
  private long netCoolUntilMs;

  /** Time source, so tests need not sleep. */
  interface Clock {
    long nowMs();
  }

  public MergedSource(SourceAdapter local, SourceAdapter net) {
    this(local, net, System::currentTimeMillis);
  }

  MergedSource(SourceAdapter local, SourceAdapter net, Clock clock) {
    this.local = local;
    this.net = net;
    this.clock = clock;
  }

  @Override
  public String id() {
    return "mix";
  }

  @Override
  public FetchResult fetch(double homeLat, double homeLon, double radiusM) throws SourceException {
    FetchResult l = null, n = null;
    SourceException localErr = null, netErr = null;
    try {
      l = local.fetch(homeLat, homeLon, radiusM);
    } catch (SourceException e) {
      localErr = e; // the internet feed can still carry the screen
    }
    if (clock.nowMs() >= netCoolUntilMs) {
      try {
        n = net.fetch(homeLat, homeLon, radiusM);
      } catch (SourceException e) {
        netErr = e;
        if (e.retryAfterS > 0) netCoolUntilMs = clock.nowMs() + e.retryAfterS * 1000L;
      }
    }
    if (l == null && n == null) {
      SourceException e = netErr != null ? netErr : localErr;
      String msg =
          netErr == null
              ? localErr.getMessage() + " (internet feed waiting out a Retry-After)"
              : localErr.getMessage() + "; " + netErr.getMessage();
      throw new SourceException(msg, null, e.retryAfterS);
    }
    if (l == null) return n;
    if (n == null) return l;
    return new FetchResult(merge(l, n), l.observedAtMs, l.feedNow, l.feedMessages);
  }

  /** Pure: one entry per hex, the fresher fix, gaps filled from the other copy. */
  static List<Aircraft> merge(FetchResult l, FetchResult n) {
    // Ages are relative to each fetch's own arrival; a fetch can differ by a second or so, which
    // is far below the fix intervals that matter here.
    Map<String, Aircraft> byHex = new LinkedHashMap<>();
    for (Aircraft a : n.aircraft) byHex.put(a.hex, a);
    for (Aircraft a : l.aircraft) {
      Aircraft other = byHex.get(a.hex);
      byHex.put(a.hex, other == null ? a : fresher(a, other));
    }
    return new ArrayList<>(byHex.values());
  }

  private static Aircraft fresher(Aircraft local, Aircraft net) {
    boolean useNet = net.positionAgeS + 1.0 < local.positionAgeS; // ties go to the LAN feed
    Aircraft win = useNet ? net : local, lose = useNet ? local : net;
    return new Aircraft(
        win.hex,
        win.callsign != null ? win.callsign : lose.callsign,
        win.registration != null ? win.registration : lose.registration,
        win.type != null ? win.type : lose.type,
        win.category != null ? win.category : lose.category,
        win.lat,
        win.lon,
        Double.isNaN(win.altitudeM) && !win.onGround ? lose.altitudeM : win.altitudeM,
        Double.isNaN(win.groundSpeedMs) ? lose.groundSpeedMs : win.groundSpeedMs,
        Double.isNaN(win.trackDeg) ? lose.trackDeg : win.trackDeg,
        Double.isNaN(win.verticalRateMs) ? lose.verticalRateMs : win.verticalRateMs,
        win.positionAgeS,
        win.onGround);
  }
}
