package io.github.nisaka520.jevguide

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一个联系人一份的长期记忆。
 *
 * 为什么要有它：判读只看得见屏幕上那几行，关系好不好、上次聊到哪、他讨厌什么，
 * 每次都要重新猜。所以按联系人攒一份**本机**记忆：滚动摘要 + 关键事实 + 攻略度曲线 +
 * 近期对话。它只在本机落盘（`filesDir/memory/`），随卸载一起消失，不上传。
 *
 * 为什么每联系人一个文件，而不是塞进 SharedPreferences：
 *  - 一份记忆几十 KB，40 个联系人就是几 MB，SharedPreferences 是整表读进内存的 XML，
 *    每次读设置都要解析一遍，早晚卡主线程；
 *  - 单文件写入是原子的（写坏的只可能是一份记忆），不会像一张大表那样一处写坏全表报废。
 *
 * 数据形态与 [Digest.ScreenMsg] 对齐：[Turn.mine] 与 `ScreenMsg.mine` 是同一个意思
 * （true = 我发的），这样抓屏结果能直接灌进来，不用再做一层翻译。
 */
data class Turn(
    /** 时间戳（毫秒） */
    val ts: Long,
    /** true = 我发的（与 ScreenMsg.mine 同义） */
    val mine: Boolean,
    val text: String
)

/** 一条关键事实（ts 是**第一次**记下它的时间，不是每次重写的时间） */
data class MemFact(val text: String, val ts: Long)

/** 一次攻略度打分（score 是 0..100 的百分比） */
data class ScorePoint(val ts: Long, val score: Int, val note: String)

/**
 * 某个联系人的全部记忆。字段顺序与上限见 [Memories] 的常量。
 *
 * 列表方向是刻意不统一的，看的时候注意：
 *  - [facts] / [scores]：**新的在前**（要"最近几条"，从头部取最省事，也不用每次都排序）；
 *  - [turns]：**旧 → 新**（要拼给模型看对话，必须按时间正序读）。
 */
data class ContactMemory(
    /** 规范化后的联系人键（见 [MemKeys.of]），也是文件名的来源 */
    val key: String,
    /** 展示名（微信里的会话标题，保留原始写法，方便人看） */
    val name: String,
    /** 最近一次使用的关系（普通朋友/情侣/...） */
    val relation: String,
    /** 滚动摘要（模型生成，≤600 字） */
    val summary: String,
    /** 关键事实（≤12 条，新的在前） */
    val facts: List<MemFact>,
    /** 历次攻略度（≤50 条，新的在前） */
    val scores: List<ScorePoint>,
    /** 近期对话（≤40 条，旧 → 新） */
    val turns: List<Turn>,
    /** 最近一次写入时间 */
    val updatedAt: Long,
    /**
     * 上次刷新摘要时的 [turns] 条数（由 [Memories.setSummaryAndFacts] 维护）。
     *
     * 为什么需要它：判断"攒够 N 条新对话该刷新摘要了"必须有个水位线 ——
     * 否则每次都拿 `turns.size` 去比，同一批对话会被反复压缩，白烧调用。
     */
    val turnsAtSummary: Int = 0
)

/**
 * 联系人键的规范化与文件名映射。
 *
 * 为什么不直接用标题当文件名：微信标题可能是"小明❤️"、"[表情] 老王"、"项目组 (8)"，
 * 含表情/斜杠/引号/换行（换行在部分文件系统上直接非法），还可能长到超过文件名上限。
 * 统一先规范化成 key，再用 key 的 SHA-1 当文件名，就只剩 `[0-9a-f]{40}.json` 这一种安全形态。
 */
object MemKeys {

    /** 群聊标题尾部的人数后缀，半角/全角括号都出现（"项目组 (8)" / "项目组（12）" / "项目组（3/9）"） */
    private val GROUP_SUFFIX = Regex("""[(（]\s*\d+\s*[/／]?\s*\d*\s*[)）]\s*$""")

    private const val HEX = "0123456789abcdef"

