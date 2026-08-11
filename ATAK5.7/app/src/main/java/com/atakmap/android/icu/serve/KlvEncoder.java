package com.atakmap.android.icu.serve;

import java.io.ByteArrayOutputStream;

/**
 * Builds MISB ST 0601.17 Local Data Set (LDS) packets — platform/sensor telemetry
 * carried in-band alongside the video, the same way {@code WinTAK/Services/KlvService.cs}
 * does for the WinTAK side of this suite. Pure/stateless: one static {@link #build} call
 * per telemetry sample, fed by {@code share.KlvTelemetryEmitter}.
 *
 * <p>Unlike the WinTAK encoder (no IMU on a PC, so pitch/roll are always 0), this one
 * takes real values — the phone has a rotation-vector sensor.</p>
 *
 * <p>Tag reference (MISB ST 0601.17), same 16-tag subset as the WinTAK encoder:</p>
 * <pre>
 *   1  Checksum                   uint16   CRC-16/CCITT (auto)
 *   2  Precision Timestamp        uint64   µs since Unix epoch
 *   5  Platform Heading Angle     uint16   0–360°  → 0–65535
 *   6  Platform Pitch Angle       int16    −20–+20° → −32768–32767
 *   7  Platform Roll Angle        int16    −50–+50° → −32768–32767
 *   9  Image Source Sensor        UTF-8    e.g. "ATAK-ICU"
 *  10  Image Coordinate System    UTF-8    "Geodetic WGS84"
 *  13  Sensor Latitude            int32    ±90°   → ±2 147 483 647
 *  14  Sensor Longitude           int32    ±180°  → ±2 147 483 647
 *  15  Sensor True Altitude       uint16   −900–+19 000 m → 0–65535
 *  16  Sensor Horizontal FOV      uint16   0–180° → 0–65535
 *  17  Sensor Vertical FOV        uint16   0–180° → 0–65535
 *  18  Sensor Relative Azimuth    uint32   0–360° → 0–4 294 967 295
 *  19  Sensor Relative Elevation  int32    ±180°  → ±2 147 483 647
 *  20  Sensor Relative Roll       uint32   0–360° → 0–4 294 967 295
 *  65  UAS LDS Version Number     uint16   17 (ST 0601 rev 17)
 * </pre>
 */
public final class KlvEncoder {

    private KlvEncoder() {}

    // MISB ST 0601 Universal Label
    private static final byte[] ST0601_KEY = {
            0x06, 0x0E, 0x2B, 0x34, 0x02, 0x0B, 0x01, 0x01,
            0x0E, 0x01, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00
    };

