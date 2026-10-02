package com.atakmap.android.hbc;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * HBC Protocol v1.5 decoder — packed HBC bytes to reconstructed CoT XML.
 * Direct port of hbc_decoder.py (Modes 1-6), including the deterministic
 * UID derivation rule (HBC-{CALLSIGN}, HBC-{ORIGINATOR}-911, etc.).
 */
public final class HbcDecoder {

    public static final String UID_PREFIX = "HBC";

    /** Mode 1 (v1.5) Affiliation code -> reconstructed atom type. */
    private static final String[] AFFILIATION_TYPES = {"a-f-G", "a-h-G", "a-n-G", "a-u-G"};

    private HbcDecoder() {}

    /** Decoded message with CoT XML reconstruction. */
    public static final class Decoded {
        public int mode;
        public int version;
        public String callsign = "";
        public double lat, lon;

        // Mode 1
        public boolean isSpot;
        public String name = "";
        public int affiliation = 3; // 0 Friendly, 1 Hostile, 2 Neutral, 3 Unknown
        // Mode 2
        public boolean alertActive = true;
        public String alertName = "";
        public String origName = "";
        // Mode 3
        public String chatText = "";
        public int chatDestKind;         // 0 All Chat Rooms, 1 Named Room, 2 Direct Message
        public String chatRoom = "";
        public String chatRecipient = "";
        /** Set by the receiving plugin when the DM recipient is this device:
         *  the local ATAK device UID. ATAK's chat service only files a 1:1
         *  message into the chat window when chatgrp/uid1 equals the local
         *  device UID, so the derived HBC-{CALLSIGN} UID is not enough. */
        public String chatRecipientUidOverride = null;
        /** Mode 3 DM (v1.6): 16-bit message tag echoed in Mode 0 acks. */
        public int chatMsgTag;
        /** Set by xmlMode3(): the fresh messageId the injected DM was given,
         *  so the plugin can map it back to the tag when ATAK emits its
         *  automatic b-t-f-d/b-t-f-r receipt for that message. */
        public String chatInjectedMessageId = "";

        // Mode 0 — Ack (v1.6)
        public int ackKind;              // 0 delivered (b-t-f-d), 1 read (b-t-f-r)
        public String ackRecipient = ""; // original DM sender this ack targets
        /** Set by the receiving plugin before toXml(): the original ATAK
         *  messageId the tag maps to. ATAK matches receipts to chat messages
         *  purely by the receipt event's UID == messageId. */
        public String ackMessageId = "";
        // Mode 4
        public int shapeKind;
        public int radiusM;
        public final List<double[]> shapePoints = new ArrayList<>();
        // Mode 5
        public int freqUnits, urgent, urgentSurgical, priority, routine,
                convenience, litter, ambulatory, flags, security, hlz, zone;
        // Mode 6
        public String cotType = "";     // reconstructed full type string
        public int iconKind;            // 0 none, 1 2525C, 2 spotmap, 3 custom
        public int spotArgb;
        public String iconsetUuid = ""; // lowercase hyphenated uuid
        public String iconSubPath = "";
        public boolean hasTint;
        public int tintArgb;

        /** Set by the plugin before toXml(): a live ATAK network contact
         *  with the same callsign already exists (e.g. both stations also
         *  share a WiFi/TAK-server link), so omit the mesh endpoint from
         *  the reconstructed PLI to avoid a duplicated contacts-list row. */
        public boolean suppressEndpoint = false;

        public String summary() {
            switch (mode) {
                case 0: return "Mode 0 Ack(" + (ackKind == 1 ? "READ" : "DELIVERED")
                        + ") " + callsign + " -> " + ackRecipient
                        + " tag 0x" + String.format("%04X", chatMsgTag);
                case 1: return "Mode 1 " + (isSpot ? "Spot" : "PLI") + " " + callsign
                        + " '" + name + "' @ " + fmt6(lat) + "," + fmt6(lon);
                case 2: return "Mode 2 Alert(" + (alertActive ? "ACTIVE" : "CANCEL") + ") "
                        + origName + " @ " + fmt6(lat) + "," + fmt6(lon);
                case 3: return "Mode 3 Chat " + callsign + ": " + chatText;
                case 4: return "Mode 4 Shape(" + shapeKind + ") " + callsign + " '" + name + "'";
                case 5: return "Mode 5 CASEVAC " + callsign + " '" + name + "'";
                case 6: return "Mode 6 Marker " + cotType + " " + callsign
                        + " '" + name + "' @ " + fmt6(lat) + "," + fmt6(lon);
                default: return "Mode " + mode + " " + callsign;
            }
        }

