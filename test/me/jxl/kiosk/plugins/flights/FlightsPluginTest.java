package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import me.jxl.kiosk.plugins.PluginHost;
import org.junit.jupiter.api.Test;

/** The screensaver-visibility gate, driven through a fake host. */
class FlightsPluginTest {
  static final class FakeHost implements PluginHost {
    final List<String> published = new CopyOnWriteArrayList<>();
    final List<String> statuses = new CopyOnWriteArrayList<>();
    final List<String> logs = new CopyOnWriteArrayList<>();
    Boolean initiallyActive = true; // null: the read fails
    boolean refuse; // subscribe throws, as without the host.read capability

    public void showWindow(String t, String m, String b) {}

    public void hideWindow() {}

    public void log(String m) {
      logs.add(m);
    }

    public void status(String m, boolean error) {
      statuses.add(m);
    }

    public void publishScreensaver(String key, String title, String html) {
      published.add(html);
    }

    public void subscribe(String event) {
      if (refuse) throw new UnsupportedOperationException("host.read not granted");
    }

    public void executeCommand(String c, Map<String, Object> a, CommandCallback cb) {
      if (initiallyActive == null) cb.onResult(false, null, "failed");
      else cb.onResult(true, initiallyActive, null);
    }
  }

  /**
   * A local feed that refuses connections at once, so a tick publishes without any real network.
   */
  static Map<String, Object> settings() {
    Map<String, Object> m = new HashMap<>();
    m.put("homeLat", "27.5");
    m.put("homeLon", "-82.5");
    m.put("primarySource", "Local feed");
    m.put("fallbackSource", "None");
    m.put("localUrl", "http://127.0.0.1:1/aircraft.json");
    return m;
  }

  static Map<String, Object> state(boolean active) {
    return Collections.<String, Object>singletonMap("active", active);
  }

  static boolean waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
    for (int i = 0; i < 60 && !c.getAsBoolean(); i++) Thread.sleep(50);
    return c.getAsBoolean();
  }

  static boolean isIdle(String html) {
    return html.contains("\"idle\":true");
  }

  @Test
  void hidingPublishesTheIdleSkyAndStopsFetchingUntilItShowsAgain() throws Exception {
    FakeHost h = new FakeHost();
    FlightsPlugin p = new FlightsPlugin();
    try {
      p.start(h, settings());
      assertTrue(waitFor(() -> !h.published.isEmpty()), "the first tick publishes");
      assertFalse(isIdle(h.published.get(0)));

      p.onEvent("ks.screensaver.state", state(false));
      assertTrue(isIdle(h.published.get(h.published.size() - 1)));
      assertEquals("Paused: screensaver not showing", h.statuses.get(h.statuses.size() - 1));
      int n = h.published.size();
      Thread.sleep(300);
      assertEquals(n, h.published.size(), "nothing is published while paused");

      p.onEvent("ks.screensaver.state", state(true));
      assertTrue(
          waitFor(() -> !isIdle(h.published.get(h.published.size() - 1))),
          "the first tick after a wake replaces the idle sky even though the data is unchanged");
    } finally {
      p.stop();
    }
  }

  @Test
  void startingWhileHiddenParksTheLoop() throws Exception {
    FakeHost h = new FakeHost();
    h.initiallyActive = false;
    FlightsPlugin p = new FlightsPlugin();
    try {
      p.start(h, settings());
      assertTrue(
          waitFor(() -> !h.published.isEmpty() && isIdle(h.published.get(h.published.size() - 1))));
      int n = h.published.size();
      Thread.sleep(300);
      assertEquals(n, h.published.size());
    } finally {
      p.stop();
    }
  }

  @Test
  void aRefusedOrFailedReadKeepsPollingAsBefore() throws Exception {
    FakeHost refused = new FakeHost();
    refused.refuse = true;
    FlightsPlugin p = new FlightsPlugin();
    try {
      p.start(refused, settings());
      assertTrue(waitFor(() -> !refused.published.isEmpty()));
      assertFalse(isIdle(refused.published.get(0)));
      assertTrue(
          refused.logs.stream().anyMatch(l -> l.startsWith("screensaver state unavailable")));
    } finally {
      p.stop();
    }
    FakeHost failed = new FakeHost();
    failed.initiallyActive = null;
    p = new FlightsPlugin();
    try {
      p.start(failed, settings());
      assertTrue(waitFor(() -> !failed.published.isEmpty()));
      assertFalse(isIdle(failed.published.get(0)));
    } finally {
      p.stop();
    }
  }

  @Test
  void theIdlePayloadMatchesTheGoldenFixture() throws Exception {
    org.json.JSONObject m = new org.json.JSONObject(PayloadEncoder.idle(1L));
    org.json.JSONObject g =
        new org.json.JSONObject(
            new String(
                java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("fixtures/payload/idle.json")),
                "UTF-8"));
    for (String k : new String[] {"v", "st", "src", "r", "u", "idle"}) {
      assertEquals(g.get(k), m.get(k), k);
    }
    assertTrue(m.isNull("fa") && g.isNull("fa"));
    assertEquals(0, m.getJSONArray("ac").length());
  }
}
