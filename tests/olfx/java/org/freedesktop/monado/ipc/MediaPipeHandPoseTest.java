// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

/** Run with javac/java: synthetic camera projection and coordinate-rotation regression. */
public class MediaPipeHandPoseTest {
    private static void near(float actual, float expected) {
        if (Math.abs(actual - expected) > 0.0001f) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        float[][] image = new float[21][2], world = new float[21][3];
        for (int i = 0; i < 21; i++) {
            world[i][0] = (i % 5) * .01f;
            world[i][1] = (i / 5) * .02f;
            world[i][2] = i * .001f;
            image[i][0] = .5f + world[i][0] / (1f + world[i][2]);
            image[i][1] = .5f + world[i][1] / (1f + world[i][2]);
        }
        float[][] p = MediaPipeHandPose.convert(image, world, 400, 400, 400, 400, 0);
        if (p == null) throw new AssertionError("valid pose rejected");
        near(p[0][2], -1f);
        near(p[9][0] - p[0][0], world[9][0]);
        near(p[9][1] - p[0][1], -world[9][1]);
        near(p[9][2] - p[0][2], -world[9][2]);
        for (int i = 0; i < 21; i++) {
            float x = image[i][0], y = image[i][1];
            image[i][0] = 1 - y; image[i][1] = x;
        }
        p = MediaPipeHandPose.convert(image, world, 400, 400, 400, 400, 90);
        if (p == null) throw new AssertionError("rotated pose rejected");
        near(p[9][0] - p[0][0], -world[9][1]);
        near(p[9][1] - p[0][1], -world[9][0]);
        world[4][0] = Float.NaN;
        if (MediaPipeHandPose.convert(image, world, 400, 400, 400, 400, 90) != null)
            throw new AssertionError("invalid pose accepted");
    }
}
