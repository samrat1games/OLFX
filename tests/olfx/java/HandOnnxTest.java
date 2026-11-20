package org.freedesktop.monado.ipc;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

public class HandOnnxTest {
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }
    public static void main(String[] a) throws Exception {
        // geometry
        float[] lb = HandOnnxMath.letterbox(640, 480, 320);
        check(Math.abs(lb[0] - 0.5f) < 1e-6 && lb[1] == 0 && lb[2] == 40, "letterbox");
        float[] c = HandOnnxMath.cropFromBox(100, 100, 200, 180);
        check(c[0] == 150 && c[1] == 140 && c[2] == 125, "crop");
        check(Math.abs(HandOnnxMath.cropToImage(128, 150, 125, 256) - 150) < 1e-4, "crop centre");
        float[][] px = new float[21][2];
        for (int i = 0; i < 21; i++) { px[i][0] = 10 + i; px[i][1] = 50 - 2 * i; }
        for (int r : new int[] {0, 90, 180, 270}) {
            float[][] w = HandOnnxMath.worldFromPixels(px, r);
            float len = (float) Math.hypot(w[9][0], w[9][1]);
            check(Math.abs(len - 0.095f) < 1e-4, "palm metric r=" + r);
            float[] v = {w[9][0], w[9][1]};          // un-rotating by r must give the upright vector
            HandOnnxMath.rotate(v, r);
            float s = 0.095f / (float) Math.hypot(9, 18);
            check(Math.abs(v[0] - 9 * s) < 1e-4 && Math.abs(v[1] + 18 * s) < 1e-4, "rotate roundtrip r=" + r);
        }
        if (a.length == 0) { System.out.println("geometry OK"); return; }
        // real models
        OnnxHandPipeline p = OnnxHandPipeline.create(new File(a[0]));
        check(p != null, "models load");
        System.out.println("provider=" + p.provider);
        int w = 640, h = 480;
        ByteBuffer img = ByteBuffer.allocateDirect(w * h * 4);
        for (int i = 0; i < w * h; i++) { img.put((byte) 120).put((byte) 100).put((byte) 90).put((byte) 255); }
        img.rewind();
        for (int rot : new int[] {0, 90}) {
            long t = System.nanoTime();
            List<OnnxHandPipeline.Hand> hands = p.detect(img, w, h, rot);
            System.out.println("rot=" + rot + " hands=" + hands.size() + " ms=" + (System.nanoTime() - t) / 1000000);
        }
        if (a.length > 3) { // real photo: a[1]=rgba file, a[2]=w, a[3]=h
            byte[] raw = java.nio.file.Files.readAllBytes(new File(a[1]).toPath());
            int iw = Integer.parseInt(a[2]), ih = Integer.parseInt(a[3]);
            ByteBuffer ph = ByteBuffer.allocateDirect(raw.length); ph.put(raw); ph.rewind();
            List<OnnxHandPipeline.Hand> hs = p.detect(ph, iw, ih, a.length > 4 ? Integer.parseInt(a[4]) : 0);
            check(hs.size() >= 1, "real hand detected");
            OnnxHandPipeline.Hand hd = hs.get(0);
            System.out.printf("real: hands=%d score=%.2f wrist=(%.2f,%.2f) middleTip=(%.2f,%.2f)%n", hs.size(), hd.score,
                    hd.upright[0][0], hd.upright[0][1], hd.upright[12][0], hd.upright[12][1]);
            for (float[] k : hd.upright) check(k[0] > -0.3 && k[0] < 1.3 && k[1] > -0.3 && k[1] < 1.3, "keypoint in frame");
        }
        p.close();
        System.out.println("pipeline OK");
    }
}
