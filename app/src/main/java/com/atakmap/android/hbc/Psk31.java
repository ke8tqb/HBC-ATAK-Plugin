package com.atakmap.android.hbc;

import java.util.HashMap;
import java.util.Map;

/** BPSK31, 31.25 baud, ASCII Varicode per ITU-R M.2034 / G3PLX. */
public final class Psk31 {
    public static final int SAMPLE_RATE = 8000;
    public static final int SAMPLES_PER_SYMBOL = 256;
    // Published protocol alphabet, indexed by ASCII value (not a software library).
    private static final String[] CODES = (
        "1010101011 1011011011 1011101101 1101110111 1011101011 1101011111 1011101111 1011111101 " +
        "1011111111 11101111 11101 1101101111 1011011101 11111 1101110101 1110101011 " +
        "1011110111 1011110101 1110101101 1110101111 1101011011 1101101011 1101101101 1101010111 " +
        "1101111011 1101111101 1110110111 1101010101 1101011101 1110111011 1011111011 1101111111 " +
        "1 111111111 101011111 111110101 111011011 1011010101 1010111011 101111111 " +
        "11111011 11110111 101101111 111011111 1110101 110101 1010111 110101111 " +
        "10110111 10111101 11101101 11111111 101110111 101011011 101101011 110101101 " +
        "110101011 110110111 11110101 110111101 111101101 1010101 111010111 1010101111 " +
        "1010111101 1111101 11101011 10101101 10110101 1110111 11011011 11111101 " +
        "101010101 1111111 111111101 101111101 11010111 10111011 11011101 10101011 " +
        "11010101 111011101 10101111 1101111 1101101 101010111 110110101 101011101 " +
        "101110101 101111011 1010101101 111110111 111101111 111111011 1010111111 101101101 " +
        "1011011111 1011 1011111 101111 101101 11 111101 1011011 " +
        "101011 1101 111101011 10111111 11011 111011 1111 111 " +
        "111111 110111111 10101 10111 101 110111 1111011 1101011 " +
        "11011111 1011101 111010101 1010110111 110111011 1010110101 1011010111 1110110101"
    ).split(" ");
    private static final Map<String, Character> DECODE = new HashMap<>();
    static {
        for (int i = 32; i < 127; i++) DECODE.put(CODES[i], (char) i);
        DECODE.put(CODES[9], '\t');
        DECODE.put(CODES[10], '\n');
        DECODE.put(CODES[13], '\r');
        DECODE.put(CODES[8], '\b');
    }

    private Psk31() {}

    public interface TextSink { void onText(char value); }
    public interface PcmSink { boolean write(short[] samples, int count); }

