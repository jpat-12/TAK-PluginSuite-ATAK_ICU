package com.atakmap.android.icu.util;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Compass bearing (0–360°) of the direction the phone's <b>back camera</b> is pointing,
 * derived from the fused rotation-vector sensor.
 *
 * <p>Used to aim the broadcast FOV wedge where the camera is actually looking — track
 * heading only reflects direction of movement, so it's useless when the operator stands
 * still and pans the camera.</p>
 */
public final class CompassHeading implements SensorEventListener {

    private final SensorManager sm;
    private final Sensor rotationVector;
    private final float[] rot = new float[9];

    private volatile double azimuthDeg = 0;
    private volatile boolean hasReading;
    private boolean registered;

    public CompassHeading(Context ctx) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        rotationVector = (sm != null) ? sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
    }

    public void start() {
        if (sm != null && rotationVector != null && !registered) {
            sm.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI);
            registered = true;
        }
    }

    public void stop() {
        if (sm != null && registered) {
            sm.unregisterListener(this);
            registered = false;
        }
        hasReading = false;
    }

    public boolean hasReading() { return hasReading; }
    public double azimuth() { return azimuthDeg; }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor == null || e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        // The back camera points along the device -Z axis. Its world components are
        // -(rot[2], rot[5], rot[8]) with X=East, Y=North. Bearing = atan2(east, north).
        double east  = -rot[2];
        double north = -rot[5];
        double az = Math.toDegrees(Math.atan2(east, north));
        if (az < 0) az += 360;
        azimuthDeg = az;
        hasReading = true;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
