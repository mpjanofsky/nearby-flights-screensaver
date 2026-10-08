// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.flights.enrich.Enricher;
import me.jxl.kiosk.plugins.flights.source.Aircraft;
import me.jxl.kiosk.plugins.flights.source.FetchResult;
import me.jxl.kiosk.plugins.flights.source.SourceAdapter;
import me.jxl.kiosk.plugins.flights.source.SourceException;

/**
 * One refresh cycle: fetch (primary, then fallback), select, diff-gate, encode, publish. Knows
 * nothing about Android; the plugin supplies a Publisher and a scheduler that calls tick(). Not
 * thread-safe: the plugin calls it from a single background thread.
 */
public final class Engine {
  /** Receives each payload that passed the diff gate. */
  public interface Publisher {
    void publish(String payloadJson, long genMs);
  }

  static final int STALE_AFTER_FAILURES = 3;
  static final int PRIMARY_RETRY_EVERY = 6; // while on the fallback, try the primary every Nth tick
  static final long MAX_BACKOFF_MS = 120_000;

  private final Config config;
  private final SourceAdapter primary;
  private final SourceAdapter fallback; // may be null
  private final Publisher publisher;
  private final long refreshMs;
  private final Enricher enricher; // may be null (tests, enrichment off)
  private final Selector selector = new Selector();
  private final PublishGate gate = new PublishGate();
  private final Featuring featuring = new Featuring();

  /** Per shown aircraft: {first shown ms, route-known ms or -1, callsign-known 0/1}. */
  private final java.util.Map<String, long[]> shown = new java.util.HashMap<>();

  /** Radius steps (nm) between the configured radius and the limit. */
  private static final double[] WIDEN_STEPS_NM = {25, 40, 60, 100, 150, 250};

  private double fetchRadiusM; // where the next fetch starts; stays wide until the base is enough
  private Config effective; // config at the radius actually shown

  private boolean onFallback;
  private int ticksOnFallback;
  private int failures;
  private long lastOkMs = -1;
  private long observedAtMs;
  private List<Tracked> rows = new ArrayList<>();
  private String featuredId;
  private String sourceId;
  private String lastError = "";
  private long tickMs; // the current tick's time, for helpers that run inside it

  /**
   * Per source: when a Retry-After from it runs out. One source's wait must not stall the other.
   */
  private final java.util.Map<SourceAdapter, Long> coolUntilMs = new java.util.HashMap<>();

  private final Airports airports = new Airports();

  /** What the last publish showed, so the next one can say what moved (the page keeps no state). */
  private Transition.Snapshot previous;

  public Engine(
      Config config,
      SourceAdapter primary,
      SourceAdapter fallback,
      Publisher publisher,
      long refreshMs) {
    this(config, primary, fallback, publisher, refreshMs, null);
  }

  public Engine(
      Config config,
      SourceAdapter primary,
      SourceAdapter fallback,
      Publisher publisher,
      long refreshMs,
      Enricher enricher) {
    this.enricher = enricher;
    if (enricher != null) selector.setNotReady(enricher::notReady);
    this.config = config;
    this.primary = primary;
    this.fallback = fallback;
    this.publisher = publisher;
    this.refreshMs = refreshMs;
    this.sourceId = primary.id();
    this.fetchRadiusM = config.radiusM;
    this.effective = config;
  }

  /** The screen was replaced behind the gate's back (the idle screen), so publish next tick. */
  public void forcePublish() {
    gate.reset();
  }

  /** Status line for the plugin's settings page. Never contains a URL or a coordinate. */
  public String statusText() {
    if (failures >= STALE_AFTER_FAILURES) return "Stale: " + lastError;
    String base =
        (onFallback ? "Fallback source" : "Primary source") + ", " + rows.size() + " aircraft";
    return enricher == null ? base : base + " | " + enricher.stats().brief();
  }

  public boolean isStale() {
    return failures >= STALE_AFTER_FAILURES;
  }

