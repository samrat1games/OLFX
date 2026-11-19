// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HandImageGeometryTest {
    @Test fun rearCameraRotationFollowsDisplay() {
        assertEquals(90, HandImageGeometry.clockwiseRotation(90, 0))
        assertEquals(0, HandImageGeometry.clockwiseRotation(90, 90))
        assertEquals(180, HandImageGeometry.clockwiseRotation(90, 270))
        assertEquals(0, HandImageGeometry.clockwiseRotation(270, 270))
    }

    @Test fun normalizedLandmarksRotateWithPreview() {
        // MediaPipe returns points in the original image; the preview is rotated clockwise.
        assertEquals(0.75f, HandImageGeometry.rotateX(0.25f, 0.25f, 90), 1e-6f)
        assertEquals(0.25f, HandImageGeometry.rotateY(0.25f, 0.25f, 90), 1e-6f)
        assertEquals(0.75f, HandImageGeometry.rotateX(0.25f, 0.25f, 180), 1e-6f)
        assertEquals(0.75f, HandImageGeometry.rotateY(0.25f, 0.25f, 180), 1e-6f)
        assertEquals(0.75f, HandImageGeometry.rotateX(0.25f, 0.75f, 270), 1e-6f)
        assertEquals(0.75f, HandImageGeometry.rotateY(0.25f, 0.75f, 270), 1e-6f)
    }

    @Test fun focalAxesSwapForPortraitSensorInLandscapeGame() {
        val raw = HandImageGeometry.focalPixels(3.0f, 4.0f, 4000, 3000, 4000, 3000, 480, 360, 0)
        val upright = HandImageGeometry.focalPixels(3.0f, 4.0f, 4000, 3000, 4000, 3000, 480, 360, 90)
        assertArrayEquals(raw, floatArrayOf(upright[1], upright[0]), 1e-3f)
        assertEquals(360f, upright[0], 1e-3f)
    }

    @Test fun invalidMetadataUsesFiniteFallback() {
        val f = HandImageGeometry.focalPixels(0f, 0f, 0, 0, 0, 0, 480, 360, 90)
        assertEquals(240f / Math.tan(Math.toRadians(30.0)).toFloat(), f[0], 1e-3f)
        assertEquals(f[0], f[1], 1e-3f)
    }
}
