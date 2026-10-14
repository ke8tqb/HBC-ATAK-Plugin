# Radio Presets — Recommended Settings for 30 Radios

Recommended **HBC plugin settings** and **radio-side setup** for 20
popular VHF/UHF radios (16 handhelds, 4 mobiles) and 10 HF radios.

How to use this document:

1. Find your radio below (summary table first, details after).
2. Apply its **profile** (A/B/C baseline settings, defined next) in the
   plugin's Audio Setup tab.
3. Apply the per-radio **overrides and radio-side settings** from its
   entry.

> These are field-tested starting points, not laws. Two rules are
> absolute: **every station on a channel must run the same modem and the
> same channel-access mode/timing**, and on VOX radios **Guard must
> exceed the slowest radio's VOX hang** (calibration procedure in the
> ICD §3.6). Menu numbers can shift between firmware revisions — the
> setting names are what matter.

---

## The three baseline profiles

### Profile A — VOX handheld (acoustic coupling or speaker-mic cable)

The zero-hardware path: phone speaker → radio mic, radio keys itself by
VOX.

| Plugin setting (Audio Setup) | Value |
|---|---|
| Modem | **OFDM (Rattlegram)** — best survivor of phone/radio audio paths |
| TX output / PTT | **Speaker — VOX / acoustic** |
| TX audio stream | **Alarm** (bypasses phone media DSP; set Alarm volume to max) |
| VOX Lead ms | **250** (covers VOX attack clipping the first tones) |
| Channel access | Ring for 3+ stations, CSMA for 2 |
| Guard ms | **1500** (covers a fixed ~1–1.5 s VOX hang) |
| Skip ms | **1200** |
| Max turn ms | **0** (automatic per modem) |

Radio side: VOX ON at medium sensitivity (raise it until data audio
keys reliably, no higher), squelch just above the noise, TX time-out
(TOT) ≥ 60 s, phone speaker 2–5 cm from the radio mic.

### Profile B — Digirig-keyed radio (hard PTT, no VOX)

A Digirig Mobile (CP210x RTS) or Digirig Lite (CM108 GPIO) between
phone and radio. The plugin keys PTT electrically — no VOX attack or
hang, so turns get much shorter.

| Plugin setting (Audio Setup) | Value |
|---|---|
| Modem | **OFDM** (VHF/UHF) |
| TX output / PTT | **USB + RTS PTT (Digirig)** — approve the Android USB prompt |
| TX audio stream | (TX rides the **Media** stream on USB — set Media volume to max) |
| VOX Lead ms | **0** |
| Guard ms | **400** |
| Skip ms | **1200** |
| Max turn ms | **0** (automatic) |

