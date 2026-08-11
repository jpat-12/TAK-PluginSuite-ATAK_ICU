package com.atakmap.android.icu.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.atakmap.android.icu.ICUVideoDropDownReceiver;
import com.atakmap.android.icu.plugin.R;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.navigation.views.NavView;
import com.atakmap.android.navigation.views.buttons.NavZoomButton;
import com.atakmap.android.widgets.LayoutWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.MarkerIconWidget;
import com.atakmap.android.widgets.TextWidget;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.map.AtakMapView;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Persistent map-anchored status indicator — the plugin icon with a small red/green
 * dot badge and a pulsing "LIVE" label while streaming, mirroring ATAK's built-in
 * "Display Connection Widget" (Settings › Network Preferences › Network Connection
 * Preferences). Lives in the plugin's own {@link LayoutWidget} (owned by
 * {@code ICUVideoMapComponent}, an {@code AbstractWidgetMapComponent}), so it renders
 * on the map regardless of whether the plugin's drop-down pane is open.
 *
 * <p>Defaults to sitting directly below ATAK's on-screen zoom +/- control
 * ({@link NavZoomButton}, found by walking {@link NavView}'s view tree — there's no
 * public accessor for it) so it reads as part of the same left-side HUD cluster. A
 * long-press-and-drag on the icon lets the user relocate it; once moved, the custom
 * position is persisted (per device) and the zoom-button auto-alignment is disabled
 * until app data is cleared. A plain tap always opens the plugin pane.</p>
 */
public class StreamStatusWidget implements AtakMapView.OnMapViewResizedListener {

    private static final String TAG = "ICU.StreamStatusWidget";

    private static final float ICON_DP     = 40f;
    private static final float DOT_DP      = 14f;
    private static final float GAP_DP      = 6f;
    private static final float MARGIN_X_DP = 16f;
    private static final float LABEL_RESERVE_DP = 26f; // vertical room to keep "LIVE" on-screen

    private static final String PREFS_NAME  = "icu_status_widget";
    private static final String KEY_USER_POSITIONED = "user_positioned";
    private static final String KEY_X = "pos_x";
    private static final String KEY_Y = "pos_y";

    // "LIVE" breathes between MIN_ALPHA and full opacity over PULSE_PERIOD_MS.
    private static final int  MIN_ALPHA        = 70;
    private static final long PULSE_PERIOD_MS  = 1400L;
    private static final long PULSE_FRAME_MS   = 50L;
    private static final int  LIVE_TEXT_RGB    = 0x2ECC71;

    private final MapView mapView;
    private final LayoutWidget rootLayout;
    private final MarkerIconWidget iconWidget = new MarkerIconWidget();
    private final MarkerIconWidget dotWidget  = new MarkerIconWidget();
    private final TextWidget liveText;
    private final SharedPreferences prefs;

    private final Icon dotGreen;
    private final Icon dotRed;

    private final float density;
    private final float iconPx;
    private final float dotPx;
    private final float gapPx;
    private final float marginXPx;
    private final float labelReservePx;
    private final float liveTextWidthPx;

    private boolean streaming;
    private boolean userPositioned;
    private float customX;
    private float customY;

    // Drag state (icon widget is the drag handle for the whole group).
    private float dragAnchorDX;
    private float dragAnchorDY;
    private boolean longPressArmed;
    private boolean dragMoved;

    private final ViewTreeObserver.OnGlobalLayoutListener navLayoutListener = this::relayout;
    private final Handler pulseHandler = new Handler(Looper.getMainLooper());
    private long pulseStartMs;
    private final Runnable pulseTick = new Runnable() {
        @Override public void run() {
            if (!streaming) return;
            long elapsed = System.currentTimeMillis() - pulseStartMs;
            double phase = (elapsed % PULSE_PERIOD_MS) / (double) PULSE_PERIOD_MS;
            double wave = 0.5 - 0.5 * Math.cos(phase * 2 * Math.PI); // 0 -> 1 -> 0
            int alpha = (int) (MIN_ALPHA + wave * (255 - MIN_ALPHA));
            liveText.setColor((alpha << 24) | LIVE_TEXT_RGB);
            pulseHandler.postDelayed(this, PULSE_FRAME_MS);
        }
    };

