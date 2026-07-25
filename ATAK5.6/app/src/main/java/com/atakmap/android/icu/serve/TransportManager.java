package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.CapturePipeline;
import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PHASE 2 — owns the active {@link Transport}s and fans the encoded stream out to all
 * of them. Implements {@link CapturePipeline.Sink}, so the capture pipeline feeds SPS/PPS
 * and NAL units straight through to every enabled transport.
 *
 * <p>Register whichever transports the operator has enabled (on-device RTSP by default;
 * RTMP-push / SRT when a media server is configured), then {@link #startAll}.</p>
 */
public class TransportManager implements CapturePipeline.Sink {

    private static final String TAG = "ICU.TransportManager";

    private final CopyOnWriteArrayList<Transport> transports = new CopyOnWriteArrayList<>();

    public interface ErrorListener { void onTransportError(String name, String message); }
    private ErrorListener errorListener;
    public void setErrorListener(ErrorListener l) { this.errorListener = l; }

    public void register(Transport t) { transports.add(t); }
    public void clear() { transports.clear(); }

    /** Start every registered transport; a failure in one doesn't stop the others. */
    public void startAll(EncoderConfig config) {
        for (Transport t : transports) {
            try {
                t.start(config);
                Log.d(TAG, "started transport: " + t.name());
            } catch (Exception e) {
                Log.w(TAG, "transport failed: " + t.name() + " — " + e.getMessage());
                transports.remove(t);
                if (errorListener != null) errorListener.onTransportError(t.name(), e.getMessage());
            }
        }
    }

    public void stopAll() {
        for (Transport t : transports) {
            try { t.stop(); } catch (Exception ignored) {}
        }
    }

    // ── CapturePipeline.Sink ────────────────────────────────────────────────────

    @Override
    public void onFormat(byte[] sps, byte[] pps) {
        for (Transport t : transports) t.onFormat(sps, pps);
    }

    @Override
    public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        for (Transport t : transports) t.onNal(data, keyFrame, ptsUs);
    }

    // ── Status ──────────────────────────────────────────────────────────────────

    /** De-duplicated list of every viewable endpoint across all transports. */
    public List<StreamEndpoint> allEndpoints() {
        List<StreamEndpoint> out = new ArrayList<>();
        for (Transport t : transports) out.addAll(t.endpoints());
        return out;
    }

    /** One status line per transport (skips nulls). */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        for (Transport t : transports) {
            String s = t.statusLine();
            if (s != null) out.add(s);
        }
        return out;
    }

    public int totalViewers() {
        int sum = 0;
        for (Transport t : transports) {
            int v = t.viewerCount();
            if (v > 0) sum += v;
        }
        return sum;
    }
}
