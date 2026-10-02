package com.atakmap.android.hbc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * HBC Protocol v1.5 encoder — CoT XML string to packed HBC bytes.
 * Direct port of hbc_encoder.py (Modes 1-6).
 *
 * Wire layout:
 *   Header: callsign (ITA2, CR-terminated) + version (3b, 000=v1) + mode (3b)
 *   Payload: mode-dependent (see README / hbc_encoder.py).
 *
 * v1.4 added a 2-bit destination-kind field to Mode 3 (All Chat Rooms /
 * Named Room / Direct Message). v1.5 added a 2-bit Affiliation field to
 * Mode 1 (Friendly/Hostile/Neutral/Unknown), fixing a bug where PLI always
 * decoded as Friendly and Spot always decoded as Unknown regardless of the
 * actual CoT type.
 */
public final class HbcEncoder {

    public static final int MAX_NAME_CHARS   = 7;
    public static final int MAX_CS_CHARS     = 8;
    public static final int MAX_SHAPE_POINTS = 15;

    private static final String[] PLI_PREFIXES = {"a-f-G", "a-h-G", "a-n-G"};

    /** Mode 1 (v1.5) 2-bit Affiliation code order: 0 Friendly, 1 Hostile,
     *  2 Neutral, 3 Unknown (also the default for non-atom types). */
    private static final String[] AFFILIATION_PREFIXES = {"a-f-G", "a-h-G", "a-n-G", "a-u-G"};

    private static int detectAffiliation(String cotType) {
        for (int i = 0; i < AFFILIATION_PREFIXES.length; i++)
            if (cotType.startsWith(AFFILIATION_PREFIXES[i])) return i;
        return 3;
    }

    /** Mode 3 (v1.4) destination kinds. */
    private static final int CHAT_DEST_ALL  = 0;
    private static final int CHAT_DEST_ROOM = 1;
    private static final int CHAT_DEST_DM   = 2;

