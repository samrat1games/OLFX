// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Camera hand tracking for OLFX: camera -> hand landmark model -> 26 OpenXR joints -> native device.
 * @ingroup ipc_android
 */
package org.freedesktop.monado.ipc;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Display;
import androidx.annotation.NonNull;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.ByteBufferImageBuilder;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.components.containers.Landmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import java.util.Collections;
import java.util.List;

/**
 * Runs the device camera and a hand landmark model, converts the 21 landmarks per hand into the 26 OpenXR hand
 * joints (head space: x right, y up, -z forward, metres) and pushes them to the native OLFX hand device.
 *
 * Runs inside the Monado service process, started by {@link MonadoService}.
 */
public final class OlfxHands {
    private static final String TAG = "OlfxHands";
    private static final int FRAME_W = 640;
    private static final int FRAME_H = 480;
    private static final int JOINTS = 26;
    private static final int FLOATS_PER_JOINT = 8;

    static native void nativeSetHand(int hand, float[] joints, boolean active);

    static native void nativeYuvToRgba(
            java.nio.ByteBuffer y, java.nio.ByteBuffer u, java.nio.ByteBuffer v, int yRow, int uvRow, int uvPix,
            int w, int h, java.nio.ByteBuffer out);

    private static OlfxHands instance;

    private final Context context;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandLandmarker landmarker;
    private float focalPx = 500f; // focal length in pixels of the raw frame
    private int rotationCcw = 0;  // rotation (counter-clockwise, degrees) that makes the frame upright
    private int uprightW = FRAME_W;
    private int uprightH = FRAME_H;
    private final int[] missing = new int[2];
    private long lastTimestampMs = 0;
    private volatile boolean running = false;