    /**
     * @param lat, lon, alt    sensor position (degrees, degrees, meters)
     * @param headingDeg       platform heading, 0-360 (track heading — direction of travel)
     * @param pitchDeg         platform pitch, -20..+20 (real IMU value; WinTAK hardcodes 0)
     * @param rollDeg          platform roll, -50..+50 (real IMU value; WinTAK hardcodes 0)
     * @param hfovDeg, vfovDeg sensor field of view, 0-180
     * @param sensorName       e.g. "ATAK-ICU"
     */
    public static byte[] build(double lat, double lon, double alt,
            double headingDeg, double pitchDeg, double rollDeg,
            double hfovDeg, double vfovDeg, String sensorName) {
        ByteArrayOutputStream ms = new ByteArrayOutputStream();

        // Tag 2: Precision Timestamp — uint64, µs since Unix epoch
        long us = System.currentTimeMillis() * 1000L;
        item(ms, 2, be8(us));

        // Tag 5: Platform Heading Angle — uint16, 0-360° -> 0-65535
        item(ms, 5, be2((int) Math.round(clamp(headingDeg, 0, 360) / 360.0 * 65535.0)));

        // Tag 6: Platform Pitch Angle — int16, -20..+20° -> -32768..32767
        item(ms, 6, be2s(clampS16(clamp(pitchDeg, -20, 20) / 20.0 * 32767.0)));

        // Tag 7: Platform Roll Angle — int16, -50..+50° -> -32768..32767
        item(ms, 7, be2s(clampS16(clamp(rollDeg, -50, 50) / 50.0 * 32767.0)));

        // Tag 9: Image Source Sensor — UTF-8
        item(ms, 9, utf8(sensorName != null ? sensorName : "ATAK-ICU"));

        // Tag 10: Image Coordinate System — UTF-8
        item(ms, 10, utf8("Geodetic WGS84"));

        // Tag 13: Sensor Latitude — int32, ±90° -> ±2 147 483 647
        item(ms, 13, be4((int) Math.round(clamp(lat, -90, 90) / 90.0 * 2_147_483_647.0)));

        // Tag 14: Sensor Longitude — int32, ±180° -> ±2 147 483 647
        item(ms, 14, be4((int) Math.round(clamp(lon, -180, 180) / 180.0 * 2_147_483_647.0)));

        // Tag 15: Sensor True Altitude — uint16, -900..+19000m -> 0-65535
        item(ms, 15, be2((int) Math.round(
                (clamp(alt, -900, 19000) + 900.0) / 19900.0 * 65535.0)));

        // Tag 16: Sensor Horizontal FOV — uint16, 0-180° -> 0-65535
        item(ms, 16, be2((int) Math.round(clamp(hfovDeg, 0, 180) / 180.0 * 65535.0)));

        // Tag 17: Sensor Vertical FOV — uint16, 0-180° -> 0-65535
        item(ms, 17, be2((int) Math.round(clamp(vfovDeg, 0, 180) / 180.0 * 65535.0)));

        // Tag 18: Sensor Relative Azimuth — uint32, 0-360° -> 0-4294967295
        // Camera is bore-sighted with the phone body -> azimuth offset = 0.
        item(ms, 18, be4u(0L));

        // Tag 19: Sensor Relative Elevation — int32, ±180° -> ±2147483647
        item(ms, 19, be4(0));

        // Tag 20: Sensor Relative Roll — uint32, 0-360° -> 0-4294967295
        item(ms, 20, be4u(0L));

        // Tag 65: UAS LDS Version Number — uint16, value = 17 (ST 0601 rev 17)
        item(ms, 65, be2(17));

        return wrapLds(ms.toByteArray());
    }

    // ── LDS wrapper + checksum ────────────────────────────────────────────────

    private static byte[] wrapLds(byte[] items) {
        int payloadLen = items.length + 4; // items + checksum TLV (1+1+2)
        ByteArrayOutputStream ms = new ByteArrayOutputStream();
        ms.write(ST0601_KEY, 0, 16);
        berLen(ms, payloadLen);
        ms.write(items, 0, items.length);
        ms.write(0x01); ms.write(0x02); // tag 1, len 2
        ms.write(0x00); ms.write(0x00); // CRC placeholder

        byte[] pkt = ms.toByteArray();
        int crc = crc16(pkt, pkt.length - 2);
        pkt[pkt.length - 2] = (byte) (crc >> 8);
        pkt[pkt.length - 1] = (byte) (crc & 0xFF);
        return pkt;
    }

    private static void item(ByteArrayOutputStream s, int tag, byte[] value) {
        s.write(tag);
        // BER short-form length (all our values are well under 127 bytes)
        s.write(value.length);
        s.write(value, 0, value.length);
    }

    private static void berLen(ByteArrayOutputStream s, int len) {
        if (len < 128)       { s.write(len); }
        else if (len <= 255) { s.write(0x81); s.write(len); }
        else                 { s.write(0x82); s.write(len >> 8); s.write(len & 0xFF); }
    }

    // ── CRC-16/CCITT ─────────────────────────────────────────────────────────

    private static int crc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++)
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
            crc &= 0xFFFF;
        }
        return crc;
    }

    // ── Big-endian encoders ───────────────────────────────────────────────────

    private static byte[] be8(long v) {
        return new byte[] {
                (byte) (v >> 56), (byte) (v >> 48), (byte) (v >> 40), (byte) (v >> 32),
                (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v };
    }

    private static byte[] be4(int v) {
        return new byte[] { (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v };
    }

    private static byte[] be4u(long v) {
        return new byte[] { (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v };
    }

    private static byte[] be2(int v) {
        return new byte[] { (byte) (v >> 8), (byte) (v & 0xFF) };
    }

    private static byte[] be2s(short v) {
        return new byte[] { (byte) (v >> 8), (byte) (v & 0xFF) };
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static short clampS16(double v) {
        return v > 32767 ? (short) 32767 : v < -32768 ? (short) -32768 : (short) Math.round(v);
    }
}
