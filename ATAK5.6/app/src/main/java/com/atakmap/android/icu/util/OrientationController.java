package com.atakmap.android.icu.util;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.OrientationEventListener;

/**
 * Watches the physical device orientation and reports the rotation correction the camera
 * stream needs so the picture stays upright as the operator turns the phone — the "Auto"
 * rotation mode. Maps each 90° quadrant to the same correction values the manual setting
 * uses (Portrait 0°, Landscape 270°, Reverse Portrait 180°, Reverse Landscape 90°).
 *
 * <p>Because a portrait↔landscape change forces a capture restart (the encoder output size
 * flips), reports are debounced: a new quadrant must hold for {@link #SETTLE_MS} before it's
 * emitted, so a quick wrist-twist through landscape doesn't tear the stream down and back up.</p>
 */
public final class OrientationController {

    /** Fired on the main thread when the settled orientation maps to a new correction angle. */
    public interface Listener { void onRotationChanged(int degrees); }

    private static final long SETTLE_MS = 700;

    private final OrientationEventListener sensor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private int  emittedQuadrant = -1;      // last quadrant actually reported
    private int  pendingQuadrant = -1;      // candidate awaiting settle
    private Runnable settle;

    public OrientationController(Context ctx, Listener listener) {
        this.listener = listener;
        this.sensor = new OrientationEventListener(ctx, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                onOrientation(orientation);
            }
        };
    }

    /** Begin watching (no-op if the device can't report orientation). */
    public void enable() {
        emittedQuadrant = -1; pendingQuadrant = -1;
        if (sensor.canDetectOrientation()) sensor.enable();
    }

    public void disable() {
        sensor.disable();
        if (settle != null) { main.removeCallbacks(settle); settle = null; }
    }

    private void onOrientation(int orientation) {
        final int q = quadrant(orientation);
        if (q == emittedQuadrant) {              // back to where we already are — cancel any pending change
            pendingQuadrant = q;
            if (settle != null) { main.removeCallbacks(settle); settle = null; }
            return;
        }
        if (q == pendingQuadrant) return;        // already waiting on this one
        pendingQuadrant = q;
        if (settle != null) main.removeCallbacks(settle);
        settle = () -> {
            emittedQuadrant = pendingQuadrant;
            settle = null;
            listener.onRotationChanged(degreesFor(emittedQuadrant));
        };
        main.postDelayed(settle, SETTLE_MS);
    }

    /** 0=portrait, 1=landscape, 2=reverse portrait, 3=reverse landscape. */
    private static int quadrant(int orientation) {
        if (orientation >= 45  && orientation < 135) return 1;
        if (orientation >= 135 && orientation < 225) return 2;
        if (orientation >= 225 && orientation < 315) return 3;
        return 0;
    }

    /** Correction angle per quadrant — mirrors the manual rotation values in the receiver. */
    private static int degreesFor(int quadrant) {
        switch (quadrant) {
            case 1:  return 270;   // landscape
            case 2:  return 180;   // reverse portrait
            case 3:  return 90;    // reverse landscape
            default: return 0;     // portrait
        }
    }
}