    public StreamStatusWidget(MapView mapView, Context pluginContext, LayoutWidget rootLayout) {
        this.mapView = mapView;
        this.rootLayout = rootLayout;

        density        = mapView.getContext().getResources().getDisplayMetrics().density;
        iconPx         = ICON_DP * density;
        dotPx          = DOT_DP * density;
        gapPx          = GAP_DP * density;
        marginXPx      = MARGIN_X_DP * density;
        labelReservePx = LABEL_RESERVE_DP * density;

        // Persist against the HOST ATAK context: a plugin context's SharedPreferences
        // are not backed by ATAK's persistent data dir and are lost on restart. Unlike
        // the resource/icon lookups below (which MUST use the plugin package), a named
        // prefs file keyed off the host context is collision-free and persistent.
        prefs = mapView.getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        userPositioned = prefs.getBoolean(KEY_USER_POSITIONED, false);
        customX = prefs.getFloat(KEY_X, 0f);
        customY = prefs.getFloat(KEY_Y, 0f);

        // Must pair the plugin's own R with the plugin's own package name — pairing it
        // with the host ATAK app's package (mapView.getContext()) resolves to the wrong
        // resources.arsc and silently fails to load (blank/invisible icon).
        String imageUri = "android.resource://" + pluginContext.getPackageName()
                + "/" + R.drawable.ic_launcher;
        Icon pluginIcon = new Icon.Builder()
                .setAnchor(0, 0)
                .setColor(Icon.STATE_DEFAULT, Color.WHITE)
                .setSize((int) iconPx, (int) iconPx)
                .setImageUri(Icon.STATE_DEFAULT, imageUri)
                .build();
        iconWidget.setIcon(pluginIcon);

        dotGreen = buildDotIcon(pluginContext, 0xFF2ECC71, "icu_status_dot_green.png");
        dotRed   = buildDotIcon(pluginContext, 0xFFE74C3C, "icu_status_dot_red.png");
        dotWidget.setIcon(dotRed);

        int liveTextSizePx = Math.round(10 * density);
        liveText = new TextWidget("LIVE",
                new MapTextFormat(Typeface.DEFAULT_BOLD, liveTextSizePx));
        liveText.setColor(0xFF000000 | LIVE_TEXT_RGB);
        // TextWidget doesn't expose measured width, so measure the same font ourselves
        // to horizontally center the label under the icon.
        TextPaint measure = new TextPaint();
        measure.setTypeface(Typeface.DEFAULT_BOLD);
        measure.setTextSize(liveTextSizePx);
        liveTextWidthPx = measure.measureText("LIVE");
        liveText.setBackground(TextWidget.TRANSLUCENT_BLACK);
        liveText.setVisible(false);

        // Tap = open the plugin pane. Long-press-and-drag = relocate the whole badge.
        iconWidget.addOnClickListener(new MapWidget.OnClickListener() {
            @Override public void onMapWidgetClick(MapWidget widget, MotionEvent event) {
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent(ICUVideoDropDownReceiver.SHOW));
            }
        });
        iconWidget.addOnPressListener(new MapWidget.OnPressListener() {
            @Override public void onMapWidgetPress(MapWidget widget, MotionEvent event) {
                dragAnchorDX = iconWidget.getPointX() - event.getX();
                dragAnchorDY = iconWidget.getPointY() - event.getY();
                longPressArmed = false;
                dragMoved = false;
            }
        });
        iconWidget.addOnLongPressListener(new MapWidget.OnLongPressListener() {
            @Override public void onMapWidgetLongPress(MapWidget widget) {
                longPressArmed = true;
            }
        });
        iconWidget.addOnMoveListener(new MapWidget.OnMoveListener() {
            @Override public boolean onMapWidgetMove(MapWidget widget, MotionEvent event) {
                if (!longPressArmed) return false;
                dragMoved = true;
                float x = clamp(event.getX() + dragAnchorDX, 0, mapView.getWidth() - iconPx);
                float y = clamp(event.getY() + dragAnchorDY, 0,
                        mapView.getHeight() - iconPx - labelReservePx);
                placeAt(x, y);
                return true;
            }
        });
        iconWidget.addOnUnpressListener(new MapWidget.OnUnpressListener() {
            @Override public void onMapWidgetUnpress(MapWidget widget, MotionEvent event) {
                if (dragMoved) {
                    userPositioned = true;
                    customX = iconWidget.getPointX();
                    customY = iconWidget.getPointY();
                    prefs.edit().putBoolean(KEY_USER_POSITIONED, true)
                            .putFloat(KEY_X, customX).putFloat(KEY_Y, customY).apply();
                }
                longPressArmed = false;
                dragMoved = false;
            }
        });

