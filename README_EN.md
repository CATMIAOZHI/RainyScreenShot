# 📸 Rainy Screenshot

**[简体中文](README.md) | English**

> *"Silent Capture, Full Control"*

An Android screenshot & screen recording app built on a privileged shell (dual backend: Shizuku / Porter) — no MediaProjection, no system dialogs, no status-bar recording indicator. The foreground app never knows it is being captured. Supports quick triggers and fully customizable capture parameters.

```
screencap/screenrecord (shell uid 2000, u:r:shell:s0)
        │  No MediaProjection authorization dialog
        │  No "screenshot blocked" prompts (FLAG_SECURE content is still black)
        │  No "screen sharing" indicator
        ▼
   RainyScreenShot gets the result exclusively
```

RainyScreenShot — Silent Screenshot & Screen Recorder · the Rainy Family tools.

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 🤫 **Silent screenshot** | `screencap` under shell uid reads the framebuffer directly; the foreground app gets no callback, no awareness, no "screenshot blocked" prompt (verified: `dumpsys media_projection` stays null during capture). If a window sets FLAG_SECURE its content appears blacked out — system-level content protection no approach can bypass |
| 🎬 **Silent recording** | `screenrecord` (v1.4) system encoder under shell uid; start/stop are just process SIGINT — no projection session registered, no notification, no indicator |
| ⚡ **Quick triggers** | Three ways: in-app buttons · floating control ball (OverlayManager TYPE_APPLICATION_OVERLAY) · Quick Settings tiles (TileService, works on lock screen). Tiles are added manually from the home page (one silent tap adds them to the panel — no edit mode needed); tapping a tile auto-collapses the panel first so the panel never appears in the shot |
| 🛠️ **Custom parameters** | Screenshot: PNG/RAW, display-id, multi-display `-a`. Recording: resolution `--size`, bitrate `--bit-rate` (default 8M), time limit `--time-limit` (0 = unlimited), bugreport overlay, `--display-id` |
| 🎯 **Region capture** | (Phase 2) pixel-crop the captured result, no root needed |
| ⏺️ **Floating control** | A translucent mini control ball while recording — tap to stop; can be hidden entirely, leaving tiles/app as the only stop paths |
| 🌙 **Dark mode** | Global Material You adaptive |
| 🔌 **Dual backend** | The shell channel is provided by Shizuku or Porter (an actively maintained Shizuku fork); switchable in Settings (Automatic / Porter / Shizuku) — Automatic prefers Porter; force-stop and reopen to apply |
| 🔐 **Fully local** | Zero network, zero telemetry; outputs in private app paths |

---

## 🔍 How the invisibility works (verified on Android 16 / API 36)

Conclusions from real-device testing (Shizuku shell uid 2000, `u:r:shell:s0`):

| Detection vector | MediaProjection approach | This project (direct shell) |
|------------------|--------------------------|------------------------------|
| System authorization dialog | Required every time (Android 14+ cannot skip) | ✅ None |
| `MediaProjectionManager` session registration | Yes — apps can sense it via `dumpsys media_projection` / API | ✅ Stays `null` throughout recording (verified) |
| Status-bar "recording/cast" icon | Yes (Android 14+ cannot hide) | ✅ No indicator |
| Foreground app callbacks | No direct API, but indirect signals exist (virtual Display creation, mic focus, etc.) | ✅ No callback path |
| FLAG_SECURE content | Blacked out | ⚠️ Also blacked out (system-level protection; the shell path cannot bypass it either). The difference: zero prompts, zero awareness for the target app |

