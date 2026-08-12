package com.atakmap.android.icu.serve;

/**
 * Minimal, pure-Java MPEG-TS muxer for H.264 elementary streams — enough to carry
 * the ICU camera feed over UDP multicast in a form ATAK / VLC / ffmpeg can play
 * directly (a raw {@code udp://group:port} MPEG-TS feed).
 *
 * <p>It emits a single program (program 1) with one video elementary stream
 * (stream_type 0x1B, H.264) on PID {@code 0x100}; the PCR rides on that same PID.
 * PAT/PMT are re-sent ahead of every key frame so a late-joining multicast
 * receiver can lock on within one GOP without any out-of-band SDP.</p>
 *
 * <p>Muxed 188-byte transport packets are batched {@value #PKTS_PER_DATAGRAM} to a
 * datagram (1316 bytes — the classic MPEG-TS/UDP payload that stays under a 1500-byte
 * MTU) and handed to a {@link Sink}. The muxer is single-threaded: call
 * {@link #writeFrame} from one thread (the encoder callback).</p>
 */
final class TsMuxer {

    /** Receives a completed UDP datagram of one or more whole TS packets. */
    interface Sink { void onDatagram(byte[] data, int length); }

    private static final int PAT_PID   = 0x0000;
    private static final int PMT_PID   = 0x1000;
    private static final int VIDEO_PID = 0x0100;
    private static final int PCR_PID   = VIDEO_PID;
    private static final int PROGRAM_NUMBER = 1;

    private static final int TS_PKT = 188;
    private static final int PKTS_PER_DATAGRAM = 7;   // 7 * 188 = 1316 bytes

    // Access-unit delimiter NAL (00 00 00 01 09 F0) prefixed to each frame.
    private static final byte[] AUD = {0, 0, 0, 1, 9, (byte) 0xF0};
    private static final byte[] START_CODE = {0, 0, 0, 1};

    private final Sink sink;

    // Continuity counters (mod 16), one per PID.
    private int ccPat, ccPmt, ccVideo;

    private byte[] sps, pps;

    // Datagram batching buffer.
    private final byte[] batch = new byte[TS_PKT * PKTS_PER_DATAGRAM];
    private int batchPkts;

    TsMuxer(Sink sink) { this.sink = sink; }

    /** Update the H.264 codec config (SPS/PPS). Either may be null to leave unchanged. */
    void setConfig(byte[] sps, byte[] pps) {
        if (sps != null) this.sps = stripStartCode(sps);
        if (pps != null) this.pps = stripStartCode(pps);
    }

    /**
     * Mux one encoded frame (a single Annex-B NAL unit, with or without a start code)
     * and flush the resulting datagrams to the sink. Key frames are preceded by a fresh
     * PAT/PMT and an inline SPS/PPS so a receiver can start decoding mid-stream.
     */
    void writeFrame(byte[] nal, boolean keyFrame, long ptsUs) {
        long pts90 = (ptsUs * 9) / 100;   // 90 kHz clock

        if (keyFrame) { writePsi(); }

        byte[] au = buildAccessUnit(nal, keyFrame);
        byte[] pes = buildPes(au, pts90);
        writePes(VIDEO_PID, pes, pts90);

        flushBatch();   // frame boundary → push whatever is buffered (keeps latency low)
    }

    /** Reset counters so a fresh multicast session starts clean. */
    void reset() { ccPat = ccPmt = ccVideo = 0; batchPkts = 0; }

    // ── Access unit / PES ────────────────────────────────────────────────────────

    private byte[] buildAccessUnit(byte[] nal, boolean keyFrame) {
        byte[] body = ensureStartCode(nal);
        int len = AUD.length + body.length;
        if (keyFrame && sps != null && pps != null) {
            len += START_CODE.length + sps.length + START_CODE.length + pps.length;
        }
        byte[] out = new byte[len];
        int p = 0;
        p = append(out, p, AUD);
        if (keyFrame && sps != null && pps != null) {
            p = append(out, p, START_CODE); p = append(out, p, sps);
            p = append(out, p, START_CODE); p = append(out, p, pps);
        }
        append(out, p, body);
        return out;
    }

