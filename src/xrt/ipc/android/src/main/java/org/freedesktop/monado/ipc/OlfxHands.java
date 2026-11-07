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
import android.graphics.Bitmap;
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
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.components.containers.Landmark;
import com.google.mediapipe.tasks.core.BaseOptions;
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

        HandLandmarker.HandLandmarkerOptions options =
                HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(2)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .build();
        landmarker = HandLandmarker.createFromOptions(context, options);

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
            MPImage mp = new BitmapImageBuilder(yuvToBitmap(image)).build();
            long ts = Math.max(lastTimestampMs + 1, image.getTimestamp() / 1_000_000L);
            lastTimestampMs = ts;
            ImageProcessingOptions opts =
                    ImageProcessingOptions.builder().setRotationDegrees(rotationCcw).build();
            HandLandmarkerResult result = landmarker.detectForVideo(mp, opts, ts);
            publish(result);
        } catch (Throwable t) {
            Log.w(TAG, "frame failed: " + t);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private Bitmap bitmap;
    private int[] argb;

    /** YUV_420_888 -> ARGB_8888 bitmap (the model input must be RGBA). */
    private Bitmap yuvToBitmap(Image image) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            argb = new int[w * h];
        }
        Image.Plane[] planes = image.getPlanes();
        java.nio.ByteBuffer yb = planes[0].getBuffer();
        java.nio.ByteBuffer ub = planes[1].getBuffer();
        java.nio.ByteBuffer vb = planes[2].getBuffer();
        final int yRow = planes[0].getRowStride();
        final int uvRow = planes[1].getRowStride();
        final int uvPix = planes[1].getPixelStride();
        for (int y = 0; y < h; y++) {
            int yi = y * yRow;
            int uvi = (y >> 1) * uvRow;
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int Y = yb.get(yi + x) & 0xFF;
                int uvIdx = uvi + (x >> 1) * uvPix;
                int U = (ub.get(uvIdx) & 0xFF) - 128;
                int V = (vb.get(uvIdx) & 0xFF) - 128;
                int r = Y + ((91881 * V) >> 16);
                int g = Y - ((22554 * U + 46802 * V) >> 16);
                int b = Y + ((116130 * U) >> 16);
                r = r < 0 ? 0 : Math.min(r, 255);
                g = g < 0 ? 0 : Math.min(g, 255);
                b = b < 0 ? 0 : Math.min(b, 255);
                argb[o + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        bitmap.setPixels(argb, 0, w, 0, 0, w, h);
        return bitmap;
    }

    // ------------------------------------------------------------------------------------------------------------

    private void publish(HandLandmarkerResult result) {
        boolean[] seen = new boolean[2];
        List<List<NormalizedLandmark>> all = result.landmarks();
        List<List<Landmark>> world = result.worldLandmarks();
        List<List<Category>> hands = result.handedness();
        for (int i = 0; i < all.size() && i < 2; i++) {
            if (all.get(i).size() < 21 || world.get(i).size() < 21 || hands.get(i).isEmpty()) {
                continue;
            }
            // The rear camera image is not mirrored, which matches what the model expects: labels are correct.
            String label = hands.get(i).get(0).categoryName();
            int hand = "Left".equalsIgnoreCase(label) ? 0 : 1;
            if (seen[hand]) {
                continue;
            }
            float[] joints = toJoints(all.get(i), world.get(i), hand == 0);
            if (joints != null) {
                nativeSetHand(hand, joints, true);
                seen[hand] = true;
                missing[hand] = 0;
            }
        }
        for (int h = 0; h < 2; h++) {
            if (!seen[h] && ++missing[h] == 3) {
                nativeSetHand(h, null, false);
            }
        }
    }

    /** 21 MediaPipe landmarks -> 26 OpenXR joints in head space. */
    private float[] toJoints(List<NormalizedLandmark> lm, List<Landmark> wl, boolean left) {
        // Depth from apparent size: palm bones are known in metres (world landmarks) and in pixels (image).
        double worldLen = dist(wl, 0, 9) + dist(wl, 5, 17);
        double pixLen = pixDist(lm, 0, 9) + pixDist(lm, 5, 17);
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

        // Back-of-hand direction. For a right hand (index on the left when looking at the back) v1 x v2 points
        // to the palm; the left hand is mirrored.
        float[] v1 = sub(p[5], p[0]);
        float[] v2 = sub(p[17], p[0]);
        float[] dorsal = normalize(cross(v1, v2));
        if (!left) {
            dorsal = neg(dorsal);
        }

        float[] out = new float[JOINTS * FLOATS_PER_JOINT];
        for (int k = 0; k < JOINTS; k++) {
            float[] fwd;
            if (k == 0) {
                fwd = sub(p[9], p[0]);
            } else if (k == 1) {
                fwd = sub(p[9], p[0]);
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
