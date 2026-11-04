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
    private static final int REQ_MIC = 1, REQ_IMAGE = 2, REQ_CAMERA = 3, REQ_CAM_PERM = 4;
    /** Words that mean "use the camera" (not the screen): "scan this", "look at this", "take a photo", "open the camera". */
    /** "live scan", "scan live", "live camera/view", "what am I looking at": the continuously updating camera view. */
    private static final java.util.regex.Pattern LIVE_CUE = java.util.regex.Pattern.compile(
            "\\b(live (scan|camera|view|mode|vision)|scan live|go live|start live|what am i looking at)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    /** Phrases that already say HOW to scan, so JARVIS does not need to ask: "take a photo", "use the camera". */
    private static final java.util.regex.Pattern PHOTO_CUE = java.util.regex.Pattern.compile(
            "\\b(take (?:a )?(?:photo|picture|pic)|photograph|(?:use|open|start) (?:the |my )?camera)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern SCAN_CUE = java.util.regex.Pattern.compile(
            "\\b(scan|look(?:ing)? at|take (?:a )?(?:photo|picture|pic)|(?:use|open|start) (?:the |my )?camera)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int CYAN = Color.parseColor("#27D3FF");

    private VoiceRecorder recorder;
    private SpeechRecognizer deviceStt;   // fallback when Meta transcription is unavailable
    private TextView transcript;
    private ImageView mic;
    private ImageView thumb;
    private SheetDrawable sheetBg;
    private LinearLayout sheet;
    private ArcReactorOrbView orb;
    private boolean closing;
    private ObjectAnimator glowPulse;
    private String attachment;
    private java.io.File cameraFile;       // where the camera app writes the photo (app cache, deleted after use)
    private String scanChoiceGoal;        // set while we are waiting for "photo" or "live"
    private Runnable afterCameraPerm;     // what to do once the camera permission is answered
    private String pendingScanGoal;        // what the user said, re-sent together with the photo
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
    private final java.util.List<String> missionLabels = new java.util.ArrayList<>();
    private final java.util.List<Integer> missionState = new java.util.ArrayList<>();   // 0 pending, 1 active, 2 done, 3 failed

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
        sheetBg = new SheetDrawable(getResources().getDisplayMetrics().density);
        sheet.setBackground(sheetBg);
        sheet.setOnClickListener(v -> { });                          // swallow taps so the sheet doesn't dismiss
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(sheet, sl);

        // The glow lives inside SheetDrawable and follows the curved corners; pulse its intensity.
        glowPulse = ObjectAnimator.ofFloat(sheetBg, "intensity", 0.55f, 1f);
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
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        plus.setPadding(dp(12), dp(12), dp(12), dp(12));
        plus.setContentDescription("Attach an image");
        plus.setOnClickListener(v -> openPhotoChooser());
        bar.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        View sp1 = new View(this);
        bar.addView(sp1, new LinearLayout.LayoutParams(0, 1, 1f));

        mic = new ImageView(this);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setPadding(dp(15), dp(15), dp(15), dp(15));
        mic.setContentDescription("Speak");
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
    }

    private void orbState(ArcReactorOrbView.State s) { if (orb != null) orb.setState(s); }

    /** Record -> Meta transcription -> submit. Replaces Android's SpeechRecognizer. */
    private void startListening() {
        if (submitted) return;
        if (recorder != null) recorder.cancel();
        listening = true;
        transcript.setText("Listening\u2026");
        mic.setVisibility(View.VISIBLE);
        mic.setImageResource(R.drawable.ic_stop);
        mic.setContentDescription("Stop listening");
        orbState(ArcReactorOrbView.State.LISTENING);
        recorder = new VoiceRecorder(new VoiceRecorder.Listener() {
            @Override public void onLevel(float level) {
                runOnUiThread(() -> { if (orb != null) orb.setLevel(level); });
            }
            @Override public void onResult(byte[] wav) {
                runOnUiThread(() -> { listening = false; { mic.setImageResource(R.drawable.ic_mic); mic.setContentDescription("Speak"); } });
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
        if (scanChoiceGoal != null) { answerScanChoice(goal); return; }
        if (LIVE_CUE.matcher(goal).find()) { startLiveScan(); return; }
        if (attachment == null && pendingScanGoal == null && SCAN_CUE.matcher(goal).find()) {
            if (PHOTO_CUE.matcher(goal).find()) openCameraFor(goal); else offerScanChoices(goal);
            return;
        }
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

    private void startLiveScan() {
        startActivity(new Intent(this, LiveScanActivity.class));
        finish();
    }

    private void openPhotoChooser() {
        withCameraPermission(() -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
            Intent chooser = Intent.createChooser(pick, "Add a photo");
            // The camera option is offered only if the permission was granted; the gallery always works.
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                try { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{captureIntent()}); } catch (Exception ignored) { }
            }
            startActivityForResult(chooser, REQ_IMAGE);
        });
    }

    /** "Scan this": ask how, with buttons and by voice. */
    private void offerScanChoices(String goal) {
        scanChoiceGoal = goal;
        submitted = false;
        orbState(ArcReactorOrbView.State.IDLE);
        showChoiceCard("How do you want to scan?", new String[]{"Take a photo", "Live scan", "Choose a photo"}, new Runnable[]{
                () -> { String g = scanChoiceGoal; scanChoiceGoal = null; openCameraFor(g != null ? g : goal); },
                () -> { scanChoiceGoal = null; startLiveScan(); },
                () -> { scanChoiceGoal = null; submitted = false; openPhotoChooser(); }});
        mic.setVisibility(View.VISIBLE);
        transcript.setText("Photo or live? Tap a button, or tap the mic and say it.");
        AgentRunner.speak("Photo, or live scan?", () -> { });      // no auto-listen: the microphone would pick up this very question
    }

    private void answerScanChoice(String reply) {
        String r = reply.toLowerCase(java.util.Locale.ROOT), g = scanChoiceGoal;
        if (r.matches(".*\\b(cancel|never ?mind|forget it|stop|no)\\b.*")) { scanChoiceGoal = null; showCard(null, "Okay."); sayThen("Okay.", false); return; }
        if (r.contains("live") || r.contains("video") || r.contains("continuous")) { scanChoiceGoal = null; startLiveScan(); return; }
        if (r.matches(".*\\b(gallery|choose|pick|library|existing|saved)\\b.*")) { scanChoiceGoal = null; submitted = false; openPhotoChooser(); return; }
        if (r.matches(".*\\b(photo|picture|pic|camera|snap|shot|take|yes|yeah)\\b.*")) { scanChoiceGoal = null; openCameraFor(g); return; }
        // Not understood: stay on the choices rather than guessing.
        submitted = false;
        mic.setVisibility(View.VISIBLE);
        transcript.setText("Say \u201cphoto\u201d or \u201clive\u201d, or tap a button.");
        orbState(ArcReactorOrbView.State.IDLE);
    }

    /** A card with a prompt and a row of tappable buttons. */
    private void showChoiceCard(String title, String[] labels, Runnable[] actions) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#B30D2540"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#1B3A5C"));
        c.setBackground(bg);
        c.setOnClickListener(v -> { });
        c.addView(text(title, 16, "#FFFFFF", false));
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            final Runnable act = actions[i];
            TextView b = text(labels[i], 13, i == 1 ? "#070E18" : "#E8F3FF", i == 1);
            b.setGravity(Gravity.CENTER);
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(dp(22));
            if (i == 1) bb.setColor(CYAN); else { bb.setColor(Color.parseColor("#B30D2540")); bb.setStroke(dp(1), Color.parseColor("#27D3FF")); }
            b.setBackground(bb);
            b.setOnClickListener(v -> act.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(b, lp);
        }
        c.addView(row, spaced(12));
        if (lastCard != null) col.removeView(lastCard);
        lastCard = c;
        col.addView(c, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = dp(8);
    }

    /** Android refuses to open the camera for an app that declares the permission but was never granted it, so ask first. */
    private void withCameraPermission(Runnable go) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { go.run(); return; }
        afterCameraPerm = go;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
    }

    private Intent captureIntent() throws java.io.IOException {
        cameraFile = java.io.File.createTempFile("scan_", ".jpg", getCacheDir());
        Uri out = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile);
        return new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out)
                .putExtra("android.intent.extras.CAMERA_FACING", 0)          // back camera
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** "Scan this": open the back camera, then send the photo together with what the user said. */
    private void openCameraFor(String goal) {
        pendingScanGoal = goal;
        submitted = false;                              // the real request is sent after the photo comes back
        orbState(ArcReactorOrbView.State.IDLE);
        showCard(null, "Opening the camera\u2026 take a photo and I'll look at it.");
        withCameraPermission(() -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingScanGoal = null;
                showCard(null, "I need camera access to scan. Allow it in Settings \u2192 Permissions, or tap + to choose a photo.");
                mic.setVisibility(View.VISIBLE);
                return;
            }
            try { startActivityForResult(captureIntent(), REQ_CAMERA); }
            catch (Exception e) {
                pendingScanGoal = null;
                showCard(null, "I couldn't open the camera. Tap + to choose a photo instead.");
                mic.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Shrink the photo, keep it in memory only, and show a thumbnail. Returns false if it could not be read. */
    private boolean attachBitmapFrom(java.io.File f, Uri uri) {
        try {
            Bitmap bmp;
            if (f != null && f.length() > 0) bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            else try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in); }
            if (bmp == null) return false;
            float sc = Math.min(1f, 1800f / Math.max(bmp.getWidth(), bmp.getHeight()));   // large enough to read a full page of small print
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            attachment = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            thumb.setImageBitmap(scaled);
            thumb.setVisibility(View.VISIBLE);
            return true;
        } catch (Exception e) { return false; }
        finally { if (f != null) f.delete(); }          // never leave the photo on disk
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
                missionQueue.clear(); missionFailures.clear(); missionLabels.clear(); missionState.clear();
                for (int i = 0; i < steps.length(); i++) {
                    missionQueue.add(steps.optJSONObject(i));
                    missionLabels.add(steps.optJSONObject(i).optString("say", "Done."));
                    missionState.add(0);
                }
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
            if (missionDone < missionState.size()) missionState.set(missionDone, res.say != null ? 3 : 2);
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
            showMissionCard(say);
            sayThen(say, false);
            return;
        }
        final String action = step.optString("action"), stepSay = step.optString("say", "Done.");
        final JSONObject args = step.optJSONObject("args") == null ? new JSONObject() : step.optJSONObject("args");
        if (missionDone < missionState.size()) missionState.set(missionDone, 1);
        showMissionCard("Working through " + missionTotal + " steps\u2026");
        new Thread(() -> {
            DeviceActions.Result res = DeviceActions.run(this, action, args);
            runOnUiThread(() -> afterDevice(goal, action, args, stepSay, res));
        }, "jarvis-mission").start();
    }

    /** Checklist card: each step shows pending / working / done / failed as it progresses. */
    private void showMissionCard(String headline) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#B30D2540"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#1B3A5C"));
        c.setBackground(bg);
        c.setOnClickListener(v -> { });
        c.addView(text(headline, 16, "#FFFFFF", false));
        for (int i = 0; i < missionLabels.size(); i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int st = missionState.get(i);
            View mark;
            if (st == 2 || st == 3) {
                ImageView iv = new ImageView(this);
                iv.setImageResource(st == 2 ? R.drawable.ic_check : R.drawable.ic_close);
                mark = iv;
            } else {
                View dot = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                if (st == 1) d.setColor(CYAN); else { d.setColor(Color.TRANSPARENT); d.setStroke(dp(1), Color.parseColor("#5B7F9E")); }
                dot.setBackground(d);
                if (st == 1) {
                    ObjectAnimator p = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f);
                    p.setDuration(700); p.setRepeatMode(ObjectAnimator.REVERSE); p.setRepeatCount(ObjectAnimator.INFINITE); p.start();
                }
                FrameLayout box = new FrameLayout(this);
                box.addView(dot, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
                mark = box;
            }
            row.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView t = text(missionLabels.get(i), 14, st == 3 ? "#E5484D" : st == 0 ? "#8FB0CC" : "#E8F3FF", false);
            t.setPadding(dp(10), 0, 0, 0);
            row.addView(t);
            c.addView(row, spaced(8));
        }
        if (lastCard != null) col.removeView(lastCard);
        lastCard = c;
        col.addView(c, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = dp(8);
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
            if (!card.optString("title").isEmpty()) c.addView(text(card.optString("title"), 14, "#9FC4E0", false), spaced(14));
            if (!card.optString("big").isEmpty() || !card.optString("subtitle").isEmpty()) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(text(card.optString("big"), 34, "#FFFFFF", false));
                TextView sub = text(card.optString("subtitle"), 18, "#CFEAFF", false);
                sub.setPadding(dp(16), 0, 0, 0);
                row.addView(sub);
                c.addView(row);
            }
            String details = card.optString("text", "");
            if (!details.isEmpty()) {                       // long content: scrollable full text under the spoken summary
                TextView body = text(details, 14, "#E8F3FF", false);
                body.setLineSpacing(0, 1.15f);
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(body);
                int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.38f);
                body.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels - dp(92), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.UNSPECIFIED);
                c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(body.getMeasuredHeight(), maxH)));
                ((LinearLayout.LayoutParams) sv.getLayoutParams()).topMargin = dp(10);
            }
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
        if (req == REQ_CAMERA) {
            final String goal = pendingScanGoal;
            pendingScanGoal = null;
            if (res == RESULT_OK && attachBitmapFrom(cameraFile, null) && goal != null) { submit(goal); return; }
            if (cameraFile != null) cameraFile.delete();
            showCard(null, "No photo taken. Say \u201cscan this\u201d to try again, or tap + to choose one.");
            mic.setVisibility(View.VISIBLE);
            return;
        }
        if (req != REQ_IMAGE || res != RESULT_OK) return;
        // From the + chooser: either a picked image (data) or a fresh camera photo (written to cameraFile).
        Uri picked = data == null ? null : data.getData();
        if (!attachBitmapFrom(picked == null ? cameraFile : null, picked)) showCard(null, "I couldn't read that photo.");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_CAM_PERM) {
            Runnable go = afterCameraPerm;
            afterCameraPerm = null;
            if (go != null) go.run();        // the action re-checks the permission and explains if it was denied
            return;
        }
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