    private OlfxHands(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    /** Starts tracking if the camera permission is granted (otherwise asks for it once). */
    public static synchronized void start(Context ctx) {
        if (instance != null) {
            return;
        }
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No camera permission: asking");
            try {
                Intent i = new Intent(ctx, CameraPermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "Could not show the camera permission request: " + e);
            }
            return;
        }
        OlfxHands h = new OlfxHands(ctx);
        try {
            h.open();
            instance = h;
        } catch (Throwable t) {
            Log.e(TAG, "Hand tracking failed to start", t);
            h.close();
        }
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private void open() throws Exception {
        thread = new HandlerThread("olfx-hands");
        thread.start();
        handler = new Handler(thread.getLooper());

        // GPU gave no speed-up on the test tablet (pre/post-processing dominates) and risks native crashes.
        landmarker = createLandmarker(Delegate.CPU);
        if (landmarker == null) {
            throw new IllegalStateException("hand landmarker unavailable");
        }

        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String id = null;
        CameraCharacteristics chars = null;
        for (String cid : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(cid);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                id = cid;
                chars = c;
                break;
            }
        }
        if (id == null) {
            throw new IllegalStateException("no back camera");
        }
        computeGeometry(chars);

        reader = ImageReader.newInstance(FRAME_W, FRAME_H, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);

        running = true;
        cm.openCamera(
                id,
                new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice device) {
                        camera = device;
                        startSession(device);
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice device) {
                        device.close();
                    }

                    @Override
                    public void onError(@NonNull CameraDevice device, int error) {
                        Log.e(TAG, "camera error " + error);
                        device.close();
                    }
                },
                handler);
        Log.i(TAG, "camera " + id + " opening, focal=" + focalPx + "px, rotateCcw=" + rotationCcw);
    }

    private HandLandmarker createLandmarker(Delegate delegate) {
        try {
            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(
                                    BaseOptions.builder()
                                            .setModelAssetPath("hand_landmarker.task")
                                            .setDelegate(delegate)
                                            .build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumHands(2)
                            .setMinHandDetectionConfidence(0.4f)
                            .setMinHandPresenceConfidence(0.4f)
                            .setMinTrackingConfidence(0.4f)
                            .build();
            HandLandmarker l = HandLandmarker.createFromOptions(context, options);
            Log.i(TAG, "hand landmarker running on " + delegate);
            return l;
        } catch (Throwable t) {
            Log.w(TAG, "hand landmarker " + delegate + " failed: " + t);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void startSession(CameraDevice device) {
        try {
            final CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            device.createCaptureSession(
                    Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession s) {
                            session = s;
                            try {
                                s.setRepeatingRequest(b.build(), null, handler);
                            } catch (Exception e) {
                                Log.e(TAG, "setRepeatingRequest", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            Log.e(TAG, "capture session configure failed");
                        }
                    },
                    handler);
        } catch (Exception e) {
            Log.e(TAG, "startSession", e);
        }
    }

    private void computeGeometry(CameraCharacteristics c) {
        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        android.util.SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focals != null && focals.length > 0 && sensor != null && sensor.getWidth() > 0) {
            focalPx = focals[0] * FRAME_W / sensor.getWidth();
        }
        Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int so = sensorOrientation != null ? sensorOrientation : 90;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        int rot = d != null ? d.getRotation() * 90 : 0;
        int clockwise = (so - rot + 360) % 360;
        rotationCcw = (360 - clockwise) % 360;
        boolean swap = clockwise == 90 || clockwise == 270;
        uprightW = swap ? FRAME_H : FRAME_W;
        uprightH = swap ? FRAME_W : FRAME_H;
    }

    private void onImage(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null || !running) {
                return;
            }
            long tc = System.nanoTime();
            MPImage mp = toMpImage(image);
            statConvNs += System.nanoTime() - tc;
            long ts = Math.max(lastTimestampMs + 1, image.getTimestamp() / 1_000_000L);
            lastTimestampMs = ts;
            ImageProcessingOptions opts =
                    ImageProcessingOptions.builder().setRotationDegrees(rotationCcw).build();
            long t0 = System.nanoTime();
            HandLandmarkerResult result = landmarker.detectForVideo(mp, opts, ts);
            statDetectNs += System.nanoTime() - t0;
            publish(result);
            if (statFrames > 0 && statFrames % 120 == 0) {
                Log.i(TAG, "tracking: convert " + (statConvNs / statFrames / 1_000_000L) + " ms, detect avg "
                        + (statDetectNs / statFrames / 1_000_000L) + " ms, hand in view "
                        + (100 * statHandFrames / statFrames) + "% of " + statFrames + " frames");
            }
        } catch (Throwable t) {
            Log.w(TAG, "frame failed: " + t);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private java.nio.ByteBuffer rgba;

    /** Camera YUV frame -> RGBA direct buffer (native conversion) -> model input image. */
    private MPImage toMpImage(Image image) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        if (rgba == null || rgba.capacity() != w * h * 4) {
            rgba = java.nio.ByteBuffer.allocateDirect(w * h * 4);
        }
        Image.Plane[] planes = image.getPlanes();
        nativeYuvToRgba(
                planes[0].getBuffer(), planes[1].getBuffer(), planes[2].getBuffer(),
                planes[0].getRowStride(), planes[1].getRowStride(), planes[1].getPixelStride(), w, h, rgba);
        rgba.rewind();
        return new ByteBufferImageBuilder(rgba, w, h, MPImage.IMAGE_FORMAT_RGBA).build();
    }

    // ---- smoothing --------------------------------------------------------------------------------------------

    /** One Euro filter for a 3-vector: low lag when moving, strong smoothing when still. */
    private static final class OneEuro3 {
        private final float minCutoff;
        private final float beta;
        private final float[] x = new float[3];
        private final float[] dx = new float[3];
        private boolean init = false;

        OneEuro3(float minCutoff, float beta) {
            this.minCutoff = minCutoff;
            this.beta = beta;
        }

        void reset() {
            init = false;
        }

        private static float alpha(float cutoff, float dt) {
            float tau = (float) (1.0 / (2.0 * Math.PI * cutoff));
            return 1.0f / (1.0f + tau / dt);
        }

        float[] filter(float[] in, float dt) {
            if (!init) {
                System.arraycopy(in, 0, x, 0, 3);
                dx[0] = dx[1] = dx[2] = 0;
                init = true;
                return x.clone();
            }
            float ad = alpha(1.0f, dt);
            for (int i = 0; i < 3; i++) {
                float rawD = (in[i] - x[i]) / dt;
                dx[i] = dx[i] + ad * (rawD - dx[i]);
                float cutoff = minCutoff + beta * Math.abs(dx[i]);
                float a = alpha(cutoff, dt);
                x[i] = x[i] + a * (in[i] - x[i]);
            }
            return x.clone();
        }
    }

    private static final class HandFilter {
        final OneEuro3 root = new OneEuro3(1.8f, 4.0f);
        final OneEuro3[] rel = new OneEuro3[JOINTS];
        long lastNs = 0;

        HandFilter() {
            for (int i = 0; i < JOINTS; i++) {
                rel[i] = new OneEuro3(3.5f, 8.0f);
            }
        }

        void reset() {
            root.reset();
            for (OneEuro3 f : rel) {
                f.reset();
            }
        }
    }

    private final HandFilter[] filters = {new HandFilter(), new HandFilter()};
    private long statFrames = 0;
    private long statHandFrames = 0;
    private long statDetectNs = 0;
    private long statConvNs = 0;

    /** Smooth the wrist position and every joint relative to the wrist (keeps the hand shape intact). */
    private void smooth(int hand, float[][] j) {
        HandFilter f = filters[hand];
        long now = System.nanoTime();
        if (f.lastNs == 0 || now - f.lastNs > 300_000_000L) {
            f.reset();
        }
        float dt = f.lastNs == 0 ? 0.033f : Math.min(0.2f, Math.max(0.005f, (now - f.lastNs) / 1e9f));
        f.lastNs = now;
        float[] wrist = j[1].clone();
        float[] r = f.root.filter(wrist, dt);
        for (int k = 0; k < JOINTS; k++) {
            float[] off = f.rel[k].filter(sub(j[k], wrist), dt);
            j[k] = new float[] {r[0] + off[0], r[1] + off[1], r[2] + off[2]};
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private void publish(HandLandmarkerResult result) {
        boolean[] seen = new boolean[2];
        List<List<NormalizedLandmark>> all = result.landmarks();
        List<List<Landmark>> world = result.worldLandmarks();
        List<List<Category>> hands = result.handedness();
        int n = Math.min(2, all.size());

        // Which user hand is each detection? The rear camera image is NOT mirrored, but the model's handedness
        // assumes a mirrored (selfie) image, so its label must be swapped. With two hands the image position is the
        // more reliable cue: the user's left hand is on the left of the (upright, rear camera) image.
        int[] assign = new int[n];
        for (int i = 0; i < n; i++) {
            String label = hands.get(i).isEmpty() ? "" : hands.get(i).get(0).categoryName();
            assign[i] = "Left".equalsIgnoreCase(label) ? 1 : 0;
        }
        if (n == 2 && all.get(0).size() >= 21 && all.get(1).size() >= 21) {
            boolean firstIsLeft = all.get(0).get(0).x() < all.get(1).get(0).x();
            assign[0] = firstIsLeft ? 0 : 1;
            assign[1] = firstIsLeft ? 1 : 0;
        }

        for (int i = 0; i < n; i++) {
            if (all.get(i).size() < 21 || world.get(i).size() < 21) {
                continue;
            }
            int hand = assign[i];
            if (seen[hand]) {
                continue;
            }
            float[][] j = toJoints(all.get(i), world.get(i));
            if (j == null) {
                continue;
            }
            smooth(hand, j);
            nativeSetHand(hand, buildOutput(j, hand == 0), true);
            seen[hand] = true;
            missing[hand] = 0;
        }
        for (int h = 0; h < 2; h++) {
            if (!seen[h] && ++missing[h] == 6) { // hold the last pose for ~200 ms, then drop the hand
                nativeSetHand(h, null, false);
            }
        }
        statFrames++;
        if (seen[0] || seen[1]) {
            statHandFrames++;
        }
    }

    /** 21 MediaPipe landmarks -> 26 OpenXR joint positions in head space (before smoothing). */
    private float[][] toJoints(List<NormalizedLandmark> lm, List<Landmark> wl) {
        // Depth from apparent size: palm bones are known in metres (world landmarks) and in pixels (image).
        double worldLen = dist(wl, 0, 9) + dist(wl, 5, 17) + dist(wl, 0, 5) + dist(wl, 0, 17);
        double pixLen = pixDist(lm, 0, 9) + pixDist(lm, 5, 17) + pixDist(lm, 0, 5) + pixDist(lm, 0, 17);
        if (worldLen <= 1e-4 || pixLen <= 1e-3) {
            return null;
        }
        double depthWrist = focalPx * worldLen / pixLen;

        float[][] p = new float[21][3];
        for (int i = 0; i < 21; i++) {
            double z = depthWrist + (wl.get(i).z() - wl.get(0).z());
            double u = lm.get(i).x() * uprightW - uprightW / 2.0;
            double v = lm.get(i).y() * uprightH - uprightH / 2.0;
            // camera space: x right, y down, z forward -> OpenXR head space: x right, y up, -z forward
            p[i][0] = (float) (u / focalPx * z);
            p[i][1] = (float) (-v / focalPx * z);
            p[i][2] = (float) (-z);
        }

        float[][] j = new float[JOINTS][3];
        j[0] = avg(p[0], p[5], p[9], p[13], p[17]);         // palm
        j[1] = p[0];                                         // wrist
        j[2] = p[1]; j[3] = p[2]; j[4] = p[3]; j[5] = p[4]; // thumb
        int[][] fingers = {{5, 6, 7, 8}, {9, 10, 11, 12}, {13, 14, 15, 16}, {17, 18, 19, 20}};
        for (int f = 0; f < 4; f++) {
            int base = 6 + f * 5;
            int[] m = fingers[f];
            j[base] = lerp(p[0], p[m[0]], 0.25f); // metacarpal (no landmark: between wrist and knuckle)
            j[base + 1] = p[m[0]];
            j[base + 2] = p[m[1]];
            j[base + 3] = p[m[2]];
            j[base + 4] = p[m[3]];
        }
        return j;
    }

    /** Joint frames (OpenXR convention) from the smoothed joint positions: 26 * (xyz, quat xyzw, radius). */
    private float[] buildOutput(float[][] j, boolean left) {
        // Back-of-hand direction. For a right hand (index on the left when looking at the back) v1 x v2 points
        // to the palm; the left hand is mirrored.
        float[] v1 = sub(j[7], j[1]);
        float[] v2 = sub(j[22], j[1]);
        float[] dorsal = normalize(cross(v1, v2));
        if (!left) {
            dorsal = neg(dorsal);
        }

        float[] out = new float[JOINTS * FLOATS_PER_JOINT];
        for (int k = 0; k < JOINTS; k++) {
            float[] fwd;
            if (k <= 1) {
                fwd = sub(j[12], j[1]); // wrist/palm: toward the middle finger knuckle
            } else {
                int next = nextJoint(k);
                fwd = next >= 0 ? sub(j[next], j[k]) : sub(j[k], j[prevJoint(k)]);
            }
            float[] q = orientation(normalize(fwd), dorsal);
            int o = k * FLOATS_PER_JOINT;
            out[o] = j[k][0];
            out[o + 1] = j[k][1];
            out[o + 2] = j[k][2];
            out[o + 3] = q[0];
            out[o + 4] = q[1];
            out[o + 5] = q[2];
            out[o + 6] = q[3];
            out[o + 7] = k == 0 ? 0.0f : (k == 1 ? 0.03f : 0.01f);
        }
        return out;
    }

    /** Next joint along the chain for k (2..25), or -1 for fingertips. */
    private static int nextJoint(int k) {
        if (k < 2) {
            return -1;
        }
        if (k <= 5) {
            return k == 5 ? -1 : k + 1; // thumb has 4 joints: 2..5
        }
        return ((k - 6) % 5 == 4) ? -1 : k + 1;
    }

    private static int prevJoint(int k) {
        return k - 1;
    }

    /** OpenXR joint frame: -Z toward the fingertip, +Y toward the back of the hand. */
    private static float[] orientation(float[] forward, float[] dorsal) {
        float[] z = neg(forward);
        float[] x = normalize(cross(dorsal, z));
        float[] y = cross(z, x);
        // rotation matrix with columns x, y, z -> quaternion
        float m00 = x[0], m01 = y[0], m02 = z[0];
        float m10 = x[1], m11 = y[1], m12 = z[1];
        float m20 = x[2], m21 = y[2], m22 = z[2];
        float tr = m00 + m11 + m22;
        float qx, qy, qz, qw;
        if (tr > 0) {
            float s = (float) Math.sqrt(tr + 1.0f) * 2f;
            qw = 0.25f * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0f + m00 - m11 - m22) * 2f;
            qw = (m21 - m12) / s;
            qx = 0.25f * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0f + m11 - m00 - m22) * 2f;
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25f * s;
            qz = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1.0f + m22 - m00 - m11) * 2f;
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25f * s;
        }
        float n = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        return new float[] {qx / n, qy / n, qz / n, qw / n};
    }

    private static double dist(List<Landmark> l, int a, int b) {
        double dx = l.get(a).x() - l.get(b).x();
        double dy = l.get(a).y() - l.get(b).y();
        double dz = l.get(a).z() - l.get(b).z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double pixDist(List<NormalizedLandmark> l, int a, int b) {
        double dx = (l.get(a).x() - l.get(b).x()) * uprightW;
        double dy = (l.get(a).y() - l.get(b).y()) * uprightH;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] neg(float[] a) {
        return new float[] {-a[0], -a[1], -a[2]};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static float[] normalize(float[] a) {
        float n = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        if (n < 1e-9f) {
            return new float[] {0, 0, -1};
        }
        return new float[] {a[0] / n, a[1] / n, a[2] / n};
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static float[] avg(float[]... pts) {
        float[] r = new float[3];
        for (float[] p : pts) {
            r[0] += p[0];
            r[1] += p[1];
            r[2] += p[2];
        }
        r[0] /= pts.length;
        r[1] /= pts.length;
        r[2] /= pts.length;
        return r;
    }

    private void close() {
        running = false;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        try {
            if (landmarker != null) landmarker.close();
        } catch (Exception ignored) {
        }
        if (thread != null) {
            thread.quitSafely();
        }
        nativeSetHand(0, null, false);
        nativeSetHand(1, null, false);
    }
}
