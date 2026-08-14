package com.atakmap.android.icu.serve.ts;

import java.io.ByteArrayOutputStream;

/**
 * Minimal MPEG-TS muxer for one H.264 video track and (optionally) one AAC audio track.
 *
 * <p>Produces a standard transport stream — PAT/PMT tables plus PES-packetized access
 * units — as a sequence of 188-byte TS packets handed to a {@link Sink}. It's just enough
 * TS to be playable by ATAK's video player, VLC and ffmpeg from a single {@code udp://}
 * URL; it is not a general-purpose muxer.</p>
 *
 * <p>Conventions (matching the ffmpeg/TAK defaults so generic receivers "just work"):
 * PMT on PID 0x1000, H.264 on PID 0x0100 (stream_type 0x1B), AAC on PID 0x0101
 * (stream_type 0x0F, ADTS-framed). PCR rides the video PID. PTS is the encoder clock in
 * 90 kHz.</p>
 *
 * <p>All access units are fed one NAL at a time by the capture pipeline; SPS/PPS are set
 * once via {@link #setVideoConfig} and prepended to every keyframe (raw H.264 in TS has no
 * out-of-band parameter sets). Not thread-safe — the transport serializes calls.</p>
 */
public final class MpegTsMuxer {

    /** Receives each finished 188-byte TS packet. The transport batches these into datagrams. */
    public interface Sink { void onTsPacket(byte[] packet188); }

    private static final int TS_SIZE   = 188;
    private static final int PID_PAT   = 0x0000;
    private static final int PID_PMT   = 0x1000;
    private static final int PID_VIDEO = 0x0100;
    private static final int PID_AUDIO = 0x0101;
    private static final int STREAM_ID_VIDEO = 0xE0;
    private static final int STREAM_ID_AUDIO = 0xC0;

    private static final byte[] START_CODE = { 0, 0, 0, 1 };
    // Access-unit delimiter (primary_pic_type = 7) — starts each video access unit.
    private static final byte[] AUD = { 0, 0, 0, 1, 0x09, (byte) 0xF0 };

    private final Sink sink;

    private byte[] sps, pps;                 // stored WITHOUT start codes
    private boolean hasAudio;
    private int audioSampleRate = 44100, audioChannels = 1;

    // Continuity counters (4-bit, per PID).
    private int ccPat, ccPmt, ccVideo, ccAudio;
    // Re-emit the tables periodically so a receiver that joins mid-stream can sync quickly.
    private int videoAusSincePsi = Integer.MAX_VALUE / 2;

    // Video and audio arrive on different clocks (encoder surface time vs. audio sample
    // count), so each is normalized to its own zero base. Both then share a ~0 origin, which
    // keeps A/V roughly in sync under the single PCR without a shared capture timestamp.
    private long videoBasePtsUs = Long.MIN_VALUE;
    private long audioBasePtsUs = Long.MIN_VALUE;

    public MpegTsMuxer(Sink sink) { this.sink = sink; }

    /** Provide SPS/PPS (Annex-B, with or without start codes). Required before video flows. */
    public void setVideoConfig(byte[] sps, byte[] pps) {
        this.sps = stripStartCode(sps);
        this.pps = stripStartCode(pps);
    }

    /** Enable the AAC audio track. Call before audio samples; adds an entry to the PMT. */
    public void setAudioConfig(int sampleRate, int channels) {
        this.hasAudio = true;
        this.audioSampleRate = sampleRate > 0 ? sampleRate : 44100;
        this.audioChannels   = channels   > 0 ? channels   : 1;
    }

    /**
     * Mux one H.264 NAL (Annex-B, with start code) as a video access unit.
     * On a keyframe the stored SPS/PPS are prepended so late joiners can decode.
     */
    public void writeVideo(byte[] nalAnnexB, boolean keyFrame, long ptsUs) {
        if (nalAnnexB == null || nalAnnexB.length == 0) return;
        if (videoBasePtsUs == Long.MIN_VALUE) videoBasePtsUs = ptsUs;
        long pts90 = Math.max(0, ptsUs - videoBasePtsUs) * 90L / 1000L;

        // (Re)send PAT/PMT ahead of a keyframe, and at least every ~30 access units.
        if (keyFrame || videoAusSincePsi >= 30) { sendPsi(); videoAusSincePsi = 0; }
        videoAusSincePsi++;

        ByteArrayOutputStream es = new ByteArrayOutputStream();
        es.write(AUD, 0, AUD.length);
        if (keyFrame) {
            if (sps != null) { es.write(START_CODE, 0, START_CODE.length); es.write(sps, 0, sps.length); }
            if (pps != null) { es.write(START_CODE, 0, START_CODE.length); es.write(pps, 0, pps.length); }
        }
        byte[] nal = ensureStartCode(nalAnnexB);
        es.write(nal, 0, nal.length);

        byte[] pes = buildPes(STREAM_ID_VIDEO, es.toByteArray(), pts90, /*unbounded*/ true);
        packetize(PID_VIDEO, pes, /*withPcr*/ true, pts90, isVideoCc());
    }

