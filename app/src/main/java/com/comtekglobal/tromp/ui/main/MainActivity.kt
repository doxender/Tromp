// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.comtekglobal.tromp.R
import com.comtekglobal.tromp.data.db.KnownLocationEntity
import com.comtekglobal.tromp.data.db.TrekDatabase
import com.comtekglobal.tromp.databinding.ActivityMainBinding
import com.comtekglobal.tromp.elevation.DemClient
import com.comtekglobal.tromp.location.LocationSource
import com.comtekglobal.tromp.sensors.BarometerSource
import com.comtekglobal.tromp.service.TrackingService
import com.comtekglobal.tromp.service.TrackingNotifier
import com.comtekglobal.tromp.tracking.AutoStopDetector
import com.comtekglobal.tromp.tracking.AutoStopTrimmer
import com.comtekglobal.tromp.tracking.BenchmarkSession
import com.comtekglobal.tromp.tracking.TrackingSession
import com.comtekglobal.tromp.tracking.computeQnhHpa
import com.comtekglobal.tromp.ui.benchmark.BenchmarkActivity
import com.comtekglobal.tromp.ui.benchmarks.BenchmarksActivity
import com.comtekglobal.tromp.ui.calibration.CalibrationActivity
import com.comtekglobal.tromp.ui.history.HistoryActivity
import com.comtekglobal.tromp.ui.licenses.LicensesActivity
import com.comtekglobal.tromp.ui.summary.SummaryActivity
import com.comtekglobal.tromp.util.DebugLog
import com.comtekglobal.tromp.util.DistanceUnit
import com.comtekglobal.tromp.util.METERS_PER_FOOT
import com.comtekglobal.tromp.util.SafetyDisclaimer
import com.comtekglobal.tromp.util.UnitPrefs
import com.comtekglobal.tromp.util.elevationUnit
import com.comtekglobal.tromp.util.formatDistance
import com.comtekglobal.tromp.util.formatDuration
import com.comtekglobal.tromp.util.formatElevation
import com.comtekglobal.tromp.util.haversineMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos

