// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import me.jxl.kiosk.plugins.flights.enrich.Airlines;
import me.jxl.kiosk.plugins.flights.source.Aircraft;
import me.jxl.kiosk.plugins.flights.source.FetchResult;

/**
 * Filter, rank by distance, keep the closest {@link Config#aircraftWanted}, and attach trails. Owns
 * the TrailStore.
 */
public final class Selector {
  private static final Pattern AIRLINE_CALLSIGN = Pattern.compile("^[A-Z]{3}\\d{1,4}[A-Z]{0,2}$");
  private static final Pattern N_NUMBER = Pattern.compile("^N\\d{1,5}[A-Z]{0,2}$");

  /**
   * A position older than this is a ghost. A weak receiver keeps reporting aircraft whose last fix
   * is old (measured 2026-10-07 on the local feed: 21% of fixes older than 30 s, 5% older than 60
   * s, none beyond 90 s), and the renderer dead-reckons and then dims them from 60 s on, so only
   * the truly abandoned are dropped.
   */
  static final double MAX_POSITION_AGE_S = 120;

  /** How many not-yet-shown aircraft to hand on for early lookups. */
  static final int PREFETCH = 6;

  /**
   * Minimum time a shown aircraft keeps its place, and how long one that stopped qualifying stays.
   */
  static final long MIN_DISPLAY_MS = 20_000, GRACE_MS = 20_000;

  /**
   * How long a newly appearing aircraft that is not ready (no callsign, or its route not yet looked
   * up) waits before it is shown. Transponders often send position before identification, and the
   * row would otherwise show a bare hex code, or no route, and then change. Lookups for it start
   * meanwhile (it is in {@link #upNext}). One that never identifies itself still appears after
   * this.
   */
  static final long NEW_HOLD_MS = 10_000;

  /**
   * How long a shown aircraft that vanished (out of the feed, or past its grace) keeps its place
   * while a newcomer is still waiting to be shown, so a slot is not left empty. Without a waiting
   * newcomer it goes at once.
   */
  static final long DEPART_HOLD_MS = 10_000;

  private final TrailStore trails = new TrailStore();
  private int waitingCount;
  private Predicate<Aircraft> notReady = a -> a.callsign == null;
  private final Map<String, Long> shownSinceMs = new HashMap<>(); // hex -> when it entered the list
  private final Map<String, Long> lastQualifiedMs = new HashMap<>();
  private Map<String, Tracked> lastChosen = new HashMap<>(); // shown at the previous select
  private final Map<String, Tracked> lingering =
      new HashMap<>(); // vanished, kept for a waiting slot
  private final Map<String, Long> lingerSinceMs = new HashMap<>();
  private long lastSelectMs = Long.MIN_VALUE;
  private final Map<String, Long> firstSeenMs =
      new HashMap<>(); // hex -> first time it was a candidate
  private List<Aircraft> upNext = new ArrayList<>();

  /**
   * How many aircraft {@link #select} would consider within the config's radius, before the row
   * cap. Pure: touches no trails, so the Engine can probe several radii on one fetch.
   */
  public static int eligible(FetchResult result, Config c) {
    int n = 0;
    for (Aircraft a : result.aircraft) {
      if (skip(a, c)) continue;
      double[] p = Geo.eastNorthM(c.homeLat, c.homeLon, a.lat, a.lon);
      if (Math.hypot(p[0], p[1]) <= c.radiusM) n++;
    }
    return n;
  }

  /** How many aircraft the last {@link #select} held back as not ready yet (see NEW_HOLD_MS). */
  public int waitingCount() {
    return waitingCount;
  }

  /**
   * The closest aircraft that passed the filters but are not shown: just beyond the radius, or past
   * the row cap. The Engine looks them up early so they arrive already enriched.
   */
  public List<Aircraft> upNext() {
    return upNext;
  }

