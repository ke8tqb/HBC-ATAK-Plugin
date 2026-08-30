# Third-Party Notices — HBC ATAK Plugin

This plugin bundles or derives from the following third-party software.

## javAX25 — AFSK1200 / AX.25 modem (GPL v2 or later)
- Source: https://github.com/sivantoledo/javAX25
- Copyright (C) Sivan Toledo, 2012
- CRC computation adapted from **soundmodem**, Copyright (C) 1999-2000
  Thomas Sailer (also GPL v2 or later)
- Bundled files: `app/src/main/java/sivantoledo/**` (unmodified upstream
  sources; license headers retained in each file)
- License: GNU General Public License, version 2 or (at your option) any
  later version. Because this code is compiled into the plugin APK, any
  distribution of the plugin binary must be under GPL-compatible terms and
  accompanied by corresponding source (this project tree).

## rattlegram / aicodix — COFDMTV OFDM modem (ISC-style permissive)
- Source: https://github.com/aicodix/rattlegram (which bundles the
  aicodix `dsp` and `code` header libraries)
- Copyright (C) 2022 Ahmet Inan <inan@aicodix.de>
- Bundled files: `app/src/main/cpp/*.hh` (unmodified upstream headers) and
  `app/src/main/cpp/hbc-ofdm-jni.cpp` (adapted from rattlegram's
  `native-lib.cpp`)
- License: "Permission to use, copy, modify, and/or distribute this
  software for any purpose with or without fee is hereby granted." with a
  standard warranty disclaimer (ISC-style). See the upstream LICENSE file.

## FreeDV / codec2 DATAC raw-data modem (LGPL v2.1), via Mercury
- Source: https://github.com/Rhizomatica/mercury (`modem/freedv/`, the
  vendored FreeDV/codec2 subset used by the Mercury HF modem,
  https://mercury.hermes.radio/); upstream https://github.com/drowe67/codec2
- Copyright (C) David Rowe and the codec2 contributors
- Bundled files: `app/src/main/cpp/mercury/**` (unmodified upstream C
  sources/headers; license at `app/src/main/cpp/mercury/LICENSE-freedv`)
  plus `app/src/main/cpp/hbc-mercury-jni.cpp` (original JNI bridge)
- License: GNU Lesser General Public License v2.1. Mercury itself is
  GPL v3, but no Mercury application code (ARQ/data-link layer) is
  included — only the LGPL FreeDV modem sources it vendors.

## APRSdroid / jsoundmodem — reference implementations (not bundled)
- https://github.com/ge0rg/aprsdroid (Georg Lukas) and jsoundmodem
  (Bastian Mueller) were studied as references for Android audio-output
  behavior (native sample rate selection, output stream selection, audio
  thread priority). No APRSdroid or jsoundmodem code is included in this
  plugin.

## ATAK-CIV Plugin SDK
- The plugin is built against the ATAK-CIV SDK published by the TAK
  Product Center (https://tak.gov /
  https://github.com/TAK-Product-Center). The ATAK API (`main.jar`) and
  Gradle tooling are used under the SDK's published terms and are not
  redistributed in this tree. "ATAK" and "TAK" are identifiers of the TAK
  Product Center; no affiliation or endorsement is implied.

## HBC Protocol
- The HBC codec (`app/src/main/java/com/atakmap/android/hbc/Hbc*.java`,
  `Ita2.java`, `BitReader.java`, `BitWriter.java`) is an original
  implementation of the MIT-licensed HBC Protocol
  (https://github.com/ke8tqb/HBC-Protocol).
- Cursor on Target (CoT) is a message standard defined by The MITRE
  Corporation (public-release schemas, MITRE Case #11-3895).

---

**Effective license of the combined plugin APK:** because it incorporates
GPL v2+ code (javAX25), the plugin as a whole is distributed under the
GNU General Public License v2 or later. All other bundled components
(ISC, MIT, Apache-2.0) are GPL-compatible.
