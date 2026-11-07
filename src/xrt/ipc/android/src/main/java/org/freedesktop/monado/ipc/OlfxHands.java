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
    private HandLandmarker cpuLandmarker;
    private HandLandmarker gpuLandmarker;
    private int benchPhase = 0; // 0 = measuring CPU, 1 = measuring GPU, 2 = done
    private long benchFrames = 0;
    private long benchNs = 0;
    private long benchCpuNs = 0;
    private float focalPx = 500f; // focal length in pixels of the raw frame
    private int rotationCcw = 0;  // rotation (counter-clockwise, degrees) that makes the frame upright
    private int uprightW = FRAME_W;
    private int uprightH = FRAME_H;
    private final int[] missing = new int[2];
    private long lastTimestampMs = 0;
    private volatile boolean running = false;
    private boolean swap = false; // user setting: swap left/right

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
        int[] settings = readSettings(ctx);
        if (settings[0] == 0) {
            Log.i(TAG, "Hand tracking is switched off in OLFX");
            return;
        }
        OlfxHands h = new OlfxHands(ctx);
        h.swap = settings[1] == 1;
        try {
            h.open();
            instance = h;
        } catch (Throwable t) {
            Log.e(TAG, "Hand tracking failed to start", t);
            h.close();
        }
    }

    /** {enabled, swapHands} from the OLFX launcher (runtime broker provider); defaults if OLFX is unreachable. */
    private static int[] readSettings(Context ctx) {
        try (android.database.Cursor c =
                ctx.getContentResolver()
                        .query(
                                android.net.Uri.parse("content://org.khronos.openxr.runtime_broker/olfx/hands"),
                                null,
                                null,
                                null,
                                null)) {
            if (c != null && c.moveToFirst()) {
                return new int[] {c.getInt(0), c.getInt(1)};
            }
        } catch (Throwable t) {
            Log.w(TAG, "OLFX hand settings unavailable: " + t);
        }
        return new int[] {1, 0};
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

        // Start on the CPU; onImage() benchmarks the GPU delegate on the first frames and keeps the faster one.
        landmarker = createLandmarker(Delegate.CPU);
        cpuLandmarker = landmarker;
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
        boolean rotated = clockwise == 90 || clockwise == 270;
        uprightW = rotated ? FRAME_H : FRAME_W;
        uprightH = rotated ? FRAME_W : FRAME_H;
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
            long took = System.nanoTime() - t0;
            statDetectNs += took;
            if (benchPhase < 2) {
                benchmark(took);
            }
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

    /** CPU vs GPU delegate: time 60 frames on each, keep the faster (GPU must be clearly faster to be chosen). */
    private void benchmark(long tookNs) {
        benchFrames++;
        benchNs += tookNs;
        if (benchFrames < 60) {
            return;
        }
        long avg = benchNs / benchFrames;
        benchFrames = 0;
        benchNs = 0;
        if (benchPhase == 0) {
            benchCpuNs = avg;
            // created here, on the thread that will use it (a GPU delegate is bound to its creating thread)
            gpuLandmarker = createLandmarker(Delegate.GPU);
            if (gpuLandmarker == null) {
                benchPhase = 2;
                Log.i(TAG, "benchmark: CPU " + avg / 1_000_000L + " ms, GPU unavailable");
                return;
            }
            landmarker = gpuLandmarker;
            benchPhase = 1;
        } else {
            boolean useGpu = avg < benchCpuNs * 0.8;
            Log.i(TAG, "benchmark: CPU " + benchCpuNs / 1_000_000L + " ms, GPU " + avg / 1_000_000L + " ms -> "
                    + (useGpu ? "GPU" : "CPU"));
            landmarker = useGpu ? gpuLandmarker : cpuLandmarker;
            HandLandmarker unused = useGpu ? cpuLandmarker : gpuLandmarker;
            if (unused != null) {
                unused.close();
            }
            benchPhase = 2;
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

    // ---- filtering ---------------------------------------------------------------------------------------------

    /** One Euro filter for a 3-vector: low lag when moving, strong smoothing when still. */
    private static final class OneEuro3 {
        private final float[] minCutoff;
        private final float beta;
        private final float[] x = new float[3];
        private final float[] dx = new float[3];
        private boolean init = false;

        OneEuro3(float minCutoff, float beta) {
            this(new float[] {minCutoff, minCutoff, minCutoff}, beta);
        }

        OneEuro3(float[] minCutoff, float beta) {
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
                float cutoff = minCutoff[i] + beta * Math.abs(dx[i]);
                x[i] = x[i] + alpha(cutoff, dt) * (in[i] - x[i]);
            }
            return x.clone();
        }
    }

    /** Bones as (parent, child) landmark indices, parents always before children (forward kinematics order). */
    private static final int[][] BONES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 4},         // thumb
        {0, 5}, {5, 6}, {6, 7}, {7, 8},         // index
        {0, 9}, {9, 10}, {10, 11}, {11, 12},    // middle
        {0, 13}, {13, 14}, {14, 15}, {15, 16},  // ring
        {0, 17}, {17, 18}, {18, 19}, {19, 20},  // little
    };

    /** One tracked hand: follows a hand over time, owns its filters and its (learned) bone lengths. */
    private static final class Track {
        boolean active;
        long lastSeenNs;
        float wu, wv;       // wrist position in the image (0..1), for matching
        float chirality;    // -1 = left, +1 = right: smoothed model label
        int slot = -1;      // 0 = left output, 1 = right output
        final OneEuro3 root = new OneEuro3(new float[] {1.6f, 1.6f, 0.7f}, 3.0f);
        final OneEuro3[] dir = new OneEuro3[BONES.length];
        final float[] len = new float[BONES.length];
        boolean hasLen;
        long lastFilterNs;
        float[] lastRoot;   // last accepted wrist position (head space)
        int rejected;

        Track() {
            for (int i = 0; i < dir.length; i++) {
                dir[i] = new OneEuro3(5.0f, 10.0f);
            }
        }

        void resetFilters() {
            root.reset();
            for (OneEuro3 f : dir) {
                f.reset();
            }
            hasLen = false;
            lastFilterNs = 0;
            lastRoot = null;
            rejected = 0;
        }
    }

    private final Track[] tracks = {new Track(), new Track()};
    private long statFrames = 0;
    private long statHandFrames = 0;
    private long statDetectNs = 0;
    private long statConvNs = 0;
    private long statZPlus = 0;
    private long statZMinus = 0;
    private double statResid = 0;
    private long statResidN = 0;
    private long statRejected = 0;

    // ---- pose from the 21 landmarks ----------------------------------------------------------------------------

    /**
     * Least-squares translation of the model's metric 3D hand (world landmarks) so that its perspective projection
     * matches the 21 image landmarks. Uses every point and the hand's tilt, so depth does not jump when the hand
     * rotates (apparent size alone does). Returns {tx, ty, tz, residualPx} in camera space (x right, y down,
     * z forward), or null.
     */
    private double[] solveTranslation(List<NormalizedLandmark> lm, List<Landmark> wl, int zSign) {
        double[][] ata = new double[3][3];
        double[] atb = new double[3];
        final double f = focalPx;
        double[] us = new double[21];
        double[] vs = new double[21];
        for (int i = 0; i < 21; i++) {
            double u = lm.get(i).x() * uprightW - uprightW / 2.0;
            double v = lm.get(i).y() * uprightH - uprightH / 2.0;
            us[i] = u;
            vs[i] = v;
            double wx = wl.get(i).x();
            double wy = wl.get(i).y();
            double wz = zSign * wl.get(i).z();
            // f*tx - u*tz = u*wz - f*wx
            accumulate(ata, atb, f, 0, -u, u * wz - f * wx);
            // f*ty - v*tz = v*wz - f*wy
            accumulate(ata, atb, 0, f, -v, v * wz - f * wy);
        }
        double[] t = solve3(ata, atb);
        if (t == null) {
            return null;
        }
        double err = 0;
        for (int i = 0; i < 21; i++) {
            double X = t[0] + wl.get(i).x();
            double Y = t[1] + wl.get(i).y();
            double Z = t[2] + zSign * wl.get(i).z();
            if (Z < 0.02) {
                return null;
            }
            double du = f * X / Z - us[i];
            double dv = f * Y / Z - vs[i];
            err += Math.sqrt(du * du + dv * dv);
        }
        return new double[] {t[0], t[1], t[2], err / 21.0};
    }

    private static void accumulate(double[][] ata, double[] atb, double a0, double a1, double a2, double b) {
        double[] a = {a0, a1, a2};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                ata[r][c] += a[r] * a[c];
            }
            atb[r] += a[r] * b;
        }
    }

    private static double[] solve3(double[][] m, double[] b) {
        double[][] a = new double[3][4];
        for (int r = 0; r < 3; r++) {
            System.arraycopy(m[r], 0, a[r], 0, 3);
            a[r][3] = b[r];
        }
        for (int c = 0; c < 3; c++) {
            int piv = c;
            for (int r = c + 1; r < 3; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][c]) < 1e-9) {
                return null;
            }
            double[] tmp = a[c];
            a[c] = a[piv];
            a[piv] = tmp;
            for (int r = 0; r < 3; r++) {
                if (r != c) {
                    double k = a[r][c] / a[c][c];
                    for (int cc = c; cc < 4; cc++) {
                        a[r][cc] -= k * a[c][cc];
                    }
                }
            }
        }
        return new double[] {a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2]};
    }

    // ---- full pose refinement ------------------------------------------------------------------------------------

    private static double[][] rodrigues(double wx, double wy, double wz) {
        double th = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (th < 1e-12) {
            return new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        }
        double x = wx / th, y = wy / th, z = wz / th;
        double c = Math.cos(th), sn = Math.sin(th), k = 1 - c;
        return new double[][] {
            {c + x * x * k, x * y * k - z * sn, x * z * k + y * sn},
            {y * x * k + z * sn, c + y * y * k, y * z * k - x * sn},
            {z * x * k - y * sn, z * y * k + x * sn, c + z * z * k},
        };
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }

    /** Residuals (px) of projecting R*w + t onto the image landmarks; fills out[42], returns the mean error. */
    private double residuals(double[][] r, double[] t, double[][] w, double[] us, double[] vs, double[] out) {
        double sum = 0;
        for (int i = 0; i < 21; i++) {
            double X = r[0][0] * w[i][0] + r[0][1] * w[i][1] + r[0][2] * w[i][2] + t[0];
            double Y = r[1][0] * w[i][0] + r[1][1] * w[i][1] + r[1][2] * w[i][2] + t[1];
            double Z = r[2][0] * w[i][0] + r[2][1] * w[i][1] + r[2][2] * w[i][2] + t[2];
            if (Z < 0.02) {
                Z = 0.02;
            }
            double du = focalPx * X / Z - us[i];
            double dv = focalPx * Y / Z - vs[i];
            out[2 * i] = du;
            out[2 * i + 1] = dv;
            sum += Math.sqrt(du * du + dv * dv);
        }
        return sum / 21.0;
    }

    /**
     * Gauss-Newton / Levenberg-Marquardt fit of rotation + translation of the model hand to the image landmarks,
     * starting from "no rotation + least-squares translation". Returns {tx, ty, tz, errPx, R(9)} or null.
     */
    private double[] solvePose(List<NormalizedLandmark> lm, List<Landmark> wl, int zSign) {
        double[] t0 = solveTranslation(lm, wl, zSign);
        if (t0 == null) {
            return null;
        }
        double[][] w = new double[21][3];
        double[] us = new double[21];
        double[] vs = new double[21];
        for (int i = 0; i < 21; i++) {
            w[i][0] = wl.get(i).x();
            w[i][1] = wl.get(i).y();
            w[i][2] = zSign * wl.get(i).z();
            us[i] = lm.get(i).x() * uprightW - uprightW / 2.0;
            vs[i] = lm.get(i).y() * uprightH - uprightH / 2.0;
        }
        double[][] rot = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        double[] t = {t0[0], t0[1], t0[2]};
        double[] res = new double[42];
        double cost = residuals(rot, t, w, us, vs, res);
        double lambda = 1e-2;

        for (int iter = 0; iter < 8; iter++) {
            double[][] jac = new double[42][6];
            double[] tmp = new double[42];
            final double eps = 1e-4;
            for (int k = 0; k < 6; k++) {
                double[][] rk = rot;
                double[] tk = t.clone();
                if (k < 3) {
                    double[] d = new double[3];
                    d[k] = eps;
                    rk = mul(rodrigues(d[0], d[1], d[2]), rot);
                } else {
                    tk[k - 3] += eps;
                }
                residuals(rk, tk, w, us, vs, tmp);
                for (int r = 0; r < 42; r++) {
                    jac[r][k] = (tmp[r] - res[r]) / eps;
                }
            }
            double[][] a = new double[6][6];
            double[] g = new double[6];
            for (int r = 0; r < 42; r++) {
                for (int i = 0; i < 6; i++) {
                    g[i] += jac[r][i] * res[r];
                    for (int j = 0; j < 6; j++) {
                        a[i][j] += jac[r][i] * jac[r][j];
                    }
                }
            }
            for (int i = 0; i < 6; i++) {
                a[i][i] *= 1 + lambda;
                a[i][i] += 1e-9;
            }
            double[] step = solveN(a, g);
            if (step == null) {
                break;
            }
            double[][] rn = mul(rodrigues(-step[0], -step[1], -step[2]), rot);
            double[] tn = {t[0] - step[3], t[1] - step[4], t[2] - step[5]};
            double[] rtmp = new double[42];
            double c2 = residuals(rn, tn, w, us, vs, rtmp);
            if (c2 < cost) {
                rot = rn;
                t = tn;
                res = rtmp;
                boolean done = cost - c2 < 1e-3;
                cost = c2;
                lambda *= 0.5;
                if (done) {
                    break;
                }
            } else {
                lambda *= 4;
            }
        }

        double trace = rot[0][0] + rot[1][1] + rot[2][2];
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (trace - 1) / 2))));
        if (angle > 75 || t[2] < 0.05) {
            // Implausible: fall back to the translation-only solution (no rotation).
            return new double[] {t0[0], t0[1], t0[2], t0[3], 1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        return new double[] {
            t[0], t[1], t[2], cost,
            rot[0][0], rot[0][1], rot[0][2], rot[1][0], rot[1][1], rot[1][2], rot[2][0], rot[2][1], rot[2][2]
        };
    }

    /** Solves A x = b for small dense systems (Gaussian elimination with pivoting). */
    private static double[] solveN(double[][] m, double[] b) {
        int n = b.length;
        double[][] a = new double[n][n + 1];
        for (int r = 0; r < n; r++) {
            System.arraycopy(m[r], 0, a[r], 0, n);
            a[r][n] = b[r];
        }
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][c]) < 1e-12) {
                return null;
            }
            double[] t = a[c];
            a[c] = a[piv];
            a[piv] = t;
            for (int r = 0; r < n; r++) {
                if (r != c) {
                    double k = a[r][c] / a[c][c];
                    for (int cc = c; cc <= n; cc++) {
                        a[r][cc] -= k * a[c][cc];
                    }
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = a[i][n] / a[i][i];
        }
        return x;
    }

    /**
     * Rigid, smoothed 21-point hand in head space (x right, y up, -z forward), or null if the frame is not
     * trustworthy.
     */
    private float[][] poseLandmarks(Track tr, List<NormalizedLandmark> lm, List<Landmark> wl) {
        int zSign;
        double[] t;
        if (statZPlus + statZMinus >= 30) {
            // Once enough frames have voted, stick to one axis convention: flipping it per frame bends the hand.
            zSign = statZMinus >= statZPlus ? -1 : 1;
            t = solvePose(lm, wl, zSign);
            if (t == null) {
                return null;
            }
        } else {
            double[] plus = solvePose(lm, wl, +1);
            double[] minus = solvePose(lm, wl, -1);
            if (plus != null && (minus == null || plus[3] <= minus[3])) {
                t = plus;
                zSign = 1;
                statZPlus++;
            } else if (minus != null) {
                t = minus;
                zSign = -1;
                statZMinus++;
            } else {
                return null;
            }
        }
        statResid += t[3];
        statResidN++;
        double depth = t[2];
        if (depth < 0.10 || depth > 1.5 || t[3] > 60.0) {
            return null; // behind the lens, absurdly far or the model does not fit the image
        }

        // Raw landmark positions: the model's metric shape moved to where the image says it is.
        float[][] raw = new float[21][3];
        for (int i = 0; i < 21; i++) {
            double wx = wl.get(i).x();
            double wy = wl.get(i).y();
            double wz = zSign * wl.get(i).z();
            double x = t[0] + t[4] * wx + t[5] * wy + t[6] * wz;
            double y = t[1] + t[7] * wx + t[8] * wy + t[9] * wz;
            double z = t[2] + t[10] * wx + t[11] * wy + t[12] * wz;
            raw[i][0] = (float) x;     // camera x right   -> head x right
            raw[i][1] = (float) -y;    // camera y down    -> head y up
            raw[i][2] = (float) -z;    // camera z forward -> head -z forward
        }

        long now = System.nanoTime();
        if (tr.lastFilterNs != 0 && now - tr.lastFilterNs > 300_000_000L) {
            tr.resetFilters();
        }
        // Reject teleports: a hand cannot move 15 cm between two frames.
        if (tr.lastRoot != null) {
            float dx = raw[0][0] - tr.lastRoot[0];
            float dy = raw[0][1] - tr.lastRoot[1];
            float dz = raw[0][2] - tr.lastRoot[2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > 0.15 && ++tr.rejected <= 3) {
                statRejected++;
                return null;
            }
        }
        tr.rejected = 0;
        float dt = tr.lastFilterNs == 0 ? 0.033f : Math.min(0.2f, Math.max(0.005f, (now - tr.lastFilterNs) / 1e9f));
        tr.lastFilterNs = now;

        float[][] q = new float[21][];
        q[0] = tr.root.filter(raw[0], dt);
        tr.lastRoot = q[0].clone();
        for (int b = 0; b < BONES.length; b++) {
            int pa = BONES[b][0];
            int ch = BONES[b][1];
            float[] vec = sub(raw[ch], raw[pa]);
            float length = (float) Math.sqrt(vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2]);
            if (length < 1e-4f) {
                return null;
            }
            float[] d = {vec[0] / length, vec[1] / length, vec[2] / length};
            if (!tr.hasLen) {
                tr.len[b] = length;
            } else if (length > 0.6f * tr.len[b] && length < 1.5f * tr.len[b]) {
                tr.len[b] += 0.04f * (length - tr.len[b]); // bones do not change length: learn it slowly
            }
            float[] fd = normalize(tr.dir[b].filter(d, dt));
            q[ch] = new float[] {
                q[pa][0] + fd[0] * tr.len[b], q[pa][1] + fd[1] * tr.len[b], q[pa][2] + fd[2] * tr.len[b]
            };
        }
        tr.hasLen = true;
        return q;
    }

    /** 21 landmarks -> 26 OpenXR joint positions. */
    private static float[][] toJoints(float[][] p) {
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

    // ---- who is who ---------------------------------------------------------------------------------------------

    private void publish(HandLandmarkerResult result) {
        List<List<NormalizedLandmark>> all = result.landmarks();
        List<List<Landmark>> world = result.worldLandmarks();
        List<List<Category>> hands = result.handedness();
        long now = System.nanoTime();

        int n = 0;
        int[] det = new int[2];
        for (int i = 0; i < all.size() && n < 2; i++) {
            if (all.get(i).size() >= 21 && world.get(i).size() >= 21) {
                det[n++] = i;
            }
        }

        for (Track t : tracks) {
            if (t.active && now - t.lastSeenNs > 400_000_000L) {
                t.active = false;
            }
        }

        // 1. match detections to existing tracks by wrist position in the image
        Track[] matched = new Track[n];
        boolean[] used = new boolean[2];
        for (int d = 0; d < n; d++) {
            NormalizedLandmark w = all.get(det[d]).get(0);
            int best = -1;
            float bestDist = 0.30f;
            for (int k = 0; k < 2; k++) {
                if (used[k] || !tracks[k].active) {
                    continue;
                }
                float dist = (float) Math.hypot(w.x() - tracks[k].wu, w.y() - tracks[k].wv);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = k;
                }
            }
            if (best >= 0) {
                used[best] = true;
                matched[d] = tracks[best];
            }
        }
        // 2. unmatched detections start new tracks (reuse an inactive one, else the stalest)
        for (int d = 0; d < n; d++) {
            if (matched[d] != null) {
                continue;
            }
            int k = -1;
            for (int c = 0; c < 2; c++) {
                if (!used[c] && !tracks[c].active) {
                    k = c;
                    break;
                }
            }
            if (k < 0) {
                for (int c = 0; c < 2; c++) {
                    if (!used[c]) {
                        k = c;
                        break;
                    }
                }
            }
            if (k < 0) {
                continue;
            }
            used[k] = true;
            Track t = tracks[k];
            t.resetFilters();
            t.active = true;
            t.slot = -1;
            t.chirality = 0;
            matched[d] = t;
        }

        // 3. update tracks: position, smoothed handedness vote
        for (int d = 0; d < n; d++) {
            Track t = matched[d];
            if (t == null) {
                continue;
            }
            NormalizedLandmark w = all.get(det[d]).get(0);
            t.wu = w.x();
            t.wv = w.y();
            t.lastSeenNs = now;
            t.active = true;
            List<Category> cat = hands.get(det[d]);
            if (!cat.isEmpty()) {
                // The model assumes a mirrored image; the rear camera is not mirrored, so its label is swapped.
                float vote = "Left".equalsIgnoreCase(cat.get(0).categoryName()) ? +1f : -1f;
                if (swap) {
                    vote = -vote;
                }
                vote *= Math.min(1f, Math.max(0.2f, cat.get(0).score()));
                t.chirality = t.slot < 0 && t.chirality == 0 ? 0.5f * vote : 0.92f * t.chirality + 0.08f * vote;
            }
        }

        // 4. slots: two hands -> by position (user's left hand is on the left); one hand -> by smoothed label
        Track a = null;
        Track b2 = null;
        for (int d = 0; d < n; d++) {
            if (matched[d] == null) {
                continue;
            }
            if (a == null) {
                a = matched[d];
            } else {
                b2 = matched[d];
            }
        }
        if (a != null && b2 != null) {
            boolean aIsLeft = (a.wu <= b2.wu) != swap;
            Track left = aIsLeft ? a : b2;
            Track right = left == a ? b2 : a;
            assignSlot(left, 0);
            assignSlot(right, 1);
        } else if (a != null) {
            int want = a.chirality < 0 ? 0 : 1;
            if (a.slot < 0 || (a.slot != want && Math.abs(a.chirality) > 0.25f)) {
                assignSlot(a, want);
            }
        }

        // 5. publish
        boolean[] seen = new boolean[2];
        for (int d = 0; d < n; d++) {
            Track t = matched[d];
            if (t == null || t.slot < 0 || seen[t.slot]) {
                continue;
            }
            float[][] q = poseLandmarks(t, all.get(det[d]), world.get(det[d]));
            if (q == null) {
                continue;
            }
            float[] out = buildOutput(toJoints(q), t.slot == 0);
            nativeSetHand(t.slot, out, true);
            seen[t.slot] = true;
            missing[t.slot] = 0;
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
        if (statFrames % 120 == 0 && statResidN > 0) {
            Log.i(TAG, "pose: worldZ sign +" + statZPlus + " / -" + statZMinus + ", fit error "
                    + String.format(java.util.Locale.US, "%.1f", statResid / statResidN) + " px, rejected "
                    + statRejected);
        }
    }

    private void assignSlot(Track t, int slot) {
        if (t.slot != slot) {
            t.slot = slot;
            t.resetFilters(); // joints are mirrored between hands: never blend across a switch
        }
    }

    /** Joint frames (OpenXR convention) from the smoothed joint positions: 26 * (xyz, quat xyzw, radius). */
    private float[] buildOutput(float[][] j, boolean left) {
        // Palm frame. Back-of-hand direction: for a right hand (index on the left when looking at the back) v1 x v2
        // points to the palm; the left hand is mirrored.
        float[] palmFwd = normalize(sub(j[12], j[1]));
        float[] v1 = sub(j[7], j[1]);
        float[] v2 = sub(j[22], j[1]);
        float[] dorsal = cross(v1, v2);
        if (!left) {
            dorsal = neg(dorsal);
        }
        float k0 = dot(dorsal, palmFwd);
        dorsal = normalize(new float[] {dorsal[0] - k0 * palmFwd[0], dorsal[1] - k0 * palmFwd[1], dorsal[2] - k0 * palmFwd[2]});
        // Across the hand (finger flexion axis): x = y x z = fwd x dorsal for OpenXR joint frames.
        float[] lateral = normalize(cross(palmFwd, dorsal));

        float[] out = new float[JOINTS * FLOATS_PER_JOINT];
        for (int k = 0; k < JOINTS; k++) {
            float[] q;
            if (k <= 1) {
                q = quatFromAxes(lateral, dorsal, neg(palmFwd)); // palm, wrist
            } else {
                int next = nextJoint(k);
                float[] fwd = normalize(next >= 0 ? sub(j[next], j[k]) : sub(j[k], j[prevJoint(k)]));
                if (k >= 6) {
                    // Fingers bend around the axis across the hand, which stays well defined however far the finger
                    // curls (the palm normal does not: a curled bone becomes parallel to it and the frame spins).
                    q = flexFrame(fwd, lateral);
                } else {
                    float[] z = neg(fwd);
                    if (norm(cross(dorsal, z)) > 0.3f) {
                        q = orientation(fwd, dorsal); // thumb: its own plane, fine unless nearly aligned
                    } else {
                        q = flexFrame(fwd, lateral);
                    }
                }
            }
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

    /** Frame with -Z along the bone and X as close as possible to {@code lateral}. */
    private static float[] flexFrame(float[] forward, float[] lateral) {
        float[] z = neg(forward);
        float d = dot(lateral, z);
        float[] x = normalize(new float[] {lateral[0] - d * z[0], lateral[1] - d * z[1], lateral[2] - d * z[2]});
        float[] y = cross(z, x);
        return quatFromAxes(x, y, z);
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float norm(float[] a) {
        return (float) Math.sqrt(dot(a, a));
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
        return quatFromAxes(x, y, z);
    }

    /** Rotation matrix with columns x, y, z -> quaternion (x, y, z, w). */
    private static float[] quatFromAxes(float[] x, float[] y, float[] z) {
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
            if (cpuLandmarker != null && cpuLandmarker != landmarker) cpuLandmarker.close();
            if (gpuLandmarker != null && gpuLandmarker != landmarker) gpuLandmarker.close();
        } catch (Exception ignored) {
        }
        if (thread != null) {
            thread.quitSafely();
        }
        nativeSetHand(0, null, false);
        nativeSetHand(1, null, false);
    }
}
