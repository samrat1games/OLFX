package org.olfx.monkego;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Starts the local server and opens the game in the device browser (WebXR needs a real browser, not a WebView). */
public class MainActivity extends Activity {
    private static final String URL = "http://localhost:" + ServerService.PORT + "/";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xFF1B1B1F);
        TextView t = new TextView(this);
        t.setText("Monke Go\n\nOpening the game in the browser...\n" + URL + "\n\nIn the game choose ENTER VR.");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        Button open = new Button(this);
        open.setText("Open game");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGame();
            }
        });
        col.addView(open);
        setContentView(col);
        startServer();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                openGame();
            }
        }, 700);
    }

    private void startServer() {
        Intent i = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void openGame() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(URL)));
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "No browser found. Open " + URL + " in the headset browser.", android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
