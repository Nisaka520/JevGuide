package io.github.nisaka520.jevguide

/**
 * 一次判读的结果 + 固定 3 条 Toast 的排版（纯逻辑，可单测）。
 *
 * 结果排版与插件版 JevIntent 一致：
 *   ① 意图：打招呼或闲聊 85%
 *      情绪：平静 56% · 客套 42% · 着急 2%
 *   ② 着急：不着急 · 0.48/3
 *   ③ 建议：正常交流 90%
 */
data class Verdict(
    val intent: String,
    val intentP: Double,
    /** 情绪分布前 N 名（已按概率降序） */
    val emotions: List<Pair<String, Double>>,
    val urgency: Double?,
    val style: String,
    val styleP: Double,
    val advice: String,
    val needReply: Double?,
    val risk: Double?,
    /** 攻略度原始分（score 0~10，11 档的连续浮点）；没问这题时为 null */
    val guideScore: Double? = null
) {

    /**
     * 攻略度百分比（0~100）。
     *
     * 档位是 10 档（0%…90%，接口上限就是 10 档），Jev 返回的是 `[0, 9]` 的连续浮点，
     * 所以按 100/9 换算 —— 6.48 → 72%。精度靠浮点补足，0% 和 100% 都能取到。
     */
    fun guidePercent(): Int? = guideScore?.let { Prompt.guidePercentOf(it) }

    /** 攻略度那一行；trend 是"与上一次的差值"（首次为 null） */
    fun guideLine(trend: Int?): String {
        val p = guidePercent() ?: return "攻略度：—"
        val tail = when {
            trend == null -> "（首次记录）"
            trend > 0 -> "（↑ +$trend）"
            trend < 0 -> "（↓ $trend）"
            else -> "（持平）"
        }
        return "攻略度：$p%" + tail
    }

    fun urgencyWord(): String {
        val u = urgency ?: return "—"
        val idx = Math.round(u).toInt().coerceIn(0, Prompt.URGENCY.size - 1)
        return Prompt.URGENCY[idx]
    }

    /** ① 意图 + 情绪分布 */
    fun coreLine(emotionCount: Int): String {
        val head = "意图：" + (intent.ifEmpty { "—" }) + if (intent.isEmpty()) "" else pct(intentP)
        val emo = emotions.take(emotionCount.coerceAtLeast(1))
        if (emo.isEmpty()) return head
        val tail = emo.joinToString(" · ") { it.first + pct(it.second) }
        return head + "\n" + "情绪：" + tail
    }

    /** ② 着急 */
    fun urgencyLine(): String =
        "着急：" + urgencyWord() + (urgency?.let { " · " + fmt(it) + "/3" } ?: "")

    /** ③ 建议（姿态）—— 选项自带"建议"前缀，这里去掉免得重复 */
    fun adviceLine(): String {
        val s = style.ifEmpty { advice }
        if (s.isEmpty()) return "建议：—"
        val clean = if (s.startsWith("建议")) s.substring(2) else s
        return "建议：" + clean + pct(styleP)
    }

    fun lines(emotionCount: Int = 3): List<String> = listOf(coreLine(emotionCount), urgencyLine(), adviceLine())

    /** 设置页"最近一次结果"用：一条紧凑的多行文本 */
    fun detail(): String = buildString {
        append(guideLine(null)).append('\n')
        append(coreLine(3)).append('\n')
        append(urgencyLine()).append('\n')
        append(adviceLine()).append('\n')
        append("建议动作：").append(advice.ifEmpty { "—" })
        append(" ｜ 待回复：").append(needReply?.let { fmt(it) } ?: "—")
        append(" ｜ 风险：").append(risk?.let { fmt(it) } ?: "—")
    }

    private fun pct(p: Double): String {
        if (p <= 0.0) return ""
        return " " + Math.round(p * 100) + "%"
    }

    private fun fmt(d: Double): String = String.format("%.2f", d)
}

object Verdicts {

    /**
     * 解析 Jev 的响应体。lang 为 "en" 时把英文键映射回中文，保证下游只有一套口径。
     * 返回 null 表示这次响应里没有任何可用的答案（调用方据此报错，而不是弹一堆"—"）。
     */
    fun parse(response: String, lang: String, emotionTop: Int = 3): Verdict? {
        val answers = Json.obj(Json.at(Json.parse(response), "answers"))
        if (answers.isEmpty()) return null

        val intent = choiceOf(answers, "intent", lang)
        val emotions = distributionOf(answers, "emotion", lang, emotionTop)
        val style = choiceOf(answers, "reply_style", lang)
        val advice = choiceOf(answers, "advice", lang).first

        val urgency = Json.num(Json.at(answers["urgency"], "score"))
        val needReply = Json.num(Json.at(answers["need_reply"], "noul"))
        val risk = Json.num(Json.at(answers["risk"], "noul"))
        val guide = Json.num(Json.at(answers["guide_score"], "score"))

        if (intent.first.isEmpty() && emotions.isEmpty() && style.first.isEmpty() &&
            urgency == null && guide == null
        ) return null

        return Verdict(
            intent = intent.first, intentP = intent.second,
            emotions = emotions,
            urgency = urgency,
            style = style.first, styleP = style.second,
            advice = advice,
            needReply = needReply,
            risk = risk,
            guideScore = guide
        )
    }

    private fun choiceOf(answers: Map<String, Any?>, q: String, lang: String): Pair<String, Double> {
        val blk = answers[q]
        val raw = Json.str(Json.at(blk, "choice"))
        val key = if (lang == "en") Prompt.toZh(q, raw) else raw
        val p = topProbability(blk, raw)
        return key to p
    }

    /** choice 题的完整概率分布 → 前 n 名；probabilities 缺席时退化成只有 top1 */
    private fun distributionOf(
        answers: Map<String, Any?>,
        q: String,
        lang: String,
        n: Int
    ): List<Pair<String, Double>> {
        val blk = answers[q]
        val probs = Json.obj(Json.at(blk, "probabilities"))
        if (probs.isEmpty()) {
            val (k, p) = choiceOf(answers, q, lang)
            return if (k.isEmpty()) emptyList() else listOf(k to p)
        }
        return probs.entries
            .mapNotNull { (k, v) ->
                val d = Json.num(v) ?: return@mapNotNull null
                val label = if (lang == "en") Prompt.toZh(q, k) else k
                label to d
            }
            .filter { it.second > 0.005 }
            .sortedByDescending { it.second }
            .take(n)
    }

    private fun topProbability(blk: Any?, rawKey: String): Double {
        val probs = Json.obj(Json.at(blk, "probabilities"))
        if (probs.isEmpty()) return 0.0
        Json.num(probs[rawKey])?.let { return it }
        // 概率最高的那个（英中键不一致时的兜底）
        return probs.values.mapNotNull { Json.num(it) }.maxOrNull() ?: 0.0
    }
}