        public String toXml() {
            Date now = new Date();
            switch (mode) {
                case 0: return xmlMode0(now);
                case 1: return xmlMode1(now);
                case 2: return xmlMode2(now);
                case 3: return xmlMode3(now);
                case 4: return xmlMode4(now);
                case 5: return xmlMode5(now);
                case 6: return xmlMode6(now);
                default:
                    throw new IllegalStateException("No XML reconstruction for mode " + mode);
            }
        }

        // ------------------------------------------------------------------
        private String xmlMode0(Date now) {
            // ATAK matches chat receipts by the receipt event's UID, which
            // must equal the original message's messageId (set ackMessageId
            // from the sender-side tag map before calling toXml()).
            String cotType = ackKind == 1 ? "b-t-f-r" : "b-t-f-d";
            String uid = ackMessageId.isEmpty()
                    ? String.format("HBC-ACK-%04X", chatMsgTag) : ackMessageId;
            Date stale = new Date(now.getTime() + 5L * 60 * 1000);
            StringBuilder sb = new StringBuilder();
            eventOpen(sb, uid, cotType, now, stale, "h-g-i-g-o");
            sb.append("  <point lat=\"0\" lon=\"0\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/>\n");
            sb.append("  <detail>\n");
            sb.append("    <__chatreceipt ackedUid=\"").append(esc(uid))
              .append("\" senderCallsign=\"").append(esc(callsign)).append("\"/>\n");
            sb.append("  </detail>\n</event>");
            return sb.toString();
        }

        private String xmlMode1(Date now) {
            String uid;
            String cotType = AFFILIATION_TYPES[
                    (affiliation >= 0 && affiliation < AFFILIATION_TYPES.length) ? affiliation : 3];
            String how;
            Date stale;
            if (isSpot) {
                uid = UUID.randomUUID().toString();
                how = "h-g-i-g-o";
                stale = new Date(now.getTime() + 365L * 24 * 3600 * 1000);
            } else {
                uid = UID_PREFIX + "-" + callsign.toUpperCase();
                how = "m-g";
                stale = new Date(now.getTime() + 5L * 60 * 1000);
            }
            StringBuilder sb = new StringBuilder();
            eventOpen(sb, uid, cotType, now, stale, how);
            point(sb, lat, lon);
            sb.append("  <detail>\n");
            if (!isSpot) {
                // Contact display name. The 7-char Mode 1 name field
                // truncates longer ATAK callsigns (KEYSTONE -> KEYSTON);
                // when the separately-carried station callsign confirms the
                // name is just its truncated prefix, use the full callsign.
                // Otherwise chat replies to this contact are addressed to
                // the truncated name and the station drops them as
                // "not this station".
                String display = name.isEmpty() ? callsign : name;
                if (display.length() >= 7
                        && callsign.length() > display.length()
                        && callsign.toUpperCase().startsWith(display.toUpperCase()))
                    display = callsign;
                // ATAK only registers a station as a messageable contact
                // (chat DM list, "send to" pickers) when its PLI carries a
                // <contact endpoint=...>. Use the standard mesh endpoint
                // placeholder; outgoing chat to it is intercepted by the
                // plugin's PreSendProcessor and sent over HBC anyway.
                sb.append("    <contact callsign=\"")
                  .append(esc(display));
                if (!suppressEndpoint)
                    sb.append("\" endpoint=\"*:-1:stcp");
                sb.append("\"/>\n");
                sb.append("    <__group name=\"Cyan\" role=\"Team Member\"/>\n");
                sb.append("    <uid Droid=\"").append(esc(display)).append("\"/>\n");
                sb.append("    <track speed=\"0.0\" course=\"9999999.0\"/>\n");
            } else {
                sb.append("    <contact callsign=\"").append(esc(name.isEmpty() ? callsign : name)).append("\"/>\n");
                sb.append("    <creator callsign=\"").append(esc(callsign)).append("\"/>\n");
                sb.append("    <archive/>\n");
            }
            sb.append("  </detail>\n</event>");
            return sb.toString();
        }

