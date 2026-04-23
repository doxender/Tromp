// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trektracker.R
import com.trektracker.data.db.ActivityEntity
import com.trektracker.data.db.TrekDatabase
import com.trektracker.databinding.ActivityHistoryBinding
import com.trektracker.databinding.ItemActivityRowBinding
import com.trektracker.tracking.TrackSnapshot
import com.trektracker.tracking.TrackingSession
import com.trektracker.ui.summary.SummaryActivity
import com.trektracker.util.DistanceUnit
import com.trektracker.util.UnitPrefs
import com.trektracker.util.elevationUnit
import com.trektracker.util.formatDistance
import com.trektracker.util.formatDuration
import com.trektracker.util.formatElevation
import com.trektracker.util.formatLocalIsoMinute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * History list: past activities sorted by recency, with an all-time totals
 * card on top. Tap a row to reopen its summary + map by populating
 * TrackingSession from Room and launching SummaryActivity.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = HistoryAdapter(
            unit = UnitPrefs.get(this),
            onClick = ::openActivity,
            onRename = ::showRenameDialog,
            onDelete = ::confirmDelete,
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        // Pick up any unit-setting change made while we were paused.
        adapter.unit = UnitPrefs.get(this)
        loadData()
    }

    private fun loadData() {
        val db = TrekDatabase.get(this)
        lifecycleScope.launch {
            val activities = withContext(Dispatchers.IO) {
                db.activities().observeAll().first()
            }
            val completed = activities.filter { it.endTime != null }
            renderStats(completed)
            adapter.submit(completed)
            binding.txtEmpty.visibility =
                if (completed.isEmpty()) android.view.View.VISIBLE
                else android.view.View.GONE
        }
    }

    private fun renderStats(list: List<ActivityEntity>) {
        val count = list.size
        val totalDistM = list.sumOf { it.totalDistanceM }
        val totalAscentM = list.sumOf { it.totalAscentM }
        val unit = UnitPrefs.get(this)
        binding.txtStats.text = "%d activities · %s · ↑%s".format(
            count,
            formatDistance(totalDistM, unit),
            formatElevation(totalAscentM, unit.elevationUnit(), 0),
        )
    }

    private fun openActivity(activity: ActivityEntity) {
        val db = TrekDatabase.get(this)
        lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) {
                db.trackPoints().forActivity(activity.id)
            }
            TrackingSession.reset()
            TrackingSession.lastSnapshot = TrackSnapshot(
                activityId = activity.id,
                type = activity.type,
                isPaused = false,
                isAutoPaused = false,
                lat = null, lon = null, elevationM = null,
                horizontalAccuracyM = null, speedMps = 0.0,
                totalDistanceM = activity.totalDistanceM,
                totalAscentM = activity.totalAscentM,
                totalDescentM = activity.totalDescentM,
                currentGradePct = null,
                maxGradePct = activity.maxGradePct,
                minGradePct = activity.minGradePct,
                avgSpeedMps = activity.avgSpeedMps,
                maxSpeedMps = activity.maxSpeedMps,
                elapsedMs = activity.elapsedMs,
                movingMs = activity.movingMs,
                pressureHpa = null,
                qnhHpa = activity.qnhHpa,
                stepCount = activity.stepCount,
            )
            points.forEach {
                TrackingSession.append(
                    TrackingSession.Point(
                        lat = it.lat, lon = it.lon,
                        elevM = it.altM,
                        gpsElevM = it.gpsAltM,
                        pressureHpa = it.pressureHpa,
                        horizAccM = it.horizAccM,
                        tMs = it.time,
                    )
                )
            }
            startActivity(Intent(this@HistoryActivity, SummaryActivity::class.java))
        }
    }

    private fun showRenameDialog(activity: ActivityEntity) {
        val input = EditText(this).apply {
            setText(activity.name.orEmpty())
            setSelection(text.length)
            hint = getString(R.string.activity_rename_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { null }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        TrekDatabase.get(this@HistoryActivity)
                            .activities().rename(activity.id, newName)
                    }
                    loadData()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(activity: ActivityEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_delete_confirm_title)
            .setMessage(R.string.activity_delete_confirm_body)
            .setPositiveButton(R.string.activity_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val db = TrekDatabase.get(this@HistoryActivity)
                        // Manual cascade: track_point and waypoint don't declare
                        // foreign keys, so we need to clear them explicitly.
                        db.trackPoints().deleteForActivity(activity.id)
                        db.waypoints().deleteForActivity(activity.id)
                        db.activities().deleteById(activity.id)
                    }
                    loadData()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private class HistoryAdapter(
    var unit: DistanceUnit,
    private val onClick: (ActivityEntity) -> Unit,
    private val onRename: (ActivityEntity) -> Unit,
    private val onDelete: (ActivityEntity) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<ActivityEntity>()

    fun submit(list: List<ActivityEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemActivityRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.bind(a, unit)
        holder.itemView.setOnClickListener { onClick(a) }
        holder.b.btnRename.setOnClickListener { onRename(a) }
        holder.b.btnDelete.setOnClickListener { onDelete(a) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val b: ItemActivityRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(a: ActivityEntity, unit: DistanceUnit) {
            b.txtName.text = a.name ?: a.type
            b.txtMeta.text = formatLocalIsoMinute(a.startTime)
            b.txtStats.text = "%s · ↑%s · %s".format(
                formatDistance(a.totalDistanceM, unit),
                formatElevation(a.totalAscentM, unit.elevationUnit(), 0),
                formatDuration(a.elapsedMs),
            )
        }
    }
}
