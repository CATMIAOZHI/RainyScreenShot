package com.rainy.screenshot.capture

/**
 * 悬浮球隐藏控制器（依赖反转，断 capture → overlay 环）。
 *
 * capture 引擎层截屏前隐藏悬浮球（防球/菜单入镜），但不依赖
 * overlay 层具体实现——由 FloatingBallService 启动时注册、
 * 销毁时注销。服务未运行时 hide/show 均为无操作。
 *
 * 线程契约：实现方必须能从任意线程被调用（引擎侧统一在
 * Dispatchers.Main.immediate 上下文中调用，见 ScreenshotEngine）。
 */
fun interface BallHider {

    /** @param hidden true = 隐藏球与菜单；false = 恢复。幂等。 */
    fun setHidden(hidden: Boolean)
}

/** BallHider 实现注册表（悬浮球服务生命周期内注册）。 */
object BallHiderRegistry {

    @Volatile
    private var impl: BallHider? = null

    /** 注册实现（悬浮球服务 onCreate）。 */
    fun register(hider: BallHider) {
        impl = hider
    }

    /** 注销（悬浮球服务 onDestroy；幂等）。 */
    fun unregister(hider: BallHider) {
        if (impl === hider) impl = null
    }

    /** 隐藏悬浮球（无实现时 no-op）。 */
    fun hide() {
        impl?.setHidden(true)
    }

    /** 恢复悬浮球（无实现时 no-op）。 */
    fun show() {
        impl?.setHidden(false)
    }
}