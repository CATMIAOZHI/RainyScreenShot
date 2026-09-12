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
   -d: specify the display ID to capture (If the id is not given, it defaults to the primary display)
       see "dumpsys SurfaceFlinger --display-id" for valid display IDs.
   -p: outputs in png format.
   --hint-for-seamless If set will use the hintForSeamless path in SF

If FILENAME ends in .png it will be saved as a png.
If FILENAME is not given, the results will be printed to stdout.
```

- 本机默认 display-id 为长整型硬件 ID（HWC display 0，port=147，pnpId=QCM，值已脱敏）；不同设备不同，运行时用 `dumpsys SurfaceFlinger --display-id` 探测，不硬编码。
- **stdout 输出路径已验证**：`screencap -p` 无文件名时输出到 stdout，可由 APP 管道接收 → 私有目录写入，全程不经过公共存储。
- 注意：API 36 上 `screencap -h` 攓不到 help 文本（stderr 输出且 exit code ≠ 0），解析 help 不可靠，用固定参数表。

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

1. **FLAG_SECURE**：FLAG_SECURE 窗口内容在**任何**捕获路径（含 shell uid）中均显示为黑块——系统级内容保护，无法绕过（与 MediaProjection 捕获结果相同，锁屏黑屏实测见 §8.3）。本方案相对 MediaProjection 的差异仅在于：全程不触发任何提示，目标 App 无法感知本次捕获。
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

---

## §11 本机构建环境的 git 行为（2026-09-05 实测）

**背景**：首次 commit 遇到 `invalid object` 失败。经 RainyToken 对照实验确认：**工作区内直接 `.git` 结构完全可用**（RainyToken 同款常态），失败与目录位置无关。

### 现象与机制

- Operit 工作区同步系统会拦截 git 写入的 loose object：真实内容落为 `.git/objects/xx/.l2s.tmp_obj_XXXXX.0001`（隐藏临时文件），对象路径 `xx/yyyy` 变为**符号链接**指向该临时文件（`/data/user/0/...` 绝对路径视图）。
- **符号链接是可读的**（内容完整）——只要 `.0001` 临时载体存在，`git cat-file`/`git log`/`git add`/`git commit` 全部正常。
- 失败的真正触发条件：**对象刚写入、同步系统正在接管/临时载体尚未就位的窗口期**，此时建树读取对象会得到 `invalid object`。实测一次 60 秒轮询（6 轮×10s）内 84 个对象仍全部处于符号链接态、未落定为真实文件——落定周期远大于秒级，**不能用「等待落定」作为可靠策略**。
- `git fsck --strict` 会把 `.l2s.tmp_obj_*` 临时文件报为 `bad sha1 file`（垃圾提示），**fsck 退出码仍为 0**，不代表对象损坏。

### 有效的可靠提交策略（已验证）

**策略 A（本次采用，最稳）：pack 传输绕过 loose object 写入**
```bash
# 1) 在 /root（proot rootfs）建裸仓库提交一次
git init --separate-git-dir=/root/rainy-repos/X.git -b main .
git add -A && git commit ... && git tag v0.1.0
# 2) 回工作区重建 .git 并 fetch —— 对象以 packfile 流式写入 + refs 为真实文件
rm .git && git init -b main .
git remote add origin /root/rainy-repos/X.git
git fetch origin main --tags && git reset --mixed FETCH_HEAD && git remote remove origin
```
fetch 的 packfile 写入不触发 loose object 的符号链接接管（验证：fsck 0 错误、refs 为真实文件、log/cat-file 正常）。

**策略 B（备选）：失败即重试**——若直接 `git init . && add && commit` 报 invalid object，`rm -rf .git` 重来，通常第二轮成功（临时载体就位后链路通）。本次实测第二轮在 /root 下直接成功。

### 禁忌（实测教训）

- ❌ **不要 `find .git/objects -name '.l2s.tmp_obj_*' -delete`**——`.0001` 临时载体是符号链接的目标，删掉即断链，所有对象变为不可读（本次第一次诊断后误删，导致 43 个对象全损，只能重建仓库）。
- ❌ 不要试图在工作区 git 路径下 `mv`/`cp` 手动恢复对象——同步系统对目录内新建文件同样接管。
- ⚠️ `git init` 默认 `core.bare=false`；若手动 `GIT_DIR=` 初始化裸仓再补 `core.worktree`，git 会拒绝（`core.bare and core.worktree do not make sense`），必须用 `git init --separate-git-dir=... .` 一步到位。

*实测时间：2026-09-05 · 雨晴喵 · git 化阶段*

---

## §12 悬浮窗权限经 Shizuku shell 静默授予（2026-09-05 实测）

**需求**：悬浮球 overlay 权限（SYSTEM_ALERT_WINDOW）不跳系统设置页，直接经 Shizuku 获取。

**实测结论**：shell uid 执行 `appops set <pkg> SYSTEM_ALERT_WINDOW allow` 可静默授予。

```bash
$ appops get com.rainy.screenshot SYSTEM_ALERT_WINDOW   # 初始：ignore
$ appops set com.rainy.screenshot SYSTEM_ALERT_WINDOW allow   # EXIT=0 ✅
$ cmd appops get com.rainy.screenshot SYSTEM_ALERT_WINDOW     # allow（生效）
```

- 应用未安装时也可 set（Android 16 / API 36 实测），安装后 `Settings.canDrawOverlays()` 返回 true。
- 语义：授予的是 appops 层权限位，`Settings.canDrawOverlays()` 即为复核手段。
- **App 侧实现**：`ShellExecutor.grantOverlayPermission()`（exec appops set），调用方在授予后必须再用 `Settings.canDrawOverlays(context)` 复核，避免 shell 侧成功但 app 侧未感知。
- 失败回落：Shizuku 不可用时仍走系统设置页引导（ACTION_MANAGE_OVERLAY_PERMISSION + package: URI）。

*实测时间：2026-09-05 · 雨晴喵 · 阶段 3 权限增强*

---

## §13 任务栏磁贴与面板控制（2026-09-07 实测）

**需求**：①截屏/录屏磁贴（TileService）出现在任务栏且点击结果干净（不拍到展开的面板）②磁贴默认不进任务栏，用户发现成本高。

**实测环境**：本机 Android 16 / API 36（MIUI 系），shell uid 2000（`u:r:shell:s0`）——与 APP 经 Shizuku 的执行环境一致。

### cmd statusbar 能力清单（`cmd statusbar` help 实测输出节选）

| 命令 | 作用 | 实测结果 |
|------|------|----------|
| `cmd statusbar add-tile <pkg>/<cls>` | 把 TileService 磁贴加进任务栏 | ✅ EXIT=0，磁贴真实进入 `sysui_qs_tiles`（排在列表首位） |
| `cmd statusbar remove-tile <pkg>/<cls>` | 移除磁贴 | ✅ EXIT=0，列表恢复原状 |
| `cmd statusbar collapse` | 收起通知/快捷设置面板 | ✅ EXIT=0，幂等（面板已收起时调用同样 0） |
| `cmd statusbar check-support` | QS API 支持探测 | ✅ 返回 true |

### 关键实测结论

1. **add-tile 幂等**：对同一组件重复 add-tile，列表中始终只有一项（实测连加两次 count 不变）。
2. **组件名缩写存储**：`sysui_qs_tiles` 中第三方磁贴以 `custom(pkg/.ShortClass)` 缩写格式存储（包名前缀剥离）——**判断磁贴在列必须匹配缩写格式**，完整组件名 `contains()` 会漏判（工程实现 hasQuickSettingsTile 对两种格式都检查）。
3. **collapse 生效验证**：`expand-settings` 后 `mCurrentFocus=NotificationShade`；`collapse` 后约 300ms 焦点回到前台 App，且后续截图画面干净（普通前台 App 内容）。
4. **collapse 幂等**：面板已收起时调用同样 EXIT=0 → 点击磁贴可无条件发出，失败忽略即可。

### 诚实记录的存疑点

- 本机（MIUI 系）`expand-settings` 后立即 screencap 拍到的仍是前台 App 而非展开面板——该 ROM 上 expand 或 shade 层截取机制未完全验证。**代码按标准 Android 行为保守处理**：磁贴点击一律 `collapse` + 500ms 停顿后再截（停顿值：焦点复位实测约 300ms，取 500ms 兼容厂商动画偏慢）。
- 上述 300ms/500ms 未在全部 ROM 家族验证（遵循不真机验证约定，跨设备以保守值为准）。

### 工程决策

- **磁贴点击链路**：截屏磁贴 = collapse → 500ms → screenshotQuick()；录屏磁贴 = collapse → 500ms → 启停（防第一帧/收尾几帧录到面板）+ 点击时状态快照（停顿窗口内被 time-limit 自动收尾时不误开新录制）。
- **磁贴添加 = 纯手动**（水晴决策，v0.1.1 调整）：不自动注入任务栏，入口放主页「任务栏磁贴」卡（截屏/录屏两行，状态探测 hasQuickSettingsTile + 添加/移除按钮）。理由：任务栏是用户领地，未经用户明确操作不应被动变化（开发期一次实测残留被误认为新功能，反证了这一点）。
- **添加命令经 Shizuku 静默执行**：手动点「添加」后 add-tile 无弹窗无跳转（与悬浮球权限 appops 同思路），但触发时机完全由用户掌控。

*实测时间：2026-09-07 · 雨晴喵 · 磁贴功能阶段*

---

## §14 磁贴图标的 SystemUI 单色化渲染（2026-09-07 水晴实测截图）

**需求**：磁贴图标需与系统截屏/录屏磁贴区分（v1「相机/录制环」与系统混淆；v3「粉/红取景框」改为彩色设计）——但实测证明颜色方案在系统层失效。

**现象**：快捷设置面板中磁贴图标被 SystemUI 单色化渲染为白色。v3 的粉（#FF85A2）/ 红（#F4666F）原色（已去 `android:tint`）在磁贴显示时全部呈现为白色剪影，两枚彩色磁贴退化成「白框+白点」无法区分（水晴快捷面板截图，2026-09-07）。

**结论（设计约束）**：

1. **磁贴图标不得依赖颜色**承担区分或品牌识别——差异必须落在剪影形状上（白化后依然成立）。当前 v5 先例：公共隐私徽标（顶部「眼睛+斜杠」，两枚磁贴同构，表达静默/隐身）+ 中心形状区分（截屏=小圆点 Ø4.0，录屏=大圆角方块 8.8 见方，尺寸差 2.2 倍）。
2. **去掉 `android:tint` 仅保证 vector 自渲染不被染色**；SystemUI 层的单色化处理无公开 API 可绕过（MIUI 系实测）。平台原生（AOSP）磁贴是否有同样行为未验证。
3. **尺寸口径**：磁贴图标注释/几何计算须按「含 stroke 外缘」核算（外框 r=1.6 + stroke 1.0 = 视觉半径 2.1）。仅按 path 半径核算会把贴边/熔接漏算（v5 审计实测教训：初版徽标声明 Ø4.0，视觉实为 Ø5.2 且顶部贴 viewport 边）。

*实测时间：2026-09-07 · 证据来源：水晴快捷面板截图 · 磁贴图标阶段*
## §15 Porter 原生后端集成

- Porter SDK 版本：`com.github.d4rken-org.porter-api:client:0.1.0`。
- 项目移除直接的 `dev.rikka.shizuku:api` / `dev.rikka.shizuku:provider` 依赖，改由 Porter SDK 提供兼容的 `rikka.shizuku.*` API 与 Binder 接口。
- Manifest 同时声明 Porter 与 Shizuku 权限/包可见性，并使用 `PorterProvider` + `SelectedShizukuProvider`；保留 `${applicationId}.shizuku` authority 供 SDK 内部 Binder lookup。
- 后端支持 `AUTO` / `PORTER` / `SHIZUKU`：
  - `AUTO` 优先 Porter；未安装 Porter 时使用 Shizuku。
  - 显式 `PORTER` 不会因为 Porter 停止而偷偷切到 Shizuku。
  - 显式 `SHIZUKU` 始终使用 Shizuku。
- `PorterClient.setBackendForNextProcess()` 只影响下一次进程启动。设置页保存成功后必须完整 force-stop 应用，再重新打开；不能通过 Activity recreation 切换正在运行的 Binder 后端。
- 运行态仍通过 `Shizuku.pingBinder()` 判断实时连接，通过 `Shizuku.checkSelfPermission()` 判断当前选中后端的授权；Porter 选中时这些兼容调用由 Porter 接管。
