package com.atakmap.android.icu.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.serve.MediaServerConfig;
import com.atakmap.android.maps.MapView;

/**
 * Persistence for ICU broadcast settings (destination, server, credentials, encoding).
 *
 * <p>IMPORTANT: pass the <b>host ATAK context</b> ({@code getMapView().getContext()}),
 * NOT the plugin context. A plugin context's SharedPreferences are not backed by
 * ATAK's persistent data directory, so they do not survive an ATAK restart — the
 * values only live in the in-memory cache for the current session. Using the host
 * context writes to ATAK's own {@code shared_prefs} dir, which persists.</p>
 */
public final class Prefs {

    private static final String FILE = "icu_video_prefs";

    private Prefs() {}

    public static void load(Context ctx, MediaServerConfig srv, EncoderConfig enc) {
        SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);

        // Default the alias + stream path to the operator's callsign so two operators on
        // default settings don't collide on the same server path (e.g. both "icu").
        String callsign = deviceCallsign();
        String defAlias = (callsign != null) ? callsign : "VIDEO_1";
        String defPath  = (callsign != null) ? pathSafe(callsign) : "icu";

        srv.destination = "LAN".equals(sp.getString("destination", "SERVER"))
                ? MediaServerConfig.Destination.LAN : MediaServerConfig.Destination.SERVER;
        srv.alias      = sp.getString("alias", defAlias);
        srv.host       = sp.getString("server_host", "");
        srv.streamPath = sp.getString("stream_path", defPath);
        // Migrate the old shared defaults to the callsign so existing testers stop
        // colliding; custom values are left untouched.
        if (callsign != null) {
            if ("VIDEO_1".equals(srv.alias)) srv.alias = defAlias;
            if ("icu".equals(srv.streamPath)) srv.streamPath = defPath;
        }
        srv.username   = sp.getString("username", "");
        srv.password   = sp.getString("password", "");
        srv.serverPort = sp.getInt("server_port", 8554);
        srv.srtPassphrase = sp.getString("srt_passphrase", "");
        srv.feedUuid   = sp.getString("feed_uuid", "");
        srv.multicastEnabled = sp.getBoolean("multicast_enabled", true);
        srv.multicastGroup   = sp.getString("multicast_group", "239.1.1.1");
        srv.multicastPort    = sp.getInt("multicast_port", 5600);
        try { srv.pushProtocol = MediaServerConfig.PushProtocol.valueOf(
                sp.getString("push_protocol", "RTSP")); }
        catch (Exception ignored) { srv.pushProtocol = MediaServerConfig.PushProtocol.RTSP; }

        String res = sp.getString("resolution", "P720");
        try { enc.resolution = EncoderConfig.Resolution.valueOf(res); }
        catch (Exception ignored) { enc.resolution = EncoderConfig.Resolution.P720; }
        enc.fps             = sp.getInt("fps", 30);
        enc.bitrateKbps     = sp.getInt("bitrate", 2500);
        enc.useFrontCamera  = sp.getBoolean("front_camera", false);
        enc.rotationDegrees = sp.getInt("rotation", 270);
        enc.showStatusWidget = sp.getBoolean("show_status_widget", true);
        enc.streamAudio      = sp.getBoolean("stream_audio", true);
        enc.streamWithScreenOff = sp.getBoolean("stream_screen_off", false);
        enc.gopSeconds     = sp.getInt("keyframe_sec", 2);
        enc.fovRefreshSec  = sp.getInt("fov_refresh_sec", 3);
        enc.fovRangeM      = sp.getInt("fov_range_m", 100);
    }

    public static void save(Context ctx, MediaServerConfig srv, EncoderConfig enc) {
        // commit() (not apply()) so the write is on disk before this call returns —
        // apply()'s write-behind can otherwise be lost if ATAK is force-stopped/killed
        // shortly after Save, which is exactly what "restart ATAK" testing does.
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("destination", srv.destination.name())
                .putString("alias", nz(srv.alias, "VIDEO_1"))
                .putString("server_host", srv.host == null ? "" : srv.host.trim())
                .putString("stream_path", nz(srv.streamPath, "icu"))
                .putString("username", srv.username == null ? "" : srv.username)
                .putString("password", srv.password == null ? "" : srv.password)
                .putInt("server_port", srv.serverPort)
                .putString("srt_passphrase", srv.srtPassphrase == null ? "" : srv.srtPassphrase)
                .putString("feed_uuid", srv.feedUuid == null ? "" : srv.feedUuid)
                .putBoolean("multicast_enabled", srv.multicastEnabled)
                .putString("multicast_group", nz(srv.multicastGroup, "239.1.1.1"))
                .putInt("multicast_port", srv.multicastPort)
                .putString("push_protocol", srv.pushProtocol.name())
                .putString("resolution", enc.resolution.name())
                .putInt("fps", enc.fps)
                .putInt("bitrate", enc.bitrateKbps)
                .putBoolean("front_camera", enc.useFrontCamera)
                .putInt("rotation", enc.rotationDegrees)
                .putBoolean("show_status_widget", enc.showStatusWidget)
                .putBoolean("stream_audio", enc.streamAudio)
                .putBoolean("stream_screen_off", enc.streamWithScreenOff)
                .putInt("keyframe_sec", enc.gopSeconds)
                .putInt("fov_refresh_sec", enc.fovRefreshSec)
                .putInt("fov_range_m", enc.fovRangeM)
                .commit();
    }

    private static String nz(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    /** The operator's ATAK callsign, or null if unavailable. */
    private static String deviceCallsign() {
        try {
            MapView mv = MapView.getMapView();
            String cs = (mv != null) ? mv.getDeviceCallsign() : null;
            return (cs == null || cs.trim().isEmpty()) ? null : cs.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Callsign reduced to a URL/stream-path-safe token (letters, digits, - and _). */
    private static String pathSafe(String s) {
        String p = s.replaceAll("[^A-Za-z0-9_-]", "");
        return p.isEmpty() ? "icu" : p;
    }
}
