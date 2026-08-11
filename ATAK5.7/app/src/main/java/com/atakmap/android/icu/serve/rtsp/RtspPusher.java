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
    private volatile boolean streaming;  // true after RECORD; gates the drain loop
    private Thread drainThread;          // reads/discards the server's inbound RTCP + keepalives

    // ── Optional second track: AAC audio (RFC 3640 mpeg4-generic) ──
    private boolean hasAudio;
    private byte[]  audioAsc;            // AudioSpecificConfig
    private int     audioRate = 44100, audioChannels = 1;
    private int     audioRtpChannel = 2;                 // interleaved channel from audio SETUP
    private int     audioSeq;
    private final int audioSsrc = new Random().nextInt();
    private static final int PT_VIDEO = 96, PT_AUDIO = 97;

    public RtspPusher(String host, int port, String path, String user, String pass) {
        this.host = host;
        this.port = port;
        this.path = path.startsWith("/") ? path.substring(1) : path;
        this.user = user;
        this.pass = pass;
        this.baseUrl = "rtsp://" + host + ":" + port + "/" + this.path;
    }

    /** Enable an AAC audio track. Must be called before {@link #publish} (the SDP is built
     *  once during the handshake). */
    public void setAudio(byte[] asc, int sampleRate, int channels) {
        if (asc == null || asc.length == 0) return;
        this.hasAudio = true;
        this.audioAsc = asc.clone();
        this.audioRate = sampleRate;
        this.audioChannels = channels;
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
        if (announce.code != 200) { logFail("ANNOUNCE", announce); throw new IOException("ANNOUNCE failed: " + announce.code); }

        Resp setup = request("SETUP", baseUrl + "/trackID=0",
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1;mode=record\r\n", null);
        Log.d(TAG, "SETUP -> " + setup.code + " transport=" + transportLine(setup.headers));
        if (setup.code != 200) { logFail("SETUP", setup); throw new IOException("SETUP failed: " + setup.code); }
        session = parseSession(setup.headers);
        rtpChannel = parseRtpChannel(setup.headers);   // honor the server's negotiated channel

        if (hasAudio) {
            // Second track: interleaved RTP/RTCP on channels 2-3. The Session from the video
            // SETUP is carried automatically (aggregate control), so both tracks share it.
            Resp aSetup = request("SETUP", baseUrl + "/trackID=1",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3;mode=record\r\n", null);
            Log.d(TAG, "SETUP audio -> " + aSetup.code + " transport=" + transportLine(aSetup.headers));
            if (aSetup.code != 200) {
                // Non-fatal: fall back to video-only rather than aborting the whole publish.
                logFail("SETUP audio", aSetup);
                hasAudio = false;
            } else {
                audioRtpChannel = parseRtpChannel(aSetup.headers);
                if (audioRtpChannel == 0) audioRtpChannel = 2;   // default if server didn't echo
            }
        }

        Resp record = request("RECORD", baseUrl, "Range: npt=0.000-\r\n", null);
        Log.d(TAG, "RECORD -> " + record.code);
        if (record.code != 200) { logFail("RECORD", record); throw new IOException("RECORD failed: " + record.code); }
        streaming = true;
        startDrain();   // keep the socket read side drained so the server's writes don't back up
        Log.d(TAG, "RTSP publishing → " + baseUrl + " (session " + session + ", rtpChannel " + rtpChannel + ")");
    }

    /**
     * After RECORD the server keeps sending on the same TCP connection — interleaved RTCP
     * receiver reports and periodic keepalives. If we never read them, its send buffer fills
     * and (with the server's writeTimeout) gortsplib eventually fails to write and drops the
     * publish, which surfaces here as a "Broken pipe". Draining the read side prevents that;
     * we don't need the contents, just to keep the pipe clear.
     */
    private void startDrain() {
        drainThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                while (streaming) {
                    int n = inRaw.read(buf);
                    if (n < 0) break;   // server closed the connection
                }
            } catch (IOException ignored) {
                // socket closed on teardown, or the server dropped us — nothing to do
            }
        }, "ICU-RtspDrain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    /** Log the server's full reply (status line + body + headers) for a failed handshake
     *  step. A MediaMTX 400 with body "path '…' is not configured" means the server has no
     *  path rule for the stream name — a server-config issue, not a client bug. */
    private void logFail(String verb, Resp r) {
        Log.w(TAG, verb + " rejected: '" + r.status + "'"
                + (r.body.isEmpty() ? "" : " body=[" + r.body.trim() + "]")
                + " reqUri=" + baseUrl
                + " headers=[" + r.headers.replace("\r\n", " | ").trim() + "]");
    }

    /** True once the server challenged with 401 and we applied Basic/Digest credentials —
     *  i.e. this publish is actually authenticated (not anonymous). Drives the UI badge. */
    public boolean usedAuth() { return digest || authHeader != null; }

    public void close() {
        streaming = false;
        // Closing the socket unblocks the drain thread's read and signals EOF to the server,
        // which destroys the session. (A formal TEARDOWN would race the drain reader for its
        // response, so we rely on the disconnect instead — gortsplib handles EOF cleanly.)
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (drainThread != null) {
            try { drainThread.join(300); } catch (InterruptedException ignored) {}
            drainThread = null;
        }
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
            writeInterleaved(rtpChannel, buildRtp(nal, 0, nal.length, marker, ts, PT_VIDEO, seq++, ssrc));
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
                writeInterleaved(rtpChannel, buildRtp(payload, 0, payload.length, last && marker, ts, PT_VIDEO, seq++, ssrc));
                first = false; pos += chunk;
            }
        }
    }

    /** Send one AAC access unit as an RFC 3640 (mpeg4-generic, AAC-hbr) RTP packet. Sent over
     *  TCP interleaved, so a whole frame fits in one packet — no fragmentation needed. */
    public void sendAudioSample(byte[] aac, long ptsUs) {
        if (out == null || !hasAudio || aac == null || aac.length == 0) return;
        long ts = ptsUs * audioRate / 1_000_000L;   // RTP audio clock = sample rate
        // AU-headers-length (16 bits) + one AU-header: 13-bit size, 3-bit index(0).
        byte[] payload = new byte[4 + aac.length];
        payload[0] = 0x00; payload[1] = 0x10;                     // AU-headers-length = 16 bits
        int hdr = (aac.length & 0x1FFF) << 3;                     // size<<3 | index(0)
        payload[2] = (byte) (hdr >> 8); payload[3] = (byte) hdr;
        System.arraycopy(aac, 0, payload, 4, aac.length);
        try {
            writeInterleaved(audioRtpChannel,
                    buildRtp(payload, 0, payload.length, true, ts, PT_AUDIO, audioSeq++, audioSsrc));
        } catch (IOException e) {
            if (!sendErrLogged) {
                sendErrLogged = true;
                Log.w(TAG, "sendAudio failed (" + e.getMessage() + ")");
            }
        }
    }

    private byte[] buildRtp(byte[] d, int off, int len, boolean marker, long ts,
                            int payloadType, int seqNum, int ssrcId) {
        byte[] p = new byte[12 + len];
        int s = seqNum & 0xFFFF;
        p[0] = (byte) 0x80;
        p[1] = (byte) ((marker ? 0x80 : 0) | payloadType);
        p[2] = (byte) (s >> 8); p[3] = (byte) s;
        p[4] = (byte) (ts >> 24); p[5] = (byte) (ts >> 16); p[6] = (byte) (ts >> 8); p[7] = (byte) ts;
        p[8] = (byte) (ssrcId >> 24); p[9] = (byte) (ssrcId >> 16); p[10] = (byte) (ssrcId >> 8); p[11] = (byte) ssrcId;
        System.arraycopy(d, off, p, 12, len);
        return p;
    }

    private synchronized void writeInterleaved(int channel, byte[] rtp) throws IOException {
        out.write(0x24);          // '$'
        out.write(channel);       // negotiated RTP interleaved channel (video or audio)
        out.write((rtp.length >> 8) & 0xFF);
        out.write(rtp.length & 0xFF);
        out.write(rtp);
        out.flush();
    }

    // ── RTSP request/response ─────────────────────────────────────────────────

    private static final class Resp { int code; String status = ""; String headers = ""; String body = ""; }

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
        resp.status = status;
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
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < contentLength; i++) { int c = in.read(); if (c >= 0) b.append((char) c); }
        resp.body = b.toString();   // captured so logFail() can show the server's reason
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
        StringBuilder sdp = new StringBuilder()
               .append("v=0\r\n")
               .append("o=- 0 0 IN IP4 127.0.0.1\r\n")
               .append("s=ICU VideoStreamer\r\n")
               .append("c=IN IP4 0.0.0.0\r\n")
               .append("t=0 0\r\n")
               .append("m=video 0 RTP/AVP ").append(PT_VIDEO).append("\r\n")
               .append("a=rtpmap:").append(PT_VIDEO).append(" H264/90000\r\n")
               .append("a=fmtp:").append(PT_VIDEO)
               .append(" packetization-mode=1;sprop-parameter-sets=").append(spsB64).append(",").append(ppsB64).append("\r\n")
               .append("a=control:trackID=0\r\n");
        if (hasAudio) {
            // RFC 3640 mpeg4-generic (AAC-hbr). config = the AudioSpecificConfig as hex.
            sdp.append("m=audio 0 RTP/AVP ").append(PT_AUDIO).append("\r\n")
               .append("a=rtpmap:").append(PT_AUDIO).append(" mpeg4-generic/")
               .append(audioRate).append("/").append(audioChannels).append("\r\n")
               .append("a=fmtp:").append(PT_AUDIO)
               .append(" streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=")
               .append(hex(audioAsc)).append("\r\n")
               .append("a=control:trackID=1\r\n");
        }
        return sdp.toString();
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
