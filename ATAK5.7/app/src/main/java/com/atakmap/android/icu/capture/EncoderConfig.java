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
     * Camera2 id to open, as reported by {@link CameraSource#listCameras}. Empty = auto
     * (pick front/back per {@link #useFrontCamera}, the pre-existing behavior). Set to a
     * specific id — including a {@code LENS_FACING_EXTERNAL} device, i.e. a USB/UVC camera
     * the OS already exposes through Camera2 — to pin the capture source to it.
     *
     * <p>Reserved value {@link #CAMERA_ID_USB} instead routes capture through
     * {@link UsbCameraSource} (the {@code com.herohan:UVCAndroid} driver) for USB cameras
     * that Camera2 doesn't surface at all — the common case on stock Android.</p>
     */
    public String cameraId = "";

    /** Sentinel {@link #cameraId} value selecting the direct UVC (USB) capture path. */
    public static final String CAMERA_ID_USB = "usb";

    /**
     * Extra rotation applied to the preview (and, best-effort, the encoded stream).
     * -1 = Auto (derive from sensor + display); otherwise 0/90/180/270.
     * Manual override exists because some devices show inverted video in landscape.
     */
    public int rotationDegrees = 270;

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
     * Local-recording quality override — record at a different (usually higher) quality
     * than the stream, e.g. stream 720p15 over a constrained link but keep a 1080p30
     * file. 0 = record the broadcast stream as-is (no second encoder, the original
     * zero-cost tap). Non-zero values spin up a second H.264 encoder fed from the same
     * camera; the camera then captures at the higher of the two specs. Global, not
     * per-destination — the recording is local, so LAN/SERVER doesn't change it.
     */
    public int recordHeight = 0;   // 0 = same as stream, else 480/720/1080
    public int recordFps    = 0;   // 0 = same as stream, else 15/24/30

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
