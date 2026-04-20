# Changelog

All notable changes to TrekTracker are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

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
