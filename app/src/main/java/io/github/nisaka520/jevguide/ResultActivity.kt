package io.github.nisaka520.jevguide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.nisaka520.jevguide.DraftGenerator.DraftGen
import io.github.nisaka520.jevguide.ui.Design
import io.github.nisaka520.jevguide.ui.UiKit
import io.github.nisaka520.jevguide.ui.isNight

// 数据契约 ResultPayload 与拉起逻辑 intent()/show() 在同包的 ResultPayload.kt（阶段一抽走）；
// intent()/show() 是本类 companion 的扩展函数，调用点写法不变。
// 「重新生成」的编排也不在本页：DraftGenerator 是判读流程与本页共用的唯一实现。

/**
 * 结果页：攻略度 + Jev 判读 + 候选文案（点一下就复制）。
 *
 * 主题是 `Theme.JevGuide.Dialog` —— 它浮在微信上面，关掉就回到聊天。
 * 视觉全部走 [Design]/[UiKit]（「仪表台」同一套变量）：这一屏的主角是**攻略度英雄卡**，
 * 大号百分比用 [Design.Type.DISPLAY]；好/中/差语义色一律走 [Design.scoreColor] 的
 * 红绿灯口径 —— 与常驻浮条、首页就绪面板同一个来源
 * （旧版本页自带一套好/中/差色值，与浮条口径不一致，已删）。
 * 布局仍用代码搭：这一屏控件少而内容全动态（几条文案、几行判读），XML 反而要多写样板。
 */
class ResultActivity : AppCompatActivity() {

    /**
     * companion 本体（extra 键名 + intent()/show()）在 ResultPayload.kt，
     * 以 companion 扩展函数的形式挂回来 —— `ResultActivity.intent/show(...)`
     * 的调用点写法不变。这里保留一个空的 companion，作为那组扩展的接收者。
     */
    companion object {}

    private lateinit var draftsBox: LinearLayout
    private lateinit var statusLine: TextView
    private var state = ""
    private var memBlock = ""
    private var n = 3

    /** 攻略度已出、文案还在生成的过渡态（浮条先上屏之后点开结果页会遇到） */
    private var draftsPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config(this)
        n = cfg.draftsN
        state = intent.getStringExtra(EX_STATE).orEmpty()
        memBlock = intent.getStringExtra(EX_MEM).orEmpty()

        val title = intent.getStringExtra(EX_TITLE).orEmpty()
        val relation = intent.getStringExtra(EX_RELATION).orEmpty()
        val guide = intent.getIntExtra(EX_GUIDE, -1)
        val trendRaw = intent.getIntExtra(EX_TREND, Int.MIN_VALUE)
        val trend = if (trendRaw == Int.MIN_VALUE) null else trendRaw
        val lines = intent.getStringArrayListExtra(EX_LINES).orEmpty()
        val cost = intent.getLongExtra(EX_COST, 0L)
        val chatReady = intent.getBooleanExtra(EX_CHAT_READY, false)
        draftsPending = intent.getBooleanExtra(EX_DRAFTS_PENDING, false)

        val night = isNight()

        val root = UiKit.column(this).apply {
            setPadding(dp(Design.Space.L), dp(Design.Space.L), dp(Design.Space.L), dp(Design.Space.L))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        // 点弹窗之外的空白处也能关掉（用户要求）。这一屏用的是 Dialog 主题，
        // 外面本来就有留白，这一行把那片留白变成可点。
        setFinishOnTouchOutside(true)

        // ── 抬头：谁 + 什么关系 ──
        root.addView(
            UiKit.text(
                this, if (title.isEmpty()) "微信" else title,
                Design.Type.TITLE, Design.onSurface(this), bold = true
            )
        )
        root.addView(
            UiKit.text(
                this,
                if (relation.isEmpty()) "弦外之音 · 判读结果" else "弦外之音 · $relation",
                Design.Type.CAPTION, Design.onSurfaceVariant(this)
            ).apply { setPadding(0, dp(Design.Space.XS), 0, 0) },
            matchWrap(top = 0, bottom = Design.Space.M)
        )

        // ── 攻略度：这一屏的主角 —— 装进一张圆角英雄卡里，和下面的判读/文案拉开层次 ──
        if (guide >= 0) {
            val tint = Design.scoreColor(guide, night)
            // 英雄卡底色带一点分数色渐变：卡片区隔靠色阶不够时，分数色再托一层，
            // 好坏一眼可辨，但浓度压得很低（10%~22%），不抢大数字
            val hero = UiKit.column(this).apply {
                setPadding(dp(Design.Space.L), dp(Design.Space.L), dp(Design.Space.L), dp(Design.Space.L))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        blend(Design.containerLow(this@ResultActivity), tint, 0.10f),
                        blend(Design.containerLow(this@ResultActivity), tint, 0.22f)
                    )
                ).apply { cornerRadius = dp(Design.Radius.LG).toFloat() }
            }
            val head = UiKit.row(this).apply { gravity = Gravity.BOTTOM }
            head.addView(UiKit.text(this, "$guide", Design.Type.DISPLAY, tint, bold = true))
            head.addView(
                UiKit.text(this, "%", Design.Type.TITLE, tint, bold = true)
                    .apply { setPadding(0, 0, dp(Design.Space.M), 0) }
            )
            val (tailText, tailColor) = when {
                trend == null -> "首次记录" to Design.onSurfaceVariant(this)
                trend > 0 -> "↑ +$trend" to Design.scoreGood(night)
                trend < 0 -> "↓ $trend" to Design.scoreBad(night)
                else -> "持平" to Design.onSurfaceVariant(this)
            }
            head.addView(
                UiKit.text(this, tailText, Design.Type.BODY, tailColor, bold = true)
                    .apply { setPadding(0, 0, 0, dp(Design.Space.XS)) }
            )
            hero.addView(head)

