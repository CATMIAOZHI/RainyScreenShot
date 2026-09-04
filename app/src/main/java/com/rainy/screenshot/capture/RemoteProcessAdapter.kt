package com.rainy.screenshot.capture

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * IRemoteProcess（Shizuku AIDL 公开接口）的 java.lang.Process 适配器。
 *
 * Shizuku 13.x 的 ShizukuRemoteProcess 构造器为包私有（实测 javap 确认），
 * 本类用公开 AIDL 接口（IShizukuService.newProcess → IRemoteProcess）
 * 复刻其包装逻辑，为 [ShellExecutor] 提供标准 Process 语义。
 *
 * @see rikka.shizuku.ShizukuRemoteProcess（官方参考实现，包私有构造器）
 */
class RemoteProcessAdapter(
    private val remote: IRemoteProcess
) : Process() {

    private val destroyLock = ReentrantLock()
    @Volatile
    private var destroyed = false

    @Volatile
    private var osPfd: ParcelFileDescriptor? = null
    @Volatile
    private var isPfd: ParcelFileDescriptor? = null
    @Volatile
    private var esPfd: ParcelFileDescriptor? = null

    override fun getOutputStream(): OutputStream {
        val pfd = osPfd ?: remote.outputStream.also { osPfd = it }
        return ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    }

    override fun getInputStream(): InputStream {
        val pfd = isPfd ?: remote.inputStream.also { isPfd = it }
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    override fun getErrorStream(): InputStream {
        val pfd = esPfd ?: remote.errorStream.also { esPfd = it }
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    @Throws(InterruptedException::class)
    override fun waitFor(): Int = try {
        remote.waitFor()
    } catch (e: RemoteException) {
        throw RuntimeException(e)
    }

    override fun exitValue(): Int = try {
        remote.exitValue()
    } catch (e: RemoteException) {
        throw RuntimeException(e)
    }

    /**
     * 终止远端进程。
     *
     * 注意：screenrecord 收尾应优先 SIGINT（RecordingEngine.stop 已处理，
     * 保证 mp4 moov 正常定稿），此处 destroy 仅作超时/异常兜底。
     */
    override fun destroy() {
        destroyLock.withLock {
            if (destroyed) return
            destroyed = true
        }
        runCatching { remote.destroy() }
        // 释放本地 fd（重复 close 安全）
        runCatching { isPfd?.close() }
        runCatching { esPfd?.close() }
        runCatching { osPfd?.close() }
    }

    /** 进程是否存活（IRemoteProcess 扩展能力）。 */
    fun alive(): Boolean = try {
        remote.alive()
    } catch (e: RemoteException) {
        false
    }

    companion object {
        /**
         * 通过 Shizuku binder 创建远端进程。
         *
         * @throws IllegalStateException binder 未就绪（服务死亡）
         * @throws RuntimeException server 侧错误
         */
        fun create(command: Array<String>): RemoteProcessAdapter {
            val binder = Shizuku.getBinder()
                ?: throw IllegalStateException("Shizuku binder not available")
            val service = IShizukuService.Stub.asInterface(binder)
            val remote = try {
                service.newProcess(command, null, null)
            } catch (e: RemoteException) {
                throw RuntimeException("newProcess failed: ${e.message}", e)
            }
            return RemoteProcessAdapter(remote)
        }
    }
}