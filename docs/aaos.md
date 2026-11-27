---
summary: "Running the bridge app on Android Automotive OS (AAOS) car head units — install, connect, and tool availability."
read_when:
  - "Installing the bridge on a car head unit"
  - "Checking which tools work on AAOS"
---

# Android Automotive OS (Car Head Units)

The bridge app can run on Android Automotive OS (AAOS) car head units. Phone-specific features (SMS, calls, contacts) gracefully return errors when the hardware is unavailable.

## Installation

1. Get the APK: download `flipsibridge-<version>-github.apk` from the [Latest Build release](https://github.com/TechFlipsi/FlipsiBridge/releases/tag/latest-build), or build it with `cd hermes-android-bridge && ./gradlew assembleGithubDebug`
2. Sideload via ADB: `adb install flipsibridge-*-github.apk`
   - USB: connect directly to the head unit's USB port
   - WiFi: `adb connect <head-unit-ip>:5555` then install
3. Grant Accessibility Service: **Settings > Accessibility > Hermes Bridge > Enable**
4. Grant overlay permission: **Settings > Apps > Special access > Draw over apps > Hermes Bridge**
5. Skip phone-specific permissions (SMS, calls, contacts) — not applicable

## Connection

The car head unit needs network access to reach the relay server:

- **USB tethering** (recommended): `adb forward tcp:8766 tcp:8766`, then enter `http://localhost:8766` in the app
- **WiFi**: enter the relay server's `http://<ip>:8766` in the app (both devices on same network)

## Limitations

| Tool | Status on AAOS |
|------|---------------|
| `android_send_sms` | Not available — returns error |
| `android_call` | Not available — returns error |
| `android_search_contacts` | Not available — returns error |
| `android_location` | Depends on car unit GPS configuration |
| `android_screen_record` | May behave differently (restricted MediaProjection on some OEMs) |

All other tools (tap, swipe, type, screenshot, read screen, open apps, etc.) work normally.