Radio side: VOX OFF, squelch just above noise, TOT ≥ 60 s, mic gain
default; start the Digirig attenuation/levels low and raise until
decodes are solid (RX audio too hot is the #1 failure).

### Profile C — HF SSB (Mercury or PSK31)

| Plugin setting (Audio Setup) | Value |
|---|---|
| Modem | **Mercury HF** (DATAC4, ~87 bps) — or **PSK31** for extreme weak-signal work (very slow: PLI ≈ 16 s of air) |
| TX output / PTT | **USB + RTS PTT** when the radio/interface gives the plugin a CP210x serial (see entries); otherwise the radio's data-VOX |
| VOX Lead ms | **0** (hard PTT) |
| Guard ms | **500** |
| Skip ms | **1200** |
| Max turn ms | **0** (automatic; PSK31 sizes its own ~94 s turns) |
| PLI min s | **120** on HF nets (60 default is busy for DATAC4), with the Ring floor handling the rest |

Radio side, always: **USB/upper-sideband data mode** (USB-D / DATA-U /
PKT), **speech processor/compressor OFF**, mic EQ off for the data
input, power ~25–50 % with **ALC barely moving**, RF gain normal, AGC
fast/off per taste, filter wide (≥ 2.4 kHz; the Mercury/OFDM carriers
sit near 1500 Hz). **On PSK31, disable or maximize the radio's TX
time-out timer — bursts can exceed 60 s.**

> Mercury note: routed Direct messages carry ~29 B of HBC payload on
> DATAC4 (broadcasts ~39 B) — every standard message type fits.

---

## Summary table

| # | Radio | Band | Profile | PTT path |
|---|---|---|---|---|
| 1 | Baofeng UV-5R | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 2 | Baofeng BF-F8HP | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 3 | Baofeng UV-82HP | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 4 | Baofeng AR-5RM | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 5 | Quansheng UV-K5(8) | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 6 | TIDRADIO TD-H3 | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 7 | Yaesu FT-65R | VHF/UHF HT | A (or B) | VOX / Digirig cable |
| 8 | Yaesu FT-60R | VHF/UHF HT | **B** | Digirig (no VOX) |
| 9 | Yaesu FT-70DR | VHF/UHF HT | A (or B) | VOX / Digirig cable |
| 10 | Yaesu VX-6R | VHF/UHF HT | A | VOX (adapter needed for cable) |
| 11 | Icom ID-52A | VHF/UHF HT | **B** | Digirig (treat as no VOX) |
| 12 | Icom IC-V86 | VHF HT | **B** | Digirig Icom 2-pin (no VOX) |
| 13 | Kenwood TH-D75A | VHF/UHF HT | **B** | Digirig K-style 2-pin (no VOX) |
| 14 | Anytone AT-D878UVII+ | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 15 | Wouxun KG-UV9PX | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 16 | TYT TH-UV88 | VHF/UHF HT | A (or B) | VOX / Digirig K1 |
| 17 | Kenwood TM-V71A | VHF/UHF mobile | **B** | Digirig → 6-pin mini-DIN |
| 18 | Icom IC-2730A | VHF/UHF mobile | **B** | Interface → mic jack |
| 19 | Yaesu FT-2980R | VHF mobile | **B** | Interface → mic jack |
| 20 | Anytone AT-D578UVIII+ | VHF/UHF mobile | **B** | Interface → mic jack |
| 21 | Icom IC-7300 | HF | **C** | Direct USB (codec + CP210x RTS) |
| 22 | Icom IC-705 | HF–UHF | **C** | Direct USB (codec + CP210x RTS) |
| 23 | Icom IC-7100 | HF–UHF | **C** | Direct USB (codec + CP210x RTS) |
| 24 | Yaesu FT-991A | HF–UHF | **C** | Direct USB (codec + SiLabs RTS) |
| 25 | Yaesu FT-891 | HF | **C** | Digirig → rear DATA jack |
| 26 | Yaesu FT-710 | HF | **C** | Direct USB (codec + SiLabs RTS) |
| 27 | Yaesu FT-818ND | HF–UHF | **C** | Digirig → 6-pin DATA jack |
| 28 | Xiegu G90 | HF | **C** | Digirig → 8-pin ACC |
| 29 | Xiegu X6100 | HF | **C** | USB audio (fw-dependent) or Digirig ACC |
| 30 | Kenwood TS-590SG | HF | **C** | Direct USB (codec + SiLabs RTS) |

**PTT-path legend:** "Digirig K1" = Digirig with the Kenwood 2-pin
(K1) HT cable. "Direct USB" = phone USB-C OTG straight into the
radio's own USB port: the radio's built-in sound card carries audio
automatically, and when its internal serial chip is a Silicon Labs
CP210x the plugin's **USB + RTS PTT** keys it directly (set the
radio's "USB SEND"/"PTT SELECT"-style menu to **RTS**). If your
radio's serial chip is not a CP210x, fall back to the radio's data
VOX or a Digirig.

---

## VHF/UHF handhelds

### 1. Baofeng UV-5R  *(reference radio)*
- **Plugin:** Profile A as-is (these defaults were calibrated on this
  radio). Digirig K1 cable → Profile B.
- **Radio:** `VOX` (menu 4) = 5–7; `SQL` (menu 0) = 3–5; `TOT`
  (menu 9) ≥ 60 s; `ROGER` OFF; `BCL` OFF; volume ~50–70 % toward the
  phone mic. VOX hang is fixed ~1–1.5 s — that is what Guard 1500
  covers; do not reduce Guard below the measured hang.
- **Notes:** dual-watch (`TDR`) OFF while running data — a reply on the
  other watch slot garbles RX.

### 2. Baofeng BF-F8HP
- **Plugin:** Profile A (or B with a K1 Digirig).
- **Radio:** same menu family as the UV-5R: `VOX` menu 4, `SQL` menu 0,
  `TOT` menu 9 ≥ 60 s, `ROGER`/`BCL` OFF. Use LOW/MID power on the
  bench — 8 W next to the phone can induce RF into the audio path.
- **Notes:** identical data behavior to the UV-5R; same Guard/Skip.

### 3. Baofeng UV-82HP
- **Plugin:** Profile A (or B).
- **Radio:** `VOX` menu 4 = 5–7 (VOX keys the *upper* PTT band — make
  sure the data channel is the A/upper VFO), `SQL` 3–5, `TOT` ≥ 60 s,
  `ROGER` OFF.
- **Notes:** the dual-PTT button quirk only matters for manual keying.

### 4. Baofeng AR-5RM
- **Plugin:** Profile A (or B).
- **Radio:** VOX in the menu (level 1–10) = 5; squelch 3–5; TOT
  ≥ 60 s; roger beep OFF; disable the FM-broadcast radio while running.
- **Notes:** the USB-C port is charge-only — audio still goes through
  the K1 jack. Spectrum/scan features off during data.

### 5. Quansheng UV-K5(8)
- **Plugin:** Profile A, but start **VOX Lead 300** — the stock VOX
  attack clips more than the Baofengs. Digirig K1 → Profile B.
- **Radio:** `VOX` = 5; `SQL` 3–4; `TOT` ≥ 60 s; roger/beep OFF. On
  modded firmware (IJV/egzumer), keep AGC defaults for data.
- **Notes:** very hot RX audio out — keep volume toward the phone low
  (30–40 %) to avoid clipping the phone mic.

### 6. TIDRADIO TD-H3
- **Plugin:** Profile A (or B with a K1 Digirig).
- **Radio:** VOX on (level ~5), squelch 3–5, TOT ≥ 60 s, roger beep
  and voice prompts OFF.
- **Notes:** ships in "ham/normal/GMRS" modes — any mode works for
  data; keep the Bluetooth programming link off while operating.

### 7. Yaesu FT-65R  *(reference radio)*
- **Plugin:** Profile A as-is (the second calibration radio). For
  cabled use: Digirig with the FT-65's 4-conductor 3.5 mm jack cable →
  Profile B.
- **Radio:** `VOX` (Set Mode item 35) is **ON/OFF only** — no
  sensitivity adjustment; it keys reliably from speaker audio with the
  phone close. SQL in Set Mode; `TOT` ≥ 60 s (item "TOT"); beep OFF.
- **Notes:** VOX hang ~1–1.5 s fixed → Guard 1500. The ⓘ dialog in the
  plugin prints this radio's exact numbers.

### 8. Yaesu FT-60R
- **Plugin:** **Profile B** — the FT-60R has **no VOX**; use a Digirig
  with the 4-conductor 3.5 mm cable (or hold PTT by hand for tests).
- **Radio:** VOX n/a; `TOT` (Set Mode) ≥ 60 s; squelch knob just past
  noise; beeps OFF.
- **Notes:** a classic, but strictly a cabled/manual-PTT data radio.

### 9. Yaesu FT-70DR
- **Plugin:** Profile A (VOX exists, adjustable) or B with the
  4-conductor cable.
- **Radio:** set VOX in the Set Mode menu (ON, mid sensitivity); force
  **FM (analog) mode** on the data channel — C4FM/AMS must not engage
  (AMS OFF); `TOT` ≥ 60 s; beep OFF.
- **Notes:** if AMS flips to digital mid-net, every burst is lost —
  pin the channel to analog.

### 10. Yaesu VX-6R
- **Plugin:** Profile A. (Cabled use needs the CT-91/waterproof-jack
  adapter to split SP/MIC — most owners stay acoustic.)
- **Radio:** `VOX` in the menu (enable, mid gain); `TOT` ≥ 60 s
  ("TOT" menu); squelch just past noise; beep OFF.
- **Notes:** the screw-down jack is waterproof — hand-tighten after
  inserting an adapter or the mic line floats.

### 11. Icom ID-52A
- **Plugin:** **Profile B** (treat as no usable VOX for data) with an
  Icom HT cable on the speaker-mic jack; acoustic coupling + manual
  PTT works for short tests.
- **Radio:** run plain **FM**, not D-STAR (DV OFF); squelch just past
  noise; `TOT` ≥ 60 s; beeps OFF.
- **Notes:** keep the GPS beacon off — unneeded airtime on the data
  channel.

### 12. Icom IC-V86
- **Plugin:** **Profile B** — no VOX; Digirig with the Icom 2-pin
  (3.5 mm + 2.5 mm) cable, or acoustic + manual PTT.
- **Radio:** squelch just past noise; `TOT` ≥ 60 s; beep OFF; MID/LOW
  power on the bench.
- **Notes:** simple, loud 2 m radio — excellent acoustic-RX performer;
  only TX needs the cable or a thumb.

### 13. Kenwood TH-D75A
- **Plugin:** **Profile B** — no VOX; Digirig with the K-style 2-pin
  cable (the D75's USB-C is programming/TNC, not a sound card for the
  phone).
- **Radio:** FM mode (not DV); squelch just past noise; TOT ≥ 60 s;
  turn the **built-in TNC/APRS OFF** so it never keys over HBC bursts.
- **Notes:** speaker output is strong — moderate volume into the
  Digirig (attenuation on).

### 14. Anytone AT-D878UVII Plus
- **Plugin:** Profile A (VOX available) or B with a K1 Digirig —
  prefer B; the DMR-style VOX attack is slow even at max.
- **Radio:** **analog channel** (not DMR); VOX level 3/3 + VOX delay
  minimum if going acoustic; squelch 3–5; TOT ≥ 60 s; roger OFF;
  APRS/GPS beaconing OFF on the data channel.
- **Notes:** if acoustic VOX clips the first symbols, raise plugin VOX
  Lead to 400 before touching anything else.

### 15. Wouxun KG-UV9PX
- **Plugin:** Profile A (or B with a K1 Digirig).
- **Radio:** `VOX` menu ON, mid level; squelch 3–5; TOT ≥ 60 s;
  roger/voice prompts OFF.
- **Notes:** keep the second (dual-watch) band muted or disabled while
  passing data.

### 16. TYT TH-UV88
- **Plugin:** Profile A (or B).
- **Radio:** `VOX` menu ON (level ~5), squelch 3–5, TOT ≥ 60 s, beeps
  OFF.
- **Notes:** behavior tracks the Baofeng family closely; UV-5R values
  transfer as-is.

## VHF/UHF mobiles

### 17. Kenwood TM-V71A
- **Plugin:** **Profile B** — Digirig with the TM-V71A 6-pin mini-DIN
  data cable (PTT is a dedicated pin; RTS keys it cleanly).
- **Radio:** set the data port speed to **1200** (PKT.SPD menu) — that
  selects the correct (de-emphasized mic-level) audio path for all
  HBC modems; squelch just past noise; TOT ≥ 60 s (PSK31 excepted —
  see the Profile C warning).
- **Notes:** audio levels on the 6-pin port are fixed and clean — this
  is one of the easiest mobiles to interface.

### 18. Icom IC-2730A
- **Plugin:** **Profile B** — no data port: use a mic-jack (RJ45)
  interface cable to the Digirig, with RX audio from the 3.5 mm
  external-speaker jack.
- **Radio:** squelch just past noise; TOT ≥ 60 s; beeps OFF; LOW/MID
  power on the bench.
- **Notes:** mic-jack interfacing carries mic gain — keep Digirig TX
  drive low and bring it up until the far station decodes cleanly with
  minimal deviation.

### 19. Yaesu FT-2980R
- **Plugin:** **Profile B** — mic-jack (RJ45) interface + external
  speaker jack for RX, keyed by the Digirig.
- **Radio:** squelch just past noise; TOT ≥ 60 s ("TOT" menu); beeps
  OFF. 80 W is pointless for data across town — run LOW1/LOW2.
- **Notes:** mono-band 2 m; a solid fixed-station data radio once
  cabled.

### 20. Anytone AT-D578UVIII Plus
- **Plugin:** **Profile B** via a mic-jack interface (preferred). Its
  menu VOX exists but, as on the 878, the attack is slow for data.
- **Radio:** **analog channel**; squelch 3–5; TOT ≥ 60 s; roger OFF;
  disable APRS auto-TX on the data channel.
- **Notes:** if you must run VOX, plugin VOX Lead 400 and test
  carefully for clipped preambles.

## HF radios

### 21. Icom IC-7300
- **Plugin:** Profile C. Phone USB-C (OTG) → radio USB-B directly: the
  7300's built-in sound card carries both directions, and its internal
  **Silicon Labs CP210x** serial lets **USB + RTS PTT** key the radio.
- **Radio:** `SET > Connectors`: **USB SEND = RTS**, DATA MOD = USB,
  USB AF output ~50 %; mode **USB-D**; speech comp OFF; power so ALC
  barely moves; TOT long/off for PSK31.
- **Notes:** the single most-popular HF+HBC combination — no Digirig
  needed at all.

### 22. Icom IC-705
- **Plugin:** Profile C, direct USB exactly like the 7300 (same codec +
  CP210x; **USB SEND = RTS**).
- **Radio:** mode USB-D, DATA MOD = USB, comp OFF; its 5–10 W is ample
  for DATAC4/PSK31. Battery note: USB OTG draws from the phone — carry
  a powered hub for long field sessions.
- **Notes:** also covers VHF/UHF SSB/FM — the same setup works there.

### 23. Icom IC-7100
- **Plugin:** Profile C, direct USB (codec + CP210x; set **USB SEND =
  RTS** in the connectors menu).
- **Radio:** mode USB-D, DATA MOD = USB (ACC/USB setting), comp OFF,
  ALC barely moving.
- **Notes:** fan runs on TX — normal for long Mercury/PSK31 bursts;
  keep duty cycle in mind and power at ~30 %.

### 24. Yaesu FT-991A
- **Plugin:** Profile C, direct USB: built-in codec + Silicon Labs
  dual COM; set the radio's **PTT SELECT = RTS** (and CAT RTS flow
  control OFF).