    public static void validateText(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("Enter a message.");
        if (text.length() > 2000) throw new IllegalArgumentException("Limit messages to 2,000 characters.");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= 32 && c <= 126) || c == '\n' || c == '\r' || c == '\t')) {
                throw new IllegalArgumentException("BPSK31 currently supports ASCII text only.");
            }
        }
    }

    /** Streams audio without allocating an entire long message. False means canceled. */
    public static boolean transmit(String text, double frequency, int sampleRate, PcmSink sink) {
        validateText(text);
        if (frequency < 300 || frequency > 2500 || sampleRate < 8000)
            throw new IllegalArgumentException("Unsupported audio parameters.");
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < 64; i++) bits.append('0'); // 2.048 seconds acquisition.
        for (int i = 0; i < text.length(); i++) bits.append(CODES[text.charAt(i)]).append("00");
        for (int i = 0; i < 16; i++) bits.append('1'); // Standard unmodulated postamble.
        short[] block = new short[1024];
        int fill = 0;
        double sign = 1, carrier = 0;
        double increment = 2 * Math.PI * frequency / sampleRate;
        // Fractional sample counts also support 44.1 kHz hardware.
        double symbolSamples = sampleRate / 31.25;
        long emitted = 0;
        for (int b = 0; b < bits.length(); b++) {
            double next = bits.charAt(b) == '0' ? -sign : sign;
            long end = Math.round((b + 1) * symbolSamples);
            long start = emitted;
            while (emitted < end) {
                double t = (emitted - start) / (double) (end - start);
                double envelope = sign == next ? sign : sign * Math.cos(Math.PI * t);
                double ramp = Math.min(1, emitted / (sampleRate * 0.01));
                double remaining = bits.length() * symbolSamples - emitted;
                ramp = Math.min(ramp, Math.min(1, remaining / (sampleRate * 0.01)));
                block[fill++] = (short) (Math.sin(carrier) * envelope * ramp * 15000);
                carrier += increment;
                if (carrier >= 2 * Math.PI) carrier -= 2 * Math.PI;
                emitted++;
                if (fill == block.length) {
                    if (!sink.write(block, fill)) return false;
                    fill = 0;
                }
            }
            sign = next;
        }
        return fill == 0 || sink.write(block, fill);
    }

    /** Streaming complex downconverter, narrow FIR filters, timing and differential detector. */
    public static final class Receiver {
        private final TextSink sink;
        private final double step;
        private final Fir inputI, inputQ, baseI, baseQ;
        private final double[] energy = new double[32];
        private double oscillator, previousI, previousQ, frequencyError;
        private int decimation;
        private long clock, lastDecision = -1000;
        private int bestPhase;
        private boolean havePrevious;
        private final StringBuilder word = new StringBuilder();
        private int zeros;
        private volatile double level, quality;

        public Receiver(double frequency, TextSink sink) {
            this.sink = sink;
            step = 2 * Math.PI * frequency / SAMPLE_RATE;
            inputI = new Fir(129, 110.0 / SAMPLE_RATE);
            inputQ = new Fir(129, 110.0 / SAMPLE_RATE);
            baseI = new Fir(65, 32.0 / 1000);
            baseQ = new Fir(65, 32.0 / 1000);
        }

        public double getLevel() { return level; }
        public double getQuality() { return quality; }

        public void accept(short[] pcm, int count) {
            for (int n = 0; n < count; n++) {
                double value = pcm[n] / 32768.0;
                level = 0.999 * level + 0.001 * value * value;
                double i = inputI.push(value * Math.cos(oscillator));
                double q = inputQ.push(-value * Math.sin(oscillator));
                oscillator += step + 2 * Math.PI * frequencyError / SAMPLE_RATE;
                if (oscillator >= 2 * Math.PI) oscillator -= 2 * Math.PI;
                if (++decimation != 8) continue;
                decimation = 0;
                process(baseI.push(i), baseQ.push(q));
            }
        }

        private void process(double i, double q) {
            int phase = (int) (clock % 32);
            double power = i * i + q * q;
            energy[phase] += 0.075 * (power - energy[phase]);
            if (clock >= 512 && clock % 128 == 0) {
                int candidate = 0;
                for (int p = 1; p < 32; p++)
                    if (energy[p] > energy[candidate]) candidate = p;
                // Keep a stable sampling position during runs of unchanging phase.
                if (energy[candidate] > energy[bestPhase] * 1.015 || !havePrevious)
                    bestPhase = candidate;
            }
            if (clock >= 512 && phase == bestPhase && clock - lastDecision >= 16) {
                lastDecision = clock;
                if (power < 0.000004) {
                    havePrevious = false;
                    word.setLength(0);
                    zeros = 0;
                    quality *= 0.9;
                } else {
                    if (havePrevious) {
                        double dot = previousI * i + previousQ * q;
                        double cross = previousI * q - previousQ * i;
                        double angle = Math.atan2(cross, dot);
                        double confidence = Math.abs(Math.cos(angle));
                        quality = 0.9 * quality + 0.1 * confidence;
                        // Fold phase reversals away to estimate residual carrier error.
                        double residual = angle;
                        if (residual > Math.PI / 2) residual -= Math.PI;
                        if (residual < -Math.PI / 2) residual += Math.PI;
                        if (confidence > 0.7) {
                            frequencyError += 0.035 * residual * 31.25 / (2 * Math.PI);
                            frequencyError = Math.max(-10, Math.min(10, frequencyError));
                            bit(dot >= 0 ? '1' : '0');
                        } else {
                            word.setLength(0);
                            zeros = 0;
                        }
                    }
                    previousI = i;
                    previousQ = q;
                    havePrevious = true;
                }
            }
            clock++;
        }

        private void bit(char bit) {
            if (bit == '0') {
                zeros++;
                if (zeros == 2) {
                    Character c = DECODE.get(word.toString());
                    if (c != null && quality >= 0.82) sink.onText(c);
                    word.setLength(0);
                }
                return;
            } else {
                if (zeros == 1 && word.length() > 0) word.append('0');
                word.append('1');
                zeros = 0;
            }
            if (word.length() > 14) {
                word.setLength(0);
                zeros = 0;
            }
        }
    }

    private static final class Fir {
        private final double[] taps, history;
        private int cursor;
        Fir(int size, double cutoff) {
            taps = new double[size];
            history = new double[size];
            double sum = 0;
            for (int n = 0; n < size; n++) {
                int k = n - size / 2;
                double sinc = k == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * k) / (Math.PI * k);
                taps[n] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * n / (size - 1)));
                sum += taps[n];
            }
            for (int n = 0; n < size; n++) taps[n] /= sum;
        }
        double push(double sample) {
            history[cursor] = sample;
            double value = 0;
            int pos = cursor;
            for (double tap : taps) {
                value += tap * history[pos];
                if (--pos < 0) pos = history.length - 1;
            }
            if (++cursor == history.length) cursor = 0;
            return value;
        }
    }
}
