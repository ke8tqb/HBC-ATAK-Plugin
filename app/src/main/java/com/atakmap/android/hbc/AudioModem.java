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

    private static final double TX_AMPLITUDE = 0.4;   // fraction of full scale
    private static final int LEAD_SILENCE_MS = 150;   // absorbed by device fade-in
    private static final int TAIL_SILENCE_MS = 250;   // keeps VOX keyed till frame end

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

    private final Deque<Packet> txQueue = new ArrayDeque<>();
    private final Object txLock = new Object();
    private final CsmaSense csma = new CsmaSense();

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
        return d != null && d.dcd();
    }

    public synchronized void start() throws Exception {
        if (running) return;

        modulator = new Afsk1200Modulator(SAMPLE_RATE);
        demodulator = new Afsk1200MultiDemodulator(SAMPLE_RATE, new PacketHandler() {
            @Override
            public void handlePacket(byte[] bytes) {
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
                + " Hz, alarm-stream TX)");
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
    private short[] renderTransmission(Packet packet, int outRate) {
        List<float[]> chunks = new ArrayList<>();
        int total = 0;

        // leading silence: device pop-suppression/fade-in eats this, not data
        int lead = LEAD_SILENCE_MS * outRate / 1000;
        chunks.add(new float[lead]);
        total += lead;

        // optional VOX leader: steady mark tone
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

        // preamble + frame, rendered at the native output rate so the OS
        // resampler never touches the waveform (APRSdroid technique)
        Afsk1200Modulator txMod = new Afsk1200Modulator(outRate);
        txMod.setTxDelay(Math.max(1, txDwellMs / 10)); // 10 ms units
        txMod.prepareToTransmit(packet);
        float[] buf = txMod.getTxSamplesBuffer();
        int n;
        while ((n = txMod.getSamples()) > 0) {
            float[] c = new float[n];
            System.arraycopy(buf, 0, c, 0, n);
            chunks.add(c);
            total += n;
        }

        // trailing silence: playback truncation/VOX drop can't clip the CRC
        int tail = TAIL_SILENCE_MS * outRate / 1000;
        chunks.add(new float[tail]);
        total += tail;

        short[] pcm = new short[total];
        int off = 0;
        for (float[] c : chunks) {
            for (float v : c)
                pcm[off++] = (short) (v * TX_AMPLITUDE * 32767.0);
        }
        return pcm;
    }

    private void txLoop() {
        try {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception ignored) {}
        while (running) {
            Packet packet;
            synchronized (txLock) {
                while (running && txQueue.isEmpty()) {
                    try { txLock.wait(500); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
                packet = txQueue.pollFirst();
            }
            if (packet == null) continue;

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
                short[] pcm = renderTransmission(packet, outRate);

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
                listener.onStatus("TX " + packet.toString()
                        + " (" + durMs + " ms burst)");
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
                    AudioDeviceInfo usbIn = findUsbDevice(false);
                    if (usbIn != null) {
                        r.setPreferredDevice(usbIn);
                        listener.onStatus("RX audio source: " + names[i]
                                + " via USB (" + usbIn.getProductName() + ")");
                    } else {
                        listener.onStatus("RX audio source: " + names[i]
                                + " (built-in mic)");
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
                for (int i = 0; i < n; i++)
                    samples[i] = pcm[i] / 32768.0f;
                demodulator.addSamples(samples, n);
            }
            record.stop();
        } catch (Exception e) {
            listener.onStatus("RX thread failed: " + e.getMessage());
        } finally {
            if (record != null) {
                try { record.release(); } catch (Exception ignored) {}
            }
        }
    }
}
