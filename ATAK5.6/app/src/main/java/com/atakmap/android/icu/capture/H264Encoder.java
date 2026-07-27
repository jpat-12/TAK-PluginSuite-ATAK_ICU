package com.atakmap.android.icu.capture;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import com.atakmap.coremap.log.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * PHASE 1 — MediaCodec H.264 surface-input encoder.
 *
 * <p>Provides an input {@link Surface} for {@link CameraSource}, then drains the
 * encoded bitstream on a background thread and emits SPS/PPS plus individual NAL
 * units to a {@link Callback}. In Phase 1 the NALs are just counted/logged; Phase 2's
 * {@code serve/RtspServer} consumes them for RTP fan-out.</p>
 */
public class H264Encoder {

    private static final String TAG  = "ICU.H264Encoder";
    private static final String MIME = "video/avc";

    public interface Callback {
        void onSpsReady(byte[] sps);
        void onPpsReady(byte[] pps);
        void onNalUnit(byte[] data, boolean isKeyFrame, long ptsUs);
        void onError(String message);
    }

    private MediaCodec       codec;
    private Surface          inputSurface;
    private Thread           drainThread;
    private volatile boolean running;
    private java.util.Timer  syncTimer;
    private long             lastKeyMs;   // for keyframe-cadence logging
    private long             meterStartMs, meteredBytes;   // measured output-bitrate meter

    /**
     * Configure and start the encoder.
     *
     * @return the encoder input {@link Surface} to hand to {@link CameraSource},
     *         or {@code null} if setup failed (the callback receives the error).
     */
    public Surface start(EncoderConfig config, Callback callback) {
        try {
            // The camera's SurfaceTexture matrix bakes in the 90° sensor orientation, so the
            // upright frame the GL stage produces is PORTRAIT for the 0°/180° settings and
            // LANDSCAPE for 90°/270°. Size the encoder surface to match that aspect, or the
            // full-frame draw stretches (e.g. 16:9 content squeezed into a 9:16 surface).
            // The GL rotation stage (GlRotationPipe) does the actual pixel rotation;
            // KEY_ROTATION is only metadata that raw H.264 over RTSP ignores.
            int rot = ((config.rotationDegrees % 360) + 360) % 360;
            boolean swap = (rot == 0 || rot == 180);   // portrait output for these settings
            int outW = swap ? config.captureH : config.captureW;
            int outH = swap ? config.captureW : config.captureH;

            Log.d(TAG, "encoder surface " + outW + "x" + outH + " (capture " + config.captureW
                    + "x" + config.captureH + ", rot=" + rot + ", swap=" + swap + ")");
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, outW, outH);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.gopSeconds);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            codec = MediaCodec.createEncoderByType(MIME);

            // Enforce the bitrate as a real cap. Without a mode, MediaCodec defaults to VBR,
            // which treats KEY_BIT_RATE as an average/target and overshoots ~80% on motion —
            // bad for a metered cellular budget. CBR holds the wire rate near the configured
            // value. Only requested when the encoder advertises support (else configure fails).
            try {
                MediaCodecInfo.EncoderCapabilities ec = codec.getCodecInfo()
                        .getCapabilitiesForType(MIME).getEncoderCapabilities();
                if (ec != null && ec.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                    fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                    Log.d(TAG, "bitrate mode: CBR @ " + config.bitrateKbps + " kbps");
                } else {
                    Log.w(TAG, "CBR unsupported; encoder default (VBR) may overshoot the cap");
                }
            } catch (Exception e) {
                Log.w(TAG, "bitrate mode check failed: " + e.getMessage());
            }

            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            codec.start();
            running = true;

