package io.github.nisaka520.jevguide

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 常驻悬浮条：把「当前联系人的攻略度」一直挂在屏幕上，而不是每次弹个窗看一眼就没了。
 *
 * ## 为什么用无障碍浮层
 *
 * 无障碍服务可以用 `TYPE_ACCESSIBILITY_OVERLAY` 直接加窗口，**不需要 SYSTEM_ALERT_WINDOW**
 * （那个"显示在其他应用上层"的权限还得让用户去系统设置里翻）。代价是这个窗口只在本服务的
 * 生命周期内存在 —— 服务被系统杀掉，浮层也跟着没了（这反而合理：没服务就没数据）。
 *
 * ## 长相
 *
 * 胶囊形 + 左侧一个**分数色环**（≥70 绿 / ≥40 黄 / 其余红）+ 联系人与百分比。
 * 颜色取主题属性，所以能跟上 Material You 的壁纸取色（跟设置页、结果页一致）。
 *
 * ⚠ 它是用**无障碍服务的 context** 造 View 的，而 Material 主题不在那个 context 上，
 * 所以必须先套一层 `ContextThemeWrapper`，否则取主题属性会拿到系统默认值（颜色全错）。
 *
 * ## 交互
 *
 * - **拖**：按住拖动，位置记进设置，下次还在那儿
 * - **点**：有结果 → 打开结果页（3 条文案在那儿复制）；没结果 → 立刻判读一次
 * - **长按**：隐藏（想再要回来去设置页开，或重开无障碍服务）
 *
 * ## 为什么不做成"只在微信前台显示"
 *
 * 服务的 `packageNames` 只订阅了微信，拿不到别的 App 的窗口事件，所以判断不了"现在是不是微信"。
 * 与其猜，不如让用户自己拖走/长按关掉 —— 说清楚比自作聪明强。
 */
object ScoreOverlay {

    private const val PAD_H = 14
    private const val PAD_V = 9
    private const val RING = 10          // 色环外径 dp
    private const val RING_STROKE = 3    // 环宽 dp

    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var ring: View? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    /**
     * WindowManager 必须在有 Looper 的线程上操作；判读在后台线程，所以要自己切回主线程。
     *
     * ⚠ 用 `by lazy`：`format()` / `colorOf()` 是纯逻辑，要能在纯 JVM 单测里跑 ——
     * 如果这里在对象初始化时就 `Looper.getMainLooper()`，单测一加载这个类就炸（NoClassDefFoundError）。
     */
    private val main: android.os.Handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 最近一次判读的产物：点浮层时用它打开结果页 */
    @Volatile
    var lastPayload: ResultPayload? = null

    /** 结果页打开期间压制浮条：屏幕上只留一个浮窗，不叠着显示 */
    @Volatile
    private var suppressed = false

    fun isShowing(): Boolean = root != null

    /**
     * 结果页进出时调用：进去就收起来，出来再按上次的文字恢复。
     * 从 [WatchService.instance] 拿服务（结果页自己不是无障碍服务）。
     */
    fun setSuppressed(cfg: Config, on: Boolean) {
        suppressed = on
        if (on) {
            hide()
        } else {
            val svc = WatchService.instance ?: return
            val t = cfg.lastOverlayText.ifEmpty { format("微信", null, null) }
            show(svc, cfg, t, cfg.lastOverlayPercent.takeIf { it >= 0 })
        }
    }

    /** 纯逻辑：浮层上写什么。抽出来是为了能单测（不碰 Android） */
    fun format(title: String, percent: Int?, trend: Int?): String {
        val who = title.trim().ifEmpty { "微信" }
        if (percent == null) return "$who · 点我判读"
        val tail = when {
            trend == null -> ""
            trend > 0 -> " ↑$trend"
            trend < 0 -> " ↓${abs(trend)}"
            else -> " →"
        }
        return "$who · 攻略度 $percent%$tail"
    }

    /** 分数配色：跟结果页保持一致，扫一眼就知道好坏 */
    fun colorOf(percent: Int?): Int = when {
        percent == null -> 0xFF9AA8BB.toInt()
        percent >= 70 -> 0xFF7EE0A8.toInt()
        percent >= 40 -> 0xFFFFD54F.toInt()
        else -> 0xFFE57373.toInt()
    }

