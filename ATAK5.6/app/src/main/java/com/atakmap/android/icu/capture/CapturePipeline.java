package com.atakmap.android.icu.capture;

import android.content.Context;
import android.view.Surface;

import com.atakmap.coremap.log.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * PHASE 1 — orchestrates {@link H264Encoder} + {@link CameraSource} as one unit.
 *
 * <p>Owns start/stop ordering (encoder first so its input Surface exists, then the
 * camera), tracks basic stats, and surfaces errors on the caller's thread via a
 * {@link Listener}. Phase 2 will let {@code serve/RtspServer} subscribe to the same
 * NAL stream this pipeline receives.</p>
 */
public class CapturePipeline {

    private static final String TAG = "ICU.CapturePipeline";

    public interface Listener {
        void onStarted();
        void onError(String message);
        /** Periodic-ish callback as encoded frames flow — useful for a Phase 1 sanity readout. */
        void onFrame(int totalNalUnits);
    }

    /**
     * Consumer of the encoded stream (implemented by the serve layer's TransportManager).
     * SPS/PPS arrive via {@link #onFormat} before NAL units; each encoded NAL via {@link #onNal}.
     */
    public interface Sink {
        void onFormat(byte[] sps, byte[] pps);
        void onNal(byte[] data, boolean keyFrame, long ptsUs);
    }

    private final H264Encoder encoder = new H264Encoder();
    private final CameraSource camera = new CameraSource();

    private final AtomicInteger nalCount = new AtomicInteger();
    private volatile boolean running;
    private volatile byte[] sps;
    private volatile byte[] pps;
    private volatile Sink sink;

    public boolean isRunning() { return running; }

    /** Attach the serve-layer sink before {@link #start}. */
    public void setSink(Sink sink) { this.sink = sink; }

    /**
     * Start capture + encode. Preview may be null (encoder-only).
     * The caller must already hold {@code android.permission.CAMERA}.
     */
    public void start(Context ctx, EncoderConfig config, Surface preview, Listener listener) {
        if (running) return;
        nalCount.set(0);

        Surface encoderSurface = encoder.start(config, new H264Encoder.Callback() {
            @Override public void onSpsReady(byte[] s) {
                sps = s; Log.d(TAG, "SPS ready (" + s.length + "B)");
                pushFormat();
            }
            @Override public void onPpsReady(byte[] p) {
                pps = p; Log.d(TAG, "PPS ready (" + p.length + "B)");
                pushFormat();
            }
            @Override public void onNalUnit(byte[] data, boolean isKeyFrame, long ptsUs) {
                Sink s = sink;
                if (s != null) s.onNal(data, isKeyFrame, ptsUs);
                listener.onFrame(nalCount.incrementAndGet());
            }
            @Override public void onError(String message) {
                running = false;
                listener.onError(message);
            }
        });

        if (encoderSurface == null) {
            // encoder already reported the error via its callback
            return;
        }

        camera.start(ctx, config, encoderSurface, preview, message -> {
            running = false;
            stop();
            listener.onError(message);
        });

        running = true;
        listener.onStarted();
    }

    public void stop() {
        running = false;
        camera.stop();
        encoder.stop();
    }

    /**
     * Attach or detach the local preview target while capture keeps running — used when
     * the drop-down pane's TextureView is destroyed/recreated (dropdown closed/reopened)
     * so broadcasting isn't interrupted. No-op if capture isn't running.
     */
    public void setPreviewSurface(Surface preview) {
        if (running) camera.setPreviewSurface(preview);
    }

    /** Forward codec config to the sink once both SPS and PPS are known. */
    private void pushFormat() {
        Sink s = sink;
        if (s != null && sps != null && pps != null) s.onFormat(sps, pps);
    }

    public byte[] getSps() { return sps; }
    public byte[] getPps() { return pps; }

    public CameraSource getCamera() { return camera; }

    /**
     * Capture a single JPEG still from the live camera — works with the preview attached
     * or not (snapshot from the radial with the panel closed). No-op error if not running.
     */
    public void captureStill(int jpegOrientation, CameraSource.StillCallback cb) {
        if (!running) { cb.onStillError("not broadcasting"); return; }
        camera.captureStill(jpegOrientation, cb);
    }
}
