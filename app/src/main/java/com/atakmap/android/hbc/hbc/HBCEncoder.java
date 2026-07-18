package com.atakmap.android.hbc.hbc;

import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;

/**
 * HBC Encoder — Java port of hbc_encoder.py
 *
 * Converts a CoT XML string into an HBC binary payload (byte[]).
 * The returned bytes are handed to OFDMModem.encodeHBC() for audio encoding.
 *
 * Supported modes:
 *   Mode 1 (PLI / Spot marker)
 *   Mode 2 (911 Alert)
 */
public class HBCEncoder {

    private static final String TAG = "HBCEncoder";

    // Protocol constants
    private static final int HBC_VERSION   = 1;
    private static final int MAX_NAME_CHARS = 7;

    // CoT types that map to Mode 2
    private static final String MODE2_TYPE = "b-a-o-tbl";

    // CoT type prefixes for PLI (moving unit, PLI bit = 0)
    private static final String[] PLI_PREFIXES = {"a-f-G", "a-h-G", "a-n-G"};

    /** Encode a CoT XML string to HBC bytes. Returns null on failure. */
    public static byte[] encode(String cotXml) {
        try {
            Document doc = parseXml(cotXml);
            Element root = doc.getDocumentElement();
            String cotType = root.getAttribute("type");
            int mode = detectMode(cotType);

            Element point = (Element) root.getElementsByTagName("point").item(0);
            if (point == null) {
                Log.e(TAG, "No <point> element in CoT XML");
                return null;
            }
            double lat = Double.parseDouble(point.getAttribute("lat"));
            double lon = Double.parseDouble(point.getAttribute("lon"));

            Element detail = (Element) root.getElementsByTagName("detail").item(0);

            if (mode == 1) {
                return encodeMode1(cotType, lat, lon, detail);
            } else if (mode == 2) {
                return encodeMode2(cotType, lat, lon, detail);
            }
        } catch (Exception e) {
            Log.e(TAG, "encode() failed: " + e.getMessage());
        }
        return null;
    }

    // ─── Mode 1: PLI / Spot ──────────────────────────────────────────────────

    private static byte[] encodeMode1(String cotType, double lat, double lon, Element detail) {
        String callsign = "";
        String name     = "";

        if (detail != null) {
            Element uid     = (Element) detail.getElementsByTagName("uid").item(0);
            Element contact = (Element) detail.getElementsByTagName("contact").item(0);
            Element creator = (Element) detail.getElementsByTagName("creator").item(0);

            if (uid     != null) callsign = uid.getAttribute("Droid");
            if (contact != null) {
                String cs = contact.getAttribute("callsign");
                name = cs;
                if (callsign.isEmpty()) callsign = cs;
            }
            if (creator != null) callsign = creator.getAttribute("callsign");
        }
        if (name.isEmpty()) name = callsign;

        BitWriter bw = new BitWriter();
        appendCallsign(bw, callsign);
        appendVersion(bw);
        appendMode(bw, 1);

        boolean isSpot = !isPLI(cotType);
        bw.writeBits(isSpot ? 1 : 0, 1);              // PLI/Spot bit

        appendName(bw, name);
        appendCoords(bw, lat, lon);

        return bw.toBytes();
    }

    // ─── Mode 2: Alert ───────────────────────────────────────────────────────

    private static byte[] encodeMode2(String cotType, double lat, double lon, Element detail) {
        String alertCallsign = "";
        if (detail != null) {
            Element contact = (Element) detail.getElementsByTagName("contact").item(0);
            if (contact != null) alertCallsign = contact.getAttribute("callsign");
        }
        String origName = alertCallsign.contains("-")
            ? alertCallsign.substring(0, alertCallsign.indexOf('-'))
            : alertCallsign;

        BitWriter bw = new BitWriter();
        appendCallsign(bw, origName);
        appendVersion(bw);
        appendMode(bw, 2);

        appendName(bw, alertCallsign);  // alert_name
        appendName(bw, origName);       // orig_name
        appendCoords(bw, lat, lon);

        return bw.toBytes();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static void appendVersion(BitWriter bw) {
        bw.writeBits(HBC_VERSION - 1, 3);
    }

    private static void appendMode(BitWriter bw, int mode) {
        bw.writeBits(mode - 1, 3);
    }

    private static void appendName(BitWriter bw, String name) {
        if (name.length() > MAX_NAME_CHARS) name = name.substring(0, MAX_NAME_CHARS);
        bw.writeBits(name.length(), 3);
        for (char c : name.toCharArray())
            bw.writeBits(c, 8);
    }

    private static void appendCoords(BitWriter bw, double lat, double lon) {
        int latInt = (int) Math.round(lat * 10000.0);
        int lonInt = (int) Math.round(lon * 10000.0);
        // 21-bit two's complement for latitude
        bw.writeBits(latInt & 0x1FFFFF, 21);
        // 22-bit two's complement for longitude
        bw.writeBits(lonInt & 0x3FFFFF, 22);
    }

    /** Encode callsign to ITA2 with shift handling, terminated by CR. */
    static void appendCallsign(BitWriter bw, String callsign) {
        callsign = callsign.toUpperCase();
        if (callsign.length() > 8) callsign = callsign.substring(0, 8);

        boolean inFigures = false;
        for (char ch : callsign.toCharArray()) {
            if (ITA2.LETTERS.containsKey(ch)) {
                if (inFigures) {
                    bw.writeBits(ITA2.LTRS, 5);
                    inFigures = false;
                }
                bw.writeBits(ITA2.LETTERS.get(ch), 5);
            } else if (ITA2.FIGURES.containsKey(ch)) {
                if (!inFigures) {
                    bw.writeBits(ITA2.FIGS, 5);
                    inFigures = true;
                }
                bw.writeBits(ITA2.FIGURES.get(ch), 5);
            }
            // unknown characters are silently skipped
        }
        if (inFigures) bw.writeBits(ITA2.LTRS, 5);
        bw.writeBits(ITA2.CR_TERM, 5);  // terminator
    }

    private static int detectMode(String cotType) {
        if (MODE2_TYPE.equals(cotType)) return 2;
        return 1;
    }

    private static boolean isPLI(String cotType) {
        for (String prefix : PLI_PREFIXES)
            if (cotType.startsWith(prefix)) return true;
        return false;
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    // ─── BitWriter ───────────────────────────────────────────────────────────

    /**
     * Accumulates a bit stream and packs it into bytes (MSB first, right-zero-padded).
     */
    static class BitWriter {
        private final StringBuilder bits = new StringBuilder();

        void writeBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--)
                bits.append((value >> i) & 1);
        }

        byte[] toBytes() {
            String s = bits.toString();
            // Pad to byte boundary
            while (s.length() % 8 != 0) s += "0";
            byte[] result = new byte[s.length() / 8];
            for (int i = 0; i < result.length; i++)
                result[i] = (byte) Integer.parseInt(s.substring(i * 8, i * 8 + 8), 2);
            return result;
        }

        int length() { return bits.length(); }
    }
}
