package com.atakmap.android.icu.ui.qr;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.atakmap.coremap.log.Log;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Full-screen {@link Dialog} that opens the camera and decodes a QR code with
 * ZXing, delivering the result via a plain in-process {@link Callback} — modeled
 * on QuickCapture's {@code QrScanDialog} (see
 * {@code ATAK-Plugin-QuickCapture/app/src/main/java/.../QrScanDialog.java}).
 *
 * <p>Deliberately <b>not</b> a separate Activity. The plugin's original scanner was
 * a plugin-hosted {@code Activity} launched with {@code FLAG_ACTIVITY_NEW_TASK},
 * with the result routed back over a same-package {@code BroadcastReceiver} — extra
 * moving parts (a second window/task, manifest registration, exported-component
 * flags to get right on API 33+) for a feature that never needed to leave the
 * calling DropDownReceiver's own process. A Dialog owned directly by the settings
 * dialog's context has none of that: no manifest entry, no broadcast, no
 * classloader boundary to cross — just a constructor callback.</p>
 */
public class QrScanDialog extends Dialog {

    private static final String TAG = "ICU.QrScanDialog";

    public interface Callback {
        void onQrDecoded(String text);
    }

    // Higher than a bare minimum decode target so the live preview isn't visibly
    // blocky on modern high-DPI screens — still comfortably within every device's
    // supported YUV_420_888 stream configs.
    private static final int PREVIEW_W = 1920;
    private static final int PREVIEW_H = 1080;

    // Static/shared across instances (not per-dialog): cameraDevice.close() is
    // asynchronous, so if the dialog is closed and immediately reopened, a fresh
    // instance's openCamera() can race the previous instance's still-in-flight
    // teardown — black preview, then a crash. Held for the camera's full
    // open-through-close lifetime (released in closeCamera(), not on open) so a
    // reopen genuinely waits for the previous session to finish releasing it.
    private static final Semaphore cameraLock = new Semaphore(1);

    private final Callback callback;
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextureView previewView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Surface previewSurface;
    private volatile boolean decoded;
    private boolean cameraLockHeld;

    // The one manual "Orientation" setting shared with the streaming pipeline
    // (ICUVideoDropDownReceiver's Settings dialog / EncoderConfig.rotationDegrees) —
    // one of {0, 90, 180, 270}. Deliberately not auto-detected: ATAK's host Activity is
    // orientation-locked, so there's no reliable live signal to derive this from, and
    // accelerometer-based guessing (OrientationEventListener + SENSOR_ORIENTATION) went
    // through two rounds of "still wrong" before being replaced with this fixed value
    // that the user picks once and both the scanner and the stream honor.
    private final int rotationDegrees;

    public QrScanDialog(Context atakContext, int rotationDegrees, Callback callback) {
        super(atakContext);
        this.callback = callback;
        this.rotationDegrees = rotationDegrees;

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
        reader.setHints(hints);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (getWindow() != null) {
            getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void show() {
        if (getContext().checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Caller is expected to have already requested CAMERA before constructing
            // this dialog (see ICUVideoDropDownReceiver's Scan QR button) — this is a
            // last-resort guard, not the primary permission flow.
            Log.w(TAG, "show() without CAMERA permission");
            return;
        }
        super.show();
        startCameraThread();
    }

    // ── UI (fully programmatic — no plugin layout resources to resolve here,
    //      matching the rest of this plugin's dialogs) ─────────────────────────

    private View buildUi() {
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.BLACK);

        previewView = new TextureView(getContext());
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                previewSurface = new Surface(st);
                configureTransform();
                maybeStartCaptureSession();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                configureTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                previewSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });

        float d = getContext().getResources().getDisplayMetrics().density;

        TextView hint = new TextView(getContext());
        hint.setText("Point the camera at the Stream URL QR code");
        hint.setTextColor(Color.WHITE);
        hint.setBackgroundColor(0x99000000);
        hint.setPadding((int) (16 * d), (int) (10 * d), (int) (16 * d), (int) (10 * d));
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hintLp.topMargin = (int) (24 * d);
        root.addView(hint, hintLp);

