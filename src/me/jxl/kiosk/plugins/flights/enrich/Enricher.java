// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.flights.Geo;
import me.jxl.kiosk.plugins.flights.source.Aircraft;

/**
 * Looks up registration/type (by hex) and airline/route (by callsign) in the background and answers
 * from a bounded in-memory cache. {@link #infoFor} never touches the network, so the position
 * publish is never delayed; an answer shows up on the first refresh after it arrives. Entries live
 * 12 h, misses 1 h, and an expired entry is still served while it is refreshed. One request at a
 * time, at least {@link #MIN_GAP_MS} apart, with capped backoff after a failure, kept per service
 * so one slow service does not hold up the others. Memory only: the plugin API gives no storage
 * directory, and a restart costs a few lookups.
 */
public final class Enricher {
  static final int MAX_ENTRIES = 512;
  static final long TTL_MS = 12 * 3_600_000L, MISS_TTL_MS = 3_600_000L;
  static final long CALLSIGN_TTL_MS = 20 * 60_000L;

  /** A route whose segment passes farther than this from the aircraft is dropped. */
  static final double MAX_OFF_ROUTE_M = 100_000;

  /**
   * Airlines whose route data names the right origin but a stale destination (in a 12-hour test
   * Allegiant was right 0 of 17 times, with the origin mostly right). Rotations change daily and
   * the free route set lags, so these show "from X" only. A blunt per-airline rule until a check
   * against the aircraft's observed takeoff exists.
   */
  static final Set<String> STALE_DESTINATION_AIRLINES =
      new HashSet<>(Arrays.asList("AAY", "SWA", "MXY"));

  static final int DEFAULT_PRIORITY = 50;
  static final long MIN_GAP_MS = 1_000, MAX_BACKOFF_MS = 60_000L;

  private static final class Cached {
    final Info info; // null = the service said "unknown"
    final long expiresMs;

    Cached(Info info, long expiresMs) {
      this.info = info;
      this.expiresMs = expiresMs;
    }
  }

  private final Lookup lookup;
  private final EnrichStats stats = new EnrichStats();

  /**
   * Access-ordered, so the eldest entry is the least recently used. Keys: "h:hex", "c:callsign",
   * "a:icao".
   */
  private final Map<String, Cached> cache =
      new LinkedHashMap<String, Cached>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  /** Keys waiting for a lookup, with the best (lowest) priority asked for; see {@link #infoFor}. */
  private final Map<String, Integer> pending = new LinkedHashMap<>();

  /**
   * The gap between any two requests. A failing service adds its own wait in {@link #svcNotBefore}.
   */
  private long notBeforeMs;

  /**
   * Backoff per service (hex, route, airline), so a slow hex service cannot hold up route lookups.
   */
  private final long[] svcNotBefore = new long[3];

  private final int[] svcFailures = new int[3];
  private Thread thread;

  public EnrichStats stats() {
    return stats;
  }

  public Enricher(Lookup lookup) {
    this.lookup = lookup;
  }

  /**
   * What is known about this aircraft right now (possibly nothing), and queues whatever is missing
   * or expired. Fields the feed already supplied are not looked up.
   */
  public synchronized Info infoFor(Aircraft a, long nowMs) {
    return infoFor(a, nowMs, DEFAULT_PRIORITY);
  }

