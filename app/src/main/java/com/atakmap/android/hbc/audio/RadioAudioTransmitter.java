package com.atakmap.android.hbc.audio;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * RadioAudioTransmitter
 *
 * Plays a short[] of 16-bit PCM audio samples through the Android AudioTrack.
 * The output device is user-selectable (wired headset, USB audio, speaker, etc.)
 * and is set via setPreferredDevice().
 *
 * A configurable PTT delay (in milliseconds) inserts silence before the signal
 * so that external VOX or PTT hardware has time to key the radio.
 */
public class RadioAudioTransmitter {

    private static final String TAG = "RadioAudioTX";
    public  static final int SAMPLE_RATE = 8000;

    private AudioDeviceInfo preferredOutputDevice = null;
    private int pttDelayMs = 0;

    private volatile boolean transmitting = false;

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static RadioAudioTransmitter instance;
    public static synchronized RadioAudioTransmitter getInstance() {
        if (instance == null) instance = new RadioAudioTransmitter();
        return instance;
    }
    private RadioAudioTransmitter() {}

    // ─── Configuration ───────────────────────────────────────────────────────

    public void setPreferredOutputDevice(AudioDeviceInfo device) {
        this.preferredOutputDevice = device;
    }

    public void setPttDelayMs(int ms) {
        this.pttDelayMs = Math.max(0, ms);
    }

    // ─── Transmit ────────────────────────────────────────────────────────────

    /**
     * Transmit PCM samples asynchronously.
     * Returns immediately; the audio plays on a background thread.
     * Call isTransmitting() to check status.
     */
    public void transmit(final short[] samples) {
        if (samples == null || samples.length == 0) return;
        if (transmitting) {
            Log.w(TAG, "Already transmitting — skipping");
            return;
        }

        new Thread(() -> {
            transmitting = true;
            try {
                playAudio(samples);
            } finally {
                transmitting = false;
            }
        }, "HBC-TX").start();
    }

    public boolean isTransmitting() { return transmitting; }

    // ─── Internal ────────────────────────────────────────────────────────────

    private void playAudio(short[] samples) {
        int bufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT);

        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

        AudioFormat format = new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build();

        AudioTrack track = new AudioTrack(attrs, format, bufSize,
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);

        if (preferredOutputDevice != null)
            track.setPreferredDevice(preferredOutputDevice);

        track.play();

        // PTT delay — silence before signal so radio has time to key up
        if (pttDelayMs > 0) {
            int silenceSamples = (SAMPLE_RATE * pttDelayMs) / 1000;
            short[] silence = new short[silenceSamples];
            track.write(silence, 0, silence.length);
        }

        // Write PCM data in chunks
        int offset = 0;
        while (offset < samples.length) {
            int chunk = Math.min(bufSize / 2, samples.length - offset);
            track.write(samples, offset, chunk);
            offset += chunk;
        }

        track.stop();
        track.release();
        Log.i(TAG, "TX complete: " + samples.length + " samples");
    }
}
