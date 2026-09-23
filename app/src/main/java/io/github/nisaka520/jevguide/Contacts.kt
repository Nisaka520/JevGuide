package io.github.nisaka520.jevguide

/**
 * 联系人表：**别名 → 关系/性别/备注**。
 *
 * 为什么要有这张表：微信单聊标题就是对方昵称，昵称经常改、还常带表情与后缀，
 * 光靠"当前会话标题"记不住关系。所以按「一个联系人可以有很多写法」的思路存，
 * 匹配时挑**命中最长**的那条，避免"小明"和"小明妈妈"互相打架。
 *
 * 结构参考了同类项目（jev-chat-jarvis）「联系人 + 别名」的做法，但字段、匹配规则、
 * 存储格式（JSON 文件）与界面都是本项目自己写的。
 */
data class Contact(
    /** 第一项是显示名，其余是别名（如 妈妈 / 妈 / 老妈） */
    val names: List<String>,
    val relation: String,
    val sex: String,
    val note: String = ""
) {
    val display: String get() = names.firstOrNull().orEmpty().ifEmpty { "(未命名)" }

    fun displayWithRelation(): String =
        if (names.isEmpty()) "(未命名)" else display + " · " + relation
}

object Contacts {

    val RELATIONS = listOf("普通朋友", "陌生人", "家人", "情侣", "死党", "同事", "客户", "领导", "同学", "网友")
    val SEXES = listOf("未知", "男", "女")

    /** 没有匹配到联系人时用的兜底 */
    fun fallback(title: String): Contact = Contact(
        names = listOf(title.ifEmpty { "(未识别)" }),
        relation = "普通朋友",
        sex = "未知",
        note = ""
    )

    /**
     * 归一化：小写、去掉所有空白、去掉微信群名尾部的人数（"项目组 (8)" → "项目组"）。
     * 半角/全角括号都要处理 —— 微信两种都会出现。
     */
    fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.replace(Regex("""[(（]\s*\d+\s*[/／]?\s*\d*\s*[)）]\s*$"""), "")
        s = s.replace(Regex("""\s+"""), "")
        return s
    }

    /**
     * 在联系人表里找最合适的一条。candidates 一般传 [会话标题, 对方昵称]。
     * 打分：完全相等 1000+长度；互相包含（键长 ≥2）用键长。取最高分，平手取靠前的。
     */
    fun match(contacts: List<Contact>, vararg candidates: String): Contact? {
        val cs = candidates.map { normalize(it) }.filter { it.isNotEmpty() }
        if (cs.isEmpty()) return null
        var best: Contact? = null
        var bestScore = 0
        for (c in contacts) {
            var score = 0
            for (key in c.names.map { normalize(it) }.filter { it.isNotEmpty() }) {
                for (cand in cs) {
                    val s = when {
                        key == cand -> 1000 + key.length
                        key.length >= 2 && cand.length >= 2 && (cand.contains(key) || key.contains(cand)) -> key.length
                        else -> 0
                    }
                    if (s > score) score = s
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    fun toJson(contacts: List<Contact>): String =
        Json.write(contacts.map { mapOf("names" to it.names, "relation" to it.relation, "sex" to it.sex, "note" to it.note) })

    fun fromJson(text: String): List<Contact> {
        val arr = Json.list(Json.parse(text))
        return arr.mapNotNull { item ->
            val m = Json.obj(item)
            val names = Json.list(m["names"]).mapNotNull { Json.str(it).ifEmpty { null } }
            if (names.isEmpty()) return@mapNotNull null
            val rel = Json.str(m["relation"]).ifEmpty { "普通朋友" }
            val sex = Json.str(m["sex"]).ifEmpty { "未知" }
            Contact(
                names = names,
                relation = if (rel in RELATIONS) rel else "普通朋友",
                sex = if (sex in SEXES) sex else "未知",
                note = Json.str(m["note"])
            )
        }
    }

    /** 设置页用：把一行文本解析成联系人（"妈妈,妈,老妈=家人/女/生日3月" 这种手输格式） */
    fun parseLine(line: String): Contact? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val eq = t.indexOf('=')
        val left = if (eq >= 0) t.substring(0, eq) else t
        val right = if (eq >= 0) t.substring(eq + 1) else ""
        val names = left.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        val parts = right.split('/', '／').map { it.trim() }
        val rel = parts.getOrNull(0).orEmpty().ifEmpty { "普通朋友" }
        val sex = parts.getOrNull(1).orEmpty().ifEmpty { "未知" }
        val note = parts.drop(2).joinToString("/")
        return Contact(
            names = names,
            relation = if (rel in RELATIONS) rel else "普通朋友",
            sex = if (sex in SEXES) sex else "未知",
            note = note
        )
    }

    fun formatLine(c: Contact): String {
        val head = c.names.joinToString(",")
        val tail = listOf(c.relation, c.sex).joinToString("/") + if (c.note.isNotEmpty()) "/" + c.note else ""
        return "$head=$tail"
    }
}
