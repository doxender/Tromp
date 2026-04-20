// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.trektracker.R
import com.trektracker.databinding.ActivityMainBinding
import com.trektracker.service.TrackingService
import com.trektracker.service.TrackingNotifier
import com.trektracker.tracking.BenchmarkSession
import com.trektracker.ui.benchmark.BenchmarkActivity
import com.trektracker.ui.history.HistoryActivity
import com.trektracker.ui.summary.SummaryActivity
import com.trektracker.util.formatDuration
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Idle landing screen and live-tracking toggle. START launches TrackingService;
 * while a session is active the button flips to STOP and the status line shows
 * live elapsed + distance. STOP finalizes the service and launches the summary.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isTracking: Boolean = false

    private val benchmarkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshIdleUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            if (isTracking) onStopClicked() else onStartClicked()
        }
        binding.btnAcquireBenchmark.setOnClickListener {
            benchmarkLauncher.launch(Intent(this, BenchmarkActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            Toast.makeText(this, "Settings — pending implementation", Toast.LENGTH_SHORT).show()
        }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        TrackingService.snapshots
            .onEach { snap ->
                isTracking = snap != null
                if (snap != null) {
                    binding.btnStart.setText(R.string.action_stop)
                    binding.btnAcquireBenchmark.isEnabled = false
                    binding.txtStatus.text = (
                        "%s · %.2f km · ↑%.0f m ↓%.0f m"
                    ).format(
                        formatDuration(snap.elapsedMs),
                        snap.totalDistanceM / 1000.0,
                        snap.totalAscentM,
                        snap.totalDescentM,
                    )
                } else {
                    binding.btnStart.setText(R.string.action_start)
                    binding.btnAcquireBenchmark.isEnabled = true
                    refreshIdleUi()
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        if (!isTracking) refreshIdleUi()
    }

    private fun refreshIdleUi() {
        val b = BenchmarkSession.current
        binding.txtStatus.text = if (b != null) {
            getString(R.string.benchmark_active, b.elevM, b.source)
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
        if (BenchmarkSession.isStale()) {
            showStaleBenchmarkDialog()
            return
        }
        startTrackingService()
    }

    private fun showStaleBenchmarkDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.stale_benchmark_title)
            .setMessage(R.string.stale_benchmark_body)
            .setPositiveButton(R.string.stale_benchmark_yes) { _, _ ->
                benchmarkLauncher.launch(Intent(this, BenchmarkActivity::class.java))
            }
            .setNegativeButton(R.string.stale_benchmark_no) { _, _ ->
                startTrackingService()
            }
            .show()
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_ACTIVITY_TYPE, "hike")
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
        }
    }

    companion object {
        private const val REQ_FINE_LOCATION = 100
        private const val REQ_NOTIFICATIONS = 101
    }
}
