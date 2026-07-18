package com.atakmap.android.hbc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import com.atakmap.android.hbc.audio.HBCAudioMonitor;
import com.atakmap.android.hbc.audio.OFDMModem;
import com.atakmap.android.hbc.audio.RadioAudioTransmitter;
import com.atakmap.android.hbc.hbc.HBCDecoder;
import com.atakmap.android.hbc.hbc.HBCEncoder;

/**
 * HBCMapComponent
 *
 * The central plugin component. Responsibilities:
 *   TX: intercepts outgoing CoT events via PreSendProcessor,
 *       encodes them to HBC binary, then to OFDM audio, and transmits.
 *   RX: starts HBCAudioMonitor, receives decoded CoT strings,
 *       and injects them into ATAK's internal CoT dispatcher.
 */
public class HBCMapComponent extends DropDownMapComponent
        implements CommsMapComponent.PreSendProcessor,
                   HBCAudioMonitor.CoTListener {

    private static final String TAG = "HBCMapComponent";

    public static final String PREF_TX_ENABLED   = "hbc_tx_enabled";
    public static final String PREF_RX_ENABLED   = "hbc_rx_enabled";
    public static final String PREF_CALLSIGN     = "hbc_callsign";
    public static final String PREF_PTT_DELAY_MS = "hbc_ptt_delay_ms";
    public static final String PREF_OUT_DEVICE   = "hbc_output_device_id";
    public static final String PREF_IN_DEVICE    = "hbc_input_device_id";

    private static HBCMapComponent instance;
    public static HBCMapComponent getInstance() { return instance; }

    private Context        pluginContext;
    private SharedPreferences prefs;
    private HBCDropDownReceiver dropDown;
    private HBCWidget      widget;
    private HBCReceiver    receiver;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Context ctx, Intent intent, MapView view) {
        instance = this;
        pluginContext = ctx;
        prefs = PreferenceManager.getDefaultSharedPreferences(view.getContext());

        // Register as pre-send processor (TX path)
        CommsMapComponent.getInstance().addPreSendProcessor(this);

        // Register broadcast receiver
        receiver = new HBCReceiver(view, ctx);
        AtakBroadcast.DocumentedIntentFilter filter =
            new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(HBCReceiver.SHOW_PLUGIN);
        filter.addAction(HBCReceiver.REFRESH_DEVICES);
        AtakBroadcast.getInstance().registerReceiver(receiver, filter);

        // Status widget
        widget = new HBCWidget(view, ctx);

        // Drop-down receiver (settings UI)
        dropDown = new HBCDropDownReceiver(view, ctx);

        // Start RX monitor if enabled
        HBCAudioMonitor.getInstance().setCoTListener(this);
        if (prefs.getBoolean(PREF_RX_ENABLED, false))
            HBCAudioMonitor.getInstance().start();

        Log.d(TAG, "HBCMapComponent created");
    }

    // ─── PreSendProcessor (TX path) ──────────────────────────────────────────

    @Override
    public boolean processCotEvent(CotEvent event, Bundle bundle) {
        // Return true to let ATAK continue normal processing after our TX
        if (!prefs.getBoolean(PREF_TX_ENABLED, false)) return true;
        if (event == null || !event.isValid())          return true;

        // Run encoding + TX on a background thread to avoid blocking the UI
        new Thread(() -> transmitCoT(event), "HBC-TX-prep").start();
        return true;
    }

    private void transmitCoT(CotEvent event) {
        try {
            String xml      = event.toString();
            String callsign = prefs.getString(PREF_CALLSIGN, "NOCALL");
            int    pttDelay = prefs.getInt(PREF_PTT_DELAY_MS, 0);

            byte[] hbcBytes = HBCEncoder.encode(xml);
            if (hbcBytes == null) {
                Log.w(TAG, "HBCEncoder returned null for CoT type: " + event.getType());
                return;
            }

            RadioAudioTransmitter.getInstance().setPttDelayMs(pttDelay);
            short[] audio = OFDMModem.getInstance()
                .encodeHBC(hbcBytes, callsign, RadioAudioTransmitter.SAMPLE_RATE);

            if (audio == null) {
                Log.e(TAG, "OFDMModem.encodeHBC returned null");
                return;
            }

            RadioAudioTransmitter.getInstance().transmit(audio);
            Log.i(TAG, "TX: " + event.getUID() + " (" + hbcBytes.length + " bytes HBC)");

        } catch (Exception e) {
            Log.e(TAG, "transmitCoT() error: " + e.getMessage());
        }
    }

    // ─── HBCAudioMonitor.CoTListener (RX path) ───────────────────────────────

    @Override
    public void onCoTReceived(String cotXml) {
        mainHandler.post(() -> injectCoT(cotXml));
    }

    private void injectCoT(String cotXml) {
        try {
            CotEvent event = CotEvent.parse(cotXml);
            if (event == null || !event.isValid()) {
                Log.w(TAG, "Received invalid CoT from HBC decoder");
                return;
            }
            // Inject into ATAK's internal CoT pipeline — appears on the map
            CotMapComponent.getInstance()
                .getInternalDispatcher()
                .dispatchEvent(event, null);
            Log.i(TAG, "RX CoT injected: " + event.getUID());
        } catch (Exception e) {
            Log.e(TAG, "injectCoT() error: " + e.getMessage());
        }
    }

    // ─── RX toggle (called from UI) ──────────────────────────────────────────

    public void setRxEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_RX_ENABLED, enabled).apply();
        if (enabled) HBCAudioMonitor.getInstance().start();
        else         HBCAudioMonitor.getInstance().stop();
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    @Override
    protected void onDestroyImpl(Context ctx, MapView view) {
        HBCAudioMonitor.getInstance().stop();
        CommsMapComponent.getInstance().removePreSendProcessor(this);
        AtakBroadcast.getInstance().unregisterReceiver(receiver);
        if (widget  != null) widget.destroy();
        if (dropDown != null) dropDown.dispose();
        instance = null;
    }
}
