#!/usr/bin/env python3
"""Write the shared golden payload fixtures (fixtures/payload/*.json).

Everything is fictional: positions are metres east/north of an imaginary home, so no
real coordinates exist anywhere. Rerun after changing docs/payload.md, then commit
both together. Usage: scripts/gen-fixtures.py
"""
import json, math, pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "fixtures" / "payload"
GEN = 1760000000000  # fixed so the files are byte-stable; the harness rewrites it
NM, FT, KT = 1852.0, 0.3048, 1852 / 3600


def ac(id, x, y, gs_kt, trk, alt_ft, vr_fpm, a=2.0, trail_s=(60, 45, 30, 15, 0), **kw):
    gs, v = gs_kt * KT, (math.sin(math.radians(trk)), math.cos(math.radians(trk)))
    tr = [[round(a + t, 1), round(x - gs * t * v[0]), round(y - gs * t * v[1])] for t in trail_s]
    d = dict(id=id, x=round(x), y=round(y), a=a, gs=round(gs, 1), trk=trk,
             alt=round(alt_ft * FT), vr=round(vr_fpm * FT / 60, 1), tr=tr)
    d.update(kw)
    return d


def base(ac_list, **kw):
    p = dict(v=2, gen=GEN, fa=2.0, st=False, src="local", r=int(15 * NM), u="av", ac=ac_list,
             ap=[dict(c="NRW", x=round(-5.2 * NM), y=round(8.1 * NM)),
                 dict(c="SPT", x=round(9.5 * NM), y=round(-3.4 * NM))])
    p.update(kw)
    return p


normal = [
    ac("a1b2c3", 2.1 * NM, 3.0 * NM, 142, 215, 2400, -700, cs="DAL1234", rg="N371DA", ty="B739",
       al="DAL", an="Delta", o="ATL", d="MCO"),
    ac("a4d5e6", -5.5 * NM, 1.2 * NM, 250, 95, 6100, 1800, cs="ASA2210", rg="N8812Q", ty="B38M",
       al="ASA", an="Alaska", o="MCO", d="PDX"),
    ac("0c7f81", 6.0 * NM, -7.2 * NM, 410, 310, 34000, 0, cs="UAL482", rg="N27244", ty="B77W",
       al="UAL", an="United", o="EWR", d="LAX"),
    ac("ab12cd", -1.0 * NM, 9.6 * NM, 95, 20, 1500, 400, cs="N512QX", rg="N512QX", ty="C172"),
]
missing = [
    ac("f00001", 3.0 * NM, 2.0 * NM, 180, 270, 5200, 0, cs="JBU915", al="JBU"),
    dict(id="f00002", x=round(-4 * NM), y=round(-6 * NM), a=8.0),
]
longs = [
    ac("e2e2e2", -30.0 * NM, -22.0 * NM, 300, 40, 12500, -1500, cs="ABX1103", ty="B763",
       al="ABX", an="ABX Air", o="CVG", d="MIA"),
    ac("e1e1e1", 41.5 * NM, 12.0 * NM, 480, 250, 41000, 0, cs="EDV4852X", rg="N8915A", ty="CRJ9",
       al="EDV", an="Endeavor Air", o="DTW", d="MSP"),
]

# A realistic mix for judging the list layout: a route, an airline tail number with no route,
# a hex-only aircraft, and one whose position fix is old (rendered dim).
mix = [
    ac("0c2f1e", 3.0 * NM, 0.4 * NM, 172, 20, 3000, 0, cs="CMP393", rg="HP-1822CMP", ty="B738",
       al="CMP", an="Copa Airlines", o="PTY", d="TPA"),
    ac("ac7256", 9.0 * NM, 2.0 * NM, 160, 300, 2000, 400, cs="N9006", rg="N9006", ty="A319",
       al="AAL", an="American Airlines"),
    ac("a002a6", 8.5 * NM, -3.0 * NM, 150, 80, 3000, 0),
    ac("a84e3b", 8.0 * NM, -8.0 * NM, 300, 230, 12000, -1500, a=75.0, cs="DAL2195", rg="N849DN",
       ty="B739", al="DAL", an="Delta Air Lines", o="ATL", d="EYW \u2192 ATL"),  # a multi-stop chain
]

