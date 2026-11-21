package org.olfx.g2;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.picovr.vractivity.Eye;
import com.picovr.vractivity.HmdState;
import com.picovr.client.HbController;
import com.picovr.client.HbListener;
import com.picovr.client.HbManager;
import com.picovr.client.HbTool;
import com.picovr.client.Orientation;
import com.picovr.vractivity.RenderInterface;
import com.psmart.vrlib.PicovrSDK;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OLFX launcher for Pico G2 (Pico Native SDK 2.8.5.4): VRActivity runs the frame loop and calls the RenderInterface
 * methods below on the render thread; the scene is drawn by libolfx_g2. The launcher is a 1280x720 panel of app tiles.
 */
public class G2Activity extends com.picovr.vractivity.VRActivity implements RenderInterface {
    static {
        System.loadLibrary("olfx_g2");
    }

    private static final int COLS = 4, ROWS = 2, PER_PAGE = COLS * ROWS;
    private FrameLayout panel;
    private final List<ResolveInfo> apps = new ArrayList<>();
    private int page;
    private int eyeW = 1024, eyeH = 1024;
    // Pico G2 controller (Hummingbird) + SDK arm model
    private HbManager hbManager;
    private volatile HbController hb;
    private final float[] ctrlRot = new float[4], ctrlPos = new float[3], zero3 = new float[3];
    private final float[] ctrlQuat = new float[4];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0xFF1B1B1F);
        // Attached (so clicks run) but far off-screen: the VR scene, not the window, is what the user sees.
        addContentView(panel, new FrameLayout.LayoutParams(1280, 720));
        panel.setTranslationX(-6000f);
        G2Host.panel = panel;
        try {
            hbManager = new HbManager(this);
            hbManager.setHbListener(new HbListener() {
                @Override public void onConnect() {}
                @Override public void onDisconnect() {}
                @Override public void onDataUpdate() {}
                @Override public void onReCenter() {}
                @Override public void onBindService() {}
            });
            hbManager.InitServices();
        } catch (Throwable t) {
            hbManager = null;   // no controller service: head-gaze pointer stays active
        }
        panel.post(new Runnable() {
            @Override
            public void run() {
                loadApps();
                buildPanel();
            }
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(main, 0);
        apps.clear();
        for (ResolveInfo r : all) {
            if (!r.activityInfo.packageName.equals(getPackageName())) apps.add(r);
        }
        final PackageManager p2 = pm;
        Collections.sort(apps, new java.util.Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(p2).toString().compareToIgnoreCase(b.loadLabel(p2).toString());
            }
        });
    }

    private void buildPanel() {
        panel.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(32, 24, 32, 24);
        TextView title = new TextView(this);
        title.setText("OLFX");
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        col.addView(title);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        int from = page * PER_PAGE;
        for (int i = from; i < Math.min(apps.size(), from + PER_PAGE); i++) {
            final ResolveInfo r = apps.get(i);
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(12, 12, 12, 12);
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(r.loadIcon(getPackageManager()));
            tile.addView(icon, new LinearLayout.LayoutParams(160, 160));
            TextView t = new TextView(this);
            t.setText(r.loadLabel(getPackageManager()));
            t.setTextColor(Color.WHITE);
            t.setTextSize(18);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            tile.addView(t, new LinearLayout.LayoutParams(280, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launch(r);
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 290;
            lp.height = 250;
            grid.addView(tile, lp);
        }
        col.addView(grid);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        Button prev = new Button(this), next = new Button(this);
        prev.setText("<");
        next.setText(">");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (page > 0) { page--; buildPanel(); }
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ((page + 1) * PER_PAGE < apps.size()) { page++; buildPanel(); }
            }
        });
        nav.addView(prev);
        TextView pg = new TextView(this);
        pg.setText("  " + (page + 1) + " / " + Math.max(1, (apps.size() + PER_PAGE - 1) / PER_PAGE) + "  ");
        pg.setTextColor(Color.WHITE);
        nav.addView(pg);
        nav.addView(next);
        col.addView(nav);
        panel.addView(col, new FrameLayout.LayoutParams(1280, 720));
        panel.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        panel.layout(0, 0, 1280, 720);
    }

    private void launch(ResolveInfo r) {
        Intent i = getPackageManager().getLaunchIntentForPackage(r.activityInfo.packageName);
        if (i != null) startActivity(i);   // pauses this activity, so the scene stops and the game gets the display
    }

    @Override
    protected void onResume() {
        super.onResume();
        G2Host.start();
        try {
            if (hbManager != null) {
                hbManager.Resume();
                hb = hbManager.getHbController();
                if (hb != null) {
                    hb.Start();
                    hb.startUpdateThread();
                }
                // right-handed default (0) / left-handed (1) from the system setting; gaze-blend while moving; 15 deg pointer tilt
                PicovrSDK.setArmModelParameter(PicovrSDK.getPvrHandness(this), 1, 0f, 0f, 15f);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        try {
            if (hb != null) {
                hb.stopUpdateThread();
                hb.Stop();
            }
            if (hbManager != null) hbManager.Pause();
        } catch (Throwable ignored) {
        }
        hb = null;
        G2Host.stop();
        super.onPause();
    }

    /** Any confirm key acts as the trigger of the gaze pointer. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
                || k == KeyEvent.KEYCODE_BUTTON_R1 || k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) G2Host.nativeSetPressed(true);
            else if (e.getAction() == KeyEvent.ACTION_UP) G2Host.nativeSetPressed(false);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Feeds the Hummingbird controller to the SDK arm model and passes the resulting pointer pose to the renderer. */
    private void updateController() {
        HbController c = hb;
        boolean valid = false, pressed = false;
        try {
            if (c != null && c.getConnectState() == HbTool.STATE_CONNECTED) {
                Orientation o = c.getOrientation();
                if (o != null) {
                    ctrlQuat[0] = o.x; ctrlQuat[1] = o.y; ctrlQuat[2] = o.z; ctrlQuat[3] = o.w;
                    float[] acc = c.getAccel(), gyro = c.getAngular();
                    int[] keys = c.keyData;
                    PicovrSDK.setControllerData(ctrlQuat, acc != null ? acc : zero3, gyro != null ? gyro : zero3, keys != null ? keys : new int[8]);
                    PicovrSDK.calcArmModelParameters();
                    PicovrSDK.getPointerPose(ctrlRot, ctrlPos);
                    valid = true;
                    pressed = c.getTrigerKeyEvent() == 1 || c.getButtonState(HbTool.ButtonNum.click);
                    if (c.getButtonState(HbTool.ButtonNum.app)) PicovrSDK.resetSensor(0);   // App button recenters the view
                }
            }
        } catch (Throwable t) {
            valid = false;
        }
        G2Host.nativeController(ctrlRot, ctrlPos, pressed, valid);
    }

    // ---- RenderInterface (render thread) ----
    @Override
    public void onFrameBegin(HmdState s) {
        updateController();
        G2Host.nativeFrameBegin(s.getOrientation(), s.getPos(), s.getIpd(), s.getFov(), eyeW, eyeH);
    }

    @Override
    public void onDrawEye(Eye e) {
        G2Host.nativeDrawEye(e.getType());
    }

    @Override
    public void onFrameEnd() {}

    @Override
    public void onTouchEvent() {}

    @Override
    public void onRenderPause() {}

    @Override
    public void onRenderResume() {}

    @Override
    public void onRendererShutdown() {
        G2Host.nativeShutdown();
    }

    @Override
    public void initGL(int w, int h) {
        try { eyeW = PicovrSDK.GetEyeBufferWidth(); eyeH = PicovrSDK.GetEyeBufferHeight(); } catch (Throwable t) { eyeW = w; eyeH = h; }
        G2Host.nativeInit(getAssets());
    }

    @Override
    public void deInitGL() {}

    @Override
    public void renderEventCallBack(int event) {}

    @Override
    public void surfaceChangedCallBack(int w, int h) {}
}
