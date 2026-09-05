package com.rainy.screenshot.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.rainy.screenshot.recordingSessionManager
import com.rainy.screenshot.settingsStore
import com.rainy.screenshot.session.RecordingSessionManager
import com.rainy.screenshot.ui.preview.PreviewActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 悬浮控制球（阶段 3）。
 *
 * 职责：任意界面常驻小圆球。
 * - 单击 = 弹出快捷菜单：录屏启停 / 预览最新截图 / 预览最新视频
 * - 拖动移动位置；录制中变红点，空闲变樱粉
 * - 状态与 RecordingSessionManager 单例联动（多入口一致性）
 */
class FloatingBallService : Service() {

    companion object {
        private const val BALL_SIZE_DP = 56
        private const val PADDING_DP = 8
        private const val MENU_WIDTH_DP = 168

        /** 启动/停止悬浮球 */
        fun start(context: android.content.Context) {
            // 不用 startForegroundService——强制前台通知违反隐身性红线。
            runCatching {
                context.startService(Intent(context, FloatingBallService::class.java))
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }

    /** Application 实例（扩展属性定义在 Application 上，Service 需 cast）。 */
    private val app: com.rainy.screenshot.RainyScreenShotApplication
        get() = applicationContext as com.rainy.screenshot.RainyScreenShotApplication

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var ballView: View

    /** 快捷菜单（单击弹出） */
    private var menuView: View? = null

    /** 状态流订阅（time-limit 自动结束/他处停止时球色同步）。 */
    private var stateJob: kotlinx.coroutines.Job? = null

    /** 菜单因外部点击收起的时刻（防「点球收菜单→OUTSIDE+toggle 双触发又弹出」）。 */
    private var outsideDismissAt = 0L

    private var lastX = 0
    private var lastY = 0
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ballView = createBallView()
        addToWindow()
    }

    /** 创建圆球 View（简易绘制，无 Compose overlay 依赖）。 */
    private fun createBallView(): View {
        val density = resources.displayMetrics.density
        val size = (BALL_SIZE_DP * density).toInt()
        val pad = (PADDING_DP * density).toInt()

        val view = View(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xFFFF85A2.toInt()) // 草莓粉
        bg.setStroke((3 * density).toInt(), 0xFFFFFFFF.toInt())
        view.background = bg

        // 拖动 + 单击（区分阈值 8px）
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = event.rawX.toInt()
                    initialY = event.rawY.toInt()
                    lastX = initialX
                    lastY = initialY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (kotlin.math.abs(event.rawX - initialX) > 8 ||
                        kotlin.math.abs(event.rawY - initialY) > 8
                    ) {
                        isDragging = true
                        // 拖动开始即收起菜单（避免菜单残留旧位置）
                        if (menuView != null) dismissMenu()
                        // Gravity.END 坐标系：x 为距右缘偏移，手指向左移动（dx<0）应增大 x（球向左移）
                        updatePosition(
                            (ballView.tag as WindowManager.LayoutParams).x - dx,
                            (ballView.tag as WindowManager.LayoutParams).y + dy
                        )
                    }
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 350ms 内点球导致菜单 OUTSIDE 收起 → 本次 UP 只消费不切换
                    //（否则会「收起后立刻又弹出」）
                    if (!isDragging) {
                        if (System.currentTimeMillis() - outsideDismissAt < 350) {
                            outsideDismissAt = 0L
                        } else {
                            toggleMenu()
                        }
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = pad
            y = pad * 3
        }
        view.tag = layoutParams
        return view
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    @SuppressLint("InflateParams")
    private fun addToWindow() {
        val params = ballView.tag as WindowManager.LayoutParams
        runCatching { windowManager.addView(ballView, params) }
    }

    private fun updatePosition(x: Int, y: Int) {
        val params = ballView.tag as WindowManager.LayoutParams
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(ballView, params) }
    }

    // ─────────────────────────────────────────────
    // 快捷菜单
    // ─────────────────────────────────────────────

    /** 单击悬浮球：菜单未显示则弹出，已显示则收起。 */
    private fun toggleMenu() {
        if (menuView != null) {
            dismissMenu()
        } else {
            showMenu()
        }
    }

    @SuppressLint("InflateParams")
    private fun showMenu() {
        val density = resources.displayMetrics.density
        val width = (MENU_WIDTH_DP * density).toInt()
        val pad = (PADDING_DP * density).toInt()
        val recording = app.recordingSessionManager.currentState() is
            RecordingSessionManager.SessionState.Recording

        // 菜单容器：白色圆角卡片（背景必须挂在真正 addView 的 container 上）
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE
            bg.setColor(0xFFFFFFFF.toInt())
            bg.cornerRadius = 16f * density
            // 轻阴影近似：描边 + 半透明黑边
            bg.setStroke((1 * density).toInt(), 0x22000000)
            background = bg
        }

        val items = listOf(
            if (recording) "■ 停止录屏" else "● 开始录屏",
            "🖼 预览最新截图",
            "🎬 预览最新视频"
        )
        items.forEachIndexed { index, label ->
            val item = android.widget.TextView(this)
            item.text = label
            item.setTextSize(15f)
            item.setPadding((16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt())
            item.setTextColor(0xFF3D2C35.toInt())
            item.setOnClickListener {
                when (index) {
                    0 -> toggleRecording()
                    1 -> openLatestImage()
                    2 -> openLatestVideo()
                }
                dismissMenu()
            }
            container.addView(item)
        }

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // 点菜单之外任意处 → ACTION_OUTSIDE → 收起（点菜单项在 frame 内不触发）
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            val ballParams = ballView.tag as WindowManager.LayoutParams
            val screenW = resources.displayMetrics.widthPixels
            // x 反演成绝对坐标：球距右缘 x，则球左缘绝对坐标 = screenW - x - ballSize
            val ballAbsLeft = screenW - ballParams.x - (BALL_SIZE_DP * density).toInt()
            val ballAbsTop = ballParams.y
            if (ballAbsLeft - width - pad >= 0) {
                // 球左侧空间够：菜单贴球左
                gravity = Gravity.TOP or Gravity.END
                x = ballParams.x + (BALL_SIZE_DP * density).toInt() + pad
                y = ballAbsTop
            } else {
                // 球在屏幕左半侧：菜单放到球右侧（防越出左缘被裁剪）
                gravity = Gravity.TOP or Gravity.START
                x = ballAbsLeft + (BALL_SIZE_DP * density).toInt() + pad
                y = ballAbsTop
            }
        }
        // 菜单容器监听 OUTSIDE：点菜单之外任意处收起
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                outsideDismissAt = System.currentTimeMillis()
                dismissMenu()
                true
            } else {
                false
            }
        }

        runCatching { windowManager.addView(container, params) }
            .onSuccess { menuView = container }
    }

    private fun dismissMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
    }

    // ─────────────────────────────────────────────
    // 动作
    // ─────────────────────────────────────────────

    /** 录屏启停（经 SessionManager，与首页/磁贴一致）。 */
    private fun toggleRecording() {
        scope.launch {
            val manager = app.recordingSessionManager
            if (manager.currentState() is RecordingSessionManager.SessionState.Recording) {
                when (manager.stop()) {
                    is RecordingSessionManager.StopResult.Success -> refreshBallColor(false)
                    RecordingSessionManager.StopResult.NotRecording -> refreshBallColor(false)
                    is RecordingSessionManager.StopResult.Failure ->
                        refreshBallColor(
                            manager.currentState()
                                is RecordingSessionManager.SessionState.Recording
                        )
                }
            } else {
                val config = app.settingsStore
                    .recordConfigFlow.first().normalized()
                runCatching { manager.start(config) }
                refreshBallColor(
                    manager.currentState() is RecordingSessionManager.SessionState.Recording
                )
            }
        }
    }

    /** 预览最新截图（PNG/RAW，取 Screenshots 目录最新文件）。 */
    private fun openLatestImage() {
        val latest = latestFile(com.rainy.screenshot.capture.ScreenshotEngine.DIR_SCREENSHOTS)
        if (latest == null) {
            Toast.makeText(this, "还没有截图喵", Toast.LENGTH_SHORT).show()
            return
        }
        PreviewActivity.start(this, latest, isVideo = false)
    }

    /** 预览最新视频（Recordings 目录最新 mp4）。 */
    private fun openLatestVideo() {
        val latest = latestFile(com.rainy.screenshot.capture.RecordingEngine.DIR_RECORDINGS)
        if (latest == null) {
            Toast.makeText(this, "还没有录屏喵", Toast.LENGTH_SHORT).show()
            return
        }
        PreviewActivity.start(this, latest, isVideo = true)
    }

    /** 指定子目录下最新文件（无文件返回 null；排除正在写入的录制文件）。 */
    private fun latestFile(subDir: String): File? {
        val root = applicationContext.getExternalFilesDir(null)
            ?: applicationContext.filesDir
        val dir = File(root, subDir)
        // 正在录制的输出文件：内容未定稿，跳过
        val writing = (app.recordingSessionManager.currentState()
            as? RecordingSessionManager.SessionState.Recording)?.outputFile?.absolutePath
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0L && it.absolutePath != writing }
            ?.maxByOrNull { it.lastModified() }
    }

    /** 录制中 → 红点；空闲 → 草莓粉。 */
    private fun refreshBallColor(recording: Boolean) {
        val bg = ballView.background as? GradientDrawable ?: return
        bg.setColor(
            if (recording) 0xFFE91E63.toInt() else 0xFFFF85A2.toInt()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshBallColor(
            app.recordingSessionManager.currentState()
                is RecordingSessionManager.SessionState.Recording
        )
        stateJob?.cancel()
        stateJob = scope.launch {
            app.recordingSessionManager.state.collect { state ->
                refreshBallColor(state is RecordingSessionManager.SessionState.Recording)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        dismissMenu()
        scope.cancel()
        runCatching { windowManager.removeView(ballView) }
        super.onDestroy()
    }
}