# HBC ATAK Plugin

**Ham Binary Cursor on Target (HBC) over amateur radio audio** — an
ATAK-CIV plugin that transmits and receives Cursor on Target (CoT) events
as audio through a ham radio: no internet, no TAK server, no
infrastructure. Compliant with FCC Part 97 station identification (your
callsign rides in every frame).

CoT events are compressed with the
[HBC Protocol v1.3](https://github.com/ke8tqb/HBC-Protocol) (14–49 bytes
per message instead of 500–1000+ bytes of CoT XML) and modulated as audio
by your choice of two software modems.

---

## How it works

```
ATAK CoT event ──> HBC encoder (14-49 B) ──> modem ──> radio ──> RF
RF ──> radio audio ──> modem ──> HBC decoder ──> CoT ──> ATAK map
```

- **Outbound:** every CoT event ATAK sends is intercepted
  (`PreSendProcessor`), HBC-encoded, and queued for transmission.
- **Inbound:** received audio is demodulated continuously; CRC-valid
  frames are HBC-decoded and injected into ATAK's map via the internal
  CoT dispatcher. Duplicate frames and self-heard transmissions are
  filtered.

## Two selectable modems

| | AFSK1200 / AX.25 | OFDM COFDMTV (rattlegram) |
|---|---|---|
| Compatibility | Standard packet radio: digipeaters, Direwolf, APRS gear | Rattlegram-family COFDMTV (`short` branch protocol) |
| Framing | AX.25 UI frames, callsign-SSID + digipeater path | OFDM metadata callsign + polar-coded payload |
| Robustness | Needs a clean audio path | Very robust: tolerates device DSP, frequency offset, level variation; forward error correction |
| Multi-hop | Yes (WIDE-style digipeating) | No (point-to-point / broadcast) |

Both modems support the phone/tablet speaker+mic (VOX-keyed radios) or a
USB-C audio interface such as the Digirig Mobile — when a USB audio device
is attached, TX and RX are automatically routed to it exclusively.

## Message types (HBC modes)

- **PLI** position reports (Mode 1)
- **911 Alerts** and cancels (Mode 2)
- **GeoChat** (Mode 3)
- **Shapes** — circles, rectangles, freeform (Mode 4)
- **CASEVAC** 9-line (Mode 5)
- **Extended Markers** (Mode 6, HBC v1.3) — placed markers keep their full
  CoT type, MIL-STD-2525 / spot-map / custom-iconset icon reference, and
  color tint

## Settings (in-plugin pane, Settings tab)

- Ham callsign (ITA2-validated, max 8 chars) — required
- Modem selection (AFSK1200/AX.25 or OFDM) — must match on all stations
- TX audio stream (Alarm / Media / Ring / Notification) — Alarm bypasses
  Samsung media DSP that distorts FSK tones
- AX.25 destination and digipeater path
- TX dwell (TXDelay preamble) and VOX leader tone duration
- PLI rate limit; per-message-type transmit toggles; TX/RX enables

A dedicated **Decodes** tab shows every received packet with timestamp,
source, mode summary, and payload size.

## Install

1. Install ATAK-CIV **5.5** (the SDK-signed `atak.apk` from the ATAK-CIV
   5.5 SDK release) and grant it microphone permission.
2. Install the plugin APK and load it from ATAK's plugin manager.
3. Toolbar → **HBC Radio** → enter callsign → Start Radio Link.

## Build

Requires the [ATAK-CIV SDK](https://github.com/TAK-Product-Center/atak-civ)
(5.5.x), Android SDK 35, NDK 27, CMake 3.22, JDK 17+.

```
# place this project in the SDK's samples/ directory (or set takdev.plugin
# and sdk paths in local.properties; see template.local.properties)
./gradlew assembleCivRelease
```

`codec-test/` contains JVM-runnable reference-vector tests
(`HbcCodecTest`, `Mode6Test`) verifying byte-for-byte parity with the
Python [HBC-Protocol](https://github.com/ke8tqb/HBC-Protocol)
implementation, plus `DemodFile` for running recorded audio through the
demodulator offline.

## License

GPL v2 or later (see `LICENSE`) — required because the plugin bundles the
GPLv2+ javAX25 AFSK modem. Third-party components and full attributions:
[`THIRD-PARTY.md`](THIRD-PARTY.md). The HBC protocol itself and its
reference implementation are MIT-licensed in the
[HBC-Protocol](https://github.com/ke8tqb/HBC-Protocol) repository.

---

*Callsign: KE8TQB*
