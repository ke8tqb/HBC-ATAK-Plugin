# Troubleshooting

---

## Plugin won't load — "INVALID signature"

**Cause:** You are running production ATAK-CIV, which only accepts plugins
signed with the official TAK release certificate. Debug-signed plugins are
rejected.

**Fix:** Install the **ATAK SDK build** (`atak.apk`) from tak.gov instead of
production ATAK-CIV. See the Installation section of the README.

---

## Plugin won't load — "requires software version X but you are running Y"

**Cause:** The ATAK version on the device doesn't match the `ATAK_VERSION`
declared in the plugin's manifest.

**Fix:** The plugin targets **ATAK 5.7.0 CIV**. Use the matching `atak.apk`
from the 5.7.0 SDK. If you have a different version of ATAK installed, you
must either rebuild the plugin against your SDK version (change `ATAK_VERSION`
in both `build.gradle` files) or use the matching ATAK version.

---

## Encryption passphrase prompt after installing atak.apk

**Cause:** ATAK found encrypted data from a previous ATAK installation in
`/sdcard/atak/`. The SDK build cannot read data encrypted by the production build.

**Fix:** Delete the ATAK data folder and start fresh:
```
adb shell rm -rf /sdcard/atak
```
Or delete the `atak` folder from device storage using any file manager app.
Note: this erases all ATAK maps, contacts, and settings.

---

## TX button (green "HBC TX") doesn't appear when tapping a marker

**Cause:** Several possibilities:
1. You tapped your own marker (KEYSTON callsign) — the button intentionally
   does not appear for your own position
2. The plugin is not loaded or started — check the toolbar for the antenna icon
3. The ATAK item is not a PointMapItem (e.g. a route or shape) — the button
   only appears for point markers

**Fix:** Ensure the plugin is enabled (antenna icon visible in toolbar),
and tap a marker that belongs to another station, not yourself.

---

## "HBC TX" button appears but nothing happens when pressed

**Cause:** TX path failed silently. Common reasons:
- Callsign not set in plugin settings (defaults to "NOCALL" which encodes but
  may not transmit correctly)
- Audio output device not connected or wrong device selected
- OFDMModem native library failed to load (check logcat for `HBC-OFDM` tag)

**Fix:** 
1. Open the plugin panel and confirm your callsign is entered
2. Verify the correct audio output device is selected
3. Run `adb logcat -s HBC-OFDM HBCMapComponent` and check for errors

---

## Radio doesn't key up (VOX) when transmitting

**Cause:** The audio signal level from the Android device is too low to
trigger VOX on the radio.

**Fix:**
1. Set Android system volume to maximum
2. Try increasing PTT Delay to 1000 ms (gives more time and ensures
   the lead-in silence is long enough for slow VOX)
3. Check the physical cable connection
4. Use a different audio output device (DigiRig provides better levels than
   the built-in speaker output)
5. If using a wired headset adapter, verify it's a 4-conductor TRRS (not TRS)

---

## Radio keys but no decode on the receiving end

**Cause:** The audio is not reaching the receiving device's microphone input,
or levels are incorrect.

**Fix:**
1. Verify the RX cable connects radio speaker output → Android mic input
2. Set Android microphone sensitivity appropriately (not too high → overdriven)
3. Check that **Receive (RX)** is toggled ON in the plugin panel
4. Verify the correct **Audio Input** device is selected (especially if
   using DigiRig or USB audio)
5. Check logcat: `adb logcat -s HBCAudioMonitor` — you should see
   "Signal detected (RMS=...)" messages when audio is present

---

## Positions appear on the map but with wrong callsign / wrong icon

**Cause:** The sender's callsign is truncated to 7 characters in the HBC name
field. If two stations share the same 7-character prefix, they would appear
under the same callsign label. This is a known limitation.

The CoT type is always `a-f-G` (friendly ground track) regardless of the
original ATAK icon — HBC v1.0 does not transmit the full type hierarchy.

---

## My own position appears on the map after I transmit (loopback)

**Cause:** Self-echo suppression requires your callsign to be entered in the
plugin settings. If the callsign field is empty, no suppression occurs.

**Fix:** Open the plugin panel and enter your callsign. The plugin also checks
the ATAK self-marker callsign as a fallback.

If it still happens after setting the callsign:
- Verify the callsign in the plugin matches what appears in the decoded CoT
  (check logcat: `adb logcat -s HBCDecoder HBCMapComponent`)
- Ensure your callsign is ≤ 8 characters (ITA2 limit); if longer, only the
  first 8 characters are transmitted and compared

---

## DigiRig not appearing in the audio device spinners

**Cause:** The device list is populated when the plugin panel is opened.
If the DigiRig was connected after the panel was opened, it may not have
triggered a refresh.

**Fix:** The plugin registers an `AudioDeviceCallback` that should
auto-refresh within ~1 second of plugging in the DigiRig. If it doesn't:
1. Close and reopen the plugin panel
2. Toggle RX off and back on (this restarts the AudioRecord with the new device)
3. Unplug and replug the DigiRig

---

## Build fails — "com.android.application:8.13.0 not found"

**Cause:** The `pluginManagement` block in `settings.gradle` is missing, so
Gradle can't find Google's Maven repository where AGP lives.

**Fix:** Verify `settings.gradle` contains:
```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```
This is present in the repo — if you're seeing this error, you may have
an old `settings.gradle`. Pull the latest from the repo.

---

## Build fails — "Android Gradle plugin requires Java 17"

**Cause:** The JDK on your PATH is older than Java 17.

**Fix:** Add this to `gradle.properties`:
```properties
org.gradle.java.home=C\:/Program Files/Android/Android Studio/jbr
```
Replace the path with the location of Android Studio's bundled JDK on your
machine. The bundled JDK is always Java 17 or newer.

---

## Build fails — "UNC paths are not supported"

**Cause:** Gradle's batch file (`gradlew.bat`) spawns `cmd.exe`, which
cannot use UNC network paths (`\\server\share\...`) as the working directory.

**Fix:** Copy the project to a local drive before building:
```
xcopy /E /I "\\server\share\HBC-ATAK-Plugin" "C:\Users\YourName\Desktop\HBC-ATAK-Plugin"
cd C:\Users\YourName\Desktop\HBC-ATAK-Plugin
.\gradlew.bat assembleCivDebug
```

---

## Logcat reference

| Tag | Source | What to look for |
|-----|--------|------------------|
| `HBC-OFDM` | hbc_jni.cpp | Encode/decode sample counts, errors |
| `HBCEncoder` | HBCEncoder.java | Encoding failures, unsupported CoT types |
| `HBCDecoder` | HBCDecoder.java | Decode failures, invalid bit streams |
| `HBCAudioMonitor` | HBCAudioMonitor.java | Signal detection, RMS levels, decode attempts |
| `HBCMapComponent` | HBCMapComponent.java | TX/RX events, self-echo suppression, CoT injection |
| `HBCPlugin` | HBCPlugin.java | Plugin start/stop |
| `RadioAudioTX` | RadioAudioTransmitter.java | TX sample count, device |

Run all at once:
```
adb logcat -s HBC-OFDM HBCEncoder HBCDecoder HBCAudioMonitor HBCMapComponent HBCPlugin RadioAudioTX
```
