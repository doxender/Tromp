# Tromp — Design Document

> Copyright (c) 2026 Daniel V. Oxender. Licensed under the MIT License — see `LICENSE`. This notice must be preserved in all derivative works.

Android activity tracker for hikes, runs, walks, and rides. Records position, elevation, distance, climb/descent, grade, and waypoints. Maps the track over OpenStreetMap with offline tile support; falls back to a 2D elevation-colored ribbon view when no map tiles are available. Presents per-activity detail and aggregate stats over user-selected date ranges.

Includes a GPS-averaging benchmark acquisition flow and barometer calibration flow that establish a base elevation so live altitude during tracking can be read from the barometer instead of GPS.

---

## 1. Requirements

### 1.1 Functional — Live Tracking
- Start / pause / resume / stop an activity session.
- Optional pre-session **Acquire Benchmark**: 60 s GPS average + USGS 3DEP / Open-Elevation lookup, producing a high-confidence starting elevation.
- Optional **Calibrate Barometer**: compute QNH from the benchmark elevation + averaged barometer reading; calibration persists for the current session only.
- During tracking, continuously display and update:
  - Current latitude / longitude
  - Current elevation (barometer-driven if calibrated; GPS altitude otherwise)
  - Current distance (horizontal, cumulative)
  - Total ascent / total descent (cumulative)
  - Current grade (rise-over-run over a rolling window)
  - Max grade (steepest sustained climb this activity)
  - Min grade (steepest sustained descent this activity; typically negative)
  - Current speed / average speed / pace
  - Elapsed time / moving time
- **Drop waypoint**: user taps a button to flag the current location, with an optional text note.
- **Units** selectable: (m, km) / (ft, mi). Always stored in SI, converted at display time.

### 1.2 Functional — Map & Visualization
- **Map view**: OpenStreetMap via osmdroid, with the live track overlaid and a current-position marker.
- **North-up / track-up** toggle (nav-style heading).
- **Offline map**: pre-downloaded tile cache per region. User pans/zooms the map to cover the target area, taps "Download this view", picks a max zoom level, confirms estimated size.
- **Offline fallback view**: when no tiles are cached for the current area and no cell coverage, render the track as a 2D polyline projected top-down; color encodes elevation (blue low → red high); supports north-up / track-up rotation and pinch-to-zoom. Shows current position marker.
- **Elevation profile chart**: elevation over distance, shown in activity detail view.
- **Attribution**: "© OpenStreetMap contributors" rendered on all OSM map surfaces.

### 1.3 Functional — Persistence & History
- Each completed activity is saved with its full track (points) and a summary row.
- **Activity list**: reverse-chronological list of saved activities; tap to open detail.
- **Activity detail**: summary stats, elevation profile chart, map of the route (or ribbon fallback), waypoint list, share (GPX) / rename / delete.
- **Stats dashboard**: selectable date range (this week / this month / this year / all-time / custom); aggregate totals (distance, ascent, descent, moving time, activity count); per-type breakdowns splitting those totals by Hike / Run / Bike / Walk / Other; year-over-year comparison (selected period vs. the same period one year prior); personal records (longest, most climb, steepest); distance-per-week bar chart.
- **GPX export**: share an activity as a standard GPX 1.1 file (`<trkpt>` with lat/lon/elevation/time + `<wpt>`); uses WGS84 altitude for maximum compatibility.
- **Crash / kill recovery**: if the app process is terminated mid-activity, on next launch offer to resume the orphaned session.

### 1.4 Functional — Settings (gear icon on main screen)
- Units: metric / imperial defaults for distance and elevation.
- Default GPS sample interval (1 s / 3 s / 5 s / 10 s).
- Distance-accuracy threshold (default 15 m).
- Auto-pause enabled / disabled; speed threshold configurable.
- Grade window length (default 20 m).
- Offline tile download manager (list downloaded regions; delete).
- Default activity type label (Hike / Run / Bike / Walk / Other).

### 1.5 Non-Functional
- Tracking must continue reliably with the phone screen off and the app backgrounded.
- A **foreground service** guarantees continuous GPS/sensor access, with a **low-importance ongoing notification** exposing Pause and Stop actions and showing live distance + elapsed time. The app itself may be backgrounded or swapped away freely; only the small status-bar notification is user-visible.
- Battery target: a 4-hour activity at default settings consumes < 25% battery on a modern phone (to be measured post-impl).
- Storage: must handle ≥ 1,000 saved activities without UX degradation.
- Persistence durability: track points flushed to DB at least every 5 seconds so an OS kill loses ≤ 5 s of data.
- Offline-first: core tracking, elevation math, grade computation, and the fallback ribbon view must function with **zero network connectivity**.
- Network is required only for: DEM lookups during Acquire Benchmark, online OSM tiles (when no offline cache), map tile downloads.

