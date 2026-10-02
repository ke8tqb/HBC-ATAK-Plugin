package com.atakmap.android.hbc;

import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.util.List;

/**
 * Forces the device's input-path voice effects OFF for a modem AudioRecord
 * session. Some OEM audio HALs attach noise suppression / AGC / echo
 * cancellation even to the VOICE_RECOGNITION source. A noise suppressor
 * treats steady narrowband tones — exactly what AFSK1200 is made of — as
 * stationary noise and notches them out, which silently kills AFSK receive
 * while the noise-like OFDM waveform survives. Creating each effect and
 * explicitly disabling it overrides whatever default the platform attached.
 *
 * The created {@link AudioEffect} instances must stay referenced for the
 * lifetime of the recording session (store them and release on stop);
 * letting them be garbage-collected can restore the platform default.
 */
public final class RxAudioEffects {

    private RxAudioEffects() {}

    /**
     * Disable NS/AGC/AEC on the record session where available.
     *
     * @param record the initialized AudioRecord
     * @param keep   live-reference list; created effects are added to it
     * @return human-readable suffix such as ", NS/AGC off" (empty if none)
     */
    public static String disable(AudioRecord record, List<AudioEffect> keep) {
        StringBuilder off = new StringBuilder();
        int session;
        try {
            session = record.getAudioSessionId();
        } catch (Throwable t) {
            return "";
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(session);
                if (ns != null) {
                    ns.setEnabled(false);
                    keep.add(ns);
                    append(off, "NS");
                }
            }
        } catch (Throwable ignored) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl agc = AutomaticGainControl.create(session);
                if (agc != null) {
                    agc.setEnabled(false);
                    keep.add(agc);
                    append(off, "AGC");
                }
            }
        } catch (Throwable ignored) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler aec = AcousticEchoCanceler.create(session);
                if (aec != null) {
                    aec.setEnabled(false);
                    keep.add(aec);
                    append(off, "AEC");
                }
            }
        } catch (Throwable ignored) {}
        return off.length() == 0 ? "" : ", " + off + " off";
    }

    /** Release all effects created by {@link #disable} and clear the list. */
    public static void release(List<AudioEffect> effects) {
        for (AudioEffect e : effects) {
            try { e.release(); } catch (Throwable ignored) {}
        }
        effects.clear();
    }

    private static void append(StringBuilder sb, String tag) {
        if (sb.length() > 0) sb.append('/');
        sb.append(tag);
    }
}
