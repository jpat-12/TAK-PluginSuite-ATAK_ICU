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

    public RtmpPushTransport(MediaServerConfig server) {
        this.server = server;
    }

    @Override public String name() { return "RTMP push"; }

    @Override
    public void start(EncoderConfig config) throws Exception {
        if (!server.isConfigured())
            throw new IllegalStateException("No media server configured");

        // Sanitize the host in case a full URL was pasted.
        String host = server.host.trim();
        int scheme = host.indexOf("://");
        if (scheme >= 0) host = host.substring(scheme + 3);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);

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
        up = false;
        state = "idle";
        if (publisher != null) { publisher.close(); publisher = null; }
    }

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
