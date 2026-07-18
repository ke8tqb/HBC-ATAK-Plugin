package com.atakmap.android.hbc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

/**
 * HBCReceiver
 *
 * BroadcastReceiver that handles plugin-specific intents.
 * SHOW_PLUGIN opens the settings/status dropdown.
 * REFRESH_DEVICES triggers re-enumeration of audio devices.
 */
public class HBCReceiver extends BroadcastReceiver {

    private static final String TAG = "HBCReceiver";

    public static final String SHOW_PLUGIN     = "com.atakmap.android.hbc.SHOW_PLUGIN";
    public static final String REFRESH_DEVICES = "com.atakmap.android.hbc.REFRESH_DEVICES";

    private final MapView view;
    private final Context ctx;

    public HBCReceiver(MapView view, Context ctx) {
        this.view = view;
        this.ctx  = ctx;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case SHOW_PLUGIN:
                Log.d(TAG, "SHOW_PLUGIN received");
                HBCMapComponent comp = HBCMapComponent.getInstance();
                if (comp != null) {
                    // Open the dropdown via AtakBroadcast to HBCDropDownReceiver
                    Intent show = new Intent(HBCDropDownReceiver.SHOW_DROPDOWN);
                    AtakBroadcast.getInstance().sendBroadcast(show);
                }
                break;
            case REFRESH_DEVICES:
                Log.d(TAG, "REFRESH_DEVICES received");
                Intent refresh = new Intent(HBCDropDownReceiver.REFRESH_DEVICES);
                AtakBroadcast.getInstance().sendBroadcast(refresh);
                break;
        }
    }
}
