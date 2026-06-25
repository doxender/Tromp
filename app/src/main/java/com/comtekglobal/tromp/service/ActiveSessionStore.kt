// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.service

import android.content.Context

/** Small durable pointer used to reconnect a sticky service to its Room row. */
object ActiveSessionStore {
    data class State(
        val activityId: Long,
        val quickStart: Boolean,
        val acquiringFix: Boolean,
    )

    fun load(context: Context): State? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_ACTIVITY_ID, -1L)
        if (id < 0L) return null
        return State(
            activityId = id,
            quickStart = prefs.getBoolean(KEY_QUICK_START, false),
            acquiringFix = prefs.getBoolean(KEY_ACQUIRING_FIX, false),
        )
    }

    fun save(context: Context, state: State) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ACTIVITY_ID, state.activityId)
            .putBoolean(KEY_QUICK_START, state.quickStart)
            .putBoolean(KEY_ACQUIRING_FIX, state.acquiringFix)
            .commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private const val PREFS_NAME = "trektracker.active_session"
    private const val KEY_ACTIVITY_ID = "activityId"
    private const val KEY_QUICK_START = "quickStart"
    private const val KEY_ACQUIRING_FIX = "acquiringFix"
}
