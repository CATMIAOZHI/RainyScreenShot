# TECH_NOTES — Shell 截屏/录屏实测记录

> 实测环境：本机 Android 16（API 36），arm64-v8a，Shizuku shell uid 2000（`u:r:shell:s0`）。
> 所有结论均来自真实执行验证，非文档推断。规划阶段产生，开发阶段持续补充。

---

## 1. 可用命令清单（已验证）

| 命令 | 路径 | 权限 | 实测结果 |
|------|------|------|----------|
| `screencap` | `/system/bin/screencap` | shell 可执行 | ✅ `-p` 输出 PNG（845KB @1440x3200） |
| `screenrecord` | `/system/bin/screenrecord` | shell 可执行 | ✅ 录制正常，mp4 可播放 |
| `kill -INT` | 系统自带 | shell uid 可向自己启动的进程发信号 | ✅ SIGINT 后 mp4 正常定稿 |

## 2. screencap 参数（`screencap -h` 实测输出）

```
usage: screencap [-ahp] [-d display-id] [FILENAME]
   -h: this message
   -a: captures all the active displays. This appends an integer postfix to the FILENAME.
       e.g., FILENAME_0.png, FILENAME_1.png. If both -a and -d are given, it ignores -d.
   -d: specify the display ID to capture (If the id is not given, it defaults to 4630946338122044307)
       see "dumpsys SurfaceFlinger --display-id" for valid display IDs.
   -p: outputs in png format.
   --hint-for-seamless If set will use the hintForSeamless path in SF

If FILENAME ends with .png it will be saved as a png.
If FILENAME is not given, the results will be printed to stdout.
```

- 本机默认 display-id：`4630946338122044307`（HWC display 0，port=147，pnpId=QCM）
- **stdout 输出路径已验证**：`screencap -p` 无文件名时输出到 stdout，可由 APP 管道接收 → 私有目录写入，全程不经过公共存储。
- 注意：API 36 上 `screencap -h` 输出到 stderr 且 exit code ≠ 0，解析 help 文本不可靠，用固定参数表。

## 3. screenrecord 参数（`screenrecord --help` 实测输出）

```
Usage: screenrecord [options] <filename>
Android screenrecord v1.4.  Records the device's display to a .mp4 file.

Options:
--size WIDTHxHEIGHT        Set the video size, e.g. "1280x720".  Default is the device's main display resolution (if supported), 1280x720 if not.
--bit-rate RATE            Set the video bit rate, in bits per second. Value may be specified as bits or megabits, e.g. '4000000' is equivalent to '4M'.  Default 20Mbps.
--bugreport                Add additional information, such as a timestamp overlay
--time-limit TIME          Set the maximum recording time, in seconds.  Default is 180.  Set to 0 to remove the time limit.
--display-id ID            specify the physical display ID to record.  Default is the primary display.
--verbose                  Display interesting information on stdout.
--version
--help
```

- **无 `-o`、无 stop 参数**。停止方式只有两种：`--time-limit` 到时自动停，或向进程发 **SIGINT**。
- **无音频**（help 全文无 audio 相关选项；Android 官方 screenrecord 亦不带内录功能）。

## 4. 录屏停止机制实测（关键验证）

```bash
# 后台启动 60s 录制
$ screenrecord --time-limit 60 --bit-rate 8M rainy_rec_test.mp4 &
# 5s 后：进程存活（PID 15955），文件 1.87MB 增长中
$ kill -INT 15955
# 3s 后：进程消失，文件 5.81MB 定稿 → SIGINT 正常完成 moov box 收尾，视频可播放
```

**结论**：停止录屏 = 向 screenrecord 进程发 SIGINT。APP 实现时：
- 同一 shell 会话启动录制 → `sh` 记录 `$!` 拿到 pid（或 `pgrep -f` 定位）→ stop 命令 `kill -INT <pid>`。
- 兜底：`--time-limit` 最大值自动停止；检测进程退出（`ps` 轮询 / 退出码上报）→ APP 更新状态。

## 5. MediaProjection 状态实测（隐身性验证）

```bash
# 录屏进行中执行：
$ dumpsys media_projection
MEDIA PROJECTION MANAGER (dumpsys media_projection)
Media Projection:
null

# 录屏进行中同步截屏（screencap）也正常产出：
$ screencap -p → 1.0MB PNG 成功
```

**结论**：shell 路径录屏全程**不注册 MediaProjection 会话**，状态栏无「投屏/录屏」图标，目标 App 无任何可感知回调。对比 MediaProjection 方案（Android 14+ 强制弹窗 + 强制状态栏指示）为本质优势。

## 6. 存储路径实测（App ↔ shell 协作）

| 路径 | shell 写入 | App 读取 | 用途 |
|------|-----------|---------|------|
| `/sdcard/Android/data/<pkg>/files/` | ✅ 实测成功（uid shell 组含 `ext_data_rw`） | ✅ App 自己的沙箱目录 | v1 首选输出目录（App 无需存储权限） |
| `/data/local/tmp/` | ✅ shell 专用 | ❌ App 无法读 | 备用/中转 |

- 注意：`/sdcard/Android/data/<pkg>` 在设备重启或应用卸载重装后依然由系统管理，App 只需读自己 uid 的目录。跨 uid 场景（shell 写 → app 读）实测通畅。
- `screencap -p` stdout 管道方案可完全绕过文件路径可见性问题（v1.1 优化项）。

## 7. 退出码与异常路径（实测）

