package com.jarvis.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class JarvisSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) { return new Session(this); }

    static class Session extends VoiceInteractionSession {
        Session(Context c) { super(c); }

        @Override
        public void onShow(Bundle args, int flags) {
            super.onShow(args, flags);
            startAssistantActivity(new Intent(getContext(), AssistActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            hide();
        }
    }
}
