# Changelog

All notable changes to HBC Audio Plugin are documented here.

---

## [0.26] — Digirig Lite support + USB hot-plug audio recovery + USB TX level

Field findings from the 10-09 RF test (Digirig Mobile now keys
reliably): the Digirig Mobile **Lite** did not route/key, reconnecting
USB mid-session broke receive audio, and USB TX audio was faint.

- **Digirig Lite (CM108 GPIO PTT).** The Lite has no CP210x — its PTT
  is a GPIO pin on the CM108-family audio chip. `UsbPtt` now falls
  back to C-Media devices (VID 0x0D8C/0x0C76), claims their HID
  interface and keys GPIO3 with Direwolf-compatible SET_REPORT
  transfers (`{0, mask 0x04, data, 0}`). Log: `PTT: USB GPIO ready
  (…)`; the scan-miss message is now `PTT: no Digirig PTT device
  found (CP210x serial or CM108 GPIO)`.
- **USB hot-plug audio recovery.** Any USB attach/detach while the
  link runs now asks the active modem to re-open its AudioRecord
  (`rebindAudio()` — a fresh `RX audio source: …` line confirms the
  new path), so receive recovers without Stop/Start after a
  disconnect/reconnect. TX already re-routes per burst.
- **USB TX level fix.** In USB + RTS PTT mode the transmit tones now
  ride the MEDIA stream instead of Alarm (the Alarm trick only exists
  to dodge speaker DSP; alarm volume on USB sinks is low/fixed on
  some OEMs — the "faint TX" finding). Set Media volume to max; the
  log reminds you on engage. Speaker mode is unchanged (Alarm default
  still bypasses Samsung speaker DSP).
- PTT key-failure log generalized: `PTT: keying write failed (…)`.
- Test campaign: PTT-04 (Digirig Lite GPIO keying), PTT-05
  (mid-session unplug/replug recovers RX automatically).
- ICD figures rebuilt: thirteen annotated 0.26 screenshots (Tools
  entry, both Audio Setup halves, every selector expanded, Options
  incl. C2 Bridge / Push to RF, full-page Decodes, running header,
  live Activity Log, session-log save) replace the 0.20-era set.

---

## [0.25] — Selectable TX output: speaker/VOX or USB + RTS PTT (Digirig)

