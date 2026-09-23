package io.github.nisaka520.jevguide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * 一次分析的全部产物（判读 + 攻略度 + 候选文案）。
 *
 * 为什么不直接传 `Verdict`：结果页可能由无障碍服务在**另一个进程生命周期**里拉起，
 * 塞 Parcelable 要额外写一堆样板；这里只挑结果页真正要显示的几个字符串，
 * Intent extras 足够小，也不会因为类结构变化而崩。
 */
data class ResultPayload(
    val title: String,
    val relation: String,
    val guidePercent: Int?,
    val trend: Int?,
    val lines: List<String>,
    val drafts: List<Draft>,
    val costMs: Long,
    /** 重新生成要用：拼好的 state 与记忆块 */
    val state: String,
    val memoryBlock: String,
    val chatReady: Boolean
)

/**
 * 结果页：攻略度 + Jev 判读 + 候选文案（点一下就复制）。
 *
 * 用 **Material 3** 控件（卡片、进度条、tonal 按钮），主题是 `Theme.JevGuide.Dialog` ——
 * 所以它是浮在微信上面的一层，关掉就回到聊天。颜色一律走主题属性，
 * 在 Android 12+ 上会跟着壁纸取色（Material You），跟设置页保持一致。
 *
 * 布局仍用代码搭：这一屏的控件数量不多，且内容全是动态的（几条文案、几行判读），
 * 用 XML 反而要写一堆 findView 样板。
 */
class ResultActivity : AppCompatActivity() {

