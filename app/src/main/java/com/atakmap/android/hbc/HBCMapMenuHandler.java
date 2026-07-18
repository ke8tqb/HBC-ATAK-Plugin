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
        // Only for point markers with a geographic position
        if (!(item instanceof PointMapItem)) return;

        // Skip own self-marker (use the pane's "Send My Position" button for that)
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        if (mv.getSelfMarker() != null
                && item.getUID().equals(mv.getSelfMarker().getUID())) return;

        try {
            // ATAK context required for widget display metrics and resources
            Context ctx = mv.getContext();

            float span  = menu.getButtonSpan();
            float width = menu.getButtonWidth();

            WidgetIcon radioIcon = buildRadioIcon();

            // ── SECOND LEVEL: the actual transmit action button ─────────────────
            MapMenuButtonWidget txBtn = new MapMenuButtonWidget(ctx);
            txBtn.setButtonSize(span, width);
            if (radioIcon != null) txBtn.setIcon(radioIcon);
            txBtn.setText("Send HBC");

            final MapItem target = item;
            txBtn.setOnButtonClickHandler(
                new gov.tak.api.widgets.IMapMenuButtonWidget.OnButtonClickHandler() {
                    @Override public boolean isSupported(Object o) { return true; }
                    @Override public void performAction(Object o) {
                        mapComponent.transmitMapItem(target);
                    }
                });

            // ── SECOND-LEVEL MENU: same pattern as ATAK's SEND submenu ─────────
            MapMenuWidget subMenu = new MapMenuWidget();
            subMenu.setCoveredAngle(span);   // one button → its full angle
            subMenu.setButtonWidth(width);
            subMenu.addChildWidget(txBtn);

            // ── FIRST LEVEL: radio icon button that opens the submenu ─────────
            MapMenuButtonWidget radioBtn = new MapMenuButtonWidget(ctx);
            radioBtn.setButtonSize(span, width);
            if (radioIcon != null) radioBtn.setIcon(radioIcon);
            radioBtn.setText("HBC");
            radioBtn.setSubmenu(subMenu);    // press → second level opens

            menu.addChildWidget(radioBtn);

            Log.d(TAG, "HBC first→second level button added for: "
                    + item.getMetaString("callsign", item.getUID())
                    + " (span=" + span + ", w=" + width + ")");

        } catch (Exception e) {
            Log.e(TAG, "updateMenu failed: " + e.getMessage());
        }
    }

    private WidgetIcon buildRadioIcon() {
        try {
            String uri = "android.resource://"
                    + pluginContext.getPackageName()
                    + "/" + R.drawable.ic_radial_hbc;
            return new WidgetIcon(MapDataRef.parseUri(uri), new Point(0, 0), 40, 40);
        } catch (Exception e) {
            Log.w(TAG, "Could not load radio icon: " + e.getMessage());
            return null;
        }
    }
}
