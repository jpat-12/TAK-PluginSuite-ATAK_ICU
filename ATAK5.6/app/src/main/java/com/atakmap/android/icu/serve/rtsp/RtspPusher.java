package com.atakmap.android.icu.serve.rtsp;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/**
 * Minimal RTSP client that PUBLISHES H.264 to a server (ANNOUNCE → SETUP → RECORD),
 * sending RTP interleaved over the RTSP TCP connection. Works with generic
 * RTSP ingest.
 *
 * <p>Supports both HTTP <b>Basic</b> and <b>Digest</b> auth (MD5, with or without
 * {@code qop=auth}). Digest is what MediaMTX and most RTSP servers actually use, so it's
 * required for user/password ingest to work. Because the Digest response is computed per
 * request (method + URI), the header is (re)built on every request rather than cached.</p>
 */
public class RtspPusher {

    private static final String TAG = "ICU.RtspPush";

    private final String host;
    private final int    port;
    private final String path;
    private final String user, pass;

    private final String baseUrl;
    private Socket socket;
    private InputStream inRaw;
    private BufferedReader in;
    private OutputStream out;
    private int cseq = 1;
    private String session;
    private String authHeader;              // cached Basic auth header once known to be needed
    // Digest auth challenge (parsed from the 401 WWW-Authenticate); rebuilt per request.
    private boolean digest;
    private String realm, nonce, opaque, qop;
    private int nc = 1;

    private final int ssrc = new Random().nextInt();
    private int seq = 0;
    private byte[] sps, pps;
    private int rtpChannel = 0;          // interleaved RTP channel negotiated in SETUP
    private boolean sendErrLogged;       // throttle the "broken pipe" spam to one line

    public RtspPusher(String host, int port, String path, String user, String pass) {
        this.host = host;
        this.port = port;
        this.path = path.startsWith("/") ? path.substring(1) : path;
        this.user = user;
        this.pass = pass;
        this.baseUrl = "rtsp://" + host + ":" + port + "/" + this.path;
    }

    // ── Publish handshake ────────────────────────────────────────────────────

