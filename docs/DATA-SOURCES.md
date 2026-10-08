# Data sources

Every service, dataset and bundled asset the plugin uses is listed here: what it gives us, what
we send it, its terms, and what credit it needs. A new source is added to this page before it
ships. Last checked October 2026.

## Services the plugin calls

| Source | What we use it for | What we send | Terms | Credit |
|---|---|---|---|---|
| Your own receiver (readsb or tar1090 `aircraft.json`) | Positions (the usual primary source) | Nothing. The plugin only reads the file, on your network. | Your own data | None |
| [adsb.lol](https://adsb.lol) `/v2/point` and `/v2/hex` | Positions (fallback, or primary, or the farther steps of Soft radius), and a live callsign by hex code when your receiver has none | Your location rounded to two decimals with the radius plus 1 nm for positions; only the hex code for a callsign lookup | Data and API are ODbL. Rate limits are "dynamic", and keys may be required in future. We send a descriptive User-Agent and back off on 429. Nothing is stored. | "Contains information from adsb.lol, made available under the ODbL" (in the README) |
| [adsb.im](https://adsb.im) `/api/0/route/{callsign}` | Route (origin and destination) by callsign | One callsign per request, no location, at most one request per second. Cached 12 hours (misses 1 hour), in memory only. | The server's published API description says ODbL 1.0, serving community [standing data](https://github.com/vradarserver/standing-data) (CC0, updated daily). It has no separate usage terms. adsb.lol's equivalent endpoint is deprecated in favour of static files with the same data. | Credited in the README |
| [adsbdb.com](https://www.adsbdb.com) `/aircraft/{hex}` and `/airline/{code}` | Registration, type and registered owner by hex code; airline name by operator code | A hex code, or an operator code. Never a location. | Server code is MIT. Its README credits PlaneBase for aircraft data. No stated rate limit or API terms. We query live and keep a bounded in-memory cache (12 hours), never a copy. | adsbdb.com and PlaneBase (in the README) |

## Bundled with the plugin

| Asset | What it is for | Terms | Credit |
|---|---|---|---|
| [OurAirports](https://ourairports.com/data/) subset | Airport markers on the scope. Large airports plus scheduled medium ones, compiled into `AirportData.java` by `scripts/gen-airports.py`. | Public domain ("released to the Public Domain, with no guarantee of accuracy"). Re-read 2026-10-08. | Not required, given in the README |
| ADS-B Radar aircraft icon pack | Aircraft silhouettes by type | "Free for personal and commercial use, with a backlink to ADS-B Radar", from the pack's own readme. | "Icons by ADS-B Radar for macOS - https://adsb-radar.com" (in the README and the icon file) |
| Airline table (`Airlines.java`, brand hues in `lib.js`) | Callsign prefix to airline name, and the chip colour | Written for this project from public facts. The hues are approximate. | None |
| US N-number from hex (`enrich/NNumber`) | A registration for a US hex code when nothing else gave one | The FAA/ICAO allocation of the US block A00001-ADF7C7 to N-numbers is a public rule, implemented from the rule. No data file, no network. | None |

## How the sources fit together

**Positions (every refresh, default 10 s)** — `SourceAdapter` → `ReadsbJsonSource`, two instances:
- *Local feed* (the home readsb `aircraft.json`, LAN): primary by default; sees only what the antenna hears, and
  often has no callsign, registration or type.
- *adsb.lol* `/v2/point/{lat}/{lon}/{nm}` (location rounded to 2 decimals, radius +1 nm): the fallback. Used when the
  local feed fails, retried against the primary every 6th tick; also asked for the wider probes of Soft radius mode
  because the local antenna cannot see far. With no local URL configured, adsb.lol runs alone.
- 3 failed ticks in a row = stale (status line, dimmed display); backoff follows the failures and `Retry-After`.
- Selector then drops: on the ground, position fix older than 60 s, outside the radius, GA when hidden.

**Enrichment (background thread, 1 request/s, memory cache, never blocks a publish)** — `Enricher`:
- *adsbdb* `/aircraft/{hex}`: registration, type, and the registered owner's operator code (airline fallback).
- *adsb.lol* `/v2/hex/{hex}`: the live callsign when the feed has none (cached 20 min; if it is down, adsbdb's answer
  still stands). Requests carry only the hex. If adsbdb is the one that fails, adsb.lol's answer (registration, type, live
  callsign) stands in when it has a callsign; otherwise the lookup fails and backs off. No new service, no new data sent.
- *adsb.im* `/api/0/route/{callsign}`: origin and destination (two airports only; dropped if the great circle misses the
  aircraft by more than 100 km).
- A route that does not fit the aircraft is simply not shown.
- Bundled `Airlines` table: airline name from the callsign prefix, so a name shows before any lookup. A prefix not in the table is looked up once by operator code (`adsbdb /v0/airline/<ICAO>`, the code only, never a location) and cached like the other lookups (hits 12 h, misses 1 h, memory only).
- Airline precedence: route answer, then callsign prefix (table), then the operator-code lookup, then the registered owner. Cache: hits 12 h, misses 1 h.

**Not used:** the renderer has no network.

**Airports (`ap`):** OurAirports `airports.csv` (public domain), subset compiled into `AirportData.java` by
`scripts/gen-airports.py` (large airports plus scheduled medium ones, code = IATA else ICAO; 3,286 airports, 68 KB).
`Airports.within` filters to the radius around the exact home. Refresh by re-downloading the CSV and rerunning the script.
