package com.atakmap.android.icu.util;

import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Type-tolerant reads over a {@link SharedPreferences}.
 *
 * <p>ATAK's {@code .pref} importer stores each entry as whatever {@code class} the file
 * declares. A hand-written file that says {@code class java.lang.String} for a number
 * would make {@code getInt()} throw {@code ClassCastException} and take the plugin
 * down on load. These getters accept any stored type that converts cleanly and fall
 * back to the default otherwise, so a bad entry costs one setting, never the plugin.
 * The next {@link Prefs#save} rewrites the value with the correct type.</p>
 */
final class PrefReader {

    private final SharedPreferences sp;

    PrefReader(SharedPreferences sp) { this.sp = sp; }

    boolean has(String key) { return sp.contains(key); }

    String getString(String key, String def) {
        Object v = raw(key);
        return v == null ? def : v.toString();
    }

    int getInt(String key, int def) {
        Object v = raw(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return (int) Double.parseDouble(((String) v).trim()); }
            catch (NumberFormatException ignored) { return def; }
        }
        return def;
    }

    boolean getBoolean(String key, boolean def) {
        Object v = raw(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.US);
            if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")) return false;
        }
        return def;
    }

    /** Enum by name, case-insensitive; {@code def} when absent or unknown. */
    <E extends Enum<E>> E getEnum(String key, Class<E> type, E def) {
        Object v = raw(key);
        if (v == null) return def;
        try { return Enum.valueOf(type, v.toString().trim().toUpperCase(Locale.US)); }
        catch (IllegalArgumentException e) { return def; }
    }

    private Object raw(String key) {
        try {
            return sp.getAll().get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
