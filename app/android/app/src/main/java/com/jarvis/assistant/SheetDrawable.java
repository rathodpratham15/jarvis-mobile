package com.jarvis.assistant;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Background of the assistant sheet: a dark gradient with rounded top corners and a soft cyan-to-violet glow
 * that follows the CURVE of those corners (so there is never a straight strip or rectangle above the sheet).
 */
final class SheetDrawable extends Drawable {
    private final float d, r;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private float intensity = 1f;   // 0..1, pulsed by the activity

    SheetDrawable(float density) {
        d = density;
        r = 28f * d;
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    void setIntensity(float v) { intensity = v; invalidateSelf(); }
    public float getIntensity() { return intensity; }

    @Override public void draw(Canvas c) {
        RectF b = new RectF(getBounds());
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xF5141414, 0xFF000000, Shader.TileMode.CLAMP));
        // Extend the rectangle below the view so the bottom corners are not rounded.
        c.drawRoundRect(new RectF(b.left, b.top, b.right, b.bottom + r), r, r, fill);

        glow.setShader(new LinearGradient(b.left, 0, b.right, 0,
                new int[]{0xFFFFFFFF, 0xFFA3A3A3, 0xFFFFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        // Draw the glow in its own layer, then fade it out down the sides so it has no visible end.
        float fadeEnd = b.top + r + 44f * d;
        int layer = c.saveLayer(b.left, b.top, b.right, fadeEnd, null);
        float[] widths = {10f * d, 6f * d, 3f * d, 1.5f * d};
        int[] alphas = {26, 46, 90, 230};
        for (int i = 0; i < widths.length; i++) {
            float in = widths[i] / 2f;
            glow.setStrokeWidth(widths[i]);
            glow.setAlpha(Math.round(alphas[i] * intensity));
            c.drawPath(topArc(b, in, fadeEnd), glow);
        }
        fade.setShader(new LinearGradient(0, b.top + r * 0.5f, 0, fadeEnd, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(b.left, b.top, b.right, fadeEnd, fade);
        c.restoreToCount(layer);
    }

    /** Open path along the top edge and both rounded corners, inset so the stroke stays inside the view. */
    private Path topArc(RectF b, float in, float sideEnd) {
        float rr = r - in;
        Path p = new Path();
        p.moveTo(b.left + in, sideEnd);
        p.lineTo(b.left + in, b.top + r);
        p.arcTo(new RectF(b.left + in, b.top + in, b.left + in + 2 * rr, b.top + in + 2 * rr), 180f, 90f, false);
        p.lineTo(b.right - in - rr, b.top + in);
        p.arcTo(new RectF(b.right - in - 2 * rr, b.top + in, b.right - in, b.top + in + 2 * rr), 270f, 90f, false);
        p.lineTo(b.right - in, sideEnd);
        return p;
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
