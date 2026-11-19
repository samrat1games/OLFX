// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.List;
import java.util.Locale;

/**
 * In-app hand-tracking test: live upright camera frame, 21-landmark skeleton, FPS/inference time and
 * explicit start/stop. Runs in the Monado process so it uses the same camera/model pipeline as games.
 */
public final class HandTestActivity extends Activity implements OlfxHands.TestListener {
    private static final int REQ_CAMERA = 71;
    private static boolean nativeLoaded = false;

    private TextView status;
    private PreviewView preview;
    private Button toggle;
    private Button probe;
    private TextView probeStatus;
    private boolean testing = false;
    private boolean probing = false;

    private static final int[][] BONES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 4},
        {0, 5}, {5, 6}, {6, 7}, {7, 8},
        {0, 9}, {9, 10}, {10, 11}, {11, 12},
        {0, 13}, {13, 14}, {14, 15}, {15, 16},
        {0, 17}, {17, 18}, {18, 19}, {19, 20},
        {5, 9}, {9, 13}, {13, 17}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Match the landscape orientation used by XR games so the test validates the same geometry.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setTitle(str("test_hand_tracking", "Test hand tracking"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 18, 20));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15f);
        status.setText(str("hand_test_hint", "Point the rear camera at your hands. Start the test and check the skeleton."));
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        preview = new PreviewView();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        pp.topMargin = dp(12);
        pp.bottomMargin = dp(12);
        root.addView(preview, pp);

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setText(str("hand_test_start", "Start test"));
        toggle.setOnClickListener(v -> {
            if (testing) stopTest();
            else ensurePermissionAndStart();
        });
        root.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        probe = new Button(this);
        probe.setAllCaps(false);
        probe.setText(str("hand_probe_button", "Test OpenXR delivery"));
        probe.setOnClickListener(v -> runProbe());
        root.addView(probe, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        probeStatus = new TextView(this);
        probeStatus.setTextColor(Color.LTGRAY);
        probeStatus.setTextSize(13f);
        probeStatus.setText(str("hand_probe_hint", "Close the game first. This calls xrCreateHandTrackerEXT and xrLocateHandJointsEXT through the OpenXR loader."));
        root.addView(probeStatus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Explicit start only: opening the test screen must not silently grab the camera.
    }

    @Override
    protected void onPause() {
        stopTest();
        if (probe != null) probe.setEnabled(!probing);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopTest();
        super.onDestroy();
    }

    private void runProbe() {
        if (probing) return;
        probing = true;
        probe.setEnabled(false);
        probeStatus.setText(str("hand_probe_running", "Testing OpenXR hand delivery…"));
        new Thread(() -> {
            String result = OlfxOpenXrProbe.probe(getApplicationContext());
            runOnUiThread(() -> {
                probing = false;
                if (isFinishing() || isDestroyed()) return;
                probe.setEnabled(true);
                probeStatus.setText(str("hand_probe_result", "OpenXR probe: %1$s").replace("%1$s", result));
            });
        }, "olfx-openxr-probe").start();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionAndStart() {
        if (hasCameraPermission()) {
            startTest();
        } else {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTest();
        } else {
            status.setText(str("hand_test_permission", "Camera permission is required for the hand-tracking test."));
        }
    }

    private void startTest() {
        if (testing) return;
        try {
            if (!nativeLoaded) {
                System.loadLibrary(BuildConfig.SERVICE_LIB_NAME);
                nativeLoaded = true;
            }
        } catch (Throwable t) {
            status.setText(str("hand_test_error", "Hand test error: %1$s").replace("%1$s", String.valueOf(t.getMessage())));
            return;
        }
        testing = true;
        toggle.setText(str("hand_test_stop", "Stop test"));
        status.setText(str("hand_test_starting", "Starting camera and hand model…"));
        OlfxHands.startTest(this, this);
    }

    private void stopTest() {
        if (!testing) return;
        testing = false;
        OlfxHands.stopTest();
        resumeRuntimeIfActive();
        runOnUiThread(() -> {
            toggle.setText(str("hand_test_start", "Start test"));
            preview.clear();
            status.setText(str("hand_test_stopped", "Test stopped."));
        });
    }

    private void resumeRuntimeIfActive() {
        // The test borrows the runtime camera. Give it back to active games when the test releases it.
        if (MonadoService.Companion.isRunning()) {
            OlfxHands.start(getApplicationContext());
        }
    }

    @Override
    public void onHandFrame(Bitmap frame, List<List<NormalizedLandmark>> landmarks, long detectNs, float fps) {
        runOnUiThread(() -> {
            if (!testing) {
                if (frame != null) frame.recycle();
                return;
            }
            preview.setFrame(frame, landmarks);
            int hands = landmarks.size();
            String msg = String.format(Locale.US, "%s  •  %.1f FPS  •  %.1f ms  •  %s: %d",
                    str("hand_test_running", "Tracking"), fps, detectNs / 1_000_000.0,
                    str("hand_test_hands", "hands"), hands);
            status.setText(msg + "\n" + openXrStatus());
        });
    }

    /** Shows whether this build actually contains the OpenXR hand-tracking delivery path. */
    private String openXrStatus() {
        int st = OlfxHands.nativeHandTrackingStatus();
        String compiled = (st & 8) != 0
                ? str("hand_openxr_compiled", "extensions OK")
                : str("hand_openxr_not_compiled", "extensions missing");
        String device = (st & 1) != 0
                ? str("hand_openxr_device_active", "device active")
                : str("hand_openxr_device_waiting", "waiting for game");
        String detail = compiled + ", " + device;
        if ((st & 6) != 0) {
            String hands = ((st & 2) != 0 ? "L" : "") + ((st & 4) != 0 ? "R" : "");
            detail += ", " + str("hand_openxr_hands_active", "hands: %1$s").replace("%1$s", hands);
        }
        long age = OlfxHands.lastPublishAgeMs();
        String delivery = age < 0 ? "no native publish yet" : ("native publish " + age + " ms ago");
        int mask = OlfxHands.activeHandsMask();
        if (mask != 0) {
            delivery += ", java hands " + ((mask & 1) != 0 ? "L" : "") + ((mask & 2) != 0 ? "R" : "");
        }
        long fb = OlfxHands.fallbackFrames();
        if (fb > 0) delivery += ", fallback frames " + fb;
        return str("hand_openxr_status", "OpenXR hand tracking: %1$s").replace("%1$s", detail + "; " + delivery);
    }

    @Override
    public void onHandError(String message) {
        runOnUiThread(() -> {
            testing = false;
            toggle.setText(str("hand_test_start", "Start test"));
            preview.clear();
            status.setText(errorMessage(message));
            resumeRuntimeIfActive();
        });
    }

    private String errorMessage(String code) {
        if ("camera_permission".equals(code)) {
            return str("hand_test_permission", "Camera permission is required for the hand-tracking test.");
        }
        if ("no_back_camera".equals(code)) {
            return str("hand_test_no_camera", "No accessible rear camera was found on this device.");
        }
        if ("camera_disconnected".equals(code)) {
            return str("hand_test_camera_disconnected", "The camera was disconnected. Start the test again.");
        }
        if (code != null && (code.startsWith("camera_error_") || "capture_configure_failed".equals(code)
                || "session_failed".equals(code) || "repeating_request_failed".equals(code))) {
            return str("hand_test_camera_error", "The camera could not be started. Close other camera apps and try again.");
        }
        if ("hand_disabled".equals(code)) {
            return str("hand_test_disabled", "Hand tracking is switched off in OLFX settings.");
        }
        if ("model_unavailable".equals(code)) {
            return str("hand_test_model_error", "The hand-tracking model could not be loaded.");
        }
        if ("yuv_unsupported".equals(code) || "no_supported_yuv_size".equals(code)) {
            return str("hand_test_yuv_error", "This camera did not provide a supported hand-tracking frame format.");
        }
        return str("hand_test_error", "Hand test error: %1$s").replace("%1$s", String.valueOf(code));
    }

    private String str(String name, String fallback) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id != 0 ? getString(id) : fallback;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Draws the upright camera frame and the normalized landmark skeleton. */
    private final class PreviewView extends View {
        private Bitmap frame;
        private List<List<NormalizedLandmark>> result;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();

        PreviewView() {
            super(HandTestActivity.this);
            setBackgroundColor(Color.rgb(32, 32, 36));
            linePaint.setStrokeWidth(3f);
            pointPaint.setColor(Color.WHITE);
        }

        void setFrame(Bitmap bitmap, List<List<NormalizedLandmark>> res) {
            Bitmap old = frame;
            frame = bitmap;
            result = res;
            if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
            invalidate();
        }

        void clear() {
            if (frame != null && !frame.isRecycled()) frame.recycle();
            frame = null;
            result = null;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (frame == null || frame.isRecycled()) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.LTGRAY);
                p.setTextSize(16f * getResources().getDisplayMetrics().scaledDensity);
                String hint = str("hand_test_preview", "Camera preview");
                float w = p.measureText(hint);
                canvas.drawText(hint, (getWidth() - w) / 2f, getHeight() / 2f, p);
                return;
            }
            float scale = Math.min((float) getWidth() / frame.getWidth(), (float) getHeight() / frame.getHeight());
            float dw = frame.getWidth() * scale;
            float dh = frame.getHeight() * scale;
            float left = (getWidth() - dw) / 2f;
            float top = (getHeight() - dh) / 2f;
            dst.set(left, top, left + dw, top + dh);
            canvas.drawBitmap(frame, null, dst, bitmapPaint);
            if (result == null) return;
            List<List<NormalizedLandmark>> hands = result;
            for (int h = 0; h < hands.size(); h++) {
                List<NormalizedLandmark> lm = hands.get(h);
                if (lm.size() < 21) continue;
                int color = (h % 2 == 0) ? Color.rgb(64, 224, 208) : Color.rgb(255, 105, 180);
                linePaint.setColor(color);
                pointPaint.setColor(color);
                for (int[] bone : BONES) {
                    NormalizedLandmark a = lm.get(bone[0]);
                    NormalizedLandmark b = lm.get(bone[1]);
                    canvas.drawLine(left + a.x() * dw, top + a.y() * dh,
                            left + b.x() * dw, top + b.y() * dh, linePaint);
                }
                for (NormalizedLandmark pt : lm) {
                    canvas.drawCircle(left + pt.x() * dw, top + pt.y() * dh, 4f, pointPaint);
                }
            }
        }
    }
}
