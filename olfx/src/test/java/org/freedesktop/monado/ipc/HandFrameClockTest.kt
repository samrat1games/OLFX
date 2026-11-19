// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc

import org.junit.Assert.assertEquals
import org.junit.Test

class HandFrameClockTest {
    @Test fun monotonic_camera_timestamp_is_preserved() {
        assertEquals(1_000_000_000L, HandFrameClock.normalizeTimestampNs(1_000_000_000L, 2_000_000_000L, 9_000_000_000L, false))
    }

    @Test fun realtime_camera_timestamp_is_converted_to_monotonic() {
        // Boottime is 5 s ahead of monotonic after suspend; the camera sample must not look 5 s future-dated.
        assertEquals(1_000_000_000L, HandFrameClock.normalizeTimestampNs(6_000_000_000L, 2_000_000_000L, 7_000_000_000L, true))
    }

    @Test fun invalid_or_future_samples_fall_back_to_now() {
        assertEquals(2_000_000_000L, HandFrameClock.normalizeTimestampNs(0L, 2_000_000_000L, 7_000_000_000L, false))
        assertEquals(2_000_000_000L, HandFrameClock.normalizeTimestampNs(3_000_000_000L, 2_000_000_000L, 7_000_000_000L, false))
        assertEquals(2_000_000_000L, HandFrameClock.normalizeTimestampNs(1L, 2_000_000_000L, 7_000_000_000L, true))
    }

    @Test fun mediapipe_video_timestamps_are_strictly_increasing() {
        assertEquals(1000L, HandFrameClock.nextMediaPipeTimestampMs(999L, 1_000_000_000L))
        assertEquals(1001L, HandFrameClock.nextMediaPipeTimestampMs(1000L, 1_000_000_000L))
        assertEquals(1002L, HandFrameClock.nextMediaPipeTimestampMs(1001L, 999_000_000L))
    }
}