    /** Mode 0 (v1.6) ack kinds. */
    public static final int ACK_DELIVERED = 0;   // b-t-f-d
    public static final int ACK_READ      = 1;   // b-t-f-r

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) — the DM message tag. */
    public static int crc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++)
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF
                                            : (crc << 1) & 0xFFFF;
        }
        return crc;
    }

    /** Mode 6 type-token charset: index into 0-9 (0-9), A-Z (10-35), a-z (36-61). */
    static final String TOKEN_CHARSET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** Mode 6 spot-map standard color palette (index 0-14; 15 = raw ARGB follows). */
    static final int[] SPOT_COLORS = {
            0xFFFFFFFF, // 0 white   (-1)
            0xFFFFFF00, // 1 yellow  (-256)
            0xFFFF0000, // 2 red     (-65536)
            0xFF00FF00, // 3 green   (-16711936)
            0xFF0000FF, // 4 blue    (-16776961)
            0xFFFFA500, // 5 orange
            0xFFFF00FF, // 6 magenta (-65281)
            0xFF00FFFF, // 7 cyan    (-16711681)
            0xFF000000, // 8 black   (-16777216)
            0xFF808080, // 9 gray
            0xFFA52A2A, // 10 brown
            0xFF800080, // 11 purple
    };

    private HbcEncoder() {}

    /** Result: the packed payload plus diagnostic info. */
    public static final class Encoded {
        public final byte[] bytes;
        public final int mode;
        public final String callsign;
        public final String cotType;
        /** Mode 3 DM only (v1.6): the 16-bit tag transmitted with the DM,
         *  and the ATAK messageId it hashes — the sender plugin maps
         *  tag -> messageId so incoming Mode 0 acks can be resolved. */
        public int chatMsgTag = -1;
        public String chatMessageId = "";
        /** Mode 3 (v1.4+): destination kind (0 all / 1 room / 2 DM) and the
         *  DM recipient callsign — used by the mesh layer to route DMs. */
        public int chatDestKind = -1;
        public String chatRecipient = "";

        Encoded(byte[] bytes, int mode, String callsign, String cotType) {
            this.bytes = bytes;
            this.mode = mode;
            this.callsign = callsign;
            this.cotType = cotType;
        }
    }

    /** Thrown when the event type has no HBC representation problem or fields are invalid. */
    public static final class HbcEncodeException extends Exception {
        public HbcEncodeException(String msg) { super(msg); }
        public HbcEncodeException(String msg, Throwable t) { super(msg, t); }
    }

    /** Encode a CoT XML event string into HBC bytes. */
    public static Encoded encode(String cotXml) throws HbcEncodeException {
        return encode(cotXml, false);
    }

    /**
     * Encode a CoT XML event string into HBC bytes.
     *
     * @param preferSpot treat an atom-type event (a-f-G / a-h-G / a-n-G
     *        included) as a PLACED MARKER rather than a self position
     *        report: try the Mode 6 extended-marker encoding first and,
     *        on fallback, set the Mode 1 spot flag. The caller decides
     *        (by UID) whether an event is the station's own PLI — the
     *        CoT type alone cannot distinguish a hostile ground marker
     *        from a hostile station's position report.
     */
    public static Encoded encode(String cotXml, boolean preferSpot) throws HbcEncodeException {
        if (cotXml == null)
            throw new HbcEncodeException("CoT XML is null");
        // CoT events never carry a DTD. Rejecting DOCTYPE outright blocks
        // XXE and entity-expansion payloads on every parser implementation,
        // including Android's, which does not implement the Xerces feature
        // URIs set below.
        if (cotXml.contains("<!DOCTYPE"))
            throw new HbcEncodeException("DOCTYPE is not allowed in CoT XML");
        Element root;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setExpandEntityReferences(false);
            // XXE / XML-bomb hardening: forbid DTDs and external entity
            // resolution. The JDK's Xerces honors these features (used by
            // the codec-test JVM harness); Android's factory throws on the
            // unsupported URIs and is covered by the DOCTYPE reject above.
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                dbf.setXIncludeAware(false);
            } catch (Exception ignored) {
                // Factory does not support the Xerces feature URIs (Android).
            }
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(cotXml.getBytes(StandardCharsets.UTF_8)));
            root = doc.getDocumentElement();
        } catch (Exception e) {
            throw new HbcEncodeException("CoT XML parse failed: " + e.getMessage(), e);
        }

        String cotType = attr(root, "type");
        Element point  = child(root, "point");
        if (point == null)
            throw new HbcEncodeException("CoT XML has no <point> element");
        double lat = dbl(attr(point, "lat"));
        double lon = dbl(attr(point, "lon"));
        Element detail = child(root, "detail");

        int mode = detectMode(cotType);
        if (mode == 1 && (preferSpot || isSpot(cotType))) {
            // Prefer Mode 6 (extended marker) for placed markers so the
            // symbol/icon survives; fall back to Mode 1 when unencodable.
            try {
                return mode6(cotType, detail, lat, lon);
            } catch (HbcEncodeException ignored) {
                // fall through to Mode 1 spot
            }
        }
        switch (mode) {
            case 1:  return mode1(cotType, detail, lat, lon, preferSpot);
            case 2:  return mode2(cotType, detail, lat, lon);
            case 3:  return mode3(cotType, detail);
            case 4:  return mode4(cotType, detail, lat, lon);
            case 5:  return mode5(cotType, detail, lat, lon);
            default: throw new HbcEncodeException("No builder for HBC mode " + mode);
        }
    }

    private static int detectMode(String cotType) {
        switch (cotType) {
            case "b-a-o-tbl":
            case "b-a-o-can": return 2;
            case "b-t-f":     return 3;
            case "u-d-c-c":
            case "u-d-r":
            case "u-d-f":     return 4;
            case "b-r-f-h-c": return 5;
            default:          return 1;
        }
    }

    private static boolean isSpot(String cotType) {
        for (String p : PLI_PREFIXES)
            if (cotType.startsWith(p)) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Header helper
    // ------------------------------------------------------------------
    private static BitWriter header(String callsign, int mode) throws HbcEncodeException {
        if (callsign == null || callsign.isEmpty())
            throw new HbcEncodeException("No callsign available for HBC header");
        BitWriter w = new BitWriter();
        try {
            w.raw(Ita2.encodeCallsign(callsign, MAX_CS_CHARS));
        } catch (IllegalArgumentException e) {
            throw new HbcEncodeException(e.getMessage());
        }
        w.bits(0, 3);          // version 1 -> 000
        // Mode 0 (Ack, v1.6) rides the previously reserved bit pattern 111;
        // all other modes encode as (mode number - 1).
        w.bits(mode == 0 ? 7 : mode - 1, 3);
        return w;
    }

    private static void coords(BitWriter w, double lat, double lon) throws HbcEncodeException {
        long latInt = Math.round(lat * 10000.0);
        long lonInt = Math.round(lon * 10000.0);
        if (latInt < -900000 || latInt > 900000)
            throw new HbcEncodeException("Latitude " + lat + " out of range");
        if (lonInt < -1800000 || lonInt > 1800000)
            throw new HbcEncodeException("Longitude " + lon + " out of range");
        w.bits((int) latInt, 21);
        w.bits((int) lonInt, 22);
    }

    // ------------------------------------------------------------------
    // Mode 1 — PLI / Spot
    // ------------------------------------------------------------------
    private static Encoded mode1(String cotType, Element detail, double lat, double lon,
                                 boolean forceSpot)
            throws HbcEncodeException {
        String callsign = "", name = "";
        if (detail != null) {
            Element uidEl = child(detail, "uid");
            if (uidEl != null) callsign = attr(uidEl, "Droid");
            Element contact = child(detail, "contact");
            if (contact != null) {
                String cs = attr(contact, "callsign");
                name = cs;
                if (callsign.isEmpty()) callsign = cs;
            }
            Element creator = child(detail, "creator");
            if (creator != null && !attr(creator, "callsign").isEmpty())
                callsign = attr(creator, "callsign");
        }
        if (name.isEmpty()) name = callsign;

        boolean spot = forceSpot || isSpot(cotType);
        int affiliation = detectAffiliation(cotType);
        BitWriter w = header(callsign, 1);
        w.bits(spot ? 1 : 0, 1);
        w.bits(affiliation, 2);
        w.name(name, MAX_NAME_CHARS);
        coords(w, lat, lon);
        return new Encoded(w.toBytes(), 1, callsign, cotType);
    }

    // ------------------------------------------------------------------
    // Mode 2 — Alert (active or cancelled)
    // ------------------------------------------------------------------
    private static Encoded mode2(String cotType, Element detail, double lat, double lon)
            throws HbcEncodeException {
        boolean active = !cotType.equals("b-a-o-can");
        String alertCallsign = "", originator = "";
        if (detail != null) {
            Element emergency = child(detail, "emergency");
            if (!active) {
                if (emergency != null && emergency.getTextContent() != null)
                    originator = emergency.getTextContent().trim();
            } else {
                Element contact = child(detail, "contact");
                if (contact != null) alertCallsign = attr(contact, "callsign");
                originator = alertCallsign.contains("-")
                        ? alertCallsign.substring(0, alertCallsign.indexOf('-'))
                        : alertCallsign;
            }
        }

        BitWriter w = header(originator, 2);
        w.bits(active ? 1 : 0, 1);
        w.name(active ? alertCallsign : "", MAX_NAME_CHARS);
        w.name(originator, MAX_NAME_CHARS);
        coords(w, lat, lon);
        return new Encoded(w.toBytes(), 2, originator, cotType);
    }

    // ------------------------------------------------------------------
    // Mode 3 — GeoChat
    // ------------------------------------------------------------------
    private static Encoded mode3(String cotType, Element detail) throws HbcEncodeException {
        String sender = "", message = "", chatroom = "", messageId = "";
        int destKind = CHAT_DEST_ALL;
        String chatRoom = "", chatRecipient = "";
        if (detail != null) {
            Element chat = child(detail, "__chat");
            if (chat != null) {
                sender = attr(chat, "senderCallsign");
                chatroom = attr(chat, "chatroom");
                if (chatroom.isEmpty()) chatroom = attr(chat, "id");
                messageId = attr(chat, "messageId");
                Element chatgrp = child(chat, "chatgrp");
                int memberCount = 0;
                if (chatgrp != null) {
                    org.w3c.dom.NamedNodeMap attrs = chatgrp.getAttributes();
                    for (int i = 0; i < attrs.getLength(); i++)
                        if (attrs.item(i).getNodeName().matches("uid\\d+"))
                            memberCount++;
                }
                if (chatroom.isEmpty() || chatroom.trim().equalsIgnoreCase("all chat rooms")) {
                    destKind = CHAT_DEST_ALL;
                } else if (memberCount >= 3) {
                    destKind = CHAT_DEST_ROOM;
                    chatRoom = chatroom;
                } else {
                    destKind = CHAT_DEST_DM;
                    chatRecipient = chatroom;
                }
            }
            Element remarks = child(detail, "remarks");
            if (remarks != null && remarks.getTextContent() != null)
                message = remarks.getTextContent().trim();
        }
        BitWriter w = header(sender, 3);
        w.bits(destKind, 2);
        int msgTag = -1;
        try {
            if (destKind == CHAT_DEST_ROOM) {
                w.raw(Ita2.encodeText(chatRoom));
            } else if (destKind == CHAT_DEST_DM) {
                w.raw(Ita2.encodeCallsign(chatRecipient, MAX_CS_CHARS));
                // v1.6: 16-bit message tag (CRC-16 of the ATAK messageId),
                // echoed back in Mode 0 acks for the delivered/read checkmark.
                msgTag = messageId.isEmpty() ? 0
                        : crc16(messageId.getBytes(StandardCharsets.UTF_8));
                w.bits(msgTag, 16);
            }
        } catch (IllegalArgumentException e) {
            throw new HbcEncodeException(e.getMessage());
        }
        w.raw(Ita2.encodeText(message));
        Encoded enc = new Encoded(w.toBytes(), 3, sender, cotType);
        enc.chatMsgTag = msgTag;
        enc.chatMessageId = messageId;
        enc.chatDestKind = destKind;
        enc.chatRecipient = chatRecipient;
        return enc;
    }

    /**
     * Mode 0 — Ack (v1.6, wire mode bits 111): delivery/read receipt for a
     * Direct Message.  Payload: recipient callsign (ITA2, CR-terminated,
     * the original DM sender) + ack kind (2 bits) + echoed 16-bit tag.
     */
    public static Encoded encodeAck(String callsign, String recipient,
                                    int kind, int msgTag) throws HbcEncodeException {
        if (kind != ACK_DELIVERED && kind != ACK_READ)
            throw new HbcEncodeException("Invalid ack kind " + kind);
        BitWriter w = header(callsign, 0);
        try {
            w.raw(Ita2.encodeCallsign(recipient, MAX_CS_CHARS));
        } catch (IllegalArgumentException e) {
            throw new HbcEncodeException(e.getMessage());
        }
        w.bits(kind, 2);
        w.bits(msgTag & 0xFFFF, 16);
        Encoded enc = new Encoded(w.toBytes(), 0, callsign,
                kind == ACK_READ ? "b-t-f-r" : "b-t-f-d");
        enc.chatMsgTag = msgTag & 0xFFFF;
        return enc;
    }

    // ------------------------------------------------------------------
    // Mode 4 — Shape
    // ------------------------------------------------------------------
    private static Encoded mode4(String cotType, Element detail, double lat, double lon)
            throws HbcEncodeException {
        String name = "", creatorCs = "";
        if (detail != null) {
            Element contact = child(detail, "contact");
            if (contact != null) name = attr(contact, "callsign");
            Element creator = child(detail, "creator");
            if (creator != null) creatorCs = attr(creator, "callsign");
        }
        String callsign = !creatorCs.isEmpty() ? creatorCs : name;

        if (cotType.equals("u-d-c-c")) {
            double radius = 0.0;
            Element shapeEl = detail != null ? child(detail, "shape") : null;
            if (shapeEl != null) {
                Element ellipse = child(shapeEl, "ellipse");
                if (ellipse != null) radius = dbl(attr(ellipse, "major"));
            }
            int r = (int) Math.round(radius);
            if (r < 0 || r > 65535)
                throw new HbcEncodeException("Circle radius " + radius + " m out of range (0-65535)");
            BitWriter w = header(callsign, 4);
            w.bits(0, 2);              // kind = circle
            w.name(name, MAX_NAME_CHARS);
            coords(w, lat, lon);
            w.bits(r, 16);
            return new Encoded(w.toBytes(), 4, callsign, cotType);
        }

        // polygon / polyline from <link point="lat,lon"/>
        List<double[]> pts = new ArrayList<>();
        if (detail != null) {
            NodeList links = detail.getElementsByTagName("link");
            for (int i = 0; i < links.getLength(); i++) {
                Element link = (Element) links.item(i);
                if (link.getParentNode() != detail) continue;
                String pt = attr(link, "point");
                if (pt.isEmpty()) continue;
                String[] parts = pt.split(",");
                if (parts.length >= 2) {
                    try {
                        pts.add(new double[]{
                                Double.parseDouble(parts[0].trim()),
                                Double.parseDouble(parts[1].trim())});
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (pts.size() < 2)
            throw new HbcEncodeException(cotType + " has fewer than 2 link points");

        int kind = 1; // closed polygon
        if (cotType.equals("u-d-f")) {
            if (pts.size() > 2
                    && pts.get(0)[0] == pts.get(pts.size() - 1)[0]
                    && pts.get(0)[1] == pts.get(pts.size() - 1)[1]) {
                pts.remove(pts.size() - 1); // drop duplicate closing point
            } else {
                kind = 2; // open polyline
            }
        }
        if (pts.size() < 2 || pts.size() > MAX_SHAPE_POINTS)
            throw new HbcEncodeException("Polygon needs 2-" + MAX_SHAPE_POINTS
                    + " points, got " + pts.size());

        BitWriter w = header(callsign, 4);
        w.bits(kind, 2);
        w.name(name, MAX_NAME_CHARS);
        w.bits(pts.size(), 4);
        coords(w, pts.get(0)[0], pts.get(0)[1]);
        long prevLa = Math.round(pts.get(0)[0] * 10000.0);
        long prevLo = Math.round(pts.get(0)[1] * 10000.0);
        for (int i = 1; i < pts.size(); i++) {
            long ila = Math.round(pts.get(i)[0] * 10000.0);
            long ilo = Math.round(pts.get(i)[1] * 10000.0);
            long dla = ila - prevLa, dlo = ilo - prevLo;
            if (dla < -8192 || dla > 8191 || dlo < -8192 || dlo > 8191)
                throw new HbcEncodeException("Polygon segment exceeds the ±0.8191° delta limit");
            w.bits((int) dla, 14);
            w.bits((int) dlo, 14);
            prevLa = ila;
            prevLo = ilo;
        }
        return new Encoded(w.toBytes(), 4, callsign, cotType);
    }

    // ------------------------------------------------------------------
    // Mode 5 — CASEVAC / MEDEVAC
    // ------------------------------------------------------------------
    private static Encoded mode5(String cotType, Element detail, double lat, double lon)
            throws HbcEncodeException {
        Element mv = detail != null ? child(detail, "_medevac_") : null;
        String callsign = "";
        if (detail != null) {
            Element link = child(detail, "link");
            if (link != null) callsign = attr(link, "parent_callsign");
            if (callsign.isEmpty()) {
                Element contact = child(detail, "contact");
                if (contact != null) callsign = attr(contact, "callsign");
            }
        }
        String title = mv != null ? attr(mv, "title") : "";

        double freqMhz = mv != null ? dbl(attr(mv, "freq")) : 0.0;
        int freqUnits = clamp((int) Math.round(freqMhz * 100.0), 65535);

        int urgent    = clamp(intAttr(mv, "urgent"), 15);
        int urgentSrg = clamp(intAttr(mv, "urgent_surgical"), 15);
        int priority  = clamp(intAttr(mv, "priority"), 15);
        int routine   = clamp(intAttr(mv, "routine"), 15);
        int conven    = clamp(intAttr(mv, "convenience"), 15);
        int litter    = clamp(intAttr(mv, "litter"), 15);
        int ambul     = clamp(intAttr(mv, "ambulatory"), 15);
        int flags = (boolAttr(mv, "casevac") ? 8 : 0)
                | (boolAttr(mv, "equipment_none") ? 4 : 0)
                | (boolAttr(mv, "terrain_none") ? 2 : 0);
        int security = clamp(intAttr(mv, "security"), 3);
        int hlz      = clamp(intAttr(mv, "hlz_marking"), 7);
        int zone     = clamp(intAttr(mv, "zone_prot_selection"), 3);

        BitWriter w = header(callsign, 5);
        w.name(title, MAX_NAME_CHARS);
        w.bits(freqUnits, 16);
        w.bits(urgent, 4).bits(urgentSrg, 4).bits(priority, 4)
         .bits(routine, 4).bits(conven, 4);
        w.bits(litter, 4).bits(ambul, 4);
        w.bits(flags, 4);
        w.bits(security, 2).bits(hlz, 3).bits(zone, 2);
        coords(w, lat, lon);
        return new Encoded(w.toBytes(), 5, callsign, cotType);
    }

    // ------------------------------------------------------------------
    // Mode 6 — Extended Marker (HBC v1.3)
    // name + lat/lon + CoT type tokens + icon reference + optional tint
    // ------------------------------------------------------------------
    private static Encoded mode6(String cotType, Element detail, double lat, double lon)
            throws HbcEncodeException {
        // --- type tokens: every dash-separated token must be one charset char
        String[] tokens = cotType.split("-");
        if (tokens.length < 1 || tokens.length > 15)
            throw new HbcEncodeException("Mode 6: token count " + tokens.length);
        int[] tokenIdx = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].length() != 1)
                throw new HbcEncodeException("Mode 6: multi-char token '" + tokens[i] + "'");
            int idx = TOKEN_CHARSET.indexOf(tokens[i].charAt(0));
            if (idx < 0)
                throw new HbcEncodeException("Mode 6: token char '" + tokens[i] + "'");
            tokenIdx[i] = idx;
        }

        // --- fields from detail
        String callsign = "", name = "", iconPath = "";
        Integer tintArgb = null;
        if (detail != null) {
            Element contact = detail == null ? null : child(detail, "contact");
            if (contact != null) name = attr(contact, "callsign");
            Element creator = child(detail, "creator");
            if (creator != null) callsign = attr(creator, "callsign");
            if (callsign.isEmpty()) {
                Element link = child(detail, "link");
                if (link != null) callsign = attr(link, "parent_callsign");
            }
            Element usericon = child(detail, "usericon");
            if (usericon != null) iconPath = attr(usericon, "iconsetpath");
            Element color = child(detail, "color");
            if (color != null && !attr(color, "argb").isEmpty()) {
                try {
                    tintArgb = (int) Long.parseLong(attr(color, "argb").trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        if (name.isEmpty()) name = callsign;

        // --- icon kind
        int kind;            // 0 none, 1 2525C, 2 spotmap, 3 custom
        int spotArgb = 0;
        byte[] setUuid = null;
        String subPath = null;
        if (iconPath.isEmpty()) {
            kind = 0;
        } else if (iconPath.startsWith("COT_MAPPING_2525")) {
            kind = 1; // derivable from the type string
        } else if (iconPath.startsWith("COT_MAPPING_SPOTMAP")) {
            kind = 2;
            String[] seg = iconPath.split("/");
            try {
                spotArgb = (int) Long.parseLong(seg[seg.length - 1].trim());
            } catch (NumberFormatException e) {
                spotArgb = tintArgb != null ? tintArgb : 0xFFFFFFFF;
            }
        } else {
            int slash = iconPath.indexOf('/');
            if (slash != 36)
                throw new HbcEncodeException("Mode 6: iconsetpath not UUID-prefixed");
            try {
                java.util.UUID u = java.util.UUID.fromString(iconPath.substring(0, 36));
                setUuid = new byte[16];
                long msb = u.getMostSignificantBits(), lsb = u.getLeastSignificantBits();
                for (int i = 0; i < 8; i++) {
                    setUuid[i] = (byte) (msb >>> (56 - 8 * i));
                    setUuid[8 + i] = (byte) (lsb >>> (56 - 8 * i));
                }
            } catch (IllegalArgumentException e) {
                throw new HbcEncodeException("Mode 6: bad iconset UUID");
            }
            subPath = iconPath.substring(37);
            kind = 3;
        }

        BitWriter w = header(callsign, 6);
        w.name(name, MAX_NAME_CHARS);
        coords(w, lat, lon);
        w.bits(tokens.length, 4);
        for (int idx : tokenIdx) w.bits(idx, 6);
        w.bits(kind, 2);
        if (kind == 2) {
            int palette = -1;
            for (int i = 0; i < SPOT_COLORS.length; i++)
                if (SPOT_COLORS[i] == spotArgb) { palette = i; break; }
            if (palette >= 0) {
                w.bits(palette, 4);
            } else {
                w.bits(15, 4);
                w.bits(spotArgb, 32);
            }
        } else if (kind == 3) {
            for (byte b : setUuid) w.bits(b & 0xFF, 8);
            w.raw(Ita2.encodeText(subPath));
        }
        // tint (skip for spotmap: color already carried above)
        if (kind != 2 && tintArgb != null) {
            w.bits(1, 1);
            w.bits(tintArgb, 32);
        } else {
            w.bits(0, 1);
        }
        return new Encoded(w.toBytes(), 6, callsign, cotType);
    }

    // ------------------------------------------------------------------
    // DOM helpers
    // ------------------------------------------------------------------
    private static Element child(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++)
            if (nl.item(i).getParentNode() == parent)
                return (Element) nl.item(i);
        return null;
    }

    private static String attr(Element el, String name) {
        return el == null ? "" : el.getAttribute(name);
    }

    private static double dbl(String s) {
        try {
            return s == null || s.isEmpty() ? 0.0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int intAttr(Element el, String name) {
        try {
            String s = attr(el, name);
            return s.isEmpty() ? 0 : (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean boolAttr(Element el, String name) {
        return attr(el, name).trim().equalsIgnoreCase("true");
    }

    private static int clamp(int v, int hi) {
        return Math.max(0, Math.min(hi, v));
    }
}
