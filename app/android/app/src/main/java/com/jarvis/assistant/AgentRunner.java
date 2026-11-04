package com.jarvis.assistant;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native observe → backend → act loop, so it keeps running after the voice panel closes. */
final class AgentRunner {
    private static final int MAX_STEPS = 25;
    private static volatile boolean running;
    private static volatile boolean stopRequested;
    private static TextToSpeech tts;

    static boolean isRunning() { return running; }
    static void stop() { stopRequested = true; Http.cancelCurrent(); }

    static synchronized void start(Context ctx, String goal, String attachmentB64) {
        initTts(ctx.getApplicationContext());
        if (running) return;
        running = true;
        stopRequested = false;
        final Context app = ctx.getApplicationContext();
        initTts(app);
        new Thread(() -> {
            try { loop(app, goal, attachmentB64); }
            catch (Exception e) { finish(app, stopRequested ? "Stopped." : "Something went wrong: " + e.getMessage()); }
            finally { running = false; }
        }, "jarvis-agent").start();
    }

    private static void loop(Context app, String goal, String attachment) throws Exception {
        if (JarvisAccessibilityService.get() == null) {
            finish(app, "Please turn on the JARVIS accessibility service first.");
            return;
        }
        StatusOverlay.show("Working on it…"); // quiet: no per-step narration
        StatusOverlay.setStopVisible(true);
        Thread.sleep(700); // let the voice panel finish closing so we observe the real app
        List<String> history = new ArrayList<>();
        for (int step = 1; step <= MAX_STEPS; step++) {
            if (stopRequested) { finish(app, "Stopped."); return; }
            JarvisAccessibilityService svc = JarvisAccessibilityService.get();
            if (svc == null) { finish(app, "Accessibility service was turned off."); return; }

            JSONObject body = new JSONObject();
            body.put("goal", goal);
            body.put("step", step);
            body.put("history", new JSONArray(history));
            body.put("observation", svc.observe(true));
            if (attachment != null) body.put("attachments", new JSONArray().put(attachment));

            JSONObject res = post(app, body);
            if (stopRequested) { finish(app, "Stopped."); return; }     // user tapped Stop while the model was thinking
            if (res.has("error")) { finish(app, "Backend error: " + res.getString("error")); return; }
            JSONObject action = res.getJSONObject("action");
            String kind = action.optString("action");

            if (kind.equals("done")) { finish(app, action.optString("result", "Done.")); return; }
            if (kind.equals("fail")) { finish(app, action.optString("reason", "I couldn't do that.")); return; }

            if (action.optBoolean("needs_confirmation")) {
                if (!StatusOverlay.confirm(action.optString("confirm_prompt", "Allow this action?"))) {
                    finish(app, "Okay, I cancelled that.");
                    return;
                }
            }
            if (stopRequested) { finish(app, "Stopped."); return; }
            String result = svc.act(action);
            history.add(step + ". " + kind + " -> " + result);
            Thread.sleep(700);
        }
        finish(app, "I reached my step limit before finishing.");
    }

    private static JSONObject post(Context app, JSONObject body) throws Exception {
        return Http.post(app, "/api/phone/step", body, 90000);
    }

    private static void finish(Context app, String message) {
        StatusOverlay.setStopVisible(false);
        StatusOverlay.show(message);
        speak(message);
        try { Thread.sleep(2800); } catch (InterruptedException ignored) { }
        StatusOverlay.hide();
    }

    private static final String TAG = "JarvisTTS";
    private static volatile Runnable pendingDone;
    private static volatile boolean ttsReady, initStarted;
    private static volatile String queued;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Idempotent. Prefers Google's engine, falls back to the system default. */
    private static Context appCtx;
    private static android.media.MediaPlayer player;

    static synchronized void initTts(Context ctx) {
        appCtx = ctx.getApplicationContext();
        if (initStarted) return;
        initStarted = true;
        createTts(ctx.getApplicationContext(), "com.google.android.tts");
    }