        private String xmlMode2(Date now) {
            String originator = origName.isEmpty() ? callsign : origName;
            String uid = UID_PREFIX + "-" + originator.toUpperCase() + "-911";
            StringBuilder sb = new StringBuilder();
            if (!alertActive) {
                Date stale = new Date(now.getTime() + 60L * 1000);
                eventOpen(sb, uid, "b-a-o-can", now, stale, "m-g");
                point(sb, lat, lon);
                sb.append("  <detail>\n");
                sb.append("    <emergency cancel=\"true\">").append(esc(originator)).append("</emergency>\n");
                sb.append("  </detail>\n</event>");
                return sb.toString();
            }
            Date stale = new Date(now.getTime() + 5L * 60 * 1000);
            eventOpen(sb, uid, "b-a-o-tbl", now, stale, "m-g");
            point(sb, lat, lon);
            sb.append("  <detail>\n");
            sb.append("    <emergency type=\"911 Alert\"/>\n");
            sb.append("    <contact callsign=\"")
              .append(esc(alertName.isEmpty() ? originator + "-Alert" : alertName)).append("\"/>\n");
            sb.append("    <creator callsign=\"").append(esc(originator)).append("\"/>\n");
            sb.append("  </detail>\n</event>");
            return sb.toString();
        }

        private String xmlMode3(Date now) {
            String msgId = UUID.randomUUID().toString();
            chatInjectedMessageId = msgId;
            String senderUid = UID_PREFIX + "-" + callsign.toUpperCase();

            String roomId, destUid, display, toAttr;
            if (chatDestKind == 1) {                     // Named Room
                roomId = chatRoom;
                destUid = roomId;
                display = roomId;
                toAttr = roomId;
            } else if (chatDestKind == 2) {               // Direct Message
                display = chatRecipient.toUpperCase();
                // Prefer the real local device UID (set by the plugin when this
                // EUD is the recipient) so ATAK files the message into the chat
                // window; fall back to the derived HBC contact UID otherwise.
                destUid = chatRecipientUidOverride != null
                        ? chatRecipientUidOverride
                        : UID_PREFIX + "-" + display;
                // Real ATAK 1:1 wire format uses the recipient UID as the
                // conversation id and the peer callsign as the chatroom label.
                roomId = destUid;
                toAttr = destUid;
            } else {                                      // All Chat Rooms (default)
                roomId = destUid = display = toAttr = "All Chat Rooms";
            }

            String uid = "GeoChat." + senderUid + "." + roomId + "." + msgId;
            Date stale = new Date(now.getTime() + 24L * 3600 * 1000);
            StringBuilder sb = new StringBuilder();
            eventOpen(sb, uid, "b-t-f", now, stale, "h-g-i-g-o");
            sb.append("  <point lat=\"0\" lon=\"0\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/>\n");
            sb.append("  <detail>\n");
            sb.append("    <__chat id=\"").append(esc(roomId)).append("\" chatroom=\"").append(esc(display))
              .append("\" senderCallsign=\"").append(esc(callsign))
              .append("\" groupOwner=\"false\" messageId=\"").append(msgId).append("\">\n");
            sb.append("      <chatgrp id=\"").append(esc(roomId)).append("\" uid0=\"").append(esc(senderUid))
              .append("\" uid1=\"").append(esc(destUid)).append("\"/>\n");
            sb.append("    </__chat>\n");
            sb.append("    <link uid=\"").append(esc(senderUid)).append("\" type=\"a-f-G-U\" relation=\"p-p\"/>\n");
            // remarks/@source MUST use the BAO.F.ATAK. prefix: ATAK's
            // ChatMessageParser.getSenderUid() strips exactly that prefix to
            // recover the sender uid. Any other prefix (we used BAO.F.HBC.)
            // is taken VERBATIM as the sender uid, so ATAK fabricated a
            // second contact "BAO.F.HBC.HBC-<CALL>" and filed incoming DMs
            // into its window while replies went out from the real
            // HBC-<CALL> contact — a split conversation. With this prefix
            // the sender resolves to our injected HBC-<CALL> contact and
            // both directions share one chat window.
            sb.append("    <remarks source=\"BAO.F.ATAK.").append(esc(senderUid))
              .append("\" to=\"").append(esc(toAttr)).append("\" time=\"").append(ts(now)).append("\">")
              .append(esc(chatText)).append("</remarks>\n");
            sb.append("  </detail>\n</event>");
            return sb.toString();
        }