Field finding: a Digirig-cabled FT-65 never keys via VOX — the Digirig
is a hardware-PTT interface (its PTT transistor follows the CP210x
serial port's RTS line) and the FT-65's fixed VOX threshold cannot
trip on data-level audio arriving through the attenuated mic path.
The plugin now keys the radio itself.

- **"TX output / PTT" selector** (Audio Setup tab): *Speaker — VOX /
  acoustic* (default; TX audio is now explicitly pinned to the
  built-in speaker even when a USB interface is attached) or *USB +
  RTS PTT (Digirig)* (TX audio routed to the USB audio interface, PTT
  keyed electrically).
- **Digirig RTS keying** (new `UsbPtt`): CP210x (VID 0x10C4) driven by
  two raw USB vendor control transfers (IFC_ENABLE, SET_MHS) — no
  serial-driver library, so the TAK pipeline build stays
  dependency-free. RTS asserts 60 ms before each rendered burst (the
  lead silence covers radio TX settle) and releases right after the
  tail — one keying per batch, in all three modems via the new
  `PttKeyer` hook.
- With RTS PTT there is no VOX attack or hang: run VOX Lead 0 and
  Guard 300–500 ms (ⓘ dialog updated) — much faster Ring rotations.
- One-time Android USB permission: starting the link in USB mode
  raises "Allow ATAK to access the USB device?" (explicit
  PendingIntent — required since Android 14, where the implicit form
  is silently rejected and no dialog ever appears); tapping Allow
  engages RTS automatically via a grant receiver — no Stop/Start
  needed. Android's generic "Choose an app for the USB device" popup
  at plug-in time is unrelated and safe to dismiss. Activity Log
  lines: `PTT: USB RTS ready (…)`, `PTT: requesting USB access …`,
  `PTT: USB permission granted/denied`, `PTT: no CP210x …`,
  `PTT: RTS write failed (…)`. Session-log header records
  `out=speaker|usb-rts`.
- Hot-plug: attaching the Digirig while the link is running engages
  RTS PTT automatically (`PTT: Digirig attached — engaging`);
  detaching it logs `PTT: Digirig detached — RTS PTT off` and TX falls
  back to plain audio. (TX audio routing self-heals per burst; the RX
  microphone still binds at Start, so Stop/Start once if you want RX
  through the Digirig after a late plug-in.)
- The ⓘ radio-defaults dialog text was rebuilt — it had shipped with
  double-encoded punctuation since 0.22 and now renders cleanly.
- Docs: ICD v1.7 (§6.2 control row, §6.4 PTT log lines, §3.5 radio
  profile note, §8 compat bullet, Appendix tree) + regenerated PDF;
  README; test campaign PTT-01..03.

---

## [0.24] — C2 Bridge: one-way radio → network data diode

For command-post use: the phone running HBC can also sit on a normal
ATAK network (TAK server / mesh SA) and gateway the radio picture to
it — without ever letting network-volume traffic flood the RF channel.
Pure local behavior: no wire-format change, remote stations need no
update.

- **C2 Bridge checkbox** (Options tab, default off; live effect). The
  header shows `· BRIDGE` while active; the session-log header records
  `bridge=on/off`.
- **Radio → network (automatic).** Every decoded radio event — PLIs,
  markers, 911 alerts, shapes, CASEVAC, broadcast/room chat — is
  re-published onto the ATAK network outputs (external CoT dispatch).
  Mode 0 receipts and the operator's private DMs stay local. Forwarded
  PLIs are re-rendered without the chat endpoint, so network users see
  radio stations on the map but cannot DM a station that could never
  answer through a one-way bridge. Log: `Bridge: {summary} -> network`.
- **Network → radio (manual only).** The bridge still displays
  everything the network sends it, but nothing network-originated is
  auto-relayed to RF: only events authored on the bridge itself (self
  PLI, operator-placed items, own chats, receipts for radio DMs) still
  transmit automatically. Everything else is blocked
  (`Bridge: blocked {type} {uid} (network-origin) — use Push to RF`)
  and stashed — newest 20 items, kept 10 minutes.
- **Push to RF… dialog.** Multi-select list of the blocked items; the
  operator hand-picks what is worth airtime. Selected items are
  encoded through the normal HBC path (Send-to/DM routing, ring queue,
  batching) exactly once — the human is the rate limiter. 911 alerts
  pushed this way still get ring preemption.
- **Echo-loop protection.** Forwarded radio events are tagged
  (10-minute UID memory) and `HBC-*` / `GeoChat.HBC-*` UIDs are always
  dropped by the diode, so an event can never do RF → LAN → RF.
- **New `BridgePolicy.java`** — pure-Java forward/verdict/authorship
  rules + the push stash, JVM-tested by new
  `codec-test/BridgePolicyTest.java` (31 checks); all five suites
  green.
- **Docs.** ICD v1.6: new §2.1 (C2 Bridge data diode incl. Part 97
  note), §6.2 rows, §6.4 `Bridge:` log table, §8 compat bullet,
  glossary entry, Appendix A tree refreshed (RingMac/BridgePolicy/
  test files); PDF regenerated. README C2 Bridge section. Test
  campaign gains BRIDGE-01..06.

---

## [0.23] — Ring-aware ARQ: fix chat-storm TX queue buildup

Field test (10-07, 3 stations, OFDM + Ring) showed heavy chat building
~20-frame TX queues: the mesh ARQ's CSMA-era 5 s retry timer fired
while the original Direct was still waiting for its ring turn, so every
DM went to air up to 4×, 20 of 23 Directs were declared FAILED (only 3
ACKs ever completed), and ATAK receipts amplified each chat into ~10+
frames network-wide. No wire-format change — 0.22/0.23 interoperate.

- **TX-gated retry clock (MeshRouter).** A pending Direct's
  retry/failure timer no longer runs while its latest copy sits in the
  ring queue — `notifyTransmitted()` arms it when the frame actually
  reaches the modem (first copy and every retry copy). A pending whose
  copies never air fails after 180 s (stuck-queue safety) instead of
  retrying into the same queue.
- **Rotation-scaled retry pacing.** New `MeshRouter.RetryPolicy` hook;
  the plugin installs: Ring → `max(8 s, 1.25 × measured rotation)`
  capped at 60 s (+0–2 s jitter), CSMA → unchanged 5–7 s. RingMac now
  measures its rotation time (EMA of cycle-wrap intervals,
  `measuredCycleMs()`).
- **Unacked chat receipts.** Delivered/read receipts (Mode 0) now go
  out via `sendDirectUnacked()` — same wire format, routed the same,
  but no retries and no FAILED verdict; the recipient's mesh ACK is
  simply ignored. A lost checkmark no longer costs 4 on-air copies.
- **Stale-PLI replacement.** A queued self-PLI broadcast is removed
  when a fresher one is enqueued (ring mode) — positions don't stack.
- **Backlog warning.** `Ring: TX queue N frames (~X s to drain)` logs
  (at most every 30 s) once more than 6 frames wait, with a drain
  estimate from the measured rotation.
- **Batching follows Max turn.** Frames-per-turn now derive from the
  turn budget (`(Max turn − 500 ms) / per-frame burst`, 1–4): defaults
  unchanged (AFSK 4 / OFDM 2 / Mercury 1), but raising Max turn on all
  stations now drains queues faster instead of only lengthening the
  deadline. The modem batch cap follows the same number.
- **PLI auto-floor raised** to roster × 8 s (Mercury × 13 s) — keeps
  PLIs at ~25% of rotation airtime; companion per-group-size
  recommendation table exported with the 10-07 field logs.
- **Docs.** ICD v1.5: §3.5 ARQ-interaction note + capacity update,
  §4.9 rewritten (TX-gated clock, pacing, unacked receipts), §5.4,
  §6.2/6.4 new rows, §7 timing rows, §8 compat bullet, ARQ glossary
  entry; PDF regenerated. PROTOCOL.md: normative "ARQ interaction"
  subsection + batching/floor updates. README updated.
- **Tests.** MeshRouterTest: TX-gated retry, stuck-queue failure,
  RetryPolicy override, unacked-direct delivery/no-retry; RingMacTest:
  measured-cycle fallback + EMA. All four suites green.

---

## [0.22] — Ring MAC: deterministic multi-user channel access + TX batching

Channel-access rework for multi-user nets (up to ~20 stations) on VOX
HTs — reference radios Baofeng UV-5R and Yaesu FT-65, whose ~1.0–1.5 s
VOX hang is NOT adjustable (FT-65 VOX is ON/OFF only; UV-5R exposes
only sensitivity). No wire-format change: 0.21 and 0.22 stations
interoperate on the air.

- **Ring MAC (new "Channel access" selector, Audio Setup tab).**
  Decentralized rotation over the sorted station roster (self + mesh
  routes — the roster the mesh already learns passively). The schedule
  IS the sorted callsign list; no coordinator, no token packet, no new
  frame types. A station transmits only in its own turn; every station
  advances the turn locally on the FIRST of: early release (carrier
  heard, then clear for Guard ms), silent skip (owner never keyed
  within Skip ms), or deadline (Max turn + Guard — keeps the ring
  alive through hidden terminals and dead stations). Decoded mesh
  frames re-align everyone's turn pointer to the actual transmitter.
  Stations never key into a busy channel (interlock), so mixed
  Ring+CSMA nets degrade safely toward polite CSMA.
- **Ring settings:** Guard ms (default 1500 — covers the UV-5R/FT-65
  VOX tail; set it from measured hang + 150 ms), Skip ms (default
  1200 — covers VOX attack), Max turn ms (0 = auto per modem: AFSK
  6000, OFDM ~4400, Mercury ~6000). Settings echo in the session-log
  header (`mac=ring guard=1500ms skip=1200ms`). Recommended companion
  preset for these HTs: VOX Lead 250 ms.
- **TX burst batching (all modems, CSMA mode too).** The modem TX loop
  now drains up to AFSK 4 / OFDM 2 / Mercury 1 queued frames into ONE
  continuous keying — single lead silence, single VOX leader, single
  tail — so the radio's VOX hang is paid once per talk burst instead
  of once per frame. AFSK continuation frames ride the already-synced
  demod with a short ~20 ms flag run; OFDM/Mercury bursts concatenate
  with 100 ms re-arm gaps. Log: `TX batch: 3 frames (4120 ms burst)`.
- **PLI auto-floor (ring mode).** Self-PLI interval is floored at
  roster × 6 s (Mercury × 10 s) so 20 stations' position reports
  always fit one rotation.
- **Emergency preemption.** Mode 2 (911) alerts may transmit in the
  next inter-turn idle window after a 0–300 ms random offset instead
  of waiting a full rotation — the single allowed contention
  exception.
- **Instrumentation.** New Activity/session-log lines: `Ring: started
  …`, `Ring: TX turn (3/9, 2 frames)`, `Ring: joined rotation …`,
  `Ring: roster now N stations (…)`, `Ring: X dormant (3 silent
  turns)`, `Ring: emergency TX (preempt)`; session-log DBG lines for
  every sync/skip/early-release/deadline decision.
- **Docs.** ICD v1.4: §3.4 split into CSMA (legacy) + new §3.5 Ring
  MAC (rules, parameters, capacity math, UV-5R/FT-65 radio profile and
  guard-calibration procedure); §6/§7/§8 updated; PDF regenerated.
  PROTOCOL.md gains a normative "Channel Access (MAC)" chapter. Test
  campaign checklist (+CSV) gains a MAC section.
- **Tests.** New JVM suite `codec-test/RingMacTest` (rotation, early
  release, silent skip, deadline with hidden owner, busy interlock,
  emergency window, roster-at-wrap, dormancy); all existing suites
  unchanged and green.
- **Radio-defaults help.** The Channel access section carries an
  "ⓘ UV-5R / FT-65 defaults" link that opens a dialog with the
  recommended values for those radios (Ring, Guard 1500, Skip 1200,
  Max turn 0, VOX Lead 250, Dwell 500), why their VOX hang cannot be
  shortened, the Guard calibration procedure, and the hardwired-PTT
  alternative values.
- **Compact pane layout.** The four-row header (title, status,
  countdown, full-width Start button) is now one slim row — status +
  Next-PLI on the left, a compact Start/Stop button on the right — and
  the tab buttons shed Android's default 48 dp minimum height. The
  reclaimed space goes to the scrollable tab content, which was
  cramped on phone-sized screens.
- **Decodes is now a full-panel page.** Tapping the Decodes tab hides
  the header and tab bar so the Packet Decodes list and Live Activity
  Log get the whole pane; a ◀ Back button returns to the last
  settings tab.
- **Tools-menu icon fixed.** ATAK tints Tools-grid icons white, which
  turned the opaque badge into a solid white square. The Tools entry
  now uses a transparent-background tower glyph (`ic_tools`) that
  tints into a clean white graphic; the colored badge remains the app
  icon shown in TAK Package Mgmt and the plugin manager.
- **Radio-status toolbar icon removed.** The separate gray/green
  tap-to-toggle icon (and its "HBC Radio: RUNNING/STOPPED" tooltip
  entry) is gone — the radio is controlled entirely from the pane's
  Start/Stop button, leaving a single HBC Radio entry in the Tools
  menu.
- **OFDM is the default modem** and the selector now lists OFDM
  (Rattlegram), Mercury, AX.25/APRS in that order. Stored settings
  keep the historic encoding, so existing installs retain whatever
  modem they had selected.
- **Colored status line.** The header status shows green while the
  radio link is RUNNING and red while STOPPED.
- **Build stamp.** While stopped, the header's second line shows
  `Build {timestamp} · v{version}` so a stale install (same version
  name, old binary) is spotted at a glance.
- **Hardened pane binding.** Start/Stop and the tab/Back buttons are
  wired before any other UI setup, and the remaining wiring runs in a
  containment block — a failure there now shows a toast naming the
  exception and leaves the radio controls alive (previously a mid-bind
  exception left the whole pane unresponsive).

Also landed since 0.21 (previously tracked as unreleased):

- prebuilt/: added the TAK Product Center production-signed 0.21
  civ-release APK for ATAK 5.8.0 (third-party pipeline output from the
  10-02 submission; signer CN "TAK Product Center ATAK Untrusted
  Plugin Release"), replacing the 0.20 civ-release APK for that line.
  The 5.7.0 line stays at the 0.20 release APK — 0.21 was submitted
  for 5.8 only.

---

## [0.21] — Field-test fixes (10-02 campaign) + session debug log

Fixes for the three failures found in the 10-02-26 PLI test campaign:

- **Placed friendly/hostile/neutral markers now transmit.** Every
  `a-f-G/a-h-G/a-n-G` event used to be treated as a self position
  report, so placed markers of those affiliations were swallowed by the
  PLI rate limiter (and reset it) — only `a-u-G` markers ever went out.
  Self-PLI is now identified by the ATAK device/self-marker UID; placed
  markers of any affiliation take the spots path (Mode 6 extended
  marker first, Mode 1 spot-flag fallback — wire format unchanged).
  New `HbcEncoder.encode(xml, preferSpot)` overload carries the
  distinction; the PLI rate limiter applies only to the real self PLI.
- **AFSK1200 receive hardening.** The DSP chain verifies clean on the
  JVM (modulate→demodulate→parse round trip at 48/44.1/22.05 kHz), so
  the on-phone "TX fine, RX never decodes" failure points at device
  audio processing: input noise suppressors notch out steady 1200/2200
  Hz tones (noise-like OFDM survives, pure-tone AFSK dies). AFSK RX now
  prefers the UNPROCESSED source, and all three modems explicitly
  disable NoiseSuppressor/AGC/AEC on their record session (status line
  shows e.g. `RX audio source: UNPROCESSED (built-in mic), NS/AGC
  off`). AFSK TX drive raised 0.4 → 0.5 (parity with OFDM) and a new
  diagnostic logs "AFSK: heard a signal but decoded no frame" when
  carrier energy came and went without a decode.
- **AFSK minimum TX preamble.** Session logs from the field (the new
  diagnostic above) showed both stations *hearing* each AFSK burst but
  never decoding — with `TX Dwell` set to 0 ms the transmission carried
  only ~2 HDLC flags (~13 ms) of preamble, far too short for the
  receiver's clock recovery over an acoustic path (OFDM/Mercury carry
  their own long sync preambles and were unaffected). AFSK now enforces
  a 300 ms minimum flag preamble (the classic TNC TXDelay default)
  regardless of the dwell setting and logs when it clamps; dwell values
  above 300 ms behave exactly as before.
- **AFSK TX level setting.** New "AFSK TX level %" field in settings
  (1–100, default 50 = previous fixed drive) scales the rendered AFSK
  waveform before playback — AX.25 modem only, OFDM/Mercury unchanged.
  At close acoustic range full-volume FSK tones overdrive the speaker
  and/or clip the receiving mic (and with NS/AGC now disabled on RX,
  nothing tames clipped input), distorting the tones beyond decoding —
  the preamble-fix retest still decoded nothing while both sides logged
  "heard a signal but decoded no frame". Lower the level (e.g. 20–30%)
  when stations sit close together. The active level is echoed in the
  "Modem started" line and the session-log settings header. Note: the
  default Alarm TX stream is loudness-normalized by many OEMs, which
  can cancel digital level changes — switch "TX audio stream" to Media
  when tuning speaker-to-mic levels.
- **AFSK RX burst level meter.** Every heard burst now logs its
  measured input level, e.g. `AFSK RX burst 1040 ms: peak 99%, RMS 62%
  — CLIPPING: lower TX level/volume or move apart`, with a VERY LOW
  hint under 5% peak. This separates the three failure modes — input
  clipping, too-quiet input, and clean-but-undecodable tones — directly
  in the session log.
- **Duplicated contact rows.** When both stations also share a normal
  network link (WiFi/TAK server), the station appeared twice in the
  contacts list — once from ATAK's own network contact and once from
  the HBC-injected PLI endpoint. The plugin now suppresses the mesh
  endpoint when a live non-HBC contact with the same callsign already
  exists (radio-only operation is unchanged: endpoint still injected).
  GeoChat-derived entries for HBC stations (uids like
  `BAO.F.HBC.HBC-…`, created after a DM) no longer count as network
  contacts for this check.
- **DM replies to stations with long callsigns were dropped.** Field
  logs: a DM to KEYSTONE went out addressed to `KEYSTON` and the
  station logged "RX chat: DM for 'KEYSTON' — not this station,
  ignored". The Mode 1 name field truncates at 7 chars, so the
  receiving side's contact for an 8-char station was created under the
  truncated name and every reply inherited it. Three fixes, no wire
  change: (1) injected PLI contacts now display the full station
  callsign when the name field is just its truncated prefix; (2)
  DM/ack recipient matching tolerates field-width truncation (a ≥7-char
  recipient matches a local callsign it prefixes); (3) DMs and acks now
  translate the ATAK callsign to the station's ham callsign (learned
  from received traffic) so the mesh routes them Direct instead of
  falling back to "no route — sending as broadcast".
