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
import com.comtekglobal.tromp.export.CsvWriter
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
import java.io.File

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
     * Writes the active session's enriched per-point capture to a CSV under
     * `Android/data/<applicationId>/files/exports/<activityId>.csv` and opens
     * the system share sheet so the user can email / Drive / etc. it. The
     * file remains in place after sharing — `adb pull` and the device's
     * Files app can both read it back.
     */
    private fun exportAndShareCsv(activityId: Long?) {
        if (activityId == null) {
            toast(getString(R.string.summary_export_csv_no_data))
            return
        }
        lifecycleScope.launch {
            try {
                val (file, mimeType) = withContext(Dispatchers.IO) {
                    val db = TrekDatabase.get(this@SummaryActivity)
                    val activity: ActivityEntity = db.activities().byId(activityId)
                        ?: error("activity row missing")
                    val rows: List<TrackPointEntity> = db.trackPoints().forActivity(activityId)
                    val dir = File(getExternalFilesDir(null), "exports").apply { mkdirs() }
                    val out = File(dir, "tromp-$activityId.csv")
                    out.bufferedWriter().use { CsvWriter.write(it, activity, rows) }
                    out to "text/csv"
                }
                toast(getString(R.string.summary_export_csv_saved, file.name))
                val uri: Uri = FileProvider.getUriForFile(
                    this@SummaryActivity,
                    "$packageName.fileprovider",
                    file,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
