package com.jarvis.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The assistant orb: a glass sphere with soft silver light flowing inside it (Siri-like, in black and white).
 * States change the flow speed and the pulse; the microphone level swells it while listening.
 */
final class FluidOrbView extends View {
    enum State { IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING, SUCCESS, ERROR }

    private final float d;
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();

    private State state = State.IDLE;
    private float time, speed = 1f, targetSpeed = 1f, level, flash = 1f, errorMix;
    private long last;
    private ValueAnimator clock, flashAnim;
    private Shader haloSh, bodySh, blobSh, glossSh, rimSh;
    private float lastR = -1;

    FluidOrbView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.2f * d);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.2f * d);
    }

    @Override protected void onMeasure(int w, int h) {
        int s = Math.round(132f * d);
        setMeasuredDimension(s, s);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        last = 0;
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(1000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            long now = System.nanoTime();
            float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            speed += (targetSpeed - speed) * Math.min(1f, dt * 4f);   // ease between speeds so changes never jump
            time += dt * speed;
            errorMix += ((state == State.ERROR ? 1f : 0f) - errorMix) * Math.min(1f, dt * 6f);
            invalidate();
        });
        clock.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (clock != null) clock.cancel();
        if (flashAnim != null) flashAnim.cancel();
        super.onDetachedFromWindow();
    }

    void setLevel(float l) { level = level * 0.6f + Math.max(0f, Math.min(1f, l)) * 0.4f; }

    void setState(State s) {
        if (s == state) return;
        state = s;
        targetSpeed = s == State.THINKING ? 2.6f : s == State.TRANSCRIBING ? 2.0f : s == State.SPEAKING ? 1.8f
                : s == State.LISTENING ? 1.3f : 0.8f;
        if (s == State.SUCCESS) {
            if (flashAnim != null) flashAnim.cancel();
            flashAnim = ValueAnimator.ofFloat(1f, 1.14f, 1f);
            flashAnim.setDuration(320);
            flashAnim.addUpdateListener(a -> flash = (float) a.getAnimatedValue());
            flashAnim.start();
        } else flash = 1f;
        if (s != State.LISTENING) level = 0f;
    }

    private void buildShaders(float r) {
        lastR = r;
        haloSh = new RadialGradient(0, 0, r * 1.6f, new int[]{0x38FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[]{0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        bodySh = new RadialGradient(-r * 0.25f, -r * 0.3f, r * 1.4f, new int[]{0xFF2A2A2E, 0xFF0C0C0E, 0xFF000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        blobSh = new RadialGradient(0, 0, r * 0.62f, new int[]{0xB3FFFFFF, 0x55D8D8DE, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        glossSh = new LinearGradient(0, -r, 0, r * 0.1f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        rimSh = new SweepGradient(0, 0, new int[]{0xCCFFFFFF, 0x22FFFFFF, 0x99FFFFFF, 0x11FFFFFF, 0xCCFFFFFF}, null);
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = 38f * d * flash * (1f + level * 0.18f);
        if (Math.abs(r - lastR) > 0.01f || haloSh == null) buildShaders(r);
        c.save();
        c.translate(cx, cy);

        halo.setShader(haloSh);
        float breathe = 1f + 0.04f * (float) Math.sin(time * 2.2f);
        c.save(); c.scale(breathe, breathe);
        c.drawCircle(0, 0, r * 1.6f, halo);
        c.restore();

        if (state == State.SPEAKING || state == State.LISTENING) {
            for (int i = 0; i < 2; i++) {
                float t = ((time * 0.55f) + i * 0.5f) % 1f;
                ring.setColor(((Math.round(120 * (1f - t))) << 24) | 0xFFFFFF);
                c.drawCircle(0, 0, r * (1.05f + 0.45f * t), ring);
            }
        }

        body.setShader(bodySh);
        c.drawCircle(0, 0, r, body);

        clip.reset();
        clip.addCircle(0, 0, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        blob.setShader(blobSh);
        for (int i = 0; i < 4; i++) {   // four soft lights drifting on different orbits
            float a = time * (0.9f + i * 0.37f) + i * 1.9f;
            float x = (float) Math.cos(a) * r * (0.42f + 0.08f * i);
            float y = (float) Math.sin(a * 1.13f + i) * r * (0.40f + 0.06f * i);
            float sc = 0.8f + 0.35f * (float) Math.sin(a * 0.7f + i);
            blob.setAlpha(Math.round(255 * (i % 2 == 0 ? 0.9f : 0.6f)));
            c.save(); c.translate(x, y); c.scale(sc, sc);
            c.drawCircle(0, 0, r * 0.62f, blob);
            c.restore();
        }
        gloss.setShader(glossSh);
        c.save(); c.translate(0, -r * 0.42f); c.scale(0.78f, 0.5f);
        c.drawCircle(0, 0, r * 0.9f, gloss);
        c.restore();
        c.restore();

        rim.setShader(rimSh);
        c.save(); c.rotate(time * 40f);
        c.drawCircle(0, 0, r - rim.getStrokeWidth() / 2f, rim);
        c.restore();
        if (errorMix > 0.01f) {   // brief red edge on an error
            rim.setShader(null);
            rim.setColor(((Math.round(220 * errorMix)) << 24) | 0xE5484D);
            c.drawCircle(0, 0, r, rim);
        }
        c.restore();
    }
}
