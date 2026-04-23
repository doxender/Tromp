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
import com.trektracker.util.UnitPrefs
import com.trektracker.util.elevationUnit
import com.trektracker.util.formatDistance
import com.trektracker.util.formatDuration
import com.trektracker.util.formatElevation
import com.trektracker.util.formatSpeed

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
            val unit = UnitPrefs.get(this)
            val eUnit = unit.elevationUnit()
            binding.txtTotals.text = buildString {
                appendLine("Duration:  ${formatDuration(snap.elapsedMs)}")
                appendLine("Distance:  ${formatDistance(snap.totalDistanceM, unit)}")
                appendLine("Ascent:    ${formatElevation(snap.totalAscentM, eUnit, 0)}")
                appendLine("Descent:   ${formatElevation(snap.totalDescentM, eUnit, 0)}")
                appendLine("Avg speed: ${formatSpeed(snap.avgSpeedMps, unit)}")
                appendLine("Max speed: ${formatSpeed(snap.maxSpeedMps, unit)}")
                if (snap.stepCount > 0 && snap.totalDistanceM > 0) {
                    val strideM = snap.totalDistanceM / snap.stepCount
                    appendLine("Steps:     ${snap.stepCount}")
                    appendLine("Stride:    ${formatElevation(strideM, eUnit, 2)}")
                }
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