- **DM conversations no longer split across two windows / two
  contacts.** Injected chat used `remarks source="BAO.F.HBC.HBC-<CALL>"`,
  but ATAK's ChatMessageParser only strips the `BAO.F.ATAK.` /
  `BAO.F.WinTAK.` prefixes — anything else is taken verbatim as the
  sender uid. ATAK therefore fabricated a second contact
  (`BAO.F.HBC.HBC-<CALL>`, the duplicate row in the contacts list even
  with no IP network) and filed incoming DMs into its window, while
  replies went out from the real `HBC-<CALL>` contact's window — so a
  station could never "respond in the correct window". Chat CoT now
  uses the `BAO.F.ATAK.` prefix, making ATAK resolve the sender to the
  PLI-injected contact: one contact, one window, both directions.
  Receiving a PLI also removes any leftover `BAO.F.HBC.HBC-*` ghost
  contact created by earlier builds.

UI overhaul:

- **Three-tab layout.** The pane is reorganized into **Audio Setup**
  (modem selection, TX audio stream, AFSK TX level, TX dwell, VOX
  lead), **Options** (callsign, mesh Send-to, PLI rate, Transmit /
  Receive enables, message-type selection), and **Decodes** — which now
  shows two areas: the simple Packet Decodes list on top and the
  detailed Live Activity Log below it. Title, status line, Next-PLI
  countdown, and the Start/Stop Radio Link button moved to a persistent
  header visible from every tab.
