package org.olfx.monkego;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Keeps the local game server alive while the headset browser is in the foreground. */
public class ServerService extends Service {
    static final int PORT = 8765;
    private static AssetServer server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("server", "Monke Go server", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            Notification n = new Notification.Builder(this, "server").setContentTitle("Monke Go").setContentText("Local game server on port " + PORT)
                    .setSmallIcon(android.R.drawable.ic_media_play).build();
            startForeground(1, n);
        }
        synchronized (ServerService.class) {
            if (server == null) {
                final AssetManager am = getAssets();
                AssetServer s = new AssetServer(new AssetServer.Source() {
                    @Override
                    public byte[] read(String path) throws IOException {
                        try (InputStream in = am.open("web/" + path)) {
                            ByteArrayOutputStream b = new ByteArrayOutputStream();
                            byte[] buf = new byte[1 << 16];
                            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                            return b.toByteArray();
                        } catch (IOException e) {
                            return null;
                        }
                    }
                }, PORT);
                if (s.start()) server = s;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (ServerService.class) {
            if (server != null) { server.stop(); server = null; }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