  /**
   * As {@link #infoFor(Aircraft, long)}, and whatever is missing is queued at `priority` (lower
   * goes first): 0 for the featured aircraft, then list rows, then aircraft not shown yet that are
   * about to be. A key already waiting keeps its best priority.
   */
  public synchronized Info infoFor(Aircraft a, long nowMs, int priority) {
    // The feed's callsign wins. A local receiver often has none, so a hex lookup may supply one.
    String airline = Airlines.prefix(a.callsign);
    Info hex =
        (a.registration == null || a.type == null || airline == null)
            ? cached("h:" + a.hex, nowMs, priority)
            : null;
    String callsign = a.callsign;
    if (callsign == null && hex != null && hex.callsign != null) {
      callsign = hex.callsign;
      airline = Airlines.prefix(callsign);
    }
    Info route = airline == null ? null : cached("c:" + callsign, nowMs, priority);
    // Reused flight numbers: the service may name another leg, so drop a route that cannot fit.
    if (route != null && !plausible(route, a)) route = null;
    if (route != null && route.stops != null) route = pickLeg(route, a);
    // Route answer first, then the callsign prefix, then the registered owner of the airframe.
    String icao = route != null && route.airlineIcao != null ? route.airlineIcao : airline;
    if (icao == null && hex != null) icao = hex.airlineIcao;
    String name =
        route != null && route.airlineName != null ? route.airlineName : Airlines.name(icao);
    // An operator name by its code beats the owner's name: the owner can be a different company.
    if (name == null && icao != null) {
      Info named = cached("a:" + icao, nowMs, priority);
      if (named != null) name = named.airlineName;
    }
    // The owner's name applies when it is the same operator, or when there is no operator code at
    // all (an owner with no usable code, such as a military serial with no callsign).
    if (name == null
        && hex != null
        && (icao == null ? hex.airlineIcao == null : icao.equals(hex.airlineIcao))) {
      name = hex.airlineName;
    }
    return new Info(
            hex == null ? null : hex.registration,
            hex == null ? null : hex.type,
            icao,
            name,
            route == null ? null : route.origin,
            route == null || (icao != null && STALE_DESTINATION_AIRLINES.contains(icao))
                ? null
                : route.destination)
        .withCallsign(a.callsign == null ? callsign : null);
  }

  /**
   * Cached routes are per callsign but callsigns get reused for other legs, so each aircraft's
   * position has the final say (for a multi-stop chain, on any leg). A route with no airport
   * coordinates cannot be checked and stays.
   */
  static boolean plausible(Info r, Aircraft a) {
    if (Double.isNaN(r.originLat) || Double.isNaN(r.destLat)) return true;
    if (r.path != null) { // multi-stop: the aircraft only has to be on one of the legs
      for (int i = 0; i + 3 < r.path.length; i += 2) {
        double d =
            Geo.distanceToSegmentM(
                a.lat, a.lon, r.path[i], r.path[i + 1], r.path[i + 2], r.path[i + 3]);
        if (d <= MAX_OFF_ROUTE_M) return true;
      }
      return false;
    }
    if (Geo.distanceToSegmentM(a.lat, a.lon, r.originLat, r.originLon, r.destLat, r.destLon)
        > MAX_OFF_ROUTE_M) {
      return false;
    }
    return !flyingReverse(r, a);
  }

  /** Clear of both airports, an aircraft heading this far back along the line is on the return. */
  static final double REVERSE_CLEAR_OF_AIRPORT_M = 60_000;

  /**
   * True when the aircraft is flying the route backwards: a callsign reused for the opposite leg
   * passes the distance test, because it is the same line. Near either airport the course is
   * unrelated to the line (pattern work, departure and arrival turns), so no judgement is made
   * there, nor without a track.
   */
  private static boolean flyingReverse(Info r, Aircraft a) {
    if (Double.isNaN(a.trackDeg)) return false;
    if (Geo.distanceM(a.lat, a.lon, r.originLat, r.originLon) < REVERSE_CLEAR_OF_AIRPORT_M
        || Geo.distanceM(a.lat, a.lon, r.destLat, r.destLon) < REVERSE_CLEAR_OF_AIRPORT_M) {
      return false;
    }
    double along = bearingDeg(r.originLat, r.originLon, r.destLat, r.destLon);
    return cosTurn(a.trackDeg, along) < -0.5; // more than 120 degrees off the route's direction
  }

  /** Legs whose distance from the aircraft is within this of the nearest count as ambiguous. */
  static final double LEG_TIE_M = 30_000;