  /**
   * Picks what to show. Ranking is by distance, softened so the list is calm:
   *
   * <ul>
   *   <li>An aircraft that has been shown keeps its place for at least {@link #MIN_DISPLAY_MS},
   *       even if a closer one appears (the newcomer waits for a free slot).
   *   <li>A shown aircraft that stops qualifying (just past the radius, or its position fix has
   *       aged out) stays for {@link #GRACE_MS} while the feed still reports it. Ground and
   *       general-aviation filters apply at once.
   * </ul>
   */
  public List<Tracked> select(FetchResult result, Config c) {
    long now = result.observedAtMs;
    List<Tracked> candidates = new ArrayList<>(); // qualifying now, or within the grace period
    List<Tracked> outside = new ArrayList<>(); // not qualifying and not held: prefetch only
    Set<String> held = new HashSet<>();
    Set<String> filtered = new HashSet<>(); // on the ground or general aviation: leave at once
    for (Aircraft a : result.aircraft) {
      if (a.onGround || (c.excludeGa && isGeneralAviation(a))) {
        filtered.add(a.hex);
        continue;
      }
      double[] p = Geo.eastNorthM(c.homeLat, c.homeLon, a.lat, a.lon);
      boolean qualifies =
          Math.hypot(p[0], p[1]) <= c.radiusM && a.positionAgeS <= MAX_POSITION_AGE_S;
      Long wasShown = shownSinceMs.get(a.hex);
      if (qualifies) lastQualifiedMs.put(a.hex, now);
      boolean inGrace =
          !qualifies
              && wasShown != null
              && now - lastQualifiedMs.getOrDefault(a.hex, now) < GRACE_MS;
      if (!qualifies && !inGrace) {
        outside.add(new Tracked(a, p[0], p[1], new ArrayList<>()));
        continue;
      }
      held.add(a.hex);
      firstSeenMs.putIfAbsent(a.hex, now);
      long tObs = now - Math.round(a.positionAgeS * 1000);
      candidates.add(new Tracked(a, p[0], p[1], trails.record(a.hex, tObs, p[0], p[1])));
    }
    trails.retainOnly(held);
    lastQualifiedMs.keySet().retainAll(held);
    firstSeenMs.keySet().retainAll(held);
    Collections.sort(candidates, (l, r) -> Double.compare(l.distM, r.distM));

    int wanted = c.aircraftWanted();
    List<Tracked> chosen = new ArrayList<>();
    List<Tracked> rest = new ArrayList<>();
    List<Tracked> waiting = new ArrayList<>();
    for (Tracked t : candidates) { // those still inside their minimum display time go first
      if (isWaitingForCallsign(t.aircraft, now)) {
        waiting.add(t);
        continue;
      }
      Long since = shownSinceMs.get(t.aircraft.hex);
      boolean protectedNow =
          since != null && now - since < MIN_DISPLAY_MS && chosen.size() < wanted;
      (protectedNow ? chosen : rest).add(t);
    }
    for (Tracked t : rest) if (chosen.size() < wanted) chosen.add(t);
    List<Tracked> next = new ArrayList<>(candidates);
    next.removeAll(chosen);
    next.addAll(outside);
    Collections.sort(next, (l, r) -> Double.compare(l.distM, r.distM));
    upNext = new ArrayList<>();
    for (int i = 0; i < Math.min(PREFETCH, next.size()); i++) upNext.add(next.get(i).aircraft);

    waitingCount = waiting.size();
    keepDeparted(chosen, waiting, filtered, candidates, wanted, now);
    Map<String, Long> kept = new HashMap<>();
    for (Tracked t : chosen)
      kept.put(t.aircraft.hex, shownSinceMs.getOrDefault(t.aircraft.hex, now));
    shownSinceMs.clear();
    shownSinceMs.putAll(kept);
    Collections.sort(chosen, (l, r) -> Double.compare(l.distM, r.distM));
    return chosen;
  }

