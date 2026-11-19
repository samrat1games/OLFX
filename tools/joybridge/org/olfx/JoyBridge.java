package org.olfx;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Forwards gamepad / Joy-Con key and stick events seen by the game window to the OVRPlugin shim. Events pass through unchanged. */
public final class JoyBridge {
    static native void nativeKey(int role, int keyCode, boolean down);
    static native void nativeAxes(int role, float lx, float ly, float rx, float ry, float lt, float rt);

    private static Window.Callback ours;

    public static void install(final Activity a) {
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    Window w = a.getWindow();
                    Window.Callback cur = w.getCallback();
                    if (cur != null && cur != ours) {
                        final Window.Callback orig = cur;
                        ours = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                                new Class[] {Window.Callback.class}, new InvocationHandler() {
                            @Override public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                                try {
                                    String n = m.getName();
                                    if (args != null && args.length == 1) {
                                        if (n.equals("dispatchKeyEvent")) key((KeyEvent) args[0]);
                                        else if (n.equals("dispatchGenericMotionEvent")) motion((MotionEvent) args[0]);
                                    }
                                } catch (Throwable ignored) { }
                                try { return m.invoke(orig, args); }
                                catch (InvocationTargetException e) { throw e.getCause(); }
                            }
                        });
                        w.setCallback(ours);
                    }
                } catch (Throwable ignored) { }
                h.postDelayed(this, 3000); // Unity may replace the callback; re-wrap if so
            }
        });
    }

    private static int role(InputDevice d) {
        if (d == null) return 0;
        String n = d.getName();
        if (n != null) {
            if (n.contains("(L)")) return 1;
            if (n.contains("(R)")) return 2;
        }
        return 0;
    }

    private static boolean pad(int src) {
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static void key(KeyEvent e) {
        if (!pad(e.getSource()) || e.getRepeatCount() > 0) return;
        int act = e.getAction();
        if (act != KeyEvent.ACTION_DOWN && act != KeyEvent.ACTION_UP) return;
        nativeKey(role(e.getDevice()), e.getKeyCode(), act == KeyEvent.ACTION_DOWN);
    }

    private static void motion(MotionEvent e) {
        if (!pad(e.getSource()) || e.getActionMasked() != MotionEvent.ACTION_MOVE) return;
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        nativeAxes(role(e.getDevice()),
                e.getAxisValue(MotionEvent.AXIS_X), -e.getAxisValue(MotionEvent.AXIS_Y),
                e.getAxisValue(MotionEvent.AXIS_Z), -e.getAxisValue(MotionEvent.AXIS_RZ), lt, rt);
    }

    private JoyBridge() { }
}