  /**
   * Narrows a multi-stop chain to the leg the aircraft is on: the nearest leg, and when several are
   * about equally near (an out-and-back flies the same line both ways, and legs meet at airports)
   * the one the aircraft is flying (closing on its destination, leaving its origin). With no track
   * and no clear nearest leg the whole chain is kept rather than guessing.
   */
  static Info pickLeg(Info r, Aircraft a) {
    int legs = r.stops.length - 1;
    double[] dist = new double[legs];
    int nearest = 0;
    for (int i = 0; i < legs; i++) {
      dist[i] =
          Geo.distanceToSegmentM(
              a.lat, a.lon, r.path[2 * i], r.path[2 * i + 1], r.path[2 * i + 2], r.path[2 * i + 3]);
      if (dist[i] < dist[nearest]) nearest = i;
    }
    int pick = nearest;
    int close = 0;
    for (int i = 0; i < legs; i++) if (dist[i] <= dist[nearest] + LEG_TIE_M) close++;
    if (close > 1) {
      if (Double.isNaN(a.trackDeg)) return r;
      double best = -Double.MAX_VALUE;
      for (int i = 0; i < legs; i++) {
        if (dist[i] > dist[nearest] + LEG_TIE_M) continue;
        // On this leg the aircraft closes on the leg's destination and moves away from its
        // origin. Comparing against the leg's own bearing would fail near an airport, where
        // approach and departure courses are unrelated to the leg's direction.
        double[] o = {r.path[2 * i], r.path[2 * i + 1]}, d = {r.path[2 * i + 2], r.path[2 * i + 3]};
        double towardDest = cosTurn(a.trackDeg, bearingDeg(a.lat, a.lon, d[0], d[1]));
        double awayFromOrigin = cosTurn(a.trackDeg, bearingDeg(o[0], o[1], a.lat, a.lon));
        double score = towardDest + awayFromOrigin;
        if (score > best) {
          best = score;
          pick = i;
        }
      }
    }
    return r.leg(r.stops[pick], r.stops[pick + 1]);
  }

  /** Cosine of the angle between two bearings: 1 when they agree, -1 when opposite. */
  private static double cosTurn(double bearingA, double bearingB) {
    return Math.cos(Math.toRadians(bearingA - bearingB));
  }

