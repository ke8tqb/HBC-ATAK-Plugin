package com.atakmap.android.hbc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Uncoordinated distance-vector mesh routing layer, implementing
 * "Adaptation of Uncoordinated Distance-Vector Routing for Unencrypted
 * Amateur Radio Networks" (Reticulum-style announce propagation without
 * cryptography). This layer sits between the HBC codec and whichever
 * audio modem is active; the modems are dumb byte pipes for mesh frames.
 *
 * Wire format — 13-byte common header on every packet:
 *   [0]     packet type: 0x01 Announce, 0x02 Broadcast, 0x03 Direct, 0x04 ACK
 *   [1-5]   origin callsign      (bit-packed ITA2, 8 codes = 40 bits)
 *   [6-10]  transmitter callsign (bit-packed ITA2)
 *   [11-12] sequence ID (unsigned 16-bit big-endian)
 * Announce: +1 byte hop count.
 * Broadcast: + payload (HBC binary — project deviation from the paper's
 * ITA2-text payload, approved for airtime efficiency).
 * Direct: +5 bytes destination, +5 bytes next hop, + payload.
 * ACK: +5 bytes destination (the original origin), +2 bytes acked seq.
 *
 * Callsign fields use the paper's ITA2 variant (MSB-first, LTRS=11111,
 * FIGS=11011, NULL=00000; e.g. K1ABC packs to FF B7 BF E2 6E), which is
 * the bit-reversal of HBC's own ITA2 table. Fields are exactly 8 codes,
 * NULL-padded. When a callsign needs 9 codes with its leading LTRS (e.g.
 * KE8TQB), the leading LTRS is omitted — the decoder starts in letters
 * state, so the paper's examples and shift-tight callsigns both decode.
 *
 * Behavior per the paper's state machine:
 *  - Periodic Announces (hops=0) flood the mesh; each node rebroadcasts
 *    unseen announces with hops+1 and itself as transmitter, learning
 *    Destination -> (Next hop = transmitter, hops). Lowest hop count wins;
 *    ties resolve "first heard" via the dedup cache. Routes expire after
 *    30 minutes without a fresh announce.
 *  - [origin+seq] dedup cache (5-minute expiry) prevents loops/storms.
 *  - Direct messages are routed hop-by-hop: a node whose callsign matches
 *    the Next Hop field rewrites next-hop/transmitter and re-transmits.
 *    The final destination replies with an ACK routed back to the origin.
 *  - The sender retries an unacknowledged Direct with the SAME sequence ID,
 *    up to 3 retries. Because retries reuse the seq, the destination
 *    re-ACKs (without re-delivering) when it sees a cached [origin+seq]
 *    Direct addressed to it — covering a lost ACK.
 *  - v0.23: the retry clock only starts once a copy actually AIRS
 *    (notifyTransmitted) — under a queueing MAC (Ring) a frame can wait
 *    many seconds for its turn, and retrying a frame that never
 *    transmitted just duplicates it in the queue. Retry pacing is
 *    pluggable (RetryPolicy) so the hosting MAC can scale it to its
 *    rotation time; the default stays 5 s + jitter (CSMA behavior).
 */
public final class MeshRouter {

    public static final int TYPE_ANNOUNCE  = 0x01;
    public static final int TYPE_BROADCAST = 0x02;
    public static final int TYPE_DIRECT    = 0x03;
    public static final int TYPE_ACK       = 0x04;

    public static final int HEADER_LEN = 13;
    public static final int CALLSIGN_LEN = 5;     // 8 ITA2 codes bit-packed

    private static final long ROUTE_TTL_MS   = 30 * 60 * 1000L;
    private static final long CACHE_TTL_MS   = 5 * 60 * 1000L;
    private static final long CLEANUP_MS     = 60 * 1000L;
    private static final long RETRY_BASE_MS  = 5000L;
    private static final long RETRY_JITTER_MS = 2000L;
    private static final int  MAX_RETRIES    = 3;
    // A pending whose latest copy never airs (stuck MAC queue) is failed
    // outright after this long instead of retrying into the same queue.
    private static final long STUCK_FAIL_MS  = 180_000L;

