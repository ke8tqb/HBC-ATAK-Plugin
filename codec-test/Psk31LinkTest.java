import com.atakmap.android.hbc.Psk31;
import com.atakmap.android.hbc.Psk31Armor;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * End-to-end PSK31 link test on the JVM: binary mesh frame -> armor text ->
 * Psk31 modulator -> (channel impairments) -> Psk31 receiver -> deframer ->
 * original bytes. Also validates CRC rejection, immunity to human PSK31
 * chatter, and that the armor's airtime estimate brackets the real rendered
 * burst duration (the Ring MAC turn budget depends on it).
 */
public class Psk31LinkTest {
    static int failures = 0;

    static short[] render(String text, double freqOffset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Psk31.transmit(text, 1500 + freqOffset, 8000, (block, count) -> {
            for (int i = 0; i < count; i++) {
                out.write(block[i] & 255);
                out.write((block[i] >> 8) & 255);
            }
            return true;
        });
        byte[] b = out.toByteArray();
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++)
            s[i] = (short) ((b[2 * i] & 255) | (b[2 * i + 1] << 8));
        return s;
    }

    static byte[] decode(short[] samples) {
        final byte[][] got = new byte[1][];
        final Psk31Armor.Deframer df = new Psk31Armor.Deframer();
        Psk31.Receiver rx = new Psk31.Receiver(1500, c -> {
            byte[] f = df.accept(c);
            if (f != null) got[0] = f;
        });
        for (int off = 0; off < samples.length; off += 173) {
            int n = Math.min(173, samples.length - off);
            short[] chunk = new short[n];
            System.arraycopy(samples, off, chunk, 0, n);
            rx.accept(chunk, n);
        }
        rx.accept(new short[8000], 8000);   // flush
        return got[0];
    }

    static void roundTrip(String name, byte[] frame, double freqOffset, double noise) {
        short[] clean = render(Psk31Armor.frame(frame), freqOffset);
        short[] ch = new short[clean.length + 4000];
        Random r = new Random(42);
        for (int i = 0; i < clean.length; i++) {
            double v = clean[i] + r.nextGaussian() * noise;
            ch[i + 1777] = (short) Math.max(-32768, Math.min(32767, v));
        }
        byte[] got = decode(ch);
        if (got != null && Arrays.equals(got, frame))
            System.out.println("PASS " + name);
        else {
            System.out.println("FAIL " + name + " got "
                    + (got == null ? "null" : got.length + "B"));
            failures++;
        }
    }

    public static void main(String[] args) {
        Random r = new Random(7);

        // typical PLI-sized mesh frame (binary, all byte values possible)
        byte[] pli = new byte[28];
        r.nextBytes(pli);
        roundTrip("PLI-size frame clean loopback", pli, 0, 0);
        roundTrip("PLI-size frame +3 Hz offset", pli, 3, 0);
        roundTrip("PLI-size frame additive noise", pli, 0, 2000);

        // Mode 6 marker-sized frame
        byte[] marker = new byte[60];
        r.nextBytes(marker);
        roundTrip("Mode6-size frame clean", marker, 0, 0);
        roundTrip("Mode6-size frame noise", marker, -2, 1500);

        // corruption must be rejected by the CRC, not delivered
        short[] good = render(Psk31Armor.frame(pli), 0);
        // zero out a 200 ms slice mid-frame: destroys several characters
        short[] bad = good.clone();
        Arrays.fill(bad, bad.length / 2, bad.length / 2 + 1600, (short) 0);
        byte[] gotBad = decode(bad);
        if (gotBad == null) System.out.println("PASS corrupted frame rejected");
        else { System.out.println("FAIL corruption accepted"); failures++; }

        // human PSK31 chatter (with stray '~') must not produce frames
        byte[] chatter = decode(render(
                "CQ CQ DE N0CALL ~ hello psk31 world 73 ~", 0));
        if (chatter == null) System.out.println("PASS human chatter ignored");
        else { System.out.println("FAIL chatter decoded as frame"); failures++; }

        // airtime estimate must bracket reality (feeds the Ring turn budget):
        // estimate >= actual, and within +60% (not wastefully oversized)
        int[] sizes = {20, 28, 60, 120, 180};
        for (int n : sizes) {
            byte[] f = new byte[n];
            r.nextBytes(f);
            int actualMs = render(Psk31Armor.frame(f), 0).length / 8; // 8 samp/ms
            int estMs = Psk31Armor.estimateAirtimeMs(n);
            boolean ok = estMs >= actualMs && estMs <= actualMs * 1.6;
            System.out.println((ok ? "PASS" : "FAIL") + " airtime " + n
                    + " B: actual " + actualMs + " ms, estimate " + estMs + " ms");
            if (!ok) failures++;
        }

        System.out.println(failures == 0 ? "ALL PSK31 LINK TESTS PASSED"
                : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
