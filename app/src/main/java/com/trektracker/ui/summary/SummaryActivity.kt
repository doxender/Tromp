// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.ui.summary

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.trektracker.databinding.ActivitySummaryBinding
import com.trektracker.tracking.TrackingSession
import com.trektracker.ui.map.MapActivity
import com.trektracker.util.formatDuration
import com.trektracker.util.metersToFeet
import com.trektracker.util.metersToMiles
import com.trektracker.util.mpsToMph

/**
 * Post-activity summary: totals + a link to the map. Reads from the in-memory
 * TrackingSession — persistence to Room is a later pass.
 */
class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val snap = TrackingSession.lastSnapshot
        val points = TrackingSession.points()

        if (snap == null) {
            binding.txtTotals.text = "No activity data."
            binding.btnMap.visibility = View.GONE
        } else {
            val distMi = snap.totalDistanceM.metersToMiles()
            val ascentFt = snap.totalAscentM.metersToFeet()
            val descentFt = snap.totalDescentM.metersToFeet()
            val avgMph = snap.avgSpeedMps.mpsToMph()
            val maxMph = snap.maxSpeedMps.mpsToMph()

            binding.txtTotals.text = buildString {
                appendLine("Duration:  ${formatDuration(snap.elapsedMs)}")
                appendLine("Distance:  %.2f mi (%.2f km)".format(distMi, snap.totalDistanceM / 1000.0))
                appendLine("Ascent:    %.0f ft (%.0f m)".format(ascentFt, snap.totalAscentM))
                appendLine("Descent:   %.0f ft (%.0f m)".format(descentFt, snap.totalDescentM))
                appendLine("Avg speed: %.1f mph (%.2f m/s)".format(avgMph, snap.avgSpeedMps))
                appendLine("Max speed: %.1f mph (%.2f m/s)".format(maxMph, snap.maxSpeedMps))
                appendLine("Points:    ${points.size}")
            }
            binding.btnMap.isEnabled = points.size >= 2
        }

        binding.btnMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        binding.btnDone.setOnClickListener { finish() }
    }
}
