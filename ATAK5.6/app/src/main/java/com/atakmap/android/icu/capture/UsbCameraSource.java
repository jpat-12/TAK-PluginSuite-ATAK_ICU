package com.atakmap.android.icu.capture;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.atakmap.coremap.log.Log;
import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.utils.UVCUtils;

import java.util.List;

/**
 * UVC (USB webcam) capture source via {@code com.herohan:UVCAndroid} — a non-rooted
 * USB-host UVC driver (USBMonitor + libusb/libuvc). Parallel to {@link CameraSource}:
 * feeds an already-attached USB camera into the same encoder/preview {@link Surface}s the
 * Camera2 path uses, so {@link CapturePipeline} doesn't need to know which source is active.
 *
 * <p>Most Android builds do <b>not</b> surface a USB/UVC camera through {@code CameraManager}
 * — unlike Camera2, this talks to the USB device directly, prompting the OS's standard
 * "allow this app to access the USB device" permission dialog the first time a given camera
 * is plugged in for this app.</p>
 */
public class UsbCameraSource {

    private static final String TAG = "ICU.UsbCameraSource";

    public interface Callback {
        void onError(String message);
        /** Fired once a device is attached, permitted, opened, and streaming. */
        default void onOpened() {}
    }

    /** How long to wait for a USB camera to actually open before reporting "not found" —
     *  register()/onAttach are async with no other signal if nothing is plugged in. */
    private static final long CONNECT_TIMEOUT_MS = 5000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ICameraHelper cameraHelper;
    private Surface encoderSurface;
    private Surface previewSurface;
    private volatile boolean running;

    /** Quick check for the settings UI: is any USB device attached at all? Doesn't confirm
     *  it's a camera or that permission has been granted — just whether it's worth offering
     *  "USB camera" as an option. */
    public static boolean hasAttachedDevice(Context ctx) {
        try {
            UVCUtils.init(ctx.getApplicationContext());
            CameraHelper probe = new CameraHelper();
            List<UsbDevice> devices = probe.getDeviceList();
            probe.release();
            return devices != null && !devices.isEmpty();
        } catch (Exception e) {
            Log.w(TAG, "hasAttachedDevice: " + e.getMessage());
            return false;
        }
    }

    public void start(Context ctx, Surface encoderSurface, Surface previewSurface, Callback cb) {
        this.encoderSurface = encoderSurface;
        this.previewSurface = previewSurface;

        UVCUtils.init(ctx.getApplicationContext());
        final ICameraHelper helper = new CameraHelper();
        cameraHelper = helper;

        // register()/onAttach are async with no other signal if nothing is (or never gets)
        // plugged in — without this, picking "USB camera" with no device attached would
        // leave the pipeline silently "broadcasting" a black frame forever.
        final Runnable connectTimeout = () -> {
            if (cameraHelper == helper && !running) {
                Log.w(TAG, "no USB camera opened within " + CONNECT_TIMEOUT_MS + "ms");
                cb.onError("No USB camera detected — check the connection");
            }
        };
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);

        helper.setStateCallback(new ICameraHelper.StateCallback() {
            // Every callback below fires on the main thread (StateCallbackWrapper posts to
            // it), same thread stop() runs on — but stop() may have already nulled/replaced
            // cameraHelper by the time a callback queued just before it finally runs, so
            // guard against acting on a stale/superseded helper instance.
            private boolean isCurrent() { return cameraHelper == helper; }

            @Override public void onAttach(UsbDevice device) {
                if (!isCurrent()) return;
                Log.d(TAG, "USB camera attached: " + device.getDeviceName());
                helper.selectDevice(device);
            }
            @Override public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
                if (!isCurrent()) return;
                helper.openCamera();
            }
            @Override public void onCameraOpen(UsbDevice device) {
                if (!isCurrent()) return;
                Log.d(TAG, "USB camera open: " + device.getDeviceName());
                mainHandler.removeCallbacks(connectTimeout);
                if (UsbCameraSource.this.encoderSurface != null) {
                    helper.addSurface(UsbCameraSource.this.encoderSurface, true);
                }
                if (UsbCameraSource.this.previewSurface != null) {
                    helper.addSurface(UsbCameraSource.this.previewSurface, false);
                }
                helper.startPreview();
                running = true;
                cb.onOpened();
            }
            @Override public void onCameraClose(UsbDevice device) { running = false; }
            @Override public void onDeviceClose(UsbDevice device) {}
            @Override public void onDetach(UsbDevice device) {
                if (!isCurrent()) return;
                if (running) { running = false; cb.onError("USB camera disconnected"); }
            }
            @Override public void onCancel(UsbDevice device) {
                if (!isCurrent()) return;
                mainHandler.removeCallbacks(connectTimeout);
                cb.onError("USB camera permission denied");
            }
            @Override public void onError(UsbDevice device, CameraException e) {
                if (!isCurrent()) return;
                mainHandler.removeCallbacks(connectTimeout);
                running = false;
                cb.onError("USB camera error: " + e.getMessage());
            }
        });
        // register() (triggered by setStateCallback above) replays onAttach for any device
        // already plugged in, as well as future hotplugs — no manual enumeration needed.
    }

    /** Swap the preview target without touching the encoder feed — mirrors
     *  {@link CameraSource#setPreviewSurface}. No-op before {@link #start} completes. */
    public void setPreviewSurface(Surface preview) {
        Surface old = previewSurface;
        previewSurface = preview;
        if (cameraHelper == null || !running) return;
        if (old != null) cameraHelper.removeSurface(old);
        if (preview != null) cameraHelper.addSurface(preview, false);
    }

    public void stop() {
        running = false;
        ICameraHelper helper = cameraHelper;
        cameraHelper = null;   // invalidates isCurrent() for any callback already queued
        if (helper != null) {
            try { helper.stopPreview(); } catch (Exception ignored) {}
            try { helper.setStateCallback(null); } catch (Exception ignored) {}
            try { helper.release(); } catch (Exception ignored) {}
        }
        encoderSurface = null;
        previewSurface = null;
    }

    public boolean isRunning() { return running; }
}
