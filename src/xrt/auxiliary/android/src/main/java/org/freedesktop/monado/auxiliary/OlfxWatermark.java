// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * "OLFX - Open Free": drawn on top of the Monado compositor window, once per eye, at a fixed place in the
 * view (head-locked). It is part of the runtime window itself: it appears with every game and has no setting.
 */
public final class OlfxWatermark {
    private OlfxWatermark() {}

    public static View create(Context context) {
        return new WatermarkView(context);
    }

    private static final class WatermarkView extends View {
        private static final String TEXT = "OLFX - Open Free";
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        WatermarkView(Context context) {
            super(context);
            setWillNotDraw(false);
            paint.setColor(Color.WHITE);
            paint.setAlpha(215);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = Math.max(getWidth(), getHeight());
            final int h = Math.min(getWidth(), getHeight());
            if (getWidth() < getHeight()) {
                return; // only meaningful in the landscape stereo layout
            }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            // Eye centres: the lenses are 6 cm apart, centred on the screen.
            float screenMeters = w / dm.xdpi * 0.0254f;
            float lens = 0.06f;
            float leftX = (screenMeters / 2f - lens / 2f) / screenMeters * w;
            float rightX = (screenMeters / 2f + lens / 2f) / screenMeters * w;
            // small mark near the top and a bigger one near the bottom, once per eye
            paint.setTextSize(h * 0.04f);
            float tw = paint.measureText(TEXT);
            float y = h * 0.16f;
            canvas.drawText(TEXT, leftX - tw / 2f, y, paint);
            canvas.drawText(TEXT, rightX - tw / 2f, y, paint);
            paint.setTextSize(h * 0.085f);
            tw = paint.measureText(TEXT);
            float yb = h * 0.86f;
            canvas.drawText(TEXT, leftX - tw / 2f, yb, paint);
            canvas.drawText(TEXT, rightX - tw / 2f, yb, paint);
        }
    }
}