    private static void createTts(Context app, String engine) {
        final TextToSpeech[] ref = new TextToSpeech[1];
        ref[0] = new TextToSpeech(app, status -> {
            if (status != TextToSpeech.SUCCESS) {
                android.util.Log.w(TAG, "init failed for engine=" + engine);
                if (engine != null) createTts(app, null);
                return;
            }
            TextToSpeech t = ref[0];
            int lang = t.setLanguage(Locale.US);
            android.util.Log.i(TAG, "ready engine=" + t.getDefaultEngine() + " setLanguage=" + lang);
            t.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) { android.util.Log.i(TAG, "started"); lastSpeechMs = 0; fireStart(); }
                @Override public void onDone(String id) { fireDone(); }
                @Override public void onError(String id) { android.util.Log.w(TAG, "utterance error"); fireDone(); }
            });
            tts = t;
            ttsReady = true;
            String q = queued;
            if (q != null) { queued = null; speakNow(q); }
        }, engine);
    }

    private static void speakNow(String s) {
        int r = tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
        android.util.Log.i(TAG, "speak -> " + r + " : " + s);
    }

    private static void fireDone() {
        Runnable r = pendingDone;
        pendingDone = null;
        if (r != null) MAIN.post(r);
    }

    static void speak(String s) { speak(s, null); }

    private static volatile int speechGen;
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<java.io.File>> PREP =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static int prepSlot;

    private static java.io.File fetchClip(String s, int slot) throws Exception {
        byte[] mp3 = Http.postForBytes(appCtx, "/api/tts", new JSONObject().put("text", s), 15000);
        java.io.File f = new java.io.File(appCtx.getCacheDir(), "tts" + slot + ".mp3");
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) { os.write(mp3); }
        return f;
    }

    /** Start downloading the voice clip now, so it is ready by the time the caller actually speaks the same text. */
    static void prepare(String s) {
        if (s == null || s.isEmpty() || appCtx == null || PREP.containsKey(s)) return;
        final int slot = (prepSlot++ % 3) + 1;
        final java.util.concurrent.CompletableFuture<java.io.File> cf = new java.util.concurrent.CompletableFuture<>();
        PREP.put(s, cf);
        new Thread(() -> { try { cf.complete(fetchClip(s, slot)); } catch (Exception e) { cf.completeExceptionally(e); } }, "jarvis-tts-prep").start();
    }

    /** Cut off whatever is being spoken right now (the done/start callbacks are dropped, not fired). */
    static void stopSpeech() {
        speechGen++;                                   // any clip still downloading is dropped when it arrives
        PREP.clear();
        pendingDone = null;
        pendingStart = null;
        queued = null;
        try { if (player != null) { player.stop(); player.release(); player = null; } } catch (Exception ignored) { }
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
    }

    /** Length in ms of the clip being played (0 when the built-in voice speaks and the length is unknown). */
    static volatile long lastSpeechMs;
    private static volatile Runnable pendingStart;

    private static void fireStart() {
        Runnable r = pendingStart;
        pendingStart = null;
        if (r != null) MAIN.post(r);
    }

    /** Like speak(s, done), but onStart runs when sound actually begins (for lip-sync). */
    static void speak(String s, Runnable onStart, Runnable done) {
        pendingStart = onStart;
        speakWith(s, done);
    }

    /** ElevenLabs voice via the backend; falls back to the built-in Android voice if that fails. */
    static void speak(String s, Runnable done) {
        pendingStart = null;
        speakWith(s, done);
    }

    private static void speakWith(String s, Runnable done) {
        pendingDone = done;
        final int gen = ++speechGen;
        if (done != null) MAIN.postDelayed(() -> { if (pendingDone == done) fireDone(); }, 8000L + s.length() * 120L);
        final java.util.concurrent.CompletableFuture<java.io.File> prepared = PREP.remove(s);
        new Thread(() -> {
            try {
                java.io.File f = prepared != null ? prepared.get(15, java.util.concurrent.TimeUnit.SECONDS) : fetchClip(s, 0);
                if (gen != speechGen) return;          // stopped (or replaced) while the clip was downloading
                MAIN.post(() -> { if (gen == speechGen) play(f, s); });
            } catch (Exception e) {
                android.util.Log.w(TAG, "ElevenLabs unavailable (" + e.getMessage() + "), using device voice");
                if (gen == speechGen) speakAndroid(s);
            }
        }, "jarvis-tts").start();
    }

    private static void play(java.io.File f, String fallbackText) {
        try {
            if (player != null) { try { player.release(); } catch (Exception ignored) { } }
            player = new android.media.MediaPlayer();
            player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(f.getAbsolutePath());
            player.setOnPreparedListener(mp -> { lastSpeechMs = mp.getDuration(); mp.start(); fireStart(); android.util.Log.i(TAG, "ElevenLabs playing"); });
            player.setOnCompletionListener(mp -> { mp.release(); player = null; fireDone(); });
            player.setOnErrorListener((mp, w, x) -> { mp.release(); player = null; speakAndroid(fallbackText); return true; });
            player.prepareAsync();
        } catch (Exception e) {
            speakAndroid(fallbackText);
        }
    }

    /** Built-in Android voice (queued until the engine is ready). */
    private static void speakAndroid(String s) {
        if (ttsReady && tts != null) speakNow(s); else { queued = s; android.util.Log.i(TAG, "queued until ready"); }
    }
}
