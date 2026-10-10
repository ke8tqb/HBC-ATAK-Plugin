package com.atakmap.android.hbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * C2 Bridge decision rules (v0.24) — a one-way data diode for a station
 * that hears the HBC radio mesh AND sits on a normal ATAK network
 * (TAK server / mesh SA).
 *
 * Direction 1, RF -> LAN: everything decoded from radio is re-published
 * onto the network so C2 staff see it ({@link #shouldForwardToLan}).
 *
 * Direction 2, LAN -> RF: NOTHING network-originated is auto-relayed to
 * the slow radio channel ({@link #txVerdict}); blocked events are kept in
 * a small {@link PushStash} from which the OPERATOR manually selects what
 * is worth airtime ("Push to RF"). The human is the rate limiter.
 *
 * Pure Java on purpose: all inputs are plain strings/ints extracted by
 * the plugin, so the whole decision table runs under the JVM test suite
 * (codec-test/BridgePolicyTest.java), like RingMac and MeshRouter.
 */
public final class BridgePolicy {

    private BridgePolicy() {}

    /**
     * Should a decoded radio event be re-published onto the LAN?
     * Mode 0 receipts are radio ARQ plumbing, and direct messages are
     * private to the bridge operator — everything else is C2-relevant.
     */
    public static boolean shouldForwardToLan(int mode, int chatDestKind) {
        if (mode == 0) return false;                       // ARQ plumbing
        if (mode == 3 && chatDestKind == 2) return false;  // operator DM
        return true;
    }

    /**
     * May this outbound CoT be AUTO-transmitted over RF while the bridge
     * is active?
     *
     * @return null when allowed; otherwise the block reason:
     *         "radio-origin echo" (drop silently — it came FROM the air)
     *         or "network-origin" (stash for the manual Push-to-RF list).
     */
    public static String txVerdict(boolean selfPli, String uid,
                                   String authorUid, String deviceUid,
                                   boolean recentlyBridged) {
        if (uid != null) {
            String u = uid.toUpperCase(Locale.US);
            if (recentlyBridged || u.startsWith("HBC-")
                    || u.startsWith("GEOCHAT.HBC-"))
                return "radio-origin echo";
        }
        if (selfPli) return null;
        if (authorUid != null && deviceUid != null && !deviceUid.isEmpty()
                && authorUid.equalsIgnoreCase(deviceUid))
            return null;
        return "network-origin";
    }

    /**
     * Best-effort authorship extraction from CoT XML — who CREATED this
     * event. Checked in order: {@code <creator uid>} (placed markers,
     * CASEVAC), GeoChat {@code chatgrp uid0} (message sender), the
     * {@code BAO.F.ATAK.<uid>} remarks source, and finally a
     * {@code <link relation="p-p" uid>} parent-producer link. Returns
     * null when the event carries no authorship information (the diode
     * then blocks it — false-block is safer than false-allow).
     */
    public static String extractAuthorUid(String cotXml) {
        if (cotXml == null) return null;
        String v = firstAttr(cotXml, "<creator", "uid");
        if (v != null) return v;
        v = firstAttr(cotXml, "<chatgrp", "uid0");
        if (v != null) return v;
        int i = cotXml.indexOf("source=\"BAO.F.ATAK.");
        if (i >= 0) {
            int s = i + "source=\"BAO.F.ATAK.".length();
            int e = cotXml.indexOf('"', s);
            if (e > s) return cotXml.substring(s, e);
        }
        // placed-marker parent link: uid of the producing device
        i = cotXml.indexOf("<link");
        while (i >= 0) {
            int end = cotXml.indexOf('>', i);
            if (end < 0) break;
            String seg = cotXml.substring(i, end);
            if (seg.contains("relation=\"p-p\"")) {
                String u = firstAttr(seg, "<link", "uid");
                if (u != null) return u;
            }
            i = cotXml.indexOf("<link", end);
        }
        return null;
    }

    /**
     * First value of {@code attr="..."} inside the first occurrence of
     * element {@code elem} (e.g. "&lt;contact") in the XML, or null.
     */
    public static String firstAttr(String xml, String elem, String attr) {
        if (xml == null) return null;
        int i = xml.indexOf(elem);
        while (i >= 0) {
            int end = xml.indexOf('>', i);
            if (end < 0) end = xml.length();
            String seg = xml.substring(i, end);
            int a = seg.indexOf(attr + "=\"");
            if (a >= 0) {
                int s = a + attr.length() + 2;
                int e = seg.indexOf('"', s);
                if (e > s) return seg.substring(s, e);
            }
            i = xml.indexOf(elem, end);
        }
        return null;
    }

    // ------------------------------------------------------------------

    /**
     * The blocked-event stash backing the "Push to RF" dialog: newest
     * entry wins per UID, bounded size (oldest evicted), entries expire
     * after a TTL. Thread-safe; payload type is generic so the rules are
     * JVM-testable without Android classes.
     */
    public static final class PushStash<T> {

        public static final class Entry<T> {
            public final String uid;
            public final String label;
            public final long atMs;
            public final T payload;
            Entry(String uid, String label, long atMs, T payload) {
                this.uid = uid;
                this.label = label;
                this.atMs = atMs;
                this.payload = payload;
            }
        }

        private final int cap;
        private final long ttlMs;
        private final LinkedHashMap<String, Entry<T>> map = new LinkedHashMap<>();

        public PushStash(int cap, long ttlMs) {
            this.cap = Math.max(1, cap);
            this.ttlMs = Math.max(1000, ttlMs);
        }

        /** Insert/refresh an entry; the newest copy per UID is kept. */
        public synchronized void put(String uid, String label, long nowMs, T payload) {
            if (uid == null || uid.isEmpty()) return;
            prune(nowMs);
            map.remove(uid);                 // re-insert at the tail (newest)
            map.put(uid, new Entry<>(uid, label, nowMs, payload));
            Iterator<String> it = map.keySet().iterator();
            while (map.size() > cap && it.hasNext()) {
                it.next();
                it.remove();                 // evict oldest
            }
        }

        /** Live entries, newest first. */
        public synchronized List<Entry<T>> list(long nowMs) {
            prune(nowMs);
            List<Entry<T>> out = new ArrayList<>(map.values());
            Collections.reverse(out);
            return out;
        }

        /** Remove and return one entry (null when expired/absent). */
        public synchronized Entry<T> take(String uid, long nowMs) {
            prune(nowMs);
            return map.remove(uid);
        }

        public synchronized int size(long nowMs) {
            prune(nowMs);
            return map.size();
        }

        private void prune(long nowMs) {
            Iterator<Entry<T>> it = map.values().iterator();
            while (it.hasNext())
                if (nowMs - it.next().atMs > ttlMs)
                    it.remove();
        }
    }
}
