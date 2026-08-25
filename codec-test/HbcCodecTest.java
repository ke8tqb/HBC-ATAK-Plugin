import com.atakmap.android.hbc.HbcDecoder;
import com.atakmap.android.hbc.HbcEncoder;

/** Verifies the Java HBC port against the Python reference vectors. */
public class HbcCodecTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        // --- Mode 1 PLI: exact bytes from HBC-Protocol README ---
        String pliXml =
            "<event version=\"2.0\" uid=\"ANDROID-KE8TQB-001\" type=\"a-f-G-U-C\""
          + " time=\"2026-07-12T19:00:41.00Z\" start=\"2026-07-12T19:00:41.00Z\""
          + " stale=\"2026-07-12T19:05:41.00Z\" how=\"m-g\" access=\"Undefined\">"
          + "<point lat=\"39.871776\" lon=\"-98.324262\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><contact callsign=\"KE8TQB\"/><uid Droid=\"KE8TQB\"/>"
          + "<track speed=\"0.0\" course=\"9999999.0\"/></detail></event>";
        check("Mode1 PLI exact hex",
                hex(HbcEncoder.encode(pliXml).bytes),
                "78 76 6F C2 F9 40 0C 96 8A 70 A8 A2 84 61 57 EC 3F CD 40");

        HbcDecoder.Decoded d1 = HbcDecoder.decode(HbcEncoder.encode(pliXml).bytes);
        check("Mode1 callsign", d1.callsign, "KE8TQB");
        check("Mode1 name", d1.name, "KE8TQB");
        check("Mode1 spot", String.valueOf(d1.isSpot), "false");
        checkClose("Mode1 lat", d1.lat, 39.8718);
        checkClose("Mode1 lon", d1.lon, -98.3243);

        // --- Mode 1 Spot: exact bytes from README ---
        String spotXml =
            "<event version=\"2.0\" uid=\"f1907ab4\" type=\"a-u-G\" how=\"h-g-i-g-o\">"
          + "<point lat=\"39.871743\" lon=\"-100.324462\" hae=\"219.631\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><creator callsign=\"ONYX\" type=\"a-f-G-U-C\" uid=\"x\"/>"
          + "<contact callsign=\"U.17.124805\"/></detail></event>";
        // Since HBC v1.3, spots upgrade to Mode 6 (extended marker, kind 0)
        HbcEncoder.Encoded spotEnc = HbcEncoder.encode(spotXml);
        check("Mode1 Spot upgraded to Mode 6", String.valueOf(spotEnc.mode), "6");
        HbcDecoder.Decoded spotDec = HbcDecoder.decode(spotEnc.bytes);
        check("Spot type preserved", spotDec.cotType, "a-u-G");
        check("Spot name", spotDec.name, "U.17.12");

        // --- Mode 2 Alert round-trip ---
        String alertXml =
            "<event version=\"2.0\" uid=\"14403346965-9-1-1\" type=\"b-a-o-tbl\" how=\"m-g\">"
          + "<point lat=\"39.871743\" lon=\"-100.324462\" hae=\"241.601\" ce=\"4.5\" le=\"9999999\"/>"
          + "<detail><emergency type=\"911 Alert\"/><contact callsign=\"ONYX-Alert\"/></detail></event>";
        HbcDecoder.Decoded d2 = HbcDecoder.decode(HbcEncoder.encode(alertXml).bytes);
        check("Mode2 active", String.valueOf(d2.alertActive), "true");
        check("Mode2 alertName", d2.alertName, "ONYX-Al");
        check("Mode2 origName", d2.origName, "ONYX");
        checkClose("Mode2 lat", d2.lat, 39.8717);

        // --- Mode 2 Cancel round-trip ---
        String cancelXml =
            "<event version=\"2.0\" uid=\"x-9-1-1\" type=\"b-a-o-can\" how=\"m-g\">"
          + "<point lat=\"38.871749\" lon=\"-99.32417\" hae=\"248.301\" ce=\"2.0\" le=\"9999999\"/>"
          + "<detail><emergency cancel=\"true\">KEYSTON</emergency></detail></event>";
        HbcDecoder.Decoded d2c = HbcDecoder.decode(HbcEncoder.encode(cancelXml).bytes);
        check("Mode2c active", String.valueOf(d2c.alertActive), "false");
        check("Mode2c origName", d2c.origName, "KEYSTON");

        // --- Mode 3 GeoChat: exact bytes from README ---
        String chatXml =
            "<event version=\"2.0\" uid=\"GeoChat.S.All Chat Rooms.x\" type=\"b-t-f\" how=\"h-g-i-g-o\">"
          + "<point lat=\"0\" lon=\"0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
          + "<detail><__chat id=\"All Chat Rooms\" chatroom=\"All Chat Rooms\""
          + " senderCallsign=\"RECEIVER\" groupOwner=\"false\" messageId=\"m\">"
          + "<chatgrp id=\"All Chat Rooms\" uid0=\"S\" uid1=\"All Chat Rooms\"/></__chat>"
          + "<link uid=\"S\" type=\"a-f-G-U\" relation=\"p-p\"/>"
          + "<remarks source=\"BAO.F.WinTAK.S\" to=\"All Chat Rooms\""
          + " time=\"2026-08-19T01:15:28.47Z\">test message</remarks></detail></event>";
        check("Mode3 exact hex",
                hex(HbcEncoder.encode(chatXml).bytes),
                "50 5C 13 78 2A 40 50 09 60 4E 04 A5 1E 82 80");
        HbcDecoder.Decoded d3 = HbcDecoder.decode(HbcEncoder.encode(chatXml).bytes);
        check("Mode3 text", d3.chatText, "TEST MESSAGE");
        check("Mode3 sender", d3.callsign, "RECEIVER");

        // --- Mode 4 Circle: exact bytes from README ---
        String circleXml =
            "<event version=\"2.0\" uid=\"858caa5a\" type=\"u-d-c-c\" how=\"h-g-i-g-o\">"
          + "<point lat=\"38.91531734\" lon=\"-99.17975988\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
          + "<detail><creator uid=\"S\" type=\"a-f-G-U\" callsign=\"RECEIVER\" time=\"t\"/>"
          + "<shape><ellipse minor=\"346.236470751501\" angle=\"360\" major=\"346.236470751501\"/></shape>"
          + "<contact callsign=\"Circle 1\"/></detail></event>";
        check("Mode4 circle exact hex",
                hex(HbcEncoder.encode(circleXml).bytes),
                "50 5C 13 78 2A 40 67 43 69 72 63 6C 65 20 2F 81 0E 1B B9 40 2B 40");

        // --- Mode 4 Rectangle: exact bytes from README ---
        String rectXml =
            "<event version=\"2.0\" uid=\"7e6ed0be\" type=\"u-d-r\" how=\"h-e\">"
          + "<point lat=\"38.90963393\" lon=\"-99.20234887\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
          + "<detail><creator uid=\"S\" type=\"a-f-G-U\" callsign=\"RECEIVER\" time=\"t\"/>"
          + "<link point=\"38.91933019,-99.18464613\"/>"
          + "<link point=\"38.91933019,-99.22005161\"/>"
          + "<link point=\"38.89993498,-99.22004666\"/>"
          + "<link point=\"38.89993498,-99.18465108\"/>"
          + "<contact callsign=\"Rectangle 2\"/></detail></event>";
        check("Mode4 rect exact hex",
                hex(HbcEncoder.encode(rectXml).bytes),
                "50 5C 13 78 2A 40 6F 52 65 63 74 61 6E 67 42 F8 24 E1 BB 34 00 07 D3 BF 9F 00 02 00 00 2C 20");
        HbcDecoder.Decoded d4 = HbcDecoder.decode(HbcEncoder.encode(rectXml).bytes);
        check("Mode4 points", String.valueOf(d4.shapePoints.size()), "4");
        check("Mode4 kind", String.valueOf(d4.shapeKind), "1");

        // --- Mode 5 CASEVAC: exact bytes from README ---
        String medXml =
            "<event version=\"2.0\" uid=\"ed60672a\" type=\"b-r-f-h-c\" how=\"h-g-i-g-o\">"
          + "<point lat=\"38.92363824\" lon=\"-99.29583102\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
          + "<detail><link type=\"a-f-G-U\" uid=\"S\" parent_callsign=\"RECEIVER\" relation=\"p-p\""
          + " production_time=\"2026-08-19T01:19:41Z\"/><archive/>"
          + "<_medevac_ casevac=\"False\" title=\"MED.19.011941\" freq=\"0.0\" urgent=\"1\" routine=\"3\""
          + " priority=\"4\" urgent_surgical=\"2\" convenience=\"5\" equipment_none=\"true\""
          + " ambulatory=\"1\" security=\"0\" hlz_marking=\"3\" terrain_none=\"true\""
          + " obstacles=\"None\" zone_prot_selection=\"0\"/>"
          + "<contact callsign=\"RECEIVER.1\"/></detail></event>";
        check("Mode5 exact hex",
                hex(HbcEncoder.encode(medXml).bytes),
                "50 5C 13 78 2A 40 9D 35 15 10 B8 C4 E4 B8 00 00 49 0D 40 58 61 7C 1D 30 D9 42");
        HbcDecoder.Decoded d5 = HbcDecoder.decode(HbcEncoder.encode(medXml).bytes);
        check("Mode5 title", d5.name, "MED.19.");
        check("Mode5 urgent", String.valueOf(d5.urgent), "1");
        check("Mode5 hlz", String.valueOf(d5.hlz), "3");

        // XML reconstruction sanity: parseable, correct root fields
        for (HbcDecoder.Decoded d : new HbcDecoder.Decoded[]{d1, d2, d2c, d3, d4, d5}) {
            String xml = d.toXml();
            if (!xml.startsWith("<event ") || !xml.endsWith("</event>")) {
                System.out.println("FAIL xml reconstruction mode " + d.mode + ": " + xml);
                failures++;
            }
        }

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String label, String got, String want) {
        if (!got.equals(want)) {
            System.out.println("FAIL " + label + "\n  got:  " + got + "\n  want: " + want);
            failures++;
        } else {
            System.out.println("PASS " + label);
        }
    }

    static void checkClose(String label, double got, double want) {
        if (Math.abs(got - want) > 0.001) {
            System.out.println("FAIL " + label + " got " + got + " want " + want);
            failures++;
        } else {
            System.out.println("PASS " + label);
        }
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