  /** Runs one cycle and returns the delay in ms until the next one. */
  public long tick(long nowMs) {
    tickMs = nowMs;
    long waitMs = Long.MAX_VALUE; // the soonest any source we did not use may be asked again
    FetchResult got = null;
    SourceAdapter used = null;
    boolean tryPrimary =
        !onFallback || fallback == null || ticksOnFallback % PRIMARY_RETRY_EVERY == 0;
    List<SourceAdapter> order = new ArrayList<>();
    if (tryPrimary) order.add(primary);
    if (fallback != null) order.add(fallback);
    for (SourceAdapter s : order) {
      long cool = coolUntilMs.getOrDefault(s, 0L) - nowMs;
      if (cool > 0) { // it asked us to wait: leave it alone, the other source may still answer
        waitMs = Math.min(waitMs, cool);
        continue;
      }
      try {
        got = s.fetch(config.homeLat, config.homeLon, fetchRadiusM);
        used = s;
        break;
      } catch (SourceException e) {
        // Counted below; the cause is kept (without URL or body) for the status line.
        lastError = e.getMessage();
        if (e.retryAfterS > 0) coolUntilMs.put(s, nowMs + e.retryAfterS * 1000L);
        waitMs = Math.min(waitMs, e.retryAfterS * 1000L);
      }
    }
    if (got != null) {
      boolean usedFallback = used != primary;
      ticksOnFallback = usedFallback ? ticksOnFallback + 1 : 0;
      onFallback = usedFallback;
      failures = 0;
      lastOkMs = nowMs;
      observedAtMs = got.observedAtMs;
      sourceId = used.id();
      got = widen(got, used);
      // Enrichment first: the featuring rule prefers an aircraft whose data is complete.
      Featuring.Result f =
          featuring.apply(
              withEnrichment(selector.select(got, effective), nowMs), nowMs, this::isComplete);
      rows = f.rows;
      featuredId = f.featuredId;
    } else {
      failures++;
      if (onFallback) ticksOnFallback++;
    }
    // An empty list only because every aircraft is still waiting for its data (the new-aircraft
    // hold)
    // is not worth showing: keep the previous screen for the few seconds until something is ready.
    boolean waitingForData = got != null && rows.isEmpty() && selector.waitingCount() > 0;
    if (!waitingForData) publishIfNeeded(nowMs);
    if (got != null) return refreshMs;
    long backoff = Math.min(MAX_BACKOFF_MS, refreshMs << Math.min(failures, 5));
    return Math.max(backoff, waitMs == Long.MAX_VALUE ? 0 : waitMs);
  }

  /**
   * Chooses the radius to show. `got` was fetched at fetchRadiusM; the smallest step it already
   * satisfies wins (so the display snaps back without a new fetch). If none does, fetch wider,
   * asking the other source too since a local feed cannot see beyond its antenna. Returns the
   * result to select from; sets `effective` and the next fetch radius.
   */
  private FetchResult widen(FetchResult got, SourceAdapter used) {
    double[] steps = widenSteps();
    FetchResult best = got;
    double fetchedM = fetchRadiusM;
    while (true) {
      for (double s : steps) {
        if (s > fetchedM + 1) break;
        if (config.satisfied(Selector.eligible(best, config.withRadius(s)))) {
          return settle(best, s);
        }
      }
      double next = Double.NaN;
      for (double s : steps) {
        if (s > fetchedM + 1) {
          next = s;
          break;
        }
      }
      if (Double.isNaN(next)) return settle(best, fetchedM); // at the limit: show what exists
      FetchResult wider = fetchWider(next, used);
      if (wider == null) return settle(best, fetchedM); // transient: keep what we have
      best = wider;
      fetchedM = next;
    }
  }

  private FetchResult settle(FetchResult r, double radiusM) {
    fetchRadiusM = radiusM;
    effective = config.withRadius(radiusM);
    return r;
  }

  /** Ascending radii in metres from the configured one to the limit; just the base when off. */
  private double[] widenSteps() {
    if (config.widen == Config.Widen.HARD) return new double[] {config.radiusM};
    List<Double> l = new ArrayList<>();
    l.add(config.radiusM);
    for (double nm : WIDEN_STEPS_NM) {
      double m = nm * 1852.0;
      if (m > config.radiusM + 1 && m < config.maxRadiusM - 1) l.add(m);
    }
    if (config.maxRadiusM > config.radiusM + 1) l.add(config.maxRadiusM);
    double[] out = new double[l.size()];
    for (int i = 0; i < out.length; i++) out[i] = l.get(i);
    return out;
  }

  /**
   * Fetches at a wider radius: the source in use first, then the other one if the first still
   * leaves the sky short. Null when neither answers (the caller keeps the narrower result).
   */
  private FetchResult fetchWider(double radiusM, SourceAdapter used) {
    List<SourceAdapter> order = new ArrayList<>();
    order.add(used);
    SourceAdapter other = used == primary ? fallback : primary;
    if (other != null) order.add(other);
    FetchResult best = null;
    for (SourceAdapter s : order) {
      if (coolUntilMs.getOrDefault(s, 0L) > tickMs) continue;
      try {
        FetchResult r = s.fetch(config.homeLat, config.homeLon, radiusM);
        if (best == null
            || Selector.eligible(r, config.withRadius(radiusM))
                > Selector.eligible(best, config.withRadius(radiusM))) {
          best = r;
          sourceId = s.id();
          observedAtMs = r.observedAtMs;
        }
        if (config.satisfied(Selector.eligible(r, config.withRadius(radiusM)))) break;
      } catch (SourceException e) {
        // A failed widening probe is not a failure of the base feed: keep the narrower answer and
        // try again next tick. Only the status text remembers why.
        lastError = e.getMessage();
      }
    }
    return best;
  }

