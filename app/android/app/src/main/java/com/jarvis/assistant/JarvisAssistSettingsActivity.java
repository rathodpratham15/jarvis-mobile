package com.jarvis.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Target of the assistant's "settings" gear in system settings: just opens the main app. */
public class JarvisAssistSettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
