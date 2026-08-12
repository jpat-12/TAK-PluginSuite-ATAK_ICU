package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.serve.rtsp.RtspPusher;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Push H.264 to a server via RTSP publish (ANNOUNCE/RECORD, RTP interleaved over TCP).
 * The handshake needs SPS/PPS, so it runs once the first codec config arrives.
 */
public class RtspPushTransport implements Transport {

    private static final String TAG = "ICU.RtspPushT";

    private final MediaServerConfig server;
    private RtspPusher pusher;
    private volatile boolean handshaking, up;
    private volatile String state = "idle";
    private volatile String failReason;   // non-null once the publish handshake fails TERMINALLY
    private volatile boolean reconnecting; // true while a network-change re-handshake is in flight
    private volatile boolean closed;      // set in stop() so in-flight reconnect threads bail
    private volatile int epoch;           // bumped per reconnect so stale retry threads cancel

    // Both tracks must be known before the SDP is built. Video (SPS/PPS) always; audio
    // (AAC config) only when the operator enabled it — we wait for it before announcing.
    private volatile boolean audioExpected, audioReady;
    private byte[] vsps, vpps, aAsc;
    private int aRate, aChannels;

    public RtspPushTransport(MediaServerConfig server) { this.server = server; }

    @Override public String name() { return "RTSP push → server"; }

    @Override
    public void start(EncoderConfig config) throws Exception {
        if (!server.isConfigured()) throw new IllegalStateException("No media server configured");
        closed = false;
        audioExpected = config.streamAudio;
        state = "waiting for keyframe…";
    }

    /** Server host with any pasted scheme/port/path stripped. */
    private String sanitizedHost() {
        String host = server.host.trim();
        int scheme = host.indexOf("://"); if (scheme >= 0) host = host.substring(scheme + 3);
        int slash = host.indexOf('/');    if (slash >= 0)  host = host.substring(0, slash);
        int colon = host.indexOf(':');    if (colon >= 0)  host = host.substring(0, colon);
        return host;
    }

    @Override
    public void onFormat(byte[] sps, byte[] pps) {
        if (sps == null || pps == null) return;
        vsps = sps.clone();
        vpps = pps.clone();
        maybeHandshake();
    }

    @Override
    public void onAudioFormat(byte[] asc, int sampleRate, int channels) {
        if (asc == null || asc.length == 0) return;
        aAsc = asc.clone();
        aRate = sampleRate;
        aChannels = channels;
        audioReady = true;
        maybeHandshake();
    }

    /** Start the publish once video (and, if enabled, audio) formats are known. Runs once. */
    private synchronized void maybeHandshake() {
        if (up || handshaking || reconnecting || closed) return;
        if (vsps == null || vpps == null) return;
        if (audioExpected && !audioReady) return;   // hold for the AAC config
        handshaking = true;

        final String fHost = sanitizedHost();

        final byte[] s = vsps, p = vpps;
        final boolean withAudio = audioExpected && audioReady;
        final byte[] asc = aAsc; final int rate = aRate, ch = aChannels;
        state = "connecting…";
        failReason = null;
        new Thread(() -> {
            try {
                pusher = new RtspPusher(fHost, server.serverPort, server.streamPath,
                        server.username, server.password);
                if (withAudio) pusher.setAudio(asc, rate, ch);
                pusher.publish(s, p);
                up = true;
                state = "live";
                Log.d(TAG, "RTSP publish established" + (withAudio ? " (with audio)" : ""));
            } catch (Exception e) {
                up = false;
                failReason = e.getMessage();
                state = "FAILED: " + e.getMessage();
                Log.w(TAG, "RTSP publish failed: " + e.getMessage(), e);
            }
        }, "ICU-RtspPush").start();
    }

    @Override
    public void onAudioSample(byte[] aac, long ptsUs) {
        RtspPusher pr = pusher;
        if (up && pr != null) pr.sendAudioSample(aac, ptsUs);
    }

    @Override
    public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        RtspPusher pr = pusher;
        if (up && pr != null) {
            pr.sendNal(data, keyFrame, ptsUs);
            // Self-heal: a dead socket (server close or a network change we weren't told
            // about) surfaces as a send failure — re-handshake on the current network.
            if (pr.isBroken() && !reconnecting && !closed) reconnect();
        }
    }

    @Override
    public void stop() {
        closed = true; epoch++;
        up = false; handshaking = false; reconnecting = false; state = "idle"; failReason = null;
        if (pusher != null) { pusher.close(); pusher = null; }
    }

    /**
     * Tear down the dead socket and re-run the publish handshake on the now-current default
     * network, with bounded exponential backoff. A fresh {@link java.net.Socket} binds to the
     * active network automatically, so this is what carries the stream across a Wi-Fi↔LTE
     * handoff. Only sets a terminal {@link #failReason} once every retry is exhausted.
     */
    @Override
    public void reconnect() {
        if (closed) return;
        if (vsps == null || vpps == null) return;   // never handshook; nothing to redo
        final int myEpoch = ++epoch;
        reconnecting = true;
        up = false; handshaking = false;
        state = "reconnecting…";

        RtspPusher old = pusher;
        pusher = null;
        if (old != null) { try { old.close(); } catch (Exception ignored) {} }

        final String fHost = sanitizedHost();
        final byte[] s = vsps, p = vpps;
        final boolean withAudio = audioExpected && audioReady;
        final byte[] asc = aAsc; final int rate = aRate, ch = aChannels;

        new Thread(() -> {
            long delayMs = 1000;
            for (int attempt = 1; attempt <= 6 && !closed && myEpoch == epoch; attempt++) {
                try {
                    RtspPusher np = new RtspPusher(fHost, server.serverPort, server.streamPath,
                            server.username, server.password);
                    if (withAudio) np.setAudio(asc, rate, ch);
                    np.publish(s, p);
                    if (closed || myEpoch != epoch) { np.close(); return; }  // superseded/stopped
                    pusher = np; up = true; reconnecting = false; failReason = null; state = "live";
                    Log.d(TAG, "RTSP reconnected on attempt " + attempt);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "RTSP reconnect attempt " + attempt + " failed: " + e.getMessage());
                    state = "reconnecting… (" + e.getMessage() + ")";
                    try { Thread.sleep(delayMs); }
                    catch (InterruptedException ie) { return; }
                    delayMs = Math.min(delayMs * 2, 8000);
                }
            }
            if (!up && myEpoch == epoch && !closed) {
                reconnecting = false;
                failReason = "reconnect failed after network change";
                state = "FAILED: " + failReason;
            }
        }, "ICU-RtspReconnect").start();
    }

    @Override public boolean reconnecting() { return reconnecting; }

    @Override
    public List<StreamEndpoint> endpoints() {
        List<StreamEndpoint> eps = new ArrayList<>();
        if (up) eps.add(new StreamEndpoint("RTSP",
                "rtsp://" + server.host + ":" + server.serverPort + "/" + server.streamPath));
        return eps;
    }

    @Override public int viewerCount() { return -1; }

    @Override
    public boolean usingAuth() {
        RtspPusher p = pusher;
        return up && p != null && p.usedAuth();
    }

    @Override
    public String failureReason() { return failReason; }

    @Override
    public String statusLine() {
        return "Server RTSP (" + server.host + ":" + server.serverPort
                + "/" + server.streamPath + "): " + state;
    }
}
