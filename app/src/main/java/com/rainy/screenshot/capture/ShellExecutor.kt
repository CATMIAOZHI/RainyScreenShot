package com.rainy.screenshot.capture

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
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
    }

    // ─────────────────────────────────────────────
    // 环境状态
    // ─────────────────────────────────────────────

    /** Shizuku 是否已安装 */
    fun isShizukuInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Shizuku 服务是否在运行（未授权时也返回 true） */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: IllegalStateException) {
            false
        }
    }

    /** 本应用是否已获得 Shizuku 权限 */
    fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: IllegalStateException) {
            false // binder 未就绪
        }
    }

    /** 环境就绪：已安装 + 已运行 + 已授权 */
    fun isEnvironmentReady(): Boolean =
        isShizukuInstalled() && isShizukuRunning() && isShizukuGranted()

    /**
     * 请求 Shizuku 授权（Activity 上下文中调用）。
     * 结果通过 [Shizuku.addRequestPermissionResultListener] 异步回调。
     */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

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
        if (!isShizukuInstalled()) throw ShellException.NotInstalled()
        if (!isShizukuRunning()) throw ShellException.NotRunning()
        if (!isShizukuGranted()) throw ShellException.NotGranted()
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
}