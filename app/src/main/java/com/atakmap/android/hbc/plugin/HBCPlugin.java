package com.atakmap.android.hbc.plugin;

import android.content.Context;
import android.content.Intent;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.hbc.HBCMapComponent;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * HBCPlugin
 *
 * ATAK 5.x plugin entry point. Declared in assets/plugin.xml as:
 *   <extension type="gov.tak.api.plugin.IPlugin" impl="...HBCPlugin" singleton="true" />
 *
 * ATAK calls the constructor (passing IServiceController), then onStart() / onStop()
 * as the plugin is enabled/disabled.
 */
public class HBCPlugin implements IPlugin {

    private static final String TAG = "HBCPlugin";

    private final IServiceController serviceController;
    private final Context            pluginContext;
    private final IHostUIService     uiService;

    private HBCMapComponent  mapComponent;
    private ToolbarItem      toolbarItem;
    private Pane             settingsPane;

    public HBCPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;

        // Obtain the plugin context (isolated from ATAK's context)
        final PluginContextProvider ctxProvider =
            serviceController.getService(PluginContextProvider.class);
        Context ctx = null;
        if (ctxProvider != null) {
            ctx = ctxProvider.getPluginContext();
            ctx.setTheme(R.style.ATAKPluginTheme);
        }
        this.pluginContext = ctx;

        // Obtain the UI service for adding toolbar items and showing panes
        this.uiService = serviceController.getService(IHostUIService.class);

        // Initialise the native library before anything else touches JNI
        PluginNativeLoader.init(pluginContext);
        PluginNativeLoader.loadLibrary("hbc-ofdm");

        // Initialise the map component (handles CoT TX/RX, PreSendProcessor)
        this.mapComponent = new HBCMapComponent(pluginContext);

        // After any TX: close the HBC Audio pane (returns ATAK to normal map view)
        // and show ATAK's native toast as tactile confirmation of the button press.
        final IHostUIService ui = this.uiService;
        mapComponent.setOnTransmitCallback(() -> {
            if (ui != null) {
                // Close the settings pane so the user returns to the plain map
                if (settingsPane != null && ui.isPaneVisible(settingsPane))
                    ui.closePane(settingsPane);
                // ATAK's own toast — brief, non-intrusive confirmation
                ui.showToast("HBC: Transmitting...");
            }
        });

        // Build the toolbar button that opens the settings pane
        this.toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                    // Use the white, no-background version so ATAK can tint it correctly.
                    // ic_launcher (dark bg + green) is for the plugin manager icon only.
                    pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                    android.graphics.drawable.Drawable.class,
                    gov.tak.api.commons.graphics.Bitmap.class))
            .setListener(new ToolbarItemAdapter() {
                @Override
                public void onClick(ToolbarItem item) {
                    showPane();
                }
            })
            .setIdentifier(pluginContext.getPackageName())
            .build();

        Log.d(TAG, "HBCPlugin constructed");
    }

    @Override
    public void onStart() {
        mapComponent.start();
        if (uiService != null)
            uiService.addToolbarItem(toolbarItem);
        Log.d(TAG, "HBCPlugin started");
    }

    @Override
    public void onStop() {
        mapComponent.stop();
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        Log.d(TAG, "HBCPlugin stopped");
    }

    // ─── Settings pane ───────────────────────────────────────────────────────

    private void showPane() {
        if (uiService == null) return;

        if (settingsPane == null) {
            // Inflate the settings layout using PluginLayoutInflater so resources
            // resolve against the plugin's context rather than ATAK's context.
            android.view.View settingsView = PluginLayoutInflater.inflate(
                pluginContext, R.layout.dropdown, null);

            // Bind the UI controls to preferences / audio devices
            mapComponent.bindSettingsView(settingsView);

            settingsPane = new PaneBuilder(settingsView)
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO,  0.4D)
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6D)
                .build();
        }

        if (!uiService.isPaneVisible(settingsPane))
            uiService.showPane(settingsPane, null);
    }
}