  /**
   * A shown aircraft that vanished (not a candidate, not filtered) stays in a free slot while a
   * newcomer is waiting, for up to {@link #DEPART_HOLD_MS}. Its fix is aged so it dead-reckons and
   * dims like any old fix, and it is dropped once the newcomer is ready or the time is up.
   */
  private void keepDeparted(
      List<Tracked> chosen,
      List<Tracked> waiting,
      Set<String> filtered,
      List<Tracked> candidates,
      int wanted,
      long now) {
    double sinceS = lastSelectMs == Long.MIN_VALUE ? 0 : (now - lastSelectMs) / 1000.0;
    Set<String> alive = new HashSet<>();
    for (Tracked t : candidates) alive.add(t.aircraft.hex);
    for (Map.Entry<String, Tracked> e : lingering.entrySet()) { // age what is already kept
      Tracked t = e.getValue();
      e.setValue(t.withAircraft(t.aircraft.withAge(sinceS)));
    }
    for (Map.Entry<String, Tracked> e : lastChosen.entrySet()) {
      String hex = e.getKey();
      if (alive.contains(hex) || filtered.contains(hex) || lingering.containsKey(hex)) continue;
      Tracked t = e.getValue();
      lingering.put(hex, t.withAircraft(t.aircraft.withAge(sinceS)));
      lingerSinceMs.put(hex, now);
    }
    lingering.keySet().removeIf(h -> alive.contains(h) || filtered.contains(h));
    lingering
        .keySet()
        .removeIf(h -> waiting.isEmpty() || now - lingerSinceMs.get(h) >= DEPART_HOLD_MS);
    lingerSinceMs.keySet().retainAll(lingering.keySet());
    List<Tracked> byDistance = new ArrayList<>(lingering.values());
    Collections.sort(byDistance, (l, r) -> Double.compare(l.distM, r.distM));
    for (Tracked t : byDistance) if (chosen.size() < wanted) chosen.add(t);
    lingering.keySet().retainAll(idsOf(chosen));
    lastSelectMs = now;
    lastChosen = new HashMap<>();
    for (Tracked t : chosen) lastChosen.put(t.aircraft.hex, t);
  }

  private static Set<String> idsOf(List<Tracked> l) {
    Set<String> out = new HashSet<>();
    for (Tracked t : l) out.add(t.aircraft.hex);
    return out;
  }

  /**
   * Replaces what "not ready" means for a new aircraft (default: no callsign). The Engine adds the
   * enrichment answers, so a row appears with its route and airline instead of filling them in.
   */
  public void setNotReady(Predicate<Aircraft> notReady) {
    this.notReady = notReady;
  }

  private boolean isWaitingForCallsign(Aircraft a, long now) {
    return notReady.test(a)
        && !shownSinceMs.containsKey(a.hex)
        && now - firstSeenMs.getOrDefault(a.hex, now) < NEW_HOLD_MS;
  }

  private static boolean skip(Aircraft a, Config c) {
    return a.onGround
        || a.positionAgeS > MAX_POSITION_AGE_S
        || (c.excludeGa && isGeneralAviation(a));
  }

  /**
   * Light aircraft (A1) and rotorcraft (A7) by ADS-B category, or a callsign that is a bare
   * registration (unless the category says large or heavy). No category and no callsign means no
   * evidence, so the aircraft stays.
   */
  static boolean isGeneralAviation(Aircraft a) {
    String cs = a.callsign;
    if ("A1".equals(a.category) || "A7".equals(a.category)) {
      // A scheduled carrier flying a light aircraft (a feeder Caravan) is not what the filter is
      // for.
      return Airlines.name(Airlines.prefix(cs)) == null;
    }
    // An airliner can fly under its tail number (an American A319 as N9006); a large or heavy
    // category (A3-A6) is evidence enough that it is not general aviation. Enrichment runs after
    // selection, so the feed's category is what we have here.
    if (a.category != null && a.category.matches("A[3-6]")) return false;
    if (cs == null || AIRLINE_CALLSIGN.matcher(cs).matches()) return false;
    String bare = a.registration == null ? "" : a.registration.replace("-", "");
    return cs.replace("-", "").equals(bare) || N_NUMBER.matcher(cs).matches();
  }
}
