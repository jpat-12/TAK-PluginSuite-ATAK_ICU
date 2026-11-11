package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.serve.rtmp.RtmpPublisher;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Push H.264 to a generic media backend over RTMP. Advertises the push URL as the
 * viewable URL (no assumptions about backend re-serving).
 *
 * <p>Uses the dependency-free {@link RtmpPublisher} (no native code). Dialing happens on a
 * background thread so a slow or absent server never blocks Broadcast.</p>
 *
 * <p>The connection is <b>re-established automatically</b> when it drops, whether because
 * the device changed networks or because the server went away. A publish that fails keeps
 * retrying on an exponential backoff, and only after {@link #MAX_ATTEMPTS} consecutive
 * failures does it give up and report a {@link #failureReason()} — which is what tears the
 * broadcast down and puts the reason on screen. Reporting failure on the first dropped
 * write instead would end a broadcast that a two-second handover was about to restore.</p>
 */
public class RtmpPushTransport implements Transport {

    private static final String TAG = "ICU.RtmpPush";

    /**
     * Consecutive failed dials before the transport is declared dead and the broadcast is
     * torn down. With the backoff below this is roughly a minute of grace, which covers
     * walking out of Wi-Fi range and waiting for LTE to attach. Short enough that a genuinely
     * wrong host still surfaces promptly; the count resets whenever the network moves again.
     */
    private static final int  MAX_ATTEMPTS   = 8;
    private static final long FIRST_RETRY_MS = 1000;
    private static final long MAX_RETRY_MS   = 15000;

    private final MediaServerConfig server;

    private volatile RtmpPublisher publisher;
    private volatile boolean up;
    private volatile boolean stopping;
    private volatile String  state = "idle";
    private volatile String  failReason;   // non-null only once we have stopped retrying

    /** Guards against two connect loops running at once. */
    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile Thread connectThread;

    /**
     * Bumped whenever something asks for a fresh dial (a network change, a dropped
     * connection). The connect loop watches it so an in-flight retry backoff restarts its
     * attempt count rather than continuing to count down against the old network.
     */
    private volatile int generation;

    // Codec config, kept so a reconnect can re-arm the new publisher's sequence header
    // without waiting for the encoder to emit SPS/PPS again (it only does so once).
    private volatile byte[] sps, pps;

    private String host;

    public RtmpPushTransport(MediaServerConfig server) {
        this.server = server;
    }

    @Override public String name() { return "RTMP push"; }

    @Override
    public void start(EncoderConfig config) throws Exception {
        if (!server.isConfigured())
            throw new IllegalStateException("No media server configured");

        // Sanitize the host in case a full URL was pasted.
        String h = server.host.trim();
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        host = h;

        stopping   = false;
        failReason = null;
        beginConnect("initial publish");
    }

    @Override
    public void onFormat(byte[] sps, byte[] pps) {
        this.sps = sps;
        this.pps = pps;
        RtmpPublisher p = publisher;
        if (p != null) p.setFormat(sps, pps);
    }

    @Override
    public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        RtmpPublisher p = publisher;
        if (p != null && p.isReady()) {
            p.sendVideo(data, keyFrame, ptsUs);
            return;
        }
        // The publisher closed itself on a write failure. Nothing is reaching the server,
        // so start dialing again rather than dropping frames into a dead socket forever.
        if (up) noteConnectionLost(p);
    }

    @Override
    public void stop() {
        stopping = true;
        up = false;
        state = "idle";
        failReason = null;
        Thread t = connectThread;
        if (t != null) t.interrupt();          // wake a sleeping backoff so it exits
        RtmpPublisher p = publisher;
        publisher = null;
        if (p != null) p.close();
    }

    @Override
    public void reconnect(EncoderConfig config) {
        if (stopping) return;
        up = false;
        RtmpPublisher p = publisher;
        publisher = null;
        if (p != null) p.close();              // the old socket is bound to a dead interface
        state = "network changed — reconnecting…";
        beginConnect("network changed");
    }

    private void noteConnectionLost(RtmpPublisher dead) {
        up = false;
        String why = dead != null ? dead.failureReason() : null;
        Log.w(TAG, "RTMP connection lost"
                + (why != null ? " (" + why + ")" : "") + " — redialing");
        publisher = null;
        state = "connection lost — reconnecting…";
        beginConnect("connection lost");
    }

    /**
     * Ask for a (re)dial. If a connect loop is already running, bumping the generation is
     * enough: it will notice, abandon its current backoff, and start counting attempts
     * afresh against the new network.
     */
    private void beginConnect(String why) {
        if (stopping) return;
        generation++;
        Thread t = connectThread;
        if (t != null) t.interrupt();
        if (!connecting.compareAndSet(false, true)) return;
        Log.d(TAG, "dialing: " + why);
        connectThread = new Thread(this::connectLoop, "ICU-RtmpConnect");
        connectThread.start();
    }

    private void connectLoop() {
        try {
            int  gen     = -1;
            int  attempt = 0;
            long delay   = FIRST_RETRY_MS;

            while (!stopping) {
                if (gen != generation) {       // a fresh request superseded the old backoff
                    gen     = generation;
                    attempt = 0;
                    delay   = FIRST_RETRY_MS;
                }
                attempt++;

                final String tcUrl = "rtmp://" + host + ":" + server.serverPort
                        + "/" + server.streamPath;
                state = attempt == 1 ? "connecting…" : "reconnecting (attempt " + attempt + ")…";

                RtmpPublisher p = new RtmpPublisher(
                        host, server.serverPort, server.streamPath, server.streamPath);
                try {
                    p.connect();
                    if (stopping || gen != generation) { p.close(); continue; }
                    // Re-arm the sequence header: this is a brand new publisher, and the
                    // encoder emits SPS/PPS only once per broadcast.
                    byte[] s = sps, pp = pps;
                    if (s != null && pp != null) p.setFormat(s, pp);
                    publisher  = p;
                    up         = true;
                    failReason = null;
                    state      = "live";
                    Log.d(TAG, "RTMP publish established → " + tcUrl);
                    return;
                } catch (Exception e) {
                    p.close();
                    up = false;
                    if (gen != generation) continue;   // superseded mid-dial; retry at once
                    if (attempt >= MAX_ATTEMPTS) {
                        failReason = "RTMP publish failed: " + e.getMessage();
                        state = "FAILED: " + e.getMessage();
                        Log.w(TAG, "giving up after " + attempt + " attempts: " + e.getMessage());
                        return;
                    }
                    Log.w(TAG, "RTMP connect attempt " + attempt + " failed ("
                            + e.getMessage() + "); retrying in " + delay + " ms");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        // A new request came in — loop round and re-read the generation.
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
    public List<StreamEndpoint> endpoints() {
        List<StreamEndpoint> eps = new ArrayList<>();
        if (up) eps.add(new StreamEndpoint("RTMP", server.viewUrl()));
        return eps;
    }

    @Override public int viewerCount() { return -1; } // server-side; unknown from here

    @Override public boolean isConnecting() { return !up && connecting.get(); }

    @Override public String failureReason() { return failReason; }

    @Override
    public String statusLine() {
        return "Server RTMP (" + server.host + ":" + server.serverPort
                + "/" + server.streamPath + "): " + state;
    }
}
