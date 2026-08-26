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
                "78 76 6F C2 F9 40 03 25 A2 9C 2A 28 A1 18 55 FB 0F F3 50");

        HbcDecoder.Decoded d1 = HbcDecoder.decode(HbcEncoder.encode(pliXml).bytes);
        check("Mode1 callsign", d1.callsign, "KE8TQB");
        check("Mode1 name", d1.name, "KE8TQB");
        check("Mode1 spot", String.valueOf(d1.isSpot), "false");
        check("Mode1 affiliation", String.valueOf(d1.affiliation), "0");
        checkClose("Mode1 lat", d1.lat, 39.8718);
        checkClose("Mode1 lon", d1.lon, -98.3243);

        // --- Mode 1 (v1.5): Hostile / Neutral PLI correct labeling ---
        String hostileXml =
            "<event version=\"2.0\" uid=\"ANDROID-hostile001\" type=\"a-h-G-U-C\" how=\"m-g\">"
          + "<point lat=\"39.871776\" lon=\"-98.324262\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><contact callsign=\"BANDIT1\"/><uid Droid=\"BANDIT1\"/></detail></event>";
        check("Mode1 hostile exact hex",
                hex(HbcEncoder.encode(hostileXml).bytes),
                "C8 D8 93 43 77 FA 00 3D 09 05 39 11 25 50 C4 C2 AF D8 7F 9A 80");
        HbcDecoder.Decoded dHostile = HbcDecoder.decode(HbcEncoder.encode(hostileXml).bytes);
        check("Mode1 hostile affiliation", String.valueOf(dHostile.affiliation), "1");
        check("Mode1 hostile decoded type", extractType(dHostile.toXml()), "a-h-G");

        String neutralXml =
            "<event version=\"2.0\" uid=\"ANDROID-neutral001\" type=\"a-n-G-U-C\" how=\"m-g\">"
          + "<point lat=\"39.871776\" lon=\"-98.324262\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><contact callsign=\"CIVIC1\"/><uid Droid=\"CIVIC1\"/></detail></event>";
        check("Mode1 neutral exact hex",
                hex(HbcEncoder.encode(neutralXml).bytes),
                "71 BC 67 6E FF 40 0B 21 A4 AB 24 A1 98 98 55 FB 0F F3 50");
        HbcDecoder.Decoded dNeutral = HbcDecoder.decode(HbcEncoder.encode(neutralXml).bytes);
        check("Mode1 neutral affiliation", String.valueOf(dNeutral.affiliation), "2");
        check("Mode1 neutral decoded type", extractType(dNeutral.toXml()), "a-n-G");

        // Real captured Command Post (b-m-p-c-cp): 'cp' is a 2-char type token,
        // so Mode 6 rejects it and it falls back to Mode 1. Not an 'a-' atom
        // type at all, so it correctly reports Unknown (3).
        String cpXml =
            "<event version=\"2.0\" uid=\"246b95d0-ba26-4577-a1c8-918276dff506\" type=\"b-m-p-c-cp\" how=\"h-g-i-g-o\">"
          + "<point lat=\"39.6227396\" lon=\"-84.2035144\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail><creator uid=\"ANDROID-60a23e2d48e13de0\" callsign=\"FAF\" type=\"a-f-G-U-C\"/>"
          + "<contact callsign=\"FAF.25.194409\"/></detail></event>";
        check("Mode1 command-post exact hex",
                hex(HbcEncoder.encode(cpXml).bytes),
                "68 DA 80 3F 46 41 46 2E 32 35 2E 30 5E 1E 64 D9 A0");
        HbcDecoder.Decoded dCp = HbcDecoder.decode(HbcEncoder.encode(cpXml).bytes);
        check("Mode1 command-post affiliation", String.valueOf(dCp.affiliation), "3");
        check("Mode1 command-post name", dCp.name, "FAF.25.");

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
                "50 5C 13 78 2A 40 44 02 58 13 81 29 47 A0 A0");
        HbcDecoder.Decoded d3 = HbcDecoder.decode(HbcEncoder.encode(chatXml).bytes);
        check("Mode3 text", d3.chatText, "TEST MESSAGE");
        check("Mode3 sender", d3.callsign, "RECEIVER");
        check("Mode3 dest kind", String.valueOf(d3.chatDestKind), "0");

        // --- Mode 3 (v1.4): Named Room ---
        String roomXml =
            "<event version=\"2.0\" uid=\"GeoChat.S-1-5-21.9f8e7d6c.a1b2c3d4\" type=\"b-t-f\" how=\"h-g-i-g-o\">"
          + "<point lat=\"0\" lon=\"0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
          + "<detail><__chat id=\"9f8e7d6c-0000-0000-0000-000000000000\" chatroom=\"Recon Team\""
          + " senderCallsign=\"RECEIVER\" groupOwner=\"false\" messageId=\"a1b2c3d4-0000-0000-0000-000000000000\">"
          + "<chatgrp id=\"9f8e7d6c-0000-0000-0000-000000000000\" uid0=\"S-1-5-21\""
          + " uid1=\"ANDROID-aaaa\" uid2=\"ANDROID-bbbb\"/></__chat>"
          + "<link uid=\"S-1-5-21\" type=\"a-f-G-U\" relation=\"p-p\"/>"
          + "<remarks source=\"BAO.F.WinTAK.S-1-5-21\" to=\"Recon Team\""
          + " time=\"2026-08-25T14:02:11.00Z\">status check</remarks></detail></event>";
        check("Mode3 room exact hex",
                hex(HbcEncoder.encode(roomXml).bytes),
                "50 5C 13 78 2A 40 4A 82 EC 30 90 08 F8 82 C0 70 39 48 EA 05 CF 40");
        HbcDecoder.Decoded dRoom = HbcDecoder.decode(HbcEncoder.encode(roomXml).bytes);
        check("Mode3 room dest kind", String.valueOf(dRoom.chatDestKind), "1");
        check("Mode3 room name", dRoom.chatRoom, "RECON TEAM");
        check("Mode3 room text", dRoom.chatText, "STATUS CHECK");

        // --- Mode 3 (v1.4): Direct Message ---
        String dmXml =
            "<event version=\"2.0\" uid=\"GeoChat.S-1-5-21.c07f979e.e0295a69\" type=\"b-t-f\" how=\"h-g-i-g-o\">"
          + "<point lat=\"0\" lon=\"0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
          + "<detail><__chat id=\"c07f979e-0000-0000-0000-000000000000\" chatroom=\"ONYX\""
          + " senderCallsign=\"RECEIVER\" groupOwner=\"false\" messageId=\"e0295a69-0000-0000-0000-000000000000\">"
          + "<chatgrp id=\"c07f979e-0000-0000-0000-000000000000\" uid0=\"S-1-5-21\""
          + " uid1=\"ANDROID-onyxuid\"/></__chat>"
          + "<link uid=\"S-1-5-21\" type=\"a-f-G-U\" relation=\"p-p\"/>"
          + "<remarks source=\"BAO.F.WinTAK.S-1-5-21\" to=\"ONYX\""
          + " time=\"2026-08-25T14:05:00.00Z\">helo landing zone</remarks></detail></event>";
        // v1.6: DM carries a 16-bit message tag (CRC-16 of __chat/@messageId)
        check("Mode3 dm exact hex",
                hex(HbcEncoder.encode(dmXml).bytes),
                "50 5C 13 78 2A 40 56 19 5E A1 16 0E 81 96 09 21 B1 26 66 89 1C 30 28");
        HbcDecoder.Decoded dDm = HbcDecoder.decode(HbcEncoder.encode(dmXml).bytes);
        check("Mode3 dm dest kind", String.valueOf(dDm.chatDestKind), "2");
        check("Mode3 dm recipient", dDm.chatRecipient, "ONYX");
        check("Mode3 dm text", dDm.chatText, "HELO LANDING ZONE");
        check("Mode3 dm tag", String.format("%04X", dDm.chatMsgTag), "4583");
        check("Mode3 dm uid1", dDm.toXml().contains("uid1=\"HBC-ONYX\"") ? "yes" : "no", "yes");

        // v1.6: Mode 0 ack round trip (delivered + read), exact reference bytes
        HbcEncoder.Encoded ackD = HbcEncoder.encodeAck("ONYX", "RECEIVER",
                HbcEncoder.ACK_DELIVERED, dDm.chatMsgTag);
        check("Mode0 ack mode", String.valueOf(ackD.mode), "0");
        HbcDecoder.Decoded adD = HbcDecoder.decode(ackD.bytes);
        check("Mode0 ack rx mode", String.valueOf(adD.mode), "0");
        check("Mode0 ack kind", String.valueOf(adD.ackKind), "0");
        check("Mode0 ack recipient", adD.ackRecipient, "RECEIVER");
        check("Mode0 ack tag", String.format("%04X", adD.chatMsgTag), "4583");
        adD.ackMessageId = "e0295a69-0000-0000-0000-000000000000";
        check("Mode0 receipt uid", adD.toXml().contains(
                "uid=\"e0295a69-0000-0000-0000-000000000000\"") ? "y" : "n", "y");
        check("Mode0 receipt type", adD.toXml().contains("type=\"b-t-f-d\"") ? "y" : "n", "y");
        HbcDecoder.Decoded adR = HbcDecoder.decode(HbcEncoder.encodeAck(
                "ONYX", "RECEIVER", HbcEncoder.ACK_READ, dDm.chatMsgTag).bytes);
        check("Mode0 read kind", String.valueOf(adR.ackKind), "1");
        check("Mode0 read receipt type", adR.toXml().contains("type=\"b-t-f-r\"") ? "y" : "n", "y");

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

    static String extractType(String xml) {
        int i = xml.indexOf("type=\"");
        if (i < 0) return "";
        int start = i + 6;
        int end = xml.indexOf('"', start);
        return xml.substring(start, end);
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