/**
 * Idle landing screen and live-tracking toggle. START launches TrackingService;
 * while a session is active the button flips to STOP and the status line shows
 * live elapsed + distance. STOP finalizes the service and launches the summary.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isTracking: Boolean = false
    private var autoStopDialogShownFor: AutoStopDetector.Reason? = null
    private var quickStartJob: Job? = null
    private var quickStartDialog: AlertDialog? = null

    /**
     * Full benchmark flow. On success we chain into calibration — and then,
     * if a QNH locks, straight into tracking — so the user doesn't have to
     * tap START again after a fresh benchmark.
     */
    private val benchmarkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && BenchmarkSession.current != null) {
            calibrateForStartLauncher.launch(Intent(this, CalibrationActivity::class.java))
        } else {
            refreshIdleUi()
        }
    }

    /**
     * Launched after a cached-location match at START or after a fresh
     * benchmark completes. When calibration finishes, start tracking iff a
     * QNH was locked.
     */
    private val calibrateForStartLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (BenchmarkSession.qnhHpa != null) startTrackingService()
        else refreshIdleUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtVersion.text = "v${versionName()}"

        DebugLog.init(this)
        BenchmarkSession.load(this)

        if (!SafetyDisclaimer.hasAccepted(this)) {
            SafetyDisclaimer.showBlocking(this) { /* nothing extra — user may now interact */ }
        }

        binding.btnStart.setOnClickListener {
            if (isTracking) onStopClicked() else onStartClicked()
        }
        binding.btnQuickStart.setOnClickListener {
            // Quick Start is unavailable while a session is active — the
            // Start button doubles as Stop in that state, so showing a
            // second action below it would just confuse the flow.
            if (!isTracking) onQuickStartClicked()
        }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnLegal.setOnClickListener {
            SafetyDisclaimer.showInformational(this)
        }

        TrackingService.snapshots
            .onEach { snap ->
                isTracking = snap != null
                if (snap != null) {
                    binding.btnStart.setText(R.string.action_stop)
                    binding.btnQuickStart.visibility = View.GONE
                    binding.txtAcquiringBanner.visibility =
                        if (snap.isAcquiringFix) View.VISIBLE else View.GONE
                    val unit = UnitPrefs.get(this)
                    val eUnit = unit.elevationUnit()
                    binding.txtStatus.text = "%s · %s · ↑%s ↓%s".format(
                        formatDuration(snap.elapsedMs),
                        formatDistance(snap.totalDistanceM, unit),
                        formatElevation(snap.totalAscentM, eUnit, 0),
                        formatElevation(snap.totalDescentM, eUnit, 0),
                    )
                    maybeShowAutoStopDialog(snap.autoStopReason, snap.autoStopTrimAfterMs)
                } else {
                    binding.btnStart.setText(R.string.action_start)
                    binding.btnQuickStart.visibility = View.VISIBLE
                    binding.txtAcquiringBanner.visibility = View.GONE
                    autoStopDialogShownFor = null
                    refreshIdleUi()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun maybeShowAutoStopDialog(
        reason: AutoStopDetector.Reason?,
        trimAfterMs: Long?,
    ) {
        if (reason == null || trimAfterMs == null) {
            autoStopDialogShownFor = null
            return
        }
        // One prompt per latched trigger: if the user dismisses, the detector
        // re-arms on its own and will fire again when the condition recurs.
        if (autoStopDialogShownFor == reason) return
        autoStopDialogShownFor = reason

        val points = TrackingSession.points()
        val totals = AutoStopTrimmer.trim(points, trimAfterMs)
        val body = getString(
            R.string.autostop_body,
            reasonLabel(reason),
            totals.droppedPointCount,
            formatDistance(totals.droppedDistanceM, UnitPrefs.get(this)),
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.autostop_title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.autostop_end_trim) { _, _ ->
                val stopIntent = Intent(this, TrackingService::class.java).apply {
                    action = TrackingNotifier.ACTION_STOP
                    putExtra(TrackingService.EXTRA_TRIM_AFTER_MS, trimAfterMs)
                }
                startService(stopIntent)
                startActivity(Intent(this, SummaryActivity::class.java))
            }
            .setNegativeButton(R.string.autostop_keep_going) { _, _ ->
                val dismissIntent = Intent(this, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_DISMISS_AUTO_STOP
                }
                startService(dismissIntent)
            }
            .show()
    }

    private fun reasonLabel(reason: AutoStopDetector.Reason): String = when (reason) {
        AutoStopDetector.Reason.SPEED_SPIKE -> getString(R.string.autostop_reason_speed)
        AutoStopDetector.Reason.RETURNED_HOME -> getString(R.string.autostop_reason_home)
    }

    override fun onResume() {
        super.onResume()
        if (!isTracking) refreshIdleUi()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun refreshIdleUi() {
        val b = BenchmarkSession.current
        binding.txtStatus.text = if (b != null) {
            val eUnit = UnitPrefs.get(this).elevationUnit()
            getString(
                R.string.benchmark_active,
                formatElevation(b.elevM, eUnit, 1),
                b.source,
            )
        } else {
            getString(R.string.status_idle)
        }
    }

    private fun onStartClicked() {
        if (!hasFineLocation()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_FINE_LOCATION,
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifications()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS,
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasActivityRecognition()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                REQ_ACTIVITY_RECOGNITION,
            )
            return
        }
        // Look up our position in the known-location cache. If we're within
        // 100 ft of a previously benchmarked spot, auto-calibrate the
        // barometer against its stored elevation and start tracking on
        // success. Otherwise block: a benchmark is required before tracking.
        lifecycleScope.launch {
            val loc = LocationSource(this@MainActivity).lastKnown()
            DebugLog.log(
                "START",
                "lastKnown=${loc?.let { "lat=%.6f lon=%.6f".format(it.latitude, it.longitude) } ?: "null"}"
            )
            if (loc == null) { showBenchmarkRequiredDialog(); return@launch }
            val cached = findNearestKnown(loc.latitude, loc.longitude, PROXIMITY_THRESHOLD_M)
            DebugLog.log(
                "START",
                "cache hit=${cached != null} elev=${cached?.elevM} source=${cached?.source}"
            )
            if (cached == null) { showBenchmarkRequiredDialog(); return@launch }
            beginAutoCalibratedStart(cached)
        }
    }

    /**
     * Quick Start: skip the full 60 s benchmark + calibration flow. Take
     * one GPS fix (15 s timeout), one barometer reading, run a DEM lookup,
     * calibrate QNH, start tracking. Permission gating mirrors the regular
     * Start path. CONTEXT.md "Quick Start feature spec".
     *
     * Quick Start benchmarks are session-only by design and never written
     * to the `known_location` cache — they're known to be lower-accuracy
     * than the full benchmark and shouldn't pollute future proximity hits.
     */
    private fun onQuickStartClicked() {
        if (!hasFineLocation()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_FINE_LOCATION_QUICK,
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifications()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS_QUICK,
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasActivityRecognition()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                REQ_ACTIVITY_RECOGNITION_QUICK,
            )
            return
        }
        showAcquiringDialog()
    }

    /**
     * 15 s acquisition modal. Concurrent: pull one GPS fix from
     * LocationSource.updates(), one baro reading. If the fix arrives in
     * time, run a DEM lookup. Whether all three (fix + baro + elevation)
     * succeed determines Quick-Start-locked vs. deferred-fix mode.
     */
    private fun showAcquiringDialog() {
        quickStartJob?.cancel()
        DebugLog.log("QSTART", "modal opened")
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.quick_start_acquiring_title)
            .setMessage(R.string.quick_start_acquiring_body)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelQuickStart() }
            .show()
        quickStartDialog = dialog

        quickStartJob = lifecycleScope.launch {
            val locationSource = LocationSource(this@MainActivity)
            val baroSource = BarometerSource(this@MainActivity)

            // Run fix + baro acquisitions in parallel, both bounded by the
            // 15 s window. Either may return null on timeout. async is on
            // the launch's implicit CoroutineScope (this), so cancelling
            // quickStartJob propagates into both children.
            val fixDeferred = async {
                withTimeoutOrNull(QUICK_ACQUIRE_TIMEOUT_MS) {
                    locationSource.updates(intervalMs = 1_000L).first()
                }
            }
            val baroDeferred = async {
                if (!baroSource.isAvailable) return@async null
                withTimeoutOrNull(QUICK_ACQUIRE_TIMEOUT_MS) {
                    baroSource.readings().first()
                }
            }
            val fix = fixDeferred.await()
            val baroHpa = baroDeferred.await()
            DebugLog.log(
                "QSTART",
                "modal results fix=${fix?.let { "lat=%.6f lon=%.6f acc=%.1f".format(it.latitude, it.longitude, it.accuracy) } ?: "null"} " +
                    "baroHpa=${baroHpa?.let { "%.2f".format(it) } ?: "null"}"
            )

            if (fix == null) {
                // Timed out without a position. Offer deferred-fix mode.
                showNoFixDialog()
                return@launch
            }

            // Try to resolve elevation: DEM first, fall back to loc.altitude.
            val dem = withContext(Dispatchers.IO) {
                runCatching { DemClient.lookup(fix.latitude, fix.longitude) }.getOrNull()
            }
            val demElev = dem?.best
            val demSource = dem?.source
            val gpsAlt: Double? = if (fix.hasAltitude()) fix.altitude else null
            val bestElev = demElev ?: gpsAlt
            val bestSource = demSource ?: if (gpsAlt != null) "GPS" else null
            DebugLog.log(
                "QSTART",
                "elevation dem=${demElev?.let { "%.1f".format(it) } ?: "null"} " +
                    "src=${demSource ?: "-"} gpsAlt=${gpsAlt?.let { "%.1f".format(it) } ?: "null"} " +
                    "chosen=${bestElev?.let { "%.1f".format(it) } ?: "null"}"
            )

            if (bestElev == null || bestSource == null) {
                // Fix arrived but no elevation reference (no DEM, no GPS
                // altitude). Same dialog as no-fix-at-all — offer deferred
                // mode and let the in-service cascade retry on later fixes.
                showNoFixDialog()
                return@launch
            }

            // Got everything. Calibrate QNH (if we have a baro reading) and
            // start tracking. Don't `.save()` the benchmark — it's
            // session-only and shouldn't poison future regular-Start
            // proximity checks.
            val qnh = baroHpa?.let { computeQnhHpa(it.toDouble(), bestElev) }
            BenchmarkSession.current = BenchmarkSession.Benchmark(
                lat = fix.latitude, lon = fix.longitude,
                elevM = bestElev,
                source = "$bestSource (quick)",
                horizAccM = fix.accuracy.toDouble(),
                fixCount = 1,
                baroAvgHpa = baroHpa?.toDouble(),
                baroSampleCount = if (baroHpa != null) 1 else 0,
                acquiredAtMs = System.currentTimeMillis(),
            )
            BenchmarkSession.qnhHpa = qnh

            quickStartDialog?.dismiss()
            quickStartDialog = null
            startTrackingService(quickStart = true)
        }
    }

    private fun showNoFixDialog() {
        quickStartDialog?.dismiss()
        quickStartDialog = null
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_start_no_fix_title)
            .setMessage(R.string.quick_start_no_fix_body)
            .setCancelable(false)
            .setPositiveButton(R.string.quick_start_use_next_fix) { _, _ ->
                // Deferred mode: clear any lingering benchmark / QNH so the
                // service comes up with isAcquiringFix = true and the
                // in-service cascade locks elevation on the first usable fix.
                BenchmarkSession.clear()
                startTrackingService(quickStart = true)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelQuickStart() }
            .show()
    }

    private fun cancelQuickStart() {
        DebugLog.log("QSTART", "cancelled")
        quickStartJob?.cancel()
        quickStartJob = null
        quickStartDialog?.dismiss()
        quickStartDialog = null
    }

    private fun beginAutoCalibratedStart(cached: KnownLocationEntity) {
        val now = System.currentTimeMillis()
        BenchmarkSession.current = BenchmarkSession.Benchmark(
            lat = cached.lat,
            lon = cached.lon,
            elevM = cached.elevM,
            source = cached.source,
            horizAccM = cached.horizAccM ?: 0.0,
            fixCount = cached.fixCount ?: 1,
            baroAvgHpa = null,
            baroSampleCount = 0,
            acquiredAtMs = now,
        )
        BenchmarkSession.save(this)
        // Bump this benchmark to the top of the MRU cache, then prune to 100.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val dao = TrekDatabase.get(this@MainActivity).knownLocations()
                dao.touch(cached.id, now)
                dao.trimToMostRecent(BENCHMARK_CACHE_SIZE)
            }
        }
        calibrateForStartLauncher.launch(Intent(this, CalibrationActivity::class.java))
    }

    private suspend fun findNearestKnown(
        lat: Double, lon: Double, thresholdM: Double,
    ): KnownLocationEntity? {
        val db = TrekDatabase.get(this)
        val latDelta = thresholdM / 111_000.0
        val lonDelta = thresholdM /
            (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.000001))
        val candidates = withContext(Dispatchers.IO) {
            db.knownLocations().withinBox(
                minLat = lat - latDelta, maxLat = lat + latDelta,
                minLon = lon - lonDelta, maxLon = lon + lonDelta,
            )
        }
        return candidates
            .map { it to haversineMeters(lat, lon, it.lat, it.lon) }
            .filter { it.second <= thresholdM }
            .minByOrNull { it.second }
            ?.first
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            getString(R.string.settings_units),
            getString(R.string.settings_benchmarks),
            getString(R.string.settings_safety),
            getString(R.string.settings_licenses),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showUnitsDialog()
                    1 -> startActivity(Intent(this, BenchmarksActivity::class.java))
                    2 -> SafetyDisclaimer.showInformational(this)
                    3 -> startActivity(Intent(this, LicensesActivity::class.java))
                }
            }
            .show()
    }

    private fun showUnitsDialog() {
        val options = arrayOf(
            getString(R.string.units_imperial),
            getString(R.string.units_metric),
        )
        val current = UnitPrefs.get(this)
        val checked = if (current == DistanceUnit.IMPERIAL) 0 else 1
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_units_title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                UnitPrefs.set(
                    this,
                    if (which == 0) DistanceUnit.IMPERIAL else DistanceUnit.METRIC,
                )
                dialog.dismiss()
                if (!isTracking) refreshIdleUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBenchmarkRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.benchmark_required_title)
            .setMessage(R.string.benchmark_required_body)
            .setPositiveButton(R.string.benchmark_required_acquire) { _, _ ->
                benchmarkLauncher.launch(Intent(this, BenchmarkActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startTrackingService(quickStart: Boolean = false) {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_ACTIVITY_TYPE, "hike")
            putExtra(TrackingService.EXTRA_QUICK_START, quickStart)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onStopClicked() {
        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = TrackingNotifier.ACTION_STOP
        }
        startService(stopIntent)
        startActivity(Intent(this, SummaryActivity::class.java))
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasActivityRecognition(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_FINE_LOCATION -> if (granted) onStartClicked()
            REQ_NOTIFICATIONS -> if (granted) onStartClicked()
            // Activity recognition is optional: if the user denies, tracking still
            // proceeds; the summary just won't include a stride figure.
            REQ_ACTIVITY_RECOGNITION -> onStartClicked()
            REQ_FINE_LOCATION_QUICK -> if (granted) onQuickStartClicked()
            REQ_NOTIFICATIONS_QUICK -> if (granted) onQuickStartClicked()
            REQ_ACTIVITY_RECOGNITION_QUICK -> onQuickStartClicked()
        }
    }

    companion object {
        private const val REQ_FINE_LOCATION = 100
        private const val REQ_NOTIFICATIONS = 101
        private const val REQ_ACTIVITY_RECOGNITION = 102
        private const val REQ_FINE_LOCATION_QUICK = 110
        private const val REQ_NOTIFICATIONS_QUICK = 111
        private const val REQ_ACTIVITY_RECOGNITION_QUICK = 112
        /** Auto-calibrate if the user is within this distance of a cached benchmark. */
        private val PROXIMITY_THRESHOLD_M: Double = 100.0 * METERS_PER_FOOT
        /** Most-recently-used benchmarks kept on-device. Older ones are evicted. */
        private const val BENCHMARK_CACHE_SIZE: Int = 100
        /** Quick Start single-shot acquisition window (CONTEXT.md). */
        private const val QUICK_ACQUIRE_TIMEOUT_MS: Long = 15_000L
    }
}
