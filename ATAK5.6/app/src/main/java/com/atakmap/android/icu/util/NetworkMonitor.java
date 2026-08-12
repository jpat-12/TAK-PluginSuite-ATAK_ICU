package com.atakmap.android.icu.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

/**
 * Watches the device's <b>default</b> network and fires a listener when it changes to a
 * different underlying network — e.g. a Wi-Fi → LTE (or LTE → Wi-Fi) handoff. The plugin
 * uses this to re-establish its stream transports on the new network instead of streaming
 * into a dead socket.
 *
 * <p>Uses {@link ConnectivityManager#registerDefaultNetworkCallback} (API 24+). The
 * callback fires once for the network that is already current at registration; that first
 * event is swallowed so only real <i>changes</i> reach the listener. Notifications are
 * delivered on the main thread.</p>
 */
public final class NetworkMonitor {

    /** Fired (on the main thread) when the default network changes to a different one. */
    public interface Listener { void onNetworkChanged(); }

    private static final String TAG = "ICU.NetMon";

    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private ConnectivityManager.NetworkCallback callback;
    private Network current;
    private volatile boolean started;

    public NetworkMonitor(Context ctx, Listener listener) {
        this.cm = (ConnectivityManager) ctx.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    /** Begin watching. Idempotent; safe to call when no ConnectivityManager is available. */
    public void start() {
        if (started || cm == null) return;
        started = true;
        try { current = cm.getActiveNetwork(); } catch (Exception ignored) { current = null; }
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network net) { switchTo(net); }
            // onLost is intentionally ignored — we act when the *replacement* default
            // arrives via onAvailable, which is when a fresh socket can actually connect.
        };
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (Exception e) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: " + e.getMessage());
            started = false;
            callback = null;
        }
    }

    private void switchTo(Network net) {
        if (!started || net == null) return;
        Network prev = current;
        current = net;
        if (prev != null && !prev.equals(net)) {
            Log.d(TAG, "default network changed " + prev + " → " + net + " — signalling reconnect");
            main.post(() -> { if (started) listener.onNetworkChanged(); });
        }
    }

    /** Stop watching. Idempotent. */
    public void stop() {
        if (!started) return;
        started = false;
        if (callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
            callback = null;
        }
        current = null;
    }
}
