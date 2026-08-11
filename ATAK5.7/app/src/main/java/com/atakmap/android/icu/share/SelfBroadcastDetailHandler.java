package com.atakmap.android.icu.share;

import com.atakmap.android.cot.detail.CotDetailHandler;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.comms.CommsMapComponent.ImportResult;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

/**
 * PHASE 3 — while broadcasting, decorates the operator's <b>self marker</b> outgoing
 * PLI with a {@code <sensor>} FOV and a standard {@code <__video>} detail (URL +
 * {@code <ConnectionEntry>}). Other ATAK users then see the broadcaster as a sensor
 * with a tappable live feed — the URL and feed are embedded right in the position
 * message.
 *
 * <p>This <i>adds</i> details on the fly; it never mutates the self marker's identity.
 * When {@link #setBroadcasting}(false) the details stop being emitted and the marker
 * reverts to the user's normal preferences on the next PLI.</p>
 *
 * <p>Registered under a private detail name so it never intercepts inbound {@code __video}
 * / {@code sensor} details (those still route to ATAK's core handlers). Outbound,
 * {@code toCotDetail} is invoked for every item — including the self marker — so the
 * gating below is what limits us to the self PLI while live.</p>
 */
public class SelfBroadcastDetailHandler extends CotDetailHandler {

    private static final String TAG = "ICU.SelfBroadcast";

    /** Values match ATAK's SensorDetailHandler / VideoDetailHandler attribute keys. */
    private static final String SENSOR = "sensor";
    private static final String VIDEO  = "__video";
    private static final String CONNECTION_ENTRY = "ConnectionEntry";

    private volatile boolean broadcasting;

    // Video
    private volatile String url;
    private volatile String uid;
    private volatile String alias   = "ICU VideoStreamer";
    private volatile String address = "";
    private volatile int    port    = 8554;
    private volatile String path    = "";
    private volatile String protocol = "rtsp";

    // Sensor FOV
    private volatile double fovDeg   = 60;
    private volatile double rangeM   = 200;

    public SelfBroadcastDetailHandler() {
        super("__icuselfbroadcast");   // private name → no inbound collision with core handlers
    }

    // ── Control ──────────────────────────────────────────────────────────────

    public void setBroadcasting(boolean on) { this.broadcasting = on; }
    public boolean isBroadcasting() { return broadcasting; }

    public void setVideo(String url, String uid, String alias,
                         String address, int port, String path, String protocol) {
        this.url = url; this.uid = uid;
        if (alias != null) this.alias = alias;
        this.address = address; this.port = port;
        this.path = (path == null) ? "" : path;
        this.protocol = (protocol == null) ? "rtsp" : protocol.toLowerCase();
    }

    public void setSensor(double fovDeg, double rangeM) {
        this.fovDeg = fovDeg; this.rangeM = rangeM;
    }

    // ── Outbound: decorate the self PLI ──────────────────────────────────────

    @Override
    public boolean toCotDetail(MapItem item, CotEvent event, CotDetail root) {
        if (!broadcasting || url == null) return false;
        MapView mv = MapView.getMapView();
        if (mv == null || item != mv.getSelfMarker()) return false;

        Log.d(TAG, "emitting <sensor>+<__video> on self PLI (uid=" + event.getUID()
                + " type=" + event.getType() + ")");

        // <__video url="..."><ConnectionEntry .../></__video>
        CotDetail video = new CotDetail(VIDEO);
        video.setAttribute("uid", uid == null ? "" : uid);
        video.setAttribute("url", url);

        CotDetail ce = new CotDetail(CONNECTION_ENTRY);
        ce.setAttribute("uid", uid == null ? "" : uid);
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
        root.addChild(video);

        // <sensor fov=".." azimuth=".." range=".." .../> — added ONLY to this outbound
        // CoT copy, never to the live self marker's metadata, so peers see the FOV wedge
        // without us ever mutating (and risking corrupting) the local self marker.
        double az = 0;
        if (item instanceof Marker) {
            double h = ((Marker) item).getTrackHeading();
            if (!Double.isNaN(h)) az = h;
        }
        CotDetail sensor = new CotDetail(SENSOR);
        sensor.setAttribute("fov", fmt(fovDeg));
        sensor.setAttribute("azimuth", fmt(az));
        sensor.setAttribute("range", fmt(rangeM));
        sensor.setAttribute("fovRed", "0.0");
        sensor.setAttribute("fovGreen", "0.6");
        sensor.setAttribute("fovBlue", "1.0");
        sensor.setAttribute("fovAlpha", "0.3");
        sensor.setAttribute("displayMagneticReference", "0");
        root.addChild(sensor);

        return true;
    }

    @Override
    public ImportResult toItemMetadata(MapItem item, CotEvent event, CotDetail detail) {
        // We only ever produce these details; inbound handling stays with ATAK core.
        return ImportResult.IGNORE;
    }

    private static String fmt(double d) {
        return Long.toString(Math.round(d));
    }
}
