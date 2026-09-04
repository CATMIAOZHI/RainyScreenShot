package com.rainy.screenshot.session

import com.rainy.screenshot.capture.RecordConfig
import com.rainy.screenshot.capture.RecordingEngine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 录制会话状态机 —— 状态单一来源（B1 修复：engine 退出回调在此迁移状态）。
 *
 * 状态流：Idle → Recording → (用户 stop / 进程退出回调) → Completed
 *                                     ↘ Failed
 *
 * 多入口共用：APP 首页 / 快捷磁贴 / 悬浮控制球 全部经由本单例。
 */
@Singleton
class RecordingSessionManager @Inject constructor(
    private val recordingEngine: RecordingEngine
) {

    /** 对外暴露的会话状态。 */
    sealed class SessionState {
        /** 空闲 */
        data object Idle : SessionState()

        /** 录制中（含开始时间戳与输出文件） */
        data class Recording(
            val startedAt: Long,
            val outputFile: File
        ) : SessionState()

        /** 已完成（stop 成功或超时自动收尾） */
        data class Completed(
            val outputFile: File,
            val durationMs: Long
        ) : SessionState()

        /** 失败（启动失败 / 停止异常） */
        data class Failed(val reason: String) : SessionState()
    }

    /** stop() 的结构化结果（B7 修复：不再用 null 静默吞失败）。 */
    sealed class StopResult {
        data class Success(val outputFile: File, val durationMs: Long) : StopResult()
        data class Failure(val reason: String) : StopResult()
        data object NotRecording : StopResult()
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** 是否正在录制 */
    val isRecording: Boolean
        get() = _state.value is SessionState.Recording

    init {
        // B1 修复：注册进程退出回调（time-limit 自动停止 → Completed 联动）
        recordingEngine.setExitListener { session, reason ->
            val current = _state.value
            if (current is SessionState.Recording && current.outputFile == session.outputFile) {
                _state.value = if (reason == null) {
                    SessionState.Completed(
                        outputFile = session.outputFile,
                        durationMs = System.currentTimeMillis() - session.startedAt
                    )
                } else {
                    SessionState.Failed(reason)
                }
            }
        }
    }

    /**
     * 开始录制。
     *
     * B8 修复：失败时只在**非录制中**前置状态下置 Failed，
     * 不把合法的 Recording 覆盖为 Failed。
     *
     * @throws ShellException 环境未就绪
     */
    suspend fun start(config: RecordConfig = RecordConfig.DEFAULT) {
        val current = _state.value
        if (current is SessionState.Recording) {
            // 已在录制：不启动新会话，也不改状态（保持 Recording）
            return
        }
        try {
            val session = recordingEngine.start(config)
            _state.value = SessionState.Recording(
                startedAt = session.startedAt,
                outputFile = session.outputFile
            )
        } catch (e: Exception) {
            // B8 修复（竞态守卫）：并发 start 时后到者可能撞上
            // 前者刚置的 Recording——此时不得覆盖为 Failed
            if (_state.value !is SessionState.Recording) {
                _state.value = SessionState.Failed(e.message ?: "start failed")
            }
            throw e
        }
    }

    /**
     * 停止录制（SIGINT）。
     *
     * B7 修复：结构化结果，UI 据此区分成功/失败，不再误报。
     */
    suspend fun stop(): StopResult {
        val current = _state.value
        if (current !is SessionState.Recording) {
            return StopResult.NotRecording
        }
        return try {
            val session = recordingEngine.stop()
            val duration = System.currentTimeMillis() - current.startedAt
            _state.value = SessionState.Completed(session.outputFile, duration)
            StopResult.Success(session.outputFile, duration)
        } catch (e: Exception) {
            // N2 修复后契约：stop 失败时 engine 已恢复会话句柄
            // （active = session + watch 重启），用户可重试 stop。
            // 仅当 engine 确认无会话（进程已死且非我方停止）时才迁移 Failed；
            // 并发 stop 场景（前者已成功置 Completed）不得覆盖。
            val engineSession = recordingEngine.currentSession()
            when {
                engineSession != null ->
                    // 会话仍在 → 保留 Recording 状态（用户可重试）
                    StopResult.Failure(e.message ?: "stop failed")

                _state.value is SessionState.Recording ->
                    // engine 无会话但状态仍是 Recording（罕见不一致）→ Failed
                    _state.value.let { st ->
                        if (st is SessionState.Recording) {
                            _state.value = SessionState.Failed(e.message ?: "stop failed")
                        }
                        StopResult.Failure(e.message ?: "stop failed")
                    }

                else ->
                    // 前一个 stop 已成功（Completed）→ 不覆盖成功状态
                    StopResult.Failure(e.message ?: "stop failed")
            }
        }
    }

    /**
     * 冷启动恢复：APP 重启后，若 shell 侧 screenrecord 仍在跑（APP 被杀场景），
     * 依据磁盘上未定稿的 mp4 文件 + cmdline 校验重建会话状态。
     */
    suspend fun restore() {
        if (isRecording) return
        if (!recordingEngine.isEnvironmentReadyQuiet()) return

        val dir = recordingEngine.recordingsDir()
        val candidates = dir.listFiles { f -> f.name.startsWith("rainy_rec_") }
            ?.filter { System.currentTimeMillis() - it.lastModified() < 60_000 }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        val recent = candidates.firstOrNull() ?: return

        val pid = recordingEngine.findPidByFile(recent.name) ?: return
        val adopted = runCatching {
            recordingEngine.adoptSession(pid, recent)
        }.getOrDefault(false)
        if (adopted) {
            _state.value = SessionState.Recording(
                startedAt = recent.lastModified(),
                outputFile = recent
            )
        }
    }

    /** 供磁贴查询（避免磁贴内部持有状态流订阅的生命周期问题）。 */
    fun currentState(): SessionState = _state.value
}