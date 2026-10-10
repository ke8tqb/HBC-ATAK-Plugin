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
import com.atakmap.android.hbc.BridgePolicy;
import com.atakmap.android.hbc.HbcDecoder;
import com.atakmap.android.hbc.HbcEncoder;
import com.atakmap.android.hbc.Ita2;
import com.atakmap.android.hbc.MercuryModem;
import com.atakmap.android.hbc.MeshRouter;
import com.atakmap.android.hbc.OfdmModem;
import com.atakmap.android.hbc.RingMac;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    // Fixed AX.25 destination used purely as PHY framing on the AFSK modem
    // (the mesh header carries all real addressing; the AX.25 source still
    // carries the ham callsign for Part 97 station ID)
    private static final String DEFAULT_DEST = "HBC";

    // "Send to" spinner entry 0: unrouted broadcast
    private static final String SEND_TO_BROADCAST = "Broadcast (everyone)";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;

    private SharedPreferences prefs;
    private AudioModem modem;
    private OfdmModem ofdm;
    private MercuryModem mercury;
    private MeshRouter mesh;
    private RingMac ringMac;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Frames waiting for this station's Ring MAC turn (ring mode only;
    // under CSMA frames go straight to the modem queue as before)
    private final java.util.ArrayDeque<byte[]> ringPending = new java.util.ArrayDeque<>();

    // v0.23 ring-queue hygiene: flag set just before a self-PLI broadcast
    // reaches txFrame (same call stack) so the queue can replace the
    // previous, now-stale queued PLI; plus rate-limited backlog warnings.
    private volatile boolean pliBroadcastNext = false;
    private byte[] lastQueuedPli;             // guarded by ringPending
    private volatile long lastQueueWarnMs = 0;
    private volatile int ringFramesPerTurn = 1;

    // v0.24 C2 Bridge (one-way RF -> LAN data diode): UIDs we re-published
    // onto the network (never allowed back to RF), and the stash of
    // network-origin events blocked from auto-relay, awaiting the
    // operator's "Push to RF" selection.
    private final Map<String, Long> bridgedUids = new HashMap<>();
    private final BridgePolicy.PushStash<CotEvent> blockedForPush =
            new BridgePolicy.PushStash<>(20, 10 * 60_000L);

    // UI
    private EditText etCallsign, etDwell, etVoxLeader, etPliRate, etAfskLevel;
    private EditText etRingGuard, etRingSkip, etRingMaxTurn;
    private android.widget.Spinner spTxStream, spModem, spSendTo, spMacMode,
            spTxOutput;
    private CheckBox cbTxEnable, cbRxEnable, cbSelfPli, cbChat, cbAlerts, cbShapes,
            cbCasevac, cbSpots, cbC2Bridge;
    private Button btnPushRf;

    // v0.25: Digirig RTS keyer (open while the radio link runs in
    // "USB + RTS PTT" TX output mode) + the USB-permission result
    // receiver that engages it as soon as the user taps Allow
    private UsbPtt usbPtt;
    private android.content.BroadcastReceiver usbPermReceiver;
    private Button btnStartStop;
    private TextView tvStatus, tvLog, tvPliCountdown;
    private boolean pliTickerRunning = false;
    private View tabAudio, tabOptions, tabDecodes;
    private View headerView, tabBarView;
    private int lastConfigTab = 0;   // tab to return to from the Decodes page
    private Button btnTabAudio, btnTabOptions, btnTabDecodes, btnDecodesClear,
            btnDecodesBack;
    private TextView tvDecodes, tvDecodesCount;
    private android.widget.ScrollView svDecodes;
    private int decodeCount = 0;

    private volatile boolean started = false;

    // per-session detailed debug log (created on Start, offered for saving
    // to Downloads when the user presses Stop Radio Link)
    private volatile SessionLog sessionLog;

    // rate limiting + dedup
    private long lastPliTxMs = 0;
    private final Map<String, Long> recentTx = new LinkedHashMap<>();
    private final Map<Integer, Long> recentRx = new HashMap<>();

    // v1.6 DM delivery/read receipts:
    //  - sender side: DM tag -> the original ATAK messageId (to resolve
    //    incoming Mode 0 acks back into b-t-f-d / b-t-f-r receipt CoTs)
    //  - receiver side: injected messageId -> {tag, sender callsign} (to
    //    convert ATAK's automatic receipts into Mode 0 acks)
    private final Map<Integer, String> sentDmMessageIds = new LinkedHashMap<>();
    private static final class PendingAck {
        final int tag; final String senderCallsign;
        PendingAck(int tag, String senderCallsign) {
            this.tag = tag; this.senderCallsign = senderCallsign;
        }
    }
    private final Map<String, PendingAck> rxDmAcks = new LinkedHashMap<>();
    private static final int MAX_ACK_MAP = 200;

    // ATAK callsign -> mesh (ham) callsign, learned from received traffic.
    // DMs/acks are addressed to ATAK callsigns but mesh routes are keyed by
    // ham callsigns, so without this map direct sends always fell back to
    // "no route — sending as broadcast".
    private final Map<String, String> atakToHamCall = new LinkedHashMap<>();

    private static <K, V> void capSize(Map<K, V> map) {
        Iterator<? extends Map.Entry<K, V>> it = map.entrySet().iterator();
        while (map.size() > MAX_ACK_MAP && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public HbcPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider =
                serviceController.getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);

        // Tools-grid items are tinted white by ATAK, so the toolbar icon
        // must be an alpha silhouette (ic_tools), not the opaque badge
        // (ic_launcher stays as the app/package-manager icon).
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        rasterize(pluginContext.getResources()
                                .getDrawable(R.drawable.ic_tools), 192),
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

    /**
     * Draw any drawable (vectors included) into an ARGB bitmap. The TAK
     * drawable→bitmap marshaler only handles bitmap-backed drawables, so a
     * VectorDrawable passed straight in comes out as a blank square.
     */
    private android.graphics.drawable.Drawable rasterize(
            android.graphics.drawable.Drawable d, int px) {
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        d.setBounds(0, 0, px, px);
        d.draw(canvas);
        return new android.graphics.drawable.BitmapDrawable(
                pluginContext.getResources(), bmp);
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

    // NOTE: the separate radio-status toolbar icon (gray/green, tap to
    // toggle the modem) was removed in 0.22 — the radio is controlled
    // entirely from the plugin pane's Start/Stop button.

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
        refreshSendTo();
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    private void bindViews(View v) {
        // CORE CONTROLS FIRST. If anything in the secondary wiring below
        // throws (bad cached resources after a same-version reinstall,
        // adapter/resource trouble, etc.), Start/Stop and the tab switcher
        // must already be alive — a mid-bind exception previously left
        // every later listener unbound and the pane looked "dead".
        btnStartStop  = v.findViewById(R.id.hbc_start_stop);
        tvStatus      = v.findViewById(R.id.hbc_status);
        headerView    = v.findViewById(R.id.hbc_header);
        tabBarView    = v.findViewById(R.id.hbc_tabbar);
        tabAudio      = v.findViewById(R.id.hbc_tab_audio);
        tabOptions    = v.findViewById(R.id.hbc_tab_options);
        tabDecodes    = v.findViewById(R.id.hbc_tab_decodes);
        btnTabAudio   = v.findViewById(R.id.hbc_tab_btn_audio);
        btnTabOptions = v.findViewById(R.id.hbc_tab_btn_options);
        btnTabDecodes = v.findViewById(R.id.hbc_tab_btn_decodes);
        btnDecodesBack = v.findViewById(R.id.hbc_decodes_back);

        btnStartStop.setOnClickListener(view -> {
            if (started) {
                SessionLog finished = sessionLog;
                stopRadio();
                promptSaveSessionLog(finished);
            } else {
                if (validateAndSavePrefs())
                    startRadio();
            }
            updateUiState();
        });
        btnTabAudio.setOnClickListener(view -> selectTab(0));
        btnTabOptions.setOnClickListener(view -> selectTab(1));
        btnTabDecodes.setOnClickListener(view -> selectTab(2));
        if (btnDecodesBack != null)
            btnDecodesBack.setOnClickListener(view -> selectTab(lastConfigTab));
        selectTab(0);

        try {
            bindSecondaryViews(v);
        } catch (Throwable t) {
            Log.e(TAG, "secondary UI wiring failed", t);
            toast("HBC: settings UI partly failed to load ("
                    + t.getClass().getSimpleName()
                    + ") — uninstall and reinstall the plugin");
        }

        updateUiState();
    }

    /** Everything beyond the core controls; failures here are contained. */
    private void bindSecondaryViews(View v) {
        etCallsign  = v.findViewById(R.id.hbc_callsign);
        etDwell     = v.findViewById(R.id.hbc_dwell);
        etVoxLeader = v.findViewById(R.id.hbc_vox_leader);
        etPliRate   = v.findViewById(R.id.hbc_pli_rate);
        etAfskLevel = v.findViewById(R.id.hbc_afsk_level);
        etRingGuard   = v.findViewById(R.id.hbc_ring_guard);
        etRingSkip    = v.findViewById(R.id.hbc_ring_skip);
        etRingMaxTurn = v.findViewById(R.id.hbc_ring_maxturn);
        View macHelp = v.findViewById(R.id.hbc_mac_help);
        if (macHelp != null)
            macHelp.setOnClickListener(view -> showMacHelp());
        cbTxEnable  = v.findViewById(R.id.hbc_tx_enable);
        cbRxEnable  = v.findViewById(R.id.hbc_rx_enable);
        cbSelfPli   = v.findViewById(R.id.hbc_mode_pli);
        cbSpots     = v.findViewById(R.id.hbc_mode_spots);
        cbAlerts    = v.findViewById(R.id.hbc_mode_alerts);
        cbChat      = v.findViewById(R.id.hbc_mode_chat);
        cbShapes    = v.findViewById(R.id.hbc_mode_shapes);
        cbCasevac   = v.findViewById(R.id.hbc_mode_casevac);
        cbC2Bridge  = v.findViewById(R.id.hbc_c2_bridge);
        btnPushRf   = v.findViewById(R.id.hbc_push_rf);
        if (cbC2Bridge != null)
            cbC2Bridge.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("c2_bridge", checked).apply();
                updateUiState();
            });
        if (btnPushRf != null)
            btnPushRf.setOnClickListener(view -> showPushRfDialog());
        tvPliCountdown = v.findViewById(R.id.hbc_pli_countdown);
        tvLog       = v.findViewById(R.id.hbc_log);
        if (tvLog != null)
            tvLog.setMovementMethod(new ScrollingMovementMethod());
        startPliTicker();

        tvDecodes      = v.findViewById(R.id.hbc_decodes);
        tvDecodesCount = v.findViewById(R.id.hbc_decodes_count);
        svDecodes      = v.findViewById(R.id.hbc_decodes_scroll);
        btnDecodesClear = v.findViewById(R.id.hbc_decodes_clear);
        if (btnDecodesClear != null)
            btnDecodesClear.setOnClickListener(view -> {
                decodeCount = 0;
                if (tvDecodes != null) tvDecodes.setText("");
                if (tvDecodesCount != null)
                    tvDecodesCount.setText(
                            pluginContext.getString(R.string.hbc_decodes_none));
            });

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

        spTxOutput = v.findViewById(R.id.hbc_tx_output);
        android.widget.ArrayAdapter<CharSequence> outAdapter =
                android.widget.ArrayAdapter.createFromResource(pluginContext,
                        R.array.hbc_tx_output_options,
                        android.R.layout.simple_spinner_item);
        outAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        spTxOutput.setAdapter(outAdapter);

        spMacMode = v.findViewById(R.id.hbc_mac_mode);
        android.widget.ArrayAdapter<CharSequence> macAdapter =
                android.widget.ArrayAdapter.createFromResource(pluginContext,
                        R.array.hbc_mac_options,
                        android.R.layout.simple_spinner_item);
        macAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        spMacMode.setAdapter(macAdapter);

        spSendTo = v.findViewById(R.id.hbc_send_to);
        refreshSendTo();

        loadPrefs();
    }

    /**
     * Rebuild the "Send to" spinner: Broadcast + every destination learned
     * from mesh Announces, keeping the saved selection when possible.
     */
    private void refreshSendTo() {
        if (spSendTo == null) return;
        java.util.List<String> items = new ArrayList<>();
        items.add(SEND_TO_BROADCAST);
        if (mesh != null)
            items.addAll(mesh.knownDestinations());
        String saved = prefs == null ? "" : prefs.getString("send_to", "");
        if (!saved.isEmpty() && !items.contains(saved))
            items.add(saved);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                pluginContext, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSendTo.setAdapter(adapter);
        int idx = saved.isEmpty() ? 0 : items.indexOf(saved);
        spSendTo.setSelection(Math.max(0, idx));
    }

    /**
     * Once-a-second UI ticker showing when the next self-PLI broadcast can
     * go out, based on the user's "PLI min s" rate limit and the time of
     * the last PLI actually transmitted. ATAK originates the PLI events;
     * the plugin transmits the first one that arrives after the countdown
     * reaches zero.
     */
    private void startPliTicker() {
        if (pliTickerRunning) return;
        pliTickerRunning = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updatePliCountdown();
                checkTxBacklog();
                mainHandler.postDelayed(this, 1000);
            }
        });
    }

    // v0.27 TX stall watchdog: if more than 5 frames are waiting anywhere
    // in the TX path (ring queue + modem queue), raise a visible error at
    // most every 30 s — the queue silently never draining was a field
    // finding (carrier sense latched busy by a hot Digirig RX line).
    private long lastTxAlarmMs = 0;

    private void checkTxBacklog() {
        if (!started) {
            lastTxAlarmMs = 0;
            return;
        }
        int depth;
        synchronized (ringPending) {
            depth = ringPending.size();
        }
        AudioModem m = modem;
        OfdmModem o = ofdm;
        MercuryModem h = mercury;
        if (m != null) depth += m.queuedFrames();
        if (o != null) depth += o.queuedFrames();
        if (h != null) depth += h.queuedFrames();
        if (depth <= 5) {
            if (depth == 0) lastTxAlarmMs = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTxAlarmMs < 30_000) return;
        lastTxAlarmMs = now;
        log("TX ISSUE: " + depth + " frames stuck in the TX queue \u2014 "
                + "channel reads busy or PTT/audio failed. Check RX "
                + "volume/squelch and the PTT lines above; Stop/Start "
                + "recovers if it persists");
        toast("HBC: TX queue stuck (" + depth + " frames)");
    }

    private void updatePliCountdown() {
        if (tvPliCountdown == null) return;
        String text;
        if (!started) {
            // While stopped, show WHICH binary is installed — instantly
            // exposes a stale install in the field.
            text = "Build " + BuildConfig.BUILD_STAMP
                    + " \u00b7 v" + BuildConfig.VERSION_NAME;
        } else if (!prefs.getBoolean("tx_enable", true)
                || !prefs.getBoolean("mode_pli", true)) {
            text = "Next PLI: disabled";
        } else {
            long rateMs = prefs.getInt("pli_rate_s", 60) * 1000L;
            long remain = lastPliTxMs == 0 ? 0
                    : lastPliTxMs + rateMs - System.currentTimeMillis();
            if (remain <= 0) {
                text = "Next PLI: ready (waiting for ATAK position update)";
            } else {
                long s = (remain + 999) / 1000;
                text = String.format(Locale.US, "Next PLI: in %d:%02d",
                        s / 60, s % 60);
            }
        }
        tvPliCountdown.setText(text);
    }

    /**
     * 0 = Audio Setup, 1 = Options, 2 = Decodes. The Decodes view is a
     * full-panel page: it hides the header and tab bar and is left via
     * its own Back button (returns to the last config tab).
     */
    private void selectTab(int tab) {
        if (tabAudio == null) return;
        boolean fullPage = tab == 2;
        if (!fullPage) lastConfigTab = tab;
        if (headerView != null)
            headerView.setVisibility(fullPage ? View.GONE : View.VISIBLE);
        if (tabBarView != null)
            tabBarView.setVisibility(fullPage ? View.GONE : View.VISIBLE);
        tabAudio.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        tabOptions.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        tabDecodes.setVisibility(fullPage ? View.VISIBLE : View.GONE);
        btnTabAudio.setEnabled(tab != 0);
        btnTabOptions.setEnabled(tab != 1);
        btnTabDecodes.setEnabled(tab != 2);
    }

    private void loadPrefs() {
        etCallsign.setText(prefs.getString("callsign", ""));
        etDwell.setText(String.valueOf(prefs.getInt("dwell_ms", 500)));
        etVoxLeader.setText(String.valueOf(prefs.getInt("vox_leader_ms", 0)));
        etPliRate.setText(String.valueOf(prefs.getInt("pli_rate_s", 60)));
        if (etAfskLevel != null)
            etAfskLevel.setText(String.valueOf(prefs.getInt("afsk_tx_level_pct", 50)));
        cbTxEnable.setChecked(prefs.getBoolean("tx_enable", true));
        cbRxEnable.setChecked(prefs.getBoolean("rx_enable", true));
        cbSelfPli.setChecked(prefs.getBoolean("mode_pli", true));
        cbSpots.setChecked(prefs.getBoolean("mode_spots", true));
        cbAlerts.setChecked(prefs.getBoolean("mode_alerts", true));
        cbChat.setChecked(prefs.getBoolean("mode_chat", true));
        cbShapes.setChecked(prefs.getBoolean("mode_shapes", true));
        cbCasevac.setChecked(prefs.getBoolean("mode_casevac", true));
        if (cbC2Bridge != null)
            cbC2Bridge.setChecked(prefs.getBoolean("c2_bridge", false));
        if (spTxStream != null)
            spTxStream.setSelection(prefs.getInt("tx_stream", 0));
        if (spTxOutput != null)
            spTxOutput.setSelection(prefs.getInt("tx_output", 0));
        if (spModem != null)
            spModem.setSelection(modemTypeToPos(prefs.getInt("modem_type", 1)));
        if (spMacMode != null)
            spMacMode.setSelection(prefs.getInt("mac_mode", 0));
        if (etRingGuard != null)
            etRingGuard.setText(String.valueOf(
                    prefs.getInt("ring_guard_ms", RingMac.DEFAULT_GUARD_MS)));
        if (etRingSkip != null)
            etRingSkip.setText(String.valueOf(
                    prefs.getInt("ring_skip_ms", RingMac.DEFAULT_SKIP_MS)));
        if (etRingMaxTurn != null)
            etRingMaxTurn.setText(String.valueOf(
                    prefs.getInt("ring_max_turn_ms", 0)));
    }

    private boolean validateAndSavePrefs() {
        if (etCallsign == null) {
            // Secondary UI failed to bind: run from the previously saved
            // settings instead of blocking the radio entirely.
            String saved = prefs.getString("callsign", "");
            if (saved.isEmpty()) {
                toast("Settings fields unavailable — reinstall the plugin");
                return false;
            }
            return true;
        }
        String callsign = etCallsign.getText().toString().trim().toUpperCase();
        if (callsign.isEmpty()) {
            toast("Enter your ham radio callsign first");
            return false;
        }
        if (callsign.length() > 8 || !Ita2.isEncodable(callsign)) {
            toast("Callsign must be \u2264 8 ITA2 characters (A-Z, 0-9, -)");
            return false;
        }
        String sendTo = "";
        if (spSendTo != null && spSendTo.getSelectedItemPosition() > 0
                && spSendTo.getSelectedItem() != null)
            sendTo = spSendTo.getSelectedItem().toString();

        prefs.edit()
                .putString("callsign", callsign)
                .putString("send_to", sendTo)
                .putInt("dwell_ms", intOf(etDwell, 500))
                .putInt("vox_leader_ms", intOf(etVoxLeader, 0))
                .putInt("pli_rate_s", intOf(etPliRate, 60))
                .putInt("afsk_tx_level_pct", etAfskLevel == null ? 50
                        : Math.max(1, Math.min(100, intOf(etAfskLevel, 50))))
                .putBoolean("tx_enable", cbTxEnable.isChecked())
                .putBoolean("rx_enable", cbRxEnable.isChecked())
                .putBoolean("mode_pli", cbSelfPli.isChecked())
                .putBoolean("mode_spots", cbSpots.isChecked())
                .putBoolean("mode_alerts", cbAlerts.isChecked())
                .putBoolean("mode_chat", cbChat.isChecked())
                .putBoolean("mode_shapes", cbShapes.isChecked())
                .putBoolean("mode_casevac", cbCasevac.isChecked())
                .putBoolean("c2_bridge", cbC2Bridge != null
                        ? cbC2Bridge.isChecked()
                        : prefs.getBoolean("c2_bridge", false))
                .putInt("tx_stream", spTxStream == null ? 0
                        : spTxStream.getSelectedItemPosition())
                .putInt("tx_output", spTxOutput == null
                        ? prefs.getInt("tx_output", 0)
                        : spTxOutput.getSelectedItemPosition())
                .putInt("modem_type", spModem == null ? 1
                        : modemPosToType(spModem.getSelectedItemPosition()))
                .putInt("mac_mode", spMacMode == null ? 0
                        : spMacMode.getSelectedItemPosition())
                .putInt("ring_guard_ms", etRingGuard == null
                        ? RingMac.DEFAULT_GUARD_MS
                        : Math.max(100, intOf(etRingGuard, RingMac.DEFAULT_GUARD_MS)))
                .putInt("ring_skip_ms", etRingSkip == null
                        ? RingMac.DEFAULT_SKIP_MS
                        : Math.max(200, intOf(etRingSkip, RingMac.DEFAULT_SKIP_MS)))
                .putInt("ring_max_turn_ms", etRingMaxTurn == null ? 0
                        : Math.max(0, intOf(etRingMaxTurn, 0)))
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
        if (tvStatus != null) {
            String bridge = bridgeOn() ? " \u00b7 BRIDGE" : "";
            tvStatus.setText((started
                    ? "HBC Radio \u2014 RUNNING as " + prefs.getString("callsign", "?")
                    : "HBC Radio \u2014 STOPPED") + bridge);
            // green while running, red while stopped
            tvStatus.setTextColor(started ? 0xFF00E676 : 0xFFFF5252);
        }
    }

    /**
     * The modem spinner displays OFDM, Mercury, AX.25 (in that order, OFDM
     * default) but the stored pref keeps the historic encoding
     * 0=AFSK 1=OFDM 2=Mercury so existing installs keep their selection.
     */
    private static int modemPosToType(int pos) {
        switch (pos) {
            case 0:  return 1;   // OFDM
            case 1:  return 2;   // Mercury
            default: return 0;   // AX.25/AFSK
        }
    }

    private static int modemTypeToPos(int type) {
        switch (type) {
            case 1:  return 0;   // OFDM
            case 2:  return 1;   // Mercury
            default: return 2;   // AX.25/AFSK
        }
    }

    /**
     * Help dialog for the Channel access section: recommended Ring/VOX
     * values for the reference radios (Baofeng UV-5R, Yaesu FT-65).
     */
    private void showMacHelp() {
        mainHandler.post(() -> {
            android.content.Context dlgCtx;
            try {
                dlgCtx = com.atakmap.android.maps.MapView.getMapView().getContext();
            } catch (Throwable t) {
                dlgCtx = null;
            }
            if (dlgCtx == null) dlgCtx = pluginContext;
            try {
                new android.app.AlertDialog.Builder(dlgCtx)
                        .setTitle(pluginContext.getString(R.string.hbc_mac_help_title))
                        .setMessage(pluginContext.getString(R.string.hbc_mac_help_text))
                        .setPositiveButton("Close", (d, w) -> d.dismiss())
                        .setCancelable(true)
                        .show();
            } catch (Throwable t) {
                toast("UV-5R/FT-65: Ring, Guard 1500, Skip 1200, Max turn 0, VOX Lead 250");
            }
        });
    }

    // ------------------------------------------------------------------
    // Radio lifecycle
    // ------------------------------------------------------------------
    /** 0 = AFSK1200, 1 = OFDM (COFDMTV), 2 = Mercury HF (FreeDV DATAC) */
    private int modemType() {
        return prefs.getInt("modem_type", 1);   // default OFDM
    }

    private String modemName() {
        switch (modemType()) {
            case 1:  return "OFDM";
            case 2:  return "Mercury HF";
            default: return "AFSK1200";
        }
    }

    private synchronized void startRadio() {
        if (started) return;
        try {
            boolean ringMode = prefs.getInt("mac_mode", 0) == 1;
            int ringGuard = prefs.getInt("ring_guard_ms", RingMac.DEFAULT_GUARD_MS);
            int ringSkip = prefs.getInt("ring_skip_ms", RingMac.DEFAULT_SKIP_MS);
            int ringMaxTurn = prefs.getInt("ring_max_turn_ms", 0);
            // fresh detailed session log (replaces any unsaved previous one)
            sessionLog = new SessionLog(
                    prefs.getString("callsign", ""), modemName(),
                    "build=" + BuildConfig.BUILD_STAMP
                    + " v" + BuildConfig.VERSION_NAME
                    + " dwell=" + prefs.getInt("dwell_ms", 500)
                    + "ms vox=" + prefs.getInt("vox_leader_ms", 0)
                    + "ms pliRate=" + prefs.getInt("pli_rate_s", 60)
                    + "s stream=" + prefs.getInt("tx_stream", 0)
                    + " out=" + (prefs.getInt("tx_output", 0) == 1
                            ? "usb-rts" : "speaker")
                    + " afskLevel=" + prefs.getInt("afsk_tx_level_pct", 50) + "%"
                    + " mac=" + (ringMode ? "ring guard=" + ringGuard
                            + "ms skip=" + ringSkip + "ms" : "csma")
                    + " bridge=" + (bridgeOn() ? "on" : "off")
                    + " sendTo=" + prefs.getString("send_to", "(broadcast)")
                    + " tx=" + prefs.getBoolean("tx_enable", true)
                    + " rx=" + prefs.getBoolean("rx_enable", true));
            switch (modemType()) {
                case 1:
                    ofdm = new OfdmModem(pluginContext, this);
                    ofdm.setVoxLeaderMs(prefs.getInt("vox_leader_ms", 0));
                    ofdm.setTxStreamIndex(prefs.getInt("tx_stream", 0));
                    ofdm.start();
                    break;
                case 2:
                    mercury = new MercuryModem(pluginContext, this);
                    mercury.setVoxLeaderMs(prefs.getInt("vox_leader_ms", 0));
                    mercury.setTxStreamIndex(prefs.getInt("tx_stream", 0));
                    mercury.start();
                    break;
                default:
                    modem = new AudioModem(pluginContext, this);
                    modem.setTxDwellMs(prefs.getInt("dwell_ms", 500));
                    modem.setVoxLeaderMs(prefs.getInt("vox_leader_ms", 0));
                    modem.setTxStreamIndex(prefs.getInt("tx_stream", 0));
                    modem.setTxLevelPercent(prefs.getInt("afsk_tx_level_pct", 50));
                    modem.start();
                    break;
            }

            // v0.25: TX output routing + optional Digirig RTS PTT. In USB
            // mode the radio is keyed electrically (no VOX attack/hang),
            // so Guard 300-500 ms and VOX Lead 0 become usable.
            int txOut = prefs.getInt("tx_output", 0);
            if (modem != null)   modem.setTxOutput(txOut);
            if (ofdm != null)    ofdm.setTxOutput(txOut);
            if (mercury != null) mercury.setTxOutput(txOut);
            if (txOut == 1) {
                registerUsbPermReceiver();
                engageUsbPtt();
            }

            // Mesh routing layer above the modem
            mesh = new MeshRouter(prefs.getString("callsign", ""),
                    new MeshRouter.Callbacks() {
                        @Override
                        public void onHbcPayload(String origin, byte[] hbc) {
                            handleHbcRx(origin, hbc);
                        }
                        @Override
                        public void transmitFrame(byte[] frame) {
                            txFrame(frame);
                        }
                        @Override
                        public void onStatus(String message) {
                            log(message);
                            if (message.startsWith("Mesh: route"))
                                mainHandler.post(HbcPlugin.this::refreshSendTo);
                        }
                        @Override
                        public void onHeardTransmitter(String transmitter) {
                            RingMac rm = ringMac;
                            if (rm != null) rm.onHeardTransmitter(transmitter);
                        }
                    });
            // Announces are automatic: our own traffic (PLI broadcasts etc.)
            // acts as the announce via passive route learning; a real mesh
            // announce only goes out as a keepalive after 10 quiet minutes.
            mesh.setAnnounceIntervalMin(10);
            // v0.23: ARQ pacing must match the MAC. Under the Ring MAC a
            // retry is pointless until at least one full rotation has
            // passed (the ACK needs the recipient's own turn to travel
            // back); under CSMA the classic 5 s + jitter stays.
            mesh.setRetryPolicy(tries -> {
                RingMac rm = ringMac;
                if (rm != null) {
                    long cycle = rm.measuredCycleMs();
                    long d = Math.max(8_000L, cycle + cycle / 4);
                    return Math.min(60_000L, d)
                            + (long) (Math.random() * 2000);
                }
                return 5_000L + (long) (Math.random() * 2000);
            });
            mesh.start();

            // Ring MAC: deterministic rotation replaces CSMA contention
            if (ringMode) {
                synchronized (ringPending) {
                    ringPending.clear();
                    lastQueuedPli = null;
                }
                ringMac = new RingMac(prefs.getString("callsign", ""), ringHooks());
                ringMac.setGuardMs(ringGuard);
                ringMac.setSkipMs(ringSkip);
                int autoTurn, perFrameMs;
                switch (modemType()) {
                    case 1:  autoTurn = 4400; perFrameMs = 1700; break;
                    case 2:  autoTurn = 6000; perFrameMs = 6000; break;
                    default: autoTurn = 6000; perFrameMs = 1100; break;
                }
                int effTurn = ringMaxTurn > 0 ? ringMaxTurn : autoTurn;
                // v0.23: frames per turn derive from the turn budget, so
                // raising Max turn (identically on all stations) buys more
                // batching instead of just a longer deadline. Defaults are
                // unchanged: OFDM 2, Mercury 1, AFSK 4.
                int framesPerTurn = Math.max(1, Math.min(4,
                        (effTurn - 500) / perFrameMs));
                ringMac.setMaxTurnMs(effTurn);
                ringMac.setMaxFramesPerTurn(framesPerTurn);
                ringFramesPerTurn = framesPerTurn;
                // keep the modem's burst batching aligned with the turn
                if (ofdm != null) ofdm.setMaxBatchFrames(framesPerTurn);
                else if (mercury != null) mercury.setMaxBatchFrames(framesPerTurn);
                else if (modem != null) modem.setMaxBatchFrames(framesPerTurn);
                sessionDebug("Ring: maxTurn=" + effTurn + " ms, framesPerTurn="
                        + framesPerTurn);
                ringMac.start();
            }

            CommsMapComponent.getInstance().registerPreSendProcessor(this);
            started = true;
            log("Radio link started (" + modemName() + ", mesh routing)");
        } catch (Throwable e) {
            Log.e(TAG, "start failed", e);
            log("Start failed: " + e);
            stopRadio();
        }
    }

    private synchronized void stopRadio() {
        if (modem != null || ofdm != null || mercury != null) {
            try {
                CommsMapComponent.getInstance().registerPreSendProcessor(null);
            } catch (Exception ignored) {}
            if (ringMac != null) { ringMac.stop(); ringMac = null; }
            synchronized (ringPending) {
                ringPending.clear();
                lastQueuedPli = null;
            }
            if (mesh != null)    { mesh.stop();    mesh = null; }
            if (modem != null)   { modem.stop();   modem = null; }
            if (ofdm != null)    { ofdm.stop();    ofdm = null; }
            if (mercury != null) { mercury.stop(); mercury = null; }
            if (usbPtt != null)  { usbPtt.close(); usbPtt = null; }
            unregisterUsbPermReceiver();
        }
        if (started) log("Radio link stopped");
        started = false;
        mainHandler.post(this::updateUiState);
    }

    /**
     * Hand a mesh frame toward the air. Under CSMA it goes straight to the
     * active modem's queue; under the Ring MAC it waits in ringPending
     * until this station's turn (RingMac releases it via releaseFrames).
     */
    private void txFrame(byte[] frame) {
        try {
            sessionDebug("TX mesh frame " + frame.length + " B: " + hex(frame));
            boolean isPli = pliBroadcastNext;
            pliBroadcastNext = false;
            if (ringMac != null) {
                boolean replaced = false;
                int depth;
                synchronized (ringPending) {
                    if (isPli) {
                        // a queued position is stale the moment a fresh one
                        // exists — never let PLIs stack up in the queue
                        if (lastQueuedPli != null
                                && ringPending.remove(lastQueuedPli))
                            replaced = true;
                        lastQueuedPli = frame;
                    }
                    ringPending.addLast(frame);
                    depth = ringPending.size();
                }
                if (replaced)
                    sessionDebug("Ring: replaced stale queued PLI");
                maybeWarnBacklog(depth);
                return;
            }
            sendFrameToModem(frame);
        } catch (Exception e) {
            log("TX error: " + e);
        }
    }

    /**
     * Rate-limited visibility into ring-queue congestion: one LOG line at
     * most every 30 s once more than 6 frames are waiting, with a drain
     * estimate from the measured rotation time (v0.23).
     */
    private void maybeWarnBacklog(int depth) {
        if (depth <= 6) return;
        long now = System.currentTimeMillis();
        if (now - lastQueueWarnMs < 30_000) return;
        lastQueueWarnMs = now;
        RingMac rm = ringMac;
        long drainS = 0;
        if (rm != null) {
            int perTurn = Math.max(1, ringFramesPerTurn);
            long turns = (depth + perTurn - 1) / perTurn;
            drainS = turns * rm.measuredCycleMs() / 1000;
        }
        log("Ring: TX queue " + depth + " frames"
                + (drainS > 0 ? " (~" + drainS + " s to drain)" : ""));
    }

    /** Push one mesh frame into whichever modem is active (dumb byte pipe). */
    private void sendFrameToModem(byte[] frame) {
        String myCall = prefs.getString("callsign", "");
        if (ofdm != null)
            ofdm.transmit(myCall, frame);
        else if (mercury != null)
            mercury.transmit(myCall, frame);
        else if (modem != null)
            modem.transmit(DEFAULT_DEST, myCall, new String[0], frame);
        // v0.23: the frame has left for the modem — arm the mesh ARQ's
        // retry clock now (it must never run while a Direct is still
        // waiting in the ring queue).
        MeshRouter ms = mesh;
        if (ms != null) ms.notifyTransmitted(frame);
    }

    // ------------------------------------------------------------------
    // v0.25 USB RTS PTT plumbing
    // ------------------------------------------------------------------
    /** Context that owns USB access + receiver registration. */
    private android.content.Context usbContext() {
        try {
            android.content.Context c = com.atakmap.android.maps.MapView
                    .getMapView().getContext();
            if (c != null) return c;
        } catch (Throwable ignored) {}
        return pluginContext;
    }

    /**
     * Open the Digirig's keyer and hand it to the active modem. Safe to
     * call repeatedly — no-ops once engaged. Called at Start, from the
     * USB-permission/attach receiver, and from the dead-keyer recovery.
     */
    private synchronized void engageUsbPtt() {
        if (usbPtt != null) return;
        if (prefs.getInt("tx_output", 0) != 1) return;
        if (modem == null && ofdm == null && mercury == null) return;
        usbPtt = UsbPtt.open(usbContext(), this::log,
                () -> mainHandler.post(this::recoverUsbPtt));
        if (usbPtt != null) {
            if (modem != null)   modem.setPtt(usbPtt);
            if (ofdm != null)    ofdm.setPtt(usbPtt);
            if (mercury != null) mercury.setPtt(usbPtt);
            // v0.26: USB TX rides the MEDIA stream for predictable level
            log("PTT: TX audio uses the Media volume on USB \u2014 "
                    + "set Media volume to max");
        }
    }

    /**
     * v0.27: a keying write failed mid-session (device dropped off the
     * bus or re-enumerated). Tear the old connection down and try a
     * fresh open — open() always releases the key line first, which
     * also clears a potentially stuck transmitter.
     */
    private synchronized void recoverUsbPtt() {
        if (usbPtt != null) {
            usbPtt.close();
            usbPtt = null;
            if (modem != null)   modem.setPtt(null);
            if (ofdm != null)    ofdm.setPtt(null);
            if (mercury != null) mercury.setPtt(null);
        }
        engageUsbPtt();
        if (usbPtt == null)
            log("PTT: keyer not recovered \u2014 replug the Digirig "
                    + "(PTT re-engages automatically on attach)");
    }

    /** Detach cleanup: drop the keyer so TX falls back cleanly. */
    private synchronized void disengageUsbPtt() {
        if (usbPtt == null) return;
        usbPtt.close();
        usbPtt = null;
        if (modem != null)   modem.setPtt(null);
        if (ofdm != null)    ofdm.setPtt(null);
        if (mercury != null) mercury.setPtt(null);
        log("PTT: Digirig detached \u2014 RTS PTT off (VOX/manual keying)");
    }

    /** CP210x serial (Digirig Mobile) or CM108-family (Digirig Lite). */
    private static boolean isPttDevice(android.content.Intent intent) {
        try {
            android.hardware.usb.UsbDevice d = intent.getParcelableExtra(
                    android.hardware.usb.UsbManager.EXTRA_DEVICE);
            if (d == null) return false;
            int vid = d.getVendorId();
            return vid == 0x10C4 || vid == 0x0D8C || vid == 0x0C76;
        } catch (Throwable t) {
            return false;
        }
    }

    /** USB topology changed: ask the active modem to re-open RX (v0.26). */
    private void rebindModemAudio(String why) {
        sessionDebug("RX rebind: " + why);
        AudioModem m = modem;
        OfdmModem o = ofdm;
        MercuryModem h = mercury;
        if (m != null) m.rebindAudio();
        if (o != null) o.rebindAudio();
        if (h != null) h.rebindAudio();
    }

    private void registerUsbPermReceiver() {
        if (usbPermReceiver != null) return;
        try {
            usbPermReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c,
                                      android.content.Intent intent) {
                    String action = intent.getAction();
                    if (android.hardware.usb.UsbManager
                            .ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                        // Hot-plug while running: re-open RX capture so it
                        // follows the new device (v0.26), and engage PTT
                        // when the attached device is a Digirig keyer.
                        rebindModemAudio("USB attached");
                        if (isPttDevice(intent)) {
                            log("PTT: Digirig attached \u2014 engaging");
                            mainHandler.post(HbcPlugin.this::engageUsbPtt);
                        }
                        return;
                    }
                    if (android.hardware.usb.UsbManager
                            .ACTION_USB_DEVICE_DETACHED.equals(action)) {
                        rebindModemAudio("USB detached");
                        if (isPttDevice(intent))
                            mainHandler.post(HbcPlugin.this::disengageUsbPtt);
                        return;
                    }
                    boolean granted = intent.getBooleanExtra(
                            android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED,
                            false);
                    if (granted) {
                        log("PTT: USB permission granted");
                        mainHandler.post(HbcPlugin.this::engageUsbPtt);
                    } else {
                        log("PTT: USB permission denied \u2014 RTS PTT stays off");
                    }
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter(UsbPtt.ACTION_USB_PERMISSION);
            f.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
            android.content.Context ctx = usbContext();
            if (android.os.Build.VERSION.SDK_INT >= 33)
                ctx.registerReceiver(usbPermReceiver, f,
                        android.content.Context.RECEIVER_NOT_EXPORTED);
            else
                ctx.registerReceiver(usbPermReceiver, f);
        } catch (Throwable t) {
            sessionDebug("PTT: permission receiver registration failed: " + t);
            usbPermReceiver = null;
        }
    }

    private void unregisterUsbPermReceiver() {
        if (usbPermReceiver == null) return;
        try {
            usbContext().unregisterReceiver(usbPermReceiver);
        } catch (Throwable ignored) {}
        usbPermReceiver = null;
    }

    // ------------------------------------------------------------------
    // v0.24 C2 Bridge: one-way RF -> LAN data diode
    // ------------------------------------------------------------------
    /** Live C2 Bridge state: checkbox when bound, saved pref otherwise. */
    private boolean bridgeOn() {
        CheckBox cb = cbC2Bridge;
        return cb != null ? cb.isChecked()
                : (prefs != null && prefs.getBoolean("c2_bridge", false));
    }

    /** Short human-readable row for the Push-to-RF dialog. */
    private static String pushLabel(CotEvent event) {
        String xml = event.toString();
        String cs = BridgePolicy.firstAttr(xml, "<contact", "callsign");
        if (cs == null)
            cs = BridgePolicy.firstAttr(xml, "<link", "parent_callsign");
        return event.getType()
                + (cs != null ? " '" + cs + "'" : " " + event.getUID());
    }

    /**
     * C2 Bridge: push one decoded radio event onto the normal ATAK
     * network outputs (TAK server / mesh SA) so LAN users see it. The
     * UID is remembered so the event can never echo back to RF through
     * our own PreSendProcessor, and forwarded PLIs are re-rendered
     * WITHOUT the chat endpoint — network users must not try to DM a
     * station that can never hear them through a one-way bridge.
     */
    private void forwardToNetwork(HbcDecoder.Decoded dec, CotEvent event) {
        try {
            String uid = event.getUID();
            if (uid != null)
                synchronized (bridgedUids) {
                    bridgedUids.put(uid, System.currentTimeMillis());
                    pruneOld(bridgedUids, 600_000);
                }
            CotEvent out = event;
            if (dec.mode == 1 && !dec.isSpot && !dec.suppressEndpoint) {
                dec.suppressEndpoint = true;
                CotEvent stripped = CotEvent.parse(dec.toXml());
                dec.suppressEndpoint = false;
                if (stripped != null && stripped.isValid())
                    out = stripped;
            }
            CotMapComponent.getExternalDispatcher().dispatch(out);
            log("Bridge: " + dec.summary() + " -> network");
        } catch (Throwable t) {
            sessionDebug("Bridge: forward failed: " + t);
        }
    }

    /** C2 Bridge: multi-select dialog over the blocked-event stash. */
    private void showPushRfDialog() {
        final java.util.List<BridgePolicy.PushStash.Entry<CotEvent>> entries =
                blockedForPush.list(System.currentTimeMillis());
        if (entries.isEmpty()) {
            toast(pluginContext.getString(R.string.hbc_push_rf_empty));
            return;
        }
        if (!started || mesh == null) {
            toast("Start the radio link first");
            return;
        }
        android.content.Context dlgCtx;
        try {
            dlgCtx = com.atakmap.android.maps.MapView.getMapView().getContext();
        } catch (Throwable t) {
            dlgCtx = pluginContext;
        }
        long now = System.currentTimeMillis();
        final String[] labels = new String[entries.size()];
        final boolean[] checked = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            long ageS = Math.max(0, (now - entries.get(i).atMs) / 1000);
            labels[i] = entries.get(i).label
                    + "  (" + (ageS / 60) + "m" + (ageS % 60) + "s ago)";
        }
        try {
            new android.app.AlertDialog.Builder(dlgCtx)
                    .setTitle(pluginContext.getString(R.string.hbc_push_rf_title))
                    .setMultiChoiceItems(labels, checked,
                            (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("Send", (d, w) -> {
                        int n = 0;
                        for (int i = 0; i < entries.size(); i++) {
                            if (!checked[i]) continue;
                            BridgePolicy.PushStash.Entry<CotEvent> e =
                                    blockedForPush.take(entries.get(i).uid,
                                            System.currentTimeMillis());
                            if (e != null && pushEventToRf(e.payload)) n++;
                        }
                        toast("Pushed " + n + " item" + (n == 1 ? "" : "s")
                                + " to RF");
                    })
                    .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                    .show();
        } catch (Throwable t) {
            toast("Push dialog failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * One-shot manual LAN -> RF push — the deliberate operator exception
     * to the diode. Encodes through the normal HBC path (same routing,
     * ring queue and batching as native traffic).
     */
    private boolean pushEventToRf(CotEvent event) {
        MeshRouter ms = mesh;
        if (ms == null) return false;
        try {
            HbcEncoder.Encoded enc = HbcEncoder.encode(event.toString(), true);
            if (enc.mode == 3 && enc.chatDestKind == 2
                    && !enc.chatRecipient.isEmpty()) {
                ms.sendDirect(meshDestFor(enc.chatRecipient), enc.bytes);
            } else {
                String sendTo = prefs.getString("send_to", "");
                if (!sendTo.isEmpty())
                    ms.sendDirect(sendTo, enc.bytes);
                else
                    ms.sendBroadcast(enc.bytes);
            }
            log("Bridge: manual push mode " + enc.mode + " ("
                    + enc.bytes.length + " B) " + event.getType() + " -> RF");
            if (enc.mode == 2 && ringMac != null)
                ringMac.flagEmergency();
            return true;
        } catch (HbcEncoder.HbcEncodeException e) {
            log("Bridge: push skip: " + e.getMessage());
            return false;
        } catch (Exception e) {
            log("Bridge: push error: " + e);
            return false;
        }
    }

    /** RingMac's window into the plugin: channel state, roster, TX queue. */
    private RingMac.Hooks ringHooks() {
        return new RingMac.Hooks() {
            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }
            @Override
            public boolean channelBusy() {
                AudioModem m = modem;
                OfdmModem o = ofdm;
                MercuryModem h = mercury;
                if (m != null) return m.isChannelBusy();
                if (o != null) return o.isChannelBusy();
                if (h != null) return h.isChannelBusy();
                return false;
            }
            @Override
            public boolean transmitting() {
                AudioModem m = modem;
                OfdmModem o = ofdm;
                MercuryModem h = mercury;
                if (m != null && m.isTransmitting()) return true;
                if (o != null && o.isTransmitting()) return true;
                return h != null && h.isTransmitting();
            }
            @Override
            public java.util.List<String> meshRoster() {
                MeshRouter ms = mesh;
                return ms != null ? ms.knownDestinations()
                                  : java.util.Collections.emptyList();
            }
            @Override
            public int pendingFrames() {
                synchronized (ringPending) {
                    return ringPending.size();
                }
            }
            @Override
            public int releaseFrames(int max) {
                int n = 0;
                while (n < max) {
                    byte[] f;
                    synchronized (ringPending) {
                        f = ringPending.pollFirst();
                    }
                    if (f == null) break;
                    sendFrameToModem(f);
                    n++;
                }
                return n;
            }
            @Override
            public void onStatus(String message) {
                log(message);
            }
            @Override
            public void onDebug(String message) {
                sessionDebug(message);
            }
        };
    }

    /** Debug-only session log entry (not shown in the Activity Log). */
    private void sessionDebug(String msg) {
        Log.d(TAG, msg);
        SessionLog sl = sessionLog;
        if (sl != null) sl.debug(msg);
    }

    private static String hex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    /**
     * After Stop Radio Link: offer to save the finished session's detailed
     * debug log into the device's Downloads folder.
     */
    private void promptSaveSessionLog(SessionLog finished) {
        if (finished == null) return;
        sessionLog = null;   // session is over either way
        mainHandler.post(() -> {
            android.content.Context dlgCtx;
            try {
                dlgCtx = com.atakmap.android.maps.MapView.getMapView().getContext();
            } catch (Throwable t) {
                dlgCtx = null;
            }
            if (dlgCtx == null) {
                // no UI context available: save unconditionally rather than
                // silently losing debugging data
                saveSessionLog(finished);
                return;
            }
            try {
                new android.app.AlertDialog.Builder(dlgCtx)
                        .setTitle("Save HBC session log?")
                        .setMessage("Save the detailed debug log of this radio "
                                + "session (" + finished.lineCount() + " entries) to\n"
                                + "Downloads/" + finished.fileName() + "?")
                        .setPositiveButton("Save", (d, w) -> saveSessionLog(finished))
                        .setNegativeButton("Discard", (d, w) -> d.dismiss())
                        .setCancelable(true)
                        .show();
            } catch (Throwable t) {
                saveSessionLog(finished);
            }
        });
    }

    private void saveSessionLog(SessionLog finished) {
        new Thread(() -> {
            try {
                String where = finished.saveToDownloads(
                        com.atakmap.android.maps.MapView.getMapView() != null
                                ? com.atakmap.android.maps.MapView.getMapView()
                                        .getContext()
                                : pluginContext);
                log("Session log saved: " + where);
                toast("HBC session log saved to " + where);
            } catch (Exception e) {
                Log.e(TAG, "session log save failed", e);
                toast("Session log save FAILED: " + e.getMessage());
            }
        }, "HBC-SessionLogSave").start();
    }

    // ------------------------------------------------------------------
    // Outbound: ATAK -> HBC -> AX.25 -> audio
    // ------------------------------------------------------------------
    @Override
    public void processCotEvent(CotEvent event, String[] toUIDs) {
        if (!started || mesh == null
                || (modem == null && ofdm == null && mercury == null)
                || !prefs.getBoolean("tx_enable", true))
            return;
        try {
            String type = event.getType();
            log("TX candidate: " + type + " uid " + event.getUID());

            // v1.6: ATAK's automatic chat receipts for an HBC-delivered DM.
            // The receipt's UID equals the messageId of the message being
            // acknowledged; convert it into a compact Mode 0 ack frame.
            if (type.equals("b-t-f-d") || type.equals("b-t-f-r")) {
                PendingAck pa = rxDmAcks.get(event.getUID());
                if (pa != null) {
                    int kind = type.equals("b-t-f-r")
                            ? HbcEncoder.ACK_READ : HbcEncoder.ACK_DELIVERED;
                    HbcEncoder.Encoded ack = HbcEncoder.encodeAck(
                            prefs.getString("callsign", ""),
                            pa.senderCallsign, kind, pa.tag);
                    // Route the chat receipt back to the DM sender WITHOUT
                    // ARQ (v0.23): a lost checkmark is tolerable, while
                    // retry-storming every delivered/read receipt was a
                    // major source of ring-queue buildup.
                    mesh.sendDirectUnacked(meshDestFor(pa.senderCallsign), ack.bytes);
                    log("Queued TX mode 0 ack (" + type + ") -> "
                            + pa.senderCallsign + " tag 0x"
                            + String.format("%04X", pa.tag));
                }
                return;   // receipts are never encoded as normal messages
            }

            // Self PLI vs placed marker: the CoT type alone cannot tell a
            // hostile ground MARKER (a-h-G...) apart from a hostile STATION's
            // position report — only the UID can. The station's own PLI
            // carries the ATAK device/self-marker UID; placed markers get
            // random UUIDs. Previously every a-f/h/n-G event was treated as
            // a PLI, so placed friendly/hostile/neutral markers were eaten
            // by the PLI rate limiter and never transmitted at all.
            boolean groundAtom = type.startsWith("a-f-G")
                    || type.startsWith("a-h-G") || type.startsWith("a-n-G");
            boolean selfPli = false;
            if (groundAtom) {
                String su = selfUid();
                selfPli = su != null ? su.equals(event.getUID())
                        // self marker not available yet: fall back to the
                        // old heuristic for friendly device-uid events only
                        : (type.startsWith("a-f-G")
                           && event.getUID() != null
                           && event.getUID().startsWith("ANDROID-"));
            }

            // v0.24 C2 Bridge diode: while bridging, never AUTO-relay
            // network-origin events to the radio. Radio-origin echoes
            // (events we ourselves forwarded to the LAN) are dropped
            // outright; anything else not authored on this device is
            // stashed for the operator's "Push to RF" dialog.
            if (bridgeOn()) {
                String evUid = event.getUID();
                boolean recentlyBridged;
                synchronized (bridgedUids) {
                    pruneOld(bridgedUids, 600_000);
                    recentlyBridged = evUid != null
                            && bridgedUids.containsKey(evUid);
                }
                String verdict = BridgePolicy.txVerdict(selfPli, evUid,
                        BridgePolicy.extractAuthorUid(event.toString()),
                        selfUid(), recentlyBridged);
                if ("network-origin".equals(verdict)) {
                    blockedForPush.put(evUid, pushLabel(event),
                            System.currentTimeMillis(), event);
                    log("Bridge: blocked " + type + " " + evUid
                            + " (network-origin) \u2014 use Push to RF");
                    return;
                }
                if (verdict != null) {
                    sessionDebug("Bridge: dropped " + type + " " + evUid
                            + " (" + verdict + ")");
                    return;
                }
            }

            if (!modeEnabled(type, selfPli)) {
                log("TX skip: type " + type + " disabled in settings");
                return;
            }

            // PLI rate limit — applies ONLY to the station's own position
            // reports, never to placed markers. Under the Ring MAC the
            // interval is floored by roster size so N stations' PLIs use
            // at most ~25% of the rotation's airtime and chat/ACK traffic
            // keeps room to drain (v0.23: 8 s/station; 13 s on Mercury —
            // see the PLI recommendation table in the docs).
            if (selfPli) {
                long minInterval = prefs.getInt("pli_rate_s", 60) * 1000L;
                RingMac rm = ringMac;
                if (rm != null) {
                    long perStation = modemType() == 2 ? 13_000L : 8_000L;
                    long floor = rm.rosterSize() * perStation;
                    if (floor > minInterval) {
                        minInterval = floor;
                        sessionDebug("Ring: PLI interval floored to "
                                + (floor / 1000) + " s (" + rm.rosterSize()
                                + " stations)");
                    }
                }
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

            HbcEncoder.Encoded enc = HbcEncoder.encode(event.toString(), !selfPli);

            if (enc.mode == 3 && enc.chatDestKind == 2 && !enc.chatRecipient.isEmpty()) {
                // GeoChat DM: automatically route direct to the recipient
                // (translated to the station's ham callsign when known)
                mesh.sendDirect(meshDestFor(enc.chatRecipient), enc.bytes);
            } else {
                String sendTo = prefs.getString("send_to", "");
                if (!sendTo.isEmpty())
                    mesh.sendDirect(sendTo, enc.bytes);
                else {
                    // tag the frame about to reach txFrame (same call
                    // stack) so the ring queue can replace a stale PLI
                    pliBroadcastNext = selfPli;
                    mesh.sendBroadcast(enc.bytes);
                }
            }
            log("Queued TX mode " + enc.mode + " (" + enc.bytes.length + " B) " + type);

            // 911 alerts may preempt the ring's turn order: they transmit
            // in the next inter-turn idle window instead of waiting a full
            // rotation (the single allowed contention exception).
            if (enc.mode == 2 && ringMac != null)
                ringMac.flagEmergency();

            // v1.6: remember outgoing DM tags so incoming Mode 0 acks can be
            // mapped back to the original messageId (delivered/read checkmark).
            if (enc.mode == 3 && enc.chatMsgTag >= 0 && !enc.chatMessageId.isEmpty()) {
                sentDmMessageIds.put(enc.chatMsgTag, enc.chatMessageId);
                capSize(sentDmMessageIds);
            }
        } catch (HbcEncoder.HbcEncodeException e) {
            log("TX skip: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "processCotEvent failed", e);
            log("TX error: " + e);
        }
    }

    private boolean modeEnabled(String type, boolean selfPli) {
        if (selfPli)
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
                //  - ALL atom types, now including placed friendly/hostile/
                //    neutral ground units (a-f-G/a-h-G/a-n-G markers that are
                //    NOT this station's own PLI), a-u-G, aircraft, ...
                //  - b-m-p-* point markers: spot map (b-m-p-s-m), waypoints
                //    (b-m-p-w), command posts (b-m-p-c-cp), etc.
                if (type.startsWith("a-") || type.startsWith("b-m-p"))
                    return cbOrPref(cbSpots, "mode_spots");
                return false;
        }
    }

    /** UID of this station's own position marker (ATAK device uid), or null. */
    private String selfUid() {
        try {
            com.atakmap.android.maps.MapView mv =
                    com.atakmap.android.maps.MapView.getMapView();
            if (mv != null) {
                if (mv.getSelfMarker() != null
                        && mv.getSelfMarker().getUID() != null)
                    return mv.getSelfMarker().getUID();
                return com.atakmap.android.maps.MapView.getDeviceUid();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean cbOrPref(CheckBox cb, String key) {
        return cb != null ? cb.isChecked() : prefs.getBoolean(key, true);
    }

    // ------------------------------------------------------------------
    // Inbound: audio -> mesh -> HBC -> ATAK
    // ------------------------------------------------------------------
    @Override
    public void onFrame(byte[] ax25Frame) {
        if (!started || !prefs.getBoolean("rx_enable", true))
            return;
        try {
            // dedup identical frames heard twice (echoes / dual demod)
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
            sessionDebug("RX AX.25 " + ax25Frame.length + " B src=" + p.source
                    + " dst=" + p.destination + ": " + hex(ax25Frame));
            if (payload == null || payload.length == 0)
                return;

            // ignore our own transmissions heard back (loopback / echo):
            // the AX.25 source address is our configured ham callsign
            String myCall = prefs.getString("callsign", "");
            String srcBase = p.source == null ? ""
                    : (p.source.contains("-")
                            ? p.source.substring(0, p.source.indexOf('-'))
                            : p.source);
            if (srcBase.equalsIgnoreCase(myCall)) {
                sessionDebug("RX dropped: own transmission echoed back");
                return;
            }

            if (mesh != null)
                mesh.onRadioFrame(payload);
        } catch (Exception e) {
            sessionDebug("RX frame not mesh: " + e.getMessage());
        }
    }

    @Override
    public void onStatus(String message) {
        log(message);
    }

    // ------------------------------------------------------------------
    // Inbound (OFDM / Mercury): audio -> mesh -> HBC -> ATAK
    // ------------------------------------------------------------------
    @Override
    public void onPayload(String callsign, byte[] meshFrame) {
        if (!started || !prefs.getBoolean("rx_enable", true))
            return;
        try {
            // dedup identical frames (repeats / echoes)
            int hash = Arrays.hashCode(meshFrame) * 31 + callsign.hashCode();
            long now = System.currentTimeMillis();
            Long seen = recentRx.get(hash);
            if (seen != null && now - seen < 30000)
                return;
            recentRx.put(hash, now);
            pruneOld(recentRx, 300000);

            sessionDebug("RX mesh frame " + meshFrame.length + " B (meta call '"
                    + callsign + "'): " + hex(meshFrame));
            String myCall = prefs.getString("callsign", "");
            if (!callsign.isEmpty() && callsign.equalsIgnoreCase(myCall)) {
                sessionDebug("RX dropped: own transmission heard back");
                return; // our own transmission heard back
            }

            if (mesh != null)
                mesh.onRadioFrame(meshFrame);
        } catch (Exception e) {
            sessionDebug("RX payload not mesh: " + e.getMessage());
        }
    }

    /**
     * A mesh Broadcast/Direct payload delivered to this station: run the
     * HBC decode pipeline and inject the CoT into ATAK.
     */
    private void handleHbcRx(String origin, byte[] hbcPayload) {
        try {
            HbcDecoder.Decoded dec = HbcDecoder.decode(hbcPayload);
            sessionDebug("RX HBC payload " + hbcPayload.length + " B from '"
                    + origin + "': " + hex(hbcPayload) + " -> " + dec.summary());

            String myCall = prefs.getString("callsign", "");
            if (dec.callsign.equalsIgnoreCase(myCall))
                return;   // our own transmission echoed back
            try {
                String atakCallsign = com.atakmap.android.maps.MapView
                        .getMapView().getDeviceCallsign();
                if (atakCallsign != null
                        && dec.callsign.equalsIgnoreCase(atakCallsign))
                    return;
            } catch (Exception ignored) {}

            // learn ATAK callsign -> ham (mesh) callsign for DM/ack routing
            if (!dec.callsign.isEmpty() && origin != null && !origin.isEmpty()
                    && !dec.callsign.equalsIgnoreCase(origin)) {
                atakToHamCall.put(dec.callsign.toUpperCase(Locale.US),
                        origin.toUpperCase(Locale.US));
                capSize(atakToHamCall);
            }

            if (dec.mode == 0) {
                handleAckRx(dec);
                return;
            }
            if (!resolveDirectMessage(dec))
                return;

            // avoid a duplicated contacts-list row when the station is also
            // reachable over a normal ATAK network link (WiFi / TAK server)
            if (dec.mode == 1 && !dec.isSpot) {
                dec.suppressEndpoint = hasLiveNetworkContact(dec);
                removeGhostChatContact(dec.callsign);
            }

            String xml = dec.toXml();
            sessionDebug("RX reconstructed CoT:\n" + xml);
            CotEvent event = CotEvent.parse(xml);
            if (event == null || !event.isValid()) {
                log("RX decode produced invalid CoT (" + dec.summary() + ")");
                return;
            }
            CotMapComponent.getInternalDispatcher().dispatch(event);
            registerIncomingDm(dec);
            log("RX " + origin + " " + dec.summary());
            logDecode(origin
                    + "\n  " + dec.summary()
                    + "\n  " + hbcPayload.length + " B payload");

            // v0.24 C2 Bridge: re-publish the decoded radio event onto the
            // normal ATAK network outputs so LAN users see it.
            if (bridgeOn()
                    && BridgePolicy.shouldForwardToLan(dec.mode, dec.chatDestKind))
                forwardToNetwork(dec, event);
        } catch (Exception e) {
            sessionDebug("RX payload not HBC: " + e.getMessage());
        }
    }

    /**
     * Remove the ghost chat contact "BAO.F.HBC.HBC-<CALL>" that older
     * builds caused ATAK to fabricate (chat remarks source used the
     * BAO.F.HBC. prefix, which ATAK's parser treats as a literal sender
     * uid). It duplicated the station in the contacts list and split the
     * DM conversation across two windows. New chats use the BAO.F.ATAK.
     * prefix and resolve to the real HBC-<CALL> contact; this cleans up
     * leftovers on devices that chatted with the old builds.
     */
    private void removeGhostChatContact(String callsign) {
        try {
            String ghost = "BAO.F.HBC.HBC-" + callsign.toUpperCase(Locale.US);
            com.atakmap.android.contact.Contacts cts =
                    com.atakmap.android.contact.Contacts.getInstance();
            if (cts.getContactByUuid(ghost) != null) {
                cts.removeContactByUuid(ghost);
                log("Removed stale chat contact " + ghost);
            }
        } catch (Throwable ignored) {
            // Contacts API unavailable — harmless, contact just lingers
        }
    }

    /**
     * True when ATAK already lists a live contact with this station's
     * callsign from a normal network channel (WiFi / TAK server). Injecting
     * our mesh endpoint as well would show the callsign twice in the
     * contacts list, so the caller suppresses the endpoint in that case.
     */
    private boolean hasLiveNetworkContact(HbcDecoder.Decoded dec) {
        try {
            String want1 = dec.name == null ? "" : dec.name.trim();
            String want2 = dec.callsign == null ? "" : dec.callsign.trim();
            java.util.List<com.atakmap.android.contact.Contact> all =
                    com.atakmap.android.contact.Contacts.getInstance()
                            .getAllContacts();
            if (all == null) return false;
            for (com.atakmap.android.contact.Contact c : all) {
                if (c == null) continue;
                String uid = c.getUID();
                String nm = c.getName();
                if (uid == null || nm == null) continue;
                // skip our own injections AND GeoChat-derived entries for
                // them (e.g. BAO.F.HBC.HBC-KEYSTONE created after a DM) —
                // only a real network contact should suppress the endpoint
                if (uid.contains("HBC-")) continue;
                if ((!want1.isEmpty() && nm.equalsIgnoreCase(want1))
                        || (!want2.isEmpty() && nm.equalsIgnoreCase(want2))) {
                    sessionDebug("RX PLI: '" + nm + "' already a network "
                            + "contact (uid " + uid + ") - endpoint suppressed");
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Contacts API unavailable: keep the endpoint (default behavior)
        }
        return false;
    }

    /**
     * After a DM addressed to this station has been injected, remember its
     * injected messageId so ATAK's automatic b-t-f-d/b-t-f-r receipts for it
     * can be converted into Mode 0 acks back to the sender (v1.6).
     */
    private void registerIncomingDm(HbcDecoder.Decoded dec) {
        if (dec.mode == 3 && dec.chatDestKind == 2
                && !dec.chatInjectedMessageId.isEmpty()) {
            rxDmAcks.put(dec.chatInjectedMessageId,
                    new PendingAck(dec.chatMsgTag, dec.callsign));
            capSize(rxDmAcks);
        }
    }

    /**
     * Mode 0 Ack received (v1.6): if addressed to this station, map the
     * 16-bit tag back to the original outgoing DM's messageId and inject a
     * b-t-f-d/b-t-f-r receipt CoT so ATAK shows the delivered/read checkmark.
     */
    private void handleAckRx(HbcDecoder.Decoded dec) {
        String myCall = prefs.getString("callsign", "");
        String atakCallsign = null;
        try {
            com.atakmap.android.maps.MapView mv =
                    com.atakmap.android.maps.MapView.getMapView();
            if (mv != null) atakCallsign = mv.getDeviceCallsign();
        } catch (Exception ignored) {}
        boolean forUs = callsignMatches(dec.ackRecipient, myCall)
                || (atakCallsign != null && callsignMatches(dec.ackRecipient, atakCallsign));
        if (!forUs) {
            log("RX ack for '" + dec.ackRecipient + "' — not this station, ignored");
            return;
        }
        String messageId = sentDmMessageIds.get(dec.chatMsgTag);
        if (messageId == null) {
            log("RX ack tag 0x" + String.format("%04X", dec.chatMsgTag)
                    + " — no matching sent DM, ignored");
            return;
        }
        dec.ackMessageId = messageId;
        String xml = dec.toXml();
        CotEvent event = CotEvent.parse(xml);
        if (event == null || !event.isValid()) {
            log("RX ack produced invalid CoT (" + dec.summary() + ")");
            return;
        }
        CotMapComponent.getInternalDispatcher().dispatch(event);
        log("RX " + dec.summary() + " -> receipt injected");
        logDecode(dec.callsign + " ack " + (dec.ackKind == 1 ? "READ" : "DELIVERED")
                + "\n  msg " + messageId);
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

        boolean forUs = callsignMatches(recipient, myCall)
                || (atakCallsign != null && callsignMatches(recipient, atakCallsign));
        if (!forUs) {
            log("RX chat: DM for '" + recipient + "' — not this station, ignored");
            return false;
        }
        if (deviceUid != null && !deviceUid.isEmpty())
            dec.chatRecipientUidOverride = deviceUid;
        return true;
    }

    /**
     * True when `recipient` names `local`, tolerating the HBC wire-format
     * truncation: the Mode 1 name field holds 7 chars and the ITA2 callsign
     * fields 8, so "KEYSTON"/"KEYSTONE" must still match a local callsign
     * "KEYSTONE1". A recipient long enough to have filled a field (≥ 7
     * chars) matches when it is a prefix of the local callsign; shorter
     * recipients must match exactly.
     */
    private static boolean callsignMatches(String recipient, String local) {
        if (recipient == null || local == null) return false;
        String r = recipient.trim().toUpperCase(Locale.US);
        String l = local.trim().toUpperCase(Locale.US);
        if (r.isEmpty() || l.isEmpty()) return false;
        if (r.equals(l)) return true;
        return r.length() >= 7 && l.startsWith(r);
    }

    /**
     * Translate a DM/ack destination (an ATAK callsign) into the station's
     * ham callsign for mesh routing, when learned from received traffic.
     * Falls back to the input unchanged (mesh then broadcasts).
     */
    private String meshDestFor(String callsign) {
        if (callsign == null || callsign.isEmpty()) return callsign;
        String key = callsign.trim().toUpperCase(Locale.US);
        String ham = atakToHamCall.get(key);
        if (ham != null) return ham;
        if (key.length() >= 7)
            for (Map.Entry<String, String> e : atakToHamCall.entrySet())
                if (e.getKey().startsWith(key)) return e.getValue();
        return callsign;
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
        SessionLog sl = sessionLog;
        if (sl != null) sl.info(msg);
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