            startDrainThread(callback);
            startSyncFrameTimer(config);   // force keyframes even if the encoder ignores the GOP hint
            return inputSurface;
        } catch (IOException | IllegalStateException e) {
            callback.onError("Encoder start: " + e.getMessage());
            stop();
            return null;
        }
    }

    public void stop() {
        running = false;
        if (syncTimer != null) {
            try { syncTimer.cancel(); } catch (Exception ignored) {}
            syncTimer = null;
        }
        if (drainThread != null) {
            try { drainThread.join(500); } catch (InterruptedException ignored) {}
            drainThread = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception e) {
                Log.w(TAG, "codec.stop: " + e.getMessage());
            }
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        if (inputSurface != null) {
            try { inputSurface.release(); } catch (Exception ignored) {}
            inputSurface = null;
        }
    }

    // ── Keyframe cadence ─────────────────────────────────────────────────────────
    // Many hardware surface-input encoders ignore KEY_I_FRAME_INTERVAL and emit a
    // keyframe only every ~30s. Browser/HLS players can only cut segments at keyframes,
    // so that makes them buffer 30–60s behind live. Requesting a sync frame on a timer
    // reliably forces the ~1–2s keyframe cadence live streaming needs.

    private void startSyncFrameTimer(EncoderConfig config) {
        final long periodMs = Math.max(1, config.gopSeconds) * 1000L;
        syncTimer = new java.util.Timer("ICU-SyncFrame", true);
        syncTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() { requestSyncFrame(); }
        }, periodMs, periodMs);
    }

    private void requestSyncFrame() {
        MediaCodec c = codec;
        if (c == null || !running) return;
        try {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(params);
        } catch (Exception e) {
            Log.w(TAG, "requestSyncFrame: " + e.getMessage());
        }
    }

    // ── Output drain ───────────────────────────────────────────────────────────

    private void startDrainThread(Callback cb) {
        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                int idx;
                try {
                    idx = codec.dequeueOutputBuffer(info, 10_000);
                } catch (IllegalStateException e) {
                    break; // codec torn down
                }
                if (idx < 0) continue;
                try {
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    if (buf != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        buf.position(info.offset);
                        buf.get(data);

                        // Measured output bitrate (bits/ms == kbps) — logged every ~5s.
                        meteredBytes += data.length;
                        long tMs = android.os.SystemClock.elapsedRealtime();
                        if (meterStartMs == 0) meterStartMs = tMs;
                        long win = tMs - meterStartMs;
                        if (win >= 5000) {
                            long kbps = (meteredBytes * 8L) / win;
                            Log.d(TAG, String.format("measured output bitrate: %d kbps (%.2f Mbps)",
                                    kbps, kbps / 1000.0));
                            meterStartMs = tMs;
                            meteredBytes = 0;
                        }

                        boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (isConfig) {
                            parseSpsAndPps(data, cb);
                        } else {
                            boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (isKey) {
                                long now = android.os.SystemClock.elapsedRealtime();
                                if (lastKeyMs > 0)
                                    Log.d(TAG, "keyframe interval: " + (now - lastKeyMs) + " ms");
                                lastKeyMs = now;
                            }
                            cb.onNalUnit(data, isKey, info.presentationTimeUs);
                        }
                    }
                } finally {
                    try { codec.releaseOutputBuffer(idx, false); } catch (Exception ignored) {}
                }
            }
        }, "ICU-CodecDrain");
        drainThread.start();
    }

    /** CODEC_CONFIG output holds SPS + PPS concatenated, each with a start code. */
    private void parseSpsAndPps(byte[] data, Callback cb) {
        int i = 0;
        while (i < data.length - 4) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                int end = data.length;
                for (int j = i + 4; j < data.length - 3; j++) {
                    if (data[j] == 0 && data[j + 1] == 0 && data[j + 2] == 0 && data[j + 3] == 1) {
                        end = j;
                        break;
                    }
                }
                byte[] nal = Arrays.copyOfRange(data, i, end);
                int nalType = nal[4] & 0x1F;
                if (nalType == 7)      cb.onSpsReady(nal);
                else if (nalType == 8) cb.onPpsReady(nal);
                i = end;
            } else {
                i++;
            }
        }
    }
}
