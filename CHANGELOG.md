# Changelog

All notable changes to TrekTracker are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

## [1.7] — Unreleased

Work on branch `AutoStopSuggestion` — phase 1 of auto start/stop, plus an auto-calibrate-on-START flow.

### Added
- **Auto-stop suggestion** during an active session. The service watches each accepted fix for two end-of-activity signals: (a) a sustained speed spike (three consecutive fixes above 3× the trailing 60 s mean AND ≥ 10 mph), which indicates the user got into a vehicle; (b) a "returned home" condition (session older than 10 min, current speed low, current position within 100 m of the session's first fix). When either fires, a dialog offers to end the activity and trim the trailing points back to just before the trigger. Choosing Keep Going suppresses re-fire until the condition clears and re-arms.
- New pure-logic modules `tracking/AutoStopDetector` (state machine over fix stream) and `tracking/AutoStopTrimmer` (replays Haversine distance + `AscentAccumulator` over the kept fixes to recompute totals after a trim). Both have unit tests.
- **Auto-calibrate on START**: pressing START now consults the `known_location` table using last-known GPS. If the current position is within 100 ft of a prior benchmark, the cached elevation populates `BenchmarkSession` and `CalibrationActivity` runs to lock a fresh QNH against that elevation — then tracking starts. If no cached location is within 100 ft (or last-known location is unavailable), a "Benchmark required" dialog blocks tracking until a benchmark is acquired.

### Changed
- Removed the four-way Fresh / NoBenchmark / StaleNearby / StaleNeedsFull dialog tree from `MainActivity`. The new flow collapses it to a binary: auto-calibrated start, or benchmark-required block. `BenchmarkSession.check()` and the `Freshness` sealed class are gone (dead code after the UI simplification); `STALE_THRESHOLD_MS` and `PROXIMITY_THRESHOLD_M` with it.

## [1.6] — Unreleased

Work on branch `BenchmarkFix`.

### Added
- **Known-location elevation cache** (new `known_location` Room table, v3 schema). Every successful benchmark records its lat/lon/elevation/source. On subsequent benchmarks, the first GPS fix is compared against the cache: if the user is within 50 m of a previously benchmarked spot, we short-circuit the 60 s averaging + DEM lookup and offer the cached elevation, with a choice to accept or run the full flow anyway.

### Changed
- `DemClient.queryUsgs3dep` and `queryOpenElevation` now retry once on failure (~750 ms backoff) and log the reason (`Log.w`) when both attempts fail. The USGS EPQS endpoint is known to return transient 5xx / timeouts; a single retry typically turns a miss into a hit.

## [1.5] — Unreleased

Work on branch `StrideLength`.

### Added
- **Average stride length** on the summary screen (computed as total distance / step count, shown in feet and meters) when step data is available.
- `StepCounterSource` wrapping `Sensor.TYPE_STEP_COUNTER` as a cold `Flow<Float>`. `TrackingService` records the baseline step count at session start and diffs against the latest sample on each fix; session steps persist on `ActivityEntity` and are restored by `HistoryActivity`.
- `ACTIVITY_RECOGNITION` runtime permission, prompted alongside location/notifications on first START. Devices without a step counter, or users who deny the permission, get a summary without the stride line.

### Changed
- Room schema bumped from v1 to v2 with a `Migration(1, 2)` that adds the `stepCount` column to `activity`. `exportSchema = true` and kapt `room.schemaLocation` now writes generated schema JSON under `app/schemas/`.

## [1.4] — Unreleased

Work on branch `SummarySpeedUnits`.

### Changed
- Summary screen now shows avg and max speed in three units side-by-side: km/h, m/s, and mph.

## [1.3] — Unreleased

Work on branch `StalenessRules`.

### Changed
- **Benchmark staleness rules** (DESIGN.md Decision Log row 5). A benchmark is now considered stale if any of: it does not exist, it was taken more than **100 ft** from the current location, or it is more than **1 h** old. On START the app checks against the last-known GPS fix and routes to one of three dialogs: no-benchmark (offer full), stale-but-nearby (offer fast barometer-only refresh or full), or stale-far (offer full). A barometer-only refresh keeps the stored benchmark elevation and just re-runs calibration.
- **Benchmark persistence**: the benchmark record (lat/lon/elev/source/acquiredAt) is now saved to `SharedPreferences` on Accept and restored on app launch, so the proximity check still works after the OS kills the process. QNH is not persisted — weather drift makes it stale within hours.

## [1.2] — Unreleased

Work on branch `AddVersion`.

### Added
- Version label shown under the title on the main screen, pulled at runtime from `PackageManager` so it always matches the built APK.

## [1.1] — Unreleased

Work in progress on branch `AltitudeAccuracy` — improvements to altitude precision.

### Added
- **Stale-benchmark warning**: `BenchmarkSession` now exposes `isStale()` with a 4-hour freshness window (DESIGN.md Decision Log row 5). If you press START with a calibrated QNH older than that, a dialog warns you that altitude readings may be off and offers to re-benchmark. Choosing No proceeds with the stale QNH; Yes opens the benchmark flow.

### Changed
- **Barometric altitude in the live pipeline**: when a session starts with a calibrated QNH and the device has a barometer, `TrackingService` now subscribes to pressure readings and derives altitude via `SensorManager.getAltitude(qnh, pressure)` for every fix. Raw GPS altitude is the fallback when QNH is absent or no pressure reading has arrived yet.
- **GPS accuracy filter**: fixes with `horizontalAccuracy > 15 m` (or unreported accuracy) are dropped before feeding into distance/ascent/grade, per DESIGN.md §4.3 step 2. Prevents noisy fixes from inflating totals.
- `TrackingSession.Point` extended with `gpsElevM`, `pressureHpa`, and `horizAccM` so the Room layer preserves raw sensor data alongside the chosen altitude.

## [1.0] — 2026-04-20

First public release.

### Added
- Foreground-service activity tracking via `FusedLocationProviderClient`. Records position, elevation, distance (haversine), ascent/descent (3 m-hysteresis accumulator), and speed. Live updates on the main screen and in an ongoing Android notification.
- Main-screen START/STOP toggle; STOP finalizes the session and opens a summary.
- Summary screen with totals (duration, distance, ascent/descent, avg/max speed, point count) and a **View Map** button.
- Map screen rendering the track as a polyline on OpenStreetMap tiles via osmdroid.
- Benchmark acquisition flow (60 s GPS averaging + USGS 3DEP / Open-Elevation DEM lookup) that establishes a base elevation.
- Barometer calibration that auto-locks once the last 100 pressure samples (at 25 ms) have σ ≤ 0.1 hPa, then stores QNH for the session so live altitude comes from the barometer instead of GPS.
- Room persistence for completed activities and track points. History screen lists past activities with an all-time totals header; tapping a row reopens its summary + map.
- Pure-logic unit tests for grade, ascent, auto-pause, QNH, haversine, and units conversions.
- MIT License with a preservation clause for derivative works.

### Project
- `DESIGN.md` build spec (requirements, palette, flows, Room schema, algorithms, Decision Log).
- `CLAUDE.md` guidance for Claude Code sessions working on this repo.
- `.claude/settings.json` project-scoped permission allowlist for read-only bash/adb commands.
