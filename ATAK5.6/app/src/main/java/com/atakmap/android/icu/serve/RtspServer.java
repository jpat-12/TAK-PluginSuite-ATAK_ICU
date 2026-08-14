package com.atakmap.android.icu.serve;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 2 — Minimal RTSP/RTP server that distributes H.264 from the phone camera.
 * Clients connect via RTSP and receive RTP/UDP unicast (RFC 3984 / 6184).
 *
 * <p>Ported from the proven TAK_ICU implementation. Wrapped by
 * {@link OnDeviceRtspTransport} to fit the {@link Transport} abstraction.</p>
 */
public class RtspServer {

    private static final String TAG = "ICU.RtspServer";
    public static final int    PORT        = 8554;
    public static final String STREAM_PATH = "/live";

    private volatile byte[] sps;
    private volatile byte[] pps;

    public void setSps(byte[] sps) { this.sps = sps; }
    public void setPps(byte[] pps) { this.pps = pps; }

    // Optional second track: AAC audio (RFC 3640 mpeg4-generic), advertised in the SDP and
    // sent to each client's audio RTP port. Absent unless setAudioConfig() is called.
    private volatile byte[] audioAsc;
    private volatile int    audioRate = 44100, audioChannels = 1;
    private volatile boolean hasAudio;

    /** Advertise + serve an AAC track alongside the video. Call once the AudioSpecificConfig
     *  is known (from the encoder), before {@link #sendAudioSample}. */
    public void setAudioConfig(byte[] asc, int sampleRate, int channels) {
        if (asc == null || asc.length == 0) return;
        this.audioAsc      = asc.clone();
        this.audioRate     = sampleRate > 0 ? sampleRate : 44100;
        this.audioChannels = channels   > 0 ? channels   : 1;
        this.hasAudio      = true;
    }

    private ServerSocket serverSocket;
    private Thread       acceptThread;
    private volatile boolean running;
    private final CopyOnWriteArrayList<ClientSession> clients = new CopyOnWriteArrayList<>();
    private final int  ssrc    = new Random().nextInt();
    private volatile int seqNum = 0;

    public interface Listener {
        void onClientConnected();
        void onClientDisconnected();
        void onError(String msg);
    }

    private Listener listener;
    public void setListener(Listener l) { this.listener = l; }

