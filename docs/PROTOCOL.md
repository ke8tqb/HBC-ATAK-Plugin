# HBC Protocol Specification

**Ham Binary Cursor on Target (HBC) v1.0**

A compact binary encoding of ATAK Cursor on Target (CoT) messages designed
for transmission over bandwidth-constrained amateur radio audio links.

Reference implementation: [github.com/ke8tqb/HBC-Protocol](https://github.com/ke8tqb/HBC-Protocol)

---

## Design Goals

- Fit a complete position report in under 25 bytes
- Include transmitting station callsign for FCC Part 97 identification
- Decode reliably without pre-shared state or lookup tables
- Produce a valid ATAK CoT event on the receiving end

---

## Bit Stream Layout

```
┌──────────────────────────────────────────┐
│  HEADER (variable length)                │
│  Callsign   ITA2, CR-terminated          │
│  Version    3 bits  (000 = v1.0)         │
│  Mode       3 bits  (see Mode table)     │
├──────────────────────────────────────────┤
│  PAYLOAD (mode-dependent, see below)     │
└──────────────────────────────────────────┘
```

All fields are packed MSB-first with no padding between fields.
The entire bit stream is right-zero-padded to a byte boundary for transmission.

---

## Header — Callsign Field

The callsign is encoded in **ITA2 (International Telegraph Alphabet No. 2)**,
5 bits per character. Digits require a FIGS shift code before the digit and
a LTRS shift code after. The field is terminated by the CR code (0b01000).

Maximum callsign length: **8 characters** (ITA2 field limit).

### ITA2 Letters table (used by default)

| Code  | Char | Code  | Char | Code  | Char |
|-------|------|-------|------|-------|------|
| 00001 | E    | 01010 | R    | 10010 | L    |
| 00011 | A    | 01011 | J    | 10011 | W    |
| 00100 | (sp) | 01100 | N    | 10100 | H    |
| 00101 | S    | 01101 | F    | 10101 | Y    |
| 00110 | I    | 01110 | C    | 10110 | P    |
| 00111 | U    | 01111 | K    | 10111 | Q    |
| 01000 | **CR** | 10000 | T  | 11000 | O    |
| 01001 | D    | 10001 | Z    | 11001 | B    |
| —     | —    | —     | —    | 11010 | G    |
| 11011 | **FIGS** | 11100 | M | 11101 | X  |
| 11110 | V    | 11111 | **LTRS** | — | —  |

### ITA2 Figures table (after FIGS shift)

| Code  | Char | Code  | Char |
|-------|------|-------|------|
| 00001 | 3    | 00011 | -    |
| 00101 | '    | 00110 | 8    |
| 00111 | 7    | 01001 | $    |
| 01010 | 4    | 01100 | ,    |
| 01101 | !    | 01110 | :    |
| 01111 | (    | 10000 | 5    |
| 10001 | "    | 10010 | )    |
| 10011 | 2    | 10100 | #    |
| 10101 | 6    | 10110 | 0    |
| 10111 | 1    | 11000 | 9    |
| 11001 | ?    | 11010 | &    |
| 11100 | .    | 11101 | /    |
| 11110 | ;    | —     | —    |

### Example — Callsign "KE8TQB"

```
K        = 01111  (Letters)
E        = 00001  (Letters)
FIGS     = 11011  (shift for digit)
8        = 00110  (Figures)
LTRS     = 11111  (return to letters)
T        = 10000
Q        = 10111
B        = 11001
CR_TERM  = 01000  (end of callsign field)

Total: 45 bits
```

---

## Header — Version and Mode Fields

```
Bits   Field     Values
[2:0]  Version   000 = protocol v1.0
[5:3]  Mode      000 = Mode 1 (PLI/Spot)
                 001 = Mode 2 (Alert)
```

Encoded as (value - 1), so Version 1 → 000, Mode 1 → 000, Mode 2 → 001.

---

## Mode 1 — PLI / Spot Marker

Used for position reports and map markers.
CoT types: `a-f-G-*` (friendly), `a-h-G-*` (hostile), `a-n-G-*` (neutral),
`a-u-G` (unknown), and other ground tracks.

```
Bit     Field       Description
[0]     PLI bit     0 = PLI (moving unit), 1 = Spot (placed marker)
[3:1]   Name len    0–7 (number of ASCII characters that follow)
[N]     Name        ASCII, 8 bits per character, N = name_len × 8
[20:0]  Latitude    21-bit signed two's complement, units = degrees × 10,000
[21:0]  Longitude   22-bit signed two's complement, units = degrees × 10,000
```

### Coordinate encoding

```
encoded = round(coordinate_degrees × 10,000)
decoded = encoded_integer / 10,000.0
```

| Field     | Bits | Range (degrees) | Precision |
|-----------|------|-----------------|-----------|
| Latitude  | 21   | ±90°            | ~11 m     |
| Longitude | 22   | ±180°           | ~11 m     |

### Decoded CoT type

| PLI bit | Decoded CoT type | ATAK marker |
|---------|-----------------|-------------|
| 0       | `a-f-G`         | Friendly ground track (teal diamond) |
| 1       | `a-u-G`         | Unknown ground (yellow diamond) |

### Name field limit

Maximum **7 characters**. Longer callsigns/names are truncated on encode.
The receiving side displays what was transmitted.

### Worked example — KE8TQB at 40.621776°N, 83.204262°W

```
Header:    KE8TQB (ITA2, 45 bits) + 000 (version) + 000 (mode) = 51 bits
PLI bit:   0  (moving unit)
Name len:  110  (6 chars)
Name:      "KE8TQB" = 01001011 01000101 00111000 01010100 01010001 01000010  (48 bits)
Latitude:  406218 → 001100011001011001010  (21 bits)
Longitude: -832043 → 1100110100110111010101  (22 bits)

Total: 51 + 1 + 3 + 48 + 21 + 22 = 146 bits → 19 bytes
Hex:  78 76 6F C2 F9 40 0C 96 8A 70 A8 A2 84 63 2C AC D3 75 40
```

---

## Mode 2 — Alert Message

Used for 911 / emergency alerts. CoT type: `b-a-o-tbl`.

```
[3:1]   Alert name len     0–7
[N]     Alert name         ASCII (contact callsign, e.g. "ONYX-Al")
[3:1]   Originator len     0–7
[M]     Originator name    ASCII (station name before '-', e.g. "ONYX")
[20:0]  Latitude           21-bit signed
[21:0]  Longitude          22-bit signed
```

---

## UID Derivation Rule

HBC does not transmit the full ATAK device UID (`ANDROID-xxxxxxxx`) because
it is too long and varies per device. Instead, every decoder independently
derives a stable UID from the callsign already in the header:

| Mode        | PLI bit | Derived UID                  |
|-------------|---------|------------------------------|
| Mode 1 PLI  | 0       | `HBC-{CALLSIGN}`             |
| Mode 1 Spot | 1       | Random UUID4 (ephemeral)     |
| Mode 2 Alert| —       | `HBC-{CALLSIGN}-911`         |

**Why this works:** Ham radio callsigns are globally unique by ITU regulation
and remain constant for the lifetime of a licence. All receiving stations
applying this rule independently produce the **same UID for the same callsign**,
so TAK clients across the network track each station under one consistent marker.

---

## Sentinel Values

Fields that cannot be recovered from HBC are set to ATAK standard sentinels:

| Field | Sentinel | Meaning |
|-------|----------|---------|
| `hae` | 9999999  | Altitude unknown |
| `ce`  | 9999999  | Circular error unknown |
| `le`  | 9999999  | Linear error unknown |
| `track/@course` | 9999999.0 | Heading unknown |
| `track/@speed`  | 0.0       | Speed unknown |
| `time` / `start` | decode time | Original capture time not transmitted |
| `stale` | +5 min (PLI/Alert) / +1 year (Spot) | From decode time |

---

## OFDM Transport

HBC bytes are handed to the **aicodix OFDM modem** (short branch) for
audio encoding. The modem parameters used by this plugin:

| Parameter | Value |
|-----------|-------|
| Sample rate | 8000 Hz |
| Bit depth | 16-bit signed |
| Channels | 1 (mono) |
| Center frequency | 1500 Hz |
| Bandwidth | ~1600 Hz |
| Mode | oper_mode 16 (680 data bits = 85 bytes payload) |
| FEC | Polar codes (rate ~0.49) |
| Sync | Schmidl-Cox correlator |
| Lead-in silence | 1 second |

The OFDM frame can carry up to **170 bytes** raw (before FEC). HBC frames
(14–21 bytes) fit comfortably with significant margin for future expansion.

---

## Adding New Modes

Both `HBCEncoder.java` and `HBCDecoder.java` use dispatch tables for mode
handling. To add a new mode:

1. **Encoder**: add an entry to `_MODE_BY_COT_TYPE`, a `_build_modeN()` method,
   and register it in `_BUILDERS`.
2. **Decoder**: add a `_decode_modeN()` method and register it in `_DECODERS`.
3. Increment the spec version in `HBC_VERSION` if the wire format changes.

See comments marked `// ADDING A NEW MODE` in both files.
