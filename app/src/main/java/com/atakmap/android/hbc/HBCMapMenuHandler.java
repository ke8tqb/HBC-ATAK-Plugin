package com.atakmap.android.hbc;

import android.content.Context;
import android.graphics.Point;

import com.atakmap.android.maps.MapDataRef;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.menu.MapMenuButtonWidget;
import com.atakmap.android.menu.MapMenuHandler;
import com.atakmap.android.menu.MapMenuWidget;
import com.atakmap.android.widgets.WidgetIcon;
import com.atakmap.android.hbc.plugin.R;
import com.atakmap.coremap.log.Log;

/**
 * HBCMapMenuHandler
 *
 * Adds an HBC transmit button to the ATAK radial menu whenever the user
 * selects any map item (marker). Pressing the button encodes that item's
 * position via the HBC protocol and transmits it as OFDM audio.
 *
 * Registered via MapMenuReceiver.getInstance().registerMapMenuHandler().
 */
public class HBCMapMenuHandler implements MapMenuHandler {

    private static final String TAG = "HBCMapMenuHandler";

    private final Context       pluginContext;
    private final HBCMapComponent mapComponent;

    public HBCMapMenuHandler(Context pluginContext, HBCMapComponent mapComponent) {
        this.pluginContext = pluginContext;
        this.mapComponent  = mapComponent;
    }

    @Override
    public void updateMenu(MapItem item, MapMenuWidget menu) {
        // Only add button for point items (markers) that have a position
        if (!(item instanceof PointMapItem)) return;

        // Skip our own self-marker — no need to re-transmit own position from here
        // (use the "Send My Position" button in the settings pane for that)
        MapView mv = MapView.getMapView();
        if (mv != null && mv.getSelfMarker() != null
                && item.getUID().equals(mv.getSelfMarker().getUID())) {
            return;
        }

        try {
            // ── Create the HBC transmit button ─────────────────────────────
            MapMenuButtonWidget btn = new MapMenuButtonWidget(pluginContext);

            // Set the radio transmitter icon
            String iconUri = "android.resource://"
                    + pluginContext.getPackageName()
                    + "/" + R.drawable.ic_radial_hbc;
            MapDataRef ref = MapDataRef.parseUri(iconUri);
            // 40×40 looks crisp inside ATAK's radial button circles (~80dp radius)
            WidgetIcon icon = new WidgetIcon(ref, new Point(0, 0), 40, 40);
            btn.setIcon(icon);
            btn.setText("HBC TX");

            // ── Click: transmit the item via HBC audio ──────────────────────
            final MapItem targetItem = item;
            btn.setOnButtonClickHandler(
                new gov.tak.api.widgets.IMapMenuButtonWidget.OnButtonClickHandler() {
                    @Override
                    public boolean isSupported(Object mapItem) {
                        return true; // show button for all supported items
                    }
                    @Override
                    public void performAction(Object mapItem) {
                        mapComponent.transmitMapItem(targetItem);
                    }
                });

            // ── Add our button to the existing radial menu ──────────────────
            menu.addWidget(btn);
            Log.d(TAG, "HBC TX button added for: " + item.getMetaString("callsign", item.getUID()));

        } catch (Exception e) {
            Log.e(TAG, "updateMenu failed: " + e.getMessage());
        }
    }
}
