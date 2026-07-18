package com.atakmap.android.hbc.hbc;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * HBC Decoder — Java port of hbc_decoder.py
 *
 * Converts HBC binary bytes (received from OFDMModem.decodeFromAudio())
 * back into a CoT XML string suitable for injection into ATAK.
 */
public class HBCDecoder {

    private static final String TAG = "HBCDecoder";
    private static final String HBC_UID_PREFIX = "HBC";

    /** Decode HBC bytes into a CoT XML string. Returns null on failure. */
    public static String decode(byte[] hbcBytes) {
        if (hbcBytes == null || hbcBytes.length == 0) return null;
        try {
            BitReader br = new BitReader(hbcBytes);
            String callsign = readCallsign(br);
            int version = br.readInt(3) + 1;
            int mode    = br.readInt(3) + 1;

            if (mode == 1) return decodeMode1(br, callsign, version);
            if (mode == 2) return decodeMode2(br, callsign, version);

            Log.e(TAG, "Unsupported HBC mode: " + mode);
        } catch (Exception e) {
            Log.e(TAG, "decode() failed: " + e.getMessage());
        }
        return null;
    }

    // ─── Mode 1: PLI / Spot ──────────────────────────────────────────────────

    private static String decodeMode1(BitReader br, String callsign, int version) throws Exception {
        boolean isSpot = br.readInt(1) == 1;
        String  name   = readName(br);
        double  lat    = br.readSigned(21) / 10000.0;
        double  lon    = br.readSigned(22) / 10000.0;

        if (name.isEmpty()) name = callsign;
        String uid = isSpot
            ? UUID.randomUUID().toString()
            : HBC_UID_PREFIX + "-" + callsign.toUpperCase();

        String cotType = isSpot ? "a-u-G" : "a-f-G";
        String how     = isSpot ? "h-g-i-g-o" : "m-g";
        String now     = nowTs();
        String stale   = isSpot ? staleTs(525600) : staleTs(5); // 1yr vs 5min

        StringBuilder sb = new StringBuilder();
        sb.append("<event version=\"2.0\" uid=\"").append(uid).append("\"")
          .append(" type=\"").append(cotType).append("\"")
          .append(" time=\"").append(now).append("\"")
          .append(" start=\"").append(now).append("\"")
          .append(" stale=\"").append(stale).append("\"")
          .append(" how=\"").append(how).append("\"")
          .append(" access=\"Undefined\">\n");
        sb.append("  <point lat=\"").append(String.format(Locale.US, "%.6f", lat)).append("\"")
          .append(" lon=\"").append(String.format(Locale.US, "%.6f", lon)).append("\"")
          .append(" hae=\"9999999\" ce=\"9999999\" le=\"9999999\" />\n");
        sb.append("  <detail>\n");
        sb.append("    <contact callsign=\"").append(name).append("\" />\n");
        if (!isSpot) {
            sb.append("    <uid Droid=\"").append(name).append("\" />\n");
            sb.append("    <track speed=\"0.0\" course=\"9999999.0\" />\n");
        } else {
            sb.append("    <creator callsign=\"").append(callsign).append("\" />\n");
            sb.append("    <archive />\n");
        }
        sb.append("  </detail>\n</event>");
        return sb.toString();
    }

    // ─── Mode 2: Alert ───────────────────────────────────────────────────────

    private static String decodeMode2(BitReader br, String callsign, int version) throws Exception {
        String alertName = readName(br);
        String origName  = readName(br);
        double lat       = br.readSigned(21) / 10000.0;
        double lon       = br.readSigned(22) / 10000.0;

        String uid   = HBC_UID_PREFIX + "-" + callsign.toUpperCase() + "-911";
        String now   = nowTs();
        String stale = staleTs(5);

        StringBuilder sb = new StringBuilder();
        sb.append("<event version=\"2.0\" uid=\"").append(uid).append("\"")
          .append(" type=\"b-a-o-tbl\"")
          .append(" time=\"").append(now).append("\"")
          .append(" start=\"").append(now).append("\"")
          .append(" stale=\"").append(stale).append("\"")
          .append(" how=\"m-g\"")
          .append(" access=\"Undefined\">\n");
        sb.append("  <point lat=\"").append(String.format(Locale.US, "%.6f", lat)).append("\"")
          .append(" lon=\"").append(String.format(Locale.US, "%.6f", lon)).append("\"")
          .append(" hae=\"9999999\" ce=\"9999999\" le=\"9999999\" />\n");
        sb.append("  <detail>\n");
        sb.append("    <emergency type=\"911 Alert\" />\n");
        sb.append("    <contact callsign=\"").append(alertName).append("\" />\n");
        sb.append("    <creator callsign=\"").append(origName).append("\" />\n");
        sb.append("  </detail>\n</event>");
        return sb.toString();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String readCallsign(BitReader br) {
        StringBuilder result = new StringBuilder();
        boolean inFigures = false;
        while (true) {
            int code = br.readInt(5);
            if (code == ITA2.CR_TERM) break;
            if (code == ITA2.FIGS) { inFigures = true;  continue; }
            if (code == ITA2.LTRS) { inFigures = false; continue; }
            Character ch = inFigures
                ? ITA2.FIGURES_DECODE.get(code)
                : ITA2.LETTERS_DECODE.get(code);
            if (ch != null) result.append(ch);
        }
        return result.toString();
    }

    private static String readName(BitReader br) {
        int length = br.readInt(3);
        if (length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++)
            sb.append((char) br.readInt(8));
        return sb.toString();
    }

    private static final SimpleDateFormat TS_FMT;
    static {
        TS_FMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        TS_FMT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private static String nowTs() {
        return TS_FMT.format(new Date());
    }

    private static String staleTs(int plusMinutes) {
        return TS_FMT.format(new Date(System.currentTimeMillis() + (long) plusMinutes * 60 * 1000));
    }

    // ─── BitReader ───────────────────────────────────────────────────────────

    /** Reads bits sequentially from a byte array (MSB first per byte). */
    static class BitReader {
        private final byte[] data;
        private int bitPos = 0;

        BitReader(byte[] data) { this.data = data; }

        int readInt(int count) {
            int result = 0;
            for (int i = 0; i < count; i++) {
                int byteIdx = bitPos / 8;
                int bitIdx  = 7 - (bitPos % 8);
                result = (result << 1) | ((byteIdx < data.length)
                    ? ((data[byteIdx] >> bitIdx) & 1) : 0);
                bitPos++;
            }
            return result;
        }

        int readSigned(int count) {
            int val = readInt(count);
            if (val >= (1 << (count - 1))) val -= (1 << count);
            return val;
        }
    }
}