    /**
     * 规范化：去不可见字符 → 去首尾空白 → 去群聊人数后缀 → 小写 → 折叠内部空白为单个空格。
     *
     * 注意**折叠**而不是"删掉"内部空白：微信昵称里"张 伟"和"张伟"是两个人，
     * 但连续两个空格和三个空格只是手抖，所以折叠成一个（Contacts.normalize 是删光空白，
     * 那是为了别名包含匹配，用途不同，别混用）。
     */
    fun of(title: String): String {
        val sb = StringBuilder(title.length)
        for (ch in title) if (!isInvisible(ch)) sb.append(ch)
        var s = sb.toString().trim()
        s = GROUP_SUFFIX.replace(s, "").trim().lowercase()

        val out = StringBuilder(s.length)
        var pendingSpace = false
        for (ch in s) {
            if (ch.isWhitespace()) {
                pendingSpace = true
                continue
            }
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(ch)
        }
        return out.toString()
    }

    /** key 的 SHA-1 十六进制 + ".json"（避免中文/表情/非法字符当文件名） */
    fun fileName(key: String): String {
        val hex = try {
            val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            sb.toString()
        } catch (e: Exception) {
            // SHA-1 在 Android 上一定有，真取不到也不能让整个记忆功能瘫掉：
            // 退化成 hashCode 十六进制（可能碰撞，但至少不崩、还能读写）
            AppLog.add("记忆文件名哈希失败，退化处理：${e.message}")
            Integer.toHexString(key.hashCode())
        }
        return hex + ".json"
    }

    /**
     * 不可见字符：零宽/方向控制/BOM/变体选择符/不换行空格（NBSP）。
     * 昵称里夹这些字符肉眼看不出来，会让"同一个人"变成两个 key。
     * 全角空格 \u3000 不在这里处理 —— 它是真的空白，交给上面的"折叠"逻辑。
     */
    private fun isInvisible(ch: Char): Boolean {
        if (ch == '\u200B' || ch == '\u200C' || ch == '\u200D') return true
        if (ch == '\u200E' || ch == '\u200F' || ch == '\u2060') return true
        if (ch == '\uFEFF' || ch == '\uFE0E' || ch == '\uFE0F') return true
        if (ch == '\u00A0' || ch == '\u2007' || ch == '\u202F') return true
        // 控制字符（保留 tab / 换行，让它们走空白折叠）
        if (ch.code < 0x20 && ch != '\t' && ch != '\n') return true
        return false
    }
}

// ---------------------------------------------------------------------------
// 截断 / 去重：纯逻辑，抽成 internal 顶层函数，单测能直接打（MemoryTest.kt）
// ---------------------------------------------------------------------------

/**
 * 近期对话只留最新 cap 条，**顺序仍是旧 → 新**。
 * 为什么留最新的：判断"现在聊得怎么样"只需要最近的来回，半年期的一句"在吗"只会占 token。
 */
internal fun trimTurns(list: List<Turn>, cap: Int = Memories.MAX_TURNS): List<Turn> {
    if (cap <= 0) return emptyList()
    if (list.size <= cap) return list
    return ArrayList(list.subList(list.size - cap, list.size))
}

/** 关键事实只留前 cap 条（列表新的在前，所以是"留最新的"） */
internal fun trimFacts(list: List<MemFact>, cap: Int = Memories.MAX_FACTS): List<MemFact> {
    if (cap <= 0) return emptyList()
    if (list.size <= cap) return list
    return ArrayList(list.subList(0, cap))
}

/** 攻略度只留前 cap 条（列表新的在前） */
internal fun trimScores(list: List<ScorePoint>, cap: Int = Memories.MAX_SCORES): List<ScorePoint> {
    if (cap <= 0) return emptyList()
    if (list.size <= cap) return list
    return ArrayList(list.subList(0, cap))
}

/**
 * 摘要按字符截断，超出补 "…"。
 *
 * 截断后**总长**不超过 cap（省略号算在里面），这样"≤600 字"是一个能一眼验证的硬约束；
 * 顺手去掉截断点前的空白，免得出现"…  "这种尾巴。
 */
