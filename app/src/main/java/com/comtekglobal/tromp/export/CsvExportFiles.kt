// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.export

import android.content.Context
import java.io.File

/**
 * Single source of truth for the per-activity CSV file paths. v1.15.1
 * writes two files per session — `tromp-<id>-pretrim.csv` (every fix,
 * with state column) and `tromp-<id>-posttrim.csv` (DAWDLING dropped) —
 * and both `TrackingService` (auto-export at stop) and `SummaryActivity`
 * (Export CSV button) need to agree on names. Centralised here so renames
 * touch one place.
 */
data class CsvExportFiles(
    val dir: File,
    val preTrim: File,
    val postTrim: File,
) {
    companion object {
        fun forActivity(context: Context, activityId: Long): CsvExportFiles {
            val dir = File(context.getExternalFilesDir(null), "exports")
            return CsvExportFiles(
                dir = dir,
                preTrim = File(dir, "tromp-$activityId-pretrim.csv"),
                postTrim = File(dir, "tromp-$activityId-posttrim.csv"),
            )
        }
    }
}
