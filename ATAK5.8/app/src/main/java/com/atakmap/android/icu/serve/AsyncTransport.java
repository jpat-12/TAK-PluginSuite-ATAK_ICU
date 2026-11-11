package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.util.DiagLog;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Decorator that moves a {@link Transport}'s media delivery onto its own bounded
 * queue + sender thread, so the ENCODER DRAIN THREAD NEVER BLOCKS ON THE NETWORK.
 *
 * <p>Why this exists: transports used to be fed synchronously from the encoder's
 * drain callback. A push transport's socket write blocking on a congested link (a
 * loaded radio mesh, cell handover, a slow server) stalled the drain thread, which
 * backed up MediaCodec's output buffers, which stopped the encoder accepting camera
 * frames, which froze the GL stage — and every one of those stalls was baked into
 * the LOCAL recording as a capture gap. In the field that read as "my recording
 * keeps pausing like it's buffering", on a file that never touched the network.</p>
 *
 * <p>With this wrapper the trade goes the right way: when the network can't keep
 * up, the STREAM drops frames (normal live-video behavior — the viewer sees a
 * skip), while capture, recording, and every other transport keep running at full
 * rate. Overflow policy per media type:</p>
 * <ul>
 *   <li><b>Video:</b> a full queue drops the queued video and everything further
 *       until the next keyframe, then resumes on that clean GOP boundary (a
 *       decoder can't join mid-GOP anyway, so delivering the tail of one would
 *       only waste the bandwidth that's already scarce).</li>
 *   <li><b>Audio/KLV:</b> tiny packets — dropped individually on overflow.</li>
 *   <li><b>SPS/PPS (onFormat):</b> never dropped; forces room by evicting queued
 *       video (the next keyframe needs them at the far end).</li>
 * </ul>
 *
 * <p>Everything non-media (start/stop/status/reconnect/endpoints) delegates
 * straight through, so the pane's status polling is unaffected. {@link #reconnect}
 * also clears the queue — frames staged for a dead socket are worthless on the
 * new one.</p>
 */
public final class AsyncTransport implements Transport {

    /** ~2s of 30fps video + interleaved audio; the live stream should never be
     *  further behind than this — beyond it, dropping is kinder than lagging. */
    private static final int QUEUE_CAPACITY = 96;

    private static final int T_FORMAT = 0, T_VIDEO = 1, T_AFORMAT = 2, T_AUDIO = 3, T_KLV = 4;

    private static final class Item {
        final int type;
        final byte[] a, b;
        final boolean key;
        final long ptsUs;
        final int sampleRate, channels;
        Item(int type, byte[] a, byte[] b, boolean key, long ptsUs, int sampleRate, int channels) {
            this.type = type; this.a = a; this.b = b; this.key = key;
            this.ptsUs = ptsUs; this.sampleRate = sampleRate; this.channels = channels;
        }
    }

    private final Transport delegate;
    private final LinkedBlockingDeque<Item> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
    private Thread sender;
    private volatile boolean running;
    private volatile boolean droppingVideo;   // waiting for the next keyframe after overflow
    private long droppedVideo, droppedAudio;  // sender-side stats for the diag log

    public AsyncTransport(Transport delegate) { this.delegate = delegate; }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    @Override public void start(EncoderConfig config) throws Exception {
        delegate.start(config);
        running = true;
        sender = new Thread(this::sendLoop, "ICU-Send-" + delegate.name());
        sender.setDaemon(true);
        sender.start();
    }

    @Override public void stop() {
        running = false;
        if (sender != null) {
            sender.interrupt();
            try { sender.join(500); } catch (InterruptedException ignored) {}
            sender = null;
        }
        queue.clear();
        delegate.stop();
        if (droppedVideo + droppedAudio > 0)
            DiagLog.d("ICU.AsyncTransport", delegate.name() + ": dropped "
                    + droppedVideo + " video / " + droppedAudio + " audio frames "
                    + "to keep capture real-time (network slower than the stream)");
    }

    @Override public void reconnect(EncoderConfig config) {
        // Frames staged for the dead socket would only delay the new connection.
        queue.clear();
        droppingVideo = true;   // resume on a clean keyframe once redialed
        delegate.reconnect(config);
    }

    private void sendLoop() {
        while (running) {
            Item it;
            try { it = queue.take(); } catch (InterruptedException e) { return; }
            try {
                switch (it.type) {
                    case T_FORMAT:  delegate.onFormat(it.a, it.b); break;
                    case T_VIDEO:   delegate.onNal(it.a, it.key, it.ptsUs); break;
                    case T_AFORMAT: delegate.onAudioFormat(it.a, it.sampleRate, it.channels); break;
                    case T_AUDIO:   delegate.onAudioSample(it.a, it.ptsUs); break;
                    case T_KLV:     delegate.onKlv(it.a, it.ptsUs); break;
                }
            } catch (Throwable t) {
                // Transports report their own failures via failureReason(); a throw here
                // must not kill the sender for whatever the transport recovers into.
                DiagLog.w("ICU.AsyncTransport", delegate.name() + " send: " + t);
            }
        }
    }

    // ── Media hooks (called on the encoder drain / audio / KLV threads) ─────────

    @Override public void onFormat(byte[] sps, byte[] pps) {
        Item it = new Item(T_FORMAT, sps, pps, false, 0, 0, 0);
        while (!queue.offer(it)) evictOneVideo();   // never drop codec config
    }

    @Override public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        if (droppingVideo) {
            if (!keyFrame) { droppedVideo++; return; }
            droppingVideo = false;   // clean GOP boundary — resume with this keyframe
        }
        if (!queue.offer(new Item(T_VIDEO, data, null, keyFrame, ptsUs, 0, 0))) {
            // Network is behind: shed the staged video and wait for the next keyframe.
            droppedVideo += 1 + clearQueuedVideo();
            droppingVideo = true;
            if (droppedVideo == 1 || droppedVideo % 300 == 0)
                DiagLog.w("ICU.AsyncTransport", delegate.name()
                        + ": network can't keep up — dropping stream video until the next "
                        + "keyframe (" + droppedVideo + " dropped so far; capture/recording unaffected)");
        }
    }

    @Override public void onAudioFormat(byte[] asc, int sampleRate, int channels) {
        Item it = new Item(T_AFORMAT, asc, null, false, 0, sampleRate, channels);
        while (!queue.offer(it)) evictOneVideo();
    }

    @Override public void onAudioSample(byte[] aac, long ptsUs) {
        if (!queue.offer(new Item(T_AUDIO, aac, null, false, ptsUs, 0, 0))) droppedAudio++;
    }

    @Override public void onKlv(byte[] klvPacket, long ptsUs) {
        queue.offer(new Item(T_KLV, klvPacket, null, false, ptsUs, 0, 0));   // 1 Hz — losable
    }

    /** Remove queued video items (returns how many); used on overflow and to make
     *  room for must-deliver config items. */
    private int clearQueuedVideo() {
        int removed = 0;
        for (Iterator<Item> i = queue.iterator(); i.hasNext(); ) {
            if (i.next().type == T_VIDEO) { i.remove(); removed++; }
        }
        return removed;
    }

    private void evictOneVideo() {
        for (Iterator<Item> i = queue.iterator(); i.hasNext(); ) {
            if (i.next().type == T_VIDEO) { i.remove(); droppedVideo++; return; }
        }
        queue.poll();   // no video queued — evict the oldest whatever it is
    }

    // ── Pass-through status/introspection ───────────────────────────────────────

    @Override public String name() { return delegate.name(); }
    @Override public boolean isConnecting() { return delegate.isConnecting(); }
    @Override public List<StreamEndpoint> endpoints() { return delegate.endpoints(); }
    @Override public int viewerCount() { return delegate.viewerCount(); }
    @Override public String statusLine() { return delegate.statusLine(); }
    @Override public boolean usingAuth() { return delegate.usingAuth(); }
    @Override public String failureReason() { return delegate.failureReason(); }
}