internal fun trimSummary(s: String, cap: Int = Memories.MAX_SUMMARY): String {
    val t = s.trim()
    if (cap <= 0) return ""
    if (t.length <= cap) return t
    return t.take(cap - 1).trimEnd() + "…"
}

/**
 * 去重追加：只跟**末尾那条**比（文本 trim 相同 + 侧别相同 → 跳过）。
 *
 * 为什么只比末尾：抓屏每次读到的都是"整屏可见消息"，一屏里前面几条跟上次完全一样，
 * 全量比对要 O(n²)，而且"同一句话连发两条"会被误吃。只挡"末尾重复"就够用 ——
 * 重复灌入的特征就是新读的一屏以旧屏的最后一条开头（Digest.dedupeLines 同一思路）。
 * 另外顺手丢掉空白轮次：屏幕上读到的空节点没有任何信息量，留着只会挤掉上限。
 */
internal fun appendTurnsDedup(existing: List<Turn>, incoming: List<Turn>): List<Turn> {
    val out = ArrayList<Turn>(existing.size + incoming.size)
    out.addAll(existing)
    for (t in incoming) {
        val text = t.text.trim()
        if (text.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.mine == t.mine && last.text.trim() == text) continue
        out.add(Turn(t.ts, t.mine, text))
    }
    return trimTurns(out)
}

/**
 * 文件是否"看起来完整"：首尾是花括号、括号配对、字符串引号闭合。
 *
 * 为什么要这一步：手写解析器很宽容，被截断的 JSON（写一半掉电）它也能吐出一堆半截字段，
 * 结果就是记忆里混进一条 ts=1、text="}" 的鬼东西。宁可整份丢掉（返回空记忆），
 * 也不要拿垃圾去喂模型。
 */
internal fun looksIntact(s: String): Boolean {
    val t = s.trim()
    if (t.length < 2 || t[0] != '{' || t[t.length - 1] != '}') return false
    var depth = 0
    var inStr = false
    var esc = false
    for (c in t) {
        if (inStr) {
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = false
            }
            continue
        }
        when (c) {
            '"' -> inStr = true
            '{', '[' -> depth++
            '}', ']' -> {
                depth--
                if (depth < 0) return false
            }
        }
    }
    return depth == 0 && !inStr
}

/**
 * 趋势：只用**最近两次**的差值判断，±3 以内算基本持平（分数是人给的印象分，差 1~2 分是噪声）。
 * [scores] 必须按时间**正序**（旧 → 新）；不足两次返回"首次"。
 */
internal fun scoreTrend(scores: List<ScorePoint>): String {
    if (scores.size < 2) return "首次"
    val delta = scores[scores.size - 1].score - scores[scores.size - 2].score
    return when {
        delta >= 3 -> "上升"
        delta <= -3 -> "下降"
        else -> "基本持平"
    }
}

/** 记忆块里只用"月-日"：年份/时分对判断关系没帮助，还占 token。ts<=0 视为没有时间 */
internal fun fmtDate(ts: Long): String =
    if (ts <= 0L) "" else SimpleDateFormat("MM-dd", Locale.US).format(Date(ts))

// ---------------------------------------------------------------------------

/**
 * 记忆的读写。存储布局：`filesDir/memory/<sha1(key)>.json` + `filesDir/memory/index.json`。
 *
 * 索引为什么存在：`listAll`（设置页列表）如果每次都扫目录再逐份解析几十 KB 的 JSON，
 * 打开设置页就要几百毫秒。索引里只放 key → name/updatedAt（几十字节一份），
 * 扫它就能排序展示；索引丢了/写坏了就回退扫目录 —— 索引是**加速器**，不是唯一真相，
 * 真相永远在各联系人自己的文件里（文件内也存了 key/name）。
 *
 * 所有方法都不抛异常：无障碍服务在别的线程抓屏写记忆，一次 IOException 把服务崩掉
 * 比丢一条记忆严重得多。失败只记 [AppLog] 并返回空记忆 / false / 0。
 */
object Memories {

    /** 近期对话上限：40 条来回（约 20 轮）够模型看出语气和节奏 */
    const val MAX_TURNS = 40

