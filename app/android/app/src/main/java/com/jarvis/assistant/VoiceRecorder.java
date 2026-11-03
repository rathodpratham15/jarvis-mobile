package com.jarvis.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Records one utterance as 16 kHz mono 16-bit WAV (the format Meta's transcription API wants) and
 * stops itself shortly after you stop talking, using a simple adaptive energy detector.
 */
final class VoiceRecorder {
    interface Listener {
        void onLevel(float level);          // 0..1, for the pulsing mic
        void onResult(byte[] wavOrNull);    // null = nothing heard
    }

    private static final int RATE = 16000;
    private final Listener listener;
    private volatile boolean stop, manual, cancelled;

    VoiceRecorder(Listener l) { listener = l; }

    void start() { new Thread(this::run, "jarvis-rec").start(); }
    /** User tapped the mic: stop now and keep what was captured. */
    void finish() { manual = true; stop = true; }
    void cancel() { cancelled = true; stop = true; }

    private void run() {
        int frame = RATE / 50;  // 20 ms
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, frame * 20));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); if (!cancelled) listener.onResult(null); return; }

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] buf = new short[frame];
        long t0 = System.currentTimeMillis(), lastVoice = 0;
        boolean spoke = false;
        double noise = 0; int calib = 0;
        rec.startRecording();
        while (!stop) {
            int n = rec.read(buf, 0, frame);
            if (n <= 0) continue;
            double sum = 0;
            ByteBuffer bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) { sum += (double) buf[i] * buf[i]; bb.putShort(buf[i]); }
            pcm.write(bb.array(), 0, n * 2);
            double rms = Math.sqrt(sum / n);
            listener.onLevel((float) Math.min(1.0, rms / 6000.0));

            long now = System.currentTimeMillis(), elapsed = now - t0;
            if (calib < 12) { noise += rms / 12; calib++; continue; }   // first ~240 ms = room noise floor
            double thr = Math.max(700, noise * 2.5);
            if (rms > thr) { spoke = true; lastVoice = now; }
            if (spoke && now - lastVoice > 900) break;      // paused after speaking
            if (!spoke && elapsed > 7000) break;             // never started
            if (elapsed > 15000) break;                      // hard cap
        }
        rec.stop();
        rec.release();
        if (cancelled) return;
        boolean enough = spoke || (manual && pcm.size() > RATE);   // >0.5 s if the user tapped stop
        listener.onResult(enough ? wav(pcm.toByteArray()) : null);
    }

    private static byte[] wav(byte[] pcm) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
}
