// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-memory counters on how well enrichment is doing, to check what the display shows against what
 * the services return: lookup outcomes and latency, how complete the shown rows are, and how long a
 * row takes to get its route. Nothing here is a location or a URL: only counts, durations and hex
 * ids. Read through {@link #brief} (status line), {@link #summary} (periodic log) and {@link
 * #drainEvents} (one line per aircraft that left the display).
 */
public final class EnrichStats {
  private static final class Svc {
    int hit, miss, err;
    long totalMs, maxMs;

    void add(long ms, Outcome o) {
      if (o == Outcome.HIT) hit++;
      else if (o == Outcome.MISS) miss++;
      else err++;
      totalMs += ms;
      maxMs = Math.max(maxMs, ms);
    }

    int n() {
      return hit + miss + err;
    }
  }

  public enum Outcome {
    HIT,
    MISS,
    ERROR
  }

  private final Svc hexSvc = new Svc(), routeSvc = new Svc();
  private int callsignFilled; // hex lookups that supplied a callsign the feed lacked
  private long rowSamples, routeRows, withRoute, withAirline, withReg, withCallsign;
  private int routeResolved, leftNoRouteWithCs, leftNoRouteNoCs;
  private long routeMsTotal, routeMsMax;
  private int queueNow;
  private String lastError = "";
  private final List<String> events = new ArrayList<>();

  public synchronized void lookup(boolean hex, long ms, Outcome o, boolean gaveCallsign) {
    (hex ? hexSvc : routeSvc).add(ms, o);
    if (hex && gaveCallsign) callsignFilled++;
  }

  /** Why the latest lookup failed (exception class and message; messages never carry a URL). */
  public synchronized void error(Exception e) {
    String m = e.getClass().getSimpleName() + ": " + e.getMessage();
    lastError = m.length() > 70 ? m.substring(0, 70) : m;
  }

  public synchronized void queue(int pending) {
    queueNow = pending;
  }

  /**
   * One sample of the shown rows: how many, and how many have each piece of data. A route is only
   * expected of scheduled airline flights, so `routeRows` counts those and `route` is how many of
   * them have one; general aviation and military never would.
   */
  public synchronized void rows(
      int n, int routeRows, int route, int airline, int reg, int callsign) {
    rowSamples += n;
    this.routeRows += routeRows;
    withRoute += route;
    withAirline += airline;
    withReg += reg;
    withCallsign += callsign;
  }

  /** An aircraft got its route `ms` after it first reached the display. */
  public synchronized void routeResolved(long ms) {
    routeResolved++;
    routeMsTotal += ms;
    routeMsMax = Math.max(routeMsMax, ms);
  }

  /** An aircraft left the display; `routeMs` < 0 means it never had a route. */
  public synchronized void left(String hex, long shownMs, long routeMs, boolean hadCallsign) {
    if (routeMs < 0) {
      if (hadCallsign) leftNoRouteWithCs++;
      else leftNoRouteNoCs++;
    }
    if (events.size() < 200) {
      events.add(
          String.format(
              Locale.US,
              "enrich left hex=%s shown=%ds route=%s callsign=%s",
              hex,
              shownMs / 1000,
              routeMs < 0 ? "never" : (routeMs / 1000) + "s",
              hadCallsign ? "yes" : "no"));
    }
  }

  public synchronized List<String> drainEvents() {
    List<String> out = new ArrayList<>(events);
    events.clear();
    return out;
  }

  private static String pct(long part, long whole) {
    return whole == 0 ? "-" : Math.round(100.0 * part / whole) + "%";
  }

  private static String avg(Svc s) {
    return s.n() == 0 ? "-" : String.format(Locale.US, "%.1fs", s.totalMs / 1000.0 / s.n());
  }

  /** Short enough for the settings status line. */
  public synchronized String brief() {
    int total = hexSvc.n() + routeSvc.n();
    return "rows with route "
        + pct(withRoute, routeRows)
        + ", airline "
        + pct(withAirline, rowSamples)
        + ", callsign "
        + pct(withCallsign, rowSamples)
        + "; lookups "
        + total
        + " (err: hex "
        + hexSvc.err
        + ", route "
        + routeSvc.err
        + "), queue "
        + queueNow
        + (lastError.isEmpty() ? "" : "; last err " + lastError);
  }

  /** The full picture, for the periodic log line. */
  public synchronized String summary() {
    return String.format(
        Locale.US,
        "enrich summary: row samples=%d route=%s airline=%s reg=%s callsign=%s | hex lookups=%d"
            + " (hit %d, miss %d, err %d, avg %s, max %.1fs, callsign filled %d) | route lookups=%d"
            + " (hit %d, miss %d, err %d, avg %s, max %.1fs) | time to route: n=%d avg %.0fs max"
            + " %.0fs | left without route: callsign known %d, no callsign %d | queue %d",
        rowSamples,
        pct(withRoute, routeRows),
        pct(withAirline, rowSamples),
        pct(withReg, rowSamples),
        pct(withCallsign, rowSamples),
        hexSvc.n(),
        hexSvc.hit,
        hexSvc.miss,
        hexSvc.err,
        avg(hexSvc),
        hexSvc.maxMs / 1000.0,
        callsignFilled,
        routeSvc.n(),
        routeSvc.hit,
        routeSvc.miss,
        routeSvc.err,
        avg(routeSvc),
        routeSvc.maxMs / 1000.0,
        routeResolved,
        routeResolved == 0 ? 0 : routeMsTotal / 1000.0 / routeResolved,
        routeMsMax / 1000.0,
        leftNoRouteWithCs,
        leftNoRouteNoCs,
        queueNow);
  }
}
