package com.atakmap.android.icu.menu;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import com.atakmap.android.icu.ICUVideoDropDownReceiver;
import com.atakmap.android.icu.plugin.R;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapDataRef;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.assets.MapAssets;
import com.atakmap.android.menu.MapMenuButtonWidget;
import com.atakmap.android.menu.MapMenuFactory;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.menu.MapMenuWidget;
import com.atakmap.android.menu.MenuMapAdapter;
import com.atakmap.android.menu.MenuResourceFactory;
import com.atakmap.android.widgets.AbstractButtonWidget;
import com.atakmap.android.widgets.WidgetIcon;
import com.atakmap.coremap.log.Log;

import gov.tak.api.widgets.IMapMenuButtonWidget;
import gov.tak.api.widgets.IMapWidget;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Part B2 — adds a dedicated <b>ICU</b> button to the operator's own self-marker radial
 * menu; pressing it opens a submenu of Broadcast / Record / Snapshot, each firing the
 * matching headless intent handled by {@link ICUVideoDropDownReceiver}.
 *
 * <p>Implemented as a {@link MapMenuFactory} (mirroring the FeatureLink plugin): we
 * rebuild the item's default menu via {@link MenuResourceFactory} and append our button,
 * so ATAK lays the whole ring out in one pass and our button sits <i>in</i> the radial
 * (not floating over it). The button geometry — {@code setOrientation} /
 * {@code setButtonSize} / {@code setLayoutWeight} — matches the sibling buttons, per the
 * SDK radialmenudemo pattern.</p>
 */
public final class IcuSelfMarkerMenu implements MapMenuFactory {

    private static final String TAG = "ICU.RadialMenu";
    private static final int ICON_PX = 32;

    private final MapView mapView;
    private final Context appContext;   // ATAK context (widgets + writable cache)
    private final Context pluginCtx;    // plugin context (drawable resources)
    private final MenuResourceFactory resourceFactory;
    private boolean registered;

    public IcuSelfMarkerMenu(MapView mapView, Context pluginCtx) {
        this.mapView = mapView;
        this.pluginCtx = pluginCtx;
        this.appContext = mapView.getContext();

        MapAssets mapAssets = new MapAssets(appContext);
        MenuMapAdapter adapter = new MenuMapAdapter();
        try {
            adapter.loadMenuFilters(mapAssets, "filters/menu_filters.xml");
        } catch (IOException e) {
            Log.w(TAG, "menu filters default: " + e.getMessage());
        }
        resourceFactory = new MenuResourceFactory(
                mapView, mapView.getMapData(), mapAssets, adapter);
    }

    public void register() {
        MapMenuReceiver r = MapMenuReceiver.getInstance();
        if (r != null && !registered) {
            r.registerMapMenuFactory(this);
            registered = true;
            Log.d(TAG, "ICU self-marker radial factory registered");
        } else {
            Log.w(TAG, "radial factory NOT registered (receiver=" + r + ")");
        }
    }

    public void dispose() {
        MapMenuReceiver r = MapMenuReceiver.getInstance();
        if (r != null && registered) {
            r.unregisterMapMenuFactory(this);
            registered = false;
        }
    }

    @Override
    public MapMenuWidget create(MapItem item) {
        // Only our own skittle.
        if (item == null || mapView.getSelfMarker() != item) return null;

        MapMenuWidget menu = resourceFactory.create(item);
        if (menu == null) return null;

        MapMenuButtonWidget icu = buildIcuButton(menu);
        if (icu != null) {
            menu.addChildWidget(icu);
            Log.d(TAG, "appended ICU button to self-marker radial");
        }
        return menu;
    }

    private MapMenuButtonWidget buildIcuButton(MapMenuWidget menu) {
        try {
            WidgetIcon icon = icon(R.drawable.ic_icu_menu);
            if (icon == null) {
                Log.w(TAG, "ICU button skipped — icon failed to build");
                return null;
            }
            MapMenuButtonWidget btn = new MapMenuButtonWidget(appContext);

            // Position/size to match sibling buttons (radialmenudemo pattern).
            btn.setOrientation(btn.getOrientationAngle(), menu.getInnerRadius());
            btn.setButtonSize(btn.getButtonSpan(), menu.getButtonWidth());
            btn.setLayoutWeight(averageLayoutWeight(menu));

            btn.setIcon(icon);
            btn.setSubmenu(buildSubmenu(menu));
            return btn;
        } catch (Exception e) {
            Log.e(TAG, "buildIcuButton failed", e);
            return null;
        }
    }

    /** Broadcast / Record / Snapshot / Blackout ring shown when the ICU button is pressed. */
    private MapMenuWidget buildSubmenu(MapMenuWidget parent) {
        MapMenuWidget sub = new MapMenuWidget();
        sub.setButtonWidth(parent.getButtonWidth());
        // One ring out from the parent so the submenu clears the base radial.
        sub.setInnerRadius(parent.getInnerRadius() + parent.getButtonWidth());
        addSubButton(sub, R.drawable.ic_broadcast,     ICUVideoDropDownReceiver.TOGGLE);
        addSubButton(sub, textIcon("BLK\nOUT"),        ICUVideoDropDownReceiver.BLACKOUT);
        addSubButton(sub, R.drawable.ic_record,        ICUVideoDropDownReceiver.RECORD);
        addSubButton(sub, R.drawable.ic_snapshot,      ICUVideoDropDownReceiver.SNAPSHOT);
        return sub;
    }

