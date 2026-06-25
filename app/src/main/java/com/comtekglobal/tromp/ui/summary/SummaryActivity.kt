// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.ui.summary

import android.content.Context
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
import com.comtekglobal.tromp.tracking.TrackSnapshot
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

/** Reads completed activity data from Room before enabling Summary actions. */
class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding
    private var activityId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMap.isEnabled = false
        binding.btnExportCsv.isEnabled = false
        binding.btnMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        binding.btnExportCsv.setOnClickListener { exportAndShareCsv(activityId) }
        binding.btnDone.setOnClickListener { finish() }

        val requestedId = intent.getLongExtra(EXTRA_ACTIVITY_ID, -1L)
            .takeIf { it >= 0L }
        if (requestedId != null) {
            loadFromRoom(requestedId)
        } else {
            activityId = TrackingSession.lastSnapshot?.activityId
            render(TrackingSession.lastSnapshot, TrackingSession.points())
        }
    }

    private fun loadFromRoom(requestedId: Long) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val db = TrekDatabase.get(this@SummaryActivity)
                db.activities().byId(requestedId) to
                    db.trackPoints().forActivity(requestedId)
            }
            val activity = loaded.first
            if (activity == null) {
                render(null, emptyList())
                return@launch
            }
            val points = loaded.second.map { it.toPoint() }
            val snapshot = activity.toSnapshot(points.lastOrNull())
            TrackingSession.replace(points, snapshot)
            activityId = requestedId
            render(snapshot, points)
        }
    }

    private fun render(
        snapshot: TrackSnapshot?,
        points: List<TrackingSession.Point>,
    ) {
        if (snapshot == null) {
            binding.txtTotals.text = getString(R.string.summary_no_data)
            binding.btnMap.visibility = View.GONE
            binding.btnExportCsv.visibility = View.GONE
            return
        }

        val unit = UnitPrefs.get(this)
        val elevationUnit = unit.elevationUnit()
        binding.txtTotals.text = buildString {
            appendLine("Duration:  ${formatDuration(snapshot.elapsedMs)}")
            appendLine("Distance:  ${formatDistance(snapshot.totalDistanceM, unit)}")
            appendLine("Ascent:    ${formatElevation(snapshot.totalAscentM, elevationUnit, 0)}")
            appendLine("Descent:   ${formatElevation(snapshot.totalDescentM, elevationUnit, 0)}")
            appendLine("Avg speed: ${formatSpeed(snapshot.avgSpeedMps, unit)}")
            appendLine("Max speed: ${formatSpeed(snapshot.maxSpeedMps, unit)}")
            if (snapshot.stepCount > 0 && snapshot.totalDistanceM > 0) {
                val strideM = snapshot.totalDistanceM / snapshot.stepCount
                appendLine("Steps:     ${snapshot.stepCount}")
                appendLine("Stride:    ${formatElevation(strideM, elevationUnit, 2)}")
            }
            appendLine("Points:    ${points.size}")
        }
        binding.btnMap.visibility = View.VISIBLE
        binding.btnExportCsv.visibility = View.VISIBLE
        binding.btnMap.isEnabled = points.size >= 2
        binding.btnExportCsv.isEnabled = points.isNotEmpty()
    }

    private fun exportAndShareCsv(requestedId: Long?) {
        if (requestedId == null) {
            toast(getString(R.string.summary_export_csv_no_data))
            return
        }
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val csvFiles = CsvExportFiles.forActivity(
                        this@SummaryActivity,
                        requestedId,
                    )
                    if (!csvFiles.preTrim.exists() || !csvFiles.postTrim.exists()) {
                        regenerateCsvFiles(requestedId, csvFiles)
                    }
                    csvFiles
                }
                val authority = "$packageName.fileprovider"
                val uris = ArrayList<Uri>(2).apply {
                    add(FileProvider.getUriForFile(this@SummaryActivity, authority, files.preTrim))
                    add(FileProvider.getUriForFile(this@SummaryActivity, authority, files.postTrim))
                }
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "text/csv"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            putExtra(
                                Intent.EXTRA_SUBJECT,
                                getString(R.string.summary_export_csv_subject),
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        getString(R.string.summary_export_csv_chooser),
                    )
                )
            } catch (error: Exception) {
                toast(
                    getString(
                        R.string.summary_export_csv_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                )
            }
        }
    }

    private suspend fun regenerateCsvFiles(
        requestedId: Long,
        files: CsvExportFiles,
    ) {
        val db = TrekDatabase.get(this)
        val activity = db.activities().byId(requestedId)
            ?: error("activity row missing")
        val rows = db.trackPoints().forActivity(requestedId)
        val classifications = TrackPostProcessor.classify(rows.map {
            TrackPostProcessor.Sample(
                tMs = it.time,
                speedMps = it.speedMps.toDouble(),
                altM = it.altM,
                cumStepCount = it.cumStepCount,
            )
        })
        check(files.dir.exists() || files.dir.mkdirs())
        files.preTrim.bufferedWriter().use {
            CsvWriter.write(it, activity, rows, classifications, includeStates = null)
        }
        files.postTrim.bufferedWriter().use {
            CsvWriter.write(
                it,
                activity,
                rows,
                classifications,
                includeStates = setOf(
                    TrackPostProcessor.State.ACTIVE,
                    TrackPostProcessor.State.CLAMBERING,
                ),
            )
        }
    }

    private fun ActivityEntity.toSnapshot(
        last: TrackingSession.Point?,
    ): TrackSnapshot =
        TrackSnapshot(
            activityId = id,
            type = type,
            isPaused = false,
            isAutoPaused = last?.isAutoPaused ?: false,
            lat = last?.lat,
            lon = last?.lon,
            elevationM = last?.elevM,
            horizontalAccuracyM = last?.horizAccM,
            speedMps = last?.speedMps?.toDouble() ?: 0.0,
            totalDistanceM = totalDistanceM,
            totalAscentM = totalAscentM,
            totalDescentM = totalDescentM,
            currentGradePct = null,
            maxGradePct = maxGradePct,
            minGradePct = minGradePct,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = maxSpeedMps,
            elapsedMs = elapsedMs,
            movingMs = movingMs,
            pressureHpa = last?.pressureHpa,
            qnhHpa = qnhHpa,
            stepCount = stepCount,
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_ACTIVITY_ID = "activity_id"

        fun intent(context: Context, activityId: Long): Intent =
            Intent(context, SummaryActivity::class.java)
                .putExtra(EXTRA_ACTIVITY_ID, activityId)
    }
}
