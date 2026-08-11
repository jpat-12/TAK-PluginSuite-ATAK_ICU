package com.atakmap.android.icu;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.icu.menu.IcuSelfMarkerMenu;
import com.atakmap.android.icu.ui.StreamStatusWidget;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.AbstractWidgetMapComponent;

/**
 * MapComponent for ICU VideoStreamer — creates and registers the drop-down pane
 * receiver, and owns its lifecycle.
 *
 * <p>Extends {@link AbstractWidgetMapComponent} (rather than the plain
 * AbstractMapComponent) so it also owns a {@code LayoutWidget} that renders
 * directly on the map, independent of the drop-down pane's open/closed state —
 * this is what backs {@link StreamStatusWidget}, the persistent broadcast-status
 * indicator that stays visible after the plugin panel is closed.</p>
 */
public class ICUVideoMapComponent extends AbstractWidgetMapComponent {

    private ICUVideoDropDownReceiver dropDown;
    private StreamStatusWidget statusWidget;
    private IcuSelfMarkerMenu radialMenu;

    @Override
    protected void onCreateWidgets(Context context, Intent intent, MapView mapView) {
        // context here is the plugin context (from the plugin classloader).
        statusWidget = new StreamStatusWidget(mapView, context, getRootLayoutWidget());
        dropDown = new ICUVideoDropDownReceiver(mapView, context, statusWidget);

        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(ICUVideoDropDownReceiver.SHOW,
                "Show the ICU VideoStreamer panel");
        filter.addAction(ICUVideoDropDownReceiver.TOGGLE,
                "Start/stop the ICU broadcast without opening the panel");
        filter.addAction(ICUVideoDropDownReceiver.SNAPSHOT,
                "Capture a snapshot of the current frame");
        filter.addAction(ICUVideoDropDownReceiver.RECORD,
                "Toggle local recording");
        filter.addAction(ICUVideoDropDownReceiver.BLACKOUT,
                "Black out the screen while streaming continues");
        registerReceiver(context, dropDown, filter);

        // Add the ICU button (→ Broadcast/Record/Snapshot submenu) to the self-marker
        // radial. Mirrors FeatureLink: a MapMenuFactory that rebuilds + augments the menu.
        radialMenu = new IcuSelfMarkerMenu(mapView, context);
        radialMenu.register();
    }

    @Override
    protected void onDestroyWidgets(Context context, MapView mapView) {
        if (radialMenu != null) {
            radialMenu.dispose();
            radialMenu = null;
        }
        if (dropDown != null) {
            unregisterReceiver(context, dropDown);
            dropDown.dispose();
            dropDown = null;
        }
        if (statusWidget != null) {
            statusWidget.dispose();
            statusWidget = null;
        }
    }
}
