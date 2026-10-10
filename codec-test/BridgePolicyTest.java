import com.atakmap.android.hbc.BridgePolicy;

import java.util.List;

/**
 * JVM tests for the C2 Bridge decision rules (v0.24): RF->LAN forward
 * matrix, LAN->RF diode verdicts, CoT authorship extraction, and the
 * Push-to-RF stash eviction rules.
 *
 * Compile/run (from repo root, with the Android Studio JBR):
 *   javac -d out -sourcepath app\src\main\java codec-test\BridgePolicyTest.java
 *   java -cp out BridgePolicyTest
 */
public final class BridgePolicyTest {

    private static int failures = 0;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        testForwardMatrix();
        testAuthorExtraction();
        testTxVerdict();
        testPushStash();
        System.out.println(failures == 0
                ? "All BridgePolicy tests PASSED"
                : failures + " BridgePolicy test(s) FAILED");
        if (failures > 0) System.exit(1);
    }

    // ---- RF -> LAN forward matrix ------------------------------------
    private static void testForwardMatrix() {
        check("F1 mode 0 ack never forwarded",
                !BridgePolicy.shouldForwardToLan(0, 0));
        check("F2 PLI/spot forwarded",
                BridgePolicy.shouldForwardToLan(1, 0));
        check("F3 alert forwarded",
                BridgePolicy.shouldForwardToLan(2, 0));
        check("F4 all-chat forwarded",
                BridgePolicy.shouldForwardToLan(3, 0));
        check("F5 room chat forwarded",
                BridgePolicy.shouldForwardToLan(3, 1));
        check("F6 operator DM NOT forwarded",
                !BridgePolicy.shouldForwardToLan(3, 2));
        check("F7 shape forwarded",
                BridgePolicy.shouldForwardToLan(4, 0));
        check("F8 casevac forwarded",
                BridgePolicy.shouldForwardToLan(5, 0));
        check("F9 marker forwarded",
                BridgePolicy.shouldForwardToLan(6, 0));
    }

    // ---- authorship extraction ---------------------------------------
    private static void testAuthorExtraction() {
        String marker = "<event uid=\"abc\" type=\"a-u-G\"><detail>"
                + "<creator uid=\"ANDROID-123\" callsign=\"ONYX\" time=\"t\"/>"
                + "</detail></event>";
        check("A1 creator uid",
                "ANDROID-123".equals(BridgePolicy.extractAuthorUid(marker)));

        String chat = "<event uid=\"GeoChat.ANDROID-9.x.m\" type=\"b-t-f\"><detail>"
                + "<__chat chatroom=\"All Chat Rooms\">"
                + "<chatgrp uid0=\"ANDROID-9\" uid1=\"All Chat Rooms\"/></__chat>"
                + "</detail></event>";
        check("A2 chatgrp uid0",
                "ANDROID-9".equals(BridgePolicy.extractAuthorUid(chat)));

        String remarks = "<event uid=\"g\" type=\"b-t-f\"><detail>"
                + "<remarks source=\"BAO.F.ATAK.ANDROID-77\" time=\"t\">HI</remarks>"
                + "</detail></event>";
        check("A3 BAO.F.ATAK source",
                "ANDROID-77".equals(BridgePolicy.extractAuthorUid(remarks)));

        String link = "<event uid=\"m1\" type=\"a-h-G\"><detail>"
                + "<link uid=\"ANDROID-55\" production_time=\"t\" relation=\"p-p\""
                + " type=\"a-f-G-U-C\" parent_callsign=\"BASE\"/>"
                + "</detail></event>";
        check("A4 p-p link uid",
                "ANDROID-55".equals(BridgePolicy.extractAuthorUid(link)));

        String routeLink = "<event uid=\"r1\" type=\"b-m-r\"><detail>"
                + "<link uid=\"wp1\" relation=\"c\" point=\"1,2\"/>"
                + "</detail></event>";
        check("A5 non-p-p link ignored",
                BridgePolicy.extractAuthorUid(routeLink) == null);

        String bare = "<event uid=\"n1\" type=\"a-u-G\"><detail>"
                + "<contact callsign=\"X\"/></detail></event>";
        check("A6 no authorship -> null",
                BridgePolicy.extractAuthorUid(bare) == null);
    }

    // ---- LAN -> RF diode verdicts --------------------------------------
    private static void testTxVerdict() {
        final String DEV = "ANDROID-123";
        check("V1 self PLI allowed",
                BridgePolicy.txVerdict(true, DEV, null, DEV, false) == null);
        check("V2 own-authored marker allowed",
                BridgePolicy.txVerdict(false, "m1", DEV, DEV, false) == null);
        check("V3 author case-insensitive",
                BridgePolicy.txVerdict(false, "m1", "android-123", DEV, false) == null);
        check("V4 bridged uid dropped as echo", "radio-origin echo".equals(
                BridgePolicy.txVerdict(false, "HBC-S26", DEV, DEV, true)));
        check("V5 HBC-* uid dropped as echo", "radio-origin echo".equals(
                BridgePolicy.txVerdict(false, "HBC-PIX-911", null, DEV, false)));
        check("V6 GeoChat.HBC-* dropped as echo", "radio-origin echo".equals(
                BridgePolicy.txVerdict(false, "GeoChat.HBC-TAB.All Chat Rooms.m",
                        null, DEV, false)));
        check("V7 foreign author blocked", "network-origin".equals(
                BridgePolicy.txVerdict(false, "m2", "ANDROID-999", DEV, false)));
        check("V8 unknown author blocked", "network-origin".equals(
                BridgePolicy.txVerdict(false, "m3", null, DEV, false)));
        check("V9 no device uid blocked", "network-origin".equals(
                BridgePolicy.txVerdict(false, "m4", "ANDROID-999", null, false)));
    }

    // ---- Push-to-RF stash ----------------------------------------------
    private static void testPushStash() {
        BridgePolicy.PushStash<String> st =
                new BridgePolicy.PushStash<>(3, 10_000);
        long t = 1_000_000;
        st.put("a", "A", t, "pa");
        st.put("b", "B", t + 100, "pb");
        check("S1 size counts live entries", st.size(t + 200) == 2);

        // newest-per-uid: re-adding refreshes payload and position
        st.put("a", "A2", t + 300, "pa2");
        List<BridgePolicy.PushStash.Entry<String>> l = st.list(t + 400);
        check("S2 newest first", l.get(0).uid.equals("a")
                && l.get(0).label.equals("A2") && l.get(1).uid.equals("b"));
        check("S3 one entry per uid", l.size() == 2);

        // cap: oldest evicted
        st.put("c", "C", t + 500, "pc");
        st.put("d", "D", t + 600, "pd");
        check("S4 cap evicts oldest", st.size(t + 700) == 3
                && st.take("b", t + 700) == null);

        // take removes
        BridgePolicy.PushStash.Entry<String> e = st.take("a", t + 800);
        check("S5 take returns payload", e != null && "pa2".equals(e.payload));
        check("S6 take removes entry", st.take("a", t + 900) == null);

        // TTL expiry
        check("S7 TTL expires entries", st.size(t + 20_000) == 0);
    }
}
