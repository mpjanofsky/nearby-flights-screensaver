# Payload contract (Java → renderer)

The only interface between the plugin and the renderer. It is versioned with a
`v` key that the renderer checks. Any change updates this file and
`fixtures/payload/` (regenerate with `scripts/gen-fixtures.py`) in the same commit.

**Status:** v2 (v1 plus the transition keys `pn`, `pv`, `gone`). Golden fixtures: `fixtures/payload/*.json`.

## Delivery

Inline HTML. Java substitutes the compact JSON for the `/*PAYLOAD*/`
marker in `<script id="payload" type="application/json">/*PAYLOAD*/</script>` and
publishes the whole page. The 32 KiB data-map limit does not apply; the target is
still ≤8 KiB (the 4-aircraft fixture is ~1.2 KiB). Java must escape `</` as `<\/`
inside the JSON. Each publish is a new document and the page is sandboxed (opaque origin, no `localStorage`; probed 2026-10-07), so the renderer cannot remember the previous layout: Java states it (see Transitions).

## Conventions

- Raw SI: metres, metres/second, degrees. The renderer converts for display.
- **Positions are metres east (`x`) / north (`y`) of home**, computed by Java from
  the exact home. No lat/lon and no home coordinate ever reaches the page. The
  renderer owns projection and dead-reckoning in this local plane.
- **Time needs no cross-clock correction.** Java and the page share the device
  clock. `gen` is Java's wall clock (epoch ms) at encode time; every `a`/`fa` is an
  age in seconds *at `gen`*. The renderer's age of anything is
  `age + (Date.now() - gen) / 1000`.
- Absent or unknown optional fields are `null` or omitted; the renderer never
  prints "null" or leaves a hole.

## Top level

| Key | Type | Meaning |
|---|---|---|
| `v` | int | Contract version. `2`. The renderer shows a "version mismatch" state otherwise. |
| `gen` | number | Epoch ms when Java encoded the payload. |
| `fa` | number\|null | Seconds since the last successful fetch, at `gen`. `null` = never fetched. |
| `st` | bool | Java's stale flag (N consecutive failures). |
| `src` | `"local"`\|`"net"`\|`"mix"` | Active source, for the footer indicator. `mix` = both feeds merged (added 2026-10-07, additive: an older renderer would reject it). |
| `r` | number | Radius in metres. Blips beyond it are not drawn. |
| `u` | `"av"`\|`"met"`\|`"imp"` | Display units: aviation (nm, ft, kt), metric (km, m, km/h), imperial (mi, ft, mph). |
| `clock` | `"12"`\|`"24"`\|omitted | Clock hour cycle. Omitted follows the device locale; `"24"` uses 00 at midnight. Device time zone and date format are unchanged. Additive: no version bump. |
| `ft` | string\|omitted | Id of the featured aircraft (the top band). Must match an `ac[].id`; the renderer falls back to the first row if it does not or if `ft` is absent. |
| `pn` | int\|omitted | Transition (below): how many list rows (not counting the featured aircraft) the previous publish showed. Omitted on the first publish: nothing to animate from. |
| `msg` | string\|omitted | A calm one-line message shown in place of the aircraft (used for a settings problem Java cannot fix by retrying). Sent with `ac` empty; the renderer shows it in the top band and the stale treatment is not applied. Additive: no version bump. |
| `idle` | bool\|omitted | True while Java has paused fetching because the screensaver is not showing. Sent with `ac` empty and `fa` null; the renderer shows the quiet sky with "Looking for aircraft…" and never applies the stale treatment, because the document may be shown hours later and is replaced within seconds. Additive: no version bump. |
| `ac` | array ≤5 | The featured aircraft plus the list rows (up to 4), in list order (Java decides; see below). Usually near-closest-first but deliberately **not** re-sorted on every publish. |
| `gone` | array ≤5\|omitted | Aircraft shown by the previous publish and not in this one, in their previous order, so the renderer can fade them out. Same shape as `ac[]` without `tr`; the scope ignores them. Omitted when empty. |
| `ap` | array ≤8 | Airports within the radius: `{c: code, x, y}` (metres east/north of home). Java picks them from the bundled OurAirports subset (`Airports`): large airports first, then nearest. |

## Aircraft (`ac[]`)

