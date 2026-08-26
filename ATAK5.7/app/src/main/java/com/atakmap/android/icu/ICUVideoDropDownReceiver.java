package com.atakmap.android.icu;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.icu.capture.CameraSource;
import com.atakmap.android.icu.capture.CapturePipeline;
import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.capture.Mp4Recorder;
import com.atakmap.android.icu.plugin.R;
import com.atakmap.android.icu.serve.MediaServerConfig;
import com.atakmap.android.icu.serve.OnDeviceRtspTransport;
import com.atakmap.android.icu.serve.RtmpPushTransport;
import com.atakmap.android.icu.serve.RtspPushTransport;
import com.atakmap.android.icu.serve.SrtTransport;
import com.atakmap.android.icu.serve.StreamEndpoint;
import com.atakmap.android.icu.serve.TransportManager;
import com.atakmap.android.icu.share.SelfMarkerFov;
import com.atakmap.android.icu.ui.StreamStatusWidget;
import com.atakmap.android.icu.ui.qr.QrScanDialog;
import com.atakmap.android.icu.util.NetworkMonitor;
import com.atakmap.android.icu.util.Prefs;
import com.atakmap.android.icu.util.StreamUrlParser;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.util.List;

/**
 * ATAK-ICU main pane.
 *
 * <p>Broadcasts the phone camera into ATAK. Destination (this device vs. a media
 * server), server address/credentials, and encoding are configured via the Settings
 * (gear) button — mirroring the TAK ICU app.</p>
 */
