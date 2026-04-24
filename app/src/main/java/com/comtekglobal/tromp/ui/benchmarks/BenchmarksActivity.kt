// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.ui.benchmarks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.comtekglobal.tromp.R
import com.comtekglobal.tromp.data.db.KnownLocationEntity
import com.comtekglobal.tromp.data.db.TrekDatabase
import com.comtekglobal.tromp.databinding.ActivityBenchmarksBinding
import com.comtekglobal.tromp.databinding.ItemBenchmarkRowBinding
import com.comtekglobal.tromp.util.DistanceUnit
import com.comtekglobal.tromp.util.UnitPrefs
import com.comtekglobal.tromp.util.elevationUnit
import com.comtekglobal.tromp.util.formatElevation
import com.comtekglobal.tromp.util.formatLocalIsoMinute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Manage benchmarks. Lists the cached known_location rows in MRU
 * order; each row has a rename button (EditText dialog) and a delete button
 * (confirmation dialog). Nothing here drives the tracking flow — it's purely
 * cache housekeeping so users can clean up stale benchmarks or label the ones
 * they care about.
 */
class BenchmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBenchmarksBinding
    private lateinit var adapter: BenchmarksAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBenchmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BenchmarksAdapter(
            unit = UnitPrefs.get(this),
            onRename = ::showRenameDialog,
            onDelete = ::confirmDelete,
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        adapter.unit = UnitPrefs.get(this)
        reload()
    }

    private fun reload() {
        val db = TrekDatabase.get(this)
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { db.knownLocations().allMruDesc() }
            adapter.submit(rows)
            binding.txtEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showRenameDialog(entry: KnownLocationEntity) {
        val input = EditText(this).apply {
            setText(entry.name.orEmpty())
            setSelection(text.length)
            hint = getString(R.string.benchmarks_rename_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.benchmarks_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { null }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        TrekDatabase.get(this@BenchmarksActivity)
                            .knownLocations().rename(entry.id, newName)
                    }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(entry: KnownLocationEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.benchmarks_delete_confirm_title)
            .setMessage(R.string.benchmarks_delete_confirm_body)
            .setPositiveButton(R.string.benchmarks_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        TrekDatabase.get(this@BenchmarksActivity)
                            .knownLocations().deleteById(entry.id)
                    }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private class BenchmarksAdapter(
    var unit: DistanceUnit,
    private val onRename: (KnownLocationEntity) -> Unit,
    private val onDelete: (KnownLocationEntity) -> Unit,
) : RecyclerView.Adapter<BenchmarksAdapter.VH>() {

    private val items = mutableListOf<KnownLocationEntity>()

    fun submit(rows: List<KnownLocationEntity>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemBenchmarkRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.bind(row, unit)
        holder.itemView.context.let { ctx ->
            holder.b.btnRename.setOnClickListener { onRename(row) }
            holder.b.btnDelete.setOnClickListener { onDelete(row) }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(val b: ItemBenchmarkRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: KnownLocationEntity, unit: DistanceUnit) {
            val ctx = b.root.context
            b.txtName.text = row.name ?: ctx.getString(R.string.benchmarks_unnamed)
            b.txtMeta.text = "%s · %s · %s".format(
                ctx.getString(R.string.benchmarks_coords, row.lat, row.lon),
                formatElevation(row.elevM, unit.elevationUnit(), 2),
                row.source,
            )
            b.txtDates.text = "Last used: %s · Recorded: %s".format(
                if (row.lastUsedAt > 0) formatLocalIsoMinute(row.lastUsedAt) else "—",
                formatLocalIsoMinute(row.recordedAt),
            )
        }
    }
}
