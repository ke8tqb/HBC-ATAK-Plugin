import com.atakmap.android.hbc.RingMac;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM tests for the Ring MAC state machine. A fake Hooks implementation
 * provides a manual clock, channel state, roster and TX queue; tests drive
 * RingMac.tick() in 50 ms steps (startManual: no background ticker).
 *
 * Compile/run (from repo root, with the Android Studio JBR):
 *   javac -d out -sourcepath app\src\main\java codec-test\RingMacTest.java
 *   java -cp out RingMacTest
 */
public class RingMacTest {

    // ---- fake environment -------------------------------------------
    static long now = 1_000_000;
    static boolean busy = false;
    static boolean transmitting = false;
    static List<String> meshRoster = new ArrayList<>();
    static int pending = 0;
    static int released = 0;
    static List<String> statusLog = new ArrayList<>();
    static List<String> debugLog = new ArrayList<>();

    static RingMac.Hooks hooks = new RingMac.Hooks() {
        public long nowMs() { return now; }
        public boolean channelBusy() { return busy; }
        public boolean transmitting() { return transmitting; }
        public List<String> meshRoster() { return new ArrayList<>(meshRoster); }
        public int pendingFrames() { return pending; }
        public int releaseFrames(int max) {
            int n = Math.min(max, pending);
            pending -= n;
            released += n;
            if (n > 0) transmitting = true;   // modem starts playing out
            return n;
        }
        public void onStatus(String m) { statusLog.add(m); }
        public void onDebug(String m) { debugLog.add(m); }
    };

    static void step(RingMac rm, long ms) {
        long end = now + ms;
        while (now < end) {
            now += 50;
            rm.tick(now);
        }
    }

    static boolean sawStatus(String frag) {
        for (String s : statusLog) if (s.contains(frag)) return true;
        return false;
    }

    static boolean sawDebug(String frag) {
        for (String s : debugLog) if (s.contains(frag)) return true;
        return false;
    }

    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static void reset() {
        now = 1_000_000;
        busy = false;
        transmitting = false;
        meshRoster = new ArrayList<>();
        pending = 0;
        released = 0;
        statusLog = new ArrayList<>();
        debugLog = new ArrayList<>();
    }

    static RingMac fresh(String myCall) {
        RingMac rm = new RingMac(myCall, hooks);
        rm.setGuardMs(1500);
        rm.setSkipMs(1200);
        rm.setMaxTurnMs(6000);
        rm.setMaxFramesPerTurn(4);
        rm.startManual();
        return rm;
    }

