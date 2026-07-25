package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;

import java.util.List;

/**
 * A way of getting the encoded H.264 stream to viewers.
 *
 * <p>Implementations fall into two families:</p>
 * <ul>
 *   <li><b>On-device servers</b> — the phone listens and peers pull directly
 *       (e.g. {@link OnDeviceRtspTransport}). Zero infrastructure; fits LAN/mesh.</li>
 *   <li><b>Push transports</b> — the phone pushes once to a media server
 *       (a generic media server), which re-serves the stream to viewers. This is what
 *       unlocks <i>all four</i> protocols and true RTSPS. (Phase 2b.)</li>
 * </ul>
 *
 * <p>The {@code onFormat}/{@code onNal} hooks are fed by
 * {@link com.atakmap.android.icu.capture.CapturePipeline} via {@link TransportManager}.</p>
 */
public interface Transport {

    /** Human-readable name for status/logging (e.g. "On-device RTSP"). */
    String name();

    /** Bring the transport up. Throwing aborts just this transport, not the others. */
    void start(EncoderConfig config) throws Exception;

    /** Latest SPS/PPS (codec config). Called before NAL units and on format changes. */
    void onFormat(byte[] sps, byte[] pps);

    /** One encoded NAL unit (Annex-B, with start code). */
    void onNal(byte[] data, boolean keyFrame, long ptsUs);

    void stop();

    /** URL(s) viewers can use for this transport. May be empty until started. */
    List<StreamEndpoint> endpoints();

    /** Best-effort count of connected viewers, or -1 if unknown. */
    int viewerCount();

    /** One-line human-readable status for the pane (e.g. "Server RTMP: live"), or null. */
    String statusLine();

    /**
     * True when this transport is up and actually authenticated to the server (the server
     * required credentials and we supplied them). Default false — on-device servers and
     * anonymous pushes aren't authenticated. Drives the "Using Auth" badge in the pane.
     */
    default boolean usingAuth() { return false; }

    /**
     * Non-null one-line reason when this transport has failed terminally — e.g. the RTSP
     * publish was rejected for bad credentials — and is therefore NOT delivering to the
     * server, even though the encoder keeps producing frames. Null while healthy or still
     * connecting. Lets the pane show the truth instead of a false "LIVE".
     */
    default String failureReason() { return null; }
}