    companion object {
        private const val EX_TITLE = "title"
        private const val EX_RELATION = "relation"
        private const val EX_GUIDE = "guide"
        private const val EX_TREND = "trend"
        private const val EX_LINES = "lines"
        private const val EX_DRAFT_TITLES = "draft_titles"
        private const val EX_DRAFT_TEXTS = "draft_texts"
        private const val EX_COST = "cost"
        private const val EX_STATE = "state"
        private const val EX_MEM = "mem"
        private const val EX_CHAT_READY = "chat_ready"

        /**
         * 拉起结果页。返回 false 表示没能拉起（后台启动被系统拦、或没有 Activity 环境），
         * 调用方据此降级成通知/Toast —— 工具最忌讳"点了没反应"。
         */
        fun show(ctx: Context, p: ResultPayload): Boolean = try {
            val i = Intent(ctx, ResultActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EX_TITLE, p.title)
                putExtra(EX_RELATION, p.relation)
                putExtra(EX_GUIDE, p.guidePercent ?: -1)
                putExtra(EX_TREND, p.trend ?: Int.MIN_VALUE)
                putStringArrayListExtra(EX_LINES, ArrayList(p.lines))
                putStringArrayListExtra(EX_DRAFT_TITLES, ArrayList(p.drafts.map { it.title }))
                putStringArrayListExtra(EX_DRAFT_TEXTS, ArrayList(p.drafts.map { it.text }))
                putExtra(EX_COST, p.costMs)
                putExtra(EX_STATE, p.state)
                putExtra(EX_MEM, p.memoryBlock)
                putExtra(EX_CHAT_READY, p.chatReady)
            }
            ctx.startActivity(i)
            true
        } catch (t: Throwable) {
            AppLog.add("结果页启动失败：${t.javaClass.simpleName} ${t.message ?: ""}")
            false
        }
    }

    private lateinit var draftsBox: LinearLayout
    private lateinit var statusLine: TextView
    private var state = ""
    private var memBlock = ""
    private var n = 3

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        // 结果页是用户停留最久的地方，底部放一行不打扰的赞助入口（aff 露出位）。
        // 链接不硬编：走 Aff.keyUrl(当前厂商)，没配邀请码时自动回退到官方入口，
        // 等于顺带给用户一个「去哪儿申请密钥」的方便。
        // 措辞按披露要求写清楚：带码、双方都有奖励、用户价格不变、不想带码可以自己去官网。
        run {
            val p = Providers.byId(cfg.providerId)
            val url = Aff.keyUrl(p)
            if (url.isNotBlank()) {
                val pad = (14 * resources.displayMetrics.density).toInt()
                val tv = TextView(this).apply {
                    text = if (Aff.hasLink(p.id)) {
                        "本 App 免费开源 · 用 ${p.name} 的免费额度就能跑\n" +
                            "点这里注册（含作者邀请码：你和作者各得平台奖励，你的价格与权益不变；" +
                            "不想带码就直接去官网注册，一样能用）"
                    } else {
                        "本 App 免费开源 · 去 ${p.name} 申请密钥"
                    }
                    textSize = 11.5f
                    setPadding(0, pad, 0, 0)
                    alpha = 0.75f
                    isClickable = true
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        } catch (t: Throwable) {
                            android.widget.Toast.makeText(
                                this@ResultActivity, "打不开浏览器：$url", android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                root.addView(tv)
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })

        // ── 抬头：谁 + 什么关系 ──
        root.addView(text(if (title.isEmpty()) "微信" else title, 22f, cOnSurface, bold = true))
        root.addView(text(
            if (relation.isEmpty()) "Jev攻略 · 判读结果" else "Jev攻略 · $relation",
            12.5f, cOnSurfaceVariant, bold = false
        ).apply { setPadding(0, dp(2), 0, dp(10)) })

        // ── 攻略度：这一屏的主角，所以给最大的字号 ──
        if (guide >= 0) {
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            head.addView(text("$guide", 52f, guideColor(guide), bold = true))
            head.addView(text("%", 22f, guideColor(guide), bold = true).apply {
                setPadding(0, 0, dp(10), dp(8))
            })
            val (tailText, tailColor) = when {
                trend == null -> "首次记录" to cOnSurfaceVariant
                trend > 0 -> "↑ +$trend" to okColor
                trend < 0 -> "↓ $trend" to badColor
                else -> "持平" to cOnSurfaceVariant
            }
            head.addView(text(tailText, 14f, tailColor, bold = true).apply {
                setPadding(0, 0, 0, dp(12))
            })
            root.addView(head)

            root.addView(LinearProgressIndicator(this).apply {
                setProgressCompat(guide, false)
                max = 100
                trackCornerRadius = dp(5)
                trackThickness = dp(8)
                setIndicatorColor(guideColor(guide))
                setTrackColor(attr(com.google.android.material.R.attr.colorSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2); bottomMargin = dp(14) }
            })
        }

        // ── Jev 判读行：放在一张低层卡片里，跟文案区分开 ──
        if (lines.isNotEmpty()) {
            val card = card(cContainerLow)
            lines.forEach { card.addView(text(it, 13.5f, cOnSurface, bold = false)) }
            root.addView(card, matchWrap(top = 0, bottom = 14))
        }

        // ── 候选文案 ──
        root.addView(text("候选文案 · 点一下复制", 14f, cPrimary, bold = true).apply {
            setPadding(0, 0, 0, dp(2))
        })
        draftsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(draftsBox)
        val titles = intent.getStringArrayListExtra(EX_DRAFT_TITLES).orEmpty()
        val texts = intent.getStringArrayListExtra(EX_DRAFT_TEXTS).orEmpty()
        val drafts = titles.indices.map { Draft(it, titles[it], texts.getOrElse(it) { "" }) }
        renderDrafts(drafts)

        statusLine = text("", 11.5f, cOnSurfaceVariant, bold = false)
        root.addView(statusLine)

        // ── 按钮：主操作给实心，其余给 tonal ──
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        if (chatReady && state.isNotEmpty()) {
            row.addView(btn("重新生成") { regenerate() })
        }
        row.addView(btn("复制全部") {
            val all = drafts.joinToString("\n\n") { "【${it.title}】\n${it.text}" }
            copy("Jev攻略", all)
            toast("已复制 ${drafts.size} 条文案")
        })
        row.addView(btn("关闭", filled = true) { finish() }.apply {
            // 关闭键要显眼：这一屏是浮在微信上面的，找不到"怎么退出去"最让人烦躁
            textSize = 15f
            setPadding(dp(28), paddingTop, dp(28), paddingBottom)
        })
        root.addView(row)

        statusLine.text = buildString {
            append("Jev + 文案耗时 ").append(cost).append("ms")
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
            draftsBox.addView(text("（这次没生成文案）", 13.5f, cOnSurfaceVariant, bold = false))
            return
        }
        drafts.forEachIndexed { i, d ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(14))
            }
            box.addView(text("${circle(i)} ${d.title}", 12.5f, cPrimary, bold = true).apply {
                setPadding(0, 0, 0, dp(6))
            })
            box.addView(text(d.text, 16f, cOnSurface, bold = false))

            // 整张卡片可点＝复制。用 MaterialCardView 而不是自己画背景，是为了白拿涟漪反馈 ——
            // 点了没反应是这类"复制型"界面最让人不放心的地方。
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(cContainerHigh)
                isClickable = true
                isFocusable = true
                addView(box)
                setOnClickListener {
                    copy("Jev攻略文案", d.text)
                    toast("已复制：${d.title}")
                    // 复制完自动关：用户下一步一定是回微信粘贴，留在这一屏只会挡着聊天
                    finish()
                }
            }
            draftsBox.addView(card, matchWrap(top = 8, bottom = 0))
        }
    }

    /** 重新生成：只重打聊天模型（Jev 的判读与攻略度不动），省一次 Jev 调用 */
    private fun regenerate() {
        val cfg = Config(this)
        if (!cfg.hasChatKey()) {
            toast("还没填聊天模型的密钥")
            return
        }
        statusLine.text = "重新生成中…"
        Thread({
            val user = ReplyPrompt.buildUser(emptyList(), state, n)
            val r = ChatHttp.complete(
                cfg.chatBaseUrl, cfg.chatApiKey, cfg.chatModel,
                ReplyPrompt.buildSystem(memBlock, cfg.lang, cfg.promptExtra), user
            )
            runOnUiThread {
                when (r) {
                    is ChatResult.Ok -> {
                        val ds = ReplyPrompt.parse(r.text, n)
                        renderDrafts(ds)
                        statusLine.text = "已重新生成 ${ds.size} 条"
                    }
                    is ChatResult.Err -> statusLine.text = "重新生成失败：" + r.message
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun circle(i: Int) = listOf("①", "②", "③", "④", "⑤").getOrElse(i) { "${i + 1}." }

    /** 攻略度配色：跟常驻浮条同一套阈值（≥70 好 / ≥40 中 / 其余差） */
    private fun guideColor(p: Int) = when {
        p >= 70 -> okColor
        p >= 40 -> midColor
        else -> badColor
    }

    // ────────────────────────── 小工具（MD3）

    /** 取主题属性里的颜色 —— 这样 Material You 的壁纸取色才会生效（写死 R.color.* 是不会跟着走的） */
    private fun attr(@androidx.annotation.AttrRes id: Int, fallback: Int = R.color.text): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(id, tv, true) && tv.data != 0) tv.data else getColor(fallback)
    }

    private val cPrimary: Int get() = attr(com.google.android.material.R.attr.colorPrimary)
    private val cOnSurface: Int get() = attr(com.google.android.material.R.attr.colorOnSurface)
    private val cOnSurfaceVariant: Int get() = attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val cContainerHigh: Int get() = attr(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    private val cContainerLow: Int get() = attr(com.google.android.material.R.attr.colorSurfaceContainerLow)

    /**
     * 好/中/差三档色。
     *
     * 为什么不用主题色：这三个是**语义色**（像红绿灯），必须一眼看出好坏 ——
     * 跟着壁纸变成紫色或粉色就失去意义了。所以取固定的绿/黄/红，
     * 但深色下要换更亮的一档，否则在深底上发闷。
     */
    private val okColor: Int get() = if (isNight) 0xFF7EE0A8.toInt() else 0xFF2E7D4F.toInt()
    private val midColor: Int get() = if (isNight) 0xFFFFD54F.toInt() else 0xFF8A6100.toInt()
    private val badColor: Int get() = if (isNight) 0xFFE57373.toInt() else 0xFFB3261E.toInt()
    private val isNight: Boolean
        get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun text(s: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(5).toFloat(), 1f)
        }

    private fun card(fill: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun btn(label: String, filled: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = label
            textSize = 13.5f
            isAllCaps = false
            cornerRadius = dp(12)
            if (!filled) {
                backgroundTintList = ColorStateList.valueOf(
                    attr(com.google.android.material.R.attr.colorSecondaryContainer)
                )
                setTextColor(attr(com.google.android.material.R.attr.colorOnSecondaryContainer))
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        }

    private fun matchWrap(top: Int, bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
