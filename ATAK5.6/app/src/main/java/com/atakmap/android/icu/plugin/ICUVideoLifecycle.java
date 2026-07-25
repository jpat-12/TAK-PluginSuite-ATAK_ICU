package com.atakmap.android.icu.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.icu.ICUVideoMapComponent;

import gov.tak.api.plugin.IServiceController;

/**
 * Plugin entry point for ICU VideoStreamer — registered in assets/plugin.xml.
 *
 * <p>Uses {@link AbstractPlugin} so we inherit the standard AbstractPluginTool +
 * AbstractMapComponent wiring (the same pattern as the QuickCapture reference).</p>
 */
public class ICUVideoLifecycle extends AbstractPlugin {

    public ICUVideoLifecycle(IServiceController serviceController) {
        super(serviceController,
                new ICUVideoTool(initContext(serviceController)),
                new ICUVideoMapComponent());
    }

    // super() must be the first statement, so theme init lives in a static helper.
    private static Context initContext(IServiceController serviceController) {
        Context ctx = serviceController.getService(PluginContextProvider.class)
                .getPluginContext();
        ctx.setTheme(R.style.ATAKPluginTheme);
        return ctx;
    }
}
