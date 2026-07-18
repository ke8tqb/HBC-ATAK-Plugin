package com.atakmap.android.hbc.audio;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HBCAudioMonitor
 *
 * Background thread that continuously captures mono 16-bit PCM audio at 8000 Hz
 * from the selected input device. Uses a simple amplitude squelch to detect when
 * a signal is present, then accumulates audio until quiet and passes the buffer
 * to OFDMModem for decoding.
 *
 * On a successful decode the CoTListener callback is called with the recovered
 * CoT XML string, which HBCMapComponent injects into ATAK's map.
 */
public class HBCAudioMonitor {

    private static final String TAG = "HBCAudioMonitor";

    public static final int SAMPLE_RATE    = 8000;
    private static final int CHUNK_SAMPLES = 1024;             // ~128 ms per chunk
    private static final int MAX_BUF_SEC   = 30;               // max recording window
    private static final int MAX_BUF_SAMP  = SAMPLE_RATE * MAX_BUF_SEC;
    private static final float SQUELCH_THRESHOLD = 500f;       // RMS amplitude (0–32767)
    private static final int QUIET_CHUNKS_NEEDED = 8;          // ~1s of quiet to trigger decode

    // ─── Callback ────────────────────────────────────────────────────────────

    public interface CoTListener {
        void onCoTReceived(String cotXml);
    }

    // ─── State ───────────────────────────────────────────────────────────────

    private final AtomicBoolean running  = new AtomicBoolean(false);
    private CoTListener           listener;
    private AudioDeviceInfo       preferredInputDevice;
    private Thread                thread;

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static HBCAudioMonitor instance;
    public static synchronized HBCAudioMonitor getInstance() {
        if (instance == null) instance = new HBCAudioMonitor();
        return instance;
    }
    private HBCAudioMonitor() {}

    // ─── Configuration ───────────────────────────────────────────────────────

    public void setCoTListener(CoTListener l)             { this.listener = l; }
    public void setPreferredInputDevice(AudioDeviceInfo d){ this.preferredInputDevice = d; }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    public void start() {
        if (running.getAndSet(true)) return;
        thread = new Thread(this::monitorLoop, "HBC-RX");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "RX monitor started");
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        Log.i(TAG, "RX monitor stopped");
    }

    // ─── Recording loop ──────────────────────────────────────────────────────

    private void monitorLoop() {
        int minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);

        AudioRecord recorder = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(minBuf, CHUNK_SAMPLES * 2));

        if (preferredInputDevice != null)
            recorder.setPreferredDevice(preferredInputDevice);

        recorder.startRecording();
        Log.i(TAG, "AudioRecord started on device: " +
            (preferredInputDevice != null ? preferredInputDevice.getProductName() : "default"));

        short[] chunk   = new short[CHUNK_SAMPLES];
        ArrayList<short[]> capturedChunks = new ArrayList<>();
        int quietCount  = 0;
        boolean inFrame = false;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int read = recorder.read(chunk, 0, CHUNK_SAMPLES);
            if (read <= 0) continue;

            float rms = rms(chunk, read);
            boolean active = rms > SQUELCH_THRESHOLD;

            if (active) {
                // Signal detected — accumulate audio
                if (!inFrame) {
                    capturedChunks.clear();
                    inFrame = true;
                    Log.d(TAG, "Signal detected (RMS=" + (int)rms + ") — recording");
                }
                quietCount = 0;
                short[] copy = new short[read];
                System.arraycopy(chunk, 0, copy, 0, read);
                capturedChunks.add(copy);

                // Safety: drop oldest chunks if buffer is full
                while (totalSamples(capturedChunks) > MAX_BUF_SAMP && !capturedChunks.isEmpty())
                    capturedChunks.remove(0);

            } else if (inFrame) {
                // Channel went quiet
                quietCount++;
                short[] copy = new short[read];
                System.arraycopy(chunk, 0, copy, 0, read);
                capturedChunks.add(copy);  // include the quiet tail

                if (quietCount >= QUIET_CHUNKS_NEEDED) {
                    // Enough quiet — attempt decode
                    inFrame = false;
                    quietCount = 0;
                    int total = totalSamples(capturedChunks);
                    Log.d(TAG, "Attempting decode on " + total + " samples (" +
                        (total / SAMPLE_RATE) + "s)");
                    attemptDecode(capturedChunks, total);
                    capturedChunks.clear();
                }
            }
        }

        recorder.stop();
        recorder.release();
        Log.i(TAG, "AudioRecord stopped");
    }

    // ─── Decode attempt ──────────────────────────────────────────────────────

    private void attemptDecode(ArrayList<short[]> chunks, int totalSamples) {
        // Flatten into one array
        short[] all = new short[totalSamples];
        int pos = 0;
        for (short[] c : chunks) {
            System.arraycopy(c, 0, all, pos, c.length);
            pos += c.length;
        }

        byte[] hbcBytes = OFDMModem.getInstance()
            .decodeFromAudio(all, SAMPLE_RATE);

        if (hbcBytes == null) {
            Log.d(TAG, "No HBC frame found");
            return;
        }

        String cotXml = com.atakmap.android.hbc.hbc.HBCDecoder.decode(hbcBytes);
        if (cotXml == null) {
            Log.w(TAG, "HBC decode produced null CoT");
            return;
        }

        Log.i(TAG, "RX success — injecting CoT");
        if (listener != null) listener.onCoTReceived(cotXml);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static float rms(short[] buf, int len) {
        long sum = 0;
        for (int i = 0; i < len; i++) sum += (long) buf[i] * buf[i];
        return (float) Math.sqrt((double) sum / len);
    }

    private static int totalSamples(ArrayList<short[]> chunks) {
        int n = 0;
        for (short[] c : chunks) n += c.length;
        return n;
    }
}
