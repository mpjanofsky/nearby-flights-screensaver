#!/usr/bin/env python3
"""Bake the aircraft icons in assets/flights/icons.js into src/.../nativeview/AircraftIcons.java.

The native view cannot draw SVG, so each icon becomes one absolute path (M/L/C/Z, transforms applied,
arcs turned into curves) in a 512 x 512 box centred on 0,0, nose up. The type-code to icon table is
read from lib.js so the two renderers agree until the HTML one is removed.
Icons by ADS-B Radar for macOS - https://adsb-radar.com (free with a backlink; see docs/DATA-SOURCES.md).
Usage: scripts/gen-icons-java.py
"""
import json, math, pathlib, re

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src/me/jxl/kiosk/plugins/flights/nativeview/AircraftIcons.java"


def load_icons():
    s = (ROOT / "assets/flights/icons.js").read_text()
    return json.loads(s[s.index("{"): s.rindex("}") + 1])


def mat_mul(a, b):  # a * b, matrices as (a, b, c, d, e, f)
    return (a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1],
            a[0] * b[2] + a[2] * b[3], a[1] * b[2] + a[3] * b[3],
            a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5])


def parse_transform(t):
    m = (1, 0, 0, 1, 0, 0)
    for name, args in re.findall(r"(\w+)\(([^)]*)\)", t or ""):
        v = [float(x) for x in re.split(r"[ ,]+", args.strip())]
        if name == "translate":
            n = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == "scale":
            n = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif name == "matrix":
            n = tuple(v)
        else:
            raise SystemExit("unhandled transform " + name)
        m = mat_mul(m, n)
    return m


def arc_to_cubics(x1, y1, rx, ry, phi, fa, fs, x2, y2):
    if rx == 0 or ry == 0:
        return [("L", x2, y2)]
    phi = math.radians(phi)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(0, num / den)) * (-1 if fa == fs else 1)
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx, cy = cp * cxp - sp * cyp + (x1 + x2) / 2, sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    n = max(1, math.ceil(abs(dt) / (math.pi / 2)))
    d = dt / n
    k = 4 / 3 * math.tan(d / 4)
    out = []
    for i in range(n):
        a, b = t1 + i * d, t1 + (i + 1) * d

        def pt(t):
            return (cx + rx * math.cos(t) * cp - ry * math.sin(t) * sp,
                    cy + rx * math.cos(t) * sp + ry * math.sin(t) * cp)

        def dv(t):
            return (-rx * math.sin(t) * cp - ry * math.cos(t) * sp,
                    -rx * math.sin(t) * sp + ry * math.cos(t) * cp)

        p0, p3 = pt(a), pt(b)
        d0, d3 = dv(a), dv(b)
        out.append(("C", p0[0] + k * d0[0], p0[1] + k * d0[1], p3[0] - k * d3[0], p3[1] - k * d3[1], p3[0], p3[1]))
    return out


def flatten(d):
    """Path data to absolute segments: ('M',x,y) ('L',x,y) ('C',x1,y1,x2,y2,x,y) ('Z',)."""
    toks = re.findall(r"[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?", d)
    segs, i, cmd = [], 0, None
    x = y = sx = sy = 0.0
    lcx = lcy = None  # last cubic control point, for S
    num = lambda: float(toks[i])  # noqa: E731
    while i < len(toks):
        if re.match(r"[A-Za-z]", toks[i]):
            cmd = toks[i]
            i += 1
            if cmd in "Zz":
                segs.append(("Z",))
                x, y = sx, sy
                lcx = None
                continue
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = (x, y) if rel else (0, 0)
        if c == "M":
            x, y = ox + num(), oy + float(toks[i + 1])
            i += 2
            sx, sy = x, y
            segs.append(("M", x, y))
            cmd = "l" if rel else "L"
            lcx = None
        elif c == "L":
            x, y = ox + num(), oy + float(toks[i + 1])
            i += 2
            segs.append(("L", x, y))
            lcx = None
        elif c == "H":
            x = ox + num()
            i += 1
            segs.append(("L", x, y))
            lcx = None
        elif c == "V":
            y = (y if rel else 0) + num()
            i += 1
            segs.append(("L", x, y))
            lcx = None
        elif c == "C":
            v = [float(t) for t in toks[i:i + 6]]
            i += 6
            p = (ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5])
            segs.append(("C",) + p)
            lcx, lcy = p[2], p[3]
            x, y = p[4], p[5]
        elif c == "S":
            v = [float(t) for t in toks[i:i + 4]]
            i += 4
            x1, y1 = (2 * x - lcx, 2 * y - lcy) if lcx is not None else (x, y)
            p = (x1, y1, ox + v[0], oy + v[1], ox + v[2], oy + v[3])
            segs.append(("C",) + p)
            lcx, lcy = p[2], p[3]
            x, y = p[4], p[5]
        elif c == "Q":
            v = [float(t) for t in toks[i:i + 4]]
            i += 4
            qx, qy, ex, ey = ox + v[0], oy + v[1], ox + v[2], oy + v[3]
            segs.append(("C", x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y),
                         ex + 2 / 3 * (qx - ex), ey + 2 / 3 * (qy - ey), ex, ey))
            x, y = ex, ey
            lcx = None
        elif c == "A":
            v = [float(t) for t in toks[i:i + 7]]
            i += 7
            ex, ey = ox + v[5], oy + v[6]
            segs += arc_to_cubics(x, y, v[0], v[1], v[2], int(v[3]), int(v[4]), ex, ey)
            x, y = ex, ey
            lcx = None
        else:
            raise SystemExit("unhandled path command " + cmd)
    return segs