        private String xmlMode4(Date now) {
            String uid = UUID.randomUUID().toString();
            Date stale = new Date(now.getTime() + 7L * 24 * 3600 * 1000);
            String cotType;
            String how;
            double cLat, cLon;
            if (shapeKind == 0) {
                cotType = "u-d-c-c";
                how = "h-g-i-g-o";
                cLat = lat;
                cLon = lon;
            } else {
                cotType = (shapeKind == 1 && shapePoints.size() == 4) ? "u-d-r" : "u-d-f";
                how = "h-e";
                double sa = 0, so = 0;
                for (double[] p : shapePoints) { sa += p[0]; so += p[1]; }
                cLat = sa / shapePoints.size();
                cLon = so / shapePoints.size();
            }
            StringBuilder sb = new StringBuilder();
            eventOpen(sb, uid, cotType, now, stale, how);
            point(sb, cLat, cLon);
            sb.append("  <detail>\n");
            sb.append("    <fillColor value=\"-2130706433\"/>\n");
            sb.append("    <strokeColor value=\"-1\"/>\n");
            sb.append("    <strokeWeight value=\"4.0\"/>\n");
            sb.append("    <strokeStyle value=\"solid\"/>\n");
            sb.append("    <archive/>\n");
            sb.append("    <creator uid=\"").append(UID_PREFIX).append("-")
              .append(esc(callsign.toUpperCase()))
              .append("\" type=\"a-f-G-U\" callsign=\"").append(esc(callsign)).append("\"/>\n");
            if (shapeKind == 0) {
                sb.append("    <shape>\n      <ellipse major=\"").append(radiusM)
                  .append("\" minor=\"").append(radiusM).append("\" angle=\"360\"/>\n    </shape>\n");
            } else {
                List<double[]> outPts = new ArrayList<>(shapePoints);
                if (shapeKind == 1 && cotType.equals("u-d-f"))
                    outPts.add(shapePoints.get(0)); // close the ring
                for (double[] p : outPts)
                    sb.append("    <link point=\"").append(fmt6(p[0])).append(",")
                      .append(fmt6(p[1])).append("\"/>\n");
            }
            sb.append("    <contact callsign=\"")
              .append(esc(name.isEmpty() ? "HBC Shape" : name)).append("\"/>\n");
            sb.append("  </detail>\n</event>");
            return sb.toString();
        }

        private String xmlMode5(Date now) {
            String uid = UUID.randomUUID().toString();
            Date stale = new Date(now.getTime() + 24L * 3600 * 1000);
            String title = name.isEmpty() ? callsign + "-CASEVAC" : name;
            StringBuilder sb = new StringBuilder();
            eventOpen(sb, uid, "b-r-f-h-c", now, stale, "h-g-i-g-o");
            point(sb, lat, lon);
            sb.append("  <detail>\n");
            sb.append("    <link type=\"a-f-G-U\" uid=\"").append(UID_PREFIX).append("-")
              .append(esc(callsign.toUpperCase()))
              .append("\" parent_callsign=\"").append(esc(callsign)).append("\" relation=\"p-p\"/>\n");
            sb.append("    <archive/>\n");
            sb.append("    <_medevac_ casevac=\"").append((flags & 8) != 0 ? "True" : "False")
              .append("\" title=\"").append(esc(title))
              .append("\" freq=\"").append(String.format(Locale.US, "%.1f", freqUnits / 100.0)).append("\"");
            appendCount(sb, "urgent", urgent);
            appendCount(sb, "urgent_surgical", urgentSurgical);
            appendCount(sb, "priority", priority);
            appendCount(sb, "routine", routine);
            appendCount(sb, "convenience", convenience);
            appendCount(sb, "litter", litter);
            appendCount(sb, "ambulatory", ambulatory);
            if ((flags & 4) != 0) sb.append(" equipment_none=\"true\"");
            if ((flags & 2) != 0) sb.append(" terrain_none=\"true\" obstacles=\"None\"");
            sb.append(" security=\"").append(security).append("\"");
            sb.append(" hlz_marking=\"").append(hlz).append("\"");
            sb.append(" zone_prot_selection=\"").append(zone).append("\"/>\n");
            sb.append("    <contact callsign=\"").append(esc(title)).append("\"/>\n");
            sb.append("  </detail>\n</event>");
            return sb.toString();
        }

