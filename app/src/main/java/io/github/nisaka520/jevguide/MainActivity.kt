package io.github.nisaka520.jevguide

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * 首页：把设置拆成几个入口，而不是一上来丢一整页几十项给用户。
 *
 * 为什么值得单独做一屏：设置页现在有十几个分区、几十个控件（状态、密钥、联系人表、
 * 判读、聊天模型、记忆、读取方式、悬浮条、诊断、维护），第一次打开的人根本不知道该从哪下手。
 * 首页只回答一个问题：「你想干什么」，点进去再落到那一分区。
 *
 * 实现上**不复制任何设置逻辑**：每个入口只是带着 `section=<key>` 打开设置页，
 * 设置页建完页面后滚到对应分区。所以以后增删设置项，首页不需要跟着改
 * （除了这一屏的文案），也不会出现"两处配置不一致"这种经典坑。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var page: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            addView(page)
            clipToPadding = false
        }
        // 跟设置页一样：targetSdk 35 起系统强制全面屏，得自己把系统栏高度吃成内边距
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)

        title("Jev攻略")
        sub("点下面任一项进设置。判读本身不用打开这个 App —— 在微信里点浮条就行。")

        // 状态 + 启动直接放首页：这两个是「能不能用」的关键，不该藏在二级页里
        statusCard()
        startButton()

        row(
            "设定无障碍和启动", "开无障碍服务、选读取方式（无障碍树／截图识别）、抓屏诊断",
            "a11y", 0xFF6FD3C7.toInt()
        )
        row(
            "浮条设置", "常驻浮条的开关、位置与显示内容；点启动后它会当场出现在屏幕上",
            "overlay", 0xFF7EE0A8.toInt()
        )
        row(
            "模型密钥配置", "填 Jev 密钥（必填）与聊天模型（生成 3 条候选文案用）",
            "key", 0xFF7FB3FF.toInt()
        )
        row(
            "关系设置", "每个联系人是什么关系（普通朋友／同事／对象…），判读会照这个口径来",
            "relation", 0xFFFFC46B.toInt()
        )
        row(
            "记忆管理", "看看记了谁、记了什么；也能一键清空。记忆只存本机",
            "memory", 0xFFB79CFF.toInt()
        )
        row(
            "关于本软件", "版本、隐私说明、邀请码与免责声明",
            "about", 0xFF9AA8BB.toInt()
        )
    }

    override fun onStart() {
        super.onStart()
        // 首页也把浮条收起来：它只该出现在微信里，不该压住自己的界面
        ScoreOverlay.setSuppressed(Config(this), true)
    }

    override fun onStop() {
        super.onStop()
        ScoreOverlay.setSuppressed(Config(this), false)
    }

    private lateinit var statusText: TextView

    /** 首页顶部状态：一眼看清「能不能用」，不用点进去猜 */
    private fun statusCard() {
        statusText = TextView(this).apply {
            setTextColor(cOnSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(cContainerHigh)
                cornerRadius = dp(20).toFloat()
            }
        }
        page.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        refreshStatus()
    }

    /**
     * 从系统设置授权回来时要重新读一遍：状态卡是建页面时填的，不刷新就会一直显示旧状态
     * （用户刚授权完回到首页，却还写着「未开启」，会以为授权没生效）。
     */
    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refreshStatus()
    }

    private fun refreshStatus() {
        val cfg = Config(this)
        val a11y = when {
            WatchService.instance != null -> "运行中 ✓"
            a11yEnabled() -> "已授权，但服务还没连上（点启动重挂）"
            else -> "未开启（点启动去授权）"
        }
        statusText.text = listOf(
            "无障碍服务：$a11y",
            "Jev 密钥：" + if (cfg.hasKey()) "已配置（${cfg.apiKey.length} 字符）" else "还没填",
            "聊天模型：" + if (cfg.hasChatKey()) cfg.chatModel else "没配（只有判读，没有候选文案）",
            "常驻浮条：" + if (cfg.overlayEnabled) "开" else "关"
        ).joinToString("\n")
    }

    private fun startButton() {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "启动"
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(20)
            setOnClickListener { doStart() }
        }
        page.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
    }

    /**
     * 启动按钮。顺序有讲究：
     *   1. 通知权限（Android 13+ 要用户点头，没它常驻通知不显示）
     *   2. 无障碍没授权 → 直接把人送到系统设置（App 无权代开，只能请求授权）
     *   3. 已授权 → restartSelf()：清掉上一次的浮层与按钮标志，再按当前配置重挂
     *   4. 密钥没填 → 提醒去「模型密钥配置」
     */
    private fun doStart() {
        askNotification()
        if (WatchService.instance == null && !a11yEnabled()) {
            toast("请在接下来弹出的系统设置里打开「Jev攻略」，然后回来再点一次启动")
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (t: Throwable) {
                toast("打不开系统无障碍设置：" + (t.message ?: ""))
            }
            return
        }
        val svc = WatchService.instance
        if (svc == null) {
            toast("已授权，但系统还没把服务连上，等 1~2 秒再点一次")
            return
        }
        toast(svc.restartSelf())
        if (!Config(this).hasKey()) toast("还没配 Jev 密钥，去「模型密钥配置」填一下")
    }

    /** 无障碍服务是否已在系统设置里被勾选（必须用实际包名，debug 包带 .debug 后缀） */
    private fun a11yEnabled(): Boolean {
        val want = ComponentName(this, WatchService::class.java).flattenToString()
        val on = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        } catch (t: Throwable) {
            ""
        }
        return on.split(':').any { it.equals(want, true) }
    }

    private fun askNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } catch (_: Exception) {
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /**
     * 进二级页的转场：右边滑入 + 旧的淡出。
     *
     * 不这么做的话点卡片是「啪」地整页换掉，观感像卡了一下、也看不出层级关系；
     * 有方向感的滑动能直接告诉用户「你进到更深一层了」，返回时反向滑回来。
     * 用系统自带的 android.R.anim.*，不新增任何资源文件。
     */
    @Suppress("DEPRECATION")
    private fun forwardTransition() = overridePendingTransition(
        R.anim.slide_in_right, R.anim.slide_out_left
    )

    /** 一行入口：左边一个色点（分区色），中间标题+说明，右边一个 › */
    private fun row(title: String, sub: String, section: String, accent: Int) {
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                rightMargin = dp(14)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(cOnSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = sub
                setTextColor(cOnSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(3), 0, 0)
            })
        }
        val arrow = TextView(this).apply {
            text = "›"
            setTextColor(cOnSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(8), 0, 0, 0)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            addView(dot)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow)
        }
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(cContainerHigh)
            isClickable = true
            isFocusable = true
            addView(inner)
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, SettingsActivity::class.java).putExtra("screen", section)
                )
                forwardTransition()
            }
        }
        page.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
    }

    private fun attr(@androidx.annotation.AttrRes id: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(id, tv, true) && tv.data != 0) tv.data else fallback
    }

    private val cOnSurface: Int get() = attr(com.google.android.material.R.attr.colorOnSurface, 0xFF1A1C1E.toInt())
    private val cOnSurfaceVariant: Int
        get() = attr(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF44474E.toInt())
    private val cContainerHigh: Int
        get() = attr(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFE7E8EC.toInt())

    private fun title(t: String) = page.addView(TextView(this).apply {
        text = t
        setTextColor(cOnSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(4))
    })

    private fun sub(t: String) = page.addView(TextView(this).apply {
        text = t
        setTextColor(cOnSurfaceVariant)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(2), 0, dp(2), dp(6))
    })

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
