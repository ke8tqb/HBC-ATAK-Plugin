package com.atakmap.android.hbc;

import java.util.HashMap;
import java.util.Map;

/**
 * ITA2 (Baudot) 5-bit encoding used by the HBC header callsign and Mode 3 chat.
 * Matches HBC Protocol v1.2 Table 1 (hbc_encoder.py / hbc_decoder.py).
 */
public final class Ita2 {

    public static final String CR   = "01000"; // field terminator
    public static final String FIGS = "11011"; // figures shift
    public static final String LTRS = "11111"; // letters shift

    private static final Map<Character, String> LETTERS = new HashMap<>();
    private static final Map<Character, String> FIGURES = new HashMap<>();
    private static final Map<String, Character> LETTERS_DEC = new HashMap<>();
    private static final Map<String, Character> FIGURES_DEC = new HashMap<>();

    static {
        String[][] letters = {
                {"E","00001"},{"A","00011"},{" ","00100"},{"S","00101"},
                {"I","00110"},{"U","00111"},
                {"D","01001"},{"R","01010"},{"J","01011"},{"N","01100"},
                {"F","01101"},{"C","01110"},{"K","01111"},{"T","10000"},
                {"Z","10001"},{"L","10010"},{"W","10011"},{"H","10100"},
                {"Y","10101"},{"P","10110"},{"Q","10111"},{"O","11000"},
                {"B","11001"},{"G","11010"},{"M","11100"},{"X","11101"},
                {"V","11110"},
        };
        String[][] figures = {
                {"3","00001"},{"-","00011"},{"'","00101"},{"8","00110"},
                {"7","00111"},{"$","01001"},{"4","01010"},{",","01100"},
                {"!","01101"},{":","01110"},{"(","01111"},{"5","10000"},
                {"\"","10001"},{")","10010"},{"2","10011"},{"#","10100"},
                {"6","10101"},{"0","10110"},{"1","10111"},{"9","11000"},
                {"?","11001"},{"&","11010"},{".","11100"},{"/","11101"},
                {";","11110"},
        };
        for (String[] e : letters) {
            LETTERS.put(e[0].charAt(0), e[1]);
            LETTERS_DEC.put(e[1], e[0].charAt(0));
        }
        for (String[] e : figures) {
            FIGURES.put(e[0].charAt(0), e[1]);
            FIGURES_DEC.put(e[1], e[0].charAt(0));
        }
    }

    private Ita2() {}

    /** True if every character of s (uppercased) has an ITA2 code. */
    public static boolean isEncodable(String s) {
        for (char c : s.toUpperCase().toCharArray())
            if (!LETTERS.containsKey(c) && !FIGURES.containsKey(c))
                return false;
        return true;
    }

    /**
     * Encode the header callsign: strict — throws on non-ITA2 characters.
     * 5 bits/char with FIGS/LTRS shifts, CR-terminated.
     */
    public static String encodeCallsign(String callsign, int maxChars) {
        String cs = callsign.toUpperCase();
        if (cs.length() > maxChars) cs = cs.substring(0, maxChars);
        StringBuilder bits = new StringBuilder();
        boolean inFigures = false;
        for (char c : cs.toCharArray()) {
            if (LETTERS.containsKey(c)) {
                if (inFigures) { bits.append(LTRS); inFigures = false; }
                bits.append(LETTERS.get(c));
            } else if (FIGURES.containsKey(c)) {
                if (!inFigures) { bits.append(FIGS); inFigures = true; }
                bits.append(FIGURES.get(c));
            } else {
                throw new IllegalArgumentException(
                        "Character '" + c + "' is not ITA2-encodable");
            }
        }
        if (inFigures) bits.append(LTRS);
        bits.append(CR);
        return bits.toString();
    }

    /**
     * Encode free text (Mode 3 chat): lenient — uppercases, replaces
     * unencodable characters with '?'. CR-terminated.
     */
    public static String encodeText(String text) {
        StringBuilder bits = new StringBuilder();
        boolean inFigures = false;
        for (char c0 : text.toUpperCase().toCharArray()) {
            char c = c0;
            if (!LETTERS.containsKey(c) && !FIGURES.containsKey(c)) c = '?';
            if (LETTERS.containsKey(c)) {
                if (inFigures) { bits.append(LTRS); inFigures = false; }
                bits.append(LETTERS.get(c));
            } else {
                if (!inFigures) { bits.append(FIGS); inFigures = true; }
                bits.append(FIGURES.get(c));
            }
        }
        if (inFigures) bits.append(LTRS);
        bits.append(CR);
        return bits.toString();
    }

    /** Decode ITA2 codes from the reader until the CR terminator. */
    public static String decode(BitReader reader) {
        StringBuilder out = new StringBuilder();
        boolean inFigures = false;
        while (true) {
            String code = reader.read(5);
            if (code.equals(CR)) break;
            if (code.equals(FIGS)) { inFigures = true;  continue; }
            if (code.equals(LTRS)) { inFigures = false; continue; }
            Character c = inFigures ? FIGURES_DEC.get(code) : LETTERS_DEC.get(code);
            out.append(c == null ? '?' : c);
        }
        return out.toString();
    }
}
