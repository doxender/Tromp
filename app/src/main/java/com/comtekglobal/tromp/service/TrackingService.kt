// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.comtekglobal.tromp.R
import com.comtekglobal.tromp.data.db.ActivityEntity
import com.comtekglobal.tromp.data.db.TrackPointEntity
import com.comtekglobal.tromp.data.db.TrekDatabase
import com.comtekglobal.tromp.elevation.DemClient
import com.comtekglobal.tromp.export.DiagnosticExportWorker
import com.comtekglobal.tromp.location.LocationSource
import com.comtekglobal.tromp.sensors.BarometerSource
import com.comtekglobal.tromp.sensors.StepCounterSource
import com.comtekglobal.tromp.tracking.AscentAccumulator
import com.comtekglobal.tromp.tracking.AutoPauseDetector
import com.comtekglobal.tromp.tracking.AutoStopDetector
import com.comtekglobal.tromp.tracking.BenchmarkSession
import com.comtekglobal.tromp.tracking.GradeCalculator
import com.comtekglobal.tromp.tracking.SessionStatsCalculator
import com.comtekglobal.tromp.tracking.TrackSnapshot
import com.comtekglobal.tromp.tracking.TrackingSession
import com.comtekglobal.tromp.tracking.computeQnhHpa
import com.comtekglobal.tromp.util.DebugLog
import com.comtekglobal.tromp.util.defaultActivityName
import com.comtekglobal.tromp.util.formatDuration
import com.comtekglobal.tromp.util.haversineMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the active tracking engine. Room is the durable
 * source of truth: an in-progress activity row is created at Start and points
 * are flushed in bounded batches every five seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.comtekglobal.tromp.service.ACTION_START"
        const val ACTION_RESUME_SESSION = "com.comtekglobal.tromp.service.ACTION_RESUME_SESSION"
        const val ACTION_FINISH_ORPHAN = "com.comtekglobal.tromp.service.ACTION_FINISH_ORPHAN"
        const val ACTION_DISMISS_AUTO_STOP =
            "com.comtekglobal.tromp.service.ACTION_DISMISS_AUTO_STOP"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val EXTRA_ACTIVITY_ID = "activity_id"
        const val EXTRA_TRIM_AFTER_MS = "trim_after_ms"
        const val EXTRA_QUICK_START = "quick_start"

        private const val ACCURACY_THRESHOLD_M = 15.0f
        private const val DEFAULT_QNH_HPA = 1013.25
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val DEFERRED_DEM_TIMEOUT_MS = 4_000L
        private const val MAX_BUFFERED_BARO_SAMPLES = 2_400

        private val _snapshots = MutableStateFlow<TrackSnapshot?>(null)
        val snapshots: StateFlow<TrackSnapshot?> = _snapshots.asStateFlow()

        private val _completedActivities = MutableSharedFlow<Long>(extraBufferCapacity = 1)
        val completedActivities: SharedFlow<Long> = _completedActivities.asSharedFlow()
    }

    private val trackingDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val serviceScope = CoroutineScope(SupervisorJob() + trackingDispatcher)
    private val flushMutex = Mutex()
    private val finalizing = AtomicBoolean(false)
    private val cascadeInFlight = AtomicBoolean(false)

    private var tickerJob: Job? = null
    private var locationJob: Job? = null
    private var barometerJob: Job? = null
    private var stepCounterJob: Job? = null
    private var flushJob: Job? = null
    private var restoreJob: Job? = null

    private val ascent = AscentAccumulator()
    private val grade = GradeCalculator()
    private val autoPause = AutoPauseDetector()
    private val autoStop = AutoStopDetector()

    private var elapsedBaseMs = 0L
    private var resumedAtElapsedMs = 0L
    private var lastFixLat: Double? = null
    private var lastFixLon: Double? = null
    private var maxSpeedMps = 0.0
    private var sessionName = ""
    private var sessionBenchmarkElevM: Double? = null
    private var nextSeq = 0
    private var stepOffset = 0

    @Volatile private var lastPressureHpa: Double? = null
    @Volatile private var sessionQnhHpa: Double? = null
    @Volatile private var stepBaseline: Float? = null
    @Volatile private var sessionStepCount = 0
    @Volatile private var isQuickStart = false

    private val pendingRows = mutableListOf<TrackPointEntity>()
    private val bufferedBaro = ArrayDeque<Pair<Long, Double>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (_snapshots.value == null && restoreJob?.isActive != true) {
                    val active = ActiveSessionStore.load(this)
                    if (active != null) {
                        enterForeground(getString(R.string.notif_tracking_recovering))
                        restoreSession(active.activityId, resume = true)
                    } else {
                        startTracking(
                            type = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: "hike",
                            quickStart = intent.getBooleanExtra(EXTRA_QUICK_START, false),
                        )
                    }
                }
            }

            ACTION_RESUME_SESSION -> {
                val id = intent.getLongExtra(EXTRA_ACTIVITY_ID, -1L)
                if (id >= 0L && _snapshots.value == null) {
                    enterForeground(getString(R.string.notif_tracking_recovering))
                    restoreSession(id, resume = true)
                }
            }

            ACTION_FINISH_ORPHAN -> {
                val id = intent.getLongExtra(EXTRA_ACTIVITY_ID, -1L)
                if (id >= 0L && _snapshots.value == null) {
                    restoreSession(id, resume = false)
                }
            }

            TrackingNotifier.ACTION_PAUSE -> updatePaused(true)
            TrackingNotifier.ACTION_RESUME -> updatePaused(false)
            TrackingNotifier.ACTION_STOP -> {
                val trim = intent.getLongExtra(EXTRA_TRIM_AFTER_MS, -1L)
                    .takeIf { it >= 0L }
                requestStop(trim)
                return START_NOT_STICKY
            }

            ACTION_DISMISS_AUTO_STOP -> dismissAutoStop()
            null -> {
                val active = ActiveSessionStore.load(this)
                if (active != null && _snapshots.value == null) {
                    enterForeground(getString(R.string.notif_tracking_recovering))
                    restoreSession(active.activityId, resume = true)
                } else if (active == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun startTracking(type: String, quickStart: Boolean) {
        if (_snapshots.value != null) return
        DebugLog.init(this)
        TrackingNotifier.ensureChannel(this)

        val activityId = System.currentTimeMillis()
        isQuickStart = quickStart
        sessionQnhHpa = BenchmarkSession.qnhHpa
        sessionBenchmarkElevM = BenchmarkSession.current?.elevM
        sessionName = defaultActivityName(type, activityId)
        elapsedBaseMs = 0L
        resumedAtElapsedMs = SystemClock.elapsedRealtime()
        resetEngine()

        val acquiringFix = quickStart && sessionQnhHpa == null
        val initial = TrackSnapshot.empty(activityId, type).copy(
            qnhHpa = sessionQnhHpa,
            isAcquiringFix = acquiringFix,
        )
        TrackingSession.replace(emptyList(), initial)
        _snapshots.value = initial
        ActiveSessionStore.save(
            this,
            ActiveSessionStore.State(activityId, quickStart, acquiringFix),
        )
        enterForeground(getString(R.string.notif_tracking_body_idle))
        startCollectors(acquiringFix)
        serviceScope.launch { flushPending() }
    }

    private fun restoreSession(activityId: Long, resume: Boolean) {
        if (restoreJob?.isActive == true) return
        restoreJob = serviceScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val db = TrekDatabase.get(this@TrackingService)
                val activity = db.activities().byId(activityId)
                val rows = db.trackPoints().forActivity(activityId)
                activity to rows
            }
            val activity = restored.first
            if (activity == null || activity.endTime != null) {
                ActiveSessionStore.clear(this@TrackingService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val points = restored.second.map { it.toPoint() }
            val activeState = ActiveSessionStore.load(this@TrackingService)
            isQuickStart = activeState?.quickStart ?: false
            sessionQnhHpa = activity.qnhHpa
            sessionBenchmarkElevM = activity.benchmarkElevM
            sessionName = activity.name ?: defaultActivityName(activity.type, activity.startTime)
            nextSeq = points.size
            elapsedBaseMs = maxOf(
                activity.elapsedMs,
                System.currentTimeMillis() - activity.startTime,
            )
            resumedAtElapsedMs = SystemClock.elapsedRealtime()

            val replay = SessionStatsCalculator.calculate(
                activity.startTime,
                points,
                points.lastOrNull()?.tMs ?: activity.startTime + activity.elapsedMs,
            )
            val last = points.lastOrNull()
            val snapshot = TrackSnapshot(
                activityId = activity.id,
                type = activity.type,
                isPaused = false,
                isAutoPaused = last?.isAutoPaused ?: false,
                lat = last?.lat,
                lon = last?.lon,
                elevationM = last?.elevM,
                horizontalAccuracyM = last?.horizAccM,
                speedMps = last?.speedMps?.toDouble() ?: 0.0,
                totalDistanceM = if (points.isEmpty()) activity.totalDistanceM
                    else replay.totalDistanceM,
                totalAscentM = if (points.isEmpty()) activity.totalAscentM
                    else replay.totalAscentM,
                totalDescentM = if (points.isEmpty()) activity.totalDescentM
                    else replay.totalDescentM,
                currentGradePct = null,
                maxGradePct = if (points.isEmpty()) activity.maxGradePct
                    else replay.maxGradePct,
                minGradePct = if (points.isEmpty()) activity.minGradePct
                    else replay.minGradePct,
                avgSpeedMps = if (points.isEmpty()) activity.avgSpeedMps
                    else replay.avgSpeedMps,
                maxSpeedMps = if (points.isEmpty()) activity.maxSpeedMps
                    else replay.maxSpeedMps,
                elapsedMs = elapsedBaseMs,
                movingMs = activity.movingMs,
                pressureHpa = last?.pressureHpa,
                qnhHpa = activity.qnhHpa,
                stepCount = maxOf(activity.stepCount, replay.stepCount),
                isAcquiringFix = activeState?.acquiringFix ?: false,
            )
            rebuildEngine(points, snapshot)
            TrackingSession.replace(points, snapshot)
            _snapshots.value = snapshot

            if (resume) {
                startCollectors(snapshot.isAcquiringFix)
                refreshNotification(snapshot)
            } else {
                requestStop(trimAfterMs = null)
            }
        }
    }

    private fun resetEngine() {
        ascent.reset()
        grade.reset()
        autoPause.reset()
        autoStop.reset()
        lastFixLat = null
        lastFixLon = null
        maxSpeedMps = 0.0
        lastPressureHpa = null
        stepBaseline = null
        sessionStepCount = 0
        stepOffset = 0
        nextSeq = 0
        cascadeInFlight.set(false)
        synchronized(pendingRows) { pendingRows.clear() }
        synchronized(bufferedBaro) { bufferedBaro.clear() }
    }

    private fun rebuildEngine(points: List<TrackingSession.Point>, snapshot: TrackSnapshot) {
        resetEngine()
        var cumulativeDistance = 0.0
        var previous: TrackingSession.Point? = null
        for (point in points) {
            val prior = previous
            if (prior != null && !point.isAutoPaused) {
                cumulativeDistance += haversineMeters(
                    prior.lat, prior.lon, point.lat, point.lon,
                )
            }
            if (!point.isAutoPaused) {
                point.elevM?.let {
                    ascent.add(it)
                    grade.add(cumulativeDistance, it)
                }
            }
            previous = point
        }
        val last = points.lastOrNull()
        lastFixLat = last?.lat
        lastFixLon = last?.lon
        lastPressureHpa = last?.pressureHpa
        maxSpeedMps = snapshot.maxSpeedMps
        sessionStepCount = snapshot.stepCount
        stepOffset = snapshot.stepCount
        nextSeq = points.size
    }

    private fun startCollectors(acquiringFix: Boolean) {
        stopCollectors()
        tickerJob = serviceScope.launch1Hz { tickElapsed() }

        locationJob = LocationSource(this)
            .updates(intervalMs = 2_000L)
            .onEach(::onLocationFix)
            .catch { DebugLog.log("LOC", "updates stopped: ${it.javaClass.simpleName}") }
            .launchIn(serviceScope)

        val barometer = BarometerSource(this)
        barometerJob = if (
            barometer.isAvailable && (sessionQnhHpa != null || isQuickStart)
        ) {
            barometer.readings()
                .onEach { reading ->
                    val pressure = reading.toDouble()
                    lastPressureHpa = pressure
                    if (_snapshots.value?.isAcquiringFix == true) {
                        synchronized(bufferedBaro) {
                            if (bufferedBaro.size == MAX_BUFFERED_BARO_SAMPLES) {
                                bufferedBaro.removeFirst()
                            }
                            bufferedBaro.addLast(System.currentTimeMillis() to pressure)
                        }
                    }
                }
                .launchIn(serviceScope)
        } else null

        val canCountSteps =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACTIVITY_RECOGNITION,
                ) == PackageManager.PERMISSION_GRANTED
        val stepCounter = StepCounterSource(this)
        stepCounterJob = if (canCountSteps && stepCounter.isAvailable) {
            stepCounter.readings()
                .onEach { raw ->
                    val base = stepBaseline ?: raw.also { stepBaseline = it }
                    sessionStepCount =
                        stepOffset + (raw - base).toInt().coerceAtLeast(0)
                }
                .launchIn(serviceScope)
        } else null

        flushJob = serviceScope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushPending()
            }
        }

        if (!acquiringFix) synchronized(bufferedBaro) { bufferedBaro.clear() }
    }

    private fun stopCollectors() {
        tickerJob?.cancel()
        locationJob?.cancel()
        barometerJob?.cancel()
        stepCounterJob?.cancel()
        flushJob?.cancel()
        tickerJob = null
        locationJob = null
        barometerJob = null
        stepCounterJob = null
        flushJob = null
    }

    private fun onLocationFix(location: Location) {
        val current = _snapshots.value ?: return
        if (current.isPaused || finalizing.get()) return
        if (!location.hasAccuracy() || location.accuracy > ACCURACY_THRESHOLD_M) {
            DebugLog.log("FIX", "rejected accuracy=${location.accuracy}")
            return
        }

        if (current.isAcquiringFix) {
            val gpsElevation = location.altitude.takeIf { location.hasAltitude() }
            if (gpsElevation != null) {
                onElevationLocked(
                    location.latitude,
                    location.longitude,
                    location.accuracy,
                    gpsElevation,
                    "GPS",
                )
                processAcceptedLocation(location)
            } else {
                tryStartCascade(location)
            }
            return
        }
        processAcceptedLocation(location)
    }

    private fun processAcceptedLocation(location: Location) {
        val previousSnapshot = _snapshots.value ?: return
        val nowMs = System.currentTimeMillis()
        val speed = location.speed.toDouble().takeIf { location.hasSpeed() } ?: 0.0

        autoPause.onSample(nowMs, speed)
        val autoPaused = autoPause.state == AutoPauseDetector.State.PAUSED

        val previousLat = lastFixLat
        val previousLon = lastFixLon
        val addedDistance = if (autoPaused || previousLat == null || previousLon == null) {
            0.0
        } else {
            haversineMeters(
                previousLat, previousLon, location.latitude, location.longitude,
            )
        }
        lastFixLat = location.latitude
        lastFixLon = location.longitude

        val gpsAltitude = location.altitude.takeIf { location.hasAltitude() }
        val pressure = lastPressureHpa
        val barometricAltitude =
            if (sessionQnhHpa != null && pressure != null) {
                SensorManager.getAltitude(
                    sessionQnhHpa!!.toFloat(),
                    pressure.toFloat(),
                ).toDouble()
            } else null
        val chosenAltitude = barometricAltitude ?: gpsAltitude
        if (!autoPaused && chosenAltitude != null) ascent.add(chosenAltitude)
        if (!autoPaused) maxSpeedMps = maxOf(maxSpeedMps, speed)

        val elapsedMs = currentElapsedMs()
        val totalDistanceM = previousSnapshot.totalDistanceM + addedDistance
        val avgSpeedMps =
            if (elapsedMs > 0) totalDistanceM / (elapsedMs / 1000.0) else 0.0

        val gradeReading = if (!autoPaused && chosenAltitude != null) {
            grade.add(totalDistanceM, chosenAltitude)
            grade.currentGradePct()
        } else null

        val signal = autoStop.feed(
            AutoStopDetector.Sample(
                tMs = nowMs,
                lat = location.latitude,
                lon = location.longitude,
                speedMps = speed,
            )
        )
        val next = previousSnapshot.copy(
            isAutoPaused = autoPaused,
            lat = location.latitude,
            lon = location.longitude,
            elevationM = chosenAltitude ?: previousSnapshot.elevationM,
            horizontalAccuracyM = location.accuracy,
            speedMps = speed,
            totalDistanceM = totalDistanceM,
            totalAscentM = ascent.totalAscentM,
            totalDescentM = ascent.totalDescentM,
            currentGradePct = gradeReading,
            maxGradePct = gradeReading?.let {
                maxOf(previousSnapshot.maxGradePct, it)
            } ?: previousSnapshot.maxGradePct,
            minGradePct = gradeReading?.let {
                minOf(previousSnapshot.minGradePct, it)
            } ?: previousSnapshot.minGradePct,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = maxSpeedMps,
            elapsedMs = elapsedMs,
            pressureHpa = pressure,
            stepCount = sessionStepCount,
            autoStopReason = signal?.reason ?: previousSnapshot.autoStopReason,
            autoStopTrimAfterMs =
                signal?.trimAfterMs ?: previousSnapshot.autoStopTrimAfterMs,
        )
        _snapshots.value = next
        TrackingSession.lastSnapshot = next

        val point = TrackingSession.Point(
            lat = location.latitude,
            lon = location.longitude,
            elevM = chosenAltitude,
            gpsElevM = gpsAltitude,
            pressureHpa = pressure,
            horizAccM = location.accuracy,
            speedMps = location.speed.takeIf { location.hasSpeed() } ?: 0f,
            bearingDeg = location.bearing.takeIf { location.hasBearing() },
            cumStepCount = sessionStepCount,
            isAutoPaused = autoPaused,
            tMs = nowMs,
        )
        TrackingSession.append(point)
        synchronized(pendingRows) {
            pendingRows += point.toEntity(next.activityId, nextSeq++)
        }
        DebugLog.log(
            "FIX",
            "accepted speed=%.2f accuracy=%.1f autoPaused=%s distance=%.1f signal=%s"
                .format(
                    speed,
                    location.accuracy,
                    autoPaused,
                    totalDistanceM,
                    signal?.reason?.name ?: "-",
                )
        )
        refreshNotification(next)
    }

    private fun tryStartCascade(location: Location) {
        if (!cascadeInFlight.compareAndSet(false, true)) return
        val captured = Location(location)
        serviceScope.launch {
            val dem = withContext(Dispatchers.IO) {
                DemClient.lookup(
                    captured.latitude,
                    captured.longitude,
                    timeoutMs = DEFERRED_DEM_TIMEOUT_MS,
                )
            }
            val elevation = dem.best
            val source = dem.source
            if (elevation != null && source != null && _snapshots.value?.isAcquiringFix == true) {
                onElevationLocked(
                    captured.latitude,
                    captured.longitude,
                    captured.accuracy,
                    elevation,
                    source,
                )
                processAcceptedLocation(captured)
            }
            cascadeInFlight.set(false)
        }
    }

    private fun onElevationLocked(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        elevationM: Double,
        source: String,
    ) {
        val buffered = synchronized(bufferedBaro) {
            bufferedBaro.toList().also { bufferedBaro.clear() }
        }
        val calibrationPressure = buffered.firstOrNull()?.second ?: lastPressureHpa
        val qnh = calibrationPressure?.let { computeQnhHpa(it, elevationM) }
        sessionQnhHpa = qnh
        if (qnh != null) {
            for ((_, pressure) in buffered) {
                ascent.add(
                    SensorManager.getAltitude(
                        qnh.toFloat(),
                        pressure.toFloat(),
                    ).toDouble()
                )
            }
        }

        BenchmarkSession.current = BenchmarkSession.Benchmark(
            lat = lat,
            lon = lon,
            elevM = elevationM,
            source = "$source (quick)",
            horizAccM = accuracyM.toDouble(),
            fixCount = 1,
            baroAvgHpa = calibrationPressure,
            baroSampleCount = buffered.size,
            acquiredAtMs = System.currentTimeMillis(),
        )
        BenchmarkSession.qnhHpa = qnh
        sessionBenchmarkElevM = elevationM

        _snapshots.value?.let { previous ->
            val updated = previous.copy(
                isAcquiringFix = false,
                qnhHpa = qnh,
                totalAscentM = ascent.totalAscentM,
                totalDescentM = ascent.totalDescentM,
            )
            _snapshots.value = updated
            TrackingSession.lastSnapshot = updated
            ActiveSessionStore.save(
                this,
                ActiveSessionStore.State(updated.activityId, isQuickStart, false),
            )
        }
    }

    private fun tickElapsed() {
        val previous = _snapshots.value ?: return
        val movingDelta =
            if (!previous.isPaused && !previous.isAutoPaused) 1_000L else 0L
        val next = previous.copy(
            elapsedMs = currentElapsedMs(),
            movingMs = previous.movingMs + movingDelta,
            stepCount = sessionStepCount,
        )
        _snapshots.value = next
        TrackingSession.lastSnapshot = next
        refreshNotification(next)
    }

    private fun currentElapsedMs(): Long =
        elapsedBaseMs + (SystemClock.elapsedRealtime() - resumedAtElapsedMs)

    private fun updatePaused(paused: Boolean) {
        val previous = _snapshots.value ?: return
        val next = previous.copy(isPaused = paused)
        _snapshots.value = next
        TrackingSession.lastSnapshot = next
        refreshNotification(next)
        serviceScope.launch { flushPending() }
    }

    private fun dismissAutoStop() {
        val previous = _snapshots.value ?: return
        val next = previous.copy(
            autoStopReason = null,
            autoStopTrimAfterMs = null,
        )
        _snapshots.value = next
        TrackingSession.lastSnapshot = next
    }

    private suspend fun flushPending() {
        flushMutex.withLock {
            val snapshot = _snapshots.value ?: return
            if (finalizing.get()) return
            val batch = synchronized(pendingRows) {
                pendingRows.toList().also { pendingRows.clear() }
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    TrekDatabase.get(this@TrackingService).withTransaction {
                        val db = TrekDatabase.get(this@TrackingService)
                        db.activities().upsert(snapshot.toEntity(endTime = null))
                        if (batch.isNotEmpty()) db.trackPoints().insertAll(batch)
                    }
                }
                ActiveSessionStore.save(
                    this@TrackingService,
                    ActiveSessionStore.State(
                        snapshot.activityId,
                        isQuickStart,
                        snapshot.isAcquiringFix,
                    ),
                )
            }.onFailure {
                synchronized(pendingRows) { pendingRows.addAll(0, batch) }
                DebugLog.log("DB", "periodic flush failed: ${it.javaClass.simpleName}")
            }
        }
    }

    private fun requestStop(trimAfterMs: Long?) {
        val raw = _snapshots.value ?: return
        if (!finalizing.compareAndSet(false, true)) return
        stopCollectors()

        var working = raw.copy(
            elapsedMs = currentElapsedMs(),
            stepCount = sessionStepCount,
            autoStopReason = null,
            autoStopTrimAfterMs = null,
        )
        if (working.isAcquiringFix) {
            val buffered = synchronized(bufferedBaro) {
                bufferedBaro.toList().also { bufferedBaro.clear() }
            }
            for ((_, pressure) in buffered) {
                ascent.add(
                    SensorManager.getAltitude(
                        DEFAULT_QNH_HPA.toFloat(),
                        pressure.toFloat(),
                    ).toDouble()
                )
            }
            working = working.copy(
                isAcquiringFix = false,
                totalAscentM = ascent.totalAscentM,
                totalDescentM = ascent.totalDescentM,
            )
        }

        val allPoints = TrackingSession.points()
        val finalPoints =
            if (trimAfterMs == null) allPoints
            else allPoints.filter { it.tMs <= trimAfterMs }
        val finalEndTime =
            if (trimAfterMs == null) System.currentTimeMillis()
            else finalPoints.lastOrNull()?.tMs ?: working.activityId

        val finalSnapshot = if (trimAfterMs == null) {
            working
        } else {
            val stats = SessionStatsCalculator.calculate(
                working.activityId,
                finalPoints,
                finalEndTime,
            )
            val last = finalPoints.lastOrNull()
            working.copy(
                isAutoPaused = last?.isAutoPaused ?: false,
                lat = last?.lat,
                lon = last?.lon,
                elevationM = last?.elevM,
                horizontalAccuracyM = last?.horizAccM,
                speedMps = last?.speedMps?.toDouble() ?: 0.0,
                totalDistanceM = stats.totalDistanceM,
                totalAscentM = stats.totalAscentM,
                totalDescentM = stats.totalDescentM,
                currentGradePct = null,
                maxGradePct = stats.maxGradePct,
                minGradePct = stats.minGradePct,
                avgSpeedMps = stats.avgSpeedMps,
                maxSpeedMps = stats.maxSpeedMps,
                elapsedMs = stats.elapsedMs,
                movingMs = stats.movingMs,
                stepCount = stats.stepCount,
            )
        }
        TrackingSession.replace(finalPoints, finalSnapshot)
        _snapshots.value = finalSnapshot
        refreshNotification(finalSnapshot, getString(R.string.notif_tracking_saving))

        serviceScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val db = TrekDatabase.get(this@TrackingService)
                    val rows = finalPoints.mapIndexed { index, point ->
                        point.toEntity(finalSnapshot.activityId, index)
                    }
                    db.withTransaction {
                        db.trackPoints().deleteForActivity(finalSnapshot.activityId)
                        if (rows.isNotEmpty()) db.trackPoints().insertAll(rows)
                        db.activities().upsert(finalSnapshot.toEntity(finalEndTime))
                    }
                }
            }
            result.onSuccess {
                synchronized(pendingRows) { pendingRows.clear() }
                ActiveSessionStore.clear(this@TrackingService)
                DiagnosticExportWorker.enqueue(this@TrackingService, finalSnapshot.activityId)
                _snapshots.value = null
                _completedActivities.tryEmit(finalSnapshot.activityId)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }.onFailure {
                finalizing.set(false)
                DebugLog.log("DB", "finalize failed: ${it.javaClass.simpleName}")
                refreshNotification(
                    finalSnapshot,
                    getString(R.string.notif_tracking_save_failed),
                )
                startCollectors(finalSnapshot.isAcquiringFix)
            }
        }
    }

    private fun enterForeground(body: String) {
        TrackingNotifier.ensureChannel(this)
        val notification = TrackingNotifier.build(
            context = this,
            title = getString(R.string.notif_tracking_title),
            body = body,
            isPaused = false,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TrackingNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(TrackingNotifier.NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification(
        snapshot: TrackSnapshot,
        bodyOverride: String? = null,
    ) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        val body = bodyOverride ?: "${formatDuration(snapshot.elapsedMs)} · ${
            "%.2f km".format(snapshot.totalDistanceM / 1000.0)
        }"
        manager.notify(
            TrackingNotifier.NOTIFICATION_ID,
            TrackingNotifier.build(
                context = this,
                title = getString(R.string.notif_tracking_title),
                body = body,
                isPaused = snapshot.isPaused,
            ),
        )
    }

    private fun TrackSnapshot.toEntity(endTime: Long?): ActivityEntity =
        ActivityEntity(
            id = activityId,
            startTime = activityId,
            endTime = endTime,
            type = type,
            name = sessionName,
            totalDistanceM = totalDistanceM,
            totalAscentM = totalAscentM,
            totalDescentM = totalDescentM,
            elapsedMs = elapsedMs,
            movingMs = movingMs,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = maxSpeedMps,
            maxGradePct = maxGradePct.takeUnless { it == Double.NEGATIVE_INFINITY } ?: 0.0,
            minGradePct = minGradePct.takeUnless { it == Double.POSITIVE_INFINITY } ?: 0.0,
            benchmarkElevM = sessionBenchmarkElevM,
            qnhHpa = qnhHpa,
            stepCount = stepCount,
        )

    private fun TrackingSession.Point.toEntity(
        activityId: Long,
        seq: Int,
    ): TrackPointEntity =
        TrackPointEntity(
            activityId = activityId,
            seq = seq,
            time = tMs,
            lat = lat,
            lon = lon,
            altM = elevM ?: gpsElevM ?: 0.0,
            gpsAltM = gpsElevM ?: 0.0,
            pressureHpa = pressureHpa,
            horizAccM = horizAccM,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            cumStepCount = cumStepCount,
            isAutoPaused = isAutoPaused,
        )

    private fun TrackPointEntity.toPoint(): TrackingSession.Point =
        TrackingSession.Point(
            lat = lat,
            lon = lon,
            elevM = altM,
            gpsElevM = gpsAltM,
            pressureHpa = pressureHpa,
            horizAccM = horizAccM,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            cumStepCount = cumStepCount,
            isAutoPaused = isAutoPaused,
            tMs = time,
        )

    override fun onDestroy() {
        stopCollectors()
        restoreJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

private fun CoroutineScope.launch1Hz(block: suspend () -> Unit): Job =
    launch {
        while (true) {
            delay(1_000L)
            block()
        }
    }
