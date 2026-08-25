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
 * OFDM audio modem using the aicodix rattlegram-short (COFDMTV) protocol via
 * the bundled native library. QPSK, 1600 Hz bandwidth, polar-coded with
 * CRC-32 — far more robust against device audio DSP, level and multipath
 * problems than AFSK1200, at the cost of AX.25 compatibility (no digipeater
 * path; the source callsign rides in the OFDM metadata).
 *
 * Payload framing: byte0 = HBC payload length (1..169), bytes1..n = HBC
 * bytes, zero-padded to 170. The length prefix is always non-zero, which is
 * also what the rattlegram mode auto-selection keys off.
 */
public class OfdmModem {

    public interface PayloadListener {
        /** CRC-valid decode: metadata callsign + de-framed HBC payload. */
        void onPayload(String callsign, byte[] hbcPayload);

        void onStatus(String message);
    }

    public static final int SAMPLE_RATE = 48000;
    private static final double TX_AMPLITUDE = 0.5;
    private static final int LEAD_SILENCE_MS = 150;
    private static final int TAIL_SILENCE_MS = 250;
    private static final int CARRIER_HZ = 1500;

    // extended_length = ((1280 * RATE) / 8000) * 9 / 8
    private static final int SYMBOL_SAMPLES =
            ((1280 * SAMPLE_RATE) / 8000) + ((1280 * SAMPLE_RATE) / 8000) / 8;

    private final Context context;
    private final PayloadListener listener;

    private Thread txThread, rxThread;
    private volatile boolean running = false;
    private volatile boolean transmitting = false;

    private volatile int voxLeaderMs = 0;
    private volatile int txStream = AudioManager.STREAM_ALARM;

    private static final class TxItem {
        final String callsign;
        final byte[] payload;
        TxItem(String c, byte[] p) { callsign = c; payload = p; }
    }

    private final Deque<TxItem> txQueue = new ArrayDeque<>();
    private final Object txLock = new Object();

