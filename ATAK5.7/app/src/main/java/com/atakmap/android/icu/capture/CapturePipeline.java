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
        /** AAC AudioSpecificConfig — arrives before audio samples (video-only sinks ignore). */
        default void onAudioFormat(byte[] asc, int sampleRate, int channels) {}
        /** One raw AAC access unit with its presentation time (microseconds). */
        default void onAudioSample(byte[] aac, long ptsUs) {}
    }

    private final H264Encoder encoder = new H264Encoder();
    private final CameraSource camera = new CameraSource();
    private final AacEncoder   audio  = new AacEncoder();
    private GlRotationPipe      glPipe;   // rotates camera → encoder pixels (null = no rotation)

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

        // Size capture/encode to the sensor's native aspect (at the chosen quality height)
        // so the stream keeps the camera's true proportions — no forced-16:9 crop or stretch.
        int[] cap = CameraSource.chooseNativeCaptureSize(ctx, config.useFrontCamera, config.resolution.h);
        config.captureW = cap[0];
        config.captureH = cap[1];
        Log.d(TAG, "native capture size " + config.captureW + "x" + config.captureH);

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

        // Insert a GL stage so the camera frames are physically rotated into the encoder
        // (raw H.264 over RTSP carries no rotation metadata). The camera renders into the
        // GL pipe's SurfaceTexture; the pipe draws the rotated frame into the real encoder
        // surface. On any GL failure, fall back to feeding the encoder directly (no rotation).
        Surface cameraTarget = encoderSurface;
        glPipe = new GlRotationPipe(encoderSurface, config.captureW, config.captureH,
                config.rotationDegrees, config.useFrontCamera);
        Surface glInput = glPipe.start();
        if (glInput != null) {
            cameraTarget = glInput;
        } else {
            Log.w(TAG, "GL rotation stage failed to start; encoding camera directly (no rotation)");
            glPipe.release();
            glPipe = null;
        }

        camera.start(ctx, config, cameraTarget, preview, message -> {
            running = false;
            stop();
            listener.onError(message);
        });

        // Optional mic audio as a second (AAC) track. A failure here is non-fatal — the
        // video keeps streaming; we just log and continue without audio.
        if (config.streamAudio) {
            boolean ok = audio.start(new AacEncoder.Callback() {
                @Override public void onAudioFormat(byte[] asc, int sampleRate, int channels) {
                    Sink s = sink;
                    if (s != null) s.onAudioFormat(asc, sampleRate, channels);
                }
                @Override public void onAudioSample(byte[] aac, long ptsUs) {
                    Sink s = sink;
                    if (s != null) s.onAudioSample(aac, ptsUs);
                }
                @Override public void onError(String message) {
                    Log.w(TAG, "audio disabled: " + message);
                }
            });
            if (!ok) Log.w(TAG, "audio requested but did not start; continuing video-only");
        }

        running = true;
        listener.onStarted();
    }

    public void stop() {
        running = false;
        audio.stop();
        camera.stop();                 // stop feeding the GL input first…
        if (glPipe != null) { glPipe.release(); glPipe = null; }   // …then tear down GL…
        encoder.stop();                // …then the encoder it fed
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
