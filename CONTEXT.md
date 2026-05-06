# Tromp — Project Context

Running notebook for ongoing project context. Read on every session start; update continuously as decisions / constraints / gotchas accumulate. Project-scoped complement to the global `~/.claude/Context/HISTORY.md`.

For the canonical spec see `DESIGN.md`. For session-coding guidance see `CLAUDE.md`. For per-version what-changed see `CHANGELOG.md`.

---

## Snapshot (2026-05-05)

- **Branch / version:** on `master`. `versionName 1.15.1` / `versionCode 19`. v1.14.1 + v1.14.2 are committed (latest commit `7a28778`); v1.15.0 Quick Start + v1.15.1 classifier diagnostic export ship as a single follow-up commit on master.
- **Active branches still around:** `keystore-rotation`, `legal-and-maps`, `rename-tromp` — all merged into master, leftover. Safe to delete locally if Dan wants tidiness; doesn't affect CI.
- **`applicationId` / `namespace`:** `com.comtekglobal.tromp`. Source root `app/src/main/java/com/comtekglobal/tromp/`.
- **Toolchain:** AGP 8.13.2, Kotlin 1.9.24, JVM 17, KSP for Room (kapt was removed in 1.8 — see CHANGELOG). Room 2.7.2 at schema **v5** (`app/schemas/com.comtekglobal.tromp.data.db.TrekDatabase/` is the exported source of truth for migrations).
- **Release keystore:** committed at `app/release.keystore` (CN=Tromp, alias=tromp, password=tromp2026). Pre-rotation TrekTracker keystore archived at `app/release.keystore.trektracker.bak` — never reuse it.
- **CI:** GitHub Actions `.github/workflows/build.yml` builds a signed APK on every push, attaches to release on `v*` tags.

## What's actually built (vs. what `CLAUDE.md` says)

`CLAUDE.md`'s "Scaffold state (2026-04-19)" section is stale — it describes a v1.0 skeleton. The actual app at v1.12 is much further along. Going by the code:

- **Working end-to-end:** START → cached-benchmark auto-calibrate (or full Acquire-Benchmark flow) → CalibrationActivity locks QNH → TrackingService records via `LocationSource` + `BarometerSource` + `StepCounterSource` → STOP persists `ActivityEntity` + `TrackPointEntity` rows → SummaryActivity reads `TrackingSession.lastSnapshot` → MapActivity renders polyline on osmdroid.
- **Auto-stop phase 1:** `AutoStopDetector` watches each fix for SPEED_SPIKE (≥3× trailing 60s mean AND ≥10 mph for 3 consecutive fixes) and RETURNED_HOME (post-leftHome, ≤1 m/s within 100 m of session start). MainActivity surfaces a confirm dialog; accepting trims via `AutoStopTrimmer` and recomputes totals.
- **History:** `HistoryActivity` lists past activities w/ all-time totals header; rename + delete; tap reopens via re-populating `TrackingSession` from Room.
- **Settings dialog (gear):** Units (Imperial default), Manage benchmarks, Safety & disclaimer, Open source licenses.
- **Legal:** First-run blocking `SafetyDisclaimer` keyed to `CURRENT_VERSION = "2026-04-24.v1"`; `LicensesActivity` renders `res/raw/oss_licenses.txt`; README has the full Legal section.

**Not yet built (canonical list lives in README "Not yet built" §):** dedicated live tracking screen (the main screen doubles as it for now), ribbon fallback, offline tile manager, activity detail w/ elevation profile chart (MPAndroidChart dep is declared but unused), full stats dashboard (DAOs `aggregateBetween` / `aggregateByTypeBetween` are ready), crash-recovery dialog, GPX export wired to UI, automatic session start on detected motion.

## Recent bug — persistActivity race (fixed in v1.14.1)

