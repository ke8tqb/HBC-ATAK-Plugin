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
import com.atakmap.android.hbc.MercuryModem;
import com.atakmap.android.hbc.MeshRouter;
import com.atakmap.android.hbc.OfdmModem;
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
    ToolbarItem radioStatusItem;
    Pane pane;

    private SharedPreferences prefs;
    private AudioModem modem;
    private OfdmModem ofdm;
    private MercuryModem mercury;
    private MeshRouter mesh;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI
    private EditText etCallsign, etDwell, etVoxLeader, etPliRate, etAfskLevel;
    private android.widget.Spinner spTxStream, spModem, spSendTo;
    private CheckBox cbTxEnable, cbRxEnable, cbSelfPli, cbChat, cbAlerts, cbShapes,
            cbCasevac, cbSpots;
    private Button btnStartStop;
    private TextView tvStatus, tvLog, tvPliCountdown;
    private boolean pliTickerRunning = false;
    private View tabAudio, tabOptions, tabDecodes;
    private Button btnTabAudio, btnTabOptions, btnTabDecodes, btnDecodesClear;
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

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        rasterize(pluginContext.getResources()
                                .getDrawable(R.drawable.ic_launcher), 192),
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
     * VectorDrawable passed straight in comes out as a blank square — the
     * same reason renderRadioIcon() rasterizes the radio glyphs.
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
        updateRadioStatusIcon();
    }

    @Override
    public void onStop() {
        stopRadio();
        if (uiService == null)
            return;
        if (radioStatusItem != null) {
            uiService.removeToolbarItem(radioStatusItem);
            radioStatusItem = null;
        }
        uiService.removeToolbarItem(toolbarItem);
    }

    /**
     * Toolbar radio icon reflecting the radio-link state: electric green
     * while the audio modem is running, gray when stopped. Follows the same
     * `started` flag as the Start/Stop Radio Link button, so the two can
     * never disagree.
     *
     * Tap toggles the modem on/off (the plugin pane stays reachable via
     * the main HBC toolbar icon). ATAK core consumes long-presses to show
     * the item tooltip, so the tooltip title carries the modem state
     * ("HBC Radio: RUNNING/STOPPED") instead of a long-press action.
     *
     * ToolbarItems are immutable, so the item is rebuilt on state change —
     * a fixed identifier keeps ATAK treating it as the same tool, so a
     * user-dragged toolbar placement survives the swap. The vector icon is
     * rasterized at high resolution so it stays as sharp as the stock icons.
     */
    private static final String RADIO_STATUS_ID = "hbc-radio-status";

    private void updateRadioStatusIcon() {
        if (uiService == null || pluginContext == null)
            return;
        try {
            if (radioStatusItem != null)
                uiService.removeToolbarItem(radioStatusItem);
            String title = pluginContext.getString(started
                    ? R.string.hbc_radio_status_on
                    : R.string.hbc_radio_status_off);
            radioStatusItem = new ToolbarItem.Builder(
                    title,
                    MarshalManager.marshal(renderRadioIcon(),
                            android.graphics.drawable.Drawable.class,
                            gov.tak.api.commons.graphics.Bitmap.class))
                    .setIdentifier(RADIO_STATUS_ID)
                    .setListener(new ToolbarItemAdapter() {
                        @Override
                        public void onClick(ToolbarItem item) {
                            toggleRadioFromIcon();   // tap toggles the modem
                        }
                    })
                    .build();
            uiService.addToolbarItem(radioStatusItem);
        } catch (Exception e) {
            Log.d(TAG, "radio status icon update failed: " + e.getMessage());
        }
    }

    /**
     * Rasterize the state-colored vector at 192 px so it stays sharp.
     * When the modem is running, a soft light-green radial glow is painted
     * behind the icon ("backlit" look).
     */
    private android.graphics.drawable.Drawable renderRadioIcon() {
        android.graphics.drawable.Drawable vector = pluginContext.getResources()
                .getDrawable(started ? R.drawable.ic_radio_on
                                     : R.drawable.ic_radio_off);
        int px = 192;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        if (started) {
            // backlit glow: light green, bright at center, fading to clear
            float c = px / 2f;
            android.graphics.Paint glow = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            glow.setShader(new android.graphics.RadialGradient(
                    c, c, c,
                    new int[]{0xB4A8FFB0, 0x6E7CFF8C, 0x00000000},
                    new float[]{0f, 0.55f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(c, c, c, glow);
        }
        // inset the glyph slightly so the glow forms a visible halo
        int inset = px / 8;
        vector.setBounds(inset, inset, px - inset, px - inset);
        vector.draw(canvas);
        return new android.graphics.drawable.BitmapDrawable(
                pluginContext.getResources(), bmp);
    }

    /** Tap on the toolbar radio icon: start/stop the modem. */
    private void toggleRadioFromIcon() {
        if (started) {
            SessionLog finished = sessionLog;
            stopRadio();
            promptSaveSessionLog(finished);
            toast("HBC radio link stopped");
        } else {
            if (prefs == null || prefs.getString("callsign", "").isEmpty()) {
                toast("Set your callsign in HBC settings first");
                showPane();
                return;
            }
            startRadio();
            toast(started ? "HBC radio link started"
                          : "HBC radio link failed to start — see log");
        }
        mainHandler.post(this::updateUiState);
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
        refreshSendTo();
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    private void bindViews(View v) {
        etCallsign  = v.findViewById(R.id.hbc_callsign);
        etDwell     = v.findViewById(R.id.hbc_dwell);
        etVoxLeader = v.findViewById(R.id.hbc_vox_leader);
        etPliRate   = v.findViewById(R.id.hbc_pli_rate);
        etAfskLevel = v.findViewById(R.id.hbc_afsk_level);
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
        tvPliCountdown = v.findViewById(R.id.hbc_pli_countdown);
        tvLog       = v.findViewById(R.id.hbc_log);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        startPliTicker();

        // tabs: Audio Setup / Options / Decodes
        tabAudio      = v.findViewById(R.id.hbc_tab_audio);
        tabOptions    = v.findViewById(R.id.hbc_tab_options);
        tabDecodes    = v.findViewById(R.id.hbc_tab_decodes);
        btnTabAudio   = v.findViewById(R.id.hbc_tab_btn_audio);
        btnTabOptions = v.findViewById(R.id.hbc_tab_btn_options);
        btnTabDecodes = v.findViewById(R.id.hbc_tab_btn_decodes);
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

        spSendTo = v.findViewById(R.id.hbc_send_to);
        refreshSendTo();

        btnTabAudio.setOnClickListener(view -> selectTab(0));
        btnTabOptions.setOnClickListener(view -> selectTab(1));
        btnTabDecodes.setOnClickListener(view -> selectTab(2));
        btnDecodesClear.setOnClickListener(view -> {
            decodeCount = 0;
            tvDecodes.setText("");
            tvDecodesCount.setText(pluginContext.getString(R.string.hbc_decodes_none));
        });
        selectTab(0);

        loadPrefs();

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

        updateUiState();
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
                mainHandler.postDelayed(this, 1000);
            }
        });
    }

    private void updatePliCountdown() {
        if (tvPliCountdown == null) return;
        String text;
        if (!started) {
            text = "Next PLI: \u2014 (radio off)";
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

    /** 0 = Audio Setup, 1 = Options, 2 = Decodes */
    private void selectTab(int tab) {
        if (tabAudio == null) return;
        tabAudio.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        tabOptions.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        tabDecodes.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
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
    /** 0 = AFSK1200, 1 = OFDM (COFDMTV), 2 = Mercury HF (FreeDV DATAC) */
    private int modemType() {
        return prefs.getInt("modem_type", 0);
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
            // fresh detailed session log (replaces any unsaved previous one)
            sessionLog = new SessionLog(
                    prefs.getString("callsign", ""), modemName(),
                    "dwell=" + prefs.getInt("dwell_ms", 500)
                    + "ms vox=" + prefs.getInt("vox_leader_ms", 0)
                    + "ms pliRate=" + prefs.getInt("pli_rate_s", 60)
                    + "s stream=" + prefs.getInt("tx_stream", 0)
                    + " afskLevel=" + prefs.getInt("afsk_tx_level_pct", 50) + "%"
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
                    });
            // Announces are automatic: our own traffic (PLI broadcasts etc.)
            // acts as the announce via passive route learning; a real mesh
            // announce only goes out as a keepalive after 10 quiet minutes.
            mesh.setAnnounceIntervalMin(10);
            mesh.start();
            CommsMapComponent.getInstance().registerPreSendProcessor(this);
            started = true;
            mainHandler.post(this::updateRadioStatusIcon);
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
            if (mesh != null)    { mesh.stop();    mesh = null; }
            if (modem != null)   { modem.stop();   modem = null; }
            if (ofdm != null)    { ofdm.stop();    ofdm = null; }
            if (mercury != null) { mercury.stop(); mercury = null; }
        }
        if (started) log("Radio link stopped");
        started = false;
        mainHandler.post(this::updateRadioStatusIcon);
    }

    /** Hand a mesh frame to whichever modem is active (dumb byte pipe). */
    private void txFrame(byte[] frame) {
        try {
            String myCall = prefs.getString("callsign", "");
            sessionDebug("TX mesh frame " + frame.length + " B: " + hex(frame));
            if (ofdm != null)
                ofdm.transmit(myCall, frame);
            else if (mercury != null)
                mercury.transmit(myCall, frame);
            else if (modem != null)
                modem.transmit(DEFAULT_DEST, myCall, new String[0], frame);
        } catch (Exception e) {
            log("TX error: " + e);
        }
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
                    // route the chat receipt straight back to the DM sender
                    mesh.sendDirect(meshDestFor(pa.senderCallsign), ack.bytes);
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

            if (!modeEnabled(type, selfPli)) {
                log("TX skip: type " + type + " disabled in settings");
                return;
            }

            // PLI rate limit — applies ONLY to the station's own position
            // reports, never to placed markers
            if (selfPli) {
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

            HbcEncoder.Encoded enc = HbcEncoder.encode(event.toString(), !selfPli);

            if (enc.mode == 3 && enc.chatDestKind == 2 && !enc.chatRecipient.isEmpty()) {
                // GeoChat DM: automatically route direct to the recipient
                // (translated to the station's ham callsign when known)
                mesh.sendDirect(meshDestFor(enc.chatRecipient), enc.bytes);
            } else {
                String sendTo = prefs.getString("send_to", "");
                if (!sendTo.isEmpty())
                    mesh.sendDirect(sendTo, enc.bytes);
                else
                    mesh.sendBroadcast(enc.bytes);
            }
            log("Queued TX mode " + enc.mode + " (" + enc.bytes.length + " B) " + type);

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
