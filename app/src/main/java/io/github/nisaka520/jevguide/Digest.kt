package io.github.nisaka520.jevguide

/** 从屏幕上读到的一条消息 */
data class ScreenMsg(val mine: Boolean, val text: String, val quoted: String? = null)

/** 一次抓屏的结果：会话标题 + 可见消息（按屏幕顺序，旧 → 新） */
data class Digest(val title: String, val msgs: List<ScreenMsg>) {

    /** 单聊时标题就是对方昵称；群聊标题带人数后缀 */
    val peer: String get() = title

    val isGroup: Boolean get() = Regex("""[(（]\s*\d+\s*[)）]\s*$""").containsMatchIn(title.trim())

    /** 最后一条对方发来的消息（要判读的就是它） */
    fun latestPeerMessage(): ScreenMsg? = msgs.lastOrNull { !it.mine }

    /**
     * 拼请求用的 state。键名与插件版 JevIntent 完全一致
     * （关系 / 对方性别 / 会话类型 / 最近对话 / 待分析消息），这样两版结论可比。
     */
    fun state(contact: Contact, maxCtx: Int, extra: String = ""): String {
        val target = latestPeerMessage() ?: return ""
        val ctx = msgs.takeWhile { it !== target }.takeLast(maxCtx)
            .map { (if (it.mine) "我：" else "对方：") + it.text + (it.quoted?.let { q -> "（引用：" + q + "）" } ?: "") }
        val m = LinkedHashMap<String, Any?>()
        m["关系"] = contact.relation
        m["对方性别"] = contact.sex
        m["会话类型"] = if (isGroup) "群聊" else "单聊"
        m["最近对话"] = ctx
        m["待分析消息"] = target.text + (target.quoted?.let { "（引用：" + it + "）" } ?: "")
        val bg = listOf(contact.note, extra).filter { it.isNotEmpty() }.joinToString("；")
        if (bg.isNotEmpty()) m["背景"] = bg
        return Json.write(m)
    }
}

/**
 * 从无障碍节点里挑出"对话正文"的规则（纯函数，方便单测）。
 *
 * 这些规则只依赖屏幕上能观察到的东西，不依赖微信内部实现：
 *  - 单聊里**我方消息靠右**：节点中心点落在屏幕右侧 40% → 我发的。
 *  - 时间戳 / 系统提示 / 纯表情 / 按钮文字都不是正文。
 *  - 同一条消息可能被多个节点重复读到，需要去重。
 */
object ScreenRules {

    /** 我方判定：节点中心点超过屏宽的 mineRatio 即视为我方（微信单聊自己的气泡在右） */
    fun isMine(centerX: Float, screenWidth: Int, mineRatio: Float = 0.62f): Boolean {
        if (screenWidth <= 0) return false
        return centerX / screenWidth >= mineRatio
    }

    private val TIME_ONLY = Regex("""^[上下晚凌零中]?午?\s*\d{1,2}[:：]\d{2}$""")
    private val DATE_ONLY = Regex("""^(\d{1,2}月\d{1,2}日|\d{4}年\d{1,2}月\d{1,2}日|昨天|今天|星期[一二三四五六日天])(\s+.*)?$""")
    private val SYSTEM_HINTS = listOf(
        "撤回了一条消息", "拍了拍", "以下为新消息", "对方正在输入", "已添加你为朋友",
        "你已添加了", "消息已发出，但被对方拒收", "开启了朋友验证", "转账", "红包",
        "语音通话", "视频通话", "邀请你加入群聊", "加入了群聊", "退出了群聊"
    )

    /** 是否"不是对话正文" */
    fun isNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length > 2000) return true                       // 明显是整页文本被合成一个节点
        if (TIME_ONLY.matches(t) || DATE_ONLY.matches(t)) return true
        if (SYSTEM_HINTS.any { t.contains(it) }) return true
        // 纯符号/纯表情/纯数字（"1"、"。。。"、"😂😂"）没有判读价值
        if (t.none { it.isLetter() }) return true
        if (t.length <= 2 && t.all { !it.isLetterOrDigit() }) return true
        return false
    }

    /** 清掉不可见字符、折叠空白，保留换行结构 */
    fun clean(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\u200B' || ch == '\uFEFF' || ch == '\u00A0' -> {}
                ch == '\r' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString().split('\n').joinToString("\n") { it.trim() }.trim()
    }

    /**
     * 去重：同一条消息常常被多个节点读到（同一气泡两三个节点）。
     *
     * ⚠ 只比文本会把"同一句话连发两条"也吃掉，所以必须**同时**比侧别、文本和纵坐标。
     */
    fun dedupeLines(flagged: List<Pair<RawLine, Boolean>>): List<Pair<RawLine, Boolean>> {
        val out = ArrayList<Pair<RawLine, Boolean>>(flagged.size)
        for (cur in flagged) {
            val last = out.lastOrNull()
            if (last != null &&
                last.second == cur.second &&
                last.first.text.trim() == cur.first.text.trim() &&
                Math.abs(last.first.top - cur.first.top) <= 4
            ) continue
            out.add(cur)
        }
        return out
    }
}
