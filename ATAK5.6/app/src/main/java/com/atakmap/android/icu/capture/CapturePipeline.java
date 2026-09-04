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
        /**
         * The capture source finished opening its device. Only the USB/UVC path fires this
         * (Camera2 is already open by the time {@link #onStarted} runs); it can land well
         * after {@code onStarted} because enumeration and the OS permission prompt are async.
         * Frames are <b>not</b> guaranteed to follow — a UVC device can accept startPreview
         * and then deliver nothing — so it marks where a no-feed timeout should start.
         */
        default void onSourceOpened() {}
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
    private final UsbCameraSource usbCamera = new UsbCameraSource();
    private final AacEncoder   audio  = new AacEncoder();
    private GlRotationPipe      glPipe;   // rotates camera → encoder pixels (null = no rotation)

    private final AtomicInteger nalCount = new AtomicInteger();
    private volatile boolean running;
    private volatile boolean usingUsbCamera;
    private volatile byte[] sps;
    private volatile byte[] pps;
    private volatile byte[] audioAsc;
    private volatile int     audioSampleRate;
    private volatile int     audioChannels;
    private volatile Sink sink;
    private volatile Sink recorder;

    public boolean isRunning() { return running; }

    /** Attach the serve-layer sink before {@link #start}. */
    public void setSink(Sink sink) { this.sink = sink; }

    /**
     * Attach or detach a second consumer of the encoded stream — the local MP4 recorder
     * ({@link Mp4Recorder}), which the operator toggles independently of the broadcast.
     * Kept separate from {@link #setSink} so recording can start and stop mid-broadcast
     * without touching the transports the serve layer owns. Pass null to detach.
     */
    public void setRecorder(Sink recorder) { this.recorder = recorder; }

    /**
     * Populate {@code config.captureW/H} — the landscape capture/encode size — for the
     * currently configured camera. USB/UVC cameras aren't queryable via Camera2
     * characteristics, so they get the same 16:9-at-target-height fallback
     * {@link CameraSource#chooseNativeCaptureSize} itself uses when no camera info is
     * available. Idempotent and safe to call before {@link #start}; the pane calls it to
     * size the preview buffer to the same aspect the encoder will use.
     */
    public static void resolveCaptureSize(Context ctx, EncoderConfig config) {
        int[] cap = EncoderConfig.CAMERA_ID_USB.equals(config.cameraId)
                ? new int[]{ (config.resolution.h * 16) / 9, config.resolution.h }
                : CameraSource.chooseNativeCaptureSize(
                        ctx, config.cameraId, config.useFrontCamera, config.resolution.h);
        config.captureW = cap[0];
        config.captureH = cap[1];
    }

    /**
     * Start capture + encode. Preview may be null (encoder-only).
     * The caller must already hold {@code android.permission.CAMERA}.
     */
    public void start(Context ctx, EncoderConfig config, Surface preview, Listener listener) {
        if (running) return;
        nalCount.set(0);
        audioAsc = null;   // audio may be off this run — don't carry the last run's config
        usingUsbCamera = EncoderConfig.CAMERA_ID_USB.equals(config.cameraId);

        resolveCaptureSize(ctx, config);
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
                Sink r = recorder;
                if (r != null) r.onNal(data, isKeyFrame, ptsUs);
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

        if (usingUsbCamera) {
            usbCamera.start(ctx, cameraTarget, preview, new UsbCameraSource.Callback() {
                @Override public void onError(String message) {
                    running = false;
                    stop();
                    listener.onError(message);
                }
                @Override public void onOpened() {
                    // Device is up — restart the caller's no-feed clock from here rather
                    // than from onStarted, so a slow USB permission prompt isn't mistaken
                    // for a camera that isn't delivering.
                    listener.onSourceOpened();
                }
            });
        } else {
            camera.start(ctx, config, cameraTarget, preview, message -> {
                running = false;
                stop();
                listener.onError(message);
            });
        }

        // Optional mic audio as a second (AAC) track. A failure here is non-fatal — the
        // video keeps streaming; we just log and continue without audio.
        if (config.streamAudio) {
            boolean ok = audio.start(new AacEncoder.Callback() {
                @Override public void onAudioFormat(byte[] asc, int sampleRate, int channels) {
                    // Cached so a recording started later in the broadcast can still add an
                    // audio track — the AudioSpecificConfig is only emitted once, at start.
                    audioAsc = asc;
                    audioSampleRate = sampleRate;
                    audioChannels = channels;
                    Sink s = sink;
                    if (s != null) s.onAudioFormat(asc, sampleRate, channels);
                    Sink r = recorder;
                    if (r != null) r.onAudioFormat(asc, sampleRate, channels);
                }
                @Override public void onAudioSample(byte[] aac, long ptsUs) {
                    Sink s = sink;
                    if (s != null) s.onAudioSample(aac, ptsUs);
                    Sink r = recorder;
                    if (r != null) r.onAudioSample(aac, ptsUs);
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
        usbCamera.stop();
        if (glPipe != null) { glPipe.release(); glPipe = null; }   // …then tear down GL…
        encoder.stop();                // …then the encoder it fed
    }

    /**
     * Attach or detach the local preview target while capture keeps running — used when
     * the drop-down pane's TextureView is destroyed/recreated (dropdown closed/reopened)
     * so broadcasting isn't interrupted. No-op if capture isn't running.
     */
    public void setPreviewSurface(Surface preview) {
        if (!running) return;
        if (usingUsbCamera) usbCamera.setPreviewSurface(preview);
        else camera.setPreviewSurface(preview);
    }

    /** Forward codec config to the sink once both SPS and PPS are known. */
    private void pushFormat() {
        if (sps == null || pps == null) return;
        Sink s = sink;
        if (s != null) s.onFormat(sps, pps);
        Sink r = recorder;
        if (r != null) r.onFormat(sps, pps);
    }

    public byte[] getSps() { return sps; }
    public byte[] getPps() { return pps; }

    /** Cached AAC AudioSpecificConfig for the running broadcast, or null when video-only. */
    public byte[] getAudioAsc() { return audioAsc; }
    public int getAudioSampleRate() { return audioSampleRate; }
    public int getAudioChannels() { return audioChannels; }

    /** Encoded frame size — the encoder's own output dimensions, which are the capture size
     *  swapped for the portrait orientations. What a recorded MP4 must declare. */
    public int getEncodedWidth()  { return encoder.getOutputWidth(); }
    public int getEncodedHeight() { return encoder.getOutputHeight(); }

    public CameraSource getCamera() { return camera; }

    /**
     * Capture a single JPEG still from the live camera — works with the preview attached
     * or not (snapshot from the radial with the panel closed). No-op error if not running.
     */
    public void captureStill(int jpegOrientation, CameraSource.StillCallback cb) {
        if (!running) { cb.onStillError("not broadcasting"); return; }
        if (usingUsbCamera) { cb.onStillError("Snapshot isn't supported on a USB camera yet"); return; }
        camera.captureStill(jpegOrientation, cb);
    }
}
