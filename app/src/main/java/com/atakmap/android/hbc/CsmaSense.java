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

    // v0.27: if "busy" persists far longer than this modem's real
    // transmissions last, the loud level IS the channel (open squelch /
    // hot Digirig RX line / constant interference) — re-baseline the
    // floor toward it so carrier sense releases instead of latching busy
    // forever (which starved the Ring MAC's own turn in the field).
    //
    // v0.28: per-modem, because "far longer than a real transmission"
    // depends on the modem. 10 s suits AFSK/OFDM/Mercury (bursts of a
    // few seconds); a PSK31 burst is a CONTINUOUS carrier for up to
    // ~77 s, which the 10 s default would mistake for a stuck-open
    // squelch — releasing carrier sense mid-burst and defeating the
    // modem's long CSMA patience. Each modem owns its own CsmaSense and
    // configures this to exceed its own worst-case burst.
    private volatile long busyRebaselineMs = 10_000;

    /** Set the busy re-baseline window (see above). Per-modem tuning. */
    public void setBusyRebaselineMs(long ms) {
        busyRebaselineMs = Math.max(1000, ms);
    }

    private final Random random = new Random();
    private volatile long lastBusyMs = 0;
    private volatile long busyEpisodeStartMs = 0;
    private volatile double noiseFloor = -1;

    // ------------------------------------------------------------------
    // Live level telemetry for the Levels tab. Purely passive: feed()
    // already sees every RX chunk (never our own playout), so publishing
    // a short-window peak/rms plus clip/busy history here gives the UI
    // meters without touching any modem or any CSMA behavior.
    // ------------------------------------------------------------------
    private volatile double lastWinPeak = 0, lastWinRms = 0;
    private double bktPeak = 0, bktSumSq = 0;
    private long bktSamples = 0, bktStartMs = 0;
    private final boolean[] clipRing = new boolean[40];   // 40 x ~250 ms = 10 s
    private final boolean[] busyRing = new boolean[120];  // 120 x ~250 ms = 30 s
    private volatile int ringIdx = 0;
    private volatile long lastFeedMs = 0;

    /** Peak of the last completed ~250 ms window (0..1). */
    public double levelPeak() { return lastWinPeak; }

    /** RMS of the last completed ~250 ms window (0..1). */
    public double levelRms() { return lastWinRms; }

    /** Learned background-noise RMS (0..1); 0 until seeded. */
    public double noiseFloorRms() {
        double f = noiseFloor;
        return f < 0 ? 0 : f;
    }

    /** Fraction of the last ~10 s whose windows peaked at full scale. */
    public double clipFraction() {
        int len = Math.min(ringIdx, clipRing.length);
        if (len == 0) return 0;
        int c = 0;
        for (int i = 0; i < len; i++) if (clipRing[i]) c++;
        return (double) c / len;
    }

    /** Fraction of the last ~30 s sensed busy. */
    public double busyFraction() {
        int len = Math.min(ringIdx, busyRing.length);
        if (len == 0) return 0;
        int c = 0;
        for (int i = 0; i < len; i++) if (busyRing[i]) c++;
        return (double) c / len;
    }

    /** Milliseconds since the last RX audio chunk; MAX_VALUE if never. */
    public long msSinceAudio() {
        long t = lastFeedMs;
        return t == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

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
        double pk = 0;
        for (int i = 0; i < n; i++) {
            double v = pcm[i] / 32768.0;
            sum += v * v;
            double a = v < 0 ? -v : v;
            if (a > pk) pk = a;
        }
        double rms = Math.sqrt(sum / n);

        // Levels-tab telemetry (~250 ms wall-clock buckets); passive only.
        long nowTele = System.currentTimeMillis();
        lastFeedMs = nowTele;
        if (bktStartMs == 0) bktStartMs = nowTele;
        bktSumSq += sum;
        bktSamples += n;
        if (pk > bktPeak) bktPeak = pk;
        if (nowTele - bktStartMs >= 250 && bktSamples > 0) {
            lastWinPeak = bktPeak;
            lastWinRms = Math.sqrt(bktSumSq / bktSamples);
            clipRing[ringIdx % clipRing.length] = bktPeak >= 0.99;
            busyRing[ringIdx % busyRing.length] = isBusy();
            ringIdx++;
            bktPeak = 0;
            bktSumSq = 0;
            bktSamples = 0;
            bktStartMs = nowTele;
        }

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
            } else if (now - busyEpisodeStartMs > busyRebaselineMs) {
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
