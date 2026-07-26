package com.atakmap.android.icu.share;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.cot.detail.SensorDetailHandler;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.log.Log;

import java.net.URI;
import java.util.UUID;

/**
 * While broadcasting, makes the operator's <b>own self CoT</b> carry the field-of-view and
 * the playable feed — so peers see the FOV wedge off the skittle and can tap it to watch —
 * without any separate sensor marker.
 *
 * <p>Uses ATAK's purpose-built {@link CotMapComponent#addAdditionalDetail} hook, which
 * appends a {@code <detail>} child to every outbound self SA report. This is the only
 * reliable way to inject into the self PLI (plain {@code CotDetailHandler}s are not invoked
 * for the self marker), and it never mutates the live self marker's metadata — so it can't
 * corrupt "lock on self" the way earlier attempts did.</p>
 *
 * <p>Locally we also call {@link SensorDetailHandler#addFovToMap} so the broadcaster sees
 * their own wedge (the outbound hook only affects what's transmitted). Both are refreshed on
 * a short timer so the azimuth tracks heading and the wedge survives self-marker refreshes.</p>
 */
public final class SelfMarkerFov {

    private static final String TAG = "ICU.SelfFov";

    private static final String KEY_SENSOR = "__icu_sensor";
    private static final String KEY_VIDEO  = "__icu_video";
    private static final String KEY_DEVICE = "__icu_device";

    private static final double FOV_DEG = 60, RANGE_M = 15;
    private static final float[] FOV_RGBA = { 0.0f, 0.6f, 1.0f, 0.3f };
    /** How often the local wedge is re-drawn (self marker refreshes clear it faster). */
    private static final long LOCAL_REFRESH_MS = 1000;
    private static final long DEFAULT_OUTBOUND_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean active;
    private String url;
    private String alias = "ICU VideoStreamer";
    private String videoUid;
    /** User-configured FOV refresh/force-send interval; set from EncoderConfig on start. */
    private long outboundMs = DEFAULT_OUTBOUND_MS;
    private com.atakmap.android.icu.util.CompassHeading compass;

    public void start(String videoUrl, String alias) {
        start(videoUrl, alias, (int) (DEFAULT_OUTBOUND_MS / 1000));
    }

    public void start(String videoUrl, String alias, int refreshSec) {
        this.url = videoUrl;
        this.outboundMs = Math.max(1, refreshSec) * 1000L;   // clamp to >= 1s
        String callsign = callsign();
        // Prefer the passed alias; else the operator callsign — never a bare UUID.
        if (alias != null && !alias.trim().isEmpty()) this.alias = alias.trim();
        else if (callsign != null) this.alias = callsign;
        // Identify the feed by callsign (readable) rather than a random UUID.
        this.videoUid = "ICU-" + (callsign != null ? callsign : UUID.randomUUID().toString());
        active = true;
        // Aim the wedge where the camera is pointing (compass), not direction of travel.
        MapView mv = MapView.getMapView();
        if (mv != null) {
            compass = new com.atakmap.android.icu.util.CompassHeading(mv.getContext());
            compass.start();
        }
        handler.post(outboundTick);
        // Local wedge on the broadcasting device: ATAK's shipped addFovToMap only (no
        // hand-written metadata). Re-enabled to test whether this alone corrupts
        // "lock on self" or whether the earlier manual sensorFov writes were the cause.
        handler.post(localTick);
        Log.d(TAG, "self CoT FOV+video on: " + videoUrl);
    }

