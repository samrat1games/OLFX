// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RTMDet-nano (hand) + RTMPose-m (hand, SimCC) on ONNX Runtime. Execution providers: NNAPI (NPU/DSP),
 * XNNPACK, CPU - the first that creates both sessions wins. Works on the upright (display-rotated) image.
 */
final class OnnxHandPipeline implements AutoCloseable {
    static final String DET_FILE = "rtmdet_hand.onnx";
    static final String POSE_FILE = "rtmpose_hand.onnx";
    static final int DET = 320, POSE = 256;
    static final float DET_MIN = 0.35f, POSE_MIN = 0.2f;

    static final class Hand {
        float[][] upright; // 21 x {x, y, 0}, normalised 0..1 in the upright frame
        float[][] world;   // 21 x 3 metres, hand-centred, in the unrotated camera frame
        float score;
    }

    final String provider;
    private final OrtEnvironment env;
    private final OrtSession det, pose;
    private final float[] detBuf = new float[3 * DET * DET];
    private final float[] poseBuf = new float[3 * POSE * POSE];

    private OnnxHandPipeline(OrtEnvironment env, OrtSession det, OrtSession pose, String provider) {
        this.env = env; this.det = det; this.pose = pose; this.provider = provider;
    }

    static boolean modelsPresent(File dir) {
        return new File(dir, DET_FILE).isFile() && new File(dir, POSE_FILE).isFile();
    }

    static OnnxHandPipeline create(File dir) {
        if (!modelsPresent(dir)) return null;
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        for (String ep : new String[] {"NNAPI", "XNNPACK", "CPU"}) {
            OrtSession d = null, p = null;
            try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
                if (ep.equals("NNAPI")) o.addNnapi();
                else if (ep.equals("XNNPACK")) o.addXnnpack(Collections.<String, String>emptyMap());
                d = env.createSession(new File(dir, DET_FILE).getAbsolutePath(), o);
                p = env.createSession(new File(dir, POSE_FILE).getAbsolutePath(), o);
                return new OnnxHandPipeline(env, d, p, ep);
            } catch (Throwable t) {
                try { if (d != null) d.close(); } catch (Throwable ignored) { }
                try { if (p != null) p.close(); } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /** Bilinear RGB sample of the upright image at upright pixel (u, v); returns false outside. */
    private static void sample(ByteBuffer rgba, int w, int h, int rot, int uw, int uh, float u, float v, float[] rgb) {
        float nx = HandImageGeometry.rotateX(u / uw, v / uh, (360 - rot) % 360);
        float ny = HandImageGeometry.rotateY(u / uw, v / uh, (360 - rot) % 360);
        float x = nx * w - 0.5f, y = ny * h - 0.5f;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        rgb[0] = rgb[1] = rgb[2] = 0;
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                int xx = Math.min(w - 1, Math.max(0, x0 + i)), yy = Math.min(h - 1, Math.max(0, y0 + j));
                float wt = (i == 0 ? 1 - fx : fx) * (j == 0 ? 1 - fy : fy);
                int o = (yy * w + xx) * 4;
                rgb[0] += wt * (rgba.get(o) & 0xff);
                rgb[1] += wt * (rgba.get(o + 1) & 0xff);
                rgb[2] += wt * (rgba.get(o + 2) & 0xff);
            }
        }
    }

