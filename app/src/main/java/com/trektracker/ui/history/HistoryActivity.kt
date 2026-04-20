// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trektracker.data.db.ActivityEntity
import com.trektracker.data.db.TrekDatabase
import com.trektracker.databinding.ActivityHistoryBinding
import com.trektracker.databinding.ItemActivityRowBinding
import com.trektracker.tracking.TrackSnapshot
import com.trektracker.tracking.TrackingSession
import com.trektracker.ui.summary.SummaryActivity
import com.trektracker.util.formatDuration
import com.trektracker.util.formatLocalIsoMinute
import com.trektracker.util.metersToFeet
import com.trektracker.util.metersToMiles
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

        adapter = HistoryAdapter(::openActivity)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        loadData()
    }

    override fun onResume() {
        super.onResume()
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
        binding.txtStats.text = (
            "%d activities · %.1f mi (%.1f km) · ↑%.0f ft (%.0f m)"
        ).format(
            count,
            totalDistM.metersToMiles(),
            totalDistM / 1000.0,
            totalAscentM.metersToFeet(),
            totalAscentM,
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
            )
            points.forEach {
                TrackingSession.append(
                    TrackingSession.Point(
                        lat = it.lat, lon = it.lon,
                        elevM = it.altM, tMs = it.time,
                    )
                )
            }
            startActivity(Intent(this@HistoryActivity, SummaryActivity::class.java))
        }
    }
}

private class HistoryAdapter(
    private val onClick: (ActivityEntity) -> Unit,
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
        holder.bind(a)
        holder.itemView.setOnClickListener { onClick(a) }
    }

    override fun getItemCount(): Int = items.size

    class VH(private val b: ItemActivityRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(a: ActivityEntity) {
            b.txtName.text = a.name ?: a.type
            b.txtMeta.text = formatLocalIsoMinute(a.startTime)
            b.txtStats.text = (
                "%.2f mi · ↑%.0f ft · %s"
            ).format(
                a.totalDistanceM.metersToMiles(),
                a.totalAscentM.metersToFeet(),
                formatDuration(a.elapsedMs),
            )
        }
    }
}
