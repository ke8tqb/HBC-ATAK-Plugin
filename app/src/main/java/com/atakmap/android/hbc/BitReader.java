package com.atakmap.android.hbc;

/** Sequential bit extraction from a string of '0'/'1' characters. */
public final class BitReader {

    private final String bits;
    private int pos = 0;

    public BitReader(String bits) {
        this.bits = bits;
    }

    public static BitReader fromBytes(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 8);
        for (byte b : data) {
            for (int i = 7; i >= 0; i--)
                sb.append(((b >> i) & 1) == 1 ? '1' : '0');
        }
        return new BitReader(sb.toString());
    }

    public String read(int n) {
        if (pos + n > bits.length())
            throw new IllegalArgumentException(
                    "Bit stream exhausted at position " + pos + ": need " + n
                            + " bits, only " + (bits.length() - pos) + " remain");
        String chunk = bits.substring(pos, pos + n);
        pos += n;
        return chunk;
    }

    public int readInt(int n) {
        return (int) Long.parseLong(read(n), 2);
    }

    public int readSigned(int n) {
        long val = Long.parseLong(read(n), 2);
        if (val >= (1L << (n - 1))) val -= (1L << n);
        return (int) val;
    }

    public int remaining() {
        return bits.length() - pos;
    }
}