    public interface Callbacks {
        /** A Broadcast/Direct payload for this station (HBC binary). */
        void onHbcPayload(String origin, byte[] hbc);
        /** Hand a fully built mesh frame to the active modem. */
        void transmitFrame(byte[] frame);
        void onStatus(String message);
        /**
         * Any frame was decoded with this transmitter callsign — hard
         * evidence of who holds the channel right now (used by the Ring
         * MAC to keep all stations' turn pointers converged).
         */
        default void onHeardTransmitter(String transmitter) {}
    }

    /**
     * Pluggable retry pacing (v0.23): delay from an AIRED, still
     * unacknowledged Direct to its next retry attempt. Under the Ring MAC
     * the plugin scales this to the measured rotation time — the fixed
     * CSMA-era 5 s fires long before a turn-based ACK can possibly return.
     */
    public interface RetryPolicy {
        long retryDelayMs(int tries);
    }

    private static final class Route {
        String nextHop; int hops; long lastSeen;
    }

    private static final class Pending {
        String dest; byte[] hbc; int seq; int tries; long nextAttemptMs;
        boolean aired;      // latest copy actually left the modem
        long queuedAtMs;    // when the latest copy entered the TX path
    }

    private final String myCall;
    private final Callbacks cb;
    private final Random random = new Random();

    private final Map<String, Route> routes = new TreeMap<>();
    private final Map<String, Long> seenCache = new LinkedHashMap<>();
    private final Map<Integer, Pending> pendingDirects = new HashMap<>();

    private int seqCounter;
    private volatile RetryPolicy retryPolicy = null;   // null = 5 s default
    private volatile long announceIntervalMs = 10 * 60 * 1000L;
    private volatile boolean running = false;
    private Thread timerThread;
    private long nextAnnounceMs = 0;
    private long nextCleanupMs = 0;

    public MeshRouter(String myCallsign, Callbacks callbacks) {
        this.myCall = myCallsign.toUpperCase();
        this.cb = callbacks;
        this.seqCounter = new Random().nextInt(0x10000);
    }

    /** minutes between periodic announces; 0 disables periodic announces. */
    public void setAnnounceIntervalMin(int minutes) {
        announceIntervalMs = Math.max(0, minutes) * 60_000L;
    }

    /** Install MAC-aware retry pacing; null restores the 5 s default. */
    public void setRetryPolicy(RetryPolicy policy) {
        retryPolicy = policy;
    }

    private long retryDelay(int tries) {
        RetryPolicy rp = retryPolicy;
        if (rp != null) {
            try {
                return Math.max(1000L, rp.retryDelayMs(tries));
            } catch (Exception ignored) {}
        }
        return RETRY_BASE_MS + (long) random.nextInt((int) RETRY_JITTER_MS);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        nextAnnounceMs = now() + 3000;    // first announce shortly after start
        nextCleanupMs = now() + CLEANUP_MS;
        timerThread = new Thread(this::timerLoop, "HBC-Mesh-Timer");
        timerThread.setDaemon(true);
        timerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (timerThread != null) {
            timerThread.interrupt();
            try { timerThread.join(2000); } catch (InterruptedException ignored) {}
            timerThread = null;
        }
        synchronized (routes) {
            pendingDirects.clear();
        }
    }

    long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // TX API
    // ------------------------------------------------------------------

    /** Broadcast an HBC payload to everyone in RF range (unacknowledged). */
    public void sendBroadcast(byte[] hbc) {
        int seq = nextSeq();
        deferAnnounce();
        cb.transmitFrame(buildBroadcast(myCall, myCall, seq, hbc));
    }

    /**
     * Any of our own traffic (PLI broadcasts especially) already announces
     * this station to everyone who hears it via passive route learning, so
     * push the next periodic announce out by a full interval. Announces
     * then only fire as a keepalive when the station has been quiet.
     */
    private void deferAnnounce() {
        if (announceIntervalMs > 0)
            nextAnnounceMs = now() + announceIntervalMs;
    }

