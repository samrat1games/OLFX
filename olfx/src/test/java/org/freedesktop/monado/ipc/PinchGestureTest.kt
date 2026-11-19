// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchGestureTest {
    @Test fun oneClickPerDeliberatePinch() {
        val g = PinchGesture()
        assertFalse(g.update(0.8f, 0L))            // open
        assertFalse(g.update(0.2f, 40_000_000L))   // closing since 40 ms
        assertTrue(g.update(0.2f, 100_000_000L))   // 60 ms dwell -> click
        assertTrue(g.isClosed)
        assertFalse(g.update(0.2f, 200_000_000L))  // still closed -> no repeat
        assertFalse(g.update(0.25f, 240_000_000L)) // hysteresis band -> stays closed
        assertFalse(g.update(0.6f, 280_000_000L))  // open again
        assertFalse(g.isClosed)
        assertFalse(g.update(0.2f, 620_000_000L))  // new pinch after cooldown, dwell starts
        assertTrue(g.update(0.2f, 680_000_000L))   // second deliberate click
    }

    @Test fun invalidInputResetsWithoutClicking() {
        val g = PinchGesture()
        assertFalse(g.update(Float.NaN, 0L))
        assertFalse(g.update(-1f, 10_000_000L))
        assertFalse(g.isClosed)
    }

    @Test fun cooldownBlocksRapidRepinceThenAllowsHeldClick() {
        val g = PinchGesture()
        assertFalse(g.update(0.2f, 0L))            // closing since 0 (valid timestamp)
        assertTrue(g.update(0.2f, 60_000_000L))    // click at 60 ms
        assertFalse(g.update(0.6f, 70_000_000L))   // release
        assertFalse(g.update(0.2f, 130_000_000L))  // re-pinch, dwell starts
        assertFalse(g.update(0.2f, 190_000_000L))  // dwell met but cooldown (130 ms) blocks
        assertTrue(g.update(0.2f, 420_000_000L))   // held through cooldown -> one click
        assertFalse(g.update(0.2f, 800_000_000L))  // closed -> no auto-repeat
    }
}
