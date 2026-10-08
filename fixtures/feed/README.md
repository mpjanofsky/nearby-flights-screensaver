# Feed fixtures

- `adsblol-point.json`: real adsb.lol `/v2/point` response, trimmed to 9 aircraft, public airport area.
- `readsb-local-real.json`: real local readsb `aircraft.json` (30 entries, 18 with a position). Every
  position was shifted by a fixed offset and `rssi` removed; distances are preserved. Tests use home
  (-10.51, 58.59), the shifted centroid. Note: no `r` (registration) or `t` (type) in this feed.
- `readsb-local-synthetic.json`: the adsb.lol aircraft re-shaped as a local feed (`aircraft` array, `now`
  in seconds, one entry without a position). Used to check both envelopes give equal results.
