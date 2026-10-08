// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

import java.util.Collections;
import java.util.List;

/** The outcome of one successful fetch. Failures are exceptions, never an empty list. */
public final class FetchResult {
  public final List<Aircraft> aircraft;

  /** Device wall clock (epoch ms) when the response arrived: ages are measured from here. */
  public final long observedAtMs;

  /** The feed's own `now` (its units differ by source) and cumulative `messages`; NaN if absent. */
  public final double feedNow, feedMessages;

  public FetchResult(List<Aircraft> aircraft, long observedAtMs) {
    this(aircraft, observedAtMs, Double.NaN, Double.NaN);
  }

  public FetchResult(
      List<Aircraft> aircraft, long observedAtMs, double feedNow, double feedMessages) {
    this.aircraft = Collections.unmodifiableList(aircraft);
    this.observedAtMs = observedAtMs;
    this.feedNow = feedNow;
    this.feedMessages = feedMessages;
  }
}
