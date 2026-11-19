// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Path;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * User-enabled accessibility service that turns an OLFX hand-tracking pinch into a screen tap.
 *
 * <p>Runs in the same {@code :monado} process as {@link OlfxHands}, so pinch events are delivered
 * without cross-process IPC. Android requires the user to enable this service explicitly; OLFX only
 * opens the system accessibility settings and never enables it silently. The tap position is the
 * upright camera-frame pinch midpoint mirrored horizontally onto the display (an approximation that
 * depends on camera/lens placement, not a calibrated gaze ray).
 */
public final class PinchClickService extends AccessibilityService implements OlfxHands.PinchListener {
    private static final String TAG = "PinchClickService";
    private static final long MIN_TAP_INTERVAL_NS = 300_000_000L;
    private static final long TAP_DURATION_MS = 45L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean connected;
    private volatile boolean pinchMode;
    private volatile boolean gesturePending;
    private long lastTapNs;
    private ContentObserver clickObserver;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        pinchMode = readPinchMode(this);
        clickObserver = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                pinchMode = readPinchMode(PinchClickService.this);
            }
        };
        getContentResolver().registerContentObserver(clickUri(this), true, clickObserver);
        OlfxHands.setPinchListener(this);
        Log.i(TAG, "pinch click service connected; mode=" + (pinchMode ? "pinch" : "screen"));
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        connected = false;
        OlfxHands.setPinchListener(null);
        if (clickObserver != null) {
            getContentResolver().unregisterContentObserver(clickObserver);
            clickObserver = null;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* gesture-only service */ }
    @Override public void onInterrupt() { /* no feedback to interrupt */ }

    @Override
    public void onPinch(int hand, float screenX, float screenY, long timestampNs) {
        if (!connected || !pinchMode || gesturePending) return;
        main.post(() -> dispatchTap(screenX, screenY));
    }

    private void dispatchTap(float nx, float ny) {
        if (!connected || !pinchMode || gesturePending) return;
        long now = System.nanoTime();
        if (now - lastTapNs < MIN_TAP_INTERVAL_NS) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int w = metrics.widthPixels;
        int h = metrics.heightPixels;
        if (w <= 2 || h <= 2) return;
        float x = clamp(nx, 0f, 1f) * (w - 1);
        float y = clamp(ny, 0f, 1f) * (h - 1);
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                .build();
        gesturePending = true;
        lastTapNs = now;
        boolean ok = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { gesturePending = false; }
            @Override public void onCancelled(GestureDescription gestureDescription) { gesturePending = false; }
        }, main);
        if (!ok) {
            gesturePending = false;
            Log.w(TAG, "dispatchGesture rejected");
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static Uri clickUri(Context ctx) {
        return Uri.parse("content://" + ctx.getPackageName() + ".settings/olfx/click");
    }

    private static boolean readPinchMode(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(clickUri(ctx), null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return "pinch".equals(c.getString(c.getColumnIndexOrThrow("mode")));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "click mode unavailable; screen taps only", e);
        }
        return false;
    }

    /** True when the user has enabled this accessibility service in Android settings. */
    public static boolean isEnabled(Context ctx) {
        String enabled = android.provider.Settings.Secure.getString(
                ctx.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String component = new android.content.ComponentName(ctx, PinchClickService.class).flattenToString();
        for (String entry : enabled.split(":")) {
            if (component.equalsIgnoreCase(entry)) return true;
        }
        return false;
    }
}
