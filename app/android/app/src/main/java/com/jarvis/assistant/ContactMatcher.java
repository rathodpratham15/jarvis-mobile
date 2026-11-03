package com.jarvis.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a spoken name ("mummy") to saved contacts, on-device.
 * Tiers, first non-empty wins:
 *   1. literal exact match      "mummy"  -> "Mummy"           (NOT "Badi Mummy")
 *   2. nickname-group exact     "mom"    -> "Mummy"/"Mother"
 *   3. whole-word containment   "piyush" -> "Piyush Mummy"
 */
final class ContactMatcher {
    static final class Contact {
        final long id; final String name; final String number;
        Contact(long id, String name, String number) { this.id = id; this.name = name; this.number = number; }
    }

    private static final String[][] GROUPS = {
            {"mom", "mommy", "mummy", "mumma", "mum", "mother", "mama", "maa", "ma"},
            {"dad", "daddy", "papa", "pappa", "father", "pop", "baba", "pitaji"},
    };

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static Set<String> variants(String q) {
        Set<String> v = new LinkedHashSet<>();
        v.add(q);
        for (String[] g : GROUPS) if (Arrays.asList(g).contains(q)) v.addAll(Arrays.asList(g));
        return v;
    }

    static List<Contact> match(String query, List<Contact> all) {
        String q = norm(query);
        if (q.isEmpty()) return new ArrayList<>();
        List<Contact> literal = new ArrayList<>(), nick = new ArrayList<>(), part = new ArrayList<>();
        Set<String> v = variants(q);
        List<String> qWords = Arrays.asList(q.split(" "));
        for (Contact c : all) {
            String n = norm(c.name);
            if (n.equals(q)) literal.add(c);
            else if (v.contains(n)) nick.add(c);
            else if (Arrays.asList(n.split(" ")).containsAll(qWords)) part.add(c);
        }
        return !literal.isEmpty() ? literal : !nick.isEmpty() ? nick : part;
    }

    /** Distinct display names, in order. More than one means the request is ambiguous. */
    static List<String> distinctNames(List<Contact> list) {
        Set<String> names = new LinkedHashSet<>();
        for (Contact c : list) names.add(c.name);
        return new ArrayList<>(names);
    }

    /** Picks the contact entry whose name equals `name` (first number/row wins). */
    static Contact first(List<Contact> list, String name) {
        for (Contact c : list) if (c.name.equals(name)) return c;
        return null;
    }
}
