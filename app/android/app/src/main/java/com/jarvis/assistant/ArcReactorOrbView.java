package com.jarvis.assistant;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * The assistant orb (96dp stage): breathing aura, two rotating dashed rings and a glossy core.
 * States change its colour and motion; voice level makes it pulse while listening.
 */
final class ArcReactorOrbView extends View {
    enum State { IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING, SUCCESS, ERROR }

    private static final int CYAN = 0xFF27D3FF, THINK = 0xFF34D399 /* emerald */, GREEN = 0xFF4ADE80 /* success */, RED = 0xFFE5484D;

    private final float d;
    private final Paint aura = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripple = new Paint(Paint.ANTI_ALIAS_FLAG);

    private State state = State.IDLE;
    private int accent = CYAN;
    private float level, breath = 1f, rotation, rippleT, flash = 1f;

    private ValueAnimator spin, breathe, rippler, colorAnim, flashAnim;

    ArcReactorOrbView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        outer.setStyle(Paint.Style.STROKE);
        outer.setStrokeWidth(2f * d);
        outer.setPathEffect(new DashPathEffect(new float[]{24f * d, 16f * d}, 0f));
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(1f * d);
        inner.setPathEffect(new DashPathEffect(new float[]{6f * d, 24f * d}, 0f));
        ripple.setStyle(Paint.Style.STROKE);
        ripple.setStrokeWidth(2f * d);
    }

    @Override protected void onMeasure(int w, int h) {
        int s = Math.round(120f * d);   // 96dp stage + room for the 120dp aura
        setMeasuredDimension(s, s);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        breathe = ValueAnimator.ofFloat(0.92f, 1.08f);
        breathe.setDuration(1200);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.setInterpolator(new AccelerateDecelerateInterpolator());
        breathe.addUpdateListener(a -> { breath = (float) a.getAnimatedValue(); invalidate(); });
        breathe.start();
        startSpin(12000);
        rippler = ValueAnimator.ofFloat(0f, 1f);
        rippler.setDuration(1100);
        rippler.setRepeatCount(ValueAnimator.INFINITE);
        rippler.setInterpolator(new LinearInterpolator());
        rippler.addUpdateListener(a -> { rippleT = (float) a.getAnimatedValue(); if (state == State.SPEAKING) invalidate(); });
        rippler.start();
    }

    @Override protected void onDetachedFromWindow() {
        for (ValueAnimator a : new ValueAnimator[]{spin, breathe, rippler, colorAnim, flashAnim}) if (a != null) a.cancel();
        super.onDetachedFromWindow();
    }

    private void startSpin(long ms) {
        if (spin != null) spin.cancel();
        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(ms);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> { rotation = (float) a.getAnimatedValue(); invalidate(); });
        spin.start();
    }

    void setLevel(float l) {   // 0..1 microphone level
        level = level * 0.6f + Math.max(0f, Math.min(1f, l)) * 0.4f;
        if (state == State.LISTENING) invalidate();
    }

    void setState(State s) {
        if (s == state) return;
        state = s;
        int target = s == State.THINKING ? THINK : s == State.SUCCESS ? GREEN : s == State.ERROR ? RED : CYAN;
        if (colorAnim != null) colorAnim.cancel();
        colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), accent, target);
        colorAnim.setDuration(350);
        colorAnim.addUpdateListener(a -> { accent = (int) a.getAnimatedValue(); invalidate(); });
        colorAnim.start();
        startSpin(s == State.TRANSCRIBING ? 1500 : s == State.THINKING ? 4000 : 12000);
        if (s == State.SUCCESS) {   // one overshoot flash
            if (flashAnim != null) flashAnim.cancel();
            flashAnim = ValueAnimator.ofFloat(1f, 1.2f, 1f);
            flashAnim.setDuration(300);
            flashAnim.addUpdateListener(a -> { flash = (float) a.getAnimatedValue(); invalidate(); });
            flashAnim.start();
        } else flash = 1f;
        if (s != State.LISTENING) level = 0f;
    }

    private static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float pulse = 1f + level * 0.28f;

        // Layer 3: aura glow
        float ar = 60f * d * breath * pulse * flash;
        aura.setShader(new RadialGradient(cx, cy, ar, new int[]{alpha(accent, 0x66), alpha(accent, 0x33), 0x00000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, ar, aura);

        // Speaking: expanding ripples with fading alpha
        if (state == State.SPEAKING) {
            for (int i = 0; i < 2; i++) {
                float t = (rippleT + i * 0.5f) % 1f;
                ripple.setColor(alpha(accent, Math.round(170 * (1f - t))));
                c.drawCircle(cx, cy, (30f + 30f * t) * d, ripple);
            }
        }

        // Layer 2: rotating dashed rings
        outer.setColor(accent);
        inner.setColor(0x80E8F3FF);
        c.save();
        c.rotate(rotation, cx, cy);
        c.drawCircle(cx, cy, 44f * d * pulse, outer);
        c.restore();
        c.save();
        c.rotate(-rotation * 1.6f, cx, cy);
        c.drawCircle(cx, cy, 34f * d, inner);
        c.restore();

        // Layer 1: glossy core sphere
        float cr = 29f * d * flash * (1f + level * 0.12f);
        core.setShader(new RadialGradient(cx - 8f * d, cy - 8f * d, cr * 1.4f,
                new int[]{0xFFFFFFFF, accent, 0xFF0B1C2E},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, cr, core);
    }
}
