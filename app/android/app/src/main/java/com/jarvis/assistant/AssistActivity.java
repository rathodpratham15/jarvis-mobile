package com.jarvis.assistant;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Gemini-style assistant panel: bottom pill with "+", live transcript and mic, over a glowing edge.
 * Opens (via long-press home/power) already listening; hands the request to AgentRunner and closes.
 */
public class AssistActivity extends Activity {
    private static final int REQ_MIC = 1, REQ_IMAGE = 2;
    private static final int CYAN = Color.parseColor("#27D3FF");

    private VoiceRecorder recorder;
    private SpeechRecognizer deviceStt;   // fallback when Meta transcription is unavailable
    private TextView transcript, mic;
    private ImageView thumb;
    private View glow;
    private ObjectAnimator glowPulse;
    private String attachment;
    private boolean submitted;
    private boolean listening;
    private LinearLayout col;
    private View lastCard;
    private final JSONArray history = new JSONArray();
    private String pendingAction;            // device action awaiting the user's choice
    private JSONObject pendingArgs;
    private java.util.List<String> pendingChoices;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        WakeWordService.pause();   // free the microphone for this panel
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        setContentView(buildUi());
        // Debug builds only: let adb inject a typed request (no microphone needed) for testing.
        String typed = getIntent().getStringExtra("utterance");
        if (typed != null && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            AgentRunner.initTts(this);
            submit(typed);
            return;
        }
        java.util.List<String> need = new java.util.ArrayList<>();
        for (String perm : new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
                Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR})
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) need.add(perm);
        if (need.isEmpty()) startListening();
        else requestPermissions(need.toArray(new String[0]), REQ_MIC);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish()); // tap outside the pill to dismiss

        // Glowing scrim rising from the bottom edge.
        glow = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#6627D3FF"), Color.TRANSPARENT});
        glow.setBackground(g);
        root.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM));
        glowPulse = ObjectAnimator.ofFloat(glow, "alpha", 0.55f, 1f);
        glowPulse.setDuration(900);
        glowPulse.setRepeatMode(ObjectAnimator.REVERSE);
        glowPulse.setRepeatCount(ObjectAnimator.INFINITE);
        glowPulse.start();

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cl.setMargins(dp(16), 0, dp(16), dp(28));
        root.addView(col, cl);

        thumb = new ImageView(this);
        thumb.setVisibility(View.GONE);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(72), dp(72));
        tl.bottomMargin = dp(10);
        tl.gravity = Gravity.START;
        col.addView(thumb, tl);

        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(18), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F0081522"));
        bg.setCornerRadius(dp(36));
        bg.setStroke(dp(1), Color.parseColor("#6627D3FF"));
        pill.setBackground(bg);
        pill.setOnClickListener(v -> { });   // swallow taps so the pill doesn't dismiss
        col.addView(pill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextColor(Color.WHITE);
        plus.setTextSize(28);
        plus.setPadding(0, 0, dp(14), dp(4));
        plus.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
            startActivityForResult(i, REQ_IMAGE);
        });
        pill.addView(plus);

        transcript = new TextView(this);
        transcript.setText("Listening…");
        transcript.setTextColor(Color.parseColor("#CFEAFF"));
        transcript.setTextSize(18);
        transcript.setMaxLines(2);
        pill.addView(transcript, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mic = new TextView(this);
        mic.setText("🎙");
        mic.setTextSize(22);
        mic.setGravity(Gravity.CENTER);
        GradientDrawable mb = new GradientDrawable();
        mb.setShape(GradientDrawable.OVAL);
        mb.setColor(Color.parseColor("#1F5E8C"));
        mic.setBackground(mb);
        mic.setOnClickListener(v -> {
            if (listening && recorder != null) recorder.finish();   // stop now and transcribe what was heard
            else startListening();
        });
        pill.addView(mic, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return root;
    }

    /** Record -> Meta transcription -> submit. Replaces Android's SpeechRecognizer. */
    private void startListening() {
        if (submitted) return;
        if (recorder != null) recorder.cancel();
        listening = true;
        transcript.setText("Listening\u2026");
        mic.setVisibility(View.VISIBLE);
        recorder = new VoiceRecorder(new VoiceRecorder.Listener() {
            @Override public void onLevel(float level) {
                runOnUiThread(() -> { float s = 1f + level * 0.5f; mic.setScaleX(s); mic.setScaleY(s); });
            }
            @Override public void onResult(byte[] wav) {
                runOnUiThread(() -> { listening = false; mic.setScaleX(1f); mic.setScaleY(1f); });
                if (wav == null) { runOnUiThread(() -> transcript.setText("Didn't catch that \u2014 tap the mic")); return; }
                runOnUiThread(() -> transcript.setText("Transcribing\u2026"));
                new Thread(() -> {
                    String text = null, err = null;
                    try {
                        JSONObject j = Http.postBytes(AssistActivity.this, "/api/stt", wav, "audio/wav", 30000);
                        text = j.optString("text", "").trim();
                        if (j.has("error")) err = j.optString("error");
                    } catch (Exception e) { err = e.getMessage(); }
                    final String t = text, e = err;
                    runOnUiThread(() -> {
                        if (t != null && !t.isEmpty()) submit(t);
                        else if (e != null) { transcript.setText("Switching to device voice\u2026"); deviceListen(); }
                        else transcript.setText("Didn't catch that \u2014 tap the mic");
                    });
                }, "jarvis-stt").start();
            }
        });
        // Give the wake-word listener a moment to release the microphone.
        root().postDelayed(() -> { if (recorder != null && !isFinishing()) recorder.start(); }, 350);
    }

    /** Fallback speech-to-text: the phone's own recognizer (Google/Samsung). Needs the user to speak again. */
    private void deviceListen() {
        if (submitted) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { transcript.setText("Couldn't transcribe \u2014 tap the mic"); return; }
        if (deviceStt != null) deviceStt.destroy();
        deviceStt = SpeechRecognizer.createSpeechRecognizer(this);
        deviceStt.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { transcript.setText("Listening (device voice)\u2026"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int e) { transcript.setText("Didn't catch that \u2014 tap the mic"); }
            @Override public void onEvent(int t, Bundle p) { }
            @Override public void onPartialResults(Bundle p) {
                ArrayList<String> l = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) transcript.setText(l.get(0));
            }
            @Override public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty() && !l.get(0).trim().isEmpty()) submit(l.get(0).trim());
                else transcript.setText("Didn't catch that \u2014 tap the mic");
            }
        });
        deviceStt.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true));
    }

    private void submit(String goal) {
        if (submitted) return;
        submitted = true;
        transcript.setText("\u201c" + goal + "\u201d");
        mic.setVisibility(View.GONE);
        AgentRunner.initTts(this);
        if (pendingAction != null) { resolveChoice(goal); return; }
        new Thread(() -> {
            JSONObject r;
            try {
                JSONObject body = new JSONObject().put("utterance", goal)
                        .put("units", java.util.Locale.getDefault().getCountry().equals("US") ? "imperial" : "metric")
                        .put("history", history)
                        .put("now", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).format(new java.util.Date()))
                        .put("tz", java.util.TimeZone.getDefault().getID())
                        .put("messaging_apps", new JSONArray(DeviceActions.messagingApps(this)));
                if (attachment != null) body.put("attachments", new JSONArray().put(attachment));
                JSONObject screen = ScreenContext.pointsAtScreen(goal) ? ScreenContext.forRequest() : null;
                if (screen != null) body.put("screen", screen);
                r = Http.post(this, "/api/assistant", body, 60000);
            } catch (Exception e) {
                r = new JSONObject();
                try { r.put("type", "answer").put("say", "Sorry, I couldn't reach the server."); } catch (Exception ignored) { }
            }
            final JSONObject res = r;
            runOnUiThread(() -> handle(goal, res));
        }, "jarvis-ask").start();
    }

    private void remember(String user, String assistant) {
        try {
            history.put(new JSONObject().put("role", "user").put("content", user));
            history.put(new JSONObject().put("role", "assistant").put("content", assistant));
        } catch (Exception ignored) { }
    }

    /** Speak, then (if `relisten`) reopen the mic automatically for the user's reply. */
    private void sayThen(String say, boolean relisten) {
        AgentRunner.speak(say, () -> {
            if (relisten && !isFinishing()) { submitted = false; mic.setVisibility(View.VISIBLE); startListening(); }
            else root().postDelayed(this::finish, 1800);
        });
    }

    private void handle(String goal, JSONObject r) {
        String type = r.optString("type");
        String say = r.optString("say", r.optString("error", "Sorry, something went wrong."));
        if (r.has("error") && !r.has("type")) say = "Sorry, " + r.optString("error");
        if (type.equals("operate")) {
            // No direct tool fits: close the panel and work hidden in the background.
            AgentRunner.start(this, r.optString("goal", goal), attachment);
            finish();
            return;
        }
        if (type.equals("ask")) {                       // JARVIS needs more info: ask, then listen again
            remember(goal, say);
            showCard(null, say);
            sayThen(say, true);
            return;
        }
        if (type.equals("device")) {
            final String action = r.optString("action"), backendSay = say;
            final JSONObject args = r.optJSONObject("args") == null ? new JSONObject() : r.optJSONObject("args");
            showCard(null, "Working on it\u2026");
            new Thread(() -> {
                DeviceActions.Result res = DeviceActions.run(this, action, args);
                runOnUiThread(() -> afterDevice(goal, action, args, backendSay, res));
            }, "jarvis-device").start();
            return;
        }
        remember(goal, say);
        showCard(r.optJSONObject("card"), say);
        sayThen(say, false);
        root().postDelayed(this::finish, 15000);
    }

    private void afterDevice(String goal, String action, JSONObject args, String backendSay, DeviceActions.Result res) {
        if (res.choices != null) {                       // several contacts match: ask which
            pendingAction = action; pendingArgs = args; pendingChoices = res.choices;
            String q = "Which one: " + String.join(", or ", res.choices) + "?";
            showCard(null, q);
            sayThen(q, true);
            return;
        }
        String say = res.say != null ? res.say : backendSay;
        remember(goal, say);
        showCard(null, say);
        sayThen(say, false);
    }

    private void resolveChoice(String reply) {
        String norm = ContactMatcher.norm(reply);
        String chosen = null;
        for (String c : pendingChoices) {
            if (ContactMatcher.norm(c).equals(norm) || norm.contains(ContactMatcher.norm(c))) { chosen = c; break; }
        }
        if (chosen == null) for (String c : pendingChoices) {          // e.g. user said just "piyush"
            for (String w : ContactMatcher.norm(c).split(" ")) if (!w.equals("mummy") && w.length() > 2 && norm.contains(w)) chosen = c;
        }
        final String action = pendingAction; final JSONObject args = pendingArgs;
        pendingAction = null; pendingArgs = null; pendingChoices = null;
        if (chosen == null) { showCard(null, "Okay, cancelled."); sayThen("Okay, cancelled.", false); return; }
        try { args.put("contact", chosen); } catch (Exception ignored) { }
        showCard(null, "Working on it\u2026");
        new Thread(() -> {
            DeviceActions.Result res = DeviceActions.run(this, action, args);
            runOnUiThread(() -> afterDevice(reply, action, args, "Done.", res));
        }, "jarvis-device").start();
    }

    private View root() { return getWindow().getDecorView(); }

    private void showCard(JSONObject card, String say) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F0081522"));
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), Color.parseColor("#6627D3FF"));
        c.setBackground(bg);
        c.setOnClickListener(v -> { });
        c.addView(text(say, 18, "#FFFFFF", false));
        if (card != null) {
            c.addView(text(card.optString("title"), 14, "#9FC4E0", false), spaced(14));
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(card.optString("big"), 34, "#FFFFFF", false));
            TextView sub = text(card.optString("subtitle"), 18, "#CFEAFF", false);
            sub.setPadding(dp(16), 0, 0, 0);
            row.addView(sub);
            c.addView(row);
            JSONArray rows = card.optJSONArray("rows");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONArray kv = rows.optJSONArray(i);
                LinearLayout line = new LinearLayout(this);
                TextView k = text(kv.optString(0), 14, "#9FC4E0", false);
                TextView v = text(kv.optString(1), 14, "#FFFFFF", false);
                line.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                line.addView(v);
                c.addView(line, spaced(6));
            }
        }
        if (lastCard != null) col.removeView(lastCard);
        lastCard = c;
        col.addView(c, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = dp(12);
    }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMAGE || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in); }
            if (bmp == null) return;
            float s = Math.min(1f, 1024f / Math.max(bmp.getWidth(), bmp.getHeight()));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * s), Math.round(bmp.getHeight() * s), true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            attachment = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            thumb.setImageBitmap(scaled);
            thumb.setVisibility(View.VISIBLE);
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening();
        else transcript.setText("Microphone permission is needed to listen.");
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) recorder.cancel();
        if (deviceStt != null) deviceStt.destroy();
        if (glowPulse != null) glowPulse.cancel();
        ScreenContext.clear();      // never keep the screenshot around
        WakeWordService.resume();  // go back to listening for "Hey Jarvis"
        super.onDestroy();
    }
}
