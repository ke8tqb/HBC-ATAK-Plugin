# HBC ATAK Plugin

**Ham Binary Cursor on Target (HBC) over amateur radio audio** — an
ATAK-CIV plugin that transmits and receives Cursor on Target (CoT) events
as audio through a ham radio: no internet, no TAK server, no
infrastructure. Compliant with FCC Part 97 station identification (your
callsign rides in every frame).

---

## 🚀 Quick Start

Get on the air in five minutes:

1. **Install ATAK-CIV 5.8** from [tak.gov](https://tak.gov) or the Google
   Play Store, open it once, and grant it **microphone** permission.
2. **Download the plugin APK:**
   ➡️ [`ATAK-Plugin-HBC-ATAK-Plugin-0.29--5.8.0-civ-release.apk`](prebuilt/ATAK-Plugin-HBC-ATAK-Plugin-0.29--5.8.0-civ-release.apk)
   (tap the link, then the **Download raw file** button).
3. **Install it** — open the downloaded APK on your phone and approve the
   install.
4. **Load it in ATAK** — ATAK detects the new plugin on next launch
   (or: ATAK menu → Plugins → scan). Tap **Load** when prompted.
5. **Start the radio link** — ATAK menu (☰) → **Tools** → **HBC Radio** →
   enter your ham callsign → **Start Radio Link**.

Hold your handheld's mic near the phone speaker (or use VOX / a Digirig
cable), and your position, markers, and chat now travel by radio. Repeat
on a second phone + radio and watch stations appear on each other's maps.

> **No radios handy?** Two phones on a desk work: start the link on both
> with the same modem and point speakers at microphones.

> **Settings for your radio:** see
> [`docs/RADIO_PRESETS.md`](docs/RADIO_PRESETS.md) — recommended plugin +
> radio settings for 30 common VHF/UHF and HF radios.

## Installing the APK — details

- **Install ATAK first, then the plugin.** The plugin APK has no launcher
  icon of its own; ATAK discovers and loads it.
- The provided APK is a **production release signed by the TAK Product
  Center**, built for the **ATAK-CIV 5.8** line. ATAK requires an exact
  plugin-api match, so it loads only on official 5.8.x releases from
  tak.gov or the Play Store (not on 5.7 or SDK/developer builds).
- Verify the plugin loaded under ATAK → **TAK Package Mgmt** (it is
  listed as *HBC-ATAK-Plugin*, with the HBC tower badge). The plugin's
  own pane shows the installed version in its header while stopped.
- Updating: install the newer APK over the old one, then restart ATAK.
- The only Android permission the plugin relies on is ATAK's
  **microphone** access (for receive audio).

## Documentation

- [`docs/HBC_ICD.html`](docs/HBC_ICD.html) ([PDF](docs/HBC_ICD.pdf)) —
  Interface Control Document: per-control GUI reference with annotated
  screenshots, wire formats, channel-access (CSMA / Ring MAC)
  specification, and log interpretation guides
- [`docs/HBC_BIT_WALKTHROUGH.md`](docs/HBC_BIT_WALKTHROUGH.md)
  ([PDF](docs/HBC_BIT_WALKTHROUGH.pdf)) — **bit-level walkthrough** of a
  CoT event's full journey (CoT → HBC → mesh frame → modem audio), with a
  worked, real-on-air example for every HBC mode
- [`docs/RADIO_PRESETS.md`](docs/RADIO_PRESETS.md)
  ([PDF](docs/RADIO_PRESETS.pdf)) — recommended plugin + radio settings
  for 30 popular radios (20 VHF/UHF, 10 HF)
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md),
  [`docs/RADIO_SETUP.md`](docs/RADIO_SETUP.md),
  [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)
- [`docs/Unencrypted_Distance-Vector_Routing_for_Amateur_Radio.pdf`](docs/Unencrypted_Distance-Vector_Routing_for_Amateur_Radio.pdf)
  — the routing design the mesh layer implements
