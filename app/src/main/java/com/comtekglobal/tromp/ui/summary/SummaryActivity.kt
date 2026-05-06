// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.ui.summary

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.comtekglobal.tromp.R
import com.comtekglobal.tromp.data.db.ActivityEntity
import com.comtekglobal.tromp.data.db.TrackPointEntity
import com.comtekglobal.tromp.data.db.TrekDatabase
import com.comtekglobal.tromp.databinding.ActivitySummaryBinding
import com.comtekglobal.tromp.export.CsvExportFiles
import com.comtekglobal.tromp.export.CsvWriter
import com.comtekglobal.tromp.tracking.TrackPostProcessor
import com.comtekglobal.tromp.tracking.TrackingSession
import com.comtekglobal.tromp.ui.map.MapActivity
import com.comtekglobal.tromp.util.UnitPrefs
import com.comtekglobal.tromp.util.elevationUnit
import com.comtekglobal.tromp.util.formatDistance
import com.comtekglobal.tromp.util.formatDuration
import com.comtekglobal.tromp.util.formatElevation
import com.comtekglobal.tromp.util.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList

/**
 * Post-activity summary: totals + a link to the map. Reads from the in-memory
 * TrackingSession (which TrackingService populates while recording, and which
 * HistoryActivity re-populates from Room when reopening a past activity).
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
            binding.btnExportCsv.visibility = View.GONE
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
            binding.btnExportCsv.isEnabled = points.isNotEmpty()
        }

        binding.btnMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        binding.btnExportCsv.setOnClickListener {
            exportAndShareCsv(snap?.activityId)
        }
        binding.btnDone.setOnClickListener { finish() }
    }

    /**
     * v1.15.1: shares both diagnostic CSVs at once via ACTION_SEND_MULTIPLE
     * (pre-trim and post-trim, both produced by `TrackingService` at stop
     * and parked next to each other in
     * `Android/data/<applicationId>/files/exports/`). For activities
     * recorded before v1.15.1 the auto-export hadn't run, so the files are
     * regenerated on demand by re-running the classifier against the
     * persisted points — no schema migration needed.
     */
    private fun exportAndShareCsv(activityId: Long?) {
        if (activityId == null) {
            toast(getString(R.string.summary_export_csv_no_data))
            return
        }
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val csvFiles = CsvExportFiles.forActivity(this@SummaryActivity, activityId)
                    if (!csvFiles.preTrim.exists() || !csvFiles.postTrim.exists()) {
                        regenerateCsvFiles(activityId, csvFiles)
                    }
                    csvFiles
                }
                toast(getString(R.string.summary_export_csv_saved, files.preTrim.name))
                val authority = "$packageName.fileprovider"
                val uris = ArrayList<Uri>(2).apply {
                    add(FileProvider.getUriForFile(this@SummaryActivity, authority, files.preTrim))
                    add(FileProvider.getUriForFile(this@SummaryActivity, authority, files.postTrim))
                }
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/csv"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        getString(R.string.summary_export_csv_subject),
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(
                    Intent.createChooser(
                        send,
                        getString(R.string.summary_export_csv_chooser),
                    )
                )
            } catch (t: Throwable) {
                toast(
                    getString(
                        R.string.summary_export_csv_failed,
                        t.message ?: t.javaClass.simpleName,
                    )
                )
            }
        }
    }

    private suspend fun regenerateCsvFiles(activityId: Long, files: CsvExportFiles) {
        val db = TrekDatabase.get(this@SummaryActivity)
        val activity: ActivityEntity = db.activities().byId(activityId)
            ?: error("activity row missing")
        val rows: List<TrackPointEntity> = db.trackPoints().forActivity(activityId)
        val samples = rows.map {
            TrackPostProcessor.Sample(
                tMs = it.time,
                speedMps = it.speedMps.toDouble(),
                altM = it.altM,
                cumStepCount = it.cumStepCount,
            )
        }
        val classifications = TrackPostProcessor.classify(samples)
        files.dir.mkdirs()
        files.preTrim.bufferedWriter().use {
            CsvWriter.write(it, activity, rows, classifications, includeStates = null)
        }
        files.postTrim.bufferedWriter().use {
            CsvWriter.write(
                it, activity, rows, classifications,
                includeStates = setOf(
                    TrackPostProcessor.State.ACTIVE,
                    TrackPostProcessor.State.CLAMBERING,
                ),
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
