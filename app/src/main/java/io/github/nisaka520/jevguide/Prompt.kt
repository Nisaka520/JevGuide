package io.github.nisaka520.jevguide

/**
 * 七个问题（一次请求发完）。
 *
 * 口径与插件版 JevIntent v1.8 **完全一致**：同一套 9 格情绪 / 10 类意图 / 4 档着急 /
 * 8 项建议 / 11 格回复姿态，连 instructions 都不改字 —— 这样两版数据可比，
 * 而且插件版踩过的坑（比如"别为 3 个情绪开 3 个问题"）这里不用再踩一遍。
 *
 * 语言变体来自实测（docs/实验-题目语言AB.md）：英文题目并不会更准，只是 top1 置信度更高、
 * 标签会漂（en 与 zh 只有 65% 的标签一致）。所以默认 zh，另外两档留给想复现实验的人。
 */
object Prompt {

    val LANGS = listOf("zh", "mix", "en")
    val LANG_LABELS = listOf("zh 中文（默认，客观分最高）", "mix 英文提问+中文选项（与 zh 95% 一致）", "en 全英文（置信度更高但标签会漂）")

    val MODELS = listOf("jev-latest", "jev-1.13.0")

    val EMOTIONS = listOf("平静", "高兴", "不满", "生气", "焦虑", "着急", "难过", "讽刺", "客套")
    val INTENTS = listOf(
        "打招呼或闲聊", "询问信息或情况", "请求帮忙或让我办事", "催促进度或催回复", "商务合作或推销",
        "投诉或表达不满", "通知或告知信息", "表达情绪或吐槽", "邀约见面或请客", "其他"
    )
    val ADVICES = listOf(
        "立刻回复并给出明确答复", "简短确认收到，稍后详答", "先问清楚细节再答复", "礼貌婉拒", "不必回复",
        "涉及钱/账号，先人工核实再回", "需要上报或转给相关同事", "共情或闲聊式回应即可"
    )
    val STYLES = listOf(
        "建议调侃打趣", "建议正常交流", "建议礼貌客套", "建议肯定赞赏", "建议关心问候", "建议共情吐槽",
        "建议表达同情", "建议先安抚道歉", "建议先问清细节", "建议温和婉拒", "建议暂不回复"
    )
    /** 着急 4 档（score 0~3） */
    val URGENCY = listOf("不着急", "一般", "急", "非常急")

    /**
     * 攻略度 10 档（score 0~9，换算成百分比见 [guidePercentOf]）。
     *
     * ⚠ 为什么是 10 档而不是 11 档：**接口硬性上限就是 10 档** ——
     * 用 11 档（0%…100%）实测直接被拒：`Too many score levels. Must have at most 10 levels.`
     * 所以档位文字只到 90%，剩下那 10% 由 score 的**连续浮点**补足：
     * 返回 6.48 → 6.48/9×100 = 72%。这样既有 0% 也有 100%，精度还不输 11 档。
     *
     * ⚠ 档位文字一旦上线就别改（改字=换量尺，历史评分不可比）。
     */
    val GUIDE_BANDS = (0..9).map { "${it * 10}%" }

    /** score 的最大值（= 档数-1），换算百分比时当分母 */
    const val GUIDE_MAX_LEVEL = 9

    /** score（0~9 的连续浮点）→ 百分比（0~100） */
    fun guidePercentOf(score: Double): Int =
        Math.round(score * 100.0 / GUIDE_MAX_LEVEL).toInt().coerceIn(0, 100)

    /** 英文键 ↔ 中文标签（en 变体用；解析时再映射回中文，保证下游只有一套口径） */
    val EN_OF: Map<String, List<String>> = mapOf(
        "emotion" to listOf("calm", "happy", "displeased", "angry", "anxious", "urgent", "sad", "sarcastic", "polite_smalltalk"),
        "intent" to listOf(
            "greeting_smalltalk", "asking_information", "requesting_favor", "pressing_progress", "business_pitch",
            "complaint", "notifying", "venting_emotion", "meetup_invitation", "other"
        ),
        "advice" to listOf(
            "reply_now_with_answer", "ack_receipt_then_later", "ask_for_details_first", "polite_decline", "no_reply_needed",
            "verify_money_or_account_first", "escalate_to_colleague", "respond_warmly"
        ),
        "reply_style" to listOf(
            "tease_playful", "normal_chat", "polite_formal", "praise_affirm", "care_greeting", "empathize_vent",
            "show_sympathy", "soothe_apologize_first", "ask_details_first", "gentle_decline", "hold_reply"
        )
    )

