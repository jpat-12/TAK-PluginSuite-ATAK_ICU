package com.atakmap.android.icu.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.serve.MediaServerConfig;
import com.atakmap.android.maps.MapView;

import java.util.Locale;

import static com.atakmap.android.icu.util.PrefKeys.*;

/**
 * Persistence for ICU broadcast settings (destination, server, credentials, encoding).
 *
 * <p>IMPORTANT: pass the <b>host ATAK context</b> ({@code getMapView().getContext()}),
 * NOT the plugin context. A plugin context's SharedPreferences are not backed by
 * ATAK's persistent data directory, so they do not survive an ATAK restart — the
 * values only live in the in-memory cache for the current session. Using the host
 * context writes to ATAK's own {@code shared_prefs} dir, which persists — and is also
 * where ATAK's {@code .pref} importer writes, so a {@code .pref} file can provision
 * every key in {@link PrefKeys}.</p>
 *
 * <p>The capture/encode settings (resolution, fps, bitrate, camera, rotation,
 * keyframe interval, audio) are kept as <b>one profile per destination</b> — a
 * "{@code .LAN}"/"{@code .SERVER}"-suffixed copy of each key — because a LAN
 * broadcast and a media-server push typically want different tunings. A profile
 * key that has never been written falls back to the legacy unsuffixed key, so an
 * upgrade seeds both profiles with whatever was saved before the split. Everything
 * else (server identity, FOV, display/power) stays destination-independent.</p>
 *
 * <p>All reads are type-tolerant (see {@link PrefReader}) because imported values
 * carry whatever type the {@code .pref} file declared.</p>
 */
public final class Prefs {

    static final String DEFAULT_NET_CAMERA_URL = "rtsp://172.20.1.1:554/stream1";

    private Prefs() {}

    public static void load(Context ctx, MediaServerConfig srv, EncoderConfig enc) {
        PrefReader r = reader(ctx);

        // Default the alias + stream path to the operator's callsign so two operators on
        // default settings don't collide on the same server path (e.g. both "icu").
        String callsign = deviceCallsign();
        String defAlias = (callsign != null) ? callsign : "VIDEO_1";
        String defPath  = (callsign != null) ? pathSafe(callsign) : "icu";

        srv.destination = r.getEnum(DESTINATION, MediaServerConfig.Destination.class,
                MediaServerConfig.Destination.SERVER);
        srv.alias      = r.getString(ALIAS, defAlias);
        srv.host       = r.getString(SERVER_HOST, "").trim();
        srv.streamPath = r.getString(STREAM_PATH, defPath);
        // Migrate the old shared defaults to the callsign so existing testers stop
        // colliding; custom values are left untouched.
        if (callsign != null) {
            if ("VIDEO_1".equals(srv.alias)) srv.alias = defAlias;
            if ("icu".equals(srv.streamPath)) srv.streamPath = defPath;
        }
        srv.username   = r.getString(USERNAME, "");
        srv.password   = r.getString(PASSWORD, "");
        srv.pushProtocol = r.getEnum(PUSH_PROTOCOL, MediaServerConfig.PushProtocol.class,
                MediaServerConfig.PushProtocol.RTSP);
        // A provisioned protocol without a port gets that protocol's default port.
        srv.serverPort = r.getInt(SERVER_PORT, srv.pushProtocol.defaultPort);
        srv.srtPassphrase = r.getString(SRT_PASSPHRASE, "");
        srv.feedUuid   = r.getString(FEED_UUID, "");

        enc.showStatusWidget = r.getBoolean(SHOW_STATUS_WIDGET, true);
        enc.streamWithScreenOff = r.getBoolean(STREAM_SCREEN_OFF, false);
        enc.fovRefreshSec  = Math.max(1, r.getInt(FOV_REFRESH_SEC, 3));
        enc.fovRangeM      = Math.max(1, r.getInt(FOV_RANGE_M, 100));
        enc.recordHeight   = r.getInt(RECORD_HEIGHT, 0);
        enc.recordFps      = r.getInt(RECORD_FPS, 0);
        enc.networkCameraUrl = r.getString(NET_CAMERA_URL, DEFAULT_NET_CAMERA_URL).trim();

        loadProfileInto(r, srv.destination, enc);
    }

    /** The capture/encode profile saved for {@code dest} (globals left at defaults). */
    public static EncoderConfig loadProfile(Context ctx, MediaServerConfig.Destination dest) {
        EncoderConfig enc = new EncoderConfig();
        loadProfileInto(reader(ctx), dest, enc);
        return enc;
    }

    private static void loadProfileInto(PrefReader r,
                                        MediaServerConfig.Destination dest,
                                        EncoderConfig enc) {
        String d = dest.name();
        enc.resolution = parseResolution(
                r.getString(profile(RESOLUTION, d), r.getString(RESOLUTION, "P720")));
        enc.fps             = r.getInt(profile(FPS, d), r.getInt(FPS, 30));
        enc.bitrateKbps     = r.getInt(profile(BITRATE, d), r.getInt(BITRATE, 2500));
        enc.useFrontCamera  = r.getBoolean(profile(FRONT_CAMERA, d), r.getBoolean(FRONT_CAMERA, false));
        enc.cameraId        = r.getString(profile(CAMERA_ID, d), r.getString(CAMERA_ID, ""));
        // Fresh installs default to Auto (-1, follow ATAK's orientation); an existing
        // save keeps whatever manual rotation was already chosen.
        enc.rotationDegrees = r.getInt(profile(ROTATION, d), r.getInt(ROTATION, -1));
        enc.gopSeconds      = r.getInt(profile(KEYFRAME_SEC, d), r.getInt(KEYFRAME_SEC, 2));
        enc.streamAudio     = r.getBoolean(profile(STREAM_AUDIO, d), r.getBoolean(STREAM_AUDIO, true));
    }

