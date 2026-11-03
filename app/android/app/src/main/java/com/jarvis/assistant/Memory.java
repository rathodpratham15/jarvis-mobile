package com.jarvis.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** User-controlled memory. Stored only on this phone; the facts are sent with each request so the model can use them. */
final class Memory {
    private Memory() { }

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE); }

    static List<String> all(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("facts", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private static void save(Context c, List<String> l) { p(c).edit().putString("facts", new JSONArray(l).toString()).apply(); }

    static void remember(Context c, String fact) {
        List<String> l = all(c);
        for (String f : l) if (f.equalsIgnoreCase(fact)) return;
        l.add(fact.trim());
        if (l.size() > 30) l.remove(0);
        save(c, l);
    }

    /** Removes memories containing the keyword ("all" clears everything). Returns how many were removed. */
    static int forget(Context c, String keyword) {
        List<String> l = all(c);
        String k = keyword.toLowerCase(Locale.ROOT).trim();
        if (k.equals("all") || k.equals("everything")) { save(c, new ArrayList<>()); return l.size(); }
        int before = l.size();
        l.removeIf(f -> f.toLowerCase(Locale.ROOT).contains(k) || k.contains(f.toLowerCase(Locale.ROOT)));
        save(c, l);
        return before - l.size();
    }
}
