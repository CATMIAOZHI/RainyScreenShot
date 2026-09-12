package com.rainy.screenshot.capture

/**
 * Privileged service backend exposed by the Porter adapter.
 * AUTO prefers Porter when installed, otherwise Shizuku.
 */
enum class ServiceBackend {
    AUTO,
    PORTER,
    SHIZUKU
}


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
    class NotInstalled(val backend: ServiceBackend = ServiceBackend.AUTO) :
        ShellException("${backendName(backend)} is not installed on this device")

    /** 当前后端未激活（引导用户启动所选服务） */
    class NotRunning(val backend: ServiceBackend = ServiceBackend.AUTO) :
        ShellException("${backendName(backend)} service is not running")

    /** 当前后端未授予本应用权限 */
    class NotGranted(val backend: ServiceBackend = ServiceBackend.AUTO) :
        ShellException("${backendName(backend)} permission not granted")

    /** shell 命令执行失败（含退出码与 stderr 摘要） */
    class Execution(
        val exitCode: Int,
        val stderr: String
    ) : ShellException("shell execution failed (exit=$exitCode): ${stderr.take(300)}")

    companion object {
        private fun backendName(backend: ServiceBackend): String = when (backend) {
            ServiceBackend.PORTER -> "Porter"
            ServiceBackend.SHIZUKU -> "Shizuku"
            ServiceBackend.AUTO -> "selected backend"
        }
    }
}

/** 一次 shell 命令的结果。 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)
