// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * Tiny OLFX launcher-image badge for SBS: one app image at the top-left of each eye/lens half.
 * No text watermark. The service process uses environment config; client-hosted surfaces receive
 * the package/SBS flag explicitly over IMonado because Android environment variables are per-process.
 */
public final class GameBadgeOverlay {
    private static final int ICON_PX = 64;
    private static volatile Bitmap ownIcon;

    private GameBadgeOverlay() {}

    public static boolean sbsEnabled() {
        String sbs = System.getenv("XRT_COMPOSITOR_SBS");
        return "1".equals(sbs) || "true".equalsIgnoreCase(sbs);
    }

    public static String environmentGamePackage() {
        String pkg = System.getenv("OLFX_GAME_PACKAGE");
        return pkg == null ? "" : pkg;
    }

    /** Only create the overlay window when it will actually draw something. */
    public static boolean shouldShow(Context context, String gamePackage) {
        // The badge identifies OLFX, not the launched game. It also works for direct game launches.
        return loadIcon(context) != null;
    }

    public static View create(Context context, String gamePackage) {
        return new BadgeView(context);
    }

    private static Bitmap loadIcon(Context context) {
        Bitmap cached = ownIcon;
        if (cached != null && !cached.isRecycled()) return cached;
        synchronized (GameBadgeOverlay.class) {
            cached = ownIcon;
            if (cached != null && !cached.isRecycled()) return cached;
            try {
                Drawable drawable = context.getPackageManager().getApplicationIcon(context.getPackageName());
                ownIcon = drawableToBitmap(drawable);
                return ownIcon;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) drawable).getBitmap();
            if (b != null && !b.isRecycled()) {
                if (b.getWidth() <= ICON_PX && b.getHeight() <= ICON_PX) return b;
                return Bitmap.createScaledBitmap(b, ICON_PX, ICON_PX, true);
            }
        }
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w > 0 && h > 0) {
            float scale = Math.min((float) ICON_PX / w, (float) ICON_PX / h);
            w = Math.max(1, Math.round(w * scale));
            h = Math.max(1, Math.round(h * scale));
        } else {
            w = ICON_PX;
            h = ICON_PX;
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bitmap;
    }

    private static final class BadgeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();
        private final Bitmap icon;

        BadgeView(Context context) {
            super(context);
            setWillNotDraw(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Tiny static badge: software layer avoids extra GPU queue contention with the VR compositor.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            paint.setAlpha(220);
            icon = loadIcon(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (icon == null || icon.isRecycled()) return;
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || w < h) return; // landscape stereo layout only
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float eyeW = w / 2f;
            float margin = Math.min(8f * dm.density, eyeW * 0.02f);
            float size = Math.min(18f * dm.density, eyeW * 0.06f);
            if (size < 6f) size = 6f;
            for (int eye = 0; eye < 2; eye++) {
                float left = eye * eyeW + margin;
                float top = margin;
                dst.set(left, top, left + size, top + size);
                canvas.drawBitmap(icon, null, dst, paint);
            }
        }
    }
}