def bake(svg):
    vb = [float(v) for v in re.search(r'viewBox="([^"]*)"', svg).group(1).split()]
    k = 512 / vb[2]
    base = (k, 0, 0, k, -256 - vb[0] * k, -256 - vb[1] * k)
    # Only one <path> per icon; its parent <g> (if any) carries the transform.
    path = re.search(r"<path[^>]*>", svg).group(0)
    d = re.search(r' d="([^"]*)"', path).group(1)
    m = base
    for g in re.findall(r"<g[^>]*>", svg[:svg.index(path)]):
        t = re.search(r'transform="([^"]*)"', g)
        m = mat_mul(m, parse_transform(t.group(1) if t else ""))
    m = mat_mul(m, parse_transform((re.search(r'transform="([^"]*)"', path) or [None, ""])[1]))
    evenodd = "evenodd" in svg
    out = []
    for s in flatten(d):
        if s[0] == "Z":
            out.append("Z")
            continue
        pts = []
        for j in range(1, len(s), 2):
            pts += [m[0] * s[j] + m[2] * s[j + 1] + m[4], m[1] * s[j] + m[3] * s[j + 1] + m[5]]
        out.append(s[0] + " ".join(("%.1f" % p).rstrip("0").rstrip(".") for p in pts))
    return " ".join(out), evenodd


def type_table():
    lib = (ROOT / "assets/flights/lib.js").read_text()
    table = {}
    for key, codes in re.findall(r"family\('(\w+)', '([^']*)'\)", lib):
        for c in codes.split():
            table[c] = key
    heli = re.search(r"const HELI = /(.*)/;", lib).group(1)
    return table, heli


def main():
    icons = load_icons()
    table, heli = type_table()
    for k in set(table.values()) | {"a0", "a7"}:
        assert k in icons, k
    lines = []
    for k in sorted(icons):
        data, eo = bake(icons[k])
        lines.append('    "%s", "%s", "%s",' % (k, "eo" if eo else "nz", data))
    kv = []
    for c in sorted(table):
        kv.append('"%s", "%s"' % (c, table[c]))
    rows = [", ".join(kv[i:i + 6]) for i in range(0, len(kv), 6)]
    src = f'''// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Aircraft silhouettes as path text (nose up, 512 x 512 box centred on 0,0) and the ICAO type to
 * icon table. Generated by scripts/gen-icons-java.py from assets/flights/icons.js and lib.js; do not
 * edit. Icons by ADS-B Radar for macOS - https://adsb-radar.com (free with a backlink; see
 * docs/DATA-SOURCES.md).
 */
final class AircraftIcons {{
  /** key, fill rule ("eo" even-odd or "nz" non-zero), path text. */
  static final String[] PATHS = {{
{chr(10).join(lines)}
  }};

  private static final String[] TYPES = {{
{("," + chr(10)).join("    " + r for r in rows)}
  }};

  private static final Pattern HELI = Pattern.compile("{heli.replace(chr(92), chr(92) * 2)}");
  private static final Map<String, String> BY_TYPE = new HashMap<>();

  static {{
    for (int i = 0; i < TYPES.length; i += 2) BY_TYPE.put(TYPES[i], TYPES[i + 1]);
  }}

  /** Icon key for an ICAO type code: exact families first, then helicopters by prefix, else a generic jet. */
  static String keyFor(String ty) {{
    if (ty == null || ty.isEmpty()) return "a0";
    String t = ty.toUpperCase();
    String k = BY_TYPE.get(t);
    return k != null ? k : HELI.matcher(t).find() ? "a7" : "a0";
  }}

  private AircraftIcons() {{}}
}}
'''
    OUT.write_text(src)
    print(f"{len(icons)} icons, {len(table)} types, {OUT.stat().st_size} B")


main()