> ⚠️ **Honest boundary**: this approach bypasses *conventional perceptible signals*. It does not modify the target app or inject any code. If the target app performs system-level self-checks via `dumpsys` (requires READ_LOGS/adb-level permission, which ordinary apps don't have) or applies digital watermarking/blur detection to its content, no recording approach can guarantee undetectability; FLAG_SECURE windows (banking apps, DRM video) are black on every capture path — that is system-level content protection and equally unbypassable. The goal achieved in practice: *every detection means available to an ordinary app fails*.

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────┐
│                 Android App                       │
│                                                   │
│  ┌─────────────────────────────────────────────┐ │
│  │        Compose UI                            │ │
│  │  Home(status) · History · Settings          │ │
│  └────────────────────┬────────────────────────┘ │
│                       │                           │
│  ┌────────────────────▼─────────────────────────┐ │
│  │     RecordingSessionManager (singleton)      │ │
│  │  start/stop · state machine · duration       │ │
│  └────────────────────┬────────────────────────┘ │
│                       │                           │
│  ┌────────────────────▼─────────────────────────┐ │
│  │        ShellCapture (core)                   │ │
│  │  Shizuku shell session · stdout piping       │ │
│  │  · SIGINT stop · exit polling/watching       │ │
│  └────────────────────┬────────────────────────┘ │
│                       │                           │
│  ┌────────────────────▼─────────────────────────┐ │
│  │  Triggers: App UI · OverlayManager ball      │ │
│  │           · TileService quick tiles          │ │
│  └──────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

**Key technical decisions:**

1. **Direct shell invocation instead of Shizuku Binder APIs**: `screencap`/`screenrecord` live in `/system/bin` and shell uid can execute them (verified). A persistent shell session is established via `Shizuku.newProcess()` (the SDK now ships through Porter's compatible layer while keeping the `rikka.shizuku.*` API, supporting both Shizuku and Porter backends); output streams back over stdout pipes and never touches public storage.
2. **Stop mechanism**: `screenrecord` has no stop parameter — sending `SIGINT` to the process finalizes the mp4 moov box (verified: SIGINT → clean, playable mp4). The same shell session holds the pid; stop = `kill -INT <pid>`.
3. **Session survival**: the Shizuku shell process is independent of the app process — killing the app mid-recording doesn't stop the capture; stop paths are the floating ball / tiles / time limit, triple-redundant.
4. **Silent design without foreground services**: the recording body runs under shell uid, so the app itself holds no foreground service and shows no notification (Android 14+ mandatory FGS notifications therefore never apply).

---

## 📁 Project structure

```
RainyScreenShot/
├── app/src/main/java/com/rainy/screenshot/
│   ├── capture/            # Core
│   │   ├── ShellExecutor.kt       # privileged shell session (Shizuku/Porter; stdout/exitcode/pid)
│   │   ├── ScreenshotEngine.kt    # screencap wrapper (PNG/RAW/display-id/-a)
│   │   ├── RecordingEngine.kt     # screenrecord wrapper (SIGINT stop/timeout)
│   │   └── CaptureConfig.kt       # capture parameter models
│   ├── session/
│   │   └── RecordingSessionManager.kt  # recording state machine + duration
│   ├── trigger/
│   │   ├── ScreenshotTileService.kt  # screenshot tile (collapse panel first)
│   │   └── RecordTileService.kt      # recording tile (collapse before toggle)
│   ├── overlay/
│   │   ├── FloatingBallService.kt    # floating control ball (OverlayManager)
│   │   └── FloatingBallController.kt # ball switch control (silent Shizuku grant)
│   ├── ui/
│   │   ├── home/          # Home: status card + quick capture + tile management
│   │   ├── history/       # Result browser (private app dirs)
│   │   ├── preview/       # Image/video preview (FileProvider sharing)
│   │   ├── settings/      # Parameter customization
│   │   └── theme/         # Rainy pink theme
│   └── data/
│       └── local/         # DataStore/SharedPreferences persistence
├── gradle/libs.versions.toml
└── docs/
    └── TECH_NOTES.md      # Field-test archive (command behavior, exit codes, limits)
```

---

## 🌐 Localization

Three languages built in — Simplified Chinese (default), Traditional Chinese, English. The app follows the system language automatically; on Android 13+ you can also pick one per-app in system settings (Languages → Rainy Screenshot).

---

## 🛠️ Build

```bash
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

**Runtime prerequisite**: Shizuku or Porter activated on the device (via ADB or Root) with permission granted to this app. The service backend can be switched in Settings (Automatic / Porter / Shizuku; force-stop and reopen to apply).

---

## 🐱 About

RainyScreenShot is a member of the "Rainy Family" tools, developed and maintained by [Rainy](https://github.com/CATMIAOZHI):

- [RainyLLM](https://github.com/CATMIAOZHI/RainyLLM) — fully offline on-device LLM inference server for Android
- [RainyScanner](https://github.com/CATMIAOZHI/RainyScanner) — Android QR scanner that never intercepts or redirects
- [Rainy2FA](https://github.com/CATMIAOZHI/Rainy2FA) — fully local, zero-network, biometric-protected TOTP authenticator
- [RainyToken](https://github.com/CATMIAOZHI/Rainytoken) — AI balance & usage monitor
- **RainyScreenShot** — silent screenshot & recording tool (this project)

> Comes quietly, records quietly 📷

---

## 📄 License

MIT License © 2026 Rainy