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
import com.comtekglobal.tromp.location.LocationSource
import com.comtekglobal.tromp.sensors.BarometerSource
import com.comtekglobal.tromp.sensors.StepCounterSource
import com.comtekglobal.tromp.tracking.AscentAccumulator
import com.comtekglobal.tromp.tracking.AutoStopDetector
import com.comtekglobal.tromp.tracking.AutoStopTrimmer
import com.comtekglobal.tromp.tracking.BenchmarkSession
import com.comtekglobal.tromp.tracking.TrackSnapshot
import com.comtekglobal.tromp.tracking.TrackingSession
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

/**
 * Foreground service that owns the active tracking session. Subscribes to
 * LocationSource, feeds fixes through distance + ascent accumulators, and
 * emits a StateFlow<TrackSnapshot> for the UI. Track points are buffered in
 * TrackingSession for the summary + map screens. Room persistence is a
 * later pass.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.comtekglobal.tromp.service.ACTION_START"
        const val ACTION_DISMISS_AUTO_STOP = "com.comtekglobal.tromp.service.ACTION_DISMISS_AUTO_STOP"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val EXTRA_TRIM_AFTER_MS = "trim_after_ms"

        /** DESIGN.md §5.2: drop GPS fixes with horizontal accuracy worse than this. */
        private const val ACCURACY_THRESHOLD_M: Float = 15.0f

        private val _snapshots = MutableStateFlow<TrackSnapshot?>(null)
        val snapshots: StateFlow<TrackSnapshot?> = _snapshots.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var locationJob: Job? = null
    private var barometerJob: Job? = null
    private var stepCounterJob: Job? = null
    private val ascent = AscentAccumulator()
    private val autoStop = AutoStopDetector()

    private var startElapsedMs: Long = 0L
    private var lastFixLat: Double? = null
    private var lastFixLon: Double? = null
    private var maxSpeedMps: Double = 0.0

    @Volatile
    private var lastPressureHpa: Double? = null
    private var sessionQnhHpa: Double? = null

    @Volatile
    private var stepBaseline: Float? = null

    @Volatile
    private var sessionStepCount: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val type = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: "hike"
                startTracking(type)
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

    private fun startTracking(type: String) {
        DebugLog.init(this)
        DebugLog.log("SVC", "startTracking type=$type qnh=${BenchmarkSession.qnhHpa}")
        TrackingNotifier.ensureChannel(this)
        val activityId = System.currentTimeMillis()
        TrackingSession.reset()
        ascent.reset()
        lastFixLat = null
        lastFixLon = null
        maxSpeedMps = 0.0
        lastPressureHpa = null
        sessionQnhHpa = BenchmarkSession.qnhHpa
        stepBaseline = null
        sessionStepCount = 0
        autoStop.reset()
        startElapsedMs = android.os.SystemClock.elapsedRealtime()

        val initial = TrackSnapshot.empty(activityId, type).copy(
            qnhHpa = sessionQnhHpa,
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
        barometerJob = if (sessionQnhHpa != null && baro.isAvailable) {
            baro.readings()
                .onEach { lastPressureHpa = it.toDouble() }
                .launchIn(scope)
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

        val prevLat = lastFixLat
        val prevLon = lastFixLon
        val addedDistance = if (prevLat != null && prevLon != null) {
            haversineMeters(prevLat, prevLon, loc.latitude, loc.longitude)
        } else 0.0
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
        if (chosenAlt != null) ascent.add(chosenAlt)

        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        if (speed > maxSpeedMps) maxSpeedMps = speed

        val elapsed = android.os.SystemClock.elapsedRealtime() - startElapsedMs
        val totalDist = prev.totalDistanceM + addedDistance
        val avgSpeed = if (elapsed > 0) totalDist / (elapsed / 1000.0) else 0.0

        // Feed the auto-stop detector; latch the signal so the UI shows
        // exactly one prompt per trigger. The latch clears via
        // ACTION_DISMISS_AUTO_STOP (user said "Keep going") or via stop.
        val nowMs = System.currentTimeMillis()
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
                "mean=%.2f spikes=%d spikeArmed=%s " +
                "leftHome=%s homeArmed=%s dist=%.1f dur=%ds " +
                "signal=%s")
                .format(
                    loc.latitude, loc.longitude, speed, loc.accuracy,
                    if (loc.hasAltitude()) loc.altitude else Double.NaN,
                    tel.trailingMeanMps, tel.consecutiveSpikes, tel.spikeArmed,
                    tel.leftHomeOnce, tel.homeArmed, tel.distFromStartM,
                    tel.durationMs / 1000L,
                    signal?.reason?.name ?: "-",
                )
        )

        val next = prev.copy(
            lat = loc.latitude,
            lon = loc.longitude,
            elevationM = chosenAlt ?: prev.elevationM,
            horizontalAccuracyM = loc.accuracy,
            speedMps = speed,
            totalDistanceM = totalDist,
            totalAscentM = ascent.totalAscentM,
            totalDescentM = ascent.totalDescentM,
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
                tMs = System.currentTimeMillis(),
            )
        )
        refreshNotification(next)
    }

    private fun tickElapsed() {
        val prev = _snapshots.value ?: return
        if (prev.isPaused) return
        val elapsed = android.os.SystemClock.elapsedRealtime() - startElapsedMs
        val next = prev.copy(elapsedMs = elapsed)
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
                speedMps = 0f,
            )
        }
        scope.launch {
            db.activities().upsert(entity)
            if (pointRows.isNotEmpty()) db.trackPoints().insertAll(pointRows)
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        locationJob?.cancel()
        barometerJob?.cancel()
        stepCounterJob?.cancel()
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