- `screenrecord --help`、`screencap -h`：stderr 输出 + 非零退出码 → 解析 help 不可靠
- `screenrecord` 直接前台运行 + 部分重定向写法会被 Android `sh` 拒绝（`illegal file descriptor name`）→ APP 中统一用 `sh -c` 包裹 + 完整 `1> 2>&1` 语法
- 通过本工具链（super_admin:shell）执行含特殊字符的命令时，管道/重定向需整体传入单个 `sh -c`，否则部分调用返回空输出。→ APP 内实现时以 Process API 为准，不依赖 help 文本

## 8. 边界与已知限制

1. **FLAG_SECURE**：shell uid 捕获**不受** FLAG_SECURE 影响且不通知目标 App（与 MediaProjection 相同的捕获内容），差异在于目标 App 无法通过窗口层 API 感知本次捕获。
2. **音频内录**：`screenrecord` 无音频能力（实测无此参数）。如未来需要，方案 = 拉起系统 root 后用 `aplay` 内录或接入 MediaProjection 仅音频（会破坏隐身性），v1 不做。
3. **锁屏期间录制**：shell 命令在锁屏下可执行（Shizuku 保持运行），但受 FLAG_SECURE 影响的 keyguard 内容可能全黑，录制的其他内容正常。
4. **系统级自检**：目标 App 若通过 `dumpsys` 轮询（需 READ_LOGS 级权限，普通 App 不具备）或画面数字水印检测，任何方案都无法保证不可检测。本方案目标 = 常规检测手段全部失效。
5. **Shizuku 服务死亡**：shell 会话随 Shizuku 进程终止而终止 → 录制进程可能仍在系统侧继续运行。RecordingEngine 通过存活轮询（`ps -p <pid> -o comm=`，三态探测，见 §10.5）感知进程退出并回调 APP 更新状态（防「以为还在录实际已死」）。

## 9. 编译期实测发现（Shizuku API 13.1.5，javap 验证）

- `rikka.shizuku.Shizuku.newProcess(...)` 是 **private static**（javap -p 确认），不能直接调用。
- 正确路径：公开 AIDL 接口 `moe.shizuku.server.IShizukuService.newProcess(String[] cmd, String[] env, String dir)` → 返回 `IRemoteProcess`（IShizukuService.Stub.asInterface(Shizuku.getBinder())）。
- `rikka.shizuku.ShizukuRemoteProcess` 构造器为包私有，无法复用；项目自建 `RemoteProcessAdapter`（Process 子类，ParcelFileDescriptor 包装 stdin/stdout/stderr）。
- `dev.rikka.shizuku:api:13.1.5` 传递依赖 `aidl:13.1.5`（编译期可见），无需额外声明。
- `IRemoteProcess.alive` 在 Kotlin 中需显式调用 `alive()`（AIDL getter 语义差异）。
- manifest 中不存在 `<uses-permission-sdk-30-and-up>` 元素（AAPT 报错实测），`<queries><package>` 声明后无需 QUERY_ALL_PACKAGES。

## 10. 会话进程模型实测（审计阶段补充，重要）

**场景**：`sh -c "cd /sdcard/Download && screenrecord --time-limit 60 --bit-rate 8M rainy_pgrep_test.mp4 &"`（模拟 Shizuku.newProcess 的 sh -c 启动方式）

实测进程树：
```
shell 28889 (sh)          cmdline = sh -c "cd ... && screenrecord ... rainy_pgrep_test.mp4 &"  ← 父 sh **不退出**，sigsuspend 挂起等待
└── shell 28890 (screenrecord)  cmdline = screenrecord --time-limit 60 --bit-rate 8M rainy_pgrep_test.mp4
```

关键结论：
1. **父 sh 不退出**：`sh -c "cmd &"` 模式下 sh 以 sigsuspend 等待后台任务，不是孤儿收养模型。APP 被杀后整个进程树仍在。
2. **pgrep -f 自匹配**：父 sh 的 cmdline 含模式文本 → `pgrep -f rainy_xxx.mp4` 返回**两行**（sh pid + screenrecord pid）。取 `firstOrNull` 会拿到 sh 的 pid！
3. **SIGINT 正确对象 = screenrecord 进程**：`kill -INT <screenrecord_pid>` 实测 mp4 正常定稿。若误发给 sh：sh 死亡会连带 screenrecord（信号进程组语义/管道关闭），mp4 收尾行为未验证且不可靠。
4. **正则技巧防自匹配**：`pgrep -f 'screenrecord.*rainy_pgrep_test'` 仍会匹配 sh cmdline（cmdline 含 screenrecord 字样）。正确方案：`pgrep -f 'screenrecor[d].*rainy_pgrep_test'`（字符类破坏自匹配）或取全部结果后用 `/proc/<pid>/cmdline` 校验首字段为 screenrecord。
5. **kill -0 exitCode 不可靠**：组合命令下 exit code 传递不稳定（实测部分调用返回空输出）。`isProcessAlive` 应改用 `ps -p <pid> -o comm=` 文本匹配或 `/proc/<pid>` 探测，明确区分「确认死亡」与「无法检测」。
6. **cmdline 读取**：`/proc/<pid>/cmdline` shell uid 可读（实测），字段以 `\0` 分隔，首个字段即进程名，可精确判定 screenrecord。

**工程决策（v1.0.1 修复）**：
- RecordingEngine 改用「取 pgrep 全量结果 → 逐个读 /proc/<pid>/cmdline 校验首字段 == screenrecord」定位真实 pid；
- 不再用 `sh -c "... &"` 包裹（父 sh 挂起徒增一层进程与一个污染源），直接 `screenrecord ... &` 由 AIDL 层 `IShizukuService.newProcess(arrayOf("sh","-c", cmd))` 中 sh 自然等待；
- isProcessAlive 改为 `ps -p <pid> -o comm=` 精确校验进程名。

---

*实测时间：2026-09-05 · 雨晴喵 · 规划阶段*