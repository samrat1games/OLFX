// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

/**
 * Pure pinch-to-click state machine (no Android dependencies, covered by JVM tests).
 *
 * <p>Input is the thumb-tip/index-tip distance divided by a palm-size reference, so the thresholds
 * are independent of image resolution and hand distance. Hysteresis + dwell + cooldown turn a noisy
 * landmark stream into one deliberate click per pinch, without repeated taps while the fingers stay
 * closed.
 */
public final class PinchGesture {
    public static final float CLOSE_RATIO = 0.30f;
    public static final float OPEN_RATIO = 0.46f;
    public static final long DWELL_NS = 60_000_000L;    // 60 ms closed before a click
    public static final long COOLDOWN_NS = 350_000_000L; // one click per deliberate pinch

    private static final long NEVER = Long.MIN_VALUE;

    private boolean closed;
    private long closedSinceNs = NEVER;
    private long lastClickNs = NEVER;

    /**
     * @param ratio thumb/index distance normalized by palm size; NaN/negative means "no hand"
     * @param nowNs monotonic timestamp
     * @return true exactly once when a pinch should click
     */
    public boolean update(float ratio, long nowNs) {
        if (!Float.isFinite(ratio) || ratio < 0f) {
            reset();
            return false;
        }
        if (!closed) {
            if (ratio <= CLOSE_RATIO) {
                if (closedSinceNs == NEVER) closedSinceNs = nowNs;
                if (nowNs - closedSinceNs >= DWELL_NS
                        && (lastClickNs == NEVER || nowNs - lastClickNs >= COOLDOWN_NS)) {
                    closed = true;
                    lastClickNs = nowNs;
                    return true;
                }
            } else {
                closedSinceNs = NEVER;
            }
        } else if (ratio >= OPEN_RATIO) {
            closed = false;
            closedSinceNs = NEVER;
        }
        return false;
    }

    public boolean isClosed() {
        return closed;
    }

    public void reset() {
        closed = false;
        closedSinceNs = NEVER;
        // Keep lastClickNs: losing tracking for a moment must not allow an immediate re-click.
    }
}
