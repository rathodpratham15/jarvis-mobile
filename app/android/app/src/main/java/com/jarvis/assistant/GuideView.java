package com.jarvis.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The JARVIS guide. The character was designed and edited by Meta's Muse Image model (one base picture plus edited
 * mouth shapes and a blink); see docs/GUIDE_CHARACTER.md. Only small face patches are swapped while it talks, so the
 * hair and clothes never flicker. Motion (breathing, bounce, squash and stretch, lean) is applied in code.
 */
final class GuideView extends View {
    enum Pose { IDLE, TALK, WAVE, POINT, CHEER }

    private static Bitmap base;
    private static final Map<String, Bitmap> patches = new HashMap<>();
    private static final Map<String, int[]> pos = new HashMap<>();   // name -> x, y (in sprite pixels)
    private static int spriteW, spriteH;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);   // separate from the sprite paint, or the sprite inherits its transparency
    private final RectF r = new RectF();

    private Pose pose = Pose.IDLE;
    private float pointDir = 1f;
    private long t0 = System.nanoTime(), nextBlinkAt = 2200, blinkUntil, cheerStart;

    // Speech: the mouth steps through the text over the duration of the audio.
    private String speech = "";
    private long speechStart, speechMs;
    private String mouth = "mouth_closed";

    private ValueAnimator clock;

    GuideView(Context c) {
        super(c);
        load(c);
    }

    private static synchronized void load(Context c) {
        if (base != null) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            base = BitmapFactory.decodeResource(c.getResources(), R.drawable.guide_base, o);
            String[] names = {"mouth_closed", "mouth_ah", "mouth_oh", "mouth_ee", "eyes_blink"};
            int[] ids = {R.drawable.guide_mouth_closed, R.drawable.guide_mouth_ah, R.drawable.guide_mouth_oh,
                    R.drawable.guide_mouth_ee, R.drawable.guide_eyes_blink};
            for (int i = 0; i < names.length; i++) patches.put(names[i], BitmapFactory.decodeResource(c.getResources(), ids[i], o));
            try (InputStream in = c.getResources().openRawResource(R.raw.guide_sprite)) {
                byte[] b = new byte[in.available()];
                in.read(b);
                JSONObject m = new JSONObject(new String(b, "UTF-8"));
                spriteW = m.getInt("w");
                spriteH = m.getInt("h");
                JSONObject pj = m.getJSONObject("patches");
                for (String n : names) pos.put(n, new int[]{pj.getJSONObject(n).getInt("x"), pj.getJSONObject(n).getInt("y")});
            }
        } catch (Exception e) {
            base = null;
        }
    }

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

    // ---- control (same API as before) ----

    void setPose(Pose pz) { pose = pz; if (pz == Pose.CHEER) cheerStart = ms(); }
    void pointToward(float dir) { pointDir = dir < 0 ? -1f : 1f; }

    void speak(String text, long durationMs) {
        speech = text == null ? "" : text;
        speechMs = durationMs > 0 ? durationMs : Math.max(1200, speech.length() * 62L);
        speechStart = ms();
        if (pose == Pose.IDLE) pose = Pose.TALK;
    }

    void stopSpeaking() { speechMs = 0; mouth = "mouth_closed"; if (pose == Pose.TALK) pose = Pose.IDLE; }

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

    /** Which mouth picture to show for the letter being spoken right now. */
    private String viseme(long now) {
        if (speechMs <= 0 || speech.isEmpty()) return "mouth_closed";
        long el = now - speechStart;
        if (el > speechMs) { speechMs = 0; if (pose == Pose.TALK) pose = Pose.IDLE; return "mouth_closed"; }
        int i = Math.min(speech.length() - 1, (int) (el * speech.length() / (float) speechMs));
        char ch = Character.toLowerCase(speech.charAt(i));
        if ("ae".indexOf(ch) >= 0) return "mouth_ah";
        if ("ouw".indexOf(ch) >= 0) return "mouth_oh";
        if ("iy".indexOf(ch) >= 0) return "mouth_ee";
        return "mouth_closed";                       // consonants, spaces and punctuation
    }

    // ---- drawing ----

    @Override protected void onDraw(Canvas c) {
        if (base == null || getWidth() == 0) return;
        long now = ms();
        float shadowH = getHeight() * 0.035f;
        float s = (getHeight() - shadowH * 1.4f) / spriteH;
        float w = spriteW * s;
        float left = (getWidth() - w) / 2f, bottom = getHeight() - shadowH;

        // Ground shadow stays put while the body moves.
        shadow.setColor(0x59000000);
        r.set(getWidth() * 0.18f, getHeight() - shadowH * 1.7f, getWidth() * 0.82f, getHeight() - shadowH * 0.3f);
        c.drawOval(r, shadow);

        // Motion: breathing, lean, bounce, squash and stretch, all pivoting on the feet.
        float breathe = (float) Math.sin(now / 560.0) * 0.007f;
        float lean = 0f, hop = 0f, sx = 1f, sy = 1f + breathe;
        boolean talking = speechMs > 0;
        if (talking) { lean = (float) Math.sin(now / 330.0) * 1.1f; sy += (float) Math.abs(Math.sin(now / 190.0)) * 0.012f; }
        switch (pose) {
            case WAVE:  lean = (float) Math.sin(now / 90.0) * 3.2f; hop = -Math.abs((float) Math.sin(now / 160.0)) * getHeight() * 0.025f; break;
            case POINT: lean = pointDir * 4.5f; break;
            case CHEER: {
                long ce = now - cheerStart;
                float ph = Math.min(1f, ce / 900f);
                hop = -(float) Math.abs(Math.sin(ph * Math.PI * 2)) * getHeight() * 0.06f;
                float squash = (float) Math.sin(ph * Math.PI * 4) * 0.03f;
                sy += squash; sx -= squash * 0.8f;
                if (ce > 1800) pose = talking ? Pose.TALK : Pose.IDLE;
                break;
            }
            default: break;
        }
        c.save();
        c.translate(0, hop);
        c.rotate(lean, getWidth() / 2f, bottom);
        c.scale(sx, sy, getWidth() / 2f, bottom);
        c.translate(left, bottom - spriteH * s);
        c.scale(s, s);

        c.drawBitmap(base, 0, 0, p);

        // Eyes: a quick blink every few seconds.
        if (now > nextBlinkAt) { blinkUntil = now + 150; nextBlinkAt = now + 2300 + (long) (Math.random() * 2700); }
        if (now < blinkUntil) draw(c, "eyes_blink");

        // Mouth: the picture that matches the letter being spoken (the neutral smile is already in the base).
        mouth = viseme(now);
        if (!"mouth_closed".equals(mouth)) draw(c, mouth);
        c.restore();
    }

    private void draw(Canvas c, String name) {
        Bitmap b = patches.get(name);
        int[] at = pos.get(name);
        if (b != null && at != null) c.drawBitmap(b, at[0], at[1], p);
    }
}
