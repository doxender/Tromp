// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.util

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.comtekglobal.tromp.R

/**
 * First-run safety acknowledgement. Blocks the app until the user accepts that
 * Tromp is not for emergency / safety-of-life use and is not a substitute for
 * proper navigation tools. The accepted-version string is stored in prefs so
 * that a material change to the disclaimer text (bump CURRENT_VERSION) forces
 * a re-acknowledgement on next launch.
 */
object SafetyDisclaimer {

    /** Bump when the disclaimer text changes in a way that warrants re-accept. */
    const val CURRENT_VERSION: String = "2026-04-24.v1"

    fun hasAccepted(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACCEPTED, null) == CURRENT_VERSION
    }

    fun markAccepted(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCEPTED, CURRENT_VERSION)
            .apply()
    }

    /**
     * Show the blocking first-run dialog. [onAccept] runs only after the user
     * taps the accept button. If the user declines, the host activity is
     * finished — there is no usable app state without acceptance.
     */
    fun showBlocking(activity: Activity, onAccept: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_body)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                markAccepted(activity)
                onAccept()
            }
            .setNegativeButton(R.string.disclaimer_decline) { _, _ ->
                activity.finish()
            }
            .show()
    }

    /** Re-viewable from Settings. Non-blocking; just an informational dialog. */
    fun showInformational(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private const val PREFS_NAME = "tromp.disclaimer"
    private const val KEY_ACCEPTED = "acceptedVersion"
}
