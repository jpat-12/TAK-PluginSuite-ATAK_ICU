package com.atakmap.android.icu.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

/**
 * Watches the device's default network and fires a callback when it hands off to a different
 * one — e.g. Wi-Fi → LTE when the operator walks out of range, or back again. That's the cue
 * to rebuild the broadcast's transports: a push (RTSP/RTMP/SRT) socket bound to the old
 * network is dead after a handoff, and the on-device LAN address has changed, so the feed
 * would otherwise silently stall until the operator manually restarts it.
 *
 * <p>The first network seen at registration is treated as the baseline (no callback), and
 * subsequent switches are debounced — a handoff can bounce through several transient states
 * before it settles, and we only want to reconnect once.</p>
 */
public final class NetworkMonitor {

    private static final String TAG = "ICU.NetworkMonitor";
    private static final long DEBOUNCE_MS = 1500;

    /** Fired on the main thread once a settled network change is detected. */
    public interface Listener { void onNetworkChanged(); }

    private final ConnectivityManager cm;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ConnectivityManager.NetworkCallback callback;
    private Network  baseline;
    private boolean  haveBaseline;
    private Runnable pending;

    public NetworkMonitor(Context ctx, Listener listener) {
        this.cm = (ConnectivityManager) ctx.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public void start() {
        if (cm == null || callback != null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (!haveBaseline) {                 // first default network — the baseline, not a change
                    haveBaseline = true;
                    baseline = network;
                    return;
                }
                if (network != null && !network.equals(baseline)) {
                    baseline = network;
                    Log.d(TAG, "default network changed → reconnect");
                    schedule();
                }
            }
        };
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (Throwable t) {
            Log.w(TAG, "registerDefaultNetworkCallback: " + t.getMessage());
            callback = null;
        }
    }

    public void stop() {
        if (cm != null && callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Throwable ignored) {}
        }
        callback = null;
        haveBaseline = false;
        baseline = null;
        if (pending != null) { main.removeCallbacks(pending); pending = null; }
    }

    private void schedule() {
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> { pending = null; listener.onNetworkChanged(); };
        main.postDelayed(pending, DEBOUNCE_MS);
    }
}