    /** 关键事实上限：12 条。再多模型也记不住，还会稀释重点 */
    const val MAX_FACTS = 12

    /** 攻略度上限：50 次（约两三个月的手动判读量） */
    const val MAX_SCORES = 50

    /** 摘要上限：600 字 */
    const val MAX_SUMMARY = 600

    private const val DIR = "memory"
    private const val INDEX = "index.json"

    /** 不存在 / 读不出来 → 一份空记忆（key 已按 [MemKeys.of] 规范化），调用方不必判空 */
    @Synchronized
    fun load(ctx: Context, key: String, name: String): ContactMemory {
        val k = key.ifEmpty { MemKeys.of(name) }
        val f = try {
            dirOf(ctx)?.let { File(it, MemKeys.fileName(k)) }
        } catch (e: Exception) {
            AppLog.add("记忆读取失败（$k）：${e.message}")
            null
        }
        if (f == null || !f.exists()) return empty(k, name)
        return readFile(f) ?: empty(k, name)
    }

    /** 落盘并刷新索引；失败只记日志（save 无返回值，调用方按"尽力而为"处理） */
    @Synchronized
    fun save(ctx: Context, mem: ContactMemory) {
        if (mem.key.isEmpty()) {
            AppLog.add("记忆保存跳过：key 为空")
            return
        }
        try {
            val dir = dirOf(ctx) ?: return
            File(dir, MemKeys.fileName(mem.key)).writeText(toJson(mem))
            indexPut(ctx, mem.key, mem.name, mem.updatedAt)
        } catch (e: Exception) {
            AppLog.add("记忆保存失败（${mem.key}）：${e.message}")
        }
    }

    /**
     * 追加对话轮次（去重 + 截断）。内容没变化时**不写盘** ——
     * 自动模式每次抓屏都会调它，一屏没动还去写文件纯属浪费电。
     */
    @Synchronized
    fun appendTurns(ctx: Context, key: String, name: String, turns: List<Turn>): ContactMemory {
        val cur = load(ctx, key, name)
        val merged = appendTurnsDedup(cur.turns, turns)
        val newName = name.ifEmpty { cur.name }
        if (merged == cur.turns && newName == cur.name) return cur
        val mem = cur.copy(name = newName, turns = merged, updatedAt = System.currentTimeMillis())
        save(ctx, mem)
        return mem
    }

    /** 记一次攻略度（越界会被夹到 0..100，不让脏数据把曲线拉飞） */
    @Synchronized
    fun addScore(ctx: Context, key: String, name: String, score: Int, note: String): ContactMemory {
        val cur = load(ctx, key, name)
        val s = score.coerceIn(0, 100)
        if (s != score) AppLog.add("攻略度越界已夹取：$score → $s")
        val now = System.currentTimeMillis()
        val mem = cur.copy(
            name = name.ifEmpty { cur.name },
            scores = trimScores(listOf(ScorePoint(now, s, note.trim())) + cur.scores),
            updatedAt = now
        )
        save(ctx, mem)
        return mem
    }

    /**
     * 用模型产出的摘要 + 事实**覆盖**摘要与事实（方法名是 set，不是 add）。
     *
     * 两个细节：
     *  - 同文本的事实保留**原来的时间戳**：事实的价值在"什么时候知道的"，不是"什么时候重写的"；
     *  - 事实按传入顺序去重（同文本只留第一条），传入顺序即"新的在前"。
     */
    @Synchronized
    fun setSummaryAndFacts(
        ctx: Context,
        key: String,
        name: String,
        summary: String,
        facts: List<String>
    ): ContactMemory {
        val cur = load(ctx, key, name)
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val fs = ArrayList<MemFact>(facts.size)
        for (raw in facts) {
            val t = raw.trim()
            if (t.isEmpty() || !seen.add(t)) continue
            fs.add(MemFact(t, cur.facts.firstOrNull { it.text == t }?.ts ?: now))
        }
        val mem = cur.copy(
            name = name.ifEmpty { cur.name },
            summary = trimSummary(summary),
            facts = trimFacts(fs),
            updatedAt = now,
            // 水位线：记下"这次摘要是基于多少条对话压出来的"，避免同一批对话被反复压缩
            turnsAtSummary = cur.turns.size
        )
        save(ctx, mem)
        return mem
    }

