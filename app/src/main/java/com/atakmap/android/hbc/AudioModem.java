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

import sivantoledo.ax25.Afsk1200Modulator;
import sivantoledo.ax25.Afsk1200MultiDemodulator;
import sivantoledo.ax25.Packet;
import sivantoledo.ax25.PacketDemodulator;
import sivantoledo.ax25.PacketHandler;

/**
 * AFSK1200 (Bell 202) audio modem for AX.25 UI frames.
 *
 * TX: the complete transmission (leading silence pad, optional VOX leader
 *     tone, HDLC preamble + frame, trailing silence pad) is rendered into a
 *     single PCM buffer and played through a MODE_STATIC AudioTrack created
 *     with USAGE_ALARM / CONTENT_TYPE_SONIFICATION attributes. Rendering
 *     up-front means device fade-in eats silence instead of preamble, and
 *     the alarm usage bypasses media post-processing (Dolby Atmos / EQ /
 *     adaptive sound) that distorts FSK on some devices (e.g. Samsung
 *     flagships). TX drive is kept at ~40% full scale to stay clear of
 *     speaker-protection limiters.
 *
 * RX: AudioRecord using VOICE_RECOGNITION (falls back to UNPROCESSED, then
 *     MIC) to avoid voice-call AGC/noise-suppression mangling the tones.
 */
public class AudioModem {

    public interface FrameListener {
        /** Called on the RX thread with a CRC-valid AX.25 frame (no CRC bytes). */
        void onFrame(byte[] ax25Frame);

        /** Modem status/log line. */
        void onStatus(String message);
    }

    public static final int SAMPLE_RATE = 48000;

    private static final double TX_AMPLITUDE = 0.5;   // fraction of full scale (parity with OFDM)
    private static final int LEAD_SILENCE_MS = 150;   // absorbed by device fade-in
    private static final int TAIL_SILENCE_MS = 250;   // keeps VOX keyed till frame end

    // Minimum HDLC flag preamble actually transmitted, regardless of the TX
    // Dwell setting. The acoustic path (speaker ramp, mic settle, demod clock
    // recovery) needs a run of flags to lock; dwell=0 would otherwise yield
    // ~2 flags (~13 ms) and the receiver hears the burst but never syncs.
    // 300 ms (~45 flags) is the classic TNC TXDelay default.
    private static final int MIN_PREAMBLE_MS = 300;

    // Continuation-frame preamble inside a batched burst: the demodulator is
    // already bit-synced after the first frame of the burst, so a short flag
    // run (~3 flags) is enough to separate frames.
    private static final int CONT_PREAMBLE_MS = 20;

    private final FrameListener listener;
    private final Context context;

    private Afsk1200Modulator modulator;
    private PacketDemodulator demodulator;

    private Thread txThread, rxThread;
    private volatile boolean running = false;
    private volatile boolean transmitting = false;

    private volatile int txDwellMs = 500;   // HDLC flag preamble (TXDelay)
    private volatile int voxLeaderMs = 0;   // steady mark tone before preamble
    private volatile int txStream = AudioManager.STREAM_ALARM;
    private volatile double txLevel = TX_AMPLITUDE; // user-set TX drive (0.01..1.0)
    private volatile int maxBatchFrames = 4; // frames per continuous burst

    private final Deque<Packet> txQueue = new ArrayDeque<>();
    private final Object txLock = new Object();
    private final CsmaSense csma = new CsmaSense();

    // RX diagnostics: when the carrier sense heard a signal but the
    // demodulator produced no frame, say so (helps separate "no audio"
    // from "audio present but undecodable" in the field).
    private volatile long lastFrameMs = 0;
    private long lastNoDecodeLogMs = 0;
    private boolean chWasBusy = false;
    private long busyStartMs = 0;

    // level stats for the current busy period (RX burst): tells clipping
    // (peak pinned at 100%) apart from too-quiet (peak a few %) in the field
    private float busyPeak = 0;
    private double busySumSq = 0;
    private long busySamples = 0;

    // input effects we explicitly disabled; kept referenced while running
    private final List<android.media.audiofx.AudioEffect> rxEffects = new ArrayList<>();

    private volatile boolean warnedDwellClamp = false;