    public void start() throws IOException {
        running      = true;
        // SO_REUSEADDR + delayed bind so a restart (e.g. rebuilding transports after a network
        // handoff) can re-listen on :8554 immediately instead of hitting "address in use".
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new java.net.InetSocketAddress(PORT));
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket s        = serverSocket.accept();
                    ClientSession c = new ClientSession(s);
                    clients.add(c);
                    new Thread(c).start();
                } catch (IOException e) {
                    if (running) Log.e(TAG, "Accept error", e);
                }
            }
        }, "ICU-RtspAccept");
        acceptThread.start();
        Log.d(TAG, "RTSP server listening on :" + PORT);
    }

    public void stop() {
        running = false;
        for (ClientSession c : clients) c.close();
        clients.clear();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    public int getClientCount() {
        return (int) clients.stream().filter(c -> c.playing).count();
    }

    public void sendNalUnit(byte[] nal, boolean isKeyFrame, long ptsUs) {
        if (clients.isEmpty()) return;
        long rtpTs = ptsUs * 90L / 1000L;
        int  seq   = seqNum++;
        for (ClientSession c : clients) {
            if (c.playing) c.sendNalUnit(nal, isKeyFrame, rtpTs, seq);
        }
    }

    /** Send one AAC access unit to every playing client that negotiated the audio track. */
    public void sendAudioSample(byte[] aac, long ptsUs) {
        if (!hasAudio || clients.isEmpty()) return;
        long rtpTs = ptsUs * audioRate / 1_000_000L;   // audio RTP clock = sample rate
        for (ClientSession c : clients) {
            if (c.playing) c.sendAudio(aac, rtpTs);
        }
    }

    /** Lower-case hex, used for the AAC {@code config=} SDP parameter. */
    private static String hex(byte[] d) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                           .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    // ── Client session ────────────────────────────────────────────────────────

    private class ClientSession implements Runnable {

        private final Socket socket;
        private volatile boolean active = true;
        volatile boolean playing = false;

        private DatagramSocket udpSocket;
        private InetAddress    clientAddr;
        private int pendingClientPort;                  // client_port from the last Transport header
        private int videoClientPort = -1;
        private int audioClientPort = -1;
        private int serverRtpPort;
        private int audioSeq;
        private final int audioSsrc = new Random().nextInt();
        private final int sessionId = new Random().nextInt(99999999) + 1;

        ClientSession(Socket s) { this.socket = s; }

        @Override
        public void run() {
            try {
                clientAddr = socket.getInetAddress();
                BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter    out = new PrintWriter(socket.getOutputStream(), true);
                StringBuilder  req = new StringBuilder();
                String line;
                while (active && (line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        processRequest(req.toString(), out);
                        req = new StringBuilder();
                    } else {
                        req.append(line).append("\r\n");
                    }
                }
            } catch (IOException e) {
                if (active) Log.d(TAG, "Client gone: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private void processRequest(String req, PrintWriter out) {
            if (req.isEmpty()) return;
            String[] lines  = req.split("\r\n");
            String[] first  = lines[0].split(" ");
            String   method = first[0];
            String   uri    = first.length > 1 ? first[1] : "";
            String   cseq   = "0";
            for (String l : lines) {
                if (l.startsWith("CSeq:"))      cseq = l.substring(5).trim();
                if (l.startsWith("Transport:")) parseTransport(l);
            }
            switch (method) {
                case "OPTIONS":  handleOptions(out, cseq);      break;
                case "DESCRIBE": handleDescribe(out, cseq);     break;
                case "SETUP":    handleSetup(out, cseq, uri);   break;
                case "PLAY":     handlePlay(out, cseq);         break;
                case "TEARDOWN": handleTeardown(out, cseq);     break;
                default:         respond(out, 405, "Method Not Allowed", cseq, ""); break;
            }
        }

        private void parseTransport(String line) {
            Matcher m = Pattern.compile("client_port=(\\d+)").matcher(line);
            if (m.find()) pendingClientPort = Integer.parseInt(m.group(1));
        }

        private void handleOptions(PrintWriter out, String cseq) {
            respond(out, 200, "OK", cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n");
        }

        private void handleDescribe(PrintWriter out, String cseq) {
            String sdp = buildSdp();
            respond(out, 200, "OK", cseq,
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: " + sdp.length() + "\r\n" +
                    "\r\n" + sdp);
        }

        private void handleSetup(PrintWriter out, String cseq, String uri) {
            try {
                if (udpSocket == null) {                 // one send socket serves both tracks
                    udpSocket     = new DatagramSocket();
                    serverRtpPort = udpSocket.getLocalPort();
                }
            } catch (Exception e) {
                respond(out, 500, "Internal Error", cseq, ""); return;
            }
            // Route this SETUP to the right track: trackID=1 is audio (when advertised), else video.
            boolean audioTrack = hasAudio && uri != null && uri.contains("trackID=1");
            if (audioTrack) audioClientPort = pendingClientPort;
            else            videoClientPort = pendingClientPort;
            int clientPort = pendingClientPort;
            respond(out, 200, "OK", cseq,
                    "Transport: RTP/AVP;unicast;client_port=" + clientPort + "-" + (clientPort + 1) +
                    ";server_port=" + serverRtpPort + "-" + (serverRtpPort + 1) + "\r\n" +
                    "Session: " + sessionId + ";timeout=60\r\n");
        }

        private void handlePlay(PrintWriter out, String cseq) {
            playing = true;
            if (listener != null) listener.onClientConnected();
            respond(out, 200, "OK", cseq, "Session: " + sessionId + "\r\n");
        }

        private void handleTeardown(PrintWriter out, String cseq) {
            playing = false;
            respond(out, 200, "OK", cseq, "Session: " + sessionId + "\r\n");
            close();
        }

        private void respond(PrintWriter out, int code, String msg, String cseq, String extra) {
            out.print("RTSP/1.0 " + code + " " + msg + "\r\n");
            out.print("CSeq: " + cseq + "\r\n");
            out.print("Server: ICU-VideoStreamer/1.0\r\n");
            if (!extra.isEmpty()) out.print(extra);
            if (!extra.endsWith("\r\n")) out.print("\r\n");
            out.flush();
        }

        private String buildSdp() {
            String spsB64 = (sps != null) ? Base64.encodeToString(strip(sps), Base64.NO_WRAP) : "";
            String ppsB64 = (pps != null) ? Base64.encodeToString(strip(pps), Base64.NO_WRAP) : "";
            StringBuilder sdp = new StringBuilder()
                   .append("v=0\r\n")
                   .append("o=- 0 0 IN IP4 0.0.0.0\r\n")
                   .append("s=ICU VideoStreamer\r\n")
                   .append("c=IN IP4 0.0.0.0\r\n")
                   .append("t=0 0\r\n")
                   .append("a=control:*\r\n")
                   .append("m=video 0 RTP/AVP 96\r\n")
                   .append("a=rtpmap:96 H264/90000\r\n")
                   .append("a=fmtp:96 profile-level-id=42A01E;packetization-mode=1;")
                   .append("sprop-parameter-sets=").append(spsB64).append(",").append(ppsB64).append("\r\n")
                   .append("a=control:trackID=0\r\n");
            if (hasAudio && audioAsc != null) {
                // RFC 3640 mpeg4-generic (AAC-hbr) — config = the AudioSpecificConfig as hex.
                sdp.append("m=audio 0 RTP/AVP 97\r\n")
                   .append("a=rtpmap:97 mpeg4-generic/").append(audioRate).append("/").append(audioChannels).append("\r\n")
                   .append("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;")
                   .append("sizelength=13;indexlength=3;indexdeltalength=3;config=").append(hex(audioAsc)).append("\r\n")
                   .append("a=control:trackID=1\r\n");
            }
            return sdp.toString();
        }

        // ── RTP ───────────────────────────────────────────────────────────────

        void sendNalUnit(byte[] nal, boolean isKeyFrame, long rtpTs, int seq) {
            if (udpSocket == null || !active) return;
            if (isKeyFrame && sps != null) sendRtp(sps, false, rtpTs, seq);
            if (isKeyFrame && pps != null) sendRtp(pps, false, rtpTs, seq);
            sendRtp(nal, true, rtpTs, seq);
        }

        /** Send one AAC access unit as an RFC 3640 (AAC-hbr) RTP packet on the audio track. */
        void sendAudio(byte[] aac, long rtpTs) {
            if (udpSocket == null || !active || audioClientPort < 0 || aac == null || aac.length == 0) return;
            byte[] payload = new byte[4 + aac.length];
            payload[0] = 0x00; payload[1] = 0x10;               // AU-headers-length = 16 bits
            int hdr = (aac.length & 0x1FFF) << 3;               // 13-bit size, 3-bit index(0)
            payload[2] = (byte) (hdr >> 8); payload[3] = (byte) hdr;
            System.arraycopy(aac, 0, payload, 4, aac.length);

            int seq = audioSeq++ & 0xFFFF;
            byte[] p = new byte[12 + payload.length];
            p[0]  = (byte) 0x80;
            p[1]  = (byte) (0x80 | 97);                          // marker + payload type 97
            p[2]  = (byte) (seq >> 8);          p[3]  = (byte) (seq & 0xFF);
            p[4]  = (byte) (rtpTs >> 24);       p[5]  = (byte) (rtpTs >> 16);
            p[6]  = (byte) (rtpTs >> 8);        p[7]  = (byte) (rtpTs & 0xFF);
            p[8]  = (byte) (audioSsrc >> 24);   p[9]  = (byte) (audioSsrc >> 16);
            p[10] = (byte) (audioSsrc >> 8);    p[11] = (byte) (audioSsrc & 0xFF);
            System.arraycopy(payload, 0, p, 12, payload.length);
            sendUdp(p, audioClientPort);
        }

        private void sendRtp(byte[] data, boolean marker, long rtpTs, int seq) {
            int off = scLen(data);
            int len = data.length - off;
            if (len <= 0) return;
            if (len <= 1400) {
                sendUdp(buildRtpPacket(data, off, len, seq, rtpTs, marker), videoClientPort);
            } else {
                fuA(data, off, len, rtpTs, seq, marker);
            }
        }

        private void fuA(byte[] d, int off, int len, long ts, int seq, boolean lastMark) {
            byte nalType = (byte) (d[off] & 0x1F);
            byte fuInd   = (byte) ((d[off] & 0xE0) | 28);
            int  pos     = off + 1;
            boolean first = true;
            while (pos < off + len) {
                int  chunk = Math.min(off + len - pos, 1398);
                boolean last = (pos + chunk >= off + len);
                byte fuHdr = nalType;
                if (first) fuHdr |= 0x80;
                if (last)  fuHdr |= 0x40;
                byte[] payload = new byte[2 + chunk];
                payload[0] = fuInd; payload[1] = fuHdr;
                System.arraycopy(d, pos, payload, 2, chunk);
                sendUdp(buildRtpPacket(payload, 0, payload.length, seq, ts, last && lastMark), videoClientPort);
                first = false; pos += chunk;
            }
        }

        private byte[] buildRtpPacket(byte[] d, int off, int len, int seq, long ts, boolean marker) {
            byte[] p = new byte[12 + len];
            p[0]  = (byte) 0x80;
            p[1]  = (byte) ((marker ? 0x80 : 0) | 96);
            p[2]  = (byte) (seq >> 8);    p[3]  = (byte) (seq & 0xFF);
            p[4]  = (byte) (ts  >> 24);   p[5]  = (byte) (ts  >> 16);
            p[6]  = (byte) (ts  >>  8);   p[7]  = (byte) (ts  & 0xFF);
            p[8]  = (byte) (ssrc >> 24);  p[9]  = (byte) (ssrc >> 16);
            p[10] = (byte) (ssrc >>  8);  p[11] = (byte) (ssrc & 0xFF);
            System.arraycopy(d, off, p, 12, len);
            return p;
        }

        private void sendUdp(byte[] data, int port) {
            if (port < 0) return;
            try { udpSocket.send(new DatagramPacket(data, data.length, clientAddr, port)); }
            catch (IOException e) { Log.w(TAG, "UDP: " + e.getMessage()); }
        }

        private byte[] strip(byte[] d) {
            int off = scLen(d);
            return Arrays.copyOfRange(d, off, d.length);
        }

        private int scLen(byte[] d) {
            if (d.length > 4 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) return 4;
            if (d.length > 3 && d[0] == 0 && d[1] == 0 && d[2] == 1) return 3;
            return 0;
        }

        void close() {
            active = false; playing = false;
            if (listener != null) listener.onClientDisconnected();
            try { if (socket    != null) socket.close();    } catch (IOException ignored) {}
            try { if (udpSocket != null) udpSocket.close(); } catch (Exception  ignored) {}
            clients.remove(this);
        }

        void cleanup() { close(); }
    }
}
