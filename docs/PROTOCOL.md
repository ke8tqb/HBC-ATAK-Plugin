# HBC Protocol Specification

**Ham Binary Cursor on Target (HBC) v1.5**

> This document covers Modes 1-3 in detail. For the full, authoritative
> spec (all six modes, worked examples, and wire-format history) see the
> reference implementation linked below.

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
[0]     PLI bit       0 = PLI (moving unit), 1 = Spot (placed marker)
[2:1]   Affiliation   00 Friendly (a-f-G), 01 Hostile (a-h-G),
                      10 Neutral (a-n-G), 11 Unknown (a-u-G, or any
                      type with no atom affiliation prefix)
[3:1]   Name len      0–7 (number of ASCII characters that follow)
[N]     Name          ASCII, 8 bits per character, N = name_len × 8
[20:0]  Latitude      21-bit signed two's complement, units = degrees × 10,000
[21:0]  Longitude     22-bit signed two's complement, units = degrees × 10,000
```

> **v1.5:** Affiliation was added because decode previously hardcoded PLI
> as Friendly and Spot as Unknown regardless of what was transmitted —
> silently mislabeling hostile/neutral units. This is a wire-incompatible
> change from earlier plugin versions.

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

The reconstructed type is `AFFILIATION_TYPES[affiliation]`, independent of
the PLI/Spot bit (PLI in practice only ever encodes 0/1/2; Spot most often
encodes 3, but can encode any value if the original marker had an atom
affiliation prefix):

| Affiliation | Decoded CoT type | ATAK marker |
|-------------|-----------------|-------------|
| 0 Friendly  | `a-f-G`         | Friendly ground track (teal diamond) |
| 1 Hostile   | `a-h-G`         | Hostile ground track (red diamond) |
| 2 Neutral   | `a-n-G`         | Neutral ground track (green square) |
| 3 Unknown   | `a-u-G`         | Unknown ground (yellow diamond) |

### Name field limit

Maximum **7 characters**. Longer callsigns/names are truncated on encode.
The receiving side displays what was transmitted.

### Worked example — KE8TQB at 40.621776°N, 83.204262°W

```
Header:      KE8TQB (ITA2, 45 bits) + 000 (version) + 000 (mode) = 51 bits
PLI bit:     0  (moving unit)
Affiliation: 00  (Friendly — type is a-f-G-U-C)
Name len:    110  (6 chars)
Name:        "KE8TQB" = 01001011 01000101 00111000 01010100 01010001 01000010  (48 bits)
Latitude:    406218 → 001100011001011001010  (21 bits)
Longitude:   -832043 → 1100110100110111010101  (22 bits)

Total: 51 + 1 + 2 + 3 + 48 + 21 + 22 = 148 bits → 19 bytes
Hex:  78 76 6F C2 F9 40 03 25 A2 9C 2A 28 A1 18 CB 2B 34 DD 50
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

## Mode 3 — GeoChat

Used for chat messages. CoT type: `b-t-f`.

```
[1:0]   Destination Kind   2 bits: 00 All Chat Rooms, 01 Named Room,
                           10 Direct Message, 11 reserved
Named Room:
  [N]   Room Name          ITA2, CR-terminated (free text)
Direct Message:
  [N]   Recipient          ITA2, CR-terminated (max 8 chars, same
                           alphabet as the header callsign)
[N]     Message            ITA2, CR-terminated, uppercase only
```

Classification on encode, from `<__chat>`/`<chatgrp>`:

