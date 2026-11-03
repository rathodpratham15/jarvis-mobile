package com.jarvis.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * The screen the user was looking at when they said "Hey Jarvis" (captured before the panel covers it).
 * Held in memory only, expires after 90 s, and is sent to the backend only when the request points at it.
 */
final class ScreenContext {
    private static volatile JSONObject obs;
    private static volatile long at;

    // "this", "these", "screen", "here", "above", "current page/app", "reply" ...
    private static final Pattern CUE = Pattern.compile(
            "\\b(this|these|screen|here|above|current (page|app|screen)|what am i looking at|reply)\\b",
            Pattern.CASE_INSENSITIVE);

    static boolean pointsAtScreen(String utterance) { return CUE.matcher(utterance).find(); }

    /** Blocking (screenshot takes a moment): call off the main thread. */
    static void captureBlocking() {
        JarvisAccessibilityService s = JarvisAccessibilityService.get();
        if (s == null) { obs = null; return; }
        try { obs = s.observe(true); at = System.currentTimeMillis(); } catch (Exception e) { obs = null; }
    }

    static void clear() { obs = null; }

    /** {screenshot_b64, package, text} if fresh, else null. */
    static JSONObject forRequest() {
        JSONObject o = obs;
        if (o == null || System.currentTimeMillis() - at > 90_000 || !o.has("screenshot_b64")) return null;
        try {
            StringBuilder text = new StringBuilder();
            JSONArray nodes = o.optJSONArray("nodes");
            for (int i = 0; nodes != null && i < nodes.length() && text.length() < 1500; i++) {
                String t = nodes.getJSONObject(i).optString("text", "").trim();
                if (!t.isEmpty()) text.append(t).append(" | ");
            }
            return new JSONObject().put("screenshot_b64", o.getString("screenshot_b64"))
                    .put("package", o.optString("package")).put("text", text.toString());
        } catch (Exception e) { return null; }
    }
}
