package com.atakmap.android.icu.share;

/**
 * PHASE 3 — Advertise the live stream URL over CoT.
 *
 * <p>This is the ICU equivalent of the UAS Tool's "Attach Video To Self Marker":
 * it attaches a video CoT detail (ATAK's {@code gov.tak.api.video.cot.ConnectionEntryDetail}
 * / {@code VideoDetailHandler}) to the operator's self-marker and refreshes it on an
 * interval, so every other EUD can tap the marker and open the same URL.</p>
 *
 * <p>Pairs with {@link VideoConnectionPublisher}, which registers the stream with
 * ATAK's local video framework so it also shows in the built-in Video tool.</p>
 */
public class VideoCotBroadcaster {

    private static final int INTERVAL_MS = 30_000;
    private static final int STALE_SEC   = 90;

    public void start(String rtspUrl, String alias) {
        throw new UnsupportedOperationException("VideoCotBroadcaster.start — Phase 3");
    }

    public void stop() {
        // TODO(Phase 3): send a stale CoT + cancel refresh.
    }
}
