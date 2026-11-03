package com.jarvis.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

/**
 * Always-on "Hey Jarvis" listener (offline, on-device). A Vosk recognizer restricted to the phrase
 * grammar runs in a foreground service. On a match it releases the microphone and opens AssistActivity,
 * which resumes this service when it closes.
 */
public class WakeWordService extends Service implements RecognitionListener {
    static final String ACTION_STOP = "com.jarvis.assistant.WAKE_STOP";
    private static final String CHANNEL = "wake";
    private static final String GRAMMAR = "[\"hey jarvis\", \"jarvis\", \"[unk]\"]";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static volatile boolean enabled;
    private static volatile WakeWordService instance;

    private Model model;
    private SpeechService speech;
    private long lastTrigger;

    static boolean isEnabled() { return enabled; }

    /** Release the microphone (called before the panel starts listening). */
    static void pause() {
        WakeWordService s = instance;
        if (s != null) MAIN.post(s::stopListening);
    }

    /** Re-arm after the panel closes. */
    static void resume() {
        WakeWordService s = instance;
        if (s != null && enabled) MAIN.postDelayed(s::startListening, 1200);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW));
        Notification n = notification("Listening for \u201cHey Jarvis\u201d");
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(7, n);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, WakeWordService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JARVIS").setContentText(text)
                .setContentIntent(open).setOngoing(true)
                .addAction(0, "Turn off", stop).build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(7, notification(text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            enabled = false;
            stopListening();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        enabled = true;
        if (model == null) {
            StorageService.unpack(this, "model-en-us", "model",
                    m -> { model = m; startListening(); },
                    e -> updateNotification("Wake word unavailable: " + e.getMessage()));
        } else startListening();
        return START_STICKY;
    }

    private void startListening() {
        if (model == null || speech != null || !enabled) return;
        try {
            Recognizer rec = new Recognizer(model, 16000f, GRAMMAR);
            speech = new SpeechService(rec, 16000f);
            speech.startListening(this);
            updateNotification("Listening for \u201cHey Jarvis\u201d");
        } catch (Exception e) {
            speech = null;
            updateNotification("Microphone unavailable: " + e.getMessage());
        }
    }

    private void stopListening() {
        if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
    }

    private void maybeTrigger(String json, String key) {
        try {
            String t = new JSONObject(json).optString(key);
            if (!t.contains("jarvis")) return;
        } catch (Exception e) { return; }
        long now = System.currentTimeMillis();
        if (now - lastTrigger < 4000) return;
        lastTrigger = now;
        // Never stop the recognizer from its own thread (join would deadlock): hop to main.
        MAIN.post(() -> {
            stopListening();
            // Grab what the user is looking at before the panel covers it, then open the panel.
            new Thread(() -> {
                ScreenContext.captureBlocking();
                MAIN.post(() -> startActivity(new Intent(this, AssistActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            }, "jarvis-screen").start();
        });
    }

    @Override public void onPartialResult(String h) { maybeTrigger(h, "partial"); }
    @Override public void onResult(String h) { maybeTrigger(h, "text"); }
    @Override public void onFinalResult(String h) { maybeTrigger(h, "text"); }
    @Override public void onError(Exception e) { MAIN.post(() -> { stopListening(); MAIN.postDelayed(this::startListening, 2000); }); }
    @Override public void onTimeout() { }

    @Override
    public void onDestroy() {
        stopListening();
        if (model != null) model.close();
        instance = null;
        super.onDestroy();
    }
}
