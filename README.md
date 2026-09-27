# HBC ATAK Plugin

**Ham Binary Cursor on Target (HBC) over amateur radio audio** — an
ATAK-CIV plugin that transmits and receives Cursor on Target (CoT) events
as audio through a ham radio: no internet, no TAK server, no
infrastructure. Compliant with FCC Part 97 station identification (your
callsign rides in every frame).

CoT events are compressed with the
[HBC Protocol v1.6](https://github.com/ke8tqb/HBC-Protocol) (14–49 bytes
per message instead of 500–1000+ bytes of CoT XML), carried in a
distance-vector mesh frame, and modulated as audio by your choice of
three software modems — including the Mercury HF waveform for long-haul
SSB work.

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

## Three selectable modems

| | AFSK1200 / AX.25 | OFDM COFDMTV (rattlegram) | Mercury HF (FreeDV DATAC) |
|---|---|---|---|
| Compatibility | Standard packet radio gear: Direwolf, hardware TNCs | Rattlegram-family COFDMTV (`short` branch protocol) | The [Mercury HF modem](https://mercury.hermes.radio/) waveform: FreeDV/codec2 DATAC raw-data OFDM |
| Framing | AX.25 UI frames as PHY framing (fixed dest `HBC`, source = your callsign for Part 97 ID) | OFDM metadata callsign + polar-coded payload | One DATAC frame per burst — `len, 'H', HBC bytes`, zero-pad, CRC-16 — with Mercury's exact preamble/postamble |
| Robustness | Needs a clean audio path | Very robust on FM: tolerates device DSP, frequency offset, level variation; FEC | Built for HF SSB multipath: DATAC4 (~87 bps) decodes below 0 dB SNR; LDPC FEC |
| Best for | Interop with existing packet infrastructure | VHF/UHF FM voice radios | Long-haul HF |

All three modems support the phone/tablet speaker+mic (VOX-keyed radios)
or a USB-C audio interface such as the Digirig Mobile — when a USB audio
device is attached, TX and RX are automatically routed to it exclusively.
Multi-hop delivery is handled by the mesh layer below, on every modem.

> **Mercury interop note:** the plugin speaks Mercury's waveform, frame,
> and CRC layout (the same vendored FreeDV sources, built as
> `libhbcmercury` with a JNI bridge), so desktop Mercury demodulates the
> bursts — but the plugin does not implement Mercury's ARQ/data-link
> protocol, so frames will not surface on Mercury's TCP data interface.

## Collision avoidance (CSMA)

All three modems share an energy-based carrier sense with p-persistent
backoff (`CsmaSense`): received audio is tracked against an adaptive
noise floor, and before every transmission the modem waits for a clear
channel plus a random 150–550 ms that must stay clear. After 8 s (15 s
on Mercury HF) the frame is sent regardless so traffic is never starved.
On AFSK the demodulator's DCD is an additional carrier-sense input.

## Mesh networking

Every frame carries a 13-byte distance-vector mesh header implementing
["Adaptation of Uncoordinated Distance-Vector Routing for Unencrypted
Amateur Radio Networks"](docs/Unencrypted_Distance-Vector_Routing_for_Amateur_Radio.pdf)
(Reticulum-style announce propagation, no cryptography), above all three
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
  makes the station selectable for direct chat (v0.18).

## Message types (HBC modes)

- **PLI / Spot** position reports and markers with Friendly / Hostile /
  Neutral / Unknown affiliation (Mode 1, HBC v1.5)
- **911 Alerts** and cancels (Mode 2)
- **GeoChat** — All Chat Rooms, named rooms, or direct messages (Mode 3,
  HBC v1.4)
- **Shapes** — circles, rectangles, freeform (Mode 4)
- **CASEVAC** 9-line (Mode 5)
- **Extended Markers** (Mode 6, HBC v1.3) — placed markers keep their full
  CoT type, MIL-STD-2525 / spot-map / custom-iconset icon reference, and
  color tint

## Settings (in-plugin pane, Settings tab)

- Ham callsign (ITA2-validated, max 8 chars) — required
- Modem selection (AFSK1200/AX.25, OFDM, or Mercury HF) — must match on
  all stations
- TX audio stream (Alarm / Media / Ring / Notification) — Alarm bypasses
  Samsung media DSP that distorts FSK tones
- Send to — Broadcast, or route markers/points direct to any station
  learned from the mesh
- TX dwell (TXDelay preamble) and VOX leader tone duration
- PLI rate limit; per-message-type transmit toggles; TX/RX enables

A dedicated **Decodes** tab shows every received packet with timestamp,
source, mode summary, and payload size.

## Install

1. Install ATAK-CIV **5.7 or 5.8** (the SDK-signed `atak.apk` from the
   matching ATAK-CIV SDK release) and grant it microphone permission.
2. Install the plugin APK **for your ATAK line** — ready-to-install
   civ-debug builds for both 5.7.x and 5.8.x are checked in at
   [`prebuilt/`](prebuilt/). The plugin-api must match exactly: a 5.7
   build will not load on 5.8 and vice versa.
3. Load it from ATAK's plugin manager, then Toolbar → **HBC Radio** →
   enter callsign → Start Radio Link.

## Build

Requires an [ATAK-CIV SDK](https://tak.gov) zip (5.7.x and/or 5.8.x),
Android SDK 35, NDK 25.1.8937393, CMake 3.22, JDK 17+.

An APK only loads on the ATAK line it was built for (ATAK requires an
exact `plugin-api` match), so each supported line gets its own build of
this same source — pick the target with `-PATAK_VERSION` (default 5.7.0):

```
# local.properties needs sdk.dir, takdev.plugin and sdk.path
# (see local.properties.example)
./gradlew -PATAK_VERSION=5.7.0 assembleCivDebug
./gradlew -PATAK_VERSION=5.8.0 assembleCivDebug
```

Or build every supported line in one shot (Windows):

```
./build-both.ps1                            # civ-debug for 5.7.0 + 5.8.0
./build-both.ps1 -Task assembleCivRelease   # proguarded release builds
```

`build-both.ps1` extracts each SDK zip once (cached under
`%LOCALAPPDATA%\ATAK-SDKs`), runs takdev in Offline DevKit mode against it
(`-Psdk.path`), stamps the matching plugin-api, and collects the
version-suffixed APKs.

### TAK.gov third-party pipeline submission

For production signatures, submit the source to the pipeline
(tak.gov → Resources → Third Party Pipeline). `make-submission.ps1`
produces the zip in the shape the pipeline requires — a single
`HBC-ATAK-Plugin/` root folder (the pipeline names its APKs after it),
tracked files only (no `local.properties`, no build outputs, prebuilt
APKs excluded), with the target ATAK line pinned via `ATAK_VERSION` in
`gradle.properties`:

```
./make-submission.ps1                     # source zip targeting ATAK 5.7.0
./make-submission.ps1 -AtakVersion 5.8.0  # source zip targeting ATAK 5.8.0
```

Pipeline source-archive requirements and how this repo meets them:

- Gradle build at the archive root with `assembleCivRelease` defined
- Every ATAK SDK reference resolved through `atak-gradle-takdev`
  (current-plugintemplate configuration; the pipeline supplies the TAK
  maven repo credentials at build time)
- Plugin-specific proguard repackage: `atakplugin.HBC-ATAK-Plugin`
- `com.atakmap.app.component` discovery activity in AndroidManifest.xml
- `ndkVersion` pinned to 25.1.8937393, one of the NDKs pre-installed on
  the pipeline build machine (it does not install declared versions)

`codec-test/` contains JVM-runnable reference-vector tests
(`HbcCodecTest`, `Mode6Test`) verifying byte-for-byte parity with the
Python [HBC-Protocol](https://github.com/ke8tqb/HBC-Protocol)
implementation, plus `DemodFile` for running recorded audio through the
demodulator offline.

## Documentation

- [`docs/HBC_ICD.html`](docs/HBC_ICD.html) ([PDF](docs/HBC_ICD.pdf)) —
  Interface Control Document v1.2: per-control GUI reference, wire
  formats, and log interpretation guides
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md),
  [`docs/RADIO_SETUP.md`](docs/RADIO_SETUP.md),
  [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)
- [`docs/Unencrypted_Distance-Vector_Routing_for_Amateur_Radio.pdf`](docs/Unencrypted_Distance-Vector_Routing_for_Amateur_Radio.pdf)
  — the routing design the mesh layer implements
- [`CHANGELOG.md`](CHANGELOG.md) — per-version details

## License

GPL v2 or later (see `LICENSE`) — required because the plugin bundles the
GPLv2+ javAX25 AFSK modem. Third-party components and full attributions:
[`THIRD-PARTY.md`](THIRD-PARTY.md). The HBC protocol itself and its
reference implementation are MIT-licensed in the
[HBC-Protocol](https://github.com/ke8tqb/HBC-Protocol) repository.

---

*Callsign: KE8TQB*
