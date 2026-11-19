// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

/** Convert MediaPipe's image and hand-centred metric landmarks into head-space metres. */
final class MediaPipeHandPose {
    private static final int[] PALM = {0, 5, 9, 13, 17};

    static float[][] convert(float[][] image, float[][] world, int width, int height,
                             float fx, float fy, int rotation) {
        if (image.length < 21 || world.length < 21 || width <= 0 || height <= 0
                || !(fx > 0f) || !(fy > 0f)) return null;
        float[][] rotated = new float[21][3];
        for (int i = 0; i < 21; i++) {
            if (image[i].length < 2 || world[i].length < 3) return null;
            for (int c = 0; c < 3; c++) if (!Float.isFinite(world[i][c])) return null;
            if (!Float.isFinite(image[i][0]) || !Float.isFinite(image[i][1])) return null;
            float x = world[i][0], y = world[i][1];
            switch (rotation) {
                case 90: rotated[i][0] = -y; rotated[i][1] = x; break;
                case 180: rotated[i][0] = -x; rotated[i][1] = -y; break;
                case 270: rotated[i][0] = y; rotated[i][1] = -x; break;
                default: rotated[i][0] = x; rotated[i][1] = y; break;
            }
            rotated[i][2] = world[i][2];
        }
        // Solve translation from the perspective equations using only palm landmarks.
        // MediaPipe's world coordinates are centred on the hand, not on the camera.
        double meanU = 0, meanV = 0, meanA = 0, meanB = 0;
        for (int i : PALM) {
            double u = (image[i][0] - 0.5) * width / fx;
            double v = (image[i][1] - 0.5) * height / fy;
            meanU += u; meanV += v;
            meanA += rotated[i][0] - u * rotated[i][2];
            meanB += rotated[i][1] - v * rotated[i][2];
        }
        meanU /= PALM.length; meanV /= PALM.length;
        meanA /= PALM.length; meanB /= PALM.length;
        double numerator = 0, denominator = 0;
        for (int i : PALM) {
            double u = (image[i][0] - 0.5) * width / fx;
            double v = (image[i][1] - 0.5) * height / fy;
            double du = u - meanU, dv = v - meanV;
            double a = rotated[i][0] - u * rotated[i][2] - meanA;
            double b = rotated[i][1] - v * rotated[i][2] - meanB;
            numerator += du * a + dv * b;
            denominator += du * du + dv * dv;
        }
        if (denominator < 0.0001) return null;
        double depth = numerator / denominator;
        if (!Double.isFinite(depth) || depth < 0.08 || depth > 2.0) return null;
        double tx = meanU * depth - meanA;
        double ty = meanV * depth - meanB;
        float[][] result = new float[21][3];
        for (int i = 0; i < 21; i++) {
            double z = depth + rotated[i][2];
            if (z <= 0.02) return null;
            result[i][0] = (float) (tx + rotated[i][0]);
            result[i][1] = (float) (-ty - rotated[i][1]);
            result[i][2] = (float) -z;
        }
        return result;
    }

    private MediaPipeHandPose() {}
}
