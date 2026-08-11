package com.atakmap.android.icu.serve;

/**
 * A viewable URL a {@link Transport} exposes, tagged with the ATAK video protocol
 * name (matching {@code gov.tak.api.video.ConnectionEntryBase.Protocol}) so Phase 3
 * can build the right {@code ConnectionEntry} / CoT detail.
 */
public class StreamEndpoint {

    /** e.g. "RTSP", "RTSPS", "SRT", "RTMP" — must match ATAK's Protocol enum names. */
    public final String protocol;
    public final String url;

    public StreamEndpoint(String protocol, String url) {
        this.protocol = protocol;
        this.url = url;
    }

    @Override public String toString() { return url; }
}