    /** Mux one raw AAC access unit (no ADTS header) as an audio PES; wraps it in ADTS. */
    public void writeAudio(byte[] aac, long ptsUs) {
        if (!hasAudio || aac == null || aac.length == 0) return;
        if (audioBasePtsUs == Long.MIN_VALUE) audioBasePtsUs = ptsUs;
        long pts90 = Math.max(0, ptsUs - audioBasePtsUs) * 90L / 1000L;
        byte[] adts = addAdtsHeader(aac);
        byte[] pes = buildPes(STREAM_ID_AUDIO, adts, pts90, /*unbounded*/ false);
        packetize(PID_AUDIO, pes, /*withPcr*/ false, pts90, isAudioCc());
    }

    // ── PSI (PAT/PMT) ─────────────────────────────────────────────────────────

    private void sendPsi() {
        sink.onTsPacket(sectionPacket(PID_PAT, buildPat(), nextCc(true)));
        sink.onTsPacket(sectionPacket(PID_PMT, buildPmt(), nextCcPmt()));
    }

    private byte[] buildPat() {
        // program_number 1 → PMT PID 0x1000
        byte[] body = {
                0x00, 0x01,                                  // program_number = 1
                (byte) (0xE0 | ((PID_PMT >> 8) & 0x1F)),     // reserved + PMT PID hi
                (byte) (PID_PMT & 0xFF)                      // PMT PID lo
        };
        return buildSection(0x00, 0x0001, body);
    }

