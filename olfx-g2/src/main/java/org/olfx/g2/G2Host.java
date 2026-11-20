package org.olfx.g2;

import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

/** JNI bridge for olfx_g2.cpp: panel texture, gaze pointer -> touch events on the launcher panel. */
final class G2Host {
    static native void nativeInit(AssetManager assets);
    static native void nativeFrameBegin(float[] quat, float[] pos, float ipd, float fov, int eyeW, int eyeH);
    static native void nativeDrawEye(int eye);
    static native void nativeSetPressed(boolean down);
    static native void nativeShutdown();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile View panel;
    private static volatile SurfaceTexture texture;
    private static volatile Surface surface;
    private static long downTime;

    /** Draws the panel view into the SurfaceTexture ~30 times a second. */
    static final Runnable DRAW = new Runnable() {
        @Override
        public void run() {
            View v = panel;
            Surface s = surface;
            if (v != null && s != null && s.isValid() && v.getWidth() > 0) {
                try {
                    android.graphics.Canvas c = s.lockHardwareCanvas();
                    c.drawColor(0xFF1B1B1F);
                    v.draw(c);
                    s.unlockCanvasAndPost(c);
                } catch (Throwable ignored) {
                }
            }
            MAIN.postDelayed(this, 33);
        }
    };

    static void start() {
        MAIN.removeCallbacks(DRAW);
        MAIN.post(DRAW);
    }

    static void stop() {
        MAIN.removeCallbacks(DRAW);
        try {
            if (surface != null) surface.release();
            if (texture != null) texture.release();
        } catch (Throwable ignored) {
        }
        surface = null;
        texture = null;
    }

    // ---- called from native (render thread, GL context current) ----
    public static void panelCreate(int texId) {
        View v = panel;
        SurfaceTexture st = new SurfaceTexture(texId);
        st.setDefaultBufferSize(v != null && v.getWidth() > 0 ? v.getWidth() : 1280, v != null && v.getHeight() > 0 ? v.getHeight() : 720);
        texture = st;
        surface = new Surface(st);
    }

    public static void panelUpdate(float[] out) {
        SurfaceTexture st = texture;
        try {
            if (st != null) {
                st.updateTexImage();
                st.getTransformMatrix(out);
            }
        } catch (Throwable ignored) {
        }
    }

    /** action: 0 down, 1 move, 2 up, 3 cancel; u,v in 0..1 (v down). */
    public static void onPointer(final float u, final float v, final int action) {
        final View view = panel;
        if (view == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                if (action == 0) downTime = now;
                int a = action == 0 ? MotionEvent.ACTION_DOWN : action == 1 ? MotionEvent.ACTION_MOVE
                        : action == 2 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL;
                MotionEvent e = MotionEvent.obtain(downTime, now, a, u * view.getWidth(), v * view.getHeight(), 0);
                view.dispatchTouchEvent(e);
                e.recycle();
            }
        });
    }
}