    public void stop() {
        if (!active) return;
        active = false;
        if (compass != null) { compass.stop(); compass = null; }
        handler.removeCallbacks(outboundTick);
        handler.removeCallbacks(localTick);
        try {
            CotMapComponent cmc = CotMapComponent.getInstance();
            if (cmc != null) {
                cmc.removeAdditionalDetail(KEY_SENSOR);
                cmc.removeAdditionalDetail(KEY_DEVICE);
                cmc.removeAdditionalDetail(KEY_VIDEO);
            }
        } catch (Throwable t) {
            Log.w(TAG, "remove additional detail: " + t.getMessage());
        }
        removeLocalFov();
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    /** Refresh the FOV detail on the outbound self CoT, then force a self report so it
     *  actually goes out at the configured interval instead of ATAK's throttled self-SA rate
     *  (up to ~30s when stationary). */
    private final Runnable outboundTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            try {
                double az = heading();
                CotMapComponent cmc = CotMapComponent.getInstance();
                if (cmc != null) {
                    cmc.addAdditionalDetail(KEY_SENSOR, sensorDetail(az));
                    cmc.addAdditionalDetail(KEY_DEVICE, deviceDetail(az));
                    cmc.addAdditionalDetail(KEY_VIDEO, videoDetail());
                }
                // Ask ATAK to emit the self report now (setReportAsap) — otherwise the
                // updated FOV waits for the dynamic/stationary reporting cadence.
                android.content.Intent report =
                        new android.content.Intent("com.atakmap.cot.reporting.REPORT_LOCATION");
                report.putExtra("reason", "ICU FOV update");
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(report);
            } catch (Throwable t) {
                Log.w(TAG, "outbound refresh: " + t.getMessage());
            }
            handler.postDelayed(this, outboundMs);
        }
    };

    /** Re-draw the local wedge (purely local, no network) so it survives self-marker refreshes. */
    private final Runnable localTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            renderLocalFov(heading());
            handler.postDelayed(this, LOCAL_REFRESH_MS);
        }
    };

    /**
     * Full sensor detail matching what ATAK's own SensorDetailHandler emits — iTAK's
     * parser needs the complete attribute set (elevation, vfov, roll, stroke and
     * range-line attributes), not just fov/azimuth/range, or it ignores the FOV.
     */
    private CotDetail sensorDetail(double az) {
        CotDetail s = new CotDetail("sensor");
        s.setAttribute("elevation", "0");
        s.setAttribute("vfov", "0");
        s.setAttribute("roll", "0");
        s.setAttribute("range", Long.toString(Math.round(RANGE_M)));
        s.setAttribute("azimuth", Long.toString(Math.round(az)));
        s.setAttribute("fov", Long.toString(Math.round(FOV_DEG)));
        s.setAttribute("fovRed", "0.0");
        s.setAttribute("fovGreen", "0.6");
        s.setAttribute("fovBlue", "1.0");
        s.setAttribute("fovAlpha", "0.30196078431372547");
        s.setAttribute("strokeColor", "-16777216");
        s.setAttribute("strokeWeight", "0.55");
        s.setAttribute("rangeLines", "25");
        s.setAttribute("rangeLineStrokeColor", "-16777216");
        s.setAttribute("rangeLineStrokeWeight", "1.0");
        s.setAttribute("displayMagneticReference", "0");
        return s;
    }

    /** Device pointing element — present on ATAK's own sensor CoT, mirrored for iTAK. */
    private CotDetail deviceDetail(double az) {
        CotDetail d = new CotDetail("device");
        d.setAttribute("azimuth", Double.toString(az));
        d.setAttribute("pitch", "0.0");
        return d;
    }

    private CotDetail videoDetail() {
        CotDetail video = new CotDetail("__video");
        video.setAttribute("uid", videoUid);
        video.setAttribute("url", url);

        String address = "", path = "", protocol = "rtsp";
        int port = 8554;
        boolean tcp = false;
        try {
            URI u = URI.create(url);
            if (u.getScheme() != null) protocol = u.getScheme().toLowerCase();
            // Keep the userinfo in the address (user:pass@host) so the viewer authenticates.
            if (u.getHost() != null)
                address = (u.getUserInfo() != null ? u.getUserInfo() + "@" : "") + u.getHost();
            if (u.getPort() > 0)       port     = u.getPort();
            if (u.getPath() != null)   path     = u.getPath();
            tcp = "tcp".equalsIgnoreCase(u.getQuery());   // ?tcp → reliable RTSP-interleaved
        } catch (Exception ignored) {}

        CotDetail ce = new CotDetail("ConnectionEntry");
        ce.setAttribute("uid", videoUid);
        ce.setAttribute("alias", alias);
        ce.setAttribute("address", address);
        ce.setAttribute("port", Integer.toString(port));
        ce.setAttribute("roverPort", "-1");
        ce.setAttribute("path", path);
        ce.setAttribute("protocol", protocol);
        ce.setAttribute("networkTimeout", "5000");
        ce.setAttribute("bufferTime", "-1");
        ce.setAttribute("rtspReliable", tcp ? "1" : "0");
        ce.setAttribute("ignoreEmbeddedKLV", "false");
        video.addChild(ce);
        return video;
    }

    private void renderLocalFov(double az) {
        Marker self = selfMarker();
        if (self == null) return;
        // Last arg = true: the 6-arg overload's boolean is passed straight into ATAK's
        // internal addFovToMap; false was leaving the wedge created-but-invisible.
        SensorDetailHandler.addFovToMap(self, az, FOV_DEG, RANGE_M, FOV_RGBA, true);
        if (!loggedPoint) {
            loggedPoint = true;
            com.atakmap.coremap.maps.coords.GeoPoint p = self.getPoint();
            Log.d(TAG, "local FOV: self point valid=" + (p != null && p.isValid())
                    + " " + (p != null ? p.getLatitude() + "," + p.getLongitude() : "null"));
        }
    }
    private boolean loggedPoint;

    private void removeLocalFov() {
        try {
            Marker self = selfMarker();
            if (self == null) return;
            self.removeMetaData(SensorDetailHandler.SENSOR_FOV);
            MapGroup g = SensorDetailHandler.getOrAddMapGroup();
            if (g != null) {
                MapItem fov = g.deepFindUID(self.getUID() + SensorDetailHandler.UID_POSTFIX);
                if (fov != null) fov.removeFromGroup();
            }
        } catch (Throwable t) {
            Log.w(TAG, "removeLocalFov: " + t.getMessage());
        }
    }

    /** Clear any stale FOV metadata/shape left on the self marker (call at plugin load so
     *  the wedge isn't stuck "on" when not broadcasting — addFovToMap's metadata persists). */
    public void clearStaleFov() {
        removeLocalFov();
    }

    /** Camera pointing direction (compass) when available, else fall back to track heading. */
    private double heading() {
        if (compass != null && compass.hasReading()) return compass.azimuth();
        Marker self = selfMarker();
        if (self == null) return 0;
        double h = self.getTrackHeading();
        return Double.isNaN(h) ? 0 : h;
    }

    private static Marker selfMarker() {
        MapView mv = MapView.getMapView();
        return mv != null ? mv.getSelfMarker() : null;
    }

    /** Operator callsign sanitized to a safe token (letters/digits/-/_), or null. */
    private static String callsign() {
        try {
            MapView mv = MapView.getMapView();
            String cs = (mv != null) ? mv.getDeviceCallsign() : null;
            if (cs == null) return null;
            cs = cs.replaceAll("[^A-Za-z0-9_-]", "");
            return cs.isEmpty() ? null : cs;
        } catch (Throwable t) {
            return null;
        }
    }
}
