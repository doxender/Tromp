// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration

/**
 * App-wide one-time setup. The main job here is configuring osmdroid's
 * tile-fetching User-Agent so that OSMF can identify Tromp traffic distinctly
 * in their logs — per their Tile Usage Policy
 * (https://operations.osmfoundation.org/policies/tiles/). Without this,
 * osmdroid sends the generic "osmdroid" UA and Tromp becomes anonymous in the
 * pool of every other osmdroid-based app.
 */
class TrompApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(this, prefs)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        Configuration.getInstance().userAgentValue =
            "Tromp/$version (+https://github.com/doxender/Tromp)"
    }
}
