package com.atakmap.android.hbc;

/**
 * JNI bindings for the Mercury HF modem physical layer — the FreeDV DATAC
 * raw data modes (codec2 OFDM), vendored from Rhizomatica's Mercury tree.
 * All DATAC modes run at 8000 Hz, 16-bit mono.
 */
public final class MercuryNative {

    public static final int SAMPLE_RATE = 8000;

    /** Mode indices accepted by {@link #create(int)}. */
    public static final int MODE_DATAC4 = 0;  // ~87 bit/s, works to about -4 dB SNR
    public static final int MODE_DATAC3 = 1;  // ~321 bit/s, works to about 0 dB SNR
    public static final int MODE_DATAC1 = 2;  // ~980 bit/s, needs about 5 dB SNR

    static {
        System.loadLibrary("hbcmercury");
    }

    private MercuryNative() {}

    public static native boolean create(int mode);
    public static native void destroy();

    /** Usable payload bytes per modem frame (frame minus 16-bit CRC). */
    public static native int payloadBytesPerFrame();

    /** Worst-case burst PCM length in samples, for sizing txBurst buffers. */
    public static native int maxBurstSamples();

    /**
     * Render one burst (preamble + frame + CRC16 + postamble) for a payload
     * of exactly payloadBytesPerFrame() bytes. Returns samples written into
     * pcmOut, or -1 on error.
     */
    public static native int txBurst(byte[] frame, short[] pcmOut);

    /** Samples the demodulator wants next (freedv_nin). */
    public static native int rxNin();

    /** Upper bound for the rxProcess sample buffer. */
    public static native int rxMaxSamples();

    /**
     * Feed exactly rxNin() samples. Returns CRC-valid frame bytes written
     * to frameOut (payload + 2 CRC bytes) or 0 when no frame completed.
     */
    public static native int rxProcess(short[] pcm, byte[] frameOut);

    /** Demodulator sync state (0 = searching). */
    public static native int rxSync();
}
