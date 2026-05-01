# Tromp — Project Context

Running notebook for ongoing project context. Read on every session start; update continuously as decisions / constraints / gotchas accumulate. Project-scoped complement to the global `~/.claude/Context/HISTORY.md`.

For the canonical spec see `DESIGN.md`. For session-coding guidance see `CLAUDE.md`. For per-version what-changed see `CHANGELOG.md`.

---

## Snapshot (2026-05-01)

- **Branch / version:** working on `wire-grade-and-autopause` off `master`. `versionName 1.13` / `versionCode 14`. Latest master commit `cfb529f` (keystore rotation merge).
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

## Update protocol

When something material changes (algorithm decision, dep added, schema bump, new gotcha discovered, comment-vs-reality drift fixed) edit this file in the same commit. If it gets long, split by topic — but don't let it lag behind the code.
