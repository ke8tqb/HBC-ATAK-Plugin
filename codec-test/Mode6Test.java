import com.atakmap.android.hbc.HbcDecoder;
import com.atakmap.android.hbc.HbcEncoder;

public class Mode6Test {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 1) user's yellow spot (2525C usericon) -> Mode 6 kind 1
        String spot2525 =
            "<event version=\"2.0\" uid=\"f1907ab4\" type=\"a-u-G\" how=\"h-g-i-g-o\">"
          + "<point lat=\"40.621743\" lon=\"-85.204462\" hae=\"219.631\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><creator callsign=\"ONYX\" type=\"a-f-G-U-C\" uid=\"x\"/>"
          + "<usericon iconsetpath=\"COT_MAPPING_2525C/a-u/a-u-G\"/>"
          + "<color argb=\"-1\"/>"
          + "<contact callsign=\"U.17.124805\"/></detail></event>";
        HbcEncoder.Encoded e1 = HbcEncoder.encode(spot2525);
        check("2525 mode", e1.mode, 6);
        HbcDecoder.Decoded d1 = HbcDecoder.decode(e1.bytes);
        checkS("2525 type", d1.cotType, "a-u-G");
        check("2525 kind", d1.iconKind, 1);
        checkS("2525 call", d1.callsign, "ONYX");
        check("2525 tint", d1.hasTint ? 1 : 0, 1);
        check("2525 tintArgb", d1.tintArgb, -1);
        System.out.println("2525 bytes " + e1.bytes.length + ": " + hex(e1.bytes));
        System.out.println("  xml: " + d1.toXml().replace("\n", " "));

        // 2) yellow spot-map marker
        String spotmap =
            "<event version=\"2.0\" uid=\"abc\" type=\"b-m-p-s-m\" how=\"h-g-i-g-o\">"
          + "<point lat=\"40.1\" lon=\"-85.2\" hae=\"0\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><creator callsign=\"ONYX\"/>"
          + "<usericon iconsetpath=\"COT_MAPPING_SPOTMAP/b-m-p-s-m/-256\"/>"
          + "<color argb=\"-256\"/>"
          + "<contact callsign=\"SPOT1\"/></detail></event>";
        HbcEncoder.Encoded e2 = HbcEncoder.encode(spotmap);
        check("spotmap mode", e2.mode, 6);
        HbcDecoder.Decoded d2 = HbcDecoder.decode(e2.bytes);
        checkS("spotmap type", d2.cotType, "b-m-p-s-m");
        check("spotmap kind", d2.iconKind, 2);
        check("spotmap argb", d2.spotArgb, 0xFFFFFF00);
        System.out.println("spotmap bytes " + e2.bytes.length + ": " + hex(e2.bytes));

        // 3) custom iconset (public safety helicopter)
        String custom =
            "<event version=\"2.0\" uid=\"def\" type=\"a-f-A\" how=\"h-g-i-g-o\">"
          + "<point lat=\"40.5\" lon=\"-85.5\" hae=\"0\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><creator callsign=\"KE8TQB\"/>"
          + "<usericon iconsetpath=\"6d781afb-89a6-4c07-b2b9-a89748b6a38f/AIR/EMS.PNG\"/>"
          + "<contact callsign=\"MEDEVAC\"/></detail></event>";
        HbcEncoder.Encoded e3 = HbcEncoder.encode(custom);
        check("custom mode", e3.mode, 6);
        HbcDecoder.Decoded d3 = HbcDecoder.decode(e3.bytes);
        checkS("custom type", d3.cotType, "a-f-A");
        check("custom kind", d3.iconKind, 3);
        checkS("custom uuid", d3.iconsetUuid, "6d781afb-89a6-4c07-b2b9-a89748b6a38f");
        checkS("custom path", d3.iconSubPath, "AIR/EMS.PNG");
        System.out.println("custom bytes " + e3.bytes.length + ": " + hex(e3.bytes));

        // 4) PLI must still be Mode 1
        String pli =
            "<event version=\"2.0\" uid=\"a\" type=\"a-f-G-U-C\" how=\"m-g\">"
          + "<point lat=\"39.87\" lon=\"-98.32\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><contact callsign=\"KE8TQB\"/><uid Droid=\"KE8TQB\"/></detail></event>";
        check("pli stays mode1", HbcEncoder.encode(pli).mode, 1);

        System.out.println(failures == 0 ? "ALL MODE6 TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String l, int got, int want) {
        if (got != want) { System.out.println("FAIL " + l + " got " + got + " want " + want); failures++; }
        else System.out.println("PASS " + l);
    }

    static void checkS(String l, String got, String want) {
        if (!got.equals(want)) { System.out.println("FAIL " + l + " got '" + got + "' want '" + want + "'"); failures++; }
        else System.out.println("PASS " + l);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i]));
        }
        return sb.toString();
    }
}
