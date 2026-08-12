package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.serve.rtmp.RtmpPublisher;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Push H.264 to a generic media backend over RTMP. Advertises the push URL as the
 * viewable URL (no assumptions about backend re-serving).
 *
 * <p>Uses the dependency-free {@link RtmpPublisher} (no native code). The RTMP connect
 * runs on a background thread so a slow/absent server doesn't block Broadcast.</p>
 */
public class RtmpPushTransport implements Transport {

    private static final String TAG = "ICU.RtmpPush";

    private final MediaServerConfig server;
    private RtmpPublisher publisher;
    private volatile boolean up;
    private volatile String state = "idle";
    private volatile boolean reconnecting; // true while a network-change reconnect is in flight
    private volatile boolean closed;       // set in stop() so in-flight reconnect threads bail
    private volatile int epoch;            // bumped per reconnect so stale retry threads cancel
    private volatile byte[] vsps, vpps;    // cached codec config so a reconnect can re-prime

    public RtmpPushTransport(MediaServerConfig server) {
        this.server = server;
    }

    @Override public String name() { return "RTMP push"; }

    /** Server host with any pasted scheme/port/path stripped. */
    private String sanitizedHost() {
        String host = server.host.trim();
        int scheme = host.indexOf("://"); if (scheme >= 0) host = host.substring(scheme + 3);
        int slash = host.indexOf('/');    if (slash >= 0)  host = host.substring(0, slash);
        int colon = host.indexOf(':');    if (colon >= 0)  host = host.substring(0, colon);
        return host;
    }

    @Override
    public void start(EncoderConfig config) throws Exception {
        if (!server.isConfigured())
            throw new IllegalStateException("No media server configured");
        closed = false;

        final String host = sanitizedHost();
        final String tcUrl = "rtmp://" + host + ":" + server.serverPort + "/" + server.streamPath;
        state = "connecting…";
        Log.d(TAG, "RTMP connecting → " + tcUrl);

        // app = stream path (server maps the RTMP app to its path); publish name matches.
        publisher = new RtmpPublisher(host, server.serverPort, server.streamPath, server.streamPath);

        new Thread(() -> {
            try {
                publisher.connect();
                up = true;
                state = "live";
                Log.d(TAG, "RTMP publish established → " + tcUrl);
            } catch (Exception e) {
                up = false;
                state = "FAILED: " + e.getMessage();
                Log.w(TAG, "RTMP connect failed: " + e.getMessage(), e);
            }
        }, "ICU-RtmpConnect").start();
    }

    @Override
    public void onFormat(byte[] sps, byte[] pps) {
        if (sps != null) vsps = sps.clone();
        if (pps != null) vpps = pps.clone();
        RtmpPublisher p = publisher;
        if (p != null) p.setFormat(sps, pps);
    }

    @Override
    public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        RtmpPublisher p = publisher;
        if (p != null && p.isReady()) p.sendVideo(data, keyFrame, ptsUs);
    }

    @Override
    public void stop() {
        closed = true; epoch++;
        up = false; reconnecting = false;
        state = "idle";
        if (publisher != null) { publisher.close(); publisher = null; }
    }

    /**
     * Re-establish the RTMP publish on the current default network after a handoff, with
     * bounded backoff. The new connection binds to the active network automatically; codec
     * config is re-fed from the cached SPS/PPS and re-sent to viewers on the next key frame.
     */
    @Override
    public void reconnect() {
        if (closed) return;
        final int myEpoch = ++epoch;
        reconnecting = true;
        up = false;
        state = "reconnecting…";

        RtmpPublisher old = publisher;
        publisher = null;
        if (old != null) { try { old.close(); } catch (Exception ignored) {} }

        final String host = sanitizedHost();
        final byte[] s = vsps, p = vpps;

        new Thread(() -> {
            long delayMs = 1000;
            for (int attempt = 1; attempt <= 6 && !closed && myEpoch == epoch; attempt++) {
                try {
                    RtmpPublisher np = new RtmpPublisher(host, server.serverPort,
                            server.streamPath, server.streamPath);
                    np.connect();
                    if (s != null && p != null) np.setFormat(s, p);
                    if (closed || myEpoch != epoch) { np.close(); return; }
                    publisher = np; up = true; reconnecting = false; state = "live";
                    Log.d(TAG, "RTMP reconnected on attempt " + attempt);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "RTMP reconnect attempt " + attempt + " failed: " + e.getMessage());
                    state = "reconnecting… (" + e.getMessage() + ")";
                    try { Thread.sleep(delayMs); }
                    catch (InterruptedException ie) { return; }
                    delayMs = Math.min(delayMs * 2, 8000);
                }
            }
            if (!up && myEpoch == epoch && !closed) { reconnecting = false; state = "FAILED: reconnect gave up"; }
        }, "ICU-RtmpReconnect").start();
    }

    @Override public boolean reconnecting() { return reconnecting; }

    @Override
    public List<StreamEndpoint> endpoints() {
        List<StreamEndpoint> eps = new ArrayList<>();
        if (up) eps.add(new StreamEndpoint("RTMP", server.viewUrl()));
        return eps;
    }

    @Override public int viewerCount() { return -1; } // server-side; unknown from here

    @Override
    public String statusLine() {
        return "Server RTMP (" + server.host + ":" + server.serverPort
                + "/" + server.streamPath + "): " + state;
    }
}
