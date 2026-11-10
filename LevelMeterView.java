package com.tvkouame.studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Vumètre en barres + courbe d'historique. Plat quand il n'y a aucun signal. */
public class LevelMeterView extends View {

    private static final int BARS = 48;
    private final float[] history = new float[BARS];
    private float current = 0f;
    private boolean signal = false;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public LevelMeterView(Context c) { super(c); }

    public void push(float level, boolean hasSignal) {
        current = level;
        signal = hasSignal;
        System.arraycopy(history, 1, history, 0, BARS - 1);
        history[BARS - 1] = level;
        postInvalidateOnAnimation();
    }

    public void clear() {
        for (int i = 0; i < BARS; i++) history[i] = 0f;
        current = 0f;
        signal = false;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF141A22);
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, 24, 24, p);

        float gap = 4f;
        float bw = (w - 32 - gap * (BARS - 1)) / BARS;
        float mid = h / 2f;
        for (int i = 0; i < BARS; i++) {
            float v = history[i];
            float bh = Math.max(4f, v * (h - 40));
            int color;
            if (v <= 0f) color = 0xFF2A3340;
            else if (v < 0.65f) color = 0xFF2ECC71;
            else if (v < 0.85f) color = 0xFFF1C40F;
            else color = 0xFFE74C3C;
            p.setColor(color);
            float x = 16 + i * (bw + gap);
            r.set(x, mid - bh / 2f, x + bw, mid + bh / 2f);
            canvas.drawRoundRect(r, bw / 2f, bw / 2f, p);
        }

        // Barre de niveau instantané
        p.setColor(0xFF2A3340);
        r.set(16, h - 14, w - 16, h - 8);
        canvas.drawRoundRect(r, 6, 6, p);
        if (signal) {
            p.setColor(0xFF4FC3F7);
            r.set(16, h - 14, 16 + (w - 32) * current, h - 8);
            canvas.drawRoundRect(r, 6, 6, p);
        }
    }
}
