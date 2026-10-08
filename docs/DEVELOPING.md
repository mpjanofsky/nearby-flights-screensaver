# Building, testing and changing the plugin

For people who want to build it from source or change it. If you only want to use it, the
[README](../README.md) is enough.

## What it is made of

- **Java plugin** (`src/`): fetches positions, looks up routes and aircraft details, chooses what
  to show and publishes it. Java 17 source, compiled with `--release 8` and converted to DEX for
  Android.
- **Page** (`assets/flights/`): plain HTML, CSS and JavaScript with no build step and no
  libraries. It draws whatever the plugin publishes and has no network or input.
- **Payload** ([payload.md](payload.md)): the one message between the two. Positions in metres
  from home, raw SI units. The page does unit display, projection and animation.
- **Fixtures** (`fixtures/payload/`): sample payloads, shared by the Java tests, the page tests
  and the harness.

The split matters: no logic is written twice, and the page never learns where data came from.

## Tools you need

- JDK 17 (`JAVA_HOME` if it isn't on the path) and the Android SDK build-tools (for the DEX step).
  `tools/check-sdk.py` tells you what is missing.
- Python 3 and a recent Node (for `node --test`).
- For the checks: `scripts/fetch-test-deps.sh` downloads JUnit, org.json and google-java-format
  into `tools/lib/` and checks each file against the checksum Maven Central publishes.

## Build

```bash
python3 tools/build.py --version 1.0.0
```

The package is written to `dist/nearby-flights-<version>.zip` with a `.sha256` next to it. The
version is stamped into the package's manifest at build time; the version in
`kiosk-satellite-plugin.json` is only the development default.

`scripts/release.sh 1.0.0` does the same from a clean tree, after running the checks, and tags the
commit. It pushes nothing.

## Check

```bash
scripts/check.sh
```

Prints failures only. It runs the page tests (`node --test`), the Java tests (JUnit against the
fixtures), the payload schema and fixture drift check, the formatter, a `--release 8` compile of
the shipped sources, and a package size gate (3.5 MB; the limit is 4 MB).

Run `scripts/format.sh` to format the Java sources.

## Try the screen without a device

Open `harness/index.html` in a browser. It loads a fixture and has a control to simulate a
publish, which recreates the page the way Kiosk Satellite does.

`node scripts/review-screens.mjs <fixture> <age_s> <t_ms>` renders a fixture to a 960 × 480 PNG
in `build/screens/` using headless Chrome. `scripts/gen-fixtures.py` regenerates the
fixtures.

## Try it on a device

`scripts/ks.py` talks to Kiosk Satellite's Remote Admin API. Put the address and password in
`LOCAL.md` (kept out of git) as `KS_ADMIN_URL:` and `KS_ADMIN_AUTH:` lines.

```bash
scripts/ks.py install dist/nearby-flights-1.0.0.zip
scripts/ks.py listPlugins '{}'
```

One trap: `configurePlugin` replaces the whole settings map. Read the current values, change what
you need, and send the full map, or you wipe the rest.

## Soak tests

- `scripts/soak-sim.sh [days] [heapMB] [outageMin] [periodMin]` replays synthetic traffic through
  the engine on a fake clock, with source and lookup outages, and prints heap and queue sizes. A
  month takes about ten seconds. Heap stays flat when there is no leak.

## Adding a data source

Every source sits behind a small interface and can be swapped by configuration, so a new one
never touches the page or the payload.

- Positions: implement `SourceAdapter` (`src/.../source/`). `ReadsbJsonSource` is the model; the
  local receiver and adsb.lol share it.
- Routes, registration and type: implement `Lookup` (`src/.../enrich/`).
- Record the source in [DATA-SOURCES.md](DATA-SOURCES.md) before it ships: what it gives us, its
  terms, and what we send it.

## Changing the payload

Update [payload.md](payload.md), the fixtures and the page's validator in the same commit. The
payload carries a `v` key, and the page shows a "version mismatch" screen if it doesn't match.

## Limits of the platform

The page has no network and no input. The plugin's data map is limited to 32 KiB of scalars (we
publish whole pages instead), at most 4 publishes a second, and lifecycle callbacks must return
within 3 seconds, so all fetching happens on a background thread. The package must stay under 4 MB.

## Other documents

- [DATA-SOURCES.md](DATA-SOURCES.md): every dataset, its terms and what we send it.
