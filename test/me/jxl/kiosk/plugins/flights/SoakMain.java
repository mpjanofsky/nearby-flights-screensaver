package me.jxl.kiosk.plugins.flights;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import me.jxl.kiosk.plugins.flights.enrich.Enricher;
import me.jxl.kiosk.plugins.flights.enrich.Info;
import me.jxl.kiosk.plugins.flights.enrich.Lookup;
import me.jxl.kiosk.plugins.flights.source.Aircraft;
import me.jxl.kiosk.plugins.flights.source.FetchResult;
import me.jxl.kiosk.plugins.flights.source.SourceAdapter;
import me.jxl.kiosk.plugins.flights.source.SourceException;

/**
 * Accelerated soak: runs the engine and the enricher against synthetic traffic on a fake clock, so
 * days of ticks take seconds, and reports how memory and the engine's own collections grow. Run
 * with scripts/soak-sim.sh. Not a JUnit test: it is a measurement, not a pass/fail check.
 *
 * <p>Every aircraft gets a fresh hex, so caches see an endless stream of new keys, as a real sky
 * does. Both sources and both lookup services fail in windows to exercise backoff and the queue.
 */
public final class SoakMain {
  static final double LAT = 27.0, LON = -82.0;

  /** Aircraft flying straight through the area; a new hex for every one. */
  static final class Traffic {
    final Random rnd = new Random(42);
    final List<double[]> live = new ArrayList<>(); // id, lat, lon, dLat/s, dLon/s, diesAtS
    final List<String> cs = new ArrayList<>();
    long nextId = 0;

    List<Aircraft> at(long tS) {
      // About 40 flights an hour on average, 3x that in a busy hour, none at night.
      double hour = (tS / 3600.0) % 24;
      double perHour = hour < 5 || hour > 23 ? 4 : hour > 16 && hour < 20 ? 120 : 40;
      if (rnd.nextDouble() < perHour / 3600.0 * 10) spawn(tS);
      List<Aircraft> out = new ArrayList<>();
      for (int i = live.size() - 1; i >= 0; i--) {
        double[] a = live.get(i);
        if (a[5] < tS) {
          live.remove(i);
          cs.remove(i);
          continue;
        }
        double lat = a[1] + a[3] * (tS - (a[5] - 600)), lon = a[2] + a[4] * (tS - (a[5] - 600));
        String hex = String.format("%06x", (int) a[0]);
        String call = rnd.nextInt(10) == 0 ? null : cs.get(i); // some feeds lack a callsign
        out.add(new Aircraft(hex, call, null, null, "A3", lat, lon, 3000, 150, 90, 0, 1.0, false));
      }
      return out;
    }

    void spawn(long tS) {
      double ang = rnd.nextDouble() * Math.PI * 2;
      double dLat = Math.cos(ang) * 0.3 / 600, dLon = Math.sin(ang) * 0.3 / 600;
      live.add(new double[] {nextId++, LAT - dLat * 300, LON - dLon * 300, dLat, dLon, tS + 600});
      String[] pre = {"DAL", "SWA", "AAY", "UAL", "JBU", "N", "FDX"};
      cs.add(pre[rnd.nextInt(pre.length)] + (100 + rnd.nextInt(8000)));
    }
  }

  static long outageS = 600, periodS = 7200;

  /** Down for `outageS` out of every `periodS`, from a given offset (a third of a period apart). */
  static boolean down(long tS, long offsetS) {
    return (tS + offsetS * periodS / 7200) % periodS < outageS;
  }

  public static void main(String[] args) throws Exception {
    int days = args.length > 0 ? Integer.parseInt(args[0]) : 30;
    if (args.length > 2) {
      outageS = Long.parseLong(args[1]) * 60;
      periodS = Long.parseLong(args[2]) * 60;
    }
    int[] maxPending = {0};
    long refresh = 10;
    Traffic traffic = new Traffic();
    final long[] clock = {0};
    SourceAdapter local =
        new SourceAdapter() {
          public String id() {
            return "local";
          }

          public FetchResult fetch(double la, double lo, double r) throws SourceException {
            if (down(clock[0], 0)) throw new SourceException("local source unreachable", null, 0);
            return new FetchResult(traffic.at(clock[0]), clock[0] * 1000);
          }
        };
    SourceAdapter lol =
        new SourceAdapter() {
          public String id() {
            return "adsb.lol";
          }

          public FetchResult fetch(double la, double lo, double r) throws SourceException {
            if (down(clock[0], 3600)) throw new SourceException("adsb.lol unreachable", null, 0);
            return new FetchResult(traffic.at(clock[0]), clock[0] * 1000);
          }
        };
    Lookup lookup =
        new Lookup() {
          public Info aircraft(String hex) throws IOException {
            if (down(clock[0], 1800)) throw new IOException("hex service down");
            return new Info("N" + hex, "B738", "DAL", "Delta", null, null);
          }

          public Info route(String callsign) throws IOException {
            if (down(clock[0], 5400)) throw new IOException("route service down");
            return new Info(null, null, "DAL", "Delta", "ATL", "TPA");
          }
        };
    Enricher enricher = new Enricher(lookup);
    int[] publishes = {0};
    long[] bytes = {0};
    Engine e =
        new Engine(
            new Config(LAT, LON, 15 * 1852.0, "av", 4, false),
            local,
            lol,
            (json, gen) -> {
              publishes[0]++;
              bytes[0] += json.length();
            },
            refresh * 1000,
            enricher);

    long end = days * 86400L;
    long nextReport = 0, step = Math.max(86400L / 4, end / 40);
    System.out.printf(
        "%-8s %-10s %-9s %-9s %-9s %-8s%n", "day", "heapKB", "cache", "pending", "pubs", "KB/pub");
    for (long t = 0; t <= end; t += refresh) {
      clock[0] = t;
      e.tick(t * 1000);
      for (int k = 0; k < refresh; k++)
        enricher.step((t + k) * 1000); // one lookup a second at most
      maxPending[0] = Math.max(maxPending[0], size(enricher, "pending"));
      if (t >= nextReport) {
        nextReport += step;
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1024;
        System.out.printf(
            "%-8.2f %-10d %-9d %-9d %-9d %-8d%n",
            t / 86400.0,
            used,
            size(enricher, "cache"),
            size(enricher, "pending"),
            publishes[0],
            publishes[0] == 0 ? 0 : bytes[0] / publishes[0] / 1024);
      }
    }
    System.out.println("max pending queue: " + maxPending[0]);
  }

  static int size(Object o, String field) {
    try {
      Field f = o.getClass().getDeclaredField(field);
      f.setAccessible(true);
      Object v = f.get(o);
      return v instanceof Map ? ((Map<?, ?>) v).size() : ((Collection<?>) v).size();
    } catch (ReflectiveOperationException ex) {
      return -1;
    }
  }
}