    /** 显示或更新（已经在就只改文字与颜色） */
    fun show(service: AccessibilityService, cfg: Config, text: String, percent: Int?) {
        if (!cfg.overlayEnabled || suppressed) return
        // 判读跑在后台线程，而 addView/updateViewLayout 需要主线程的 Looper —— 统一在这里切
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            main.post { show(service, cfg, text, percent) }
            return
        }
        try {
            val existing = label
            if (existing != null) {
                existing.text = text
                existing.setTextColor(colorOf(percent))
                ring?.background = ringDrawable(service, percent)
                return
            }

            // Material 主题不在无障碍服务的 context 上，得自己套一层，否则取主题属性全是系统默认值
            val themed = ContextThemeWrapper(service, R.style.Theme_JevGuide_Overlay)
            val container = themedColor(
                themed, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF1A1F2A.toInt()
            )
            val outline = themedColor(themed, com.google.android.material.R.attr.colorOutline, 0xFF3A3A3E.toInt())

            val tv = TextView(themed).apply {
                this.text = text
                setTextColor(colorOf(percent))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(themed, 2), 0, 0, 0)
                maxLines = 1
            }
            val dot = View(themed).apply {
                background = ringDrawable(service, percent)
                layoutParams = LinearLayout.LayoutParams(dp(service, RING), dp(service, RING))
            }
            // 左区：色环 + 名字与攻略度 —— 点它看结果/选项
            val left = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot)
                addView(tv)
            }
            // 右区：一个「识别」图标键 —— 点它立刻重新读一次。
            // 为什么非要单独一个键：原来整颗胶囊只有一个动作，读过一次之后它就只会打开结果页，
            // 想再读一次的人就找不到入口了（用户原话："读取一次之后，再点击浮条，变成了选项。之后怎么读取呢？"）
            val sep = View(themed).apply {
                background = GradientDrawable().apply { setColor(outline) }
                layoutParams = LinearLayout.LayoutParams(dp(service, 1), dp(service, 18)).apply {
                    leftMargin = dp(service, 10)
                    rightMargin = dp(service, 10)
                }
            }
            val icon = ImageView(themed).apply {
                setImageResource(R.drawable.ic_scan)
                imageTintList = android.content.res.ColorStateList.valueOf(colorOf(percent))
                layoutParams = LinearLayout.LayoutParams(dp(service, 20), dp(service, 20))
            }
            val box = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(service, PAD_H), dp(service, PAD_V), dp(service, PAD_H), dp(service, PAD_V))
                background = GradientDrawable().apply {
                    // 圆角给得比高度还大 → 系统会钳到"半高"，于是得到胶囊形（不用去量实际高度）
                    cornerRadius = dp(service, 40).toFloat()
                    setColor(container)
                    setStroke(dp(service, 1), outline)
                }
                elevation = dp(service, 6).toFloat()
                addView(left)
                addView(sep)
                addView(icon)
            }
            // 色环用"分数语义色"（跟结果页同一套阈值色），刻意不跟壁纸走
            val w = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = cfg.overlayX
                y = cfg.overlayY
            }

            box.setOnTouchListener(DragTap(service, cfg, w, p))
            w.addView(box, p)
            root = box
            label = tv
            ring = dot
            wm = w
            params = p
            AppLog.add("常驻悬浮条已显示：$text")
        } catch (t: Throwable) {
            // 厂商可能禁掉无障碍浮层；失败不该影响判读本身
            AppLog.add("悬浮条显示失败：${t.javaClass.simpleName} ${t.message ?: ""}")
        }
    }

    fun hide() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            main.post { hide() }
            return
        }
        val v = root ?: return
        try {
            wm?.removeView(v)
        } catch (t: Throwable) {
            AppLog.add("悬浮条移除失败：${t.javaClass.simpleName}")
        }
        root = null
        label = null
        ring = null
        wm = null
        params = null
    }

    /** 分数色环：空心圆环，颜色＝分数档位色 */
    private fun ringDrawable(ctx: Context, percent: Int?): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x00000000)
        setStroke(dp(ctx, RING_STROKE), colorOf(percent))
    }

    private fun themedColor(ctx: Context, attrId: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attrId, tv, true) && tv.data != 0) tv.data else fallback
    }

    @Suppress("unused")
    private fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private class DragTap(
        private val service: AccessibilityService,
        private val cfg: Config,
        private val w: WindowManager,
        private val p: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false
        private var longFired = false
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragged = false; longFired = false
                    handler.postDelayed({
                        longFired = true
                        hide()
                        Toast3.toast(service, "已隐藏悬浮条（设置里可再开）")
                    }, 800)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        dragged = true
                        handler.removeCallbacksAndMessages(null)
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try { w.updateViewLayout(v, p) } catch (t: Throwable) { /* 窗口没了就算了 */ }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!dragged && !longFired) {
                        // 记下位置，下次还在原地
                        cfg.overlayX = p.x
                        cfg.overlayY = p.y
                        // 右端 52dp 是「识别」键，其余部分点开结果/选项。
                        // 用坐标分区、而不是给子 View 挂点击：子 View 一旦可点就会吃掉事件，
                        // 那样连拖动都没法从图标上起手了。
                        val rightZone = v.width - dp(service, 52)
                        if (e.x >= rightZone) analyzeNow() else openResult()
                    } else if (dragged) {
                        cfg.overlayX = p.x
                        cfg.overlayY = p.y
                    }
                    return true
                }
            }
            return false
        }

        /** 左区：有结果就打开结果页（文案在那儿复制）；还没结果就先识别一次 */
        private fun openResult() {
            val payload = lastPayload
            if (payload == null) {
                Toast3.toast(service, "还没有结果，先识别一次")
                analyzeNow()
                return
            }
            if (!ResultActivity.show(service, payload)) {
                Toast3.toast(service, "结果页打不开，看通知栏", true)
            }
        }

        /**
         * 右区「识别」键：永远只管重新读一次。
         *
         * 这个入口必须**与有没有结果无关** —— 之前整颗胶囊读过一次就只剩
         * "打开结果页"一个动作，于是想再读的人找不到路。
         */
        private fun analyzeNow() {
            val svc = WatchService.instance
            if (svc == null) {
                Toast3.toast(service, "无障碍服务没在运行", true)
                return
            }
            Toast3.toast(service, "开始识别…")
            svc.analyzeNow(true)
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        Math.round(v * ctx.resources.displayMetrics.density)
}
