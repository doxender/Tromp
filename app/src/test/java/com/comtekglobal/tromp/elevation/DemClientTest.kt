// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.elevation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemClientTest {

    @Test
    fun `open elevation is not contacted when USGS succeeds`() {
        var fallbackCalled = false
        val result = DemClient.fallbackLookup(
            usgs = { 123.4 },
            openElevation = {
                fallbackCalled = true
                999.0
            },
        )

        assertEquals(123.4, result.best!!, 0.001)
        assertFalse(fallbackCalled)
        assertFalse(result.openElevationAttempted)
    }

    @Test
    fun `open elevation is contacted after USGS failure`() {
        var fallbackCalled = false
        val result = DemClient.fallbackLookup(
            usgs = { null },
            openElevation = {
                fallbackCalled = true
                456.7
            },
        )

        assertEquals(456.7, result.best!!, 0.001)
        assertTrue(fallbackCalled)
        assertTrue(result.openElevationAttempted)
    }
}
