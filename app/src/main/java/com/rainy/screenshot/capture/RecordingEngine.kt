package com.rainy.screenshot.capture

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 静默录屏引擎（screenrecord 封装）。
 *
 * 关键机制（TECH_NOTES §3/§4/§10 实测）：
 * 1. 启动：直接 `screenrecord <args> <out.mp4> 1>/dev/null 2>&1 &`，
 *    父 sh 以 sigsuspend 等待（§10 实测，sh 不退出）——我们持有该 sh 的
 *    Process 句柄直到 stop（防止 fd 泄漏，B5）
 * 2. pid 定位：pgrep 全量结果 → 逐个读 /proc/<pid>/cmdline 校验首字段
 *    == screenrecord（§10 实测：pgrep -f 会自匹配父 sh，B9 修复）
 * 3. 停止：kill -INT <screenrecord pid> → moov box 正常定稿（§4 实测），
 *    退出码校验 + 存活复核（B6 修复：不得误报成功）
 * 4. Mutex 贯穿 start/stop 全程（B3 修复：双录竞态）
 * 5. 存活探测区分「确认死亡」与「无法检测」（B12 修复：Shizuku 死亡
 *    不等于 screenrecord 死亡，误清状态会导致失控录制）
 * 6. 进程退出回调 onProcessExit（B1 修复：time-limit 自动停止时
 *    SessionManager 状态必须联动迁移）
 */
