package org.freedesktop.monado.ipc;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * ONNX Runtime hand pose backend (RTMDet-hand detector + RTMPose-hand 21-keypoint SimCC head).
 * Execution providers are tried NPU-first: NNAPI (NPU/DSP), then XNNPACK, then CPU. Model files are
 * looked up as {@code files/olfx_models/rtmdet_hand.onnx} and {@code rtmpose_hand.onnx}; when they are
 * missing {@link #create} returns null and the caller keeps using MediaPipe.
 */
final class OnnxHandBackend implements AutoCloseable {
    static final int POSE_SIZE = 256;
    final String provider;
    private final OrtEnvironment env;
    private final OrtSession pose;

    private OnnxHandBackend(OrtEnvironment env, OrtSession pose, String provider) {
        this.env = env;
        this.pose = pose;
        this.provider = provider;
    }

    static OnnxHandBackend create(File modelDir) {
        File f = new File(modelDir, "rtmpose_hand.onnx");
        if (!f.isFile()) return null;
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        for (String ep : new String[] {"NNAPI", "XNNPACK", "CPU"}) {
            try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
                if (ep.equals("NNAPI")) o.addNnapi();
                else if (ep.equals("XNNPACK")) o.addXnnpack(Collections.<String, String>emptyMap());
                return new OnnxHandBackend(env, env.createSession(f.getAbsolutePath(), o), ep);
            } catch (Throwable t) {
                // try the next provider
            }
        }
        return null;
    }

    /** @param chw normalised RGB crop, 3*256*256 floats. @return 21*3 keypoints (crop pixels + score), or null. */
    float[] infer(float[] chw) {
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[] {1, 3, POSE_SIZE, POSE_SIZE});
                OrtSession.Result r = pose.run(Map.of(pose.getInputNames().iterator().next(), in))) {
            float[][] x = ((float[][][]) r.get(0).getValue())[0];
            float[][] y = ((float[][][]) r.get(1).getValue())[0];
            return SimccDecoder.decode(x, y, 2.0f);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            pose.close();
        } catch (Throwable ignored) {
        }
    }
}
