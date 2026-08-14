package com.atakmap.android.icu.capture;

/**
 * Capture + encode parameters for the phone-camera source.
 * Mirrors the resolution/bitrate options a drone datalink would expose.
 */
public class EncoderConfig {

    public enum Resolution {
        P480(854, 480, "480p"),
        P720(1280, 720, "720p"),
        P1080(1920, 1080, "1080p");

        public final int w, h;
        public final String label;
        Resolution(int w, int h, String label) { this.w = w; this.h = h; this.label = label; }
    }

    public Resolution resolution  = Resolution.P720;

    /**
     * Actual capture/encode dimensions in landscape (w ≥ h), chosen at runtime to match
     * the camera sensor's <b>native aspect ratio</b> at ~{@link #resolution} height — so the
     * stream is never cropped or stretched to a forced 16:9. Populated by CapturePipeline
     * before the encoder starts; defaults mirror {@code resolution} until then.
     */
    public int captureW = 1280;
    public int captureH = 720;

    public int        bitrateKbps = 2500;
    public int        fps         = 30;
    public int        gopSeconds  = 2;
    public boolean    useFrontCamera = false;

    /**
     * Extra rotation applied to the preview (and, best-effort, the encoded stream).
     * -1 = Auto (derive live from the device orientation); otherwise 0/90/180/270.
     * Manual override exists because some devices show inverted video in landscape.
     *
     * <p>This field is the persisted <i>setting</i>. When it is -1 (Auto), the concrete
     * angle actually used by capture is tracked separately in {@link #resolvedRotation}
     * and driven by the device orientation sensor — read {@link #captureRotation()}
     * everywhere a real angle is needed.</p>
     */
    public int rotationDegrees = 270;

    /** Concrete rotation used by the encoder/preview while Auto is active. Not persisted;
     *  updated live by the orientation sensor. Ignored when {@link #rotationDegrees} >= 0. */
    private int resolvedRotation = 270;

    /** True when the operator selected Auto (device-matched) rotation. */
    public boolean isAutoRotate() { return rotationDegrees < 0; }

    /** The concrete rotation to apply right now: the manual setting, or — in Auto — the
     *  latest device-derived angle. Always 0/90/180/270. */
    public int captureRotation() {
        int d = isAutoRotate() ? resolvedRotation : rotationDegrees;
        return ((d % 360) + 360) % 360;
    }

    /** Update the live Auto angle (no-op when a manual rotation is set). Returns true if
     *  the effective capture rotation actually changed. */
    public boolean setResolvedRotation(int deg) {
        if (!isAutoRotate()) return false;
        int d = ((deg % 360) + 360) % 360;
        if (d == resolvedRotation) return false;
        resolvedRotation = d;
        return true;
    }

    /** Portrait (0/180) vs landscape (90/270) — the encoder output aspect class. A change
     *  here needs a capture restart (MediaCodec output size is fixed); a same-class flip
     *  (e.g. 90↔270) can be applied live in the GL stage. */
    public static boolean isLandscapeRotation(int deg) {
        int d = ((deg % 360) + 360) % 360;
        return d == 90 || d == 270;
    }

    /** Whether the persistent on-map broadcast-status badge is shown. Default on. */
    public boolean showStatusWidget = true;

    /** Capture and stream microphone audio (AAC) alongside the video. Default on. */
    public boolean streamAudio = true;

    /**
     * Allow the screen to turn off while broadcasting (capture continues in the
     * background). Default false = keep the screen awake while live.
     */
    public boolean streamWithScreenOff = false;

    /**
     * How often (seconds) the FOV/video detail is refreshed and force-sent on the self
     * report while broadcasting. Lower = the wedge tracks the camera sooner on peers, at
     * the cost of more frequent position reports. Default 3s.
     */
    public int fovRefreshSec = 3;

    /**
     * How far (meters) the broadcast FOV wedge extends from the operator's marker. Purely
     * a visual reach for the wedge on peers' maps; does not affect the video. Default 100m.
     */
    public int fovRangeM = 100;
}
