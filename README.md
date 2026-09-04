# 📸 雨晴截屏

> *"悄然记录，尽收眼底 — Silent Capture, Full Control"*

基于 Shizuku shell 的 Android 截屏/录屏 APP —— 不经 MediaProjection、无系统弹窗、无状态栏摄像头指示，前台 App 完全无感知被截屏/录屏。支持快捷触发与完全自定义的截屏/录屏参数。

```
screencap/screenrecord (shell uid 2000, u:r:shell:s0)
        │  不触发 MediaProjection 授权弹窗
        │  不触发 FLAG_SECURE 检测（非 Window 层路径）
        │  不触发「屏幕共享中」指示器
        ▼
   RainyScreenShot 独享结果
```

RainyScreenShot（雨晴截屏）— Silent Screenshot & Screen Recorder · the Rainy Family tools.

---

## ✨ 功能特性

| 特性 | 说明 |
|------|------|
| 🤫 **静默截屏** | shell uid `screencap` 直取 framebuffer，前台 App 无任何回调与感知；绕过 FLAG_SECURE 对自身弹窗的依赖面（实测：录屏期间 `dumpsys media_projection` 始终为 null） |
| 🎬 **静默录屏** | shell uid `screenrecord`（v1.4）系统级编码器；开始/停止仅表现为进程 SIGINT，无投屏会话注册、无通知指示 |
| ⚡ **快捷触发** | 三方式：APP 内按钮 · 悬浮控制球（OverlayManager TYPE_APPLICATION_OVERLAY）· 快捷磁贴（TileService，锁屏可用） |
| 🛠️ **自定义参数** | 截屏：PNG/RAW、指定 display-id、多显示器 `-a`。录屏：分辨率 `--size`、码率 `--bit-rate`（默认 8M）、时长 `--time-limit`（0=不限）、bugreport 时间戳叠加、`--display-id` |
| 🎯 **区域截屏** | （阶段二）对截取结果做像素裁剪，无需 root |
| ⏺️ **悬浮控制** | 录屏时显示半透明小控制球，点击即停；可整体隐藏，仅靠磁贴/APP 停止 |
| 🌙 **深色模式** | 全局 Material You 自适应 |
| 🔐 **纯本地** | 零联网、零权限上报；MediaStore 私有路径产出 |

---

## 🔍 隐身原理（实测于 Android 16 / API 36）

本机（shizuku shell uid 2000，`u:r:shell:s0`）实测结论：

| 检测维度 | MediaProjection 方案 | 本项目方案（shell 直调） |
|----------|--------------------|------------------------|
| 系统授权弹窗 | 每次需确认（Android 14+ 默认不可跳过） | ✅ 无弹窗 |
| `MediaProjectionManager` 会话注册 | 有，App 可通过 `dumpsys media_projection` / API 感知 | ✅ 录屏全程实测为 `null` |
| 状态栏「录屏/投屏」图标 | 有（Android 14+ 不可隐藏） | ✅ 无任何指示 |
| 前台 App 感知回调 | 无直接 API，但存在间接信号（虚拟 Display 创建、麦克风焦点等） | ✅ 无回调路径 |
| FLAG_SECURE 内容 | 显示为黑块 | ✅ 正常捕获（shell 路径不受影响） |

> ⚠️ **边界如实说明**：本方案绕过的是「常规可感知信号」。它不修改目标 App、不注入任何代码；如目标 App 通过 `dumpsys` 系统级自检（需 READ_LOGS/adb 权限，普通 App 不具备）或对画面内容做数字水印/模糊检测，任何录屏方案都无法保证不可检测。方案目标 = 「普通 App 的常规检测手段全部失效」，这在实测中已成立。

---

## 🏗️ 技术架构