        rootLayout.addWidget(iconWidget);
        rootLayout.addWidget(dotWidget);
        rootLayout.addWidget(liveText);
        mapView.addOnMapViewResizedListener(this);
        // Re-sync if the zoom button moves for a reason other than a map resize
        // (nav flipped to the right side, buttons toggled, etc) — no-op once the
        // user has dragged the badge to a custom position.
        NavView.getInstance().getViewTreeObserver().addOnGlobalLayoutListener(navLayoutListener);
        // First layout pass may not have happened yet when the plugin loads.
        mapView.post(this::relayout);
        relayout();
    }

    private boolean enabled = true;

    /** Show/hide the whole badge (user setting). Idempotent. */
    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (on) {
            rootLayout.addWidget(iconWidget);
            rootLayout.addWidget(dotWidget);
            rootLayout.addWidget(liveText);
            mapView.post(this::relayout);
        } else {
            rootLayout.removeWidget(iconWidget);
            rootLayout.removeWidget(dotWidget);
            rootLayout.removeWidget(liveText);
        }
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
        dotWidget.setIcon(streaming ? dotGreen : dotRed);
        liveText.setVisible(streaming);
        pulseHandler.removeCallbacks(pulseTick);
        if (streaming) {
            pulseStartMs = System.currentTimeMillis();
            pulseHandler.post(pulseTick);
        } else {
            liveText.setColor(0xFF000000 | LIVE_TEXT_RGB); // reset to full opacity for next time
        }
    }

    public void dispose() {
        pulseHandler.removeCallbacks(pulseTick);
        mapView.removeOnMapViewResizedListener(this);
        NavView.getInstance().getViewTreeObserver().removeOnGlobalLayoutListener(navLayoutListener);
        rootLayout.removeWidget(iconWidget);
        rootLayout.removeWidget(dotWidget);
        rootLayout.removeWidget(liveText);
    }

    @Override
    public void onMapViewResized(AtakMapView view) {
        relayout();
    }

    private void relayout() {
        float x, y;
        if (userPositioned) {
            x = clamp(customX, 0, mapView.getWidth() - iconPx);
            y = clamp(customY, 0, mapView.getHeight() - iconPx - labelReservePx);
        } else {
            View zoomButton = findZoomButton(NavView.getInstance());
            if (zoomButton != null && zoomButton.getWidth() > 0) {
                int[] mapLoc = new int[2];
                mapView.getLocationOnScreen(mapLoc);
                int[] zoomLoc = new int[2];
                zoomButton.getLocationOnScreen(zoomLoc);
                // Same left edge as the zoom control, stacked directly beneath it.
                x = zoomLoc[0] - mapLoc[0];
                y = (zoomLoc[1] - mapLoc[1]) + zoomButton.getHeight() + gapPx;
            } else {
                // Zoom control not found (disabled, or NavView not laid out yet) — fall
                // back to a reasonable left-edge, vertically-centered position.
                x = marginXPx;
                y = mapView.getHeight() / 2f;
            }
        }
        placeAt(x, y);
    }

    private void placeAt(float x, float y) {
        iconWidget.setPoint(x, y);
        dotWidget.setPoint(x + iconPx - dotPx * 0.6f, y + iconPx - dotPx * 0.6f);
        liveText.setPoint(x + iconPx / 2f - liveTextWidthPx / 2f, y + iconPx + gapPx);
    }

    private static float clamp(float v, float min, float max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    /** Recursively searches ATAK's nav bar for the zoom +/- control (no public accessor). */
    private static View findZoomButton(View root) {
        if (root instanceof NavZoomButton) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findZoomButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Draws a small filled+outlined circle once and caches it as a PNG (file:// Icon URI). */
    private Icon buildDotIcon(Context pluginContext, int argbColor, String cacheFileName) {
        int size = Math.round(dotPx);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float cx = size / 2f;
        float cy = size / 2f;
        float radius = size / 2f - density; // leave room for the outline stroke

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(argbColor);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(density);
        canvas.drawCircle(cx, cy, radius, stroke);

        File out = new File(pluginContext.getCacheDir(), cacheFileName);
        try (FileOutputStream os = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        } catch (Exception e) {
            Log.w(TAG, "buildDotIcon: failed writing " + cacheFileName, e);
        }

        return new Icon.Builder()
                .setAnchor(0, 0)
                .setSize(size, size)
                .setImageUri(Icon.STATE_DEFAULT, "file://" + out.getAbsolutePath())
                .build();
    }
}
