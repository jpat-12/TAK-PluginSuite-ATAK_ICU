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
import com.atakmap.android.icu.util.Prefs;
import com.atakmap.android.icu.util.StreamUrlParser;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.util.List;

/**
 * ICU VideoStreamer main pane.
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
    private final EncoderConfig     config       = new EncoderConfig();
    private final MediaServerConfig serverConfig = new MediaServerConfig();
    // Injects the FOV + video into the operator's OWN outbound self CoT via
    // CotMapComponent.addAdditionalDetail — the FOV rides on the skittle's own CoT
    // (ATAK renders it), no separate marker. See SelfMarkerFov.
    private final SelfMarkerFov sensor = new SelfMarkerFov();

    // Registers the stream as a feed on the TAK Server's Video Feed Manager (server DB
    // via /Marti/vcm) when pushing to a server. See VideoConnectionPublisher.
    private final com.atakmap.android.icu.share.VideoConnectionPublisher videoPublisher =
            new com.atakmap.android.icu.share.VideoConnectionPublisher();
    private final StreamStatusWidget statusWidget;

    private TransportManager transports;

    private TextView    statusText;
    private int         defaultStatusColor;    // status text's normal colour (for reset after errors)
    private boolean     authFailureHandled;    // guard so a push auth failure auto-stops only once
    private TextView    destBadge;
    private TextView    authBadge;
    private TextView    previewHint;
    private TextView    broadcastLabel;
    private View        liveDot;
    private ImageButton broadcastButton;
    private ImageButton recordButton;
    private ImageButton snapshotButton;
    private ImageButton settingsButton;
    private TextureView previewView;
    private volatile Surface previewSurface;

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
        liveDot         = root.findViewById(R.id.icu_live_dot);
        broadcastButton = root.findViewById(R.id.icu_broadcast_button);
        recordButton    = root.findViewById(R.id.icu_record_button);
        snapshotButton  = root.findViewById(R.id.icu_snapshot_button);
        settingsButton  = root.findViewById(R.id.icu_settings_button);

        setupPreview();

        // Persist against the HOST ATAK context, not the plugin context. A plugin
        // context's SharedPreferences are not backed by ATAK's persistent data dir,
        // so they're lost on restart (values only survive in the in-memory cache
        // during the session). atakContext() == getMapView().getContext().
        Prefs.load(atakContext(), serverConfig, config);
        refreshDestBadge();
        statusWidget.setEnabled(config.showStatusWidget);
        sensor.clearStaleFov();   // clear any FOV a prior build left stuck on the self marker

        // Settings page (pushed overlay with its own back button).
        settingsPage      = root.findViewById(R.id.icu_settings_page);
        settingsContainer = root.findViewById(R.id.icu_settings_container);
        settingsSaveBtn   = root.findViewById(R.id.icu_settings_save);
        root.findViewById(R.id.icu_settings_back).setOnClickListener(v -> hideSettingsPage());
        root.findViewById(R.id.icu_settings_cancel).setOnClickListener(v -> hideSettingsPage());

        broadcastButton.setOnClickListener(v -> toggleBroadcast());
        recordButton.setOnClickListener(v -> takeRecord());
        snapshotButton.setOnClickListener(v -> takeSnapshot());
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
                previewSurface = new Surface(st);
                applyPreviewRotation();
                // Re-attach preview to an already-running capture session (dropdown reopened
                // while broadcasting continued in the background).
                pipeline.setPreviewSurface(previewSurface);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
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
        boolean swap = (deg == 90 || deg == 270);
        // Source frame dimensions in display orientation (swapped for 90/270).
        float dispW = swap ? config.resolution.h : config.resolution.w;
        float dispH = swap ? config.resolution.w : config.resolution.h;

        float cx = vw / 2f, cy = vh / 2f;
        RectF viewRect = new RectF(0, 0, vw, vh);
        RectF srcRect  = new RectF(0, 0, dispW, dispH);
        srcRect.offset(cx - srcRect.centerX(), cy - srcRect.centerY());

        Matrix m = new Matrix();
        // Undo the default buffer→view stretch, giving square pixels at source aspect...
        m.setRectToRect(viewRect, srcRect, Matrix.ScaleToFit.FILL);
        // ...then scale so the frame covers the whole pane (center-crop — max, so the
        // longer edge fills the pane and the overflow on the other axis is cropped)...
        float scale = Math.max(vw / dispW, vh / dispH);
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

        pipeline.setSink(transports);
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
                    // Register the stream on the TAK Server's Video Feed Manager (server DB)
                    // when pushing to a server — makes it discoverable server-side, not just
                    // via the CoT feed on the self marker.
                    if (serverConfig.pushEnabled()) {
                        ensureFeedUuid();
                        videoPublisher.publish(serverConfig, serverConfig.feedUuid);
                    }
                    statusWidget.setStreaming(true);
                    updateLiveStatus(0);
                });
            }
            @Override public void onError(String message) {
                Log.w(TAG, "capture error: " + message);
                ui.post(() -> {
                    sensor.stop();
                    if (transports != null) transports.stopAll();
                    releaseWakeLock();
                    statusWidget.setStreaming(false);
                    resetIdleUi();
                    setStatus("Failed: " + message);
                });
            }
            @Override public void onFrame(int totalNalUnits) {
                if (totalNalUnits % 30 == 0) ui.post(() -> updateLiveStatus(totalNalUnits));
            }
        });
    }

    private void stopBroadcast() {
        sensor.stop();                       // revert self marker to the user's prefs
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

    /** Default stream path — the callsign (path-safe), else icu. Avoids operators
     *  colliding on the same server path when they clear the field. */
    private String defaultPath() {
        String cs = getMapView().getDeviceCallsign();
        if (cs != null) cs = cs.replaceAll("[^A-Za-z0-9_-]", "");
        return (cs != null && !cs.isEmpty()) ? cs : "icu";
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

    /** Local MP4 recording — planned; the encoded stream is already flowing to transports. */
    private void takeRecord() {
        Toast.makeText(atakContext(), "Local recording is coming soon.", Toast.LENGTH_SHORT).show();
    }

    // ── Status UI ────────────────────────────────────────────────────────────────

    private void updateLiveStatus(int frames) {
        if (authFailureHandled) return;   // already auto-stopped; keep the error on screen

        // A server push that failed (e.g. bad credentials) isn't reaching anyone, even
        // though the encoder keeps producing frames — so stop the broadcast outright
        // instead of showing a false "LIVE".
        String failure = (transports != null) ? transports.failureReason() : null;
        if (failure != null) { handlePushFailure(failure); return; }

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
            final CharSequence[] camOpts  = pta(R.array.icu_cameras);

            sel[0] = serverConfig.destination == MediaServerConfig.Destination.SERVER ? 1 : 0;
            sel[1] = config.resolution.ordinal();
            sel[2] = fpsIndex(config.fps);
            sel[3] = rotationIndex(config.rotationDegrees);
            sel[4] = serverConfig.pushProtocol.ordinal();
            sel[5] = config.useFrontCamera ? 1 : 0;
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

            destBtn.setOnClickListener(x -> picker(ctx, ps(R.string.icu_destination), destOpts, i -> {
                sel[0] = i; destBtn.setText(destOpts[i]);
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
                            sel[0] = 1; destBtn.setText(destOpts[1]); srv.setVisibility(View.VISIBLE);
                            sel[4] = p.protocol.ordinal(); protoBtn.setText(protoOpts[sel[4]]);
                            address.setText(p.host);
                            port.setText(Integer.toString(p.port));
                            path.setText(p.path);
                            String msg = "Filled in from QR: " + p.protocol + " " + p.host + ":" + p.port;
                            if (p.passphrase != null) {
                                scannedPassphrase[0] = p.passphrase;
                                msg += " (passphrase captured)";
                            }
                            if (p.name != null && !p.name.isEmpty()) {
                                alias.setText(p.name);
                                msg += " — \"" + p.name + "\"";
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
                config.resolution = EncoderConfig.Resolution.values()[sel[1]];
                config.fps = intOf(fpsOpts[sel[2]].toString(), 30);
                config.bitrateKbps = intOf(bitrate, 2500);
                config.rotationDegrees = rotationValue(sel[3]);
                config.useFrontCamera = sel[5] == 1;
                config.gopSeconds = gopVals[gopSel[0]];
                config.fovRefreshSec = Math.max(1, intOf(fovRefresh, 3));
                config.fovRangeM = Math.max(1, intOf(fovRange, 100));
                config.streamAudio = audioSel[0] == 1;
                config.showStatusWidget = widgetSel[0] == 0;
                config.streamWithScreenOff = screenSel[0] == 1;

                Prefs.save(atakContext(), serverConfig, config);
                statusWidget.setEnabled(config.showStatusWidget);
                if (pipeline.isRunning()) root.setKeepScreenOn(!config.streamWithScreenOff);
                refreshDestBadge();
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
        pipeline.stop();
        if (transports != null) { transports.stopAll(); transports = null; }
        sensor.stop();
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
