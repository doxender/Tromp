# Changelog

All notable changes to TrekTracker are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

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
