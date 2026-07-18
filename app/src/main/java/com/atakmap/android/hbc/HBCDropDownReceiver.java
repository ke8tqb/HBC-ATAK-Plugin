package com.atakmap.android.hbc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.hbc.audio.HBCAudioMonitor;
import com.atakmap.android.hbc.audio.RadioAudioTransmitter;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * HBCDropDownReceiver
 *
 * Manages the plugin's settings/status panel (shown via Tools menu or SHOW_DROPDOWN intent).
 *
 * Controls:
 *   - TX Enable toggle
 *   - RX Enable toggle
 *   - Callsign input (embedded in OFDM modem header for FCC ID)
 *   - Audio Output device spinner
 *   - Audio Input device spinner
 *   - PTT Delay (ms) input
 *   - Status log (last TX/RX event)
 */
public class HBCDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener {

    private static final String TAG = "HBCDropDown";

    public static final String SHOW_DROPDOWN   = "com.atakmap.android.hbc.SHOW_DROPDOWN";
    public static final String REFRESH_DEVICES = "com.atakmap.android.hbc.REFRESH_DEVICES_UI";

    private final Context         pluginCtx;
    private final SharedPreferences prefs;
    private View                  rootView;

    // Audio device lists
    private AudioDeviceInfo[] outputDevices;
    private AudioDeviceInfo[] inputDevices;

    public HBCDropDownReceiver(MapView view, Context ctx) {
        super(view);
        this.pluginCtx = ctx;
        this.prefs     = PreferenceManager.getDefaultSharedPreferences(view.getContext());

        // Register to receive show/refresh intents
        AtakBroadcast.DocumentedIntentFilter filter =
            new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(SHOW_DROPDOWN);
        filter.addAction(REFRESH_DEVICES);
        AtakBroadcast.getInstance().registerReceiver(this, filter);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (SHOW_DROPDOWN.equals(intent.getAction())) {
            showDropDown(getDropDownView(),
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT, false, this);
        } else if (REFRESH_DEVICES.equals(intent.getAction())) {
            populateDeviceSpinners();
        }
    }

    // ─── Drop-down view ──────────────────────────────────────────────────────

    private View getDropDownView() {
        if (rootView == null) {
            rootView = android.view.LayoutInflater.from(pluginCtx)
                .inflate(com.atakmap.android.hbc.plugin.R.layout.dropdown, null);
            bindViews();
        }
        return rootView;
    }

