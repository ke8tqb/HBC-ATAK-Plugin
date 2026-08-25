package com.atakmap.android.hbc;

/** Accumulates a bit string and packs it into bytes (right-zero-padded). */
public final class BitWriter {

    private final StringBuilder bits = new StringBuilder();

    public BitWriter raw(String bitString) {
        bits.append(bitString);
        return this;
    }

    /** Append value as an n-bit unsigned/two's-complement field. */
    public BitWriter bits(int value, int n) {
        long masked = value & ((1L << n) - 1);
        String s = Long.toBinaryString(masked);
        for (int i = s.length(); i < n; i++) bits.append('0');
        bits.append(s);
        return this;
    }

    /** Append a name/title: 3-bit length + 8-bit ASCII per char. */
    public BitWriter name(String name, int maxChars) {
        String enc = name.length() > maxChars ? name.substring(0, maxChars) : name;
        bits(enc.length(), 3);
        for (char c : enc.toCharArray()) bits(c, 8);
        return this;
    }

    public String bitString() {
        return bits.toString();
    }

    public int bitCount() {
        return bits.length();
    }

    public byte[] toBytes() {
        int nBytes = (bits.length() + 7) / 8;
        StringBuilder padded = new StringBuilder(bits);
        while (padded.length() < nBytes * 8) padded.append('0');
        byte[] out = new byte[nBytes];
        for (int i = 0; i < nBytes; i++)
            out[i] = (byte) Integer.parseInt(padded.substring(i * 8, i * 8 + 8), 2);
        return out;
    }
}
