// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.export

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.comtekglobal.tromp.data.db.TrekDatabase
import com.comtekglobal.tromp.tracking.TrackPostProcessor

/** Generates the optional diagnostic CSVs after the activity is durable. */
class DiagnosticExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val activityId = inputData.getLong(KEY_ACTIVITY_ID, -1L)
        if (activityId < 0L) return Result.failure()
        return runCatching {
            val db = TrekDatabase.get(applicationContext)
            val activity = db.activities().byId(activityId)
                ?: error("Activity $activityId does not exist")
            val points = db.trackPoints().forActivity(activityId)
            if (points.isEmpty()) return@runCatching

            val classifications = TrackPostProcessor.classify(points.map {
                TrackPostProcessor.Sample(
                    tMs = it.time,
                    speedMps = it.speedMps.toDouble(),
                    altM = it.altM,
                    cumStepCount = it.cumStepCount,
                )
            })
            val files = CsvExportFiles.forActivity(applicationContext, activityId)
            check(files.dir.exists() || files.dir.mkdirs()) {
                "Unable to create ${files.dir}"
            }
            files.preTrim.bufferedWriter().use {
                CsvWriter.write(it, activity, points, classifications, includeStates = null)
            }
            files.postTrim.bufferedWriter().use {
                CsvWriter.write(
                    it,
                    activity,
                    points,
                    classifications,
                    includeStates = setOf(
                        TrackPostProcessor.State.ACTIVE,
                        TrackPostProcessor.State.CLAMBERING,
                    ),
                )
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (runAttemptCount >= 2) Result.failure() else Result.retry()
            },
        )
    }

    companion object {
        private const val KEY_ACTIVITY_ID = "activityId"

        fun enqueue(context: Context, activityId: Long) {
            val request = OneTimeWorkRequestBuilder<DiagnosticExportWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_ACTIVITY_ID, activityId)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "tromp-diagnostic-export-$activityId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