    private void bindViews() {
        // ── TX toggle ────────────────────────────────────────────────────────
        Switch txSwitch = rootView.findViewById(R.id.hbc_switch_tx);
        txSwitch.setChecked(prefs.getBoolean(HBCMapComponent.PREF_TX_ENABLED, false));
        txSwitch.setOnCheckedChangeListener((btn, on) ->
            prefs.edit().putBoolean(HBCMapComponent.PREF_TX_ENABLED, on).apply());

        // ── RX toggle ────────────────────────────────────────────────────────
        Switch rxSwitch = rootView.findViewById(R.id.hbc_switch_rx);
        rxSwitch.setChecked(prefs.getBoolean(HBCMapComponent.PREF_RX_ENABLED, false));
        rxSwitch.setOnCheckedChangeListener((btn, on) -> {
            prefs.edit().putBoolean(HBCMapComponent.PREF_RX_ENABLED, on).apply();
            if (HBCMapComponent.getInstance() != null)
                HBCMapComponent.getInstance().setRxEnabled(on);
        });

        // ── Callsign ─────────────────────────────────────────────────────────
        EditText callsignEdit = rootView.findViewById(R.id.hbc_edit_callsign);
        callsignEdit.setText(prefs.getString(HBCMapComponent.PREF_CALLSIGN, ""));
        callsignEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus)
                prefs.edit()
                    .putString(HBCMapComponent.PREF_CALLSIGN,
                               callsignEdit.getText().toString().toUpperCase().trim())
                    .apply();
        });

        // ── PTT delay ────────────────────────────────────────────────────────
        EditText pttEdit = rootView.findViewById(R.id.hbc_edit_ptt_delay);
        pttEdit.setText(String.valueOf(prefs.getInt(HBCMapComponent.PREF_PTT_DELAY_MS, 0)));
        pttEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                try {
                    int ms = Integer.parseInt(pttEdit.getText().toString());
                    prefs.edit().putInt(HBCMapComponent.PREF_PTT_DELAY_MS, ms).apply();
                    RadioAudioTransmitter.getInstance().setPttDelayMs(ms);
                } catch (NumberFormatException ignored) {}
            }
        });

        // ── Audio device spinners ─────────────────────────────────────────────
        populateDeviceSpinners();
    }

    private void populateDeviceSpinners() {
        if (rootView == null) return;
        AudioManager am = (AudioManager) pluginCtx.getSystemService(Context.AUDIO_SERVICE);

        outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        inputDevices  = am.getDevices(AudioManager.GET_DEVICES_INPUTS);

        // Output spinner
        Spinner outSpinner = rootView.findViewById(R.id.hbc_spinner_output);
        List<String> outNames = new ArrayList<>();
        outNames.add("System Default");
        for (AudioDeviceInfo d : outputDevices) outNames.add(deviceName(d));
        ArrayAdapter<String> outAdapter = new ArrayAdapter<>(pluginCtx,
            android.R.layout.simple_spinner_item, outNames);
        outAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        outSpinner.setAdapter(outAdapter);

        int savedOutId = prefs.getInt(HBCMapComponent.PREF_OUT_DEVICE, -1);
        for (int i = 0; i < outputDevices.length; i++) {
            if (outputDevices[i].getId() == savedOutId) { outSpinner.setSelection(i + 1); break; }
        }
        outSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                AudioDeviceInfo dev = (pos == 0) ? null : outputDevices[pos - 1];
                RadioAudioTransmitter.getInstance().setPreferredOutputDevice(dev);
                prefs.edit().putInt(HBCMapComponent.PREF_OUT_DEVICE,
                    dev != null ? dev.getId() : -1).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // Input spinner
        Spinner inSpinner = rootView.findViewById(R.id.hbc_spinner_input);
        List<String> inNames = new ArrayList<>();
        inNames.add("System Default");
        for (AudioDeviceInfo d : inputDevices) inNames.add(deviceName(d));
        ArrayAdapter<String> inAdapter = new ArrayAdapter<>(pluginCtx,
            android.R.layout.simple_spinner_item, inNames);
        inAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        inSpinner.setAdapter(inAdapter);

        int savedInId = prefs.getInt(HBCMapComponent.PREF_IN_DEVICE, -1);
        for (int i = 0; i < inputDevices.length; i++) {
            if (inputDevices[i].getId() == savedInId) { inSpinner.setSelection(i + 1); break; }
        }
        inSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                AudioDeviceInfo dev = (pos == 0) ? null : inputDevices[pos - 1];
                HBCAudioMonitor.getInstance().setPreferredInputDevice(dev);
                prefs.edit().putInt(HBCMapComponent.PREF_IN_DEVICE,
                    dev != null ? dev.getId() : -1).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    // ─── DropDown.OnStateListener ────────────────────────────────────────────

    @Override public void onDropDownVisible(boolean v)     {}
    @Override public void onDropDownSizeChanged(double w, double h) {}
    @Override public void onDropDownClose()                {}

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String deviceName(AudioDeviceInfo d) {
        CharSequence name = d.getProductName();
        String type = typeString(d.getType());
        return (name != null && name.length() > 0)
            ? name + " (" + type + ")"
            : type + " [id=" + d.getId() + "]";
    }

    private static String typeString(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:      return "Built-in Mic";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:  return "Speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:    return "Wired Headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired Headphones";
            case AudioDeviceInfo.TYPE_USB_DEVICE:       return "USB";
            case AudioDeviceInfo.TYPE_USB_HEADSET:      return "USB Headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:    return "Bluetooth SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:   return "Bluetooth A2DP";
            default: return "Device " + type;
        }
    }

    @Override
    public void disposeImpl() {
        AtakBroadcast.getInstance().unregisterReceiver(this);
    }
}