    /**
     * Send an HBC payload as a routed, acknowledged Direct message. Falls
     * back to a plain Broadcast (with a status log) when no route is known.
     */
    public void sendDirect(String destCallsign, byte[] hbc) {
        String dest = destCallsign.toUpperCase();
        String nextHop;
        synchronized (routes) {
            Route r = routes.get(dest);
            nextHop = r == null ? null : r.nextHop;
        }
        if (nextHop == null) {
            cb.onStatus("Mesh: no route to " + dest + " \u2014 sending as broadcast");
            sendBroadcast(hbc);
            return;
        }
        int seq = nextSeq();
        deferAnnounce();
        Pending p = new Pending();
        p.dest = dest; p.hbc = hbc; p.seq = seq; p.tries = 0;
        p.aired = false;
        p.queuedAtMs = now();
        p.nextAttemptMs = Long.MAX_VALUE;   // armed by notifyTransmitted()
        synchronized (routes) {
            pendingDirects.put(seq, p);
        }
        cb.onStatus("Mesh: direct to " + dest + " via " + nextHop
                + " (seq " + String.format("%04X", seq) + ")");
        cb.transmitFrame(buildDirect(myCall, myCall, seq, dest, nextHop, hbc));
    }

    /**
     * Send a routed Direct WITHOUT delivery tracking (v0.23): no pending
     * entry, no retries, no FAILED verdict. Used for traffic whose loss
     * is tolerable — chat delivered/read receipts — where the full ARQ
     * treatment turned every receipt into its own retry storm under the
     * Ring MAC. Wire format is unchanged: the recipient still mesh-ACKs,
     * and the origin simply has no pending entry to match.
     */
    public void sendDirectUnacked(String destCallsign, byte[] hbc) {
        String dest = destCallsign.toUpperCase();
        String nextHop;
        synchronized (routes) {
            Route r = routes.get(dest);
            nextHop = r == null ? null : r.nextHop;
        }
        if (nextHop == null) {
            cb.onStatus("Mesh: no route to " + dest + " \u2014 sending as broadcast");
            sendBroadcast(hbc);
            return;
        }
        int seq = nextSeq();
        deferAnnounce();
        cb.onStatus("Mesh: direct to " + dest + " via " + nextHop
                + " (seq " + String.format("%04X", seq) + ", unacked)");
        cb.transmitFrame(buildDirect(myCall, myCall, seq, dest, nextHop, hbc));
    }

    /**
     * The hosting layer reports that a frame has actually left the modem.
     * Under the Ring MAC a frame can sit in the turn queue long after
     * transmitFrame(), so the retry clock for a pending Direct is armed
     * HERE — for the first copy and for every retry copy alike. Matches
     * own-origin Direct frames and the broadcast fallback copies a retry
     * can produce (same sequence ID).
     */
    public void notifyTransmitted(byte[] frame) {
        if (frame == null || frame.length < HEADER_LEN) return;
        int type = frame[0] & 0xFF;
        if (type != TYPE_DIRECT && type != TYPE_BROADCAST) return;
        String origin;
        try {
            origin = unpackCallsign(frame, 1);
        } catch (Exception e) {
            return;
        }
        if (!origin.equalsIgnoreCase(myCall)) return;
        int seq = ((frame[11] & 0xFF) << 8) | (frame[12] & 0xFF);
        synchronized (routes) {
            Pending p = pendingDirects.get(seq);
            if (p == null || p.aired) return;
            p.aired = true;
            p.nextAttemptMs = now() + retryDelay(p.tries);
        }
    }

    /** Send an immediate announce (also called by the periodic timer). */
    public void announceNow() {
        int seq = nextSeq();
        cb.transmitFrame(buildAnnounce(myCall, myCall, seq, 0));
        cb.onStatus("Mesh: announce sent (seq " + String.format("%04X", seq) + ")");
    }

    /** Known destinations (fresh routes), sorted, for the Send-to UI. */
    public List<String> knownDestinations() {
        List<String> out = new ArrayList<>();
        long cutoff = now() - ROUTE_TTL_MS;
        synchronized (routes) {
            for (Map.Entry<String, Route> e : routes.entrySet())
                if (e.getValue().lastSeen >= cutoff)
                    out.add(e.getKey());
        }
        return out;
    }

