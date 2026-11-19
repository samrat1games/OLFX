// Copyright 2026 OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Runs a real OpenXR client probe through the bundled Khronos loader and the selected runtime.
 * The native side calls xrCreateHandTrackerEXT/xrLocateHandJointsEXT; it does not infer delivery
 * from compile-time flags or Java camera counters.
 */
public final class OlfxOpenXrProbe {
    private static boolean loaded;

    private static native String nativeProbe(String loaderPath, Context context);

    public static synchronized boolean load() {
        if (loaded) return true;
        try {
            System.loadLibrary(BuildConfig.SERVICE_LIB_NAME);
            loaded = true;
        } catch (Throwable t) {
            android.util.Log.e("OlfxOpenXrProbe", "native probe unavailable", t);
        }
        return loaded;
    }

    /** Blocking; call from a worker thread. Returns newline-separated key=value diagnostics. */
    public static String probe(Context context) {
        if (!load()) return "stage=native_load result=unavailable";
        File loader = new File(context.getCodeCacheDir(), "olfx-openxr-loader.so");
        try {
            try (InputStream in = context.getAssets().open("khronos/libopenxr_loader_arm64.so")) {
                if (!loader.isFile() || loader.length() == 0) {
                    File tmp = new File(loader.getAbsolutePath() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.getFD().sync();
                    }
                    if (!tmp.renameTo(loader)) {
                        tmp.delete();
                        if (!loader.isFile()) return "stage=loader_extract result=rename_failed";
                    }
                }
            }
            return nativeProbe(loader.getAbsolutePath(), context.getApplicationContext());
        } catch (Throwable t) {
            return "stage=probe result=" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
    }
}
