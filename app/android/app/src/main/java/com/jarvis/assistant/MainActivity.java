package com.jarvis.assistant;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DeviceControlPlugin.class); // must precede super.onCreate
        super.onCreate(savedInstanceState);
    }
}
