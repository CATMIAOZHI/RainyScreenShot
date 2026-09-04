package com.rainy.screenshot.capture

/**
 * Shizuku shell 环境不可用 / 执行失败的统一异常。
 *
 * UI 层根据具体类型映射用户可理解的引导文案。
 */
sealed class ShellException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** Shizuku 未安装（跳转安装页引导） */
    class NotInstalled : ShellException("Shizuku is not installed on this device")

    /** Shizuku 未激活（引导用户到 Shizuku APP 启动服务） */
    class NotRunning : ShellException("Shizuku service is not running")

    /** 未授予本应用 Shizuku 权限（发起授权请求） */
    class NotGranted : ShellException("Shizuku permission not granted")

    /** shell 命令执行失败（含退出码与 stderr 摘要） */
    class Execution(
        val exitCode: Int,
        val stderr: String
    ) : ShellException("shell execution failed (exit=$exitCode): ${stderr.take(300)}")
}

/** 一次 shell 命令的结果。 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)