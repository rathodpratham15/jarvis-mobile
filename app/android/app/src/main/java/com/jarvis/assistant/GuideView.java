package com.jarvis.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * The JARVIS guide: a friendly cartoon person (hoodie with an arc-reactor emblem, cyan headset) drawn in code.
 * It stands, breathes and blinks; when it speaks the mouth follows the words; it can wave, point and cheer.
 * Everything is drawn in a 200 x 300 unit space and scaled to the view. It is a cartoon, not a real person's likeness.
 */
final class GuideView extends View {
    enum Pose { IDLE, TALK, WAVE, POINT, CHEER }

    private static final int SKIN = 0xFFF2C6A0, SKIN_SHADE = 0xFFE0AE86, HAIR = 0xFF2B2B3A, HOODIE = 0xFF0D2540,
            PANTS = 0xFF1B3A5C, CYAN = 0xFF27D3FF, MOUTH = 0xFF5A1F27;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    private Pose pose = Pose.IDLE;
    private float pointDir = 1f;              // -1 points left, +1 points right
    private float lookX = 0f;                 // where the eyes look, -1..1
    private float mouthOpen, blink = 1f;
    private long t0 = System.nanoTime(), nextBlinkAt = 2500, blinkUntil, cheerStart;

    // Speech state: the mouth walks through the text over the speech duration.
    private String speech = "";
    private long speechStart, speechMs;

    private ValueAnimator clock;

    GuideView(Context c) { super(c); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(1000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> invalidate());
        clock.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (clock != null) clock.cancel();
        super.onDetachedFromWindow();
    }

    // ---- control ----

    void setPose(Pose pz) { pose = pz; if (pz == Pose.CHEER) cheerStart = ms(); }
    void pointToward(float dir) { pointDir = dir < 0 ? -1f : 1f; lookX = pointDir * 0.7f; }

    /** Speak: the mouth follows the text for durationMs (0 = estimate from the length). */
    void speak(String text, long durationMs) {
        speech = text == null ? "" : text;
        speechMs = durationMs > 0 ? durationMs : Math.max(1200, speech.length() * 62L);
        speechStart = ms();
        if (pose == Pose.IDLE) pose = Pose.TALK;
    }

    void stopSpeaking() { speechMs = 0; if (pose == Pose.TALK) pose = Pose.IDLE; }

