package com.atakmap.android.icu.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.ipc.AtakBroadcast;

/**
 * Calls back on the main thread each time ATAK finishes importing a {@code .pref}
 * file (Import Manager, data package, MDM, loadout, Settings → Manage preferences).
 *
 * <p>ATAK's {@code PreferenceControl.loadSettings} sends
 * {@value #PREFERENCES_LOADED} after it has written every block in the file. The
 * broadcast does not say which preference files changed, so the callback must check
 * for itself whether ICU's settings moved (see {@link Prefs#snapshot}).</p>
 */
public final class PrefImportListener {

    /** Sent by ATAK's PreferenceControl after a {@code .pref} import completes. */
    public static final String PREFERENCES_LOADED = "com.atakmap.app.PREFERENCES_LOADED";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onImported;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PREFERENCES_LOADED.equals(intent.getAction())) return;
            // The import may run off the UI thread; settings/UI work belongs on main.
            main.post(onImported);
        }
    };
    private boolean registered;

    public PrefImportListener(Runnable onImported) {
        this.onImported = onImported;
    }

    public void register() {
        if (registered) return;
        AtakBroadcast.DocumentedIntentFilter filter = new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(PREFERENCES_LOADED,
                "Reload ICU settings after an ATAK .pref import");
        AtakBroadcast.getInstance().registerReceiver(receiver, filter);
        registered = true;
    }

    public void unregister() {
        if (!registered) return;
        AtakBroadcast.getInstance().unregisterReceiver(receiver);
        main.removeCallbacks(onImported);
        registered = false;
    }
}
