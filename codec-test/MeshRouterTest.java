import com.atakmap.android.hbc.MeshRouter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM tests for MeshRouter against the routing paper's Appendix A byte
 * vectors and the Appendix B 4-node announce propagation trace.
 *
 * Run:  javac -d _build app\src\main\java\com\atakmap\android\hbc\MeshRouter.java codec-test\MeshRouterTest.java
 *       java -cp _build MeshRouterTest
 */
public final class MeshRouterTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        testCallsignVectors();
        testBroadcastFrameVector();
        testDirectFrameVector();
        testTightCallsign();
        testFourNodeAnnounceTrace();
        testDirectForwardAndAck();
        System.out.println(failures == 0
                ? "\nAll MeshRouter tests PASSED"
                : "\n" + failures + " MeshRouter test(s) FAILED");
        if (failures > 0) System.exit(1);
    }

    // Appendix A: K1ABC = LTRS K FIGS 1 LTRS A B C. NOTE: the paper's hex
    // column lists byte 03 as BF, but its own bit-level breakdown ("last 4
    // bits of 1 (1101) + first 4 of LTRS (1111)") = 11011111 = DF; BF would
    // require '1' = 1011x, contradicting the paper's stated code 1=11101.
    // We follow the arithmetic (DF); N3DEF/W2XYZ vectors match byte-exact.
    private static void testCallsignVectors() {
        byte[] buf = new byte[5];
        MeshRouter.packCallsign("K1ABC", buf, 0);
        check("K1ABC packing", hex(buf).equals("FF B7 DF E2 6E"));
        check("K1ABC round trip", MeshRouter.unpackCallsign(buf, 0).equals("K1ABC"));

        // N3DEF -> F9 B7 0F CA 16 ; W2XYZ -> FE 77 9F DE B1 (Appendix A Ex 2)
        MeshRouter.packCallsign("N3DEF", buf, 0);
        check("N3DEF packing", hex(buf).equals("F9 B7 0F CA 16"));
        check("N3DEF round trip", MeshRouter.unpackCallsign(buf, 0).equals("N3DEF"));
        MeshRouter.packCallsign("W2XYZ", buf, 0);
        check("W2XYZ packing", hex(buf).equals("FE 77 9F DE B1"));
        check("W2XYZ round trip", MeshRouter.unpackCallsign(buf, 0).equals("W2XYZ"));
    }

    // Appendix A Example 1 header bytes (payload here is HBC binary by
    // design, so we verify the 13-byte header exactly).
    private static void testBroadcastFrameVector() {
        byte[] payload = {(byte) 0xF9, 0x58};   // the example's ITA2 "HI" bytes
        byte[] f = MeshRouter.buildBroadcast("K1ABC", "K1ABC", 0x0001, payload);
        check("broadcast frame is 15 bytes", f.length == 15);
        check("broadcast frame bytes", hex(f).equals(
                "02 FF B7 DF E2 6E FF B7 DF E2 6E 00 01 F9 58"));
    }

    // Appendix A Example 2: full 27-byte Direct frame (payload TEST in ITA2
    // = F8 61 40 80 per the paper).
    private static void testDirectFrameVector() {
        byte[] payload = {(byte) 0xF8, 0x61, 0x40, (byte) 0x80};
        byte[] f = MeshRouter.buildDirect("K1ABC", "K1ABC", 0x0005,
                "N3DEF", "W2XYZ", payload);
        check("direct frame is 27 bytes", f.length == 27);
        check("direct frame bytes", hex(f).equals(
                "03 FF B7 DF E2 6E FF B7 DF E2 6E 00 05 "
                + "F9 B7 0F CA 16 FE 77 9F DE B1 F8 61 40 80"));
    }

    // KE8TQB needs 9 codes with a leading LTRS; the implicit-letters rule
    // must make it fit and round-trip.
    private static void testTightCallsign() {
        byte[] buf = new byte[5];
        MeshRouter.packCallsign("KE8TQB", buf, 0);
        check("KE8TQB round trip", MeshRouter.unpackCallsign(buf, 0).equals("KE8TQB"));
    }

    /** Test double: collects transmitted frames and delivered payloads. */
    private static final class Node implements MeshRouter.Callbacks {
        final MeshRouter router;
        final List<byte[]> txed = new ArrayList<>();
        final List<String> rxOrigins = new ArrayList<>();
        final List<byte[]> rxPayloads = new ArrayList<>();
        Node(String call) {
            router = new MeshRouter(call, this);
            router.setAnnounceIntervalMin(0);   // no periodic announces in tests
        }
        public void onHbcPayload(String origin, byte[] hbc) {
            rxOrigins.add(origin); rxPayloads.add(hbc);
        }
        public void transmitFrame(byte[] frame) { txed.add(frame); }
        public void onStatus(String message) { /* quiet */ }
        byte[] lastTx() { return txed.isEmpty() ? null : txed.get(txed.size() - 1); }
    }

    // Appendix B: A -(B,C)-> D. D must lock onto the first-heard path and
    // discard the second announce for the same [origin+seq].
    private static void testFourNodeAnnounceTrace() {
        Node b = new Node("B1B"), c = new Node("C1C"), d = new Node("D1D");
        byte[] fromA = MeshRouter.buildAnnounce("A1A", "A1A", 0x0001, 0);

        b.router.onRadioFrame(fromA);
        c.router.onRadioFrame(fromA);
        check("B learned A at 1 hop", b.router.knownDestinations().contains("A1A"));
        check("B rebroadcast announce", b.txed.size() == 1
                && (b.lastTx()[0] & 0xFF) == MeshRouter.TYPE_ANNOUNCE
                && b.lastTx()[13] == 1);
        check("C rebroadcast announce", c.txed.size() == 1);

        // D hears B first, then C: must keep B's route and not rebroadcast C's.
        d.router.onRadioFrame(b.lastTx());
        int dTxAfterB = d.txed.size();
        d.router.onRadioFrame(c.lastTx());
        check("D learned A", d.router.knownDestinations().contains("A1A"));
        check("D routes A via B (2 hops)",
                d.router.routingTableSummary().contains("A1A via B1B (2 hops)"));
        check("D dropped C's duplicate", d.txed.size() == dTxAfterB);
    }

    // Direct message forwarding + end-to-end ACK.
    private static void testDirectForwardAndAck() {
        Node a = new Node("A1A"), b = new Node("B1B"), c = new Node("C1C");
        // Build routes via announces: C announces; B hears it; A hears B's copy.
        c.router.announceNow();
        b.router.onRadioFrame(c.lastTx());
        a.router.onRadioFrame(b.lastTx());
        check("A routes C via B",
                a.router.routingTableSummary().contains("C1C via B1B"));
        // And the reverse path for the ACK: A announces; B relays; C hears.
        a.router.announceNow();
        b.router.onRadioFrame(a.lastTx());
        c.router.onRadioFrame(b.lastTx());
        check("C routes A via B",
                c.router.routingTableSummary().contains("A1A via B1B"));

        byte[] hbc = {0x11, 0x22, 0x33};
        a.txed.clear(); b.txed.clear(); c.txed.clear();
        a.router.sendDirect("C1C", hbc);
        byte[] direct = a.lastTx();
        check("A sent a direct", direct != null
                && (direct[0] & 0xFF) == MeshRouter.TYPE_DIRECT);

        b.router.onRadioFrame(direct);          // B forwards, rewriting next hop
        byte[] forwarded = b.lastTx();
        check("B forwarded the direct", forwarded != null
                && (forwarded[0] & 0xFF) == MeshRouter.TYPE_DIRECT);
        check("B rewrote transmitter",
                MeshRouter.unpackCallsign(forwarded, 6).equals("B1B"));
        check("B rewrote next hop",
                MeshRouter.unpackCallsign(forwarded, 18).equals("C1C"));

        c.router.onRadioFrame(forwarded);       // C delivers + ACKs
        check("C delivered payload", c.rxPayloads.size() == 1
                && Arrays.equals(c.rxPayloads.get(0), hbc)
                && c.rxOrigins.get(0).equals("A1A"));
        byte[] ack = c.lastTx();
        check("C sent an ACK", ack != null && (ack[0] & 0xFF) == MeshRouter.TYPE_ACK);

        b.router.onRadioFrame(ack);             // B forwards the ACK
        byte[] fwdAck = b.lastTx();
        check("B forwarded the ACK", fwdAck != null
                && (fwdAck[0] & 0xFF) == MeshRouter.TYPE_ACK);

        int aTxBefore = a.txed.size();
        a.router.onRadioFrame(fwdAck);          // A clears the pending retry
        a.router.tick(System.currentTimeMillis() + 60_000);
        check("A did not retry after ACK", a.txed.size() == aTxBefore);

        // Without an ACK a retry must go out with the same sequence ID.
        a.txed.clear();
        a.router.sendDirect("C1C", hbc);
        byte[] first = a.txed.get(0);
        a.router.tick(System.currentTimeMillis() + 10_000);
        check("A retried the unacked direct", a.txed.size() == 2);
        check("retry reuses sequence ID",
                first[11] == a.txed.get(1)[11] && first[12] == a.txed.get(1)[12]);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
