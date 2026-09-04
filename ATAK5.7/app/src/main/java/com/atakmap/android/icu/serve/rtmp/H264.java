package com.atakmap.android.icu.serve.rtmp;

import java.io.ByteArrayOutputStream;

/**
 * H.264 helpers for RTMP/FLV muxing: strip Annex-B start codes, convert to AVCC
 * (length-prefixed), and build the AVCDecoderConfigurationRecord from SPS/PPS.
 */
final class H264 {

    private H264() {}

    /** Length of the Annex-B start code at the front of {@code d}, or 0 if none. */
    static int startCodeLen(byte[] d) {
        if (d.length > 4 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) return 4;
        if (d.length > 3 && d[0] == 0 && d[1] == 0 && d[2] == 1) return 3;
        return 0;
    }

    /** Return the NAL payload with any start code removed. */
    static byte[] strip(byte[] d) {
        int off = startCodeLen(d);
        if (off == 0) return d;
        byte[] out = new byte[d.length - off];
        System.arraycopy(d, off, out, 0, out.length);
        return out;
    }

    /** NAL type (lower 5 bits of the first payload byte). */
    static int nalType(byte[] d) {
        int off = startCodeLen(d);
        return d[off] & 0x1F;
    }

    /** One NAL as AVCC: 4-byte big-endian length prefix + payload (start code stripped). */
    static byte[] toAvcc(byte[] annexB) {
        byte[] nal = strip(annexB);
        byte[] out = new byte[4 + nal.length];
        int len = nal.length;
        out[0] = (byte) (len >>> 24);
        out[1] = (byte) (len >>> 16);
        out[2] = (byte) (len >>> 8);
        out[3] = (byte) len;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    /**
     * Build the AVCDecoderConfigurationRecord (the body of the AVC sequence header
     * FLV tag) from SPS + PPS. Both may be Annex-B; start codes are stripped.
     */
    static byte[] decoderConfigRecord(byte[] spsAnnexB, byte[] ppsAnnexB) {
        byte[] sps = strip(spsAnnexB);
        byte[] pps = strip(ppsAnnexB);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1);                 // configurationVersion
        o.write(sps[1]);            // AVCProfileIndication
        o.write(sps[2]);            // profile_compatibility
        o.write(sps[3]);            // AVCLevelIndication
        o.write(0xFF);              // 6 reserved bits (1) + lengthSizeMinusOne (3 → 4-byte)
        o.write(0xE1);              // 3 reserved bits (1) + numOfSequenceParameterSets (1)
        o.write((sps.length >> 8) & 0xFF);
        o.write(sps.length & 0xFF);
        o.write(sps, 0, sps.length);
        o.write(1);                 // numOfPictureParameterSets
        o.write((pps.length >> 8) & 0xFF);
        o.write(pps.length & 0xFF);
        o.write(pps, 0, pps.length);
        return o.toByteArray();
    }
}
