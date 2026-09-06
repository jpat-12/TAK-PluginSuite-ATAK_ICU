package com.atakmap.android.icu.util;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny persistent diagnostic log for field runs where adb isn't attached (the usual
 * state when the phone's USB port is feeding an ethernet radio). Lines go to logcat
 * as usual AND to {@code <atak>/ICU Video/logs/icu-diag.log}, so a failed run can be
 * reproduced offline and the file read afterwards.
 *
 * <p>Capped at ~512 KB with one rotation ({@code icu-diag.log.1}), so it can be left
 * enabled permanently without eating the device. Writes are synchronized and
 * open/append/close per line — slow-path only; nothing per-frame goes through here.</p>
 */
public final class DiagLog {

    private static final long MAX_BYTES = 512 * 1024;

    private static volatile File file;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private DiagLog() {}

    /** Point the log at its directory (receiver calls this once at plugin load). */
    public static void init(File dir) {
        try {
            if (dir != null) {
                dir.mkdirs();
                file = new File(dir, "icu-diag.log");
            }
        } catch (Exception e) {
            Log.w("ICU.DiagLog", "init: " + e.getMessage());
        }
    }

    public static File file() { return file; }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        append(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        append(tag, "WARN " + msg);
    }

    private static synchronized void append(String tag, String msg) {
        File f = file;
        if (f == null) return;
        try {
            if (f.length() > MAX_BYTES) {
                File old = new File(f.getParentFile(), f.getName() + ".1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(TS.format(new Date()) + " " + tag + ": " + msg + "\n");
            }
        } catch (Exception ignored) {
            // Diagnostics must never take the pipeline down.
        }
    }
}
