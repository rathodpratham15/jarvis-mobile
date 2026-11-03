package com.jarvis.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/** Backend URL + device token, written by the web UI and read natively by the assistant. */
final class Config {
    private static SharedPreferences p(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE); }
    static String baseUrl(Context c) { return p(c).getString("baseUrl", "http://localhost:5050"); }
    static String fallbackUrl(Context c) { return p(c).getString("fallbackUrl", ""); }
    static String token(Context c) { return p(c).getString("token", ""); }
    static void save(Context c, String baseUrl, String token, String fallbackUrl) {
        p(c).edit().putString("baseUrl", baseUrl).putString("token", token).putString("fallbackUrl", fallbackUrl).apply();
    }
}
