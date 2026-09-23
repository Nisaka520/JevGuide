package io.github.nisaka520.jevguide

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
 * 结果页：攻略度 + Jev 判读 + 3 条候选文案（点一下就复制）。
 *
 * 用**代码搭 UI**（工程运行期零第三方依赖，也没有引入 AndroidX/ViewBinding 的打算）；
 * 主题用系统对话框主题，所以它是浮在微信上面的一层，关掉就回到聊天。
 */
class ResultActivity : Activity() {

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
            setPadding(dp(18), dp(16), dp(18), dp(14))
            setBackgroundColor(0xF21C1C1E.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(text("Jev攻略 · " + title + if (relation.isEmpty()) "" else "（$relation）", 17f, 0xFFFFFFFF.toInt(), true))

        // ── 攻略度 ──
        if (guide >= 0) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(text("攻略度", 14f, 0xFFB0B0B0.toInt(), false))
            val pct = text("  $guide%", 30f, guideColor(guide), true)
            head.addView(pct)
            val tail = when {
                trend == null -> "首次记录"
                trend > 0 -> "↑ +$trend"
                trend < 0 -> "↓ $trend"
                else -> "持平"
            }
            head.addView(text("   $tail", 14f, if (trend != null && trend < 0) 0xFFFF8A80.toInt() else 0xFF9CCC65.toInt(), false))
            root.addView(head)

            root.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = guide
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(12)
                }
            })
        }

        // ── Jev 判读行 ──
        lines.forEach { root.addView(text(it, 14f, 0xFFE0E0E0.toInt(), false)) }

        root.addView(divider())

        // ── 候选文案 ──
        root.addView(text("候选文案（点一下复制）", 15f, 0xFFFFFFFF.toInt(), true))
        draftsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(draftsBox)
        val titles = intent.getStringArrayListExtra(EX_DRAFT_TITLES).orEmpty()
        val texts = intent.getStringArrayListExtra(EX_DRAFT_TEXTS).orEmpty()
        val drafts = titles.indices.map { Draft(it, titles[it], texts.getOrElse(it) { "" }) }
        renderDrafts(drafts)

        statusLine = text("", 12f, 0xFF9E9E9E.toInt(), false)
        root.addView(statusLine)

        // ── 按钮 ──
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        if (chatReady && state.isNotEmpty()) {
            row.addView(btn("重新生成") { regenerate() })
        }
        row.addView(btn("复制全部") {
            val all = drafts.joinToString("\n\n") { "【${it.title}】\n${it.text}" }
            copy("Jev攻略", all)
            toast("3 条文案已复制")
        })
        row.addView(btn("关闭") { finish() })
        root.addView(row)

        statusLine.text = buildString {
            append("Jev + 文案耗时 ").append(cost).append("ms")
            if (!chatReady) append(" · 未配聊天模型，只有判读")
        }
    }

    private fun renderDrafts(drafts: List<Draft>) {
        draftsBox.removeAllViews()
        if (drafts.isEmpty()) {
            draftsBox.addView(text("（这次没生成文案）", 14f, 0xFF9E9E9E.toInt(), false))
            return
        }
        drafts.forEachIndexed { i, d ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(12))
                setBackgroundColor(0xFF2A2A2E.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
                isClickable = true
                setOnClickListener {
                    copy("Jev攻略文案", d.text)
                    toast("已复制：${d.title}")
                }
            }
            card.addView(text("${circle(i)} ${d.title}", 13f, 0xFF80CBC4.toInt(), true))
            card.addView(text(d.text, 16f, 0xFFF5F5F5.toInt(), false))
            draftsBox.addView(card)
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
            val r = ChatHttp.complete(cfg.chatBaseUrl, cfg.chatApiKey, cfg.chatModel,
                ReplyPrompt.buildSystem(memBlock, cfg.lang), user)
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

    private fun guideColor(p: Int) = when {
        p >= 70 -> 0xFF81C784.toInt()
        p >= 40 -> 0xFFFFD54F.toInt()
        else -> 0xFFE57373.toInt()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(4).toFloat(), 1f)
        }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            }
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFF3A3A3E.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
