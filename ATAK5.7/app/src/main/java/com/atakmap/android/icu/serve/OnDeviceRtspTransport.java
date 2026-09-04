package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.util.NetworkUtils;

import java.util.Collections;
import java.util.List;

/**
 * PHASE 2 — On-device RTSP server transport (pure Java, zero infrastructure).
 *
 * <p>The phone listens on {@code :8554}; peers pull {@code rtsp://<phone-ip>:8554/live}
 * directly. This is the simplest transport and the natural fit for the LAN/mesh
 * peer-pull model. It only produces plain RTSP — RTSPS/SRT/RTMP come from the
 * push transports (media server).</p>
 */
public class OnDeviceRtspTransport implements Transport {

    private final RtspServer server = new RtspServer();
    private volatile boolean started;

    @Override public String name() { return "On-device RTSP"; }

    @Override
    public void start(EncoderConfig config) throws Exception {
        server.start();
        started = true;
    }

    @Override
    public void onFormat(byte[] sps, byte[] pps) {
        if (sps != null) server.setSps(sps);
        if (pps != null) server.setPps(pps);
    }

    @Override
    public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        server.sendNalUnit(data, keyFrame, ptsUs);
    }

    @Override
    public void onKlv(byte[] klvPacket, long ptsUs) {
        server.sendKlvUnit(klvPacket, ptsUs);
    }

    @Override
    public void stop() {
        started = false;
        server.stop();
    }

    /**
     * Nothing to redial: the listening socket is bound to the wildcard address, so it keeps
     * accepting on whatever interface the device now has. What does change is the address
     * peers must dial — {@link #endpoints()} re-derives it on every call, and the pane
     * re-advertises it on the self marker after a network change.
     *
     * <p>Viewers connected over the old interface do have to reconnect. That half of the
     * connection is theirs, and nothing on this side can repair it.</p>
     */
    @Override
    public void reconnect(EncoderConfig config) { }

    @Override
    public List<StreamEndpoint> endpoints() {
        if (!started) return Collections.emptyList();
        String url = NetworkUtils.rtspUrl(RtspServer.PORT, RtspServer.STREAM_PATH);
        return Collections.singletonList(new StreamEndpoint("RTSP", url));
    }

    @Override
    public int viewerCount() { return server.getClientCount(); }

    @Override
    public String statusLine() {
        if (!started) return null;
        return "LAN RTSP: serving · " + server.getClientCount() + " viewer(s)";
    }
}
