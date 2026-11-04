package com.jarvis.assistant;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live scan: full-screen back-camera preview with a caption that updates every few seconds.
 * Every look is one still frame sent to Meta's vision model, so it is "live" at a few-second rhythm, not video.
 * Frames are re-sent only when the view changes, and a session is capped, so it does not burn credits.
 * People are described generically, never identified (enforced in the backend prompt).
 */
public class LiveScanActivity extends AppCompatActivity {
    private static final int REQ_CAMERA = 11;
    private static final long TICK_MS = 2500;          // how often to consider a new frame
    private static final long FORCE_AFTER_MS = 9000;   // re-describe at least this often even if the view looks the same
    private static final int MAX_FRAMES = 40;          // per session (about two minutes of continuous looking)
    private static final int CYAN = Color.parseColor("#FFFFFF");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService trackWorker = Executors.newSingleThreadExecutor();   // follows the item without waiting on captions
    private volatile long lastTrackAt;
    private volatile Object[] pendingGuide;            // a newer discovery that arrived while the guide was still busy
    private final AtomicBoolean wantFrame = new AtomicBoolean(false), busy = new AtomicBoolean(false);
    private volatile boolean paused, asking, forceNext, speakAloud, summaryRequested;
    private volatile String pendingQuestion;
    private volatile long lastSentAt;
    private volatile int[] lastHash;
    private int framesSent;
    private final Runnable tickRunnable = this::tick;

    private PreviewView preview;
    private TextView caption, status, pauseBtn, speakBtn;
    private ImageView micBtn;
    private VoiceRecorder recorder;
    private ExecutorService analysisExec;
    private CameraControl cameraControl;
    private volatile long lastRefocusAt;
    private View focusRing;
    private FrameLayout summaryOverlay;
    private TextView summaryBody;
    private String summarySpoken = "";