### 1.6 Constraints
- Target: Android 8.0+ (minSdk 26), compileSdk/targetSdk 34.
- Kotlin only; toolchain AGP 8.13.2, Kotlin 1.9.24, JVM 17.
- Permissions required: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (Android 13+), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` (Android 14+).
- OSM tile usage: default `tile.openstreetmap.org` is acceptable for personal use with a proper `User-Agent`; swap to Mapbox/MapTiler later if the user base grows (one-line change in osmdroid tile source).

### 1.7 Out of Scope (v1)
- GPX **import** (pre-planned routes overlaid as a guideline).
- Photos attached to waypoints.
- Real-time social features (live location sharing, segments).
- BLE heart-rate / cadence sensors.
- Voice announcements.
- Cloud sync / multi-device.
- Google Fit / Health Connect integration.
- Turn-by-turn navigation.

---

## 2. Assumed Defaults

These are locked-in defaults unless pushed back on. Configurable via Settings where noted.

| Setting | Default | Configurable? | Rationale |
|---|---|---|---|
| GPS sample interval | 3 s | yes | Track fidelity vs battery/storage. |
| Distance accuracy threshold | 15 m | yes | Drops phantom distance from poor fixes. |
| Grade rolling window | 20 m of horizontal displacement | yes | Distance-based; matches geometric meaning of grade. |
| Auto-pause trigger | < 0.5 m/s for > 30 s | yes | Avoids false pauses on slow climbs. |
| Auto-resume trigger | > 1.0 m/s sustained 3 s | no | Hysteresis gap. |
| Ascent/descent hysteresis | 3 m sustained monotonic change | yes | Prevents noise inflating totals on flat ground. |
| Activity type model | one generic model, labeled via dropdown | — | Simpler than per-type subclasses; revisit in v2. |
| Acquire Benchmark | **optional** before start | — | Too heavy to mandate; prompted as recommended. |
| Calibrate Barometer | benchmark persisted across launches (SharedPreferences). Stale on START if (a) no benchmark exists, (b) > 1 h old, or (c) current location > 100 ft from where it was taken. If stale but within 100 ft, offer a fast barometer-only refresh; otherwise a full re-benchmark. | — | Weather drift stales QNH within hours; moving > 100 ft invalidates the stored elevation; the fast path saves time when the user is still at the same spot. |
| Theme | outdoor earth-tones dark — forest green primary, warm amber accents, dark slate background (see §2.1) | — | Low-glare in daylight. |
| Storage units | SI (m, s, m/s) internally; converted at display | — | Simplest correct approach. |
| Crash recovery | dialog on launch: "Resume activity from HH:MM?" | — | Explicit over silent. |
| Default activity naming | `"{type} · YYYY-MM-DD HH:MM"` (e.g. `"Hike · 2026-04-19 14:32"`) | editable post-save | Sortable, terse. |
| Testing | unit tests for grade/ascent/GPX/Haversine; manual on-device for everything else | — | Covers the failure-prone math without a heavy instrumentation setup. |

### 2.1 Color Palette

Outdoor earth-tones, dark-first for battery efficiency on OLED and reduced glare in daylight.

| Role | Hex | Usage |
|---|---|---|
| Background (base) | `#141A15` | App window, behind all content |
| Surface (elevated) | `#1F2A22` | Cards, tiles, bottom sheets, notification body |
| Primary | `#52B788` | Primary buttons (START), active states, current-position marker |
| Primary dark | `#2D6A4F` | Pressed state, headers |
| Accent | `#E8A33D` | Waypoints, highlights, calibration callouts |
| Warning / descent | `#C85450` | Critical warnings, steep-descent indicator on elevation chart |
| Text primary | `#E8EDE4` | Main text |
| Text secondary | `#8B9689` | Labels, captions, secondary metrics |
| Divider | `#2A3830` | Hairlines, grid lines on charts |

Track polyline on map: `#52B788` with 70% opacity, 4 dp stroke. Ribbon fallback view: linear gradient from `#3B82B5` (cold / low) through `#E8A33D` (mid) to `#C85450` (hot / high) across the session's observed elevation range.

