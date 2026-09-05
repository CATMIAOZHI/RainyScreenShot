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
import com.rainy.screenshot.recordingSessionManager
import com.rainy.screenshot.settingsStore
import com.rainy.screenshot.session.RecordingSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 悬浮控制球（阶段 3）。
 *
 * 职责：任意界面常驻小圆球，点击 = 录屏开始/停止（静默，无弹窗）。
 * - 需要 SYSTEM_ALERT_WINDOW 权限（manifest 已声明，设置页开关引导授予）
 * - 拖动移动位置；录制中变红点，空闲变樱粉
 * - 状态与 RecordingSessionManager 单例联动（多入口一致性）
 */
class FloatingBallService : Service() {

    companion object {
        private const val BALL_SIZE_DP = 56
        private const val PADDING_DP = 8

        /** 启动/停止悬浮球 */
        fun start(context: android.content.Context) {
            // 注意：不用 startForegroundService——那会强制前台通知（可感知信号，
            // 违反隐身性红线）。悬浮球由用户主动开启，普通 startService 即可
            // 常驻（App 退后台不杀已启动服务）。
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

    /** 状态流订阅（time-limit 自动结束/他处停止时球色同步，B2 修复）。 */
    private var stateJob: kotlinx.coroutines.Job? = null

    private var lastY = 0
    private var lastX = 0
    private var initialY = 0
    private var initialX = 0
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

        // 拖动 + 点击
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
                        updatePosition(event.rawX.toInt() - (size / 2), event.rawY.toInt() - pad)
                    }
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        handleClick()
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
            // B2 修复：初始右上角（贴屏幕右缘，留 8dp 边距）
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

    /** 点击：录屏启停（经 SessionManager，与首页/磁贴一致）。 */
    private fun handleClick() {
        scope.launch {
            val manager = app.recordingSessionManager
            if (manager.currentState() is RecordingSessionManager.SessionState.Recording) {
                // B2 修复：stop 结果结构化，失败时球色保持录制红，
                // 不误报为空闲
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
        // B2 修复：订阅状态流，time-limit 自动结束/他处启停时颜色同步
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
        scope.cancel()
        runCatching { windowManager.removeView(ballView) }
        super.onDestroy()
    }
}