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
import androidx.camera.core.CameraSelector;
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
    private static final int CYAN = Color.parseColor("#27D3FF");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean wantFrame = new AtomicBoolean(false), busy = new AtomicBoolean(false);
    private volatile boolean paused, asking, forceNext, speakAloud;
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

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        WakeWordService.pause();      // the microphone is needed for "Ask"
        AgentRunner.initTts(this);
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

        // Top bar: LIVE badge and close.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(36), dp(8), 0);
        LinearLayout live = new LinearLayout(this);
        live.setGravity(Gravity.CENTER_VERTICAL);
        live.setPadding(dp(12), dp(6), dp(14), dp(6));
        GradientDrawable lb = new GradientDrawable();
        lb.setCornerRadius(dp(20));
        lb.setColor(Color.parseColor("#B3070E18"));
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
        cb.setColor(Color.parseColor("#E60A1626"));
        cb.setStroke(dp(1), Color.parseColor("#1B3A5C"));
        card.setBackground(cb);
        caption = label("Point the camera at something.", 18, "#FFFFFF");
        caption.setMinLines(2);
        card.addView(caption);
        status = label("Starting the camera…", 12, "#8FB0CC");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(6);
        card.addView(status, sl);

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
        mb.setColor(Color.parseColor("#B30D2540"));
        mb.setStroke(dp(2), CYAN);
        micBtn.setBackground(mb);
        micBtn.setOnClickListener(v -> onAsk());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(58), dp(58));
        ml.leftMargin = dp(12); ml.rightMargin = dp(12);
        row.addView(micBtn, ml);
        speakBtn = pill("Read aloud: off");
        speakBtn.setOnClickListener(v -> { speakAloud = !speakAloud; speakBtn.setText(speakAloud ? "Read aloud: on" : "Read aloud: off"); });
        row.addView(speakBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(14);
        card.addView(row, rl);

        TextView note = label("Frames are sent to Meta to be read and are not saved. JARVIS describes people but never identifies them.", 11, "#5B7F9E");
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = dp(12);
        card.addView(note, nl);
        root.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
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
        TextView t = label(s, 13, "#E8F3FF");
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(23));
        g.setColor(Color.parseColor("#B30D2540"));
        g.setStroke(dp(1), Color.parseColor("#1B3A5C"));
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis);
                forceNext = true;
                tick();
            } catch (Exception e) {
                status.setText("Couldn't start the camera.");
            }
        }, ContextCompat.getMainExecutor(this));
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
        int[] h = hash(bmp);
        boolean force = forceNext || pendingQuestion != null;
        long now = System.currentTimeMillis();
        if (!force && !changed(h, lastHash) && now - lastSentAt < FORCE_AFTER_MS) return;   // same view: do not spend a call
        final String question = pendingQuestion;
        pendingQuestion = null;
        forceNext = false;
        if (!busy.compareAndSet(false, true)) return;
        lastHash = h;
        lastSentAt = now;
        framesSent++;
        runOnUiThread(() -> status.setText(question != null ? "Looking at that…" : "Looking…"));
        worker.execute(() -> {
            try {
                float sc = Math.min(1f, 1280f / Math.max(bmp.getWidth(), bmp.getHeight()));
                Bitmap small = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                JSONObject body = new JSONObject().put("image", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                if (question != null) body.put("question", question);
                JSONObject r = Http.post(this, "/api/vision", body, 30000);
                final String text = r.optString("text", r.optString("error", "I couldn't read that frame."));
                runOnUiThread(() -> showResult(text, question != null));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Can't reach the server. Retrying…"));
            } finally { busy.set(false); }
        });
    }

    private void showResult(String text, boolean wasQuestion) {
        if (isFinishing()) return;
        caption.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            caption.setText(text);
            caption.animate().alpha(1f).setDuration(180).start();
        }).start();
        status.setText(paused ? "Paused" : "Looking · updates when the view changes");
        asking = false;
        if (wasQuestion || speakAloud) AgentRunner.speak(text, () -> { });
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

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (recorder != null) recorder.cancel();
        if (analysisExec != null) analysisExec.shutdownNow();
        worker.shutdownNow();
        WakeWordService.resume();
        super.onDestroy();
    }
}
