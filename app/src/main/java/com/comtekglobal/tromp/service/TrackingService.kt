// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.hardware.SensorManager
import com.comtekglobal.tromp.data.db.ActivityEntity
import com.comtekglobal.tromp.data.db.TrackPointEntity
import com.comtekglobal.tromp.data.db.TrekDatabase
import com.comtekglobal.tromp.elevation.DemClient
import com.comtekglobal.tromp.export.CsvExportFiles
import com.comtekglobal.tromp.export.CsvWriter
import com.comtekglobal.tromp.location.LocationSource
import com.comtekglobal.tromp.sensors.BarometerSource
import com.comtekglobal.tromp.sensors.CompassSource
import com.comtekglobal.tromp.sensors.StepCounterSource
import com.comtekglobal.tromp.tracking.AscentAccumulator
import com.comtekglobal.tromp.tracking.AutoPauseDetector
import com.comtekglobal.tromp.tracking.AutoStopDetector
import com.comtekglobal.tromp.tracking.AutoStopTrimmer
import com.comtekglobal.tromp.tracking.BenchmarkSession
import com.comtekglobal.tromp.tracking.GradeCalculator
import com.comtekglobal.tromp.tracking.TrackPostProcessor
import com.comtekglobal.tromp.tracking.TrackSnapshot
import com.comtekglobal.tromp.tracking.TrackingSession
import com.comtekglobal.tromp.tracking.computeQnhHpa
import androidx.room.withTransaction
import com.comtekglobal.tromp.util.DebugLog
import com.comtekglobal.tromp.util.defaultActivityName
import com.comtekglobal.tromp.util.formatDuration
import com.comtekglobal.tromp.util.haversineMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the active tracking session. Subscribes to
 * LocationSource + BarometerSource + StepCounterSource, runs each accepted
 * fix through the distance / ascent / grade / auto-pause / auto-stop
 * pipeline, and emits a StateFlow<TrackSnapshot> for the UI. On stop, the
 * session is persisted to Room (`activity` + `track_point` tables).
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.comtekglobal.tromp.service.ACTION_START"
        const val ACTION_DISMISS_AUTO_STOP = "com.comtekglobal.tromp.service.ACTION_DISMISS_AUTO_STOP"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val EXTRA_TRIM_AFTER_MS = "trim_after_ms"
        /**
         * Quick Start mode flag. When true, the service subscribes to
         * barometer + compass even with no QNH yet, and runs in
         * deferred-fix mode (isAcquiringFix = true) until the first GPS
         * fix locks an elevation. See CONTEXT.md "Quick Start feature spec".
         */
        const val EXTRA_QUICK_START = "quick_start"

        /** DESIGN.md §5.2: drop GPS fixes with horizontal accuracy worse than this. */
        private const val ACCURACY_THRESHOLD_M: Float = 15.0f

        /**
         * Fallback QNH when a Quick-Start session is stopped before any fix
         * ever locks an elevation. ISA standard sea-level pressure. Absolute
         * altitudes are then meaningless, but the relative Δp → Δalt mapping
         * is correct, so AscentAccumulator still produces useful totals.
         */
        private const val DEFAULT_QNH_HPA: Double = 1013.25

        private val _snapshots = MutableStateFlow<TrackSnapshot?>(null)
        val snapshots: StateFlow<TrackSnapshot?> = _snapshots.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var locationJob: Job? = null
    private var barometerJob: Job? = null
    private var stepCounterJob: Job? = null
    private var compassJob: Job? = null
    private val ascent = AscentAccumulator()
    private val grade = GradeCalculator()
    private val autoPause = AutoPauseDetector()
    private val autoStop = AutoStopDetector()

    private var startElapsedMs: Long = 0L
    private var lastFixLat: Double? = null
    private var lastFixLon: Double? = null
    private var maxSpeedMps: Double = 0.0

    @Volatile
    private var lastPressureHpa: Double? = null

    @Volatile
    private var sessionQnhHpa: Double? = null

    @Volatile
    private var stepBaseline: Float? = null

    @Volatile
    private var sessionStepCount: Int = 0

    @Volatile
    private var isQuickStart: Boolean = false

    /**
     * Timestamped barometer readings collected during the deferred-fix gap.
     * Replayed through AscentAccumulator once QNH locks (or at stop with
     * default QNH if no fix ever arrived). Drained on lock; never grows
     * unbounded — gap is typically a few seconds to a couple minutes.
     */
    private val bufferedBaro: MutableList<Pair<Long, Double>> = mutableListOf()

    /**
     * Timestamped compass bearings collected during the gap. Currently
     * unconsumed (CONTEXT.md "Out of scope for v1.x"); a later version will
     * use these plus step deltas to back-project the actual start position
     * via dead reckoning. Discarded when the session ends.
     */
    private val bufferedCompass: MutableList<Pair<Long, Float>> = mutableListOf()

    /** Guards the elevation-cascade coroutine so only one runs at a time. */
    private val cascadeInFlight = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val type = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: "hike"
                val quickStart = intent.getBooleanExtra(EXTRA_QUICK_START, false)
                startTracking(type, quickStart)
            }
            TrackingNotifier.ACTION_PAUSE -> updatePaused(true)
            TrackingNotifier.ACTION_RESUME -> updatePaused(false)
            TrackingNotifier.ACTION_STOP -> {
                val trim = intent.getLongExtra(EXTRA_TRIM_AFTER_MS, -1L).takeIf { it >= 0L }
                stopTracking(trimAfterMs = trim)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_AUTO_STOP -> dismissAutoStop()
        }
        return START_STICKY
    }

    private fun startTracking(type: String, quickStart: Boolean = false) {
        DebugLog.init(this)
        DebugLog.log(
            "SVC",
            "startTracking type=$type qnh=${BenchmarkSession.qnhHpa} quickStart=$quickStart"
        )
        TrackingNotifier.ensureChannel(this)
        val activityId = System.currentTimeMillis()
        TrackingSession.reset()
        ascent.reset()
        grade.reset()
        autoPause.reset()
        lastFixLat = null
        lastFixLon = null
        maxSpeedMps = 0.0
        lastPressureHpa = null
        sessionQnhHpa = BenchmarkSession.qnhHpa
        stepBaseline = null
        sessionStepCount = 0
        autoStop.reset()
        startElapsedMs = android.os.SystemClock.elapsedRealtime()
        isQuickStart = quickStart
        synchronized(bufferedBaro) { bufferedBaro.clear() }
        synchronized(bufferedCompass) { bufferedCompass.clear() }
        cascadeInFlight.set(false)

        // Quick Start with no QNH yet → deferred-fix mode. Tracking runs
        // (elapsed/steps tick, baro+compass buffer for retroactive ascent +
        // future dead reckoning) but no track points are emitted and
        // distance/ascent stay zero until the first fix locks an elevation.
        val acquiringFix = isQuickStart && sessionQnhHpa == null
        val initial = TrackSnapshot.empty(activityId, type).copy(
            qnhHpa = sessionQnhHpa,
            isAcquiringFix = acquiringFix,
        )
        _snapshots.value = initial
        TrackingSession.lastSnapshot = initial

        val notification = TrackingNotifier.build(
            context = this,
            title = getString(com.comtekglobal.tromp.R.string.notif_tracking_title),
            body = getString(com.comtekglobal.tromp.R.string.notif_tracking_body_idle),
            isPaused = false,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TrackingNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(TrackingNotifier.NOTIFICATION_ID, notification)
        }

        tickerJob?.cancel()
        tickerJob = scope.launch1Hz { tickElapsed() }

        locationJob?.cancel()
        locationJob = LocationSource(this)
            .updates(intervalMs = 2_000L)
            .onEach { loc -> onLocationFix(loc) }
            .launchIn(scope)

        barometerJob?.cancel()
        val baro = BarometerSource(this)
        // Quick Start: subscribe even without QNH so deferred-mode fixes can
        // calibrate retroactively from the buffered samples. Regular Start
        // keeps the existing behavior (subscribe only when QNH is locked).
        barometerJob = if (baro.isAvailable && (sessionQnhHpa != null || isQuickStart)) {
            baro.readings()
                .onEach { reading ->
                    val p = reading.toDouble()
                    lastPressureHpa = p
                    if (_snapshots.value?.isAcquiringFix == true) {
                        synchronized(bufferedBaro) {
                            bufferedBaro.add(System.currentTimeMillis() to p)
                        }
                    }
                }
                .launchIn(scope)
        } else null

        // Compass is only useful during the deferred-fix gap (for future
        // dead-reckoning back-projection). Regular Start has no need; Quick
        // Start that already has QNH (modal succeeded) also has no gap.
        compassJob?.cancel()
        compassJob = if (acquiringFix) {
            val compass = CompassSource(this)
            if (compass.isAvailable) {
                compass.readings()
                    .onEach { bearingDeg ->
                        if (_snapshots.value?.isAcquiringFix == true) {
                            synchronized(bufferedCompass) {
                                bufferedCompass.add(System.currentTimeMillis() to bearingDeg)
                            }
                        }
                    }
                    .launchIn(scope)
            } else null
        } else null

        stepCounterJob?.cancel()
        val stepCounter = StepCounterSource(this)
        stepCounterJob = if (stepCounter.isAvailable) {
            stepCounter.readings()
                .onEach { raw ->
                    val base = stepBaseline ?: raw.also { stepBaseline = it }
                    sessionStepCount = (raw - base).toInt().coerceAtLeast(0)
                }
                .launchIn(scope)
        } else null
    }

    private fun onLocationFix(loc: android.location.Location) {
        val prev = _snapshots.value ?: return
        if (prev.isPaused) return

        // Deferred-fix mode (Quick Start): no QNH yet. Try to lock an
        // elevation from this fix (DEM → loc.altitude → null). Don't write
        // a track point or update totals — the live pipeline takes over once
        // the cascade succeeds. Re-armed via cascadeInFlight so the next
        // fix can retry if this one fails.
        if (prev.isAcquiringFix) {
            if (loc.hasAccuracy() && loc.accuracy <= ACCURACY_THRESHOLD_M) {
                tryStartCascade(loc)
            } else {
                DebugLog.log(
                    "FIX",
                    "ACQ-REJECT acc=${"%.1f".format(loc.accuracy)} hasAcc=${loc.hasAccuracy()}"
                )
            }
            return
        }

        // DESIGN.md §4.3 step 2: drop fixes with horizontal accuracy worse than
        // the threshold. Prevents noisy fixes (tunnels, canyons, cold-start
        // scatter) from inflating distance/ascent. hasAccuracy() is effectively
        // always true from Fused but we guard to be safe; no-accuracy fixes
        // are treated as untrustworthy and dropped.
        if (!loc.hasAccuracy() || loc.accuracy > ACCURACY_THRESHOLD_M) {
            DebugLog.log(
                "FIX",
                "REJECT acc=${"%.1f".format(loc.accuracy)} hasAcc=${loc.hasAccuracy()}"
            )
            return
        }

        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val nowMs = System.currentTimeMillis()

        // DESIGN.md §6.4: feed every accepted fix into the auto-pause state
        // machine. While PAUSED, downstream accumulators are skipped so a
        // stopped user doesn't pile up phantom distance / ascent from GPS
        // jitter. autoStop still runs — RETURNED_HOME explicitly wants the
        // low-speed-near-start signal an auto-pause produces.
        autoPause.onSample(nowMs, speed)
        val autoPaused = autoPause.state == AutoPauseDetector.State.PAUSED

        val prevLat = lastFixLat
        val prevLon = lastFixLon
        val addedDistance = if (autoPaused || prevLat == null || prevLon == null) {
            0.0
        } else {
            haversineMeters(prevLat, prevLon, loc.latitude, loc.longitude)
        }
        lastFixLat = loc.latitude
        lastFixLon = loc.longitude

        // DESIGN.md §4.3 step 4: prefer barometric altitude via calibrated QNH
        // when available; fall back to GPS altitude otherwise.
        val gpsAlt: Double? = if (loc.hasAltitude()) loc.altitude else null
        val pressure = lastPressureHpa
        val qnh = sessionQnhHpa
        val baroAlt: Double? = if (qnh != null && pressure != null) {
            SensorManager.getAltitude(qnh.toFloat(), pressure.toFloat()).toDouble()
        } else null
        val chosenAlt: Double? = baroAlt ?: gpsAlt
        if (!autoPaused && chosenAlt != null) ascent.add(chosenAlt)

        if (!autoPaused && speed > maxSpeedMps) maxSpeedMps = speed

        val elapsed = android.os.SystemClock.elapsedRealtime() - startElapsedMs
        val totalDist = prev.totalDistanceM + addedDistance
        val avgSpeed = if (elapsed > 0) totalDist / (elapsed / 1000.0) else 0.0

        // DESIGN.md §6.2: feed the rolling-window grade calculator with the
        // running cumulative distance + chosen altitude. currentGradePct
        // returns null until the window fills, which prevents early
        // under-sampled max/min pollution.
        val gradeReading: Double? = if (!autoPaused && chosenAlt != null) {
            grade.add(totalDist, chosenAlt)
            grade.currentGradePct()
        } else null
        val nextMaxGrade = if (gradeReading != null) {
            maxOf(prev.maxGradePct, gradeReading)
        } else prev.maxGradePct
        val nextMinGrade = if (gradeReading != null) {
            minOf(prev.minGradePct, gradeReading)
        } else prev.minGradePct

        // Feed the auto-stop detector; latch the signal so the UI shows
        // exactly one prompt per trigger. The latch clears via
        // ACTION_DISMISS_AUTO_STOP (user said "Keep going") or via stop.
        val signal = autoStop.feed(
            AutoStopDetector.Sample(
                tMs = nowMs,
                lat = loc.latitude,
                lon = loc.longitude,
                speedMps = speed,
            )
        )
        val reason = signal?.reason ?: prev.autoStopReason
        val trimAfter = signal?.trimAfterMs ?: prev.autoStopTrimAfterMs

        val tel = autoStop.telemetry
        DebugLog.log(
            "FIX",
            ("lat=%.6f lon=%.6f spd=%.2f acc=%.1f alt=%.1f " +
                "autoPaused=%s grade=%s " +
                "mean=%.2f spikes=%d spikeArmed=%s " +
                "leftHome=%s homeArmed=%s dist=%.1f dur=%ds " +
                "signal=%s")
                .format(
                    loc.latitude, loc.longitude, speed, loc.accuracy,
                    if (loc.hasAltitude()) loc.altitude else Double.NaN,
                    autoPaused, gradeReading?.let { "%.1f".format(it) } ?: "-",
                    tel.trailingMeanMps, tel.consecutiveSpikes, tel.spikeArmed,
                    tel.leftHomeOnce, tel.homeArmed, tel.distFromStartM,
                    tel.durationMs / 1000L,
                    signal?.reason?.name ?: "-",
                )
        )

        val next = prev.copy(
            isAutoPaused = autoPaused,
            lat = loc.latitude,
            lon = loc.longitude,
            elevationM = chosenAlt ?: prev.elevationM,
            horizontalAccuracyM = loc.accuracy,
            speedMps = speed,
            totalDistanceM = totalDist,
            totalAscentM = ascent.totalAscentM,
            totalDescentM = ascent.totalDescentM,
            currentGradePct = gradeReading,
            maxGradePct = nextMaxGrade,
            minGradePct = nextMinGrade,
            avgSpeedMps = avgSpeed,
            maxSpeedMps = maxSpeedMps,
            elapsedMs = elapsed,
            pressureHpa = pressure,
            stepCount = sessionStepCount,
            autoStopReason = reason,
            autoStopTrimAfterMs = trimAfter,
        )
        _snapshots.value = next
        TrackingSession.lastSnapshot = next

        TrackingSession.append(
            TrackingSession.Point(
                lat = loc.latitude,
                lon = loc.longitude,
                elevM = chosenAlt,
                gpsElevM = gpsAlt,
                pressureHpa = pressure,
                horizAccM = loc.accuracy,
                speedMps = if (loc.hasSpeed()) loc.speed else 0f,
                bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                cumStepCount = sessionStepCount,
                isAutoPaused = autoPaused,
                tMs = System.currentTimeMillis(),
            )
        )
        refreshNotification(next)
    }

    /**
     * Quick Start deferred-mode: kick off a one-at-a-time cascade
     * (DEM lookup → loc.altitude → null) for the given fix. On success the
     * coroutine calls [onElevationLocked]; on failure cascadeInFlight is
     * released so the next accepted fix can retry. Per Dan's call (2026-05-05)
     * we retry on every fix until it succeeds — fixes without altitude are
     * vanishingly rare in practice but we want robust handling when they
     * happen.
     */
    private fun tryStartCascade(loc: android.location.Location) {
        if (!cascadeInFlight.compareAndSet(false, true)) return
        val capturedLat = loc.latitude
        val capturedLon = loc.longitude
        val capturedAcc = loc.accuracy
        val capturedGpsAlt: Double? = if (loc.hasAltitude()) loc.altitude else null
        scope.launch(Dispatchers.IO) {
            var locked = false
            try {
                DebugLog.log(
                    "ACQ",
                    "cascade start lat=%.6f lon=%.6f gpsAlt=%s"
                        .format(capturedLat, capturedLon, capturedGpsAlt?.let { "%.1f".format(it) } ?: "-")
                )
                val dem = runCatching { DemClient.lookup(capturedLat, capturedLon) }
                    .onFailure { DebugLog.log("ACQ", "DEM threw ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
                val demElev = dem?.best
                val demSource = dem?.source
                val bestElev = demElev ?: capturedGpsAlt
                val bestSource = demSource ?: if (capturedGpsAlt != null) "GPS" else null
                DebugLog.log(
                    "ACQ",
                    "cascade result dem=${demElev?.let { "%.1f".format(it) } ?: "null"} " +
                        "src=${demSource ?: "-"} chosen=${bestElev?.let { "%.1f".format(it) } ?: "null"}"
                )
                if (bestElev != null && bestSource != null) {
                    onElevationLocked(capturedLat, capturedLon, capturedAcc, bestElev, bestSource)
                    locked = true
                }
            } finally {
                // Release only if we didn't lock — when locked, onElevationLocked
                // already flipped isAcquiringFix=false so this guard is no longer
                // needed and clearing it can't enable a stray re-cascade.
                if (!locked) cascadeInFlight.set(false)
            }
        }
    }

    /**
     * Cascade succeeded. Calibrate QNH from the earliest buffered baro
     * sample (closest in time to the user's tap), replay the buffer through
     * AscentAccumulator so retroactive ascent for the deferred-fix gap
     * counts toward totals, populate BenchmarkSession in-memory (never
     * `.save()`'d — Quick Start benchmarks are session-only by design;
     * CONTEXT.md), and flip isAcquiringFix=false so the next fix flows
     * through the regular pipeline.
     */
    private fun onElevationLocked(
        lat: Double, lon: Double, accM: Float,
        elevM: Double, source: String,
    ) {
        val tapBaro: Double? = synchronized(bufferedBaro) {
            bufferedBaro.firstOrNull()?.second
        } ?: lastPressureHpa
        val qnh: Double? = if (tapBaro != null) computeQnhHpa(tapBaro, elevM) else null
        sessionQnhHpa = qnh
        if (qnh != null) {
            // Replay buffered pressures in order. AscentAccumulator's
            // hysteresis takes care of noise; the first sample seeds the
            // anchor, subsequent samples produce real Δ.
            val buffer = synchronized(bufferedBaro) {
                val copy = bufferedBaro.toList()
                bufferedBaro.clear()
                copy
            }
            for ((_, p) in buffer) {
                val alt = SensorManager.getAltitude(qnh.toFloat(), p.toFloat()).toDouble()
                ascent.add(alt)
            }
            DebugLog.log(
                "ACQ",
                "lock qnh=%.2f elev=%.1f src=%s replayedBaro=%d ascent=%.1f desc=%.1f"
                    .format(qnh, elevM, source, buffer.size, ascent.totalAscentM, ascent.totalDescentM)
            )
        } else {
            DebugLog.log("ACQ", "lock no-baro qnh=null src=$source elev=%.1f".format(elevM))
        }

        BenchmarkSession.current = BenchmarkSession.Benchmark(
            lat = lat, lon = lon, elevM = elevM,
            source = "$source (quick)",
            horizAccM = accM.toDouble(),
            fixCount = 1,
            baroAvgHpa = tapBaro,
            baroSampleCount = 0,
            acquiredAtMs = System.currentTimeMillis(),
        )
        BenchmarkSession.qnhHpa = qnh
        // Note: not calling BenchmarkSession.save() — Quick Start benchmarks
        // are session-only and shouldn't outlive the app process.

        val prev = _snapshots.value
        if (prev != null) {
            val updated = prev.copy(
                isAcquiringFix = false,
                qnhHpa = qnh,
                totalAscentM = ascent.totalAscentM,
                totalDescentM = ascent.totalDescentM,
            )
            _snapshots.value = updated
            TrackingSession.lastSnapshot = updated
        }
        cascadeInFlight.set(false)
    }

    private fun tickElapsed() {
        val prev = _snapshots.value ?: return
        if (prev.isPaused) return
        val elapsed = android.os.SystemClock.elapsedRealtime() - startElapsedMs
        // movingMs advances at 1 s resolution while the user is neither
        // manually paused nor auto-paused. Manual pause already short-circuits
        // above; auto-pause is decided by AutoPauseDetector via location fixes.
        val movingDelta = if (prev.isAutoPaused) 0L else 1000L
        val next = prev.copy(
            elapsedMs = elapsed,
            movingMs = prev.movingMs + movingDelta,
        )
        _snapshots.value = next
        TrackingSession.lastSnapshot = next
        refreshNotification(next)
    }

    private fun updatePaused(paused: Boolean) {
        val prev = _snapshots.value ?: return
        val next = prev.copy(isPaused = paused)
        _snapshots.value = next
        TrackingSession.lastSnapshot = next
        refreshNotification(next)
    }

    private fun refreshNotification(snap: TrackSnapshot) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        val body = "${formatDuration(snap.elapsedMs)} · ${
            "%.2f km".format(snap.totalDistanceM / 1000.0)
        }"
        val n = TrackingNotifier.build(
            context = this,
            title = getString(com.comtekglobal.tromp.R.string.notif_tracking_title),
            body = body,
            isPaused = snap.isPaused,
        )
        nm.notify(TrackingNotifier.NOTIFICATION_ID, n)
    }

    private fun dismissAutoStop() {
        val prev = _snapshots.value ?: return
        DebugLog.log("SVC", "dismissAutoStop reason=${prev.autoStopReason}")
        val next = prev.copy(autoStopReason = null, autoStopTrimAfterMs = null)
        _snapshots.value = next
        TrackingSession.lastSnapshot = next
    }

    private fun stopTracking(trimAfterMs: Long? = null) {
        DebugLog.log("SVC", "stopTracking trimAfterMs=${trimAfterMs ?: "-"}")
        tickerJob?.cancel()
        tickerJob = null
        locationJob?.cancel()
        locationJob = null
        barometerJob?.cancel()
        barometerJob = null
        stepCounterJob?.cancel()
        stepCounterJob = null
        compassJob?.cancel()
        compassJob = null

        // Quick Start was never able to lock an elevation. Best-effort:
        // walk the buffered baro samples through AscentAccumulator using
        // the ISA default QNH — the absolute altitude is meaningless but
        // pressure deltas still produce correct Δaltitudes, so the totals
        // reflect any climbing the user did during the gap. No track
        // points to attach to; activity row is saved with elapsed/steps/
        // best-effort ascent only (CONTEXT.md "Quick Start feature spec").
        val rawPre = _snapshots.value
        if (rawPre != null && rawPre.isAcquiringFix) {
            val buffer = synchronized(bufferedBaro) {
                val copy = bufferedBaro.toList()
                bufferedBaro.clear()
                copy
            }
            if (buffer.isNotEmpty()) {
                for ((_, p) in buffer) {
                    val alt = SensorManager.getAltitude(
                        DEFAULT_QNH_HPA.toFloat(), p.toFloat()
                    ).toDouble()
                    ascent.add(alt)
                }
                DebugLog.log(
                    "ACQ",
                    "stop-without-lock defaultQnh replayed=%d ascent=%.1f desc=%.1f"
                        .format(buffer.size, ascent.totalAscentM, ascent.totalDescentM)
                )
                val updated = rawPre.copy(
                    isAcquiringFix = false,
                    totalAscentM = ascent.totalAscentM,
                    totalDescentM = ascent.totalDescentM,
                )
                _snapshots.value = updated
                TrackingSession.lastSnapshot = updated
            }
        }

        val raw = _snapshots.value
        val (finalSnap, pointsForPersist) = if (raw != null && trimAfterMs != null) {
            val t = AutoStopTrimmer.trim(TrackingSession.points(), trimAfterMs)
            val lastKeptTs = t.keptPoints.lastOrNull()?.tMs
            val trimmedElapsed = if (lastKeptTs != null) lastKeptTs - raw.activityId else raw.elapsedMs
            val trimmed = raw.copy(
                totalDistanceM = t.totalDistanceM,
                totalAscentM = t.totalAscentM,
                totalDescentM = t.totalDescentM,
                elapsedMs = trimmedElapsed.coerceAtLeast(0L),
                autoStopReason = null,
                autoStopTrimAfterMs = null,
            )
            trimmed to t.keptPoints
        } else {
            (raw?.copy(autoStopReason = null, autoStopTrimAfterMs = null)) to TrackingSession.points()
        }

        TrackingSession.lastSnapshot = finalSnap
        _snapshots.value = null
        if (finalSnap != null) persistActivity(finalSnap, pointsForPersist)
    }

    private fun persistActivity(snap: TrackSnapshot, points: List<TrackingSession.Point>) {
        val db = TrekDatabase.get(this)
        val benchmark = BenchmarkSession.current
        val entity = ActivityEntity(
            id = snap.activityId,
            startTime = snap.activityId,
            endTime = System.currentTimeMillis(),
            type = snap.type,
            name = defaultActivityName(snap.type, snap.activityId),
            totalDistanceM = snap.totalDistanceM,
            totalAscentM = snap.totalAscentM,
            totalDescentM = snap.totalDescentM,
            elapsedMs = snap.elapsedMs,
            movingMs = snap.movingMs,
            avgSpeedMps = snap.avgSpeedMps,
            maxSpeedMps = snap.maxSpeedMps,
            maxGradePct = if (snap.maxGradePct == Double.NEGATIVE_INFINITY) 0.0 else snap.maxGradePct,
            minGradePct = if (snap.minGradePct == Double.POSITIVE_INFINITY) 0.0 else snap.minGradePct,
            benchmarkElevM = benchmark?.elevM,
            qnhHpa = snap.qnhHpa,
            stepCount = snap.stepCount,
        )
        val pointRows = points.mapIndexed { index, p ->
            TrackPointEntity(
                activityId = snap.activityId,
                seq = index,
                time = p.tMs,
                lat = p.lat,
                lon = p.lon,
                altM = p.elevM ?: p.gpsElevM ?: 0.0,
                gpsAltM = p.gpsElevM ?: 0.0,
                pressureHpa = p.pressureHpa,
                horizAccM = p.horizAccM,
                speedMps = p.speedMps,
                bearingDeg = p.bearingDeg,
                cumStepCount = p.cumStepCount,
                isAutoPaused = p.isAutoPaused,
            )
        }
        // Persist synchronously: stopTracking is called from onStartCommand
        // (main thread) and is followed immediately by stopSelf() → onDestroy()
        // → scope.cancel(). A `scope.launch { ... }` here races that
        // cancellation; in v1.14 and earlier, cancellation could land between
        // activities().upsert(entity) and trackPoints().insertAll(pointRows),
        // leaving the activity row in the DB but no points (visible as a
        // History entry with totals where Map and Export CSV were both inert
        // because there were zero rows in track_point). Wrapped in a
        // withTransaction so the two writes are atomic — either the whole
        // session lands or neither does. runBlocking briefly stalls the main
        // thread on a one-time stop event; Room's batch insert under WAL is
        // fast enough (tens of ms even for thousands of points) that the hitch
        // is imperceptible vs. the cost of losing the entire track.
        runBlocking {
            db.withTransaction {
                db.activities().upsert(entity)
                if (pointRows.isNotEmpty()) db.trackPoints().insertAll(pointRows)
            }
            // After the activity + points are durable, auto-generate the
            // diagnostic pre-trim and post-trim CSVs so they land "with the
            // hike" without the user having to remember to tap Export.
            // Synchronous (still inside runBlocking) for the same reason
            // the persist itself is — we mustn't return to onStartCommand
            // before the work finishes or onDestroy()/scope.cancel() can
            // race the file writes (v1.14.1 lesson). File writes for a
            // few-thousand-row CSV take tens of ms; cheap.
            if (pointRows.isNotEmpty()) {
                runCatching {
                    writeDiagnosticCsvs(entity, pointRows)
                }.onFailure {
                    DebugLog.log("CSV", "auto-export failed: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
    }

    private fun writeDiagnosticCsvs(
        activity: ActivityEntity,
        points: List<TrackPointEntity>,
    ) {
        val samples = points.map {
            TrackPostProcessor.Sample(
                tMs = it.time,
                speedMps = it.speedMps.toDouble(),
                altM = it.altM,
                cumStepCount = it.cumStepCount,
            )
        }
        val classifications = TrackPostProcessor.classify(samples)
        val files = CsvExportFiles.forActivity(this, activity.id)
        files.dir.mkdirs()
        files.preTrim.bufferedWriter().use {
            CsvWriter.write(it, activity, points, classifications, includeStates = null)
        }
        files.postTrim.bufferedWriter().use {
            CsvWriter.write(
                it, activity, points, classifications,
                includeStates = setOf(
                    TrackPostProcessor.State.ACTIVE,
                    TrackPostProcessor.State.CLAMBERING,
                ),
            )
        }
        val droppedDawdling = classifications.count { it.state == TrackPostProcessor.State.DAWDLING }
        DebugLog.log(
            "CSV",
            "auto-export id=${activity.id} pretrim=${files.preTrim.name} " +
                "posttrim=${files.postTrim.name} kept=${points.size - droppedDawdling} " +
                "dawdling=$droppedDawdling"
        )
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        locationJob?.cancel()
        barometerJob?.cancel()
        stepCounterJob?.cancel()
        compassJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

}

/** Runs `block` once per second on the scope's dispatcher until cancelled. */
private fun CoroutineScope.launch1Hz(block: suspend () -> Unit): Job =
    launch {
        while (true) {
            delay(1000L)
            block()
        }
    }
