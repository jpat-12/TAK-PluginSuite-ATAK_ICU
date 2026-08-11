package com.atakmap.android.icu.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;
import com.atakmap.android.icu.ICUVideoDropDownReceiver;

/**
 * Toolbar button — fires the SHOW intent that opens the ICU VideoStreamer pane.
 */
public class ICUVideoTool extends AbstractPluginTool {

    public ICUVideoTool(Context context) {
        super(context,
                context.getString(R.string.app_name),
                context.getString(R.string.app_name),
                context.getResources().getDrawable(R.drawable.ic_icu_menu),
                ICUVideoDropDownReceiver.SHOW);
    }
}