---

## 3. User Flows

### 3.1 First launch (onboarding)
1. Splash → brief screen explaining the three permissions and why.
2. Request `ACCESS_FINE_LOCATION`.
3. Educational screen for background location → request `ACCESS_BACKGROUND_LOCATION`.
4. Request `POST_NOTIFICATIONS` (Android 13+).
5. Land on Main screen.

### 3.2 Main screen (idle)
- Big **START** button.
- Secondary links: **Acquire Benchmark** (optional), **Calibrate Barometer** (enabled only after a benchmark).
- Top bar: gear icon (settings), clock icon (history + stats).

### 3.3 Start an activity
1. User may tap Acquire Benchmark → 60 s averaging + DEM lookups → result shown.
2. User may tap Calibrate Barometer → QNH computed and held for the session.
3. User taps START. If no benchmark acquired: soft prompt "Skip benchmark? Elevation will use first GPS fix."
4. Foreground service starts; notification appears; live tracking screen opens.

### 3.4 Live tracking screen
- Header: elapsed time (moving / total).
- Metric grid: distance, ascent, descent, current grade, max grade, min grade, current speed, avg speed.
- Map toggle (OSM or ribbon fallback).
- **Waypoint** button.
- **Pause** / **Stop** buttons.

### 3.5 Backgrounded tracking
- Status-bar notification: "Tromp · 2.4 km · 01:23:10" + Pause + Stop actions.
- Tap body → reopen live screen.
- Low-importance channel: no sound, no vibration, no heads-up.

### 3.6 Pause / Resume / Stop
- **Pause**: elapsed time keeps ticking; moving time / distance / climb / descent freeze; GPS rate optionally reduced (setting).
- **Resume**: re-enters live tracking.
- **Stop**: confirmation dialog → save → lands on Activity Detail.

### 3.7 Activity detail
- Header: name (editable), date, duration.
- Summary tiles: distance, ascent, descent, avg/max speed, avg/max/min grade.
- Elevation profile chart.
- Map of route (or ribbon fallback).
- Waypoints list.
- Actions: Share GPX / **View in external maps app** (launches the user's installed Google Maps or other maps app via a `geo:` intent, centered on the activity's start point) / Rename / Change type / Delete.

### 3.8 Stats dashboard
- Date-range selector (This week / This month / YTD / All time / Custom).
- Aggregate tiles: Activities, Distance, Ascent, Descent, Moving time.
- Per-type breakdown: same tiles split by Hike / Run / Bike / Walk / Other.
- Year-over-year row: selected period vs. the same period one year ago, with delta.
- Personal records: Longest, Most climb, Steepest.
- Distance-per-week bar chart.

### 3.9 Offline tile download
- Settings → **Downloaded Maps** → **Download new region**.
- Map opens; user pans/zooms to cover target area.
- Zoom-range slider with live MB estimate.
- Confirm → progress bar (cancelable, resumable via `WorkManager`).
- Cached regions list: name, size, coverage bounding box; swipe to delete.

---

## 4. Architecture

### 4.1 Module structure (single-module app)
```
app/
  ui/
    main/           entry, history list, stats
    tracking/       live screen, map view, fallback view
    detail/         activity detail, elevation profile chart
    offline/        offline tile manager
    settings/       settings panel
    onboarding/     permissions flow
  service/
    TrackingService     foreground service; orchestrates location + sensors + persistence
    TrackingNotifier    builds the ongoing notification
  tracking/
    TrackSession        in-memory live session
    GradeCalculator     rolling-window grade math
    AscentAccumulator   hysteresis-based climb/descent totals
    AutoPauseDetector   state machine
    QnhCalibrator       QNH inversion of the barometric formula
  location/
    LocationSource      wraps FusedLocationProviderClient
  sensors/
    BarometerSource     wraps TYPE_PRESSURE
  elevation/
    DemClient           USGS 3DEP + Open-Elevation (ported)
  data/
    db/                 Room entities, DAOs, database
    repo/               ActivityRepository, SettingsRepository
  map/
    osm/                osmdroid integration, tile cache management
    fallback/           2D ribbon Canvas view
  export/
    GpxWriter
  util/
    Units, Haversine, Time
```

