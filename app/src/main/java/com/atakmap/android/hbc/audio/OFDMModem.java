package com.atakmap.android.hbc.audio;

import android.util.Log;

/**
 * OFDMModem — JNI wrapper for the aicodix OFDM modem (short branch).
 *
 * Exposes two native methods:
 *   encodeHBC  — HBC bytes → mono 16-bit PCM samples (for TX)
 *   decodeFromAudio — mono 16-bit PCM samples → HBC bytes (for RX)
 *
 * The native code lives in app/src/main/cpp/hbc_jni.cpp and is compiled
 * into libhbc-ofdm.so by the NDK CMake build.
 */
public class OFDMModem {

    private static final String TAG = "OFDMModem";

    static {
        try {
            System.loadLibrary("hbc-ofdm");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libhbc-ofdm.so: " + e.getMessage());
        }
    }

    // ─── Native methods ───────────────────────────────────────────────────────

    /**
     * Encode HBC payload bytes to mono 16-bit PCM samples.
     *
     * @param payload   HBC binary payload from HBCEncoder.encode() (≤170 bytes)
     * @param callsign  Operator's ham radio callsign (e.g. "KE8TQB"), embedded
     *                  in the OFDM modem header per FCC Part 97.
     * @param sampleRate Audio sample rate in Hz (use 8000).
     * @return          Mono 16-bit PCM samples, or null on error.
     *                  Feed directly to RadioAudioTransmitter.transmit().
     */
    public native short[] encodeHBC(byte[] payload, String callsign, int sampleRate);

    /**
     * Decode mono 16-bit PCM samples to HBC payload bytes.
     *
     * @param samples    Captured audio from AudioRecord (mono, 16-bit, 8000 Hz).
     * @param sampleRate Sample rate of the input audio (use 8000).
     * @return           Decoded HBC bytes for HBCDecoder.decode(), or null if
     *                   no valid OFDM frame was found in the audio.
     */
    public native byte[] decodeFromAudio(short[] samples, int sampleRate);

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static OFDMModem instance;

    public static synchronized OFDMModem getInstance() {
        if (instance == null) instance = new OFDMModem();
        return instance;
    }

    private OFDMModem() {}
}
