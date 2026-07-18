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
        CommsMapComponent.getInstance().registerPreSendProcessor(this);
        if (prefs.getBoolean(PREF_RX_ENABLED, false))
            HBCAudioMonitor.getInstance().start();
        Log.d(TAG, "started");
    }

    public void stop() {
        HBCAudioMonitor.getInstance().stop();
        // No unregisterPreSendProcessor in ATAK 5.7 API; use txEnabled flag to suppress TX
        prefs.edit().putBoolean(PREF_TX_ENABLED, false).apply();
        Log.d(TAG, "stopped");
    }

    // ─── PreSendProcessor (TX path) ──────────────────────────────────────────

    @Override
    public void processCotEvent(CotEvent event, String[] extras) {
        // extras contains destination UIDs (null/empty = broadcast); we always broadcast
        if (!prefs.getBoolean(PREF_TX_ENABLED, false)) return;
        if (event == null || !event.isValid())          return;
        new Thread(() -> transmitCoT(event), "HBC-TX-prep").start();
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

            // Self-echo suppression: if we hear our own transmission, discard it.
            // HBC PLI UIDs follow the pattern "HBC-{CALLSIGN}" (or "HBC-{CALLSIGN}-911").
            String myCallsign = prefs.getString(PREF_CALLSIGN, "").toUpperCase().trim();
            if (!myCallsign.isEmpty()) {
                String uid = event.getUID();
                if (uid != null && uid.startsWith("HBC-" + myCallsign)) {
                    Log.d(TAG, "Self-echo suppressed: " + uid);
                    return;
                }
            }

            CotMapComponent.getInternalDispatcher().dispatch(event);
            Log.i(TAG, "RX injected: " + event.getUID());
        } catch (Exception e) {
            Log.e(TAG, "injectCoT: " + e.getMessage());
        }
    }

    /**
     * Immediately transmit the user's current position from the ATAK self-marker.
     * Called when the user presses the "Send My Position Now" button.
     */
    public void sendManualPLI() {
        new Thread(() -> {
            try {
                com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
                if (mv == null) { Log.w(TAG, "MapView not available"); return; }

                com.atakmap.android.maps.Marker self = mv.getSelfMarker();
                if (self == null) { Log.w(TAG, "No self marker — no GPS fix?"); return; }

                com.atakmap.coremap.maps.coords.GeoPoint gp = self.getPoint();
                if (gp == null) { Log.w(TAG, "Self marker has no GeoPoint"); return; }

                String uid      = self.getUID();
                String type     = self.getType();
                String callsign = prefs.getString(PREF_CALLSIGN, uid);

                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                String now   = fmt.format(new java.util.Date());
                String stale = fmt.format(new java.util.Date(
                    System.currentTimeMillis() + 5 * 60_000L));

                double hae = gp.isAltitudeValid() ? gp.getAltitude() : 9999999;

                String xml = "<event version=\"2.0\" uid=\"" + uid
                    + "\" type=\"" + type
                    + "\" time=\"" + now
                    + "\" start=\"" + now
                    + "\" stale=\"" + stale
                    + "\" how=\"m-g\" access=\"Undefined\">"
                    + "<point lat=\"" + gp.getLatitude()
                    + "\" lon=\"" + gp.getLongitude()
                    + "\" hae=\"" + hae
                    + "\" ce=\"9999999\" le=\"9999999\" />"
                    + "<detail>"
                    + "<contact callsign=\"" + callsign + "\" />"
                    + "<uid Droid=\"" + callsign + "\" />"
                    + "<track speed=\"0.0\" course=\"9999999.0\" />"
                    + "</detail></event>";

                byte[] hbc = HBCEncoder.encode(xml);
                if (hbc == null) { Log.w(TAG, "Manual PLI: encoder returned null"); return; }

                String myCS = prefs.getString(PREF_CALLSIGN, "NOCALL");
                RadioAudioTransmitter.getInstance()
                    .setPttDelayMs(prefs.getInt(PREF_PTT_DELAY_MS, 0));
                short[] audio = OFDMModem.getInstance()
                    .encodeHBC(hbc, myCS, RadioAudioTransmitter.SAMPLE_RATE);
                if (audio != null) {
                    RadioAudioTransmitter.getInstance().transmit(audio);
                    Log.i(TAG, "Manual PLI transmitted: " + callsign
                        + " @ " + gp.getLatitude() + "," + gp.getLongitude());
                }
            } catch (Exception e) {
                Log.e(TAG, "sendManualPLI: " + e.getMessage());
            }
        }, "HBC-TX-manual").start();
    }

    public void setRxEnabled(boolean on) {
        prefs.edit().putBoolean(PREF_RX_ENABLED, on).apply();
        if (on) HBCAudioMonitor.getInstance().start();
        else    HBCAudioMonitor.getInstance().stop();
    }

    // ─── Settings pane binding ───────────────────────────────────────────────

    public void bindSettingsView(View root) {
        // Manual TX button — sends own position immediately
        android.widget.Button txNowBtn = root.findViewById(R.id.hbc_btn_tx_now);
        if (txNowBtn != null)
            txNowBtn.setOnClickListener(v -> sendManualPLI());

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
