// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.ui.map

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.trektracker.databinding.ActivityMapBinding
import com.trektracker.tracking.TrackingSession
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline

/**
 * osmdroid map rendering the current TrackingSession as a polyline. No Google
 * Maps — DESIGN.md mandates osmdroid as the sole map provider.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(this, prefs)
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)

        val points = TrackingSession.points()
        if (points.size < 2) {
            binding.txtHeader.text = "No track points recorded."
            return
        }

        val geo = points.map { GeoPoint(it.lat, it.lon) }
        val line = Polyline(binding.map).apply {
            outlinePaint.color = android.graphics.Color.rgb(0x52, 0xB7, 0x88)
            outlinePaint.strokeWidth = 8f
            setPoints(geo)
        }
        binding.map.overlays.add(line)

        val box = BoundingBox.fromGeoPoints(geo)
        binding.map.post { binding.map.zoomToBoundingBox(box, false, 80) }

        binding.txtHeader.text = "Track: ${points.size} points"
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }
}