    public OfdmModem(Context context, PayloadListener listener) {
        this.context = context;
        this.listener = listener;
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

    public synchronized void start() throws Exception {
        if (running) return;
        if (!OfdmNative.createEncoder(SAMPLE_RATE))
            throw new IllegalStateException("OFDM encoder init failed");
        if (!OfdmNative.createDecoder(SAMPLE_RATE))
            throw new IllegalStateException("OFDM decoder init failed");
        running = true;

        rxThread = new Thread(this::rxLoop, "HBC-OFDM-RX");
        rxThread.start();
        txThread = new Thread(this::txLoop, "HBC-OFDM-TX");
        txThread.start();
        listener.onStatus("Modem started (OFDM/rattlegram @ " + SAMPLE_RATE + " Hz)");
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

    /** Queue an HBC payload for transmission (callsign rides in metadata). */
    public void transmit(String callsign, byte[] hbcPayload) {
        if (hbcPayload.length < 1 || hbcPayload.length > 169) {
            listener.onStatus("OFDM: payload size " + hbcPayload.length + " unsupported");
            return;
        }
        synchronized (txLock) {
            txQueue.addLast(new TxItem(callsign, hbcPayload));
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

    private short[] renderTransmission(TxItem item) {
        byte[] payload = new byte[OfdmNative.PAYLOAD_BYTES];
        payload[0] = (byte) item.payload.length;
        System.arraycopy(item.payload, 0, payload, 1, item.payload.length);

        byte[] call = new byte[10]; // C string, max 9 chars
        String cs = item.callsign == null ? "HBC" : item.callsign.toUpperCase();
        for (int i = 0; i < 9 && i < cs.length(); i++) {
            char c = cs.charAt(i);
            call[i] = (byte) ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    ? c : ' ');
        }

        // VOX leader implemented via rattlegram noise symbols (~180 ms each)
        int symbolMs = 1000 * SYMBOL_SAMPLES / SAMPLE_RATE;
        int noiseSymbols = voxLeaderMs > 0
                ? Math.max(1, (voxLeaderMs + symbolMs - 1) / symbolMs) : 0;

        synchronized (OfdmNative.class) {
            OfdmNative.configureEncoder(payload, call, CARRIER_HZ, noiseSymbols, false);

            List<short[]> chunks = new ArrayList<>();
            int total = 0;
            int lead = LEAD_SILENCE_MS * SAMPLE_RATE / 1000;
            chunks.add(new short[lead]);
            total += lead;

            short[] block = new short[SYMBOL_SAMPLES];
            boolean more = true;
            int guard = 0;
            while (more && guard++ < 1000) {
                more = OfdmNative.produceEncoder(block, 0);
                short[] c = new short[SYMBOL_SAMPLES];
                for (int i = 0; i < SYMBOL_SAMPLES; i++)
                    c[i] = (short) (block[i] * TX_AMPLITUDE);
                chunks.add(c);
                total += SYMBOL_SAMPLES;
            }

            int tail = TAIL_SILENCE_MS * SAMPLE_RATE / 1000;
            chunks.add(new short[tail]);
            total += tail;

            short[] pcm = new short[total];
            int off = 0;
            for (short[] c : chunks) {
                System.arraycopy(c, 0, pcm, off, c.length);
                off += c.length;
            }
            return pcm;
        }
    }

    private void txLoop() {
        try {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception ignored) {}
        while (running) {
            TxItem item;
            synchronized (txLock) {
                while (running && txQueue.isEmpty()) {
                    try { txLock.wait(500); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
                item = txQueue.pollFirst();
            }
            if (item == null) continue;

            transmitting = true;
            AudioTrack track = null;
            try {
                short[] pcm = renderTransmission(item);

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
                listener.onStatus("TX OFDM " + item.payload.length + " B ("
                        + durMs + " ms burst)");
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

            short[] pcm = new short[SYMBOL_SAMPLES];
            String stagedCall = "";
            while (running) {
                int n = record.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                if (transmitting) continue; // half duplex

                boolean ready;
                synchronized (OfdmNative.class) {
                    ready = OfdmNative.feedDecoder(pcm, n, 0);
                }
                if (!ready) continue;

                int status;
                synchronized (OfdmNative.class) {
                    status = OfdmNative.processDecoder();
                }
                if (status == OfdmNative.STATUS_SYNC) {
                    float[] cfo = new float[1];
                    int[] mode = new int[1];
                    byte[] call = new byte[10];
                    synchronized (OfdmNative.class) {
                        OfdmNative.stagedDecoder(cfo, mode, call);
                    }
                    stagedCall = cstr(call);
                    listener.onStatus("OFDM sync: " + stagedCall
                            + " mode " + mode[0]
                            + " cfo " + String.format("%.1f", cfo[0]) + " Hz");
                } else if (status == OfdmNative.STATUS_DONE) {
                    byte[] payload = new byte[OfdmNative.PAYLOAD_BYTES];
                    int result;
                    synchronized (OfdmNative.class) {
                        result = OfdmNative.fetchDecoder(payload);
                    }
                    if (result < 0) {
                        listener.onStatus("OFDM decode failed (uncorrectable)");
                        continue;
                    }
                    int len = payload[0] & 0xFF;
                    if (len < 1 || len > 169) {
                        listener.onStatus("OFDM: bad length prefix " + len);
                        continue;
                    }
                    byte[] hbc = new byte[len];
                    System.arraycopy(payload, 1, hbc, 0, len);
                    listener.onPayload(stagedCall, hbc);
                } else if (status == OfdmNative.STATUS_PING) {
                    float[] cfo = new float[1];
                    int[] mode = new int[1];
                    byte[] call = new byte[10];
                    synchronized (OfdmNative.class) {
                        OfdmNative.stagedDecoder(cfo, mode, call);
                    }
                    listener.onStatus("OFDM ping from " + cstr(call));
                } else if (status == OfdmNative.STATUS_FAIL) {
                    listener.onStatus("OFDM preamble decode failed");
                }
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

    private static String cstr(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            if (x == 0) break;
            sb.append((char) (x & 0xFF));
        }
        return sb.toString().trim();
    }
}
