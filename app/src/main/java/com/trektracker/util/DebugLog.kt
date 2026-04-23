// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Best-effort diagnostic logger that appends timestamped lines to
 * `<externalFilesDir>/autostop.log`. Readable from the device's Files app
 * under `Android/data/com.trektracker/files/autostop.log`, so the user can
 * pull it off the phone without adb. Kept tiny and synchronous — per-fix
 * overhead is a single open/append/close, which is fine at ≤ 1 Hz fix rate.
 *
 * Call [init] once (safe to call multiple times) before logging.
 */
object DebugLog {

    private const val TAG = "DebugLog"
    private const val FILE_NAME = "autostop.log"
    private const val MAX_BYTES: Long = 2L * 1024L * 1024L   // 2 MB rolling cap

    @Volatile
    private var dir: File? = null

    private val fmt: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    fun init(context: Context) {
        if (dir != null) return
        dir = context.getExternalFilesDir(null) ?: context.filesDir
    }

    fun log(tag: String, line: String) {
        val d = dir ?: return
        try {
            val f = File(d, FILE_NAME)
            if (f.length() > MAX_BYTES) {
                f.writeText("[log truncated at ${f.length()} bytes]\n")
            }
            f.appendText("${fmt.get()!!.format(Date())} $tag $line\n")
        } catch (t: Throwable) {
            Log.w(TAG, "log failed: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    fun path(): String? = dir?.let { File(it, FILE_NAME).absolutePath }
}
