package com.jarvis.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Authenticated POSTs to the JARVIS backend. Tries the primary URL, then the Wi-Fi fallback. */
final class Http {
    private static final class Resp { int code; byte[] data; }

    private static Resp exec(Context ctx, String path, byte[] body, String contentType, int readTimeoutMs) throws IOException {
        IOException last = null;
        aborted = false;
        for (String base : new String[]{Config.baseUrl(ctx), Config.fallbackUrl(ctx)}) {
            if (base == null || base.trim().isEmpty()) continue;
            try { return execTo(ctx, base, path, body, contentType, readTimeoutMs); }
            catch (IOException e) { last = e; if (aborted) throw e; }   // unreachable -> try the next URL, unless the user pressed Stop
        }
        throw last != null ? last : new IOException("No backend URL configured");
    }

    private static volatile long lastWarm;

    /** Wake a sleeping backend (and open the TLS connection) in the background, so the first real request is not the slow one. */
    static void warmUp(final Context ctx) {
        final long now = System.currentTimeMillis();
        if (now - lastWarm < 4 * 60_000L) return;
        lastWarm = now;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String base = Config.baseUrl(app);
                if (base == null || base.trim().isEmpty()) return;
                HttpURLConnection c = (HttpURLConnection) new URL(base.replaceAll("/$", "") + "/api/health").openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(45000);
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) { }
        }, "jarvis-warm").start();
    }

    private static volatile HttpURLConnection current;

    /** Abort whatever request is in flight (used by Stop). The blocked read throws immediately. */
    private static volatile boolean aborted;

    static void cancelCurrent() {
        aborted = true;
        HttpURLConnection c = current;
        if (c != null) { try { c.disconnect(); } catch (Exception ignored) { } }
    }

    private static Resp execTo(Context ctx, String base, String path, byte[] body, String contentType, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base.replaceAll("/$", "") + path).openConnection();
        current = c;
        c.setConnectTimeout(3000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType);
        c.setRequestProperty("Authorization", "Bearer " + Config.token(ctx));
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bos.write(buf, 0, n); in.close(); }
        r.data = bos.toByteArray();
        return r;
    }

    private static JSONObject toJson(Resp r) throws Exception {
        String s = new String(r.data, StandardCharsets.UTF_8);
        JSONObject j = new JSONObject(s.isEmpty() ? "{}" : s);
        if (r.code >= 400 && !j.has("error")) j.put("error", "HTTP " + r.code);
        return j;
    }

    static JSONObject post(Context ctx, String path, JSONObject body, int readTimeoutMs) throws Exception {
        return toJson(exec(ctx, path, body.toString().getBytes(StandardCharsets.UTF_8), "application/json", readTimeoutMs));
    }

    /** Raw bytes in (e.g. WAV), JSON out. */
    static JSONObject postBytes(Context ctx, String path, byte[] body, String contentType, int readTimeoutMs) throws Exception {
        return toJson(exec(ctx, path, body, contentType, readTimeoutMs));
    }

    /** JSON in, raw bytes out (e.g. MP3). Throws on any non-2xx so callers can fall back. */
    static byte[] postForBytes(Context ctx, String path, JSONObject body, int readTimeoutMs) throws Exception {
        Resp r = exec(ctx, path, body.toString().getBytes(StandardCharsets.UTF_8), "application/json", readTimeoutMs);
        if (r.code >= 400) throw new IOException("HTTP " + r.code);
        return r.data;
    }
}
