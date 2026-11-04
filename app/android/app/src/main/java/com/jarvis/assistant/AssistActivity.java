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
    private View glow, edgeHolder;
    private LinearLayout sheet;
    private ArcReactorOrbView orb;
    private boolean closing;
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
    // Mission Mode: several actions from one request, run in order with one honest summary at the end.
    private final java.util.ArrayDeque<JSONObject> missionQueue = new java.util.ArrayDeque<>();
    private final java.util.List<String> missionFailures = new java.util.ArrayList<>();
    private String missionSay;
    private int missionTotal, missionDone;
    private boolean inMission;

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
        root.setBackgroundColor(Color.parseColor("#5904080F"));     // 35% scrim over the app behind
        root.setOnClickListener(v -> finish());                      // tap outside the sheet to dismiss

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setGravity(Gravity.CENTER_HORIZONTAL);
        sheet.setPadding(dp(18), dp(12), dp(18), dp(28));
        GradientDrawable sbg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#F00D2540"), Color.parseColor("#FA070E18")});
        float r = dp(28);
        sbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sbg);
        sheet.setOnClickListener(v -> { });                          // swallow taps so the sheet doesn't dismiss
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(sheet, sl);

        // Soft cyan-to-violet glow along the sheet's top edge (outside the sheet, bottom-aligned to it).
        glow = new View(this);
        glow.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#6627D3FF"), Color.TRANSPARENT}));
        FrameLayout glowHolder = new FrameLayout(this);
        glowHolder.setClickable(false);
        View edge = new View(this);
        edge.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#0027D3FF"), Color.parseColor("#B37C5CFF"), Color.parseColor("#7327D3FF"), Color.parseColor("#0027D3FF")}));
        glowHolder.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), Gravity.BOTTOM));
        glowHolder.addView(edge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM));
        edgeHolder = glowHolder;
        root.addView(glowHolder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), Gravity.BOTTOM));
        sheet.getViewTreeObserver().addOnGlobalLayoutListener(() ->
                glowHolder.setTranslationY(-(sheet.getHeight()) + dp(1)));

        glowPulse = ObjectAnimator.ofFloat(glow, "alpha", 0.5f, 1f);
        glowPulse.setDuration(1200);
        glowPulse.setRepeatMode(ObjectAnimator.REVERSE);
        glowPulse.setRepeatCount(ObjectAnimator.INFINITE);
        glowPulse.start();

        View handle = new View(this);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Color.parseColor("#59E8F3FF"));
        hb.setCornerRadius(dp(3));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(44), dp(5));
        hl.topMargin = dp(4); hl.bottomMargin = dp(8);
        sheet.addView(handle, hl);

        thumb = new ImageView(this);
        thumb.setVisibility(View.GONE);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(64), dp(64));
        tl.bottomMargin = dp(6);
        tl.gravity = Gravity.START;
        sheet.addView(thumb, tl);

        transcript = new TextView(this);
        transcript.setText("Listening\u2026");
        transcript.setTextColor(CYAN);
        transcript.setTextSize(13);
        transcript.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        transcript.setGravity(Gravity.CENTER);
        transcript.setMaxLines(2);
        transcript.setShadowLayer(dp(8), 0, 0, Color.parseColor("#5927D3FF"));
        sheet.addView(transcript, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        orb = new ArcReactorOrbView(this);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = dp(2); ol.bottomMargin = dp(2);
        sheet.addView(orb, ol);

        col = new LinearLayout(this);          // reply text and result cards are added here
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        sheet.addView(col, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextColor(Color.parseColor("#8FB0CC"));
        plus.setTextSize(26);
        plus.setGravity(Gravity.CENTER);
        plus.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_IMAGE));
        bar.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        View sp1 = new View(this);
        bar.addView(sp1, new LinearLayout.LayoutParams(0, 1, 1f));

        mic = new TextView(this);
        mic.setText("\uD83C\uDF99");
        mic.setTextSize(22);
        mic.setGravity(Gravity.CENTER);
        GradientDrawable mb = new GradientDrawable();
        mb.setShape(GradientDrawable.OVAL);
        mb.setColor(Color.parseColor("#B30D2540"));
        mb.setStroke(dp(2), CYAN);
        mic.setBackground(mb);
        mic.setOnClickListener(v -> {
            if (listening && recorder != null) recorder.finish();   // stop now and transcribe what was heard
            else startListening();
        });
        bar.addView(mic, new LinearLayout.LayoutParams(dp(54), dp(54)));
        View sp2 = new View(this);
        bar.addView(sp2, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(new View(this), new LinearLayout.LayoutParams(dp(48), 1));   // balances the + button
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(6);
        sheet.addView(bar, bl);

        // Slide up (280 ms, ease-out).
        sheet.setVisibility(View.INVISIBLE);
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight());
            sheet.setVisibility(View.VISIBLE);
            sheet.animate().translationY(0).setDuration(280)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)).start();
        });
        return root;
    }

    /** Slide down (200 ms) before closing. */
    @Override
    public void finish() {
        if (closing || sheet == null) { super.finish(); return; }
        closing = true;
        sheet.animate().translationY(sheet.getHeight()).setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> { super.finish(); overridePendingTransition(0, 0); }).start();
        if (edgeHolder != null) edgeHolder.animate().alpha(0f).setDuration(200).start();
    }

    private void orbState(ArcReactorOrbView.State s) { if (orb != null) orb.setState(s); }

    /** Record -> Meta transcription -> submit. Replaces Android's SpeechRecognizer. */
    private void startListening() {
        if (submitted) return;
        if (recorder != null) recorder.cancel();
        listening = true;
        transcript.setText("Listening\u2026");
        mic.setVisibility(View.VISIBLE);
        mic.setText("\u25A0");
        orbState(ArcReactorOrbView.State.LISTENING);
        recorder = new VoiceRecorder(new VoiceRecorder.Listener() {
            @Override public void onLevel(float level) {
                runOnUiThread(() -> { if (orb != null) orb.setLevel(level); });
            }
            @Override public void onResult(byte[] wav) {
                runOnUiThread(() -> { listening = false; mic.setText("\uD83C\uDF99"); });
                if (wav == null) { runOnUiThread(() -> { orbState(ArcReactorOrbView.State.IDLE); transcript.setText("Didn't catch that \u2014 tap the mic"); }); return; }
                runOnUiThread(() -> { orbState(ArcReactorOrbView.State.TRANSCRIBING); transcript.setText("Transcribing\u2026"); });
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
        orbState(ArcReactorOrbView.State.THINKING);
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
                        .put("memory", new JSONArray(Memory.all(this)))
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
        orbState(ArcReactorOrbView.State.SPEAKING);
        AgentRunner.speak(say, () -> {
            if (!relisten) runOnUiThread(() -> orbState(ArcReactorOrbView.State.SUCCESS));
            if (relisten && !isFinishing()) { submitted = false; mic.setVisibility(View.VISIBLE); startListening(); }
            else root().postDelayed(this::finish, 1800);
        });
    }

    private void handle(String goal, JSONObject r) {
        String type = r.optString("type");
        String say = r.optString("say", r.optString("error", "Sorry, something went wrong."));
        if (r.has("error") && !r.has("type")) say = "Sorry, " + r.optString("error");
        if (r.has("error")) orbState(ArcReactorOrbView.State.ERROR);
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
        if (type.equals("mission")) {
            org.json.JSONArray steps = r.optJSONArray("steps");
            if (steps != null && steps.length() > 0) {
                missionQueue.clear(); missionFailures.clear();
                for (int i = 0; i < steps.length(); i++) missionQueue.add(steps.optJSONObject(i));
                missionTotal = steps.length(); missionDone = 0; missionSay = say; inMission = true;
                runNextMission(goal);
                return;
            }
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
        if (inMission) {
            missionDone++;
            if (res.say != null) missionFailures.add(res.say);       // only failures carry a message
            runNextMission(goal);
            return;
        }
        String say = res.say != null ? res.say : backendSay;
        remember(goal, say);
        showCard(null, say);
        sayThen(say, false);
    }

    private void runNextMission(String goal) {
        JSONObject step = missionQueue.poll();
        if (step == null) {                                           // all done: report what really happened
            inMission = false;
            String say = missionFailures.isEmpty() ? missionSay
                    : (missionTotal - missionFailures.size()) + " of " + missionTotal + " done. " + String.join(" ", missionFailures);
            remember(goal, say);
            showCard(null, say);
            sayThen(say, false);
            return;
        }
        final String action = step.optString("action"), stepSay = step.optString("say", "Done.");
        final JSONObject args = step.optJSONObject("args") == null ? new JSONObject() : step.optJSONObject("args");
        showCard(null, "Step " + (missionDone + 1) + " of " + missionTotal + "\u2026");
        new Thread(() -> {
            DeviceActions.Result res = DeviceActions.run(this, action, args);
            runOnUiThread(() -> afterDevice(goal, action, args, stepSay, res));
        }, "jarvis-mission").start();
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
        if (chosen == null) { inMission = false; missionQueue.clear(); showCard(null, "Okay, cancelled."); sayThen("Okay, cancelled.", false); return; }
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
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#B30D2540"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#1B3A5C"));
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
        ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = dp(8);
        c.setAlpha(0f);
        c.setTranslationY(dp(8));
        c.animate().alpha(1f).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
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
