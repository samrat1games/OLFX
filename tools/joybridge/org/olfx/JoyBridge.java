package org.olfx;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
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

    static native void nativeImu(int role, float gx, float gy, float gz, float ax, float ay, float az, long tsNs);

    private static Window.Callback ours;
    private static final float[] gyro = new float[3];
    private static final java.util.Set<Integer> imuDevices = new java.util.HashSet<Integer>();

    /** Joy-Con gyro + accelerometer through InputDevice.getSensorManager() (API 31+). Safe no-op elsewhere. */
    private static void attachImu(final InputDevice d) {
        if (Build.VERSION.SDK_INT < 31 || d == null || !pad(d.getSources()) || !imuDevices.add(d.getId())) return;
        try {
            final int role = role(d);
            SensorManager sm = d.getSensorManager();
            final Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (g == null || a == null) return;
            SensorEventListener l = new SensorEventListener() {
                @Override public void onSensorChanged(SensorEvent e) {
                    if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) { System.arraycopy(e.values, 0, gyro, 0, 3); return; }
                    nativeImu(role, gyro[0], gyro[1], gyro[2], e.values[0], e.values[1], e.values[2], e.timestamp);
                }
                @Override public void onAccuracyChanged(Sensor s, int acc) { }
            };
            sm.registerListener(l, g, SensorManager.SENSOR_DELAY_GAME);
            sm.registerListener(l, a, SensorManager.SENSOR_DELAY_GAME);
        } catch (Throwable ignored) { }
    }


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
                try { for (int id : InputDevice.getDeviceIds()) attachImu(InputDevice.getDevice(id)); } catch (Throwable ignored) { }
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