    private val ZH_INSTR = mapOf(
        "intent" to "结合「关系」「对方性别」和「最近对话」上下文，判断【待分析消息】真实的意图是什么？关系会显著改变意图解读，务必先考虑关系。",
        "urgency" to "结合关系和上下文，这条消息有多着急？0=不着急，1=一般，2=急，3=非常急。",
        "need_reply" to "结合关系和上下文，对方是否在等我方回复？",
        "risk" to "结合双方关系，判断【待分析消息】对「我」的实际风险有多大：会不会被骗钱、被盗号、泄露隐私或惹上法律麻烦、人情麻烦？（关系亲近且金额很小 → 风险低；陌生人/网友索要钱财、验证码、点链接 → 风险高）",
        "advice" to "结合双方关系和上下文，最合适的应对动作是哪一个？",
        "emotion" to "结合「关系」和「最近对话」上下文，判断【待分析消息】透露出的是哪种情绪？只能选一个最接近的。",
        "reply_style" to "结合关系和上下文，我回复这句话最合适的姿态是哪一个？只判断姿态，不要判断内容。",
        "guide" to "结合「关系」「背景」（含历史记忆与历次攻略度）和「最近对话」，评估【当前攻略度】：0% = 基本没戏（对方冷淡、长期不回、关系在恶化）；30% = 能搭上话但明显是我单方面热情；50% = 有来有往、聊天自然，但还没有超出普通朋友的好感信号；70% = 有明确的好感信号（主动找我、关心我、愿意单独见面）；90%~100% = 关系已很亲密、双方都清楚彼此心意。只评**当前状态**，不要评潜力，也不要因为一句客套话就往上抬；信息不足时给中间值而不是硬猜。"
    )

    private val EN_INSTR = mapOf(
        "intent" to "Considering the relationship, the sender's gender, and the recent conversation, what is the true intent behind the MESSAGE TO ANALYZE? The relationship strongly changes how an intent should be read, so weigh it first.",
        "urgency" to "Considering the relationship and the conversation, how urgent is this message? 0=not urgent, 1=normal, 2=urgent, 3=extremely urgent.",
        "need_reply" to "Considering the relationship and the conversation, is the other person waiting for a reply from me?",
        "risk" to "Considering the relationship between us, how much real risk does the MESSAGE TO ANALYZE carry for me: being scammed out of money, account takeover, privacy leaks, legal trouble, or awkward obligations? (Close relationship and tiny amount = low risk; a stranger asking for money, a verification code, or a link = high risk.)",
        "advice" to "Considering the relationship and the conversation, which action is the most appropriate response?",
        "emotion" to "Considering the relationship and the recent conversation, which emotion does the MESSAGE TO ANALYZE reveal? Pick the single closest one.",
        "reply_style" to "Considering the relationship and the conversation, what is the most appropriate STANCE for my reply? Judge the stance only, not the content.",
        "guide" to "Considering the relationship, the BACKGROUND (including long-term memory and past progress scores) and the recent conversation, rate the CURRENT PROGRESS of this relationship: 0% = hopeless (cold, no replies, deteriorating); 30% = they answer but I am clearly the only one investing; 50% = mutual, natural chatting, no romantic signal beyond friendship; 70% = clear signs of interest (they initiate, care about me, willing to meet alone); 90-100% = intimate, both sides know how the other feels. Rate the CURRENT state only, not the potential, and do not inflate it for a single polite message; when information is thin, pick a middle value instead of guessing."
    )

    /** 解析用：把 en 变体返回的英文键翻译回中文（未知键原样返回） */
    fun toZh(question: String, key: String): String {
        val en = EN_OF[question] ?: return key
        val idx = en.indexOf(key)
        if (idx < 0) return key
        val zh = when (question) {
            "emotion" -> EMOTIONS
            "intent" -> INTENTS
            "advice" -> ADVICES
            else -> STYLES
        }
        return zh.getOrElse(idx) { key }
    }

