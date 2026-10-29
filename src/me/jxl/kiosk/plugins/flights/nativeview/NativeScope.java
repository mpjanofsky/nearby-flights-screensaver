// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import android.content.Context;
import android.view.View;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.JSONException;

/**
 * The whole screen (band, list, scope, footer) drawn natively from the same payload JSON the HTML
 * page gets. In 0.10.0 it is a beta: a second screensaver beside the HTML one, which stays the
 * default. The HTML one is removed in a later release.
 */
public final class NativeScope {
  public static final String KEY = "flights_native";
  public static final String TITLE = "Nearby Flights (beta, native)";

  /** The newest payload. Written on the plugin thread, read on the main thread. */
  static volatile Model model;

  private static volatile String stats;

  private NativeScope() {}

  public static void register(PluginHost host) {
    host.publishScreensaverView(
        KEY,
        TITLE,
        new OverlayFactory() {
          @Override
          public View create(Context context, KsTheme theme) {
            return new ScopeView(context);
          }
        });
  }

  /** Hands the newest payload to the view; no document is rebuilt. */
  public static void update(String payloadJson) {
    try {
      model = Model.parse(payloadJson, System.currentTimeMillis());
    } catch (JSONException e) {
      // Safe: the view keeps drawing the previous payload, which ages out through dead-reckoning
      // and the stale treatment, the same as a failed fetch.
    }
  }

  /** Frame-rate line for the status line, or null before the first window. */
  public static String stats() {
    return stats;
  }

  static void setStats(String s) {
    stats = s;
  }
}