    public static void save(Context ctx, MediaServerConfig srv, EncoderConfig enc) {
        // commit() (not apply()) so the write is on disk before this call returns —
        // apply()'s write-behind can otherwise be lost if ATAK is force-stopped/killed
        // shortly after Save, which is exactly what "restart ATAK" testing does.
        SharedPreferences.Editor e = prefs(ctx).edit();
        e.putString(DESTINATION, srv.destination.name())
                .putString(ALIAS, nz(srv.alias, "VIDEO_1"))
                .putString(SERVER_HOST, srv.host == null ? "" : srv.host.trim())
                .putString(STREAM_PATH, nz(srv.streamPath, "icu"))
                .putString(USERNAME, srv.username == null ? "" : srv.username)
                .putString(PASSWORD, srv.password == null ? "" : srv.password)
                .putInt(SERVER_PORT, srv.serverPort)
                .putString(SRT_PASSPHRASE, srv.srtPassphrase == null ? "" : srv.srtPassphrase)
                .putString(FEED_UUID, srv.feedUuid == null ? "" : srv.feedUuid)
                .putString(PUSH_PROTOCOL, srv.pushProtocol.name())
                .putBoolean(SHOW_STATUS_WIDGET, enc.showStatusWidget)
                .putBoolean(STREAM_SCREEN_OFF, enc.streamWithScreenOff)
                .putInt(FOV_REFRESH_SEC, enc.fovRefreshSec)
                .putInt(FOV_RANGE_M, enc.fovRangeM)
                .putInt(RECORD_HEIGHT, enc.recordHeight)
                .putInt(RECORD_FPS, enc.recordFps)
                .putString(NET_CAMERA_URL,
                        enc.networkCameraUrl == null ? "" : enc.networkCameraUrl.trim());
        putProfile(e, srv.destination, enc);
        e.commit();
    }

    /** Persist the capture/encode profile for {@code dest} (globals untouched). */
    public static void saveProfile(Context ctx, MediaServerConfig.Destination dest,
                                   EncoderConfig enc) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        putProfile(e, dest, enc);
        e.commit();
    }

    private static void putProfile(SharedPreferences.Editor e,
                                   MediaServerConfig.Destination dest,
                                   EncoderConfig enc) {
        String d = dest.name();
        e.putString(profile(RESOLUTION, d), enc.resolution.name())
                .putInt(profile(FPS, d), enc.fps)
                .putInt(profile(BITRATE, d), enc.bitrateKbps)
                .putBoolean(profile(FRONT_CAMERA, d), enc.useFrontCamera)
                .putString(profile(CAMERA_ID, d), enc.cameraId == null ? "" : enc.cameraId)
                .putInt(profile(ROTATION, d), enc.rotationDegrees)
                .putInt(profile(KEYFRAME_SEC, d), enc.gopSeconds)
                .putBoolean(profile(STREAM_AUDIO, d), enc.streamAudio);
    }

    /**
     * A string of every value {@link #load} produces, for change detection: two
     * snapshots differ exactly when the live settings would differ. Used to tell
     * whether an ATAK preference import touched ICU's settings.
     */
    public static String snapshot(MediaServerConfig srv, EncoderConfig enc) {
        return srv.destination + "|" + srv.alias + "|" + srv.host + "|" + srv.streamPath
                + "|" + srv.username + "|" + srv.password + "|" + srv.serverPort
                + "|" + srv.srtPassphrase + "|" + srv.feedUuid + "|" + srv.pushProtocol
                + "|" + enc.showStatusWidget + "|" + enc.streamWithScreenOff
                + "|" + enc.fovRefreshSec + "|" + enc.fovRangeM
                + "|" + enc.recordHeight + "|" + enc.recordFps + "|" + enc.networkCameraUrl
                + "|" + enc.resolution + "|" + enc.fps + "|" + enc.bitrateKbps
                + "|" + enc.useFrontCamera + "|" + enc.cameraId + "|" + enc.rotationDegrees
                + "|" + enc.gopSeconds + "|" + enc.streamAudio;
    }

    /** {@code P720}, {@code 720p} and {@code 720} all mean 720p; unknown → 720p. */
    static EncoderConfig.Resolution parseResolution(String v) {
        String s = v == null ? "" : v.trim().toUpperCase(Locale.US);
        if (s.endsWith("P")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("P")) s = s.substring(1);
        for (EncoderConfig.Resolution res : EncoderConfig.Resolution.values())
            if (Integer.toString(res.h).equals(s)) return res;
        return EncoderConfig.Resolution.P720;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static PrefReader reader(Context ctx) { return new PrefReader(prefs(ctx)); }

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