    public static void main(String[] args) {
        // ---- T1: alone, own turn transmits immediately ---------------
        reset();
        RingMac rm = fresh("ALPHA");
        pending = 2;
        step(rm, 100);
        check("T1 own-turn TX fires", released == 2);
        check("T1 TX-turn status", sawStatus("Ring: TX turn"));
        // our burst plays out, then clears: early release advances
        step(rm, 500);
        transmitting = false;
        step(rm, 1600);   // guard 1500
        pending = 1;
        step(rm, 6200);   // next cycle (deadline path while settled wrap)
        check("T1 second turn served", released == 3);
        rm.stop();

        // ---- T2: rotation, silent skips, settle ----------------------
        reset();
        meshRoster.add("CHARLIE");
        meshRoster.add("BRAVO");
        rm = fresh("ALPHA");   // sorted: ALPHA BRAVO CHARLIE
        // unsettled: our turn passes via deadline (6000+1500), then skips
        step(rm, 8000);        // past own-turn deadline
        step(rm, 1300);        // BRAVO silent skip
        check("T2 skip BRAVO", sawDebug("skip BRAVO"));
        step(rm, 1300);        // CHARLIE silent skip
        check("T2 skip CHARLIE", sawDebug("skip CHARLIE"));
        check("T2 settled after one cycle", sawStatus("joined rotation"));
        rm.stop();

        // ---- T3: heard transmitter aligns + early release ------------
        reset();
        meshRoster.add("BRAVO");
        meshRoster.add("CHARLIE");
        rm = fresh("ALPHA");
        rm.onHeardTransmitter("CHARLIE");     // decoded frame from CHARLIE
        rm.onHeardTransmitter("CHARLIE");
        busy = true;                           // his burst continues
        step(rm, 400);
        busy = false;                          // burst ends
        step(rm, 1600);                        // guard elapses
        check("T3 early release logged", sawDebug("CHARLIE released early"));
        rm.stop();

        // ---- T4: hidden/stuck owner hits the deadline ----------------
        reset();
        meshRoster.add("BRAVO");
        rm = fresh("ALPHA");
        rm.onHeardTransmitter("BRAVO");
        busy = true;                           // carrier never drops
        step(rm, 6000 + 1500 + 200);
        check("T4 deadline advance", sawDebug("deadline advance past BRAVO"));
        rm.stop();

        // ---- T5: own turn honors the busy interlock ------------------
        reset();
        rm = fresh("ALPHA");                   // alone => settled
        pending = 1;
        busy = true;                           // foreign carrier (legacy CSMA station)
        step(rm, 1000);
        check("T5 no TX while busy", released == 0);
        busy = false;
        step(rm, 200);
        check("T5 TX after clear", released == 1);
        rm.stop();

        // ---- T6: emergency preempts out of turn ----------------------
        reset();
        meshRoster.add("BRAVO");
        meshRoster.add("CHARLIE");
        rm = fresh("ALPHA");
        rm.onHeardTransmitter("BRAVO");        // BRAVO owns the channel
        busy = true;
        pending = 1;
        rm.flagEmergency();
        step(rm, 500);                         // still busy: must hold
        check("T6 holds while busy", released == 0);
        busy = false;                          // channel goes idle
        step(rm, 600);                         // 0-300 ms offset + ticks
        check("T6 emergency TX fired", released == 1
                && sawStatus("Ring: emergency TX"));
        rm.stop();

        // ---- T7: roster change applies at cycle wrap -----------------
        reset();
        meshRoster.add("BRAVO");
        rm = fresh("ALPHA");                   // roster ALPHA BRAVO
        // settle: our deadline pass + BRAVO skip = one full cycle
        step(rm, 8000);                        // own-turn deadline (unsettled)
        step(rm, 1300);                        // BRAVO skip -> wrap, settled
        meshRoster.add("DELTA");               // appears mid-cycle
        step(rm, 100);                         // our turn, no traffic: pass
        check("T7 not applied mid-cycle", !sawStatus("roster now 3"));
        step(rm, 1300);                        // BRAVO skip -> wrap applies roster
        check("T7 roster applied at wrap", sawStatus("roster now 3"));
        rm.stop();

        // ---- T8: dormant after 3 silent cycles -----------------------
        reset();
        meshRoster.add("BRAVO");
        rm = fresh("ALPHA");
        step(rm, 8000);                        // settle cycle part 1 (own deadline)
        for (int i = 0; i < 3; i++) {
            step(rm, 1300);                    // BRAVO silent skip
            step(rm, 100);                     // our empty turn passes
        }
        check("T8 dormant logged", sawStatus("BRAVO dormant"));
        rm.stop();

        // ---- T9: measured rotation time (v0.23) ----------------------
        reset();
        meshRoster.add("BRAVO");
        rm = fresh("ALPHA");                   // roster ALPHA BRAVO
        check("T9 fallback before first wrap",
                rm.measuredCycleMs() == 2 * (1200 + 1500));
        step(rm, 8000);                        // own-turn deadline (unsettled)
        step(rm, 1300);                        // BRAVO skip -> first wrap
        for (int i = 0; i < 4; i++) {
            step(rm, 100);                     // own empty turn passes
            step(rm, 1300);                    // BRAVO silent skip -> wrap
        }
        long cycle = rm.measuredCycleMs();     // steady cycle ~1.3-1.5 s
        check("T9 measured cycle in range", cycle >= 1000 && cycle <= 3000);
        rm.stop();

        System.out.println(failures == 0
                ? "All RingMac tests PASSED"
                : failures + " RingMac test(s) FAILED");
        if (failures > 0) System.exit(1);
    }
}