    /** 组请求体（state 永远是中文原文，只换题目语言 —— 与 A/B 实验一致） */
    fun requestJson(state: String, model: String, lang: String, guide: Boolean = true): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append(Json.quote("state")).append(':').append(Json.quote(state)).append(',')
        sb.append(Json.quote("model")).append(':').append(Json.quote(model)).append(',')
        sb.append(Json.quote("questions")).append(":{")

        // 1) 意图
        sb.append(Json.quote("intent")).append(":{")
        sb.append("\"type\":\"choice\",")
        sb.append("\"instructions\":").append(Json.quote(instr("intent", lang)))
        sb.append(",\"criteria\":").append(criteria("intent", INTENTS, lang))
        sb.append("},")

        // 2) 着急（score 0~3）—— ⚠ 这段 instructions 与插件版逐字相同，改了等于换量尺
        sb.append(Json.quote("urgency")).append(":{")
        sb.append("\"type\":\"score\",")
        sb.append("\"instructions\":").append(Json.quote(instr("urgency", lang)))
        sb.append(",\"criteria\":").append(scoreCriteria(lang))
        sb.append("},")

        // 3) 是否等回复（noul）
        sb.append(Json.quote("need_reply")).append(":{")
        sb.append("\"type\":\"noul\",")
        sb.append("\"instructions\":").append(Json.quote(instr("need_reply", lang)))
        sb.append("},")

        // 4) 风险（noul）
        sb.append(Json.quote("risk")).append(":{")
        sb.append("\"type\":\"noul\",")
        sb.append("\"instructions\":").append(Json.quote(instr("risk", lang)))
        sb.append("},")

        // 5) 建议（choice）
        sb.append(Json.quote("advice")).append(":{")
        sb.append("\"type\":\"choice\",")
        sb.append("\"instructions\":").append(Json.quote(instr("advice", lang)))
        sb.append(",\"criteria\":").append(criteria("advice", ADVICES, lang))
        sb.append("},")

        // 6) 情绪（choice 9 格；probabilities 是完整分布，取前 N 名即可，别开多个问题）
        sb.append(Json.quote("emotion")).append(":{")
        sb.append("\"type\":\"choice\",")
        sb.append("\"instructions\":").append(Json.quote(instr("emotion", lang)))
        sb.append(",\"criteria\":").append(criteria("emotion", EMOTIONS, lang))
        sb.append("},")

        // 7) 回复姿态（choice 11 格；只判姿态不生成措辞）
        sb.append(Json.quote("reply_style")).append(":{")
        sb.append("\"type\":\"choice\",")
        sb.append("\"instructions\":").append(Json.quote(instr("reply_style", lang)))
        sb.append(",\"criteria\":").append(criteria("reply_style", STYLES, lang))
        sb.append("}")

        // 8) 攻略度（score 11 档 → ×10 即百分比）—— 关掉就少问一题，省 token
        if (guide) {
            sb.append(",")
            sb.append(Json.quote("guide_score")).append(":{")
            sb.append("\"type\":\"score\",")
            sb.append("\"instructions\":").append(Json.quote(instr("guide", lang)))
            sb.append(",\"criteria\":").append(GUIDE_BANDS.joinToString(",", "[", "]") { Json.quote(it) })
            sb.append("}")
        }

        sb.append("}}")
        return sb.toString()
    }

    private fun instr(name: String, lang: String): String =
        if (lang == "zh") ZH_INSTR[name] ?: "" else EN_INSTR[name] ?: ""

    /** choice 的 criteria 是 map：key 同时也是返回的 choice 值 */
    private fun criteria(name: String, zh: List<String>, lang: String): String {
        val keys = if (lang == "en") EN_OF[name] ?: zh else zh
        return keys.joinToString(",", "{", "}") { Json.quote(it) + ":" + Json.quote(it) }
    }

    private fun scoreCriteria(lang: String): String {
        val keys = if (lang == "en") listOf("not urgent", "normal", "urgent", "extremely urgent") else URGENCY
        return keys.joinToString(",", "[", "]") { Json.quote(it) }
    }
}
