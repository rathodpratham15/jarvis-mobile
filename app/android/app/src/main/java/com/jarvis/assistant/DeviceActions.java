package com.jarvis.assistant;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.SmsManager;
import java.util.ArrayList;
import java.util.List;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.provider.AlarmClock;

import org.json.JSONObject;

import java.util.Locale;

/** Direct system actions. */
final class DeviceActions {
    /** say != null overrides the backend's confirmation (errors); choices != null means "ask which one". */
    static final class Result {
        final String say; final List<String> choices;
        Result(String say, List<String> choices) { this.say = say; this.choices = choices; }
        static Result ok() { return new Result(null, null); }
        static Result err(String s) { return new Result(s, null); }
        static Result pick(List<String> c) { return new Result(null, c); }
    }

    private static final String WA = "com.whatsapp";
    private static final String MIME_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile";
    private static final String MIME_VOICE = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call";
    private static final String MIME_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call";

    static List<String> messagingApps(Context ctx) {
        List<String> l = new ArrayList<>();
        try { ctx.getPackageManager().getPackageInfo(WA, 0); l.add("WhatsApp"); } catch (Exception ignored) { }
        if (Telephony.Sms.getDefaultSmsPackage(ctx) != null) l.add("Messages");
        return l;
    }

    static Result run(Context ctx, String name, JSONObject a) {
        try {
            switch (name) {
                case "send_message": return sendMessage(ctx, a);
                case "call_contact": return callContact(ctx, a);
                case "add_calendar_event": return addCalendarEvent(ctx, a);
                default: { String e = runSimple(ctx, name, a); return e == null ? Result.ok() : Result.err(e); }
            }
        } catch (Exception e) {
            return Result.err("Sorry, that didn't work: " + e.getMessage());
        }
    }

    // ---- contacts ----
    private static boolean has(Context c, String perm) { return c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    private static List<ContactMatcher.Contact> phoneContacts(Context ctx) {
        List<ContactMatcher.Contact> l = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone._ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            while (c != null && c.moveToNext()) l.add(new ContactMatcher.Contact(c.getLong(0), c.getString(1), c.getString(2)));
        }
        return l;
    }

