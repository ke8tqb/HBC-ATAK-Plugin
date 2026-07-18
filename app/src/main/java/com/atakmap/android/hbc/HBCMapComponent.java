package com.atakmap.android.hbc;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import com.atakmap.android.hbc.audio.HBCAudioMonitor;
import com.atakmap.android.hbc.audio.OFDMModem;
import com.atakmap.android.hbc.audio.RadioAudioTransmitter;
import com.atakmap.android.hbc.hbc.HBCDecoder;
import com.atakmap.android.hbc.hbc.HBCEncoder;
import com.atakmap.android.hbc.plugin.R;

import java.util.ArrayList;
import java.util.List;

/**
 * HBCMapComponent
 *
 * Plain controller class for the HBC plugin (no ATAK base class required).
 * Managed entirely by HBCPlugin (IPlugin entry point).
 *
 *   TX: registers as PreSendProcessor to intercept outgoing CoT
 *   RX: starts HBCAudioMonitor, injects decoded CoT into ATAK's map
 *   UI: bindSettingsView() wires the Pane settings layout
 */
public class HBCMapComponent
        implements CommsMapComponent.PreSendProcessor,
                   HBCAudioMonitor.CoTListener {

    private static final String TAG = "HBCMapComponent";

    public static final String PREF_TX_ENABLED   = "hbc_tx_enabled";
    public static final String PREF_RX_ENABLED   = "hbc_rx_enabled";
    public static final String PREF_CALLSIGN     = "hbc_callsign";
    public static final String PREF_PTT_DELAY_MS = "hbc_ptt_delay_ms";
    public static final String PREF_OUT_DEVICE   = "hbc_output_device_id";
    public static final String PREF_IN_DEVICE    = "hbc_input_device_id";

    private final Context           pluginContext;
    private final SharedPreferences prefs;
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());

    private AudioDeviceInfo[] outputDevices = new AudioDeviceInfo[0];
    private AudioDeviceInfo[] inputDevices  = new AudioDeviceInfo[0];

    public HBCMapComponent(Context pluginContext) {
        this.pluginContext = pluginContext;
        // Use ATAK's MapView context for prefs so they survive plugin restarts
        this.prefs = PreferenceManager.getDefaultSharedPreferences(
            MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : pluginContext);
        HBCAudioMonitor.getInstance().setCoTListener(this);
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    public void start() {
        CommsMapComponent.getInstance().addPreSendProcessor(this);
        if (prefs.getBoolean(PREF_RX_ENABLED, false))
            HBCAudioMonitor.getInstance().start();
        Log.d(TAG, "started");
    }

    public void stop() {
        HBCAudioMonitor.getInstance().stop();
        CommsMapComponent.getInstance().removePreSendProcessor(this);
        Log.d(TAG, "stopped");
    }

    // ─── PreSendProcessor (TX path) ──────────────────────────────────────────

    @Override
    public boolean processCotEvent(CotEvent event, android.os.Bundle bundle) {
        if (!prefs.getBoolean(PREF_TX_ENABLED, false)) return true;
        if (event == null || !event.isValid())          return true;
        new Thread(() -> transmitCoT(event), "HBC-TX-prep").start();
        return true;
    }

    private void transmitCoT(CotEvent event) {
        try {
            String callsign = prefs.getString(PREF_CALLSIGN, "NOCALL");
            int    pttDelay = prefs.getInt(PREF_PTT_DELAY_MS, 0);

            byte[] hbcBytes = HBCEncoder.encode(event.toString());
            if (hbcBytes == null) { Log.w(TAG, "Encoder null for " + event.getType()); return; }

            RadioAudioTransmitter.getInstance().setPttDelayMs(pttDelay);
            short[] audio = OFDMModem.getInstance()
                .encodeHBC(hbcBytes, callsign, RadioAudioTransmitter.SAMPLE_RATE);
            if (audio == null) { Log.e(TAG, "OFDMModem encode failed"); return; }

            RadioAudioTransmitter.getInstance().transmit(audio);
            Log.i(TAG, "TX: " + event.getUID() + " (" + hbcBytes.length + "B HBC)");
        } catch (Exception e) {
            Log.e(TAG, "transmitCoT: " + e.getMessage());
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
            if (event == null || !event.isValid()) { Log.w(TAG, "Invalid RX CoT"); return; }
            CotMapComponent.getInstance().getInternalDispatcher().dispatchEvent(event, null);
            Log.i(TAG, "RX injected: " + event.getUID());
        } catch (Exception e) {
            Log.e(TAG, "injectCoT: " + e.getMessage());
        }
    }

    public void setRxEnabled(boolean on) {
        prefs.edit().putBoolean(PREF_RX_ENABLED, on).apply();
        if (on) HBCAudioMonitor.getInstance().start();
        else    HBCAudioMonitor.getInstance().stop();
    }

    // ─── Settings pane binding ───────────────────────────────────────────────

    public void bindSettingsView(View root) {
        Switch txSwitch = root.findViewById(R.id.hbc_switch_tx);
        if (txSwitch != null) {
            txSwitch.setChecked(prefs.getBoolean(PREF_TX_ENABLED, false));
            txSwitch.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean(PREF_TX_ENABLED, on).apply());
        }
        Switch rxSwitch = root.findViewById(R.id.hbc_switch_rx);
        if (rxSwitch != null) {
            rxSwitch.setChecked(prefs.getBoolean(PREF_RX_ENABLED, false));
            rxSwitch.setOnCheckedChangeListener((b, on) -> setRxEnabled(on));
        }
        EditText csEdit = root.findViewById(R.id.hbc_edit_callsign);
        if (csEdit != null) {
            csEdit.setText(prefs.getString(PREF_CALLSIGN, ""));
            csEdit.setOnFocusChangeListener((v, f) -> {
                if (!f) prefs.edit()
                    .putString(PREF_CALLSIGN, csEdit.getText().toString().toUpperCase().trim())
                    .apply();
            });
        }
        EditText pttEdit = root.findViewById(R.id.hbc_edit_ptt_delay);
        if (pttEdit != null) {
            pttEdit.setText(String.valueOf(prefs.getInt(PREF_PTT_DELAY_MS, 0)));
            pttEdit.setOnFocusChangeListener((v, f) -> {
                if (!f) try {
                    int ms = Integer.parseInt(pttEdit.getText().toString());
                    prefs.edit().putInt(PREF_PTT_DELAY_MS, ms).apply();
                    RadioAudioTransmitter.getInstance().setPttDelayMs(ms);
                } catch (NumberFormatException ignored) {}
            });
        }
        populateDeviceSpinners(root);
    }

    private void populateDeviceSpinners(View root) {
        AudioManager am = (AudioManager) pluginContext.getSystemService(Context.AUDIO_SERVICE);
        outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        inputDevices  = am.getDevices(AudioManager.GET_DEVICES_INPUTS);

        Spinner out = root.findViewById(R.id.hbc_spinner_output);
        if (out != null) {
            List<String> names = new ArrayList<>();
            names.add("System Default");
            for (AudioDeviceInfo d : outputDevices) names.add(deviceLabel(d));
            out.setAdapter(new ArrayAdapter<>(pluginContext, android.R.layout.simple_spinner_item, names));
            int saved = prefs.getInt(PREF_OUT_DEVICE, -1);
            for (int i = 0; i < outputDevices.length; i++)
                if (outputDevices[i].getId() == saved) { out.setSelection(i + 1); break; }
            out.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    AudioDeviceInfo d = pos == 0 ? null : outputDevices[pos - 1];
                    RadioAudioTransmitter.getInstance().setPreferredOutputDevice(d);
                    prefs.edit().putInt(PREF_OUT_DEVICE, d != null ? d.getId() : -1).apply();
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        }

        Spinner in = root.findViewById(R.id.hbc_spinner_input);
        if (in != null) {
            List<String> names = new ArrayList<>();
            names.add("System Default");
            for (AudioDeviceInfo d : inputDevices) names.add(deviceLabel(d));
            in.setAdapter(new ArrayAdapter<>(pluginContext, android.R.layout.simple_spinner_item, names));
            int saved = prefs.getInt(PREF_IN_DEVICE, -1);
            for (int i = 0; i < inputDevices.length; i++)
                if (inputDevices[i].getId() == saved) { in.setSelection(i + 1); break; }
            in.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    AudioDeviceInfo d = pos == 0 ? null : inputDevices[pos - 1];
                    HBCAudioMonitor.getInstance().setPreferredInputDevice(d);
                    prefs.edit().putInt(PREF_IN_DEVICE, d != null ? d.getId() : -1).apply();
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        }
    }

    private static String deviceLabel(AudioDeviceInfo d) {
        CharSequence name = d.getProductName();
        return (name != null && name.length() > 0) ? name.toString() : "Device " + d.getId();
    }
}