In v1.14 (and silently for many versions before, masked by point loss producing only inert buttons rather than a hard error), `TrackingService.persistActivity` dispatched the two stop-time DB writes via `scope.launch { upsert; insertAll }`. The service then called `stopSelf()` → `onDestroy()` → `scope.cancel()`. Cancellation could land between the two suspend calls, persisting the activity row but losing every track point. Symptom on Summary: "View Map" and "Export CSV" both dead because both gate on `points.isNotEmpty()`. Fix: `runBlocking { db.withTransaction { upsert; insertAll } }` — synchronous + atomic. See CHANGELOG [1.14.1].

**Recovery side path:** `DebugLog.log("FIX", ...)` writes every accepted fix to `Android/data/com.comtekglobal.tromp/files/autostop.log` (2 MB rolling cap). The most recent session, if still in the log, can be parsed into GPX/CSV with `recover_track_from_log.py` at the repo root. Older sessions that aged out are unrecoverable.

## Gotchas worth keeping at the front of your mind

- **Preserved-on-purpose `trektracker*` identifiers** (DB filename, channel ID, two SharedPreferences names, `TrekDatabase` class name). Don't rename — would orphan existing sideloaded installs. See CHANGELOG [1.12].
- **`UnitPrefs` default is IMPERIAL**, not metric. Initial users are US-based. Internal storage is still SI; only display flips. Don't accidentally swap the default.
- **`TrackPointEntity.speedMps` is still always written as `0f`** in the persist path. The data model has the field but `loc.speed` isn't carried through. No screen reads it today, but if a future stat needs per-point speed, plug it in at `TrackingSession.Point` → `TrackingService.persistActivity`.
- **Auto-pause short-circuits the fix pipeline.** While `AutoPauseDetector` reports `PAUSED`, distance / ascent / grade / max-speed updates are skipped (snapshot still updates lat/lon/speed/marker). `auto-stop` keeps running since `RETURNED_HOME` actively wants the low-speed-near-start signal that an auto-pause produces. Don't restructure that without re-reading DESIGN.md §6.4.
- **`movingMs` advances at 1 s resolution** (driven by the 1 Hz ticker). It's deliberately coarse — second-level resolution matches the elapsed-time display and avoids fix-rate-dependent drift. Don't switch to per-fix accumulation without thinking through pause boundaries.
- **Background-location permission is declared but never requested.** Foreground service keeps the process alive with screen off, so it works without it on most devices, but background-prompted apps get more reliable wake on Android 14+. README documents this gap.
- **DEM lookups are blocking `HttpURLConnection`** — keep them on `Dispatchers.IO`. Don't pull in OkHttp/Retrofit for two endpoints; the retry-once-with-750ms-backoff pattern in `DemClient.withRetry` is enough.
- **osmdroid User-Agent** is set in `TrompApplication.onCreate` (`Tromp/<version> (+https://github.com/doxender/Tromp)`). Required by OSMF tile policy; if you ever fork, change it.

## Pre-publication checklist

