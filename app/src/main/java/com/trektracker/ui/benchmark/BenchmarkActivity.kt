// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.ui.benchmark

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.trektracker.databinding.ActivityBenchmarkBinding
import com.trektracker.elevation.DemClient
import com.trektracker.location.LocationSource
import com.trektracker.sensors.BarometerSource
import com.trektracker.tracking.BenchmarkSession
import com.trektracker.ui.calibration.CalibrationActivity
import com.trektracker.util.metersToFeet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        binding.btnRetry.setOnClickListener { startAveraging() }
        binding.btnAccept.setOnClickListener { acceptAndReturn() }

        if (hasFineLocation()) startAveraging() else requestFineLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionJob?.cancel()
    }

    private fun startAveraging() {
        sessionJob?.cancel()
        fixes.clear()
        pressures.clear()
        pendingResult = null

        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        binding.txtStatus.text = "Acquiring fix… (60 s averaging)"
        binding.txtResult.text = ""
        binding.btnAccept.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE

        sessionJob = lifecycleScope.launch {
            val locJob = locationSource.updates(intervalMs = 1_000L)
                .onEach { loc ->
                    fixes.add(loc)
                    updateLive()
                }
                .launchIn(this)

            val baroJob = if (barometerSource.isAvailable) {
                barometerSource.readings()
                    .onEach { pressures.add(it) }
                    .launchIn(this)
            } else null

            val startMs = System.currentTimeMillis()
            while (isActive) {
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
    }

    private fun updateLive() {
        val last = fixes.lastOrNull() ?: return
        binding.txtStatus.text =
            "Fixes: ${fixes.size}  ±%.1f m / %.1f ft horiz\n".format(
                last.accuracy, last.accuracy.toDouble().metersToFeet()
            ) +
            "GPS alt: %.1f m / %.1f ft  ±%.1f m / %.1f ft".format(
                last.altitude, last.altitude.metersToFeet(),
                last.verticalAccuracyMeters, last.verticalAccuracyMeters.toDouble().metersToFeet()
            )
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

        val dem = withContext(Dispatchers.IO) { DemClient.lookup(lat, lon) }

        val sb = StringBuilder()
        sb.appendLine("📍 %.6f, %.6f".format(lat, lon))
        sb.appendLine("   ±%.1f m / %.1f ft horizontal".format(horizAcc, horizAcc.metersToFeet()))
        sb.appendLine()
        sb.appendLine("━━ ELEVATION SOURCES ━━")
        sb.appendLine()
        sb.appendLine("GPS (ellipsoidal, WGS84)")
        sb.appendLine(
            "  %.1f m / %.1f ft  σ=%.1f m / %.1f ft (n=${fixes.size})".format(
                gpsAltMean, gpsAltMean.metersToFeet(), gpsAltStdev, gpsAltStdev.metersToFeet()
            )
        )
        sb.appendLine("  note: not orthometric; subtract geoid")
        sb.appendLine()
        dem.usgsElevM?.let {
            sb.appendLine("✓ USGS 3DEP (orthometric, NAVD88)")
            sb.appendLine("  %.2f m / %.2f ft".format(it, it.metersToFeet()))
            sb.appendLine("  typical accuracy: ~0.1–1 m")
            sb.appendLine()
        } ?: run {
            sb.appendLine("✗ USGS 3DEP: unavailable (outside US?)")
            sb.appendLine()
        }
        dem.openElevM?.let {
            sb.appendLine("✓ Open-Elevation (SRTM, global)")
            sb.appendLine("  %.1f m / %.1f ft".format(it, it.metersToFeet()))
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
            sb.appendLine("%.2f m / %.2f ft".format(bestElev, bestElev.metersToFeet()))
            sb.appendLine("source: $bestSource")
            sb.appendLine("GPS − DEM = %+.1f m / %+.1f ft".format(delta, delta.metersToFeet()))
            binding.btnAccept.visibility = View.VISIBLE
            pendingResult = PendingResult(lat, lon, bestElev, bestSource, horizAcc, baroAvg)
            binding.txtStatus.text = "Done. Review and accept, or retry."
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
        setResult(RESULT_OK)
        startActivity(Intent(this, CalibrationActivity::class.java))
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
    }
}
