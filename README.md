# Tromp

_(Renamed from **TrekTracker** on 2026-04-23. Historical `trektracker*`
storage identifiers remain so existing data is readable.)_

**Version 1.16.1** — see [CHANGELOG.md](CHANGELOG.md) for release history.

Android activity tracker for hikes and walks. Records position, elevation,
distance, climb/descent, grade, speed, and steps; stores the route locally; and
maps completed tracks over OpenStreetMap.

Includes a benchmarking function so live altitude can be read from the barometer instead of GPS.

`DESIGN.md` is the authoritative spec: requirements, color palette, user flows, Room schema, algorithms, and the Decision Log. Read it before making non-trivial changes.

> ⚠️ **Safety notice — read before use.** Tromp is a recreational activity tracker. It is **not** for emergency, safety-of-life, or sole-means-of-navigation use, and is not a substitute for a map, compass, or dedicated GPS unit. GPS position, elevation, and distance are approximations; continuous tracking drains the battery quickly. Carry backup power and a paper map in the backcountry, know your limits, and tell someone where you are going. You use Tromp at your own risk. See [Legal](#legal) below for the full disclaimer.

## Installing

Every push and pull request runs tests, full lint, and a debug build. Signed
release APKs are produced only from verified `v*` tags.

- **Tagged release** (recommended for everyday use): open the [Releases page](../../releases) and download `app-release.apk` from the latest `v*` tag. Each tag push attaches an APK automatically.
- **Latest branch build**: open the [Actions tab](../../actions) and download
  the `tromp-debug-…` artifact. Debug builds do not update a release install.

Install on-device:

1. Download the APK on your Android phone (Chrome, Files, whatever).
2. Open the file. Android will prompt to allow installs from this source — grant it for the app you downloaded it with.
3. Tap Install.

> **1.16.1 signing transition:** the old private key was committed publicly and
> is therefore retired. An install signed with the old key must be uninstalled
> once before installing 1.16.1. Export anything you need before uninstalling.

### About the release keystore

Private keys and passwords are never stored in Git. Local release builds read:

- `TROMP_KEYSTORE_PATH`
- `TROMP_KEYSTORE_PASSWORD`
- `TROMP_KEY_ALIAS`
- `TROMP_KEY_PASSWORD`

from private Gradle properties or environment variables. Tagged CI releases
use GitHub secrets with the same names (plus base64 key material).

## Status

### Working end-to-end

- **Benchmarking** — a short pre-session flow that establishes a base elevation (from DEM lookup and GPS averaging) and calibrates the barometer to that elevation, so live altitude during tracking comes from the barometer instead of GPS.
- **Quick Start** — secondary "Quick Start" button below the main START. Skips the full 60 s benchmark for users in a hurry: takes one GPS fix + one barometer reading + one DEM lookup within a 15 s window and starts tracking with whatever it can lock. If the window times out without a usable fix or elevation, offers a deferred-fix mode — tracking starts immediately and the start point is set the moment the first fix arrives, with retroactive ascent computed from buffered barometer samples. Quick benchmarks are session-only and aren't written to the cache.
- **Record an activity** — foreground-service tracking via `FusedLocationProviderClient`. Distance via haversine, ascent/descent via the 3 m-hysteresis accumulator from DESIGN.md §6.1. Live duration + totals on the main screen and in the ongoing notification.
- **Durable recording + recovery** — the active summary and new points flush
  to Room every five seconds. Process/service recreation resumes automatically;
  app launch offers Resume or Finish & save for an orphaned activity.
- **Stop + Summary** — final totals (duration, distance, ascent/descent, avg/max speed, point count) with a button to view the track on an OpenStreetMap polyline (osmdroid).
- **History** — every completed activity is persisted to Room (`activity` + `track_point` tables). Main-screen clock icon opens a list with an all-time totals header. Tap an entry to reopen its Summary + Map.
- **CSV export (diagnostic)** — after Stop, WorkManager generates pretrim and
  posttrim classifier CSVs. Summary can regenerate/share them on demand.

### Pure-logic core (unit-tested)

`GradeCalculator`, `AscentAccumulator`, `AutoPauseDetector`,
`AutoStopDetector`, `AutoStopTrimmer`, `SessionStatsCalculator`,
`TrackPostProcessor`, `QnhCalibrator`, `DemClient` fallback routing,
`Haversine`, `Units`, and `GpxWriter`.

### Not yet built

- Live tracking screen with large metrics tiles, manual pause/resume, waypoint drop. (Auto-pause is wired and freezes distance/ascent/grade automatically; manual pause still requires the action from the foreground notification.)
- Ribbon fallback view + offline tile manager.
- Activity detail with elevation profile chart (MPAndroidChart).
- Full stats dashboard — date-range tiles, per-type breakdowns, YoY, personal records, distance-per-week bar chart. The Room aggregate queries (`aggregateBetween`, `aggregateByTypeBetween`) already exist to back these.
- GPX export wired to the UI.
- Auto start — automatic session start based on detected motion (auto-stop is shipped; auto-pause is shipped; auto-start is the missing third).

## Architecture

Single-module Android app, package root `com.comtekglobal.tromp`:

```
service/     TrackingService (location foreground service), ActiveSessionStore
tracking/    GradeCalculator, AscentAccumulator, AutoPauseDetector, QnhCalibrator,
             TrackSnapshot, BenchmarkSession, TrackingSession
location/    LocationSource — FusedLocationProviderClient as a cold Flow<Location>
sensors/     BarometerSource — TYPE_PRESSURE as a cold Flow<Float>
elevation/   DemClient — USGS 3DEP + Open-Elevation one-shot GETs
export/      GPX/CSV serializers + DiagnosticExportWorker
data/db/     Room entities, DAOs, TrekDatabase
ui/main/     MainActivity (start/stop toggle, history + benchmark entry points)
ui/benchmark/         BenchmarkActivity
ui/calibration/       CalibrationActivity
ui/summary/           SummaryActivity
ui/map/               MapActivity (osmdroid)
ui/history/           HistoryActivity
util/        Haversine, Units, Time
```

Distances are stored in meters, times in milliseconds, speeds in m/s. Conversion happens at display time via `util/Units.kt`. The color palette is locked (DESIGN.md §2.1, `res/values/colors.xml`). osmdroid is the only map provider — no Google Maps SDK.

## Build & run

Gradle (Kotlin DSL), AGP 9.2.0, Kotlin 2.2.10, Gradle 9.4.1,
JVM 17, `compileSdk`/`targetSdk` 34, `minSdk` 26. The wrapper script is not
checked in; use Android Studio or an installed Gradle 9.4.1.

Required: `local.properties` with `sdk.dir=<path to Android SDK>` for CLI builds.

```bash
# Build + install the debug APK to a connected device
gradle :app:installDebug

# Assemble only
gradle :app:assembleDebug

# Run the pure-logic unit tests (no device needed)
gradle :app:testDebugUnitTest :app:lintDebug
```

## Permissions

Declared in `AndroidManifest.xml`:

- `ACCESS_FINE_LOCATION` — GPS during tracking.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` — continuous tracking.
- `POST_NOTIFICATIONS` — optional notification-drawer visibility on Android 13+.
- `ACTIVITY_RECOGNITION` — optional step/stride data.
- `INTERNET`, `ACCESS_NETWORK_STATE` — DEM lookups and online OSM tiles.

Fine location is required. Notification and activity-recognition denial does
not prevent recording.

## Related files

- `DESIGN.md` — authoritative spec.
- `MAINTAINERS.md` — technical stewardship and legal ownership.
- `SIGNING.md` — release certificate identity and secret configuration.
- `CLAUDE.md` — guidance for Claude Code sessions working on this repo (build commands, conventions, scaffold status).
- `.claude/settings.json` — project-scoped permission allowlist for read-only bash/adb commands.

## Legal

### Safety and fitness for purpose

Tromp is provided for **recreational use only**. It is not designed, tested, or certified for emergency response, search-and-rescue, aviation, maritime navigation, commercial guiding, industrial tracking, medical monitoring, or any application where a failure or inaccuracy could result in injury, loss of life, or property damage. Do **not** rely on Tromp as your sole means of navigation, route-finding, or location reporting. Always carry a map and compass, plan your route independently, and tell someone where you are going.

GPS fixes, elevation readings, distance totals, grade, and derived statistics are approximations. Their accuracy depends on sky view, atmospheric conditions, the device's sensor quality and calibration, remaining battery, and user behavior. Barometric altitude in particular is sensitive to weather changes and requires a correctly-acquired benchmark to be meaningful. The app makes no guarantee that any value it displays is correct.

### No warranty, no liability

Tromp is distributed "AS IS" and "AS AVAILABLE", without warranty of any kind, express or implied, including but not limited to warranties of merchantability, fitness for a particular purpose, title, accuracy, or non-infringement. To the maximum extent permitted by applicable law, neither the author nor any contributor shall be liable for any claim, damages, loss, or other liability — including direct, indirect, incidental, special, consequential, or punitive damages — arising from or in connection with Tromp, its use, or its inability to be used. See [`LICENSE`](LICENSE) for the full terms.

Some jurisdictions do not allow the exclusion of certain warranties or the limitation of liability for personal injury caused by a defective product; in those jurisdictions the above limitations apply only to the extent permitted by law.

### Third-party data and services

When online, Tromp transmits your current coordinates to third-party services to retrieve elevation and map tiles:

- **OpenStreetMap** — map tile imagery. Map data © OpenStreetMap contributors, licensed under the [Open Database License (ODbL)](https://www.openstreetmap.org/copyright). Tile requests are subject to the [OpenStreetMap Foundation Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/).
- **USGS 3D Elevation Program (3DEP)** — primary elevation lookups. Public-domain data provided by the U.S. Geological Survey.
- **Open-Elevation** — fallback elevation lookups when 3DEP is unavailable.

Tromp has no affiliation with, endorsement from, or control over any of these services. Their availability, accuracy, and terms may change without notice. See the in-app **Settings → Open source licenses** screen for the full third-party notices and license texts for every bundled library.

### Trademarks

"Tromp"™ is an unregistered trademark of Daniel V. Oxender / Comtek Global. All other product names, logos, and brands referenced in this project are the property of their respective owners. Use of these names does not imply endorsement.

### Privacy and data handling

Tromp stores activity data in app-private storage and disables Android backup
for that data. The developer operates no backend and receives no telemetry.
USGS receives coordinates for elevation lookup; Open-Elevation receives them
only if USGS fails; OpenStreetMap receives tile requests for viewed map areas.
Deleting an activity removes its Room rows and generated CSVs. Copies already
shared to another app are outside Tromp's control. Uninstalling removes the
remaining local app data.

A formal privacy policy will be published alongside any Google Play listing.

### License

Copyright (c) 2026 Daniel V. Oxender. Released under the MIT License — see [`LICENSE`](LICENSE) for the full text. Any derivative work (fork, port, or modification) must preserve this copyright notice and the full license text.