        private String xmlMode6(Date now) {
            String uid = UUID.randomUUID().toString();
            Date stale = new Date(now.getTime() + 365L * 24 * 3600 * 1000);
            StringBuilder sb = new StringBuilder();
            eventOpen(sb, uid, cotType, now, stale, "h-g-i-g-o");
            point(sb, lat, lon);
            sb.append("  <detail>\n");
            sb.append("    <contact callsign=\"")
              .append(esc(name.isEmpty() ? callsign : name)).append("\"/>\n");
            sb.append("    <creator callsign=\"").append(esc(callsign)).append("\"/>\n");
            sb.append("    <archive/>\n");
            switch (iconKind) {
                case 1: {
                    String[] t = cotType.split("-");
                    String mid = t.length >= 2 ? t[0] + "-" + t[1] : cotType;
                    sb.append("    <usericon iconsetpath=\"COT_MAPPING_2525C/")
                      .append(esc(mid)).append("/").append(esc(cotType)).append("\"/>\n");
                    break;
                }
                case 2:
                    sb.append("    <usericon iconsetpath=\"COT_MAPPING_SPOTMAP/b-m-p-s-m/")
                      .append(spotArgb).append("\"/>\n");
                    sb.append("    <color argb=\"").append(spotArgb).append("\"/>\n");
                    break;
                case 3:
                    sb.append("    <usericon iconsetpath=\"").append(esc(iconsetUuid))
                      .append("/").append(esc(iconSubPath)).append("\"/>\n");
                    break;
                default:
                    break;
            }
            if (hasTint)
                sb.append("    <color argb=\"").append(tintArgb).append("\"/>\n");
            sb.append("  </detail>\n</event>");
            return sb.toString();
        }

        private static void appendCount(StringBuilder sb, String key, int v) {
            if (v != 0) sb.append(" ").append(key).append("=\"").append(v).append("\"");
        }

        private static void eventOpen(StringBuilder sb, String uid, String type,
                                      Date time, Date stale, String how) {
            sb.append("<event version=\"2.0\" uid=\"").append(esc(uid))
              .append("\" type=\"").append(type)
              .append("\" time=\"").append(ts(time))
              .append("\" start=\"").append(ts(time))
              .append("\" stale=\"").append(ts(stale))
              .append("\" how=\"").append(how)
              .append("\" access=\"Undefined\">\n");
        }

        private static void point(StringBuilder sb, double lat, double lon) {
            sb.append("  <point lat=\"").append(fmt6(lat))
              .append("\" lon=\"").append(fmt6(lon))
              .append("\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/>\n");
        }

        private static String ts(Date d) {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.format(d);
        }

        private static String fmt6(double v) {
            return String.format(Locale.US, "%.6f", v);
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&apos;");
        }
    }

