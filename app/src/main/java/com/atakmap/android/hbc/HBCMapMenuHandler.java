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
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.WidgetBackground;
import com.atakmap.android.widgets.WidgetIcon;
import com.atakmap.android.hbc.plugin.R;
import com.atakmap.coremap.log.Log;

import gov.tak.api.widgets.IWidgetBackground;

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

            // Grab the button background from an existing menu button so our
            // buttons render with the same dark-arc appearance as ATAK's own
            // radial buttons instead of as a plain black rectangle.
            WidgetBackground bg = extractMenuBackground(menu);

            WidgetIcon radioIcon = buildRadioIcon();

            // ── SECOND LEVEL: the actual transmit action button ─────────────────
            MapMenuButtonWidget txBtn = new MapMenuButtonWidget(ctx);
            txBtn.setButtonSize(span, width);
            if (bg != null) txBtn.setBackground(bg.copy());
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
            if (bg != null) radioBtn.setBackground(bg.copy());
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

    /**
     * Reads the WidgetBackground from the first existing MapMenuButtonWidget in the
     * menu so our button uses the same dark-arc style as all the other radial buttons.
     * Returns null if no background can be found (button will render plain).
     */
    private static WidgetBackground extractMenuBackground(MapMenuWidget menu) {
        try {
            for (MapWidget child : menu.getChildWidgets()) {
                if (child instanceof MapMenuButtonWidget) {
                    WidgetBackground bg = ((MapMenuButtonWidget) child).getBackground();
                    if (bg != null) return bg;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "extractMenuBackground: " + e.getMessage());
        }
        return null;
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
