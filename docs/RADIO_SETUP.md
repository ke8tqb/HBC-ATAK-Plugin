# Radio Setup Guide

This guide covers connecting a ham radio to an Android device for use with the
HBC Audio plugin.

---

## Overview

The plugin sends and receives audio tones through the device's audio system.
You need to route:
- **TX path**: Android audio output → radio microphone input
- **RX path**: Radio speaker/audio output → Android audio input

The radio must be set to transmit when audio is present (VOX) or you must
key the PTT manually before the signal starts.

---

## Connection Methods

### Option 1 — DigiRig Mobile (recommended)

The [DigiRig Mobile](https://digirig.net) is a USB audio interface designed
specifically for radio interfacing. It handles audio level matching and
isolation between the radio and the Android device.

**Setup:**
1. Connect the DigiRig to your radio using the appropriate cable for your
   radio model (DigiRig offers cables for many common radios)
2. Plug the DigiRig into the Android device via USB-C
3. Open the HBC Audio plugin panel — the DigiRig appears within ~1 second
   in both the Audio Input and Audio Output spinners
4. Select it in both spinners
5. Set PTT Delay to 300–500 ms (DigiRig/VOX needs time to key)

**Advantages:** Clean audio, proper level matching, electrical isolation,
no hum/ground loop issues.

---

### Option 2 — TRRS cable (wired headset adapter)

Connect a 4-conductor TRRS cable between the Android headset jack and the
radio's external speaker + mic connectors.

**Standard TRRS pinout (CTIA, used by most Android devices):**
```
Tip   — Left audio out  (connect to radio mic input)
Ring1 — Right audio out (connect to radio mic input)
Ring2 — Mic/Ground      (connect to radio speaker ground)
Sleeve — Ground         (common ground)
```

**Required adapters:**
- Android USB-C → 3.5mm headset adapter (if device has no headset jack)
- Radio-specific audio adapter cable (varies by radio brand/model)

**Level matching:** Radios vary widely in audio levels. You may need a
resistor divider or level-matching cable to avoid distorting the radio's mic
input. Most commercial "radio interface cables" include this.

**PTT:** With a wired connection, the radio must be in VOX mode. Set PTT Delay
in the plugin to 300–700 ms so the radio keys up before the data signal starts.

---

### Option 3 — Bluetooth audio

The plugin supports Bluetooth SCO devices. Select the Bluetooth device in the
Audio Input/Output spinners after pairing.

**Limitations:**
- Bluetooth audio introduces 50–200 ms of additional latency
- The OFDM decoder handles this, but performance may be slightly reduced
- Requires a Bluetooth radio interface (rare) or a radio with Bluetooth audio
- Most ham radios do not have Bluetooth — a Bluetooth-to-3.5mm adapter
  connected to the radio audio jacks can work

---

## VOX vs PTT

### VOX (Voice-Operated Transmit)

Most portable radios have VOX mode. When audio above a threshold is detected
on the mic input, the radio keys up automatically.

- Enable VOX in the radio's menu
- Set VOX sensitivity (not too sensitive — background noise should not trigger it)
- Set **PTT Delay** in the plugin to 300–700 ms (default: 0)
  The plugin inserts this much silence before the OFDM signal

### Manual PTT

If your radio requires manual PTT (pressing a button to transmit):
- The 1-second lead-in silence is not enough time for manual PTT
- Consider using VOX instead, or a PTT-capable USB interface like DigiRig
  with its PTT circuit

### CAT/rigctld PTT (future)

Automatic PTT via serial/CAT control is not implemented in v1.0.0. Planned
for a future release.

---

## Common Radios

### Baofeng UV-5R / UV-82 / BF-F8HP

- Use a Kenwood-style 2-pin adapter cable
- VOX mode available — use PTT Delay 500 ms
- Audio levels are generally compatible with direct connection

### Yaesu FT-60 / FT-70 / FT-2980

- Use a Yaesu-style adapter cable
- VOX available on most models

### Icom IC-705 / IC-7300

- DigiRig has a dedicated cable for Icom radios
- USB audio output available on IC-705 directly (no cable needed)
- Select the IC-705 USB audio device in the plugin spinners

### Any radio with 3.5mm speaker/mic jacks

Use a TRRS breakout cable or radio-specific interface cable. Most cheap
BNC/speaker/mic cables from Amazon work for basic use.

---

## Audio Level Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Radio never keys (VOX) | Signal level too low | Check cable; try system volume max |
| Radio stays keyed after TX | VOX release time too long | Adjust radio VOX delay setting |
| Decoded CoT has errors | Overdriven audio | Reduce system volume or use attenuator cable |
| RX never decodes | Microphone too quiet | Increase mic gain or move radio closer |
| RX decodes sporadically | Too much background noise | Increase squelch; use directional antenna |

---

## FCC Part 97 Notes

Under FCC Part 97, amateur radio transmissions must include station identification.
The HBC protocol satisfies this requirement by encoding the operator's callsign
in the ITA2 header of every OFDM packet. You must enter your valid FCC callsign
in the plugin settings before transmitting.

Data transmissions on amateur radio are permitted on HF and most VHF/UHF bands.
Check your band plan for data-allowed segments. The OFDM signal occupies
approximately 1600 Hz of bandwidth centered around 1500 Hz, suitable for
narrow-band data allocations.
