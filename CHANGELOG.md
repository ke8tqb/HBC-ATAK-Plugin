# Changelog

All notable changes to HBC Audio Plugin are documented here.

---

## [0.18] — Heard stations become ATAK chat contacts

- Received PLIs are now reconstructed with a `<contact endpoint>` and a
  `<__group>` detail. ATAK only registers a station as a messageable
  contact (chat DM list, send-to pickers) when its PLI carries an
  endpoint — previously heard stations plotted on the map but could not
  be selected for a direct chat until they had messaged you first. Now
  hearing a single PLI makes the station a selectable contact; outgoing
  chat to it is intercepted by the plugin and sent over HBC/mesh as
  before. (Mesh routing already learned the route from the same PLI, so
  chat DMs to the station go routed Direct immediately.)

---

## [0.17] — PLI countdown in the settings pane

- New live "Next PLI" line under the status text: counts down (m:ss,
  updated every second) until the next self-PLI broadcast is allowed,
  based on the user's "PLI min s" rate limit and the last PLI actually
  transmitted. Shows "ready (waiting for ATAK position update)" when the
  rate limit has elapsed, "disabled" when PLI or TX is off, and "—
  (radio off)" when the modem is stopped.

---

## [0.16] — Backlit radio icon when modem is on

- When the audio modem is running, the toolbar radio icon now sits on a
  soft light-green radial glow (backlit look) in addition to the
  electric-green glyph; the glyph is inset slightly so the halo is
  visible around it. Off state is unchanged (gray, no glow).

---

## [0.15] — Radio icon: tap toggles the modem

- ATAK core consumes toolbar long-presses to show the item tooltip, so
  the 0.14 press-and-hold toggle could never fire. A single tap on the
  radio icon now starts/stops the audio modem (with toast feedback);
  the plugin pane remains reachable via the main HBC toolbar icon.
- The tooltip shown on long-press is now state-aware: "HBC Radio:
  RUNNING (tap to stop)" / "HBC Radio: STOPPED (tap to start)".

---

## [0.14] — Radio icon fixes; PLI doubles as mesh announce

- The "Announce min" setting is removed: your own transmissions (PLI
  broadcasts especially) already announce the station to everyone in
  range via passive route learning, so every TX now defers the periodic
  mesh announce. A real announce only goes out automatically as a
  keepalive after 10 quiet minutes with no traffic.

- The radio status toolbar item now carries a fixed tool identifier, so
  a user-dragged placement (e.g. pinned to the top row) survives the
  icon swap when the modem starts/stops — previously the swap made the
  icon vanish from the customized position.
- Press-and-hold on the radio icon now starts/stops the audio modem
  (with toast feedback); a short tap still opens the plugin pane. If no
  callsign is configured yet, the hold opens settings instead.
- Icon is rasterized at 192 px instead of the vector's 24 dp intrinsic
  size, matching the sharpness of the stock toolbar icons.

---

## [0.13] — Passive route learning + radio status toolbar icon

- Passive route learning: hearing ANY mesh frame (PLI, marker, chat,
  ack, relay) immediately makes the transmitter a 0-hop neighbor and the
  originator routable via that transmitter — a station shows up in the
  "Send to" list as soon as anything is heard from it, no announce or
  prior chat exchange required. Existing routes are only replaced by
  strictly better paths; equal paths just refresh the 30-min TTL.
- New toolbar radio icon showing the audio-modem state: electric green
  while the radio link is running, gray when stopped. Driven by the same
  state as the Start/Stop Radio Link button, so the two cannot disagree;
  tapping it opens the plugin pane.

---

## [0.12] — Distance-vector mesh routing replaces AX.25 addressing

Implements "Adaptation of Uncoordinated Distance-Vector Routing for
Unencrypted Amateur Radio Networks" (Reticulum-style announce
propagation, no cryptography) above all three modems.

