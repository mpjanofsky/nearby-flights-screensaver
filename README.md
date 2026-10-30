# ✈️ Nearby Flights

A screensaver for [Kiosk Satellite](https://kiosksatellite.com) that shows the aircraft flying over
the coordinates you enter, live.

![The screensaver running: the closest aircraft in the top band, three more in a list, and a radar-style scope with a sweeping wedge](docs/images/demo.gif)

*The sample screens in this README use made-up traffic. The scope is a sketch of where things are,
not a map.*

> 🧪 **New: a beta native screensaver.** Version 0.10.0 adds a second screensaver, **Nearby Flights
> (beta, native)**, that is drawn directly. This allows for smoother animation, like labels that glide
> into place and aircraft that fade in.
>
> - **Nothing changes unless you switch:** the original version stays the default for now.
> - **To try it:** change your screensaver setting to **Nearby Flights (beta, native)** in the Kiosk
>   Satellite screensaver settings.
> - Kiosk Satellite 2026.10.35 or newer is required.
> - The natively drawn screensaver will replace the original version in a future release.
> - **New features land in the beta first.** City names and weather (new in 0.10.1) show only in the
>   native screensaver. Both are off by default; see [Settings](#-settings).
>
> Notice anything strange? Open an issue and include your screen size and device.

## Contents

- [What you get](#-what-you-get)
- [Supported devices](#-supported-devices)
- [Install](#-install)
- [What's on the screen](#-whats-on-the-screen)
- [Settings](#-settings)
- [How accurate is it?](#-how-accurate-is-it)
- [Privacy](#-privacy)
- [Troubleshooting](#-troubleshooting)
- [Data and credits](#-data-and-credits)
- [More](#-more)

## 🛫 What you get

- 🎯 **The closest aircraft up top:** callsign, route, airline, type, registration, distance,
  direction and altitude, with a little arrow if it's climbing or descending.
- 📋 **A list of the next ones**, closest first, with altitude colors from green (low) to purple (high).
- 📡 **A radar-style scope** with you at the center, nearby airports, and a short trail behind every aircraft.
- 🏙️ **City names instead of airport codes** (optional, beta native screensaver): for example, Chicago
  to Tampa instead of ORD to TPA.
- 🌤️ **Your weather under the clock** (optional, beta native screensaver): temperature, conditions
  and wind from a Home Assistant weather entity.
- 🌙 **A quiet-sky screen** for when nothing is around, and a clock in the corner.
- 🔒 **Private by default:** your exact location stays on your device. See Privacy below.

Aircraft positions come from your own ADS-B receiver, the free [adsb.lol](https://adsb.lol) service,
or both. No account or key needed.

## 📱 Supported devices

Requires Kiosk Satellite 2026.10.23 or newer (2026.10.35 for the beta native screensaver).

Layouts rescale to landscape and portrait screens of any shape, from 800 × 480 up to 2560 × 1600.
Tried it on something else? Please report how it looks.

<table>
<tr>
<td><img src="docs/images/screen-1280x800.png" alt="1280 by 800 landscape: the list rows fill the height under the top band" width="360"></td>
<td><img src="docs/images/screen-portrait-4x3.png" alt="768 by 1024 portrait: the top band, a larger scope, then the list" width="200"></td>
<td><img src="docs/images/screen-portrait-tall.png" alt="800 by 1280 portrait: centred clock, the featured aircraft on three lines, the scope, then the list" width="200"></td>
</tr>
<tr>
<td align="center">1280 × 800 landscape</td>
<td align="center">768 × 1024 portrait</td>
<td align="center">800 × 1280 portrait</td>
</tr>
</table>

## 🚀 Install

### Community Plugins (Recommended)

Nearby Flights is listed in Kiosk Satellite's Community Plugins directory.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=mpjanofsky/nearby-flights-screensaver)

Or search for `Nearby Flights` in **Settings > Plugin Manager > Browse plugins**. Then enable the plugin from its row,
fill in your latitude and longitude, and pick **Nearby Flights** under **Screensaver > Screensaver mode**.

### Manual

1. In Kiosk Satellite, open **Settings > Plugin Manager** (or **Plugin Manager** in Remote Admin)
   and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste this repository's GitHub URL, and choose **Preview**.
3. Read the manifest and this README, then choose **Trust and install**.
4. Enable the plugin from its row in the list, then open the row to reach its settings.
   Fill in your latitude and longitude at least.
5. Under **Screensaver > Screensaver mode**, pick **Nearby Flights**.

**Check that it works.** Open Kiosk Satellite's remote web UI and read the plugin's status line (on the device itself it
only says paused, and the screensaver has to be dismissed to see settings). `Primary source, N aircraft`
means positions are arriving. The plugin fetches only while its own screensaver is showing; if the device is on
another screensaver mode the status reads `Paused. Screensaver: <name>`, and it needs the *Choose which
screensaver shows* permission only to read which mode that is. Start the screensaver from Kiosk Satellite and within about 30 seconds
you should see aircraft, or "Quiet sky" if nothing is in range. Turn on **Demo: quiet sky** if you
want to see the empty screen on purpose.

One Kiosk Satellite setting can get in the way: **its screensaver schedule wins.** A schedule that picks a
screensaver by time of day overrides this plugin during those hours. Turn the schedule off or leave gaps in it.

## 👀 What's on the screen

![Open-arrow route in the top band, and a position-only target at the bottom of the list](docs/images/screen-route-tiers.png)

- **Top band:** the featured aircraft, usually the closest one. It stays for at least 20 seconds,
  and changes after that only if something else is clearly closer, or when the **Featured aircraft hold**
  runs out (90 seconds by default).
- **List:** up to four more aircraft, closest first.
- **Scope:** a radar-style view centered on the location you entered, with nearby airports marked.
  The closest aircraft gets a yellow circle.
- **Bottom left:** the data feed source (local feed, internet feed, or both).
- **Weather (optional):** with **Show weather** on in the beta native screensaver, one line under the
  clock shows the parts you chose, for example `83° Partly cloudy · NE 10 mph`.

### 🧭 About the routes

ADS-B doesn't carry a flight's route, so the plugin looks the callsign up in a community database
of scheduled flights. It's a good guess, not a promise, so the screen shows how sure it is:

| You see | Details |
|---|---|
| `ATL → MCO` | Origin and destination, both believed. |
| `CVG →` in grey italics | Only the origin is trusted. |
| Nothing after the callsign | No route. Private, military and small-airline flights usually don't get one. |
| `~234BA0` and "Position only" | A target with no proper aircraft address, so no callsign or details. Altitude and distance are still real. |

### 💤 Other screens

| Screen | Details |
|---|---|
| ![Quiet sky](docs/images/screen-empty.png) | Nothing is inside your radius. With Soft radius on, it first looks farther out. |
| ![Data stale](docs/images/screen-stale.png) | The plugin can't reach its data source, so what's on screen is getting old ("No new data · 3 min" in yellow). That means the receiver or adsb.lol is down or the network dropped. It keeps retrying and recovers by itself. |

## 🔧 Settings

Open the plugin's row in Plugin Manager. Changes apply straight away.

**📍 Location**

| Setting | Default | What it does |
|---|---|---|
| Home latitude / longitude | empty | Decimal degrees, like `27.1234` and `-82.1234`. Required. |
| Radius (nm) | 15 | How far out to look, 5 to 50. |
| Radius limit | Hard | **Hard** stays inside the radius. **Soft** reaches farther while the list has empty rows. |
| Soft reach (nm) | 60 | How far Soft may reach. Ignored on Hard. |

**🎨 Display**

| Setting | Default | What it does |
|---|---|---|
| Units | Aviation | **Aviation** (nautical miles, feet), **Metric** (km, m) or **Imperial** (miles, feet). |
| Clock format | Device default | Follow the device locale, or choose **12 hour** or **24 hour**. Uses the device time zone. |
| Text size (%) | 100 | Scales the type, 80 to 125. Larger text elides long lines sooner, and four rows may not fit on a short screen. |
| Featured aircraft hold (s) | 90 | Longest the top band keeps one aircraft while others are in range, 10 to 300. A clearly closer aircraft can still take it sooner. |
| Rows | 4 | List rows under the top band, 1 to 4. |
| Radar position (portrait only) | Top | **Top**: under the featured aircraft. **Bottom**: below the list. Landscape ignores it. |
| Show city names | Off | Show the route as city names (for example Chicago to Tampa) instead of airport codes, where the line has room. The text shrinks a little to fit, down to 85 %; otherwise the codes show. Needs the beta (native) screensaver. |
| Hide general aviation | off | Hides light aircraft, helicopters and bare-registration callsigns. |
| Demo: quiet sky | off | Always shows the empty-sky screen. |

**🌤️ Weather**

| Setting | Default | What it does |
|---|---|---|
| Show weather | Off | Show the current weather under the clock, from a Home Assistant weather entity. Needs the beta (native) screensaver. |
| Weather entity | (none) | The Home Assistant weather entity to show, such as `weather.home`. Required for the weather line. Temperature and wind show in the units that entity uses. |
| Temperature | On | Show the temperature in the weather line. |
| Conditions | Icon and word | How the conditions (Sunny, Rain) show: **Icon and word**, **Word only**, **Icon only** or **Off**. |
| Wind | Off | Add the wind to the line: **Speed**, or **Speed and direction** (for example `NW 8 mph`). It is the first thing dropped when the line is too long. |

The rows after Show weather appear only while it is on. If the line is too long for the screen it gives way
in this order: wind direction, wind, the condition word. The temperature stays.

**📡 Sources**

| Setting | Default | What it does |
|---|---|---|
| Primary source | Local feed | **Local feed**, **adsb.lol**, or **Local + adsb.lol** (uses both and merges them, so you see farther than your antenna reaches). |
| Local feed URL | empty | Your receiver's `aircraft.json`, like `http://receiver.local:8080/data/aircraft.json`. |
| Fallback source | adsb.lol | What to use when the primary source is unavailable. Choose None to turn it off. |
| Refresh (s) | 10 | How often to fetch positions, 5 to 60 seconds. |

### No receiver?

Leave the local feed URL empty and the plugin uses adsb.lol on its own.

### Have a receiver?

Open your tar1090 page and add `/data/aircraft.json` to the same host and port. A page of JSON
with a long `"aircraft"` list is the right address. The plugin only reads it.

### Want to run your own receiver?

A cheap USB radio and a Raspberry Pi are enough. The
[ADS-B Ultrafeeder](https://github.com/sdr-enthusiasts/docker-adsb-ultrafeeder) project bundles
everything (readsb and tar1090 included) in one Docker container, with a quick-start guide.

Home receivers often don't hear callsigns or aircraft details for every plane, so the plugin fills
those in from lookups a few seconds later. A new row can change as details arrive.

## 🎯 How accurate is it?

- **Positions and altitude** come straight from ADS-B: the latest fix your receiver or adsb.lol
  has for each aircraft, usually a few seconds old. Anything older than a minute is dropped.
  Between updates the scope slides each aircraft along its last known track.
- **Airline and type** come from the callsign prefix (a table built into the plugin) and lookups by
  the aircraft's hex code.
- **Routes** are matched by callsign against a community database of scheduled flights. Airlines
  reuse a callsign on different legs, so occasionally you'll see the other leg of the same flight
  number, and out-and-back routes such as LGA-BOS-LGA could show the leg not being flown. The plugin
  uses the aircraft's position, course and whether it is climbing or descending near an airport to
  pick the leg, hides a route that doesn't fit, and would rather show less than guess.

## 🔒 Privacy

- **Your coordinates stay on your device.** The only place they're saved is Kiosk Satellite's own
  plugin settings. The plugin writes nothing else to storage, and its lookup caches live in memory
  and disappear on restart.
- **Receiver only?** If you use your own receiver and set Fallback to None, your location never
  leaves your network.
- **adsb.lol** gets your location rounded to two decimals (about 1 km), with the radius plus
  1 nm, and sees your IP address. That applies whenever it's used: as primary, as the default
  fallback, or for the farther steps of Soft radius.
- **Weather** comes from your own Home Assistant through Kiosk Satellite. The plugin makes no network
  call for it and never sees your Home Assistant credentials.
- **Aircraft lookups** (airline, type, route) send only an aircraft's hex code or callsign, never a
  location. Over weeks they add up to a list of the aircraft that fly near you.
- No analytics, and nothing is contacted that isn't in [docs/DATA-SOURCES.md](docs/DATA-SOURCES.md).

## 🧰 Troubleshooting

| What you see | What to check |
|---|---|
| The screensaver doesn't appear | Plugin switched on? Screensaver mode set to Nearby Flights? A Kiosk Satellite schedule can override it. |
| "Nearby Flights" is missing from Screensaver mode, but the status line shows aircraft | Restart Kiosk Satellite: on one device the entry only appeared after a restart. If it is still missing, look for "Screensaver not registered (...)" on the status line (Kiosk Satellite refused the registration) and open an issue with that text and your Kiosk Satellite version. |
| A message about settings | Latitude and longitude must be decimal numbers. The status line says what's wrong. |
| "Quiet sky" all the time | Radius too small, or Hide general aviation is on. Try a bigger radius or Soft. Check Demo: quiet sky is off. |
| "No new data" | The source isn't answering. Check the local feed URL in a browser. With the fallback on, it recovers by itself. |
| Fewer aircraft than a tracking website | A home antenna hears only part of the sky. Try Local + adsb.lol. |
| Airline or type is blank at first | They fill in a few seconds after an aircraft appears. |
| A route looks wrong | Routes are a best guess. See About the routes above. |
| Kiosk Satellite's clock covers the corner | Switch off its clock and weather widgets for the screensaver. |
| The weather line doesn't show | Show weather is on, a Weather entity is picked, and the beta (native) screensaver is selected? The entity needs a temperature, and a temperature unit the plugin knows. Wind needs a known wind unit too. If the entity is unavailable the line is left out. |
| A city name looks odd | City names are matched from airport data, so a few airports show a nearby town or an old name. Open an issue with the airport code. |
| Very dim at night | Kiosk Satellite's adaptive brightness may be turning the panel down. |

The status line also shows what the plugin is up to, for example
`Primary source, 5 aircraft | rows with route 88%, airline 56%, callsign 99%; lookups 56 (err: hex 0, route 0), queue 0`.
Low route percentages are normal. Worry about `err:` numbers that keep climbing or a queue that
never empties, which means a lookup service isn't answering. Positions aren't affected. After three
failed fetches it reads `Stale:` and the reason.

## 🙏 Data and credits

What each service gives us, its terms and exactly what we send it are in
[docs/DATA-SOURCES.md](docs/DATA-SOURCES.md).

- Positions: your own receiver and/or [adsb.lol](https://adsb.lol). Contains information from
  adsb.lol, made available under the
  [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/1-0/).
- Routes: [adsb.im](https://adsb.im)'s route API, which serves community-maintained
  [standing data](https://github.com/vradarserver/standing-data) (CC0).
- Aircraft registration and type: [adsbdb.com](https://www.adsbdb.com), which credits PlaneBase.
  Queried live, not stored or republished.
- Airports and city names: [OurAirports](https://ourairports.com) (public domain) and the Virtual Radar
  Server [standing data](https://github.com/vradarserver/standing-data) (CC0), bundled with the plugin.
- Aircraft icons: ADS-B Radar for macOS, https://adsb-radar.com.

These are free community services, so the plugin keeps its requests small (one lookup per second
at most, results cached for hours) and backs off when a service is slow. If you fork it, please
keep to those limits.

## 📚 More

- Ideas or problems? Open an issue.
- For developers: [building and changing it](docs/DEVELOPING.md) and the
  [payload format](docs/payload.md).

🤖 Built with a lot of help from Claude, who worked with me on
most of the design, code, tests and docs.

Code is Apache-2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)).