```
┌──────────────────────────────────────────────────┐
│                 Android App                       │
│                                                   │
│  ┌─────────────────────────────────────────────┐ │
│  │        Compose UI                            │ │
│  │  首页(截屏/录屏状态) · 历史记录 · 设置       │ │
│  └────────────────────┬────────────────────────┘ │
│                       │                           │
│  ┌────────────────────▼─────────────────────────┐ │
│  │        RecordingSessionManager（单例）         │ │
│  │  start/stop · 状态机 · 时长统计 · 输出路径     │ │
│  └────────────────────┬────────────────────────┘ │
│                       │                           │
│  ┌────────────────────▼─────────────────────────┐ │
│  │        ShellCapture（核心）                    │ │
│  │  Shizuku shell 会话 · stdout 管道取帧          │ │
│  │  · SIGINT 停止录制 · 轮询/监听进程退出         │ │
│  └────────────────────┬────────────────────────┘ │
│                       │                           │
│  ┌────────────────────▼─────────────────────────┐ │
│  │   触发层：App UI · OverlayManager 悬浮球       │ │
│  │           · TileService 快捷磁贴             │ │
│  └─────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

**关键技术决策：**

1. **shell 直调而非 Shizuku Binder API**：`screencap`/`screenrecord` 位于 `/system/bin`，shell uid 具备执行权（实测通过）。通过 `Shizuku.newProcess()`（`dev.rikka.shizuku:api`）建立持久 shell 会话，输出经 stdout 管道回传，不落公共目录。
2. **录屏停止机制**：`screenrecord` 无 stop 参数，靠向目标进程发 `SIGINT` 完成容器 moov box 定稿（实测：进程 SIGINT → mp4 正常关闭、可播放）。通过同会话 shell 持有 pid，`kill -INT <pid>` 停止。
3. **会话保活**：Shizuku shell 进程与 APP 进程独立，录屏期间 APP 被杀不影响录制；停止靠悬浮球/磁贴/超时三重兜底。
4. **无前台服务的静音设计**：录屏执行体在 shell uid 中，APP 自身不持前台服务、不显示任何通知（Android 14+ 对 FGS 的强制通知因此不适用）。

---

## 📁 项目结构

```
RainyScreenShot/
├── app/src/main/java/com/rainy/screenshot/
│   ├── capture/            # 核心
│   │   ├── ShellExecutor.kt       # Shizuku shell 会话管理（stdout/exitcode/pid）
│   │   ├── ScreenshotEngine.kt    # screencap 封装（PNG/RAW/display-id/-a）
│   │   ├── RecordingEngine.kt     # screenrecord 封装（SIGINT 停止/超时兜底）
│   │   └── CaptureConfig.kt       # 截屏/录屏参数模型
│   ├── session/
│   │   └── RecordingSessionManager.kt  # 录制会话状态机 + 时长统计
│   ├── trigger/
│   │   ├── OverlayControlBall.kt  # 悬浮控制球（OverlayManager）
│   │   └── CaptureTileService.kt  # 快捷磁贴
│   ├── ui/
│   │   ├── home/          # 首页：状态卡 + 快捷截屏/录屏
│   │   ├── history/       # 结果浏览（私有 MediaStore/应用目录）
│   │   ├── settings/      # 参数自定义页
│   │   └── theme/         # 雨晴粉主题
│   └── util/
│       └── ShizukuState.kt      # Shizuku 权限引导/状态监听
├── gradle/libs.versions.toml
└── docs/
    └── TECH_NOTES.md      # 实测记录归档（命令行为、退出码、边界）
```

---

## 🛠️ 构建

```bash
# 与 RainyToken 相同的 ARM64 Proot 兼容配置
./gradlew assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

**运行前提**：设备已激活 Shizuku（ADB 或 Root 方式），且已授权本应用。

---

## 🐱 关于

RainyScreenShot（雨晴截屏）是「雨晴系列」工具成员，由 [雨晴喵](https://github.com/CATMIAOZHI) 开发维护：

- [RainyLLM](https://github.com/CATMIAOZHI/RainyLLM) — 纯离线 Android 本地 LLM 推理服务器
- [RainyScanner](https://github.com/CATMIAOZHI/RainyScanner) — 不拦截不跳转的 Android 扫码工具
- [Rainy2FA](https://github.com/CATMIAOZHI/Rainy2FA) — 纯本地 · 零联网 · 生物识别保护的 TOTP 验证器
- [RainyToken](https://github.com/CATMIAOZHI/Rainytoken) — AI 余额与用量查询 APP
- **RainyScreenShot** — 静默截屏/录屏工具（本项目）

> 静悄悄地来，静悄悄地录 📷

---

## 📄 License

MIT License © 2026 Rainy