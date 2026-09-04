package com.atakmap.android.icu.share;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.icu.serve.KlvEncoder;
import com.atakmap.android.icu.serve.TransportManager;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * Samples position (self marker) + orientation (rotation-vector sensor) once a second
 * and pushes a MISB ST 0601 KLV packet to every registered {@link TransportManager}
 * transport via {@link TransportManager#onKlv}, mirroring
 * {@code WinTAK/Services/KlvService.cs}'s 1 Hz cadence.
 *
 * <p>Unlike WinTAK (no IMU on a PC), this reads real pitch/roll off the phone's
 * {@code TYPE_ROTATION_VECTOR} sensor. Heading still comes from the self marker's GPS
 * track (same source {@code StreamSensorMarker}/{@code SelfMarkerFov} already use) rather
 * than the compass azimuth — track heading is the more meaningful "platform heading" for
 * a handheld device that isn't necessarily pointed the way it's moving.</p>
 */
public class KlvTelemetryEmitter implements SensorEventListener {

    private static final String TAG = "ICU.KlvTelemetry";
    private static final long INTERVAL_MS = 1000; // 1 Hz, matches WinTAK's KlvService

    // Fixed FOV, matching SelfMarkerFov's FOV_DEG convention (no per-device optical lookup;
    // consistent with how the rest of this plugin already represents the camera's FOV).
    private static final double HFOV_DEG = 60;
    private static final double VFOV_DEG = 34;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Carries the finished packet out to the transports off the UI thread.
     *
     * <p>{@code RtspServer.sendKlvUnit} ends in a blocking {@code DatagramSocket.send}, and
     * Android's StrictMode throws {@link android.os.NetworkOnMainThreadException} for network
     * I/O on the main looper — which silently dropped every telemetry packet ever emitted.
     * The 1 Hz tick still samples the self marker and sensors on the main thread, where
     * ATAK's map objects expect to be touched; only the send is moved here.</p>
     */
    private java.util.concurrent.ExecutorService sender;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private volatile double pitchDeg;
    private volatile double rollDeg;

    private MapView mapView;
    private TransportManager transports;
    private String sensorName = "ATAK-ICU";
    private volatile boolean active;

    /** Begin 1 Hz KLV telemetry, feeding {@code transports} until {@link #stop}. */
    public void start(Context ctx, MapView mapView, TransportManager transports, String sensorName) {
        if (active) return;
        this.mapView = mapView;
        this.transports = transports;
        if (sensorName != null && !sensorName.trim().isEmpty()) this.sensorName = sensorName.trim();
        active = true;
        sender = java.util.concurrent.Executors.newSingleThreadExecutor();

        sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationSensor != null)
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
        handler.post(tick);
    }

    public void stop() {
        if (!active) return;
        active = false;
        handler.removeCallbacks(tick);
        if (sender != null) { sender.shutdownNow(); sender = null; }
        if (sensorManager != null) sensorManager.unregisterListener(this);
        sensorManager = null;
        rotationSensor = null;
        pitchDeg = 0;
        rollDeg = 0;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            // Log the throwable, not just its message: this fires once a second, and an
            // NPE's message is null on Android, so message-only logging says nothing at all
            // about where telemetry is failing.
            try { emit(); } catch (Exception e) { Log.w(TAG, "emit failed", e); }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void emit() {
        if (mapView == null || transports == null) return;
        Marker self = mapView.getSelfMarker();
        GeoPoint p = (self != null) ? self.getPoint() : null;
        if (p == null || !p.isValid()) return;

        double heading = 0;
        double h = self.getTrackHeading();
        if (!Double.isNaN(h)) heading = h;

        double alt = p.getAltitude();
        if (Double.isNaN(alt)) alt = 0;

        final byte[] klv = KlvEncoder.build(p.getLatitude(), p.getLongitude(), alt,
                heading, pitchDeg, rollDeg, HFOV_DEG, VFOV_DEG, sensorName);
        final long ptsUs = System.nanoTime() / 1000L;
        final TransportManager sinks = transports;
        java.util.concurrent.ExecutorService s = sender;
        if (s == null || s.isShutdown()) return;
        s.execute(new Runnable() {
            @Override public void run() {
                // Never let a transport failure kill the emitter thread — the next sample
                // is only a second away.
                try { sinks.onKlv(klv, ptsUs); }
                catch (Exception e) { Log.w(TAG, "klv send failed", e); }
            }
        });
    }

    // ── SensorEventListener ──────────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        // orientation[] = azimuth, pitch, roll (radians). Azimuth is ignored — heading
        // comes from GPS track above — we only need pitch/roll here.
        pitchDeg = Math.toDegrees(orientation[1]);
        rollDeg  = Math.toDegrees(orientation[2]);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
