package com.atakmap.android.hbc;

import java.util.Random;

/**
 * Energy-based carrier sense + p-persistent CSMA backoff, shared by all
 * three modems (AFSK1200, OFDM, Mercury HF).
 *
 * Carrier sense: every RX audio chunk is fed here (except while this
 * station itself is transmitting). The detector keeps a slow moving
 * average of the quiet-channel RMS (the noise floor) and declares the
 * channel busy while the incoming RMS exceeds SNR_FACTOR times that
 * floor (or an absolute minimum level, whichever is greater). Busy state
 * is held for BUSY_HOLD_MS past the last loud chunk so brief gaps inside
 * a transmission (symbol transitions, fading) do not look like idle air.
 *
 * Backoff: before transmitting, {@link #waitForClear} waits for the
 * channel to go idle, then delays a random 150-550 ms while re-checking
 * the channel. If another station grabs the channel during the backoff,
 * the wait restarts. This unsynchronizes stations that queued traffic
 * while a third station was transmitting — the main collision source.
 * After maxWaitMs the transmission proceeds regardless, so traffic can
 * never be starved by a stuck-open squelch or constant interference.
 */
public final class CsmaSense {

    /** Optional extra carrier-sense input (e.g. the AFSK demodulator's DCD). */
    public interface ExtraCarrierSense {
        boolean busy();
    }

    private static final double SNR_FACTOR = 4.0;           // busy above 4x noise floor
    private static final double MIN_RMS = 150.0 / 32768.0;  // absolute busy threshold
    private static final int BUSY_HOLD_MS = 400;            // bridges in-signal gaps

    // v0.27: a real transmission lasts seconds. If "busy" persists far
    // longer, the loud level IS the channel (open squelch / hot Digirig RX
    // line / constant interference) — re-baseline the floor toward it so
    // carrier sense releases instead of latching busy forever (which
    // starved the Ring MAC's own turn in the field).
    private static final long BUSY_REBASELINE_MS = 10_000;

    private final Random random = new Random();
    private volatile long lastBusyMs = 0;
    private volatile long busyEpisodeStartMs = 0;
    private volatile double noiseFloor = -1;

    /** Forget the learned noise floor (RX device changed — v0.27). */
    public void reset() {
        noiseFloor = -1;
        lastBusyMs = 0;
        busyEpisodeStartMs = 0;
    }

    /** Feed an RX audio chunk. Do NOT call while this station is transmitting. */
    public void feed(short[] pcm, int n) {
        if (pcm == null || n <= 0) return;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = pcm[i] / 32768.0;
            sum += v * v;
        }
        double rms = Math.sqrt(sum / n);

        double floor = noiseFloor;
        if (floor < 0) {           // first chunk: seed the floor
            noiseFloor = rms;
            return;
        }
        if (rms < floor) {
            // channel got quieter — track down quickly so a floor seeded
            // during a burst cannot stay stuck high
            noiseFloor = 0.8 * floor + 0.2 * rms;
            busyEpisodeStartMs = 0;
        } else if (rms > Math.max(MIN_RMS, floor * SNR_FACTOR)) {
            long now = System.currentTimeMillis();
            boolean continuing = now - lastBusyMs < BUSY_HOLD_MS * 2;
            lastBusyMs = now;
            if (!continuing || busyEpisodeStartMs == 0) {
                busyEpisodeStartMs = now;
            } else if (now - busyEpisodeStartMs > BUSY_REBASELINE_MS) {
                // persistent "carrier": absorb it into the noise floor so
                // isBusy() can release once the floor catches up
                noiseFloor = 0.95 * floor + 0.05 * rms;
            }
        } else {
            // ordinary quiet chunk: slow upward drift of the floor
            noiseFloor = 0.99 * floor + 0.01 * rms;
            busyEpisodeStartMs = 0;
        }
    }

    /** True while signal energy was detected within the last BUSY_HOLD_MS. */
    public boolean isBusy() {
        return System.currentTimeMillis() - lastBusyMs < BUSY_HOLD_MS;
    }

    /**
     * Wait for a clear channel plus a random 150-550 ms backoff during
     * which the channel must stay clear.
     *
     * @param maxWaitMs give up and allow the TX after this long
     * @param extra     optional additional carrier-sense (may be null)
     * @return true if the channel was clear (including backoff); false if
     *         the wait timed out and the caller is transmitting anyway
     */
    public boolean waitForClear(long maxWaitMs, ExtraCarrierSense extra)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (busyNow(extra)) {
                Thread.sleep(50);
                continue;
            }
            // channel idle: random backoff, restart if it goes busy again
            long backoffEnd = System.currentTimeMillis() + 150 + random.nextInt(400);
            boolean clear = true;
            while (System.currentTimeMillis() < backoffEnd) {
                Thread.sleep(25);
                if (busyNow(extra)) {
                    clear = false;
                    break;
                }
            }
            if (clear) return true;
        }
        return false;
    }

    private boolean busyNow(ExtraCarrierSense extra) {
        return isBusy() || (extra != null && extra.busy());
    }
}