  /** Lines about aircraft that left the display since the last call, for the host log. */
  public List<String> drainEnrichEvents() {
    return enricher == null ? new ArrayList<>() : enricher.stats().drainEvents();
  }

  /** The long enrichment summary, for the periodic log line. */
  public String enrichSummary() {
    return enricher == null ? "enrichment off" : enricher.stats().summary();
  }

  /**
   * Feeds the enrichment statistics: how complete the shown rows are, and how long each aircraft
   * took to get a route after it first reached the display.
   */
  private void observe(long nowMs) {
    if (enricher == null) return;
    int routeRows = 0, route = 0, airline = 0, reg = 0, callsign = 0;
    java.util.Set<String> now = new java.util.HashSet<>();
    for (Tracked t : rows) {
      boolean hasRoute = t.info.origin != null;
      boolean hasCs = t.aircraft.callsign != null || t.info.callsign != null;
      if (Enricher.routeExpected(
          hasCs ? (t.aircraft.callsign != null ? t.aircraft.callsign : t.info.callsign) : null)) {
        routeRows++;
        if (hasRoute) route++;
      }
      if (t.info.airlineIcao != null) airline++;
      if (t.aircraft.registration != null || t.info.registration != null) reg++;
      if (hasCs) callsign++;
      now.add(t.aircraft.hex);
      long[] st = shown.computeIfAbsent(t.aircraft.hex, h -> new long[] {nowMs, -1, 0});
      if (hasCs) st[2] = 1;
      if (hasRoute && st[1] < 0) {
        st[1] = nowMs;
        enricher.stats().routeResolved(nowMs - st[0]);
      }
    }
    enricher.stats().rows(rows.size(), routeRows, route, airline, reg, callsign);
    java.util.Iterator<java.util.Map.Entry<String, long[]>> it = shown.entrySet().iterator();
    while (it.hasNext()) {
      java.util.Map.Entry<String, long[]> e = it.next();
      if (now.contains(e.getKey())) continue;
      long[] st = e.getValue();
      enricher.stats().left(e.getKey(), nowMs - st[0], st[1] < 0 ? -1 : st[1] - st[0], st[2] == 1);
      it.remove();
    }
  }

  /** Re-applies cached enrichment every tick, so a late answer reaches the next publish. */
  private void applyEnrichment(long nowMs) {
    rows = withEnrichment(rows, nowMs);
  }

  private List<Tracked> withEnrichment(List<Tracked> in, long nowMs) {
    if (enricher == null) return in;
    List<Tracked> out = new ArrayList<>(in.size());
    for (int i = 0; i < in.size(); i++) {
      Tracked t = in.get(i);
      int priority = t.aircraft.hex.equals(featuredId) ? 0 : 1 + i;
      out.add(t.withInfo(enricher.infoFor(t.aircraft, nowMs, priority)));
    }
    // Aircraft about to be shown: queue their lookups behind the visible ones, answer unused.
    List<Aircraft> next = selector.upNext();
    for (int i = 0; i < next.size(); i++) enricher.infoFor(next.get(i), nowMs, 10 + i);
    return out;
  }

  /**
   * Worth the band: a callsign and a type, and for a scheduled airline flight its route. Unknown
   * data makes an aircraft less preferred, not ineligible (see {@link Featuring}).
   */
  private boolean isComplete(Tracked t) {
    String cs = t.aircraft.callsign != null ? t.aircraft.callsign : t.info.callsign;
    String type = t.aircraft.type != null ? t.aircraft.type : t.info.type;
    if (cs == null || type == null) return false;
    return !Enricher.routeExpected(cs) || t.info.origin != null;
  }

  private void publishIfNeeded(long nowMs) {
    applyEnrichment(nowMs);
    observe(nowMs);
    boolean stale = isStale() || lastOkMs < 0;
    // Demo switch: the real data keeps flowing (health, staleness), only the aircraft are hidden.
    List<Tracked> shown = config.demoQuiet ? new ArrayList<Tracked>() : rows;
    String shownFeatured = config.demoQuiet ? null : featuredId;
    if (!gate.changed(shown, shownFeatured, effective, stale, sourceId, nowMs)) return;
    double fetchAgeS = lastOkMs < 0 ? Double.NaN : (nowMs - lastOkMs) / 1000.0;
    String json =
        PayloadEncoder.encode(
            shown,
            effective,
            new PayloadEncoder.Health(fetchAgeS, stale, sourceId),
            nowMs,
            observedAtMs,
            shownFeatured,
            airports.within(effective.homeLat, effective.homeLon, effective.radiusM),
            Transition.between(previous, shown));
    publisher.publish(json, nowMs);
    previous = new Transition.Snapshot(shown, shownFeatured);
  }
}
