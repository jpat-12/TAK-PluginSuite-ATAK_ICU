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
    public int        bitrateKbps = 2500;
    public int        fps         = 30;
    public int        gopSeconds  = 2;
    public boolean    useFrontCamera = false;

    /**
     * Extra rotation applied to the preview (and, best-effort, the encoded stream).
     * -1 = Auto (derive from sensor + display); otherwise 0/90/180/270.
     * Manual override exists because some devices show inverted video in landscape.
     */
    public int rotationDegrees = 270;

    /** Whether the persistent on-map broadcast-status badge is shown. Default on. */
    public boolean showStatusWidget = true;

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
