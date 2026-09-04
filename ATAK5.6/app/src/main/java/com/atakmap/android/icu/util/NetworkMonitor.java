package com.atakmap.android.icu.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

/**
 * Watches the device's default network and reports when it actually moves — Wi-Fi to LTE,
 * LTE to Wi-Fi, or a Wi-Fi hop between APs.
 *
 * <p>A broadcast's outbound socket does not survive that. The old socket is bound to an
 * address that no longer exists, so every subsequent write fails; nothing recovers on its
 * own because a TCP connection has no idea its interface went away. This is the signal the
 * transports need in order to tear the dead connection down and dial again.</p>
 *
 * <p>Transitions are <b>debounced</b>. Handing off between networks is not one clean event:
 * Android routinely reports the new network available, the old one lost, and capabilities
 * changing on both, several times over a second or two. Reconnecting on the first of those
 * would dial out over a network that is about to be replaced. Waiting for the churn to
 * settle costs a second and saves a pointless connection attempt.</p>
 */
public final class NetworkMonitor {

    private static final String TAG = "ICU.NetworkMonitor";

    /** How long the network picture must hold still before we call it a real change. */
    private static final long SETTLE_MS = 1500;

    public interface Listener {
        /**
         * The default network is now a different one from the last we reported. Called on
         * the main thread, after the transition has settled.
         */
        void onNetworkChanged();
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;

    /** Identity of the network we last reported, so repeat callbacks for it are ignored. */
    private String current;
    private Runnable pending;

    public NetworkMonitor(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    /** Begin watching. Safe to call twice; the second call is a no-op. */
    public void start() {
        if (callback != null) return;
        try {
            cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                Log.w(TAG, "no ConnectivityManager — network changes will go unnoticed");
                return;
            }
            current = networkKey();
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { settle(); }
                @Override public void onLost(Network network)      { settle(); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                    // Fires when a network gains or loses validated internet access, which is
                    // the moment a freshly associated Wi-Fi link actually becomes usable.
                    settle();
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Reports the network the system would actually route through — exactly the
                // question we care about.
                cm.registerDefaultNetworkCallback(callback);
            } else {
                // Pre-N has no default-network callback, so watch every internet-capable
                // network and let activeNetwork() below decide which one is in play.
                cm.registerNetworkCallback(new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(), callback);
            }
            Log.d(TAG, "watching the default network");
        } catch (Throwable t) {
            Log.w(TAG, "could not watch the network: " + t.getMessage());
            callback = null;
        }
    }

    public void stop() {
        if (pending != null) { ui.removeCallbacks(pending); pending = null; }
        if (cm != null && callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Throwable ignored) {}
        }
        callback = null;
        current = null;
    }

    /** Restart the settle timer; whatever it lands on is the network we report. */
    private void settle() {
        ui.post(() -> {
            if (pending != null) ui.removeCallbacks(pending);
            pending = this::fire;
            ui.postDelayed(pending, SETTLE_MS);
        });
    }

    private void fire() {
        pending = null;
        String now = networkKey();
        if (now == null) {
            // Nothing to reconnect over yet. Stay quiet and wait for the next callback —
            // reporting a change here would only make the transports dial into a void.
            Log.d(TAG, "no active network yet; holding");
            return;
        }
        if (now.equals(current)) return;   // same network, just churn
        Log.d(TAG, "default network changed: " + current + " -> " + now);
        current = now;
        try {
            listener.onNetworkChanged();
        } catch (Throwable t) {
            Log.w(TAG, "listener failed: " + t.getMessage());
        }
    }

    /**
     * A stable identity for whichever network the system is currently routing through, or
     * null when there is none. {@code getActiveNetwork} only exists from M onward, so older
     * devices fall back to the legacy NetworkInfo, whose transport type plus subtype changes
     * across a Wi-Fi/cellular handover — which is the transition we are looking for.
     */
    @SuppressWarnings("deprecation")
    private String networkKey() {
        if (cm == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network n = cm.getActiveNetwork();
                return n == null ? null : n.toString();
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return null;
            return info.getType() + "/" + info.getSubtype() + "/" + info.getExtraInfo();
        } catch (Throwable t) {
            return null;
        }
    }
}