    // Guide character (a cartoon person who pops up from the scanned item and talks about it)
    private GuideView guide;
    private TextView bubble, discoveriesTxt;
    private FrameLayout guideLayer;
    private View cardView, rootView;
    private volatile boolean guideOn = true, guideBusy;
    // Tracking: while the guide is out, keep re-locating its item so it follows the item as the camera moves.
    private volatile String trackItem;
    private volatile int trackMisses;
    private final AtomicBoolean trackBusy = new AtomicBoolean(false);
    private int baseLeft, baseTop, baseBubbleLeft, baseBubbleTop, bubbleW;
    private final Runnable trackTick = new Runnable() {
        @Override public void run() {
            if (trackItem == null || isFinishing()) return;
            wantFrame.set(true);
            main.postDelayed(this, 1300);
        }
    };
    private volatile String lastGuideItem;
    private java.util.Set<String> discoveries = new java.util.HashSet<>();

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        WakeWordService.pause();      // the microphone is needed for "Ask"
        AgentRunner.initTts(this);
        discoveries = new java.util.HashSet<>(getSharedPreferences("jarvis_discoveries", MODE_PRIVATE).getStringSet("items", new java.util.HashSet<>()));
        setContentView(buildUi());
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        rootView = root;
        preview.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP) { focusAt(ev.getX(), ev.getY(), true); v.performClick(); }
            return true;
        });
        focusRing = new View(this);
        GradientDrawable fr = new GradientDrawable();
        fr.setShape(GradientDrawable.OVAL);
        fr.setStroke(dp(2), CYAN);
        fr.setColor(Color.TRANSPARENT);
        focusRing.setBackground(fr);
        focusRing.setVisibility(View.GONE);

        guideLayer = new FrameLayout(this);
        guide = new GuideView(this);
        guide.setVisibility(View.GONE);
        guideLayer.addView(guide, new FrameLayout.LayoutParams(dp(130), dp(300)));
        bubble = label("", 14, "#FFFFFF");
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bb = new GradientDrawable();
        bb.setCornerRadius(dp(18));
        bb.setColor(Color.parseColor("#F2141414"));
        bb.setStroke(dp(1), CYAN);
        bubble.setBackground(bb);
        bubble.setVisibility(View.GONE);
        guideLayer.addView(bubble, new FrameLayout.LayoutParams(-2, -2));
        root.addView(guideLayer, new FrameLayout.LayoutParams(-1, -1));
        root.addView(focusRing, new FrameLayout.LayoutParams(dp(72), dp(72)));

        // Top bar: LIVE badge and close.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(36), dp(8), 0);
        LinearLayout live = new LinearLayout(this);
        live.setGravity(Gravity.CENTER_VERTICAL);
        live.setPadding(dp(12), dp(6), dp(14), dp(6));
        GradientDrawable lb = new GradientDrawable();
        lb.setCornerRadius(dp(20));
        lb.setColor(Color.parseColor("#B3000000"));
        live.setBackground(lb);
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(Color.parseColor("#E5484D"));
        dot.setBackground(dg);
        live.addView(dot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f);
        pulse.setDuration(800); pulse.setRepeatMode(ObjectAnimator.REVERSE); pulse.setRepeatCount(ObjectAnimator.INFINITE); pulse.start();
        TextView liveTxt = label("LIVE", 12, "#FFFFFF");
        liveTxt.setPadding(dp(8), 0, 0, 0);
        liveTxt.setLetterSpacing(0.12f);
        live.addView(liveTxt);
        top.addView(live);
        discoveriesTxt = label("\u2605 " + discoveries.size(), 13, "#FBBF24");
        discoveriesTxt.setPadding(dp(14), 0, 0, 0);
        top.addView(discoveriesTxt);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Color.WHITE);
        close.setPadding(dp(14), dp(14), dp(14), dp(14));
        close.setContentDescription("Close live scan");
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Bottom card: caption, status, controls.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(22));
        GradientDrawable cb = new GradientDrawable();
        float r = dp(24);
        cb.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        cb.setColor(Color.parseColor("#E60A0A0A"));
        cb.setStroke(dp(1), Color.parseColor("#2A2A2A"));
        card.setBackground(cb);
        caption = label("Point the camera at something.", 18, "#FFFFFF");
        caption.setMinLines(2);
        card.addView(caption);
        status = label("Starting the camera…", 12, "#A3A3A3");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(6);
        card.addView(status, sl);

        TextView sumBtn = pill("Full summary of what I see");
        sumBtn.setTextColor(Color.parseColor("#FFFFFF"));
        sumBtn.setOnClickListener(v -> requestSummary());
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(-1, dp(46));
        sbl.topMargin = dp(12);
        card.addView(sumBtn, sbl);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pauseBtn = pill("Pause");
        pauseBtn.setOnClickListener(v -> togglePause());
        row.addView(pauseBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        micBtn = new ImageView(this);
        micBtn.setImageResource(R.drawable.ic_mic);
        micBtn.setPadding(dp(14), dp(14), dp(14), dp(14));
        micBtn.setContentDescription("Ask a question about what you see");
        GradientDrawable mb = new GradientDrawable();
        mb.setShape(GradientDrawable.OVAL);
        mb.setColor(Color.parseColor("#B3141414"));
        mb.setStroke(dp(2), CYAN);
        micBtn.setBackground(mb);
        micBtn.setOnClickListener(v -> onAsk());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(58), dp(58));
        ml.leftMargin = dp(12); ml.rightMargin = dp(12);
        row.addView(micBtn, ml);
        speakBtn = pill("Guide: on");
        speakBtn.setOnClickListener(v -> {
            guideOn = !guideOn;
            speakBtn.setText(guideOn ? "Guide: on" : "Guide: off");
            if (!guideOn) { stopTracking(); AgentRunner.stopSpeech(); guide.stopSpeaking(); guide.popOut(null); bubble.setVisibility(View.GONE); guideBusy = false; }
        });
        row.addView(speakBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(14);
        card.addView(row, rl);

        TextView note = label("Frames are sent to Meta to be read and are not saved. JARVIS describes people but never identifies them.", 11, "#737373");
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = dp(12);
        card.addView(note, nl);
        cardView = card;
        root.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        summaryOverlay = new FrameLayout(this);
        summaryOverlay.setBackgroundColor(Color.parseColor("#F5000000"));
        summaryOverlay.setVisibility(View.GONE);
        summaryOverlay.setOnClickListener(v -> { });
        LinearLayout sc = new LinearLayout(this);
        sc.setOrientation(LinearLayout.VERTICAL);
        sc.setPadding(dp(20), dp(52), dp(20), dp(20));
        TextView st = label("Full summary", 20, "#FFFFFF");
        st.setTypeface(null, android.graphics.Typeface.BOLD);
        sc.addView(st);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        summaryBody = label("", 15, "#F5F5F5");
        summaryBody.setLineSpacing(0, 1.2f);
        summaryBody.setTextIsSelectable(true);
        sv.addView(summaryBody);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, 0, 1f);
        svl.topMargin = dp(14); svl.bottomMargin = dp(14);
        sc.addView(sv, svl);
        LinearLayout btns = new LinearLayout(this);
        TextView readBtn = pill("Read aloud");
        readBtn.setOnClickListener(v -> AgentRunner.speak(summarySpoken, () -> { }));
        TextView closeBtn = pill("Close");
        closeBtn.setOnClickListener(v -> closeSummary());
        btns.addView(readBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(0, dp(48), 1f);
        cbl.leftMargin = dp(10);
        btns.addView(closeBtn, cbl);
        sc.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        summaryOverlay.addView(sc, new FrameLayout.LayoutParams(-1, -1));
        root.addView(summaryOverlay, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private TextView label(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private TextView pill(String s) {
        TextView t = label(s, 13, "#F5F5F5");
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(23));
        g.setColor(Color.parseColor("#B3141414"));
        g.setStroke(dp(1), Color.parseColor("#2A2A2A"));
        t.setBackground(g);
        return t;
    }

    // ---- camera ----

    private void startCamera() {
        status.setText("Looking…");
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new android.util.Size(960, 1280))
                        .build();
                analysisExec = Executors.newSingleThreadExecutor();
                analysis.setAnalyzer(analysisExec, image -> {
                    try {
                        if (!wantFrame.compareAndSet(true, false)) return;
                        Bitmap bmp = image.toBitmap();
                        int rot = image.getImageInfo().getRotationDegrees();
                        if (rot != 0) { Matrix m = new Matrix(); m.postRotate(rot); bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true); }
                        handleFrame(bmp);
                    } finally { image.close(); }
                });
                provider.unbindAll();
                Camera camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis);
                cameraControl = camera.getCameraControl();
                preview.postDelayed(() -> focusAt(preview.getWidth() / 2f, preview.getHeight() / 2.2f, false), 700);
                forceNext = true;
                tick();
            } catch (Exception e) {
                status.setText("Couldn't start the camera.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** Focus (and meter exposure) on a point of the preview. A tap shows a ring; automatic refocus stays quiet. */
    private void focusAt(float x, float y, boolean fromTap) {
        if (cameraControl == null || preview.getWidth() == 0) return;
        try {
            MeteringPoint pt = preview.getMeteringPointFactory().createPoint(x, y);
            cameraControl.startFocusAndMetering(new FocusMeteringAction.Builder(pt,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build());
            lastRefocusAt = System.currentTimeMillis();
        } catch (Exception ignored) { return; }
        if (fromTap) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusRing.getLayoutParams();
            lp.leftMargin = Math.round(x) - dp(36);
            lp.topMargin = Math.round(y) - dp(36);
            focusRing.setLayoutParams(lp);
            focusRing.setVisibility(View.VISIBLE);
            focusRing.setAlpha(1f); focusRing.setScaleX(1.5f); focusRing.setScaleY(1.5f);
            focusRing.animate().scaleX(1f).scaleY(1f).setDuration(220).withEndAction(() ->
                    focusRing.animate().alpha(0f).setStartDelay(500).setDuration(300).withEndAction(() -> focusRing.setVisibility(View.GONE)).start()).start();
        }
    }

    /** Variance of the Laplacian on a small greyscale copy: high for crisp detail, low for a blurred or empty view. */
    private static double sharpness(Bitmap b) {
        int w = 160, h = Math.max(1, Math.round(b.getHeight() * 160f / b.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        s.recycle();
        double[] g = new double[w * h];
        for (int i = 0; i < px.length; i++) g[i] = 0.299 * Color.red(px[i]) + 0.587 * Color.green(px[i]) + 0.114 * Color.blue(px[i]);
        double sum = 0, sum2 = 0; int n = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            double l = 4 * g[y * w + x] - g[y * w + x - 1] - g[y * w + x + 1] - g[(y - 1) * w + x] - g[(y + 1) * w + x];
            sum += l; sum2 += l * l; n++;
        }
        double mean = sum / n;
        return sum2 / n - mean * mean;
    }

    /** Every few seconds, ask the camera pipeline for one fresh frame (unless paused, busy or finished). */
    private void tick() {
        main.removeCallbacks(tickRunnable);
        if (isFinishing()) return;
        if (!paused && !asking && !busy.get() && framesSent < MAX_FRAMES) wantFrame.set(true);
        else if (framesSent >= MAX_FRAMES && !paused) {
            paused = true;
            runOnUiThread(() -> { status.setText("Paused to save usage. Tap Resume to keep looking."); pauseBtn.setText("Resume"); });
        }
        main.postDelayed(tickRunnable, TICK_MS);
    }

    /** Tiny 12x12 brightness fingerprint to tell whether the view really changed. */
    private static int[] hash(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 12, 12, true);
        int[] h = new int[144];
        for (int i = 0; i < 144; i++) {
            int p = s.getPixel(i % 12, i / 12);
            h[i] = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3;
        }
        s.recycle();
        return h;
    }

    private static boolean changed(int[] a, int[] b) {
        if (a == null || b == null) return true;
        long d = 0;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d / a.length > 9;       // average brightness shift per cell
    }

    private void handleFrame(Bitmap bmp) {
        if (trackItem != null && System.currentTimeMillis() - lastTrackAt > 1100) { lastTrackAt = System.currentTimeMillis(); trackFrame(bmp); }
        // ...and carry on: the caption must always describe what the camera sees NOW, even while the guide finishes talking.
        int[] h = hash(bmp);
        long nowMs = System.currentTimeMillis();
        if (sharpness(bmp) < 30 && nowMs - lastRefocusAt > 4000) {          // blurred: do what a person does and refocus
            runOnUiThread(() -> { status.setText("Focusing\u2026"); focusAt(preview.getWidth() / 2f, preview.getHeight() / 2.2f, false); });
        }
        boolean force = forceNext || pendingQuestion != null || summaryRequested;
        long now = System.currentTimeMillis();
        if (!force && !changed(h, lastHash) && now - lastSentAt < FORCE_AFTER_MS) return;   // same view: do not spend a call
        final String question = pendingQuestion;
        final boolean full = summaryRequested;
        summaryRequested = false;
        pendingQuestion = null;
        forceNext = false;
        if (!busy.compareAndSet(false, true)) return;
        lastHash = h;
        lastSentAt = now;
        framesSent++;
        runOnUiThread(() -> status.setText(full ? "Reading everything…" : question != null ? "Looking at that…" : "Looking…"));
        worker.execute(() -> {
            try {
                float sc = Math.min(1f, (full ? 1800f : 1024f) / Math.max(bmp.getWidth(), bmp.getHeight()));
                Bitmap small = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                final String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                JSONObject body = new JSONObject().put("image", b64).put("guide", guideOn && !full);
                if (full) body.put("mode", "full");
                if (question != null) body.put("question", question);
                JSONObject r = Http.post(this, "/api/vision", body, full ? 70000 : 30000);
                final String text = r.optString("text", r.optString("error", "I couldn't read that frame."));
                final String item = r.isNull("item") ? null : r.optString("item", null);
                final String details = r.optString("details", "");
                if (full) runOnUiThread(() -> showSummary(text, details.isEmpty() ? text : details));
                else runOnUiThread(() -> showResult(text, item, b64, question != null));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Can't reach the server. Retrying…"));
            } finally { busy.set(false); }
        });
    }

    private void showResult(String text, String item, String b64, boolean wasQuestion) {
        if (isFinishing()) return;
        caption.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            caption.setText(text);
            caption.animate().alpha(1f).setDuration(180).start();
        }).start();
        status.setText(paused ? "Paused" : "Looking \u00b7 updates when the view changes");
        asking = false;
        if (!guideOn) { if (wasQuestion) AgentRunner.speak(text, () -> { }); return; }
        String key = item == null ? null : item.toLowerCase(java.util.Locale.ROOT).trim();
        boolean newItem = key != null && !key.equals(lastGuideItem);
        if (wasQuestion || newItem) {
            if (guideBusy) pendingGuide = new Object[]{text, item, key, b64, System.currentTimeMillis()};   // the guide is mid-sentence: queue it
            else guideSpeak(text, item, key, b64);
        }
    }

    // ---- the guide: pops up from the item, talks about it, lip-synced ----

    /** The guide has left: if a newer discovery was waiting (and is still fresh), bring the guide back for it. */
    private void guideFree() {
        guideBusy = false;
        Object[] p = pendingGuide;
        pendingGuide = null;
        if (p != null && !isFinishing() && guideOn && System.currentTimeMillis() - (long) p[4] < 12000) {
            guideSpeak((String) p[0], (String) p[1], (String) p[2], (String) p[3]);
        }
    }

    private void guideSpeak(String text, String item, String key, String b64) {
        if (guideBusy) return;
        guideBusy = true;
        lastGuideItem = key;
        main.postDelayed(() -> guideBusy = false, 25000);                  // safety net so the guide can never get stuck
        if (key != null && discoveries.add(key)) {                          // a first-time discovery this device has seen
            getSharedPreferences("jarvis_discoveries", MODE_PRIVATE).edit().putStringSet("items", new java.util.HashSet<>(discoveries)).apply();
            discoveriesTxt.setText("\u2605 " + discoveries.size());
            status.setText("\u2605 New discovery: " + item);
        }
        AgentRunner.prepare(text);                                          // download the voice while SAM looks for the item
        if (item == null) { placeGuide(null); startTalking(text); return; }
        worker.execute(() -> {                                              // SAM 3.1: where is the item in the frame?
            float[] box = null;
            try {
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                Bitmap full = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
                float sc = Math.min(1f, 640f / Math.max(full.getWidth(), full.getHeight()));
                Bitmap sm = Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * sc), Math.round(full.getHeight() * sc), true);
                ByteArrayOutputStream lb = new ByteArrayOutputStream();
                sm.compress(Bitmap.CompressFormat.JPEG, 72, lb);
                JSONObject r = Http.post(this, "/api/locate", new JSONObject()
                        .put("image", Base64.encodeToString(lb.toByteArray(), Base64.NO_WRAP)).put("item", item), 20000);
                if (r.optBoolean("found")) {
                    box = new float[]{(float) r.getDouble("x1"), (float) r.getDouble("y1"), (float) r.getDouble("x2"),
                            (float) r.getDouble("y2"), (float) r.getDouble("frame_w"), (float) r.getDouble("frame_h")};
                }
            } catch (Exception ignored) { }
            final float[] fb = box;
            runOnUiThread(() -> { if (!isFinishing()) { placeGuide(fb); startTalking(text); if (fb != null) startTracking(item); } });
        });
    }

    /** Stand the guide on top of the item (or bottom-left if SAM did not find it), and pop it out of that spot. */
    private void placeGuide(float[] box) {
        int vw = preview.getWidth(), vh = preview.getHeight();
        int gw = dp(130), gh = dp(300), cardTop = vh - cardView.getHeight() - dp(6), topLimit = dp(110);
        int left, feetY;
        boolean pointRight = true;
        if (box != null) {
            float fw = box[4], fh = box[5], sc = Math.max(vw / fw, vh / fh);       // FILL_CENTER crops the frame to the view
            float ox = (fw * sc - vw) / 2f, oy = (fh * sc - vh) / 2f;
            float x1 = box[0] * fw * sc - ox, x2 = box[2] * fw * sc - ox, y1 = box[1] * fh * sc - oy, y2 = box[3] * fh * sc - oy;
            float cx = (x1 + x2) / 2f;
            feetY = Math.round(y1 + (y2 - y1) * 0.22f);
            if (feetY - gh < topLimit) feetY = Math.round(Math.max(y1 + (y2 - y1) * 0.55f, gh + topLimit));
            feetY = Math.min(feetY, cardTop);
            left = Math.max(dp(4), Math.min(vw - gw - dp(4), Math.round(cx - gw / 2f)));
            pointRight = cx >= vw / 2f;
        } else {
            left = dp(12);
            feetY = cardTop - dp(2);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) guide.getLayoutParams();
        lp.leftMargin = left;
        lp.topMargin = Math.max(dp(60), feetY - gh);
        guide.setLayoutParams(lp);
        guide.pointToward(pointRight ? -1f : 1f);   // glance toward the middle of the screen
        guide.setPose(GuideView.Pose.WAVE);
        guide.popIn();
        // Speech bubble on whichever side has more room; above the head if the sides are too narrow.
        int maxW = Math.round(vw * 0.55f);
        bubble.setMaxWidth(maxW);
        FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) bubble.getLayoutParams();
        int spaceRight = vw - (left + gw) - dp(8), spaceLeft = left - dp(8);
        if (Math.max(spaceRight, spaceLeft) >= dp(140)) {
            boolean right = spaceRight >= spaceLeft;
            bl.width = Math.min(maxW, right ? spaceRight : spaceLeft);
            bl.leftMargin = right ? left + gw + dp(4) : Math.max(dp(4), left - bl.width - dp(4));
            bl.topMargin = Math.max(dp(100), lp.topMargin + dp(20));
        } else {
            bl.width = vw - dp(32);
            bl.leftMargin = dp(16);
            bl.topMargin = Math.max(dp(100), lp.topMargin - dp(78));
        }
        bubble.setLayoutParams(bl);
        guide.setTranslationX(0); guide.setTranslationY(0); bubble.setTranslationX(0); bubble.setTranslationY(0);
        baseLeft = lp.leftMargin; baseTop = lp.topMargin; baseBubbleLeft = bl.leftMargin; baseBubbleTop = bl.topMargin; bubbleW = bl.width;
    }

    // ---- following the item as the camera moves ----

    private void startTracking(String item) {
        trackItem = item;
        trackMisses = 0;
        main.removeCallbacks(trackTick);
        main.postDelayed(trackTick, 1300);
    }

    private void stopTracking() {
        trackItem = null;
        main.removeCallbacks(trackTick);
    }

    /** Re-locate the item in this frame (small and fast); glide the guide to it, or let it go if the item is gone. */
    private void trackFrame(Bitmap bmp) {
        final String item = trackItem;
        if (item == null || !trackBusy.compareAndSet(false, true)) return;
        trackWorker.execute(() -> {
            float[] box = null;
            try {
                float sc = Math.min(1f, 640f / Math.max(bmp.getWidth(), bmp.getHeight()));
                Bitmap small = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 70, bos);
                JSONObject r = Http.post(this, "/api/locate", new JSONObject()
                        .put("image", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)).put("item", item), 15000);
                if (r.optBoolean("found")) {
                    box = new float[]{(float) r.getDouble("x1"), (float) r.getDouble("y1"), (float) r.getDouble("x2"),
                            (float) r.getDouble("y2"), (float) r.getDouble("frame_w"), (float) r.getDouble("frame_h")};
                }
            } catch (Exception ignored) { }
            final float[] fb = box;
            runOnUiThread(() -> {
                if (trackItem == null || isFinishing()) return;
                if (fb != null) { trackMisses = 0; moveGuideTo(fb); }
                else if (++trackMisses >= 2) guideLost();
            });
            trackBusy.set(false);
        });
    }

    private void moveGuideTo(float[] box) {
        int vw = preview.getWidth(), vh = preview.getHeight();
        int gw = dp(130), gh = dp(300), cardTop = vh - cardView.getHeight() - dp(6), topLimit = dp(110);
        float fw = box[4], fh = box[5], sc = Math.max(vw / fw, vh / fh);
        float ox = (fw * sc - vw) / 2f, oy = (fh * sc - vh) / 2f;
        float x1 = box[0] * fw * sc - ox, x2 = box[2] * fw * sc - ox, y1 = box[1] * fh * sc - oy, y2 = box[3] * fh * sc - oy;
        float cx = (x1 + x2) / 2f;
        int feetY = Math.round(y1 + (y2 - y1) * 0.22f);
        if (feetY - gh < topLimit) feetY = Math.round(Math.max(y1 + (y2 - y1) * 0.55f, gh + topLimit));
        feetY = Math.min(feetY, cardTop);
        int left = Math.max(dp(4), Math.min(vw - gw - dp(4), Math.round(cx - gw / 2f)));
        int top = Math.max(dp(60), feetY - gh);
        float dx = left - baseLeft, dy = top - baseTop;
        if (Math.abs(dx - guide.getTranslationX()) < dp(40) && Math.abs(dy - guide.getTranslationY()) < dp(40)) return;   // too small to matter: stay put
        // Keep the speech bubble on screen while it follows.
        float bdx = Math.max(dp(4) - baseBubbleLeft, Math.min(vw - bubbleW - dp(4) - baseBubbleLeft, dx));
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator();
        guide.animate().translationX(dx).translationY(dy).setDuration(380).setInterpolator(ease).start();
        bubble.animate().translationX(bdx).translationY(dy).setDuration(380).setInterpolator(ease).start();
        guide.pointToward(cx >= vw / 2f ? -1f : 1f);
    }

    /** The item left the view: stop talking about it and step away. */
    private void guideLost() {
        stopTracking();
        AgentRunner.stopSpeech();
        guide.stopSpeaking();
        status.setText("Lost sight of it. Point back at it and I'll try again.");
        bubble.animate().alpha(0f).setDuration(160).withEndAction(() -> bubble.setVisibility(View.GONE)).start();
        guide.popOut(this::guideFree);
        lastGuideItem = null;          // if you find it again, the guide may come back
    }

    private void startTalking(String text) {
        bubble.setText(text);
        bubble.setAlpha(0f);
        bubble.setVisibility(View.VISIBLE);
        bubble.animate().alpha(1f).setStartDelay(260).setDuration(220).start();
        AgentRunner.speak(text, () -> guide.speak(text, AgentRunner.lastSpeechMs), () -> {
            stopTracking();
            guide.stopSpeaking();
            guide.setPose(GuideView.Pose.CHEER);
            main.postDelayed(() -> {
                bubble.animate().alpha(0f).setDuration(200).withEndAction(() -> bubble.setVisibility(View.GONE)).start();
                guide.popOut(this::guideFree);
            }, 1600);
        });
    }

    // ---- full summary ----

    private void requestSummary() {
        if (busy.get()) { status.setText("One moment, I'm still looking…"); return; }
        asking = true;                      // hold the live updates while the full summary is read
        summaryRequested = true;
        status.setText("Reading everything… hold steady");
        wantFrame.set(true);
    }

    private void showSummary(String spoken, String details) {
        summarySpoken = spoken;
        summaryBody.setText(details);
        summaryOverlay.setAlpha(0f);
        summaryOverlay.setVisibility(View.VISIBLE);
        summaryOverlay.animate().alpha(1f).setDuration(200).start();
        if (guideOn) { guide.popOut(null); bubble.setVisibility(View.GONE); guideBusy = false; }
        AgentRunner.speak(spoken, () -> { });
    }

    private void closeSummary() {
        summaryOverlay.animate().alpha(0f).setDuration(160).withEndAction(() -> summaryOverlay.setVisibility(View.GONE)).start();
        asking = false;
        status.setText("Looking…");
    }

    @Override
    public void onBackPressed() {
        if (summaryOverlay != null && summaryOverlay.getVisibility() == View.VISIBLE) closeSummary(); else super.onBackPressed();
    }

    // ---- controls ----

    private void togglePause() {
        paused = !paused;
        pauseBtn.setText(paused ? "Resume" : "Pause");
        status.setText(paused ? "Paused" : "Looking…");
        if (!paused) { if (framesSent >= MAX_FRAMES) framesSent = 0; forceNext = true; }
    }

    /** Ask a spoken question about what the camera sees right now. */
    private void onAsk() {
        if (recorder != null) { recorder.finish(); return; }          // tap again to stop recording early
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone permission is needed to ask a question.");
            return;
        }
        asking = true;
        status.setText("Listening… ask about what you see");
        micBtn.setImageResource(R.drawable.ic_stop);
        recorder = new VoiceRecorder(new VoiceRecorder.Listener() {
            @Override public void onLevel(float level) { }
            @Override public void onResult(byte[] wav) {
                recorder = null;
                runOnUiThread(() -> micBtn.setImageResource(R.drawable.ic_mic));
                if (wav == null) { asking = false; runOnUiThread(() -> status.setText("Didn't catch that. Tap the mic and try again.")); return; }
                runOnUiThread(() -> status.setText("Transcribing…"));
                worker.execute(() -> {
                    String q = "";
                    try { q = Http.postBytes(LiveScanActivity.this, "/api/stt", wav, "audio/wav", 30000).optString("text", "").trim(); }
                    catch (Exception ignored) { }
                    if (q.isEmpty()) { asking = false; runOnUiThread(() -> status.setText("Didn't catch that. Tap the mic and try again.")); return; }
                    final String question = q;
                    pendingQuestion = question;
                    asking = false;
                    runOnUiThread(() -> caption.setText("“" + question + "”"));
                    wantFrame.set(true);
                });
            }
        });
        main.postDelayed(() -> { if (recorder != null) recorder.start(); }, 350);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else { status.setText("Camera permission is needed for live scan."); caption.setText("Allow the camera in Settings → Permissions to use live scan."); }
        }
    }

    /** Leaving the screen (back, home, recents, another app): the guide stops talking at once, like any assistant. */
    @Override
    protected void onStop() {
        stopTracking();
        AgentRunner.stopSpeech();
        guide.stopSpeaking();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopTracking();
        main.removeCallbacksAndMessages(null);
        AgentRunner.stopSpeech();
        guide.stopSpeaking();
        if (recorder != null) recorder.cancel();
        if (analysisExec != null) analysisExec.shutdownNow();
        worker.shutdownNow();
        trackWorker.shutdownNow();
        WakeWordService.resume();
        super.onDestroy();
    }
}