        Button cancel = new Button(getContext());
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> dismiss());
        FrameLayout.LayoutParams cancelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cancelLp.bottomMargin = (int) (32 * d);
        root.addView(cancel, cancelLp);

        return root;
    }

    // A plain View.setRotation() looked "stretched" — TextureView stretches its buffer
    // to fill its own bounds by default (ignoring aspect ratio), and PREVIEW_W x
    // PREVIEW_H (1920x1080, landscape) is being force-fit into this fullscreen
    // *portrait* view; rotating that already-distorted content only made it more
    // visible. This builds a real transform matrix instead — the standard Camera2
    // TextureView pattern (same one QuickCapture's scanner uses): scale-to-fill
    // (cropping instead of stretching) computed against the buffer's *true* aspect
    // ratio, then rotate around the view's center. The rotation itself is just the
    // user-picked Orientation setting (see the field comment on rotationDegrees) —
    // no sensor math involved.
    private void configureTransform() {
        if (previewView == null) return;
        int viewWidth = previewView.getWidth();
        int viewHeight = previewView.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;

        int rotationDeg = rotationDegrees;

        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if (rotationDeg == 90 || rotationDeg == 270) {
            // Buffer's on-screen shape swaps to portrait-like once rotated upright —
            // build bufferRect pre-swapped so setRectToRect scales against its real
            // (rotated) aspect ratio instead of its raw landscape one.
            RectF bufferRect = new RectF(0, 0, PREVIEW_H, PREVIEW_W);
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / PREVIEW_H, (float) viewWidth / PREVIEW_W);
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(rotationDeg, centerX, centerY);
        } else if (rotationDeg == 180) {
            matrix.postRotate(180, centerX, centerY);
        }
        // rotationDeg == 0: identity — no correction needed.

        previewView.setTransform(matrix);
    }

    // ── Camera2 (decode-only ImageReader + a visible TextureView preview) ─────────

    private void startCameraThread() {
        cameraThread = new HandlerThread("ICU-QRCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        imageReader = ImageReader.newInstance(PREVIEW_W, PREVIEW_H, ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(this::onFrame, cameraHandler);

        CameraManager manager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = selectBackCamera(manager);
            if (cameraId == null) { Log.w(TAG, "No camera available"); dismiss(); return; }

            if (!cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for camera (previous session still closing?)");
                mainHandler.post(this::dismiss);
                return;
            }
            cameraLockHeld = true;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    maybeStartCaptureSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    mainHandler.post(QrScanDialog.this::dismiss);
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    Log.w(TAG, "Camera error " + error);
                    mainHandler.post(QrScanDialog.this::dismiss);
                }
            }, cameraHandler);
        } catch (SecurityException | CameraAccessException | InterruptedException
                | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "startCameraThread: " + e.getMessage());
            mainHandler.post(this::dismiss);
        }
    }

    // Called from two different threads that can each fire independently — the main
    // thread (TextureView's onSurfaceTextureAvailable) and the camera background
    // thread (CameraDevice.StateCallback.onOpened). Whichever happens second must be
    // a no-op, or createCaptureSession() gets called twice on the same device and
    // Camera2 throws IllegalStateException — uncaught, that kills the whole app since
    // it happens off the main thread's exception handling.
    private synchronized void maybeStartCaptureSession() {
        if (cameraDevice == null || imageReader == null || captureSession != null) return;
        try {
            // Snapshot the exact Surface references the session is negotiated with —
            // onConfigured() fires asynchronously, and if the TextureView's
            // SurfaceTexture is destroyed/recreated in the meantime (e.g. a rotation
            // triggering a relayout), the mutable previewSurface field would then point
            // at a *different* Surface than the one actually configured, and Camera2
            // throws "CaptureRequest contains unconfigured Input/Output Surface!".
            final Surface sessionImageSurface = imageReader.getSurface();
            final Surface sessionPreviewSurface = previewSurface;

            List<Surface> targets = new ArrayList<>();
            targets.add(sessionImageSurface);
            if (sessionPreviewSurface != null) targets.add(sessionPreviewSurface);

            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder req = cameraDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                        req.addTarget(sessionImageSurface);
                        if (sessionPreviewSurface != null) req.addTarget(sessionPreviewSurface);
                        req.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(req.build(), null, cameraHandler);
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.w(TAG, "setRepeatingRequest: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    Log.w(TAG, "QR capture session configuration failed");
                    mainHandler.post(QrScanDialog.this::dismiss);
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "createCaptureSession: " + e.getMessage());
            mainHandler.post(this::dismiss);
        }
    }

    private String selectBackCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics ch = manager.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return ids.length > 0 ? ids[0] : null;
    }

    // ── Decode ──────────────────────────────────────────────────────────────────

    private void onFrame(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            if (decoded) return;

            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer buffer = yPlane.getBuffer();
            int rowStride = yPlane.getRowStride();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    data, rowStride, image.getHeight(), 0, 0,
                    image.getWidth(), image.getHeight(), false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            try {
                Result result = this.reader.decodeWithState(bitmap);
                deliverResult(result.getText());
            } catch (NotFoundException notFound) {
                // no QR in this frame — normal while aiming, just try the next one
            } finally {
                this.reader.reset();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onFrame decode failed: " + t.getMessage());
        } finally {
            image.close();
        }
    }

    private void deliverResult(String text) {
        if (decoded) return;
        decoded = true;
        mainHandler.post(() -> {
            dismiss();
            callback.onQrDecoded(text);
        });
    }

    // ── Teardown ────────────────────────────────────────────────────────────────

    @Override
    public void dismiss() {
        decoded = true; // stop any in-flight frame from firing a second callback
        closeCamera();
        super.dismiss();
    }

    // synchronized against maybeStartCaptureSession() — dismiss() can run on the main
    // thread while a capture-session callback is still in flight on the camera thread.
    private synchronized void closeCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } }
        catch (Exception ignored) {}
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } }
        catch (Exception ignored) {}
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; cameraHandler = null; }
        // Only give up the shared lock here, once teardown is actually done — this is
        // what lets a reopened dialog's tryAcquire() correctly wait instead of racing.
        if (cameraLockHeld) { cameraLockHeld = false; cameraLock.release(); }
    }
}
