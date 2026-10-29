// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The prototype screen: band (featured aircraft and clock), list, footer and scope, laid out in the
 * HTML page's landscape units (960 x 480, style.css). Unlike the page it keeps state between
 * payloads, so a row slides to a new place and a new aircraft fades in without any bookkeeping from
 * Java (the pn/pv/gone fields exist only because the page is rebuilt on every publish).
 */
final class ScopeView extends View {
  private static final int BG = 0xFF0B0F14, FG = 0xFFE8EEF5, DIM = 0xFFCDD8E3, LINE = 0xFF3A4A5C;
  private static final int SCOPE_TEXT = 0xFFDBE3EC; // the page's scope text colour
  private static final int ACCENT = 0xFF5CC8FF, HOT = 0xFFFFCF5C, FAINT = 0xFF8393A5;
  private static final long FRAME_NS =
      28_000_000L; // invalidate on every second vsync at 60 Hz: 30 fps
  private static final long STATS_EVERY_MS = 5000;
  private static final long STALE_FADE_MS = 3000;
  private static final float ICON_EM =
      0.92f; // chip icon box as a share of the chip's text size (the page: 26/28, 22/24)

  private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final IconPaths icons = new IconPaths();
  private final Path scratch = new Path();
  private final RectF rect = new RectF();
  private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
  private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
  private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
  private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);

  private LinearGradient legendShader;
  private float legendX;
  private float u = 1; // screen pixels per layout unit
  private float ts = 1, tsc = 1; // text scale, and the clock's half-strength one (Layout)
  private Layout lay;
  private float layW, layH;
  private int layPct, layMr;
  private boolean layRb;
  private double bandSpotTop; // units
  private final Scene scene = new Scene();
  private long lastFrameMs, tick0; // tick0: this frame's monotonic time, for field fades
  private float scopeDt; // ms since the scope was last drawn
  private long lastScopeTick;
  private float staleMix; // 0 live .. 1 stale, eased
  private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());

  private boolean running;
  private long lastInvalidateNs, lastDraw, lastStats, drawCount, slow;
  private double worstMs, drawSumMs, drawWorstMs;
  private String drawError;

  private final Choreographer.FrameCallback pump =
      new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
          if (!running) return;
          // Frame skipping: keep waking on every vsync, but only invalidate when 28 ms have passed.
          // A delayed invalidate lands near 50 ms on the Echo; this lands on every second vsync.
          if (frameTimeNanos - lastInvalidateNs >= FRAME_NS) {
            lastInvalidateNs = frameTimeNanos;
            invalidate();
          }
          Choreographer.getInstance().postFrameCallback(this);
        }
      };

  ScopeView(Context context) {
    super(context);
    line.setStyle(Paint.Style.STROKE);
    line.setStrokeJoin(Paint.Join.ROUND);
    line.setStrokeCap(Paint.Cap.ROUND);
    sweep.setStyle(Paint.Style.FILL);
    sweep.setShader(
        new SweepGradient(
            0,
            0,
            new int[] {0x005CC8FF, 0x005CC8FF, 0x2A5CC8FF},
            new float[] {0f, 1f - 0.143f, 1f}));
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    start();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    stop();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility == VISIBLE) start();
    else stop();
  }

  private void start() {
    if (running) return;
    running = true;
    lastDraw = 0;
    lastStats = 0;
    Choreographer.getInstance().postFrameCallback(pump);
  }

  private void stop() {
    running = false;
    Choreographer.getInstance().removeFrameCallback(pump);
  }

  @Override
  protected void onDraw(Canvas c) {
    long tick = SystemClock.uptimeMillis();
    c.drawColor(BG);
    try {
      draw(c, System.currentTimeMillis(), tick);
    } catch (RuntimeException e) {
      // An exception in onDraw ends the whole app (docs/overlays.md), so a bad payload costs one
      // frame instead. The class name goes on the status line so it is not silent.
      drawError = e.getClass().getSimpleName();
    }
    double spent = SystemClock.uptimeMillis() - tick; // our own drawing, not the host's compositing
    drawSumMs += spent;
    if (spent > drawWorstMs) drawWorstMs = spent;
    frameStats(tick);
  }

  private void draw(Canvas c, long wall, long tick) {
    float w = getWidth(), h = getHeight();
    u = Math.min(h / 480f, w / 960f);
    Model m = NativeScope.model;
    if (m == null) {
      txt(c, "Waiting for data…", w / 2, h / 2, 26, DIM, regular, Paint.Align.CENTER, 1);
      return;
    }
    tick0 = tick;
    scene.accept(m, wall, tick);
    String[] st = m.status(wall);
    boolean isStale = "stale".equals(st[0]);
    long dt = lastFrameMs == 0 ? 0 : tick - lastFrameMs;
    lastFrameMs = tick;
    float target = isStale ? 1 : 0;
    staleMix += (target - staleMix) * (float) (1 - Math.exp(-dt / (STALE_FADE_MS / 3.0)));
    float contentAlpha = 1 - 0.2f * staleMix; // the feed going stale dims the page gradually

    if (lay == null
        || layW != w
        || layH != h
        || layPct != m.textPct
        || layMr != m.maxRows
        || layRb != m.radarBottom) {
      lay = Layout.of(w, h, m.textPct, m.maxRows, m.radarBottom);
      layW = w;
      layH = h;
      layPct = m.textPct;
      layMr = m.maxRows;
      layRb = m.radarBottom;
    }
    u = (float) lay.u;
    ts = (float) lay.ts;
    tsc = (float) lay.tsc;
    float left = (float) lay.left * u, top = (float) lay.bandY * u, inner = (float) lay.inner * u;
    float leftW = (float) lay.listW * u;
    float colTop = (float) lay.listTop * u;
    float footerTop = (float) lay.footerY * u, listBottom = (float) lay.listBottom * u;
    boolean land = !lay.portrait();
    // Where a featured aircraft's band form sits (units): the reference its slide to a row starts
    // from.
    bandSpotTop =
        land || lay.shape == Layout.Shape.SQ ? lay.bandY : lay.bandY + (78 * lay.tsc) + 10;
    if (land) scene.frame(wall, tick, lay.listBottom, lay.pitch(), bandSpotTop - 2);
    else scene.frame(wall, tick, lay.listTop, lay.pitch(), bandSpotTop - 2, true);

    Scene.Craft feat = scene.featured();
    boolean calm = "empty".equals(st[0]);
    boolean solo = land && scene.presentCount() == 1 && m.msg == null && !m.idle && !isStale;

    band(c, m, st, wall, tick, left, top, inner, contentAlpha, solo);

    txt(c, srcText(m), left, footerTop + 17 * 0.9f * u, 17 * ts, DIM, regular, Paint.Align.LEFT, 1);
    if (calm) {
      calm(
          c,
          st,
          left,
          colTop,
          leftW,
          land ? listBottom : colTop + 4 * (float) lay.pitch() * u,
          wall);
    } else if (solo) {
      if (feat != null)
        solo(c, feat.row, left, colTop, leftW, listBottom, contentAlpha * feat.listA);
    } else {
      list(c, tick, left, colTop, leftW, contentAlpha);
    }

    float scopeSize = (float) lay.scopeSize * u;
    float sx = (float) lay.scopeX * u, sy = (float) lay.scopeY * u;
    c.save();
    c.translate(sx, sy);
    scope(
        c,
        m,
        scene.byAltitudeDescending(),
        scene.featId,
        wall,
        tick,
        scopeSize,
        isStale,
        calm ? 0.5f : 1f);
    c.restore();
  }

  // ---- band
  // --------------------------------------------------------------------------------------

  private void band(
      Canvas c,
      Model m,
      String[] st,
      long wall,
      long tick,
      float left,
      float top,
      float inner,
      float alpha,
      boolean solo) {
    // Clock, right aligned: time over date (or the stale note in its place).
    Date now = new Date(wall);
    String time =
        "24".equals(m.clock)
            ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            : "12".equals(m.clock)
                ? new SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
                : java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(now);
    boolean tall = lay.shape == Layout.Shape.TALL;
    float bandBottom = (float) (lay.bandY + lay.bandH) * u;
    float spotW;
    if (tall) {
      // Clock face on its own across the top, time over date; the aircraft goes below it.
      float cx = left + inner / 2;
      txt(c, time, cx, top + 52 * tsc * 0.8f * u, 52 * tsc, FG, bold, Paint.Align.CENTER, 1);
      float dateBase = top + (52 * 1.05f * tsc + 15.5f * tsc) * u;
      if ("stale".equals(st[0])) {
        txt(c, st[1], cx, dateBase, 20 * tsc, HOT, bold, Paint.Align.CENTER, 1);
      } else {
        txt(c, dateFmt.format(now), cx, dateBase, 20 * tsc, DIM, regular, Paint.Align.CENTER, 1);
      }
      spotW = inner;
    } else {
      // Clock, right aligned, centred in the band: time over date (or the stale note in its place).
      float cr = left + inner;
      float clockTop = top + ((float) lay.bandH - 9 - 66 * tsc) / 2 * u;
      txt(c, time, cr, clockTop + 29.9f * tsc * u, 40 * tsc, FG, bold, Paint.Align.RIGHT, 1);
      float dateBase = clockTop + 57.5f * tsc * u;
      if ("stale".equals(st[0])) {
        txt(c, st[1], cr, dateBase, 20 * tsc, HOT, bold, Paint.Align.RIGHT, 1);
      } else {
        txt(c, dateFmt.format(now), cr, dateBase, 20 * tsc, DIM, regular, Paint.Align.RIGHT, 1);
      }
      spotW = inner - (150 * tsc + 20) * u;
    }
    line.setColor(LINE);
    line.setStrokeWidth(Math.max(1, u));
    c.drawLine(left, bandBottom, left + inner, bandBottom, line);

    if (solo) return; // the one aircraft is shown large on the left instead
    float spotTop = (float) bandSpotTop * u;
    boolean any = false;
    for (Scene.Craft k : scene.crafts.values()) {
      if (k.feat.x < 0.004f || k.listA <= 0) continue;
      any = true;
      // Moving in or out of the band it slides along its row's path, fading as the role changes.
      float shift = (float) (k.slot.x - (bandSpotTop - 2)) * u;
      spotlight(c, k, left, spotTop + shift, spotW, alpha * k.listA * (float) k.feat.x);
    }
    if (!any && !"ok".equals(st[0]) && !"empty".equals(st[0])) {
      txt(c, st[1], left, spotTop + 36 * u, 26 * ts, DIM, regular, Paint.Align.LEFT, alpha);
    }
  }

  /** The featured aircraft's band form: solid chip, route, facts line. */
  private void spotlight(Canvas c, Scene.Craft k, float left, float top, float spotW, float a) {
    Model.Row feat = k.row;
    float y = top + 6 * u;
    int bg = Fmt.airlineColor(feat.ac.al);
    int fg = Fmt.luminance(feat.ac.al, bg) > 0.25 ? 0xFF0B0F14 : 0xFFFFFFFF;
    float x = left;
    x +=
        chip(
            c, feat.name, feat.ac.ty, x, y, 28 * ts, bg, fg, feat.ac.unidentified() ? 0.7f * a : a);
    float rx = x + 12 * u, rw = spotW - (x - left) - 12 * u, ry = y + 28 * ts * 0.95f * u;
    routeFaded(c, k, rx, ry, 26 * ts, DIM, rw, a);
    String facts = Model.facts(feat);
    boolean tall = lay.shape == Layout.Shape.TALL;
    float fsz = (tall ? 22 : 23) * ts;
    float fy = y + 28 * ts * 1.25f * u + 2 * u + fsz * 0.95f * u;
    // Tall screens have room for two short lines but not one long one: break where the meaning
    // does.
    String[] lines = tall ? wrapFacts(facts, fsz, spotW - 30 * u) : new String[] {facts};
    float fw = 0;
    for (int i = 0; i < lines.length; i++) {
      float ly = fy + i * fsz * 1.2f * u;
      fw =
          txt(
              c,
              ellipsize(lines[i], fsz, spotW - 30 * u, regular),
              left,
              ly,
              fsz,
              DIM,
              regular,
              Paint.Align.LEFT,
              a);
      if (i == lines.length - 1 && feat.trend != 0)
        arrow(c, left + fw + 14 * u, ly - 8 * u, 12 * u, feat.trend, a);
    }
  }

  /** At most two lines, broken between the " · " parts; the second is ellipsized by the caller. */
  private String[] wrapFacts(String facts, float sizeUnits, float maxW) {
    text.setTypeface(regular);
    text.setTextSize(sizeUnits * u);
    if (text.measureText(facts) <= maxW) return new String[] {facts};
    String[] parts = facts.split(" · ");
    StringBuilder first = new StringBuilder();
    int i = 0;
    for (; i < parts.length; i++) {
      String next = first.length() == 0 ? parts[i] : first + " · " + parts[i];
      if (i > 0 && text.measureText(next) > maxW) break;
      first.setLength(0);
      first.append(next);
    }
    if (i >= parts.length) return new String[] {facts};
    StringBuilder rest = new StringBuilder();
    for (int j = i; j < parts.length; j++) rest.append(j > i ? " · " : "").append(parts[j]);
    return new String[] {first.toString(), rest.toString()};
  }

  /** The route, cross-fading from its previous text when it changes (a late route arrives). */
  private void routeFaded(
      Canvas c,
      Scene.Craft k,
      float x,
      float baseline,
      float size,
      int color,
      float maxW,
      float a) {
    boolean unconfirmed = k.ac.d == null;
    float inA = k.route.in(tick0), outA = k.route.out(tick0);
    if (outA > 0) route(c, k.route.prev, x, baseline, size, unconfirmed, color, maxW, a * outA);
    if (!k.route.cur.isEmpty())
      route(c, k.route.cur, x, baseline, size, unconfirmed, color, maxW, a * inA);
  }

  // ---- list
  // ----------------------------------------------------------------------------------------

  private void list(Canvas c, long tick, float left, float colTop, float leftW, float alpha) {
    float rowH = (float) lay.rowH * u;
    for (Scene.Craft k : scene.crafts.values()) {
      boolean moving = k.feat.x > 0.004f && k.feat.x < 0.996f; // between list and band
      boolean listed = scene.isListed(k);
      if (!listed && !moving) continue;
      float a = alpha * k.listA * (float) (1 - k.feat.x);
      if (a <= 0) continue;
      if (k.row.stale) a *= 1 - 0.45f * (float) Math.min(1, k.row.over * 1000 / STALE_FADE_MS);
      c.save();
      // Rows that slide under the band (a departing row pushed above the list) are cut off.
      if (!moving) c.clipRect(left - 4 * u, colTop, left + leftW + 4 * u, getHeight());
      row(c, k, left, (float) k.slot.x * u, leftW, rowH, a);
      c.restore();
    }
  }

  private void row(
      Canvas c, Scene.Craft k, float left, float top, float leftW, float rowH, float a) {
    Model.Row r = k.row;
    // The page centres a row's two lines in the row.
    float x = left, y = top + (rowH - (53.8f * ts + 4) * u) / 2;
    int bg = Fmt.airlineColor(r.ac.al);
    float rightCol = left + leftW;
    float mainW = leftW - (6 + 150 * tsc) * u;
    float cw =
        outlineChip(c, r.name, r.ac.ty, x, y, 24 * ts, bg, r.ac.unidentified() ? 0.7f * a : a);
    routeFaded(
        c, k, x + cw + 12 * u, y + 24 * ts * 1.05f * u, 24 * ts, DIM, mainW - cw - 12 * u, a);
    // Baselines measured against the HTML page at 960 x 480 (build/screens): its airline line sits
    // 1 unit higher and its distance 4 higher than the airline line a shared baseline gave.
    float sy = y + 31 * ts * u + 4 * u + 20 * ts * 0.9f * u - u;
    fieldFaded(c, k.sub, x, sy, 20 * ts, DIM, regular, Paint.Align.LEFT, mainW, a);
    // Right column: altitude (in the scope's colour) and distance. The altitude leaves room for the
    // climb/descent arrow only while there is one.
    int altColor = r.ac.alt != r.ac.alt ? DIM : Fmt.altColor(r.ac.alt);
    float slot = 28 * u * (float) k.arrow.x;
    fieldFaded(
        c,
        k.alt,
        rightCol - slot,
        y + 28 * ts * 0.9f * u,
        28 * ts,
        altColor,
        medium,
        Paint.Align.RIGHT,
        0,
        a);
    if (k.arrow.x > 0.01f && k.trendDir != 0)
      arrow(c, rightCol - slot / 2, y + 14 * ts * u, 11 * u, k.trendDir, a * (float) k.arrow.x);
    txt(c, r.distText, rightCol, sy - 3 * u, 20 * ts, DIM, regular, Paint.Align.RIGHT, a);
  }

  /** A text field that cross-fades when its value changes. maxW 0 means no ellipsis. */
  private void fieldFaded(
      Canvas c,
      Scene.Fader f,
      float x,
      float baseline,
      float size,
      int color,
      Typeface face,
      Paint.Align align,
      float maxW,
      float a) {
    float inA = f.in(tick0), outA = f.out(tick0);
    if (outA > 0)
      txt(c, fit(f.prev, size, maxW, face), x, baseline, size, color, face, align, a * outA);
    txt(c, fit(f.cur, size, maxW, face), x, baseline, size, color, face, align, a * inA);
  }

  private String fit(String s, float size, float maxW, Typeface face) {
    return maxW > 0 ? ellipsize(s, size, maxW, face) : s;
  }

  private void solo(Canvas c, Model.Row r, float left, float top, float w, float bottom, float a) {
    if (r == null) return;
    float t = ts;
    float y = top + Math.max(0, (bottom - top - 190 * t * u) / 2);
    int bg = Fmt.airlineColor(r.ac.al);
    int fg = Fmt.luminance(r.ac.al, bg) > 0.25 ? 0xFF0B0F14 : 0xFFFFFFFF;
    chip(c, r.name, r.ac.ty, left, y, 44 * t, bg, fg, a);
    String route = Model.route(r.ac);
    float ry = y + 44 * t * 1.25f * u + 10 * u;
    if (route != null)
      route(c, route, left, ry + 40 * t * 0.9f * u, 40 * t, r.ac.d == null, FG, w, a);
    float ay = ry + 40 * t * 1.1f * u + 10 * u;
    float aw =
        txt(
            c,
            r.alt,
            left,
            ay + 56 * t * 0.9f * u,
            56 * t,
            r.ac.alt != r.ac.alt ? DIM : Fmt.altColor(r.ac.alt),
            bold,
            Paint.Align.LEFT,
            a);
    if (r.trend != 0) arrow(c, left + aw + 28 * t * u, ay + 30 * t * u, 18 * t * u, r.trend, a);
    txt(
        c,
        r.distText,
        left,
        ay + 56 * t * 1.1f * u + 28 * t * 0.9f * u,
        28 * t,
        DIM,
        regular,
        Paint.Align.LEFT,
        a);
    String who = join(" · ", r.ac.an, r.type.isEmpty() ? null : r.type, r.ac.rg);
    txt(
        c,
        ellipsize(who, 22 * t, w, regular),
        left,
        ay + 56 * t * 1.1f * u + 28 * t * 1.1f * u + 8 * u + 22 * t * 0.9f * u,
        22 * t,
        DIM,
        regular,
        Paint.Align.LEFT,
        a);
  }

  private void calm(
      Canvas c, String[] st, float left, float top, float w, float bottom, long wall) {
    int hr = Integer.parseInt(new SimpleDateFormat("H", Locale.US).format(new Date(wall)));
    // The page's column, centred in the list area: the sky panel (110 units + 10), the title,
    // the detail line.
    float titleH = 48 * ts * u, detailH = 25 * ts * u;
    float blockH = (float) (SkySim.H + 10) * u + titleH + 6 * u + detailH;
    float y0 = (top + bottom) / 2 - blockH / 2, cx = left + w / 2;
    sky(c, left, y0, w, wall);
    float ty = y0 + (float) (SkySim.H + 10) * u;
    txt(
        c,
        hr >= 21 || hr < 5 ? "Quiet night sky" : "Quiet sky",
        cx,
        ty + titleH * 0.5f + 40 * ts * 0.35f * u,
        40 * ts,
        DIM,
        light,
        Paint.Align.CENTER,
        1);
    txt(
        c,
        st[1],
        cx,
        ty + titleH + 6 * u + detailH * 0.5f + 21 * ts * 0.35f * u,
        21 * ts,
        FAINT,
        light,
        Paint.Align.CENTER,
        1);
  }

  /** The drifting airliner and clouds (SkySim) in the panel whose top-left is (x, y). */
  private void sky(Canvas c, float x, float y, float w, long wall) {
    SkySim.Frame f = SkySim.at(wall, w / u);
    c.save();
    c.clipRect(x, y, x + w, y + (float) SkySim.H * u);
    fill.setStyle(Paint.Style.FILL);
    for (SkySim.Cloud cl : f.clouds) {
      fill.setColor(0xFF6B8099);
      fill.setAlpha((int) Math.round(255 * cl.alpha));
      scratch.reset();
      for (double[] b : SkySim.PUFF) {
        float cx = x + (float) (cl.x + b[0] * cl.s) * u, cy = y + (float) (cl.y + b[1] * cl.s) * u;
        scratch.addCircle(cx, cy, (float) (b[2] * cl.s) * u, Path.Direction.CW);
      }
      c.drawPath(scratch, fill); // one path, so the overlaps show no seams
    }
    for (SkySim.Dot d : f.trail) {
      fill.setColor(0xFF93A6BD);
      fill.setAlpha((int) Math.round(255 * d.alpha));
      c.drawCircle(x + (float) d.x * u, y + (float) d.y * u, (float) d.radius * u, fill);
    }
    if (f.plane)
      icons.draw(
          c,
          "B738",
          x + (float) f.px * u,
          y + (float) f.py * u,
          38 * u,
          (float) Math.toDegrees(f.angle),
          0xFF6B8099,
          BG,
          (int) Math.round(191 * f.planeAlpha));
    c.restore();
  }

  private static String srcText(Model m) {
    return "local".equals(m.src)
        ? "● Local feed"
        : "mix".equals(m.src) ? "● Local + internet" : "● Internet feed";
  }

  // ---- scope
  // ---------------------------------------------------------------------------------------

  private void scope(
      Canvas c,
      Model m,
      List<Scene.Craft> crafts,
      String featId,
      long wall,
      long tick,
      float size,
      boolean dim,
      float panelAlpha) {
    scopeDt = lastScopeTick == 0 ? 0 : Math.min(500, tick - lastScopeTick);
    lastScopeTick = tick;
    float cx = size / 2, cy = size / 2, margin = size * 0.07f, rr = cx - margin;
    float k = rr / (float) m.r;
    int alpha = (int) (255 * panelAlpha);
    line.setStrokeWidth(3);
    line.setColor(0xFFA9BACD);
    line.setAlpha((int) (alpha * 1f));
    c.drawCircle(cx, cy, rr, line);
    line.setStrokeWidth(2);
    line.setColor(0xFF6B8099);
    line.setAlpha(alpha);
    double[] rings = rings(m.r, m.u);
    for (int i = 0; i < rings.length; i++) c.drawCircle(cx, cy, (float) rings[i] * k, line);
    c.drawLine(cx - rr, cy, cx + rr, cy, line);
    c.drawLine(cx, cy - rr, cx, cy + rr, line);
    float o = rr + margin * 0.5f, ts = size * 0.038f;
    px(c, "N", cx, cy - o + ts * 0.35f, ts, SCOPE_TEXT, panelAlpha);
    px(c, "S", cx, cy + o + ts * 0.35f, ts, SCOPE_TEXT, panelAlpha);
    px(c, "E", cx + o, cy + ts * 0.35f, ts, SCOPE_TEXT, panelAlpha);
    px(c, "W", cx - o, cy + ts * 0.35f, ts, SCOPE_TEXT, panelAlpha);
    // The page labels the outer ring, and every ring when there are fewer than three.
    for (int i = 0; i < rings.length; i++) {
      if (i != rings.length - 1 && rings.length >= 3) continue;
      float gr = (float) rings[i] * k;
      px(
          c,
          ringLabel(rings[i], m.u),
          cx + gr * 0.707f + 4,
          cy + gr * 0.707f + 8 + ts * 0.35f,
          ts,
          SCOPE_TEXT,
          panelAlpha);
    }

    if (!dim) {
      float deg = Scene.sweepDeg(tick);
      c.save();
      c.translate(cx, cy);
      c.rotate(deg);
      sweep.setAlpha(alpha);
      c.drawCircle(0, 0, rr, sweep);
      line.setColor(0x8C5CC8FF);
      c.drawLine(0, 0, rr, 0, line);
      c.restore();
    }
    line.setColor(FG);
    line.setAlpha(alpha);
    line.setStrokeWidth(2);
    c.drawLine(cx - 6, cy, cx + 6, cy, line);
    c.drawLine(cx, cy - 6, cx, cy + 6, line);
    for (Object[] ap : m.ap) {
      double ax = (Double) ap[1], ay = (Double) ap[2];
      if (Math.hypot(ax, ay) > m.r) continue;
      float x = cx + (float) ax * k, y = cy - (float) ay * k;
      line.setColor(FAINT);
      c.drawRect(x - 4, y - 4, x + 4, y + 4, line);
      px(c, (String) ap[0], x, y + 16 + ts * 0.35f, ts, FAINT, panelAlpha);
    }

    legend(c, size, m.u, panelAlpha);

    // Declutter: close aircraft are nudged apart (at most 3.5 % of the scope), and the nudge eases
    // in and out as neighbours come and go. Trails stay on the true positions.
    int cnt = crafts.size();
    float[][] truePts = new float[cnt][2];
    for (int i = 0; i < cnt; i++) {
      Scene.Craft cr = crafts.get(i);
      truePts[i][0] = cx + (float) (cr.row.x + cr.ex.x) * k;
      truePts[i][1] = cy - (float) (cr.row.y + cr.ey.x) * k;
    }
    float[][] spreadPts = Declutter.spread(truePts, size * 0.13f, size * 0.035f);
    float ease = scopeDt <= 0 ? 1 : (float) (1 - Math.exp(-scopeDt / 400.0));
    Declutter.Labels labels = new Declutter.Labels();
    labels.block(cx - 10, cx + 10, cy, 10, -1);
    for (Object[] ap : m.ap) {
      if (Math.hypot((Double) ap[1], (Double) ap[2]) > m.r) continue;
      float ax = cx + (float) (double) (Double) ap[1] * k,
          ay = cy - (float) (double) (Double) ap[2] * k;
      labels.block(ax - 16, ax + 16, ay + 8, 14, -1);
    }
    float[][] at = new float[cnt][];
    for (int i = 0; i < cnt; i++) {
      Scene.Craft cr = crafts.get(i);
      float tx = (spreadPts[i][0] - truePts[i][0]) / size,
          ty = (spreadPts[i][1] - truePts[i][1]) / size;
      if (!cr.nudgeInit) {
        cr.nudgeX = tx;
        cr.nudgeY = ty;
        cr.nudgeInit = true;
      } else {
        cr.nudgeX += (tx - cr.nudgeX) * ease;
        cr.nudgeY += (ty - cr.nudgeY) * ease;
      }
      float sx = truePts[i][0] + cr.nudgeX * size, sy = truePts[i][1] + cr.nudgeY * size;
      float d = (float) Math.hypot(sx - cx, sy - cy);
      if (d > rr) { // every icon stays on or inside the ring
        sx = cx + (sx - cx) * rr / d;
        sy = cy + (sy - cy) * rr / d;
      }
      at[i] = new float[] {sx, sy};
      labels.block(sx - size * 0.055f, sx + size * 0.055f, sy, size * 0.055f, i);
    }

    for (int i = 0; i < cnt; i++) {
      Scene.Craft cr = crafts.get(i);
      Model.Row r = cr.row;
      Model.Ac a = cr.ac;
      float x = at[i][0], y = at[i][1];
      float tx0 = truePts[i][0], ty0 = truePts[i][1];
      int col = Fmt.altColor(a.alt);
      float al = (r.stale || dim ? 0.55f : 1f) * panelAlpha * cr.scopeA;
      float lx = 0, ly = 0;
      int n = a.trail.length + 1;
      line.setStrokeWidth(2);
      for (int j = 0; j < n; j++) {
        float tx = j < n - 1 ? cx + (float) a.trail[j][0] * k : tx0;
        float ty = j < n - 1 ? cy - (float) a.trail[j][1] * k : ty0;
        if (j > 0) {
          line.setColor(col);
          line.setAlpha((int) (255 * (0.15 + 0.5 * j / n) * al));
          c.drawLine(lx, ly, tx, ty, line);
        }
        lx = tx;
        ly = ty;
      }
      float is = size * 0.045f;
      if (a.id.equals(featId)) {
        line.setColor(HOT);
        line.setAlpha((int) (255 * panelAlpha * cr.scopeA));
        line.setStrokeWidth(2);
        c.drawCircle(x, y, is * 1.4f, line);
      }
      icons.draw(
          c, a.ty, x, y, size * 0.11f, cr.headingInit ? cr.heading : 0f, col, BG, (int) (255 * al));
      // The label takes the nearest clear place beside the icon (the page's search, Declutter).
      float lsz = size * 0.04f;
      text.setTypeface(medium);
      text.setTextSize(lsz);
      float lw = text.measureText(a.name);
      float[] pos =
          labels.place(x, y, size * 0.11f, lw, size * 0.045f, size, i, cr.labelSide, cr.labelStep);
      cr.labelSide = (int) pos[0];
      cr.labelStep = (int) pos[3];
      // The pick can flip side or line as neighbours move; the drawn label glides there instead.
      float tgtX = pos[0] > 0 ? pos[1] - lw : pos[1];
      if (!cr.labelInit) {
        cr.labelX = tgtX;
        cr.labelY = pos[2];
        cr.labelInit = true;
      } else {
        cr.labelX += (tgtX - cr.labelX) * ease;
        cr.labelY += (pos[2] - cr.labelY) * ease;
      }
      label(
          c,
          a.name,
          cr.labelX,
          cr.labelY + lsz * 0.35f,
          lsz,
          Paint.Align.LEFT,
          a.unidentified() ? 0.6f * al : al);
    }
  }

  private static double unitM(String u) {
    return "met".equals(u) ? 1000 : "imp".equals(u) ? 1609.344 : 1852;
  }

  private static String unitName(String u) {
    return "met".equals(u) ? "km" : "imp".equals(u) ? "mi" : "nm";
  }

  private static String ringLabel(double meters, String u) {
    return Math.round(meters / unitM(u)) + " " + unitName(u);
  }

  private static double[] rings(double r, String unit) {
    double[] steps = {1, 2, 5, 10, 25, 50, 100};
    double rd = r / unitM(unit), step = 100;
    for (double s : steps) {
      if (rd / s <= 3.5) {
        step = s;
        break;
      }
    }
    int n = (int) Math.floor(rd / step + 1e-9);
    double[] out = new double[n];
    for (int i = 0; i < n; i++) out[i] = (i + 1) * step * unitM(unit);
    return out;
  }

  // ---- drawing helpers
  // -----------------------------------------------------------------------------

  /** Text sized in layout units; returns its width in pixels. y is the baseline. */
  private float txt(
      Canvas c,
      String s,
      float x,
      float y,
      float sizeUnits,
      int color,
      Typeface face,
      Paint.Align align,
      float alpha) {
    if (s == null || s.isEmpty() || sizeUnits <= 0) return 0;
    text.setTypeface(face);
    text.setTextSize(sizeUnits * u);
    text.setColor(color);
    text.setAlpha((int) (255 * Math.min(1, Math.max(0, alpha))));
    text.setTextAlign(align);
    c.drawText(s, x, y, text);
    return text.measureText(s);
  }

  /** Text sized in pixels (the scope's own scale). */
  private void px(Canvas c, String s, float x, float y, float sizePx, int color, float alpha) {
    px(c, s, x, y, sizePx, color, alpha, Paint.Align.CENTER);
  }

  private void px(
      Canvas c,
      String s,
      float x,
      float y,
      float sizePx,
      int color,
      float alpha,
      Paint.Align align) {
    text.setTypeface(regular);
    text.setTextSize(sizePx);
    text.setColor(color);
    text.setAlpha((int) (255 * Math.min(1, Math.max(0, alpha))));
    text.setTextAlign(align);
    c.drawText(s, x, y, text);
  }

  /** Scope text with a dark outline, as the page strokes its aircraft labels. y is the baseline. */
  private void label(
      Canvas c, String s, float x, float y, float sizePx, Paint.Align align, float alpha) {
    text.setTypeface(medium);
    text.setTextSize(sizePx);
    text.setTextAlign(align);
    int a = (int) (255 * Math.min(1, Math.max(0, alpha)));
    text.setStyle(Paint.Style.STROKE);
    text.setStrokeWidth(4);
    text.setStrokeJoin(Paint.Join.ROUND);
    text.setColor(BG);
    text.setAlpha(a);
    c.drawText(s, x, y, text);
    text.setStyle(Paint.Style.FILL);
    text.setColor(SCOPE_TEXT);
    text.setAlpha(a);
    c.drawText(s, x, y, text);
  }

  /** The altitude colour ramp under the scope's lower left, with its two end labels. */
  private void legend(Canvas c, float size, String unit, float alpha) {
    float w = size * 0.2f, h = size * 0.022f, x = size * 0.02f, y = size * 0.93f;
    if (legendShader == null || legendX != x) {
      int[] cols = new int[11];
      float[] pos = new float[11];
      for (int k = 0; k <= 10; k++) {
        cols[k] = Fmt.altColor(k / 10.0 * Model.ALT_TOP_M);
        pos[k] = k / 10f;
      }
      legendShader = new LinearGradient(x, 0, x + w, 0, cols, pos, Shader.TileMode.CLAMP);
      legendX = x;
    }
    fill.setStyle(Paint.Style.FILL);
    fill.setShader(legendShader);
    fill.setAlpha((int) (255 * alpha));
    c.drawRect(x, y, x + w, y + h, fill);
    fill.setShader(null);
    float fs = size * 0.036f;
    px(c, "0", x, y - 4, fs, SCOPE_TEXT, alpha, Paint.Align.LEFT);
    px(
        c,
        "met".equals(unit) ? "12 km" : "40k ft",
        x + w,
        y - 4,
        fs,
        SCOPE_TEXT,
        alpha,
        Paint.Align.RIGHT);
  }

  private String ellipsize(String s, float sizeUnits, float maxW, Typeface face) {
    text.setTypeface(face);
    text.setTextSize(sizeUnits * u);
    if (text.measureText(s) <= maxW) return s;
    int n = text.breakText(s, true, maxW - text.measureText("…"), null);
    return s.substring(0, Math.max(0, n)) + "…";
  }

  private static String join(String sep, String... parts) {
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (p == null || p.isEmpty()) continue;
      if (sb.length() > 0) sb.append(sep);
      sb.append(p);
    }
    return sb.toString();
  }

  /** A solid callsign chip; returns its width in pixels. */
  private float chip(
      Canvas c,
      String s,
      String type,
      float x,
      float y,
      float sizeUnits,
      int bg,
      int fg,
      float alpha) {
    text.setTypeface(bold);
    text.setTextSize(sizeUnits * u);
    float ic = sizeUnits * ICON_EM * u, gap = sizeUnits * 0.25f * u;
    float tw = text.measureText(s) + ic + gap, padX = 12 * u, h = sizeUnits * 1.25f * u;
    fill.setStyle(Paint.Style.FILL);
    fill.setColor(bg);
    fill.setAlpha((int) (255 * alpha));
    rect.set(x, y, x + tw + 2 * padX, y + h);
    c.drawRoundRect(rect, 8 * u, 8 * u, fill);
    icons.draw(c, type, x + padX + ic / 2, y + h * 0.55f, ic, 0, fg, bg, (int) (255 * alpha));
    txt(c, s, x + padX + ic + gap, y + h * 0.78f, sizeUnits, fg, bold, Paint.Align.LEFT, alpha);
    return tw + 2 * padX;
  }

  /** The list's chip: an outline in the airline colour, plain text inside. */
  private float outlineChip(
      Canvas c, String s, String type, float x, float y, float sizeUnits, int color, float alpha) {
    text.setTypeface(bold);
    text.setTextSize(sizeUnits * u);
    float ic = sizeUnits * ICON_EM * u, gap = sizeUnits * 0.25f * u;
    float tw = text.measureText(s) + ic + gap, padX = 10 * u, h = sizeUnits * 1.25f * u;
    line.setStrokeWidth(2 * u);
    line.setColor(color);
    line.setAlpha((int) (255 * alpha));
    rect.set(x + u, y + u, x + tw + 2 * padX - u, y + h - u);
    c.drawRoundRect(rect, 7 * u, 7 * u, line);
    icons.draw(c, type, x + padX + ic / 2, y + h * 0.55f, ic, 0, FG, BG, (int) (255 * alpha));
    txt(c, s, x + padX + ic + gap, y + h * 0.78f, sizeUnits, FG, bold, Paint.Align.LEFT, alpha);
    return tw + 2 * padX;
  }

  /** Route text with a drawn arrow (the page draws it too: the glyph differs between devices). */
  private void route(
      Canvas c,
      String route,
      float x,
      float baseline,
      float sizeUnits,
      boolean unconfirmed,
      int color,
      float maxW,
      float alpha) {
    Typeface face = unconfirmed ? Typeface.create("sans-serif-medium", Typeface.ITALIC) : medium;
    int col = unconfirmed ? 0xFF9FB0C2 : color;
    String[] parts = route.split(" → ", -1);
    float cx = x;
    float em = sizeUnits * u;
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        float aw = em * 0.95f, ah = em * 0.58f, ax = cx + em * 0.3f, ay = baseline - em * 0.3f;
        line.setStrokeWidth(Math.max(1.5f, em * 0.09f));
        line.setColor(col);
        line.setAlpha((int) (255 * alpha));
        scratch.reset();
        scratch.moveTo(ax, ay);
        scratch.lineTo(ax + aw * 0.9f, ay);
        scratch.moveTo(ax + aw * 0.62f, ay - ah / 2);
        scratch.lineTo(ax + aw * 0.9f, ay);
        scratch.lineTo(ax + aw * 0.62f, ay + ah / 2);
        c.drawPath(scratch, line);
        cx += aw + em * 0.6f;
      }
      if (!parts[i].isEmpty())
        cx += txt(c, parts[i], cx, baseline, sizeUnits, col, face, Paint.Align.LEFT, alpha);
    }
  }

  /** A small climb or descent triangle centred at (x, y). */
  private void arrow(Canvas c, float x, float y, float s, int dir, float alpha) {
    scratch.reset();
    if (dir > 0) {
      scratch.moveTo(x, y - s * 0.6f);
      scratch.lineTo(x + s * 0.6f, y + s * 0.5f);
      scratch.lineTo(x - s * 0.6f, y + s * 0.5f);
    } else {
      scratch.moveTo(x, y + s * 0.6f);
      scratch.lineTo(x + s * 0.6f, y - s * 0.5f);
      scratch.lineTo(x - s * 0.6f, y - s * 0.5f);
    }
    scratch.close();
    fill.setStyle(Paint.Style.FILL);
    fill.setColor(ACCENT);
    fill.setAlpha((int) (255 * alpha));
    c.drawPath(scratch, fill);
  }

  /** Frame intervals, so the status line can say how smooth it really is on the device. */
  private void frameStats(long tick) {
    if (lastDraw != 0) {
      double dt = tick - lastDraw;
      drawCount++;
      if (dt > 50) slow++;
      if (dt > worstMs) worstMs = dt;
    }
    lastDraw = tick;
    if (lastStats == 0) lastStats = tick;
    if (tick - lastStats >= STATS_EVERY_MS) {
      double fps = drawCount * 1000.0 / (tick - lastStats);
      NativeScope.setStats(
          String.format(
              Locale.US,
              "Native view: %.1f fps, worst frame %.0f ms, %d slow, our draw avg %.1f worst %.0f"
                  + " ms%s",
              fps,
              worstMs,
              slow,
              drawSumMs / Math.max(1, drawCount),
              drawWorstMs,
              drawError == null ? "" : ", draw error " + drawError));
      lastStats = tick;
      drawCount = 0;
      slow = 0;
      worstMs = 0;
      drawSumMs = 0;
      drawWorstMs = 0;
      drawError = null;
    }
  }
}
