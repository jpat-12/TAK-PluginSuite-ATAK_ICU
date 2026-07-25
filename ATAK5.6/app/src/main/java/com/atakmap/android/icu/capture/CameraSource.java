package com.atakmap.android.icu.capture;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.view.Surface;


import com.atakmap.coremap.log.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * PHASE 1 — Camera2 capture.
 *
 * <p>Opens the selected camera and drives up to two output Surfaces simultaneously
 * (Option A in ARCHITECTURE.md §4): the {@link H264Encoder} input Surface (→ RTSP in
 * later phases) and an optional preview Surface for the local operator pane.</p>
 *
 * <p>This is the piece the drone normally provided; here the phone becomes the source.
 * Caller must have already been granted {@code android.permission.CAMERA}.</p>
 */
public class CameraSource {

    private static final String TAG = "ICU.CameraSource";

    public interface Callback {
        void onError(String message);
    }

    /** Result of a still capture (JPEG bytes), delivered off the camera thread. */
    public interface StillCallback {
        void onStill(byte[] jpeg);
        void onStillError(String message);
    }

    private CameraDevice         cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread        cameraThread;
    private Handler              cameraHandler;
    private final Semaphore      cameraLock = new Semaphore(1);

    private Surface encoderSurface;
    private Surface previewSurface;
    private ImageReader stillReader;                 // always-on JPEG target for snapshots
    private volatile StillCallback pendingStill;     // callback for the in-flight capture
    private int     fps = 30;
    private volatile int sensorOrientation = 0;
    private volatile boolean frontFacing = false;

    /** Camera sensor mount orientation (0/90/180/270) — for the preview transform. */
    public int getSensorOrientation() { return sensorOrientation; }
    public boolean isFrontFacing() { return frontFacing; }

    /**
     * Open the camera and begin the repeating capture into the given surfaces.
     *
     * @param encoderSurface required — from {@link H264Encoder#start}
     * @param previewSurface optional — may be null (encoder-only)
     */
    public void start(Context ctx, EncoderConfig config,
                      Surface encoderSurface, Surface previewSurface, Callback cb) {
        this.encoderSurface = encoderSurface;
        this.previewSurface = previewSurface;
        this.fps            = config.fps;

        cameraThread  = new HandlerThread("ICU-CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        // Always-on JPEG target so snapshots work even when the on-screen preview
        // (drop-down pane) is closed. A dedicated still target, not the preview.
        stillReader = ImageReader.newInstance(
                config.resolution.w, config.resolution.h, ImageFormat.JPEG, 2);
        stillReader.setOnImageAvailableListener(reader -> {
            Image img = reader.acquireLatestImage();
            if (img == null) return;
            StillCallback stillCb = pendingStill;
            pendingStill = null;
            try {
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                if (stillCb != null) stillCb.onStill(bytes);
            } catch (Exception e) {
                if (stillCb != null) stillCb.onStillError("read still: " + e.getMessage());
            } finally {
                img.close();
            }
        }, cameraHandler);

        CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = selectCamera(manager, config.useFrontCamera);
            if (cameraId == null) { cb.onError("No camera found"); return; }
            if (!cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                cb.onError("Timed out acquiring camera");
                return;
            }
            // SecurityException here means the CAMERA permission wasn't actually granted.
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraLock.release();
                    cameraDevice = camera;
                    startCaptureSession(camera, cb);
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    cameraLock.release();
                    camera.close();
                    cameraDevice = null;
                }
                @Override public void onError(CameraDevice camera, int error) {
                    cameraLock.release();
                    camera.close();
                    cameraDevice = null;
                    cb.onError("Camera error " + error);
                }
            }, cameraHandler);
        } catch (SecurityException e) {
            cameraLock.release();
            cb.onError("Camera permission not granted");
        } catch (Exception e) {
            cameraLock.release();
            cb.onError("openCamera: " + e.getMessage());
        }
    }

    /**
     * Swap the preview target on an already-running capture session without touching
     * the encoder surface — used when the drop-down pane's TextureView is torn down
     * (dropdown closed) or recreated (dropdown reopened) while broadcasting continues
     * in the background. {@code preview} may be null to drop the preview target
     * entirely (encoder-only). A no-op before {@link #start} has completed.
     */
    public void setPreviewSurface(Surface preview) {
        this.previewSurface = preview;
        if (cameraDevice == null || cameraHandler == null) return;
        cameraHandler.post(() -> startCaptureSession(cameraDevice,
                message -> Log.w(TAG, "setPreviewSurface: " + message)));
    }

    public void stop() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } }
        catch (Exception ignored) {}
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } }
        catch (Exception ignored) {}
        try { if (stillReader != null) { stillReader.close(); stillReader = null; } }
        catch (Exception ignored) {}
        pendingStill = null;
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    /**
     * Capture a single JPEG still from the live camera session — works whether or not a
     * preview is attached (so snapshots succeed with the plugin panel closed). Delivered
     * on the camera thread via {@code cb}.
     *
     * @param jpegOrientation EXIF orientation degrees (0/90/180/270) to bake into the JPEG.
     */
    public void captureStill(int jpegOrientation, StillCallback cb) {
        final CameraCaptureSession session = captureSession;
        final CameraDevice camera = cameraDevice;
        if (session == null || camera == null || stillReader == null || cameraHandler == null) {
            cb.onStillError("camera not running");
            return;
        }
        pendingStill = cb;
        cameraHandler.post(() -> {
            try {
                CaptureRequest.Builder req =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                req.addTarget(stillReader.getSurface());
                req.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
                session.capture(req.build(), null, cameraHandler);
            } catch (Exception e) {
                pendingStill = null;
                cb.onStillError("captureStill: " + e.getMessage());
            }
        });
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void startCaptureSession(CameraDevice camera, Callback cb) {
        // Reconfiguring (e.g. preview surface swapped) — drop the old session first so
        // its repeating request doesn't keep referencing a torn-down Surface.
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
        try {
            List<Surface> targets = new ArrayList<>();
            targets.add(encoderSurface);
            if (previewSurface != null) targets.add(previewSurface);
            if (stillReader != null) targets.add(stillReader.getSurface());  // snapshot target

            camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder req =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        req.addTarget(encoderSurface);
                        if (previewSurface != null) req.addTarget(previewSurface);
                        req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                new Range<>(fps, fps));
                        session.setRepeatingRequest(req.build(), null, cameraHandler);
                    } catch (CameraAccessException e) {
                        cb.onError("Repeating request: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    cb.onError("Capture session configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            cb.onError("createCaptureSession: " + e.getMessage());
        }
    }

    private String selectCamera(CameraManager manager, boolean useFront)
            throws CameraAccessException {
        int target = useFront ? CameraCharacteristics.LENS_FACING_FRONT
                              : CameraCharacteristics.LENS_FACING_BACK;
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            android.hardware.camera2.CameraCharacteristics ch =
                    manager.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == target) {
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) sensorOrientation = so;
                frontFacing = (facing == CameraCharacteristics.LENS_FACING_FRONT);
                return id;
            }
        }
        return ids.length > 0 ? ids[0] : null;
    }
}
