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

## Channel access — CSMA or Ring MAC

Two selectable channel-access modes (Audio Setup → "Channel access";
must match on all stations). The MAC is behavioral only — nothing on
the air changes — so mixed versions interoperate.

- **CSMA (default, legacy)** — energy-based carrier sense with
  p-persistent backoff (`CsmaSense`): received audio is tracked against
  an adaptive noise floor, and before every transmission the modem
  waits for a clear channel plus a random 150–550 ms that must stay
  clear. After 8 s (15 s on Mercury HF) the frame is sent regardless so
  traffic is never starved. On AFSK the demodulator's DCD is an
  additional carrier-sense input. Fine for 2–5 stations.
- **Ring MAC (v0.22)** — deterministic rotation for nets up to ~20
  stations on VOX HTs (reference radios: Baofeng UV-5R / Yaesu FT-65,
  whose ~1–1.5 s VOX hang is not adjustable). Stations take turns in
  sorted-roster order (the roster the mesh already learns passively);
  no coordinator, no token packet. Turns advance on early release
  (carrier clear + Guard ms), silent skip, or a deadline that rides
  through hidden terminals. 911 alerts may preempt in the inter-turn
  gap. An ⓘ help link in the pane gives the recommended UV-5R/FT-65
  values (Guard 1500, Skip 1200, VOX Lead 250). See `docs/PROTOCOL.md`
  ("Channel Access") and ICD §3.5.

In both modes the TX path **batches** queued frames into one continuous
keying (AFSK 4 / OFDM 2 / Mercury 1 frames per burst at the automatic
defaults; since v0.23 a raised Max turn fits proportionally more frames
per batch), so the radio's VOX key-up/hang cycle is paid once per talk
burst instead of once per frame.

v0.23 also makes the mesh delivery retries **Ring-aware** after field
testing showed the CSMA-era 5 s retry timer flooding the turn queue: a
Direct's retry clock now starts only when the frame actually airs,
retries wait at least ~1.25 ring rotations, chat delivered/read
receipts fly as fire-and-forget (unacked) Directs, a queued position
report is replaced when a fresher one arrives, and a `Ring: TX queue
N frames` warning appears if a backlog builds. The in-app PLI interval
floor rose to roster × 8 s (Mercury × 13 s); see ICD §3.5/§4.9 for the
numbers behind both.

v0.25 adds **hardware PTT**: with a Digirig-class USB interface,
select **TX output / PTT = USB + RTS PTT** and the plugin keys the
radio over the CP210x serial RTS line (raw USB control transfers — no
serial-driver dependency). No VOX attack or hang means VOX Lead 0 and
Guard 300–500 ms, so ring turns get much shorter. This is the
supported path for cabled setups: the UV-5R/FT-65's fixed-threshold
VOX cannot trip on Digirig-level audio anyway. On first start Android
shows an "Allow ATAK to access the USB device?" prompt — tap Allow and
RTS engages automatically (dismiss the unrelated "Choose an app"
popup that may appear when plugging the cable in; ATAK is
intentionally not in that list).

v0.26 extends USB support: the **Digirig Lite** keys via its CM108
sound-chip GPIO (same selector, auto-detected), USB TX audio rides
the **Media** stream (set Media volume to max — fixes faint TX), and
any mid-session USB unplug/replug now re-opens the receive path
automatically — no Stop/Start needed.

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

## C2 Bridge (one-way radio → network, v0.24)

A command-post phone can run HBC and sit on a normal ATAK network
(TAK server / mesh SA) at the same time. Ticking **C2 Bridge**
(Options tab) turns it into a one-way data diode: every decoded radio
event is re-published onto the network so LAN users see the field
picture, while **nothing network-originated is ever auto-relayed to
the radio** — the slow RF channel cannot be flooded, and unlicensed
network users stay off the air. The bridge's own traffic (PLI,
markers, chat) still transmits normally, and the **Push to RF…**
dialog lets the operator hand-pick individual network items worth
airtime (kept 10 minutes, newest 20). Forwarded radio PLIs are
published without a chat endpoint so LAN users can't try to DM
through a one-way link, and radio-origin events are tagged so they
never echo back onto the air. See ICD §2.1.

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

## Settings (in-plugin pane)

Three tabs under an always-visible header (colored status line — green
RUNNING / red STOPPED — plus a build stamp while stopped, and the
Start/Stop button):

- **Audio Setup** — modem selection (OFDM default; OFDM / Mercury /
  AX.25-APRS, must match on all stations), TX audio stream (Alarm /
  Media / Ring / Notification — Alarm bypasses Samsung media DSP that
  distorts FSK tones), TX output / PTT (phone speaker for VOX/acoustic
  coupling, or USB + RTS PTT for a Digirig — v0.25), AFSK TX level %,
  TX dwell (TXDelay preamble), VOX leader duration, and Channel access
  (CSMA / Ring) with Guard / Skip / Max-turn fields and the ⓘ UV-5R /
  FT-65 defaults help dialog
- **Options** — ham callsign (ITA2-validated, max 8 chars; required),
  Send-to (Broadcast, or route markers/points direct to any station
  learned from the mesh), PLI rate limit, TX/RX enables,
  per-message-type transmit toggles, and the C2 Bridge one-way
  gateway with its Push to RF dialog (v0.24)
- **Decodes** — a full-panel page (◀ Back returns to settings) showing
  every received packet with timestamp, source, mode summary and
  payload size, above the live activity log

Every radio session also records a detailed debug log offered for
saving to Downloads on Stop (v0.21).

## Install

1. Install ATAK-CIV **5.7 or 5.8** and grant it microphone permission.
2. Install the plugin APK **for your ATAK line** from
   [`prebuilt/`](prebuilt/). The plugin-api must match exactly: a 5.7
   build will not load on 5.8 and vice versa. Two signing flavors are
   checked in for each line:
   - `*-civ-release.apk` — **production builds signed by the TAK
     Product Center** (third-party pipeline). Use these with the
     official ATAK-CIV releases from tak.gov or the Play Store.
   - `*-civ-debug.apk` — SDK-keystore builds. These only load on the
     SDK-signed `atak.apk` that ships inside each ATAK-CIV SDK zip
     (developer setups).
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
implementation, `MeshRouterTest` (mesh framing/routing vectors),
`RingMacTest` (Ring MAC turn rules under a scripted clock),
`BridgePolicyTest` (C2 Bridge diode/forward/stash rules), plus
`DemodFile` for running recorded audio through the demodulator offline.

## Documentation

- [`docs/HBC_ICD.html`](docs/HBC_ICD.html) ([PDF](docs/HBC_ICD.pdf)) —
  Interface Control Document v1.4: per-control GUI reference with
  annotated screenshots, wire formats, channel-access (CSMA/Ring MAC)
  specification, and log interpretation guides
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
