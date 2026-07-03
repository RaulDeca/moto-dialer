# Moto Dialer — Call Recorder

A professional call-recording application for Android that captures **both sides** of calls (your voice + the other party) with high-quality audio and screen-video recording for VoIP. Built and tested on **Moto G34 (Android 14)** with a clean **Dark Blue** / **White + Blue** theme system.

> Developed by Siva

---

## Features

### Recording
- **Dual-Capture Engine** — Records both your mic and the device's outgoing/incoming audio, then mixes them into one stream.
- **Regular (network) calls** → 16-bit PCM WAV (16 kHz, mono), saved in `Internal Storage/Recorder/MotoCallRecorder/<ContactName>/`.
- **VoIP / Video calls** (WhatsApp, Messenger, Zoom, Telegram, Duo, …) → screen video (H.264) + both-sided audio (AAC) muxed into a single `.mp4`.
- **Auto-fallback** to system-output-only if mic capture fails, and to `MediaRecorder` `.m4a` if MediaProjection is unavailable.
- **VoIP end-detection** — 5-second debounce timer on window-state change so accidental app switches don't kill a recording mid-call.

### Dialer / Calls
- 4-tab UI: **Recents · Dialer · Contacts · Recordings**.
- **Dialer keypad** — weighted buttons fill the row, big digit + small T9-letter labels (`SpannableString`), inline backspace.
- **Voice call** — large green circle (72 dp).
- **Video call** — small blue circle (48 dp), uses `TelecomManager.placeCall()` with `EXTRA_START_CALL_WITH_VIDEO`.
- **T9 live search** as you type digits — both number-substring match **and** name-to-T9 regex match.
- **Dual-SIM picker** — dialog shown for voice and video calls when 2+ capable accounts detected.

### Contacts & History
- **Call history** — All / Missed / Incoming / Outgoing filters, voice & video entry buttons.
- **Contacts** — searchable list, voice & video entry buttons.
- **Empty states** with permission guidance (e.g. "Grant READ_CALL_LOG permission…").
- `READ_CALL_LOG` permission requested on Android 10+ so incoming numbers resolve to names.

### File Management
- Per-contact subfolders — recordings are auto-named `<ContactName>_<Timestamp>_IN|OUT.wav|mp4`.
- In-app **Play · Share · Delete** for every recording.
- Plays via `MediaPlayer`; share via `FileProvider` (`audio/*` chooser).

### UI / Theming
- **Two themes** switchable via `?attr/` references in `MainActivity`:
  - **Dark Blue** — `#121212` background, deep navy surfaces, blue accents.
  - **White + Blue** — `#FAFAFA` background, white surfaces, blue accents.
- **Adaptive-launcher icon** — solid blue background (`#1565C0`) with a white phone handset and red record-dot accent.
- Cards: 12 dp rounded corners + 2 dp elevation.
- Dialer keypad: 16 dp rounded rectangle, ripple highlight.
- Bottom nav: 8 dp elevation.

### Permissions
`RECORD_AUDIO`, `READ_PHONE_STATE`, `READ_CALL_LOG`, `CALL_PHONE`, `READ_CONTACTS`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `MANAGE_OWN_CALLS`, and storage (`READ_MEDIA_AUDIO` on API 33+ / `READ_EXTERNAL_STORAGE` below).

### Storage Locations
```
Internal Storage/Recorder/MotoCallRecorder/<ContactName>/
```
e.g.
```
Internal Storage/Recorder/MotoCallRecorder/John_Doe/2012-06-15_14-30_IN.wav
Internal Storage/Recorder/MotoCallRecorder/Mother/2012-06-15_15-10_OUT.mp4
```

---

## Critical Implementation Notes (read this if you fork)
- **MediaProjection is the only working path on Moto G34** for capturing the *incoming network call* audio (other sources don't expose it).
- `AudioManager.MODE_IN_COMMUNICATION` must NOT be set — it breaks all mic/sink capture.
- `AudioPlaybackCaptureConfiguration` is built with **all 12 `AudioAttributes.USAGE_*` types**, including `USAGE_CALL_ASSISTANT` (API 34+), using raw values 5 and 16.
- VoIP **screen video** uses `MediaProjection` → `VirtualDisplay` → `MediaCodec` H.264 + AAC, then `MediaMuxer` → `.mp4`.
- Mixed audio uses `mixPcm16()` after reading both a system-output `AudioRecord` and a `MIC`-source `AudioRecord`, summing with hard clamping.
- The MediaProjection token lives in a static `ProjectionGlobals` — it dies with the process, so a soft-kill forces a re-grant.
- The accessibility service records video for **every** detected VoIP app (it cannot distinguish audio-only from video-call screens).

---

## File Map (key files)

| File | Purpose |
|---|---|
| `app/src/main/java/com/motocallrecorder/MainActivity.kt` | UI: dialer, T9 search, call history, contacts, recordings. |
| `app/src/main/java/com/motocallrecorder/RecordService.kt` | Dual-capture engine: `createMicRecord()`, `mixPcm16()`, `tryMediaProjection()`. |
| `app/src/main/java/com/motocallrecorder/CallRecorderAccessibilityService.kt` | VoIP detection + 5 s end debounce. |
| `app/src/main/java/com/motocallrecorder/CallStateReceiver.kt` | `PHONE_STATE` listener with `READ_CALL_LOG` fallback. |
| `app/src/main/java/com/motocallrecorder/CallLogHelper.kt` | `CallLog.Calls` query (no row limit cap). |
| `app/src/main/java/com/motocallrecorder/ContactHelper.kt` | `PhoneLookup.CONTENT_FILTER_URI` name lookup. |
| `app/src/main/java/com/motocallrecorder/EnvironmentUtils.kt` | `Recorder/MotoCallRecorder/<Contact>/` directory. |
| `app/src/main/java/com/motocallrecorder/ProjectionGlobals.kt` | Static holder for MediaProjection token. |
| `app/src/main/java/com/motocallrecorder/Prefs.kt` | SharedPreferences (enabled, recordVoip, themeMode). |
| `app/src/main/res/layout/activity_main.xml` | Bottom-nav + tabs layout. |
| `app/src/main/res/layout/item_call_log.xml` | Call history row (Rounded card + voice/video buttons). |
| `app/src/main/res/drawable/dialer_btn_bg.xml` | Keypad ripple button. |
| `app/src/main/res/drawable/card_bg.xml` | 12 dp rounded card. |
| `app/src/main/res/drawable/filter_btn_bg.xml` | Pill-style filter chips (selected = blue). |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` / `…_background.xml` | Adaptive launcher icon. |
| `app/src/main/res/values/themes.xml` | `Theme.MotoCallRecorder` (dark) & `.Light`. |
| `app/src/main/res/values/colors.xml` | Blue palette + theme-specific palette. |
| `app/src/main/res/values/attrs.xml` | Custom `?attr/` references. |

---

## Build

```bash
./gradlew.bat assembleRelease      # release APK
./gradlew.bat assembleDebug        # debug APK
```

Release APK lands at:

```
app/build/outputs/apk/release/app-release.apk
```

Built and signed with the project's `motocallrecorder.jks` (storepass `android`, alias `motocallrecorder`).

---

## Known Caveats
- Carrier video calling only works if your SIM has IMS/ViLTE and the recipient supports it — the API request can be silently downgraded by the carrier.
- VoIP screens are recorded even if you start an *audio-only* VoIP call (accessibility service can't tell them apart).

---

## Credits
Developed by **Siva** · sivagaff@gmail.com · github.com/sivateam007
