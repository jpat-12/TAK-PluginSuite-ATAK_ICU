package com.atakmap.android.icu.share;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.net.URI;
import java.util.UUID;

/**
 * Drops a CoT <b>sensor marker</b> at the phone's current position with the live
 * stream URL embedded ({@code <__video>} + {@code <sensor>} FOV), and re-dispatches
 * it every few seconds so the marker follows the phone and stays fresh. Dispatched to
 * both the local map and the network (TAK servers / contacts).
 *
 * <p>Uses direct CoT dispatch (not self-marker decoration), so we fully control the
 * emitted event and its position updates.</p>
 */
public class StreamSensorMarker {

    private static final String TAG = "ICU.SensorMarker";
    private static final int INTERVAL_MS = 10000;
    private static final int STALE_SEC   = 30;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String uid;
    private String url;
    private String alias = "ICU VideoStreamer";
    private double fovDeg = 60, rangeM = 200;
    private volatile boolean active;

    /** Begin dropping/updating the sensor marker carrying {@code videoUrl}. */
    public void start(String videoUrl, String alias) {
        this.url = videoUrl;
        if (alias != null && !alias.trim().isEmpty()) this.alias = alias.trim();
        this.uid = "ICU-CAM-" + UUID.randomUUID();
        active = true;
        handler.post(tick);
        Log.d(TAG, "sensor marker start: " + videoUrl);
    }

    public void stop() {
        if (!active) return;
        active = false;
        handler.removeCallbacks(tick);
        sendStaleAndRemove();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            try { dispatch(STALE_SEC); } catch (Exception e) { Log.w(TAG, "dispatch: " + e.getMessage()); }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    // ── CoT dispatch ─────────────────────────────────────────────────────────

    private void dispatch(int staleSec) {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        Marker self = mv.getSelfMarker();
        GeoPoint p = (self != null) ? self.getPoint() : null;
        if (p == null || !p.isValid()) return;

        double heading = 0;
        if (self != null) {
            double h = self.getTrackHeading();
            if (!Double.isNaN(h)) heading = h;
        }

        CotEvent e = build(p, heading, staleSec);
        CotMapComponent.getInternalDispatcher().dispatch(e);  // render locally
        CotMapComponent.getExternalDispatcher().dispatch(e);  // send to network
    }

    private CotEvent build(GeoPoint p, double heading, int staleSec) {
        CotEvent e = new CotEvent();
        e.setUID(uid);
        e.setType("b-m-p-s-p-loc");   // sensor point of interest
        e.setVersion("2.0");
        e.setHow("m-g");

        CoordinatedTime now = new CoordinatedTime();
        e.setTime(now);
        e.setStart(now);
        e.setStale(now.addSeconds(staleSec));

        double alt = p.getAltitude();
        if (Double.isNaN(alt)) alt = 0;
        e.setPoint(new CotPoint(p.getLatitude(), p.getLongitude(), alt, 9999999.0, 9999999.0));

        CotDetail detail = new CotDetail("detail");

        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", alias);
        detail.addChild(contact);

        // Fully transparent icon so only the FOV wedge shows — the sensor point itself is
        // hidden, making the FOV appear to come off the operator's skittle (co-located).
        CotDetail color = new CotDetail("color");
        color.setAttribute("argb", "0");
        detail.addChild(color);

        detail.addChild(videoDetail());
        detail.addChild(sensorDetail(heading));

        e.setDetail(detail);
        return e;
    }

    private CotDetail videoDetail() {
        // ATAK looks up the ConnectionEntry by the <__video> uid, so the nested
        // <ConnectionEntry> MUST carry the same uid — otherwise the player reports
        // "invalid video information, cannot display".
        String videoUid = uid + "-v";

        CotDetail video = new CotDetail("__video");
        video.setAttribute("uid", videoUid);
        video.setAttribute("url", url);

        String address = "", path = "", protocol = "rtsp";
        int port = 8554;
        try {
            URI u = URI.create(url);
            if (u.getScheme() != null) protocol = u.getScheme().toLowerCase();
            if (u.getHost() != null)   address  = u.getHost();
            if (u.getPort() > 0)       port     = u.getPort();
            if (u.getPath() != null)   path     = u.getPath();
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
        ce.setAttribute("rtspReliable", "0");
        ce.setAttribute("ignoreEmbeddedKLV", "false");
        video.addChild(ce);
        return video;
    }

    /** Full sensor attribute set — iTAK needs the complete set (elevation, vfov, roll,
     *  stroke and range-line attributes), not just fov/azimuth/range, to render the FOV. */
    private CotDetail sensorDetail(double heading) {
        CotDetail s = new CotDetail("sensor");
        s.setAttribute("elevation", "0");
        s.setAttribute("vfov", "0");
        s.setAttribute("roll", "0");
        s.setAttribute("range", Long.toString(Math.round(rangeM)));
        s.setAttribute("azimuth", Long.toString(Math.round(heading)));
        s.setAttribute("fov", Long.toString(Math.round(fovDeg)));
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

    private void sendStaleAndRemove() {
        try {
            dispatch(1);   // final event with a 1-second stale so peers expire it
        } catch (Exception ignored) {}
        try {
            MapView mv = MapView.getMapView();
            if (mv != null && uid != null) {
                MapItem m = mv.getRootGroup().deepFindUID(uid);
                if (m != null) m.removeFromGroup();
            }
        } catch (Exception e) {
            Log.w(TAG, "remove: " + e.getMessage());
        }
    }
}
