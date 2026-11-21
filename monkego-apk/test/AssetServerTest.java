package org.olfx.monkego;

import java.io.*;
import java.net.*;
import java.nio.file.*;

public class AssetServerTest {
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }
    static HttpURLConnection get(int port, String p, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + p).openConnection();
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }
    public static void main(String[] a) throws Exception {
        final Path root = Paths.get(a[0]);
        AssetServer s = new AssetServer(new AssetServer.Source() {
            public byte[] read(String p) throws IOException { Path f = root.resolve(p); return Files.isRegularFile(f) ? Files.readAllBytes(f) : null; }
        }, 0);
        check(s.start(), "start"); int port = s.port();
        HttpURLConnection c = get(port, "/", null);
        check(c.getResponseCode() == 200 && c.getContentType().startsWith("text/html"), "index");
        byte[] idx = c.getInputStream().readAllBytes();
        check(idx.length == Files.size(root.resolve("index.html")), "index length");
        c = get(port, "/js/core.js?x=1", null);
        check(c.getResponseCode() == 200 && c.getContentType().startsWith("application/javascript"), "js mime");
        c = get(port, "/nope.txt", null); check(c.getResponseCode() == 404, "404");
        c = get(port, "/%2e%2e/%2e%2e/etc/passwd", null); check(c.getResponseCode() == 404, "traversal blocked");
        String big = null;
        try (var st = Files.walk(root)) { big = st.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".glb") || f.toString().endsWith(".mp3") || f.toString().endsWith(".ogg")).findFirst().map(f -> root.relativize(f).toString()).orElse(null); }
        if (big != null) {
            long len = Files.size(root.resolve(big));
            c = get(port, "/" + big, "bytes=0-99");
            check(c.getResponseCode() == 206, "206"); check(c.getInputStream().readAllBytes().length == 100, "range len");
            check(("bytes 0-99/" + len).equals(c.getHeaderField("Content-Range")), "content-range");
            c = get(port, "/" + big, null); check(c.getInputStream().readAllBytes().length == len, "full file " + big);
            System.out.println("range+full OK on " + big + " (" + len + " bytes)");
        }
        s.stop();
        System.out.println("AssetServer OK");
    }
}
