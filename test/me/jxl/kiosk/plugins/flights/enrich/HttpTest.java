package me.jxl.kiosk.plugins.flights.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpTest {
  @Test
  void retryAfterIsWholeSecondsAndAnythingElseIsZero() {
    assertEquals(30, Http.retryAfterS(" 30 "));
    assertEquals(3600, Http.retryAfterS("99999"));
    assertEquals(0, Http.retryAfterS(null));
    assertEquals(0, Http.retryAfterS("Wed, 21 Oct 2026 07:28:00 GMT"));
  }

  @Test
  void aSlowAnswerNeedsALongerTimeout() throws Exception {
    HttpServer srv =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    srv.createContext(
        "/",
        ex -> {
          try {
            Thread.sleep(700); // slower than the short timeout, faster than the long one
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    srv.start();
    try {
      String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/";
      assertThrows(SocketTimeoutException.class, () -> Http.get(url, "t", "test", 200));
      assertEquals("ok", Http.get(url, "t", "test", 3000));
    } finally {
      srv.stop(0);
    }
  }
}
