// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.comtekglobal.tromp.R
import com.comtekglobal.tromp.data.db.ActivityEntity
import com.comtekglobal.tromp.data.db.TrekDatabase
import com.comtekglobal.tromp.databinding.ActivityHistoryBinding
import com.comtekglobal.tromp.databinding.ItemActivityRowBinding
import com.comtekglobal.tromp.ui.summary.SummaryActivity
import com.comtekglobal.tromp.util.DistanceUnit
import com.comtekglobal.tromp.util.UnitPrefs
import com.comtekglobal.tromp.util.elevationUnit
import com.comtekglobal.tromp.util.formatDistance
import com.comtekglobal.tromp.util.formatDuration
import com.comtekglobal.tromp.util.formatElevation
import com.comtekglobal.tromp.util.formatLocalIsoMinute
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
        startActivity(SummaryActivity.intent(this, activity.id))
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
                        db.withTransaction {
                            db.trackPoints().deleteForActivity(activity.id)
                            db.waypoints().deleteForActivity(activity.id)
                            db.activities().deleteById(activity.id)
                        }
                        com.comtekglobal.tromp.export.CsvExportFiles
                            .forActivity(this@HistoryActivity, activity.id)
                            .let {
                                it.preTrim.delete()
                                it.postTrim.delete()
                            }
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
