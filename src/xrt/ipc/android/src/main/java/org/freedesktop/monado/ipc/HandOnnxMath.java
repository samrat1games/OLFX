// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

/** Android-free geometry for the RTMDet + RTMPose hand pipeline (JVM-testable). */
final class HandOnnxMath {
    static final float PALM_LENGTH_M = 0.095f; // wrist -> middle-finger MCP, average adult hand
    static final float CROP_PAD = 1.25f;

    private HandOnnxMath() {}

    /** Square crop {cx, cy, size} in upright pixels around a detector box. */
    static float[] cropFromBox(float x1, float y1, float x2, float y2) {
        float size = Math.max(x2 - x1, y2 - y1) * CROP_PAD;
        return new float[] {(x1 + x2) * 0.5f, (y1 + y2) * 0.5f, Math.max(size, 8f)};
    }

    /** Pose keypoint in crop pixels (0..cropPx) -> upright image pixels. */
    static float cropToImage(float k, float centre, float size, int cropPx) {
        return centre + (k / cropPx - 0.5f) * size;
    }

    /** Rotate a y-down vector clockwise by quarter turns, exactly as MediaPipeHandPose.convert expects. */
    static void rotate(float[] v, int clockwiseDegrees) {
        float x = v[0], y = v[1];
        switch (((clockwiseDegrees % 360) + 360) % 360) {
            case 90: v[0] = -y; v[1] = x; break;
            case 180: v[0] = -x; v[1] = -y; break;
            case 270: v[0] = y; v[1] = -x; break;
            default: break;
        }
    }

    /**
     * Metric hand-centred landmarks (wrist at origin, y down, z = 0) from upright pixel keypoints, then
     * rotated back into the UNROTATED camera frame (the frame MediaPipe's world landmarks use).
     * RTMPose is 2-D only, so depth within the hand is flat; the palm scale fixes the metric size.
     */
    static float[][] worldFromPixels(float[][] px, int rotationClockwise) {
        float dx = px[9][0] - px[0][0], dy = px[9][1] - px[0][1];
        float palm = (float) Math.hypot(dx, dy);
        if (!(palm > 1e-3f)) return null;
        float s = PALM_LENGTH_M / palm;
        float[][] w = new float[21][3];
        int back = (360 - ((rotationClockwise % 360) + 360) % 360) % 360;
        for (int i = 0; i < 21; i++) {
            float[] v = {(px[i][0] - px[0][0]) * s, (px[i][1] - px[0][1]) * s};
            rotate(v, back);
            w[i][0] = v[0];
            w[i][1] = v[1];
        }
        return w;
    }

    /** Detector input (letterboxed into dst x dst) -> {scale, padX, padY}. */
    static float[] letterbox(int srcW, int srcH, int dst) {
        float scale = Math.min((float) dst / srcW, (float) dst / srcH);
        return new float[] {scale, (dst - srcW * scale) * 0.5f, (dst - srcH * scale) * 0.5f};
    }
}
