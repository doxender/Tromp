# TrekTracker

**Version 1.11** — see [CHANGELOG.md](CHANGELOG.md) for release history.

Android activity tracker for hikes, runs, walks, and rides. Records position, elevation, distance, climb/descent, grade, and waypoints. Maps the track over OpenStreetMap; falls back to a 2D elevation-colored ribbon view when no tiles are cached. Presents per-activity detail and aggregate stats over user-selected date ranges.

Includes a benchmarking function so live altitude can be read from the barometer instead of GPS.

`DESIGN.md` is the authoritative spec: requirements, color palette, user flows, Room schema, algorithms, and the Decision Log. Read it before making non-trivial changes.

## Installing

Every push to this repo builds a signed release APK via GitHub Actions. Grab it one of two ways:

- **Tagged release** (recommended for everyday use): open the [Releases page](../../releases) and download `app-release.apk` from the latest `v*` tag. Each tag push attaches an APK automatically.
- **Latest build of any branch**: open the [Actions tab](../../actions), click the most recent successful run, and download the `trektracker-…` artifact (zip) from the bottom of the run page. Retained for 90 days.

Install on-device:

1. Download the APK on your Android phone (Chrome, Files, whatever).
2. Open the file. Android will prompt to allow installs from this source — grant it for the app you downloaded it with.
3. Tap Install. Subsequent versions install over the existing one without wiping data, because all APKs from this repo are signed with the same committed keystore.

### About the release keystore

`app/release.keystore` is **intentionally committed** to this repo along with its passwords (in `app/build.gradle.kts`). This is a personal, side-loaded app — not a Play Store build — so there's nothing to protect. The upside is that anyone can rebuild from source and produce a byte-identical signed APK that installs as an update to the official one. **Do not reuse this keystore for anything you'd ship on Google Play.**

## Status

### Working end-to-end

- **Benchmarking** — a short pre-session flow that establishes a base elevation (from DEM lookup and GPS averaging) and calibrates the barometer to that elevation, so live altitude during tracking comes from the barometer instead of GPS.
- **Record an activity** — foreground-service tracking via `FusedLocationProviderClient`. Distance via haversine, ascent/descent via the 3 m-hysteresis accumulator from DESIGN.md §6.1. Live duration + totals on the main screen and in the ongoing notification.
- **Stop + Summary** — final totals (duration, distance, ascent/descent, avg/max speed, point count) with a button to view the track on an OpenStreetMap polyline (osmdroid).
- **History** — every completed activity is persisted to Room (`activity` + `track_point` tables). Main-screen clock icon opens a list with an all-time totals header. Tap an entry to reopen its Summary + Map.

### Pure-logic core (unit-tested)

`GradeCalculator`, `AscentAccumulator`, `AutoPauseDetector`, `QnhCalibrator`, `Haversine`, `Units`, `GpxWriter`. These encode the subtle correctness requirements (window-trimming, hysteresis reversal, etc.) and are the first line of defense against data-integrity regressions. Run them with `./gradlew :app:testDebugUnitTest`.

### Not yet built

- Live tracking screen with large metrics tiles, pause/resume, waypoint drop.
- Ribbon fallback view + offline tile manager.
- Activity detail with elevation profile chart (MPAndroidChart).
- Full stats dashboard — date-range tiles, per-type breakdowns, YoY, personal records, distance-per-week bar chart. The Room aggregate queries (`aggregateBetween`, `aggregateByTypeBetween`) already exist to back these.
- Crash-recovery dialog, settings UI, GPX export wired to the UI.
- Auto start/stop — automatic session start/stop based on detected motion (distinct from auto-pause, which only gates an in-progress session).

## Architecture

Single-module Android app, package root `com.trektracker`:

```
service/     TrackingService (foreground, location|dataSync) + TrackingNotifier
tracking/    GradeCalculator, AscentAccumulator, AutoPauseDetector, QnhCalibrator,
             TrackSnapshot, BenchmarkSession, TrackingSession
location/    LocationSource — FusedLocationProviderClient as a cold Flow<Location>
sensors/     BarometerSource — TYPE_PRESSURE as a cold Flow<Float>
elevation/   DemClient — USGS 3DEP + Open-Elevation one-shot GETs
export/      GpxWriter — GPX 1.1 serializer
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

Gradle (Kotlin DSL), AGP 8.13.2, Kotlin 1.9.24, JVM 17, `compileSdk`/`targetSdk` 34, `minSdk` 26. The wrapper script isn't checked in — use Android Studio (File → Open → this folder → Gradle sync) or an installed Gradle 8.13.

Required: `local.properties` with `sdk.dir=<path to Android SDK>` for CLI builds.

```bash
# Build + install the debug APK to a connected device
./gradlew :app:installDebug

# Assemble only
./gradlew :app:assembleDebug

# Run the pure-logic unit tests (no device needed)
./gradlew :app:testDebugUnitTest
```

## Permissions

Declared in `AndroidManifest.xml`:

- `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` — GPS during tracking.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_DATA_SYNC` — required on Android 14+ for a continuous-location foreground service.
- `POST_NOTIFICATIONS` — Android 13+ ongoing tracking notification.
- `INTERNET`, `ACCESS_NETWORK_STATE` — DEM lookups and online OSM tiles.

The app prompts for fine location + notifications on first Start; background location is not yet requested (the foreground service keeps the process alive with the screen off).

## Related files

- `DESIGN.md` — authoritative spec.
- `CLAUDE.md` — guidance for Claude Code sessions working on this repo (build commands, conventions, scaffold status).
- `.claude/settings.json` — project-scoped permission allowlist for read-only bash/adb commands.

## License

Copyright (c) 2026 Daniel V. Oxender. Released under the MIT License — see `LICENSE` for the full text. Any derivative work (fork, port, or modification) must preserve this copyright notice and the full license text.
