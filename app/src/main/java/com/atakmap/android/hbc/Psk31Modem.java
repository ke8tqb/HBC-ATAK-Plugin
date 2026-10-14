package com.atakmap.android.hbc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * BPSK31 audio modem — 31.25 baud binary PSK with ITU-R M.2034 Varicode,
 * 1500 Hz carrier, 8000 Hz 16-bit mono. The narrowest and slowest of the
 * plugin's modems (~31 Hz occupied bandwidth): drops into any SSB voice
 * channel alongside human PSK31 operators and survives SNRs where even
 * the OFDM modes fail, at the cost of VERY long bursts (a PLI is ~15 s
 * on air, a Mode 6 marker 25-35 s).
 *
 * Binary HBC/mesh frames ride inside {@link Psk31Armor} text frames
 * ('~' + Base64(payload+CRC-16) + '~') because Varicode itself carries
 * no error detection. Anything on frequency that is not an armored
 * frame (human PSK31 chatter) is ignored by the de-framer.
 *
 * Airtime honesty is critical for the MAC layer: {@link #airtimeMs}
 * exposes the per-frame estimate that HbcPlugin uses to size Ring MAC
 * turn budgets and CSMA waits (a 6 s turn deadline sized for AFSK would
 * advance the ring mid-burst and schedule a second station on top of a
 * PSK31 transmission still in progress).
 */
public class Psk31Modem {

    public static final int SAMPLE_RATE = Psk31.SAMPLE_RATE; // 8000
    private static final double CARRIER_HZ = 1500.0;
    private static final int LEAD_SILENCE_MS = 100;
    private static final int TAIL_SILENCE_MS = 100;
    private static final int INTER_BURST_GAP_MS = 400;  // receiver squelch re-arm
    private static final double TX_AMPLITUDE = 0.5;      // of the encoder's 15000 FS

    /** Longest mesh frame we accept (keeps bursts under ~80 s even worst-case). */
    public static final int MAX_FRAME_BYTES = 180;

    private final Context context;
    private final OfdmModem.PayloadListener listener;

    private Thread txThread, rxThread;
    private volatile boolean running = false;
    private volatile boolean transmitting = false;

    private volatile int voxLeaderMs = 0;
    private volatile int txStream = AudioManager.STREAM_ALARM;
    private volatile int maxBatchFrames = 1;   // PSK31 bursts are long: 1 frame/turn

    private static final class TxItem {
        final byte[] payload;
        TxItem(byte[] p) { payload = p; }
    }

    private final Deque<TxItem> txQueue = new ArrayDeque<>();
    private final Object txLock = new Object();
    private final CsmaSense csma = new CsmaSense();

    private final List<android.media.audiofx.AudioEffect> rxEffects = new ArrayList<>();

    public Psk31Modem(Context context, OfdmModem.PayloadListener listener) {
        this.context = context;
        this.listener = listener;
        // A neighbor's PSK31 burst is a continuous carrier for up to
        // ~77 s. The carrier sense's stuck-squelch re-baseline (10 s
        // default, right for the fast modems) must not absorb a real
        // burst into the noise floor and declare the channel clear
        // mid-transmission — that would silently defeat the 90 s CSMA
        // patience below. 90 s: a genuinely stuck-open squelch still
        // re-baselines, just on this modem's airtime scale.
        csma.setBusyRebaselineMs(90_000);
    }

    public void setVoxLeaderMs(int ms) {
        voxLeaderMs = Math.max(0, ms);
    }

    public void setTxStreamIndex(int idx) {
        switch (idx) {
            case 1:  txStream = AudioManager.STREAM_MUSIC; break;
            case 2:  txStream = AudioManager.STREAM_RING; break;
            case 3:  txStream = AudioManager.STREAM_NOTIFICATION; break;
            default: txStream = AudioManager.STREAM_ALARM; break;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isTransmitting() {
        return transmitting;
    }

    /** True while the carrier sense hears signal energy on the channel. */
    public boolean isChannelBusy() {
        return csma.isBusy();
    }

    public void setMaxBatchFrames(int n) {
        maxBatchFrames = Math.max(1, n);
    }

    /** Estimated on-air ms for one mesh frame of n bytes (for the MAC). */
    public static int airtimeMs(int frameBytes) {
        return Psk31Armor.estimateAirtimeMs(frameBytes);
    }

    // TX output routing + optional hardware PTT (same contract as the others)
    public static final int TX_OUT_SPEAKER = 0;
    public static final int TX_OUT_USB_RTS = 1;
    private volatile int txOutput = TX_OUT_SPEAKER;
    private volatile PttKeyer ptt;

    public void setTxOutput(int mode) {
        txOutput = mode == TX_OUT_USB_RTS ? TX_OUT_USB_RTS : TX_OUT_SPEAKER;
    }

    public void setPtt(PttKeyer keyer) {
        ptt = keyer;
    }

    private AudioDeviceInfo findSpeaker() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    return d;
        } catch (Exception ignored) {}
        return null;
    }

    private volatile boolean rxRebindRequested = false;

    /** Re-open RX on the current best input device (USB hot-plug). */
    public void rebindAudio() {
        rxRebindRequested = true;
    }

    /** Frames waiting in the modem TX queue (stall watchdog). */
    public int queuedFrames() {
        synchronized (txLock) {
            return txQueue.size();
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        rxThread = new Thread(this::rxLoop, "HBC-PSK31-RX");
        rxThread.start();
        txThread = new Thread(this::txLoop, "HBC-PSK31-TX");
        txThread.start();
        listener.onStatus("Modem started (BPSK31 @ " + (int) CARRIER_HZ
                + " Hz carrier, " + SAMPLE_RATE + " Hz — SLOW: a PLI takes ~"
                + (airtimeMs(30) / 1000) + " s on air)");
    }

    public synchronized void stop() {
        running = false;
        synchronized (txLock) {
            txQueue.clear();
            txLock.notifyAll();
        }
        if (txThread != null) {
            try { txThread.join(3000); } catch (InterruptedException ignored) {}
            txThread = null;
        }
        if (rxThread != null) {
            try { rxThread.join(3000); } catch (InterruptedException ignored) {}
            rxThread = null;
        }
        listener.onStatus("Modem stopped");
    }

    /** Queue a mesh frame for transmission. */
    public void transmit(String callsignIgnored, byte[] frame) {
        if (frame.length < 1 || frame.length > MAX_FRAME_BYTES) {
            listener.onStatus("PSK31: payload size " + frame.length
                    + " unsupported (max " + MAX_FRAME_BYTES + ")");
            return;
        }
        synchronized (txLock) {
            txQueue.addLast(new TxItem(frame));
            txLock.notifyAll();
        }
    }

    // ------------------------------------------------------------------
    private AudioDeviceInfo findUsbDevice(boolean output) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devs = am.getDevices(output
                    ? AudioManager.GET_DEVICES_OUTPUTS
                    : AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo d : devs) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_USB_DEVICE
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_ACCESSORY)
                    return d;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private AudioAttributes txAttributes() {
        if (txOutput == TX_OUT_USB_RTS) {
            return new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
        }
        AudioAttributes.Builder b = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
        if (txStream == AudioManager.STREAM_MUSIC)
            b.setUsage(AudioAttributes.USAGE_MEDIA)
             .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        else if (txStream == AudioManager.STREAM_RING)
            b.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        else if (txStream == AudioManager.STREAM_NOTIFICATION)
            b.setUsage(AudioAttributes.USAGE_NOTIFICATION);
        else
            b.setUsage(AudioAttributes.USAGE_ALARM);
        return b.build();
    }

    /** Render one armored frame to PCM via the Psk31 encoder. */
    private short[] renderBurst(TxItem item) {
        final String text = Psk31Armor.frame(item.payload);
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        boolean ok = Psk31.transmit(text, CARRIER_HZ, SAMPLE_RATE, (block, count) -> {
            for (int i = 0; i < count; i++) {
                bos.write(block[i] & 0xFF);
                bos.write((block[i] >> 8) & 0xFF);
            }
            return true;
        });
        if (!ok) return null;
        byte[] raw = bos.toByteArray();
        short[] out = new short[raw.length / 2];
        for (int i = 0; i < out.length; i++) {
            short s = (short) ((raw[2 * i] & 0xFF) | (raw[2 * i + 1] << 8));
            out[i] = (short) (s * TX_AMPLITUDE);
        }
        return out;
    }

    /**
     * Whole batch as ONE keying. The VOX leader is a steady carrier tone
     * (an unmodulated PSK31 carrier = idle '1's: trips VOX, decodes as
     * nothing) ahead of the encoder's own 2 s phase-reversal preamble.
     */
    private short[] renderTransmission(List<TxItem> items) {
        List<short[]> parts = new ArrayList<>();
        int total = 0;

        int lead = LEAD_SILENCE_MS * SAMPLE_RATE / 1000;
        parts.add(new short[lead]);
        total += lead;

        int leaderSamples = voxLeaderMs * SAMPLE_RATE / 1000;
        if (leaderSamples > 0) {
            short[] leader = new short[leaderSamples];
            double phase = 0, inc = 2 * Math.PI * CARRIER_HZ / SAMPLE_RATE;
            for (int i = 0; i < leaderSamples; i++) {
                double ramp = Math.min(1, i / (SAMPLE_RATE * 0.01));
                leader[i] = (short) (Math.sin(phase) * ramp * 15000 * TX_AMPLITUDE);
                phase += inc;
                if (phase >= 2 * Math.PI) phase -= 2 * Math.PI;
            }
            parts.add(leader);
            total += leaderSamples;
        }

        int gap = INTER_BURST_GAP_MS * SAMPLE_RATE / 1000;
        boolean first = true;
        for (TxItem item : items) {
            short[] b = renderBurst(item);
            if (b == null) {
                listener.onStatus("TX error: PSK31 burst render failed \u2014 frame skipped");
                continue;
            }
            if (!first) {
                parts.add(new short[gap]);
                total += gap;
            }
            first = false;
            parts.add(b);
            total += b.length;
        }
        if (first)
            return null;

        int tail = TAIL_SILENCE_MS * SAMPLE_RATE / 1000;
        parts.add(new short[tail]);
        total += tail;

        short[] pcm = new short[total];
        int off = 0;
        for (short[] c : parts) {
            System.arraycopy(c, 0, pcm, off, c.length);
            off += c.length;
        }
        return pcm;
    }

    private void txLoop() {
        try {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception ignored) {}
        while (running) {
            List<TxItem> batch = new ArrayList<>();
            synchronized (txLock) {
                while (running && txQueue.isEmpty()) {
                    try { txLock.wait(500); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
                while (!txQueue.isEmpty() && batch.size() < maxBatchFrames)
                    batch.add(txQueue.pollFirst());
            }
            if (batch.isEmpty()) continue;

            // CSMA: PSK31 neighbors transmit for tens of seconds (a
            // max-size frame measures ~77 s on air), so the patience
            // window must exceed the longest possible neighbor burst or
            // the "transmitting anyway" override fires mid-burst and
            // guarantees a collision.
            try {
                if (csma.isBusy())
                    listener.onStatus("CSMA: channel busy \u2014 deferring TX");
                if (!csma.waitForClear(90_000, null))
                    listener.onStatus("CSMA: channel busy > 90 s \u2014 transmitting anyway");
            } catch (InterruptedException e) {
                return;
            }

            transmitting = true;
            AudioTrack track = null;
            try {
                short[] pcm = renderTransmission(batch);
                if (pcm == null) {
                    listener.onStatus("TX error: PSK31 burst render failed");
                    continue;
                }

                AudioFormat fmt = new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                track = new AudioTrack(txAttributes(), fmt, pcm.length * 2,
                        AudioTrack.MODE_STATIC, 0);
                AudioDeviceInfo out = txOutput == TX_OUT_USB_RTS
                        ? findUsbDevice(true) : findSpeaker();
                if (out != null)
                    track.setPreferredDevice(out);
                track.write(pcm, 0, pcm.length);
                track.setVolume(1.0f);
                PttKeyer keyer = ptt;
                if (keyer != null) {
                    keyer.key(true);
                    Thread.sleep(60);
                }
                track.play();

                long durMs = 1000L * pcm.length / SAMPLE_RATE;
                long deadline = System.currentTimeMillis() + durMs + 500;
                while (System.currentTimeMillis() < deadline
                        && track.getPlaybackHeadPosition() < pcm.length - 32) {
                    Thread.sleep(20);
                }
                if (batch.size() == 1)
                    listener.onStatus("TX PSK31 " + batch.get(0).payload.length
                            + " B (" + durMs + " ms burst)");
                else
                    listener.onStatus("TX batch: " + batch.size()
                            + " frames (" + durMs + " ms burst)");
            } catch (Exception e) {
                listener.onStatus("TX error: " + e.getMessage());
            } finally {
                PttKeyer keyer = ptt;
                if (keyer != null)
                    keyer.key(false);
                if (track != null) {
                    try { track.stop(); } catch (Exception ignored) {}
                    try { track.release(); } catch (Exception ignored) {}
                }
                transmitting = false;
            }
        }
    }

    // ------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private AudioRecord openRecord() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, SAMPLE_RATE);
        int[] sources = (Build.VERSION.SDK_INT >= 24)
                ? new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.UNPROCESSED,
                            MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.MIC};
        String[] names = (Build.VERSION.SDK_INT >= 24)
                ? new String[]{"VOICE_RECOGNITION", "UNPROCESSED", "MIC"}
                : new String[]{"VOICE_RECOGNITION", "MIC"};
        for (int i = 0; i < sources.length; i++) {
            try {
                AudioRecord r = new AudioRecord(sources[i], SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        bufSize);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    String fx = RxAudioEffects.disable(r, rxEffects);
                    AudioDeviceInfo usbIn = findUsbDevice(false);
                    if (usbIn != null) {
                        r.setPreferredDevice(usbIn);
                        listener.onStatus("RX audio source: " + names[i]
                                + " via USB (" + usbIn.getProductName() + ")" + fx);
                    } else {
                        listener.onStatus("RX audio source: " + names[i]
                                + " (built-in mic)" + fx);
                    }
                    return r;
                }
                r.release();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void rxLoop() {
        try {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception ignored) {}
        AudioRecord record = null;
        try {
            record = openRecord();
            if (record == null) {
                listener.onStatus("RX disabled: microphone unavailable "
                        + "(check RECORD_AUDIO permission for ATAK)");
                return;
            }
            record.startRecording();

            final Psk31Armor.Deframer deframer = new Psk31Armor.Deframer();
            final byte[][] decoded = new byte[1][];
            Psk31.Receiver rx = new Psk31.Receiver(CARRIER_HZ, c -> {
                byte[] frame = deframer.accept(c);
                if (frame != null) decoded[0] = frame;
            });

            short[] pcm = new short[SAMPLE_RATE / 8];   // 125 ms chunks
            while (running) {
                if (rxRebindRequested) {
                    rxRebindRequested = false;
                    try { record.stop(); } catch (Exception ignored) {}
                    try { record.release(); } catch (Exception ignored) {}
                    RxAudioEffects.release(rxEffects);
                    record = openRecord();
                    if (record == null) {
                        listener.onStatus("RX disabled: microphone "
                                + "unavailable after USB change");
                        return;
                    }
                    record.startRecording();
                    csma.reset();
                    continue;
                }
                int n = record.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                if (transmitting) continue;  // half duplex
                csma.feed(pcm, n);
                rx.accept(pcm, n);
                if (decoded[0] != null) {
                    byte[] frame = decoded[0];
                    decoded[0] = null;
                    // Armored frames carry no station metadata; the HBC
                    // header inside carries the sender callsign.
                    listener.onPayload("", frame);
                }
            }
            record.stop();
        } catch (Exception e) {
            listener.onStatus("RX thread failed: " + e.getMessage());
        } finally {
            RxAudioEffects.release(rxEffects);
            if (record != null) {
                try { record.release(); } catch (Exception ignored) {}
            }
        }
    }
}
