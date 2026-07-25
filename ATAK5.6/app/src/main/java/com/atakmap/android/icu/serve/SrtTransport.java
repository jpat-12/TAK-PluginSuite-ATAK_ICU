package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;

import java.util.Collections;
import java.util.List;

/**
 * PHASE 2b — SRT transport (encrypted, low-latency).
 *
 * <p>Unlike RTSP, SRT has no pure-Java implementation — it requires the native
 * {@code libsrt}. Two modes: <b>listener</b> (phone accepts SRT viewers directly —
 * fits LAN/mesh peer-pull, with built-in AES encryption) or <b>caller</b> (phone
 * pushes to a generic SRT ingest).</p>
 *
 * <p>Implementation plan: add {@code libsrt} + a small JNI shim to {@code lib/}, mux
 * the H.264 NAL stream into MPEG-TS, and hand TS packets to SRT. This is the only
 * transport that adds a native dependency.</p>
 */
public class SrtTransport implements Transport {

    private final MediaServerConfig server; // used in caller mode; null = listener mode
    private final int listenPort;

    public SrtTransport(MediaServerConfig server, int listenPort) {
        this.server = server;
        this.listenPort = listenPort;
    }

    @Override public String name() { return "SRT (native libsrt)"; }

    @Override
    public void start(EncoderConfig config) throws Exception {
        // TODO(Phase 2b): load native libsrt, create MPEG-TS muxer, open SRT socket.
        throw new UnsupportedOperationException("SrtTransport.start — Phase 2b (needs native libsrt)");
    }

    @Override public void onFormat(byte[] sps, byte[] pps) { /* TODO(Phase 2b): TS PMT/PES */ }
    @Override public void onNal(byte[] data, boolean keyFrame, long ptsUs) { /* TODO(Phase 2b): TS mux + srt_send */ }
    @Override public void stop() { /* TODO(Phase 2b): srt_close */ }

    @Override
    public List<StreamEndpoint> endpoints() {
        if (server != null && server.isConfigured()) {
            return Collections.singletonList(new StreamEndpoint("SRT", server.viewUrl()));
        }
        // Listener mode: viewers connect to the phone directly.
        String ip = com.atakmap.android.icu.util.NetworkUtils.reachableIpv4();
        if (ip == null) return Collections.emptyList();
        return Collections.singletonList(
                new StreamEndpoint("SRT", "srt://" + ip + ":" + listenPort));
    }

    @Override public int viewerCount() { return -1; }

    @Override public String statusLine() { return null; } // Phase 2c (native)
}
