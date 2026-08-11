package com.atakmap.android.icu;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.icu.menu.IcuSelfMarkerMenu;
import com.atakmap.android.icu.ui.StreamStatusWidget;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.AbstractWidgetMapComponent;

/**
 * MapComponent for ATAK-ICU — creates and registers the drop-down pane
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
                "Show the ATAK-ICU panel");
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

        // Phase 0 spike: confirm libwebrtc's native lib loads inside ATAK's plugin process.
        // It has: the library loads under the plugin classloader and PeerConnectionFactory
        // constructs. Debug-only from here — the answer is known, and building then disposing
        // a factory on every ATAK start is pure cost in a shipped build. Keep it available
        // while the real transport is written, then delete it with the rest of the spike.
        //
        // Hand it the MapView's (host ATAK) context, not the plugin one — the plugin context
        // has no application context, and libwebrtc's ContextUtils.initialize rejects null.
        if (com.atakmap.android.icu.plugin.BuildConfig.DEBUG) {
            com.atakmap.android.icu.serve.p2p.P2pProbe.run(mapView.getContext());
        }
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
