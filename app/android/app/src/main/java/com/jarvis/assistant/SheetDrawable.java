package com.jarvis.assistant;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Frosted-glass background of the assistant sheet: a translucent dark fill (the real blur happens behind it,
 * in the window), a hairline border that is brightest along the top edge, and a soft highlight from above.
 */
final class SheetDrawable extends Drawable {
    private final float d, r;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int fillTop, fillBottom;

    SheetDrawable(float density, boolean blurred) {
        d = density;
        r = 32f * d;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f * d);
        // With real blur behind we can be more transparent; without it, stay darker so text stays readable.
        fillTop = blurred ? 0x9E101012 : 0xEB101012;
        fillBottom = blurred ? 0xC8000000 : 0xF5000000;
    }

    @Override public void draw(Canvas c) {
        RectF b = new RectF(getBounds());
        RectF full = new RectF(b.left, b.top, b.right, b.bottom + r);   // extend below so the bottom corners are square
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, fillTop, fillBottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(full, r, r, fill);

        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + 120f * d, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(full, r, r, sheen);

        edge.setShader(new LinearGradient(0, b.top, 0, b.top + r * 2.2f, 0x66FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP));
        RectF e = new RectF(full);
        e.inset(d / 2f, d / 2f);
        c.drawRoundRect(e, r, r, edge);
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