    // ------------------------------------------------------------------
    // Decode entry point
    // ------------------------------------------------------------------
    public static Decoded decode(byte[] data) {
        BitReader r = BitReader.fromBytes(data);
        String callsign = Ita2.decode(r);
        int version = r.readInt(3) + 1;
        int mode    = r.readInt(3) + 1;
        if (mode == 8) mode = 0;   // wire bits 111 = Mode 0 Ack (v1.6)

        Decoded d = new Decoded();
        d.callsign = callsign;
        d.version = version;
        d.mode = mode;

        switch (mode) {
            case 1: {
                d.isSpot = r.readInt(1) == 1;
                d.affiliation = r.readInt(2);
                d.name = readName(r);
                d.lat = r.readSigned(21) / 10000.0;
                d.lon = r.readSigned(22) / 10000.0;
                return d;
            }
            case 2: {
                d.alertActive = r.readInt(1) == 1;
                d.alertName = readName(r);
                d.origName = readName(r);
                d.lat = r.readSigned(21) / 10000.0;
                d.lon = r.readSigned(22) / 10000.0;
                return d;
            }
            case 3: {
                d.chatDestKind = r.readInt(2);
                if (d.chatDestKind == 3)
                    throw new IllegalArgumentException("Chat destination kind 11 is reserved");
                if (d.chatDestKind == 1) {
                    d.chatRoom = Ita2.decode(r);
                } else if (d.chatDestKind == 2) {
                    d.chatRecipient = Ita2.decode(r);
                    d.chatMsgTag = r.readInt(16);   // v1.6 message tag
                }
                d.chatText = Ita2.decode(r);
                return d;
            }
            case 0: {
                // Ack (v1.6): recipient callsign + kind (2b) + tag (16b)
                d.ackRecipient = Ita2.decode(r);
                d.ackKind = r.readInt(2);
                if (d.ackKind > 1)
                    throw new IllegalArgumentException("Ack kind " + d.ackKind + " is reserved");
                d.chatMsgTag = r.readInt(16);
                return d;
            }
            case 4: {
                d.shapeKind = r.readInt(2);
                if (d.shapeKind == 3)
                    throw new IllegalArgumentException("Shape kind 11 is reserved");
                d.name = readName(r);
                if (d.shapeKind == 0) {
                    d.lat = r.readSigned(21) / 10000.0;
                    d.lon = r.readSigned(22) / 10000.0;
                    d.radiusM = r.readInt(16);
                    d.shapePoints.add(new double[]{d.lat, d.lon});
                    return d;
                }
                int count = r.readInt(4);
                if (count < 2)
                    throw new IllegalArgumentException("Polygon point count " + count + " invalid");
                d.lat = r.readSigned(21) / 10000.0;
                d.lon = r.readSigned(22) / 10000.0;
                d.shapePoints.add(new double[]{d.lat, d.lon});
                long ila = Math.round(d.lat * 10000.0);
                long ilo = Math.round(d.lon * 10000.0);
                for (int i = 0; i < count - 1; i++) {
                    ila += r.readSigned(14);
                    ilo += r.readSigned(14);
                    d.shapePoints.add(new double[]{ila / 10000.0, ilo / 10000.0});
                }
                return d;
            }
            case 5: {
                d.name = readName(r);
                d.freqUnits = r.readInt(16);
                d.urgent = r.readInt(4);
                d.urgentSurgical = r.readInt(4);
                d.priority = r.readInt(4);
                d.routine = r.readInt(4);
                d.convenience = r.readInt(4);
                d.litter = r.readInt(4);
                d.ambulatory = r.readInt(4);
                d.flags = r.readInt(4);
                d.security = r.readInt(2);
                d.hlz = r.readInt(3);
                d.zone = r.readInt(2);
                d.lat = r.readSigned(21) / 10000.0;
                d.lon = r.readSigned(22) / 10000.0;
                return d;
            }
            case 6: {
                d.name = readName(r);
                d.lat = r.readSigned(21) / 10000.0;
                d.lon = r.readSigned(22) / 10000.0;
                int count = r.readInt(4);
                StringBuilder type = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    if (i > 0) type.append('-');
                    type.append(HbcEncoder.TOKEN_CHARSET.charAt(r.readInt(6)));
                }
                d.cotType = type.toString();
                d.iconKind = r.readInt(2);
                if (d.iconKind == 2) {
                    int palette = r.readInt(4);
                    d.spotArgb = palette == 15 ? r.readSigned(32)
                            : HbcEncoder.SPOT_COLORS[Math.min(palette,
                                    HbcEncoder.SPOT_COLORS.length - 1)];
                } else if (d.iconKind == 3) {
                    byte[] u = new byte[16];
                    for (int i = 0; i < 16; i++) u[i] = (byte) r.readInt(8);
                    long msb = 0, lsb = 0;
                    for (int i = 0; i < 8; i++) {
                        msb = (msb << 8) | (u[i] & 0xFFL);
                        lsb = (lsb << 8) | (u[8 + i] & 0xFFL);
                    }
                    d.iconsetUuid = new UUID(msb, lsb).toString();
                    d.iconSubPath = Ita2.decode(r);
                }
                d.hasTint = r.readInt(1) == 1;
                if (d.hasTint) d.tintArgb = r.readSigned(32);
                return d;
            }
            default:
                throw new IllegalArgumentException("No decoder registered for HBC mode " + mode);
        }
    }

    private static String readName(BitReader r) {
        int length = r.readInt(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append((char) r.readInt(8));
        return sb.toString();
    }
}
