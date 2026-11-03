package com.jarvis.assistant;

import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Small floating pill shown while JARVIS works in another app: a pulsing dot plus the current step.
 * Uses an accessibility overlay window, so no extra "draw over apps" permission is needed.
 */
final class StatusOverlay {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static LinearLayout root;
    private static TextView text;
    private static LinearLayout buttons;
    private static ObjectAnimator pulse;

    private static int dp(JarvisAccessibilityService s, int v) {
        return Math.round(v * s.getResources().getDisplayMetrics().density);
    }

    private static WindowManager.LayoutParams params(boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | (touchable ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = 160;
        return lp;
    }

    private static void ensure(JarvisAccessibilityService s) {
        if (root != null) return;
        root = new LinearLayout(s);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(s, 18), dp(s, 12), dp(s, 18), dp(s, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6081522"));
        bg.setCornerRadius(dp(s, 28));
        bg.setStroke(dp(s, 1), Color.parseColor("#27D3FF"));
        root.setBackground(bg);

        LinearLayout row = new LinearLayout(s);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(s);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.parseColor("#27D3FF"));
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(s, 12), dp(s, 12));
        dl.rightMargin = dp(s, 10);
        row.addView(dot, dl);
        text = new TextView(s);
        text.setTextColor(Color.parseColor("#CFEAFF"));
        text.setTextSize(14);
        text.setMaxWidth(dp(s, 260));
        row.addView(text);
        root.addView(row);

        buttons = new LinearLayout(s);
        buttons.setGravity(Gravity.END);
        buttons.setVisibility(View.GONE);
        root.addView(buttons);

        pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f);
        pulse.setDuration(700);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.start();

        ((WindowManager) s.getSystemService(JarvisAccessibilityService.WINDOW_SERVICE)).addView(root, params(false));
    }

    static void show(String msg) {
        H.post(() -> {
            JarvisAccessibilityService s = JarvisAccessibilityService.get();
            if (s == null) return;
            ensure(s);
            text.setText(msg);
        });
    }

    static void hide() {
        H.post(() -> {
            JarvisAccessibilityService s = JarvisAccessibilityService.get();
            if (s == null || root == null) return;
            pulse.cancel();
            try { ((WindowManager) s.getSystemService(JarvisAccessibilityService.WINDOW_SERVICE)).removeView(root); }
            catch (IllegalArgumentException ignored) { }
            root = null; text = null; buttons = null;
        });
    }

    /** Blocks the calling (background) thread until the user taps Allow/Deny. Denies after 60 s. */
    static boolean confirm(String prompt) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] allowed = {false};
        H.post(() -> {
            JarvisAccessibilityService s = JarvisAccessibilityService.get();
            if (s == null) { latch.countDown(); return; }
            ensure(s);
            text.setText(prompt);
            buttons.removeAllViews();
            Button deny = new Button(s); deny.setText("Deny");
            Button allow = new Button(s); allow.setText("Allow");
            deny.setOnClickListener(v -> { latch.countDown(); });
            allow.setOnClickListener(v -> { allowed[0] = true; latch.countDown(); });
            buttons.addView(deny);
            buttons.addView(allow);
            buttons.setVisibility(View.VISIBLE);
            ((WindowManager) s.getSystemService(JarvisAccessibilityService.WINDOW_SERVICE))
                    .updateViewLayout(root, params(true));
        });
        try { latch.await(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        H.post(() -> {
            JarvisAccessibilityService s = JarvisAccessibilityService.get();
            if (s == null || root == null) return;
            buttons.setVisibility(View.GONE);
            ((WindowManager) s.getSystemService(JarvisAccessibilityService.WINDOW_SERVICE))
                    .updateViewLayout(root, params(false));
        });
        return allowed[0];
    }
}
