// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.ui.benchmark

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.comtekglobal.tromp.data.db.KnownLocationEntity
import com.comtekglobal.tromp.data.db.TrekDatabase
import com.comtekglobal.tromp.databinding.ActivityBenchmarkBinding
import com.comtekglobal.tromp.elevation.DemClient
import com.comtekglobal.tromp.location.LocationSource
import com.comtekglobal.tromp.sensors.BarometerSource
import com.comtekglobal.tromp.tracking.BenchmarkSession
import com.comtekglobal.tromp.util.DebugLog
import com.comtekglobal.tromp.util.UnitPrefs
import com.comtekglobal.tromp.util.elevationUnit
import com.comtekglobal.tromp.util.formatElevation
import com.comtekglobal.tromp.util.formatElevationDelta
import com.comtekglobal.tromp.util.formatLocalIsoMinute
import com.comtekglobal.tromp.util.haversineMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Acquire-Benchmark flow (DESIGN.md §3.3). Runs a 60 s GPS average, collects
 * barometer samples in parallel, then queries USGS 3DEP + Open-Elevation for
 * an orthometric elevation at the averaged lat/lon. The user can Accept
 * (stores into BenchmarkSession) or Retry.
 */
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBenchmarkBinding
    private lateinit var locationSource: LocationSource
    private lateinit var barometerSource: BarometerSource

    private val fixes = mutableListOf<Location>()
    private val pressures = mutableListOf<Float>()
    private var sessionJob: Job? = null
    private var pendingResult: PendingResult? = null

    private data class PendingResult(
        val lat: Double,
        val lon: Double,
        val elevM: Double,
        val source: String,
        val horizAccM: Double,
        val baroAvgHpa: Double?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBenchmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationSource = LocationSource(this)
        barometerSource = BarometerSource(this)

        binding.btnCancel.setOnClickListener { finish() }
        // Retry always runs the full flow — user is explicitly opting in to
        // the 60 s average + DEM lookup instead of the cached shortcut.
        binding.btnRetry.setOnClickListener { startAveraging(useCache = false) }
        binding.btnAccept.setOnClickListener { acceptAndReturn() }

        if (hasFineLocation()) startAveraging() else requestFineLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionJob?.cancel()
    }

    private fun startAveraging(useCache: Boolean = true) {
        sessionJob?.cancel()
        fixes.clear()
        pressures.clear()
        pendingResult = null

        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        binding.txtStatus.text = "Acquiring fix…"
        binding.txtResult.text = ""
        binding.btnAccept.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE

        sessionJob = lifecycleScope.launch {
            DebugLog.init(this@BenchmarkActivity)
            DebugLog.log("BENCH", "startAveraging useCache=$useCache")
            if (useCache) {
                val quickFix = locationSource.lastKnown()
                DebugLog.log(
                    "BENCH",
                    "lastKnown=${
                        quickFix?.let { "lat=%.6f lon=%.6f acc=%.1f".format(it.latitude, it.longitude, it.accuracy) } ?: "null"
                    }"
                )
                if (quickFix != null) {
                    val cached = findNearbyKnown(quickFix.latitude, quickFix.longitude)
                    DebugLog.log(
                        "BENCH",
                        "cacheLookup hit=${cached != null} elev=${cached?.elevM} source=${cached?.source}"
                    )
                    if (cached != null) {
                        showCachedResult(quickFix, cached)
                        return@launch
                    }
                }
            }
            runFullAveraging()
        }
    }

    private suspend fun runFullAveraging() {
        binding.txtStatus.text = "Acquiring fix… (60 s averaging)"
        val locJob = locationSource.updates(intervalMs = 1_000L)
            .onEach { loc ->
                fixes.add(loc)
                updateLive()
            }
            .launchIn(lifecycleScope)

        val baroJob = if (barometerSource.isAvailable) {
            barometerSource.readings()
                .onEach { pressures.add(it) }
                .launchIn(lifecycleScope)
        } else null

        val startMs = System.currentTimeMillis()
        while (sessionJob?.isActive == true) {
            val elapsedMs = System.currentTimeMillis() - startMs
            if (elapsedMs >= SAMPLE_DURATION_MS) break
            binding.progress.progress = (elapsedMs * 100 / SAMPLE_DURATION_MS).toInt()
            delay(250)
        }

        locJob.cancelAndJoin()
        baroJob?.cancelAndJoin()
        binding.progress.progress = 100
        computeAndShow()
    }

    private suspend fun findNearbyKnown(lat: Double, lon: Double): KnownLocationEntity? {
        val db = TrekDatabase.get(this)
        // Convert the proximity threshold (meters) to a bounding-box in
        // degrees so the SQL filter prunes most of the table. 1° of latitude
        // ≈ 111 km; 1° of longitude shrinks with cos(lat).
        val latDelta = PROXIMITY_THRESHOLD_M / 111_000.0
        val lonDelta = PROXIMITY_THRESHOLD_M /
            (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.000001))
        val candidates = withContext(Dispatchers.IO) {
            db.knownLocations().withinBox(
                minLat = lat - latDelta,
                maxLat = lat + latDelta,
                minLon = lon - lonDelta,
                maxLon = lon + lonDelta,
            )
        }
        return candidates
            .map { it to haversineMeters(lat, lon, it.lat, it.lon) }
            .filter { it.second <= PROXIMITY_THRESHOLD_M }
            .minByOrNull { it.second }
            ?.first
    }

    private fun showCachedResult(fix: android.location.Location, cached: KnownLocationEntity) {
        val distM = haversineMeters(fix.latitude, fix.longitude, cached.lat, cached.lon)
        val eUnit = UnitPrefs.get(this).elevationUnit()
        val sb = StringBuilder()
        sb.appendLine("📍 %.6f, %.6f".format(fix.latitude, fix.longitude))
        sb.appendLine()
        sb.appendLine("━━ CACHED BENCHMARK ━━")
        sb.appendLine(formatElevation(cached.elevM, eUnit, 2))
        sb.appendLine("source: ${cached.source}")
        sb.appendLine("recorded: ${formatLocalIsoMinute(cached.recordedAt)}")
        sb.appendLine("distance from fix: ${formatElevation(distM, eUnit, 1)}")
        sb.appendLine()
        sb.appendLine("You are within ${PROXIMITY_THRESHOLD_M.toInt()} m of a previously benchmarked spot.")
        sb.appendLine("Accept to skip the 60 s average, or Retry to run a full benchmark anyway.")
        binding.txtResult.text = sb.toString()
        binding.txtStatus.text = "Cached match."
        binding.btnAccept.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        // baroAvgHpa isn't needed: CalibrationActivity collects its own
        // 100-sample window live after the user accepts.
        pendingResult = PendingResult(
            lat = fix.latitude,
            lon = fix.longitude,
            elevM = cached.elevM,
            source = "${cached.source} (cached)",
            horizAccM = fix.accuracy.toDouble(),
            baroAvgHpa = null,
        )
    }

    private fun updateLive() {
        val last = fixes.lastOrNull() ?: return
        val eUnit = UnitPrefs.get(this).elevationUnit()
        binding.txtStatus.text =
            "Fixes: ${fixes.size}  ±${formatElevation(last.accuracy.toDouble(), eUnit, 1)} horiz\n" +
            "GPS alt: ${formatElevation(last.altitude, eUnit, 1)}  " +
            "±${formatElevation(last.verticalAccuracyMeters.toDouble(), eUnit, 1)}"
    }

    private suspend fun computeAndShow() {
        if (fixes.isEmpty()) {
            binding.txtStatus.text = "No GPS fix obtained. Move to a spot with sky view and retry."
            binding.progress.visibility = View.GONE
            binding.btnRetry.visibility = View.VISIBLE
            return
        }

        val lat = fixes.map { it.latitude }.average()
        val lon = fixes.map { it.longitude }.average()
        val gpsAlts = fixes.map { it.altitude }
        val gpsAltMean = gpsAlts.average()
        val gpsAltStdev = stdev(gpsAlts)
        val horizAcc = fixes.map { it.accuracy.toDouble() }.average()
        val baroAvg = if (pressures.isNotEmpty()) pressures.map { it.toDouble() }.average() else null

        binding.txtStatus.text = "Averaged ${fixes.size} fixes. Querying DEMs…"

        DebugLog.log("BENCH", "demLookup start lat=%.6f lon=%.6f fixes=%d".format(lat, lon, fixes.size))
        val dem = withContext(Dispatchers.IO) { DemClient.lookup(lat, lon) }
        DebugLog.log("BENCH", "demLookup result usgs=${dem.usgsElevM} open=${dem.openElevM}")

        val eUnit = UnitPrefs.get(this).elevationUnit()
        val sb = StringBuilder()
        sb.appendLine("📍 %.6f, %.6f".format(lat, lon))
        sb.appendLine("   ±${formatElevation(horizAcc, eUnit, 1)} horizontal")
        sb.appendLine()
        sb.appendLine("━━ ELEVATION SOURCES ━━")
        sb.appendLine()
        sb.appendLine("GPS (ellipsoidal, WGS84)")
        sb.appendLine(
            "  %s  σ=%s (n=${fixes.size})".format(
                formatElevation(gpsAltMean, eUnit, 1),
                formatElevation(gpsAltStdev, eUnit, 1),
            )
        )
        sb.appendLine("  note: not orthometric; subtract geoid")
        sb.appendLine()
        dem.usgsElevM?.let {
            sb.appendLine("✓ USGS 3DEP (orthometric, NAVD88)")
            sb.appendLine("  ${formatElevation(it, eUnit, 2)}")
            sb.appendLine("  typical accuracy: ~0.1–1 m")
            sb.appendLine()
        } ?: run {
            sb.appendLine("✗ USGS 3DEP: unavailable (outside US?)")
            sb.appendLine()
        }
        dem.openElevM?.let {
            sb.appendLine("✓ Open-Elevation (SRTM, global)")
            sb.appendLine("  ${formatElevation(it, eUnit, 1)}")
            sb.appendLine("  typical accuracy: ~5–10 m")
            sb.appendLine()
        } ?: run {
            sb.appendLine("✗ Open-Elevation: unreachable")
            sb.appendLine()
        }
        baroAvg?.let {
            sb.appendLine("Barometer (raw)")
            sb.appendLine("  %.2f hPa (n=${pressures.size})".format(it))
            sb.appendLine()
        } ?: sb.appendLine("✗ No barometer on this device\n")

        val bestElev = dem.best
        val bestSource = dem.source
        if (bestElev != null && bestSource != null) {
            val delta = gpsAltMean - bestElev
            sb.appendLine("━━ RECOMMENDED BENCHMARK ━━")
            sb.appendLine(formatElevation(bestElev, eUnit, 2))
            sb.appendLine("source: $bestSource")
            sb.appendLine("GPS − DEM = ${formatElevationDelta(delta, eUnit, 1)}")
            binding.btnAccept.visibility = View.VISIBLE
            pendingResult = PendingResult(lat, lon, bestElev, bestSource, horizAcc, baroAvg)
            binding.txtStatus.text = "Done. Review and accept, or retry."

            // Cache the newly-resolved elevation so next time we hit this
            // spot we can skip the 60 s flow. Best-effort: if the insert
            // fails we still proceed normally.
            val now = System.currentTimeMillis()
            val entry = KnownLocationEntity(
                lat = lat, lon = lon,
                elevM = bestElev,
                source = bestSource,
                recordedAt = now,
                horizAccM = horizAcc,
                fixCount = fixes.size,
                lastUsedAt = now,
            )
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dao = TrekDatabase.get(this@BenchmarkActivity).knownLocations()
                    dao.insert(entry)
                    dao.trimToMostRecent(BENCHMARK_CACHE_SIZE)
                } catch (_: Exception) { /* best-effort cache; ignore */ }
            }
        } else {
            sb.appendLine("No DEM source reachable. Check connectivity and retry.")
            binding.txtStatus.text = "DEM lookup failed."
        }

        binding.progress.visibility = View.GONE
        binding.btnRetry.visibility = View.VISIBLE
        binding.txtResult.text = sb.toString()
    }

    private fun acceptAndReturn() {
        val r = pendingResult ?: return
        BenchmarkSession.current = BenchmarkSession.Benchmark(
            lat = r.lat,
            lon = r.lon,
            elevM = r.elevM,
            source = r.source,
            horizAccM = r.horizAccM,
            fixCount = fixes.size,
            baroAvgHpa = r.baroAvgHpa,
            baroSampleCount = pressures.size,
            acquiredAtMs = System.currentTimeMillis(),
        )
        BenchmarkSession.save(this)
        // Don't launch CalibrationActivity here — MainActivity chains
        // benchmark → calibration → tracking via its launchers so the final
        // step can auto-start the service with QNH in hand.
        setResult(RESULT_OK)
        finish()
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestFineLocation() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQ_FINE_LOCATION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_FINE_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startAveraging()
            } else {
                binding.txtStatus.text = "Location permission required."
            }
        }
    }

    private fun stdev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m).pow(2) } / (xs.size - 1))
    }

    companion object {
        private const val REQ_FINE_LOCATION = 200
        private const val SAMPLE_DURATION_MS = 60_000L
        /** Cache-hit radius: within this distance we reuse a prior benchmark. */
        private const val PROXIMITY_THRESHOLD_M: Double = 50.0
        /** Most-recently-used benchmarks kept on-device. Older ones are evicted. */
        private const val BENCHMARK_CACHE_SIZE: Int = 100
    }
}
