package com.atakmap.android.icu.capture;

import android.content.Context;
import android.hardware.usb.UsbDevice;
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
        cameraHelper = new CameraHelper();
        cameraHelper.setStateCallback(new ICameraHelper.StateCallback() {
            @Override public void onAttach(UsbDevice device) {
                Log.d(TAG, "USB camera attached: " + device.getDeviceName());
                cameraHelper.selectDevice(device);
            }
            @Override public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
                cameraHelper.openCamera();
            }
            @Override public void onCameraOpen(UsbDevice device) {
                Log.d(TAG, "USB camera open: " + device.getDeviceName());
                if (UsbCameraSource.this.encoderSurface != null) {
                    cameraHelper.addSurface(UsbCameraSource.this.encoderSurface, true);
                }
                if (UsbCameraSource.this.previewSurface != null) {
                    cameraHelper.addSurface(UsbCameraSource.this.previewSurface, false);
                }
                cameraHelper.startPreview();
                running = true;
                cb.onOpened();
            }
            @Override public void onCameraClose(UsbDevice device) { running = false; }
            @Override public void onDeviceClose(UsbDevice device) {}
            @Override public void onDetach(UsbDevice device) {
                if (running) { running = false; cb.onError("USB camera disconnected"); }
            }
            @Override public void onCancel(UsbDevice device) {
                cb.onError("USB camera permission denied");
            }
            @Override public void onError(UsbDevice device, CameraException e) {
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
        if (cameraHelper != null) {
            try { cameraHelper.stopPreview(); } catch (Exception ignored) {}
            try { cameraHelper.setStateCallback(null); } catch (Exception ignored) {}
            try { cameraHelper.release(); } catch (Exception ignored) {}
            cameraHelper = null;
        }
        encoderSurface = null;
        previewSurface = null;
    }

    public boolean isRunning() { return running; }
}
