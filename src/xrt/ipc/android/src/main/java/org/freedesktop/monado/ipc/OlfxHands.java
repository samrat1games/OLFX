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
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
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
    // Negotiated at runtime; 480x360 is enough for the landmark model and cheaper than 640x480.
    private static final int TARGET_W = 640;
    private static final int TARGET_H = 480;
    private static final int MAX_W = 800;
    private static final int MAX_H = 600;
    private static final int JOINTS = 26;
    private static final int FLOATS_PER_JOINT = 8;
    // Latest-frame-only pipeline: never build an unbounded camera/inference queue.
    private static final long MIN_FRAME_INTERVAL_NS = 33_333_333L; // ~30 Hz inference cap
    private static final long PREVIEW_INTERVAL_NS = 66_666_666L;   // test preview ~15 Hz

    static native void nativeSetHand(int hand, float[] joints, boolean active, long timestampNs);

    /** Bitmask: 1=device created, 2=left active, 4=right active, 8=XR_EXT_hand_tracking compiled,
     *  16=XR_EXT_hand_interaction compiled. Used by the in-app test to diagnose OpenXR delivery. */
    public static native int nativeHandTrackingStatus();

    /** Java-side delivery counters for the in-app test (not a substitute for a game-side OpenXR probe). */
    public static int activeHandsMask() { return activeHandsMaskStatic; }
    public static long lastPublishAgeMs() {
        long t = lastPublishNsStatic;
        return t == 0 ? -1 : Math.max(0, (System.nanoTime() - t) / 1_000_000L);
    }
    public static long fallbackFrames() { return fallbackFramesStatic; }
    private static volatile int activeHandsMaskStatic = 0;
    private static volatile long lastPublishNsStatic = 0;
    private static volatile long fallbackFramesStatic = 0;

    /** Receives deliberate pinch gestures; implemented by the user-enabled accessibility service. */
    public interface PinchListener {
        void onPinch(int hand, float screenX, float screenY, long timestampNs);
    }

    private static volatile PinchListener pinchListener;

    public static void setPinchListener(PinchListener listener) {
        pinchListener = listener;
    }

    static native boolean nativeYuvToRgba(
            java.nio.ByteBuffer y, java.nio.ByteBuffer u, java.nio.ByteBuffer v, int yRow, int uRow, int uPix,
            int vRow, int vPix, int w, int h, java.nio.ByteBuffer out);

    /** Visual hand-tracking test callback (camera frame + landmarks). */
    public interface TestListener {
        void onHandFrame(Bitmap frame, List<List<NormalizedLandmark>> uprightLandmarks, long detectNs, float fps);
        void onHandError(String message);
    }

    private static OlfxHands instance;
    private static final Handler RETRY_HANDLER = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicInteger RETRY_ATTEMPT = new java.util.concurrent.atomic.AtomicInteger();
    private static Runnable runtimeRetry;

    private final Context context;
    private final TestListener testListener;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandLandmarker landmarker;
    private int frameW = TARGET_W;
    private int frameH = TARGET_H;
    private float focalX = 415f;
    private float focalY = 415f;
    private CameraCharacteristics cameraCharacteristics;
    private boolean cameraTimestampRealtime = false;
    private DisplayManager.DisplayListener displayListener;
    private ImageProcessingOptions imageProcessingOptions = ImageProcessingOptions.builder().build();
    private int rotationClockwise = 0; // MediaPipe ImageProcessingOptions expects CLOCKWISE degrees.
    private int uprightW = TARGET_W;
    private int uprightH = TARGET_H;
    private final long[] lastSeenHandNs = new long[2];
    private final boolean[] publishedHand = new boolean[2];
    private long lastTimestampMs = 0;
    private long lastProcessNs = 0;
    private long lastPreviewNs = 0;
    private long lastAcceptedNs = 0;
    private int frameFailures = 0;
    private volatile boolean running = false;
    private boolean swap = false;
    private java.nio.ByteBuffer rgba;
    private Bitmap rawBitmap;
    private final Matrix previewMatrix = new Matrix();
    private ContentObserver settingsObserver;

    private OlfxHands(Context ctx, TestListener listener) {
        this.context = ctx.getApplicationContext();
        this.testListener = listener;
    }

    /** Starts runtime tracking if enabled and camera permission is granted. */
    public static synchronized void start(Context ctx) {
        startInternal(ctx, null, false);
    }

    /** Starts the in-app visual test, replacing any runtime camera session. */
    public static synchronized void startTest(Context ctx, TestListener listener) {
        startInternal(ctx, listener, true);
    }

    private static void startInternal(Context ctx, TestListener listener, boolean test) {
        if (instance != null) {
            if ((test && instance.testListener == null) || (!instance.running && instance.testListener == null)) {
                instance.close();
                instance = null;
            } else {
                return;
            }
        }
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (test) {
                if (listener != null) listener.onHandError("camera_permission");
            } else {
                Log.w(TAG, "No camera permission: asking");
                try {
                    Intent i = new Intent(ctx, CameraPermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "Could not show the camera permission request: " + e);
                }
            }
            return;
        }
        int[] settings = readSettings(ctx);
        if (!test && settings[0] == 0) {
            Log.i(TAG, "Hand tracking is switched off in OLFX");
            return;
        }
        OlfxHands h = new OlfxHands(ctx, listener);
        h.swap = settings[1] == 1;
        // Assign before posting initialization so stop()/settings changes cannot race a half-built instance.
        instance = h;
        h.openAsync();
    }

    private static int[] readSettings(Context ctx) {
        try (android.database.Cursor c =
                ctx.getContentResolver()
                        .query(
                                android.net.Uri.parse("content://" + ctx.getPackageName() + ".settings/olfx/hands"),
                                null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return new int[] {c.getInt(0), c.getInt(1)};
            }
        } catch (Throwable t) {
            Log.w(TAG, "OLFX hand settings unavailable: " + t);
        }
        return new int[] {1, 0};
    }

    public static synchronized void stop() {
        clearRuntimeRetry();
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public static synchronized void stopTest() {
        clearRuntimeRetry();
        if (instance != null && instance.testListener != null) {
            instance.close();
            instance = null;
        }
    }

    private static void clearRuntimeRetry() {
        RETRY_ATTEMPT.set(0);
        if (runtimeRetry != null) {
            RETRY_HANDLER.removeCallbacks(runtimeRetry);
            runtimeRetry = null;
        }
    }

    /** Bounded recovery for camera-open/inference failures. Never retries the visual test or a stopped service. */
    private static synchronized void scheduleRuntimeRetry(Context ctx) {
        if (MonadoService.Companion.isRunning() == false) return;
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        int attempt = RETRY_ATTEMPT.getAndIncrement();
        if (attempt >= 4 || runtimeRetry != null) return;
        final Context app = ctx.getApplicationContext();
        runtimeRetry = () -> {
            synchronized (OlfxHands.class) {
                runtimeRetry = null;
                startInternal(app, null, false);
            }
        };
        RETRY_HANDLER.postDelayed(runtimeRetry, 1000L << attempt);
        Log.w(TAG, "Scheduling hand-camera recovery attempt " + (attempt + 1));
    }

    private void openAsync() {
        thread = new HandlerThread("olfx-hands");
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        // Model loading, camera negotiation and inference stay off the UI/main thread.
        handler.post(() -> {
            try {
                initializeOnThread();
            } catch (Throwable t) {
                fail(t);
            }
        });
    }

    private void initializeOnThread() throws Exception {
        if (!running) return;
        // CPU delegate is the stable default for VRMobile: it does not contend with the compositor GPU queue.
        landmarker = createLandmarker();
        if (landmarker == null) {
            throw new IllegalStateException("model_unavailable");
        }
        if (!running) return;

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
        if (id == null || chars == null) {
            throw new IllegalStateException("no_back_camera");
        }
        cameraCharacteristics = chars;
        if (Build.VERSION.SDK_INT >= 28) {
            Integer source = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            cameraTimestampRealtime = source != null
                    && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        }
        negotiateSize(chars);
        computeGeometry(chars);
        // Games commonly rotate the display after the service has already opened the camera.
        // Re-project into the new upright frame instead of keeping the launcher portrait geometry.
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY && running && cameraCharacteristics != null) {
                    computeGeometry(cameraCharacteristics);
                }
            }
        };
        dm.registerDisplayListener(displayListener, handler);

        reader = ImageReader.newInstance(frameW, frameH, ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(this::onImage, handler);

        settingsObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                applyLiveSettings();
            }
        };
        try {
            context.getContentResolver().registerContentObserver(
                    android.net.Uri.parse("content://" + context.getPackageName() + ".settings/olfx/hands"),
                    true, settingsObserver);
        } catch (Exception ignored) {
        }
        cm.openCamera(
                id,
                new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice device) {
                        if (!running) {
                            device.close();
                            return;
                        }
                        camera = device;
                        startSession(device);
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice device) {
                        fail(new IllegalStateException("camera_disconnected"));
                    }

                    @Override
                    public void onError(@NonNull CameraDevice device, int error) {
                        fail(new IllegalStateException("camera_error_" + error));
                    }
                },
                handler);
        Log.i(TAG, "camera " + id + " opening, size=" + frameW + "x" + frameH + ", focal=" + focalX
                + "/" + focalY + "px, rotateCw=" + rotationClockwise);
    }

    /** Pick a supported YUV size instead of assuming every device exposes 640x480. */
    private void negotiateSize(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        if (map != null) {
            for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                int w = s.getWidth(), h = s.getHeight();
                if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H || (w % 2) != 0 || (h % 2) != 0) continue;
                double area = (double) w * h;
                double targetArea = (double) TARGET_W * TARGET_H;
                double aspect = (double) w / h;
                double score = Math.abs(area - targetArea) / targetArea + Math.abs(aspect - 4.0 / 3.0) * 0.25;
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("no_supported_yuv_size");
        }
        frameW = best.getWidth();
        frameH = best.getHeight();
    }

    private HandLandmarker createLandmarker() {
        // GPU first (the CPU path took ~100 ms a frame on this phone, i.e. 7 fps), CPU as the fallback.
        for (Delegate dg : new Delegate[] {Delegate.GPU, Delegate.CPU}) {
            try {
                HandLandmarker.HandLandmarkerOptions options =
                        HandLandmarker.HandLandmarkerOptions.builder()
                                .setBaseOptions(
                                        BaseOptions.builder()
                                                .setModelAssetPath("hand_landmarker.task")
                                                .setDelegate(dg)
                                                .build())
                                .setRunningMode(RunningMode.VIDEO)
                                .setNumHands(2)
                                .setMinHandDetectionConfidence(0.5f)
                                .setMinHandPresenceConfidence(0.5f)
                                .setMinTrackingConfidence(0.35f)
                                .build();
                HandLandmarker l = HandLandmarker.createFromOptions(context, options);
                Log.i(TAG, "hand landmarker running on " + dg);
                return l;
            } catch (Throwable t) {
                Log.w(TAG, "hand landmarker " + dg + " failed: " + t);
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void startSession(CameraDevice device) {
        try {
            final CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            int[] afModes = cameraCharacteristicsAfModes(device);
            if (afModes != null) {
                for (int mode : afModes) {
                    if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                        b.set(CaptureRequest.CONTROL_AF_MODE, mode);
                        break;
                    }
                }
            }
            // Short exposures and no image warping: motion blur and video stabilisation are what make fast hands vanish.
            try {
                android.util.Range<Integer>[] ranges =
                        cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                android.util.Range<Integer> best = null;
                if (ranges != null) {
                    for (android.util.Range<Integer> r : ranges) {
                        if (r.getUpper() > 60) continue;
                        if (best == null || r.getUpper() > best.getUpper()
                                || (r.getUpper().equals(best.getUpper()) && r.getLower() > best.getLower())) {
                            best = r;
                        }
                    }
                }
                if (best != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);
            } catch (Throwable t) {
                Log.w(TAG, "camera tuning skipped: " + t);
            }
            device.createCaptureSession(
                    Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession s) {
                            if (!running) {
                                s.close();
                                return;
                            }
                            session = s;
                            try {
                                s.setRepeatingRequest(b.build(), null, handler);
                            } catch (Exception e) {
                                fail(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            fail(new IllegalStateException("capture_configure_failed"));
                        }
                    },
                    handler);
        } catch (Exception e) {
            fail(e);
        }
    }

    private int[] cameraCharacteristicsAfModes(CameraDevice device) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(cid);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void computeGeometry(CameraCharacteristics c) {
        int oldRotation = rotationClockwise;
        float oldFx = focalX;
        float oldFy = focalY;
        Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int so = sensorOrientation != null ? sensorOrientation : 90;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        int rot = 0;
        if (d != null) {
            switch (d.getRotation()) {
                case android.view.Surface.ROTATION_90: rot = 90; break;
                case android.view.Surface.ROTATION_180: rot = 180; break;
                case android.view.Surface.ROTATION_270: rot = 270; break;
                default: rot = 0; break;
            }
        }
        rotationClockwise = HandImageGeometry.clockwiseRotation(so, rot);
        if (rotationClockwise != oldRotation) {
            imageProcessingOptions = ImageProcessingOptions.builder().setRotationDegrees(rotationClockwise).build();
        }
        boolean rotated = rotationClockwise == 90 || rotationClockwise == 270;
        uprightW = rotated ? frameH : frameW;
        uprightH = rotated ? frameW : frameH;

        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        android.util.SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        android.graphics.Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        float[] calibrated = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if (calibrated != null && calibrated.length >= 2 && calibrated[0] > 0 && calibrated[1] > 0
                && pixels != null && pixels.getWidth() > 0 && pixels.getHeight() > 0) {
            float fx = calibrated[0] * frameW / pixels.getWidth();
            float fy = calibrated[1] * frameH / pixels.getHeight();
            focalX = rotated ? fy : fx;
            focalY = rotated ? fx : fy;
        } else {
            float[] f = HandImageGeometry.focalPixels(
                    focals != null && focals.length > 0 ? focals[0] : 0f,
                    sensor != null ? sensor.getWidth() : 0f,
                    pixels != null ? pixels.getWidth() : 0,
                    pixels != null ? pixels.getHeight() : 0,
                    active != null ? active.width() : 0,
                    active != null ? active.height() : 0,
                    frameW, frameH, rotationClockwise);
            focalX = f[0];
            focalY = f[1];
        }
        if (oldRotation != rotationClockwise || Math.abs(oldFx - focalX) > 0.5f || Math.abs(oldFy - focalY) > 0.5f) {
            resetTrackingForGeometryChange();
            Log.i(TAG, "hand geometry updated: upright=" + uprightW + "x" + uprightH
                    + ", focal=" + focalX + "/" + focalY + ", rotateCw=" + rotationClockwise);
        }
    }

    private void resetTrackingForGeometryChange() {
        for (Track t : tracks) {
            t.active = false;
            t.slot = -1;
            t.chirality = 0;
            t.resetFilters();
        }
        java.util.Arrays.fill(lastSeenHandNs, 0L);
        java.util.Arrays.fill(publishedHand, false);
        lastTimestampMs = 0;
        nativeSetHand(0, null, false, 0L);
        nativeSetHand(1, null, false, 0L);
    }

    private void applyLiveSettings() {
        int[] settings = readSettings(context);
        if (settings[0] == 0) {
            if (testListener != null) {
                fail(new IllegalStateException("hand_disabled"));
            } else {
                stop();
            }
            return;
        }
        boolean newSwap = settings[1] == 1;
        if (newSwap != swap) {
            swap = newSwap;
            for (Track t : tracks) {
                t.active = false;
                t.slot = -1;
                t.chirality = 0;
                t.resetFilters();
            }
            nativeSetHand(0, null, false, 0L);
            nativeSetHand(1, null, false, 0L);
        }
    }

    private void onImage(ImageReader r) {
        if (!running) return;
        Image image = null;
        try {
            image = r.acquireLatestImage(); // drop stale frames; bounded latency, no queue growth
            if (image == null || !running || landmarker == null) return;
            long now = System.nanoTime();
            if (now - lastProcessNs < MIN_FRAME_INTERVAL_NS) return;
            lastProcessNs = now;

            final int width = image.getWidth();
            final int height = image.getHeight();
            final long imageTimestampNs = HandFrameClock.normalizeTimestampNs(
                    image.getTimestamp(), now, SystemClock.elapsedRealtimeNanos(), cameraTimestampRealtime);
            long ts = HandFrameClock.nextMediaPipeTimestampMs(lastTimestampMs, imageTimestampNs);
            lastTimestampMs = ts;

            HandLandmarkerResult result;
            long took;
            long tc = System.nanoTime();
            try (MPImage mp = toMpImage(image)) {
                statConvNs += System.nanoTime() - tc;
                // The model reads our RGBA buffer. Return the camera's YUV buffer before inference.
                image.close();
                image = null;
                long t0 = System.nanoTime();
                result = landmarker.detectForVideo(mp, imageProcessingOptions, ts);
                took = System.nanoTime() - t0;
            }
            statDetectNs += took;
            // MediaPipe's normalized output is still in the unrotated camera frame. Rotate it once;
            // use the SAME upright points for the preview, tracking association and perspective fit.
            List<List<NormalizedLandmark>> upright = uprightLandmarks(result);
            publish(result, upright, imageTimestampNs);
            frameFailures = 0;

            // Report processed-frame throughput, not the inverse of inference time.
            if (lastAcceptedNs != 0) {
                long delta = now - lastAcceptedNs;
                if (delta > 0) {
                    float instantFps = 1_000_000_000f / delta;
                    fpsEma = fpsEma <= 0f ? instantFps : fpsEma * 0.9f + instantFps * 0.1f;
                }
            }
            lastAcceptedNs = now;
            TestListener l = testListener;
            if (l != null && now - lastPreviewNs >= PREVIEW_INTERVAL_NS) {
                lastPreviewNs = now;
                Bitmap frame = makePreviewFrame(width, height);
                if (frame != null) l.onHandFrame(frame, upright, took, fpsEma);
            }
            if (statFrames > 0 && statFrames % 120 == 0) {
                Log.i(TAG, "tracking: convert " + (statConvNs / statFrames / 1_000_000L) + " ms, detect avg "
                        + (statDetectNs / statFrames / 1_000_000L) + " ms, hand in view "
                        + (100 * statHandFrames / statFrames) + "% of " + statFrames + " frames");
            }
        } catch (Throwable t) {
            frameFailures++;
            if (frameFailures >= 10) {
                fail(t);
            } else {
                Log.w(TAG, "frame failed (" + frameFailures + "/10): " + t);
            }
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap makePreviewFrame(int w, int h) {
        if (rgba == null) return null;
        if (rawBitmap == null || rawBitmap.getWidth() != w || rawBitmap.getHeight() != h) {
            rawBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        java.nio.ByteBuffer copy = rgba.duplicate();
        copy.rewind();
        rawBitmap.copyPixelsFromBuffer(copy);
        if (rotationClockwise == 0) {
            return rawBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        previewMatrix.reset();
        previewMatrix.postRotate(rotationClockwise);
        return Bitmap.createBitmap(rawBitmap, 0, 0, w, h, previewMatrix, true);
    }

    private MPImage toMpImage(Image image) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        if (rgba == null || rgba.capacity() != w * h * 4) {
            rgba = java.nio.ByteBuffer.allocateDirect(w * h * 4);
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            throw new IllegalArgumentException("yuv_unsupported");
        }
        boolean converted = nativeYuvToRgba(planes[0].getBuffer(), planes[1].getBuffer(), planes[2].getBuffer(),
                planes[0].getRowStride(), planes[1].getRowStride(), planes[1].getPixelStride(),
                planes[2].getRowStride(), planes[2].getPixelStride(), w, h, rgba);
        if (!converted) {
            throw new IllegalArgumentException("yuv_unsupported");
        }
        rgba.rewind();
        return new ByteBufferImageBuilder(rgba, w, h, MPImage.IMAGE_FORMAT_RGBA).build();
    }

    private List<List<NormalizedLandmark>> uprightLandmarks(HandLandmarkerResult result) {
        if (rotationClockwise == 0) return result.landmarks();
        java.util.ArrayList<List<NormalizedLandmark>> hands = new java.util.ArrayList<>(result.landmarks().size());
        for (List<NormalizedLandmark> hand : result.landmarks()) {
            java.util.ArrayList<NormalizedLandmark> points = new java.util.ArrayList<>(hand.size());
            for (NormalizedLandmark p : hand) {
                points.add(NormalizedLandmark.create(
                        HandImageGeometry.rotateX(p.x(), p.y(), rotationClockwise),
                        HandImageGeometry.rotateY(p.x(), p.y(), rotationClockwise),
                        p.z(), p.visibility(), p.presence()));
            }
            hands.add(points);
        }
        return hands;
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

    /** State belongs to one hand; loss resets filters before the next detection. */
    private static final class Track {
        boolean active;
        long lastSeenNs;
        float wu, wv;
        float chirality;
        int slot = -1;
        final PinchGesture pinch = new PinchGesture();
        final OneEuro3[] points = new OneEuro3[21];
        final float[][] prevQuat = new float[JOINTS][];
        long lastFilterNs;
        float[] lastRoot;

        Track() {
            points[0] = new OneEuro3(new float[] {0.5f, 0.5f, 0.15f}, 2.0f);
            for (int i = 1; i < points.length; i++) points[i] = new OneEuro3(0.8f, 3.0f);
        }

        void resetFilters() {
            for (OneEuro3 f : points) f.reset();
            java.util.Arrays.fill(prevQuat, null);
            lastFilterNs = 0;
            lastRoot = null;
            pinch.reset();
        }
    }

    private final Track[] tracks = {new Track(), new Track()};
    private long dbgN = 0;
    private long lastDbgNs = 0;
    private long statFrames = 0;
    private long statHandFrames = 0;
    private long statDetectNs = 0;
    private long statConvNs = 0;
    private volatile long lastNativePublishNs = 0;
    private volatile int activeHandsMask = 0;
    private float fpsEma = 0f;

    /** Detect a deliberate thumb+index pinch and report a normalized screen tap position. */
    private void maybePinch(Track t, List<NormalizedLandmark> lm, long nowNs) {
        PinchListener listener = pinchListener;
        if (listener == null || lm.size() < 21) return;
        NormalizedLandmark thumb = lm.get(4);
        NormalizedLandmark index = lm.get(8);
        NormalizedLandmark wrist = lm.get(0);
        NormalizedLandmark middle = lm.get(9);
        float tipDx = (thumb.x() - index.x()) * uprightW;
        float tipDy = (thumb.y() - index.y()) * uprightH;
        float palmDx = (wrist.x() - middle.x()) * uprightW;
        float palmDy = (wrist.y() - middle.y()) * uprightH;
        float palm = (float) Math.sqrt(palmDx * palmDx + palmDy * palmDy);
        float ratio = palm > 1e-4f ? (float) Math.sqrt(tipDx * tipDx + tipDy * tipDy) / palm : Float.NaN;
        if (t.pinch.update(ratio, nowNs)) {
            // Upright camera frame -> display. The rear camera is not mirrored, but the screen faces
            // the user, so mirror X once here to make hand-right map to screen-right.
            float nx = 1f - 0.5f * (thumb.x() + index.x());
            float ny = 0.5f * (thumb.y() + index.y());
            nx = Math.max(0f, Math.min(1f, nx));
            ny = Math.max(0f, Math.min(1f, ny));
            listener.onPinch(t.slot, nx, ny, nowNs);
        }
    }

    /** Keep MediaPipe's metric hand shape and estimate only its camera-relative translation. */
    private float[][] poseLandmarks(Track tr, List<NormalizedLandmark> lm, List<Landmark> wl) {
        float[][] image = new float[21][2];
        float[][] world = new float[21][3];
        for (int i = 0; i < 21; i++) {
            image[i][0] = lm.get(i).x();
            image[i][1] = lm.get(i).y();
            world[i][0] = wl.get(i).x();
            world[i][1] = wl.get(i).y();
            world[i][2] = wl.get(i).z();
        }
        float[][] pose = MediaPipeHandPose.convert(image, world, uprightW, uprightH,
                focalX, focalY, rotationClockwise);
        if (pose == null) return null;
        long now = System.nanoTime();
        if (tr.lastFilterNs != 0 && now - tr.lastFilterNs > 300_000_000L) tr.resetFilters();
        float dt = tr.lastFilterNs == 0 ? 0.033f
                : Math.min(0.2f, Math.max(0.005f, (now - tr.lastFilterNs) / 1e9f));
        tr.lastFilterNs = now;
        // The inferred depth can spike when the hand turns: limit translation as a whole,
        // leaving individual finger motion untouched (the same policy as the iOS tracker).
        if (tr.lastRoot != null) {
            float[] delta = sub(pose[0], tr.lastRoot);
            float distance = norm(delta);
            float limit = Math.max(0.035f, Math.min(0.12f, dt * 1.2f));
            if (distance > limit) {
                float correction = 1f - limit / distance;
                for (float[] point : pose) for (int c = 0; c < 3; c++) point[c] -= delta[c] * correction;
            }
        }
        float[] rawRoot = pose[0];
        float[] root = tr.points[0].filter(rawRoot, dt);
        for (int i = 1; i < 21; i++) {
            float[] relative = tr.points[i].filter(sub(pose[i], rawRoot), dt);
            pose[i] = new float[] {root[0] + relative[0], root[1] + relative[1], root[2] + relative[2]};
        }
        pose[0] = root;
        tr.lastRoot = root.clone();
        return pose;
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

    private void publish(HandLandmarkerResult result, List<List<NormalizedLandmark>> upright, long imageTimestampNs) {
        List<List<NormalizedLandmark>> all = upright;
        List<List<Landmark>> world = result.worldLandmarks();
        List<List<Category>> hands = result.handedness();
        long now = System.nanoTime();

        int n = 0;
        int[] det = new int[2];
        for (int i = 0; i < all.size() && n < 2; i++) {
            if (i < world.size() && all.get(i).size() >= 21 && world.get(i).size() >= 21) {
                det[n++] = i;
            }
        }

        for (Track t : tracks) {
            if (t.active && now - t.lastSeenNs > 400_000_000L) {
                t.active = false;
            }
        }

        // Associate both detections together: greedy matching depends on MediaPipe's output order
        // and can swap identities when one wrist is close to both existing tracks.
        Track[] matched = new Track[n];
        boolean[] used = new boolean[2];
        float[][] distance = new float[n][2];
        for (int d = 0; d < n; d++) {
            NormalizedLandmark w = all.get(det[d]).get(0);
            for (int k = 0; k < 2; k++) {
                distance[d][k] = tracks[k].active
                        ? (float) Math.hypot(w.x() - tracks[k].wu, w.y() - tracks[k].wv)
                        : Float.POSITIVE_INFINITY;
            }
        }
        int[] assignment = HandAssociation.match(distance);
        int best0 = assignment[0], best1 = assignment[1];
        if (n > 0 && best0 >= 0) {
            matched[0] = tracks[best0];
            used[best0] = true;
        }
        if (n > 1 && best1 >= 0) {
            matched[1] = tracks[best1];
            used[best1] = true;
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
            List<Category> cat = det[d] < hands.size() ? hands.get(det[d]) : java.util.Collections.emptyList();
            if (!cat.isEmpty()) {
                // MediaPipe handedness assumes a mirrored selfie image. The rear camera is not mirrored,
                // so a reported "Left" is the user's right hand (and vice versa) unless the user swaps them.
                // Measured on the device: the labels already match the side of the picture, so "Left" is the left slot.
                float vote = "Left".equalsIgnoreCase(cat.get(0).categoryName()) ? -1f : +1f;
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
        if (a != null && b2 != null && a.slot >= 0 && b2.slot >= 0 && a.slot != b2.slot) {
            // Matched tracks retain their identities even when the hands cross.
        } else if (a != null && b2 != null && a.slot >= 0 && b2.slot < 0) {
            assignSlot(b2, 1 - a.slot);
        } else if (a != null && b2 != null && b2.slot >= 0 && a.slot < 0) {
            assignSlot(a, 1 - b2.slot);
        } else if (a != null && b2 != null) {
            // Two hands apart in the picture: their position is far more reliable than the model's label.
            boolean apart = Math.abs(a.wu - b2.wu) > 0.18f;
            boolean confident = !apart && Math.abs(a.chirality) > 0.2f && Math.abs(b2.chirality) > 0.2f
                    && (a.chirality < 0f) != (b2.chirality < 0f);
            Track left;
            Track right;
            if (confident) {
                left = a.chirality < 0f ? a : b2;
                right = a.chirality < 0f ? b2 : a;
            } else {
                boolean aIsLeft = (a.wu <= b2.wu) != swap;
                left = aIsLeft ? a : b2;
                right = aIsLeft ? b2 : a;
            }
            assignSlot(left, 0);
            assignSlot(right, 1);
        } else if (a != null) {
            // Once assigned, retain identity until the track expires. Handedness often flips
            // temporarily when a hand turns or grips a controller.
            if (a.slot < 0) {
                assignSlot(a, a.chirality < 0 ? 0 : 1);
            }
        }

        // 5. publish
        boolean[] seen = new boolean[2];
        for (int d = 0; d < n; d++) {
            Track t = matched[d];
            if (t == null || t.slot < 0 || seen[t.slot]) {
                continue;
            }
            // Pinch clicks use the 2D landmarks directly, so they still work when the 3D pose fit
            // rejects a frame; they do not depend on the monocular depth solution.
            maybePinch(t, all.get(det[d]), now);
            float[][] q = poseLandmarks(t, all.get(det[d]), world.get(det[d]));
            if (q == null) {
                continue;
            }
            float[] out = buildOutput(toJoints(q), t.slot == 0, t);
            if (++dbgN % 4 == 0) {
                List<Category> dc = det[d] < hands.size() ? hands.get(det[d]) : java.util.Collections.emptyList();
                float[] w0 = q[0];
                float tip = 0;
                for (int f : new int[] {8, 12, 16, 20}) {
                    tip += (float) Math.sqrt(Math.pow(q[f][0] - w0[0], 2) + Math.pow(q[f][1] - w0[1], 2) + Math.pow(q[f][2] - w0[2], 2));
                }
                Log.i("OlfxHandsDbg", String.format(java.util.Locale.US,
                        "n=%d slot=%d label=%s/%.2f chir=%.2f uv=%.2f,%.2f wrist=(%.3f %.3f %.3f) tipAvg=%.3f idx=(%.3f %.3f %.3f) dtMs=%d",
                        n, t.slot, dc.isEmpty() ? "-" : dc.get(0).categoryName(), dc.isEmpty() ? 0f : dc.get(0).score(),
                        t.chirality, t.wu, t.wv, w0[0], w0[1], w0[2], tip / 4f, q[8][0], q[8][1], q[8][2],
                        (now - lastDbgNs) / 1000000));
                lastDbgNs = now;
            }
            nativeSetHand(t.slot, out, true, imageTimestampNs);
            lastNativePublishNs = now;
            lastPublishNsStatic = now;
            seen[t.slot] = true;
            lastSeenHandNs[t.slot] = now;
            publishedHand[t.slot] = true;
        }
        for (int h = 0; h < 2; h++) {
            if (!seen[h] && publishedHand[h] && now - lastSeenHandNs[h] > 450_000_000L) {
                nativeSetHand(h, null, false, 0L);
                publishedHand[h] = false;
            }
        }
        activeHandsMask = (seen[0] ? 1 : 0) | (seen[1] ? 2 : 0);
        activeHandsMaskStatic = activeHandsMask;
        if (activeHandsMask != 0) RETRY_ATTEMPT.set(0);
        statFrames++;
        if (seen[0] || seen[1]) {
            statHandFrames++;
        }

    }

    private void assignSlot(Track t, int slot) {
        if (t.slot != slot) {
            t.slot = slot;
            t.resetFilters(); // joints are mirrored between hands: never blend across a switch
        }
    }

    /** Joint frames (OpenXR convention) from the smoothed joint positions: 26 * (xyz, quat xyzw, radius). */
    private float[] buildOutput(float[][] j, boolean left, Track track) {
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
            // Short quaternion nlerp removes frame-to-frame joint-frame spin without adding a prediction queue.
            float[] previous = track.prevQuat[k];
            if (previous != null) {
                float sign = dot4(previous, q) < 0f ? -1f : 1f;
                float[] blended = new float[4];
                for (int c = 0; c < 4; c++) blended[c] = previous[c] * 0.6f + q[c] * sign * 0.4f;
                q = normalize4(blended);
            }
            track.prevQuat[k] = q;
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

    private static float dot4(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    }

    private static float[] normalize4(float[] q) {
        float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n < 1e-9f) return new float[] {0f, 0f, 0f, 1f};
        return new float[] {q[0] / n, q[1] / n, q[2] / n, q[3] / n};
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

    private void fail(Throwable t) {
        Log.e(TAG, "hand tracking failed", t);
        running = false;
        String message = t.getMessage();
        if (message == null || message.isEmpty()) message = "hand_error";
        TestListener l = testListener;
        if (l != null) l.onHandError(message);
        close();
        synchronized (OlfxHands.class) {
            if (instance == this) instance = null;
        }
        if (testListener == null) scheduleRuntimeRetry(context);
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
        session = null;
        camera = null;
        reader = null;
        if (displayListener != null) {
            try {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) dm.unregisterDisplayListener(displayListener);
            } catch (Exception ignored) {
            }
            displayListener = null;
        }
        if (settingsObserver != null) {
            try {
                context.getContentResolver().unregisterContentObserver(settingsObserver);
            } catch (Exception ignored) {
            }
            settingsObserver = null;
        }
        // Close the model on its owning thread after any in-flight detectForVideo call finishes.
        Runnable cleanup = () -> {
            try {
                if (landmarker != null) landmarker.close();
            } catch (Exception ignored) {
            }
            landmarker = null;
            if (rawBitmap != null) {
                rawBitmap.recycle();
                rawBitmap = null;
            }
            rgba = null;
            if (thread != null) thread.quitSafely();
        };
        boolean posted = false;
        if (handler != null && thread != null && Looper.myLooper() != thread.getLooper()) {
            posted = handler.post(cleanup);
        }
        if (!posted) cleanup.run();
        nativeSetHand(0, null, false, 0L);
        nativeSetHand(1, null, false, 0L);
    }
}