    /** Grow out of the spot the view's bottom-centre sits on, with a little overshoot. */
    void popIn() {
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight());
        setScaleX(0f); setScaleY(0f); setAlpha(1f); setVisibility(VISIBLE);
        animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(new OvershootInterpolator(1.9f)).start();
    }

    void popOut(Runnable end) {
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight());
        animate().scaleX(0f).scaleY(0f).setDuration(220).withEndAction(() -> { setVisibility(GONE); if (end != null) end.run(); }).start();
    }

    private long ms() { return (System.nanoTime() - t0) / 1_000_000L; }

    /** Mouth openness for the character being spoken right now. Vowels open wide, m/b/p close. */
    private float targetMouth(long now) {
        if (speechMs <= 0 || speech.isEmpty()) return 0f;
        long el = now - speechStart;
        if (el > speechMs) { speechMs = 0; if (pose == Pose.TALK) pose = Pose.IDLE; return 0f; }
        int i = Math.min(speech.length() - 1, (int) (el * speech.length() / (float) speechMs));
        char ch = Character.toLowerCase(speech.charAt(i));
        if ("aoe".indexOf(ch) >= 0) return 0.95f;
        if ("iuy".indexOf(ch) >= 0) return 0.6f;
        if ("mbp".indexOf(ch) >= 0) return 0.02f;
        if (!Character.isLetter(ch)) return 0.1f;
        return 0.38f;
    }

    // ---- drawing ----

    @Override protected void onDraw(Canvas c) {
        long now = ms();
        float s = Math.min(getWidth() / 200f, getHeight() / 300f);
        c.save();
        c.translate((getWidth() - 200f * s) / 2f, getHeight() - 300f * s);   // stand on the bottom edge
        c.scale(s, s);

        float talk = speechMs > 0 ? 1f : 0f;
        mouthOpen += (targetMouth(now) - mouthOpen) * 0.45f;
        if (now > nextBlinkAt) { blinkUntil = now + 140; nextBlinkAt = now + 2200 + (long) (Math.random() * 2600); }
        blink = now < blinkUntil ? 0.08f : 1f;

        float bob = (float) Math.sin(now / 520.0) * 1.6f;
        float jump = 0f;
        if (pose == Pose.CHEER) {
            long ce = now - cheerStart;
            jump = ce < 900 ? -Math.abs((float) Math.sin(ce / 900.0 * Math.PI * 2)) * 16f : 0f;
            if (ce > 1800) pose = speechMs > 0 ? Pose.TALK : Pose.IDLE;
        }

        // Ground shadow stays put while the body bobs or jumps.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        r.set(52, 286, 148, 299);
        c.drawOval(r, p);

        c.translate(0, bob + jump);
        drawLegs(c);
        drawArm(c, true, now);     // back arm (character's left)
        drawTorso(c);
        drawArm(c, false, now);    // front arm (character's right): the expressive one
        drawHead(c, now, talk);
        c.restore();
    }

    private void drawLegs(Canvas c) {
        p.setColor(PANTS);
        r.set(78, 205, 97, 274); c.drawRoundRect(r, 8, 8, p);
        r.set(103, 205, 122, 274); c.drawRoundRect(r, 8, 8, p);
        p.setColor(0xFFF4F7FA);
        r.set(70, 267, 99, 286); c.drawOval(r, p);
        r.set(101, 267, 130, 286); c.drawOval(r, p);
        p.setColor(CYAN);
        r.set(72, 280, 98, 284); c.drawRoundRect(r, 2, 2, p);
        r.set(102, 280, 128, 284); c.drawRoundRect(r, 2, 2, p);
    }

    private void drawTorso(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(HOODIE);
        r.set(60, 118, 140, 216); c.drawRoundRect(r, 24, 24, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f); p.setColor(0x9927D3FF);
        c.drawRoundRect(r, 24, 24, p);
        // Arc-reactor emblem
        p.setStrokeWidth(3f); p.setColor(CYAN);
        c.drawCircle(100, 166, 13, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0x6627D3FF);
        c.drawCircle(100, 166, 10, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(100, 166, 4.5f, p);
        // Hood collar
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5f); p.setColor(0xFF163355);
        r.set(78, 106, 122, 134); c.drawArc(r, 0, 180, false, p);
    }

    private void drawArm(Canvas c, boolean back, long now) {
        float sx = back ? 66 : 134, sy = 136, ex, ey;
        double tt = now / 1000.0;
        if (back) {
            ex = 46 + (float) Math.sin(tt * 1.4) * 2f; ey = 192;
            if (pose == Pose.CHEER) { ex = 38; ey = 92; }
        } else switch (pose) {
            case WAVE:  ex = 160 + (float) Math.sin(tt * 9) * 9f; ey = 96; break;
            case POINT: ex = 100 + pointDir * 92; ey = 138; break;
            case CHEER: ex = 162; ey = 92; break;
            case TALK:  ex = 156 + (float) Math.sin(tt * 3.2) * 4f; ey = 176 + (float) Math.sin(tt * 4.4) * 9f; break;
            default:    ex = 152; ey = 192;
        }
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(17f); p.setColor(HOODIE);
        c.drawLine(sx, sy, ex, ey, p);
        p.setStrokeWidth(2f); p.setColor(0x6627D3FF);
        c.drawLine(sx, sy, ex, ey, p);
        p.setStyle(Paint.Style.FILL); p.setColor(SKIN);
        c.drawCircle(ex, ey, 8.5f, p);
    }

    private void drawHead(Canvas c, long now, float talk) {
        c.save();
        float tilt = pose == Pose.TALK || talk > 0 ? (float) Math.sin(now / 340.0) * 2.2f : (float) Math.sin(now / 900.0) * 0.8f;
        c.rotate(tilt, 100, 118);
        // neck
        p.setStyle(Paint.Style.FILL); p.setColor(SKIN_SHADE);
        r.set(89, 104, 111, 128); c.drawRect(r, p);
        // hair (back)
        p.setColor(HAIR);
        c.drawCircle(100, 68, 49, p);
        // ears and face
        p.setColor(SKIN);
        c.drawCircle(55, 78, 8, p); c.drawCircle(145, 78, 8, p);
        c.drawCircle(100, 76, 43, p);
        // fringe
        p.setColor(HAIR);
        path.reset();
        path.moveTo(55, 72); path.quadTo(100, 8, 145, 72);
        path.quadTo(126, 50, 104, 60); path.quadTo(84, 46, 55, 72); path.close();
        c.drawPath(path, p);
        // headset: band, ear cups, boom mic
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4f); p.setColor(CYAN);
        r.set(52, 22, 148, 118); c.drawArc(r, 196, 148, false, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(53, 80, 9, p); c.drawCircle(147, 80, 9, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3f);
        path.reset(); path.moveTo(53, 86); path.quadTo(60, 108, 84, 104); c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL); c.drawCircle(86, 104, 4.2f, p);
        // eyes
        float ey = 76, raise = talk > 0 || pose == Pose.CHEER ? 3.2f : 0f;
        for (int k = 0; k < 2; k++) {
            float cx = k == 0 ? 84 : 116;
            p.setColor(0xFFFFFFFF);
            r.set(cx - 8, ey - 9 * blink, cx + 8, ey + 9 * blink); c.drawOval(r, p);
            p.setColor(0xFF1F2430);
            c.drawCircle(cx + lookX * 3.2f, ey, 4.6f * (blink < 0.5f ? 0.2f : 1f), p);
            p.setColor(0xFFFFFFFF);
            if (blink > 0.5f) c.drawCircle(cx + lookX * 3.2f + 1.6f, ey - 1.8f, 1.4f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.6f); p.setColor(HAIR);
            c.drawLine(cx - 8, ey - 14 - raise, cx + 8, ey - 15 - raise - (k == 0 ? 0 : 0), p);
            p.setStyle(Paint.Style.FILL);
        }
        // cheeks
        p.setColor(0x44FF7A8A);
        c.drawCircle(73, 94, 7, p); c.drawCircle(127, 94, 7, p);
        // mouth: smile when closed, an oval that opens with the words when speaking
        if (mouthOpen < 0.09f) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3f); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xFF8A3B3B);
            r.set(88, 90, 112, 108); c.drawArc(r, 20, 140, false, p);
        } else {
            float w = 13 + 8 * mouthOpen, h = 4 + 15 * mouthOpen;
            p.setStyle(Paint.Style.FILL); p.setColor(MOUTH);
            r.set(100 - w, 98, 100 + w, 98 + h); c.drawOval(r, p);
            if (mouthOpen > 0.5f) { p.setColor(0xFFE0707F); r.set(100 - w * 0.55f, 98 + h * 0.55f, 100 + w * 0.55f, 98 + h * 0.95f); c.drawOval(r, p); }
        }
        c.restore();
    }
}
