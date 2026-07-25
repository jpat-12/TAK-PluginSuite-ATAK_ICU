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

    public RtspPushTransport(MediaServerConfig server) { this.server = server; }

    @Override public String name() { return "RTSP push → server"; }

    @Override
    public void start(EncoderConfig config) throws Exception {
        if (!server.isConfigured()) throw new IllegalStateException("No media server configured");
        state = "waiting for keyframe…";
    }

    @Override
    public void onFormat(byte[] sps, byte[] pps) {
        if (up || handshaking || sps == null || pps == null) return;
        handshaking = true;

        String host = server.host.trim();
        int scheme = host.indexOf("://");
        if (scheme >= 0) host = host.substring(scheme + 3);
        int slash = host.indexOf('/'); if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':'); if (colon >= 0) host = host.substring(0, colon);
        final String fHost = host;

        final byte[] s = sps.clone(), p = pps.clone();
        state = "connecting…";
        new Thread(() -> {
            try {
                pusher = new RtspPusher(fHost, server.serverPort, server.streamPath,
                        server.username, server.password);
                pusher.publish(s, p);
                up = true;
                state = "live";
                Log.d(TAG, "RTSP publish established");
            } catch (Exception e) {
                up = false;
                state = "FAILED: " + e.getMessage();
                Log.w(TAG, "RTSP publish failed: " + e.getMessage(), e);
            }
        }, "ICU-RtspPush").start();
    }

    @Override
    public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        RtspPusher pr = pusher;
        if (up && pr != null) pr.sendNal(data, keyFrame, ptsUs);
    }

    @Override
    public void stop() {
        up = false; handshaking = false; state = "idle";
        if (pusher != null) { pusher.close(); pusher = null; }
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
    public String statusLine() {
        return "Server RTSP (" + server.host + ":" + server.serverPort
                + "/" + server.streamPath + "): " + state;
    }
}
