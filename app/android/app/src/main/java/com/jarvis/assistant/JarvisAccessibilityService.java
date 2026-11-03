package com.jarvis.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Gives JARVIS eyes and hands on the whole device: dumps the UI tree, takes
 * screenshots and performs taps / typing / scrolling in any app.
 * The user must enable it manually in Settings → Accessibility.
 */
public class JarvisAccessibilityService extends AccessibilityService {

    private static volatile JarvisAccessibilityService instance;
    private static final int MAX_SHOT_WIDTH = 1080;

    /** id → node from the most recent observe(). */
    private final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();
    /** screenshot px = real px * lastScale */
    private volatile float lastScale = 1f;

    public static JarvisAccessibilityService get() { return instance; }

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }

    // ───────────────────────── observe ─────────────────────────

    public synchronized JSONObject observe(boolean withScreenshot) throws JSONException {
        lastNodes.clear();
        JSONArray nodes = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : "";

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int realW = dm.widthPixels, realH = dm.heightPixels;
        float scale = realW > MAX_SHOT_WIDTH ? (float) MAX_SHOT_WIDTH / realW : 1f;
        lastScale = scale;

        if (root != null) walk(root, nodes, scale);

        JSONObject out = new JSONObject();
        out.put("package", pkg);
        out.put("width", Math.round(realW * scale));
        out.put("height", Math.round(realH * scale));
        out.put("nodes", nodes);
        if (withScreenshot) {
            String shot = screenshotBase64(scale);
            if (shot != null) out.put("screenshot_b64", shot);
        }
        return out;
    }

    private void walk(AccessibilityNodeInfo n, JSONArray out, float scale) throws JSONException {
        if (n == null) return;
        if (n.isVisibleToUser()) {
            CharSequence text = n.getText();
            CharSequence desc = n.getContentDescription();
            boolean interesting = text != null || desc != null || n.isClickable() || n.isEditable() || n.isScrollable();
            if (interesting) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                JSONObject o = new JSONObject();
                o.put("id", lastNodes.size());
                o.put("text", text == null ? "" : text.toString());
                o.put("desc", desc == null ? "" : desc.toString());
                o.put("cls", n.getClassName() == null ? "" : n.getClassName().toString());
                o.put("clickable", n.isClickable());
                o.put("editable", n.isEditable());
                o.put("scrollable", n.isScrollable());
                o.put("bounds", new JSONArray(new int[]{
                        Math.round(r.left * scale), Math.round(r.top * scale),
                        Math.round(r.right * scale), Math.round(r.bottom * scale)}));
                out.put(o);
                lastNodes.add(n);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, scale);
    }

    private String screenshotBase64(float scale) {
        final Bitmap[] holder = new Bitmap[1];
        final CountDownLatch latch = new CountDownLatch(1);
        takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(),
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult r) {
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                            if (hw != null) holder[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                            r.getHardwareBuffer().close();
                        } finally { latch.countDown(); }
                    }
                    @Override public void onFailure(int code) { latch.countDown(); }
                });
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        Bitmap bmp = holder[0];
        if (bmp == null) return null;
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale),
                    Math.round(bmp.getHeight() * scale), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    // ───────────────────────── WhatsApp (deterministic, no LLM) ─────────────────────────

    /** Waits for an open WhatsApp chat, types `body` and taps Send. Returns false on timeout. */
    public boolean whatsAppSend(String body) {
        long end = System.currentTimeMillis() + 14000;
        boolean typed = false;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && "com.whatsapp".contentEquals(String.valueOf(root.getPackageName()))) {
                if (!typed) {
                    AccessibilityNodeInfo field = firstByIdOrNull(root, "com.whatsapp:id/entry");
                    if (field == null) field = firstMatching(root, n -> n.isEditable());
                    if (field != null) {
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, body);
                        typed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    }
                } else {
                    AccessibilityNodeInfo send = firstByIdOrNull(root, "com.whatsapp:id/send");
                    if (send == null) send = firstMatching(root, n -> n.getContentDescription() != null
                            && "send".equalsIgnoreCase(n.getContentDescription().toString()));
                    if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { sleep(500); return true; }
                }
            }
            sleep(400);
        }
        return false;
    }

    private static AccessibilityNodeInfo firstByIdOrNull(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private static AccessibilityNodeInfo firstMatching(AccessibilityNodeInfo n, java.util.function.Predicate<AccessibilityNodeInfo> p) {
        if (n == null) return null;
        if (p.test(n)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstMatching(n.getChild(i), p);
            if (r != null) return r;
        }
        return null;
    }

    // ───────────────────────── act ─────────────────────────

    /** Executes one action from the backend. Returns a short human-readable result. */
    public synchronized String act(JSONObject a) throws JSONException {
        String kind = a.optString("action");
        switch (kind) {
            case "tap": return tap(a);
            case "type": return type(a);
            case "scroll": return scroll(a.optString("direction", "down"));
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK) ? "back" : "back failed";
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME) ? "home" : "home failed";
            case "open_app": return openApp(a.optString("name"));
            case "wait": sleep(1200); return "waited";
            default: return "unsupported action: " + kind;
        }
    }

    private String tap(JSONObject a) {
        if (a.has("node")) {
            int id = a.optInt("node", -1);
            if (id < 0 || id >= lastNodes.size()) return "tap failed: stale node " + id;
            AccessibilityNodeInfo n = lastNodes.get(id);
            // Prefer a semantic click (walks up to the nearest clickable ancestor).
            for (AccessibilityNodeInfo c = n; c != null; c = c.getParent()) {
                if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "tapped node " + id;
            }
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return gestureTap(r.centerX(), r.centerY()) ? "tapped node " + id + " (gesture)" : "tap failed";
        }
        float s = lastScale == 0 ? 1f : lastScale;
        int x = Math.round((float) a.optDouble("x") / s);
        int y = Math.round((float) a.optDouble("y") / s);
        return gestureTap(x, y) ? "tapped " + x + "," + y : "tap failed";
    }

    private String type(JSONObject a) {
        String text = a.optString("text");
        AccessibilityNodeInfo target = null;
        if (a.has("node")) {
            int id = a.optInt("node", -1);
            if (id >= 0 && id < lastNodes.size()) target = lastNodes.get(id);
        }
        if (target == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (target == null) return "type failed: no input field";
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ? "typed" : "type failed";
    }

    private String scroll(String dir) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int cx = w / 2, cy = h / 2;
        int fx = cx, fy = cy, tx = cx, ty = cy;
        switch (dir) {
            case "down":  fy = (int) (h * 0.70); ty = (int) (h * 0.30); break; // swipe up to see lower content
            case "up":    fy = (int) (h * 0.30); ty = (int) (h * 0.70); break;
            case "left":  fx = (int) (w * 0.75); tx = (int) (w * 0.25); break;
            case "right": fx = (int) (w * 0.25); tx = (int) (w * 0.75); break;
        }
        Path p = new Path();
        p.moveTo(fx, fy);
        p.lineTo(tx, ty);
        return dispatch(p, 350) ? "scrolled " + dir : "scroll failed";
    }

    private String openApp(String name) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String want = name.toLowerCase(Locale.ROOT).trim();
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            int score = label.equals(want) ? 3 : label.startsWith(want) ? 2 : label.contains(want) ? 1 : 0;
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        if (best == null) return "open_app failed: no app named " + name;
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) return "open_app failed: not launchable";
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        sleep(1200);
        return "opened " + best.loadLabel(pm);
    }

    private boolean gestureTap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatch(p, 60);
    }

    private boolean dispatch(Path path, long durationMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
        boolean accepted = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok[0] = true; latch.countDown(); }
            @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
        }, null);
        if (!accepted) return false;
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return ok[0];
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
