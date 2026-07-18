package com.atakmap.android.hbc.plugin;

import android.content.Context;

import com.atakmap.android.hbc.HBCMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import transapps.maps.plugin.life.DeploymentLifeCycle;

/**
 * HBCLifecycle
 *
 * ATAK plugin entry point. Declared in assets/plugin.xml.
 * ATAK instantiates this class and calls onCreate / onDestroy.
 */
public class HBCLifecycle implements DeploymentLifeCycle {

    private static final String TAG = "HBCLifecycle";

    private HBCMapComponent component;
    private Context         ctx;

    @Override
    public void onCreate(Context ctx, transapps.maps.plugin.life.DeploymentLifeCycle.LifeCycleListener listener) {
        this.ctx = ctx;
        Log.d(TAG, "HBC ATAK Plugin starting");
    }

    @Override
    public void onStart() {}

    @Override
    public void onPause() {}

    @Override
    public void onResume() {}

    @Override
    public void onStop() {}

    @Override
    public void onFinish() {
        if (component != null) {
            component.onDestroyImpl(ctx, MapView.getMapView());
            component = null;
        }
    }

    @Override
    public void onConfigurationChanged() {}
}