    /** Wrap an access unit in a PES packet with a PTS (no DTS; the encoder is IPPP). */
    private byte[] buildPes(byte[] au, long pts90) {
        byte[] pes = new byte[9 + 5 + au.length];
        int i = 0;
        pes[i++] = 0x00; pes[i++] = 0x00; pes[i++] = 0x01;   // packet_start_code_prefix
        pes[i++] = (byte) 0xE0;                              // stream_id = video
        pes[i++] = 0x00; pes[i++] = 0x00;                    // PES_packet_length = 0 (unbounded)
        pes[i++] = (byte) 0x80;                              // '10', no scrambling/priority
        pes[i++] = (byte) 0x80;                              // PTS present, no DTS
        pes[i++] = 0x05;                                     // PES_header_data_length
        writePts(pes, i, pts90);                             // 5-byte PTS ('0010' prefix)
        i += 5;
        System.arraycopy(au, 0, pes, i, au.length);
        return pes;
    }

    private static void writePts(byte[] b, int off, long pts) {
        b[off]     = (byte) (0x20 | ((pts >> 30) & 0x07) << 1 | 0x01);
        b[off + 1] = (byte) ((pts >> 22) & 0xFF);
        b[off + 2] = (byte) (((pts >> 15) & 0x7F) << 1 | 0x01);
        b[off + 3] = (byte) ((pts >> 7) & 0xFF);
        b[off + 4] = (byte) ((pts & 0x7F) << 1 | 0x01);
    }

    // ── TS packetisation ─────────────────────────────────────────────────────────

