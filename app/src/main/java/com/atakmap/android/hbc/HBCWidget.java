package com.atakmap.android.hbc;

import android.content.Context;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.AbstractWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.MarkerIconWidget;
import com.atakmap.coremap.log.Log;

/**
 * HBCWidget
 *
 * A small map-corner icon indicating HBC plugin status:
 *   green  = RX active (listening)
 *   yellow = TX in progress
 *   grey   = plugin loaded but TX/RX both off
 *
 * The icon is placed in the upper-right corner of the ATAK map.
 */
public class HBCWidget {

    private static final String TAG = "HBCWidget";

    private final MapView view;
    private MarkerIconWidget widget;

    public HBCWidget(MapView view, Context ctx) {
        this.view = view;
        try {
            widget = new MarkerIconWidget();
            widget.setName("HBC Radio");
            // Icons are set from res/drawable — adjust icon names as needed
            setIcon("grey");
            view.getMapOverlayManager().addWidgetToCorner(widget,
                com.atakmap.android.overlay.MapOverlayManager.Corner.UpperRight);
        } catch (Exception e) {
            Log.w(TAG, "Widget init failed (normal in unit tests): " + e.getMessage());
        }
    }

    /** Set icon colour: "green", "yellow", or "grey". */
    public void setIcon(String color) {
        if (widget == null) return;
        try {
            int iconResId;
            switch (color) {
                case "green":  iconResId = com.atakmap.android.hbc.plugin.R.drawable.ic_hbc_green;  break;
                case "yellow": iconResId = com.atakmap.android.hbc.plugin.R.drawable.ic_hbc_yellow; break;
                default:       iconResId = com.atakmap.android.hbc.plugin.R.drawable.ic_launcher;   break;
            }
            widget.setIcon(new com.atakmap.android.icons.Icon.Builder()
                .setImageUri(0, "android.resource://com.atakmap.android.hbc.plugin/" + iconResId)
                .build());
        } catch (Exception e) {
            Log.w(TAG, "setIcon failed: " + e.getMessage());
        }
    }

    public void destroy() {
        if (widget != null) {
            view.getMapOverlayManager().removeWidget(widget);
            widget = null;
        }
    }
}
