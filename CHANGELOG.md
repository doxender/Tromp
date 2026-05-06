# Changelog

All notable changes to **Tromp** (previously **TrekTracker**) are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

## [1.15.1] — Unreleased

Diagnostic plumbing for the hike / clamber / dawdle classifier. Dan reported the v1.15.0 hike "looked pretty good" — encouraging signal that the rule shape is close, but we're still in the threshold-tuning phase. This release adds the classifier itself plus the data-export flow needed to validate it against real recordings, **without** touching activity totals, the map polyline, or persisting state to Room. Once the rule's calibrated against a few real hikes, the full rollout in `CONTEXT.md` "Pending discussion" items 1–9 lands as a separate change.

### Added
- **`TrackPostProcessor`** — pure-logic classifier in `tracking/`. Per-fix state ∈ {ACTIVE, CLAMBERING, DAWDLING} from a 15 s rolling window over `loc.speed` (Doppler, never haversine — CONTEXT.md item 6) plus per-pair cadence (`Δsteps/Δt`) and vertical rate (`Δalt/Δt`). Thresholds: `0.6 m/s` active speed floor, `0.6 CV` speed-stability ceiling, `30 spm` cadence floor, `0.1 m/s` vertical-rate floor (CONTEXT.md item 4 — tunable, expose as `const val` in the file). Single-fix windows default to DAWDLING (item 7).
- **`TrackPostProcessorTest`** — unit-test coverage for the base cases (steady walk → ACTIVE, standing still → DAWDLING, slow climb → CLAMBERING), boundary cases (exactly at the speed floor), the single-fix default, and the timestamp-gap behaviour (a 60 s gap between two clusters means a fix in cluster A doesn't pull cluster B into its window). All run under `./gradlew :app:testDebugUnitTest`.
- **Two-file diagnostic CSV export.** Every activity now generates two CSVs at stop and stores them with the hike under `Android/data/<applicationId>/files/exports/`:
  - `tromp-<id>-pretrim.csv` — every fix, with `state` + `state_reason` + four `window_*` diagnostic columns (CONTEXT.md item 10) so Dan can sort/filter in Excel.
  - `tromp-<id>-posttrim.csv` — same shape, but rows classified DAWDLING are dropped. Pre-existing `dist_from_prev_m` / `dt_from_prev_s` continue to reflect the original GPS sequence (not "since the last kept row") so Excel doesn't see fake gaps where dawdling was excised.
- **Auto-export on stop.** `TrackingService.persistActivity` runs the classifier and writes both CSVs synchronously inside the existing `runBlocking { db.withTransaction { ... } }` block, so the files land before `stopSelf()` can race the writes (same race-avoidance reasoning as the v1.14.1 persist fix). Failures are logged but never block the activity persist.
- **`CsvExportFiles`** — single source of truth for the per-activity file paths. Both `TrackingService` (auto-export) and `SummaryActivity` (manual share) call `CsvExportFiles.forActivity(context, id)` so file naming can't drift.

### Changed
- **Export CSV button shares both files.** `SummaryActivity.exportAndShareCsv` now fires `ACTION_SEND_MULTIPLE` with both CSV URIs attached so a single tap puts pre-trim + post-trim into the user's email / Drive together. Old activities recorded pre-1.15.1 don't have the files on disk yet — those are regenerated on demand by re-running the classifier against the persisted track points (no schema migration needed).
- **`CsvWriter.write`** picked up an overload taking a parallel `List<Classification>` and an optional `includeStates: Set<State>` filter. The legacy single-arg overload (no classifications) still works — it just classifies fresh internally. The legacy single-file path (`tromp-<id>.csv`) is no longer written; old single-file exports stay where they are but new exports use the new names. Header banner gains `# variant,pretrim|posttrim` and `# classifier,TrackPostProcessor v1.15.1` rows so downstream scripts can tell the files apart.
- `versionCode` 18 → 19, `versionName` `1.15.0` → `1.15.1`.

## [1.15.0] — Unreleased

Quick Start: a one-tap path that skips the full 60 s benchmark when the user wants to start a hike fast. Long-standing UI gap — every prior session had to walk through the full Acquire-Benchmark flow even when a rough QNH lock was good enough. Spec lived in `CONTEXT.md` "Quick Start feature spec" since 2026-05-05; this release implements it.

### Added
- **"Quick Start" button on the main screen** — secondary text-style button below the primary START. Taps open a 15 s "Acquiring GPS…" modal with a Cancel button.
- **Single-shot acquisition cascade.** Within the 15 s window, takes one GPS fix and one barometer reading concurrently, then runs a DEM lookup at the fix's lat/lon. Elevation precedence: USGS 3DEP → Open-Elevation → `loc.altitude` → null. If a non-null elevation lands, calibrates QNH from the baro reading and starts tracking immediately.
- **Deferred-fix mode.** If the 15 s window times out without a fix, OR a fix arrives but no elevation reference can be resolved, the user is offered "Use next fix as start point." Tracking starts immediately with `isAcquiringFix = true`; the live screen shows a banner *"Acquiring GPS — start point will be set when first fix arrives."*; distance/ascent/descent stay at zero; elapsed time and step counter tick normally. The first GPS fix during tracking re-runs the cascade and locks elevation. Per Dan's call (2026-05-05) the cascade retries on every accepted fix until one succeeds — robust to fixes that lack altitude (rare but possible).
- **Retroactive ascent for the deferred-fix gap.** Barometer samples are buffered during the gap, timestamped. When the cascade locks, the QNH is calibrated from the earliest buffered sample (closest to tap time) + the resolved elevation, then the full buffer is replayed through `AscentAccumulator` so any climbing during the gap counts toward totals. If the user stops without ever locking a fix, the buffer is replayed against the ISA default QNH 1013.25 — absolute altitudes are meaningless but Δp → Δalt deltas are still correct, so totals reflect real ascent.
- **`CompassSource`** — wraps `Sensor.TYPE_ROTATION_VECTOR` as a cold `Flow<Float>` of bearings (0..360°). Subscribed only during the deferred-fix gap and only used to buffer timestamped readings — no consumer in v1.x. Architectural placeholder for a future version that back-projects the actual start position from buffered step deltas + bearings via dead reckoning (CONTEXT.md "Out of scope for v1.x of Quick Start").
- **`isAcquiringFix` on `TrackSnapshot`** drives the banner. False for all non-Quick-Start sessions; remains true during the deferred-fix gap; flips to false the moment elevation locks (or stop is tapped without a lock).

### Changed
- `TrackingService.startTracking(type, quickStart)` — new boolean param, wired via `EXTRA_QUICK_START` intent extra. When true with no QNH yet, the service subscribes to barometer + compass even without a calibrated QNH, sets `isAcquiringFix = true` in the initial snapshot, and runs the deferred-fix pipeline.
- Quick Start benchmarks are populated into `BenchmarkSession.current` / `qnhHpa` in-memory only — `BenchmarkSession.save()` is **not** called. They're known to be lower-accuracy than a 60 s averaged benchmark and shouldn't poison future regular-Start proximity hits or the `known_location` cache.
- `versionCode` 17 → 18, `versionName` `1.14.2` → `1.15.0`.

## [1.14.2] — Unreleased

CSV export enrichment for the segmenter-tuning workflow. Saves having to type formulas in Excel for the two derived rates Dan was already eyeballing by hand.

### Added
- **`cadence_spm` column** — steps per minute, computed as `steps_delta / dt_from_prev_s * 60`. Blank when there's no previous fix or `dt = 0`. During auto-pause the step counter doesn't advance, so cadence naturally falls to 0 — that's the right answer.
- **`vertical_rate_mps` column** — vertical speed (m/s), computed as `(alt_curr − alt_prev) / dt_from_prev_s`. Sign carries direction (positive = climbing). Uses the chosen altitude (`altM` — barometric when QNH is calibrated, GPS otherwise), so it matches what `AscentAccumulator` sees.

### Changed
- **Renamed two existing CSV columns** for clearer Excel column headers: `cum_step_count` → `step_count`, `steps_from_prev` → `steps_delta`.
- `recover_track_from_log.py` updated to mirror the new column shape so a recovered-from-log CSV and a natively-exported CSV are interchangeable in Excel. Columns the log doesn't carry (pressure, bearing, GPS-vs-baro alt split, step counts and everything derived from them) are emitted blank rather than dropped.
- `versionCode` 16 → 17, `versionName` `1.14.1` → `1.14.2`.

## [1.14.1] — Unreleased

Hot-fix for a data-loss race in the v1.14 stop path. Symptom: an activity recorded fine, the History list showed it with correct totals, but the Summary screen's "View Map" and "Export CSV" buttons were both inert. Root cause: `TrackingService.persistActivity` dispatched the two DB writes (activity row + track-point batch) to the service's `CoroutineScope` and then `onStartCommand` immediately called `stopSelf()`, which schedules `onDestroy() → scope.cancel()`. Cancellation could land between `db.activities().upsert(...)` and `db.trackPoints().insertAll(...)`, persisting the activity row but losing every point. With zero `track_point` rows for the activity, `SummaryActivity.btnMap.isEnabled = points.size >= 2` and `btnExportCsv.isEnabled = points.isNotEmpty()` both evaluated false, so the buttons silently did nothing.

### Fixed
- **Persist now runs synchronously and atomically.** `TrackingService.persistActivity` switched from `scope.launch { upsert; insertAll }` to `runBlocking { db.withTransaction { upsert; insertAll } }`. The transaction guarantees both writes land or neither does (no more half-persisted activities), and `runBlocking` finishes before `onStartCommand` returns to call `stopSelf()`, so the persist can no longer be cut off by `scope.cancel()`. Brief main-thread block on stop is acceptable — Room's WAL batch insert for thousands of points is tens of ms, far cheaper than losing the track.

### Recovery
- Lost tracks from prior 1.14 sessions are partially recoverable from `Android/data/com.comtekglobal.tromp/files/autostop.log` — every accepted location fix was logged with lat/lon/speed/accuracy/altitude, so the most recent session (within the log's 2 MB rolling cap) can be reconstructed into GPX/CSV. Tracks that aged out of the log before this fix landed are unrecoverable.
- `scripts/recover_track_from_log.py` is the break-glass tool for this, kept in-tree so future incidents don't need it rewritten. Outputs match the in-app CsvWriter column shape (columns the log doesn't carry — pressure, bearing, GPS-vs-baro alt split, step counts — emit blank).

### Changed
- `versionCode` 15 → 16, `versionName` `1.14` → `1.14.1`.

## [1.14] — Unreleased

Work on branch `wire-grade-and-autopause` — enriched per-point capture + CSV export, pre-work for the eventual hike-vs-puttering classifier (see CONTEXT.md "Pending discussion — track segmentation"). Diagnostic feature: the captured columns are heavier than strictly needed for live UI, but they're necessary to tune the segmenter against real recordings before deciding what the rule should look like.

### Added
- **Enriched per-point capture.** `TrackingSession.Point` and `TrackPointEntity` now record per-fix `speedMps` (was always written `0f`), `bearingDeg` (`loc.bearing` if available), `cumStepCount` (session-relative step counter at the fix), and `isAutoPaused` (whether `AutoPauseDetector` was in PAUSED at fix time). Pre-1.14 activities have null/0/false in the new columns since the data didn't exist when they were recorded.
- **CSV export from the Summary screen.** New `export/CsvWriter` writes the enriched track plus computed neighbor deltas (distance, time, bearing change, step delta, stride) and an activity-summary header banner. Files land in `Android/data/com.comtekglobal.tromp/files/exports/tromp-<activityId>.csv` and the share sheet opens immediately so the user can email or Drive the file out for analysis in Excel.
- **`androidx.core.content.FileProvider`** under authority `${applicationId}.fileprovider` exposes the exports directory for `ACTION_SEND` without raw `file://` URIs (Android 7+ requirement). Path declaration in `res/xml/file_paths.xml`.

### Schema
- Room **v5 → v6**: adds `bearingDeg REAL`, `cumStepCount INTEGER NOT NULL DEFAULT 0`, `isAutoPaused INTEGER NOT NULL DEFAULT 0` to `track_point`. No backfill — pre-existing rows predate the capture.

### Changed
- `versionCode` 14 → 15, `versionName` `1.13` → `1.14`.
- `TrackingService.persistActivity` now passes `speedMps`, `bearingDeg`, `cumStepCount`, `isAutoPaused` through to the persisted entity instead of dropping them on the floor.
- `HistoryActivity.openActivity` populates the new `Point` fields from Room when reopening an activity, so the round-trip is lossless.

## [1.13] — Unreleased

Work on branch `wire-grade-and-autopause` — connect already-tested pure-logic modules to the live pipeline that had been writing zero-valued grade and never advancing moving time.

### Added
- **Live grade in the snapshot.** `TrackingService` now feeds each accepted fix's `(cumulativeDistanceM, chosenAltitudeM)` into a `GradeCalculator` and publishes `currentGradePct` on every `TrackSnapshot`. `maxGradePct` / `minGradePct` advance from real readings instead of the `±Infinity` sentinels, so persisted activities now carry meaningful steepest-climb / steepest-descent figures (previously written as `0.0`).
- **Auto-pause integrated.** Each fix is fed to `AutoPauseDetector`. When the state machine flips to `PAUSED` (DESIGN.md §6.4: speed < 0.5 m/s for 30 s continuous), the snapshot's `isAutoPaused` flag flips and the rest of the fix pipeline (distance, ascent/descent, grade, max-speed) is short-circuited so a stationary user doesn't accumulate phantom totals from GPS jitter. `auto-stop` continues to run during auto-pause — `RETURNED_HOME` explicitly wants the low-speed-near-start signal an auto-pause produces.
- **Moving time accumulation.** The 1 Hz ticker now advances `TrackSnapshot.movingMs` while the session is neither manually paused (existing behavior) nor auto-paused (new). `ActivityEntity.movingMs` is now a real value rather than always-zero; future stats can compute "moving avg speed" and "%time stopped" off it.

### Changed
- Stale comments removed from `TrackingService.kt` (file header), `TrackingSession.kt`, and `SummaryActivity.kt` — all three claimed Room persistence was "a later pass" / "pending", but it has been wired since v1.0.
- `versionCode` 13 → 14, `versionName` `1.12` → `1.13`.

### Notes
- `TrackPointEntity.speedMps` is still always written as `0f` (the persist path doesn't pass through `loc.speed`). Left for a future pass since no current screen reads per-point speed; mentioned here so future work doesn't think it's authoritative.

## [1.12] — Unreleased

Rename from TrekTracker to Tromp.

### Added
- **First-run safety disclaimer** (`util/SafetyDisclaimer`). Blocking dialog on first launch; re-viewable from Settings → "Safety & disclaimer". Acceptance is keyed to a version string so a material text change can force re-acknowledgement on next launch.
- **Open source licenses screen** (`ui/licenses/LicensesActivity`) reachable from Settings → "Open source licenses". Renders `res/raw/oss_licenses.txt` — bundled dependency attributions (Apache 2.0 full text + per-project notices) plus OpenStreetMap / USGS 3DEP / Open-Elevation data-service attribution.
- **README Legal section** covering safety, no-warranty, third-party attribution, trademarks, and privacy; top-of-file safety callout mirroring the in-app disclaimer.

### Changed
- **App rename.** Display name `app_name` → `Tromp`; theme `Theme.TrekTracker` → `Theme.Tromp`; Gradle `rootProject.name` → `Tromp`; notification title / channel label → `Tromp`.
- **Package restructure.** `namespace` + `applicationId` changed from `com.trektracker` to `com.comtekglobal.tromp`. Source trees moved from `app/src/{main,test}/java/com/trektracker/**` to `app/src/{main,test}/java/com/comtekglobal/tromp/**`; all `package` declarations and `import` lines rewritten to match. Class names containing `TrekTracker` updated to `Tromp`.
- `versionCode` 12 → 13, `versionName` `1.11` → `1.12`.

### Rotated to Tromp identity
- **Release keystore** rotated on 2026-04-24 to `CN=Tromp, alias=tromp, password=tromp2026`. Done while the install base was effectively one device, so the unavoidable break of the signing-key continuity (Android refuses to update an app signed by a different key) cost a single uninstall+reinstall. The pre-rotation keystore is archived at `app/release.keystore.trektracker.bak` and must never be reused — any APK signed with it cannot upgrade one signed with the current keystore.

### Preserved deliberately (to keep existing installs upgradable without data loss)
- **SQLite database filename** (`trektracker.db` in `TrekDatabase.kt`). Renaming it would make every existing install appear to have no history.
- **Notification channel ID** (`trektracker.tracking` in `TrackingNotifier.kt`). Changing it resets users' per-channel sound/vibration/importance preferences.
- **SharedPreferences file names** (`trektracker.benchmark` in `BenchmarkSession.kt`, `trektracker.units` in `UnitPrefs.kt`). Renaming them orphans users' stored benchmarks and unit choice.
- `TrekDatabase` **class name** (internal, not shown to users).

These four identifiers are opaque to users and carry no brand surface; they would be cleanable given a one-time migration path, not done here. (The keystore alias was originally on this list but was rotated separately on 2026-04-24 — see "Rotated to Tromp identity" above.)

## [1.11] — Unreleased

Work on branch `ci-workflow` — CI builds, release signing, and install-from-GitHub docs.

### Added
- **GitHub Actions build workflow** at `.github/workflows/build.yml`. Every push (any branch) builds a signed release APK and uploads it as a 90-day artifact; tag pushes matching `v*` additionally attach the APK to an auto-generated GitHub Release with generated release notes.
- **Committed release keystore** at `app/release.keystore` (RSA 4096, 100-year cert), with passwords in `app/build.gradle.kts`. Lets every rebuild produce a byte-identical signed APK so updates install over each other without wiping data. Clearly labeled in the README as side-loading-only — not for Play Store distribution.
- **Install instructions in the README** pointing at the Releases page and the Actions artifacts list.

### Changed
- `buildTypes.release` now wires `signingConfig = signingConfigs.getByName("release")`. Debug builds are unaffected.

## [1.10] — Unreleased

Continued work on branch `cleanup`.

### Added
- **Rename / delete activities** from the History screen. Each row now has edit and trash icons next to the existing tap-to-open affordance. Delete prompts for confirmation and manually cascades through `track_point` and `waypoint` (those tables don't declare foreign keys on `activity.id`). Rename opens an EditText dialog; a blank name clears the field so the default derived name is used.
- **Coordinates shown on every benchmark row** at 2 decimal places (roughly ±1 km) so each cached benchmark is identifiable at a glance, even when named.
- `ActivityDao.rename(id, name)` — single-field `UPDATE` that avoids a read-modify-write round trip.

### Changed
- Benchmarks rows no longer fall back to `"at LAT, LON"` in the title line when unnamed; they show `"Unnamed"` instead, since the coordinates are now displayed on the meta line for all rows.

## [1.9] — Unreleased

Continued work on branch `cleanup`.

### Added
- **Benchmarks management screen.** Settings gear now opens a dialog with two items: Units, and Manage benchmarks. The new `BenchmarksActivity` lists all cached benchmarks in MRU order with their elevation, source, last-used and recorded timestamps. Each row has Rename (EditText dialog) and Delete (confirmation dialog) affordances.
- `KnownLocationEntity.name: String?` — user-assignable label. Row headers fall back to `"at LAT, LON"` when unnamed.

### Changed
- **Fresh benchmark auto-starts tracking.** After a full benchmark flow, the app now chains `BenchmarkActivity → CalibrationActivity → startTrackingService` automatically (previously it returned to the idle main screen and the user had to tap START again). The chain lives in `MainActivity` launchers; `BenchmarkActivity.acceptAndReturn` just saves and returns `RESULT_OK` now.

### Schema
- Room **v4 → v5**: adds nullable `name` column to `known_location`. No data migration required (existing rows default to null).

## [1.8] — Unreleased

Work on branch `cleanup` — UI unit preference, LRU benchmark cache, and toolchain fixes for Kotlin 2.2.

### Added
- **Unit preference** (Imperial / Metric) reached via the gear on the main screen. A single-choice dialog writes the selection to `SharedPreferences` (`util/UnitPrefs`); every user-facing screen (main, summary, history, benchmark, calibration) now shows the selected unit only. Internal storage and calculations remain metric SI per CLAUDE.md conventions.
- **LRU eviction for the known-location benchmark cache.** A new `lastUsedAt` column is bumped whenever a benchmark is reused at START; `trimToMostRecent(100)` runs after every insert or touch, so the cache stays capped at the 100 most-recently-used benchmarks and the oldest drop off the tail.

### Changed
- **Removed the Acquire-Benchmark button** from the main screen. The "Benchmark required" dialog launched from START remains the entry to `BenchmarkActivity`.
- **All dual-unit displays collapsed to single-unit** using the new `formatDistance` / `formatElevation` / `formatElevationDelta` / `formatSpeed` helpers in `util/Units.kt`. Strings `benchmark_active` and `autostop_body` in `strings.xml` were reshaped to accept pre-formatted distance/elevation strings instead of raw numbers + hardcoded unit suffixes.
- **Kotlin annotation processing migrated from kapt to KSP.** With Kotlin 2.2.10, the old `kotlin.kapt` 1.9.24 plugin + Room 2.6.1's bundled `kotlinx-metadata-jvm` failed with `Metadata version 2.2.0 > maximum supported 2.0.0`. Switched to `com.google.devtools.ksp` 2.2.10-2.0.2 and bumped Room to 2.7.2 (Room ≥ 2.7 is required for KSP2 + Kotlin 2.2).
- **Cleaned up deprecated AGP flags** in `gradle.properties`: removed five flags that now match AGP 9.0 defaults. Kept `android.builtInKotlin=false` (we apply our own Kotlin plugin) and `android.newDsl=false` (something in the build path still uses the legacy variant API).
- Null-safety warning in `DebugLog.kt` resolved with `ThreadLocal.get()!!` (guaranteed non-null by `withInitial`).

### Schema
- Room **v3 → v4**: adds `lastUsedAt INTEGER NOT NULL DEFAULT 0` to `known_location` with an index; `MIGRATION_3_4` seeds `lastUsedAt = recordedAt` for existing rows so pre-upgrade benchmarks have a sensible MRU order.

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
