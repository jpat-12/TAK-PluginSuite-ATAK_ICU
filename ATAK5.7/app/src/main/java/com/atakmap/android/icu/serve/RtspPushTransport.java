package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.serve.rtsp.RtspPusher;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Push H.264 to a server via RTSP publish (ANNOUNCE/RECORD, RTP interleaved over TCP).
 * The handshake needs SPS/PPS, so it runs once the first codec config arrives.
 *
 * <p>The publish is <b>re-established automatically</b> when it drops — a network handover,
 * or the server going away. Redials back off exponentially and only give up, reporting a
 * {@link #failureReason()}, after {@link #MAX_ATTEMPTS} consecutive failures. Until then
 * the pane shows "reconnecting" rather than either a false LIVE or a broadcast torn down
 * over a handover that was about to succeed.</p>
 *
 * <p>Auth failures are the exception: a rejected credential will be rejected identically on
 * every retry, so those fail fast instead of burning five attempts on a certainty.</p>
 */
public class RtspPushTransport implements Transport {

    private static final String TAG = "ICU.RtspPushT";

    /** Consecutive failed dials before giving up — about a minute of grace with the backoff
     *  below, which covers a handover gap. The count resets when the network moves again. */
    private static final int  MAX_ATTEMPTS   = 8;
    private static final long FIRST_RETRY_MS = 1000;
    private static final long MAX_RETRY_MS   = 15000;

    private final MediaServerConfig server;
    private volatile RtspPusher pusher;
    private volatile boolean up;
    private volatile boolean stopping;
    private volatile String state = "idle";
    private volatile String failReason;   // non-null only once we have stopped retrying

    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile Thread connectThread;
    /** Bumped by every new dial request so an in-flight backoff restarts its attempt count. */
    private volatile int generation;

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
        audioExpected = config.streamAudio;
        stopping   = false;
        failReason = null;
        state = "waiting for keyframe…";
    }

    @Override
    public void onFormat(byte[] sps, byte[] pps) {
        if (sps == null || pps == null) return;
        vsps = sps.clone();
        vpps = pps.clone();
        maybeHandshake("codec config ready");
    }

    @Override
    public void onAudioFormat(byte[] asc, int sampleRate, int channels) {
        if (asc == null || asc.length == 0) return;
        aAsc = asc.clone();
        aRate = sampleRate;
        aChannels = channels;
        audioReady = true;
        maybeHandshake("audio config ready");
    }

    /** Dial once video (and, if enabled, audio) formats are known. */
    private synchronized void maybeHandshake(String why) {
        if (stopping || up) return;
        if (vsps == null || vpps == null) return;
        if (audioExpected && !audioReady) return;   // hold for the AAC config

        generation++;
        Thread t = connectThread;
        if (t != null) t.interrupt();               // wake a sleeping backoff
        if (!connecting.compareAndSet(false, true)) return;
        Log.d(TAG, "dialing: " + why);
        connectThread = new Thread(this::connectLoop, "ICU-RtspPush");
        connectThread.start();
    }

    private void connectLoop() {
        try {
            String host = server.host.trim();
            int scheme = host.indexOf("://");
            if (scheme >= 0) host = host.substring(scheme + 3);
            int slash = host.indexOf('/'); if (slash >= 0) host = host.substring(0, slash);
            int colon = host.indexOf(':'); if (colon >= 0) host = host.substring(0, colon);

            int  gen     = -1;
            int  attempt = 0;
            long delay   = FIRST_RETRY_MS;

            while (!stopping) {
                if (gen != generation) {
                    gen     = generation;
                    attempt = 0;
                    delay   = FIRST_RETRY_MS;
                }
                attempt++;
                state = attempt == 1 ? "connecting…" : "reconnecting (attempt " + attempt + ")…";

                final boolean withAudio = audioExpected && audioReady;
                RtspPusher p = new RtspPusher(host, server.serverPort, server.streamPath,
                        server.username, server.password);
                try {
                    if (withAudio) p.setAudio(aAsc, aRate, aChannels);
                    // KLV is synthesized from sensors rather than the encoder, so there's no
                    // format to wait for — announce the track unconditionally and let
                    // KlvTelemetryEmitter feed it once broadcasting starts.
                    p.setKlv(true);
                    p.publish(vsps, vpps);
                    if (stopping || gen != generation) { p.close(); continue; }
                    pusher     = p;
                    up         = true;
                    failReason = null;
                    state      = "live";
                    Log.d(TAG, "RTSP publish established" + (withAudio ? " (with audio)" : ""));
                    return;
                } catch (Exception e) {
                    p.close();
                    up = false;
                    String msg = String.valueOf(e.getMessage());
                    // Bad credentials are deterministic — retrying just delays the truth.
                    boolean fatal = msg.toLowerCase().contains("auth");
                    if (gen != generation) continue;
                    if (fatal || attempt >= MAX_ATTEMPTS) {
                        failReason = msg;
                        state = "FAILED: " + msg;
                        Log.w(TAG, "RTSP publish failed"
                                + (fatal ? " (not retryable): " : " after " + attempt
                                + " attempts: ") + msg);
                        return;
                    }
                    Log.w(TAG, "RTSP publish attempt " + attempt + " failed (" + msg
                            + "); retrying in " + delay + " ms");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        // Superseded by a new request; loop and re-read the generation.
                    }
                    delay = Math.min(delay * 2, MAX_RETRY_MS);
                }
            }
        } finally {
            connecting.set(false);
            connectThread = null;
        }
    }

    @Override
    public void onAudioSample(byte[] aac, long ptsUs) {
        RtspPusher pr = pusher;
        if (up && pr != null) pr.sendAudioSample(aac, ptsUs);
    }

    @Override
    public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        RtspPusher pr = pusher;
        if (!up || pr == null) return;
        if (!pr.isAlive()) { noteConnectionLost(pr); return; }
        pr.sendNal(data, keyFrame, ptsUs);
    }

    @Override
    public void onKlv(byte[] klvPacket, long ptsUs) {
        RtspPusher pr = pusher;
        if (up && pr != null && pr.isAlive()) pr.sendKlv(klvPacket, ptsUs);
    }

    private void noteConnectionLost(RtspPusher dead) {
        up = false;
        Log.w(TAG, "RTSP publish lost (" + dead.failureReason() + ") — redialing");
        pusher = null;
        dead.close();
        state = "connection lost — reconnecting…";
        maybeHandshake("connection lost");
    }

    @Override
    public void reconnect(EncoderConfig config) {
        if (stopping) return;
        up = false;
        RtspPusher p = pusher;
        pusher = null;
        if (p != null) p.close();          // bound to an interface that no longer exists
        state = "network changed — reconnecting…";
        maybeHandshake("network changed");
    }

    @Override
    public void stop() {
        stopping = true;
        up = false;
        state = "idle";
        failReason = null;
        Thread t = connectThread;
        if (t != null) t.interrupt();
        RtspPusher p = pusher;
        pusher = null;
        if (p != null) p.close();
    }

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

    @Override public boolean isConnecting() { return !up && connecting.get(); }

    @Override
    public String failureReason() { return failReason; }

    @Override
    public String statusLine() {
        return "Server RTSP (" + server.host + ":" + server.serverPort
                + "/" + server.streamPath + "): " + state;
    }
}
