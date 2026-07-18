# Contributing

Contributions are welcome. This project is intentionally small and focused —
please discuss large changes in an Issue before submitting a PR.

---

## Reporting Issues

Open an Issue on GitHub. Include:
- Android version and device model
- ATAK-CIV version (shown in Settings → About)
- Plugin version
- Steps to reproduce
- Relevant logcat output (see the Troubleshooting guide for log tags)

---

## Pull Requests

1. Fork the repo and create a feature branch
2. Keep changes focused — one feature or fix per PR
3. Test on a real device (or emulator with audio routing)
4. Update the relevant documentation files in `docs/` if behaviour changes
5. Add a brief entry to `CHANGELOG.md` under an `[Unreleased]` section

---

## Areas to Contribute

### Protocol (HBC modes)

New HBC modes can be added without breaking existing decoders — the 3-bit mode
field provides 8 possible modes; only 2 are currently used.

Potential new modes:
- **Mode 3 — Expanded PLI**: altitude, speed, heading, team color/role
- **Mode 4 — GeoChat**: short text message (≤ ~50 chars after compression)
- **Mode 5 — Medevac/9-line**: structured medical request
- **Mode 6 — Waypoint/Route**: ordered list of points

See `docs/PROTOCOL.md` for the bit stream format and the
`// ADDING A NEW MODE` comments in `HBCEncoder.java` and `HBCDecoder.java`.

### Audio / modem

- **PTT via CAT/rigctld**: serial/USB PTT keying for radios that need it
- **Higher sample rates**: the aicodix modem supports 16000 and 44100 Hz
- **Squelch tuning**: expose the RMS threshold as a plugin setting
- **Signal quality reporting**: log and display decoded SNR to help operators
  tune their audio levels

### ATAK integration

- **Group/team metadata**: encode `__group` (team color and role) in a
  future HBC mode and decode it into ATAK contact cards
- **Contact list integration**: add HBC-decoded stations to ATAK's contacts
- **Stale timeout control**: make the stale period configurable

### Distribution

- **Release signing**: once a TAK.gov ODK certificate is obtained, add the
  release signing workflow and produce an `odk`-compatible APK

---

## Code Style

- Java code follows the existing patterns in the repo (Android-style camelCase)
- C++ follows the aicodix style (snake_case, template-heavy)
- Keep methods short and well-commented
- All ATAK API calls should have a comment explaining the API version

---

## License

By contributing you agree that your contributions will be licensed under the
MIT License as stated in `LICENSE`.
