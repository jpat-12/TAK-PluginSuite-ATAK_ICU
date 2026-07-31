package com.atakmap.android.icu.serve.p2p;

import android.content.Context;

import com.atakmap.coremap.log.Log;

import org.webrtc.PeerConnectionFactory;

/**
 * Phase 0 feasibility spike for the reliable-P2P (WebRTC) transport.
 *
 * <p>The whole feature hinges on one unknown: this plugin ships <b>no native code</b>
 * today, and ATAK loads plugins through its own classloader. libwebrtc's
 * {@code PeerConnectionFactory.initialize} calls {@code System.loadLibrary} for the
 * native {@code libjingle_peerconnection_so}. This probe runs that path once on plugin
 * load and logs whether the native lib loads inside ATAK's process — a green light for
 * the rest of the design, or a signal to pivot (out-of-process service, or a
 * hole-punch+TURN approach that doesn't need libwebrtc).</p>
 *
 * <p>Temporary: remove once the transport is real.</p>
 */
public final class P2pProbe {

    private static final String TAG = "ICU.P2pProbe";

    private P2pProbe() {}

    public static void run(final Context ctx) {
        new Thread(() -> {
            try {
                PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions
                                .builder(ctx.getApplicationContext())
                                .createInitializationOptions());
                PeerConnectionFactory factory =
                        PeerConnectionFactory.builder().createPeerConnectionFactory();
                Log.i(TAG, "WebRTC native load OK — PeerConnectionFactory created: "
                        + (factory != null));
                if (factory != null) factory.dispose();
            } catch (Throwable t) {
                // UnsatisfiedLinkError here = the native .so did not load in-plugin.
                Log.e(TAG, "WebRTC native load FAILED (" + t.getClass().getSimpleName()
                        + "): " + t.getMessage(), t);
            }
        }, "ICU-P2pProbe").start();
    }
}
