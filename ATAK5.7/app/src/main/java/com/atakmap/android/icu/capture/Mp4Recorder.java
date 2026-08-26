package com.atakmap.android.icu.capture;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Local MP4 recording — a second consumer of the same encoded stream the transports get.
 *
 * <p>Recording deliberately does <b>not</b> run its own camera or encoder: it attaches to
 * the live {@link CapturePipeline} as a {@link CapturePipeline.Sink} and muxes the already
 * encoded H.264 (and, when enabled, AAC) into an MP4 with {@link MediaMuxer}. One capture
 * session therefore feeds broadcast and recording at identical cost — a second encoder
 * would double the load on a device that's usually already thermally limited, and the two
 * files would not agree frame-for-frame with what viewers saw.</p>
 *
 * <p>(The operator can opt into that trade with the record-quality override — see
 * {@link EncoderConfig#recordHeight}/{@link EncoderConfig#recordFps} and
 * {@link CapturePipeline#startHqRecord} — in which case this class is simply fed by the
 * second encoder instead; nothing here changes.)</p>
 *
 * <p>Consequences of riding the broadcast encoder, all of which the pane accounts for:</p>
 * <ul>
 *   <li>Recording requires an active broadcast — there is no encoded stream otherwise.</li>
 *   <li>The file uses the broadcast's resolution/bitrate/orientation; changing those in
 *       Settings restarts the broadcast, which ends the recording.</li>
 *   <li>Recording starts at the first keyframe after the button is pressed (MP4 can't open
 *       on a partial GOP), so the file begins up to one GOP — see {@code gopSeconds} —
 *       after the tap.</li>
 * </ul>
 *
 * <p>Timestamps arrive on two independent clocks (video pts come from the camera's surface
 * timestamps, audio pts from the mic's running sample count), so each track is rebased to
 * its own first sample — otherwise the muxer would see tracks starting hours apart.</p>
 */
public final class Mp4Recorder implements CapturePipeline.Sink {

    private static final String TAG = "ICU.Mp4Recorder";

    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";

    /** Outcome of a finished recording, for the pane's toast. */
    public static final class Result {
        public final File file;
        public final long durationMs;
        public final boolean ok;
        public final String error;
        Result(File file, long durationMs, boolean ok, String error) {
            this.file = file; this.durationMs = durationMs; this.ok = ok; this.error = error;
        }
    }

    private MediaMuxer muxer;
    private File       file;
    private int        videoTrack = -1;
    private int        audioTrack = -1;
    private boolean    muxing;            // muxer.start() has been called
    private boolean    sawKeyFrame;       // the file's first sample must be a keyframe
    private long       videoBasePtsUs = -1;
    private long       audioBasePtsUs = -1;
    private long       lastVideoPtsUs = -1;
    private long       startedMs;
    private long       videoSamples;

    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    private volatile boolean running;

    public boolean isRunning() { return running; }

    /** Milliseconds since {@link #start}, for the pane's elapsed readout. 0 when idle. */
    public long elapsedMs() {
        return running ? System.currentTimeMillis() - startedMs : 0;
    }

    public File file() { return file; }

    /**
     * Open the output file and configure the muxer's tracks.
     *
     * <p>{@code sps}/{@code pps} must be the codec config of the <i>currently running</i>
     * encoder (see {@link CapturePipeline#getSps()}) — MP4 carries them as the track's
     * csd, so they have to be known before the first sample. Pass {@code asc == null} to
     * record video-only.</p>
     *
     * @return false if the stream isn't ready to be recorded (no SPS/PPS yet).
     */
    public synchronized boolean start(File out, int width, int height,
            byte[] sps, byte[] pps, byte[] asc, int sampleRate, int channels)
            throws IOException {
        if (running) return true;
        if (sps == null || pps == null) {
            Log.w(TAG, "cannot record: encoder has not produced SPS/PPS yet");
            return false;
        }

        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();

        muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        MediaFormat vf = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
        // The GL stage rotates pixels before the encoder, and the encoder surface is sized
        // to the upright frame — so the samples are already the right way up and the file
        // needs no orientation hint.
        vf.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        vf.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        videoTrack = muxer.addTrack(vf);

        if (asc != null && asc.length > 0) {
            MediaFormat af = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channels);
            af.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
            audioTrack = muxer.addTrack(af);
        }

        muxer.start();
        muxing         = true;
        sawKeyFrame    = false;
        videoBasePtsUs = -1;
        audioBasePtsUs = -1;
        lastVideoPtsUs = -1;
        videoSamples   = 0;
        file           = out;
        startedMs      = System.currentTimeMillis();
        running        = true;
        Log.d(TAG, "recording → " + out.getAbsolutePath() + " (" + width + "x" + height
                + (audioTrack >= 0 ? ", with audio)" : ", video-only)"));
        return true;
    }

    /**
     * Finalize the file. Safe to call when not recording (returns null). A recording that
     * never captured a keyframe produces an unplayable MP4, so the file is deleted and the
     * result reports the failure rather than leaving a 0-frame stub on the device.
     */
    public synchronized Result stop() {
        // Keyed off the muxer, not `running` — a write failure clears `running` on its own
        // and the file still has to be finalized and released.
        if (muxer == null) return null;
        running = false;

        long durationMs = System.currentTimeMillis() - startedMs;
        File out = file;
        String error = null;

        if (muxer != null) {
            try {
                if (muxing && videoSamples > 0) muxer.stop();
                else error = "no video was captured";
            } catch (Exception e) {
                error = "muxer stop: " + e.getMessage();
                Log.w(TAG, "muxer stop failed", e);
            }
            try { muxer.release(); } catch (Exception ignored) {}
            muxer = null;
        }

        if (error != null && out != null) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }

        muxing = false;
        videoTrack = audioTrack = -1;
        file = null;
        Log.d(TAG, "recording stopped after " + durationMs + " ms, "
                + videoSamples + " video samples" + (error != null ? " — " + error : ""));
        return new Result(out, durationMs, error == null, error);
    }

    // ── CapturePipeline.Sink ────────────────────────────────────────────────────
    // Called from the encoder drain thread (video) and the AAC encode thread (audio);
    // MediaMuxer is not thread-safe, hence the synchronization.

    /** SPS/PPS are baked into the track at {@link #start}; a mid-recording format change
     *  can't be applied to an open MP4 track, so this is deliberately ignored. */
    @Override public void onFormat(byte[] sps, byte[] pps) {}

    @Override
    public synchronized void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        if (!running || data == null || data.length == 0) return;
        // MP4 playback has to begin on a keyframe — drop the tail of the GOP in progress.
        if (!sawKeyFrame) {
            if (!keyFrame) return;
            sawKeyFrame = true;
        }
        if (videoBasePtsUs < 0) videoBasePtsUs = ptsUs;

        long pts = ptsUs - videoBasePtsUs;
        if (pts <= lastVideoPtsUs) pts = lastVideoPtsUs + 1;   // muxer requires strictly increasing
        lastVideoPtsUs = pts;

        write(videoTrack, data, pts, keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        videoSamples++;
    }

    @Override public void onAudioFormat(byte[] asc, int sampleRate, int channels) {}

    @Override
    public synchronized void onAudioSample(byte[] aac, long ptsUs) {
        if (!running || audioTrack < 0 || aac == null || aac.length == 0) return;
        // Hold audio until video has actually started, so the file doesn't open with a
        // stretch of sound over no picture.
        if (!sawKeyFrame) return;
        if (audioBasePtsUs < 0) audioBasePtsUs = ptsUs;
        write(audioTrack, aac, ptsUs - audioBasePtsUs, 0);
    }

    private void write(int track, byte[] data, long ptsUs, int flags) {
        if (muxer == null || track < 0) return;
        try {
            info.offset = 0;
            info.size = data.length;
            info.presentationTimeUs = Math.max(0, ptsUs);
            info.flags = flags;
            muxer.writeSampleData(track, ByteBuffer.wrap(data), info);
        } catch (Exception e) {
            // A muxer that has gone bad would otherwise throw on every frame for the rest
            // of the broadcast — stop recording and keep the stream on the air.
            Log.w(TAG, "write failed, ending recording: " + e.getMessage());
            running = false;
        }
    }
}