- **New plugin icon.** The template's stock Android robot is replaced
  with a professional badge: dark graphite rounded square, white radio
  tower, green signal arcs (matching the radio-link toolbar green).
  Shown in ATAK's Tools menu, the plugin manager, and the toolbar.

New debugging feature:

- **Per-session debug log.** Every radio session records a detailed log
  (all Activity Log lines plus raw TX/RX frame hex, decoder output,
  reconstructed CoT XML, drop reasons, millisecond timestamps). On
  **Stop Radio Link** the plugin asks "Save HBC session log?" and, on
  Save, writes `HBC_<CALL>_<MODEM>_<yyyy-MM-dd_HH-mm-ss>.log` into the
  phone's Downloads folder (session start time in the name).

Also landed since 0.20 (previously tracked as unreleased):

- prebuilt/: added the TAK Product Center production-signed 0.20
  civ-release APKs for ATAK 5.7.0 and 5.8.0 (third-party pipeline
  output; signer CN "TAK Product Center ATAK Untrusted Plugin
  Release") alongside the SDK-signed civ-debug builds. The release
  APKs load on official ATAK-CIV distributions; the debug APKs only on
  the SDK's atak.apk.
- ICD v1.3: the User Interface Reference figures are now current 0.20
  release-build screenshots with red arrows pointing at the controls
  each caption describes, plus a new Figure 1 showing where the plugin
  lives in ATAK's Tools menu. Figure numbering and cross-references
  updated; PDF regenerated.

---

## [0.20] — Security hardening from the first pipeline scan

Both 0.19 submissions built and signed successfully on the TAK.gov
third-party pipeline; this release clears the Fortify SCA findings the
pipeline reported (1 High, 2 Low — no Criticals).

- `HbcEncoder.encode()` now rejects CoT XML containing a DOCTYPE and
  configures the DOM parser with `disallow-doctype-decl` and external
  general/parameter entity resolution disabled where the runtime
  supports the Xerces feature URIs (the JDK does; Android's factory
  rejects them, never resolves external entities, and is covered by the
  DOCTYPE reject). Clears XML External Entity Injection (High) and XML
  Entity Expansion Injection (Low) at HbcEncoder.java:126.
- `codec-test/SpotTest` no longer prints a stack trace on encode
  failure (System Information Leak, Low; JVM-only test harness).
- The pipeline's dependency-check flagged CVE-2025-54057 (Apache
  SkyWalking) against two *empty* jars — a build intermediate and
  takdev's staged lint AAR — hash-matched to empty SkyWalking artifacts
  on Maven Central. False positive; the plugin contains no SkyWalking.
  `.takdev/` is now untracked (the takdev plugin re-extracts it from its
  own jar on every build), which drops that AAR from future submission
  zips and with it one of the two false-positive surfaces.