            // 进度条带动画：从 0 走到当前值，一眼看出"到了哪个档"，比静态条更有"判读完了"的完成感
            hero.addView(
                LinearProgressIndicator(this).apply {
                    setProgressCompat(guide, true)
                    max = 100
                    trackCornerRadius = dp(5)
                    trackThickness = dp(8)
                    setIndicatorColor(tint)
                    setTrackColor(
                        UiKit.attr(
                            this@ResultActivity,
                            com.google.android.material.R.attr.colorSurfaceVariant,
                            0xFFDAE4E4.toInt()
                        )
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(Design.Space.XS) }
                }
            )
            root.addView(hero, matchWrap(top = 0, bottom = Design.Space.M))
        }

        // ── Jev 判读行：放在一张低层卡片里，跟文案区分开 ──
        if (lines.isNotEmpty()) {
            val card = UiKit.card(this, Design.containerLow(this), Design.Radius.MD)
            card.addView(
                UiKit.column(this).apply {
                    setPadding(dp(Design.Space.L), dp(Design.Space.M), dp(Design.Space.L), dp(Design.Space.M))
                    lines.forEach {
                        addView(UiKit.text(this@ResultActivity, it, Design.Type.BODY, Design.onSurface(this@ResultActivity)))
                    }
                }
            )
            root.addView(card, matchWrap(top = 0, bottom = Design.Space.M))
        }

        // ── 候选文案 ──
        root.addView(
            UiKit.text(this, "候选文案 · 点一下复制", Design.Type.BODY, Design.primary(this), bold = true)
                .apply { setPadding(0, 0, 0, dp(Design.Space.XS)) }
        )
        draftsBox = UiKit.column(this)
        root.addView(draftsBox)
        val titles = intent.getStringArrayListExtra(EX_DRAFT_TITLES).orEmpty()
        val texts = intent.getStringArrayListExtra(EX_DRAFT_TEXTS).orEmpty()
        val drafts = titles.indices.map { Draft(it, titles[it], texts.getOrElse(it) { "" }) }
        renderDrafts(drafts)

        statusLine = UiKit.text(this, "", Design.Type.MICRO, Design.onSurfaceVariant(this))
        root.addView(statusLine)

        // ── 按钮：复制全部是主动作（实心），重新生成是次动作（描边）── 与首页同一语法
        val row = UiKit.row(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(Design.Space.M), 0, 0)
        }
        if (chatReady && state.isNotEmpty()) {
            row.addView(
                UiKit.button(this, "重新生成", filled = false) { regenerate() },
                buttonWrap()
            )
        }
        row.addView(
            UiKit.button(this, "复制全部", filled = true) {
                val all = drafts.joinToString("\n\n") { "【${it.title}】\n${it.text}" }
                copy("弦外之音", all)
                toast("已复制 ${drafts.size} 条文案")
            },
            buttonWrap()
        )
        root.addView(row)

        // 底部：随机一句与爱有关的古诗。纯文字，**不设点击监听**，点它不跳转（用户要求）。
        root.addView(
            UiKit.text(this, Poems.random(), Design.Type.CAPTION, Design.onSurfaceVariant(this))
                .apply { alpha = 0.85f },
            matchWrap(top = Design.Space.L, bottom = 0)
        )

        // 提示：这一屏是 Dialog 主题，点四周空白就能关（用户要求：点弹窗外也能关，并且给提示）
        root.addView(
            UiKit.text(this, "点四周空白处关闭", Design.Type.MICRO, Design.onSurfaceVariant(this))
                .apply { alpha = 0.55f },
            matchWrap(top = Design.Space.XS, bottom = Design.Space.M)
        )

        statusLine.text = buildString {
            append("Jev + 文案耗时 ").append(cost).append("ms")
            if (draftsPending) append(" · 文案生成中，稍后重开看全文")
            if (!chatReady) append(" · 未配聊天模型，只有判读")
        }
    }

    /**
     * 结果页是 `singleInstance`：判读多次只复用这一个窗口，不会再叠成好几层
     * （之前每判读一次就新起一个对话框，屏幕上会同时挂着好几个 —— 用户反馈"怎么有两个浮窗"）。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        setIntent(intent)
        recreate()          // 用新内容重建一次，比手动刷新每个控件可靠
    }

    /**
     * 结果页开着的时候把浮条收起来：一次只给用户一个浮窗，别让攻略度和结果页叠在一起。
     * 切回微信（onStop）时浮条自己回来，方便一边看文案一边粘贴。
     */
    override fun onStart() {
        super.onStart()
        ScoreOverlay.setSuppressed(Config(this), true)
    }

    override fun onStop() {
        super.onStop()
        ScoreOverlay.setSuppressed(Config(this), false)
    }

    private fun renderDrafts(drafts: List<Draft>) {
        draftsBox.removeAllViews()
        if (drafts.isEmpty()) {
            // 攻略度已上屏、文案还在生成的过渡态，跟"真没生成"要说清楚，
            // 不然用户会以为是生成失败（浮条先上屏之后这个窗口是能被点开的）
            draftsBox.addView(
                UiKit.text(
                    this,
                    if (draftsPending) "（文案还在生成，几秒后重开就有了）" else "（这次没生成文案）",
                    Design.Type.BODY, Design.onSurfaceVariant(this)
                )
            )
            return
        }
        drafts.forEachIndexed { i, d ->
            val box = UiKit.column(this).apply {
                setPadding(dp(Design.Space.L), dp(Design.Space.M), dp(Design.Space.L), dp(Design.Space.M))
                addView(
                    UiKit.text(
                        this@ResultActivity, "${circle(i)} ${d.title}",
                        Design.Type.CAPTION, Design.primary(this@ResultActivity), bold = true
                    ).apply { setPadding(0, 0, 0, dp(Design.Space.S)) }
                )
                addView(UiKit.text(this@ResultActivity, d.text, Design.Type.BODY, Design.onSurface(this@ResultActivity)))
            }

            // 整张卡片可点＝复制。用 MaterialCardView（UiKit.card）白拿涟漪反馈 ——
            // 点了没反应是这类"复制型"界面最让人不放心的地方。
            val card = UiKit.card(this, Design.containerHigh(this), Design.Radius.MD).apply {
                isClickable = true
                isFocusable = true
                addView(box)
                setOnClickListener {
                    copy("弦外之音文案", d.text)
                    toast("已复制：${d.title}")
                    // 复制完自动关：用户下一步一定是回微信粘贴，留在这一屏只会挡着聊天
                    finish()
                }
            }
            draftsBox.addView(card, matchWrap(top = Design.Space.S, bottom = 0))
        }
    }

    /**
     * 重新生成：只重打聊天模型（Jev 的判读与攻略度不动），省一次 Jev 调用。
     * 编排本体在 [DraftGenerator] —— 与判读流程共用同一份，本页不再各写各的。
     */
    private fun regenerate() {
        val cfg = Config(this)
        if (!cfg.hasChatKey()) {
            toast("还没填聊天模型的密钥")
            return
        }
        statusLine.text = "重新生成中…"
        Thread({
            val r = DraftGenerator.request(cfg, memBlock, emptyList(), state, n)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (r) {
                    is DraftGen.Ok -> {
                        renderDrafts(r.drafts)
                        statusLine.text = "已重新生成 ${r.drafts.size} 条"
                    }
                    is DraftGen.Err -> statusLine.text = "重新生成失败：" + r.message
                }
            }
        }, "jevguide-regen").start()
    }

    private fun copy(label: String, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (t: Throwable) {
            AppLog.add("复制失败：${t.javaClass.simpleName}")
        }
    }

    private fun toast(s: String) = Toast3.toast(this, s)

    private fun circle(i: Int) = listOf("①", "②", "③", "④", "⑤").getOrElse(i) { "${i + 1}." }

    /** 两个颜色按比例混合（ratio=0 → a，1 → b）：给英雄卡调低浓度的分数色底 */
    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * ratio).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * ratio).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * ratio).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    // ────────────────────────── 小工具（全部走 UiKit/Design）

    private fun buttonWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(Design.Space.S) }

    private fun matchWrap(top: Int, bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(v: Int): Int = UiKit.dp(this, v)
}
