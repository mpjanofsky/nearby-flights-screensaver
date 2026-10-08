// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.flights.enrich.AdsbImRoutes;
import me.jxl.kiosk.plugins.flights.enrich.AdsbLolHex;
import me.jxl.kiosk.plugins.flights.enrich.AdsbdbLookup;
import me.jxl.kiosk.plugins.flights.enrich.Enricher;
import me.jxl.kiosk.plugins.flights.enrich.MergedHexLookup;
import me.jxl.kiosk.plugins.flights.source.MergedSource;
import me.jxl.kiosk.plugins.flights.source.ReadsbJsonSource;
import me.jxl.kiosk.plugins.flights.source.SourceAdapter;

/**
 * Wires settings, sources and the Engine to the host. All network work runs on one daemon thread.
 */
public final class FlightsPlugin implements KioskPlugin {
  static final String USER_AGENT =
      "nearby-flights-screensaver (+https://github.com/mpjanofsky/nearby-flights-screensaver)";
  private static final String KEY = "flights", TITLE = "Nearby Flights";

  private PluginHost host;
  private ScheduledExecutorService worker;
  private Map<String, Object> applied;
  private Engine engine;
  private static final long SUMMARY_EVERY_MS = 5 * 60_000L;
  private long lastSummaryMs;
  private Enricher enricher;
  private int generation; // bumped on every configure so a superseded loop stops rescheduling

  /**
   * True while the screensaver is known not to be showing. Fetching stops and the screen is the
   * idle sky. Starts false and stays false if the host cannot tell us, so a refused or failed read
   * means polling as before.
   */
  private volatile boolean paused;

  /** True when the tick loop ended because of {@link #paused} and must be restarted on resume. */
  private boolean loopParked;

  private ScheduledFuture<?> next; // the waiting tick, cancelled on pause
  private boolean ticking; // a tick is running now, and will schedule the next itself

  @Override
  public synchronized void start(PluginHost host, Map<String, Object> settings) {
    this.host = host;
    configure(settings);
    watchScreensaver();
    host.log("Nearby Flights started");
  }

  /**
   * Subscribes first, then reads the starting state (the host does not replay events). The read's
   * callback only flips a flag, so the 3 s lifecycle deadline is never at risk. If the host refuses
   * (capability not granted, older host) we keep polling and say so in the log.
   */
  private void watchScreensaver() {
    try {
      host.subscribe("screensaver.state");
      host.executeCommand(
          "isScreensaverActive",
          Collections.<String, Object>emptyMap(),
          (ok, data, error) -> {
            if (ok && data instanceof Boolean) setActive((Boolean) data);
          });
    } catch (RuntimeException e) {
      // Safe: without the signal we poll whenever the plugin is enabled, as before.
      host.log("screensaver state unavailable: " + e.getClass().getSimpleName());
    }
  }

  /** Pauses or resumes fetching when the screensaver hides or shows. */
  private synchronized void setActive(boolean active) {
    if (active != paused) return; // no change (paused is the opposite of active)
    paused = !active;
    if (engine == null) return; // settings error: its message screen stays either way
    if (paused) {
      if (next != null) next.cancel(false); // a running tick parks itself when it ends
      loopParked = !ticking;
      host.status("Paused: screensaver not showing", false);
      publish(PayloadEncoder.idle(System.currentTimeMillis()), 0);
    } else {
      engine.forcePublish(); // the idle screen replaced the last one behind the gate's back
      if (loopParked) {
        loopParked = false;
        schedule(generation, 0);
      }
    }
  }

