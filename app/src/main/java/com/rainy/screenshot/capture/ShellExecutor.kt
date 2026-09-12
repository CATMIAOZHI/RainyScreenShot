package com.rainy.screenshot.capture

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.porter.client.PorterClient
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/**
 * Shizuku shell 执行器 —— capture 层基石。
 *
 * 设计要点（实测结论见 docs/TECH_NOTES.md）：
 * - RemoteProcessAdapter（公开 AIDL）在 shell uid 2000 中创建进程
 * - exec 超时用「独立 waitFor 线程 + 轮询回收」实现（B2 修复：
 *   waitFor 是阻塞 binder 调用，withTimeout 无法取消它）
 * - stdout/stderr 重定向必须用完整 `1>path 2>&1` 语法（§7）
 */
@Singleton
class ShellExecutor @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    companion object {
        /** 截屏命令默认超时（screencap 单帧操作，10s 足够） */
        const val SCREENSHOT_TIMEOUT_MS = 10_000L

        /** Shizuku APK 包名 */
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** 任务栏/面板操作命令超时（settings/cmd 本地操作，5s 足够） */
        private const val TILE_OP_TIMEOUT_MS = 5_000L
    }

    // ─────────────────────────────────────────────
    // 环境状态 / Porter + Shizuku 双后端
    // ─────────────────────────────────────────────

    /** Shizuku 是否已安装（用于显式 Shizuku 后端的发现/引导）。 */
    fun isShizukuInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Porter 是否已安装/可用。 */
    fun isPorterInstalled(): Boolean =
        runCatching { PorterClient.getPorterPackage(appContext) != null }.getOrDefault(false)

    /** 当前进程实际选择的后端。AUTO 会解析为当前优先使用的服务。 */
    fun activeBackend(): ServiceBackend =
        runCatching {
            when (PorterClient.getActiveBackend(appContext)) {
                PorterClient.Backend.PORTER -> ServiceBackend.PORTER
                PorterClient.Backend.SHIZUKU -> ServiceBackend.SHIZUKU
                PorterClient.Backend.AUTO -> ServiceBackend.AUTO
            }
        }.getOrDefault(ServiceBackend.AUTO)

    /**
     * 保存下一次进程启动使用的后端。
     *
     * Porter 官方要求该设置在非主线程调用；设置不会切换当前已初始化进程，
     * 用户需要完整 force-stop 后重新打开应用。
     */
    fun setBackendForNextProcess(backend: ServiceBackend): Boolean =
        when (backend) {
            ServiceBackend.AUTO -> PorterClient.setBackendForNextProcess(
                appContext, PorterClient.Backend.AUTO
            )
            ServiceBackend.PORTER -> PorterClient.setBackendForNextProcess(
                appContext, PorterClient.Backend.PORTER
            )
            ServiceBackend.SHIZUKU -> PorterClient.setBackendForNextProcess(
                appContext, PorterClient.Backend.SHIZUKU
            )
        }

    /** 当前选中的服务是否已安装。 */
    fun isSelectedBackendInstalled(): Boolean =
        when (activeBackend()) {
            ServiceBackend.PORTER -> isPorterInstalled()
            ServiceBackend.SHIZUKU -> isShizukuInstalled()
            ServiceBackend.AUTO -> isPorterInstalled() || isShizukuInstalled()
        }

    /** 当前选中的服务是否在线。pingBinder 反映的是实时连接，而非缓存授权状态。 */
    fun isSelectedBackendRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 当前选中的服务是否已授权本应用。 */
    fun isSelectedBackendGranted(): Boolean =
        runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 当前选中的服务是否已安装、在线并授权。 */
    fun isEnvironmentReady(): Boolean =
        isSelectedBackendInstalled() &&
            isSelectedBackendRunning() &&
            isSelectedBackendGranted()

    /**
     * 请求当前选中后端的访问授权。
     * Porter 选中时仍使用兼容的 Shizuku API，由 Porter 接管授权流程。
     */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /** @deprecated Use [isSelectedBackendRunning]. */
    @Deprecated("Use isSelectedBackendRunning")
    fun isShizukuRunning(): Boolean = isSelectedBackendRunning()

    /** @deprecated Use [isSelectedBackendGranted]. */
    @Deprecated("Use isSelectedBackendGranted")
    fun isShizukuGranted(): Boolean = isSelectedBackendGranted()

    // ─────────────────────────────────────────────
    // 进程创建
    // ─────────────────────────────────────────────

    /**
     * 在 Shizuku shell 中创建一个新进程（不等待完成）。
     *
     * 注意：返回的 [Process] 持有 3 个 ParcelFileDescriptor，
     * 调用方**必须**在合适时机 destroy()（关闭 fd），否则泄漏。
     *
     * @throws ShellException.NotInstalled / NotRunning / NotGranted / Execution
     */
    fun newProcess(command: String): Process {
        checkEnvironment()
        return try {
            RemoteProcessAdapter.create(arrayOf("sh", "-c", command))
        } catch (e: IllegalStateException) {
            throw ShellException.NotRunning()
        } catch (e: ShellException) {
            throw e
        } catch (e: Exception) {
            throw ShellException.Execution(-1, e.message ?: "newProcess failed")
        }
    }

    /**
     * 同步执行 shell 命令并收集结果。
     *
     * 超时实现（B2 修复）：waitFor 在独立线程执行，超时后主协程
     * destroy() 进程（远端 kill + 关 fd），waitFor 线程随 fd 关闭退出。
     *
     * @param timeoutMs 超时（超时抛 [ShellException.Execution]）
     */
    suspend fun exec(
        command: String,
        timeoutMs: Long = SCREENSHOT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        checkEnvironment()
        val process: Process = try {
            RemoteProcessAdapter.create(arrayOf("sh", "-c", command))
        } catch (e: IllegalStateException) {
            throw ShellException.NotRunning()
        } catch (e: ShellException) {
            throw e
        } catch (e: Exception) {
            throw ShellException.Execution(-1, e.message ?: "exec failed")
        }

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread {
            runCatching { process.inputStream.copyTo(stdout) }
        }
        val errThread = Thread {
            runCatching { process.errorStream.copyTo(stderr) }
        }
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        // waitFor 在守护线程执行（阻塞 binder 调用不受协程取消影响，B2）
        val waiter = Thread {
            runCatching { process.waitFor() }
        }
        waiter.isDaemon = true
        waiter.start()

        try {
            withTimeout(timeoutMs) {
                // 轮询等待 waitFor 线程结束（每 100ms，可被协程取消）
                while (waiter.isAlive) {
                    kotlinx.coroutines.delay(100)
                }
            }
            outThread.join(2000)
            errThread.join(2000)
            ShellResult(
                exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
                stdout = stdout.toString("UTF-8"),
                stderr = stderr.toString("UTF-8")
            )
        } catch (e: TimeoutCancellationException) {
            throw ShellException.Execution(-1, "timeout after ${timeoutMs}ms: $command")
        } finally {
            // 无论如何释放远端进程与本地 fd（B5 修复）
            runCatching { process.destroy() }
            outThread.join(1000)
            errThread.join(1000)
        }
    }

    /**
     * 校验环境就绪，不满足时抛出对应可恢复异常。
     */
    private fun checkEnvironment() {
        val backend = activeBackend()
        if (!isSelectedBackendInstalled()) throw ShellException.NotInstalled(backend)
        if (!isSelectedBackendRunning()) throw ShellException.NotRunning(backend)
        if (!isSelectedBackendGranted()) throw ShellException.NotGranted(backend)
    }

    /**
     * 生成输出文件（App 私有外部目录）。
     *
     * shell uid 与 app uid 对该目录均有读写权（TECH_NOTES §6 实测）。
     */
    fun newOutputFile(subDir: String, fileName: String): File {
        val dir = File(
            appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            subDir
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }

    /**
     * App 私有外部目录根（历史页扫描用）。
     * shell uid 与 app uid 对该目录均有读写权（TECH_NOTES §6 实测）。
     */
    fun outputRootDir(): File =
        appContext.getExternalFilesDir(null) ?: appContext.filesDir

    /**
     * 通过 Shizuku shell 静默授予本应用悬浮窗权限。
     *
     * 实测依据（§12）：shell uid 执行
     * `appops set <pkg> SYSTEM_ALERT_WINDOW allow` 可绕过系统设置页
     * 直接授予 overlay 权限（本机 Android 16 / API 36 实测 EXIT=0）。
     * 返回后 `Settings.canDrawOverlays()` 即返回 true。
     *
     * @return 命令执行成功（exit 0）与否。注意：仅代表 shell 侧授予成功，
     *         应为 app 侧随后用 Settings.canDrawOverlays() 复核。
     */
    suspend fun grantOverlayPermission(): Boolean {
        val pkg = appContext.packageName
        return runCatching {
            val result = exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            result.exitCode == 0
        }.getOrDefault(false)
    }

    // ─────────────────────────────────────────────
    // 任务栏磁贴 / 快捷设置面板操作（§13 实测）
    // ─────────────────────────────────────────────

    /**
     * 收起快捷设置/通知面板（磁贴点击路径防面板入镜，§13 实测）。
     *
     * - `cmd statusbar collapse`：面板已收起时调用同样 EXIT=0（幂等），
     *   任何场景无条件发出、失败忽略（不阻断截屏主链路）
     * - 通知面板展开时截图必然把面板拍进去——磁贴入口必须先收起再截
     *
     * @return 命令执行成功（exit 0）与否。失败也不阻断调用方主链路。
     */
    suspend fun collapseQuickSettings(): Boolean {
        return runCatching {
            exec("cmd statusbar collapse", TILE_OP_TIMEOUT_MS).exitCode == 0
        }.getOrDefault(false)
    }

    /**
     * 把磁贴添加到任务栏（快捷设置面板）。
     *
     * 实测依据（§13，shell uid 2000）：`cmd statusbar add-tile <pkg>/<cls>`
     * EXIT=0 且磁贴真实进入 `sysui_qs_tiles`；重复调用幂等（列表无重复项）。
     * 解决 TileService 的原生痛点：磁贴默认不出现在任务栏，需要用户
     * 手动进编辑模式添加，发现成本高。
     *
     * @return 添加动作执行成功（exit 0）。幂等：已在列表时同样返回 true。
     */
    suspend fun addQuickSettingsTile(tileClass: Class<*>): Boolean {
        val component = "${appContext.packageName}/${tileClass.name}"
        return runCatching {
            val result = exec(
                "cmd statusbar add-tile $component", TILE_OP_TIMEOUT_MS
            )
            result.exitCode == 0
        }.getOrDefault(false)
    }

    /**
     * 磁贴是否在任务栏中（读 `sysui_qs_tiles`，无需写权限）。
     *
     * 匹配两种存储格式（§13 实测）：SystemUI 存储时把组件名缩写为
     * `pkg/.ShortClass`（包名前缀剥离），部分 ROM 可能保留完整
     * `pkg/pkg.ShortClass`——两种都检查，避免缩写格式漏判。
     */
    suspend fun hasQuickSettingsTile(tileClass: Class<*>): Boolean {
        val pkg = appContext.packageName
        val full = "$pkg/${tileClass.name}"
        val short = "$pkg/.${tileClass.name.removePrefix("$pkg.")}"
        return runCatching {
            val result = exec(
                "settings get secure sysui_qs_tiles", TILE_OP_TIMEOUT_MS
            )
            result.exitCode == 0 &&
                (result.stdout.contains(full) || result.stdout.contains(short))
        }.getOrDefault(false)
    }

    /**
     * 从任务栏移除磁贴（主页「任务栏磁贴」卡手动入口）。
     */
    suspend fun removeQuickSettingsTile(tileClass: Class<*>): Boolean {
        val component = "${appContext.packageName}/${tileClass.name}"
        return runCatching {
            val result = exec(
                "cmd statusbar remove-tile $component", TILE_OP_TIMEOUT_MS
            )
            result.exitCode == 0
        }.getOrDefault(false)
    }
}
