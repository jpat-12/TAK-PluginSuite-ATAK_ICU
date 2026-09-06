package com.atakmap.android.icu.capture;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import com.atakmap.coremap.log.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Microphone → AAC-LC encoder. Captures PCM from {@link AudioRecord} and encodes it with
 * {@link MediaCodec} ("audio/mp4a-latm"), emitting the AudioSpecificConfig once and then
 * individual raw AAC access units to a {@link Callback}. The RTSP push layer packetizes
 * these into a second RTP track (RFC 3640). Video-only broadcast simply never starts this.
 */
public class AacEncoder {

    private static final String TAG  = "ICU.AacEncoder";
    private static final String MIME = "audio/mp4a-latm";

    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNELS    = 1;
    private static final int BITRATE    = 96_000;

    public interface Callback {
        /** AudioSpecificConfig (csd-0) plus the negotiated clock — arrives before samples. */
        void onAudioFormat(byte[] asc, int sampleRate, int channels);
        /** One raw AAC access unit with its presentation time (microseconds). */
        void onAudioSample(byte[] aac, long ptsUs);
        void onError(String message);
    }

    private AudioRecord     record;
    private MediaCodec      codec;
    private Thread          thread;
    private volatile boolean running;

    /** Start mic capture + AAC encode. Returns false if setup failed (callback gets the error). */
    public boolean start(Callback cb) {
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = 4096;
            int bufSize = Math.max(minBuf, 4096);

            record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                cb.onError("mic unavailable (RECORD_AUDIO not granted?)");
                releaseRecord();
                return false;
            }

            MediaFormat fmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize);

            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            running = true;
            record.startRecording();
            startLoop(cb, bufSize);
            Log.d(TAG, "AAC mic capture started @ " + SAMPLE_RATE + "Hz");
            return true;
        } catch (Exception e) {
            cb.onError("audio start: " + e.getMessage());
            stop();
            return false;
        }
    }

    private void startLoop(Callback cb, int bufSize) {
        thread = new Thread(() -> {
            byte[] pcm = new byte[bufSize];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long totalSamples = 0;
            while (running) {
                // ── Feed PCM from the mic into the encoder ──
                int read = record.read(pcm, 0, pcm.length);
                if (read > 0) {
                    try {
                        int inIdx = codec.dequeueInputBuffer(10_000);
                        if (inIdx >= 0) {
                            ByteBuffer in = codec.getInputBuffer(inIdx);
                            if (in != null) {
                                in.clear();
                                in.put(pcm, 0, read);
                                // pts from the running sample count keeps the audio clock monotonic
                                long ptsUs = totalSamples * 1_000_000L / SAMPLE_RATE;
                                codec.queueInputBuffer(inIdx, 0, read, ptsUs, 0);
                                totalSamples += read / 2;   // 16-bit mono → 2 bytes/sample
                            }
                        }
                    } catch (IllegalStateException e) {
                        break; // codec torn down
                    }
                }

                // ── Drain encoded AAC ──
                try {
                    int outIdx;
                    while ((outIdx = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIdx);
                        if (out != null && info.size > 0) {
                            byte[] data = new byte[info.size];
                            out.position(info.offset);
                            out.get(data);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                cb.onAudioFormat(data, SAMPLE_RATE, CHANNELS);   // AudioSpecificConfig
                            } else {
                                cb.onAudioSample(data, info.presentationTimeUs);
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                    }
                } catch (IllegalStateException e) {
                    break;
                }
            }
        }, "ICU-AacEncode");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) {}
            thread = null;
        }
        releaseRecord();
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
    }

    private void releaseRecord() {
        if (record != null) {
            try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
            catch (Exception ignored) {}
            try { record.release(); } catch (Exception ignored) {}
            record = null;
        }
    }
}