    public void publish(byte[] sps, byte[] pps) throws IOException {
        this.sps = strip(sps);
        this.pps = strip(pps);

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        inRaw = socket.getInputStream();
        in    = new BufferedReader(new InputStreamReader(inRaw));
        out   = socket.getOutputStream();

        Resp opt = request("OPTIONS", baseUrl, null, null);
        Log.d(TAG, "OPTIONS -> " + opt.code);

        String sdp = buildSdp();
        Resp announce = request("ANNOUNCE", baseUrl,
                "Content-Type: application/sdp\r\nContent-Length: " + sdp.length() + "\r\n", sdp);
        if (announce.code == 401) {
            // Parse the challenge and retry once. Prefer Digest (what MediaMTX & friends
            // use); fall back to Basic. The auth header then applies to every request.
            if (user != null && !user.isEmpty()) {
                if (announce.headers.toLowerCase().contains("digest")) {
                    parseDigestChallenge(announce.headers);
                } else {
                    authHeader = "Authorization: Basic " + Base64.encodeToString(
                            (user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP) + "\r\n";
                }
                announce = request("ANNOUNCE", baseUrl,
                        "Content-Type: application/sdp\r\nContent-Length: " + sdp.length() + "\r\n", sdp);
            }
            if (announce.code == 401)
                throw new IOException((user == null || user.isEmpty())
                        ? "server requires authentication (no username/password set)"
                        : "authentication failed — check username/password");
        }
        Log.d(TAG, "ANNOUNCE -> " + announce.code + (digest ? " (digest)" : authHeader != null ? " (basic)" : ""));
        if (announce.code != 200) throw new IOException("ANNOUNCE failed: " + announce.code);

        Resp setup = request("SETUP", baseUrl + "/trackID=0",
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1;mode=record\r\n", null);
        Log.d(TAG, "SETUP -> " + setup.code + " transport=" + transportLine(setup.headers));
        if (setup.code != 200) throw new IOException("SETUP failed: " + setup.code);
        session = parseSession(setup.headers);
        rtpChannel = parseRtpChannel(setup.headers);   // honor the server's negotiated channel

        Resp record = request("RECORD", baseUrl, "Range: npt=0.000-\r\n", null);
        Log.d(TAG, "RECORD -> " + record.code);
        if (record.code != 200) throw new IOException("RECORD failed: " + record.code);
        Log.d(TAG, "RTSP publishing → " + baseUrl + " (session " + session + ", rtpChannel " + rtpChannel + ")");
    }

    public void close() {
        try { if (session != null) request("TEARDOWN", baseUrl, null, null); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    // ── RTP send (interleaved over TCP) ───────────────────────────────────────

    public void sendNal(byte[] annexB, boolean keyFrame, long ptsUs) {
        if (out == null) return;
        long ts = ptsUs * 90L / 1000L;
        byte[] nal = strip(annexB);
        try {
            if (keyFrame) {
                if (sps != null) sendRtp(sps, false, ts);
                if (pps != null) sendRtp(pps, false, ts);
            }
            sendRtp(nal, true, ts);
        } catch (IOException e) {
            if (!sendErrLogged) {
                sendErrLogged = true;
                Log.w(TAG, "sendNal failed (" + e.getMessage() + ") — server closed the RTP "
                        + "connection after RECORD; stopping further log spam");
            }
        }
    }

    private void sendRtp(byte[] nal, boolean marker, long ts) throws IOException {
        if (nal.length <= 1400) {
            writeInterleaved(buildRtp(nal, 0, nal.length, marker, ts));
        } else {
            // FU-A fragmentation
            byte nalHeader = nal[0];
            byte fuInd = (byte) ((nalHeader & 0xE0) | 28);
            int pos = 1;
            boolean first = true;
            while (pos < nal.length) {
                int chunk = Math.min(nal.length - pos, 1398);
                boolean last = (pos + chunk >= nal.length);
                byte fuHdr = (byte) (nalHeader & 0x1F);
                if (first) fuHdr |= 0x80;
                if (last)  fuHdr |= 0x40;
                byte[] payload = new byte[2 + chunk];
                payload[0] = fuInd; payload[1] = fuHdr;
                System.arraycopy(nal, pos, payload, 2, chunk);
                writeInterleaved(buildRtp(payload, 0, payload.length, last && marker, ts));
                first = false; pos += chunk;
            }
        }
    }

    private byte[] buildRtp(byte[] d, int off, int len, boolean marker, long ts) {
        byte[] p = new byte[12 + len];
        int s = seq++ & 0xFFFF;
        p[0] = (byte) 0x80;
        p[1] = (byte) ((marker ? 0x80 : 0) | 96);
        p[2] = (byte) (s >> 8); p[3] = (byte) s;
        p[4] = (byte) (ts >> 24); p[5] = (byte) (ts >> 16); p[6] = (byte) (ts >> 8); p[7] = (byte) ts;
        p[8] = (byte) (ssrc >> 24); p[9] = (byte) (ssrc >> 16); p[10] = (byte) (ssrc >> 8); p[11] = (byte) ssrc;
        System.arraycopy(d, off, p, 12, len);
        return p;
    }

    private synchronized void writeInterleaved(byte[] rtp) throws IOException {
        out.write(0x24);          // '$'
        out.write(rtpChannel);    // negotiated RTP interleaved channel
        out.write((rtp.length >> 8) & 0xFF);
        out.write(rtp.length & 0xFF);
        out.write(rtp);
        out.flush();
    }

    // ── RTSP request/response ─────────────────────────────────────────────────

    private static final class Resp { int code; String headers = ""; }

    private synchronized Resp request(String method, String url, String extraHeaders, String body)
            throws IOException {
        StringBuilder r = new StringBuilder();
        r.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        r.append("CSeq: ").append(cseq++).append("\r\n");
        r.append("User-Agent: ICU-VideoStreamer\r\n");
        if (session != null) r.append("Session: ").append(session).append("\r\n");
        if (digest) r.append(digestHeader(method, url));   // Digest is per-request (method+uri)
        else if (authHeader != null) r.append(authHeader);
        if (extraHeaders != null) r.append(extraHeaders);
        r.append("\r\n");
        if (body != null) r.append(body);
        out.write(r.toString().getBytes("UTF-8"));
        out.flush();
        return readResponse();
    }

    private Resp readResponse() throws IOException {
        Resp resp = new Resp();
        String status = in.readLine();
        if (status == null) throw new IOException("connection closed");
        // "RTSP/1.0 200 OK"
        String[] parts = status.split(" ");
        resp.code = (parts.length >= 2) ? parseInt(parts[1]) : 0;
        StringBuilder h = new StringBuilder();
        String line; int contentLength = 0;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            h.append(line).append("\r\n");
            if (line.toLowerCase().startsWith("content-length:"))
                contentLength = parseInt(line.substring(15).trim());
        }
        resp.headers = h.toString();
        for (int i = 0; i < contentLength; i++) in.read(); // drain any body
        return resp;
    }

    // ── Digest auth ───────────────────────────────────────────────────────────

    /** Parse realm/nonce/opaque/qop from a "WWW-Authenticate: Digest …" header. */
    private void parseDigestChallenge(String headers) {
        for (String line : headers.split("\r\n")) {
            String low = line.toLowerCase();
            if (low.startsWith("www-authenticate:") && low.contains("digest")) {
                realm  = param(line, "realm");
                nonce  = param(line, "nonce");
                opaque = param(line, "opaque");
                qop    = param(line, "qop");
                digest = (realm != null && nonce != null);
                return;
            }
        }
    }

    /** Build the "Authorization: Digest …" header for this request's method + URI (RFC 2617). */
    private String digestHeader(String method, String uri) {
        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5(method + ":" + uri);
        String response;
        StringBuilder h = new StringBuilder("Authorization: Digest ")
                .append("username=\"").append(user).append("\", ")
                .append("realm=\"").append(realm).append("\", ")
                .append("nonce=\"").append(nonce).append("\", ")
                .append("uri=\"").append(uri).append("\"");
        if (qop != null && qop.toLowerCase().contains("auth")) {
            String ncHex = String.format("%08x", nc++);
            String cnonce = Integer.toHexString(new Random().nextInt());
            response = md5(ha1 + ":" + nonce + ":" + ncHex + ":" + cnonce + ":auth:" + ha2);
            h.append(", qop=auth, nc=").append(ncHex).append(", cnonce=\"").append(cnonce).append("\"");
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        h.append(", response=\"").append(response).append("\"");
        if (opaque != null) h.append(", opaque=\"").append(opaque).append("\"");
        return h.append("\r\n").toString();
    }

    /** Extract a quoted (or bare) parameter value from a header line. */
    private static String param(String header, String key) {
        String low = header.toLowerCase();
        int i = low.indexOf(key.toLowerCase() + "=");
        if (i < 0) return null;
        int v = i + key.length() + 1;
        if (v < header.length() && header.charAt(v) == '"') {
            int end = header.indexOf('"', v + 1);
            return end > v ? header.substring(v + 1, end) : null;
        }
        int end = v;
        while (end < header.length() && header.charAt(end) != ',' && header.charAt(end) != '\r') end++;
        return header.substring(v, end).trim();
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            return hex(d);
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                           .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static String transportLine(String headers) {
        for (String line : headers.split("\r\n"))
            if (line.toLowerCase().startsWith("transport:")) return line.substring(10).trim();
        return "";
    }

    /** The RTP interleaved channel the server assigned in SETUP (defaults to 0). */
    private static int parseRtpChannel(String headers) {
        String t = transportLine(headers).toLowerCase();
        int i = t.indexOf("interleaved=");
        if (i >= 0) {
            int s = i + "interleaved=".length();
            int e = s;
            while (e < t.length() && Character.isDigit(t.charAt(e))) e++;
            try { return Integer.parseInt(t.substring(s, e)); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static String parseSession(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("session:")) {
                String v = line.substring(8).trim();
                int semi = v.indexOf(';');
                return semi >= 0 ? v.substring(0, semi).trim() : v;
            }
        }
        return null;
    }

    private String buildSdp() {
        String spsB64 = Base64.encodeToString(sps, Base64.NO_WRAP);
        String ppsB64 = Base64.encodeToString(pps, Base64.NO_WRAP);
        return "v=0\r\n" +
               "o=- 0 0 IN IP4 127.0.0.1\r\n" +
               "s=ICU VideoStreamer\r\n" +
               "c=IN IP4 0.0.0.0\r\n" +
               "t=0 0\r\n" +
               "m=video 0 RTP/AVP 96\r\n" +
               "a=rtpmap:96 H264/90000\r\n" +
               "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=" + spsB64 + "," + ppsB64 + "\r\n" +
               "a=control:trackID=0\r\n";
    }

    private static byte[] strip(byte[] d) {
        if (d == null) return null;
        int off = 0;
        if (d.length > 4 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) off = 4;
        else if (d.length > 3 && d[0] == 0 && d[1] == 0 && d[2] == 1) off = 3;
        return off == 0 ? d : Arrays.copyOfRange(d, off, d.length);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
