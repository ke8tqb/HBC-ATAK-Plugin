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
 * Mercury HF audio modem — the physical layer of Rhizomatica's Mercury
 * modem (https://mercury.hermes.radio/): FreeDV DATAC raw data bursts
 * (codec2 OFDM with LDPC FEC and per-frame CRC-16), designed for SSB HF
 * channels with multipath and very low SNR. 8000 Hz, 16-bit mono.
 *
 * Payload framing inside a DATAC frame: byte0 = HBC payload length,
 * byte1 = 'H' domain marker, bytes2..n = HBC bytes, zero-padded to the
 * mode's payload size (DATAC4 = 54 usable HBC bytes, DATAC3 = 124,
 * DATAC1 = 508). Every current HBC message fits one frame.
 *
 * Interop note: this speaks the same waveform Mercury itself modulates
 * (identical preamble/frame/CRC layout via the same vendored FreeDV
 * code), but it does not implement Mercury's ARQ or broadcast data-link
 * framing — it is HBC-over-DATAC, station to station, half duplex,
 * like the plugin's other modems.
 */
public class MercuryModem {

    public static final int SAMPLE_RATE = MercuryNative.SAMPLE_RATE;
    private static final double TX_AMPLITUDE = 0.5;
    private static final int LEAD_SILENCE_MS = 100;   // matches Mercury's head silence
    private static final int TAIL_SILENCE_MS = 100;   // matches Mercury's TAIL_TIME_US
    private static final int INTER_BURST_GAP_MS = 100; // between batched bursts
    private static final byte DOMAIN_MARKER = 'H';    // rejects non-HBC DATAC traffic

    private final Context context;
    private final OfdmModem.PayloadListener listener;
    private final int mode;

    private Thread txThread, rxThread;
    private volatile boolean running = false;
    private volatile boolean transmitting = false;

    private volatile int voxLeaderMs = 0;
    private volatile int txStream = AudioManager.STREAM_ALARM;
    private volatile int maxBatchFrames = 1; // DATAC4 bursts are ~5.6 s each

    private static final class TxItem {
        final byte[] payload;
        TxItem(byte[] p) { payload = p; }
    }

    private final Deque<TxItem> txQueue = new ArrayDeque<>();
    private final Object txLock = new Object();
    private final CsmaSense csma = new CsmaSense();

    // input effects we explicitly disabled; kept referenced while running
    private final List<android.media.audiofx.AudioEffect> rxEffects = new ArrayList<>();

    public MercuryModem(Context context, OfdmModem.PayloadListener listener) {
        this(context, listener, MercuryNative.MODE_DATAC4);
    }