| Key | Type | Meaning |
|---|---|---|
| `id` | string | Hex id. Required. |
| `x`, `y` | number | Metres E/N of home at observation time. Required. |
| `a` | number | Age of `x`,`y` in seconds at `gen`. Required. |
| `pv` | int\|omitted | Slot this aircraft held in the previous publish: `0` the featured band, `1..` the list rows in order (the featured aircraft is not in the list). Omitted when it was not shown, or when there is no `pn`. |
| `cs`, `rg`, `ty` | string\|null | Callsign, registration, ICAO type code. |
| `al`, `an` | string\|null | Airline ICAO prefix (e.g. `DAL`), airline name. |
| `o`, `d` | string\|null | Origin and destination, IATA code (ICAO when no IATA exists). Both null when unknown. A multi-stop callsign is narrowed in Java to the leg the aircraft is on (`Enricher.pickLeg`: nearest leg; if several are within 30 km of the nearest, the one the aircraft is flying: closing on its destination and moving away from its origin). With no track and no clear nearest leg the whole chain is sent: `o` the first airport, `d` the rest joined with ` → `. |
| `alt` | number\|null | Altitude, metres. |
| `gs` | number\|null | Ground speed, m/s. |
| `trk` | number\|null | Track, degrees true, 0–360. |
| `vr` | number\|null | Vertical rate, m/s (+ climb). |
| `tr` | array | Trail `[age, x, y]`, oldest first, ages at `gen`. ≥3 points when known. |

## Transitions (`pn`, `pv`, `gone`)

So that nothing switches abruptly on a refresh. Java (`Transition`, kept by the `Engine` from the last publish, not the last
fetch) says where everything was; the renderer cross-fades what changed over 1 s, starting when the page first
paints, and carries on with a negative delay when it rebuilds its text each second. There is no motion: an
abrupt change of content is what catches the eye, so changed places fade in over the old content.

- A list row's place is counted in rows from the bottom (`k`; the list is bottom-aligned). A row is
  unchanged when its `k` now equals `pn - pv`; otherwise it fades in and its old content fades out at the old place.
- `pv` omitted: the row is new and fades in. `pv` 0: it was the featured aircraft and fades in as a list row.
- The featured aircraft with `pv` other than 0 (or none) means a different aircraft took the band: the new one
  fades in over the old one (the aircraft that had `pv` 0, in `ac` or `gone`), which fades out.
- `gone` entries with `pv` >= 1 fade out at their old place; one with `pv` 0 is the old band's content.
- Nothing changed place and nothing came or went: no animation. A heartbeat republish is one.
- Fading is for the list and band. Scope blips still jump to the new fix.

## Renderer rules the contract implies

- Dead-reckoning runs `min(age, 60 s)` forward along `trk` at `gs`; beyond 60 s the
  blip stops and takes the stale treatment. With no `gs`/`trk` it never moves.
- Stale state: `st` is true **or** the fetch age exceeds 120 s. Java therefore republishes
  at least every 60 s even when nothing changed (the diff gate's heartbeat), so a healthy
  feed never ages past the limit.
- The renderer shows the list in `ac` order **without** the featured aircraft (it has the top band) and features `ft`; the scope draws all of `ac`, so every aircraft in the list or the band is on the scope. One beyond `r` (for example during its grace period) is drawn on the ring at its bearing. It does not rank, rotate or
  re-pick; the sweep angle is the only clock-driven part.

## How the list and the featured aircraft are chosen

All of this is Java (`Selector`, `Featuring`; constants in `Featuring`). The renderer just
displays the result.

**Membership (`Selector`).** Drop aircraft on the ground and, when the setting asks, general
aviation. Keep those within the effective radius (see the radius limit setting and `Engine.widen`),
sort by distance from the exact home, and keep the closest `maxRows + 1` (the Rows setting, ≤4, counts list rows; one more is the featured aircraft). Positions are in the
home plane (metres E/N).

**Calm membership (`Selector`).** Distance ranking alone makes rows pop in and out, so: an aircraft that
has been shown keeps its place for at least 20 s (`MIN_DISPLAY_MS`) even if a closer one appears (the
newcomer waits for a free slot); and a shown aircraft that stops qualifying (just past the radius, or its
position fix aged out) stays for 20 s (`GRACE_MS`) while the feed still reports it. Ground and
general-aviation filters apply at once. Under a Hard radius an aircraft can therefore sit up to 20 s beyond
the limit.

**List order (`Featuring.order`).** Plain distance order flickers when two aircraft are about
equally far, so the order is sticky: aircraft that were already listed keep their relative
order; a newcomer is inserted ahead of the first listed aircraft that is farther; and every
30 s (`RESORT_MS`) the list is fully re-sorted by distance. Sorting therefore settles within
30 s, and nothing swaps places more often than that.

**Featured (`ft`).** The featured aircraft is the closest, with stickiness:
1. At start, or when the featured aircraft leaves the list: the closest aircraft.
2. Otherwise it is kept until one of these:
   - held at least 20 s (`MIN_HOLD_MS`) **and** the closest aircraft is under 0.7× (`SWITCH_RATIO`)
     of the featured one's distance (clearly closer, not marginally), then the closest;
   - held 90 s (`MAX_HOLD_MS`) and another aircraft is listed, then the closest other one. A lone
     aircraft stays featured for as long as it is there.
3. Featured is independent of list position, so it can be any row.

The featured state is held in the Engine and updates once per fetch tick. `ft` and the order
are part of the publish gate's signature, so a change in either publishes.

