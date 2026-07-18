package com.atakmap.android.hbc;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.maps.MapDataRef;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.menu.MapMenuButtonWidget;
import com.atakmap.android.menu.MapMenuEventListener;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.menu.MapMenuWidget;
import com.atakmap.android.menu.MenuLayoutWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.WidgetBackground;
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
 * Uses MapMenuEventListener so the button is added AFTER the menu renders,
 * not during creation (MapMenuHandler fires too early and buttons get discarded).
 */
public class HBCMapMenuHandler implements MapMenuEventListener {

    private static final String TAG = "HBCMapMenuHandler";

    private final Context       pluginContext;
    private final HBCMapComponent mapComponent;

    public HBCMapMenuHandler(Context pluginContext, HBCMapComponent mapComponent) {
        this.pluginContext = pluginContext;
        this.mapComponent  = mapComponent;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─── MapMenuEventListener ────────────────────────────────────────────────

    /**
     * Fires when a radial menu OPENS. We post our button addition to the NEXT
     * UI frame so the menu is fully laid out before we modify it.
     */
    @Override
    public boolean onShowMenu(final MapItem item) {
        if (!(item instanceof PointMapItem)) return false;

        MapView mv = MapView.getMapView();
        if (mv == null) return false;
        if (mv.getSelfMarker() != null
                && item.getUID().equals(mv.getSelfMarker().getUID())) return false;

        mainHandler.post(() -> {
            try {
                MenuLayoutWidget layout = MapMenuReceiver.getMenuWidget();
                if (layout == null) { Log.w(TAG, "getMenuWidget() returned null"); return; }

                // The displayed MapMenuWidget ring is a child of the layout
                MapMenuWidget ring = null;
                for (MapWidget child : layout.getChildWidgets()) {
                    if (child instanceof MapMenuWidget) {
                        ring = (MapMenuWidget) child;
                        break;
                    }
                }
                // Fallback: treat layout itself as the ring if no child ring found
                if (ring == null) {
                    Log.w(TAG, "No MapMenuWidget child found, skipping");
                    return;
                }

                addTxButton(ring, item);

            } catch (Exception e) {
                Log.e(TAG, "onShowMenu post: " + e.getMessage());
            }
        });

        return false; // false = let ATAK show the normal menu too
    }

    @Override
    public void onHideMenu(MapItem item) { /* nothing to clean up */ }

    // ─── Button creation ─────────────────────────────────────────────────────

    private void addTxButton(MapMenuWidget ring, MapItem item) {
        Context ctx = MapView.getMapView().getContext();
        float span  = ring.getButtonSpan();
        float width = ring.getButtonWidth();

        // Copy the background from the first existing button so ours
        // renders as a matching dark-arc slice, not a plain rectangle.
        WidgetBackground bg = extractMenuBackground(ring);

        MapMenuButtonWidget txBtn = new MapMenuButtonWidget(ctx);
        txBtn.setButtonSize(span, width);
        if (bg != null) txBtn.setBackground(bg.copy());

        WidgetIcon icon = buildRadioIcon();
        if (icon != null) txBtn.setIcon(icon);
        txBtn.setText("TX");   // user-requested short label

        final MapItem target = item;
        txBtn.setOnButtonClickHandler(
            new gov.tak.api.widgets.IMapMenuButtonWidget.OnButtonClickHandler() {
                @Override public boolean isSupported(Object o) { return true; }
                @Override public void performAction(Object o) {
                    mapComponent.transmitMapItem(target);
                }
            });

        ring.addChildWidget(txBtn);
        Log.i(TAG, "TX button added to live ring for: "
                + item.getMetaString("callsign", item.getUID()));
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
