package com.atakmap.android.hbc;

/**
 * Text armor for carrying binary HBC/mesh frames over BPSK31.
 *
 * PSK31's Varicode alphabet is printable-ASCII text with NO error
 * detection — a single flipped bit silently becomes a different
 * character. Binary frames therefore ride as:
 *
 *   '~' + Base64( frame bytes + CRC-16/X.25 over the frame ) + '~'
 *
 * The '~' sentinel is a printable ASCII character that never occurs in
 * the Base64 alphabet, so frame boundaries survive arbitrary character
 * corruption between frames; the CRC rejects corruption inside them.
 * Base64 is implemented locally (RFC 4648 alphabet, no padding) so the
 * class stays pure Java and JVM-testable like RingMac/MeshRouter.
 *
 * Airtime: Varicode is variable length. Over the Base64 alphabet plus
 * sentinels the measured average is ~9.8 code bits per character; each
 * character is followed by the mandatory "00" separator, giving ~11.8
 * bits/char at 31.25 baud (~2.65 chars/s). estimateAirtimeMs() uses the
 * exact per-character code lengths, not the average.
 */
public final class Psk31Armor {

    public static final char SENTINEL = '~';

    /** TX preamble the Psk31 encoder itself prepends (64 idle bits). */
    public static final int PREAMBLE_MS = 2048;
    /** TX postamble (16 carrier bits) plus amplitude ramps. */
    public static final int POSTAMBLE_MS = 532;

    private static final String B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Psk31Armor() {}

    // ------------------------------------------------------------------
    // CRC-16/X.25 (poly 0x8408 reflected, init 0xFFFF, final XOR 0xFFFF)
    // — same family the AX.25 and Mercury paths already rely on.
    // ------------------------------------------------------------------
    static int crc16(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++)
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x8408 : crc >>> 1;
        }
        return (~crc) & 0xFFFF;
    }

    // ------------------------------------------------------------------
    // Base64 (unpadded)
    // ------------------------------------------------------------------
    static String b64encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 2 < data.length) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8)
                    | (data[i + 2] & 0xFF);
            sb.append(B64.charAt((v >> 18) & 63)).append(B64.charAt((v >> 12) & 63))
              .append(B64.charAt((v >> 6) & 63)).append(B64.charAt(v & 63));
            i += 3;
        }
        int rem = data.length - i;
        if (rem == 1) {
            int v = (data[i] & 0xFF) << 16;
            sb.append(B64.charAt((v >> 18) & 63)).append(B64.charAt((v >> 12) & 63));
        } else if (rem == 2) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(B64.charAt((v >> 18) & 63)).append(B64.charAt((v >> 12) & 63))
              .append(B64.charAt((v >> 6) & 63));
        }
        return sb.toString();
    }

    static byte[] b64decode(String s) {
        int outLen = s.length() * 3 / 4;
        byte[] out = new byte[outLen];
        int acc = 0, bits = 0, o = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = B64.indexOf(s.charAt(i));
            if (v < 0) return null;             // corrupt character
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (o < outLen) out[o++] = (byte) ((acc >> bits) & 0xFF);
            }
        }
        return o == outLen ? out : null;
    }

    // ------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------
    /** Binary frame -> armored ASCII text ready for Psk31.transmit(). */
    public static String frame(byte[] payload) {
        byte[] withCrc = new byte[payload.length + 2];
        System.arraycopy(payload, 0, withCrc, 0, payload.length);
        int crc = crc16(payload, payload.length);
        withCrc[payload.length] = (byte) (crc & 0xFF);
        withCrc[payload.length + 1] = (byte) ((crc >> 8) & 0xFF);
        return SENTINEL + b64encode(withCrc) + SENTINEL;
    }

    /**
     * Streaming de-framer: feed decoded characters one at a time; a
     * CRC-valid payload is returned exactly once when its closing
     * sentinel arrives, otherwise null.
     */
    public static final class Deframer {
        private final StringBuilder buf = new StringBuilder();
        private boolean inFrame = false;

        /** Max armored length we will buffer (~ 255-byte frame). */
        private static final int MAX_CHARS = 360;

        public byte[] accept(char c) {
            if (c == SENTINEL) {
                if (!inFrame) {           // opening sentinel
                    inFrame = true;
                    buf.setLength(0);
                    return null;
                }
                // closing sentinel: try to decode what we collected
                String body = buf.toString();
                buf.setLength(0);
                // an empty body means back-to-back sentinels — treat the
                // second one as a fresh opener
                if (body.isEmpty()) return null;
                inFrame = false;
                byte[] withCrc = b64decode(body);
                if (withCrc == null || withCrc.length < 3) return null;
                int n = withCrc.length - 2;
                int want = ((withCrc[n] & 0xFF)) | ((withCrc[n + 1] & 0xFF) << 8);
                if (crc16(withCrc, n) != want) return null;
                byte[] payload = new byte[n];
                System.arraycopy(withCrc, 0, payload, 0, n);
                return payload;
            }
            if (inFrame) {
                buf.append(c);
                if (buf.length() > MAX_CHARS) {   // runaway: resync
                    buf.setLength(0);
                    inFrame = false;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Airtime estimation (feeds the Ring MAC turn budget / CSMA waits)
    // ------------------------------------------------------------------
    /** Varicode bit lengths for the armored alphabet (code + "00"). */
    private static int varicodeBits(char c) {
        // exact code lengths from the ITU-R M.2034 table for the armor
        // alphabet; everything else estimated at 10+2 bits
        switch (c) {
            case '~': return 11 + 2;
            case '+': return 9 + 2;
            case '/': return 9 + 2;
            default:
                if (c >= 'a' && c <= 'z') return 7 + 2;   // avg lowercase
                if (c >= 'A' && c <= 'Z') return 9 + 2;   // avg uppercase
                if (c >= '0' && c <= '9') return 10 + 2;  // digits
                return 10 + 2;
        }
    }

    /** Estimated on-air milliseconds for one armored frame of n payload bytes. */
    public static int estimateAirtimeMs(int payloadBytes) {
        int chars = 2 + ((payloadBytes + 2) * 4 + 2) / 3;  // sentinels + b64(payload+crc)
        // average the alphabet mix: b64 output is roughly uniform across
        // upper/lower/digits -> weighted mean ~11.4 bits per char
        double bits = 0;
        bits += varicodeBits('~') * 2;
        bits += (chars - 2) * 11.4;
        int dataMs = (int) Math.ceil(bits * 1000.0 / 31.25);
        return PREAMBLE_MS + dataMs + POSTAMBLE_MS;
    }
}
