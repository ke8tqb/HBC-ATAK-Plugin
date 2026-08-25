package com.atakmap.android.hbc;

/**
 * JNI bindings for the aicodix rattlegram-short OFDM modem (COFDMTV).
 * Payload buffers are always 170 bytes; unused bytes must be 0x00.
 */
public final class OfdmNative {

    public static final int PAYLOAD_BYTES = 170;

    // decoder process() status codes (decoder.hh)
    public static final int STATUS_OKAY = 0;
    public static final int STATUS_FAIL = 1;
    public static final int STATUS_SYNC = 2;
    public static final int STATUS_DONE = 3;
    public static final int STATUS_HEAP = 4;
    public static final int STATUS_NOPE = 5;
    public static final int STATUS_PING = 6;

    static {
        System.loadLibrary("hbcofdm");
    }

    private OfdmNative() {}

    public static native boolean createEncoder(int sampleRate);
    public static native void destroyEncoder();
    public static native void configureEncoder(byte[] payload170, byte[] callSignCstr,
                                               int carrierFrequency, int noiseSymbols,
                                               boolean fancyHeader);
    /** Fills audioBuffer with extended_length samples; false when finished. */
    public static native boolean produceEncoder(short[] audioBuffer, int channelSelect);

    public static native boolean createDecoder(int sampleRate);
    public static native void destroyDecoder();
    /** True when enough samples were accumulated to call processDecoder(). */
    public static native boolean feedDecoder(short[] audioBuffer, int sampleCount,
                                             int channelSelect);
    public static native int processDecoder();
    public static native void stagedDecoder(float[] cfo1, int[] mode1, byte[] callSign10);
    /** Fills payload170; negative return = unrecoverable decode failure. */
    public static native int fetchDecoder(byte[] payload170);
}