**Mesh networking (`MeshRouter`)**
- 13-byte common header on every frame (type / origin / transmitter /
  16-bit seq, callsigns bit-packed in the paper's ITA2 variant), plus
  Announce (+hop count), Broadcast (+payload), Direct (+dest +next hop
  +payload) and ACK (+dest +acked seq) packet types. Payloads carry
  compact HBC binary (deviation from the paper's ITA2 text, for airtime).
- Announce-based route discovery: periodic announces (configurable
  interval, default 10 min, 0 = off) flood the mesh; lowest hop count
  wins with first-heard tiebreak; routes expire after 30 min. [origin+seq]
  dedup cache (5 min) prevents loops and broadcast storms.
- Direct messages route hop-by-hop with next-hop rewrite at each router;
  the destination returns an end-to-end ACK; the sender retries with the
  same seq (5 s + jitter, max 3) and re-ACKs duplicate directs so a lost
  ACK does not fail delivery. Verified against the paper's Appendix A
  byte vectors and Appendix B 4-node trace (codec-test/MeshRouterTest;
  note: the paper's K1ABC hex has an internal typo — byte 03 is DF per
  its own bit math, not BF).

**Plugin behavior**
- GeoChat DMs (and their delivered/read receipts) automatically go as
  routed Direct messages to the recipient callsign; markers/points use
  the new "Send to" setting (Broadcast, or any station learned from
  announces) — "send this point to my buddy X" is a spinner pick.
  Direct falls back to broadcast (logged) when no route is known.
- AX.25 Destination and Digipeater Path settings removed; on AFSK the
  AX.25 UI frame remains only as PHY framing (fixed dest "HBC", source =
  ham callsign for Part 97 ID). OFDM and Mercury carry mesh frames in
  their existing payload framing.
- Wire format is NOT compatible with 0.11 and earlier — all stations
  must run 0.12+. On Mercury DATAC4, routed Directs are limited to
  ~29-byte HBC payloads (all standard messages fit).

---

## [0.11] — TAK third-party pipeline compliance

- NDK pinned to 25.1.8937393, the newest version pre-installed on the TAK
  third-party pipeline build machine (the pipeline only builds with its
  pre-installed NDKs; the previously declared 27.0.12077973 is not among
  them). Both civDebug and civRelease verified to compile with it.
- Verified the remaining pipeline source-archive requirements already
  hold: Gradle build at the repo root with assembleCivRelease defined,
  atak-gradle-takdev used for all ATAK SDK references, plugin-specific
  proguard repackage descriptor (atakplugin.HBC-ATAK-Plugin), and the
  com.atakmap.app.component discovery activity in AndroidManifest.xml.

---

## [0.10] — Mercury HF modem, CSMA, protocol sync with HBC-Protocol v1.6

**Collision avoidance (all modems)**
- New shared CSMA carrier sense (`CsmaSense`): RX audio energy is tracked
  against an adaptive noise floor; the channel is considered busy while
  incoming audio exceeds 4x the floor (held 400 ms past the last loud
  chunk to bridge in-signal gaps).
- p-persistent transmit backoff: before every TX the modem waits for a
  clear channel, then a random 150-550 ms during which the channel must
  stay clear — unsynchronizing stations that queued traffic while a
  third station was transmitting. After 8 s (15 s on Mercury HF) the
  frame is sent regardless so traffic cannot be starved.
- Replaces the previous AFSK-only DCD polling (which rarely asserted and
  had no backoff, so all three modems could transmit over a signal in
  progress); the AFSK demodulator's DCD is still used as an additional
  carrier-sense input on that modem.

**Modems**
- Third selectable modem: **Mercury HF** — the physical layer of the
  Mercury HF modem (https://mercury.hermes.radio/, Rhizomatica), i.e. the
  FreeDV DATAC raw-data OFDM waveform from codec2 (David Rowe et al.),
  vendored at `app/src/main/cpp/mercury/` and compiled as native library
  `libhbcmercury` with a JNI bridge (`MercuryNative`/`MercuryModem`).
- Uses DATAC4 (~87 bps, works below 0 dB SNR on SSB/HF); bursts use
  Mercury's exact layout: preamble → single data frame with CRC-16 in the
  last two bytes → postamble, at 8000 Hz mono. HBC payloads are framed
  inside the 54-byte DATAC4 frame as `len, 'H', payload, zero-pad`.
- Note: this speaks Mercury's waveform/frame/CRC layout, not Mercury's
  ARQ/broadcast data-link protocol — desktop Mercury will demodulate the
  frames but will not route them to its TCP data interface.

**Protocol**
- HBC v1.4: Mode 3 (GeoChat) gains a 2-bit destination-kind field —
  All Chat Rooms (unchanged default), Named Room, or Direct Message.
  Classified from `<__chat>`/`<chatgrp>`: no chatroom or "All Chat Rooms"
  broadcasts as before; 3+ `uidN` members on `<chatgrp>` is a Named Room;
  otherwise (exactly `uid0`+`uid1`) is a Direct Message, addressed by the
  recipient's callsign and resolved to the same deterministic
  `HBC-{CALLSIGN}` UID used elsewhere.
- HBC v1.5: Mode 1 (PLI/Spot) gains a 2-bit Affiliation field (Friendly
  `a-f-G` / Hostile `a-h-G` / Neutral `a-n-G` / Unknown `a-u-G`). Fixes a
  bug where decode always reconstructed PLI as Friendly and Spot as
  Unknown regardless of what was actually transmitted — confirmed against
  a real ATAK capture that included hostile/neutral-affiliated markers.
- Both changes are wire-incompatible with older Mode 1/Mode 3 frames from
  this plugin; byte-for-byte cross-validated against the Python reference
  implementation (`codec-test/HbcCodecTest.java`).

---

## [0.9] — 2026-08-25 — dual-modem rework

Ground-up rework of the audio layer and codec (version restarts at 0.9;
supersedes 1.0.0).

**Modems**
- Selectable modem: AFSK1200/AX.25 (javAX25-based; digipeater-path capable,
  packet-radio compatible) or OFDM COFDMTV (rattlegram `short`-branch
  protocol via bundled native library, arm64/armv7/x86)
- TX rendered fully in advance with leading/trailing silence pads;
  MODE_STATIC playback at the device's native output rate
- Selectable TX audio stream (Alarm default — bypasses Samsung media DSP
  that distorted FSK), reduced drive to avoid speaker-protection limiting
- RX via VOICE_RECOGNITION/UNPROCESSED mic sources; urgent-audio thread
  priority on both paths
- Automatic exclusive routing to USB audio interfaces (e.g. Digirig)

**Protocol**
- HBC v1.3 with Mode 6 Extended Marker: full CoT type, 2525C/spot-map/
  custom-iconset icon reference, color tint; byte-for-byte cross-validated
  against the Python reference implementation

**UI / fixes**
- Tabbed pane: Settings + dedicated Decodes log (timestamp, source, mode,
  payload size, counter)
- Self-heard transmissions no longer plot own PLI (AX.25 source + ATAK
  callsign filtering); RX dedup window
- PluginSpinner instead of stock Spinner (fixed dropdown crash)
- License change: GPL v2+ (bundles javAX25); THIRD-PARTY.md added

---

## [1.0.0] — 2026-07-18

### Initial release

**Transmit**
- Auto-TX: intercepts all outgoing ATAK CoT events via `PreSendProcessor`
- Manual TX: "SEND MY POSITION NOW" button in the plugin settings pane reads
  the ATAK self-marker and transmits the current GPS position on demand
- Marker relay TX: tap any map item to reveal a floating green "HBC TX" button;
  one more tap transmits that marker's position — two taps total

**Receive**
- Continuous `AudioRecord` thread with amplitude squelch (RMS threshold)
- On signal detection, accumulates audio until 1 second of silence, then
  attempts OFDM decode
- Decoded CoT is injected via `CotMapComponent.getInternalDispatcher().dispatch()`
- Self-echo suppression: discards packets whose callsign or UID matches the
  operator's own callsign (checks both plugin pref and ATAK self-marker)
- Truncation-aware comparison handles callsigns longer than the 7-char HBC
  name field limit

**Audio device selection**
- `AudioManager.getDevices()` enumerates all attached input/output devices
- `AudioDeviceCallback` auto-refreshes device spinners when USB devices connect
  or disconnect (DigiRig plug/unplug detected live)
- `AudioRecord.setPreferredDevice()` and `AudioTrack.setPreferredDevice()` route
  audio to the selected device
- Changing the input device while RX is active restarts the recorder immediately

**Protocol**
- HBC Mode 1: PLI (friendly ground track `a-f-G`) and Spot marker
- HBC Mode 2: 911 / Emergency alert
- Callsign embedded in every OFDM packet header (FCC Part 97 station ID)
- Deterministic UID derivation: `HBC-{CALLSIGN}`, `HBC-{CALLSIGN}-911`

**OFDM modem**
- aicodix modem, short branch: 8000 Hz, 16-bit, mono
- oper_mode 16 (680 data bits = 85 bytes) — covers all HBC frame sizes
- 1-second lead-in silence before signal for PTT/VOX hardware
- Configurable PTT delay (0–∞ ms) in plugin settings
- JNI bridge (C++) wraps aicodix Encoder/Decoder for in-memory I/O

**ATAK integration**
- ATAK 5.7.0 CIV, IPlugin API (`gov.tak.api.plugin.IPlugin`)
- Toolbar button with radio antenna icon (transparent, ATAK-tintable)
- Plugin icon (app/plugin manager): dark background, ATAK green antenna
- Settings pane via `IHostUIService` + `Pane`/`PaneBuilder`
- `PluginNativeLoader` for security-compliant JNI loading

**Build**
- Android Gradle Plugin 8.13.0, Gradle 8.14.3, compileSdk 36, Java 17
- NDK + CMake; aicodix DSP/code headers via CMake FetchContent
- `abiFilters`: arm64-v8a, armeabi-v7a
- Debug keystore auto-generated by atak-gradle-takdev (no manual setup)
