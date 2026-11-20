// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads the OpenMMLab RTMDet-nano / RTMPose-m hand ONNX models (Apache-2.0) once into app storage. */
final class OnnxModelStore {
    static final String BASE = "https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/";
    static final String DET_ZIP = BASE + "rtmdet_nano_8xb32-300e_hand-267f9c8f.zip";
    static final String POSE_ZIP =
            BASE + "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320.zip";
    private static boolean running;

    private OnnxModelStore() {}

    static File dir(Context c) {
        return new File(c.getFilesDir(), "olfx_models");
    }

    static synchronized void ensureAsync(final Context c) {
        if (running || OnnxHandPipeline.modelsPresent(dir(c))) return;
        running = true;
        final File d = dir(c);
        new Thread(() -> {
            try {
                d.mkdirs();
                if (!new File(d, OnnxHandPipeline.DET_FILE).isFile()) fetch(DET_ZIP, new File(d, OnnxHandPipeline.DET_FILE));
                if (!new File(d, OnnxHandPipeline.POSE_FILE).isFile()) fetch(POSE_ZIP, new File(d, OnnxHandPipeline.POSE_FILE));
            } catch (Throwable t) {
                android.util.Log.w("OlfxHands", "ONNX model download failed: " + t);
            } finally {
                synchronized (OnnxModelStore.class) {
                    running = false;
                }
            }
        }, "olfx-model-download").start();
    }

    /** Download a zip and store its end2end.onnx as {@code dst} (atomic rename). */
    static void fetch(String url, File dst) throws Exception {
        File tmp = new File(dst.getPath() + ".part");
        URL u = new URL(url);
        java.net.URLConnection conn = u.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try (InputStream in = conn.getInputStream()) {
            extractOnnx(in, tmp);
        }
        if (conn instanceof HttpURLConnection) ((HttpURLConnection) conn).disconnect();
        if (!tmp.renameTo(dst)) throw new java.io.IOException("rename failed");
    }

    static void extractOnnx(InputStream zip, File out) throws Exception {
        try (ZipInputStream z = new ZipInputStream(zip)) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (!e.getName().endsWith("end2end.onnx")) continue;
                try (FileOutputStream f = new FileOutputStream(out)) {
                    byte[] b = new byte[1 << 16];
                    for (int n; (n = z.read(b)) > 0; ) f.write(b, 0, n);
                }
                if (out.length() < 1_000_000) throw new java.io.IOException("model too small");
                return;
            }
        }
        throw new java.io.IOException("end2end.onnx not found in archive");
    }
}
