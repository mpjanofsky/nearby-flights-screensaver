// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

/** A fetch failed. The caller feeds this to the stale/backoff state and tries the fallback. */
public final class SourceException extends Exception {
  /** Seconds the server asked us to wait (Retry-After), or 0 when it did not say. */
  public final int retryAfterS;

  public SourceException(String message, Throwable cause, int retryAfterS) {
    super(message, cause);
    this.retryAfterS = retryAfterS;
  }

  public SourceException(String message) {
    this(message, null, 0);
  }
}
