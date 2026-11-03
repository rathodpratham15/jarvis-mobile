package com.jarvis.assistant;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

/** JS bridge: DeviceControl.{isEnabled, openSettings, observe, act}. */
@CapacitorPlugin(name = "DeviceControl", permissions = {
        @Permission(alias = "mic", strings = {Manifest.permission.RECORD_AUDIO}),
        @Permission(alias = "notif", strings = {"android.permission.POST_NOTIFICATIONS"})
})
public class DeviceControlPlugin extends Plugin {

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject r = new JSObject();
        r.put("enabled", JarvisAccessibilityService.get() != null);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void wakeWordStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("running", WakeWordService.isEnabled());
        r.put("overlay", Settings.canDrawOverlays(getContext()));
        call.resolve(r);
    }

    /** Needs "Display over other apps" (lets the service open the panel from the background) + mic. */
    @PluginMethod
    public void enableWakeWord(PluginCall call) {
        if (!Settings.canDrawOverlays(getContext())) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.reject("overlay");
            return;
        }
        requestPermissionForAliases(new String[]{"mic", "notif"}, call, "wakePermsDone");
    }

    @PermissionCallback
    private void wakePermsDone(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) { call.reject("mic"); return; }
        getContext().startForegroundService(new Intent(getContext(), WakeWordService.class));
        call.resolve();
    }

    @PluginMethod
    public void disableWakeWord(PluginCall call) {
        getContext().startService(new Intent(getContext(), WakeWordService.class).setAction(WakeWordService.ACTION_STOP));
        call.resolve();
    }

    @PluginMethod
    public void saveConfig(PluginCall call) {
        Config.save(getContext(), call.getString("baseUrl", ""), call.getString("token", ""), call.getString("fallbackUrl", ""));
        call.resolve();
    }

    @PluginMethod
    public void observe(PluginCall call) {
        JarvisAccessibilityService svc = JarvisAccessibilityService.get();
        if (svc == null) { call.reject("Accessibility service not enabled"); return; }
        final boolean shot = call.getBoolean("screenshot", true);
        // Blocking work (screenshot latch) must stay off the main thread.
        new Thread(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(svc.observe(shot)));
            } catch (Exception e) {
                call.reject("observe failed: " + e.getMessage());
            }
        }, "jarvis-observe").start();
    }

    @PluginMethod
    public void act(PluginCall call) {
        JarvisAccessibilityService svc = JarvisAccessibilityService.get();
        if (svc == null) { call.reject("Accessibility service not enabled"); return; }
        final JSONObject action = call.getData();
        new Thread(() -> {
            try {
                JSObject r = new JSObject();
                r.put("result", svc.act(action));
                call.resolve(r);
            } catch (Exception e) {
                call.reject("act failed: " + e.getMessage());
            }
        }, "jarvis-act").start();
    }
}
