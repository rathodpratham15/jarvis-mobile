package com.jarvis.assistant;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Required stub. Android (especially Samsung) expects every assistant to declare a recognition service
 * and crashes SystemUI if it is missing. JARVIS does its own listening in AssistActivity using a real
 * recognizer, so this one just reports "unsupported".
 */
public class JarvisRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback cb) {
        try { cb.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) { }
    }
    @Override protected void onCancel(Callback cb) { }
    @Override protected void onStopListening(Callback cb) { }
}
