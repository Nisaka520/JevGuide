package io.github.nisaka520.jevguide

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import io.github.nisaka520.jevguide.ui.Design
import io.github.nisaka520.jevguide.ui.UiKit
import io.github.nisaka520.jevguide.ui.installSystemBarInsets
import io.github.nisaka520.jevguide.ui.isNight
import io.github.nisaka520.jevguide.ui.playForwardTransition
import io.github.nisaka520.jevguide.ui.registerOpenTransition

/**
 * 首页：「仪表台」。
 *
 * 为什么值得单独做一屏：设置页有十几个分区、几十个控件（状态、密钥、联系人表、
 * 判读、聊天模型、记忆、读取方式、悬浮条、诊断、维护），第一次打开的人根本不知道从哪下手。
 * 首页只回答两个问题：
 *  1. **现在能不能用？** —— 就绪面板：一排状态灯 + 一个总开关（启动）。
 *  2. **想去哪一分区调东西？** —— 7 张编号入口行，点进去带 `section=<key>` 打开设置页。
 *
 * 实现上**不复制任何设置逻辑**：每个入口只是带着 `section` 打开设置页，设置页建完页面后
 * 滚到对应分区。所以以后增删设置项，首页不需要跟着改（除了这一屏的文案），
 * 也不会出现"两处配置不一致"这种经典坑。section 的合法值以 SettingsActivity 的
 * `sectionRuns` 为准（a11y/overlay/key/relation/memory/adv/about）。
 *
 * 视觉（「仪表台」方向，变量全部来自 [Design]）：首屏主角是**就绪面板**——
 * 大号状态词（已就绪/缺配置/未启动）配红绿灯 LED，四条检查行可逐项扫读；
 * 设置入口退成编号行，accent 只出现在编号上（剂量 ≤10%）。不做插画、不堆阴影，
 * 层次全靠字号、容器色阶与留白。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var page: LinearLayout

    // ── 就绪面板的活引用：onResume（从系统设置授权回来）要整组重刷 ──
    private lateinit var verdictLed: UiKit.Led
    private lateinit var verdictWord: TextView
    private val checkLeds = ArrayList<UiKit.Led>()
    private val checkValues = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        page = UiKit.column(this).apply {
            setPadding(dp(Design.Space.L), dp(Design.Space.XL), dp(Design.Space.L), dp(Design.Space.XL))
        }
        val scroll = ScrollView(this).apply {
            addView(page)
            installSystemBarInsets()
        }
        // API34+ 的转场必须在 onCreate 注册才会生效（老 API 那半边在 openSection 的
        // playForwardTransition 里兜底）—— ThemeExt 里封装的双路径
        registerOpenTransition()
        setContentView(scroll)

        // 首启先把"要读聊天内容并上传到你配的接口"讲清楚、拿到明确同意（详见 maybeAskConsent）
        maybeAskConsent()

        buildHeader()
        buildReadinessPanel()
        buildEntryGroups()
        buildFooter()
        refreshStatus()
    }

    // ────────────────────────── 页面搭建

    /** 品牌头：一行大字 + 一行副题。不做图片横幅 —— 仪器面板的封面就是状态本身 */
    private fun buildHeader() {
        page.addView(UiKit.text(this, "弦外之音", Design.Type.DISPLAY, Design.onSurface(this), bold = true))
        page.addView(
            UiKit.text(this, "听懂每句话的言外之意", Design.Type.CAPTION, Design.onSurfaceVariant(this)),
            wrapTop(Design.Space.XS)
        )
    }

    /** 就绪面板（首屏主角）：总判（LED + 状态词）→ 四条检查行 → 启动/停止 */
    private fun buildReadinessPanel() {
        val night = isNight()
        val box = UiKit.column(this).apply {
            setPadding(dp(Design.Space.L), dp(Design.Space.L), dp(Design.Space.L), dp(Design.Space.L))
        }
        val panel = UiKit.card(this, Design.containerLow(this), Design.Radius.LG).apply { addView(box) }

        // ── 总判：一颗 LED + 一个大字状态词。眯着眼也能扫出"现在能不能用" ──
        verdictLed = UiKit.Led(this, 14, Design.scoreNeutral(night))
        verdictWord = UiKit.text(this, "…", Design.Type.DISPLAY, Design.scoreNeutral(night), bold = true)
        val head = UiKit.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(verdictLed)
        head.addView(
            verdictWord,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(Design.Space.M)
            }
        )
        box.addView(head)
        box.addView(
            UiKit.text(this, "四项检查都过了，就可以去微信里点浮条", Design.Type.CAPTION, Design.onSurfaceVariant(this)),
            wrapTop(Design.Space.XS)
        )

        // ── 检查行：每行一颗小 LED + 标签 + 当前值（值里带下一步该去哪的提示）──
        checkSpecs().forEach { spec ->
            val row = UiKit.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val led = UiKit.Led(this, 8, Design.scoreNeutral(night))
            checkLeds.add(led)
            row.addView(led)
            val col = UiKit.column(this).apply {
                addView(UiKit.text(this@MainActivity, spec.label, Design.Type.CAPTION, Design.onSurfaceVariant(this@MainActivity)))
                addView(
                    UiKit.text(this@MainActivity, "", Design.Type.BODY, Design.onSurface(this@MainActivity)).also {
                        checkValues.add(it)
                    },
                    wrapTop(Design.Space.XS)
                )
            }
            row.addView(
                col,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(Design.Space.M)
                }
            )
            box.addView(row, wrapTop(Design.Space.M))
        }

        // ── 总开关：启动是全页唯一的实心主动作；停止是描边次动作 ──
        box.addView(
            UiKit.button(this, "启动判读", filled = true) { doStart() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(Design.Space.L) }
        )
        box.addView(
            UiKit.button(this, "全部停止", filled = false) { doStop() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(Design.Space.S) }
        )
        page.addView(panel, wrapTop(Design.Space.L))
    }

    /** 三个分组、7 张编号入口行。section 值 = SettingsActivity 的分区协议，逐字对齐 */
    private fun buildEntryGroups() {
        entryGroups().forEach { (groupTitle, entries) ->
            page.addView(
                UiKit.text(this, groupTitle, Design.Type.CAPTION, Design.onSurfaceVariant(this), bold = true),
                wrapTop(Design.Space.XL)
            )
            entries.forEach { e ->
                page.addView(entryRow(e), wrapTop(Design.Space.S))
            }
        }
    }

    /** 一行入口：分区色编号 + 标题/说明 + ›。整行可点（涟漪由 MaterialCardView 自带） */
    private fun entryRow(e: Entry): MaterialCardView {
        val num = UiKit.text(this, e.no, Design.Type.CAPTION, Design.accent(e.accent, isNight()), bold = true)
        val texts = UiKit.column(this).apply {
            addView(
                UiKit.text(this@MainActivity, e.title, Design.Type.BODY, Design.onSurface(this@MainActivity), bold = true)
            )
            addView(
                UiKit.text(
                    this@MainActivity, e.sub, Design.Type.CAPTION,
                    Design.onSurfaceVariant(this@MainActivity), lineSpacingDp = 3
                ).apply { setPadding(0, dp(Design.Space.XS), 0, 0) },
                wrapTop(Design.Space.XS)
            )
        }
        val arrow = UiKit.text(this, "›", Design.Type.BODY, Design.onSurfaceVariant(this), bold = true)
            .apply { alpha = 0.6f }

        val inner = UiKit.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Design.Space.L), dp(Design.Space.M), dp(Design.Space.M), dp(Design.Space.M))
            addView(num, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                texts,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(Design.Space.XS) }
            )
            addView(arrow)
        }
        return UiKit.card(this, Design.containerHigh(this), Design.Radius.MD).apply {
            isClickable = true
            isFocusable = true
            addView(inner)
            setOnClickListener { openSection(e.section) }
        }
    }

    /** 页脚一句：判读的真正入口在微信浮条里，不在本 App */
    private fun buildFooter() {
        page.addView(
            UiKit.text(this, "判读本身不用打开这个 App —— 在微信里点浮条就行。", Design.Type.CAPTION, Design.onSurfaceVariant(this))
                .apply { alpha = 0.75f },
            wrapTop(Design.Space.XL)
        )
    }

    // ────────────────────────── 数据

    private data class CheckSpec(
        val label: String,
        val value: (Config) -> String,
        val level: (Config) -> Int,   // 2=好（绿）1=警告（黄）0=未就绪（灰）
    )

    /** 四条检查：口径与旧版一致，一条都不能少（这是"能不能用"的完整清单） */
    private fun checkSpecs(): List<CheckSpec> = listOf(
        CheckSpec(
            "无障碍服务",
            { _ ->
                when {
                    WatchService.instance != null -> "运行中 ✓"
                    a11yEnabled() -> "已授权，但服务还没连上（点启动重挂）"
                    else -> "未开启（点启动去授权）"
                }
            },
            { _ ->
                when {
                    WatchService.instance != null -> 2
                    a11yEnabled() -> 1
                    else -> 0
                }
            }
        ),
        CheckSpec(
            "Jev 密钥",
            { cfg -> if (cfg.hasKey()) "已配置（${cfg.apiKey.length} 字符）" else "还没填" },
            { cfg -> if (cfg.hasKey()) 2 else 0 }
        ),
        CheckSpec(
            "聊天模型",
            { cfg -> if (cfg.hasChatKey()) cfg.chatModel else "没配（只有判读，没有候选文案）" },
            { cfg -> if (cfg.hasChatKey()) 2 else 0 }
        ),
        CheckSpec(
            "常驻浮条",
            { cfg -> if (cfg.overlayEnabled) "开" else "关" },
            { cfg -> if (cfg.overlayEnabled) 2 else 0 }
        ),
    )

    private data class Entry(
        val no: String,
        val title: String,
        val sub: String,
        val section: String,
        val accent: Design.Section,
    )

    /**
     * 分组信息架构：连接微信 → 判读口径 → 调校与关于。
     * **section 值必须与 SettingsActivity 的 sectionRuns 逐字一致**（非法值会被
     * applyScreen 回退成整页设置，入口就失去了定位意义）。
     */
    private fun entryGroups(): List<Pair<String, List<Entry>>> = listOf(
        "连接微信" to listOf(
            Entry("01", "无障碍与启动", "开无障碍服务、选读取方式（无障碍树／截图识别）、抓屏诊断", "a11y", Design.Section.A11Y),
            Entry("02", "常驻悬浮条", "开关、位置与显示内容；点启动后它会当场出现在屏幕上", "overlay", Design.Section.OVERLAY),
        ),
        "判读口径" to listOf(
            Entry("03", "模型密钥", "填 Jev 密钥（必填）与聊天模型（生成 3 条候选文案用）", "key", Design.Section.KEY),
            Entry("04", "关系设置", "每个联系人是什么关系（普通朋友／同事／对象…），判读照这个口径来", "relation", Design.Section.RELATION),
            Entry("05", "记忆管理", "看看记了谁、记了什么；也能一键清空。记忆只存本机", "memory", Design.Section.MEMORY),
        ),
        "调校与关于" to listOf(
            Entry("06", "高级设置", "看/改发给聊天模型的提示词（追加额外要求），以及判读设置", "adv", Design.Section.ADV),
            Entry("07", "关于本软件", "版本、隐私说明、邀请码与免责声明", "about", Design.Section.ABOUT),
        ),
    )

    // ────────────────────────── 状态刷新

    /**
     * 从系统设置授权回来时要重新读一遍：面板是建页面时填的，不刷新就会一直显示旧状态
     * （用户刚授权完回到首页，却还写着「未开启」，会以为授权没生效）。
     */
    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /**
     * 总判三态（与旧版 LED 三态同一口径）：
     * 绿＝服务在跑且两把密钥都齐；黄＝服务在跑但配置还缺；灰＝服务没起来。
     */
    private fun refreshStatus() {
        if (!::verdictWord.isInitialized) return
        val cfg = Config(this)
        val night = isNight()
        val running = WatchService.instance != null
        val color = when {
            running && cfg.hasKey() && cfg.hasChatKey() -> Design.scoreGood(night)
            running -> Design.scoreMid(night)
            else -> Design.scoreNeutral(night)
        }
        verdictLed.setColor(color)
        verdictWord.setTextColor(color)
        verdictWord.text = when {
            running && cfg.hasKey() && cfg.hasChatKey() -> "已就绪"
            running -> "缺配置"
            else -> "未启动"
        }
        val specs = checkSpecs()
        specs.forEachIndexed { i, spec ->
            checkValues[i].text = spec.value(cfg)
            checkLeds[i].setColor(
                when (spec.level(cfg)) {
                    2 -> Design.scoreGood(night)
                    1 -> Design.scoreMid(night)
                    else -> Design.scoreNeutral(night)
                }
            )
        }
    }

    // ────────────────────────── 生命周期与浮条

    override fun onStart() {
        super.onStart()
        // 首页也把浮条收起来：它只该出现在微信里，不该压住自己的界面
        ScoreOverlay.setSuppressed(Config(this), true)
    }

    override fun onStop() {
        super.onStop()
        ScoreOverlay.setSuppressed(Config(this), false)
    }

    // ────────────────────────── 启动 / 关闭

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
            toast("请在接下来弹出的系统设置里打开「弦外之音」，然后回来再点一次启动")
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
        if (!Config(this).hasKey()) toast("还没配 Jev 密钥，去「模型密钥」填一下")
    }

    /**
     * 关闭：停掉判读、收起浮条、撤掉通知、注销无障碍服务，然后清掉整个任务栈。
     *
     * 关于「关闭所有进程」：Android 不给 App 杀自己进程的权限（那是系统的活），
     * 而且杀进程也不会关掉无障碍服务 —— 服务是系统托管的。所以这里做的是
     * disableSelf()（真正注销服务）+ finishAndRemoveTask()（把任务栈里的界面全清掉），
     * 效果就是「全停了」，剩下的空进程由系统回收。
     */
    private fun doStop() {
        val msg = WatchService.instance?.shutdown() ?: "无障碍服务本来就没在跑"
        toast(msg)
        finishAndRemoveTask()
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

    // ────────────────────────── 首启须知

    /**
     * 首启须知：读微信聊天内容、并把内容发给你自己配置的第三方接口 —— 这两件事必须先有**明确的同意**。
     *
     * 为什么不能"装完即生效"：应用商店（尤其 Google Play 的无障碍政策）与《个人信息保护法》
     * 都要求"醒目披露 + 明确的同意动作"，而且**不能只写在隐私政策里**。
     * 不同意就退出 —— 不同意的话这个 App 没有可用的形态，它本来就是靠读聊天干活的。
     * 披露正文与版本号集中在 [Privacy]：改了它就会重新问一次。
     */
    private fun maybeAskConsent() {
        val cfg = Config(this)
        if (cfg.consentVersion >= Privacy.VERSION) return
        AlertDialog.Builder(this)
            .setTitle("使用前请先确认")
            .setMessage(Privacy.disclosure())
            .setCancelable(false)
            .setPositiveButton("我同意") { _, _ ->
                cfg.consentVersion = Privacy.VERSION
                AppLog.add("已同意首启须知 v" + Privacy.VERSION)
            }
            .setNegativeButton("不同意") { _, _ ->
                toast("不同意就先别用 —— 它本来就是靠读聊天干活的")
                finish()
            }
            .setNeutralButton("打开隐私政策") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse(Privacy.URL)
                        )
                    )
                } catch (e: Exception) {
                    toast("打不开浏览器，地址是：" + Privacy.URL)
                }
                // 点了按钮对话框就关了，看完再问一次
                page.postDelayed(Runnable { maybeAskConsent() }, 800)
            }
            .show()
    }

    // ────────────────────────── 导航

    /** 进二级页：带 section 协议打开设置页；转场走 ThemeExt 的双路径封装 */
    private fun openSection(section: String) {
        startActivity(
            Intent(this, SettingsActivity::class.java).putExtra("screen", section)
        )
        playForwardTransition()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun wrapTop(topDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }

    private fun dp(v: Int): Int = UiKit.dp(this, v)
}
