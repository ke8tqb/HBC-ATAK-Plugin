# HBC ATAK Plugin

**Ham Binary Cursor on Target (HBC) — OFDM Audio Transport for ATAK**

This ATAK plugin intercepts your CoT messages and sends them as audio tones
through your ham radio. Other operators running the same plugin hear the tones,
decode them, and see your position on their ATAK map — no internet required.

## How It Works

```
ATAK (CoT XML)
    ↓  HBC Encoder       Compresses XML from ~600 bytes → ~20 bytes
    ↓  OFDM Modem        Turns bytes into audio tones (like a modem)
    ↓  AudioTrack        Plays tones out the headset jack
    ↓  Ham Radio         Transmits the audio over the air
    ↓  (other side)
    ↓  AudioRecord       Listens to received audio from the radio
    ↓  OFDM Modem        Decodes the audio back to bytes
    ↓  HBC Decoder       Reconstructs CoT XML from bytes
    ↓  ATAK map          Other operator's position appears on map
```

---

## Step-by-Step Build Instructions

### What You Need First

You need four things before you can build this:

1. **Android Studio** — you said you already have this. ✓
2. **Android NDK** — the toolkit for building C++ code on Android
3. **ATAK-CIV SDK** — the official ATAK developer toolkit
4. **A debug keystore** — a digital "signature" file for signing the APK

---

### Step 1 — Install the Android NDK

1. Open Android Studio
2. Click **Tools → SDK Manager**
3. Click the **SDK Tools** tab at the top
4. Check the box next to **NDK (Side by side)**
5. Check the box next to **CMake**
6. Click **OK** and let it download (might take a few minutes)

---

### Step 2 — Get the ATAK-CIV SDK

1. Go to **https://tak.gov** and create a free account
2. Log in and go to **Products → ATAK-CIV**
3. Download **ATAK-CIV SDK 5.4.0** (it's a .zip file)
4. Unzip it somewhere on your computer, for example:
   `C:\ATAK-SDK\`
5. Inside you'll find these important files:
   - `main.jar` — the ATAK API
   - `atak-gradle-takdev.jar` — the build plugin
   - `debug.keystore` — the signing key for test builds

---

### Step 3 — Configure local.properties

1. In this project folder (`7-18-26 ATAK Plugin\`), find the file called
   `local.properties.example`
2. **Copy** it and rename the copy to `local.properties`
3. Open `local.properties` in Notepad and fill in your actual paths:

```properties
# Path to your Android SDK (Android Studio shows this under File > Project Structure)
sdk.dir=C\:/Users/YourName/AppData/Local/Android/Sdk

# Path to the atak-gradle-takdev.jar from the SDK you unzipped
takdev.plugin=C\:/ATAK-SDK/atak-gradle-takdev.jar

# Path to the debug.keystore from the SDK
takDebugKeyFile=C\:/ATAK-SDK/debug.keystore
takDebugKeyFilePassword=android
takDebugKeyAlias=androiddebugkey
takDebugKeyPassword=android
```

> **Important:** In Windows paths, use forward slashes `/` or double backslashes `\\`.
> Do NOT use single backslashes `\` in this file.

---

### Step 4 — Open the Project in Android Studio

1. Open Android Studio
2. Click **File → Open**
3. Navigate to this folder: `\\Primary\David\CoT Project\7-18-26 ATAK Plugin\`
4. Click **OK**
5. Android Studio will sync Gradle. This takes a minute or two.
6. **The first sync also downloads C++ dependencies from GitHub** (aicodix DSP and
   FEC libraries). Make sure you have internet the first time you sync.

If Android Studio shows a red error banner, click **Try Again** or check that
your paths in `local.properties` are correct.

---

### Step 5 — Build the APK

1. In the menu bar: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Wait for the build to finish (it may take 3–10 minutes the first time
   because it compiles C++ code)
3. When done, a popup says **"Build successful"** with a link that says
   **"locate"** — click it
4. Your APK is in:
   `app\build\outputs\apk\civ\debug\`
   The file is named something like:
   `ATAK-Plugin-HBC-ATAK-Plugin-1.0.0-civDebug-5.4.0.apk`

---

### Step 6 — Install on Your Android Device

1. Connect your Android phone or tablet via USB
2. Enable **USB Debugging** on the device:
   - Go to **Settings → About Phone**
   - Tap **Build Number** 7 times (enables Developer Options)
   - Go to **Settings → Developer Options → USB Debugging → ON**
3. In Android Studio: **Run → Run 'app'** or click the green ▶ play button
   — OR —
   Copy the APK to your device and open it with a file manager to sideload it

---

### Step 7 — Install into ATAK

1. Make sure **ATAK-CIV 5.4.0** is installed on the same device
2. Install the plugin APK (you can just tap it in a file manager)
3. Open ATAK
4. Go to **Settings → Tool Preferences → Manage Plugins**
5. The **HBC Radio Plugin** should appear — enable it
6. Open it from the **Tools** menu (hamburger icon in the top right)

---

### Using the Plugin

Once loaded, tap the **Tools** menu in ATAK and find **HBC Radio Plugin**.

In the plugin panel:
- Enter your **Ham Radio Callsign** (required — embedded in every transmission
  for FCC Part 97 ID)
- Set **Audio Output** to whichever audio jack connects to your radio's mic input
  (usually a wired headset or USB audio adapter)
- Set **Audio Input** to the source connected to your radio's speaker output
- Toggle **Transmit (TX)** ON — your ATAK position reports will now be sent as
  audio whenever ATAK generates a CoT event
- Toggle **Receive (RX)** ON — the plugin will listen for incoming HBC signals
  and add received positions to your ATAK map automatically

**PTT Delay:** If your radio uses VOX (voice-activated transmit), enter a delay
(e.g., 500 ms) so the radio has time to key up before the data signal starts.

---

## Project Structure

```
7-18-26 ATAK Plugin/
├── app/
│   ├── build.gradle                 Build configuration
│   └── src/main/
│       ├── cpp/
│       │   ├── CMakeLists.txt       C++ build config (downloads aicodix deps)
│       │   ├── hbc_jni.cpp          JNI bridge: Java ↔ OFDM modem
│       │   ├── encode.cc            aicodix OFDM encoder (verbatim)
│       │   ├── decode.cc            aicodix OFDM decoder (verbatim)
│       │   └── *.hh                 aicodix modem headers
│       └── java/com/atakmap/android/hbc/
│           ├── hbc/
│           │   ├── HBCEncoder.java  CoT XML → HBC bytes
│           │   ├── HBCDecoder.java  HBC bytes → CoT XML
│           │   └── ITA2.java        ITA2 alphabet tables
│           └── audio/
│               ├── OFDMModem.java          JNI wrapper
│               ├── RadioAudioTransmitter.java  TX via AudioTrack
│               └── HBCAudioMonitor.java    RX via AudioRecord
├── local.properties.example         Copy → local.properties, fill in your paths
└── README.md                        This file
```

## License

- This plugin code: MIT
- aicodix modem (encode.cc, decode.cc, headers): 0BSD
- HBC Protocol (hbc_encoder.py, hbc_decoder.py): MIT

73 de KE8TQB
