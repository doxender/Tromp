// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.ui.licenses

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.comtekglobal.tromp.R
import com.comtekglobal.tromp.databinding.ActivityLicensesBinding

/**
 * Renders the bundled third-party license notices from res/raw/oss_licenses.txt.
 * Required for compliance with Apache 2.0 (which obligates redistribution of
 * the NOTICE + license text) and is good hygiene for all other inbound
 * dependencies.
 */
class LicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.txtLicenses.text = resources
            .openRawResource(R.raw.oss_licenses)
            .bufferedReader()
            .use { it.readText() }
    }
}
