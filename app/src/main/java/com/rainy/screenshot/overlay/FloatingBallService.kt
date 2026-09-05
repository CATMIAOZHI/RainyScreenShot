package com.rainy.screenshot.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.rainy.screenshot.recordingSessionManager
import com.rainy.screenshot.screenshotQuick
import com.rainy.screenshot.settingsStore
import com.rainy.screenshot.session.RecordingSessionManager
import com.rainy.screenshot.ui.preview.PreviewActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 悬浮控制球（阶段 3）。
 *
 * 职责：任意界面常驻小圆球。
 * - 单击 = 弹出快捷菜单：录屏启停 / 立即截屏 / 预览最新截图 / 预览最新视频
 * - 拖动移动位置（限制在屏幕可见范围内，防拖丢）
 * - 极简自绘球面：空闲 = 取景框 + 品牌粉镜头；录制中 = 红色 REC
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

    /** addView 重试任务（权限晚到场景）。 */
    private var retryJob: kotlinx.coroutines.Job? = null

    /** 菜单因外部点击收起的时刻（防「点球收菜单→OUTSIDE+toggle 双触发又弹出」）。 */
    private var outsideDismissAt = 0L

    private var lastX = 0
    private var lastY = 0
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** BallHider 实现（unregister 时按同一实例匹配）。 */
    private val ballHider = com.rainy.screenshot.capture.BallHider { hidden ->
        setBallHidden(hidden)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ballView = createBallView()
        addToWindow()
        // 注册悬浮球隐藏实现（ScreenshotEngine 截屏窗口期调用）
        com.rainy.screenshot.capture.BallHiderRegistry.register(ballHider)
    }

    /** 创建圆球 View（BallFaceView 自绘极简图形，无 Compose overlay 依赖）。 */
    private fun createBallView(): View {
        val density = resources.displayMetrics.density
        val size = (BALL_SIZE_DP * density).toInt()
        val pad = (PADDING_DP * density).toInt()

        val view = BallFaceView(this)

        // 拖动 + 单击（区分阈值 8px）
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = event.rawX.toInt()
                    initialY = event.rawY.toInt()
                    lastX = initialX
                    lastY = initialY
                    v.alpha = 0.88f // 按压反馈
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
                    v.alpha = 1f
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
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
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
        val ok = runCatching { windowManager.addView(ballView, params) }.isSuccess
        if (ok) {
            isBallInWindow = true
            retryJob?.cancel()
            retryJob = null
        } else {
            isBallInWindow = false
            // 权限晚到场景：启动时 appops 未生效 addView 被拒——
            // 轮询重试直到加窗成功（权限经 Shizuku 授予后即出现）
            startAddViewRetry()
        }
    }

    /**
     * addView 重试：每 2s 检查一次，最长 30s。
     * 权限到位（appops 生效 / 用户系统设置手动开）即成功加窗；
     * 超时不死循环，静默放弃（下次开关操作/启动重新触发）。
     */
    private fun startAddViewRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            var elapsed = 0
            while (elapsed < 30_000) {
                delay(2_000)
                elapsed += 2_000
                if (!android.provider.Settings.canDrawOverlays(this@FloatingBallService)) {
                    continue
                }
                val params = ballView.tag as WindowManager.LayoutParams
                val ok = runCatching { windowManager.addView(ballView, params) }.isSuccess
                if (ok) {
                    isBallInWindow = true
                    retryJob = null
                    break
                }
            }
        }
    }

    /** 拖动范围限制：球整体始终留在屏幕可见区域内，防止把球拖丢。 */
    private fun clampBallPosition(x: Int, y: Int): Pair<Int, Int> {
        val ballPx = (BALL_SIZE_DP * resources.displayMetrics.density).toInt()
        // 统一尺寸源：R+ 用 maximumWindowMetrics（全屏，含系统栏），旧 API 回退
        val screenW: Int
        val screenH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager)
                .maximumWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            screenW = resources.displayMetrics.widthPixels
            @Suppress("DEPRECATION")
            screenH = resources.displayMetrics.heightPixels
        }
        // Gravity.END 坐标系（与 showMenu 的 ballAbsLeft 反演同源）：
        // x = 屏幕右缘到球右缘的距离 → 全可见区间 [0, screenW - ballPx]
        val clampedX = x.coerceIn(0, (screenW - ballPx).coerceAtLeast(0))
        val clampedY = y.coerceIn(0, (screenH - ballPx).coerceAtLeast(0))
        return Pair(clampedX, clampedY)
    }

    private fun updatePosition(x: Int, y: Int) {
        val params = ballView.tag as WindowManager.LayoutParams
        val (cx, cy) = clampBallPosition(x, y)
        params.x = cx
        params.y = cy
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
            "📸 立即截屏",
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
                    1 -> quickScreenshot()
                    2 -> openLatestImage()
                    3 -> openLatestVideo()
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
            // 尺寸源统一：与 clampBallPosition 同源（R+ maximumWindowMetrics，
            // 旧 API displayMetrics；此前两处混用，审计卫生级 14）
            val screenW: Int
            val screenH: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager)
                    .maximumWindowMetrics.bounds
                screenW = bounds.width()
                screenH = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                screenW = resources.displayMetrics.widthPixels
                @Suppress("DEPRECATION")
                screenH = resources.displayMetrics.heightPixels
            }
            val ballPx = (BALL_SIZE_DP * density).toInt()
            // x 反演成绝对坐标：球距右缘 x，则球左缘绝对坐标 = screenW - x - ballSize
            val ballAbsLeft = screenW - ballParams.x - ballPx
            val ballAbsTop = ballParams.y
            if (ballAbsLeft - width - pad >= 0) {
                // 球左侧空间够：菜单贴球左
                gravity = Gravity.TOP or Gravity.END
                x = ballParams.x + ballPx + pad
                y = ballAbsTop
            } else {
                // 球在屏幕左半侧：菜单放到球右侧（防越出左缘被裁剪）
                gravity = Gravity.TOP or Gravity.START
                x = ballAbsLeft + ballPx + pad
                y = ballAbsTop
            }
            // 垂直翻转：菜单贴屏幕底缘放不下（约 240dp 高）时上翻到球上方，
            // 防止底部菜单项伸出屏幕不可点（审计体验级 3）
            val menuH = 4 * 62f * density // 4 项 × 约 62dp/项（padding 14+14 + 15sp 文字）
            if (y + menuH > screenH) {
                y = (ballAbsTop - menuH).toInt().coerceAtLeast(0)
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

    /** 立即截屏（Application.screenshotQuick，与磁贴同链路；悬浮球自身已隐藏不入境）。 */
    private fun quickScreenshot() {
        scope.launch {
            val ok = runCatching { app.screenshotQuick() }.getOrElse { false }
            if (!ok) {
                Toast.makeText(this@FloatingBallService, "截屏失败喵", Toast.LENGTH_SHORT).show()
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

    /** 录制中 → 红色 REC；空闲 → 取景框 + 品牌粉镜头。 */
    private fun refreshBallColor(recording: Boolean) {
        (ballView as? BallFaceView)?.setRecording(recording)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 重启/开关重触发场景：球不在窗且权限已到位 → 立即补加窗
        if (!isBallInWindow && android.provider.Settings.canDrawOverlays(this)) {
            val params = ballView.tag as WindowManager.LayoutParams
            val ok = runCatching { windowManager.addView(ballView, params) }.isSuccess
            if (ok) {
                isBallInWindow = true
                retryJob?.cancel()
                retryJob = null
            }
        }
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

    /** 截屏窗口期隐藏/恢复球与菜单（幂等；任意线程可调，内部转主线程）。 */
    fun setBallHidden(hidden: Boolean) {
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() === main) {
            setBallHiddenInternal(hidden)
        } else {
            android.os.Handler(main).post { setBallHiddenInternal(hidden) }
        }
    }

    /** 实际隐藏/恢复逻辑（必须主线程：WindowManager addView/removeView）。 */
    private fun setBallHiddenInternal(hidden: Boolean) {
        if (hidden) {
            // 截屏窗口期暂停重试（防止 tick 恰好把球加回来拍进截图）
            retryJob?.cancel()
            retryJob = null
            dismissMenu()
            runCatching { windowManager.removeView(ballView) }
            isBallInWindow = false
        } else {
            if (!isBallInWindow) {
                // E2 修复：隐藏期间 UP/CANCEL 丢失 → alpha 可能残留 0.88，重置
                ballView.alpha = 1f
                val ok = runCatching {
                    windowManager.addView(
                        ballView,
                        ballView.tag as WindowManager.LayoutParams
                    )
                }.isSuccess
                isBallInWindow = ok
                if (!ok && android.provider.Settings.canDrawOverlays(this)) {
                    // 权限已到但本次加窗失败（如短暂窗口抖动）——重启重试，
                    // 防止球在截图隐藏后永久丢失（审计阻断级 1c）
                    startAddViewRetry()
                }
            }
            refreshBallColor(
                app.recordingSessionManager.currentState()
                    is RecordingSessionManager.SessionState.Recording
            )
        }
    }

    /** 球当前是否在窗口中。初始 false——onCreate 的 addToWindow 成功才置
 *  true（失败路径交由 retryJob 补位；此前初始 true 导致失败场景标志残留，
 *  onStartCommand 补加窗失效，审计阻断级 1）。 */
    private var isBallInWindow = false

    override fun onDestroy() {
        com.rainy.screenshot.capture.BallHiderRegistry.unregister(ballHider)
        stateJob?.cancel()
        retryJob?.cancel()
        dismissMenu()
        scope.cancel()
        runCatching { windowManager.removeView(ballView) }
        super.onDestroy()
    }
}

/**
 * 悬浮球自绘 View（极简风重设计 v3，纯 Paint 绘制无资源依赖）。
 *
 * v3 设计（吸取 v2 白球在浅色背景失焦 + 灰阴影显脏的教训）：
 * - 球体：品牌粉主体（微渐变立体），任何壁纸都醒目——悬浮感靠
 *   色相对比而非阴影（灰阴影在浅色背景呈「脏」感，已弃用）
 * - 描边：1.5dp 细白边（浅色/粉色壁纸下的边界保险，不粗不土）
 * - 图形：白色对焦取景框（四角 L 线）+ 中心白圆点——「截取」隐喻
 * - 录制态：球体变红 + 中心白色 REC 方块
 *
 * 录制状态变化时 invalidate() 重绘球面。
 */
private class BallFaceView(context: android.content.Context) : View(context) {

    /** 空闲态品牌粉（草莓粉，与 App 主题呼应）：中心亮 → 边缘深。 */
    private val pinkCenter = 0xFFFFB1C8.toInt()
    private val pinkEdge = 0xFFFF7FA0.toInt()

    /** 录制态红：中心亮 → 边缘深。 */
    private val redCenter = 0xFFF4666F.toInt()
    private val redEdge = 0xFFE9495D.toInt()

    /** 图形与描边白。 */
    private val pureWhite = 0xFFFFFFFF.toInt()

    @Volatile
    private var recording = false

    fun setRecording(recording: Boolean) {
        if (this.recording == recording) return
        this.recording = recording
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val w = width.toFloat()
        val cx = w / 2f
        val cy = w / 2f
        val radius = w / 2f

        // 1. 球体：品牌粉微渐变（光源正上方，上亮下暗的微妙立体感）
        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy - radius * 0.3f, radius * 1.15f,
                if (recording) redCenter else pinkCenter,
                if (recording) redEdge else pinkEdge,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, facePaint)

        // 2. 细白描边：浅色/粉色壁纸下的边界保险
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = pureWhite
        }
        canvas.drawCircle(cx, cy, radius - strokePaint.strokeWidth / 2f, strokePaint)

        // 3. 对焦取景框：四角 L 线（「截取」隐喻，圆头端点更精致）
        val half = w * 0.21f          // 取景框半边长
        val arm = half * 0.75f        // L 线臂长
        val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.4f * density
            color = pureWhite
        }
        // 左上角
        canvas.drawLine(cx - half, cy - half + arm, cx - half, cy - half, bracketPaint)
        canvas.drawLine(cx - half, cy - half, cx - half + arm, cy - half, bracketPaint)
        // 右上角
        canvas.drawLine(cx + half - arm, cy - half, cx + half, cy - half, bracketPaint)
        canvas.drawLine(cx + half, cy - half, cx + half, cy - half + arm, bracketPaint)
        // 左下角
        canvas.drawLine(cx - half, cy + half - arm, cx - half, cy + half, bracketPaint)
        canvas.drawLine(cx - half, cy + half, cx - half + arm, cy + half, bracketPaint)
        // 右下角
        canvas.drawLine(cx + half - arm, cy + half, cx + half, cy + half, bracketPaint)
        canvas.drawLine(cx + half, cy + half, cx + half, cy + half - arm, bracketPaint)

        // 4. 中心元素：镜头圆点（空闲）/ REC 方块（录制中）
        if (recording) {
            // REC 方块缩至 0.17w：与四角线（0.21w）保持视觉间隙，
            // 避免白色笔画相连合并（审计体验级 1）
            val side = w * 0.17f
            val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pureWhite
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                cx - side, cy - side, cx + side, cy + side,
                2.5f * density, 2.5f * density,
                recPaint
            )
        } else {
            val dotRadius = w * 0.085f
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pureWhite
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
        }
    }
}