This is a personal sideload. The "Tromp pre-publication checklist" in Dan's auto-memory tracks blockers before any Play Store submission. Tromp has a sideload exemption — don't surface those items unless the trigger is Play / public release / tag. (Tags currently do attach an APK to a GitHub Release, but that's still personal sideload distribution, not public Play release.)

## Pending discussion — track segmentation / smart trimming (2026-05-01)

Dan reported three real-world problems on a hike-from-home session, then proposed a generalized fix. Decision deferred — Dan was tired and asked to write it up. Pick this back up next session.

### What Dan reported

1. **Trailing puttering.** Walked home from hike, walked around yard, sat down — auto-stop never fired and the puttering ended up in the recorded hike.
2. **Leading puttering.** Tapped START + benchmark, puttered around the house before actually starting to hike — the puttering counted as part of the hike.
3. **Mid-hike short stop.** Stopped ~1 min for the dog to pee — that segment wasn't eliminated.

### What v1.13 (this session) actually fixes

| Problem | v1.13 status |
|---|---|
| #1 trailing puttering at home | Not addressed |
| #2 leading puttering before hike start | Not addressed (auto-start doesn't exist) |
| #3 mid-hike short stops inflating totals | **Fixed** (auto-pause now wired — totals freeze after 30 s of < 0.5 m/s) |
| #3 short stops still visible on map polyline | Not addressed (track points still appended during auto-pause) |

Auto-stop's `RETURNED_HOME` rule is the source of #1's failure: it requires `speed ≤ 1.0 m/s AND dist-from-start ≤ 100 m` on a single fix. Walking around the yard at ~1.4 m/s never satisfies; even when it does fire, the trim point is "moment of re-entry at low speed," not "moment hiking ended." Auto-START doesn't exist at all (README "Not yet built" calls it the missing third of the auto-trio).

### Dan's idea: post-hoc track segmentation

Use richer per-point data + a classifier over sliding windows to identify hiking vs. puttering vs. still vs. driving segments, then trim non-hiking spans at session-stop. Lookahead lets the rule see what real-time auto-pause/auto-stop can't.

**What's needed on disk** (currently lost):
- `speedMps` per point — `loc.speed` is available, written to Room as `0f` today.
- `bearingDeg` per point — `loc.bearing`, never captured.
- Step delta per point — sample `sessionStepCount` on each fix, diff vs. previous; new column.

**Signals + ranges**:
| Signal | Hiking | Puttering | Standing | Driving |
|---|---|---|---|---|
| Stride (dist / steps) | 0.5–1.5 m | 0.2–0.5 m | ~0 | huge / no steps |
| Sinuosity (path len / net disp) over 60 s | 1.0–1.3 | 3+ | undefined | ~1 |
| Speed | 0.7–2 m/s | 0.3–1 m/s | < 0.3 m/s | > 4 m/s |

Stride length is the strongest single discriminator.

### Critical constraint Dan added: rocky scrambles

His trails include 20-foot loose-rock clambers where he zigzags carefully and slows way down to avoid falling. Short-window signals will look identical to puttering: slow, short stride, high bearing variance.

**Rule must use long windows + elevation/displacement:**
- Net displacement over a longer window (3–5 min) — a real scramble nets meaningful linear progress; puttering at home does not.
- Elevation change — scrambles gain or lose altitude; puttering at home is flat.
- Don't use short-window sinuosity alone; it will false-positive on legitimate hard terrain.

Probably: classify a segment as non-hiking only when **multiple** signals agree (low stride + low long-window net-displacement + low elevation change). One signal alone is not enough.

### Suggested two-PR shape

1. ~~**Enrich capture.**~~ **Done in v1.14.** `TrackingSession.Point` + `TrackPointEntity` now record per-fix `speedMps` (was always 0), `bearingDeg`, `cumStepCount`, `isAutoPaused`. Room migration v5 → v6. CSV export from the Summary screen writes the enriched track plus computed neighbor deltas (distance, time, bearing change, step delta, stride) for offline analysis in Excel.

2. **`TrackPostProcessor`** — pure-logic classifier (unit-testable like `AutoStopTrimmer`). Takes `List<Point>` → returns segments tagged hiking / puttering / still / driving. Initially run at stop: drop leading/trailing non-hiking, recompute totals via the same shape `AutoStopTrimmer` already uses. Mid-track puttering: probably keep on map, exclude from totals. Iterate via synthetic fix-stream tests so we don't need on-device walks every revision. Tuning waits until real CSVs are in hand.

### Open questions for next session

- Should mid-track puttering be dropped from the polyline too, or kept-but-not-counted? (Dan likely cares more about clean totals than clean polyline.)
- Where does the user override live? Sometimes a "puttering" segment is a real lunch break the user wants kept.
- Auto-start: is it a separate piece of work, or does the classifier subsume it (run at stop, the classifier trims leading puttering anyway)? Probably the latter — classifier eats both the leading and trailing problem at once.

### Classification rule Dan specified 2026-05-04 (apply on close, pre-stats)

Per-fix classification using a ~15-second rolling window centered on the fix being classified. Evaluate in order — first match wins:

| State | Predicate |
|---|---|
| **ACTIVE** | `avg_speed ≥ 0.6 m/s` AND `speed_variation ≤ 60% of avg` (i.e. coefficient of variation `stddev(speed)/mean(speed) ≤ 0.6`). Hiking. |
| **CLAMBERING** | `avg_speed < 0.6 m/s` OR speed erratic, AND `(cadence ≥ ~30 spm` OR `|vertical_rate| ≥ ~0.1 m/s)`. Moving with effort, slowly — covers rocky scrambles, careful descent. |
| **DAWDLING** | `avg_speed < 0.6 m/s`, low/no cadence, `|vertical_rate| < ~0.1 m/s`. Standing or puttering. |

Cadence = `steps_delta / dt * 60` per fix (already computed by v1.14.2 CsvWriter). Vertical rate = `Δalt / dt` per fix (also v1.14.2). Both are inputs to the classifier; thresholds are starting values to tune against real CSVs after first 1.14.2 recordings land.

**Decisions resolved 2026-05-04/05 (full set after Dan's 1b/2c/3a/4b/auto-paused-b/exclude-everything-a/delete-old-c-via-migration/c-bulk+per-activity/loc-speed-a/partial-windows-a/summary-with-note-b/diagnostic-cols-a):**

1. **Effect on stats: exclude.** DAWDLING points don't count toward distance, moving time, avg/max speed, ascent, descent, max/min grade, or step count. Elapsed time is the only total that includes DAWDLING (wall-clock from start to stop).
2. **Map: untouched.** Polyline shows every recorded fix as-is. Classification only affects totals.
3. **Label persisted.** Room v6 → v7 migration adds `state TEXT` to `track_point` and `classifierVersion TEXT` to `activity`. Classifier runs once at stop in `TrackingService.stopTracking()` before `persistActivity`. The migration **wipes existing `activity`, `track_point`, and `waypoint` rows** as a one-time clean slate — calibration data (`known_location`, `offline_region`) is preserved. Going forward, new classifier versions don't wipe; users reclassify in place.
4. **Thresholds tunable.** Constants in `TrackPostProcessor.kt`: `0.6 m/s` (active speed floor), `0.6 CV` (speed-stability ceiling), `30 spm` (cadence floor), `0.1 m/s` (vertical-rate floor). Revise after first real CSVs land.
5. **Auto-paused points: classified normally.** The post-hoc rule has more signal than the live `AutoPauseDetector`; live verdict isn't binding.
6. **Speed source: `loc.speed` (Doppler).** Same as `AutoPauseDetector`. Never chain-derived from haversine — that turns GPS jitter into phantom motion.
7. **Window: 15 s, partial OK.** Window centered on the fix being classified; whatever fixes fall in that range are used. Single-fix windows (no neighbors) → no `dt`, no stddev, no cadence/vrate signal → defaults to DAWDLING. Acceptable behavior at session boundaries / after long fix gaps.
8. **Reclassify UI: bulk + per-activity.** Settings → "Reclassify all activities" runs the current classifier across every activity. Summary → per-activity "Reclassify" button rewrites just that one. No banner.
9. **Summary post-stop UI: filtered totals + note.** Summary shows post-classification totals as the headline numbers. A small line below reads "X.X km / YY min excluded as dawdling" so the user understands why the live numbers shrank.
10. **CSV diagnostic columns:** Per-fix the CSV adds `state`, `state_reason` (short string like `speed_below_active`), `window_avg_speed_mps`, `window_speed_cv`, `window_avg_cadence_spm`, `window_avg_vrate_mps`. Lets Dan sort/filter in Excel during the threshold-tuning phase.

Plan: implement as `tracking/TrackPostProcessor.kt`, pure-logic + unit-testable like `AutoStopTrimmer`. Run from `TrackingService.stopTracking()` before `persistActivity`. Recompute totals using the same shape `AutoStopTrimmer` already uses (filter to ACTIVE+CLAMBERING, walk neighbor pairs, sum distance + ascent/descent, recompute avg from totals).

### Status (v1.15.1, 2026-05-05)

- **Classifier built.** `tracking/TrackPostProcessor.kt` ships the rule above with the documented thresholds, plus `TrackPostProcessorTest` covering ACTIVE / CLAMBERING / DAWDLING base cases, the single-fix-window default, threshold boundaries, and the timestamp-gap behaviour.
- **Diagnostic export wired.** Every stop auto-generates `tromp-<id>-pretrim.csv` (every fix with `state` + four `window_*` columns) and `tromp-<id>-posttrim.csv` (DAWDLING dropped). Both land in `Android/data/<applicationId>/files/exports/` next to the activity. Summary's Export CSV button shares both via ACTION_SEND_MULTIPLE.
- **Activity totals, map polyline, and Room state persistence are deliberately untouched.** Decisions 1–9 of the resolved set haven't been actioned yet — Dan validates the rule against real recordings first, then we land the full rollout (Room v6 → v7 migration with the one-time activity-table wipe, summary "X km excluded as dawdling" line, bulk + per-activity reclassify, etc.) as a separate change.

### Next steps when ready to ship the full rollout

- Apply the Room v6 → v7 migration described in item 3 (adds `state TEXT` to `track_point`, `classifierVersion TEXT` to `activity`, wipes existing rows).
- Update `TrackingService.stopTracking()` to recompute totals against the ACTIVE+CLAMBERING subset before persistActivity.
- Wire the bulk + per-activity reclassify entry points (Settings / Summary).
- Land the "X.X km / YY min excluded as dawdling" line on Summary (item 9).

## Quick Start (shipped in v1.15.0, 2026-05-05)

Single-shot "skip the full benchmark" flow. **Quick benchmarks are never saved to the `known_location` cache** — they're known to be lower-accuracy than the 60 s averaged benchmark and shouldn't pollute the long-term cache. Also not persisted to the `BenchmarkSession` SharedPreferences (in-memory only — they don't outlive the app process).

### Implementation pointers
- UI: `MainActivity.onQuickStartClicked()`. Permission gating mirrors regular Start (`REQ_FINE_LOCATION_QUICK`/`REQ_NOTIFICATIONS_QUICK`/`REQ_ACTIVITY_RECOGNITION_QUICK`); 15 s acquisition modal in `showAcquiringDialog()`; `showNoFixDialog()` covers the timeout / no-elevation paths.
- Service: `TrackingService.startTracking(type, quickStart)` reads `EXTRA_QUICK_START`. Deferred-mode plumbing: `tryStartCascade()` (per-fix, AtomicBoolean-guarded), `onElevationLocked()` (calibrates QNH, replays `bufferedBaro` through `AscentAccumulator`, populates `BenchmarkSession.current` in-memory). Stop-without-lock path in `stopTracking()` walks `bufferedBaro` against ISA default QNH 1013.25 so totals still reflect real ascent.
- Sensors: `CompassSource` (`Sensor.TYPE_ROTATION_VECTOR` → cold `Flow<Float>` of 0..360° bearings). Subscribed only during the deferred-fix gap. Buffer is in-memory and unconsumed in v1.x — placeholder for the v2 dead-reckoning back-projection.
- Snapshot: `TrackSnapshot.isAcquiringFix` drives the banner in `activity_main.xml` (`txtAcquiringBanner`).

### Original spec (kept verbatim for traceability)

A single-shot "skip the full benchmark" flow Dan can use when he wants to start a hike fast.

### UI

- **Secondary button below the existing Start.** Smaller text-style button labeled "Quick Start." Big "Start" stays the primary action; Quick Start is the implicit "in a hurry" fallback.
- **15-second acquisition modal** when tapped: spinner with title "Acquiring GPS…" and a Cancel button. No countdown — feels more natural than watching seconds tick down.
- **No-fix dialog** if 15 s elapses without a fix, OR if a fix arrives but both DEM lookup and `loc.hasAltitude()` fail (no elevation reference): "Use next fix as start point" (primary) / Cancel (dismiss).
- **Pre-fix-gap live UI** (deferred mode after user taps "Use next fix"): banner across the top reads "Acquiring GPS — start point will be set when first fix arrives." Distance, ascent, descent, grade, avg/max speed all display 0. Elapsed time and step count tick normally. When the first fix arrives, the banner clears and the totals start updating.

### Cascade

1. Take ONE GPS fix (15 s timeout, Cancel button live).
2. ONE baro reading at the same moment (snap current pressure).
3. If GPS fix arrives:
   - Try ONE DEM lookup at fix's lat/lon (USGS 3DEP, fallback Open-Elevation, same client as full benchmark).
   - **Benchmark elevation = DEM result if it succeeds, else `loc.altitude` if available, else null.**
   - If non-null elevation: calibrate QNH from baro reading + elevation. Start tracking with calibrated QNH.
   - If null: fall through to deferred-fix mode (same as no fix) — show the dialog.
4. If 15 s timeout hits: show no-fix dialog. User picks "Use next fix" → start tracking with no QNH (yet); first fix during tracking re-runs the cascade and "nails up" the start.

### Deferred-fix mode (user tapped "Use next fix")

- Tracking starts immediately. `TrackingService.startTracking()` runs as normal but with `BenchmarkSession.qnhHpa = null` and the live screen in pre-fix-gap state.
- Step counter, barometer, compass all record from tap-time onward. **Compass bearings recorded during the gap are stored for future dead-reckoning** even though we won't use them in v1.x (we want the data available so a later version can back-project the actual start position from steps + bearings without architectural changes).
- When the first GPS fix arrives during tracking:
  - Cascade re-runs in the background: DEM at fix lat/lon, fallback to `loc.altitude`, fallback null.
  - If non-null elevation: calibrate QNH from baro reading **at start tap time** (we still have it) paired with the elevation. The fix point becomes the activity's start point.
  - **Retroactively compute pre-fix-gap ascent** by walking through the buffered baro readings (timestamped pressures from start to first fix), converting each to altitude with the now-known QNH, feeding through `AscentAccumulator`. The resulting ascent counts toward totals (no track points to attach to, since there's no lat/lon for the gap, but the totals reflect it).
  - Pre-fix gap doesn't contribute distance (no positions; dead reckoning deferred).
- If user taps Stop before any fix ever arrives: save the activity with elapsed time, step count, and best-effort ascent (computed against default QNH 1013.25 since we never got a real elevation reference — absolute is meaningless but relative changes are right). Track point list empty. Map / Export CSV would be inert. Activity row still in History; rename / delete work.

### Activity type / naming

- Inherits the current default (`hike`). No type-picker in the Quick Start flow — that's part of "quick."

### What we're recording during the gap (architecture for future)

- Step counter samples (already wired through `StepCounterSource`).
- Compass bearings — **new sensor source** to add: `CompassSource` cold Flow on `Sensor.TYPE_ROTATION_VECTOR`, sampled at ~1 Hz. Stored timestamped. Currently unused; v2-of-Quick-Start will consume them for dead reckoning.
- Barometer pressures (already wired through `BarometerSource`).

### Out of scope for v1.x of Quick Start

- Dead reckoning (steps + compass → estimated displacement → back-project actual start). Hooks in place; implementation deferred.
- Per-user step-length calibration. Hooks in place; implementation deferred.
- "Quick benchmark" cache lookup at tap time. Quick Start always does the single-shot cascade; cache lookup is what regular Start is for.

## Update protocol

When something material changes (algorithm decision, dep added, schema bump, new gotcha discovered, comment-vs-reality drift fixed) edit this file in the same commit. If it gets long, split by topic — but don't let it lag behind the code.