    private static List<ContactMatcher.Contact> whatsappContacts(Context ctx, String mime) {
        List<ContactMatcher.Contact> l = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME},
                ContactsContract.Data.MIMETYPE + "=?", new String[]{mime}, null)) {
            while (c != null && c.moveToNext()) l.add(new ContactMatcher.Contact(c.getLong(0), c.getString(1), null));
        }
        return l;
    }

    /** Resolves to exactly one contact, or Result.pick(names) if ambiguous, or Result.err if none. */
    private static Object resolve(String spoken, List<ContactMatcher.Contact> all) {
        List<ContactMatcher.Contact> m = ContactMatcher.match(spoken, all);
        List<String> names = ContactMatcher.distinctNames(m);
        if (names.isEmpty()) return Result.err("I couldn't find " + spoken + " in your contacts.");
        if (names.size() > 1) return Result.pick(names.subList(0, Math.min(3, names.size())));
        return ContactMatcher.first(m, names.get(0));
    }

    private static Result addCalendarEvent(Context ctx, JSONObject a) throws Exception {
        if (!has(ctx, Manifest.permission.WRITE_CALENDAR) || !has(ctx, Manifest.permission.READ_CALENDAR))
            return Result.err("I need calendar permission. Please allow it in JARVIS settings.");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US);
        long start = f.parse(a.getString("start")).getTime();
        long end = a.optString("end").isEmpty() ? start + 3600_000L : f.parse(a.getString("end")).getTime();

        long calId = -1;   // prefer a writable, visible, primary calendar
        try (Cursor c = ctx.getContentResolver().query(android.provider.CalendarContract.Calendars.CONTENT_URI,
                new String[]{android.provider.CalendarContract.Calendars._ID, android.provider.CalendarContract.Calendars.IS_PRIMARY},
                android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=? AND "
                        + android.provider.CalendarContract.Calendars.VISIBLE + "=1",
                new String[]{String.valueOf(android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)}, null)) {
            while (c != null && c.moveToNext()) {
                if (calId < 0 || c.getInt(1) == 1) calId = c.getLong(0);
                if (c.getInt(1) == 1) break;
            }
        }
        if (calId < 0) return Result.err("I couldn't find a calendar to add that to.");
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.CalendarContract.Events.CALENDAR_ID, calId);
        v.put(android.provider.CalendarContract.Events.TITLE, a.getString("title"));
        v.put(android.provider.CalendarContract.Events.DTSTART, start);
        v.put(android.provider.CalendarContract.Events.DTEND, end);
        v.put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID());
        if (!a.optString("location").isEmpty()) v.put(android.provider.CalendarContract.Events.EVENT_LOCATION, a.getString("location"));
        Uri u = ctx.getContentResolver().insert(android.provider.CalendarContract.Events.CONTENT_URI, v);
        return u != null ? Result.ok() : Result.err("I couldn't save that event.");
    }

    private static Result sendMessage(Context ctx, JSONObject a) throws Exception {
        String who = a.getString("contact"), body = a.getString("body");
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return Result.err("I need contacts permission. Please allow it in JARVIS settings.");
        if (a.optString("app").equals("whatsapp")) {
            Object r = resolve(who, whatsappContacts(ctx, MIME_PROFILE));
            if (r instanceof Result) return (Result) r;
            ContactMatcher.Contact c = (ContactMatcher.Contact) r;
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(
                    Uri.parse("content://com.android.contacts/data/" + c.id), MIME_PROFILE).setPackage(WA);
            String err = start(ctx, i);
            if (err != null) return Result.err(err);
            JarvisAccessibilityService svc = JarvisAccessibilityService.get();
            if (svc == null) return Result.err("Turn on the JARVIS accessibility service so I can send WhatsApp messages.");
            return svc.whatsAppSend(body) ? Result.ok() : Result.err("I opened the chat but couldn't send the message.");
        }
        if (!has(ctx, Manifest.permission.SEND_SMS)) return Result.err("I need permission to send texts. Please allow it in JARVIS settings.");
        Object r = resolve(who, phoneContacts(ctx));
        if (r instanceof Result) return (Result) r;
        ContactMatcher.Contact c = (ContactMatcher.Contact) r;
        SmsManager sm = ctx.getSystemService(SmsManager.class);
        ArrayList<String> parts = sm.divideMessage(body);
        sm.sendMultipartTextMessage(c.number, null, parts, null, null);
        return Result.ok();
    }

    private static Result callContact(Context ctx, JSONObject a) throws Exception {
        String who = a.getString("contact");
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return Result.err("I need contacts permission. Please allow it in JARVIS settings.");
        if (a.optString("app").equals("whatsapp")) {
            String mime = a.optBoolean("video") ? MIME_VIDEO : MIME_VOICE;
            Object r = resolve(who, whatsappContacts(ctx, mime));
            if (r instanceof Result) return (Result) r;
            ContactMatcher.Contact c = (ContactMatcher.Contact) r;
            String err = start(ctx, new Intent(Intent.ACTION_VIEW).setDataAndType(
                    Uri.parse("content://com.android.contacts/data/" + c.id), mime).setPackage(WA));
            return err == null ? Result.ok() : Result.err(err);
        }
        if (!has(ctx, Manifest.permission.CALL_PHONE)) return Result.err("I need permission to place calls. Please allow it in JARVIS settings.");
        Object r = resolve(who, phoneContacts(ctx));
        if (r instanceof Result) return (Result) r;
        String err = start(ctx, new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(((ContactMatcher.Contact) r).number))));
        return err == null ? Result.ok() : Result.err(err);
    }

    private static String runSimple(Context ctx, String name, JSONObject a) {
        try {
            switch (name) {
                case "set_alarm": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                            .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "JARVIS"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    return start(ctx, i);
                }
                case "set_timer": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "JARVIS"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    return start(ctx, i);
                }
                case "flashlight": {
                    CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                    cm.setTorchMode(cm.getCameraIdList()[0], a.getBoolean("on"));
                    return null;
                }
                case "open_app": return openApp(ctx, a.getString("name"));
                case "dial_number":
                    return start(ctx, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.getString("number")))));
                case "send_sms":
                    return start(ctx, new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.getString("number"))))
                            .putExtra("sms_body", a.optString("body")));
                default: return "I don't know how to do " + name + " yet.";
            }
        } catch (Exception e) {
            return "Sorry, that didn't work: " + e.getMessage();
        }
    }

    private static String start(Context ctx, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(ctx.getPackageManager()) == null) return "No app on this phone can do that.";
        ctx.startActivity(i);
        return null;
    }

    private static String openApp(Context ctx, String name) {
        PackageManager pm = ctx.getPackageManager();
        String want = name.toLowerCase(Locale.ROOT).trim();
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            int score = label.equals(want) ? 3 : label.startsWith(want) ? 2 : label.contains(want) ? 1 : 0;
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        if (best == null) return "I couldn't find an app called " + name + ".";
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        return launch == null ? "I can't open " + name + "." : start(ctx, launch);
    }
}
