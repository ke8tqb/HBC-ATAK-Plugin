package com.atakmap.android.hbc.hbc;

import java.util.HashMap;
import java.util.Map;

/**
 * ITA2 (International Telegraph Alphabet No. 2) encoding and decoding tables.
 * Used by the HBC protocol to encode the transmitter callsign into the packet header.
 *
 * Characters are encoded as 5-bit codes. Digits require a FIGS shift; letters use
 * the default LTRS mode. A CR code (01000) terminates the callsign field.
 */
public final class ITA2 {

    // 5-bit code values for control characters
    public static final int CR_TERM = 0b01000; // Carriage Return — callsign terminator
    public static final int FIGS    = 0b11011; // Figures Shift
    public static final int LTRS    = 0b11111; // Letters Shift

    // Letters mode: character → 5-bit code
    public static final Map<Character, Integer> LETTERS = new HashMap<>();
    // Figures mode: character → 5-bit code
    public static final Map<Character, Integer> FIGURES = new HashMap<>();

    // Reverse decode tables
    public static final Map<Integer, Character> LETTERS_DECODE = new HashMap<>();
    public static final Map<Integer, Character> FIGURES_DECODE = new HashMap<>();

    static {
        // ── Letters ──────────────────────────────────────────────────────────
        LETTERS.put('E', 0b00001); LETTERS.put('A', 0b00011);
        LETTERS.put(' ', 0b00100); LETTERS.put('S', 0b00101);
        LETTERS.put('I', 0b00110); LETTERS.put('U', 0b00111);
        LETTERS.put('D', 0b01001); LETTERS.put('R', 0b01010);
        LETTERS.put('J', 0b01011); LETTERS.put('N', 0b01100);
        LETTERS.put('F', 0b01101); LETTERS.put('C', 0b01110);
        LETTERS.put('K', 0b01111); LETTERS.put('T', 0b10000);
        LETTERS.put('Z', 0b10001); LETTERS.put('L', 0b10010);
        LETTERS.put('W', 0b10011); LETTERS.put('H', 0b10100);
        LETTERS.put('Y', 0b10101); LETTERS.put('P', 0b10110);
        LETTERS.put('Q', 0b10111); LETTERS.put('O', 0b11000);
        LETTERS.put('B', 0b11001); LETTERS.put('G', 0b11010);
        LETTERS.put('M', 0b11100); LETTERS.put('X', 0b11101);
        LETTERS.put('V', 0b11110);

        // ── Figures ───────────────────────────────────────────────────────────
        FIGURES.put('3', 0b00001); FIGURES.put('-', 0b00011);
        FIGURES.put('\'', 0b00101); FIGURES.put('8', 0b00110);
        FIGURES.put('7', 0b00111); FIGURES.put('$', 0b01001);
        FIGURES.put('4', 0b01010); FIGURES.put(',', 0b01100);
        FIGURES.put('!', 0b01101); FIGURES.put(':', 0b01110);
        FIGURES.put('(', 0b01111); FIGURES.put('5', 0b10000);
        FIGURES.put('"', 0b10001); FIGURES.put(')', 0b10010);
        FIGURES.put('2', 0b10011); FIGURES.put('#', 0b10100);
        FIGURES.put('6', 0b10101); FIGURES.put('0', 0b10110);
        FIGURES.put('1', 0b10111); FIGURES.put('9', 0b11000);
        FIGURES.put('?', 0b11001); FIGURES.put('&', 0b11010);
        FIGURES.put('.', 0b11100); FIGURES.put('/', 0b11101);
        FIGURES.put(';', 0b11110);

        // ── Build reverse (decode) maps ───────────────────────────────────────
        for (Map.Entry<Character, Integer> e : LETTERS.entrySet())
            LETTERS_DECODE.put(e.getValue(), e.getKey());
        for (Map.Entry<Character, Integer> e : FIGURES.entrySet())
            FIGURES_DECODE.put(e.getValue(), e.getKey());
    }

    private ITA2() {}
}