    public AudioModem(Context context, FrameListener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Find a connected USB audio device (e.g. Digirig), or null. */
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

    public void setTxDwellMs(int ms) {
        txDwellMs = Math.max(0, ms);
    }

    /**
     * TX audio drive as a percentage of full scale (1..100). Close-range
     * acoustic coupling overdrives the speaker and/or clips the peer's mic
     * (with NS/AGC disabled nothing tames it), which distorts the FSK tones
     * beyond decoding \u2014 lowering the level is the fix.
     */
    public void setTxLevelPercent(int pct) {
        txLevel = Math.max(1, Math.min(100, pct)) / 100.0;
    }

    /**
     * Max queued frames rendered into ONE continuous burst (one VOX
     * key-up/hang cycle for the whole batch instead of one per frame).
     */
    public void setMaxBatchFrames(int n) {
        maxBatchFrames = Math.max(1, n);
    }

    public void setVoxLeaderMs(int ms) {
        voxLeaderMs = Math.max(0, ms);
    }

    /** 0=Alarm 1=Media 2=Ring 3=Notification (like APRSdroid's output pref). */
    public void setTxStreamIndex(int idx) {
        switch (idx) {
            case 1:  txStream = AudioManager.STREAM_MUSIC; break;
            case 2:  txStream = AudioManager.STREAM_RING; break;
            case 3:  txStream = AudioManager.STREAM_NOTIFICATION; break;
            default: txStream = AudioManager.STREAM_ALARM; break;
        }
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

    public boolean isRunning() {
        return running;
    }

    public boolean isTransmitting() {
        return transmitting;
    }

    public boolean isChannelBusy() {
        PacketDemodulator d = demodulator;
        return csma.isBusy() || (d != null && d.dcd());
    }

    public synchronized void start() throws Exception {
        if (running) return;

        modulator = new Afsk1200Modulator(SAMPLE_RATE);
        demodulator = new Afsk1200MultiDemodulator(SAMPLE_RATE, new PacketHandler() {
            @Override
            public void handlePacket(byte[] bytes) {
                lastFrameMs = System.currentTimeMillis();
                listener.onFrame(bytes);
            }
        });

        running = true;

        rxThread = new Thread(this::rxLoop, "HBC-AFSK-RX");
        rxThread.setPriority(Thread.MAX_PRIORITY - 1);
        rxThread.start();

        txThread = new Thread(this::txLoop, "HBC-AFSK-TX");
        txThread.setPriority(Thread.MAX_PRIORITY - 1);
        txThread.start();

        listener.onStatus("Modem started (AFSK1200 @ " + SAMPLE_RATE
                + " Hz, TX level " + Math.round(txLevel * 100) + "%)");
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

    /** Queue an AX.25 UI frame for transmission. */
    public void transmit(String dest, String source, String[] path, byte[] payload) {
        Packet p = new Packet(dest, source, path == null ? new String[0] : path,
                Packet.AX25_CONTROL_APRS, Packet.AX25_PROTOCOL_NO_LAYER_3, payload);
        synchronized (txLock) {
            txQueue.addLast(p);
            txLock.notifyAll();
        }
    }

    // ------------------------------------------------------------------
    // TX
    // ------------------------------------------------------------------
    private short[] renderTransmission(List<Packet> packets, int outRate) {
        List<float[]> chunks = new ArrayList<>();
        int total = 0;

        // leading silence: device pop-suppression/fade-in eats this, not data
        int lead = LEAD_SILENCE_MS * outRate / 1000;
        chunks.add(new float[lead]);
        total += lead;

        // optional VOX leader: steady mark tone (once per burst)
        int leaderSamples = voxLeaderMs * outRate / 1000;
        if (leaderSamples > 0) {
            float[] leader = new float[leaderSamples];
            double phase = 0, inc = 2.0 * Math.PI * 1200.0 / outRate;
            for (int i = 0; i < leaderSamples; i++) {
                leader[i] = (float) Math.sin(phase);
                phase += inc;
            }
            chunks.add(leader);
            total += leaderSamples;
        }

        // frames back-to-back in ONE keying: the first frame carries the
        // full sync preamble; continuation frames only a short flag run
        // (the demodulator is already bit-synced within the burst). The
        // whole batch is rendered at the native output rate so the OS
        // resampler never touches the waveform (APRSdroid technique).
        int effDwellMs = Math.max(MIN_PREAMBLE_MS, txDwellMs);
        if (effDwellMs != txDwellMs && !warnedDwellClamp) {
            warnedDwellClamp = true;
            listener.onStatus("AFSK: TX dwell " + txDwellMs
                    + " ms too short for RX sync \u2014 using "
                    + MIN_PREAMBLE_MS + " ms preamble");
        }
        boolean first = true;
        for (Packet packet : packets) {
            Afsk1200Modulator txMod = new Afsk1200Modulator(outRate);
            txMod.setTxDelay(Math.max(1,
                    (first ? effDwellMs : CONT_PREAMBLE_MS) / 10)); // 10 ms units
            first = false;
            txMod.prepareToTransmit(packet);
            float[] buf = txMod.getTxSamplesBuffer();
            int n;
            while ((n = txMod.getSamples()) > 0) {
                float[] c = new float[n];
                System.arraycopy(buf, 0, c, 0, n);
                chunks.add(c);
                total += n;
            }
        }

        // trailing silence: playback truncation/VOX drop can't clip the CRC
        int tail = TAIL_SILENCE_MS * outRate / 1000;
        chunks.add(new float[tail]);
        total += tail;

        short[] pcm = new short[total];
        int off = 0;
        double amp = txLevel;
        for (float[] c : chunks) {
            for (float v : c)
                pcm[off++] = (short) (v * amp * 32767.0);
        }
        return pcm;
    }

    private void txLoop() {
        try {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception ignored) {}
        while (running) {
            List<Packet> batch = new ArrayList<>();
            synchronized (txLock) {
                while (running && txQueue.isEmpty()) {
                    try { txLock.wait(500); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
                while (!txQueue.isEmpty() && batch.size() < maxBatchFrames)
                    batch.add(txQueue.pollFirst());
            }
            if (batch.isEmpty()) continue;

            // CSMA: energy carrier-sense + demodulator DCD, random backoff
            try {
                if (csma.isBusy() || isChannelBusy())
                    listener.onStatus("CSMA: channel busy \u2014 deferring TX");
                if (!csma.waitForClear(8000, this::isChannelBusy))
                    listener.onStatus("CSMA: channel busy > 8 s \u2014 transmitting anyway");
            } catch (InterruptedException e) {
                return;
            }

            transmitting = true;
            AudioTrack track = null;
            try {
                // render at the device's native output rate for this stream
                int outRate;
                try {
                    outRate = AudioTrack.getNativeOutputSampleRate(txStream);
                } catch (Exception e) {
                    outRate = SAMPLE_RATE;
                }
                if (outRate <= 0) outRate = SAMPLE_RATE;
                short[] pcm = renderTransmission(batch, outRate);

                AudioAttributes attrs = txAttributes();
                AudioFormat fmt = new AudioFormat.Builder()
                        .setSampleRate(outRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                track = new AudioTrack(attrs, fmt, pcm.length * 2,
                        AudioTrack.MODE_STATIC, 0);
                AudioDeviceInfo usbOut = findUsbDevice(true);
                if (usbOut != null) {
                    // pin output to the USB interface (Digirig) only —
                    // prevents the alarm stream from also using the speaker
                    track.setPreferredDevice(usbOut);
                }
                track.write(pcm, 0, pcm.length);
                track.setVolume(1.0f);
                track.play();

                long durMs = 1000L * pcm.length / outRate;
                long deadline = System.currentTimeMillis() + durMs + 500;
                while (System.currentTimeMillis() < deadline
                        && track.getPlaybackHeadPosition() < pcm.length - 32) {
                    Thread.sleep(20);
                }
                if (batch.size() == 1)
                    listener.onStatus("TX " + batch.get(0).toString()
                            + " (" + durMs + " ms burst)");
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
    // RX
    // ------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private AudioRecord openRecord() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, SAMPLE_RATE);
        // UNPROCESSED first: AFSK is pure narrowband tones, and OEM noise
        // suppression attached to VOICE_RECOGNITION on some devices notches
        // steady tones out entirely (OFDM survives it, AFSK does not).
        int[] sources = (Build.VERSION.SDK_INT >= 24)
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED,
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.MIC};
        String[] names = (Build.VERSION.SDK_INT >= 24)
                ? new String[]{"UNPROCESSED", "VOICE_RECOGNITION", "MIC"}
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

            short[] pcm = new short[SAMPLE_RATE / 10];
            float[] samples = new float[pcm.length];
            while (running) {
                int n = record.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                if (transmitting) continue; // half duplex: ignore our own audio
                csma.feed(pcm, n);          // CSMA carrier sense
                float chunkPeak = 0;
                double chunkSumSq = 0;
                for (int i = 0; i < n; i++) {
                    float v = pcm[i] / 32768.0f;
                    samples[i] = v;
                    float a = v < 0 ? -v : v;
                    if (a > chunkPeak) chunkPeak = a;
                    chunkSumSq += (double) v * v;
                }
                demodulator.addSamples(samples, n);

                // diagnostic: a signal was heard, then the channel went
                // quiet, and the whole busy period produced no frame
                boolean busy = csma.isBusy();
                long nowMs = System.currentTimeMillis();
                if (busy && !chWasBusy) {
                    busyStartMs = nowMs;
                    busyPeak = 0;
                    busySumSq = 0;
                    busySamples = 0;
                }
                if (busy || chWasBusy) {
                    if (chunkPeak > busyPeak) busyPeak = chunkPeak;
                    busySumSq += chunkSumSq;
                    busySamples += n;
                }
                if (!busy && chWasBusy && nowMs - busyStartMs > 300) {
                    int pk = Math.round(busyPeak * 100f);
                    int rms = busySamples > 0 ? (int) Math.round(
                            Math.sqrt(busySumSq / busySamples) * 100) : 0;
                    String hint = busyPeak >= 0.98f
                            ? " \u2014 CLIPPING: lower TX level/volume or move apart"
                            : busyPeak < 0.05f
                            ? " \u2014 VERY LOW: raise volume or move closer"
                            : "";
                    listener.onStatus("AFSK RX burst " + (nowMs - busyStartMs)
                            + " ms: peak " + pk + "%, RMS " + rms + "%" + hint);
                }
                if (!busy && chWasBusy
                        && lastFrameMs < busyStartMs
                        && nowMs - busyStartMs > 700
                        && nowMs - lastNoDecodeLogMs > 30000) {
                    lastNoDecodeLogMs = nowMs;
                    listener.onStatus("AFSK: heard a signal but decoded no "
                            + "frame (check RX level/distortion)");
                }
                chWasBusy = busy;
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
