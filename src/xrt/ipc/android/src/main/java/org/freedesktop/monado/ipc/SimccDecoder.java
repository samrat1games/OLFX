package org.freedesktop.monado.ipc;

/** Decodes RTMPose SimCC outputs (per-keypoint 1-D x/y classification vectors) into pixel keypoints. Pure Java. */
final class SimccDecoder {
    private SimccDecoder() {}

    /**
     * @param simccX [k][bx] logits, @param simccY [k][by] logits, @param ratio SimCC split ratio (RTMPose: 2.0)
     * @return k * 3 floats: x, y (input-crop pixels) and score
     */
    static float[] decode(float[][] simccX, float[][] simccY, float ratio) {
        int k = simccX.length;
        float[] out = new float[k * 3];
        for (int i = 0; i < k; i++) {
            int bx = argmax(simccX[i]), by = argmax(simccY[i]);
            out[i * 3] = bx / ratio;
            out[i * 3 + 1] = by / ratio;
            out[i * 3 + 2] = Math.min(simccX[i][bx], simccY[i][by]);
        }
        return out;
    }

    static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }
}
