package com.atakmap.android.hbc;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.maps.coords.GeoPoint;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

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

    // Reference to the settings pane view so the AudioDeviceCallback can refresh spinners
    private View settingsRoot = null;

    // On-screen overlay TX button (shown when a map item is selected)
    private Button                                    txOverlayBtn;
    private MapItem                                   overlayItem;
    private MapEventDispatcher.MapEventDispatchListener itemClickListener;
    private MapEventDispatcher.MapEventDispatchListener mapClickListener;

    // Refreshes spinners when USB audio devices are connected or disconnected
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            mainHandler.post(() -> { if (settingsRoot != null) populateDeviceSpinners(settingsRoot); });
        }
        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            mainHandler.post(() -> { if (settingsRoot != null) populateDeviceSpinners(settingsRoot); });
        }
    };

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
        // Listen for USB audio devices being plugged/unplugged
        AudioManager am = (AudioManager) pluginContext.getSystemService(Context.AUDIO_SERVICE);
        am.registerAudioDeviceCallback(deviceCallback, mainHandler);
        // Show an on-screen TX button whenever a map item is selected
        mainHandler.post(this::setupOverlayButton);
        Log.d(TAG, "started");
    }

    public void stop() {
        HBCAudioMonitor.getInstance().stop();
        AudioManager am = (AudioManager) pluginContext.getSystemService(Context.AUDIO_SERVICE);
        am.unregisterAudioDeviceCallback(deviceCallback);
        removeOverlayButton();
        prefs.edit().putBoolean(PREF_TX_ENABLED, false).apply();
        settingsRoot = null;
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

            // Self-echo suppression: discard any packet whose callsign matches ours.
            //
            // Two authoritative sources for our own callsign:
            //   1. The plugin's PREF_CALLSIGN setting (what the user typed in)
            //   2. The ATAK self-marker's "callsign" meta-string (ATAK's own identity)
            //
            // HBC field limits: header callsign truncated to 8 chars (ITA2),
            // name field truncated to 7 chars. Compare against the same prefix length.
            //
            // If EITHER source produces a match, the packet is our own echo and is dropped.
            if (isOwnCallsign(event)) {
                Log.d(TAG, "Self-echo suppressed: " + event.getUID());
                return;
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

    /**
     * Returns true if the CotEvent appears to have originated from this station.
     *
     * Checks both the UID (which carries the 8-char-truncated header callsign)
     * and the <contact callsign> field (7-char-truncated name field) against:
     *   - The plugin's own PREF_CALLSIGN setting
     *   - The ATAK self-marker's callsign (ATAK's authoritative identity)
     *
     * Any match from either source suppresses the packet.
     */
    private boolean isOwnCallsign(CotEvent event) {
        // Gather candidate "own" callsigns
        java.util.Set<String> ownCallsigns = new java.util.LinkedHashSet<>();

        // Source 1: plugin settings
        String prefsCs = prefs.getString(PREF_CALLSIGN, "").toUpperCase().trim();
        if (!prefsCs.isEmpty()) ownCallsigns.add(prefsCs);

        // Source 2: ATAK self-marker callsign (ATAK's own identity)
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv != null) {
                com.atakmap.android.maps.Marker self = mv.getSelfMarker();
                if (self != null) {
                    String atakCs = self.getMetaString("callsign", "").toUpperCase().trim();
                    if (!atakCs.isEmpty()) ownCallsigns.add(atakCs);
                }
            }
        } catch (Exception ignored) {}

        if (ownCallsigns.isEmpty()) return false;

        String uid = event.getUID() != null ? event.getUID().toUpperCase() : "";

        // Extract the <contact callsign> from the decoded CoT
        String contactCs = "";
        try {
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail != null) {
                com.atakmap.coremap.cot.event.CotDetail contact = detail.getChild("contact");
                if (contact != null) {
                    String raw = contact.getAttribute("callsign");
                    if (raw != null) contactCs = raw.trim().toUpperCase();
                }
            }
        } catch (Exception ignored) {}

        for (String cs : ownCallsigns) {
            // Truncate to HBC field limits for comparison
            String cs8 = cs.length() > 8 ? cs.substring(0, 8) : cs;
            String cs7 = cs.length() > 7 ? cs.substring(0, 7) : cs;

            // UID check: "HBC-{callsign8}" prefix
            if (uid.startsWith("HBC-" + cs8)) return true;

            // Contact callsign check: exact match against 7-char truncation
            if (!contactCs.isEmpty() && contactCs.equals(cs7)) return true;
        }
        return false;
    }

    /**
     * Encodes the selected map item's position as HBC and transmits it via audio.
     * Called from HBCMapMenuHandler when the user presses the radial HBC TX button.
     */
    public void transmitMapItem(MapItem item) {
        if (!(item instanceof PointMapItem)) {
            Log.w(TAG, "transmitMapItem: item has no point position");
            return;
        }
        new Thread(() -> {
            try {
                GeoPoint gp = ((PointMapItem) item).getPoint();
                if (gp == null) { Log.w(TAG, "transmitMapItem: null GeoPoint"); return; }

                String uid      = item.getUID();
                String type     = item.getType();
                String callsign = item.getMetaString("callsign", uid);

                SimpleDateFormat fmt = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                String now   = fmt.format(new Date());
                String stale = fmt.format(new Date(System.currentTimeMillis() + 5 * 60_000L));
                double hae   = gp.isAltitudeValid() ? gp.getAltitude() : 9999999;

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
                if (hbc == null) { Log.w(TAG, "transmitMapItem: encoder returned null"); return; }

                String myCS = prefs.getString(PREF_CALLSIGN, "NOCALL");
                RadioAudioTransmitter.getInstance()
                    .setPttDelayMs(prefs.getInt(PREF_PTT_DELAY_MS, 0));
                short[] audio = OFDMModem.getInstance()
                    .encodeHBC(hbc, myCS, RadioAudioTransmitter.SAMPLE_RATE);
                if (audio != null) {
                    RadioAudioTransmitter.getInstance().transmit(audio);
                    Log.i(TAG, "Radial TX: " + callsign + " @ " + gp.getLatitude() + "," + gp.getLongitude());
                }
            } catch (Exception e) {
                Log.e(TAG, "transmitMapItem: " + e.getMessage());
            }
        }, "HBC-TX-radial").start();
    }

    // ─── On-screen TX overlay button ──────────────────────────────────────────────

    /**
     * Creates a green "TX" button and adds it to ATAK's view hierarchy.
     * The button appears when any non-self map marker is tapped and hides
     * when the map background is tapped. One tap on the button transmits.
     */
    private void setupOverlayButton() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        Context ctx = mv.getContext();

        // ── Create the TX button ─────────────────────────────────────
        txOverlayBtn = new Button(ctx);
        txOverlayBtn.setText("📡  TX");
        txOverlayBtn.setTextSize(18f);
        txOverlayBtn.setTextColor(0xFF000000);
        txOverlayBtn.setBackgroundColor(0xFF33FF66);  // ATAK green
        txOverlayBtn.setPadding(48, 24, 48, 24);
        txOverlayBtn.setVisibility(View.GONE);
        txOverlayBtn.setElevation(12f);               // float above the map
        txOverlayBtn.setOnClickListener(v -> {
            if (overlayItem != null)
                transmitMapItem(overlayItem);
        });

        // Position at bottom-center, above the ATAK nav bar
        android.widget.FrameLayout.LayoutParams lp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity     = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = 180;  // clear the ATAK bottom toolbar

        ViewGroup parent = (ViewGroup) mv.getParent();
        if (parent == null) { Log.w(TAG, "MapView has no parent"); return; }
        parent.addView(txOverlayBtn, lp);

        // ── Show on item tap ─────────────────────────────────────
        itemClickListener = event -> {
            MapItem item = event.getItem();
            if (item instanceof PointMapItem
                    && (mv.getSelfMarker() == null
                        || !item.getUID().equals(mv.getSelfMarker().getUID()))) {
                overlayItem = item;
                String cs = item.getMetaString("callsign", item.getUID());
                mainHandler.post(() -> {
                    txOverlayBtn.setText("📡  TX  " + cs);
                    txOverlayBtn.setVisibility(View.VISIBLE);
                });
            } else {
                // Tapped own marker or non-point item — hide
                overlayItem = null;
                mainHandler.post(() -> txOverlayBtn.setVisibility(View.GONE));
            }
        };
        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_CLICK, itemClickListener);

        // ── Hide on map background tap ───────────────────────────
        mapClickListener = event -> {
            overlayItem = null;
            mainHandler.post(() -> {
                if (txOverlayBtn != null) txOverlayBtn.setVisibility(View.GONE);
            });
        };
        mv.getMapEventDispatcher().addMapEventListener(
            MapEvent.MAP_CONFIRMED_CLICK, mapClickListener);

        Log.d(TAG, "TX overlay button added to map view");
    }

    private void removeOverlayButton() {
        MapView mv = MapView.getMapView();
        if (mv != null && itemClickListener != null)
            mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_CLICK, itemClickListener);
        if (mv != null && mapClickListener != null)
            mv.getMapEventDispatcher().removeMapEventListener(
                MapEvent.MAP_CONFIRMED_CLICK, mapClickListener);
        itemClickListener = null;
        mapClickListener  = null;
        overlayItem       = null;
        final Button btn  = txOverlayBtn;
        txOverlayBtn      = null;
        if (btn != null) {
            mainHandler.post(() -> {
                ViewGroup p = (ViewGroup) btn.getParent();
                if (p != null) p.removeView(btn);
            });
        }
    }

    public void setRxEnabled(boolean on) {
        prefs.edit().putBoolean(PREF_RX_ENABLED, on).apply();
        if (on) HBCAudioMonitor.getInstance().start();
        else    HBCAudioMonitor.getInstance().stop();
    }

    // ─── Settings pane binding ───────────────────────────────────────────────

    public void bindSettingsView(View root) {
        settingsRoot = root;  // keep reference so deviceCallback can refresh spinners

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

        // ── Output spinner ────────────────────────────────────────────────────
        Spinner out = root.findViewById(R.id.hbc_spinner_output);
        if (out != null) {
            List<String> names = new ArrayList<>();
            names.add("System Default");
            for (AudioDeviceInfo d : outputDevices) names.add(deviceLabel(d));

            // Clear listener BEFORE setAdapter to suppress the automatic
            // onItemSelected(pos=0) callback that Android fires on adapter change.
            out.setOnItemSelectedListener(null);
            out.setAdapter(new ArrayAdapter<>(pluginContext,
                android.R.layout.simple_spinner_item, names));

            int saved = prefs.getInt(PREF_OUT_DEVICE, -1);
            int savedPos = 0;
            for (int i = 0; i < outputDevices.length; i++)
                if (outputDevices[i].getId() == saved) { savedPos = i + 1; break; }
            out.setSelection(savedPos, false); // false = no animation, no callback

            // Attach listener AFTER selection is set, deferred to next frame
            // so the initial setSelection callback does not fire it.
            final AudioDeviceInfo[] outSnap = outputDevices;
            out.post(() -> out.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (pos < 0 || pos > outSnap.length) return;
                    AudioDeviceInfo d = pos == 0 ? null : outSnap[pos - 1];
                    RadioAudioTransmitter.getInstance().setPreferredOutputDevice(d);
                    prefs.edit().putInt(PREF_OUT_DEVICE, d != null ? d.getId() : -1).apply();
                    Log.d(TAG, "Output device -> " + (d != null ? deviceLabel(d) : "System Default"));
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            }));
        }

        // ── Input spinner ─────────────────────────────────────────────────────
        Spinner in = root.findViewById(R.id.hbc_spinner_input);
        if (in != null) {
            List<String> names = new ArrayList<>();
            names.add("System Default");
            for (AudioDeviceInfo d : inputDevices) names.add(deviceLabel(d));

            in.setOnItemSelectedListener(null);
            in.setAdapter(new ArrayAdapter<>(pluginContext,
                android.R.layout.simple_spinner_item, names));

            int saved = prefs.getInt(PREF_IN_DEVICE, -1);
            int savedPos = 0;
            for (int i = 0; i < inputDevices.length; i++)
                if (inputDevices[i].getId() == saved) { savedPos = i + 1; break; }
            in.setSelection(savedPos, false);

            final AudioDeviceInfo[] inSnap = inputDevices;
            in.post(() -> in.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (pos < 0 || pos > inSnap.length) return;
                    AudioDeviceInfo d = pos == 0 ? null : inSnap[pos - 1];
                    HBCAudioMonitor.getInstance().setPreferredInputDevice(d);
                    prefs.edit().putInt(PREF_IN_DEVICE, d != null ? d.getId() : -1).apply();
                    Log.d(TAG, "Input device -> " + (d != null ? deviceLabel(d) : "System Default"));
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            }));
        }
    }

    private static String deviceLabel(AudioDeviceInfo d) {
        CharSequence name = d.getProductName();
        return (name != null && name.length() > 0) ? name.toString() : "Device " + d.getId();
    }
}
