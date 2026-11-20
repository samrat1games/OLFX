package org.freedesktop.monado.ipc;
import java.io.*;
public class ModelExtractTest {
    public static void main(String[] a) throws Exception {
        File out = File.createTempFile("mdl", ".onnx");
        OnnxModelStore.extractOnnx(new FileInputStream(a[0]), out);
        if (out.length() < 1_000_000) throw new AssertionError("size");
        System.out.println("extract OK " + out.length());
    }
}