| Condition | Destination Kind |
|-----------|-----------------|
| No chatroom, or chatroom = "All Chat Rooms" | All Chat Rooms |
| `<chatgrp>` has 3+ `uidN` attributes | Named Room (chatroom name transmitted) |
| Otherwise (exactly `uid0` + `uid1`) | Direct Message (chatroom name — ATAK's 1:1 chat tab label — transmitted as the recipient) |

A Direct Message recipient resolves to `HBC-{RECIPIENT}` on decode, the
same deterministic UID a PLI report from that station would produce, so a
DM correlates with an existing contact automatically. A Named Room uses
the literal room name as its own UID, matching the "All Chat Rooms"
convention.

> **v1.4:** this is a wire-incompatible change from earlier plugin
> versions, which only ever encoded/decoded the All Chat Rooms case.

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

## Channel Access (MAC)

Two channel-access modes exist. The MAC is **behavioral only** — it
changes WHEN a station keys up, never the bytes on the air. Wire format,
mesh framing and HBC payloads are identical in both modes, so mixed
networks interoperate (a Ring station treats a CSMA station's
transmission as ordinary carrier and defers to it).

### CSMA (legacy)

Listen-before-talk with RMS carrier sense (busy above 4× noise floor or
an absolute threshold, 400 ms busy-hold), 150–550 ms random backoff and
an 8 s (Mercury 15 s) give-up. Adequate for 2–5 stations; degrades with
hidden terminals and VOX hang at larger counts.

### Ring (deterministic rotation)

Designed for up to ~20 stations behind VOX-keyed HTs (reference radios:
Baofeng UV-5R, Yaesu FT-65 — fixed ~1.0–1.5 s VOX hang, not
adjustable). Rules (normative):

1. **Roster.** Each station MUST maintain `sort(unique(own callsign +
   fresh mesh routes))`. The sorted list is the transmit order; the
   turn "token" is implied, never transmitted. Roster changes MUST be
   applied only at cycle wrap.
2. **Turn ownership.** A station MUST transmit data frames only during
   its own turn (exception: rule 6), and MUST NOT key up while the
   channel is busy (carrier-sense interlock).
3. **Turn advance.** Every station advances its local turn pointer on
   the FIRST of:
   - *Early release:* carrier attributed to the owner was heard and the
     channel has then been clear for `GUARD` ms;
   - *Silent skip:* no carrier within `SKIP` ms of turn start;
   - *Deadline:* `MAX_TURN + GUARD` ms since turn start (hidden
     terminals, dead stations).
4. **Re-alignment.** On decoding any mesh frame, a station SHOULD set
   its turn pointer to the frame's Transmitter callsign. Decoded
   frames are authoritative over local timers.
5. **Joining.** A new station SHOULD listen for one observed cycle (or
   30 s, whichever comes first) before taking its first turn.
6. **Emergency preemption.** Mode 2 alert frames MAY be transmitted in
   an inter-turn idle window after a 0–300 ms random offset. No other
   traffic may preempt.
7. **Batching.** During its turn a station SHOULD send all queued
   frames as ONE continuous keying, up to its per-turn cap. Since
   v0.23 the cap derives from the turn budget —
   `clamp(floor((MAX_TURN − 500 ms) / per-frame burst), 1, 4)` — which
   yields AFSK 4 / OFDM 2 / Mercury 1 frames at the automatic
   defaults; raising MAX_TURN identically on every station raises the
   cap. On AFSK the first frame carries the full flag preamble
   (≥300 ms); continuation frames carry a short ~20 ms flag run — the
   receiving demodulator stays bit-synced across the burst.
   OFDM/Mercury bursts concatenate with 100 ms gaps.

Parameters (defaults; calibrate `GUARD` as worst measured VOX hang +
150 ms using the session log's inter-burst timing):

| Parameter | Default | Covers |
|-----------|---------|--------|
| GUARD     | 1500 ms | UV-5R / FT-65 VOX hang (~1.0–1.5 s, fixed) |
| SKIP      | 1200 ms | VOX attack (~250 ms) + carrier-detect latency |
| MAX_TURN  | auto: AFSK 6000 / OFDM 4400 / Mercury 6000 ms | longest batch |
| Settle    | 1 cycle (max 30 s) | join listening period |
| PLI floor | roster × 8 s (Mercury × 13 s) — raised from × 6 / × 10 in v0.23 | PLIs ≤ ~25% of rotation airtime |

Capacity (20 stations, 5 with traffic per cycle): AFSK ≈ 32 s, OFDM ≈
35 s, Mercury ≈ 54 s per rotation.

### ARQ interaction (v0.23, normative)

The mesh layer's Direct-message ARQ MUST account for MAC queueing,
otherwise retries multiply inside the TX queue (observed on 0.22: a
3-station net turned every chat DM into 4 on-air copies, declared 20 of
23 Directs failed, and built ~21-frame backlogs):

1. **TX-gated retry clock.** The retry/failure timer for a pending
   Direct MUST NOT run while its latest copy is still waiting in the
   MAC queue; it is armed when the copy actually airs. A pending whose
   copies never air is failed after 180 s (stuck-queue safety).
2. **Rotation-scaled pacing.** Under Ring, the retry delay MUST be at
   least one full rotation — implementation: `max(8 s, 1.25 × measured
   rotation)`, capped at 60 s, plus 0–2 s jitter. Under CSMA the
   legacy 5 s + jitter applies. (An ACK cannot return before the
   recipient's own turn.)
3. **Unacknowledged receipts.** Chat delivered/read receipts (Mode 0)
   SHOULD be sent as Directs WITHOUT retry state — identical wire
   format, no retries, no failure verdict. The recipient still
   mesh-ACKs; the origin ignores it.
4. **Queue hygiene.** A queued self-PLI SHOULD be replaced in place
   when a fresher one is generated (positions go stale; two queued
   positions for one station waste a turn).

All four rules are behavioral only — wire-identical with 0.22; mixed
nets simply leave old stations with the old retry behavior.

---

## Adding New Modes

Both `HBCEncoder.java` and `HBCDecoder.java` use dispatch tables for mode
handling. To add a new mode:

1. **Encoder**: add an entry to `_MODE_BY_COT_TYPE`, a `_build_modeN()` method,
   and register it in `_BUILDERS`.
2. **Decoder**: add a `_decode_modeN()` method and register it in `_DECODERS`.
3. Increment the spec version in `HBC_VERSION` if the wire format changes.

See comments marked `// ADDING A NEW MODE` in both files.
