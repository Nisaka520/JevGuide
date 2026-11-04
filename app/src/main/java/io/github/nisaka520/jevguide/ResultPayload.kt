package io.github.nisaka520.jevguide

import android.content.Context
import android.content.Intent

/**
 * 一次分析的全部产物（判读 + 攻略度 + 候选文案）。
 *
 * 为什么不直接传 `Verdict`：结果页可能由无障碍服务在**另一个进程生命周期**里拉起，
 * 塞 Parcelable 要额外写一堆样板；这里只挑结果页真正要显示的几个字符串，
 * Intent extras 足够小，也不会因为类结构变化而崩。
 *
 * 为什么单独一个文件：它被 [Analyzer]、[ScoreOverlay]、[ResultActivity] 三处共用，
 * 原来寄生在 ResultActivity.kt 顶部，谁也不是它的家 —— 抽出来后三个使用方
 * 一行 import 都不用加（同包）。`intent()`/`show()` 做成 ResultActivity companion 的
 * **扩展函数**，调用点 `ResultActivity.intent(...)` 的写法保持不变。
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
    val chatReady: Boolean,
    /**
     * true = 攻略度已经判出来、文案还在路上（浮条先上屏的结果）。
     * 这时候打开结果页只能看到判读，别把"没生成"误报成"生成失败"。
     */
    val draftsPending: Boolean = false
)

/**
 * 结果页的 Intent extra 键（**存量协议，键名与键值逐字保留**，只搬位置不改一字）。
 * 原来是 ResultActivity companion 里的 private const；ResultActivity.onCreate
 * 读 extras 也用同一批名字，抽到同包顶层后那边照旧直呼其名。
 */
internal const val EX_TITLE = "title"
internal const val EX_RELATION = "relation"
internal const val EX_GUIDE = "guide"
internal const val EX_TREND = "trend"
internal const val EX_LINES = "lines"
internal const val EX_DRAFT_TITLES = "draft_titles"
internal const val EX_DRAFT_TEXTS = "draft_texts"
internal const val EX_COST = "cost"
internal const val EX_STATE = "state"
internal const val EX_MEM = "mem"
internal const val EX_CHAT_READY = "chat_ready"
internal const val EX_DRAFTS_PENDING = "drafts_pending"

/**
 * 拉起结果页用的 Intent。单独拆出来：兜底通知的 contentIntent 也要这组 extras，
 * 而从通知栏点开是不受「后台启动拦截」限制的 —— 结果页拉不起来时，
 * 通知内容照旧，点通知就能看到完整结果（原来点开的是设置页，结果不在那儿）。
 *
 * 挂成 [ResultActivity] companion 的扩展函数：调用点写法（`ResultActivity.intent(ctx, p)`）
 * 与抽取前完全一致，Analyzer / ScoreOverlay 一行不用动。
 */
internal fun ResultActivity.Companion.intent(ctx: Context, p: ResultPayload): Intent =
    Intent(ctx, ResultActivity::class.java).apply {
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
        putExtra(EX_DRAFTS_PENDING, p.draftsPending)
    }

/**
 * 拉起结果页。返回 false 表示没能拉起（后台启动被系统拦、或没有 Activity 环境），
 * 调用方据此降级成通知/Toast —— 工具最忌讳"点了没反应"。
 */
internal fun ResultActivity.Companion.show(ctx: Context, p: ResultPayload): Boolean = try {
    ctx.startActivity(intent(ctx, p))
    true
} catch (t: Throwable) {
    AppLog.add("结果页启动失败：${t.javaClass.simpleName} ${t.message ?: ""}")
    false
}