- [`CHANGELOG.md`](CHANGELOG.md) — per-version details
- The HBC protocol itself (spec + reference implementation):
  [github.com/ke8tqb/HBC-Protocol](https://github.com/ke8tqb/HBC-Protocol)

---

## How it works

```
ATAK CoT event ──> HBC encoder (14-49 B) ──> mesh header ──> modem ──> radio ──> RF
RF ──> radio audio ──> modem ──> mesh router ──> HBC decoder ──> CoT ──> ATAK map
```

- **Outbound:** every CoT event ATAK sends is intercepted, compressed
  with the [HBC Protocol](https://github.com/ke8tqb/HBC-Protocol)
  (14–49 bytes instead of 500–1000+ bytes of CoT XML), wrapped in a
  13-byte distance-vector mesh header, and queued for transmission as
  audio.
- **Inbound:** received audio is demodulated continuously; CRC-valid
  frames are routed/deduplicated by the mesh layer, HBC-decoded, and
  injected into ATAK's map. Duplicate frames and self-heard
  transmissions are filtered.

See [`docs/HBC_BIT_WALKTHROUGH.md`](docs/HBC_BIT_WALKTHROUGH.md) for the
byte-by-byte version of this picture.

## Four selectable modems

| | AFSK1200 / AX.25 | OFDM COFDMTV (rattlegram) | Mercury HF (FreeDV DATAC) | PSK31 |
|---|---|---|---|---|
| Compatibility | Standard packet radio gear: Direwolf, hardware TNCs | Rattlegram-family COFDMTV (`short` branch protocol) | The [Mercury HF modem](https://mercury.hermes.radio/) waveform: FreeDV/codec2 DATAC raw-data OFDM | The classic PSK31 waveform (BPSK 31.25 Bd + Varicode) |
| Framing | AX.25 UI frames as PHY framing (fixed dest `HBC`, source = your callsign for Part 97 ID) | OFDM metadata callsign + polar-coded payload | One DATAC frame per burst with Mercury's exact preamble/postamble | Binary frames ride as text armor: `~` + Base64(frame + CRC-16) + `~` |
| Robustness | Needs a clean audio path | Very robust on FM: tolerates device DSP, frequency offset, level variation; FEC | Built for HF SSB multipath: DATAC4 (~87 bps) decodes below 0 dB SNR; LDPC FEC | ~31 Hz bandwidth; decodes at SNRs below even the OFDM modes; CRC rejects corrupt frames |
| Best for | Interop with existing packet infrastructure | VHF/UHF FM voice radios | Long-haul HF | Extreme weak signal on any voice channel — **very slow** (a position report ≈ 16 s of air) |

All modems support the phone/tablet speaker+mic (VOX-keyed radios) or a
USB-C audio interface such as the Digirig Mobile/Lite — with USB
attached, TX/RX route to it automatically and the plugin can key PTT
electrically (RTS or CM108 GPIO). Every station on a channel must select
the **same modem**.

## Channel access — CSMA or Ring MAC

Two selectable channel-access modes (must match on all stations). The
MAC is behavioral only — nothing on the air changes.

- **CSMA (default)** — listen-before-talk: adaptive-noise-floor carrier
  sense plus a random 150–550 ms backoff. Give-up thresholds scale with
  the modem's airtime (8 s AFSK/OFDM, 15 s Mercury, 90 s PSK31) so
  traffic is never starved. Fine for 2–5 stations.
- **Ring MAC** — deterministic turn rotation for nets up to ~20 stations
  on VOX handhelds: stations take turns in sorted-roster order (the
  roster the mesh learns passively) — no coordinator, no token packet.
  Turn budgets are airtime-sized per modem; queued frames batch into one
  keying per turn so the VOX key-up/hang cycle is paid once per turn.
  911 alerts may preempt between turns. Mesh delivery retries are
  Ring-aware (a retry can't fire before an ACK could physically return).

The ⓘ help link in the pane gives recommended timing for the reference
radios (Baofeng UV-5R / Yaesu FT-65); per-radio values for 30 radios are
in [`docs/RADIO_PRESETS.md`](docs/RADIO_PRESETS.md). Full specification:
ICD §3.5–3.6.

## Mesh networking

Every frame carries a 13-byte distance-vector mesh header implementing
["Adaptation of Uncoordinated Distance-Vector Routing for Unencrypted
Amateur Radio Networks"](docs/Unencrypted_Distance-Vector_Routing_for_Amateur_Radio.pdf)
(Reticulum-style announce propagation, no cryptography), above all
modems:

- **Passive route learning** — hearing any frame makes the transmitter a
  0-hop neighbor and the originator routable; stations appear in the
  "Send to" list as soon as anything is heard from them. Lowest hop
  count wins; routes expire after 30 minutes.
- **Routed Direct messages with ACK** — GeoChat DMs (and their
  delivered/read receipts) route hop-by-hop to the recipient callsign
  with end-to-end ACK and automatic retries; markers/points can be
  broadcast or sent direct to any learned station.
- **Announces as keepalive only** — your own traffic already announces
  the station via passive learning, so periodic announces fire only
  after 10 quiet minutes; an [origin+seq] dedup cache prevents loops and
  broadcast storms.
- **Heard stations become ATAK chat contacts** — a single received PLI
  makes the station selectable for direct chat.

## C2 Bridge (one-way radio → network)

A command-post phone can run HBC and sit on a normal ATAK network
(TAK server / mesh SA) at the same time. Ticking **C2 Bridge**
(Options tab) turns it into a one-way data diode: every decoded radio
event is re-published onto the network so LAN users see the field
picture, while **nothing network-originated is ever auto-relayed to
the radio** — the slow RF channel cannot be flooded, and unlicensed
network users stay off the air. The bridge's own traffic (PLI,
markers, chat) still transmits normally, and the **Push to RF…**
dialog lets the operator hand-pick individual network items worth
airtime. See ICD §2.1.

## Message types (HBC modes)

- **PLI / Spot** position reports and markers with Friendly / Hostile /
  Neutral / Unknown affiliation (Mode 1)
- **911 Alerts** and cancels (Mode 2)
- **GeoChat** — All Chat Rooms, named rooms, or direct messages with
  delivered/read receipts over the air (Mode 3 + Mode 0 acks)
- **Shapes** — circles, rectangles, freeform (Mode 4)
- **CASEVAC** 9-line (Mode 5)
- **Extended Markers** (Mode 6) — placed markers keep their full CoT
  type, MIL-STD-2525 / spot-map / custom-iconset icon reference, and
  color tint

## Settings (in-plugin pane)

Three tabs under an always-visible header (colored status line — green
RUNNING / red STOPPED — plus a build stamp while stopped, and the
Start/Stop button):

- **Audio Setup** — modem selection (OFDM default; OFDM / Mercury /
  AX.25-APRS / PSK31, must match on all stations), TX audio stream
  (Alarm default — bypasses phone media DSP that distorts modem tones),
  TX output / PTT (phone speaker for VOX/acoustic coupling, or
  USB + RTS PTT for a Digirig), AFSK TX level %, TX dwell, VOX leader
  duration, and Channel access (CSMA / Ring) with Guard / Skip /
  Max-turn fields and the ⓘ radio-defaults help dialog
- **Options** — ham callsign (max 8 chars; required), Send-to
  (Broadcast, or route markers/points direct to any station learned
  from the mesh), PLI rate limit, TX/RX enables, per-message-type
  transmit toggles, and the C2 Bridge one-way gateway with its Push to
  RF dialog
- **Decodes** — a full-panel page showing every received packet with
  timestamp, source, mode summary and payload size, above the live
  activity log

Every radio session also records a detailed debug log offered for
saving to Downloads on Stop.

## License

GPL v2 or later (see `LICENSE`) — required because the plugin bundles the
GPLv2+ javAX25 AFSK modem. Third-party components and full attributions:
[`THIRD-PARTY.md`](THIRD-PARTY.md). The HBC protocol itself and its
reference implementation are MIT-licensed in the
[HBC-Protocol](https://github.com/ke8tqb/HBC-Protocol) repository.

---

