// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.ui.calibration

import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trektracker.databinding.ActivityCalibrationBinding
import com.trektracker.sensors.BarometerSource
import com.trektracker.tracking.BenchmarkSession
import com.trektracker.tracking.computeQnhHpa
import com.trektracker.util.UnitPrefs
import com.trektracker.util.elevationUnit
import com.trektracker.util.formatElevation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Calibrate-Barometer flow (DESIGN.md §6.3). Reached immediately after the
 * benchmark is accepted. Samples the barometer at ~25 ms and waits until the
 * last 100 samples have stdev ≤ 0.1 hPa — that's the "stable" threshold at
 * which we lock the average as the calibration pressure, compute QNH from the
 * benchmark elevation, and auto-finish. QNH persists in BenchmarkSession for
 * the current app session only.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var barometerSource: BarometerSource
    private val window = ArrayDeque<Float>(WINDOW_SIZE)
    private var sampleJob: Job? = null
    private var locked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        barometerSource = BarometerSource(this)

        binding.btnCancel.setOnClickListener {
            BenchmarkSession.qnhHpa = null
            finish()
        }

        startCalibration()
    }

    override fun onDestroy() {
        super.onDestroy()
        sampleJob?.cancel()
    }

    private fun startCalibration() {
        val benchmark = BenchmarkSession.current
        if (benchmark == null) {
            binding.txtSummary.text = "No benchmark available. Acquire one first."
            binding.txtLive.visibility = View.GONE
            return
        }
        if (!barometerSource.isAvailable) {
            binding.txtSummary.text =
                "No barometer on this device — calibration skipped.\n" +
                "Elevation will use GPS only."
            binding.txtLive.visibility = View.GONE
            return
        }

        val eUnit = UnitPrefs.get(this).elevationUnit()
        binding.txtSummary.text = (
            "Benchmark: %s (%s)\n" +
            "Sampling barometer at 25 ms…\n" +
            "Auto-locks when σ of last 100 samples ≤ %.2f hPa."
        ).format(
            formatElevation(benchmark.elevM, eUnit, 2),
            benchmark.source,
            STDEV_THRESHOLD_HPA,
        )

        sampleJob = barometerSource.readings(samplingPeriodUs = SAMPLING_PERIOD_US)
            .onEach { p -> onReading(p, benchmark.elevM) }
            .launchIn(lifecycleScope)
    }

    private fun onReading(pressureHpa: Float, benchmarkElevM: Double) {
        if (locked) return
        if (window.size == WINDOW_SIZE) window.removeFirst()
        window.addLast(pressureHpa)

        val mean = window.map { it.toDouble() }.average()
        val sd = stdev(window.map { it.toDouble() })
        binding.txtLive.text = (
            "Samples: %d / %d\n" +
            "Pressure: %.2f hPa\n" +
            "Mean: %.3f hPa  σ: %.3f hPa"
        ).format(window.size, WINDOW_SIZE, pressureHpa, mean, sd)

        if (window.size == WINDOW_SIZE && sd <= STDEV_THRESHOLD_HPA) {
            locked = true
            sampleJob?.cancel()
            val qnh = computeQnhHpa(mean, benchmarkElevM)
            BenchmarkSession.qnhHpa = qnh
            binding.txtLive.text = (
                "Locked.\n" +
                "Stable pressure: %.3f hPa (σ=%.3f)\n" +
                "QNH: %.2f hPa"
            ).format(mean, sd, qnh)
            finish()
        }
    }

    private fun stdev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m).pow(2) } / (xs.size - 1))
    }

    companion object {
        private const val WINDOW_SIZE = 100
        private const val STDEV_THRESHOLD_HPA = 0.1
        private const val SAMPLING_PERIOD_US = 25_000 // 25 ms
    }
}