@Singleton
class RecordingEngine @Inject constructor(
    private val shellExecutor: ShellExecutor
) {

    companion object {
        const val DIR_RECORDINGS = "Recordings"
        const val PREFIX_RECORDING = "rainy_rec"

        /** pid 定位超时（screenrecord 启动一般 < 1s） */
        private const val PGREP_TIMEOUT_MS = 5_000L

        /** 进程存活轮询间隔 */
        private const val ALIVE_CHECK_INTERVAL_MS = 2_000L

        /** mp4 定稿等待上限 */
        private const val FINALIZE_TIMEOUT_MS = 10_000L
    }

    /** 一次录制会话的运行时句柄。 */
    data class ActiveRecording(
        val pid: Int,
        val outputFile: File,
        val config: RecordConfig,
        val startedAt: Long
    )

    /** 进程存活探测结果（B12：三态）。 */
    enum class ProcessStatus {
        /** 确认存活 */
        ALIVE,
        /** 确认死亡（ps 明确无此进程） */
        DEAD,
        /** 无法检测（Shizuku 环境异常，不代表进程死亡） */
        UNKNOWN
    }

    /** 进程异常/自动退出时的回调（SessionManager 注册，B1 修复）。 */
    fun interface ProcessExitListener {
        /**
         * @param reason null = 正常收尾（用户 stop / time-limit 到时）；
         *                非空 = 异常退出描述
         */
        fun onProcessExit(session: ActiveRecording, reason: String?)
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()

    @Volatile
    private var active: ActiveRecording? = null

    /** 持有启动 sh 的 Process（fd 资源，stop 后释放，B5） */
    @Volatile
    private var shellProcess: Process? = null

    @Volatile
    private var exitListener: ProcessExitListener? = null

    @Volatile
    private var aliveWatchJob: Job? = null

    /** 当前是否在录制 */
    val isRecording: Boolean get() = active != null

    /** 当前会话（只读快照） */
    fun currentSession(): ActiveRecording? = active

    /** 注册进程退出回调（SessionManager 启动时设置）。 */
    fun setExitListener(listener: ProcessExitListener) {
        exitListener = listener
    }

    /**
     * 开始静默录屏。
     *
     * Mutex 贯穿全程（B3 修复）：并发 start 时后到者在锁上等待，
     * 拿到锁后发现 active != null 直接抛错，不会双录。
     *
     * @throws ShellException 环境问题 / 已在录制 / 启动失败
     */
    suspend fun start(config: RecordConfig): ActiveRecording = sessionMutex.withLock {
        active?.let { throw ShellException.Execution(-1, "recording already active") }

        // 输出文件（App 私有外部目录，shell 可写，TECH_NOTES §6）
        val fileName = CaptureFileNamer.timestampName(PREFIX_RECORDING, "mp4")
        val out = shellExecutor.newOutputFile(DIR_RECORDINGS, fileName)

        // 启动命令（§10 实测：直接 screenrecord & ，父 sh 挂起等待）
        val bgCmd = buildString {
            append("screenrecord ")
            append(config.toArgs())
            append("'${out.absolutePath}'")
            append(" 1>/dev/null 2>&1 &")
        }

        val shProcess = try {
            shellExecutor.newProcess(bgCmd)
        } catch (e: ShellException) {
            throw e
        }

        // 定位真实 screenrecord pid（B9 修复：全量 + cmdline 校验）
        val pid: Int = try {
            locateScreenrecordPid(out.name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // B4 附注修复：协程取消（磁贴/页面销毁）不是启动失败，
            // 直接上抛，避免污染状态机
            runCatching { shProcess.destroy() }
            throw e
        } catch (e: Exception) {
            // B4 修复：定位失败 = 兜底清理已启动的录制
            runCatching {
                val orphan = locateScreenrecordPid(out.name)
                shellExecutor.exec("kill -INT $orphan", timeoutMs = 2_000L)
            }
            runCatching { shProcess.destroy() }
            throw ShellException.Execution(
                -1,
                "screenrecord failed to start (pid locate: ${e.message})"
            )
        }

        val session = ActiveRecording(
            pid = pid,
            outputFile = out,
            config = config,
            startedAt = System.currentTimeMillis()
        )
        active = session
        shellProcess = shProcess
        startAliveWatch(session)
        session
    }

    /**
     * 停止录屏（SIGINT → 正常收尾 mp4）。
     *
     * B6 修复：kill 退出码校验 + 存活复核，两者任一失败都抛错，
     * 上层（SessionManager）据此置 Failed 而非 Completed。
     *
     * @return 定稿的 mp4 文件
     * @throws ShellException 停止失败（进程仍在录）
     */
    suspend fun stop(): ActiveRecording = sessionMutex.withLock {
        val session = active
            ?: throw ShellException.Execution(-1, "no active recording")
        aliveWatchJob?.cancel()
        aliveWatchJob = null

        // SIGINT → 正常 moov 收尾（TECH_NOTES §4）
        val killResult = runCatching {
            shellExecutor.exec("kill -INT ${session.pid}", timeoutMs = 3_000L)
        }.getOrNull()

        // N1/N2 修复：kill 失败 ≠ 进程还活着——最常见原因是
        // time-limit 刚自动结束（watch 尚未轮询到的 ≤2s 窗口），
        // 此时进程已死、文件已定稿，应按成功收尾。
        if (killResult == null || killResult.exitCode != 0) {
            when (probeProcess(session.pid)) {
                ProcessStatus.DEAD -> {
                    // N1：进程已不存在（time-limit 刚结束）→ 按成功收尾
                    active = null
                    waitForFinalize(session.outputFile)
                    releaseShellProcess()
                    return@withLock session
                }
                ProcessStatus.ALIVE, ProcessStatus.UNKNOWN -> {
                    // N2：进程可能仍在录 → 恢复会话句柄与监视，
                    // 抛错让用户重试 stop（不得静默丢失控制权）
                    active = session
                    startAliveWatch(session)
                    throw ShellException.Execution(
                        killResult?.exitCode ?: -1,
                        "kill -INT failed (exit=${killResult?.exitCode ?: "timeout"}) — recording still running, please retry stop"
                    )
                }
            }
        }

        active = null

        // 等待收尾完成（文件不再增长）
        waitForFinalize(session.outputFile)

        // B6 修复：收尾后复核进程状态
        if (probeProcess(session.pid) == ProcessStatus.ALIVE) {
            // 收尾失败但 kill 已送达（SIGINT 被吞）→ 恢复句柄让用户重试
            active = session
            startAliveWatch(session)
            throw ShellException.Execution(
                -1,
                "screenrecord still alive after SIGINT + finalize wait — recording may be corrupted, please retry stop"
            )
        }

        releaseShellProcess()
        session
    }

    /** 进程存活探测（B12 修复：三态区分）。 */
    suspend fun probeProcess(pid: Int): ProcessStatus {
        val result = try {
            shellExecutor.exec(
                "ps -p $pid -o comm=",
                timeoutMs = 2_000L
            )
        } catch (e: ShellException.NotInstalled) {
            return ProcessStatus.UNKNOWN
        } catch (e: ShellException.NotRunning) {
            return ProcessStatus.UNKNOWN
        } catch (e: ShellException.NotGranted) {
            return ProcessStatus.UNKNOWN
        } catch (e: Exception) {
            return ProcessStatus.UNKNOWN
        }
        val comm = result.stdout.trim()
        return when {
            comm.contains("screenrecord") -> ProcessStatus.ALIVE
            comm.isEmpty() -> ProcessStatus.DEAD
            // pid 被复用为其他进程 → 原录制进程已死
            else -> ProcessStatus.DEAD
        }
    }

    /** 释放启动 sh 的 fd（B5）。 */
    private fun releaseShellProcess() {
        runCatching { shellProcess?.destroy() }
        shellProcess = null
    }

    /**
     * 定位真实 screenrecord pid（B9 修复）。
     *
     * pgrep -f 全量结果 → 过滤出 /proc/<pid>/cmdline 首字段 == screenrecord
     * 的 pid（§10 实测：父 sh cmdline 含模式文本，必须逐个校验）。
     *
     * @throws kotlinx.coroutines.TimeoutCancellationException PGREP_TIMEOUT_MS 内未找到
     */
    private suspend fun locateScreenrecordPid(fileName: String): Int =
        withTimeout(PGREP_TIMEOUT_MS) {
            while (true) {
                val probe = runCatching {
                    shellExecutor.exec("pgrep -f $fileName", timeoutMs = 1_000L)
                }.getOrNull()
                val pids = probe?.stdout?.trim()?.lineSequence()
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toList()
                    ?: emptyList()

                if (pids.isNotEmpty()) {
                    // 逐个校验 cmdline 首字段（§10.6 实测：/proc/<pid>/cmdline 可读）
                    for (pid in pids) {
                        val check = runCatching {
                            shellExecutor.exec(
                                "cat /proc/$pid/cmdline | tr '\\0' ' '",
                                timeoutMs = 1_000L
                            )
                        }.getOrNull() ?: continue
                        val cmdline = check.stdout.trim()
                        if (cmdline.startsWith("screenrecord") &&
                            cmdline.contains(fileName)) {
                            return@withTimeout pid
                        }
                    }
                }
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    /** 等待 mp4 定稿（文件大小连续两次相同，最长 [FINALIZE_TIMEOUT_MS]）。 */
    private suspend fun waitForFinalize(file: File) {
        var lastSize = -1L
        runCatching {
            withTimeout(FINALIZE_TIMEOUT_MS) {
                while (true) {
                    delay(500)
                    val size = file.length()
                    if (size == lastSize && size > 0) {
                        return@withTimeout
                    }
                    lastSize = size
                }
            }
        }
        // 超时不抛异常：由调用方的 probeProcess 复核兜底（B6）
    }

    /**
     * 存活监视（B1 修复：进程退出通过 [exitListener] 回调 SessionManager）。
     *
     * - DEAD 分支持 sessionMutex（与 start/stop/adopt 一致，消除
     *   无锁写 active 的交错窗口）
     * - B1 残留修复：区分「time-limit 正常到时」与「异常退出」——
     *   未到 time-limit 就死亡 = 异常，回调非空 reason → SessionManager 置 Failed
     * - UNKNOWN（Shizuku 抖动）不清会话，下轮继续探测
     */
    private fun startAliveWatch(session: ActiveRecording) {
        aliveWatchJob = engineScope.launch {
            while (true) {
                delay(ALIVE_CHECK_INTERVAL_MS)
                if (currentSession()?.pid != session.pid) return@launch

                when (probeProcess(session.pid)) {
                    ProcessStatus.ALIVE -> { /* 继续监视 */ }
                    ProcessStatus.DEAD -> {
                        // 进程退出（time-limit 到时 / 异常终止）
                        val stillOurs = sessionMutex.withLock {
                            val ours = currentSession()?.pid == session.pid
                            if (ours) {
                                active = null
                                releaseShellProcess()
                            }
                            ours
                        }
                        if (stillOurs) {
                            // B1 残留修复：未到 time-limit 的死亡 = 异常退出
                            val elapsed = System.currentTimeMillis() - session.startedAt
                            val limitMs = session.config.timeLimitSec * 1000L
                            val reason = if (
                                session.config.timeLimitSec > 0 &&
                                elapsed < limitMs - ALIVE_CHECK_INTERVAL_MS
                            ) {
                                "screenrecord exited unexpectedly (after ${elapsed / 1000}s, before ${session.config.timeLimitSec}s limit)"
                            } else {
                                null // 正常到时收尾
                            }
                            exitListener?.onProcessExit(session, reason)
                        }
                        return@launch
                    }
                    ProcessStatus.UNKNOWN -> {
                        // 无法检测：保持会话（进程大概率仍在录，B12）
                        // 下轮继续探测
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 会话恢复（APP 被杀后重建 shell 侧仍在跑的录制）
    // ─────────────────────────────────────────────

    /** 环境是否就绪（恢复探测用，异常静默为 false）。 */
    fun isEnvironmentReadyQuiet(): Boolean =
        runCatching { shellExecutor.isEnvironmentReady() }.getOrDefault(false)

    /** 录制输出目录。 */
    fun recordingsDir(): File =
        shellExecutor.newOutputFile(DIR_RECORDINGS, ".keep").parentFile!!

    /** 按输出文件名定位 screenrecord pid（恢复场景）。 */
    suspend fun findPidByFile(fileName: String): Int? =
        runCatching { locateScreenrecordPid(fileName) }.getOrNull()

    /**
     * 收养一个仍在运行的 shell 侧录制（restore 场景）。
     *
     * 阶段 3 修复（N7 边界）：config 不再硬编码 DEFAULT——restore 时
     * 上层传入当前设置页持久化配置，aliveWatch 的 time-limit 正常收尾
     * 判断（elapsed < limitMs）才能与实际录制参数一致，否则自定义时长
     * 会话会被误判为异常退出。
     *
     * @return 是否成功（已有会话时 false）
     */
    suspend fun adoptSession(
        pid: Int,
        outputFile: File,
        config: RecordConfig = RecordConfig.DEFAULT
    ): Boolean = sessionMutex.withLock {
        if (active != null) return@withLock false
        val session = ActiveRecording(
            pid = pid,
            outputFile = outputFile,
            config = config,
            startedAt = outputFile.lastModified()
        )
        active = session
        startAliveWatch(session)
        true
    }
}