public class ICUVideoDropDownReceiver extends DropDownReceiver
        implements OnStateListener {

    public static final String TAG  = "ICUVideoDropDown";
    public static final String SHOW = "com.atakmap.android.icu.SHOW_PLUGIN";
    /** Start/stop the broadcast without opening the panel (e.g. from the self-marker
     *  radial menu, or `adb shell am broadcast -a com.atakmap.android.icu.TOGGLE_BROADCAST`). */
    public static final String TOGGLE   = "com.atakmap.android.icu.TOGGLE_BROADCAST";
    /** Snapshot the current frame — headless (self-marker radial). Needs an active preview. */
    public static final String SNAPSHOT = "com.atakmap.android.icu.SNAPSHOT";
    /** Toggle local recording — headless (self-marker radial). */
    public static final String RECORD   = "com.atakmap.android.icu.RECORD";
    /** Black out the screen while keeping it on (so capture keeps running). Tap to wake. */
    public static final String BLACKOUT = "com.atakmap.android.icu.BLACKOUT";

    private static final int REQ_CAMERA = 4711;
    private static final int REQ_MIC    = 4712;

    private final Context pluginContext;
    private final View    root;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final CapturePipeline   pipeline     = new CapturePipeline();
    // Local MP4 capture. Muxes the SAME encoded stream the transports get — see Mp4Recorder
    // for why it doesn't run a second camera/encoder, and what that implies for the operator.
    private final Mp4Recorder       recorder     = new Mp4Recorder();
    private final EncoderConfig     config       = new EncoderConfig();
    private final MediaServerConfig serverConfig = new MediaServerConfig();
    // Injects the FOV + video into the operator's OWN outbound self CoT via
    // CotMapComponent.addAdditionalDetail — the FOV rides on the skittle's own CoT
    // (ATAK renders it), no separate marker. See SelfMarkerFov.
    private final SelfMarkerFov sensor = new SelfMarkerFov();

    // MISB ST 0601 KLV telemetry (position/orientation), muxed into the on-device RTSP
    // transport's second RTP track. See serve/KlvEncoder + serve/RtspServer's KLV track.
    private final com.atakmap.android.icu.share.KlvTelemetryEmitter klv =
            new com.atakmap.android.icu.share.KlvTelemetryEmitter();

    // Registers the stream as a feed on the TAK Server's Video Feed Manager (server DB
    // via /Marti/vcm) when pushing to a server. See VideoConnectionPublisher.
    private final com.atakmap.android.icu.share.VideoConnectionPublisher videoPublisher =
            new com.atakmap.android.icu.share.VideoConnectionPublisher();
    private final StreamStatusWidget statusWidget;

    private TransportManager transports;

    // Watches for Wi-Fi/LTE handovers while live. A broadcast's outbound socket does not
    // survive one; see onNetworkChanged.
    private NetworkMonitor networkMonitor;

    private TextView    statusText;
    private int         defaultStatusColor;    // status text's normal colour (for reset after errors)
    private boolean     authFailureHandled;    // guard so a push auth failure auto-stops only once
    private TextView    destBadge;
    private TextView    authBadge;
    private TextView    previewHint;
    private TextView    broadcastLabel;
    private TextView    recordLabel;
    private View        liveDot;
    private ImageButton broadcastButton;
    private ImageButton blackoutButton;
    private ImageButton recordButton;
    private ImageButton destToggleButton;
    private TextView    destToggleLabel;
    private ImageButton settingsButton;
    private TextureView previewView;
    private volatile Surface previewSurface;
    /** Camera/resolution selection the cached captureW/H belong to; see sizePreviewBuffer. */
    private String      captureSizeKey = "";

    // Settings page (pushed overlay) — see showSettingsPage()/hideSettingsPage().
    private View        settingsPage;
    private LinearLayout settingsContainer;
    private Button      settingsSaveBtn;

    public ICUVideoDropDownReceiver(MapView mapView, Context pluginContext,
            StreamStatusWidget statusWidget) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.statusWidget = statusWidget;

        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);

        statusText      = root.findViewById(R.id.icu_status);
        defaultStatusColor = statusText.getCurrentTextColor();
        destBadge       = root.findViewById(R.id.icu_dest_badge);
        authBadge       = root.findViewById(R.id.icu_auth_badge);
        previewHint     = root.findViewById(R.id.icu_preview_hint);
        broadcastLabel  = root.findViewById(R.id.icu_broadcast_label);
        recordLabel     = root.findViewById(R.id.icu_record_label);
        liveDot         = root.findViewById(R.id.icu_live_dot);
        broadcastButton = root.findViewById(R.id.icu_broadcast_button);
        blackoutButton  = root.findViewById(R.id.icu_blackout_button);
        recordButton    = root.findViewById(R.id.icu_record_button);
        destToggleButton = root.findViewById(R.id.icu_dest_toggle_button);
        destToggleLabel  = root.findViewById(R.id.icu_dest_toggle_label);
        settingsButton  = root.findViewById(R.id.icu_settings_button);

        setupPreview();

        // Persist against the HOST ATAK context, not the plugin context. A plugin
        // context's SharedPreferences are not backed by ATAK's persistent data dir,
        // so they're lost on restart (values only survive in the in-memory cache
        // during the session). atakContext() == getMapView().getContext().
        Prefs.load(atakContext(), serverConfig, config);
        refreshDestBadge();
        refreshDestToggle();
        statusWidget.setEnabled(config.showStatusWidget);
        sensor.clearStaleFov();   // clear any FOV a prior build left stuck on the self marker

        // Settings page (pushed overlay with its own back button).
        settingsPage      = root.findViewById(R.id.icu_settings_page);
        settingsContainer = root.findViewById(R.id.icu_settings_container);
        settingsSaveBtn   = root.findViewById(R.id.icu_settings_save);
        root.findViewById(R.id.icu_settings_back).setOnClickListener(v -> hideSettingsPage());
        root.findViewById(R.id.icu_settings_cancel).setOnClickListener(v -> hideSettingsPage());

        broadcastButton.setOnClickListener(v -> toggleBroadcast());
        blackoutButton.setOnClickListener(v -> showBlackout());
        recordButton.setOnClickListener(v -> takeRecord());
        destToggleButton.setOnClickListener(v -> toggleDestination());
        settingsButton.setOnClickListener(v -> showSettingsPage());

        setRetain(true);
    }

    // ── Preview surface + rotation ───────────────────────────────────────────────

    private void setupPreview() {
        FrameLayout container = root.findViewById(R.id.icu_preview_container);
        previewView = new TextureView(pluginContext);
        container.addView(previewView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                sizePreviewBuffer(st);
                previewSurface = new Surface(st);
                applyPreviewRotation();
                // Re-attach preview to an already-running capture session (dropdown reopened
                // while broadcasting continued in the background).
                pipeline.setPreviewSurface(previewSurface);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                // TextureView resets the buffer to the new view size on every layout pass,
                // so re-pin it — but do NOT rebuild the capture session here. A live session
                // has already latched its buffer size (Camera2 sizes the producer itself, so
                // the reset can't affect the running stream), and rebuilding on every layout
                // tick thrashes the camera into a frozen preview. The pin only has to be
                // right before the *next* configuration, which happens on attach/start.
                sizePreviewBuffer(st);
                applyPreviewRotation();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                // Drop the preview target *before* the Surface goes away so a running
                // capture session doesn't keep a repeating request pointed at a dead
                // Surface (dropdown closing must not interrupt the encoder/transports).
                pipeline.setPreviewSurface(null);
                previewSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
    }

    /**
     * Pin the preview's buffer to the same size the encoder captures at.
     *
     * <p>Camera2 gives each output stream the sensor's full field of view only when that
     * output's aspect ratio matches the native (full-FOV) aspect; anything else is
     * <b>center-cropped</b> to fit. Left alone, a TextureView's SurfaceTexture defaults its
     * buffer to the on-screen view size — the drop-down pane's shape — so the camera picked a
     * differently-shaped preview stream and the operator saw a narrower, zoomed-in view than
     * what was actually going out on the wire (the encoder path is explicitly sized to the
     * native aspect in {@code GlRotationPipe}). Sizing the preview buffer to
     * {@code captureW×captureH} puts both outputs on one FOV, so the pane shows the real
     * broadcast frame.</p>
     *
     * <p>Must be called <i>before</i> the Surface is handed to the capture session — the size
     * is latched at session configuration.</p>
     */
    private void sizePreviewBuffer(SurfaceTexture st) {
        // While running, captureW/H are already authoritative (set by CapturePipeline.start);
        // re-resolving would be redundant and could disagree with the live encoder.
        if (!pipeline.isRunning()) {
            // This runs on layout passes, so memoize — resolving hits CameraManager and the
            // answer only moves when the camera or resolution selection does.
            String key = config.cameraId + "|" + config.useFrontCamera + "|" + config.resolution;
            if (!key.equals(captureSizeKey)) {
                try {
                    CapturePipeline.resolveCaptureSize(atakContext(), config);
                    captureSizeKey = key;
                } catch (Exception e) {
                    Log.w(TAG, "resolveCaptureSize: " + e.getMessage());
                }
            }
        }
        st.setDefaultBufferSize(config.captureW, config.captureH);
    }

    /** Rotate the preview upright and letterbox it to the source aspect ratio. Manual
     *  rotation only — no auto-detect (see the icu_rotation string-array and
     *  rotationIndex/rotationValue below; there is no "Auto" entry).
     *
     *  <p>The TextureView fills the pane, so with the default (identity) transform the
     *  camera frame is stretched to the pane's shape and looks squashed/elongated. We
     *  build a matrix that rotates the frame and scales it to fit the pane while keeping
     *  the encoder's source aspect ratio (letterbox), so the preview matches what's
     *  actually broadcast.</p> */
    private void applyPreviewRotation() {
        if (previewView == null) return;
        previewView.setScaleX(config.useFrontCamera ? -1f : 1f);   // mirror front camera

        int vw = previewView.getWidth();
        int vh = previewView.getHeight();
        if (vw == 0 || vh == 0) {           // not laid out yet — retry after layout
            previewView.post(this::applyPreviewRotation);
            return;
        }

        int deg = ((config.rotationDegrees % 360) + 360) % 360;
        // Match the encoder/stream: the camera's SurfaceTexture bakes in the 90° sensor
        // orientation, so the upright frame is PORTRAIT for 0°/180° and LANDSCAPE for
        // 90°/270°. Use the same swap the encoder uses so the preview aspect matches the
        // stream (otherwise portrait looks distorted here but not on the wire).
        boolean swap = (deg == 0 || deg == 180);
        float dispW = swap ? config.captureH : config.captureW;
        float dispH = swap ? config.captureW : config.captureH;

        float cx = vw / 2f, cy = vh / 2f;
        RectF viewRect = new RectF(0, 0, vw, vh);
        RectF srcRect  = new RectF(0, 0, dispW, dispH);
        srcRect.offset(cx - srcRect.centerX(), cy - srcRect.centerY());

        Matrix m = new Matrix();
        // Undo the default buffer→view stretch, giving square pixels at source aspect...
        m.setRectToRect(viewRect, srcRect, Matrix.ScaleToFit.FILL);
        // ...then fit the whole frame inside the pane (min = letterbox, so the operator
        // sees the FULL camera view — same framing that's broadcast — not a cropped zoom)...
        float scale = Math.min(vw / dispW, vh / dispH);
        m.postScale(scale, scale, cx, cy);
        // ...and rotate upright about the pane centre.
        m.postRotate(deg, cx, cy);
        previewView.setTransform(m);
    }

    // ── Broadcast ────────────────────────────────────────────────────────────────

    private void toggleBroadcast() {
        if (pipeline.isRunning()) {
            // Verify before dropping the feed for anyone watching.
            confirm(ps(R.string.icu_confirm_stop_title),
                    ps(R.string.icu_confirm_stop_msg),
                    ps(R.string.icu_stop), this::stopBroadcast);
            return;
        }
        if (!hasCameraPermission()) { requestCameraPermission(); return; }
        // Mic is only needed when the operator turned on audio; request it up front so the
        // AAC track can start. (Turn the audio setting off to broadcast video-only instead.)
        if (config.streamAudio && !hasMicPermission()) { requestMicPermission(); return; }
        // Verify before going live (shares camera + location to the network).
        String dest = serverConfig.pushEnabled()
                ? (serverConfig.protocolName() + " → " + serverConfig.pushUrl())
                : "Local network (LAN) — rtsp on this device";
        confirm(ps(R.string.icu_confirm_start_title),
                "Destination:\n" + dest, ps(R.string.icu_start), this::startBroadcast);
    }

    /** Crash-proof confirmation dialog (ATAK activity context + plugin strings). */
    private void confirm(String title, String message, String positive, Runnable onYes) {
        try {
            new AlertDialog.Builder(atakContext())
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positive, (d, w) -> onYes.run())
                    .setNegativeButton(ps(R.string.icu_cancel), null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "confirm dialog failed", t);
            onYes.run(); // don't block the action if the dialog can't show
        }
    }

    private void startBroadcast() {
        authFailureHandled = false;
        setStatus("Starting camera…");
        broadcastButton.setEnabled(false);

        // Serve layer first, so transports are ready before frames flow.
        // Exclusive by destination: SERVER pushes only; LAN serves on-device only.
        transports = new TransportManager();
        if (serverConfig.pushEnabled()) {
            switch (serverConfig.pushProtocol) {                    // user-selected push protocol
                case RTSP: transports.register(new RtspPushTransport(serverConfig)); break;
                case SRT:  transports.register(new SrtTransport(serverConfig, serverConfig.serverPort)); break;
                default:   transports.register(new RtmpPushTransport(serverConfig)); break;
            }
        } else {
            transports.register(new OnDeviceRtspTransport());       // LAN: peers pull from phone
        }
        transports.setErrorListener((name, message) ->
                ui.post(() -> Toast.makeText(pluginContext,
                        name + " unavailable: " + message, Toast.LENGTH_LONG).show()));
        transports.startAll(config);

        // Every destination failed — nothing would reach a viewer. Bail instead of running
        // the camera and encoder into a void: push mode registers exactly one transport, so
        // picking a protocol that can't start (SRT is still an unimplemented stub) would
        // otherwise leave the pane reading LIVE while publishing nowhere.
        if (transports.activeCount() == 0) {
            transports = null;
            resetIdleUi();
            setStatusError("No transport available — nothing would be broadcast");
            return;
        }

        pipeline.setSink(transports);
        // The pane's buffer was sized for whatever camera/resolution was selected when the
        // surface appeared; settings may have changed since. Re-pin it to the size this run
        // will actually capture at, before the session latches it.
        if (previewView != null && previewView.getSurfaceTexture() != null) {
            sizePreviewBuffer(previewView.getSurfaceTexture());
        }
        pipeline.start(atakContext(), config, previewSurface, new CapturePipeline.Listener() {
            @Override public void onStarted() {
                ui.post(() -> {
                    applyPreviewRotation();
                    broadcastButton.setEnabled(true);
                    broadcastButton.setImageResource(R.drawable.ic_stop);
                    broadcastButton.setBackgroundResource(R.drawable.bg_hud_button_danger);
                    broadcastLabel.setText(R.string.icu_stop);
                    liveDot.setVisibility(View.VISIBLE);
                    previewHint.setVisibility(View.GONE);
                    // Keep the screen awake while streaming unless the user opted to allow
                    // it to sleep; when allowed, hold a partial wake lock so the CPU (and
                    // thus capture/encode/network) keeps running with the screen off.
                    root.setKeepScreenOn(!config.streamWithScreenOff);
                    if (config.streamWithScreenOff) acquireWakeLock();
                    // Put the FOV + playable feed on the operator's own self marker →
                    // renders locally and rides out on the user's own PLI (native sensor +
                    // video handlers). Deterministic URL — a push transport may not be up yet.
                    sensor.start(advertisedEndpoint().url, serverConfig.alias,
                            config.fovRefreshSec, config.fovRangeM);
                    klv.start(atakContext(), getMapView(), transports, serverConfig.alias);
                    // Register the stream on the TAK Server's Video Feed Manager (server DB)
                    // when pushing to a server — makes it discoverable server-side, not just
                    // via the CoT feed on the self marker.
                    if (serverConfig.pushEnabled()) {
                        ensureFeedUuid();
                        videoPublisher.publish(serverConfig, serverConfig.feedUuid);
                    }
                    statusWidget.setStreaming(true);
                    startNetworkMonitor();
                    updateLiveStatus(0);
                    // UsbCameraSource reports "open" as soon as the UVC device accepts
                    // startPreview, which isn't proof that frames follow — a camera can sit
                    // there delivering nothing and raise no error at all. Watch for the
                    // first encoded frame and fall back if none arrives.
                    armFeedWatchdog();
                });
            }
            @Override public void onError(String message) {
                Log.w(TAG, "capture error: " + message);
                ui.post(() -> {
                    // A USB camera that never showed up, lost permission, or was unplugged
                    // mid-stream shouldn't end the broadcast — drop back to the built-in
                    // camera and keep the operator on the air.
                    if (shouldFallBackFromUsb()) { fallbackToDeviceCamera(message); return; }
                    cancelFeedWatchdog();
                    stopRecording(true);   // the encoder feeding it is gone
                    stopNetworkMonitor();
                    sensor.stop();
                    klv.stop();
                    if (transports != null) transports.stopAll();
                    releaseWakeLock();
                    statusWidget.setStreaming(false);
                    resetIdleUi();
                    setStatus("Failed: " + message);
                });
            }
            @Override public void onFrame(int totalNalUnits) {
                // First encoded NAL = the source is genuinely delivering frames.
                if (totalNalUnits == 1) ui.post(ICUVideoDropDownReceiver.this::cancelFeedWatchdog);
                if (totalNalUnits % 30 == 0) ui.post(() -> updateLiveStatus(totalNalUnits));
            }
            @Override public void onSourceOpened() {
                ui.post(ICUVideoDropDownReceiver.this::armFeedWatchdog);
            }
        });
    }

    // ── USB camera fallback ──────────────────────────────────────────────────────

    /** How long to wait for the first encoded frame before declaring the USB source dead.
     *  Generous: UVC enumeration + the OS permission prompt can eat several seconds. */
    private static final long USB_FEED_TIMEOUT_MS = 8000;

    /** Set once this broadcast has already fallen back, so a built-in camera that also
     *  fails reports normally instead of looping. Cleared by {@link #stopBroadcast}. */
    private boolean  usbFallbackUsed;
    private Runnable feedWatchdog;

    /** True when a USB-sourced broadcast is live and hasn't already used its one fallback. */
    private boolean shouldFallBackFromUsb() {
        return !usbFallbackUsed
                && EncoderConfig.CAMERA_ID_USB.equals(config.cameraId)
                && transports != null;   // a broadcast we started, not a stale callback
    }

    private void armFeedWatchdog() {
        cancelFeedWatchdog();
        if (!EncoderConfig.CAMERA_ID_USB.equals(config.cameraId)) return;
        feedWatchdog = () -> {
            feedWatchdog = null;
            if (shouldFallBackFromUsb()) fallbackToDeviceCamera("no video from the USB camera");
        };
        ui.postDelayed(feedWatchdog, USB_FEED_TIMEOUT_MS);
    }

    private void cancelFeedWatchdog() {
        if (feedWatchdog != null) { ui.removeCallbacks(feedWatchdog); feedWatchdog = null; }
    }

    /**
     * Swap a dead USB source for the phone's own camera and resume broadcasting.
     *
     * <p>Restarts the whole pipeline rather than hot-swapping the source: the encoder and GL
     * stage are sized to the capture dimensions, and the built-in camera's differ from the
     * USB path's, so new SPS/PPS have to be signalled. Viewers will need to reconnect —
     * still better than a stream that silently dies when a cable comes loose.</p>
     *
     * <p>The change is deliberately <b>not</b> persisted: the operator's saved choice stays
     * "USB camera", so plugging the camera back in and starting again retries it.</p>
     */
    private void fallbackToDeviceCamera(String why) {
        Log.w(TAG, "USB camera unusable (" + why + ") — falling back to the built-in camera");
        cancelFeedWatchdog();
        stopBroadcast();
        // After stopBroadcast, which clears the flag — this restart is the one fallback.
        usbFallbackUsed = true;
        config.cameraId      = "";      // auto — front/back per useFrontCamera
        config.useFrontCamera = false;  // rear: the sensible default for a mounted feed
        captureSizeKey       = "";      // force a re-resolve for the new source
        setStatus("USB camera unavailable — switching to the built-in camera…");
        Toast.makeText(pluginContext,
                "USB camera unavailable (" + why + ") — switched to the built-in camera",
                Toast.LENGTH_LONG).show();
        startBroadcast();
    }

    private void stopBroadcast() {
        cancelFeedWatchdog();
        // Recording rides this broadcast's encoder, so it ends with it — finalize the MP4
        // before the encoder goes away rather than leaving a truncated file.
        stopRecording(true);
        stopNetworkMonitor();
        usbFallbackUsed = false;
        sensor.stop();                       // revert self marker to the user's prefs
        klv.stop();
        // Flip the server feed inactive (can't DELETE it with an EUD cert).
        if (serverConfig.pushEnabled()
                && serverConfig.feedUuid != null && !serverConfig.feedUuid.isEmpty()) {
            videoPublisher.unpublish(serverConfig, serverConfig.feedUuid);
        }
        pipeline.stop();
        if (transports != null) { transports.stopAll(); transports = null; }
        releaseWakeLock();
        statusWidget.setStreaming(false);
        resetIdleUi();
    }

    /** Default broadcast alias — the operator callsign, else VIDEO_1. */
    private String defaultAlias() {
        String cs = getMapView().getDeviceCallsign();
        return (cs != null && !cs.trim().isEmpty()) ? cs.trim() : "VIDEO_1";
    }

    /** Strip a name down to what a server path can carry. */
    private static String pathSafe(String s) {
        return s == null ? "" : s.trim().replaceAll("[^A-Za-z0-9_-]", "");
    }

    /** Default stream path — the broadcast alias (path-safe), else the callsign, else icu.
     *  The alias is the name the operator gives this feed, so the path follows it; deriving
     *  from the callsign meant a rename left the path pinned to whatever the callsign
     *  happened to be when the field was first filled. Still avoids operators colliding on
     *  the same server path when they clear the field. */
    private String defaultPath() {
        String a = pathSafe(serverConfig.alias);
        if (!a.isEmpty()) return a;
        String cs = pathSafe(getMapView().getDeviceCallsign());
        return !cs.isEmpty() ? cs : "icu";
    }

    /** Ensure a stable feed id exists (generate + persist once) for server dedupe.
     *  Prefer a callsign-based id so the Video Feed Manager row is readable, not a UUID. */
    private void ensureFeedUuid() {
        String cs = getMapView().getDeviceCallsign();
        if (cs != null) cs = cs.replaceAll("[^A-Za-z0-9_-]", "");
        boolean haveCs = cs != null && !cs.isEmpty();
        boolean unset  = serverConfig.feedUuid == null || serverConfig.feedUuid.trim().isEmpty();
        // Migrate an existing bare UUID to the callsign form.
        boolean isRawUuid = !unset && serverConfig.feedUuid.matches("[0-9a-fA-F-]{36}");
        if (unset || (isRawUuid && haveCs)) {
            serverConfig.feedUuid = haveCs ? "ICU-" + cs : java.util.UUID.randomUUID().toString();
            Prefs.save(atakContext(), serverConfig, config);
        }
    }

    // ── Network handover ─────────────────────────────────────────────────────────
    // Switching Wi-Fi to LTE (or hopping APs) kills every socket the broadcast holds: they
    // are bound to an address the device no longer owns, and TCP has no idea its interface
    // went away, so nothing recovers by itself. Left alone the encoder keeps running, the
    // transports keep writing into dead sockets, and the pane keeps reporting LIVE while
    // no viewer receives anything. We watch for the handover and redial instead.

    private void startNetworkMonitor() {
        if (networkMonitor != null) return;
        networkMonitor = new NetworkMonitor(atakContext(), this::onNetworkChanged);
        networkMonitor.start();
    }

    private void stopNetworkMonitor() {
        if (networkMonitor != null) { networkMonitor.stop(); networkMonitor = null; }
    }

    /** The default network moved. Redial the transports and correct what we advertise. */
    private void onNetworkChanged() {
        if (!pipeline.isRunning()) return;   // stale callback after the broadcast ended
        Log.d(TAG, "network changed while live — reconnecting transports");

        // Let a fresh failure be reported: the previous broadcast-killing verdict, if any,
        // was reached on a network that is no longer the one we are using.
        authFailureHandled = false;

        if (transports != null) transports.reconnectAll(config);

        // On LAN the advertised URL embeds this device's own IP, which just changed, so
        // peers are holding a link to an address that is no longer ours. Re-derive it and
        // let the next FOV tick carry the correction out on the self report.
        String url = advertisedEndpoint().url;
        sensor.setUrl(url);

        // Same for the server-side feed registration when pushing.
        if (serverConfig.pushEnabled()
                && serverConfig.feedUuid != null && !serverConfig.feedUuid.isEmpty()) {
            videoPublisher.publish(serverConfig, serverConfig.feedUuid);
        }

        setStatus("Network changed — reconnecting…");
        Toast.makeText(pluginContext, "Network changed — reconnecting the stream…",
                Toast.LENGTH_SHORT).show();
    }

    private android.os.PowerManager.WakeLock wakeLock;

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            android.os.PowerManager pm =
                    (android.os.PowerManager) atakContext().getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ICU:Streaming");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "wake lock acquire: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    // ── Blackout (fake screen-off that keeps capture alive) ──────────────────────
    // A true screen-off backgrounds ATAK and Android cuts the camera after ~5s. Instead
    // we keep the screen ON but paint it fully black at minimum brightness: the app stays
    // foreground so capture continues, and on OLED a black screen draws almost no power.

    private View blackoutView;
    private float savedBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

    private void showBlackout() {
        try {
            final Activity a = (Activity) atakContext();
            if (blackoutView != null) return;

            final View v = new View(a);
            v.setBackgroundColor(0xFF000000);
            v.setKeepScreenOn(true);           // keep the screen on → camera stays alive
            v.setClickable(true);
            v.setFocusable(true);
            v.setOnClickListener(x -> dismissBlackout());
            a.addContentView(v, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            blackoutView = v;

            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            savedBrightness = lp.screenBrightness;
            lp.screenBrightness = 0.0f;        // minimum backlight (near-black)
            a.getWindow().setAttributes(lp);

            Toast.makeText(a, "Screen blacked out — streaming continues. Tap to wake.",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "blackout: " + t.getMessage());
        }
    }

    private void dismissBlackout() {
        try {
            final Activity a = (Activity) atakContext();
            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.screenBrightness = savedBrightness;   // restore prior brightness
            a.getWindow().setAttributes(lp);
            if (blackoutView != null && blackoutView.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) blackoutView.getParent()).removeView(blackoutView);
            blackoutView = null;
        } catch (Throwable t) {
            Log.w(TAG, "blackout dismiss: " + t.getMessage());
        }
    }

    /**
     * The URL peers should open, derived deterministically from the current config
     * (server view URL when pushing, else the on-device LAN RTSP URL). Independent of
     * whether a push transport has finished connecting yet.
     */
    private StreamEndpoint advertisedEndpoint() {
        if (serverConfig.pushEnabled()) {
            // feedViewUrl embeds user:pass@ + ?tcp so a peer opening the self-marker video
            // authenticates to the server and uses reliable RTSP-interleaved delivery.
            return new StreamEndpoint(serverConfig.protocolName(), serverConfig.feedViewUrl());
        }
        return new StreamEndpoint("RTSP",
                com.atakmap.android.icu.util.NetworkUtils.rtspUrl(
                        com.atakmap.android.icu.serve.RtspServer.PORT,
                        com.atakmap.android.icu.serve.RtspServer.STREAM_PATH));
    }

    /**
     * Capture a JPEG still from the live camera and drop a marker with it attached.
     * Uses the camera's dedicated still target (not the on-screen preview), so it works
     * with the plugin panel closed — e.g. triggered from the self-marker radial.
     */
    private void takeSnapshot() {
        if (!pipeline.isRunning()) {
            Toast.makeText(atakContext(), "Start broadcasting first.", Toast.LENGTH_SHORT).show();
            return;
        }
        pipeline.captureStill(config.rotationDegrees, new CameraSource.StillCallback() {
            @Override public void onStill(byte[] jpeg) {
                ui.post(() -> saveSnapshotJpeg(jpeg));
            }
            @Override public void onStillError(String message) {
                ui.post(() -> Toast.makeText(atakContext(),
                        "Snapshot failed: " + message, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Persist the captured JPEG and drop a marker with it attached (UI thread). */
    private void saveSnapshotJpeg(byte[] jpeg) {
        try {
            java.io.File dir = new java.io.File(atakContext().getExternalFilesDir(null), "ICU/snapshots");
            dir.mkdirs();
            String name = "ICU_" + System.currentTimeMillis() + ".jpg";
            java.io.File f = new java.io.File(dir, name);
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write(jpeg);
            os.close();
            Log.d(TAG, "snapshot → " + f.getAbsolutePath());
            boolean marked = dropSnapshotMarker(f, name);
            Toast.makeText(atakContext(),
                    marked ? "Snapshot saved + marker dropped" : "Snapshot saved: " + name,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(atakContext(), "Snapshot failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Place a marker on the map at the phone's current position and attach the snapshot
     * image to it (copied into ATAK's attachment store for that marker). Returns true if
     * the marker was placed. The original {@code imageFile} stays on the device.
     */
    private boolean dropSnapshotMarker(java.io.File imageFile, String name) {
        try {
            MapView mv = getMapView();
            com.atakmap.coremap.maps.coords.GeoPoint p = null;
            com.atakmap.android.maps.Marker self = mv.getSelfMarker();
            if (self != null && self.getPoint() != null && self.getPoint().isValid())
                p = self.getPoint();
            if (p == null && mv.getCenterPoint() != null) p = mv.getCenterPoint().get();
            if (p == null || !p.isValid()) {
                Log.w(TAG, "snapshot marker: no valid position");
                return false;
            }

            String uid = "ICU-SNAP-" + System.currentTimeMillis();
            String callsign = serverConfig.alias + " snapshot";
            com.atakmap.android.maps.Marker m =
                    new com.atakmap.android.user.PlacePointTool.MarkerCreator(p)
                            .setUid(uid)
                            .setType("b-m-p-s-m")          // generic spot marker
                            .setCallsign(callsign)
                            .showCotDetails(false)
                            .placePoint();
            if (m == null) return false;

            com.atakmap.android.util.AttachmentManager.addAttachment(m, imageFile);
            com.atakmap.android.util.AttachmentManager.notifyAttachmentChange(m.getUID());
            m.persist(mv.getMapEventDispatcher(), null, this.getClass());
            Log.d(TAG, "snapshot marker " + uid + " (" + name + ")");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "snapshot marker failed: " + t.getMessage());
            return false;
        }
    }

    // ── Local recording ──────────────────────────────────────────────────────────
    // Records the broadcast's own encoded stream to an MP4 (see Mp4Recorder). Works with
    // the pane closed — the record button and its label exist from construction, and the
    // headless RECORD intent (self-marker radial) lands here too.

    /** Where recordings are written: {@code <internal storage>/atak/ICU Video}, inside
     *  ATAK's own data folder so the files are easy to find in any file browser (the
     *  app-private external-files dir the old path used is hidden from most of them).
     *  Falls back to the old location if the ATAK root isn't resolvable. */
    private java.io.File recordingsDir() {
        try {
            java.io.File dir = com.atakmap.coremap.filesystem.FileSystemUtils.getItem("ICU Video");
            if (dir != null) return dir;
        } catch (Throwable t) {
            Log.w(TAG, "ATAK root unavailable, using app dir: " + t.getMessage());
        }
        return new java.io.File(atakContext().getExternalFilesDir(null), "ATAK ICU/recordings");
    }

    private void takeRecord() {
        if (recorder.isRunning()) { stopRecording(true); return; }

        if (!pipeline.isRunning()) {
            Toast.makeText(atakContext(),
                    "Start broadcasting first — recording captures the broadcast stream.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        java.io.File f = new java.io.File(recordingsDir(), "ICU_" + stamp + ".mp4");
        if (pipeline.hqRecordConfigured()) startHqRecording(f);
        else                               startStreamQualityRecording(f);
    }

    /** Original path — mux the broadcast encoder's own output (no extra cost). */
    private void startStreamQualityRecording(java.io.File f) {
        try {
            boolean started = recorder.start(f,
                    pipeline.getEncodedWidth(), pipeline.getEncodedHeight(),
                    pipeline.getSps(), pipeline.getPps(),
                    pipeline.getAudioAsc(), pipeline.getAudioSampleRate(),
                    pipeline.getAudioChannels());
            if (!started) {
                // No SPS/PPS yet — the encoder has only just been asked to start.
                Toast.makeText(atakContext(), "Not ready to record yet — try again in a moment.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            pipeline.setRecorder(recorder);   // start the tee only once the file is open
            setRecordingUi();
            Toast.makeText(atakContext(),
                    "Recording to " + f.getName(), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "record start failed", t);
            pipeline.setRecorder(null);
            recorder.stop();
            resetRecordUi();
            Toast.makeText(atakContext(), "Recording failed to start: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Record-quality-override path — a second encoder at the record spec (see
     *  {@link CapturePipeline#startHqRecord}); the MP4 opens once its SPS/PPS exist.
     *  If the device can't run a second encoder, falls back to stream quality. */
    private void startHqRecording(java.io.File f) {
        pipeline.startHqRecord(new CapturePipeline.HqRecordCallback() {
            @Override public void onReady(byte[] sps, byte[] pps, int w, int h) {
                ui.post(() -> {
                    try {
                        boolean started = recorder.start(f, w, h, sps, pps,
                                pipeline.getAudioAsc(), pipeline.getAudioSampleRate(),
                                pipeline.getAudioChannels());
                        if (!started) { pipeline.stopHqRecord(); return; }
                        pipeline.setRecorder(recorder);
                        setRecordingUi();
                        Toast.makeText(atakContext(), "Recording to " + f.getName()
                                + " (" + w + "x" + h + ")", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Log.w(TAG, "HQ record start failed", t);
                        pipeline.setRecorder(null);
                        pipeline.stopHqRecord();
                        recorder.stop();
                        resetRecordUi();
                        Toast.makeText(atakContext(), "Recording failed to start: "
                                + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override public void onError(String message) {
                ui.post(() -> {
                    if (recorder.isRunning()) {
                        // The record encoder died mid-recording — finalize what we have.
                        Log.w(TAG, "record encoder failed mid-recording: " + message);
                        stopRecording(true);
                        return;
                    }
                    // Couldn't start at the record spec (e.g. no second hardware encoder
                    // session available) — record at stream quality instead of not at all.
                    pipeline.stopHqRecord();
                    Toast.makeText(atakContext(), "High-quality recorder unavailable ("
                            + message + ") — recording at stream quality.", Toast.LENGTH_LONG).show();
                    startStreamQualityRecording(f);
                });
            }
        });
    }

    /** Finalize the MP4 and put the record button back to idle. Safe when not recording.
     *  {@code notify} shows the operator where the file went (suppressed on plugin teardown). */
    private void stopRecording(boolean notify) {
        pipeline.setRecorder(null);          // stop feeding before finalizing the file
        pipeline.stopHqRecord();             // tear down the record encoder, if one ran
        Mp4Recorder.Result r = recorder.stop();
        stopRecordTicker();
        resetRecordUi();
        if (r == null || !notify) return;
        if (r.ok) {
            Toast.makeText(atakContext(),
                    "Recording saved: " + (r.file != null ? r.file.getName() : "?")
                            + " (" + mmss(r.durationMs) + ")", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(atakContext(), "Recording failed: " + r.error, Toast.LENGTH_LONG).show();
        }
    }

    private void setRecordingUi() {
        recordButton.setBackgroundResource(R.drawable.bg_hud_button_danger);
        startRecordTicker();
    }

    private void resetRecordUi() {
        recordButton.setBackgroundResource(R.drawable.bg_hud_button);
        if (recordLabel != null) recordLabel.setText(R.string.icu_record);
    }

    /** Live elapsed readout under the record button; also the watchdog that reconciles the
     *  UI if the recorder gave up on its own (a muxer write failure ends recording without
     *  the operator touching anything, and must not leave the button showing REC). */
    private Runnable recordTicker;

    private void startRecordTicker() {
        stopRecordTicker();
        recordTicker = new Runnable() {
            @Override public void run() {
                if (!recorder.isRunning()) { stopRecording(true); return; }
                if (recordLabel != null)
                    recordLabel.setText("REC " + mmss(recorder.elapsedMs()));
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(recordTicker);
    }

    private void stopRecordTicker() {
        if (recordTicker != null) { ui.removeCallbacks(recordTicker); recordTicker = null; }
    }

    private static String mmss(long ms) {
        long total = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60);
    }

    // ── Status UI ────────────────────────────────────────────────────────────────

    private void updateLiveStatus(int frames) {
        if (authFailureHandled) return;   // already auto-stopped; keep the error on screen

        // A server push that failed (e.g. bad credentials) isn't reaching anyone, even
        // though the encoder keeps producing frames — so stop the broadcast outright
        // instead of showing a false "LIVE". Transports only report a failure once they
        // have exhausted their redials, so a handover in progress doesn't land here.
        String failure = (transports != null) ? transports.failureReason() : null;
        if (failure != null) { handlePushFailure(failure); return; }

        // Mid-redial: frames are being produced but nothing is reaching a viewer yet. Say
        // so rather than showing LIVE over a connection that does not currently exist.
        if (transports != null && transports.reconnecting()) {
            setStatus("Reconnecting…");
            liveDot.setVisibility(View.GONE);
            return;
        }

        int viewers = (transports != null) ? transports.totalViewers() : 0;
        StringBuilder s = new StringBuilder("LIVE · ").append(config.resolution.label);
        if (viewers > 0) s.append(" · ").append(viewers).append(" viewer(s)");
        else if (frames > 0) s.append(" · streaming");
        setStatus(s.toString());
        liveDot.setVisibility(View.VISIBLE);
        refreshAuthBadge();
    }

    /** A server push failed terminally (typically bad credentials): tear the broadcast down
     *  — encoder, transports, self-marker feed, and the on-map "live" widget — and show the
     *  reason in red. Runs once per broadcast (guarded by authFailureHandled). */
    private void handlePushFailure(String reason) {
        authFailureHandled = true;
        boolean auth = reason != null && reason.toLowerCase().contains("auth");
        stopBroadcast();   // stops capture + transports + widget and resets the pane to idle…
        setStatusError(auth ? "AUTHENTICATION FAILED" : reason);   // …then show the reason in red
    }

    /** Show the purple "Using Auth" badge only when a live transport is actually
     *  authenticated to its server — the system check for "is this stream using user/pass".
     *  Anonymous publishes and on-device serving leave it hidden. */
    private void refreshAuthBadge() {
        if (authBadge == null) return;
        boolean auth = transports != null && transports.usingAuth();
        authBadge.setVisibility(auth ? View.VISIBLE : View.GONE);
    }

    private void resetIdleUi() {
        root.setKeepScreenOn(false);   // allow normal sleep again
        broadcastButton.setEnabled(true);
        broadcastButton.setImageResource(R.drawable.ic_broadcast);
        broadcastButton.setBackgroundResource(R.drawable.bg_hud_button);
        broadcastLabel.setText(R.string.icu_broadcast);
        liveDot.setVisibility(View.GONE);
        previewHint.setVisibility(View.VISIBLE);
        if (authBadge != null) authBadge.setVisibility(View.GONE);
        setStatus(pluginContext.getString(R.string.icu_status_idle));
    }

    private void refreshDestBadge() {
        destBadge.setText(serverConfig.pushEnabled()
                ? "SERVER → " + serverConfig.host : "LAN");
    }

    private void refreshDestToggle() {
        if (destToggleLabel != null)
            destToggleLabel.setText(serverConfig.destination
                    == MediaServerConfig.Destination.SERVER ? "Server" : "LAN");
    }

    /** Quick-bar LAN ⇄ Server flip: switch the destination, swap in that destination's
     *  capture/encode profile, persist, and — if live — restart the stream onto the new
     *  destination so the toggle takes effect immediately. */
    private void toggleDestination() {
        boolean toServer = serverConfig.destination != MediaServerConfig.Destination.SERVER;
        serverConfig.destination = toServer
                ? MediaServerConfig.Destination.SERVER : MediaServerConfig.Destination.LAN;
        applyProfile(config, Prefs.loadProfile(atakContext(), serverConfig.destination));
        Prefs.save(atakContext(), serverConfig, config);
        refreshDestBadge();
        refreshDestToggle();
        applyPreviewRotation();
        if (toServer && !serverConfig.isConfigured()) {
            // SERVER with no address falls back to LAN-style serving at broadcast time —
            // say so instead of leaving the toggle looking like it did nothing useful.
            Toast.makeText(pluginContext,
                    "No media server configured — set the address in Settings",
                    Toast.LENGTH_LONG).show();
        }
        if (pipeline.isRunning()) {
            Toast.makeText(pluginContext, "Switching destination — restarting stream…",
                    Toast.LENGTH_SHORT).show();
            stopBroadcast();
            startBroadcast();
        }
    }

    private void setStatus(String text) {
        if (statusText != null) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText(text);
        }
    }

    /** Set the status line in red (e.g. "AUTHENTICATION FAILED"). */
    private void setStatusError(String text) {
        if (statusText != null) {
            statusText.setTextColor(0xFFF44336);   // icu_danger red
            statusText.setText(text);
        }
    }

    // ── Settings dialog (gear) ───────────────────────────────────────────────────

    // Selection state while the dialog is open (indices into the string arrays).
    private final int[] sel = new int[6]; // 0=dest 1=res 2=fps 3=rot 4=protocol 5=camera

    /**
     * Fully programmatic settings dialog built from the ATAK activity context, with
     * strings pulled from the plugin context. No plugin-layout inflation, no plugin
     * styles, no spinners — this avoids the whole class of "plugin resource resolved
     * against the wrong context" crashes. Wrapped so any error surfaces as a toast.
     */
    private void showSettingsPage() {
        final Context ctx = atakContext();
        try {
            settingsContainer.removeAllViews();

            final CharSequence[] destOpts = pta(R.array.icu_destinations);
            final CharSequence[] protoOpts= pta(R.array.icu_protocols);
            final CharSequence[] resOpts  = pta(R.array.icu_resolutions);
            final CharSequence[] fpsOpts  = pta(R.array.icu_framerates);
            final CharSequence[] rotOpts  = pta(R.array.icu_rotations);

            // Camera list is built live from Camera2 (not a static string-array) so a
            // plugged-in USB/UVC camera that Android surfaces as LENS_FACING_EXTERNAL
            // shows up automatically alongside the built-in front/back cameras. A "USB
            // camera (UVC)" entry is always appended too — most Android builds don't
            // register a UVC webcam via Camera2 at all, so that entry routes through
            // UsbCameraSource (com.herohan:UVCAndroid) instead, independent of whether
            // Camera2 saw anything.
            final List<CameraSource.CameraOption> camList = CameraSource.listCameras(ctx);
            camList.add(CameraSource.usbOption());
            final CharSequence[] camOpts;
            if (camList.isEmpty()) {
                camOpts = pta(R.array.icu_cameras);   // fallback: legacy front/back toggle
            } else {
                camOpts = new CharSequence[camList.size()];
                for (int i = 0; i < camList.size(); i++) camOpts[i] = camList.get(i).label;
            }

            // Each destination keeps its own capture/encode profile: the VIDEO fields below
            // are staged against the profile for whichever destination is selected, and
            // flipping the Destination picker swaps which profile the fields show. The
            // active destination's profile starts from the live config; the other side
            // comes from its last save.
            final EncoderConfig[] profiles = new EncoderConfig[2];   // [0]=LAN [1]=SERVER
            final int activeDest = serverConfig.destination == MediaServerConfig.Destination.SERVER ? 1 : 0;
            profiles[activeDest] = copyProfile(config);
            profiles[1 - activeDest] = Prefs.loadProfile(ctx, activeDest == 1
                    ? MediaServerConfig.Destination.LAN : MediaServerConfig.Destination.SERVER);

            sel[0] = activeDest;
            sel[1] = config.resolution.ordinal();
            sel[2] = fpsIndex(config.fps);
            sel[3] = rotationIndex(config.rotationDegrees);
            sel[4] = serverConfig.pushProtocol.ordinal();
            sel[5] = cameraIndexFor(camList, config);
            // Staged like the rest of the fields — only committed to serverConfig on Save.
            final String[] scannedPassphrase = {serverConfig.srtPassphrase};

            // ── Card: Broadcast ──────────────────────────────────────────────────
            final LinearLayout broadcastCard = addCard(ctx, "BROADCAST");
            final Button scanQrBtn = addSecondaryButton(ctx, broadcastCard, ps(R.string.icu_scan_qr));
            final EditText alias = addEdit(ctx, broadcastCard, ps(R.string.icu_alias),
                    serverConfig.alias, android.text.InputType.TYPE_CLASS_TEXT);
            final Button destBtn = addPicker(ctx, broadcastCard, ps(R.string.icu_destination), destOpts[sel[0]]);

            final LinearLayout srv = new LinearLayout(ctx);
            srv.setOrientation(LinearLayout.VERTICAL);
            final Button protoBtn = addPicker(ctx, srv, ps(R.string.icu_protocol), protoOpts[sel[4]]);
            final EditText address = addEdit(ctx, srv, ps(R.string.icu_address),
                    serverConfig.host, android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
            final EditText port = addEdit(ctx, srv, ps(R.string.icu_port),
                    Integer.toString(serverConfig.serverPort), android.text.InputType.TYPE_CLASS_NUMBER);
            final EditText path = addEdit(ctx, srv, ps(R.string.icu_path),
                    serverConfig.streamPath, android.text.InputType.TYPE_CLASS_TEXT);
            // Keep the stream path following the Broadcast Alias as it's typed, so renaming
            // the feed actually changes the URL viewers use. Tracks the path box only while
            // the operator hasn't typed their own value into it — once they edit the path
            // directly it stops following, so a server that needs a fixed path still works.
            final String[] trackedPath = { path.getText().toString().trim() };
            alias.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    String current = path.getText().toString().trim();
                    if (!current.equals(trackedPath[0])) return;   // operator pinned it
                    String next = pathSafe(e.toString());
                    trackedPath[0] = next;
                    path.setText(next);
                }
            });

            final EditText user = addEdit(ctx, srv, ps(R.string.icu_username),
                    serverConfig.username, android.text.InputType.TYPE_CLASS_TEXT);
            final EditText pass = addEdit(ctx, srv, ps(R.string.icu_password),
                    serverConfig.password, android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD | android.text.InputType.TYPE_CLASS_TEXT);

            // Show-password toggle for the field above.
            final android.widget.CheckBox showPass = new android.widget.CheckBox(ctx);
            showPass.setText("Show password");
            showPass.setTextColor(pColor(R.color.icu_text_secondary));
            showPass.setTextSize(13);
            showPass.setButtonTintList(android.content.res.ColorStateList.valueOf(pColor(R.color.icu_accent)));
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            spLp.topMargin = (int) (4 * ctx.getResources().getDisplayMetrics().density);
            showPass.setLayoutParams(spLp);
            showPass.setOnCheckedChangeListener((btn, checked) -> {
                pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (checked
                        ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
                pass.setSelection(pass.getText().length());   // keep the cursor at the end
            });
            srv.addView(showPass);

            broadcastCard.addView(srv);
            srv.setVisibility(sel[0] == 1 ? View.VISIBLE : View.GONE);

            // ── Card: Video ──────────────────────────────────────────────────────
            final LinearLayout videoCard = addCard(ctx, "VIDEO");
            final Button resBtn = addPicker(ctx, videoCard, ps(R.string.icu_resolution), resOpts[sel[1]]);
            final Button fpsBtn = addPicker(ctx, videoCard, ps(R.string.icu_framerate), fpsOpts[sel[2]]);
            final EditText bitrate = addEdit(ctx, videoCard, ps(R.string.icu_bitrate),
                    Integer.toString(config.bitrateKbps), android.text.InputType.TYPE_CLASS_NUMBER);
            final Button rotBtn = addPicker(ctx, videoCard, ps(R.string.icu_rotation), rotOpts[sel[3]]);
            final Button camBtn = addPicker(ctx, videoCard, ps(R.string.icu_camera), camOpts[sel[5]]);

            // Keyframe (GOP) interval — short values keep browser/HLS players near live.
            final CharSequence[] gopOpts = { "1 s (browser-friendly)", "2 s", "4 s" };
            final int[] gopVals = { 1, 2, 4 };
            int gi = 1; for (int i = 0; i < gopVals.length; i++) if (gopVals[i] == config.gopSeconds) gi = i;
            final int[] gopSel = { gi };
            final Button gopBtn = addPicker(ctx, videoCard, "Keyframe interval", gopOpts[gopSel[0]]);
            gopBtn.setOnClickListener(x -> picker(ctx, "Keyframe interval", gopOpts,
                    i -> { gopSel[0] = i; gopBtn.setText(gopOpts[i]); }));

            // Record-quality override — record locally at a different spec than the
            // stream (e.g. stream 720p15, keep a 1080p30 file). Global, not per-
            // destination: the file is local either way. "Same as stream" = the
            // original zero-cost tap of the broadcast encoder.
            final CharSequence[] recResOpts = { "Same as stream", "480p", "720p", "1080p" };
            final int[] recResVals = { 0, 480, 720, 1080 };
            int rri = 0; for (int i = 0; i < recResVals.length; i++) if (recResVals[i] == config.recordHeight) rri = i;
            final int[] recResSel = { rri };
            final Button recResBtn = addPicker(ctx, videoCard, "Record resolution", recResOpts[recResSel[0]]);
            recResBtn.setOnClickListener(x -> picker(ctx, "Record resolution", recResOpts,
                    i -> { recResSel[0] = i; recResBtn.setText(recResOpts[i]); }));

            final CharSequence[] recFpsOpts = { "Same as stream", "15 fps", "24 fps", "30 fps" };
            final int[] recFpsVals = { 0, 15, 24, 30 };
            int rfi = 0; for (int i = 0; i < recFpsVals.length; i++) if (recFpsVals[i] == config.recordFps) rfi = i;
            final int[] recFpsSel = { rfi };
            final Button recFpsBtn = addPicker(ctx, videoCard, "Record frame rate", recFpsOpts[recFpsSel[0]]);
            recFpsBtn.setOnClickListener(x -> picker(ctx, "Record frame rate", recFpsOpts,
                    i -> { recFpsSel[0] = i; recFpsBtn.setText(recFpsOpts[i]); }));

            // How often the FOV wedge is refreshed + force-sent to peers (seconds).
            final EditText fovRefresh = addEdit(ctx, videoCard, "FOV update (sec)",
                    Integer.toString(config.fovRefreshSec), android.text.InputType.TYPE_CLASS_NUMBER);

            // How far the FOV wedge reaches on peers' maps (meters).
            final EditText fovRange = addEdit(ctx, videoCard, "FOV range (m)",
                    Integer.toString(config.fovRangeM), android.text.InputType.TYPE_CLASS_NUMBER);

            // Stream microphone audio as a second (AAC) track.
            final CharSequence[] audioOpts = { "Off", "On" };
            final int[] audioSel = { config.streamAudio ? 1 : 0 };
            final Button audioBtn = addPicker(ctx, videoCard, "Stream audio (mic)", audioOpts[audioSel[0]]);
            audioBtn.setOnClickListener(x -> picker(ctx, "Stream audio (mic)", audioOpts,
                    i -> { audioSel[0] = i; audioBtn.setText(audioOpts[i]); }));

            // ── Card: Display & power ────────────────────────────────────────────
            final LinearLayout dispCard = addCard(ctx, "DISPLAY & POWER");
            final CharSequence[] widgetOpts = { "On", "Off" };
            final int[] widgetSel = { config.showStatusWidget ? 0 : 1 };
            final Button widgetBtn = addPicker(ctx, dispCard, "Status badge", widgetOpts[widgetSel[0]]);
            final CharSequence[] screenOpts = { "No — keep screen on", "Yes — allow screen off" };
            final int[] screenSel = { config.streamWithScreenOff ? 1 : 0 };
            final Button screenBtn = addPicker(ctx, dispCard, "Keep streaming when screen off",
                    screenOpts[screenSel[0]]);

            // Read the on-screen VIDEO fields into the profile for the currently selected
            // destination (sel[0]) — called before flipping destinations and on Save.
            final Runnable stashVideo = () -> {
                EncoderConfig p = profiles[sel[0]];
                p.resolution = EncoderConfig.Resolution.values()[sel[1]];
                p.fps = intOf(fpsOpts[sel[2]].toString(), 30);
                p.bitrateKbps = intOf(bitrate, 2500);
                p.rotationDegrees = rotationValue(sel[3]);
                if (!camList.isEmpty()) {
                    CameraSource.CameraOption opt = camList.get(
                            Math.max(0, Math.min(sel[5], camList.size() - 1)));
                    p.cameraId = opt.id;
                    p.useFrontCamera =
                            opt.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;
                } else {
                    p.cameraId = "";
                    p.useFrontCamera = sel[5] == 1;
                }
                p.gopSeconds = gopVals[gopSel[0]];
                p.streamAudio = audioSel[0] == 1;
            };
            // Push the profile for the newly selected destination into the VIDEO fields.
            final Runnable bindVideo = () -> {
                EncoderConfig p = profiles[sel[0]];
                sel[1] = p.resolution.ordinal();          resBtn.setText(resOpts[sel[1]]);
                sel[2] = fpsIndex(p.fps);                 fpsBtn.setText(fpsOpts[sel[2]]);
                bitrate.setText(Integer.toString(p.bitrateKbps));
                sel[3] = rotationIndex(p.rotationDegrees); rotBtn.setText(rotOpts[sel[3]]);
                sel[5] = cameraIndexFor(camList, p);      camBtn.setText(camOpts[sel[5]]);
                int g = 1; for (int i = 0; i < gopVals.length; i++) if (gopVals[i] == p.gopSeconds) g = i;
                gopSel[0] = g;                            gopBtn.setText(gopOpts[g]);
                audioSel[0] = p.streamAudio ? 1 : 0;      audioBtn.setText(audioOpts[audioSel[0]]);
            };

            destBtn.setOnClickListener(x -> picker(ctx, ps(R.string.icu_destination), destOpts, i -> {
                if (i != sel[0]) { stashVideo.run(); sel[0] = i; bindVideo.run(); }
                destBtn.setText(destOpts[i]);
                srv.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
            }));
            protoBtn.setOnClickListener(x -> picker(ctx, ps(R.string.icu_protocol), protoOpts, i -> {
                sel[4] = i; protoBtn.setText(protoOpts[i]);
                port.setText(Integer.toString(MediaServerConfig.PushProtocol.values()[i].defaultPort));
            }));
            resBtn.setOnClickListener(x -> picker(ctx, ps(R.string.icu_resolution), resOpts, i -> { sel[1] = i; resBtn.setText(resOpts[i]); }));
            fpsBtn.setOnClickListener(x -> picker(ctx, ps(R.string.icu_framerate), fpsOpts, i -> { sel[2] = i; fpsBtn.setText(fpsOpts[i]); }));
            rotBtn.setOnClickListener(x -> picker(ctx, ps(R.string.icu_rotation), rotOpts, i -> { sel[3] = i; rotBtn.setText(rotOpts[i]); }));
            camBtn.setOnClickListener(x -> picker(ctx, ps(R.string.icu_camera), camOpts, i -> { sel[5] = i; camBtn.setText(camOpts[i]); }));
            widgetBtn.setOnClickListener(x -> picker(ctx, "Status badge", widgetOpts,
                    i -> { widgetSel[0] = i; widgetBtn.setText(widgetOpts[i]); }));
            screenBtn.setOnClickListener(x -> picker(ctx, "Keep streaming when screen off", screenOpts,
                    i -> { screenSel[0] = i; screenBtn.setText(screenOpts[i]); }));

            scanQrBtn.setOnClickListener(x -> {
                if (!hasCameraPermission()) { requestCameraPermission(); return; }
                if (pipeline.isRunning()) {
                    Toast.makeText(ctx, "Stop broadcasting before scanning — the camera is in use.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    new QrScanDialog(ctx, config.rotationDegrees, text -> {
                        try {
                            StreamUrlParser.Parsed p = StreamUrlParser.parse(text);
                            // Only the server identity always comes from the QR. Every
                            // other field is applied *only when the QR carries it*, so one
                            // shared code can provision a whole fleet without clobbering
                            // the per-device stream path and alias — which default to the
                            // operator callsign (see defaultPath()/defaultAlias()) precisely
                            // so operators don't collide on the server.
                            if (sel[0] != 1) { stashVideo.run(); sel[0] = 1; bindVideo.run(); }
                            destBtn.setText(destOpts[1]); srv.setVisibility(View.VISIBLE);
                            sel[4] = p.protocol.ordinal(); protoBtn.setText(protoOpts[sel[4]]);
                            address.setText(p.host);
                            port.setText(Integer.toString(p.port));
                            String msg = "Filled in from QR: " + p.protocol + " " + p.host + ":" + p.port;
                            if (p.path != null) {
                                path.setText(p.path);
                                msg += "/" + p.path;
                            } else {
                                msg += " — kept path \"" + str(path, defaultPath()) + "\"";
                            }
                            if (p.passphrase != null) {
                                scannedPassphrase[0] = p.passphrase;
                                msg += " (passphrase captured)";
                            }
                            if (p.username != null) {
                                user.setText(p.username);
                                if (p.password != null) pass.setText(p.password);
                                msg += " (credentials captured)";
                            }
                            if (p.name != null) {
                                alias.setText(p.name);
                                msg += " — \"" + p.name + "\"";
                            } else {
                                msg += " — kept alias \"" + str(alias, defaultAlias()) + "\"";
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(ctx, "QR isn't a supported stream URL: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }).show();
                } catch (Throwable t) {
                    Log.e(TAG, "QR scan dialog failed", t);
                    Toast.makeText(ctx, "QR scanner error: " + t, Toast.LENGTH_LONG).show();
                }
            });

            settingsSaveBtn.setOnClickListener(v -> {
                serverConfig.alias = str(alias, defaultAlias());
                serverConfig.destination = sel[0] == 1
                        ? MediaServerConfig.Destination.SERVER : MediaServerConfig.Destination.LAN;
                serverConfig.pushProtocol = MediaServerConfig.PushProtocol.values()[sel[4]];
                serverConfig.host = str(address, "");
                serverConfig.serverPort = intOf(port, serverConfig.pushProtocol.defaultPort);
                serverConfig.streamPath = str(path, defaultPath());
                applyPastedAddress(serverConfig);
                serverConfig.username = str(user, "");
                serverConfig.password = str(pass, "");
                serverConfig.srtPassphrase = scannedPassphrase[0];
                stashVideo.run();
                applyProfile(config, profiles[sel[0]]);
                config.recordHeight = recResVals[recResSel[0]];
                config.recordFps = recFpsVals[recFpsSel[0]];
                config.fovRefreshSec = Math.max(1, intOf(fovRefresh, 3));
                config.fovRangeM = Math.max(1, intOf(fovRange, 100));
                config.showStatusWidget = widgetSel[0] == 0;
                config.streamWithScreenOff = screenSel[0] == 1;

                // Persist the active destination's profile (with the server + global
                // fields), then the other destination's — edits made while it was
                // selected must survive even though it isn't the one being used.
                Prefs.save(atakContext(), serverConfig, config);
                Prefs.saveProfile(atakContext(), sel[0] == 1
                        ? MediaServerConfig.Destination.LAN : MediaServerConfig.Destination.SERVER,
                        profiles[1 - sel[0]]);
                statusWidget.setEnabled(config.showStatusWidget);
                if (pipeline.isRunning()) root.setKeepScreenOn(!config.streamWithScreenOff);
                refreshDestBadge();
                refreshDestToggle();
                applyPreviewRotation();
                hideSettingsPage();
                if (pipeline.isRunning()) {
                    Toast.makeText(ctx, "Restarting with new settings…", Toast.LENGTH_SHORT).show();
                    stopBroadcast(); startBroadcast();
                }
            });

            settingsPage.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.e(TAG, "settings page failed", t);
            Toast.makeText(ctx, "Settings error: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private void hideSettingsPage() {
        if (settingsPage != null) settingsPage.setVisibility(View.GONE);
    }

    /** Close the settings page on back rather than the whole drop-down. */
    @Override
    protected boolean onBackButtonPressed() {
        if (settingsPage != null && settingsPage.getVisibility() == View.VISIBLE) {
            hideSettingsPage();
            return true;
        }
        return super.onBackButtonPressed();
    }

    // ── Programmatic dialog helpers ──────────────────────────────────────────────

    private String ps(int resId) { return pluginContext.getString(resId); }
    private CharSequence[] pta(int arrayRes) { return pluginContext.getResources().getTextArray(arrayRes); }

    // Plugin resources are resolved against the PLUGIN context — views are built with the
    // ATAK context (proper theming), but their backgrounds/colors are plugin resources.
    private int pColor(int resId) { return pluginContext.getResources().getColor(resId); }
    private android.graphics.drawable.Drawable pDrawable(int resId) {
        return pluginContext.getResources().getDrawable(resId, pluginContext.getTheme());
    }
    private static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** A titled, <b>collapsible</b> card appended to the settings container; returns the
     *  content layout callers add fields to. Tapping the header row toggles the content. */
    private LinearLayout addCard(Context ctx, String title) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(pDrawable(R.drawable.bg_card));
        int p = dp(ctx, 16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        card.setLayoutParams(lp);

        // Clickable header row (full width): title (fills) + chevron pinned right.
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headerRow.setClickable(true);
        headerRow.setFocusable(true);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView header = new TextView(ctx);
        header.setText(title);
        header.setTextColor(pColor(R.color.icu_accent));
        header.setTextSize(12);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setLetterSpacing(0.06f);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(header);

        final android.widget.ImageView chevron = new android.widget.ImageView(ctx);
        int cs = dp(ctx, 24);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        chevron.setPadding(0, 0, 0, 0);
        chevron.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        chevron.setColorFilter(pColor(R.color.icu_accent));
        chevron.setImageDrawable(pDrawable(R.drawable.ic_chevron_up));
        headerRow.addView(chevron);
        card.addView(headerRow);

        // Content the caller fills; collapses on header tap.
        final LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(ctx, 6), 0, 0);
        card.addView(content);

        View.OnClickListener toggle = v -> {
            boolean show = content.getVisibility() != View.VISIBLE;
            content.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setImageDrawable(pDrawable(
                    show ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down));
        };
        headerRow.setOnClickListener(toggle);
        chevron.setOnClickListener(toggle);

        settingsContainer.addView(card);
        return content;
    }

    private EditText addEdit(Context ctx, LinearLayout parent, String label, String value, int inputType) {
        parent.addView(makeLabel(ctx, label));
        EditText e = new EditText(ctx);
        e.setInputType(inputType);
        if (value != null) e.setText(value);
        styleInput(ctx, e);
        parent.addView(e);
        return e;
    }

    private Button addPicker(Context ctx, LinearLayout parent, String label, CharSequence current) {
        parent.addView(makeLabel(ctx, label));
        Button b = new Button(ctx);
        b.setAllCaps(false);
        b.setText(current);
        styleInput(ctx, b);
        b.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        // Trailing caret so the field visibly reads as a dropdown (not a text field).
        android.graphics.drawable.Drawable caret = pDrawable(R.drawable.ic_chevron_down);
        if (caret != null) {
            caret = caret.mutate();
            caret.setColorFilter(pColor(R.color.icu_text_secondary),
                    android.graphics.PorterDuff.Mode.SRC_IN);
            int sz = dp(ctx, 18);
            caret.setBounds(0, 0, sz, sz);
            b.setCompoundDrawables(null, null, caret, null);
            b.setCompoundDrawablePadding(dp(ctx, 8));
        }
        parent.addView(b);
        return b;
    }

    /** Full-width secondary (outlined) button, e.g. Scan QR. */
    private Button addSecondaryButton(Context ctx, LinearLayout parent, String label) {
        Button b = new Button(ctx);
        b.setAllCaps(false);
        b.setText(label);
        b.setBackground(pDrawable(R.drawable.bg_button_secondary));
        b.setTextColor(pColor(R.color.icu_text_secondary));
        b.setTextSize(14);
        b.setMinWidth(0); b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44));
        b.setLayoutParams(lp);
        parent.addView(b);
        return b;
    }

    /** Apply the design-system input look (bg + colors + sizing) to an EditText/Button. */
    private void styleInput(Context ctx, TextView v) {
        v.setBackground(pDrawable(R.drawable.bg_input));
        v.setTextColor(pColor(R.color.icu_text_primary));
        v.setHintTextColor(pColor(R.color.icu_text_hint));
        v.setTextSize(14);
        int px = dp(ctx, 12);
        v.setPadding(px, 0, px, 0);
        v.setMinHeight(dp(ctx, 44));
        if (v instanceof Button) { ((Button) v).setMinWidth(0); ((Button) v).setMinimumWidth(0); }
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44)));
    }

    private TextView makeLabel(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(pColor(R.color.icu_text_secondary));
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4));
        return t;
    }

    /** If the user pasted a full URL (rtmp://host:port/path) into the address field,
     *  split it into host / port / path so all three are used. */
    private void applyPastedAddress(MediaServerConfig s) {
        String h = s.host == null ? "" : s.host.trim();
        if (h.isEmpty()) return;
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) {
            String p = h.substring(slash + 1).trim();
            if (!p.isEmpty()) s.streamPath = p;
            h = h.substring(0, slash);
        }
        int colon = h.indexOf(':');
        if (colon >= 0) {
            try { s.serverPort = Integer.parseInt(h.substring(colon + 1).trim()); } catch (Exception ignored) {}
            h = h.substring(0, colon);
        }
        s.host = h;
    }

    private interface PickCallback { void onPick(int index); }

    /** Simple choice dialog built from the ATAK activity context (crash-proof). */
    private void picker(Context ctx, String title, CharSequence[] items, PickCallback cb) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setItems(items, (d, which) -> cb.onPick(which))
                .show();
    }

    private static int fpsIndex(int fps) {
        if (fps <= 15) return 0;
        if (fps <= 24) return 1;
        return 2;
    }
    // Orientation setting order (see icu_rotations in strings.xml): Portrait=0°,
    // Landscape=270°, Reverse Portrait=180°, Reverse Landscape=90° — anchored on the
    // device-tested value that Landscape needs a 270° correction, with the other three
    // derived from the fixed 90°-apart / 180°-reverse relationship between them.
    private static int rotationIndex(int deg) {
        switch (deg) { case 270: return 1; case 180: return 2; case 90: return 3; default: return 0; }
    }
    private static int rotationValue(int index) {
        switch (index) { case 1: return 270; case 2: return 180; case 3: return 90; default: return 0; }
    }
    private static String str(EditText e, String def) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? def : s;
    }
    private static int intOf(EditText e, int def) { return intOf(e.getText().toString(), def); }
    private static int intOf(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /** Copy the per-destination capture/encode fields of {@code src} onto {@code dst}
     *  (globals like FOV/status-badge/screen-off are left alone). */
    private static void applyProfile(EncoderConfig dst, EncoderConfig src) {
        dst.resolution      = src.resolution;
        dst.fps             = src.fps;
        dst.bitrateKbps     = src.bitrateKbps;
        dst.useFrontCamera  = src.useFrontCamera;
        dst.cameraId        = src.cameraId;
        dst.rotationDegrees = src.rotationDegrees;
        dst.gopSeconds      = src.gopSeconds;
        dst.streamAudio     = src.streamAudio;
    }

    /** The per-destination capture/encode fields of {@code src}, as a fresh instance. */
    private static EncoderConfig copyProfile(EncoderConfig src) {
        EncoderConfig p = new EncoderConfig();
        applyProfile(p, src);
        return p;
    }

    /** Index of {@code p}'s camera in {@code camList} (or the legacy front/back index).
     *  Prefer an exact id match (from a prior save); otherwise fall back to the
     *  front/back preference so upgrades keep their old choice. */
    private static int cameraIndexFor(List<CameraSource.CameraOption> camList, EncoderConfig p) {
        if (camList.isEmpty()) return p.useFrontCamera ? 1 : 0;
        int idIdx = -1, facingIdx = -1;
        for (int i = 0; i < camList.size(); i++) {
            CameraSource.CameraOption opt = camList.get(i);
            if (opt.id.equals(p.cameraId)) { idIdx = i; break; }
            boolean isFront = opt.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;
            if (facingIdx < 0 && isFront == p.useFrontCamera) facingIdx = i;
        }
        return idIdx >= 0 ? idIdx : Math.max(facingIdx, 0);
    }

    // ── Permissions ──────────────────────────────────────────────────────────────

    private boolean hasCameraPermission() {
        return atakContext().checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        Context ctx = atakContext();
        if (ctx instanceof Activity) {
            ((Activity) ctx).requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            Toast.makeText(ctx, "Grant camera access, then tap Broadcast again.", Toast.LENGTH_LONG).show();
        } else {
            setStatus("Camera permission required — grant ATAK camera access in Android Settings.");
        }
    }

    private boolean hasMicPermission() {
        return atakContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMicPermission() {
        Context ctx = atakContext();
        if (ctx instanceof Activity) {
            ((Activity) ctx).requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            Toast.makeText(ctx, "Grant microphone access for audio, then tap Broadcast again.",
                    Toast.LENGTH_LONG).show();
        } else {
            setStatus("Microphone permission required — grant ATAK mic access in Android Settings.");
        }
    }

    /** Host ATAK Activity context (holds the CAMERA permission, not the plugin). */
    private Context atakContext() { return getMapView().getContext(); }

    // ── DropDown lifecycle ───────────────────────────────────────────────────────

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (SHOW.equals(action)) {
            showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
            if (!pipeline.isRunning()) resetIdleUi();
        } else if (TOGGLE.equals(action)) {
            // Headless start/stop — the panel need not be open. The inflated root view
            // (and its buttons) exist from construction, so the UI updates in
            // start/stopBroadcast are safe even while the pane is closed.
            toggleBroadcast();
        } else if (SNAPSHOT.equals(action)) {
            takeSnapshot();
        } else if (RECORD.equals(action)) {
            takeRecord();
        } else if (BLACKOUT.equals(action)) {
            showBlackout();
        }
    }

    @Override
    protected void disposeImpl() {
        stopRecording(false);
        stopNetworkMonitor();
        pipeline.stop();
        if (transports != null) { transports.stopAll(); transports = null; }
        sensor.stop();
        klv.stop();
        releaseWakeLock();
        dismissBlackout();
    }

    @Override public void onDropDownSelectionRemoved() {}
    @Override public void onDropDownVisible(boolean visible) {}
    @Override public void onDropDownSizeChanged(double width, double height) {}
    /**
     * Closing the pane must NOT stop an active broadcast — the camera/encoder/transports
     * keep running in the background (this receiver is retained — see {@code setRetain}
     * in the constructor — and only torn down in {@code disposeImpl} when the plugin
     * itself unloads). {@link StreamStatusWidget} is the map-anchored indicator of
     * whether it's still live. The preview surface is detached separately, via the
     * TextureView listener, so the dead pane view doesn't break the capture session.
     */
    @Override public void onDropDownClose() {}
}
