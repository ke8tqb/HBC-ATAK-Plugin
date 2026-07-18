# HBC Audio — ATAK Plugin

**Ham Binary Cursor on Target (HBC) over OFDM Audio**

An ATAK-CIV plugin that transmits and receives Cursor on Target (CoT) position
reports over ham radio using OFDM audio tones — no internet, no repeater, no
infrastructure required. Compliant with FCC Part 97 station identification.

---

## Download

**[Download Latest APK from Releases](https://github.com/ke8tqb/HBC-ATAK-Plugin/releases/latest)**

> Requires the **ATAK-CIV SDK build** (`atak.apk`) — *not* the production
> ATAK-CIV release. See [Installation](#installation) for details.

---

## Features

| Feature | Description |
|---|---|
| **TX — Auto** | Automatically transmits your position whenever ATAK generates a CoT event |
| **TX — Manual (pane)** | "SEND MY POSITION NOW" button in the plugin panel |
| **TX — Marker relay** | Tap any map marker, tap the green HBC TX button, sends that marker |
| **RX** | Continuously listens for incoming HBC audio and plots decoded positions on the map |
| **Audio device selection** | Choose input/output device (built-in, USB, Bluetooth, DigiRig, etc.) |
| **PTT delay** | Configurable pre-transmit silence for VOX/PTT hardware |
| **Self-echo suppression** | Your own audio echoes are discarded before they reach the map |
| **FCC identification** | Callsign embedded in every OFDM packet header (Part 97 compliant) |

---

## How It Works

```
TRANSMIT PATH
─────────────
ATAK CoT XML  (~600 bytes)
    ↓  HBC Encoder        compress to 14-21 bytes (96% reduction)
HBC binary frame
    ↓  aicodix OFDM Modem (C++ via JNI)
PCM audio at 8000 Hz
    ↓  Android AudioTrack
Radio mic input → over the air

RECEIVE PATH
────────────
Radio speaker output
    ↓  Android AudioRecord + amplitude squelch
PCM audio buffer
    ↓  aicodix OFDM Modem (C++ via JNI)
HBC binary frame
    ↓  HBC Decoder
CoT XML → ATAK internal dispatcher → marker on map
```

### HBC Protocol

The HBC protocol compresses CoT XML to a minimal binary frame:

| Message type | CoT XML | HBC binary |
|---|---|---|
| PLI (6-char callsign) | ~600 B | **14 bytes** |
| Spot marker | ~800 B | **17 bytes** |
| 911 Alert | ~700 B | **21 bytes** |

The header encodes the transmitting station callsign in ITA2 (satisfying FCC
Part 97 ID). Coordinates use 21/22-bit fixed-point at ×10,000 scale (~11 m
precision). The UID is derived deterministically from the callsign so all
receiving stations track the same contact without pre-coordination.

### OFDM Modem

Uses the **aicodix OFDM modem** (short branch) at 8000 Hz, 16-bit mono with
Schmidl-Cox synchronization and polar codes for FEC. One audio frame carries
up to 170 bytes. Lead-in silence (1 second) gives radio PTT hardware time to
key before data starts.

---

## Requirements

### End users

- Android 9.0+ (API 28)
- ATAK-CIV SDK build `atak.apk` version 5.7.0 — see Installation
- Ham radio with audio interface (cable or USB adapter such as DigiRig Mobile)
- Valid FCC ham radio callsign

### Developers

- Android Studio with NDK + CMake
- ATAK-CIV SDK 5.7.0 from tak.gov
- Internet access on first build (downloads aicodix C++ headers via CMake FetchContent)

---

## Installation

### Step 1 — Get the ATAK SDK build

Production ATAK-CIV rejects debug-signed plugins. You need the SDK flavor.

1. Create a free account at **tak.gov**
2. Download **ATAK-CIV SDK 5.7.0** (the SDK zip, not just the app APK)
3. Inside the zip: find `atak.apk` — install this version

**Important:** Uninstall production ATAK-CIV before installing `atak.apk`.
They share the same package name and cannot coexist.

### Step 2 — Clear ATAK data (if upgrading)

If you had production ATAK installed, a passphrase prompt may appear.
Clear the old data:

```
adb shell rm -rf /sdcard/atak
```

Or delete the `atak` folder via any file manager app on the device.

### Step 3 — Install the plugin

1. Install `atak.apk` on your Android device
2. Download the plugin APK from the Releases page (link at top of this file)
3. Sideload the plugin APK (tap it in a file manager, allow unknown sources)
4. Open ATAK → Settings → Tool Preferences → Manage Plugins → Enable HBC Audio

---

## Usage

### Opening the plugin

Tap the radio antenna icon in the ATAK toolbar (top of screen).

### Plugin panel controls

| Control | Function |
|---|---|
| **SEND MY POSITION NOW** | Immediately transmits your GPS position |
| **Transmit (TX) toggle** | Auto-transmit every CoT ATAK generates |
| **Receive (RX) toggle** | Listen for incoming HBC audio continuously |
| **Ham Radio Callsign** | Your FCC callsign — embedded in every packet |
| **Audio Output** | Output device → connects to radio mic input |
| **Audio Input** | Input device → connects from radio speaker output |
| **PTT Delay (ms)** | Silence before OFDM signal (for VOX/PTT keying) |

### Transmitting a map marker (quickest method)

1. Tap any map marker (another station or placed point)
2. A green **"HBC TX [callsign]"** button appears at the bottom of the screen
3. Tap it — that position is transmitted immediately
4. Tap the map background to dismiss the button

**Two taps total** from selecting a marker to transmitting it.

### DigiRig / USB audio setup

1. Plug in the DigiRig to your Android device
2. Open the plugin panel — spinners auto-refresh to show the new device
3. Select DigiRig under **Audio Output** (TX path)
4. Select DigiRig under **Audio Input** (RX path)
5. Set PTT Delay to 300–500 ms if using VOX

---

## Self-Echo Suppression

When RX is active, your own transmissions coming back through the radio are
automatically discarded before reaching the ATAK map. The decoder checks:

- **UID**: If the decoded UID is `HBC-{your callsign}`, discard
- **Contact callsign**: If the decoded callsign matches yours (first 7 chars),
  discard

Both your plugin settings callsign and your ATAK self-marker callsign are
checked, so suppression works even if the two differ.

---

## Building from Source

### 1. Configure local.properties

Copy `local.properties.example` → `local.properties` and fill in:

```properties
sdk.dir=C\:/Users/YourName/AppData/Local/Android/Sdk
takdev.plugin=C\:/path/to/ATAK-SDK-5.7.0/atak-gradle-takdev.jar
```

No keystore setup needed for debug builds — `atak-gradle-takdev` generates
the debug keystore automatically.

### 2. Build

```powershell
.\gradlew.bat assembleCivDebug
```

Output: `app\build\outputs\apk\civ\debug\ATAK-Plugin-HBC-ATAK-Plugin-*.apk`

> **Windows UNC path note:** Gradle cannot run from a network drive path
> (`\\server\...`). Copy the project to a local drive before building.

### 3. First build note

CMake FetchContent downloads aicodix DSP and code headers on the first build:
- `github.com/aicodix/dsp` — FFT, filters, PCM I/O
- `github.com/aicodix/code` — polar codes, BCH, CRC

Internet is required once. Subsequent builds use the cached `.cxx/` directory.

---

## Project Structure

```
HBC-ATAK-Plugin/
├── app/src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt        aicodix deps via FetchContent, builds libhbc-ofdm.so
│   │   ├── hbc_jni.cpp           JNI bridge: Java byte[] <-> C++ PCM samples
│   │   ├── encode.cc / decode.cc aicodix OFDM modem (verbatim, short branch)
│   │   └── *.hh                  aicodix modem headers
│   └── java/com/atakmap/android/hbc/
│       ├── plugin/
│       │   ├── HBCPlugin.java           ATAK 5.x IPlugin entry point
│       │   └── PluginNativeLoader.java  Secure JNI loader
│       ├── HBCMapComponent.java   Core: TX intercept, RX inject, overlay button
│       ├── hbc/
│       │   ├── HBCEncoder.java    CoT XML → HBC bytes
│       │   ├── HBCDecoder.java    HBC bytes → CoT XML
│       │   └── ITA2.java          ITA2 alphabet tables
│       └── audio/
│           ├── OFDMModem.java             JNI wrapper (encodeHBC / decodeFromAudio)
│           ├── RadioAudioTransmitter.java  AudioTrack playback with device selection
│           └── HBCAudioMonitor.java        AudioRecord thread with squelch + decode
└── local.properties.example
```

---

## Protocol Specification

Full protocol documentation is in the HBC-Protocol repository:
**[github.com/ke8tqb/HBC-Protocol](https://github.com/ke8tqb/HBC-Protocol)**

---

## Licenses

| Component | License |
|---|---|
| This plugin | MIT |
| HBC Protocol | MIT — KE8TQB |
| aicodix OFDM modem | 0BSD — Ahmet Inan |
| ATAK SDK | See tak.gov terms |

---

73 de KE8TQB
