package com.atakmap.android.icu.util;

/**
 * Every persisted ICU setting key, grouped the way the Settings page groups them
 * (BROADCAST / VIDEO / DISPLAY &amp; POWER).
 *
 * <p>These names are a <b>public contract</b>: an ATAK {@code .pref} file can set any
 * of them by targeting the {@link #FILE} preference block, e.g.</p>
 * <pre>
 * &lt;preference version="1" name="icu_video_prefs"&gt;
 *   &lt;entry key="server_host" class="class java.lang.String"&gt;10.0.0.5&lt;/entry&gt;
 * &lt;/preference&gt;
 * </pre>
 * <p>ATAK's importer writes any non-reserved preference block straight into the host
 * context's SharedPreferences of that name, which is where {@link Prefs} keeps these.
 * Renaming a key breaks every deployed {@code .pref} file and every existing install —
 * add new keys, never rename old ones. See {@code docs/PREF_FILE.md}.</p>
 */
public final class PrefKeys {

    /** SharedPreferences file (in the host ATAK context) and {@code .pref} block name. */
    public static final String FILE = "icu_video_prefs";

    // ── BROADCAST ───────────────────────────────────────────────────────────────
    /** {@code LAN} or {@code SERVER}. */
    public static final String DESTINATION    = "destination";
    /** Broadcast alias shown to viewers. Default: the device callsign. */
    public static final String ALIAS          = "alias";
    /** {@code RTSP}, {@code RTMP} or {@code SRT}. */
    public static final String PUSH_PROTOCOL  = "push_protocol";
    public static final String SERVER_HOST    = "server_host";
    public static final String SERVER_PORT    = "server_port";
    /** Server stream path. Default: the device callsign, URL-safe. */
    public static final String STREAM_PATH    = "stream_path";
    public static final String USERNAME       = "username";
    public static final String PASSWORD       = "password";
    public static final String SRT_PASSPHRASE = "srt_passphrase";
    /** Internal: this device's TAK Server feed UUID. Never provision fleet-wide. */
    public static final String FEED_UUID      = "feed_uuid";

    // ── VIDEO, per destination ──────────────────────────────────────────────────
    // Stored as "<key>.LAN" / "<key>.SERVER"; the bare key is the legacy fallback
    // read when a destination's own key is absent.
    /** {@code P480}, {@code P720} or {@code P1080}. */
    public static final String RESOLUTION     = "resolution";
    public static final String FPS            = "fps";
    /** kbps. */
    public static final String BITRATE        = "bitrate";
    /** {@code -1} = Auto (match ATAK), else {@code 0}/{@code 90}/{@code 180}/{@code 270}. */
    public static final String ROTATION       = "rotation";
    public static final String FRONT_CAMERA   = "front_camera";
    /** Camera2 id, or the USB / network source id. Device-specific. */
    public static final String CAMERA_ID      = "camera_id";
    /** Keyframe (GOP) interval, seconds. */
    public static final String KEYFRAME_SEC   = "keyframe_sec";
    public static final String STREAM_AUDIO   = "stream_audio";

    // ── VIDEO, global ───────────────────────────────────────────────────────────
    public static final String NET_CAMERA_URL = "net_camera_url";
    /** Record height in px ({@code 0} = same as stream). */
    public static final String RECORD_HEIGHT  = "record_height";
    /** Record fps ({@code 0} = same as stream). */
    public static final String RECORD_FPS     = "record_fps";
    public static final String FOV_REFRESH_SEC = "fov_refresh_sec";
    public static final String FOV_RANGE_M    = "fov_range_m";

    // ── DISPLAY & POWER ─────────────────────────────────────────────────────────
    public static final String SHOW_STATUS_WIDGET = "show_status_widget";
    public static final String STREAM_SCREEN_OFF  = "stream_screen_off";

    private PrefKeys() {}

    /** The per-destination key, e.g. {@code profile(FPS, "LAN")} → {@code "fps.LAN"}. */
    public static String profile(String key, String destination) {
        return key + "." + destination;
    }
}
