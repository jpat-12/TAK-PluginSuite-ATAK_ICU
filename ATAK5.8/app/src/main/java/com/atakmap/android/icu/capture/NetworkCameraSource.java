package com.atakmap.android.icu.capture;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Base64;
import android.view.Surface;

import com.atakmap.android.icu.util.DiagLog;
import com.atakmap.coremap.log.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Network (IP) camera capture source — an RTSP <b>pull</b> client that decodes the
 * camera's H.264 stream onto the same target {@link Surface} the Camera2/UVC paths
 * render into, so {@link CapturePipeline} treats a helmet cam on the radio mesh (e.g. a
 * MOHOC behind a Silvus) exactly like a local camera: same GL stage, same encoders,
 * same transports, profiles, and recording.
 *
 * <p>Deliberately TCP-interleaved (RTP over the RTSP connection, RFC 2326 §10.12):
 * one TCP stream traverses a mesh/radio link far more predictably than a separate
 * UDP RTP flow, and needs no client port negotiation. The camera's video is decoded
 * and re-encoded rather than passed through — that costs one decode, but it's what
 * lets every existing feature (per-destination bitrate/resolution, HQ recording,
 * on-device RTSP re-serve) apply unchanged to a network source.</p>
 *
 * <p>Scope (v1): H.264 video over RTSP, no/basic auth (credentials via
 * {@code rtsp://user:pass@host/...}), camera audio ignored (the phone's own mic
 * remains the audio source when enabled). MJPEG/HEVC cameras are not supported.</p>
 */
public class NetworkCameraSource {

    private static final String TAG = "ICU.NetworkCameraSource";

    public interface Callback {
        void onError(String message);
        /** Fired once PLAY succeeded — RTP data is expected from here on. */
        default void onOpened() {}
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;
    /** No RTP data for this long after PLAY = the feed is considered dead. */
    private static final int READ_TIMEOUT_MS    = 10000;
    private static final long KEEPALIVE_MS      = 25000;

    private volatile boolean running;
    private Thread readerThread;
    private Thread drainThread;
    private Socket socket;
    private MediaCodec decoder;
    private Surface targetSurface;
    private volatile Callback callback;

    private final AtomicInteger cseq = new AtomicInteger(1);
    private String sessionId;
    private String authHeader;   // preemptive Basic auth from URL userinfo, or null

    // For the best-effort TEARDOWN on stop() (which runs on a different thread than
    // the reader that owns the session) — writes to the socket are serialized.
    private final Object writeLock = new Object();
    private volatile OutputStream ctrlOut;
    private volatile String ctrlUrl;

    public boolean isRunning() { return running; }

    public void start(String url, Surface target, Callback cb) {
        this.targetSurface = target;
        this.callback = cb;
        running = true;
        readerThread = new Thread(() -> run(url), "ICU-NetCam");
        readerThread.start();
    }

    public void stop() {
        running = false;
        Callback cb = callback;
        callback = null;   // no error reports for a deliberate stop
        // TEARDOWN before dropping the connection: single-session cameras keep the old
        // session alive for a grace period after an unannounced disconnect and refuse
        // the next SETUP until it expires — which made an immediate stop→start fail.
        OutputStream out = ctrlOut;
        String url = ctrlUrl;
        if (out != null && url != null && sessionId != null) {
            try {
                synchronized (writeLock) {
                    out.write(("TEARDOWN " + url + " RTSP/1.0\r\nCSeq: "
                            + cseq.getAndIncrement() + "\r\nSession: " + sessionId + "\r\n"
                            + (authHeader != null ? authHeader + "\r\n" : "") + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                DiagLog.d(TAG, "TEARDOWN sent (session " + sessionId + ")");
                Thread.sleep(150);   // give it a beat to reach the camera before close
            } catch (Exception e) {
                DiagLog.w(TAG, "TEARDOWN failed: " + e);
            }
        }
        sessionId = null;
        ctrlOut = null;
        ctrlUrl = null;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (readerThread != null) {
            try { readerThread.join(1000); } catch (InterruptedException ignored) {}
            readerThread = null;
        }
        // decoder/drain are torn down by the reader thread's finally block; make sure
        // even a wedged reader doesn't leak them.
        releaseDecoder();
    }

    private void fail(String message) {
        Callback cb = callback;
        if (cb != null && running) cb.onError(message);
        running = false;
    }

    // ── RTSP session ─────────────────────────────────────────────────────────────

    /** Every per-session field back to first-run state. The source object is reused
     *  across broadcasts; without this, run #2 sent the previous run's Session id on
     *  its DESCRIBE/SETUP and the camera refused the "modification" with a 501. */
    private void resetSessionState() {
        cseq.set(1);
        sessionId = null;
        authHeader = null;
        lastSeq = -1;
        lastRtpTs = -1;
        rtpTsBase = -1;
        tsWraps = 0;
        au.reset();
        fua.reset();
        auTs = -1;
        inbandSps = null;
        inbandPps = null;
    }

    private void run(String rawUrl) {
        resetSessionState();
        try {
            URI uri = new URI(rawUrl.trim());
            if (!"rtsp".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("Only rtsp:// camera URLs are supported");
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 554;
            if (uri.getUserInfo() != null) {
                authHeader = "Authorization: Basic " + Base64.encodeToString(
                        uri.getUserInfo().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            }
            // Request URL without the userinfo — some servers reject it in the target.
            String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            String reqUrl = "rtsp://" + host + ":" + port + path;

            DiagLog.d(TAG, "connecting " + host + ":" + port);
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            OutputStream out = socket.getOutputStream();
            ctrlOut = out;
            ctrlUrl = reqUrl;

            Response describe = request(in, out, "DESCRIBE", reqUrl,
                    "Accept: application/sdp\r\n");
            if (describe.code == 401)
                throw new IllegalStateException("Camera requires credentials the URL doesn't carry"
                        + " (rtsp://user:pass@host/…; digest auth not supported)");
            if (describe.code != 200)
                throw new IllegalStateException("DESCRIBE failed: " + describe.code);

            Sdp sdp = parseSdp(describe.body, header(describe, "Content-Base", reqUrl));
            if (sdp.control == null)
                throw new IllegalStateException("No H.264 video track in the camera's SDP");
            if (sdp.sps != null && sdp.pps != null) tryConfigureDecoder(sdp.sps, sdp.pps);

            Response setup = request(in, out, "SETUP", sdp.control,
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
            if (setup.code != 200) {
                DiagLog.w(TAG, "SETUP " + setup.code + " headers: " + setup.headers);
                throw new IllegalStateException("SETUP failed: " + setup.code
                        + " (camera may not support TCP-interleaved transport, or a "
                        + "previous session is still open on it)");
            }
            String session = header(setup, "Session", null);
            if (session == null) throw new IllegalStateException("SETUP returned no session");
            int semi = session.indexOf(';');
            sessionId = (semi >= 0 ? session.substring(0, semi) : session).trim();

            Response play = request(in, out, "PLAY", reqUrl, "Range: npt=0.000-\r\n");
            if (play.code != 200) throw new IllegalStateException("PLAY failed: " + play.code);

            Callback cb = callback;
            if (cb != null) cb.onOpened();
            DiagLog.d(TAG, "playing " + reqUrl + " (session " + sessionId + ")");

            readInterleaved(in, out, reqUrl, sdp.videoPayloadType);
        } catch (SocketTimeoutException e) {
            DiagLog.w(TAG, "read timeout — no data for " + (READ_TIMEOUT_MS / 1000) + "s");
            fail("Network camera stopped sending (no data for " + (READ_TIMEOUT_MS / 1000) + "s)");
        } catch (Exception e) {
            if (running) DiagLog.w(TAG, "session error: " + e);
            fail("Network camera: " + e.getMessage());
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            releaseDecoder();
        }
    }

    private static final class Response {
        int code;
        final List<String> headers = new ArrayList<>();
        String body = "";
    }

    private Response request(InputStream in, OutputStream out, String method, String url,
                             String extraHeaders) throws Exception {
        StringBuilder r = new StringBuilder();
        r.append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
                .append("CSeq: ").append(cseq.getAndIncrement()).append("\r\n")
                .append("User-Agent: ATAK-ICU\r\n");
        if (authHeader != null) r.append(authHeader).append("\r\n");
        if (sessionId != null) r.append("Session: ").append(sessionId).append("\r\n");
        if (extraHeaders != null) r.append(extraHeaders);
        r.append("\r\n");
        synchronized (writeLock) {
            out.write(r.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        Response resp = readResponse(in);
        DiagLog.d(TAG, method + " " + url + " -> " + resp.code);
        return resp;
    }

    /** Read one RTSP response (headers + Content-Length body). Assumes no interleaved
     *  binary frames yet — used only during session setup. */
    private Response readResponse(InputStream in) throws Exception {
        Response resp = new Response();
        String status = readLine(in);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !status.startsWith("RTSP/"))
            throw new IllegalStateException("Not an RTSP response: " + status);
        resp.code = Integer.parseInt(parts[1]);
        int contentLength = 0;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            resp.headers.add(line);
            if (line.toLowerCase(Locale.US).startsWith("content-length:"))
                contentLength = Integer.parseInt(line.substring(15).trim());
        }
        if (contentLength > 0) {
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(body, off, contentLength - off);
                if (n < 0) throw new IllegalStateException("EOF in response body");
                off += n;
            }
            resp.body = new String(body, StandardCharsets.UTF_8);
        }
        return resp;
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c < 0 && sb.length() == 0) throw new IllegalStateException("Connection closed");
        return sb.toString();
    }

    private static String header(Response r, String name, String def) {
        String prefix = name.toLowerCase(Locale.US) + ":";
        for (String h : r.headers)
            if (h.toLowerCase(Locale.US).startsWith(prefix))
                return h.substring(prefix.length()).trim();
        return def;
    }

    // ── SDP ──────────────────────────────────────────────────────────────────────

    private static final class Sdp {
        String control;          // absolute SETUP URL for the H.264 video track
        int videoPayloadType = -1;
        byte[] sps, pps;         // from sprop-parameter-sets, if given
    }

    private Sdp parseSdp(String sdp, String base) {
        Sdp out = new Sdp();
        boolean inVideo = false;
        String mediaControl = null;
        if (!base.endsWith("/")) base += "/";
        for (String line : sdp.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("m=")) {
                inVideo = line.startsWith("m=video");
                if (inVideo) {
                    String[] p = line.split(" ");
                    if (p.length >= 4) try { out.videoPayloadType = Integer.parseInt(p[3]); } catch (Exception ignored) {}
                }
            } else if (inVideo && line.startsWith("a=control:")) {
                mediaControl = line.substring(10).trim();
            } else if (inVideo && line.startsWith("a=fmtp:")) {
                int idx = line.indexOf("sprop-parameter-sets=");
                if (idx >= 0) {
                    String sets = line.substring(idx + 21);
                    int end = sets.indexOf(';');
                    if (end >= 0) sets = sets.substring(0, end);
                    String[] both = sets.split(",");
                    try {
                        if (both.length >= 1) out.sps = Base64.decode(both[0], Base64.DEFAULT);
                        if (both.length >= 2) out.pps = Base64.decode(both[1], Base64.DEFAULT);
                    } catch (Exception e) {
                        Log.w(TAG, "bad sprop-parameter-sets: " + e.getMessage());
                    }
                }
            }
        }
        if (mediaControl == null) mediaControl = "*";
        if (mediaControl.startsWith("rtsp://")) out.control = mediaControl;
        else if (mediaControl.equals("*"))      out.control = base;
        else                                    out.control = base + mediaControl;
        return out;
    }

    // ── Interleaved RTP read loop ────────────────────────────────────────────────

    private void readInterleaved(InputStream in, OutputStream out, String reqUrl,
                                 int videoPt) throws Exception {
        long lastKeepalive = System.currentTimeMillis();
        byte[] frame = new byte[128 * 1024];
        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastKeepalive > KEEPALIVE_MS) {
                lastKeepalive = now;
                // Response arrives interleaved and is consumed below as a non-'$' message.
                String ka = "GET_PARAMETER " + reqUrl + " RTSP/1.0\r\nCSeq: "
                        + cseq.getAndIncrement() + "\r\nSession: " + sessionId + "\r\n"
                        + (authHeader != null ? authHeader + "\r\n" : "") + "\r\n";
                try {
                    synchronized (writeLock) { out.write(ka.getBytes(StandardCharsets.UTF_8)); out.flush(); }
                } catch (Exception e) { DiagLog.w(TAG, "keepalive: " + e.getMessage()); }
            }

            int first = in.read();
            if (first < 0) throw new IllegalStateException("Camera closed the connection");
            if (first != '$') {
                // An RTSP message (keepalive reply, announce…) — consume and discard.
                consumeRtspMessage(in, first);
                continue;
            }
            int channel = in.read();
            int len = (in.read() << 8) | in.read();
            if (len < 0 || len > frame.length)
                throw new IllegalStateException("Bad interleaved frame length " + len);
            int off = 0;
            while (off < len) {
                int n = in.read(frame, off, len - off);
                if (n < 0) throw new IllegalStateException("EOF mid-frame");
                off += n;
            }
            if (channel == 0) handleRtp(frame, len, videoPt);
            // channel 1 = RTCP — ignored.
        }
    }

    /** Consume an RTSP message whose first status-line byte was already read. */
    private void consumeRtspMessage(InputStream in, int firstByte) throws Exception {
        StringBuilder firstLine = new StringBuilder();
        firstLine.append((char) firstByte);
        int c;
        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') firstLine.append((char) c);
        int contentLength = 0;
        String line;
        while (!(line = readLine(in)).isEmpty())
            if (line.toLowerCase(Locale.US).startsWith("content-length:"))
                contentLength = Integer.parseInt(line.substring(15).trim());
        long skipped = 0;
        while (skipped < contentLength) {
            long n = in.skip(contentLength - skipped);
            if (n <= 0) break;
            skipped += n;
        }
    }

    // ── RTP → H.264 access units ─────────────────────────────────────────────────

    private int lastSeq = -1;
    private long lastRtpTs = -1;
    private long rtpTsBase = -1;
    private long tsWraps;
    private final ByteArrayOutputStream au = new ByteArrayOutputStream(256 * 1024);
    private final ByteArrayOutputStream fua = new ByteArrayOutputStream(128 * 1024);
    private long auTs = -1;
    private byte[] inbandSps, inbandPps;

    private void handleRtp(byte[] buf, int len, int videoPt) {
        if (len < 12) return;
        int v = (buf[0] >> 6) & 3;
        if (v != 2) return;
        boolean padding = (buf[0] & 0x20) != 0;
        boolean ext     = (buf[0] & 0x10) != 0;
        int csrc        = buf[0] & 0x0F;
        boolean marker  = (buf[1] & 0x80) != 0;
        int pt          = buf[1] & 0x7F;
        if (videoPt >= 0 && pt != videoPt) return;
        int seq = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        long ts = ((long) (buf[4] & 0xFF) << 24) | ((buf[5] & 0xFF) << 16)
                | ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        int off = 12 + csrc * 4;
        if (ext) {
            if (len < off + 4) return;
            int extWords = ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
            off += 4 + extWords * 4;
        }
        int end = len;
        if (padding) end -= (buf[len - 1] & 0xFF);
        if (off >= end) return;

        if (lastSeq >= 0 && ((lastSeq + 1) & 0xFFFF) != seq)
            Log.w(TAG, "RTP loss: expected seq " + ((lastSeq + 1) & 0xFFFF) + " got " + seq);
        lastSeq = seq;

        // A new timestamp closes the access unit in progress.
        if (auTs != -1 && ts != auTs) submitAccessUnit();
        auTs = ts;

        int nalType = buf[off] & 0x1F;
        if (nalType >= 1 && nalType <= 23) {
            appendNal(buf, off, end - off);
        } else if (nalType == 24) {                     // STAP-A: 16-bit size-prefixed NALs
            int p = off + 1;
            while (p + 2 <= end) {
                int sz = ((buf[p] & 0xFF) << 8) | (buf[p + 1] & 0xFF);
                p += 2;
                if (sz <= 0 || p + sz > end) break;
                appendNal(buf, p, sz);
                p += sz;
            }
        } else if (nalType == 28) {                     // FU-A fragment
            int indicator = buf[off] & 0xFF;
            int fuHeader  = buf[off + 1] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0;
            boolean fin   = (fuHeader & 0x40) != 0;
            if (start) {
                fua.reset();
                fua.write((indicator & 0xE0) | (fuHeader & 0x1F));   // reconstructed NAL header
            }
            fua.write(buf, off + 2, end - (off + 2));
            if (fin && fua.size() > 0) {
                byte[] nal = fua.toByteArray();
                fua.reset();
                appendNal(nal, 0, nal.length);
            }
        }
        if (marker) submitAccessUnit();
    }

    private void appendNal(byte[] buf, int off, int len) {
        if (len <= 0) return;
        int type = buf[off] & 0x1F;
        if (type == 7) { inbandSps = slice(buf, off, len); tryConfigureFromInband(); return; }
        if (type == 8) { inbandPps = slice(buf, off, len); tryConfigureFromInband(); return; }
        au.write(0); au.write(0); au.write(0); au.write(1);
        au.write(buf, off, len);
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    private void tryConfigureFromInband() {
        if (inbandSps != null && inbandPps != null) tryConfigureDecoder(inbandSps, inbandPps);
    }

    private void submitAccessUnit() {
        if (au.size() == 0) { auTs = -1; return; }
        byte[] data = au.toByteArray();
        au.reset();
        long ts = auTs;
        auTs = -1;
        MediaCodec dec = decoder;
        if (dec == null) return;   // still waiting on SPS/PPS

        // Unwrap the 32-bit 90 kHz timestamp into monotonic microseconds.
        if (rtpTsBase < 0) rtpTsBase = ts;
        if (lastRtpTs >= 0 && ts < lastRtpTs && (lastRtpTs - ts) > 0x40000000L) tsWraps++;
        lastRtpTs = ts;
        long extended = ts + (tsWraps << 32) - rtpTsBase;
        long ptsUs = (extended * 100) / 9;   // 90 kHz → µs

        try {
            int idx = dec.dequeueInputBuffer(50_000);
            if (idx < 0) return;   // decoder busy — drop rather than stall the socket
            ByteBuffer ib = dec.getInputBuffer(idx);
            if (ib == null || ib.capacity() < data.length) {
                dec.queueInputBuffer(idx, 0, 0, 0, 0);
                return;
            }
            ib.clear();
            ib.put(data);
            dec.queueInputBuffer(idx, 0, data.length, ptsUs, 0);
        } catch (IllegalStateException e) {
            if (running) Log.w(TAG, "decoder input: " + e.getMessage());
        }
    }

    // ── Decoder ──────────────────────────────────────────────────────────────────

    private synchronized void tryConfigureDecoder(byte[] sps, byte[] pps) {
        if (decoder != null || !running) return;
        try {
            int[] dims = parseSpsDimensions(sps);
            DiagLog.d(TAG, "decoder config " + dims[0] + "x" + dims[1]
                    + " (sps " + sps.length + "B pps " + pps.length + "B)");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", dims[0], dims[1]);
            fmt.setByteBuffer("csd-0", startCoded(sps));
            fmt.setByteBuffer("csd-1", startCoded(pps));
            MediaCodec dec = MediaCodec.createDecoderByType("video/avc");
            dec.configure(fmt, targetSurface, null, 0);
            dec.start();
            decoder = dec;
            drainThread = new Thread(this::drainOutput, "ICU-NetCamDrain");
            drainThread.start();
        } catch (Exception e) {
            fail("H.264 decoder failed to start: " + e.getMessage());
        }
    }

    private static ByteBuffer startCoded(byte[] nal) {
        ByteBuffer b = ByteBuffer.allocate(nal.length + 4);
        b.put(new byte[]{0, 0, 0, 1}).put(nal).flip();
        return b;
    }

    /** Render decoded frames to the target surface as they come. */
    private void drainOutput() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            MediaCodec dec = decoder;
            if (dec == null) return;
            int idx;
            try {
                idx = dec.dequeueOutputBuffer(info, 20_000);
            } catch (IllegalStateException e) {
                return;   // torn down
            }
            if (idx >= 0) {
                try { dec.releaseOutputBuffer(idx, info.size > 0); }
                catch (IllegalStateException ignored) { return; }
            }
        }
    }

    private synchronized void releaseDecoder() {
        MediaCodec dec = decoder;
        decoder = null;
        if (drainThread != null) {
            try { drainThread.join(500); } catch (InterruptedException ignored) {}
            drainThread = null;
        }
        if (dec != null) {
            try { dec.stop(); } catch (Exception ignored) {}
            try { dec.release(); } catch (Exception ignored) {}
        }
    }

    // ── Minimal SPS parse (profile through crop) for the decoder's nominal size ──

    private static int[] parseSpsDimensions(byte[] sps) {
        try {
            BitReader r = new BitReader(stripEmulationPrevention(sps));
            r.bits(8);                       // NAL header
            int profile = r.bits(8);
            r.bits(16);                      // constraints + level
            r.ue();                          // sps id
            int chromaFormat = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244
                    || profile == 44 || profile == 83 || profile == 86 || profile == 118
                    || profile == 128 || profile == 138 || profile == 139 || profile == 134) {
                chromaFormat = r.ue();
                if (chromaFormat == 3) r.bits(1);
                r.ue(); r.ue();              // bit depths
                r.bits(1);                   // transform bypass
                if (r.bits(1) != 0)          // scaling matrix
                    for (int i = 0; i < (chromaFormat == 3 ? 12 : 8); i++)
                        if (r.bits(1) != 0) skipScalingList(r, i < 6 ? 16 : 64);
            }
            r.ue();                          // log2_max_frame_num
            int pocType = r.ue();
            if (pocType == 0) r.ue();
            else if (pocType == 1) {
                r.bits(1); r.se(); r.se();
                int n = r.ue();
                for (int i = 0; i < n; i++) r.se();
            }
            r.ue();                          // max_num_ref_frames
            r.bits(1);                       // gaps_allowed
            int mbW = r.ue() + 1;
            int mbH = r.ue() + 1;
            int frameMbsOnly = r.bits(1);
            if (frameMbsOnly == 0) r.bits(1);
            r.bits(1);                       // direct_8x8
            int w = mbW * 16, h = mbH * 16 * (2 - frameMbsOnly);
            if (r.bits(1) != 0) {            // frame cropping
                int cl = r.ue(), cr2 = r.ue(), ct = r.ue(), cb2 = r.ue();
                int cx = (chromaFormat == 0) ? 1 : 2;
                int cy = (chromaFormat == 1) ? 2 : (chromaFormat == 0 ? 1 : 1) * (2 - frameMbsOnly);
                w -= (cl + cr2) * cx;
                h -= (ct + cb2) * cy;
            }
            if (w > 0 && h > 0 && w <= 8192 && h <= 8192) return new int[]{w, h};
        } catch (Exception e) {
            Log.w(TAG, "SPS parse: " + e.getMessage());
        }
        return new int[]{1920, 1080};        // decoders take the real size from the csd
    }

    private static void skipScalingList(BitReader r, int size) {
        int last = 8, next = 8;
        for (int i = 0; i < size; i++) {
            if (next != 0) next = (last + r.se() + 256) % 256;
            if (next != 0) last = next;
        }
    }

    private static byte[] stripEmulationPrevention(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (i >= 2 && in[i] == 3 && in[i - 1] == 0 && in[i - 2] == 0) continue;
            out.write(in[i]);
        }
        return out.toByteArray();
    }

    private static final class BitReader {
        private final byte[] data; private int pos;
        BitReader(byte[] d) { data = d; }
        int bits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | ((data[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }
        int ue() {
            int zeros = 0;
            while (bits(1) == 0 && zeros < 32) zeros++;
            return (1 << zeros) - 1 + (zeros > 0 ? bits(zeros) : 0);
        }
        int se() {
            int u = ue();
            return (u % 2 == 0) ? -(u / 2) : (u + 1) / 2;
        }
    }
}