    public MercuryModem(Context context, OfdmModem.PayloadListener listener, int mode) {
        this.context = context;
        this.listener = listener;
        this.mode = mode;
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

    /**
     * Max queued frames rendered into ONE continuous keying (one VOX
     * key-up/hang cycle for the whole batch instead of one per frame).
     */
    public void setMaxBatchFrames(int n) {
        maxBatchFrames = Math.max(1, n);
    }

    public synchronized void start() throws Exception {
        if (running) return;
        synchronized (MercuryNative.class) {
            if (!MercuryNative.create(mode))
                throw new IllegalStateException("Mercury/FreeDV modem init failed");
        }
        running = true;
        rxThread = new Thread(this::rxLoop, "HBC-Mercury-RX");
        rxThread.start();
        txThread = new Thread(this::txLoop, "HBC-Mercury-TX");
        txThread.start();
        String modeName = mode == MercuryNative.MODE_DATAC1 ? "DATAC1"
                : mode == MercuryNative.MODE_DATAC3 ? "DATAC3" : "DATAC4";
        listener.onStatus("Modem started (Mercury/FreeDV " + modeName
                + " @ " + SAMPLE_RATE + " Hz)");
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
        synchronized (MercuryNative.class) {
            MercuryNative.destroy();
        }
        listener.onStatus("Modem stopped");
    }

    /** Queue an HBC payload for transmission. */
    public void transmit(String callsignIgnored, byte[] hbcPayload) {
        int cap;
        synchronized (MercuryNative.class) {
            cap = MercuryNative.payloadBytesPerFrame() - 2; // len + marker bytes
        }
        if (hbcPayload.length < 1 || (cap > 0 && hbcPayload.length > cap)) {
            listener.onStatus("Mercury: payload size " + hbcPayload.length
                    + " unsupported (max " + cap + ")");
            return;
        }
        synchronized (txLock) {
            txQueue.addLast(new TxItem(hbcPayload));
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

    /** Encode one frame into a scaled burst (no lead/tail silence). */
    private short[] renderBurst(TxItem item) {
        synchronized (MercuryNative.class) {
            int payloadBytes = MercuryNative.payloadBytesPerFrame();
            byte[] frame = new byte[payloadBytes];
            frame[0] = (byte) item.payload.length;
            frame[1] = DOMAIN_MARKER;
            System.arraycopy(item.payload, 0, frame, 2, item.payload.length);

            short[] burst = new short[MercuryNative.maxBurstSamples()];
            int n = MercuryNative.txBurst(frame, burst);
            if (n <= 0)
                return null;
            short[] out = new short[n];
            for (int i = 0; i < n; i++)
                out[i] = (short) (burst[i] * TX_AMPLITUDE);
            return out;
        }
    }

    /**
     * Render a whole batch as ONE keying: lead silence + VOX leader once,
     * bursts back-to-back with short gaps so the decoder can re-arm, one
     * tail. One VOX cycle per batch.
     */
    private short[] renderTransmission(List<TxItem> items) {
        List<short[]> parts = new ArrayList<>();
        int total = 0;
        int lead = (LEAD_SILENCE_MS + voxLeaderMs) * SAMPLE_RATE / 1000;
        parts.add(new short[lead]);
        total += lead;

        int gap = INTER_BURST_GAP_MS * SAMPLE_RATE / 1000;
        boolean first = true;
        for (TxItem item : items) {
            short[] b = renderBurst(item);
            if (b == null) {
                listener.onStatus("TX error: Mercury burst render failed \u2014 frame skipped");
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
            return null;   // nothing rendered

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

            // CSMA: energy carrier-sense with random backoff. HF bursts are
            // long, so allow a longer wait before giving up.
            try {
                if (csma.isBusy())
                    listener.onStatus("CSMA: channel busy \u2014 deferring TX");
                if (!csma.waitForClear(15000, null))
                    listener.onStatus("CSMA: channel busy > 15 s \u2014 transmitting anyway");
            } catch (InterruptedException e) {
                return;
            }

            transmitting = true;
            AudioTrack track = null;
            try {
                short[] pcm = renderTransmission(batch);
                if (pcm == null) {
                    listener.onStatus("TX error: Mercury burst render failed");
                    continue;
                }

                AudioFormat fmt = new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                track = new AudioTrack(txAttributes(), fmt, pcm.length * 2,
                        AudioTrack.MODE_STATIC, 0);
                AudioDeviceInfo usbOut = findUsbDevice(true);
                if (usbOut != null)
                    track.setPreferredDevice(usbOut);
                track.write(pcm, 0, pcm.length);
                track.setVolume(1.0f);
                track.play();

                long durMs = 1000L * pcm.length / SAMPLE_RATE;
                long deadline = System.currentTimeMillis() + durMs + 500;
                while (System.currentTimeMillis() < deadline
                        && track.getPlaybackHeadPosition() < pcm.length - 32) {
                    Thread.sleep(20);
                }
                if (batch.size() == 1)
                    listener.onStatus("TX Mercury " + batch.get(0).payload.length
                            + " B (" + durMs + " ms burst)");
                else
                    listener.onStatus("TX batch: " + batch.size()
                            + " frames (" + durMs + " ms burst)");
            } catch (Exception e) {
                listener.onStatus("TX error: " + e.getMessage());
            } finally {
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

            int maxSamples;
            byte[] frame;
            synchronized (MercuryNative.class) {
                maxSamples = Math.max(1, MercuryNative.rxMaxSamples());
                frame = new byte[MercuryNative.payloadBytesPerFrame() + 2];
            }
            short[] pcm = new short[maxSamples];
            boolean wasSynced = false;

            while (running) {
                int nin;
                synchronized (MercuryNative.class) {
                    nin = MercuryNative.rxNin();
                }
                if (nin <= 0 || nin > maxSamples) nin = maxSamples;

                int got = 0;
                while (got < nin && running) {
                    int n = record.read(pcm, got, nin - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got < nin) continue;
                if (transmitting) continue; // half duplex: discard our own audio
                csma.feed(pcm, nin);        // CSMA carrier sense

                int nbytes;
                synchronized (MercuryNative.class) {
                    nbytes = MercuryNative.rxProcess(pcm, frame);
                    boolean synced = MercuryNative.rxSync() != 0;
                    if (synced && !wasSynced)
                        listener.onStatus("Mercury sync acquired");
                    wasSynced = synced;
                }
                if (nbytes <= 2) continue;   // no CRC-valid frame this chunk

                int len = frame[0] & 0xFF;
                if (frame[1] != DOMAIN_MARKER || len < 1 || len > nbytes - 4) {
                    listener.onStatus("Mercury: non-HBC or corrupt frame ignored");
                    continue;
                }
                byte[] hbc = new byte[len];
                System.arraycopy(frame, 2, hbc, 0, len);
                // DATAC frames carry no station metadata; the HBC header
                // itself contains the sender callsign.
                listener.onPayload("", hbc);
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