### 4.2 Runtime shape
- **`TrackingService`** (foreground, `foregroundServiceType="location|dataSync"`) owns the active `TrackSession`. It receives location + barometer callbacks, feeds the derived-state computations, emits updates via a `StateFlow<TrackSnapshot>` that the UI observes (bound service), and flushes to Room every N seconds.
- UI layer uses ViewModels holding `StateFlow`s connected to the service (for live screens) or to Room directly (for history / detail / stats / settings).
- **Crash recovery**: service writes `active_session_id` to SharedPreferences on start, clears on clean stop. On launch, if the key is set and the corresponding session row has no `end_time`, offer recovery.

### 4.3 Location + elevation pipeline
1. `FusedLocationProviderClient` emits a `Location` at the configured interval.
2. **Accuracy filter**: drop if `horizontalAccuracy > threshold`.
3. **Distance step**: Haversine from last accepted fix to current; add to total.
4. **Elevation**:
   - If calibration active: `SensorManager.getAltitude(qnh, latestPressure)`.
   - Else: `Location.altitude`.
5. **Ascent/descent**: feed elevation into `AscentAccumulator` (hysteresis).
6. **Grade**: `GradeCalculator` buffers the last 20 m of (distance, elevation) pairs; reports `Δelev / Δdist × 100`.
7. Track max grade / min grade from the running grade output.
8. Emit snapshot → UI + periodic Room flush.

### 4.4 Offline fallback view
- Custom `View` rendering on a `Canvas`.
- Equirectangular projection centered on the track's bounding box (good enough at activity scales).
- Each segment colored by elevation, linearly mapped across the session's observed min/max.
- Gestures: pinch-to-zoom (`ScaleGestureDetector`), drag to pan, double-tap to reset, compass button toggles north-up / track-up.

---

## 5. Data Model (Room)

```kotlin
@Entity
data class ActivityEntity(
    @PrimaryKey val id: Long,          // epoch ms at session start
    val startTime: Long,
    val endTime: Long?,                 // null = in progress / orphaned
    val type: String,                   // "hike" | "run" | "bike" | "walk" | "other"
    val name: String?,
    val totalDistanceM: Double,
    val totalAscentM: Double,
    val totalDescentM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val maxGradePct: Double,
    val minGradePct: Double,
    val benchmarkElevM: Double?,        // from Acquire Benchmark, if used
    val qnhHpa: Double?,                // barometer calibration, if used
)

@Entity(primaryKeys = ["activityId", "seq"])
data class TrackPointEntity(
    val activityId: Long,
    val seq: Int,                       // monotonic within activity
    val time: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,                   // best available (calibrated baro or GPS)
    val gpsAltM: Double,                // raw GPS, for comparison/debug
    val pressureHpa: Double?,
    val horizAccM: Float,
    val speedMps: Float,
)

@Entity(primaryKeys = ["activityId", "seq"])
data class WaypointEntity(
    val activityId: Long,
    val seq: Int,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val note: String?,
)

@Entity
data class OfflineRegionEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val minLat: Double, val maxLat: Double,
    val minLon: Double, val maxLon: Double,
    val minZoom: Int, val maxZoom: Int,
    val tileBytesTotal: Long,
    val downloadedAt: Long,
)
```

Stats queries (aggregates) run over `ActivityEntity` using indexed `startTime`.

---

## 6. Key Algorithms

### 6.1 Ascent/descent with hysteresis
```
state: direction ∈ { UP, DOWN, FLAT }; anchorAlt
on new elevation e:
    delta = e − anchorAlt
    if |delta| < 3 m: keep current anchor
    else:
        newDir = delta > 0 ? UP : DOWN
        if newDir == direction:
            commit delta to total (ascent or descent); anchorAlt = e
        else:
            anchorAlt = e; direction = newDir  // reversal resets anchor
```
Threshold tuned for typical consumer barometer/GPS noise; configurable.

### 6.2 Grade (rolling window)
Maintain a ring buffer of `(cumulativeDist, elevation)`. On each fix:
- Append new pair.
- Trim head entries until oldest is within `window` (default 20 m) of newest.
- `currentGrade = (newest.elev − oldest.elev) / (newest.dist − oldest.dist) × 100`.
- If the window hasn't filled yet, report `"—"` — do not contribute to max/min.

### 6.3 QNH calibration (inherited)
`qnh = avgPressure / (1 − benchmarkElev / 44330)^5.255`

Live altitude: `altM = SensorManager.getAltitude(qnh, currentPressure)`.