    private byte[] buildPmt() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        // PCR_PID = video PID
        b.write(0xE0 | ((PID_VIDEO >> 8) & 0x1F));
        b.write(PID_VIDEO & 0xFF);
        // program_info_length = 0
        b.write(0xF0);
        b.write(0x00);
        // video ES: stream_type 0x1B (H.264)
        writeEsInfo(b, 0x1B, PID_VIDEO);
        // audio ES: stream_type 0x0F (AAC ADTS)
        if (hasAudio) writeEsInfo(b, 0x0F, PID_AUDIO);
        return buildSection(0x02, 0x0001, b.toByteArray());
    }

    private static void writeEsInfo(ByteArrayOutputStream b, int streamType, int pid) {
        b.write(streamType);
        b.write(0xE0 | ((pid >> 8) & 0x1F));
        b.write(pid & 0xFF);
        b.write(0xF0);   // reserved + ES_info_length hi
        b.write(0x00);   // ES_info_length lo = 0
    }

    /**
     * Wrap a table body in a generic PSI section (table_id + syntax section header + CRC32).
     * Used for both PAT (table_id 0x00) and PMT (table_id 0x02); table_id_extension is the
     * transport_stream_id for PAT and the program_number for PMT.
     */
    private static byte[] buildSection(int tableId, int tableIdExtension, byte[] body) {
        // section_length counts everything after the length field, including the 4-byte CRC:
        // 5 bytes (tsid/ext + version + section_number + last_section_number) + body + 4 (CRC).
        int sectionLength = 5 + body.length + 4;
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(tableId);
        // section_syntax_indicator=1, '0', reserved '11', section_length hi(4)
        s.write(0xB0 | ((sectionLength >> 8) & 0x0F));
        s.write(sectionLength & 0xFF);
        s.write((tableIdExtension >> 8) & 0xFF);
        s.write(tableIdExtension & 0xFF);
        s.write(0xC1);   // reserved '11', version 0, current_next_indicator 1
        s.write(0x00);   // section_number
        s.write(0x00);   // last_section_number
        s.write(body, 0, body.length);
        long crc = crc32(s.toByteArray());
        s.write((int) ((crc >> 24) & 0xFF));
        s.write((int) ((crc >> 16) & 0xFF));
        s.write((int) ((crc >> 8) & 0xFF));
        s.write((int) (crc & 0xFF));
        return s.toByteArray();
    }

    /** A PSI section fits in one TS packet: sync + PUSH + pointer_field(0) + section, padded. */
    private byte[] sectionPacket(int pid, byte[] section, int cc) {
        byte[] pkt = new byte[TS_SIZE];
        pkt[0] = 0x47;
        pkt[1] = (byte) (0x40 | ((pid >> 8) & 0x1F));   // PUSI=1
        pkt[2] = (byte) (pid & 0xFF);
        pkt[3] = (byte) (0x10 | (cc & 0x0F));           // payload only
        pkt[4] = 0x00;                                   // pointer_field
        int len = Math.min(section.length, TS_SIZE - 5);
        System.arraycopy(section, 0, pkt, 5, len);
        for (int i = 5 + len; i < TS_SIZE; i++) pkt[i] = (byte) 0xFF;   // stuffing
        return pkt;
    }

    // ── PES packetization ─────────────────────────────────────────────────────

    private static byte[] buildPes(int streamId, byte[] es, long pts90, boolean unbounded) {
        // PES header: start code + stream_id + length + '10' flags + PTS(5).
        int headerDataLen = 5;                       // PTS only
        int pesPayloadLen = es.length + 3 + headerDataLen;  // 3 optional-header bytes + PTS + ES
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x00); p.write(0x00); p.write(0x01);
        p.write(streamId);
        // PES_packet_length: 0 = unbounded (allowed for video only); else the real length.
        int declaredLen = unbounded ? 0 : (pesPayloadLen & 0xFFFF);
        p.write((declaredLen >> 8) & 0xFF);
        p.write(declaredLen & 0xFF);
        p.write(0x80);   // '10', no scrambling, no priority…
        p.write(0x80);   // PTS_DTS_flags = '10' (PTS only)
        p.write(headerDataLen);
        writePts(p, 0x2, pts90);   // prefix '0010'
        p.write(es, 0, es.length);
        return p.toByteArray();
    }

    /** Encode a 33-bit timestamp with the 4-bit prefix and marker bits (ISO 13818-1). */
    private static void writePts(ByteArrayOutputStream out, int prefix, long ts) {
        out.write((prefix << 4) | (int) (((ts >> 30) & 0x07) << 1) | 0x01);
        out.write((int) ((ts >> 22) & 0xFF));
        out.write((int) (((ts >> 15) & 0x7F) << 1) | 0x01);
        out.write((int) ((ts >> 7) & 0xFF));
        out.write((int) (((ts & 0x7F) << 1) | 0x01));
    }

    /**
     * Chop a PES packet across as many TS packets as needed. The first carries PUSI=1 (and,
     * for video, an adaptation field with the PCR); the payload is right-aligned in the last
     * packet using a stuffing adaptation field so no PES bytes are lost.
     */
    private void packetize(int pid, byte[] pes, boolean withPcr, long pcr90, CcCounter cc) {
        int offset = 0;
        boolean first = true;
        while (offset < pes.length) {
            byte[] pkt = new byte[TS_SIZE];
            pkt[0] = 0x47;
            pkt[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            pkt[2] = (byte) (pid & 0xFF);

            int remaining = pes.length - offset;
            boolean needAdaptation = (first && withPcr) || remaining < (TS_SIZE - 4);
            int payloadStart = 4;

            if (needAdaptation) {
                // Build the adaptation field: optional PCR on the first packet, then stuffing
                // so the remaining PES payload lands exactly at the end of the packet.
                // afContent = the adaptation field's content bytes (flags, plus PCR on the
                // first video packet) BEFORE stuffing. avail = payload bytes that fit with no
                // stuffing: 188 − 4 (header) − 1 (AF-length byte) − afContent.
                int afContentLen = 1;                     // the flags byte
                byte[] pcrBytes = null;
                if (first && withPcr) { pcrBytes = encodePcr(pcr90); afContentLen += pcrBytes.length; }
                int avail    = TS_SIZE - 4 - 1 - afContentLen;
                int payload  = Math.min(remaining, avail);
                int stuffing = avail - payload;           // pad the rest so the packet is full
                int afLen    = afContentLen + stuffing;   // adaptation_field_length (excludes itself)

                pkt[3] = (byte) (0x30 | (cc.next() & 0x0F));   // adaptation + payload
                int i = 4;
                pkt[i++] = (byte) afLen;
                pkt[i++] = (byte) ((first && withPcr) ? 0x10 : 0x00);   // PCR_flag
                if (pcrBytes != null) { System.arraycopy(pcrBytes, 0, pkt, i, pcrBytes.length); i += pcrBytes.length; }
                for (int s = 0; s < stuffing; s++) pkt[i++] = (byte) 0xFF;
                payloadStart = i;
                System.arraycopy(pes, offset, pkt, payloadStart, payload);
                offset += payload;
            } else {
                pkt[3] = (byte) (0x10 | (cc.next() & 0x0F));   // payload only
                int copy = Math.min(remaining, TS_SIZE - payloadStart);
                System.arraycopy(pes, offset, pkt, payloadStart, copy);
                offset += copy;
            }
            sink.onTsPacket(pkt);
            first = false;
        }
    }

    /** PCR as 6 bytes: 33-bit base (90 kHz × 300 ≈ 27 MHz) + 6 reserved + 9-bit extension(0). */
    private static byte[] encodePcr(long pcr90) {
        long base = pcr90 & 0x1FFFFFFFFL;
        byte[] b = new byte[6];
        b[0] = (byte) ((base >> 25) & 0xFF);
        b[1] = (byte) ((base >> 17) & 0xFF);
        b[2] = (byte) ((base >> 9) & 0xFF);
        b[3] = (byte) ((base >> 1) & 0xFF);
        b[4] = (byte) (((base & 0x1) << 7) | 0x7E);   // last base bit + reserved '111111' + ext hi(0)
        b[5] = 0x00;                                    // extension low
        return b;
    }

    // ── ADTS wrapping for AAC ─────────────────────────────────────────────────

    private byte[] addAdtsHeader(byte[] aac) {
        int profile = 2;                       // AAC-LC (AudioObjectType)
        int freqIdx = adtsSampleRateIndex(audioSampleRate);
        int chanCfg = Math.max(1, audioChannels);
        int frameLen = aac.length + 7;
        byte[] out = new byte[frameLen];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xF1;                  // MPEG-4, no CRC
        out[2] = (byte) (((profile - 1) << 6) | ((freqIdx & 0x0F) << 2) | ((chanCfg >> 2) & 0x01));
        out[3] = (byte) (((chanCfg & 0x03) << 6) | ((frameLen >> 11) & 0x03));
        out[4] = (byte) ((frameLen >> 3) & 0xFF);
        out[5] = (byte) (((frameLen & 0x07) << 5) | 0x1F);
        out[6] = (byte) 0xFC;                  // buffer fullness 0x7FF, 1 frame
        System.arraycopy(aac, 0, out, 7, aac.length);
        return out;
    }

    private static int adtsSampleRateIndex(int rate) {
        switch (rate) {
            case 96000: return 0;  case 88200: return 1;  case 64000: return 2;
            case 48000: return 3;  case 44100: return 4;  case 32000: return 5;
            case 24000: return 6;  case 22050: return 7;  case 16000: return 8;
            case 12000: return 9;  case 11025: return 10; case 8000:  return 11;
            default:    return 4;  // 44.1 kHz
        }
    }

    // ── Continuity counters ───────────────────────────────────────────────────

    private interface CcCounter { int next(); }
    private final CcCounter videoCc = () -> ccVideo = (ccVideo + 1) & 0x0F;
    private final CcCounter audioCc = () -> ccAudio = (ccAudio + 1) & 0x0F;
    private CcCounter isVideoCc() { return videoCc; }
    private CcCounter isAudioCc() { return audioCc; }
    private int nextCc(boolean pat) { return pat ? (ccPat = (ccPat + 1) & 0x0F) : 0; }
    private int nextCcPmt() { return ccPmt = (ccPmt + 1) & 0x0F; }

    // ── Bytes / CRC helpers ───────────────────────────────────────────────────

    private static byte[] stripStartCode(byte[] d) {
        if (d == null) return null;
        int off = startCodeLen(d);
        if (off == 0) return d.clone();
        byte[] out = new byte[d.length - off];
        System.arraycopy(d, off, out, 0, out.length);
        return out;
    }

    private static byte[] ensureStartCode(byte[] d) {
        if (startCodeLen(d) > 0) return d;
        byte[] out = new byte[d.length + 4];
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1;
        System.arraycopy(d, 0, out, 4, d.length);
        return out;
    }

    private static int startCodeLen(byte[] d) {
        if (d.length > 4 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) return 4;
        if (d.length > 3 && d[0] == 0 && d[1] == 0 && d[2] == 1) return 3;
        return 0;
    }

    /** MPEG-2 systems CRC32 (poly 0x04C11DB7, init 0xFFFFFFFF, MSB-first, no final XOR). */
    private static long crc32(byte[] data) {
        long crc = 0xFFFFFFFFL;
        for (byte b : data) {
            crc ^= ((long) (b & 0xFF)) << 24;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80000000L) != 0) crc = ((crc << 1) ^ 0x04C11DB7L) & 0xFFFFFFFFL;
                else crc = (crc << 1) & 0xFFFFFFFFL;
            }
        }
        return crc & 0xFFFFFFFFL;
    }
}
