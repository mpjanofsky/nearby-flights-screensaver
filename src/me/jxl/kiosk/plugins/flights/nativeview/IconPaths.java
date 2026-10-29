// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import java.util.HashMap;
import java.util.Map;

/** Draws the aircraft silhouettes of {@link AircraftIcons}: parsed once, tinted per call. */
final class IconPaths {
  private static final float EXTENT = 512f;

  /** Share of the icon box the silhouette fills; the page leaves a margin for the rim. */
  private static final float FILL = 0.875f;

  private final Map<String, Path> paths = new HashMap<>();
  private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);

  IconPaths() {
    rim.setStyle(Paint.Style.STROKE);
    rim.setStrokeJoin(Paint.Join.ROUND);
    body.setStyle(Paint.Style.FILL);
  }

  /**
   * Draws the icon for an ICAO type centred on (cx, cy) in a box of {@code box} pixels, nose
   * pointing {@code heading} degrees clockwise from up. The dark rim keeps a pale or dark fill
   * readable on the background.
   */
  void draw(
      Canvas c,
      String type,
      float cx,
      float cy,
      float box,
      float heading,
      int color,
      int rimColor,
      int alpha) {
    Path p = path(AircraftIcons.keyFor(type));
    float s = box * FILL / EXTENT;
    c.save();
    c.translate(cx, cy);
    c.rotate(heading);
    c.scale(s, s);
    rim.setColor(rimColor);
    rim.setAlpha(alpha);
    rim.setStrokeWidth(Math.max(1f, box * 0.06f) / s);
    c.drawPath(p, rim);
    body.setColor(color);
    body.setAlpha(alpha);
    c.drawPath(p, body);
    c.restore();
  }

  private Path path(String key) {
    Path p = paths.get(key);
    if (p == null) {
      p = build(key);
      paths.put(key, p);
    }
    return p;
  }

  private static Path build(String key) {
    String[] t = AircraftIcons.PATHS;
    for (int i = 0; i + 2 < t.length; i += 3) {
      if (!t[i].equals(key)) continue;
      Path p = parse(t[i + 2]);
      p.setFillType(t[i + 1].equals("eo") ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
      return p;
    }
    throw new IllegalStateException("no icon " + key); // keyFor only returns baked keys
  }

  /** The generator's own format: absolute M/L/C/Z only, e.g. "M1 -256 C... L... Z". */
  private static Path parse(String d) {
    Path p = new Path();
    String[] tok = d.split(" ");
    int i = 0;
    while (i < tok.length) {
      String s = tok[i++];
      char cmd = s.charAt(0);
      String first = s.substring(1);
      if (cmd == 'Z') {
        p.close();
        continue;
      }
      int n = cmd == 'C' ? 6 : 2;
      float[] v = new float[n];
      v[0] = Float.parseFloat(first);
      for (int j = 1; j < n; j++) v[j] = Float.parseFloat(tok[i++]);
      if (cmd == 'M') p.moveTo(v[0], v[1]);
      else if (cmd == 'L') p.lineTo(v[0], v[1]);
      else p.cubicTo(v[0], v[1], v[2], v[3], v[4], v[5]);
    }
    return p;
  }
}
