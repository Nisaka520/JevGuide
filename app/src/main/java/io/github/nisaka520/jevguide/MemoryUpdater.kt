package io.github.nisaka520.jevguide

import android.content.Context
import java.util.concurrent.Executors

/**
 * 长期记忆的"压缩"：攒够若干条新对话后，让聊天模型把这段时间的往来压成
 * 一份**滚动摘要 + 关键事实**，写回该联系人的记忆。
 *
 * 为什么单独跑一个线程池：它在判读之后异步进行，用户不需要等它；
 * 而且它失败**完全无害** —— 记忆里的原始 turns 还在，下次照样能带上。
 *
 * 为什么用聊天模型而不是 Jev：Jev 的题目口径是"判读单条消息"，没有"总结一段关系"这种题型；
 * 而摘要本来就是通用文本任务，交给通用模型更合适，也省 Jev 的额度。
 */
object MemoryUpdater {

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "jevguide-memory") }

    /** 上限：喂进去的原始对话条数（太多会稀释摘要质量，也费 token） */
    private const val FEED_TURNS = 30
    private const val MAX_FACTS = 6

    fun refresh(ctx: Context, cfg: Config, mem: ContactMemory) {
        if (!cfg.hasChatKey() || !cfg.memoryEnabled) return
        pool.execute {
            try {
                val r = ChatHttp.complete(
                    cfg.chatBaseUrl, cfg.chatApiKey, cfg.chatModel,
                    buildSystem(), buildUser(mem), timeoutMs = 40000, maxTokens = 500, temperature = 0.3
                )
                when (r) {
                    is ChatResult.Ok -> {
                        val (summary, facts) = parse(r.text)
                        if (summary.isNotEmpty() || facts.isNotEmpty()) {
                            Memories.setSummaryAndFacts(ctx, mem.key, mem.name, summary, facts)
                            AppLog.add("记忆摘要已刷新：${mem.name}（摘要 ${summary.length} 字 / 事实 ${facts.size} 条）")
                        } else {
                            AppLog.add("记忆摘要解析为空，保持原样：${r.text.take(120)}")
                        }
                    }
                    is ChatResult.Err -> AppLog.add("记忆摘要刷新失败：" + r.message)
                }
            } catch (t: Throwable) {
                AppLog.add("记忆摘要异常：${t.javaClass.simpleName} ${t.message ?: ""}")
            }
        }
    }

    fun buildSystem(): String = buildString {
        append("你是关系记忆整理助手。把给你的对话压缩成一份**长期记忆**，供以后判断关系时参考。")
        append("\n只输出两部分，不要任何解释、不要 markdown 代码块：")
        append("\n第一行以「摘要：」开头，用 150 字以内说清：两人现在是什么状态、最近在聊什么、有没有未了的事。")
        append("\n之后每行以「- 」开头，列出关键事实（最多 ").append(MAX_FACTS).append(" 条）：")
        append("只写对话里**明确出现**的信息（喜好、计划、时间、称呼、约定、雷区），不要推测，不要复述摘要。")
        append("\n如果对话里没有任何值得长期记住的信息，摘要写「暂无长期信息」且不输出事实行。")
    }

    fun buildUser(mem: ContactMemory): String = buildString {
        append("【关系】").append(mem.relation.ifEmpty { "普通朋友" }).append('\n')
        if (mem.summary.isNotEmpty()) append("【已有摘要】").append(mem.summary).append('\n')
        if (mem.facts.isNotEmpty()) {
            append("【已有事实】\n")
            mem.facts.take(MAX_FACTS).forEach { append("- ").append(it.text).append('\n') }
        }
        append("【最近的对话】（旧 → 新）\n")
        mem.turns.takeLast(FEED_TURNS).forEach { append(if (it.mine) "我：" else "对方：").append(it.text).append('\n') }
    }

    /**
     * 宽容解析：认「摘要：」「摘要:」「summary：」，事实行认「- 」「• 」「· 」「* 」
     * 以及「1. 」这类编号；多余的散文直接丢掉。绝不抛异常。
     */
    fun parse(raw: String): Pair<String, List<String>> {
        val text = raw.replace("\r", "").replace(Regex("```[a-zA-Z]*"), "").trim()
        var summary = ""
        val facts = ArrayList<String>()
        val summaryHead = Regex("""^\s*(摘要|总结|summary)\s*[:：]\s*(.*)$""", RegexOption.IGNORE_CASE)
        val factHead = Regex("""^\s*(?:[-•·*]|\d+[.、)])\s*(.+)$""")
        var inFacts = false
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val m = summaryHead.find(l)
            if (m != null) {
                summary = m.groupValues[2].trim()
                inFacts = false
                continue
            }
            val f = factHead.find(l)
            if (f != null) {
                val body = f.groupValues[1].trim().trim('「', '」', '"')
                if (body.isNotEmpty() && !facts.contains(body)) facts.add(body)
                inFacts = true
                continue
            }
            // 摘要还没写、又来了普通句子 → 当成摘要的续行；已经在列事实了就当噪声丢掉
            if (summary.isEmpty() && !inFacts) summary = l
            else if (inFacts) continue
            else summary = (summary + " " + l).trim()
        }
        if (summary.length > 600) summary = summary.take(599) + "…"
        return summary to facts.take(MAX_FACTS)
    }
}