# Route confidence (confirmed (both ends), unconfirmed (origin only, an airline whose
# destinations run stale), unknown (airline and type, no route), a GA tail number, and a "~" target
# (mlat/TIS-B: no real ICAO address, nothing can be looked up).
tiers = [
    ac("aa0001", 2.0 * NM, 3.0 * NM, 150, 200, 2600, -600, cs="AAY928", rg="N280G", ty="A320",
       al="AAY", an="Allegiant Air", o="CVG"),
    ac("aa0002", -5.0 * NM, 1.0 * NM, 300, 95, 11000, 1800, cs="DAL2507", rg="N371DA", ty="B739",
       al="DAL", an="Delta Air Lines", o="ATL", d="MCO"),
    ac("aa0003", 6.0 * NM, -6.0 * NM, 410, 310, 37000, 0, cs="UAL482", ty="B77W", al="UAL", an="United"),
    ac("aa0004", -1.0 * NM, 8.0 * NM, 95, 20, 1500, 400, cs="N512QX", rg="N512QX", ty="C172"),
    ac("~234ba0", 7.0 * NM, 5.0 * NM, 120, 150, 3000, 0),
]

# A refresh in which things moved: the previous publish had 3 list rows (pn=3) and the featured
# a1b2c3. Now a4d5e6 is featured (it was list slot 1), a1b2c3 dropped to the list (pv 0), 0c7f81 moved
# up from slot 3 to 2... ab12cd is new, and f00009 left (it was slot 2, so a ghost fades out).
moved = [
    dict(normal[1], pv=1),
    dict(normal[0], pv=0),
    dict(normal[2], pv=3),
    dict(normal[3]),
]
gone = [{k: v for k, v in ac("f00009", 4.0 * NM, -2.0 * NM, 200, 100, 5000, 0, cs="SKW5521", ty="E75L",
                             al="SKW", an="SkyWest", o="DEN", d="SLC").items() if k != "tr"} | dict(pv=2)]

fx = {
    "transition": base(moved, ft="a4d5e6", pn=3, gone=gone),
    "list-mix": base(mix, ft="0c2f1e"),
    "route-tiers": base(tiers, ft="aa0001"),  # featured: origin only
    "route-tiers-b": base(tiers, ft="aa0003"),  # featured: no route
    "normal-5": base(normal + [ac("c0ffee", -8.0 * NM, -6.0 * NM, 210, 60, 9000, 0, cs="JBU915", al="JBU",
                                  an="JetBlue", ty="A320", o="FLL", d="BOS")], ft="a4d5e6", src="mix"),  # 1 featured + 4 rows, merged feeds
    "normal-4": base(normal, ft="a4d5e6"),  # featured need not be first
    "rows-1": base(normal[:1]),
    "rows-2": base(normal[:2]),
    "rows-3": base(normal[:3]),
    "empty": base([]),
    "clock-12": base([], clock="12"),
    "clock-24": base([], clock="24"),
    "stale": base(normal, st=True, fa=150.0),
    "idle": base([], fa=None, idle=True),  # fetching paused while the screensaver is hidden
    "settings-error": base([], ap=[], msg="Set your home latitude and longitude"),
    "missing-fields": base(missing),
    "long-strings": base(longs, r=int(50 * NM)),
    "metric": base(normal, u="met", src="net"),
}
for name, p in fx.items():
    (OUT / f"{name}.json").write_text(json.dumps(p, indent=1) + "\n")
(OUT / "index.json").write_text(json.dumps(sorted(fx)) + "\n")
print(f"wrote {len(fx)} fixtures")