    /** Human-readable routing table for the log. */
    public String routingTableSummary() {
        StringBuilder sb = new StringBuilder();
        synchronized (routes) {
            if (routes.isEmpty()) return "(no routes)";
            for (Map.Entry<String, Route> e : routes.entrySet()) {
                Route r = e.getValue();
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getKey()).append(" via ").append(r.nextHop)
                  .append(" (").append(r.hops + 1).append(" hop")
                  .append(r.hops == 0 ? "" : "s").append(")");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // RX entry point
    // ------------------------------------------------------------------
    public void onRadioFrame(byte[] f) {
        try {
            if (f == null || f.length < HEADER_LEN) return;
            int type = f[0] & 0xFF;
            String origin = unpackCallsign(f, 1);
            String transmitter = unpackCallsign(f, 6);
            int seq = ((f[11] & 0xFF) << 8) | (f[12] & 0xFF);
            if (origin.isEmpty() || origin.equalsIgnoreCase(myCall))
                return;   // own packet heard back — never route self

            if (!transmitter.isEmpty() && !transmitter.equalsIgnoreCase(myCall))
                cb.onHeardTransmitter(transmitter);

            String sig = origin + "|" + seq + "|" + type;
            boolean duplicate = checkAndCache(sig);

            // Passive route learning: hearing ANY frame is proof of a path.
            // The transmitter of this hop is a direct neighbor (0 hops), and
            // for non-announce frames the origin is reachable via that
            // transmitter. This makes a station selectable as a destination
            // as soon as we hear a PLI/marker/chat from it — no announce or
            // prior chat required. (Announces manage the origin themselves.)
            if (type >= TYPE_ANNOUNCE && type <= TYPE_ACK) {
                if (!transmitter.isEmpty() && !transmitter.equalsIgnoreCase(myCall))
                    learnRoute(transmitter, transmitter, 0);
                if (type != TYPE_ANNOUNCE && !origin.equalsIgnoreCase(transmitter))
                    learnRoute(origin, transmitter, 1);
            }

            switch (type) {
                case TYPE_ANNOUNCE:
                    if (duplicate) return;
                    handleAnnounce(f, origin, transmitter, seq);
                    break;
                case TYPE_BROADCAST:
                    if (duplicate) return;
                    handleBroadcast(f, origin);
                    break;
                case TYPE_DIRECT:
                    handleDirect(f, origin, transmitter, seq, duplicate);
                    break;
                case TYPE_ACK:
                    if (duplicate) return;
                    handleAck(f, origin);
                    break;
                default:
                    // unknown type — not a mesh frame
                    break;
            }
        } catch (Exception e) {
            cb.onStatus("Mesh: RX frame error: " + e.getMessage());
        }
    }

    /**
     * Insert or refresh a routing-table entry. Existing routes are only
     * replaced by strictly better (fewer-hop) paths; equal-cost paths over
     * the same next hop just reset the TTL. Emits a "Mesh: route" status
     * (which also refreshes the Send-to UI) only when something changed.
     */
    private void learnRoute(String dest, String nextHop, int hops) {
        boolean changed = false;
        synchronized (routes) {
            Route r = routes.get(dest);
            if (r == null || hops < r.hops) {
                if (r == null) { r = new Route(); routes.put(dest, r); changed = true; }
                else if (!nextHop.equalsIgnoreCase(r.nextHop)) changed = true;
                r.nextHop = nextHop;
                r.hops = hops;
                r.lastSeen = now();
            } else if (hops == r.hops && nextHop.equalsIgnoreCase(r.nextHop)) {
                r.lastSeen = now();
            }
        }
        if (changed)
            cb.onStatus("Mesh: route " + dest + " via " + nextHop
                    + " (" + (hops + 1) + " hop" + (hops == 0 ? "" : "s") + ") [heard]");
    }

    private void handleAnnounce(byte[] f, String origin, String transmitter, int seq) {
        if (f.length < HEADER_LEN + 1) return;
        int hops = f[HEADER_LEN] & 0xFF;
        boolean updated = false;
        synchronized (routes) {
            Route r = routes.get(origin);
            if (r == null || hops < r.hops) {
                if (r == null) { r = new Route(); routes.put(origin, r); }
                r.nextHop = transmitter;
                r.hops = hops;
                r.lastSeen = now();
                updated = true;
            } else if (hops == r.hops && transmitter.equalsIgnoreCase(r.nextHop)) {
                r.lastSeen = now();   // fresh announce over the same path: reset TTL
                updated = true;
            }
        }
        if (updated) {
            cb.onStatus("Mesh: route " + origin + " via " + transmitter
                    + " (" + (hops + 1) + " hop" + (hops == 0 ? "" : "s") + ")");
            if (hops < 255)
                cb.transmitFrame(buildAnnounce(origin, myCall, seq, hops + 1));
        }
    }

    private void handleBroadcast(byte[] f, String origin) {
        if (f.length <= HEADER_LEN) return;
        byte[] hbc = new byte[f.length - HEADER_LEN];
        System.arraycopy(f, HEADER_LEN, hbc, 0, hbc.length);
        cb.onHbcPayload(origin, hbc);
    }

    private void handleDirect(byte[] f, String origin, String transmitter,
                              int seq, boolean duplicate) {
        if (f.length < HEADER_LEN + 2 * CALLSIGN_LEN) return;
        String dest = unpackCallsign(f, HEADER_LEN);
        String nextHop = unpackCallsign(f, HEADER_LEN + CALLSIGN_LEN);

        if (dest.equalsIgnoreCase(myCall)) {
            // Always (re-)ACK, even for duplicates — the retry we are seeing
            // usually means our previous ACK was lost.
            sendAck(origin, transmitter, seq);
            if (duplicate) return;
            byte[] hbc = new byte[f.length - HEADER_LEN - 2 * CALLSIGN_LEN];
            System.arraycopy(f, HEADER_LEN + 2 * CALLSIGN_LEN, hbc, 0, hbc.length);
            cb.onHbcPayload(origin, hbc);
            return;
        }
        if (duplicate) return;
        if (nextHop.equalsIgnoreCase(myCall)) {
            // We are a router on this path: rewrite next hop + transmitter.
            String forwardHop;
            synchronized (routes) {
                Route r = routes.get(dest);
                forwardHop = r == null ? null : r.nextHop;
            }
            if (forwardHop == null) {
                cb.onStatus("Mesh: no route to " + dest + " \u2014 direct dropped");
                return;
            }
            byte[] fwd = f.clone();
            packCallsign(myCall, fwd, 6);                          // transmitter
            packCallsign(forwardHop, fwd, HEADER_LEN + CALLSIGN_LEN); // next hop
            cb.onStatus("Mesh: forwarding " + origin + " -> " + dest
                    + " via " + forwardHop);
            cb.transmitFrame(fwd);
        }
        // else: overheard someone else's direct — dedup already cached it.
    }

    private void handleAck(byte[] f, String origin) {
        if (f.length < HEADER_LEN + CALLSIGN_LEN + 2) return;
        String dest = unpackCallsign(f, HEADER_LEN);
        int ackedSeq = ((f[HEADER_LEN + CALLSIGN_LEN] & 0xFF) << 8)
                | (f[HEADER_LEN + CALLSIGN_LEN + 1] & 0xFF);

        if (dest.equalsIgnoreCase(myCall)) {
            Pending p;
            synchronized (routes) {
                p = pendingDirects.remove(ackedSeq);
            }
            if (p != null)
                cb.onStatus("Mesh: " + p.dest + " acknowledged seq "
                        + String.format("%04X", ackedSeq));
            return;
        }
        // Intermediate node: forward the ACK toward its destination if a
        // route is known (ACKs carry no explicit next hop; the dedup cache
        // stops storms).
        String forwardHop;
        synchronized (routes) {
            Route r = routes.get(dest);
            forwardHop = r == null ? null : r.nextHop;
        }
        if (forwardHop != null) {
            byte[] fwd = f.clone();
            packCallsign(myCall, fwd, 6);   // transmitter = us
            cb.transmitFrame(fwd);
        }
    }

    private void sendAck(String origin, String heardFromTransmitter, int ackedSeq) {
        // Route the ACK back toward the origin; if no route learned yet,
        // hand it to the neighbor we heard the direct from (reverse path).
        byte[] ack = new byte[HEADER_LEN + CALLSIGN_LEN + 2];
        ack[0] = TYPE_ACK;
        packCallsign(myCall, ack, 1);
        packCallsign(myCall, ack, 6);
        int seq = nextSeq();
        ack[11] = (byte) (seq >> 8);
        ack[12] = (byte) seq;
        packCallsign(origin, ack, HEADER_LEN);
        ack[HEADER_LEN + CALLSIGN_LEN] = (byte) (ackedSeq >> 8);
        ack[HEADER_LEN + CALLSIGN_LEN + 1] = (byte) ackedSeq;
        cb.transmitFrame(ack);
    }

    // ------------------------------------------------------------------
    // Timer: announces, retries, cleanup
    // ------------------------------------------------------------------
    private void timerLoop() {
        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            long t = now();
            tick(t);
        }
    }

    /** One timer tick; public so tests can drive time manually. */
    public void tick(long t) {
        if (announceIntervalMs > 0 && t >= nextAnnounceMs) {
            nextAnnounceMs = t + announceIntervalMs;
            announceNow();
        }
        List<Pending> due = new ArrayList<>();
        List<Pending> failed = new ArrayList<>();
        synchronized (routes) {
            for (Iterator<Pending> it = pendingDirects.values().iterator(); it.hasNext();) {
                Pending p = it.next();
                if (!p.aired) {
                    // The latest copy is still waiting for airtime (MAC
                    // queue): never retry or fail a frame that has not
                    // transmitted — give up only if the queue is stuck.
                    if (t - p.queuedAtMs > STUCK_FAIL_MS) {
                        it.remove();
                        failed.add(p);
                    }
                    continue;
                }
                if (t < p.nextAttemptMs) continue;
                if (p.tries >= MAX_RETRIES) {
                    it.remove();
                    failed.add(p);
                } else {
                    p.tries++;
                    p.aired = false;            // re-armed when the copy airs
                    p.queuedAtMs = t;
                    p.nextAttemptMs = Long.MAX_VALUE;
                    due.add(p);
                }
            }
        }
        for (Pending p : failed)
            cb.onStatus("Mesh: direct to " + p.dest + " FAILED (no ack after "
                    + MAX_RETRIES + " retries)");
        for (Pending p : due) {
            String nextHop;
            synchronized (routes) {
                Route r = routes.get(p.dest);
                nextHop = r == null ? null : r.nextHop;
            }
            if (nextHop == null) {
                cb.onStatus("Mesh: retry " + p.tries + " to " + p.dest
                        + " \u2014 route lost, broadcasting");
                cb.transmitFrame(buildBroadcast(myCall, myCall, p.seq, p.hbc));
            } else {
                cb.onStatus("Mesh: retry " + p.tries + "/" + MAX_RETRIES
                        + " to " + p.dest + " via " + nextHop);
                cb.transmitFrame(buildDirect(myCall, myCall, p.seq, p.dest, nextHop, p.hbc));
            }
        }
        if (t >= nextCleanupMs) {
            nextCleanupMs = t + CLEANUP_MS;
            synchronized (routes) {
                long routeCutoff = t - ROUTE_TTL_MS;
                for (Iterator<Route> it = routes.values().iterator(); it.hasNext();)
                    if (it.next().lastSeen < routeCutoff) it.remove();
                long cacheCutoff = t - CACHE_TTL_MS;
                for (Iterator<Long> it = seenCache.values().iterator(); it.hasNext();)
                    if (it.next() < cacheCutoff) it.remove();
            }
        }
    }

    /** @return true if this signature was already cached (duplicate). */
    private boolean checkAndCache(String sig) {
        synchronized (routes) {
            Long seen = seenCache.get(sig);
            long t = now();
            if (seen != null && t - seen < CACHE_TTL_MS)
                return true;
            seenCache.put(sig, t);
            return false;
        }
    }

    private synchronized int nextSeq() {
        seqCounter = (seqCounter + 1) & 0xFFFF;
        return seqCounter;
    }

    // ------------------------------------------------------------------
    // Frame builders
    // ------------------------------------------------------------------
    public static byte[] buildAnnounce(String origin, String transmitter, int seq, int hops) {
        byte[] f = new byte[HEADER_LEN + 1];
        header(f, TYPE_ANNOUNCE, origin, transmitter, seq);
        f[HEADER_LEN] = (byte) hops;
        return f;
    }

    public static byte[] buildBroadcast(String origin, String transmitter, int seq, byte[] payload) {
        byte[] f = new byte[HEADER_LEN + payload.length];
        header(f, TYPE_BROADCAST, origin, transmitter, seq);
        System.arraycopy(payload, 0, f, HEADER_LEN, payload.length);
        return f;
    }

    public static byte[] buildDirect(String origin, String transmitter, int seq,
                                     String dest, String nextHop, byte[] payload) {
        byte[] f = new byte[HEADER_LEN + 2 * CALLSIGN_LEN + payload.length];
        header(f, TYPE_DIRECT, origin, transmitter, seq);
        packCallsign(dest, f, HEADER_LEN);
        packCallsign(nextHop, f, HEADER_LEN + CALLSIGN_LEN);
        System.arraycopy(payload, 0, f, HEADER_LEN + 2 * CALLSIGN_LEN, payload.length);
        return f;
    }

    private static void header(byte[] f, int type, String origin, String transmitter, int seq) {
        f[0] = (byte) type;
        packCallsign(origin, f, 1);
        packCallsign(transmitter, f, 6);
        f[11] = (byte) (seq >> 8);
        f[12] = (byte) seq;
    }

    // ------------------------------------------------------------------
    // Paper-variant ITA2 callsign packing (MSB-first table; bit-reversed
    // relative to HBC's Ita2 class). LTRS=11111, FIGS=11011, NULL=00000.
    // ------------------------------------------------------------------
    private static final int LTRS = 0b11111, FIGS = 0b11011, NUL = 0b00000;
    private static final String LETTER_SET = "EA SIUDRJNFCKTZLWHYPQOBGMXV";
    private static final int[] LETTER_CODE = {
            0b10000, 0b11000, 0b00100, 0b10100, 0b01100, 0b11100, 0b10010,
            0b01010, 0b11010, 0b00110, 0b10110, 0b01110, 0b11110, 0b00001,
            0b10001, 0b01001, 0b11001, 0b00101, 0b10101, 0b01101, 0b11101,
            0b00011, 0b10011, 0b01011, 0b00111, 0b10111, 0b01111 };
    private static final String FIGURE_SET = "3-'87$4,!:(5\")2#601?&./;";
    private static final int[] FIGURE_CODE = {
            0b10000, 0b11000, 0b10100, 0b01100, 0b11100, 0b10010, 0b01010,
            0b00110, 0b10110, 0b01110, 0b11110, 0b00001, 0b10001, 0b01001,
            0b11001, 0b00101, 0b10101, 0b01101, 0b11101, 0b00011, 0b10011,
            0b01011, 0b00111, 0b10111, 0b01111 };

    /**
     * Pack a callsign into 5 bytes (8 five-bit codes) at offset. Emits a
     * leading shift, dropping a leading LTRS when needed to fit 8 codes.
     * NULL-pads. Throws if the callsign cannot fit or has bad characters.
     */
    public static void packCallsign(String callsign, byte[] out, int offset) {
        String cs = callsign == null ? "" : callsign.toUpperCase().trim();
        List<Integer> codes = new ArrayList<>();
        boolean inFigures = false;
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            int li = LETTER_SET.indexOf(c);
            int fi = FIGURE_SET.indexOf(c);
            if (li >= 0) {
                if (inFigures || codes.isEmpty()) codes.add(LTRS);
                inFigures = false;
                codes.add(LETTER_CODE[li]);
            } else if (fi >= 0) {
                if (!inFigures) codes.add(FIGS);
                inFigures = true;
                codes.add(FIGURE_CODE[fi]);
            } else {
                throw new IllegalArgumentException(
                        "Callsign char '" + c + "' not ITA2-encodable");
            }
        }
        if (codes.size() > 8 && !codes.isEmpty() && codes.get(0) == LTRS)
            codes.remove(0);   // decoder starts in letters state
        if (codes.size() > 8)
            throw new IllegalArgumentException(
                    "Callsign '" + cs + "' too long for 8 ITA2 codes");
        while (codes.size() < 8) codes.add(NUL);

        long bits = 0;
        for (int code : codes)
            bits = (bits << 5) | code;
        for (int i = 0; i < CALLSIGN_LEN; i++)
            out[offset + i] = (byte) (bits >> (8 * (CALLSIGN_LEN - 1 - i)));
    }

    /** Unpack an 8-code ITA2 callsign field. Decoder starts in letters state. */
    public static String unpackCallsign(byte[] in, int offset) {
        long bits = 0;
        for (int i = 0; i < CALLSIGN_LEN; i++)
            bits = (bits << 8) | (in[offset + i] & 0xFF);
        StringBuilder sb = new StringBuilder();
        boolean inFigures = false;
        for (int i = 0; i < 8; i++) {
            int code = (int) ((bits >> (5 * (7 - i))) & 0x1F);
            if (code == NUL) continue;
            if (code == LTRS) { inFigures = false; continue; }
            if (code == FIGS) { inFigures = true;  continue; }
            String set = inFigures ? FIGURE_SET : LETTER_SET;
            int[] table = inFigures ? FIGURE_CODE : LETTER_CODE;
            char c = '?';
            for (int j = 0; j < table.length; j++)
                if (table[j] == code) { c = set.charAt(j); break; }
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
