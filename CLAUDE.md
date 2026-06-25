# CLAUDE.md

> Copyright (c) 2026 Daniel V. Oxender. Licensed under the MIT License — see `LICENSE`. This notice must be preserved in all derivative works.

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Start here

`DESIGN.md` at the repo root is the authoritative design document for this app: requirements, assumed defaults (including the locked color palette), user flows, architecture, Room schema, the key algorithms (grade, ascent hysteresis, auto-pause, QNH), dependencies, and the Decision Log of resolved open questions. Read it before making non-trivial changes — architecture decisions that look arbitrary in the code are usually justified there.

## Build & run

Single-module Gradle (Kotlin DSL) Android app. Requires `local.properties` with `sdk.dir=...` for CLI builds.

- Install debug APK to a connected device: `./gradlew :app:installDebug`
- Assemble: `./gradlew :app:assembleDebug`
- Unit tests (pure-logic classes — see §10 of DESIGN.md): `./gradlew :app:testDebugUnitTest`
- Clean: `./gradlew clean`
- Primary workflow is Android Studio (File → Open → this folder → Gradle sync). The Gradle wrapper script (`gradlew`) is not checked in.

Toolchain: AGP 9.2.0, Kotlin 2.2.10, Gradle 9.4.1, JVM target 17, `compileSdk`/`targetSdk` 34, `minSdk` 26. ViewBinding and KSP are enabled.

## Architecture

Package root: `com.comtekglobal.tromp`. Layout follows DESIGN.md §4.1:

```
service/     TrackingService (location FGS), TrackingNotifier, ActiveSessionStore
tracking/    live calculators, SessionStatsCalculator, TrackSnapshot
location/    LocationSource — FusedLocationProviderClient as a cold Flow<Location>
sensors/     BarometerSource — TYPE_PRESSURE as a cold Flow<Float>
             StepCounterSource — TYPE_STEP_COUNTER as a cold Flow<Float>
elevation/   DemClient — USGS 3DEP + Open-Elevation lookups (blocking; call from IO)
export/      GpxWriter, CsvWriter, DiagnosticExportWorker
data/db/     Room entities, DAOs, TrekDatabase
ui/main/     MainActivity — idle landing screen
util/        Haversine, Units, Time
```

### Implementation state (2026-06-24, v1.16.1)

For per-version detail see `CHANGELOG.md`; for current gotchas + state at-a-glance see `CONTEXT.md`. Brief tour:

**Pure-logic core, all unit-tested**: `GradeCalculator`, `AscentAccumulator`, `AutoPauseDetector`, `AutoStopDetector`, `AutoStopTrimmer`, `QnhCalibrator`, `TrackPostProcessor`, `Haversine`, `Units`, `GpxWriter`. If you change their behavior, update the tests — they encode the subtle invariants and are the first line of defense.

**Wired into the live pipeline**: `TrackingService` collects location, barometer, and optional step data; emits `TrackSnapshot`; creates an in-progress Room row at Start; and flushes points/summary transactionally every five seconds. Sticky restart and MainActivity recovery reload the row and persisted points. Stop finalizes on IO and WorkManager generates diagnostic CSVs afterward.

**UI shipped**: `MainActivity` (idle + live status + auto-stop dialog + settings dialog + Quick Start button + acquiring-fix banner), `BenchmarkActivity`, `CalibrationActivity`, `SummaryActivity`, `MapActivity` (osmdroid polyline), `HistoryActivity` (list with rename/delete), `BenchmarksActivity` (cache management), `LicensesActivity`. First-run safety disclaimer (`SafetyDisclaimer`) blocks until accepted.

**Quick Start**: the one-fix/barometer/DEM attempt shares a 15-second budget. Deferred mode uses GPS altitude immediately when present, otherwise runs a bounded DEM attempt, and keeps a capped barometer buffer for ascent replay.

**Not yet built** (canonical list lives in `README.md` "Not yet built"):
- Dedicated live tracking screen (large metrics tiles, manual pause/resume button, waypoint drop) — main screen doubles as the live view for now.
- Ribbon fallback view + offline tile manager.
- Activity detail w/ elevation profile chart (MPAndroidChart dep declared, unused).
- Stats dashboard (DAOs ready: `aggregateBetween`, `aggregateByTypeBetween`).
- GPX export wired to UI and auto-start (third of the auto-trio after auto-pause + auto-stop).

### Runtime shape (target end state — §4.2 of DESIGN.md)

`TrackingService` owns the active session. It subscribes to `LocationSource.updates(intervalMs)` and `BarometerSource.readings()`, runs each location fix through an accuracy filter, then through `GradeCalculator` / `AscentAccumulator` / `AutoPauseDetector`, emits updated `TrackSnapshot` values to `TrackingService.snapshots` (a `StateFlow<TrackSnapshot?>`), and flushes `TrackPointEntity` rows to Room every ≤ 5 s. UI layer observes the flow for live screens and hits Room directly for history / detail / stats.

Crash recovery: service writes an active-session-id to SharedPreferences on start, clears on clean stop. On launch, a non-null value plus an `ActivityEntity` with `endTime IS NULL` triggers the recovery dialog.

## Conventions worth preserving

- **Storage is SI**: all distances in meters, times in milliseconds, speeds in m/s. Unit conversion happens at display time via `util/Units.kt`. Don't store imperial values or change the Room schema to be unit-aware.
- **Palette is locked** (DESIGN.md §2.1). All colors live in `res/values/colors.xml`; don't hard-code hex in layouts or drawables. If you need a new color, add it there and reference it.
- **The grade/ascent algorithms have subtle correctness requirements** — the rolling-window trimming in `GradeCalculator` and the hysteresis reversal logic in `AscentAccumulator` both have unit tests that encode the invariants (window must be full before reporting; noise below threshold must never commit to totals; direction reversal must reset anchor but not bank the pending delta). Changing these algorithms without updating the tests is how real data gets miscounted.
- **No background location without a foreground service**. Android 8+ throttles background location to a handful of fixes per hour; a foreground service with an ongoing notification is the only sanctioned path to continuous GPS with the screen off. The notification channel is `IMPORTANCE_LOW` (no sound/vibration/heads-up) — keep it that way.
- **Room `exportSchema = true`** at schema v6. Preserve exported schemas and add tested migrations for every future version.
- **DEM lookups are blocking HTTP** (hand-rolled `HttpURLConnection` in `DemClient`). Keep them that way — don't pull in OkHttp/Retrofit for two one-shot GETs. Always call from `Dispatchers.IO`.
- **`kotlinx.coroutines.flow.callbackFlow`** is the chosen pattern for bridging Android callback APIs (`FusedLocationProviderClient`, `SensorManager`) into Flow. Stay consistent: new callback sources should follow the same shape as `LocationSource` / `BarometerSource`.
- **osmdroid, not Google Maps**. This was an explicit decision (DESIGN.md Decision Log): osmdroid is the only map provider. The one Google Maps touchpoint is a `geo:` Intent on the activity detail screen ("View in external maps app"). Don't add the Google Maps SDK.

## Design decisions already resolved

See DESIGN.md §11 Decision Log. Preserved `trektracker*` storage identifiers remain part of the upgrade path. The original committed release key was retired in 1.16.1; signing material must remain outside Git and tagged releases must use protected CI secrets. Other locked decisions include the earth-tone palette, one 3 m ascent hysteresis, generic activity types, and default name `"{type} · YYYY-MM-DD HH:MM"`.