### 6.4 Auto-pause
State machine: `MOVING → PAUSED` when `speed < 0.5 m/s` for 30 s continuous. `PAUSED → MOVING` when `speed > 1.0 m/s` sustained 3 s. Pause freezes moving-time + distance + ascent + descent; elapsed time continues.

### 6.5 Distance
Haversine between successive accepted fixes, with the accuracy filter rejecting fixes worse than the threshold.

---

## 7. Dependencies

| Library | Purpose |
|---|---|
| `org.osmdroid:osmdroid-android` | OSM rendering + offline tile cache |
| `androidx.room:room-runtime` + `room-ktx` + `room-compiler` (kapt) | Persistence |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` + `lifecycle-runtime-ktx` | ViewModel / StateFlow |
| `androidx.navigation:navigation-fragment-ktx` | Screen nav |
| `com.github.PhilJay:MPAndroidChart` | Elevation profile chart |
| `androidx.work:work-runtime-ktx` | Offline tile download (cancellable, resumable) |
| `play-services-location`, `kotlinx-coroutines-android` | GPS, concurrency |

---

## 8. Permissions Matrix

| Permission | When requested | Blocks feature if denied |
|---|---|---|
| `ACCESS_FINE_LOCATION` | First START | All tracking |
| `ACCESS_BACKGROUND_LOCATION` | After FINE granted, with educational screen | Pocket / screen-off tracking |
| `POST_NOTIFICATIONS` (API 33+) | Before first foreground service start | Ongoing notification (required to show the service) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | Manifest-only, auto-granted | — |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Manifest-only | DEM lookups, online map tiles |

---

## 9. Error & Edge Cases

- No GPS fix after 60 s of START: show "Still searching for GPS…" warning, keep trying.
- Barometer absent: Calibrate button disabled; elevation uses GPS; clearly indicated in UI.
- DEM lookup fails during benchmark: proceed with whichever source succeeded, or GPS mean if both fail.
- User denies background location: warn that tracking will stop when the screen locks; offer "Track with screen on only" mode.
- Clock jump (time-zone change, NTP correction): durations use `SystemClock.elapsedRealtime()`, not wall clock.
- OSM tile server unreachable: show cached tiles where available, otherwise route to fallback ribbon view.
- Zero-point activity (user starts and stops immediately): discard on save; don't litter history.
- Long activity (>12 h, >40k points): paginate track-point reads in detail view; decimate polyline to the current map zoom's resolution.

---

## 10. Testing Strategy

- **Unit tests** (JUnit + Kotlin): `GradeCalculator`, `AscentAccumulator`, `AutoPauseDetector`, `QnhCalibrator`, `Haversine`, `GpxWriter`. Pure logic, failure-prone, cheap.
- **Manual on-device**: everything else. Maintain a test protocol doc (e.g., "walk a known 1 km loop, verify distance is within 5% of Google Fit").
- No instrumentation or UI tests in v1.

---

## 11. Decision Log

All initial open questions resolved 2026-04-19.

| # | Question | Decision |
|---|---|---|
| 1 | App name | **Tromp** (originally decided as **TrekTracker** 2026-04-19; renamed to **Tromp** on 2026-04-23 to free up a distinct brand. `applicationId` / `namespace` became `com.comtekglobal.tromp`; four internal identifiers (SQLite DB filename `trektracker.db`, notification channel ID `trektracker.tracking`, the two SharedPreferences file names, and the `TrekDatabase` class) were held at their `trektracker*` values to preserve the upgrade path for existing sideloaded installs. The release keystore was originally on that preserved list but was rotated to the Tromp identity on 2026-04-24 (CN=Tromp, alias=tromp) while the install base was effectively one device — see CHANGELOG [1.12].) |
| 2 | Color palette | Outdoor earth-tones dark (forest green primary, warm amber accents, dark slate background) — see §2.1 for hex values |
| 3 | Ascent hysteresis | Single **3 m** threshold in all modes |
| 4 | Activity type in v1 | Single generic model with a type-label dropdown (Hike / Run / Bike / Walk / Other); all types tracked identically |
| 5 | Stats scope in v1 | Full: date-range tiles + per-type breakdowns + year-over-year comparison + personal records + distance-per-week bar chart |
| 6 | Default activity name | `"{type} · YYYY-MM-DD HH:MM"` — e.g. `"Hike · 2026-04-19 14:32"` |

The doc is now a build spec. Scaffolding next.
