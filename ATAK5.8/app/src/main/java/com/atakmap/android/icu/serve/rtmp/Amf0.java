package com.atakmap.android.icu.serve.rtmp;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal AMF0 encode/decode — just enough for RTMP command messages
 * (connect / createStream / publish and parsing {@code _result}).
 */
final class Amf0 {

    static final int NUMBER = 0x00, BOOLEAN = 0x01, STRING = 0x02, OBJECT = 0x03,
                     NULL = 0x05, ECMA_ARRAY = 0x08, OBJECT_END = 0x09;

    private Amf0() {}

    // ── Encode ───────────────────────────────────────────────────────────────

    static void writeString(DataOutputStream o, String s) throws IOException {
        o.writeByte(STRING);
        byte[] b = s.getBytes("UTF-8");
        o.writeShort(b.length);
        o.write(b);
    }

    static void writeNumber(DataOutputStream o, double d) throws IOException {
        o.writeByte(NUMBER);
        o.writeDouble(d);
    }

    static void writeBoolean(DataOutputStream o, boolean b) throws IOException {
        o.writeByte(BOOLEAN);
        o.writeByte(b ? 1 : 0);
    }

    static void writeNull(DataOutputStream o) throws IOException {
        o.writeByte(NULL);
    }

    /** Write an anonymous AMF0 object from an ordered string→value map. */
    static void writeObject(DataOutputStream o, Map<String, Object> props) throws IOException {
        o.writeByte(OBJECT);
        for (Map.Entry<String, Object> e : props.entrySet()) {
            byte[] k = e.getKey().getBytes("UTF-8");
            o.writeShort(k.length);
            o.write(k);
            Object v = e.getValue();
            if (v instanceof String)  writeString(o, (String) v);
            else if (v instanceof Number)  writeNumber(o, ((Number) v).doubleValue());
            else if (v instanceof Boolean) writeBoolean(o, (Boolean) v);
            else writeNull(o);
        }
        o.writeShort(0);            // empty key
        o.writeByte(OBJECT_END);
    }

    // ── Decode ───────────────────────────────────────────────────────────────

    /** A cursor over an AMF0 byte payload. */
    static final class Reader {
        final byte[] b; int p;
        Reader(byte[] b, int off) { this.b = b; this.p = off; }
        boolean hasMore() { return p < b.length; }

        /** Read one AMF0 value; objects become {@code Map<String,Object>}. */
        Object read() {
            int marker = b[p++] & 0xFF;
            switch (marker) {
                case NUMBER: {
                    long bits = 0;
                    for (int i = 0; i < 8; i++) bits = (bits << 8) | (b[p++] & 0xFFL);
                    return Double.longBitsToDouble(bits);
                }
                case BOOLEAN: return (b[p++] != 0);
                case STRING:  return readStr();
                case OBJECT: {
                    Map<String, Object> m = new LinkedHashMap<>();
                    while (true) {
                        String key = readStr();
                        if (key.isEmpty() && (b[p] & 0xFF) == OBJECT_END) { p++; break; }
                        m.put(key, read());
                    }
                    return m;
                }
                case ECMA_ARRAY: {
                    p += 4; // associative count
                    Map<String, Object> m = new LinkedHashMap<>();
                    while (true) {
                        String key = readStr();
                        if (key.isEmpty() && (b[p] & 0xFF) == OBJECT_END) { p++; break; }
                        m.put(key, read());
                    }
                    return m;
                }
                case NULL: default: return null;
            }
        }

        private String readStr() {
            int len = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            p += 2;
            String s = new String(b, p, len, java.nio.charset.StandardCharsets.UTF_8);
            p += len;
            return s;
        }
    }

    static byte[] bytes(EncoderBody body) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bos);
        try { body.write(o); } catch (IOException e) { throw new RuntimeException(e); }
        return bos.toByteArray();
    }

    interface EncoderBody { void write(DataOutputStream o) throws IOException; }
}