  @Override
  public synchronized void configure(Map<String, Object> settings) {
    if (settings.equals(applied)) return; // each edit re-sends everything; skip no-ops
    applied = new HashMap<>(settings);
    stopWorker();
    stopEnricher();
    PluginSettings s = PluginSettings.parse(settings);
    if (s.error != null) {
      engine = null;
      host.status(s.error, true);
      // Say so on the screen too, instead of leaving the last screen to count up as stale.
      publish(PayloadEncoder.message(s.error, System.currentTimeMillis()), 0);
      return;
    }
    // adsbdb for hex -> registration/type; adsb.im for callsign routes.
    enricher =
        new Enricher(
            new MergedHexLookup(
                new AdsbdbLookup(USER_AGENT),
                new AdsbLolHex(USER_AGENT),
                new AdsbImRoutes(USER_AGENT)));
    enricher.start();
    final int myGen = generation + 1; // the generation schedule() below will start
    engine =
        new Engine(
            s.config,
            source(s.primary, s.localUrl),
            NONE_OR(s.fallback, s.localUrl),
            // A tick still running when settings change must not publish the old settings' screen.
            (json, genMs) -> {
              synchronized (this) {
                if (myGen != generation || paused) return;
                publish(json, genMs); // under the lock: a tick finishing mid-pause must not win
              }
            },
            s.refreshMs,
            enricher);
    // Daemon thread: a stuck request must never hold up the host's 3 s lifecycle deadline.
    worker =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "flights-fetch");
              t.setDaemon(true);
              return t;
            });
    // Edited while the screensaver is hidden: stay parked, the resume starts the new loop.
    loopParked = paused;
    ++generation;
    if (!paused) schedule(generation, 0);
  }

  private static SourceAdapter NONE_OR(String name, String url) {
    return PluginSettings.NONE.equals(name) ? null : source(name, url);
  }

  private static SourceAdapter source(String name, String localUrl) {
    if (PluginSettings.BOTH.equals(name)) {
      return new MergedSource(
          ReadsbJsonSource.local(localUrl, USER_AGENT), ReadsbJsonSource.adsbLol(USER_AGENT));
    }
    return PluginSettings.LOCAL.equals(name)
        ? ReadsbJsonSource.local(localUrl, USER_AGENT)
        : ReadsbJsonSource.adsbLol(USER_AGENT);
  }

  private synchronized void schedule(int gen, long delayMs) {
    if (worker == null || gen != generation) return;
    next = worker.schedule(() -> runTick(gen), delayMs, TimeUnit.MILLISECONDS);
  }

  private void runTick(int gen) {
    Engine e;
    synchronized (this) {
      if (gen != generation) return;
      if (paused) {
        loopParked = true; // setActive(true) restarts the loop
        return;
      }
      ticking = true;
      e = engine;
    }
    long delay;
    try {
      delay = e.tick(System.currentTimeMillis());
      if (!paused) host.status(e.statusText(), e.isStale()); // keep "Paused" showing
      for (String line : e.drainEnrichEvents()) host.log(line);
      long nowMs = System.currentTimeMillis();
      if (nowMs - lastSummaryMs >= SUMMARY_EVERY_MS) {
        lastSummaryMs = nowMs;
        host.log(e.enrichSummary()); // counts and durations only: no location, URL or token
      }
    } catch (RuntimeException | Error ex) {
      // An unexpected bug (or an Error such as a failed class load) must not kill the loop
      // silently: report it and retry slowly. The class name only, never the message.
      host.log("tick failed: " + ex.getClass().getSimpleName());
      host.status("Internal error: " + ex.getClass().getSimpleName(), true);
      delay = 30_000;
    }
    synchronized (this) {
      ticking = false;
      if (paused && gen == generation) {
        loopParked = true;
        return;
      }
    }
    schedule(gen, delay);
  }

  private void publish(String payloadJson, long genMs) {
    try {
      // replaceFirst: the page's own code must never contain the marker, but be safe anyway.
      String html =
          FlightsHtml.html().replaceFirst("/\\*PAYLOAD\\*/", Matcher.quoteReplacement(payloadJson));
      host.publishScreensaver(KEY, TITLE, html);
    } catch (RuntimeException e) {
      host.log(
          "publish skipped: "
              + e.getClass().getSimpleName()); // host revoked after stop(): safe to drop
    }
  }

  private void stopEnricher() {
    if (enricher != null) enricher.stop();
    enricher = null;
  }

  private void stopWorker() {
    if (worker != null) worker.shutdownNow();
    worker = null;
  }

  @Override
  public void execute(String command, Map<String, Object> arguments) {}

  @Override
  public void onEvent(String event, Map<String, Object> payload) {
    if ("ks.screensaver.state".equals(event) && payload != null) {
      Object active = payload.get("active");
      if (active instanceof Boolean) setActive((Boolean) active);
    }
  }

  @Override
  public synchronized void stop() {
    generation++;
    paused = false; // a later start() reads the state afresh
    loopParked = false;
    applied = null; // a later start() with unchanged settings must rebuild, not skip as a no-op
    stopWorker();
    stopEnricher();
  }
}
