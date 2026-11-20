package org.freedesktop.monado.ipc;
public class SimccDecoderTest {
    public static void main(String[] a) {
        float[][] x = new float[21][512], y = new float[21][512];
        x[3][100] = 5; y[3][60] = 3;
        float[] o = SimccDecoder.decode(x, y, 2f);
        if (o[9] != 50f || o[10] != 30f || o[11] != 3f) throw new AssertionError(o[9] + "," + o[10] + "," + o[11]);
        System.out.println("OK");
    }
}
