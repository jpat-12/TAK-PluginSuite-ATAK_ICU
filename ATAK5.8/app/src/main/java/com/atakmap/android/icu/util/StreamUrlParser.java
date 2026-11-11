package com.atakmap.android.icu.util;

import com.atakmap.android.icu.serve.MediaServerConfig;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;

/**
 * Parses the plain-text stream URL a QR code encodes back into its parts, so the
 * settings dialog can fill itself in from a scan instead of manual entry. Mirrors
 * {@link MediaServerConfig#pushUrl()} / {@code viewUrl()} in reverse:
 *
 * <pre>
 *   rtsp://[{user}:{pass}@]{address}:{port}[/{path}][?name=...]
 *   rtmp://{address}:{port}[/{path}][?user=...&amp;pass=...][?name=...]
 *   srt://{address}:{port}?streamid={read|publish}:{path}[:{user}:{pass}][&amp;passphrase=...][&amp;name=...]
 * </pre>
 *
 * The QR itself is just that raw string — no JSON/XML/custom scheme — so this is a
 * plain URI parse, not a custom format decode.
 *
 * <p><b>Every field except host is optional, and absent means "keep what the plugin
 * already has".</b> This is what lets one QR provision a fleet: a shared code carries
 * only the server (address/port/protocol, and credentials if the server needs them),
 * while each phone keeps its own stream path and broadcast alias — which must stay
 * unique per device or the streams collide on the server. A field that is absent from
 * the payload is reported as {@code null} here, never as an empty string, so callers
 * can tell "not in the QR" apart from "explicitly blank".</p>
 *
 * <p>Credentials sit in a different place per protocol because that is where MediaMTX
 * expects them — userinfo for RTSP, query parameters for RTMP, extra colon-separated
 * stream-id fields for SRT. They are URL-decoded here.</p>
 */
public final class StreamUrlParser {

    private StreamUrlParser() {}

    public static class Parsed {
        public final MediaServerConfig.PushProtocol protocol;
        public final String host;
        public final int port;
        // null (not "") when the QR omitted it — the caller keeps its existing value.
        public final String path;
        public final String passphrase; // SRT only; null if absent
        public final String name;       // any protocol; null if absent
        public final String username;   // null if absent
        public final String password;   // null if absent

        Parsed(MediaServerConfig.PushProtocol protocol, String host, int port,
                String path, String passphrase, String name,
                String username, String password) {
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.path = path;
            this.passphrase = passphrase;
            this.name = name;
            this.username = username;
            this.password = password;
        }
    }

    /** @throws IllegalArgumentException if {@code raw} isn't one of the three supported shapes. */
    public static Parsed parse(String raw) {
        if (raw == null || raw.trim().isEmpty())
            throw new IllegalArgumentException("Empty QR payload");

        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a URI: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null)
            throw new IllegalArgumentException("Missing scheme (expected rtsp/rtmp/srt)");

        String host = uri.getHost();
        if (host == null || host.isEmpty())
            throw new IllegalArgumentException("Missing host");

        switch (scheme.toLowerCase()) {
            case "rtsp":
            case "rtmp": {
                MediaServerConfig.PushProtocol protocol = scheme.equalsIgnoreCase("rtsp")
                        ? MediaServerConfig.PushProtocol.RTSP : MediaServerConfig.PushProtocol.RTMP;
                int port = uri.getPort() >= 0 ? uri.getPort() : protocol.defaultPort;
                String path = emptyToNull(stripLeadingSlash(uri.getPath()));
                String name = queryParam(uri.getRawQuery(), "name");

                // RTSP carries credentials in the userinfo; RTMP in query params.
                // Split the RAW userinfo before decoding — decoding first would let a
                // password containing an encoded ':' split in the wrong place.
                String username = null, password = null;
                String rawUserInfo = uri.getRawUserInfo();
                if (rawUserInfo != null && !rawUserInfo.isEmpty()) {
                    int colon = rawUserInfo.indexOf(':');
                    username = urlDecode(colon >= 0 ? rawUserInfo.substring(0, colon) : rawUserInfo);
                    password = colon >= 0 ? urlDecode(rawUserInfo.substring(colon + 1)) : null;
                } else {
                    username = queryParam(uri.getRawQuery(), "user");
                    password = queryParam(uri.getRawQuery(), "pass");
                }
                return new Parsed(protocol, host, port, path, null, name,
                        emptyToNull(username), emptyToNull(password));
            }
            case "srt": {
                int port = uri.getPort() >= 0 ? uri.getPort()
                        : MediaServerConfig.PushProtocol.SRT.defaultPort;
                // RAW query throughout: URI.getQuery() hands back an already-decoded
                // string, and splitting that on '&' or ':' would mis-split a credential
                // containing an encoded separator (%26, %3A). Split first, decode after.
                String query = uri.getRawQuery();
                String streamId = rawQueryParam(query, "streamid");
                String passphrase = queryParam(query, "passphrase");
                String name = queryParam(query, "name");
                // streamid is "{read|publish}:{path}[:{user}:{pass}]" — the direction tag
                // isn't stored, only the path (this plugin always publishes; "read" URLs
                // are what gets shared with viewers and is what's typically scanned back
                // in). Split on every colon rather than just the first: a credentialed
                // stream id would otherwise fold "path:user:pass" into the path.
                String path = null, username = null, password = null;
                if (streamId != null) {
                    String[] parts = streamId.split(":", -1);
                    if (parts.length == 1) {
                        path = urlDecode(parts[0]);    // bare path, no direction tag
                    } else {
                        path = urlDecode(parts[1]);
                        if (parts.length > 2) username = urlDecode(parts[2]);
                        if (parts.length > 3) password = urlDecode(parts[3]);
                    }
                }
                return new Parsed(MediaServerConfig.PushProtocol.SRT, host, port,
                        emptyToNull(path), passphrase, name,
                        emptyToNull(username), emptyToNull(password));
            }
            default:
                throw new IllegalArgumentException("Unsupported protocol: " + scheme);
        }
    }

    /** Case-insensitive key lookup over a raw query string, URL-decoding the value. */
    private static String queryParam(String rawQuery, String key) {
        return urlDecode(rawQueryParam(rawQuery, key));
    }

    /** Same lookup, but leaves the value encoded — for values with internal structure
     *  (the SRT stream id) that has to be split before its parts are decoded. */
    private static String rawQueryParam(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String param : rawQuery.split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0) continue;
            if (!param.substring(0, eq).equalsIgnoreCase(key)) continue;
            return param.substring(eq + 1);
        }
        return null;
    }

    private static String urlDecode(String value) {
        if (value == null) return null;
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return value; // malformed encoding — fall back to the raw value
        }
    }

    /** Absent and blank are the same thing to callers: "the QR didn't set this". */
    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static String stripLeadingSlash(String path) {
        if (path == null) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