  /** Initial bearing in degrees from point 1 to point 2 (flat approximation is fine here). */
  private static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
    double dy = lat2 - lat1, dx = (lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
    return (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360;
  }

  /**
   * True for a callsign of a known scheduled airline: the only flights a route is expected for.
   * General aviation, military and unknown operators are not counted against route coverage.
   */
  public static boolean routeExpected(String callsign) {
    String prefix = Airlines.prefix(callsign);
    return prefix != null && Airlines.name(prefix) != null;
  }

  /**
   * True while a newly seen aircraft is still missing what its row needs: a callsign (from the feed
   * or a hex lookup), and, for a scheduled airline flight, the answer to its route lookup (a miss
   * is an answer). Nothing is queued here; {@link #infoFor} does that.
   */
  public synchronized boolean notReady(Aircraft a) {
    String cs = a.callsign;
    if (cs == null) {
      Cached h = cache.get("h:" + a.hex);
      cs = h == null || h.info == null ? null : h.info.callsign;
    }
    if (cs == null) return true;
    return routeExpected(cs) && !cache.containsKey("c:" + cs);
  }

  private Info cached(String key, long nowMs, int priority) {
    Cached e = cache.get(key);
    if (e == null || e.expiresMs <= nowMs) {
      pending.merge(key, priority, Math::min);
      notifyAll();
    }
    return e == null ? null : e.info;
  }

  /** True when something is waiting to be looked up. */
  public synchronized boolean hasPending() {
    return !pending.isEmpty();
  }

  /** 0 = hex ("h:"), 1 = route ("c:"), 2 = airline ("a:"). */
  private static int service(String key) {
    return key.startsWith("h:") ? 0 : key.startsWith("c:") ? 1 : 2;
  }

  /** Ms until the earliest queued request may go: the gap and its own service's backoff. */
  private long wakeIn(long nowMs) {
    long at = Long.MAX_VALUE;
    for (String k : pending.keySet()) at = Math.min(at, svcNotBefore[service(k)]);
    return Math.max(notBeforeMs, at) - nowMs;
  }

  /**
   * Performs at most one lookup if one is due. Returns the ms to wait before calling again, or -1
   * when idle. Public so tests drive it without a thread.
   */
  public long step(long nowMs) {
    String key;
    synchronized (this) {
      if (pending.isEmpty()) return -1;
      if (nowMs < notBeforeMs) return wakeIn(nowMs);
      key = null;
      int best = Integer.MAX_VALUE;
      for (Map.Entry<String, Integer> e : pending.entrySet()) { // small queue: a scan is enough
        if (svcNotBefore[service(e.getKey())] > nowMs) continue; // that service is backing off
        if (e.getValue() < best) {
          best = e.getValue();
          key = e.getKey();
        }
      }
      if (key == null) return wakeIn(nowMs); // everything queued belongs to a backing-off service
      pending.remove(key);
    }
    Info found;
    boolean hex = key.startsWith("h:"), airline = key.startsWith("a:");
    long t0 = System.nanoTime();
    try {
      found =
          hex
              ? lookup.aircraft(key.substring(2))
              : airline ? lookup.airline(key.substring(2)) : lookup.route(key.substring(2));
    } catch (IOException e) {
      // Safe to drop: the aircraft just keeps its feed fields. Requeue and back off.
      stats.error(e);
      if (!airline) { // the airline-name lookups are not part of the hex/route statistics
        stats.lookup(hex, (System.nanoTime() - t0) / 1_000_000, EnrichStats.Outcome.ERROR, false);
      }
      synchronized (this) {
        pending.merge(key, DEFAULT_PRIORITY, Math::min);
        int svc = service(key);
        svcFailures[svc]++;
        svcNotBefore[svc] =
            nowMs + Math.min(MAX_BACKOFF_MS, MIN_GAP_MS << Math.min(svcFailures[svc], 10));
        notBeforeMs = nowMs + MIN_GAP_MS;
        return wakeIn(nowMs);
      }
    }
    if (!airline) {
      stats.lookup(
          hex,
          (System.nanoTime() - t0) / 1_000_000,
          found == null ? EnrichStats.Outcome.MISS : EnrichStats.Outcome.HIT,
          found != null && found.callsign != null);
    }
    synchronized (this) {
      int svc = service(key);
      svcFailures[svc] = 0;
      svcNotBefore[svc] = 0;
      // A callsign belongs to one flight, so an entry carrying one is not trusted for 12 h.
      long ttl = found == null ? MISS_TTL_MS : found.callsign != null ? CALLSIGN_TTL_MS : TTL_MS;
      cache.put(key, new Cached(found, nowMs + ttl));
      notBeforeMs = nowMs + MIN_GAP_MS;
      stats.queue(pending.size());
      return pending.isEmpty() ? -1 : MIN_GAP_MS;
    }
  }

  /**
   * Starts the daemon worker. A stuck request must never hold up the host's lifecycle callbacks.
   */
  public synchronized void start() {
    if (thread != null) return;
    thread = new Thread(this::run, "flights-enrich");
    thread.setDaemon(true);
    thread.start();
  }

  public synchronized void stop() {
    if (thread != null) thread.interrupt();
    thread = null;
  }

  private void run() {
    try {
      while (true) {
        long wait;
        try {
          wait = step(System.currentTimeMillis());
        } catch (RuntimeException | Error e) {
          // A bug in one lookup must not end enrichment for good: the aircraft keep their feed
          // fields, and the step is retried after a pause (the key was already taken off the
          // queue).
          wait = 30_000;
        }
        synchronized (this) {
          if (thread != Thread.currentThread()) return;
          if (wait < 0) wait(60_000);
          else wait(Math.max(wait, 50));
        }
      }
    } catch (InterruptedException e) {
      // stop() asked us to exit.
    }
  }
}