    /** 删掉一个联系人的记忆文件；文件本来就不在也算成功（幂等） */
    @Synchronized
    fun clear(ctx: Context, key: String): Boolean {
        val k = key.ifEmpty { return false }
        return try {
            val dir = dirOf(ctx) ?: return false
            val f = File(dir, MemKeys.fileName(k))
            val ok = !f.exists() || f.delete()
            indexRemove(ctx, k)
            ok
        } catch (e: Exception) {
            AppLog.add("记忆删除失败（$k）：${e.message}")
            false
        }
    }

    /** 清空所有记忆，返回清掉的份数（不含索引文件本身） */
    @Synchronized
    fun clearAll(ctx: Context): Int {
        var n = 0
        try {
            val dir = dirOf(ctx) ?: return 0
            File(dir, INDEX).delete()
            for (f in dir.listFiles().orEmpty()) {
                if (!f.isFile || f.name == INDEX || !f.name.endsWith(".json")) continue
                if (f.delete()) n++
            }
        } catch (e: Exception) {
            AppLog.add("记忆清空失败：${e.message}")
        }
        return n
    }

    /** 全部记忆，按 updatedAt 降序（最近聊过的排前面）。索引坏了自动回退扫目录 */
    @Synchronized
    fun listAll(ctx: Context): List<ContactMemory> {
        val out = ArrayList<ContactMemory>()
        try {
            val dir = dirOf(ctx) ?: return emptyList()
            val idx = readIndex(ctx)
            if (idx.isNotEmpty()) {
                for (key in idx.keys) {
                    val f = File(dir, MemKeys.fileName(key))
                    if (f.exists()) readFile(f)?.let { out.add(it) }
                }
            }
            if (out.isEmpty()) {
                // 索引缺失/损坏/指向的文件都没了：回退扫目录（慢一点，但不会"看不见记忆"）
                for (f in dir.listFiles().orEmpty()) {
                    if (!f.isFile || f.name == INDEX || !f.name.endsWith(".json")) continue
                    readFile(f)?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
            AppLog.add("记忆列表读取失败：${e.message}")
        }
        return out.sortedByDescending { it.updatedAt }
    }

    /**
     * 拼给模型/ Jev 看的中文记忆块。没有的部分**整段省略**（不留空标题），
     * 全空则返回空串，调用方可以直接 `if (block.isEmpty())` 判断要不要拼进 prompt。
     */
    fun contextBlock(
        mem: ContactMemory,
        maxFacts: Int = 6,
        maxScores: Int = 5,
        maxTurns: Int = 8
    ): String {
        val lines = ArrayList<String>()

        if (mem.relation.isNotEmpty()) {
            val d = fmtDate(mem.updatedAt)
            lines.add(if (d.isEmpty()) "关系：${mem.relation}" else "关系：${mem.relation}（最近更新：$d）")
        }
        if (mem.summary.isNotEmpty()) lines.add("摘要：${mem.summary}")

        if (maxFacts > 0 && mem.facts.isNotEmpty()) {
            lines.add("关键事实：")
            for (f in mem.facts.take(maxFacts)) lines.add("- " + withDate(f.text, f.ts))
        }

        if (maxScores > 0 && mem.scores.isNotEmpty()) {
            // scores 新的在前 → 翻成旧 → 新，才像一条"曲线"
            val recent = mem.scores.take(maxScores).asReversed()
            val nums = recent.joinToString(" → ") { "${it.score}%" }
            val tail = StringBuilder("近 ${recent.size} 次，").append(scoreTrend(recent))
            val last = fmtDate(recent.lastOrNull()?.ts ?: 0L)
            if (last.isNotEmpty()) tail.append("，最近一次 ").append(last)
            lines.add("历次攻略度：$nums（$tail）")
        }

        if (maxTurns > 0 && mem.turns.isNotEmpty()) {
            lines.add("近期对话：")
            for (t in mem.turns.takeLast(maxTurns)) lines.add((if (t.mine) "我：" else "对方：") + t.text)
        }

        if (lines.isEmpty()) return ""
        // 开头点明"这段记忆属于谁"：块里的「我／对方」只说明了角色，没说明对方是谁，
        // 模型同时看到多个人的信息时容易张冠李戴 —— 一行字就把归属钉死。
        if (mem.name.isNotEmpty()) lines.add(0, "对象：${mem.name}")
        return "【记忆】\n" + lines.joinToString("\n")
    }

    /** 手写序列化，照 [Contacts.toJson] 的做法（零第三方依赖）。v 是格式版本，方便以后迁移 */
    fun toJson(mem: ContactMemory): String {
        val m = LinkedHashMap<String, Any?>()
        m["v"] = 1
        m["key"] = mem.key
        m["name"] = mem.name
        m["relation"] = mem.relation
        m["summary"] = mem.summary
        m["facts"] = mem.facts.map { mapOf("text" to it.text, "ts" to it.ts) }
        m["scores"] = mem.scores.map { mapOf("ts" to it.ts, "score" to it.score, "note" to it.note) }
        m["turns"] = mem.turns.map { mapOf("ts" to it.ts, "mine" to it.mine, "text" to it.text) }
        m["updatedAt"] = mem.updatedAt
        m["turnsAtSummary"] = mem.turnsAtSummary
        return Json.write(m)
    }

    /**
     * 解析记忆。**永不抛异常**：缺字段用默认值、类型不对当空、JSON 损坏返回空记忆。
     * [key] / [name] 是调用方知道的权威值（优先于文件里的），为空时才用文件里的。
     */
    fun fromJson(key: String, name: String, s: String): ContactMemory {
        val fallbackKey = key.ifEmpty { MemKeys.of(name) }
        val fallback = empty(fallbackKey, name)
        if (!looksIntact(s)) return fallback

        val m = try {
            Json.obj(Json.parse(s))
        } catch (e: Exception) {
            AppLog.add("记忆解析失败：${e.message}")
            return fallback
        }
        if (m.isEmpty()) return fallback

        val k = key.ifEmpty { textOf(m["key"]).ifEmpty { fallbackKey } }
        val n = name.ifEmpty { textOf(m["name"]).ifEmpty { k } }

        val facts = Json.list(m["facts"]).mapNotNull { item ->
            val o = Json.obj(item)
            val t = textOf(o["text"]).trim()
            if (t.isEmpty()) null else MemFact(t, Json.num(o["ts"])?.toLong() ?: 0L)
        }
        val scores = Json.list(m["scores"]).mapNotNull { item ->
            val o = Json.obj(item)
            // 分数不是数字就整条丢掉：宁可少一次记录，也不要造一个假的 0 分去带偏趋势
            val sc = Json.num(o["score"])?.toInt() ?: return@mapNotNull null
            ScorePoint(Json.num(o["ts"])?.toLong() ?: 0L, sc.coerceIn(0, 100), textOf(o["note"]))
        }
        val turns = Json.list(m["turns"]).mapNotNull { item ->
            val o = Json.obj(item)
            val t = textOf(o["text"]).trim()
            if (t.isEmpty()) null else Turn(Json.num(o["ts"])?.toLong() ?: 0L, boolOf(o["mine"]), t)
        }

        return ContactMemory(
            key = k,
            name = n,
            relation = textOf(m["relation"]),
            // 从磁盘读回来的也过一遍上限：文件可能被手改，或由旧版本写出
            summary = trimSummary(textOf(m["summary"])),
            facts = trimFacts(facts),
            scores = trimScores(scores),
            turns = trimTurns(turns),
            updatedAt = Json.num(m["updatedAt"])?.toLong() ?: 0L,
            // 老文件没有这个字段 → 0，等于"还没压过摘要"，下次攒够就压一次（幂等，不亏）
            turnsAtSummary = (Json.num(m["turnsAtSummary"])?.toInt() ?: 0).coerceIn(0, 10_000)
        )
    }

    /** 把抓屏得到的一条消息转成记忆轮次（与 [ScreenMsg] 对齐；引用内容折进文本，同 Digest.state） */
    fun turnOf(msg: ScreenMsg, ts: Long): Turn =
        Turn(ts, msg.mine, msg.text + (msg.quoted?.let { "（引用：$it）" } ?: ""))

    // --- 内部：文件与索引 -------------------------------------------------

    /** 一份空记忆：全部字段都是"没有"，而不是编一个默认关系出来骗人 */
    private fun empty(key: String, name: String): ContactMemory = ContactMemory(
        key = key,
        name = name,
        relation = "",
        summary = "",
        facts = emptyList(),
        scores = emptyList(),
        turns = emptyList(),
        updatedAt = 0L
    )

    /** 记忆目录，不存在就建；建不出来返回 null（调用方各自兜底） */
    private fun dirOf(ctx: Context): File? {
        val d = File(ctx.filesDir, DIR)
        if (d.isDirectory) return d
        if (d.mkdirs() || d.isDirectory) return d
        AppLog.add("记忆目录创建失败：${d.absolutePath}")
        return null
    }

    /** 读一个记忆文件；key/name 以文件内容为准（扫目录时文件名是哈希，看不出是谁） */
    private fun readFile(f: File): ContactMemory? = try {
        val text = f.readText()
        val o = Json.obj(Json.parse(text))
        val key = textOf(o["key"]).ifEmpty { f.name.removeSuffix(".json") }
        val name = textOf(o["name"]).ifEmpty { key }
        fromJson(key, name, text)
    } catch (e: Exception) {
        AppLog.add("记忆文件读取失败（${f.name}）：${e.message}")
        null
    }

    /** 只认字符串/数字；对象、数组、布尔一律当空（类型不对说明文件被改坏了） */
    private fun textOf(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> Json.str(v)
        else -> ""
    }

    /** JSON 里 true / 1 / "true" 都算 true，其余算 false（容错，不抛） */
    private fun boolOf(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Double -> v != 0.0
        is String -> v.equals("true", true) || v == "1"
        else -> false
    }

    private fun withDate(text: String, ts: Long): String {
        val d = fmtDate(ts)
        return if (d.isEmpty()) text else "$text（$d）"
    }

    /** 索引：key → name/updatedAt，只够排序展示用；读不出来就当没有（触发扫目录回退） */
    private fun readIndex(ctx: Context): Map<String, Pair<String, Long>> {
        val out = LinkedHashMap<String, Pair<String, Long>>()
        try {
            val dir = dirOf(ctx) ?: return out
            val f = File(dir, INDEX)
            if (!f.exists()) return out
            for ((k, v) in Json.obj(Json.parse(f.readText()))) {
                if (k.isEmpty()) continue
                val o = Json.obj(v)
                out[k] = textOf(o["name"]) to (Json.num(o["updatedAt"])?.toLong() ?: 0L)
            }
        } catch (e: Exception) {
            AppLog.add("记忆索引读取失败：${e.message}")
        }
        return out
    }

    private fun writeIndex(ctx: Context, idx: Map<String, Pair<String, Long>>) {
        try {
            val dir = dirOf(ctx) ?: return
            val m = LinkedHashMap<String, Any?>()
            for ((k, v) in idx) m[k] = mapOf("name" to v.first, "updatedAt" to v.second)
            File(dir, INDEX).writeText(Json.write(m))
        } catch (e: Exception) {
            AppLog.add("记忆索引写入失败：${e.message}")
        }
    }

    private fun indexPut(ctx: Context, key: String, name: String, updatedAt: Long) {
        val idx = LinkedHashMap(readIndex(ctx))
        idx[key] = name to updatedAt
        writeIndex(ctx, idx)
    }

    private fun indexRemove(ctx: Context, key: String) {
        val idx = LinkedHashMap(readIndex(ctx))
        if (idx.remove(key) == null) return
        writeIndex(ctx, idx)
    }
}