    private void addSubButton(MapMenuWidget sub, int iconRes, final String action) {
        addSubButton(sub, icon(iconRes), action);
    }

    private void addSubButton(MapMenuWidget sub, WidgetIcon icon, final String action) {
        if (icon == null) return;
        MapMenuButtonWidget b = new MapMenuButtonWidget(appContext);
        b.setOrientation(b.getOrientationAngle(), sub.getInnerRadius());
        b.setButtonSize(b.getButtonSpan(), sub.getButtonWidth());
        b.setLayoutWeight(1f);
        b.setIcon(icon);
        b.setOnButtonClickHandler(new IMapMenuButtonWidget.OnButtonClickHandler() {
            @Override public boolean isSupported(Object o) { return true; }
            @Override public void performAction(Object o) {
                AtakBroadcast.getInstance().sendBroadcast(new Intent(action));
            }
        });
        sub.addChildWidget(b);
    }

    private float averageLayoutWeight(MapMenuWidget menu) {
        float total = 0f;
        for (IMapWidget child : menu.getChildren())
            if (child instanceof MapMenuButtonWidget)
                total += ((MapMenuButtonWidget) child).getLayoutWeight();
        int n = menu.getChildWidgetCount();
        return n > 0 ? total / n : 1f;
    }

    /** Rasterize a (vector) plugin drawable to a PNG in ATAK's cache and wrap it as a WidgetIcon.
     *  Cache is keyed by resource NAME (stable across builds) — numeric ids shift between
     *  builds and would serve a stale PNG from a different icon. */
    private WidgetIcon icon(int iconRes) {
        try {
            String name = pluginCtx.getResources().getResourceEntryName(iconRes);
            File png = new File(appContext.getCacheDir(), "icu_radial_" + name + ".png");
            if (!png.exists()) {
                Drawable d = pluginCtx.getResources().getDrawable(iconRes, pluginCtx.getTheme());
                if (d == null) return null;
                Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, ICON_PX, ICON_PX);
                d.draw(c);
                writePng(bmp, png);
            }
            return iconFromPng(png);
        } catch (Throwable t) {
            Log.w(TAG, "icon build failed for res " + iconRes + ": " + t.getMessage());
            return null;
        }
    }

    /** Render a short (optionally multi-line, split on '\n') text label as a radial-button
     *  icon — radial buttons must be icons, not text. */
    private WidgetIcon textIcon(String text) {
        try {
            String[] lines = text.split("\n");
            String key = text.replaceAll("[^A-Za-z0-9]", "_");
            File png = new File(appContext.getCacheDir(), "icu_radial_txt_" + key + ".png");
            if (!png.exists()) {
                Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xFFFFFFFF);
                p.setFakeBoldText(true);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);

                // Size so the widest line spans ~92% of the icon width...
                p.setTextSize(ICON_PX);
                float maxW = 0;
                for (String ln : lines) maxW = Math.max(maxW, p.measureText(ln));
                if (maxW > 0) p.setTextSize(ICON_PX * 0.92f * ICON_PX / maxW);
                // ...then shrink if the stacked lines don't fit the height.
                android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
                float lineH = (fm.descent - fm.ascent) * 0.92f;
                float totalH = lineH * lines.length;
                if (totalH > ICON_PX) {
                    p.setTextSize(p.getTextSize() * ICON_PX / totalH);
                    fm = p.getFontMetrics();
                    lineH = (fm.descent - fm.ascent) * 0.92f;
                    totalH = lineH * lines.length;
                }
                float baseline = ICON_PX / 2f - totalH / 2f - fm.ascent;
                for (int i = 0; i < lines.length; i++)
                    c.drawText(lines[i], ICON_PX / 2f, baseline + i * lineH, p);

                writePng(bmp, png);
            }
            return iconFromPng(png);
        } catch (Throwable t) {
            Log.w(TAG, "text icon build failed for '" + text + "': " + t.getMessage());
            return null;
        }
    }

    private static void writePng(Bitmap bmp, File png) throws Exception {
        FileOutputStream os = new FileOutputStream(png);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        bmp.recycle();
    }

    private WidgetIcon iconFromPng(File png) {
        MapDataRef ref = MapDataRef.parseUri("file://" + png.getAbsolutePath());
        if (ref == null) return null;
        return new WidgetIcon.Builder()
                .setImageRef(0, ref)
                .setImageRef(AbstractButtonWidget.STATE_PRESSED, ref)
                .setImageRef(AbstractButtonWidget.STATE_SELECTED, ref)
                .setImageRef(AbstractButtonWidget.STATE_DISABLED, ref)
                .setAnchor(ICON_PX / 2, ICON_PX / 2)
                .setSize(ICON_PX, ICON_PX)
                .build();
    }
}
