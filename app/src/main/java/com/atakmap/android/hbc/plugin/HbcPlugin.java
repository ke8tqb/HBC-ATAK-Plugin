package com.atakmap.android.hbc.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.hbc.AudioModem;
import com.atakmap.android.hbc.HbcDecoder;
import com.atakmap.android.hbc.HbcEncoder;
import com.atakmap.android.hbc.Ita2;
import com.atakmap.android.hbc.OfdmModem;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * HBC ATAK Plugin — encodes outgoing CoT events with the Ham Binary CoT
 * (HBC) protocol, frames them as AX.25 UI packets, and transmits them as
 * AFSK1200 audio (speaker / USB-C audio, VOX-keyed radio). Received audio
 * is demodulated, HBC-decoded, and injected back into ATAK as CoT.
 */
public class HbcPlugin implements IPlugin, CommsMapComponent.PreSendProcessor,
        AudioModem.FrameListener, OfdmModem.PayloadListener {

    private static final String TAG = "HbcPlugin";
    private static final String PREFS = "hbc_plugin_prefs";

    // AX.25 destination "callsign" used to tag HBC traffic on the channel
    private static final String DEFAULT_DEST = "HBC";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;

    private SharedPreferences prefs;
    private AudioModem modem;
    private OfdmModem ofdm;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI
    private EditText etCallsign, etDest, etPath, etDwell, etVoxLeader, etPliRate;
    private android.widget.Spinner spTxStream, spModem;
    private CheckBox cbTxEnable, cbRxEnable, cbSelfPli, cbChat, cbAlerts, cbShapes,
            cbCasevac, cbSpots;
    private Button btnStartStop;
    private TextView tvStatus, tvLog;
    private View tabSettings, tabDecodes;
    private Button btnTabSettings, btnTabDecodes, btnDecodesClear;
    private TextView tvDecodes, tvDecodesCount;
    private android.widget.ScrollView svDecodes;
    private int decodeCount = 0;

    private volatile boolean started = false;

    // rate limiting + dedup
    private long lastPliTxMs = 0;
    private final Map<String, Long> recentTx = new LinkedHashMap<>();
    private final Map<Integer, Long> recentRx = new HashMap<>();

    public HbcPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider =
                serviceController.getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        prefs = pluginContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onStop() {
        stopRadio();
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------
    private void showPane() {
        if (pane == null) {
            View v = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            bindViews(v);
            pane = new PaneBuilder(v)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    private void bindViews(View v) {
        etCallsign  = v.findViewById(R.id.hbc_callsign);
        etDest      = v.findViewById(R.id.hbc_dest);
        etPath      = v.findViewById(R.id.hbc_path);
        etDwell     = v.findViewById(R.id.hbc_dwell);
        etVoxLeader = v.findViewById(R.id.hbc_vox_leader);
        etPliRate   = v.findViewById(R.id.hbc_pli_rate);
        cbTxEnable  = v.findViewById(R.id.hbc_tx_enable);
        cbRxEnable  = v.findViewById(R.id.hbc_rx_enable);
        cbSelfPli   = v.findViewById(R.id.hbc_mode_pli);
        cbSpots     = v.findViewById(R.id.hbc_mode_spots);
        cbAlerts    = v.findViewById(R.id.hbc_mode_alerts);
        cbChat      = v.findViewById(R.id.hbc_mode_chat);
        cbShapes    = v.findViewById(R.id.hbc_mode_shapes);
        cbCasevac   = v.findViewById(R.id.hbc_mode_casevac);
        btnStartStop = v.findViewById(R.id.hbc_start_stop);
        tvStatus    = v.findViewById(R.id.hbc_status);
        tvLog       = v.findViewById(R.id.hbc_log);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        // tabs
        tabSettings    = v.findViewById(R.id.hbc_tab_settings);
        tabDecodes     = v.findViewById(R.id.hbc_tab_decodes);
        btnTabSettings = v.findViewById(R.id.hbc_tab_btn_settings);
        btnTabDecodes  = v.findViewById(R.id.hbc_tab_btn_decodes);
        tvDecodes      = v.findViewById(R.id.hbc_decodes);
        tvDecodesCount = v.findViewById(R.id.hbc_decodes_count);
        svDecodes      = v.findViewById(R.id.hbc_decodes_scroll);
        btnDecodesClear = v.findViewById(R.id.hbc_decodes_clear);

        spTxStream = v.findViewById(R.id.hbc_tx_stream);
        android.widget.ArrayAdapter<CharSequence> streamAdapter =
                android.widget.ArrayAdapter.createFromResource(pluginContext,
                        R.array.hbc_stream_options,
                        android.R.layout.simple_spinner_item);
        streamAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        spTxStream.setAdapter(streamAdapter);

        spModem = v.findViewById(R.id.hbc_modem_type);
        android.widget.ArrayAdapter<CharSequence> modemAdapter =
                android.widget.ArrayAdapter.createFromResource(pluginContext,
                        R.array.hbc_modem_options,
                        android.R.layout.simple_spinner_item);
        modemAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        spModem.setAdapter(modemAdapter);

        btnTabSettings.setOnClickListener(view -> selectTab(false));
        btnTabDecodes.setOnClickListener(view -> selectTab(true));
        btnDecodesClear.setOnClickListener(view -> {
            decodeCount = 0;
            tvDecodes.setText("");
            tvDecodesCount.setText(pluginContext.getString(R.string.hbc_decodes_none));
        });
        selectTab(false);

        loadPrefs();

        btnStartStop.setOnClickListener(view -> {
            if (started) {
                stopRadio();
            } else {
                if (validateAndSavePrefs())
                    startRadio();
            }
            updateUiState();
        });

        updateUiState();
    }

    private void selectTab(boolean decodes) {
        if (tabSettings == null) return;
        tabSettings.setVisibility(decodes ? View.GONE : View.VISIBLE);
        tabDecodes.setVisibility(decodes ? View.VISIBLE : View.GONE);
        btnTabSettings.setEnabled(decodes);
        btnTabDecodes.setEnabled(!decodes);
    }

    private void loadPrefs() {
        etCallsign.setText(prefs.getString("callsign", ""));
        etDest.setText(prefs.getString("dest", DEFAULT_DEST));
        etPath.setText(prefs.getString("path", ""));
        etDwell.setText(String.valueOf(prefs.getInt("dwell_ms", 500)));
        etVoxLeader.setText(String.valueOf(prefs.getInt("vox_leader_ms", 0)));
        etPliRate.setText(String.valueOf(prefs.getInt("pli_rate_s", 60)));
        cbTxEnable.setChecked(prefs.getBoolean("tx_enable", true));
        cbRxEnable.setChecked(prefs.getBoolean("rx_enable", true));
        cbSelfPli.setChecked(prefs.getBoolean("mode_pli", true));
        cbSpots.setChecked(prefs.getBoolean("mode_spots", true));
        cbAlerts.setChecked(prefs.getBoolean("mode_alerts", true));
        cbChat.setChecked(prefs.getBoolean("mode_chat", true));
        cbShapes.setChecked(prefs.getBoolean("mode_shapes", true));
        cbCasevac.setChecked(prefs.getBoolean("mode_casevac", true));
        if (spTxStream != null)
            spTxStream.setSelection(prefs.getInt("tx_stream", 0));
        if (spModem != null)
            spModem.setSelection(prefs.getInt("modem_type", 0));
    }

    private boolean validateAndSavePrefs() {
        String callsign = etCallsign.getText().toString().trim().toUpperCase();
        if (callsign.isEmpty()) {
            toast("Enter your ham radio callsign first");
            return false;
        }
        if (callsign.length() > 8 || !Ita2.isEncodable(callsign)) {
            toast("Callsign must be \u2264 8 ITA2 characters (A-Z, 0-9, -)");
            return false;
        }
        String dest = etDest.getText().toString().trim().toUpperCase();
        if (dest.isEmpty()) dest = DEFAULT_DEST;

        prefs.edit()
                .putString("callsign", callsign)
                .putString("dest", dest)
                .putString("path", etPath.getText().toString().trim().toUpperCase())
                .putInt("dwell_ms", intOf(etDwell, 500))
                .putInt("vox_leader_ms", intOf(etVoxLeader, 0))
                .putInt("pli_rate_s", intOf(etPliRate, 60))
                .putBoolean("tx_enable", cbTxEnable.isChecked())
                .putBoolean("rx_enable", cbRxEnable.isChecked())
                .putBoolean("mode_pli", cbSelfPli.isChecked())
                .putBoolean("mode_spots", cbSpots.isChecked())
                .putBoolean("mode_alerts", cbAlerts.isChecked())
                .putBoolean("mode_chat", cbChat.isChecked())
                .putBoolean("mode_shapes", cbShapes.isChecked())
                .putBoolean("mode_casevac", cbCasevac.isChecked())
                .putInt("tx_stream", spTxStream == null ? 0
                        : spTxStream.getSelectedItemPosition())
                .putInt("modem_type", spModem == null ? 0
                        : spModem.getSelectedItemPosition())
                .apply();
        return true;
    }

    private static int intOf(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void updateUiState() {
        if (btnStartStop == null) return;
        btnStartStop.setText(started ? "Stop Radio Link" : "Start Radio Link");
        if (tvStatus != null)
            tvStatus.setText(started
                    ? "RUNNING as " + prefs.getString("callsign", "?")
                    : "STOPPED");
    }

    // ------------------------------------------------------------------
    // Radio lifecycle
    // ------------------------------------------------------------------
    private boolean isOfdm() {
        return prefs.getInt("modem_type", 0) == 1;
    }

    private synchronized void startRadio() {
        if (started) return;
        try {
            if (isOfdm()) {
                ofdm = new OfdmModem(pluginContext, this);
                ofdm.setVoxLeaderMs(prefs.getInt("vox_leader_ms", 0));
                ofdm.setTxStreamIndex(prefs.getInt("tx_stream", 0));
                ofdm.start();
            } else {
                modem = new AudioModem(pluginContext, this);
                modem.setTxDwellMs(prefs.getInt("dwell_ms", 500));
                modem.setVoxLeaderMs(prefs.getInt("vox_leader_ms", 0));
                modem.setTxStreamIndex(prefs.getInt("tx_stream", 0));
                modem.start();
            }
            CommsMapComponent.getInstance().registerPreSendProcessor(this);
            started = true;
            log("Radio link started (" + (isOfdm() ? "OFDM" : "AFSK1200") + ")");
        } catch (Throwable e) {
            Log.e(TAG, "start failed", e);
            log("Start failed: " + e);
            stopRadio();
        }
    }

    private synchronized void stopRadio() {
        if (modem != null || ofdm != null) {
            try {
                CommsMapComponent.getInstance().registerPreSendProcessor(null);
            } catch (Exception ignored) {}
            if (modem != null) { modem.stop(); modem = null; }
            if (ofdm != null)  { ofdm.stop();  ofdm = null; }
        }
        if (started) log("Radio link stopped");
        started = false;
    }

    // ------------------------------------------------------------------
    // Outbound: ATAK -> HBC -> AX.25 -> audio
    // ------------------------------------------------------------------
    @Override
    public void processCotEvent(CotEvent event, String[] toUIDs) {
        if (!started || (modem == null && ofdm == null)
                || !prefs.getBoolean("tx_enable", true))
            return;
        try {
            String type = event.getType();
            log("TX candidate: " + type + " uid " + event.getUID());
            if (!modeEnabled(type)) {
                log("TX skip: type " + type + " disabled in settings");
                return;
            }

            // PLI rate limit
            if (type.startsWith("a-f-G") || type.startsWith("a-h-G") || type.startsWith("a-n-G")) {
                long minInterval = prefs.getInt("pli_rate_s", 60) * 1000L;
                long now = System.currentTimeMillis();
                if (now - lastPliTxMs < minInterval) {
                    log("TX skip: PLI rate limit");
                    return;
                }
                lastPliTxMs = now;
            }

            // suppress rapid duplicates of the same uid (auto-resends)
            String uid = event.getUID();
            long now = System.currentTimeMillis();
            Long lastSent = recentTx.get(uid + "|" + type);
            if (lastSent != null && now - lastSent < 10000) {
                log("TX skip: duplicate within 10 s");
                return;
            }
            recentTx.put(uid + "|" + type, now);
            pruneOld(recentTx, 300000);

            HbcEncoder.Encoded enc = HbcEncoder.encode(event.toString());
            String myCall = prefs.getString("callsign", "");
            String dest = prefs.getString("dest", DEFAULT_DEST);
            String[] path = parsePath(prefs.getString("path", ""));

            if (ofdm != null)
                ofdm.transmit(myCall, enc.bytes);
            else
                modem.transmit(dest, myCall, path, enc.bytes);
            log("Queued TX mode " + enc.mode + " (" + enc.bytes.length + " B) " + type);
        } catch (HbcEncoder.HbcEncodeException e) {
            log("TX skip: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "processCotEvent failed", e);
            log("TX error: " + e);
        }
    }

    private boolean modeEnabled(String type) {
        if (type.startsWith("a-f-G") || type.startsWith("a-h-G") || type.startsWith("a-n-G"))
            return cbOrPref(cbSelfPli, "mode_pli");
        switch (type) {
            case "b-a-o-tbl":
            case "b-a-o-can": return cbOrPref(cbAlerts, "mode_alerts");
            case "b-t-f":     return cbOrPref(cbChat, "mode_chat");
            case "u-d-c-c":
            case "u-d-r":
            case "u-d-f":     return cbOrPref(cbShapes, "mode_shapes");
            case "b-r-f-h-c": return cbOrPref(cbCasevac, "mode_casevac");
            default:
                // Placed markers transmit as spots (Mode 6, Mode 1 fallback):
                //  - other atom types (a-u-G unknown ground, a-f-A aircraft, ...)
                //  - b-m-p-* point markers: spot map (b-m-p-s-m), waypoints
                //    (b-m-p-w), command posts (b-m-p-c-cp), etc. These were
                //    previously dropped entirely, so colored spot-map markers
                //    never transmitted at all.
                if (type.startsWith("a-") || type.startsWith("b-m-p"))
                    return cbOrPref(cbSpots, "mode_spots");
                return false;
        }
    }

    private boolean cbOrPref(CheckBox cb, String key) {
        return cb != null ? cb.isChecked() : prefs.getBoolean(key, true);
    }

    private static String[] parsePath(String s) {
        if (s == null || s.trim().isEmpty()) return new String[0];
        String[] parts = s.split("[,\\s]+");
        return Arrays.stream(parts).filter(p -> !p.isEmpty()).toArray(String[]::new);
    }

    // ------------------------------------------------------------------
    // Inbound: audio -> AX.25 -> HBC -> ATAK
    // ------------------------------------------------------------------
    @Override
    public void onFrame(byte[] ax25Frame) {
        if (!started || !prefs.getBoolean("rx_enable", true))
            return;
        try {
            // dedup identical frames heard twice (digipeats / dual demod)
            int hash = Arrays.hashCode(ax25Frame);
            long now = System.currentTimeMillis();
            Long seen = recentRx.get(hash);
            if (seen != null && now - seen < 30000)
                return;
            recentRx.put(hash, now);
            pruneOld(recentRx, 300000);

            sivantoledo.ax25.Packet p = new sivantoledo.ax25.Packet(ax25Frame);
            p.parse();
            byte[] payload = p.payload;
            if (payload == null || payload.length == 0)
                return;

            HbcDecoder.Decoded dec = HbcDecoder.decode(payload);

            // ignore our own transmissions (heard via our own mic/audio
            // loopback or digipeated back). The AX.25 source address is our
            // configured ham callsign; the HBC header callsign is the ATAK
            // device callsign — check both.
            String myCall = prefs.getString("callsign", "");
            String srcBase = p.source == null ? ""
                    : (p.source.contains("-")
                            ? p.source.substring(0, p.source.indexOf('-'))
                            : p.source);
            if (srcBase.equalsIgnoreCase(myCall)
                    || dec.callsign.equalsIgnoreCase(myCall))
                return;
            try {
                String atakCallsign = com.atakmap.android.maps.MapView
                        .getMapView().getDeviceCallsign();
                if (atakCallsign != null
                        && dec.callsign.equalsIgnoreCase(atakCallsign))
                    return;
            } catch (Exception ignored) {}

            if (!resolveDirectMessage(dec))
                return;

            String xml = dec.toXml();
            CotEvent event = CotEvent.parse(xml);
            if (event == null || !event.isValid()) {
                log("RX decode produced invalid CoT (" + dec.summary() + ")");
                return;
            }
            CotMapComponent.getInternalDispatcher().dispatch(event);
            log("RX " + p.source + ">" + p.destination + " " + dec.summary());
            logDecode(p.source + " > " + p.destination
                    + (p.path != null && p.path.length > 0
                            ? " via " + String.join(",", p.path) : "")
                    + "\n  " + dec.summary()
                    + "\n  " + payload.length + " B payload");
        } catch (Exception e) {
            Log.d(TAG, "RX frame not HBC: " + e.getMessage());
        }
    }

    @Override
    public void onStatus(String message) {
        log(message);
    }

    // ------------------------------------------------------------------
    // Inbound (OFDM): audio -> COFDMTV -> HBC -> ATAK
    // ------------------------------------------------------------------
    @Override
    public void onPayload(String callsign, byte[] hbcPayload) {
        if (!started || !prefs.getBoolean("rx_enable", true))
            return;
        try {
            // dedup identical payloads (repeats / echoes)
            int hash = Arrays.hashCode(hbcPayload) * 31 + callsign.hashCode();
            long now = System.currentTimeMillis();
            Long seen = recentRx.get(hash);
            if (seen != null && now - seen < 30000)
                return;
            recentRx.put(hash, now);
            pruneOld(recentRx, 300000);

            String myCall = prefs.getString("callsign", "");
            if (callsign.equalsIgnoreCase(myCall))
                return; // our own transmission heard back

            HbcDecoder.Decoded dec = HbcDecoder.decode(hbcPayload);
            if (dec.callsign.equalsIgnoreCase(myCall))
                return;
            try {
                String atakCallsign = com.atakmap.android.maps.MapView
                        .getMapView().getDeviceCallsign();
                if (atakCallsign != null
                        && dec.callsign.equalsIgnoreCase(atakCallsign))
                    return;
            } catch (Exception ignored) {}

            if (!resolveDirectMessage(dec))
                return;

            String xml = dec.toXml();
            CotEvent event = CotEvent.parse(xml);
            if (event == null || !event.isValid()) {
                log("RX decode produced invalid CoT (" + dec.summary() + ")");
                return;
            }
            CotMapComponent.getInternalDispatcher().dispatch(event);
            log("RX OFDM " + callsign + " " + dec.summary());
            logDecode(callsign + " (OFDM)"
                    + "\n  " + dec.summary()
                    + "\n  " + hbcPayload.length + " B payload");
        } catch (Exception e) {
            Log.d(TAG, "OFDM payload not HBC: " + e.getMessage());
        }
    }

    /**
     * Mode 3 Direct Message addressing. HBC transmits only the recipient's
     * callsign; ATAK's chat window files a 1:1 message only when the
     * reconstructed chatgrp/uid1 equals this device's real UID. If the DM is
     * addressed to us (ham callsign pref or ATAK device callsign), fill in
     * the local device UID before XML reconstruction. If it is addressed to
     * another station, do not inject it at all.
     *
     * @return true when the decoded message should be dispatched into ATAK
     */
    private boolean resolveDirectMessage(HbcDecoder.Decoded dec) {
        if (dec.mode != 3 || dec.chatDestKind != 2)
            return true;   // not a direct message — nothing to resolve
        String recipient = dec.chatRecipient == null ? "" : dec.chatRecipient.trim();
        String myCall = prefs.getString("callsign", "");
        String atakCallsign = null, deviceUid = null;
        try {
            com.atakmap.android.maps.MapView mv =
                    com.atakmap.android.maps.MapView.getMapView();
            if (mv != null) {
                atakCallsign = mv.getDeviceCallsign();
                deviceUid = mv.getSelfMarker() != null
                        ? mv.getSelfMarker().getUID() : null;
            }
        } catch (Exception ignored) {}

        boolean forUs = recipient.equalsIgnoreCase(myCall)
                || (atakCallsign != null && recipient.equalsIgnoreCase(atakCallsign));
        if (!forUs) {
            log("RX chat: DM for '" + recipient + "' — not this station, ignored");
            return false;
        }
        if (deviceUid != null && !deviceUid.isEmpty())
            dec.chatRecipientUidOverride = deviceUid;
        return true;
    }

    private static <K> void pruneOld(Map<K, Long> map, long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        Iterator<Map.Entry<K, Long>> it = map.entrySet().iterator();
        while (it.hasNext())
            if (it.next().getValue() < cutoff)
                it.remove();
    }

    // ------------------------------------------------------------------
    // Logging helpers
    // ------------------------------------------------------------------
    private void log(String msg) {
        Log.d(TAG, msg);
        mainHandler.post(() -> {
            if (tvLog != null) {
                String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                CharSequence existing = tvLog.getText();
                String updated = stamp + "  " + msg + "\n" + existing;
                if (updated.length() > 8000)
                    updated = updated.substring(0, 8000);
                tvLog.setText(updated);
            }
            updateUiState();
        });
    }

    private void logDecode(String entry) {
        mainHandler.post(() -> {
            if (tvDecodes == null) return;
            decodeCount++;
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            tvDecodes.append("[" + stamp + "]  " + entry + "\n\n");
            tvDecodesCount.setText(decodeCount + " packet(s) decoded");
            if (svDecodes != null)
                svDecodes.post(() -> svDecodes.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String msg) {
        mainHandler.post(() ->
                Toast.makeText(pluginContext, msg, Toast.LENGTH_LONG).show());
    }
}