    /** @param rgba unrotated camera frame (w x h); @param rot clockwise rotation to make it upright. */
    List<Hand> detect(ByteBuffer rgba, int w, int h, int rot) {
        boolean swap = rot == 90 || rot == 270;
        int uw = swap ? h : w, uh = swap ? w : h;
        List<Hand> out = new ArrayList<>(2);
        float[] rgb = new float[3];
        // --- detector: letterbox 320, BGR, RTMDet mean/std, pad 114
        float[] lb = HandOnnxMath.letterbox(uw, uh, DET);
        final float[] dm = {103.53f, 116.28f, 123.675f}, ds = {57.375f, 57.12f, 58.395f};
        int plane = DET * DET;
        for (int y = 0; y < DET; y++) {
            for (int x = 0; x < DET; x++) {
                float u = (x + 0.5f - lb[1]) / lb[0], v = (y + 0.5f - lb[2]) / lb[0];
                boolean inside = u >= 0 && v >= 0 && u < uw && v < uh;
                if (inside) sample(rgba, w, h, rot, uw, uh, u, v, rgb); else rgb[0] = rgb[1] = rgb[2] = 114f;
                detBuf[y * DET + x] = (rgb[2] - dm[0]) / ds[0];            // B
                detBuf[plane + y * DET + x] = (rgb[1] - dm[1]) / ds[1];    // G
                detBuf[2 * plane + y * DET + x] = (rgb[0] - dm[2]) / ds[2]; // R
            }
        }
        float[][] boxes;
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(detBuf), new long[] {1, 3, DET, DET});
                OrtSession.Result r = det.run(Map.of(det.getInputNames().iterator().next(), in))) {
            float[][] d = ((float[][][]) r.get(0).getValue())[0];
            List<float[]> keep = new ArrayList<>();
            for (float[] b : d) if (b[4] >= DET_MIN) keep.add(b);
            Collections.sort(keep, (a, b) -> Float.compare(b[4], a[4]));
            boxes = keep.subList(0, Math.min(2, keep.size())).toArray(new float[0][]);
        } catch (Throwable t) {
            return out;
        }
        // --- pose per box
        final float[] pm = {123.675f, 116.28f, 103.53f}, ps = {58.395f, 57.12f, 57.375f};
        int pp = POSE * POSE;
        for (float[] b : boxes) {
            float x1 = (b[0] - lb[1]) / lb[0], y1 = (b[1] - lb[2]) / lb[0];
            float x2 = (b[2] - lb[1]) / lb[0], y2 = (b[3] - lb[2]) / lb[0];
            float[] c = HandOnnxMath.cropFromBox(x1, y1, x2, y2);
            for (int y = 0; y < POSE; y++) {
                for (int x = 0; x < POSE; x++) {
                    float u = c[0] + ((x + 0.5f) / POSE - 0.5f) * c[2], v = c[1] + ((y + 0.5f) / POSE - 0.5f) * c[2];
                    if (u >= 0 && v >= 0 && u < uw && v < uh) sample(rgba, w, h, rot, uw, uh, u, v, rgb);
                    else rgb[0] = rgb[1] = rgb[2] = 0;
                    poseBuf[y * POSE + x] = (rgb[0] - pm[0]) / ps[0];
                    poseBuf[pp + y * POSE + x] = (rgb[1] - pm[1]) / ps[1];
                    poseBuf[2 * pp + y * POSE + x] = (rgb[2] - pm[2]) / ps[2];
                }
            }
            try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(poseBuf), new long[] {1, 3, POSE, POSE});
                    OrtSession.Result r = pose.run(Map.of(pose.getInputNames().iterator().next(), in))) {
                float[] k = SimccDecoder.decode(((float[][][]) r.get(0).getValue())[0],
                        ((float[][][]) r.get(1).getValue())[0], 2.0f);
                float[][] px = new float[21][2];
                float sum = 0;
                for (int i = 0; i < 21; i++) {
                    px[i][0] = HandOnnxMath.cropToImage(k[i * 3], c[0], c[2], POSE);
                    px[i][1] = HandOnnxMath.cropToImage(k[i * 3 + 1], c[1], c[2], POSE);
                    sum += k[i * 3 + 2];
                }
                if (sum / 21f < POSE_MIN) continue;
                float[][] world = HandOnnxMath.worldFromPixels(px, rot);
                if (world == null) continue;
                Hand hnd = new Hand();
                hnd.upright = new float[21][3];
                for (int i = 0; i < 21; i++) { hnd.upright[i][0] = px[i][0] / uw; hnd.upright[i][1] = px[i][1] / uh; }
                hnd.world = world;
                hnd.score = Math.min(1f, b[4]);
                out.add(hnd);
            } catch (Throwable t) {
                // skip this hand
            }
        }
        return out;
    }

    @Override
    public void close() {
        try { det.close(); } catch (Throwable ignored) { }
        try { pose.close(); } catch (Throwable ignored) { }
    }
}
