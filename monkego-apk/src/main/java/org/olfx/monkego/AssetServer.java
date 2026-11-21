package org.olfx.monkego;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Tiny loopback-only HTTP/1.1 static file server (GET/HEAD, Range) so a browser can open the bundled game on http://localhost. */
final class AssetServer implements Runnable {
    interface Source {
        /** @return the file's bytes, or null when missing. Path has no leading slash and no "..". */
        byte[] read(String path) throws IOException;
    }

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        String[][] m = {{"html", "text/html; charset=utf-8"}, {"js", "application/javascript; charset=utf-8"}, {"css", "text/css; charset=utf-8"},
            {"json", "application/json"}, {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"gif", "image/gif"},
            {"svg", "image/svg+xml"}, {"ico", "image/x-icon"}, {"glb", "model/gltf-binary"}, {"gltf", "model/gltf+json"}, {"mp3", "audio/mpeg"},
            {"ogg", "audio/ogg"}, {"wav", "audio/wav"}, {"ttf", "font/ttf"}, {"woff", "font/woff"}, {"woff2", "font/woff2"},
            {"wasm", "application/wasm"}, {"txt", "text/plain; charset=utf-8"}, {"mp4", "video/mp4"}, {"webm", "video/webm"}};
        for (String[] e : m) MIME.put(e[0], e[1]);
    }

    private final Source source;
    private final int port;
    private ServerSocket server;
    private volatile boolean running;

    AssetServer(Source source, int port) {
        this.source = source;
        this.port = port;
    }

    boolean start() {
        try {
            server = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            return false;
        }
        running = true;
        Thread t = new Thread(this, "monkego-http");
        t.setDaemon(true);
        t.start();
        return true;
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    int port() {
        return server != null ? server.getLocalPort() : port;
    }

    @Override
    public void run() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(s);
                    }
                }, "monkego-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            while (true) {                                   // keep-alive loop
                String head = readHead(in);
                if (head == null) return;
                String[] lines = head.split("\r\n");
                String[] rl = lines[0].split(" ");
                if (rl.length < 2) return;
                String method = rl[0];
                String range = null;
                boolean close = false;
                for (int i = 1; i < lines.length; i++) {
                    String l = lines[i].toLowerCase(Locale.ROOT);
                    if (l.startsWith("range:")) range = lines[i].substring(6).trim();
                    if (l.startsWith("connection:") && l.contains("close")) close = true;
                }
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    write(out, 405, "Method Not Allowed", null, new byte[0], false, close, null);
                    return;
                }
                String path = rl[1];
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                try {
                    path = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
                } catch (Exception e) {
                    path = "";
                }
                if (path.endsWith("/")) path += "index.html";
                while (path.startsWith("/")) path = path.substring(1);
                if (path.isEmpty()) path = "index.html";
                byte[] body = path.contains("..") || path.contains("\\") ? null : source.read(path);
                if (body == null) {
                    write(out, 404, "Not Found", "text/plain", "404".getBytes(StandardCharsets.UTF_8), method.equals("HEAD"), close, null);
                } else {
                    String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                    String type = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
                    long from = 0, to = body.length - 1;
                    boolean partial = false;
                    if (range != null && range.startsWith("bytes=")) {
                        try {
                            String[] r = range.substring(6).split("-", -1);
                            if (r[0].isEmpty()) { from = Math.max(0, body.length - Long.parseLong(r[1])); }
                            else { from = Long.parseLong(r[0]); if (!r[1].isEmpty()) to = Math.min(Long.parseLong(r[1]), body.length - 1); }
                            partial = from <= to && from < body.length;
                        } catch (Exception e) { partial = false; from = 0; to = body.length - 1; }
                    }
                    if (partial) {
                        byte[] part = new byte[(int) (to - from + 1)];
                        System.arraycopy(body, (int) from, part, 0, part.length);
                        write(out, 206, "Partial Content", type, part, method.equals("HEAD"), close, "bytes " + from + "-" + to + "/" + body.length);
                    } else {
                        write(out, 200, "OK", type, body, method.equals("HEAD"), close, null);
                    }
                }
                if (close) return;
            }
        } catch (IOException ignored) {
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) != -1) {
            b.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return new String(b.toByteArray(), StandardCharsets.ISO_8859_1).trim();
            if (b.size() > 16384) return null;
        }
        return null;
    }

    private static void write(OutputStream out, int code, String text, String type, byte[] body, boolean headOnly, boolean close, String contentRange)
            throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(text).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Accept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n");
        if (contentRange != null) h.append("Content-Range: ").append(contentRange).append("\r\n");
        h.append(close ? "Connection: close\r\n\r\n" : "Connection: keep-alive\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) out.write(body);
        out.flush();
    }
}
