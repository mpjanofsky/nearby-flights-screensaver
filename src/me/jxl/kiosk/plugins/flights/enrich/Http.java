// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.enrich;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small bounded GET shared by the lookups. Error messages name the service, never the URL. */
final class Http {
  private static final int TIMEOUT_MS = 5000, MAX_BODY_BYTES = 64 * 1024;

  private Http() {}

  /** Null on 404; throws on any other non-200 so the caller backs off. */
  static String get(String url, String userAgent, String service) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    try {
      c.setConnectTimeout(TIMEOUT_MS);
      c.setReadTimeout(TIMEOUT_MS);
      c.setRequestProperty("User-Agent", userAgent);
      int code = c.getResponseCode();
      if (code == 404) return null;
      if (code != 200) throw new IOException(service + " HTTP " + code);
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
          if (out.size() > MAX_BODY_BYTES) throw new IOException(service + " response too large");
        }
        return out.toString(StandardCharsets.UTF_8.name());
      }
    } finally {
      c.disconnect();
    }
  }
}
