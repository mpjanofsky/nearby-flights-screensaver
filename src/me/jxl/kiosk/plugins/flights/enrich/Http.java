// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small bounded GET shared by the lookups. Error messages name the service, never the URL. */
final class Http {
  private static final int TIMEOUT_MS = 5000, MAX_BODY_BYTES = 64 * 1024;

  /** A batch answer is a few KB per aircraft, so a POST may be larger than a single GET. */
  static final int MAX_POST_BODY_BYTES = 256 * 1024;

  private Http() {}

  /** A non-200 answer; carries the status and the server's Retry-After seconds (0 if none). */
  static final class StatusException extends IOException {
    final int code, retryAfterS;

    StatusException(String service, int code, int retryAfterS) {
      super(service + " HTTP " + code);
      this.code = code;
      this.retryAfterS = retryAfterS;
    }
  }

  /** Null on 404; throws on any other non-200 so the caller backs off. */
  static String get(String url, String userAgent, String service) throws IOException {
    return get(url, userAgent, service, TIMEOUT_MS);
  }

  /** As above, for a service that is slow to answer. The lookups run one at a time off-thread. */
  static String get(String url, String userAgent, String service, int timeoutMs)
      throws IOException {
    return request(url, userAgent, service, timeoutMs, null, MAX_BODY_BYTES);
  }

  /** A JSON POST. Null on 404; throws on any other non-200 so the caller backs off. */
  static String postJson(String url, String json, String userAgent, String service, int timeoutMs)
      throws IOException {
    return request(url, userAgent, service, timeoutMs, json, MAX_POST_BODY_BYTES);
  }

  private static String request(
      String url, String userAgent, String service, int timeoutMs, String json, int maxBytes)
      throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    try {
      c.setConnectTimeout(timeoutMs);
      c.setReadTimeout(timeoutMs);
      c.setRequestProperty("User-Agent", userAgent);
      if (json != null) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
          out.write(body);
        }
      }
      int code = c.getResponseCode();
      if (code == 404) return null;
      if (code != 200) {
        throw new StatusException(service, code, retryAfterS(c.getHeaderField("Retry-After")));
      }
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
          if (out.size() > maxBytes) throw new IOException(service + " response too large");
        }
        return out.toString(StandardCharsets.UTF_8.name());
      }
    } finally {
      c.disconnect();
    }
  }

  /** Whole seconds, 0 for a missing or HTTP-date value (the normal backoff then applies). */
  static int retryAfterS(String header) {
    if (header == null) return 0;
    try {
      return Math.max(0, Math.min(3600, Integer.parseInt(header.trim())));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
