// Copyright 2020, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Implementation of the Monado AIDL server
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @ingroup ipc_android
 */

package org.freedesktop.monado.ipc;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import java.io.IOException;

/**
 * Java implementation of the IMonado IPC interface.
 *
 * <p>(This is the server-side code.)
 *
 * <p>All this does is delegate calls to native JNI implementations. The warning suppression is
 * because Android Studio tends to have a hard time finding the (very real) implementations of these
 * in service-lib.
 */
@Keep
public class MonadoImpl extends IMonado.Stub {

    private static final String TAG = "MonadoImpl";

    static {
        // Load the shared library with the native parts of this class
        // This is the service-lib target.
        System.loadLibrary(BuildConfig.SERVICE_LIB_NAME);
    }

    private final Context context;
    private final boolean activeSbs;

    public MonadoImpl(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.activeSbs = applyOlfxProfile(this.context);
        nativeStartServer(this.context);
    }

    /** Read private launcher settings before starting a native runtime in this process. */
    private static boolean applyOlfxProfile(Context ctx) {
        String authority = "content://" + ctx.getPackageName() + ".settings";
        int scale = 80;
        boolean sbs = false;
        String gamePackage = "";
        try (android.database.Cursor c =
                ctx.getContentResolver()
                        .query(android.net.Uri.parse(authority + "/olfx/profile/current"), null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int value = c.getInt(c.getColumnIndexOrThrow("scale"));
                if (value >= 50 && value <= 150) scale = value;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "OLFX render settings unavailable; using defaults", e);
        }
        try (android.database.Cursor c =
                ctx.getContentResolver()
                        .query(android.net.Uri.parse(authority + "/olfx/display"), null, null, null, null)) {
            if (c != null && c.moveToFirst()) sbs = c.getInt(c.getColumnIndexOrThrow("sbs")) == 1;
        } catch (RuntimeException e) {
            Log.w(TAG, "OLFX display settings unavailable; using lens distortion", e);
        }
        gamePackage = currentGamePackage(ctx);
        try {
            android.system.Os.setenv("XRT_COMPOSITOR_SCALE_PERCENTAGE", Integer.toString(scale), true);
            android.system.Os.setenv("XRT_COMPOSITOR_SBS", sbs ? "1" : "0", true);
            // VRMobile: stable vsync-paced presentation; bounded swapchain; no GPU-contending hand benchmark.
            android.system.Os.setenv("XRT_COMPOSITOR_PRESENT_MODE", "fifo", true);
            android.system.Os.setenv("XRT_COMPOSITOR_PREFERRED_IMAGE_COUNT", "2", true);
            android.system.Os.setenv("OLFX_GAME_PACKAGE", gamePackage == null ? "" : gamePackage, true);
        } catch (Exception e) {
            Log.e(TAG, "Could not apply OLFX display settings", e);
        }
        return sbs;
    }

    private static String currentGamePackage(Context ctx) {
        String authority = "content://" + ctx.getPackageName() + ".settings";
        try (android.database.Cursor c =
                ctx.getContentResolver()
                        .query(android.net.Uri.parse(authority + "/olfx/game/current"), null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String pkg = c.getString(c.getColumnIndexOrThrow("package"));
                return pkg == null ? "" : pkg;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "OLFX current game unavailable; SBS badge disabled", e);
        }
        return "";
    }

    @Override
    public Bundle getOlfxDisplayConfig() {
        // Called by client-hosted MonadoView surfaces, where service-process env vars are not visible.
        String gamePackage = currentGamePackage(context);
        try {
            android.system.Os.setenv("OLFX_GAME_PACKAGE", gamePackage, true);
        } catch (Exception ignored) {
        }
        Bundle config = new Bundle();
        config.putBoolean("sbs", activeSbs);
        config.putString("gamePackage", gamePackage);
        return config;
    }

    @Override
    public void connect(@NonNull ParcelFileDescriptor parcelFileDescriptor) throws RemoteException {
        int fd = parcelFileDescriptor.getFd();
        Log.i(TAG, "connect: given fd " + fd);
        if (nativeAddClient(fd) != 0) {
            Log.e(TAG, "Failed to transfer client fd ownership!");
            try {
                parcelFileDescriptor.close();
            } catch (IOException e) {
                // do nothing, probably already closed.
            }

            // throw an exception so that client can gracefully fail
            throw new IllegalStateException("server not available");
        } else {
            Log.i(TAG, "connect: fd ownership transferred");
            try {
                parcelFileDescriptor.close();
            } catch (IOException e) {
                Log.e(TAG, "connect: close FileDescriptor fail!");
            }
        }
    }

    @Override
    public void passAppSurface(Surface surface) {
        Log.i(TAG, "passAppSurface");
        if (surface == null) {
            Log.e(TAG, "Received a null Surface from the client!");
            return;
        }
        nativeAppSurface(surface);
    }

    @Override
    public boolean canDrawOverOtherApps() {
        Log.i(TAG, "canDrawOverOtherApps");
        return Settings.canDrawOverlays(context);
    }

    public void shutdown() {
        Log.i(TAG, "shutdown");
        nativeShutdownServer();
    }

    /**
     * Native method that starts server.
     *
     * <p>This is essentially the entry point for the monado service on Android.
     *
     * <p>
     *
     * @param context Context object.
     */
    @SuppressWarnings("JavaJniMissingFunction")
    private native void nativeStartServer(@NonNull Context context);

    /**
     * Native handling of receiving a surface: should convert it to an ANativeWindow then do stuff
     * with it.
     *
     * <p>Ignore warnings that this function is missing: it is not, it is just in a different
     * module. See `src/xrt/targets/service-lib/service_target.cpp` for the implementation.
     *
     * @param surface The surface to pass to native code
     * @todo figure out a good way to have a client ID
     */
    @SuppressWarnings("JavaJniMissingFunction")
    private native void nativeAppSurface(@NonNull Surface surface);

    /**
     * Native handling of receiving an FD for a new client: the FD should be used to start up the
     * rest of the native IPC code on that socket.
     *
     * <p>Ignore warnings that this function is missing: it is not, it is just in a different
     * module. See `src/xrt/targets/service-lib/service_target.cpp` for the implementation.
     *
     * @param fd The incoming file descriptor: ownership is transferred to native code here.
     * @return 0 on success, anything else means the fd wasn't sent and ownership not transferred.
     * @todo figure out a good way to have a client ID
     */
    @SuppressWarnings("JavaJniMissingFunction")
    private native int nativeAddClient(int fd);

    /**
     * Native method that handles shutdown server.
     *
     * @return 0 on success; -1 means that server didn't start.
     */
    @SuppressWarnings("JavaJniMissingFunction")
    private native int nativeShutdownServer();
}