    /** Fragment a PES payload across TS packets, PCR + PUSI on the first one. */
    private void writePes(int pid, byte[] payload, long pcr90) {
        int pos = 0;
        boolean first = true;
        while (pos < payload.length) {
            byte[] pkt = new byte[TS_PKT];
            pkt[0] = 0x47;
            pkt[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            pkt[2] = (byte) (pid & 0xFF);

            int left = payload.length - pos;
            boolean usePcr = first;
            boolean af = usePcr || left < (TS_PKT - 4);

            int written;
            if (!af) {
                pkt[3] = (byte) (0x10 | (ccVideo & 0x0F));      // payload only
                written = TS_PKT - 4;
                System.arraycopy(payload, pos, pkt, 4, written);
            } else {
                pkt[3] = (byte) (0x30 | (ccVideo & 0x0F));      // adaptation + payload
                int afBody = 1 + (usePcr ? 6 : 0);              // flags (+ PCR)
                int capacity = TS_PKT - 5 - afBody;
                written = Math.min(left, capacity);
                int stuffing = capacity - written;
                int idx = 4;
                pkt[idx++] = (byte) (afBody + stuffing);        // adaptation_field_length
                pkt[idx++] = (byte) (usePcr ? 0x10 : 0x00);     // PCR_flag
                if (usePcr) {
                    long base = pcr90 & 0x1FFFFFFFFL;            // 33-bit PCR base
                    pkt[idx++] = (byte) (base >> 25);
                    pkt[idx++] = (byte) (base >> 17);
                    pkt[idx++] = (byte) (base >> 9);
                    pkt[idx++] = (byte) (base >> 1);
                    pkt[idx++] = (byte) (((base & 0x1) << 7) | 0x7E); // marker bits, ext=0
                    pkt[idx++] = 0x00;
                }
                for (int s = 0; s < stuffing; s++) pkt[idx++] = (byte) 0xFF;
                System.arraycopy(payload, pos, pkt, idx, written);
            }

            pos += written;
            first = false;
            ccVideo = (ccVideo + 1) & 0x0F;
            emit(pkt);
        }
    }

    /** Emit the PAT and PMT, each a single stuffed TS packet. */
    private void writePsi() {
        emit(psiPacket(PAT_PID, buildPat(), ccPat));
        ccPat = (ccPat + 1) & 0x0F;
        emit(psiPacket(PMT_PID, buildPmt(), ccPmt));
        ccPmt = (ccPmt + 1) & 0x0F;
    }

    /** Wrap a PSI section (PAT/PMT) in one TS packet: PUSI, pointer_field, section, stuffing. */
    private static byte[] psiPacket(int pid, byte[] section, int cc) {
        byte[] pkt = new byte[TS_PKT];
        pkt[0] = 0x47;
        pkt[1] = (byte) (0x40 | ((pid >> 8) & 0x1F));   // PUSI
        pkt[2] = (byte) (pid & 0xFF);
        pkt[3] = (byte) (0x10 | (cc & 0x0F));           // payload only
        pkt[4] = 0x00;                                   // pointer_field
        System.arraycopy(section, 0, pkt, 5, section.length);
        for (int i = 5 + section.length; i < TS_PKT; i++) pkt[i] = (byte) 0xFF;
        return pkt;
    }

    private static byte[] buildPat() {
        byte[] s = new byte[12 + 4];
        int i = 0;
        s[i++] = 0x00;                          // table_id (PAT)
        s[i++] = (byte) 0xB0; s[i++] = 0x0D;    // section_syntax + length (13)
        s[i++] = 0x00; s[i++] = 0x01;           // transport_stream_id
        s[i++] = (byte) 0xC1;                   // version 0, current
        s[i++] = 0x00; s[i++] = 0x00;           // section_number / last
        s[i++] = (byte) ((PROGRAM_NUMBER >> 8) & 0xFF);
        s[i++] = (byte) (PROGRAM_NUMBER & 0xFF);
        s[i++] = (byte) (0xE0 | ((PMT_PID >> 8) & 0x1F));
        s[i++] = (byte) (PMT_PID & 0xFF);
        putCrc(s, i);
        return s;
    }

    private static byte[] buildPmt() {
        byte[] s = new byte[17 + 4];
        int i = 0;
        s[i++] = 0x02;                          // table_id (PMT)
        s[i++] = (byte) 0xB0; s[i++] = 0x12;    // section_syntax + length (18)
        s[i++] = (byte) ((PROGRAM_NUMBER >> 8) & 0xFF);
        s[i++] = (byte) (PROGRAM_NUMBER & 0xFF);
        s[i++] = (byte) 0xC1;                   // version 0, current
        s[i++] = 0x00; s[i++] = 0x00;           // section_number / last
        s[i++] = (byte) (0xE0 | ((PCR_PID >> 8) & 0x1F));
        s[i++] = (byte) (PCR_PID & 0xFF);
        s[i++] = (byte) 0xF0; s[i++] = 0x00;    // program_info_length = 0
        s[i++] = 0x1B;                          // stream_type H.264
        s[i++] = (byte) (0xE0 | ((VIDEO_PID >> 8) & 0x1F));
        s[i++] = (byte) (VIDEO_PID & 0xFF);
        s[i++] = (byte) 0xF0; s[i++] = 0x00;    // ES_info_length = 0
        putCrc(s, i);
        return s;
    }

    /** MPEG (Ethernet) CRC-32, written big-endian into the 4 bytes at {@code off}. */
    private static void putCrc(byte[] s, int off) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < off; i++) {
            crc ^= (s[i] & 0xFF) << 24;
            for (int b = 0; b < 8; b++)
                crc = ((crc & 0x80000000) != 0) ? (crc << 1) ^ 0x04C11DB7 : (crc << 1);
        }
        s[off]     = (byte) (crc >> 24);
        s[off + 1] = (byte) (crc >> 16);
        s[off + 2] = (byte) (crc >> 8);
        s[off + 3] = (byte) crc;
    }

    // ── Datagram batching ────────────────────────────────────────────────────────

    private void emit(byte[] pkt) {
        System.arraycopy(pkt, 0, batch, batchPkts * TS_PKT, TS_PKT);
        if (++batchPkts == PKTS_PER_DATAGRAM) flushBatch();
    }

    private void flushBatch() {
        if (batchPkts == 0) return;
        sink.onDatagram(batch, batchPkts * TS_PKT);
        batchPkts = 0;
    }

    // ── Byte helpers ─────────────────────────────────────────────────────────────

    private static int append(byte[] dst, int at, byte[] src) {
        System.arraycopy(src, 0, dst, at, src.length);
        return at + src.length;
    }

    private static int startCodeLen(byte[] d) {
        if (d.length > 3 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) return 4;
        if (d.length > 2 && d[0] == 0 && d[1] == 0 && d[2] == 1) return 3;
        return 0;
    }

    private static byte[] stripStartCode(byte[] d) {
        int off = startCodeLen(d);
        if (off == 0) return d;
        byte[] out = new byte[d.length - off];
        System.arraycopy(d, off, out, 0, out.length);
        return out;
    }

    /** Return {@code d} unchanged if it already starts with an Annex-B start code, else prefix one. */
    private static byte[] ensureStartCode(byte[] d) {
        if (startCodeLen(d) != 0) return d;
        byte[] out = new byte[START_CODE.length + d.length];
        System.arraycopy(START_CODE, 0, out, 0, START_CODE.length);
        System.arraycopy(d, 0, out, START_CODE.length, d.length);
        return out;
    }
}
