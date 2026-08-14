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

    // ── LAN multicast (MPEG-TS over UDP) ──────────────────────────────────────
    // When the destination is LAN, the phone also (optionally) muxes H.264+AAC into an
    // MPEG-TS stream and sends it to a UDP multicast group. This is the one-to-many LAN
    // feed a receiver plays with a single URL (udp://@group:port) — ATAK's own video
    // player, VLC and ffmpeg all read it, and unlike the on-device RTSP server it carries
    // audio for free. Runs alongside the RTSP server so nothing existing breaks.

    /** Enable the LAN multicast MPEG-TS feed (only meaningful when destination == LAN). */
    public boolean multicastEnabled = true;

    /** Multicast group address (must be in 224.0.0.0–239.255.255.255). */
    public String multicastGroup = "239.1.1.1";

    /** Multicast UDP port. */
    public int multicastPort = 5600;

    /** The URL a receiver opens to play the LAN multicast feed, e.g. {@code udp://@239.1.1.1:5600}. */
    public String multicastUrl() {
        return "udp://@" + multicastGroup + ":" + multicastPort;
    }

    /** True when the LAN multicast feed should run (LAN destination + enabled + a group set). */
    public boolean multicastActive() {
        return destination == Destination.LAN && multicastEnabled
                && multicastGroup != null && !multicastGroup.trim().isEmpty();
    }

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

    /**
     * The URL published to the TAK Server Video Feed Manager for <b>viewers</b>. Unlike
     * {@link #viewUrl()}, this embeds credentials as RFC 3986 userinfo and (for RTSP) the
     * ATAK {@code ?tcp} transport hint — matching the TAK ConnectionEntry format ATAK's
     * video player expects, e.g. {@code rtsp://user:pass@host:8554/path?tcp}. A viewer
     * needs the credentials inline because MediaMTX also requires auth to read, and
     * {@code ?tcp} forces reliable RTSP-interleaved delivery instead of UDP.
     */
    public String feedViewUrl() {
        String creds = userInfo();
        switch (pushProtocol) {
            case RTSP: return "rtsp://" + creds + host + ":" + serverPort + "/" + streamPath + "?tcp";
            case SRT:  return "srt://"  + host + ":" + serverPort + "?streamid=read:" + streamPath;
            default:   return "rtmp://" + creds + host + ":" + serverPort + "/" + streamPath;
        }
    }

    /** Credentials as URL userinfo ({@code user:pass@}) or {@code ""} when no username is
     *  set. Shown verbatim (no percent-encoding) to match the TAK ConnectionEntry format. */
    public String userInfo() {
        if (username == null || username.trim().isEmpty()) return "";
        return username.trim() + ":" + (password == null ? "" : password) + "@";
    }

    public String protocolName() { return pushProtocol.name(); }

    /** True only when the destination is SERVER and an address is set. */
    public boolean pushEnabled() {
        return destination == Destination.SERVER && host != null && !host.trim().isEmpty();
    }

    public boolean isConfigured() { return host != null && !host.trim().isEmpty(); }
}
