package com.atakmap.android.icu.serve.rtmp;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A compact, dependency-free RTMP publisher (client → server "publish").
 *
 * <p>Implements the simple handshake, the chunk stream protocol (read + write), the
 * AMF0 command sequence (connect → createStream → publish), and FLV/AVC video muxing.
 * Feed it {@link #setFormat} (SPS/PPS) then {@link #sendVideo} per NAL.</p>
 *
 * <p>Designed to publish to a generic RTMP ingest.
 * Not yet validated against a live server — see ARCHITECTURE.md §7b.</p>
 */
public class RtmpPublisher {

    private static final String TAG = "ICU.Rtmp";

    // Chunk stream ids
    private static final int CSID_CONTROL = 2;
    private static final int CSID_CMD     = 3;
    private static final int CSID_VIDEO   = 6;

    // Message type ids
    private static final int TYPE_SET_CHUNK_SIZE = 1;
    private static final int TYPE_ACK            = 3;
    private static final int TYPE_USER_CONTROL   = 4;
    private static final int TYPE_WINDOW_ACK     = 5;
    private static final int TYPE_SET_PEER_BW    = 6;
    private static final int TYPE_AMF0_CMD       = 20;
    private static final int TYPE_VIDEO          = 9;

    private final String host;
    private final int    port;
    private final String app;
    private final String publishName;
    private final String tcUrl;

    private Socket socket;
    private DataInputStream  in;
    private DataOutputStream out;
    private Thread readerThread;
    private volatile boolean running;

    private int outChunkSize = 4096;
    private volatile int inChunkSize = 128;
    private volatile double streamId = 1;

    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private final CountDownLatch streamLatch  = new CountDownLatch(1);

    private volatile boolean ready;
    private volatile boolean seqHeaderSent;
    private volatile boolean sawKeyframe;
    private long baseTsUs = -1;
    private byte[] sps, pps;

    public RtmpPublisher(String host, int port, String app, String publishName) {
        this.host = host;
        this.port = port;
        this.app  = app;
        this.publishName = publishName;
        this.tcUrl = "rtmp://" + host + ":" + port + "/" + app;
    }

    public boolean isReady() { return ready; }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Blocking: connect, handshake, and negotiate up to the publish command. */
    public void connect() throws IOException, InterruptedException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        handshake();
        running = true;
        readerThread = new Thread(this::readLoop, "ICU-RtmpReader");
        readerThread.start();

        sendSetChunkSize(outChunkSize);
        sendConnect();
        if (!connectLatch.await(5, TimeUnit.SECONDS))
            throw new IOException("RTMP connect timed out");

        sendCreateStream();
        if (!streamLatch.await(5, TimeUnit.SECONDS))
            throw new IOException("RTMP createStream timed out");

        sendPublish();
        ready = true;
        Log.d(TAG, "RTMP ready, streamId=" + streamId);
    }

    public void close() {
        running = false;
        ready = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    // ── Video in ─────────────────────────────────────────────────────────────

    public void setFormat(byte[] sps, byte[] pps) {
        this.sps = sps;
        this.pps = pps;
        maybeSendSeqHeader();
    }

    public void sendVideo(byte[] annexBNal, boolean keyFrame, long ptsUs) {
        if (!ready) return;
        if (!seqHeaderSent) { maybeSendSeqHeader(); if (!seqHeaderSent) return; }
        if (!sawKeyframe && !keyFrame) return;   // wait for a keyframe to start
        sawKeyframe = true;

        if (baseTsUs < 0) baseTsUs = ptsUs;
        int tsMs = (int) ((ptsUs - baseTsUs) / 1000L);

        byte[] avcc = H264.toAvcc(annexBNal);
        ByteArrayOutputStream body = new ByteArrayOutputStream(avcc.length + 5);
        body.write((keyFrame ? 0x17 : 0x27));  // frameType<<4 | codecId(7=AVC)
        body.write(0x01);                       // AVCPacketType: 1 = NALU
        body.write(0); body.write(0); body.write(0); // composition time = 0
        body.write(avcc, 0, avcc.length);
        try {
            writeMessage(CSID_VIDEO, TYPE_VIDEO, streamId, tsMs, body.toByteArray());
        } catch (IOException e) {
            Log.w(TAG, "sendVideo: " + e.getMessage());
            close();
        }
    }

    private void maybeSendSeqHeader() {
        if (seqHeaderSent || !ready || sps == null || pps == null) return;
        byte[] cfg = H264.decoderConfigRecord(sps, pps);
        ByteArrayOutputStream body = new ByteArrayOutputStream(cfg.length + 5);
        body.write(0x17);                       // keyframe | AVC
        body.write(0x00);                       // AVCPacketType: 0 = sequence header
        body.write(0); body.write(0); body.write(0);
        body.write(cfg, 0, cfg.length);
        try {
            writeMessage(CSID_VIDEO, TYPE_VIDEO, streamId, 0, body.toByteArray());
            seqHeaderSent = true;
            Log.d(TAG, "AVC sequence header sent");
        } catch (IOException e) {
            Log.w(TAG, "seqHeader: " + e.getMessage());
        }
    }

    // ── Handshake ────────────────────────────────────────────────────────────

    private void handshake() throws IOException {
        byte[] c1 = new byte[1536];
        new Random().nextBytes(c1);
        for (int i = 0; i < 8; i++) c1[i] = 0;          // time + zero
        out.writeByte(0x03);                             // C0
        out.write(c1);                                   // C1
        out.flush();

        int s0 = in.read();                              // S0
        if (s0 != 0x03) throw new IOException("Bad RTMP version: " + s0);
        byte[] s1 = new byte[1536]; in.readFully(s1);    // S1
        byte[] s2 = new byte[1536]; in.readFully(s2);    // S2
        out.write(s1);                                   // C2 = echo of S1
        out.flush();
    }

    // ── Commands (out) ───────────────────────────────────────────────────────

    private void sendConnect() throws IOException {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("app", app);
        obj.put("type", "nonprivate");
        obj.put("flashVer", "FMLE/3.0 (compatible; ICU)");
        obj.put("tcUrl", tcUrl);
        byte[] payload = Amf0.bytes(o -> {
            Amf0.writeString(o, "connect");
            Amf0.writeNumber(o, 1);   // transaction id
            Amf0.writeObject(o, obj);
        });
        writeMessage(CSID_CMD, TYPE_AMF0_CMD, 0, 0, payload);
    }

    private void sendCreateStream() throws IOException {
        byte[] payload = Amf0.bytes(o -> {
            Amf0.writeString(o, "createStream");
            Amf0.writeNumber(o, 2);   // transaction id
            Amf0.writeNull(o);
        });
        writeMessage(CSID_CMD, TYPE_AMF0_CMD, 0, 0, payload);
    }

    private void sendPublish() throws IOException {
        byte[] payload = Amf0.bytes(o -> {
            Amf0.writeString(o, "publish");
            Amf0.writeNumber(o, 0);
            Amf0.writeNull(o);
            Amf0.writeString(o, publishName);
            Amf0.writeString(o, "live");
        });
        writeMessage(CSID_CMD, TYPE_AMF0_CMD, streamId, 0, payload);
    }

    private void sendSetChunkSize(int size) throws IOException {
        byte[] p = new byte[]{
                (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size};
        writeMessage(CSID_CONTROL, TYPE_SET_CHUNK_SIZE, 0, 0, p);
    }

    private void sendWindowAckSize(int size) throws IOException {
        byte[] p = new byte[]{
                (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size};
        writeMessage(CSID_CONTROL, TYPE_WINDOW_ACK, 0, 0, p);
    }

    // ── Chunk writer ─────────────────────────────────────────────────────────

    private synchronized void writeMessage(int csid, int typeId, double msgStreamId,
                                           int timestamp, byte[] payload) throws IOException {
        boolean ext = timestamp >= 0xFFFFFF;
        int tsField = ext ? 0xFFFFFF : timestamp;

        // Basic header (fmt 0) + message header (11 bytes)
        out.writeByte(csid & 0x3F);
        out.writeByte((tsField >>> 16) & 0xFF);
        out.writeByte((tsField >>> 8) & 0xFF);
        out.writeByte(tsField & 0xFF);
        out.writeByte((payload.length >>> 16) & 0xFF);
        out.writeByte((payload.length >>> 8) & 0xFF);
        out.writeByte(payload.length & 0xFF);
        out.writeByte(typeId);
        int sid = (int) msgStreamId;
        out.writeByte(sid & 0xFF);            // message stream id — little-endian
        out.writeByte((sid >>> 8) & 0xFF);
        out.writeByte((sid >>> 16) & 0xFF);
        out.writeByte((sid >>> 24) & 0xFF);
        if (ext) out.writeInt(timestamp);

        int off = 0;
        while (off < payload.length) {
            int n = Math.min(outChunkSize, payload.length - off);
            out.write(payload, off, n);
            off += n;
            if (off < payload.length) {
                out.writeByte(0xC0 | (csid & 0x3F));    // fmt 3 continuation
                if (ext) out.writeInt(timestamp);
            }
        }
        out.flush();
    }

    // ── Chunk reader ─────────────────────────────────────────────────────────

    private static final class ChunkState {
        int timestamp, length, typeId, streamId;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
    }

    private final Map<Integer, ChunkState> chunkStreams = new HashMap<>();

    private void readLoop() {
        try {
            while (running) {
                Message m = readMessage();
                if (m != null) dispatch(m);
            }
        } catch (IOException e) {
            if (running) Log.d(TAG, "reader ended: " + e.getMessage());
        }
    }

    private static final class Message {
        final int typeId; final byte[] payload;
        Message(int t, byte[] p) { typeId = t; payload = p; }
    }

    private Message readMessage() throws IOException {
        while (true) {
            int b0 = in.readUnsignedByte();
            int fmt = (b0 >>> 6) & 0x3;
            int csid = b0 & 0x3F;
            if (csid == 0)      csid = 64 + in.readUnsignedByte();
            else if (csid == 1) { int a = in.readUnsignedByte(); int b = in.readUnsignedByte(); csid = 64 + a + b * 256; }

            ChunkState cs = chunkStreams.get(csid);
            if (cs == null) { cs = new ChunkState(); chunkStreams.put(csid, cs); }

            int tsField = cs.timestamp;
            if (fmt <= 2) tsField = read24();
            if (fmt <= 1) { cs.length = read24(); cs.typeId = in.readUnsignedByte(); }
            if (fmt == 0) {
                int sid = in.readUnsignedByte() | (in.readUnsignedByte() << 8)
                        | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 24);
                cs.streamId = sid;
            }
            if (tsField == 0xFFFFFF) tsField = in.readInt();
            cs.timestamp = tsField;

            int remaining = cs.length - cs.buf.size();
            int n = Math.min(remaining, inChunkSize);
            byte[] chunk = new byte[n];
            in.readFully(chunk);
            cs.buf.write(chunk);

            if (cs.buf.size() >= cs.length) {
                byte[] payload = cs.buf.toByteArray();
                int type = cs.typeId;
                cs.buf.reset();
                return new Message(type, payload);
            }
            // else: continuation chunk of the same message — keep reading
        }
    }

    private int read24() throws IOException {
        return (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
    }

    private void dispatch(Message m) throws IOException {
        switch (m.typeId) {
            case TYPE_SET_CHUNK_SIZE:
                if (m.payload.length >= 4)
                    inChunkSize = ((m.payload[0] & 0x7F) << 24) | ((m.payload[1] & 0xFF) << 16)
                                | ((m.payload[2] & 0xFF) << 8) | (m.payload[3] & 0xFF);
                break;
            case TYPE_SET_PEER_BW:
                sendWindowAckSize(2_500_000);   // acknowledge peer bandwidth
                break;
            case TYPE_WINDOW_ACK:
            case TYPE_ACK:
            case TYPE_USER_CONTROL:
                break;                          // no action needed for publishing
            case TYPE_AMF0_CMD:
                handleCommand(m.payload);
                break;
            default:
                break;
        }
    }

    private void handleCommand(byte[] payload) {
        try {
            Amf0.Reader r = new Amf0.Reader(payload, 0);
            Object name = r.read();
            Object txn  = r.read();
            if (!(name instanceof String)) return;
            String cmd = (String) name;
            double t = (txn instanceof Number) ? ((Number) txn).doubleValue() : -1;

            if ("_result".equals(cmd)) {
                if (t == 1) {
                    connectLatch.countDown();
                } else if (t == 2) {
                    r.read();                    // command object (null)
                    Object sid = r.hasMore() ? r.read() : null;
                    if (sid instanceof Number) streamId = ((Number) sid).doubleValue();
                    streamLatch.countDown();
                }
            } else if ("_error".equals(cmd)) {
                Log.w(TAG, "RTMP _error (txn " + t + ")");
                // release any waiters so connect() fails fast rather than timing out
                connectLatch.countDown();
                streamLatch.countDown();
            } else if ("onStatus".equals(cmd)) {
                Log.d(TAG, "RTMP onStatus");
            }
        } catch (Exception e) {
            Log.w(TAG, "handleCommand: " + e.getMessage());
        }
    }
}
