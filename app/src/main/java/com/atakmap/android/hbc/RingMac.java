package com.atakmap.android.hbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Ring MAC — decentralized, collision-free channel access for up to ~20
 * stations sharing one simplex audio/RF channel through VOX-keyed radios.
 *
 * The schedule IS the sorted roster: every station sorts the callsigns it
 * knows (its own + the mesh route table) and rotates through them. The
 * "token" is implied — no token packet, no coordinator, no wire change.
 * A station transmits only during its own turn (one batched burst), and
 * every station advances the turn pointer locally on the FIRST of:
 *
 *   1. EARLY RELEASE — the owner's carrier was heard and the channel has
 *      been clear for RING_GUARD ms (guard absorbs the radio's VOX hang;
 *      UV-5R / FT-65 hold the carrier ~1.0-1.5 s after audio stops and
 *      neither radio's hang is adjustable).
 *   2. SILENT SKIP — the owner never keyed within RING_SKIP ms of turn
 *      start (covers idle stations; skip must cover VOX attack ~250 ms).
 *   3. DEADLINE — MAX_TURN + GUARD ms elapsed since turn start (covers
 *      hidden terminals and dead stations; keeps the ring alive even for
 *      stations that cannot hear the current owner).
 *
 * All timing is relative (monotonic-ish clock via Hooks.nowMs) — no
 * network time sync is needed. Hearing a decoded mesh frame re-aligns the
 * local pointer to the actual transmitter (onHeardTransmitter), so rings
 * converge even after local drift.
 *
 * Mixed networks degrade safely: a legacy-CSMA station transmitting out
 * of turn is simply carrier that the busy-interlock and guard absorb.
 *
 * Pure Java: all Android/modem interaction goes through {@link Hooks}, and
 * {@link #tick(long)} is public so JVM tests can drive time manually
 * (same pattern as MeshRouter.tick).
 */
public final class RingMac {

    /** Defaults sized for Baofeng UV-5R / Yaesu FT-65 VOX behavior. */
    public static final int DEFAULT_GUARD_MS = 1500;
    public static final int DEFAULT_SKIP_MS  = 1200;
    public static final int DEFAULT_MAX_TURN_MS = 6000;

    private static final long SETTLE_FALLBACK_MS = 30_000;
    private static final int  DORMANT_AFTER_MISSES = 3;

    public interface Hooks {
        /** Monotonic-ish time in ms (System.currentTimeMillis on device). */
        long nowMs();

        /** True while carrier energy is heard on the channel (CsmaSense). */
        boolean channelBusy();

        /** True while THIS station's modem is playing out a burst. */
        boolean transmitting();

        /** Fresh mesh destinations (ham callsigns), excluding this station. */
        List<String> meshRoster();

        /** Frames queued and waiting for this station's turn. */
        int pendingFrames();

        /**
         * Hand up to {@code max} queued frames to the modem as one batch.
         * @return the number of frames actually released
         */
        int releaseFrames(int max);

        /** LOG-level line (Activity Log + session log). */
        void onStatus(String message);

        /** DBG-level line (session log only). */
        void onDebug(String message);
    }

    private final String myCall;
    private final Hooks hooks;
    private final Random random = new Random();

    private volatile int guardMs = DEFAULT_GUARD_MS;
    private volatile int skipMs = DEFAULT_SKIP_MS;
    private volatile int maxTurnMs = DEFAULT_MAX_TURN_MS;
    private volatile int maxFramesPerTurn = 4;

    private final List<String> roster = new ArrayList<>();
    private final Map<String, Integer> silentMisses = new HashMap<>();

    private volatile boolean running = false;
    private Thread ticker;

    // per-turn state (guarded by this)
    private int turnIdx = 0;
    private long turnStartMs = 0;
    private boolean ownerKeyed = false;
    private long clearSinceMs = 0;      // busy->clear timestamp, 0 = still busy / never keyed
    private boolean ownTurnFired = false;

    // join settling: listen before taking a first turn
    private boolean settled = false;
    private long settleDeadlineMs = 0;
    private int advancesSeen = 0;

    // emergency preemption (Mode 2 alerts)
    private long emergencyAtMs = 0;

    public RingMac(String myCallsign, Hooks hooks) {
        this.myCall = myCallsign.toUpperCase(Locale.US);
        this.hooks = hooks;
    }

    public void setGuardMs(int ms)         { guardMs = Math.max(100, ms); }
    public void setSkipMs(int ms)          { skipMs = Math.max(200, ms); }
    public void setMaxTurnMs(int ms)       { maxTurnMs = Math.max(1000, ms); }
    public void setMaxFramesPerTurn(int n) { maxFramesPerTurn = Math.max(1, n); }

    public int getGuardMs()   { return guardMs; }
    public int getSkipMs()    { return skipMs; }
    public int getMaxTurnMs() { return maxTurnMs; }

    /** Current roster size (for the PLI auto-floor). */
    public synchronized int rosterSize() {
        return Math.max(1, roster.size());
    }

    public synchronized void start() {
        if (running) return;
        startManual();
        ticker = new Thread(() -> {
            while (running) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                try { tick(hooks.nowMs()); } catch (Exception ignored) {}
            }
        }, "HBC-RingMac");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** Start WITHOUT the background ticker — JVM tests drive tick(). */
    public synchronized void startManual() {
        if (running) return;
        running = true;
        long now = hooks.nowMs();
        applyRoster(now, true);
        turnIdx = 0;
        resetTurn(now);
        settled = roster.size() <= 1;   // alone: nothing to listen for
        settleDeadlineMs = now + SETTLE_FALLBACK_MS;
        advancesSeen = 0;
        hooks.onStatus("Ring: started (" + roster.size() + " station"
                + (roster.size() == 1 ? "" : "s") + ", guard " + guardMs
                + " ms, skip " + skipMs + " ms)");
    }

    public synchronized void stop() {
        running = false;
        if (ticker != null) {
            ticker.interrupt();
            try { ticker.join(1000); } catch (InterruptedException ignored) {}
            ticker = null;
        }
    }

    /**
     * A mesh frame was decoded with this transmitter callsign: hard
     * evidence of who owns the channel right now. Re-align the local turn
     * pointer — this is what keeps all stations' rings converged.
     */
    public synchronized void onHeardTransmitter(String call) {
        if (!running || call == null || call.isEmpty()) return;
        String c = call.toUpperCase(Locale.US);
        if (c.equals(myCall)) return;
        int idx = roster.indexOf(c);
        if (idx < 0) return;              // joins at next roster refresh
        silentMisses.put(c, 0);
        if (idx != turnIdx) {
            hooks.onDebug("Ring: sync to " + c + " (" + (idx + 1) + "/"
                    + roster.size() + ") [heard]");
            turnIdx = idx;
            turnStartMs = hooks.nowMs();
            ownTurnFired = false;
        }
        ownerKeyed = true;
        clearSinceMs = 0;
        if (!settled) {
            advancesSeen++;
            maybeSettle(hooks.nowMs());
        }
    }

    /** A Mode 2 alert was queued: may preempt in the next idle window. */
    public synchronized void flagEmergency() {
        if (!running) return;
        emergencyAtMs = hooks.nowMs() + random.nextInt(300);
        hooks.onStatus("Ring: emergency frame queued \u2014 preempting at next idle window");
    }

    /** One scheduler step; public so JVM tests can drive time manually. */
    public synchronized void tick(long now) {
        if (!running || roster.isEmpty()) return;

        boolean busy = hooks.channelBusy() || hooks.transmitting();
        if (busy) {
            ownerKeyed = true;
            clearSinceMs = 0;
        } else if (ownerKeyed && clearSinceMs == 0) {
            clearSinceMs = now;           // channel just went clear
        }

        // Emergency preemption: fire in any idle window, out of turn.
        if (emergencyAtMs > 0 && now >= emergencyAtMs) {
            if (!busy && hooks.pendingFrames() > 0) {
                int n = hooks.releaseFrames(maxFramesPerTurn);
                if (n > 0) {
                    hooks.onStatus("Ring: emergency TX (" + n
                            + " frame" + (n == 1 ? "" : "s") + ", preempt)");
                    ownerKeyed = true;    // our burst occupies the current turn
                    clearSinceMs = 0;
                    ownTurnFired = true;
                }
                emergencyAtMs = 0;
            } else if (!busy) {
                emergencyAtMs = 0;        // nothing left to send
            } else {
                emergencyAtMs = now + 100; // channel busy: retry shortly
            }
        }

        String owner = roster.get(turnIdx);
        boolean mine = owner.equals(myCall);

        // Own turn: release one batch as soon as the interlock allows.
        if (mine && settled && !ownTurnFired) {
            if (hooks.pendingFrames() <= 0) {
                advance(now, null);       // nothing to say: pass immediately
                return;
            }
            if (!busy) {
                int n = hooks.releaseFrames(maxFramesPerTurn);
                if (n > 0) {
                    hooks.onStatus("Ring: TX turn (" + (turnIdx + 1) + "/"
                            + roster.size() + ", " + n + " frame"
                            + (n == 1 ? "" : "s") + ")");
                    ownTurnFired = true;
                    ownerKeyed = true;
                    clearSinceMs = 0;
                }
            }
            // busy: interlock holds; the deadline below still protects us
        }

        // 1. EARLY RELEASE: owner finished, guard absorbed the VOX tail.
        if (ownerKeyed && clearSinceMs > 0 && now - clearSinceMs >= guardMs) {
            long burstMs = Math.max(0, clearSinceMs - turnStartMs);
            if (!mine)
                hooks.onDebug("Ring: " + owner + " released early ("
                        + burstMs + " ms)");
            silentMisses.put(owner, 0);
            advance(now, null);
            return;
        }

        // 2. SILENT SKIP: owner never keyed.
        if (!ownerKeyed && !mine && now - turnStartMs >= skipMs) {
            int misses = orZero(silentMisses.get(owner)) + 1;
            silentMisses.put(owner, misses);
            if (misses == DORMANT_AFTER_MISSES)
                hooks.onStatus("Ring: " + owner + " dormant ("
                        + DORMANT_AFTER_MISSES + " silent turns)");
            else
                hooks.onDebug("Ring: skip " + owner + " (silent)");
            advance(now, null);
            return;
        }

        // 3. DEADLINE: hidden terminal / stuck owner / our own stuck TX.
        if (now - turnStartMs >= (long) maxTurnMs + guardMs) {
            hooks.onDebug("Ring: deadline advance past " + owner);
            advance(now, null);
        }
    }

    // ------------------------------------------------------------------
    private void advance(long now, String reason) {
        turnIdx++;
        if (turnIdx >= roster.size()) {
            turnIdx = 0;
            applyRoster(now, false);      // roster changes only at cycle wrap
        }
        resetTurn(now);
        if (!settled) {
            advancesSeen++;
            maybeSettle(now);
        }
    }

    private void resetTurn(long now) {
        turnStartMs = now;
        ownerKeyed = false;
        clearSinceMs = 0;
        ownTurnFired = false;
    }

    private void maybeSettle(long now) {
        if (settled) return;
        if (advancesSeen >= roster.size() || now >= settleDeadlineMs) {
            settled = true;
            hooks.onStatus("Ring: joined rotation as " + myCall + " ("
                    + roster.size() + " stations)");
        }
    }

    private void applyRoster(long now, boolean initial) {
        List<String> fresh = new ArrayList<>();
        fresh.add(myCall);
        try {
            for (String c : hooks.meshRoster()) {
                if (c == null) continue;
                String u = c.toUpperCase(Locale.US);
                if (!u.isEmpty() && !fresh.contains(u))
                    fresh.add(u);
            }
        } catch (Exception ignored) {}
        Collections.sort(fresh);
        if (fresh.equals(roster)) return;

        String currentOwner = roster.isEmpty() ? null : roster.get(Math.min(turnIdx, roster.size() - 1));
        roster.clear();
        roster.addAll(fresh);
        silentMisses.keySet().retainAll(roster);
        int keep = currentOwner == null ? -1 : roster.indexOf(currentOwner);
        turnIdx = keep >= 0 ? keep : 0;
        if (!initial)
            hooks.onStatus("Ring: roster now " + roster.size() + " station"
                    + (roster.size() == 1 ? "" : "s") + " (" + join(roster) + ")");
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }
}
