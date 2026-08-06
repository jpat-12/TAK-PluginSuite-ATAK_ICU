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

    /** One Camera2 device as reported by {@link #listCameras}. */
    public static class CameraOption {
        public final String id;
        public final String label;
        public final int facing;   // CameraCharacteristics.LENS_FACING_*
        CameraOption(String id, String label, int facing) {
            this.id = id; this.label = label; this.facing = facing;
        }
    }

    /**
     * Synthetic entry for the settings picker representing the direct UVC (USB) capture
     * path — {@link UsbCameraSource} — for USB cameras Camera2 doesn't surface at all
     * (the common case; see {@link #listCameras} for the Camera2-visible case).
     */
    public static CameraOption usbOption() {
        return new CameraOption(com.atakmap.android.icu.capture.EncoderConfig.CAMERA_ID_USB,
                "USB camera (UVC)", CameraCharacteristics.LENS_FACING_EXTERNAL);
    }

    /**
     * Enumerate every camera Camera2 exposes, including USB/UVC webcams that the OS
     * surfaces as {@code LENS_FACING_EXTERNAL} devices (some Android builds with USB
     * host + UVC support auto-register a plugged-in camera this way — no vendor SDK
     * needed). Returns an empty list on any CameraManager failure.
     */
    public static List<CameraOption> listCameras(Context ctx) {
        List<CameraOption> out = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            int externalCount = 0;
            for (String id : ids) {
                CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                int f = facing != null ? facing : CameraCharacteristics.LENS_FACING_BACK;
                String label;
                if (f == CameraCharacteristics.LENS_FACING_FRONT) {
                    label = "Front camera";
                } else if (f == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    externalCount++;
                    label = externalCount > 1
                            ? "External camera (USB) " + externalCount : "External camera (USB)";
                } else {
                    label = "Rear camera";
                }
                out.add(new CameraOption(id, label, f));
            }
        } catch (Exception e) {
            Log.w(TAG, "listCameras: " + e.getMessage());
        }
        return out;
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

    /**
     * Bumped on every {@link #startCaptureSession} call. Configuring a session is async, so
     * a rebuild (preview attach/detach) can be issued while an earlier one is still in
     * flight; without this, the older {@code onConfigured} lands last and installs a session
     * whose repeating request points at a surface we already moved on from — the preview
     * freezes on its last frame. Callbacks compare their generation and drop if superseded.
     */
    private final java.util.concurrent.atomic.AtomicInteger sessionGen =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Counts down when the camera device has actually finished closing. {@code close()} is
     * asynchronous — the HAL still holds the camera when it returns — so a stop/start pair
     * (switching cameras from Settings) would try to reopen while the previous device was
     * still shutting down and fail with "Timed out acquiring camera". {@link #stop} waits
     * on this so the restart is clean.
     */
    private volatile java.util.concurrent.CountDownLatch closeLatch;
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
            String cameraId = selectCamera(manager, config);
            if (cameraId == null) { cb.onError("No camera found"); return; }
            if (!cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                cb.onError("Timed out acquiring camera");
                return;
            }
            closeLatch = new java.util.concurrent.CountDownLatch(1);
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
                @Override public void onClosed(CameraDevice camera) {
                    // The HAL has really let go now — stop() can stop waiting.
                    java.util.concurrent.CountDownLatch l = closeLatch;
                    if (l != null) l.countDown();
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

    /** How long {@link #stop} waits for the camera device to finish closing before giving
     *  up and tearing down anyway — bounds the worst case if {@code onClosed} never lands. */
    private static final long CLOSE_TIMEOUT_MS = 1500;

    public void stop() {
        // Invalidate any session callback still in flight so it can't resurrect a session
        // against surfaces we're about to drop.
        sessionGen.incrementAndGet();
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } }
        catch (Exception ignored) {}

        java.util.concurrent.CountDownLatch l = closeLatch;
        boolean wasOpen = cameraDevice != null;
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } }
        catch (Exception ignored) {}
        // Block until the HAL confirms the release. Without this, an immediate restart on a
        // different camera id (Settings → Save while live) races the close and fails to open.
        // onClosed arrives on the camera thread, so waiting here can't deadlock it.
        if (wasOpen && l != null) {
            try {
                if (!l.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    Log.w(TAG, "camera did not report closed within " + CLOSE_TIMEOUT_MS + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeLatch = null;

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
        final int gen = sessionGen.incrementAndGet();
        // Snapshot the targets for this generation — the fields can change under us while
        // configuration is in flight, and the request must match the session it configured.
        final Surface encTarget = encoderSurface;
        final Surface prevTarget = previewSurface;
        try {
            List<Surface> targets = new ArrayList<>();
            targets.add(encTarget);
            if (prevTarget != null) targets.add(prevTarget);
            if (stillReader != null) targets.add(stillReader.getSurface());  // snapshot target

            camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                /** False once a newer rebuild (or stop) has superseded this one. */
                private boolean isCurrent() { return gen == sessionGen.get(); }

                @Override public void onConfigured(CameraCaptureSession session) {
                    if (!isCurrent()) {
                        // A newer session is already being configured; installing this one
                        // would leave the repeating request on stale surfaces.
                        try { session.close(); } catch (Exception ignored) {}
                        return;
                    }
                    captureSession = session;
                    try {
                        CaptureRequest.Builder req =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        req.addTarget(encTarget);
                        if (prevTarget != null) req.addTarget(prevTarget);
                        req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                new Range<>(fps, fps));
                        session.setRepeatingRequest(req.build(), null, cameraHandler);
                    } catch (CameraAccessException | IllegalStateException e) {
                        cb.onError("Repeating request: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    if (!isCurrent()) return;
                    cb.onError("Capture session configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            cb.onError("createCaptureSession: " + e.getMessage());
        }
    }

    private String selectCamera(CameraManager manager, EncoderConfig config)
            throws CameraAccessException {
        String id = (config.cameraId != null && !config.cameraId.isEmpty())
                ? config.cameraId : findCameraId(manager, config.useFrontCamera);
        if (id != null) {
            android.hardware.camera2.CameraCharacteristics ch = manager.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (so != null) sensorOrientation = so;
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        }
        return id;
    }

    private static String findCameraId(CameraManager manager, boolean useFront)
            throws CameraAccessException {
        int target = useFront ? CameraCharacteristics.LENS_FACING_FRONT
                              : CameraCharacteristics.LENS_FACING_BACK;
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == target) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    /**
     * Pick a capture size (landscape, w ≥ h) that shows the camera's <b>full field of view</b>
     * at roughly {@code targetHeight}.
     *
     * <p>The sensor crops to whatever aspect ratio you ask it for, so the aspect — not the
     * resolution — is what decides how much of the scene viewers get. This never assumes a
     * ratio: it derives the full-FOV shape from the camera itself and then picks the closest
     * supported size at the caller's height budget. {@code targetHeight} is a quality budget
     * only (the operator's 480p/720p/1080p choice); it never constrains the shape.</p>
     *
     * <p>Reads characteristics without opening the device, so the encoder, the GL stage and
     * the on-screen preview can all be sized from one answer before capture starts — which is
     * what keeps the pane and the stream showing the same frame.</p>
     */
    public static int[] chooseNativeCaptureSize(Context ctx, boolean useFront, int targetHeight) {
        return chooseNativeCaptureSize(ctx, null, useFront, targetHeight);
    }

    /** Overload that pins the query to a specific camera id (e.g. an external/USB camera)
     *  when {@code cameraId} is non-empty; otherwise falls back to the front/back logic. */
    public static int[] chooseNativeCaptureSize(Context ctx, String cameraId, boolean useFront, int targetHeight) {
        // Last-resort shape if the camera tells us nothing at all. Reaching this means we
        // could not determine the sensor's shape, so the stream may be cropped — every path
        // that returns it logs why, because a silent fallback here is invisible FOV loss.
        int fbH = targetHeight, fbW = (targetHeight * 16) / 9;
        try {
            CameraManager m = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = (cameraId != null && !cameraId.isEmpty()) ? cameraId : findCameraId(m, useFront);
            if (id == null) {
                Log.w(TAG, "captureSize: no camera id; using " + fbW + "x" + fbH
                        + " — FOV may be cropped");
                return new int[]{ fbW, fbH };
            }
            CameraCharacteristics ch = m.getCameraCharacteristics(id);

            android.hardware.camera2.params.StreamConfigurationMap map =
                    ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size[] sizes = (map == null)
                    ? null : map.getOutputSizes(android.graphics.SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) {
                Log.w(TAG, "captureSize: camera " + id + " reported no output sizes; using "
                        + fbW + "x" + fbH + " — FOV may be cropped");
                return new int[]{ fbW, fbH };
            }

            // Full-FOV aspect, preferring the authoritative source.
            //   1. SENSOR_INFO_ACTIVE_ARRAY_SIZE — the actual readout rectangle.
            //   2. The largest supported output — the max readout is the uncropped sensor,
            //      so its shape is the shape that loses nothing. Used when (1) is absent,
            //      which is common on external/USB cameras.
            // No hardcoded ratio: guessing 4:3 or 16:9 here is exactly how FOV goes missing.
            android.graphics.Rect arr = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final float nativeAspect;
            final String aspectSrc;
            if (arr != null && arr.height() > 0) {
                nativeAspect = (float) arr.width() / arr.height();
                aspectSrc = "active array " + arr.width() + "x" + arr.height();
            } else {
                android.util.Size largest = null;
                for (android.util.Size s : sizes) {
                    if (largest == null || (long) s.getWidth() * s.getHeight()
                            > (long) largest.getWidth() * largest.getHeight()) largest = s;
                }
                nativeAspect = (float) largest.getWidth() / largest.getHeight();
                aspectSrc = "largest output " + largest.getWidth() + "x" + largest.getHeight();
            }

            android.util.Size best = null;
            float bestAspectErr = Float.MAX_VALUE;
            int bestHeightErr = Integer.MAX_VALUE;
            for (android.util.Size s : sizes) {
                float aspect = (float) s.getWidth() / s.getHeight();   // sizes are landscape
                float aErr = Math.abs(aspect - nativeAspect);
                int hErr = Math.abs(s.getHeight() - targetHeight);
                // Aspect first (it decides FOV), then the closest height to the budget.
                if (aErr < bestAspectErr - 0.01f
                        || (Math.abs(aErr - bestAspectErr) <= 0.01f && hErr < bestHeightErr)) {
                    best = s; bestAspectErr = aErr; bestHeightErr = hErr;
                }
            }
            if (best == null) {
                Log.w(TAG, "captureSize: no usable output size; using " + fbW + "x" + fbH
                        + " — FOV may be cropped");
                return new int[]{ fbW, fbH };
            }

            Log.d(TAG, "captureSize: " + best.getWidth() + "x" + best.getHeight()
                    + " (aspect " + String.format(java.util.Locale.US, "%.3f", nativeAspect)
                    + " from " + aspectSrc + ", target height " + targetHeight
                    + ", " + sizes.length + " sizes offered)");
            if (bestAspectErr > 0.02f) {
                // The camera offers nothing at its own full-FOV shape — the closest match
                // will be cropped. Rare, but the operator deserves to know it's happening.
                Log.w(TAG, "captureSize: closest supported aspect is off by "
                        + String.format(java.util.Locale.US, "%.3f", bestAspectErr)
                        + " — stream will be cropped");
            }
            return new int[]{ best.getWidth(), best.getHeight() };
        } catch (Exception e) {
            Log.w(TAG, "captureSize failed (" + e + "); using " + fbW + "x" + fbH
                    + " — FOV may be cropped", e);
        }
        return new int[]{ fbW, fbH };
    }
}