- Prebuilt civ-debug APKs refreshed to 0.20 (ATAK 5.7.0 + 5.8.0).

---

## [0.19] — One source, one APK per ATAK line (5.7 + 5.8)

- ATAK_VERSION is now a build parameter (`-PATAK_VERSION=5.8.0`, or set it
  in local.properties; default 5.7.0) instead of a hardcoded constant.
  ATAK's loader demands an exact plugin-api match (verified against
  AtakPluginRegistry), so covering 5.7.x and 5.8.x means one build per
  line — now produced from the same commit. No plugin code changes.
- New `build-both.ps1`: extracts each ATAK-CIV SDK zip once (cached under
  %LOCALAPPDATA%\ATAK-SDKs), runs takdev in Offline DevKit mode
  (`-Psdk.path`, now documented in local.properties.example), stages the
  SDK debug keystore, builds the requested task per line, and collects
  the version-suffixed APKs.
- `rootProject.name` pinned to HBC-ATAK-Plugin so APK names and the
  proguard repackage id no longer depend on the checkout folder name
  (matters for pipeline checkouts).
- Prebuilt APKs refreshed: civ-debug builds for ATAK 5.7.0 and 5.8.0,
  manifest stamps verified (`com.atakmap.app@5.7.0.CIV` /
  `com.atakmap.app@5.8.0.CIV`).

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
