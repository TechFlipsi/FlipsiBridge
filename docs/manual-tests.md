---
summary: "On-device manual test procedures for the bridge."
read_when:
  - "Before cutting a release"
  - "After changing the bridge app or relay"
---

# Manual Tests

Automated: `pytest tests/` (Python toolset) and `cd hermes-android-bridge && ./gradlew lint test` (Kotlin). The procedures below need a real device or AAOS head unit.

## Connection

1. Install fresh APK; grant Accessibility + overlay.
2. `android_setup("<code>")` → relay starts, returns `user_instructions`.
3. Enter server address + code in app → Connect.
4. `android_ping()` → reports phone connected.
5. Kill WiFi briefly → confirm auto-reconnect (exponential backoff).

## Core interaction

6. `android_open_app("com.android.settings")` then `android_read_screen()` → tree populated.
7. `android_tap_text(...)`, `android_type(...)`, `android_swipe(...)` → verify on overlay/screen.
8. `android_screenshot()` → returns `MEDIA:` path; image correct.
9. `android_wait(text=...)` resolves after navigation.

## Microphone (real device)

10. Grant the Hermes Bridge microphone permission, connect the relay, then press Home so the app UI is backgrounded.
11. From the agent host, call `android_mic_record(duration=3)`; it must return `starting` and show the foreground-service notification despite the app UI being backgrounded.
12. Wait for `android_mic_status()` to report `ready`, then call `android_mic_fetch()`; verify the returned `MEDIA:` file is a playable 16 kHz mono WAV with audible speech.
13. Start with `android_mic_record(duration=0)`, call `android_mic_stop()`, wait for `ready`, then verify `adb shell dumpsys activity services com.hermesandroid.bridge` no longer lists `MicrophoneRecorderService`.
14. Create more than 10 short recordings; `android_mic_status()` must report at most 10 completed WAVs and the newest recording must remain downloadable.

## Sensitive paths (verify PII stripping)

15. `android_send_sms` / `android_call` → success response contains NO phone number/recipient.
16. `android_location` → guarded if permission absent (#49).
17. `android_clipboard_write` response strips content per convention (#35).

## Auth / rate limiting

18. 5 wrong pairing codes within 60s → IP blocked 5 min, HTTP 429 (#51).
19. Correct code → connects.

## AAOS (if applicable)

20. SMS/calls/contacts return graceful errors; tap/type/screenshot work.

## Pairing password (optional; W17-F2, Umfang-Fix W28)

The relay can require an extra password for the enrollment of a NEW device only. Already enrolled (known) devices reconnect WITHOUT it — running/paired devices are never affected by setting, clearing or resetting the password (scope fix, 2026-11-17).

**Residual risk (deliberate, direct consequence of the scope fix):** the pairing code remains the PRIMARY credential. The password only guards the enrollment of NEW devices — a known device's `device_id` is client-claimed, so an attacker with the code alone (no password) can still connect as a known device and take over its live slot. Optional hardening candidate for a future wave: additionally require the password when replacing an ACTIVELY connected slot (offline reconnects stay password-free).

21. App: Settings › Server → „Kopplungspasswort" card. Toggle/set (min 8 chars, masked field), status shows active/inactive. Server stores ONLY a salted PBKDF2-SHA256 hash in a 0600 file (`pairing_password.json`); no plaintext ever, nothing in logs. Unknown devices then need header `X-Pairing-Password`: missing → 403 `password-required`, wrong → 403 `password-invalid` (both counted in the existing IP rate chain: 5 fails within a 60 s window → 5 min IP block).
22. While the gate is active: reconnect a KNOWN device without the password → must connect (no prompt). Enroll a new device with the password → connects; afterwards that device reconnects without the password.
23. Clear via the app (Schutz entfernen) → `/password_status` reports `{"active": false}`; new devices enroll via admin approval (`pairing.request`, TTL 10 min) again.
24. **SSH reset (forgot password / lost app access):** the relay loads the hash record into RAM at startup — deleting the file alone does NOT lift the gate. Remove the file AND restart the relay:

        # Standalone deploy (state dir /opt/flipsibridge, unit flipsibridge-relay):
        sudo rm -f /opt/flipsibridge/pairing_password.json
        sudo systemctl restart flipsibridge-relay

        # Hermes-plugin deploy (state dir <hermes home>/plugin-data/hermes-android,
        # unit hermes-android-relay):
        sudo rm -f <hermes-home>/plugin-data/hermes-android/pairing_password.json
        sudo systemctl restart hermes-android-relay

    Custom location: `ANDROID_RELAY_PASSWORD_FILE` env override. Verify afterwards: `curl -H "Authorization: Bearer <code>" https://<relay>/password_status` → `{"active": false}`. Known devices reconnect normally throughout (no re-pairing needed).