- **Radio:** mode **DATA-USB**; menu `MODE DATA` input = USB; DT GAIN
  set for barely-moving ALC; speech processor OFF; TOT per Profile C.
- **Notes:** if TX won't key, it is almost always the PTT SELECT menu
  still on DAKY.

### 25. Yaesu FT-891
- **Plugin:** Profile C with a **Digirig → rear 6-pin DATA jack**
  cable (the 891's USB carries CAT only, no audio codec).
- **Radio:** mode **DATA-USB**; menus: DATA PORT SELECT = DATA, DATA
  PTT SELECT = DAKY (the Digirig keys the DATA jack's PTT pin), DATA
  OUT LEVEL ~50; comp OFF.
- **Notes:** superb portable match for Mercury; levels via the Digirig
  pots/Media volume.

### 26. Yaesu FT-710
- **Plugin:** Profile C, direct USB (built-in codec + SiLabs serial;
  **PTT SELECT = RTS**).
- **Radio:** mode DATA-U; RPORT/DATA gain for barely-moving ALC; AESS
  speaker irrelevant for data; comp OFF.
- **Notes:** current-generation sibling of the 7300 workflow.

### 27. Yaesu FT-818ND
- **Plugin:** Profile C with a **Digirig → 6-pin mini-DIN DATA jack**
  cable (no USB on the radio).
- **Radio:** mode **DIG** with `DIG MODE = USER-U` (generic USB data);
  comp n/a; 6 W is fine for DATAC4/PSK31 — run full power with ALC
  watched.
- **Notes:** the DATA jack expects/returns fixed low-level audio — set
  phone Media volume max and trim with the Digirig.

### 28. Xiegu G90
- **Plugin:** Profile C with a **Digirig → 8-pin mini-DIN ACC** cable
  (no USB audio on the radio).
- **Radio:** mode USB; set input = LINE (ACC); AUX audio levels mid;
  comp OFF; its 20 W — run 5–10 W for data duty cycle.
- **Notes:** the ACC port carries PTT — the Digirig RTS keys it; no
  VOX needed.

### 29. Xiegu X6100
- **Plugin:** Profile C. Recent firmware exposes the radio as a USB
  sound card — try direct USB first (plugin auto-routes audio; key via
  the radio's data VOX if its serial is not a CP210x). Otherwise:
  Digirig on the 3.5 mm ACC jack.
- **Radio:** mode USB; mic/line select = LINE for the ACC path; comp
  OFF; 5–8 W with ALC barely moving.
- **Notes:** firmware moves fast on this radio — verify audio routing
  after every update.

### 30. Kenwood TS-590SG
- **Plugin:** Profile C, direct USB: built-in codec + Silicon Labs
  virtual COM — enable the radio's **PKT/DATA PTT via RTS** menu so
  USB + RTS PTT keys it (else use a Digirig on ACC2).
- **Radio:** mode USB + DATA (USB audio input selected in menu); TX
  EQ/processor OFF for data; power for barely-moving ALC.
- **Notes:** a shack classic; the USB path has been rock-solid for
  soundcard modes for a decade.

---

## Universal checklist (any radio)

1. Same **modem**, same **channel access**, same **Guard/Skip** on
   every station.
2. VOX radios: Guard must beat the slowest radio's VOX hang
   (measure it — ICD §3.6 calibration procedure).
3. Roger beeps, voice prompts, dual-watch, auto-beacons: **OFF** on the
   data channel.
4. TOT ≥ 60 s for AFSK/OFDM/Mercury; **off or maximum for PSK31**.
5. Acoustic coupling: Alarm volume max, speaker 2–5 cm from mic, quiet
   room. USB: Media volume max, Digirig levels low-and-raise.
6. Start on LOW power on the bench; RF into the audio path mimics a
   broken modem.
7. Verify with two stations before the net: a PLI each way, a chat DM
   each way (watch the delivered/read checkmarks), then a 911 test —
   that exercises broadcast, direct, ACK and preemption paths.

*HBC Radio — KE8TQB — github.com/ke8tqb/HBC-ATAK-Plugin*
