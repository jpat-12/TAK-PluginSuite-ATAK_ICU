package com.atakmap.android.icu.serve;

/**
 * Configuration for pushing to a generic media backend. We publish one stream to
 * {@code <protocol>://host:port/path} and make no assumptions about what the backend
 * does with it (re-serving, transcoding, ports). The push URL is the only thing we
 * know, so it's the only thing we advertise.
 */
public class MediaServerConfig {

    /** Where the broadcast goes — mirrors TAK ICU's "Destination Type". */
    public enum Destination {
        LAN,     // on-device RTSP server; peers pull from the phone (no infrastructure)
        SERVER   // push to a media server which re-serves RTSP/RTSPS/SRT/RTMP
    }

    public Destination destination = Destination.SERVER;

    /** Which protocol the phone uses to PUSH to the server. */
    public enum PushProtocol {
        RTMP(1935), RTSP(8554), SRT(8890);
        public final int defaultPort;
        PushProtocol(int p) { this.defaultPort = p; }
    }

    public PushProtocol pushProtocol = PushProtocol.RTSP;

    public String alias      = "VIDEO_1"; // TAK ICU "Broadcast Alias"
    public String host       = "";        // server address (empty = LAN only)
    public String streamPath = "icu";     // server path / stream name
    public String username   = "";        // optional server credentials
    public String password   = "";
    public int    serverPort = 8554;      // the port the user publishes to (RTSP default)

    // Captured from a scanned SRT QR's "passphrase" query param (srt://host:port?
    // streamid=...&passphrase=...). Not yet consumed by SrtTransport (⬜ — needs
    // native libsrt), but stored so a scan doesn't silently drop it before then.
    public String srtPassphrase = "";

    /**
     * Stable UUID for this device's TAK Server video feed. Persisted so each broadcast
     * updates the same Video Feed Manager row instead of creating duplicates. Generated
     * on first publish (see {@code ICUVideoDropDownReceiver}).
     */
    public String feedUuid = "";

    /** The URL the phone publishes to, per selected protocol. */
    public String pushUrl() {
        switch (pushProtocol) {
            case RTSP: return "rtsp://" + host + ":" + serverPort + "/" + streamPath;
            case SRT:  return "srt://"  + host + ":" + serverPort + "?streamid=publish:" + streamPath;
            default:   return "rtmp://" + host + ":" + serverPort + "/" + streamPath;
        }
    }

    /** The URL other users can view the stream at (same as the push URL for a generic backend). */
    public String viewUrl() {
        // For a generic backend we can't know a different viewer URL; publish==view is
        // the correct default for RTSP/RTMP. Backends that differ can be handled later.
        switch (pushProtocol) {
            case RTSP: return "rtsp://" + host + ":" + serverPort + "/" + streamPath;
            case SRT:  return "srt://"  + host + ":" + serverPort + "?streamid=read:" + streamPath;
            default:   return "rtmp://" + host + ":" + serverPort + "/" + streamPath;
        }
    }

    public String protocolName() { return pushProtocol.name(); }

    /** True only when the destination is SERVER and an address is set. */
    public boolean pushEnabled() {
        return destination == Destination.SERVER && host != null && !host.trim().isEmpty();
    }

    public boolean isConfigured() { return host != null && !host.trim().isEmpty(); }
}
