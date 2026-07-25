package com.atakmap.android.icu.share;

import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.icu.serve.StreamEndpoint;
import com.atakmap.coremap.log.Log;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * PHASE 3 — turns the operator's self marker into a live "sensor + video" while
 * broadcasting, then reverts it on stop.
 *
 * <p>Owns a {@link SelfBroadcastDetailHandler} registered with ATAK's
 * {@link CotDetailManager}. {@link #start} points it at the reachable stream URL and
 * flips it on; {@link #stop} flips it off (the self marker returns to the user's
 * normal preferences). The change propagates on the next self PLI (a few seconds).</p>
 */
public class SelfMarkerSensorController {

    private static final String TAG = "ICU.SelfSensor";

    private final SelfBroadcastDetailHandler handler = new SelfBroadcastDetailHandler();
    private boolean registered;
    private String videoUid;
    private String alias = "ICU VideoStreamer";

    public void register() {
        if (registered) return;
        CotDetailManager mgr = CotDetailManager.getInstance();
        if (mgr != null) { mgr.registerHandler(handler); registered = true; }
    }

    public void dispose() {
        stop();
        if (registered) {
            CotDetailManager mgr = CotDetailManager.getInstance();
            if (mgr != null) mgr.unregisterHandler(handler);
            registered = false;
        }
    }

    /**
     * Begin decorating the self PLI with the given stream. Prefers a reachable RTSP
     * endpoint (what ATAK viewers open natively).
     */
    public void start(List<StreamEndpoint> endpoints) {
        start(endpoints, null);
    }

    /**
     * Begin decorating the self PLI with the given stream, advertising it under
     * {@code alias} (the video ConnectionEntry alias peers see). A null/blank alias
     * keeps the default.
     */
    public void start(List<StreamEndpoint> endpoints, String alias) {
        StreamEndpoint ep = pickPrimary(endpoints);
        if (ep == null) {
            Log.w(TAG, "no endpoint to advertise; sensor not started");
            return;
        }
        if (alias != null && !alias.trim().isEmpty()) this.alias = alias.trim();
        videoUid = "ICU-" + UUID.randomUUID();
        applyEndpoint(ep);
        handler.setBroadcasting(true);
        Log.d(TAG, "self marker → sensor+video: " + ep.url);
    }

    public void stop() {
        handler.setBroadcasting(false);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** RTSP first (ATAK-native), else whatever is available. */
    private StreamEndpoint pickPrimary(List<StreamEndpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) return null;
        for (StreamEndpoint e : endpoints)
            if ("RTSP".equalsIgnoreCase(e.protocol)) return e;
        return endpoints.get(0);
    }

    private void applyEndpoint(StreamEndpoint ep) {
        String address = "";
        int port = 8554;
        String path = "";
        String proto = ep.protocol == null ? "rtsp" : ep.protocol.toLowerCase();
        try {
            URI u = URI.create(ep.url);
            if (u.getHost() != null) address = u.getHost();
            if (u.getPort() > 0)     port = u.getPort();
            if (u.getPath() != null) path = u.getPath();
            if (u.getScheme() != null) proto = u.getScheme().toLowerCase();
        } catch (Exception e) {
            Log.w(TAG, "url parse: " + e.getMessage());
        }
        handler.setVideo(ep.url, videoUid, alias, address, port, path, proto);
    }
}
