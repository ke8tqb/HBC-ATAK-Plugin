package com.atakmap.android.hbc;

/**
 * Hardware PTT keying abstraction (v0.25). Implemented by the plugin's
 * Digirig RTS driver (UsbPtt); the modems call it around each rendered
 * transmission so the radio is keyed electrically instead of by VOX.
 */
public interface PttKeyer {

    /** Assert (true) or release (false) the transmitter key line. */
    void key(boolean tx);
}
