package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Memory 的纯逻辑单测：只测不依赖 Context 的部分（键规范化 / JSON / 记忆块 / 截断 / 去重）。
 * 落盘与索引那几条路径要真机文件系统，不在单测范围内 —— 那些函数的兜底是"吞异常 + 记日志"。
 */
class MemoryTest {

    private fun mem(
        key: String = "小明",
        name: String = "小明",
        relation: String = "",
        summary: String = "",
        facts: List<MemFact> = emptyList(),
        scores: List<ScorePoint> = emptyList(),
        turns: List<Turn> = emptyList(),
        updatedAt: Long = 0L
    ) = ContactMemory(key, name, relation, summary, facts, scores, turns, updatedAt)

    // --- 键规范化 ---------------------------------------------------------

    @Test
    fun memKeysNormalizeGroupSuffixCaseWhitespaceAndInvisibleChars() {
        // 群聊人数后缀：半角、全角、带斜杠
        assertEquals("项目组", MemKeys.of("项目组 (8)"))
        assertEquals("项目组", MemKeys.of("项目组（12）"))
        assertEquals("项目组", MemKeys.of(" 项目组 （3/9） "))
        // 大小写
        assertEquals("abc", MemKeys.of("ABC"))
        // 不可见字符：BOM / 零宽 / NBSP
        assertEquals("小明", MemKeys.of("\uFEFF小明\u200B"))
        assertEquals("小明", MemKeys.of("小明\u00A0"))
        // 内部空白**折叠**成一个空格（不是删掉：昵称里"张 伟"和"张伟"是两个人）
        assertEquals("张 伟", MemKeys.of("  张   伟  "))
        assertEquals("张 伟", MemKeys.of("张\t伟"))
        assertEquals("张 伟", MemKeys.of("张\u3000伟"))
        // 空 / 纯空白
        assertEquals("", MemKeys.of("   "))
        assertEquals("", MemKeys.of(""))
    }

    @Test
    fun fileNameIsSha1HexPlusJson() {
        val expected = MessageDigest.getInstance("SHA-1")
            .digest("小明".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals("$expected.json", MemKeys.fileName("小明"))
        assertTrue(MemKeys.fileName("小明❤️/..\\x").matches(Regex("""[0-9a-f]{40}\.json""")))
        assertEquals(MemKeys.fileName("小明"), MemKeys.fileName("小明"))
        assertNotEquals(MemKeys.fileName("小明"), MemKeys.fileName("小红"))
    }

    // --- JSON 往返与容错 --------------------------------------------------

    @Test
    fun jsonRoundTripsEmptyMemory() {
        val e = ContactMemory("甲", "甲", "", "", emptyList(), emptyList(), emptyList(), 0L)
        assertEquals(e, Memories.fromJson("甲", "甲", Memories.toJson(e)))
    }

    @Test
    fun jsonRoundTripsEveryField() {
        val full = mem(
            key = "小明",
            name = "小明❤️",
            relation = "情侣",
            summary = "认识三个月，喜欢猫",
            facts = listOf(MemFact("喜欢猫", 1_700_000_000_000L), MemFact("下周三出差", 1_700_100_000_000L)),
            scores = listOf(ScorePoint(1_700_200_000_000L, 71, "聊得不错"), ScorePoint(1_700_100_000_000L, 62, "")),
            turns = listOf(Turn(1_700_300_000_000L, false, "在吗"), Turn(1_700_300_001_000L, true, "在")),
            updatedAt = 1_700_300_001_000L
        )
        assertEquals(full, Memories.fromJson(full.key, full.name, Memories.toJson(full)))
        // 文本里带引号/换行/反斜杠也要能回来（Json.quote 的转义）
        val tricky = mem(key = "k", name = "k", summary = "他说\"你好\"\n然后走了\\")
        assertEquals(tricky.summary, Memories.fromJson("k", "k", Memories.toJson(tricky)).summary)
    }

    @Test
    fun fromJsonToleratesMissingFieldsAndWrongTypes() {
        // 缺字段：key/name 用调用方给的权威值，其余为空
        val missing = Memories.fromJson("k", "甲", """{"key":"k"}""")
        assertEquals("k", missing.key)
        assertEquals("甲", missing.name)
        assertEquals("", missing.relation)
        assertEquals("", missing.summary)
        assertTrue(missing.facts.isEmpty())
        assertTrue(missing.scores.isEmpty())
        assertTrue(missing.turns.isEmpty())
        assertEquals(0L, missing.updatedAt)

        // key/name 传空时用文件里的
        assertEquals("小明", Memories.fromJson("", "", """{"key":"小明","name":"小明"}""").key)

        // facts 不是数组 / 数组里元素类型不对
        assertTrue(Memories.fromJson("k", "甲", """{"key":"k","facts":"不是数组"}""").facts.isEmpty())
        assertTrue(Memories.fromJson("k", "甲", """{"key":"k","facts":{"a":1}}""").facts.isEmpty())
        assertEquals(
            listOf("ok"),
            Memories.fromJson("k", "甲", """{"key":"k","facts":[1,"",{"text":"ok"}]}""").facts.map { it.text }
        )

        // relation/summary 类型不对 → 当空（不要 "{}" 这种 toString 垃圾）
        val wrong = Memories.fromJson("k", "甲", """{"key":"k","relation":[],"summary":{"a":1}}""")
        assertEquals("", wrong.relation)
        assertEquals("", wrong.summary)

        // mine 不是布尔："yes" → false，数字 1 → true
        val turns = Memories.fromJson(
            "k", "甲",
            """{"key":"k","turns":[{"ts":1,"mine":"yes","text":"hi"},{"ts":2,"mine":1,"text":"yo"}]}"""
        ).turns
        assertEquals(2, turns.size)
        assertFalse(turns[0].mine)
        assertEquals(1L, turns[0].ts)
        assertTrue(turns[1].mine)

        // 分数不是数字 → 整条丢掉（不造假的 0 分）
        assertEquals(
            listOf(80),
            Memories.fromJson("k", "甲", """{"key":"k","scores":[{"score":"abc"},{"score":80}]}""").scores.map { it.score }
        )
        // 分数越界 → 夹取
        assertEquals(
            listOf(100, 0),
            Memories.fromJson("k", "甲", """{"key":"k","scores":[{"score":180},{"score":-20}]}""").scores.map { it.score }
        )
    }

    @Test
    fun fromJsonReturnsEmptyMemoryForGarbageAndTruncatedJson() {
        assertEquals(MemKeys.of("甲"), Memories.fromJson("", "甲", "坏数据").key)
        assertTrue(Memories.fromJson("k", "甲", "坏数据").turns.isEmpty())
        assertEquals(0L, Memories.fromJson("k", "甲", "").updatedAt)
        // 截断（写一半掉电）：整份丢掉，不要半截字段
        assertTrue(Memories.fromJson("k", "甲", """{"key":"k","turns":[{"ts":1,""").turns.isEmpty())
        assertTrue(Memories.fromJson("k", "甲", """{"key":"k","summary":"没闭合""").summary.isEmpty())
        assertTrue(Memories.fromJson("k", "甲", """{"key":"k","turns":[1,2]""").turns.isEmpty())
        // 从磁盘读回来的也要满足上限（文件可能被手改）
        val longSummary = Memories.fromJson("k", "甲", "{\"key\":\"k\",\"summary\":\"" + "啊".repeat(700) + "\"}")
        assertEquals(Memories.MAX_SUMMARY, longSummary.summary.length)
    }

    @Test
    fun looksIntactAcceptsBalancedJsonOnly() {
        assertTrue(looksIntact("{}"))
        assertTrue(looksIntact("""{"a":[1,2],"b":{"c":"}"}}"""))
        assertTrue(looksIntact("""  {"a":"\\\""}  """))
        assertFalse(looksIntact(""))
        assertFalse(looksIntact("[]"))
        assertFalse(looksIntact("坏数据"))
        assertFalse(looksIntact("""{"a":1}x"""))
        assertFalse(looksIntact("""{"a":[1,2]"""))
        assertFalse(looksIntact("""{"a":"没闭合}"""))
        assertFalse(looksIntact("""{"a":}}"""))
    }

    // --- 截断与去重 -------------------------------------------------------

    @Test
    fun trimHelpersKeepTheRightEnds() {
        // turns：留最新 40 条，顺序仍是旧 → 新
        val turns = (1..45).map { Turn(it.toLong(), it % 2 == 0, "t$it") }
        val tt = trimTurns(turns)
        assertEquals(Memories.MAX_TURNS, tt.size)
        assertEquals(6L, tt.first().ts)
        assertEquals(45L, tt.last().ts)
        assertEquals(3, trimTurns(turns, 3).size)
        assertEquals(43L, trimTurns(turns, 3).first().ts)
        assertTrue(trimTurns(turns, 0).isEmpty())

        // facts / scores：列表新的在前 → 留前 N 条
        val facts = (1..20).map { MemFact("f$it", it.toLong()) }
        assertEquals(Memories.MAX_FACTS, trimFacts(facts).size)
        assertEquals("f1", trimFacts(facts).first().text)
        val scores = (1..60).map { ScorePoint(it.toLong(), it, "") }
        assertEquals(Memories.MAX_SCORES, trimScores(scores).size)
        assertEquals(1L, trimScores(scores).first().ts)

        // summary：截断后总长不超过上限，尾巴是省略号
        assertEquals("短", trimSummary("短"))
        assertEquals("", trimSummary("x", 0))
        val cut = trimSummary("啊".repeat(1000))
        assertEquals(Memories.MAX_SUMMARY, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("abcd", trimSummary("abcd", 4))    // 正好到上限：不截
        assertEquals("abc…", trimSummary("abcde", 4))   // 超一个字符就截
    }

    @Test
    fun appendTurnsDedupSkipsRepeatsAndCaps() {
        val existing = listOf(Turn(1L, false, "在吗"), Turn(2L, true, "在"))
        // 与末尾一样（连前后空白差异也算一样）→ 跳过，避免每次抓屏把整屏重复写进去
        assertEquals(existing, appendTurnsDedup(existing, listOf(Turn(3L, true, " 在 "))))
        // 文本一样但侧别不同 → 保留（"在"和"在"可能是两个人各说一遍）
        assertEquals(3, appendTurnsDedup(existing, listOf(Turn(3L, false, "在"))).size)
        // 空文本丢掉
        assertEquals(existing, appendTurnsDedup(existing, listOf(Turn(4L, true, "   "))))
        // 同一批里的连续重复也挡
        assertEquals(3, appendTurnsDedup(existing, listOf(Turn(5L, false, "好吧"), Turn(6L, false, "好吧"))).size)
        // 超过上限：留最新 40 条，顺序旧 → 新
        val out = appendTurnsDedup(emptyList(), (1..45).map { Turn(it.toLong(), false, "m$it") })
        assertEquals(Memories.MAX_TURNS, out.size)
        assertEquals(6L, out.first().ts)
        assertEquals(45L, out.last().ts)
    }

    // --- 趋势与记忆块 -----------------------------------------------------

    /**
     * 按**时间正序**（旧 → 新）造分数，可直接喂 [scoreTrend]；contextBlock 用的是存储顺序"新的在前"，调用处自己 reversed()。
     * ts 一律给 0，这样断言里不会出现"最近一次 MM-dd"（那个尾巴跟时区有关，单独测）。
     */
    private fun scoresOf(vararg v: Int) = v.map { ScorePoint(0L, it, "") }

    @Test
    fun trendUsesLastTwoScoresWithThreePointThreshold() {
        assertEquals("首次", scoreTrend(emptyList()))
        assertEquals("首次", scoreTrend(scoresOf(50)))
        assertEquals("上升", scoreTrend(scoresOf(60, 63)))      // 正好 +3
        assertEquals("基本持平", scoreTrend(scoresOf(60, 62)))  // +2 算噪声
        assertEquals("下降", scoreTrend(scoresOf(60, 57)))      // 正好 -3
        assertEquals("基本持平", scoreTrend(scoresOf(60, 58)))
        assertEquals("下降", scoreTrend(scoresOf(70, 71, 60)))  // 只看最近两次
    }

    @Test
    fun contextBlockOmitsEmptySections() {
        // 全空 → 空串，连"【记忆】"这个标题都不输出
        assertEquals("", Memories.contextBlock(mem(key = "k", name = "k")))

        // 只有关系 → 只输出关系行；updatedAt=0 时不编造"最近更新"
        val onlyRelation = Memories.contextBlock(mem(key = "k", name = "k", relation = "同事"))
        assertEquals("【记忆】\n关系：同事", onlyRelation)

        val withTime = Memories.contextBlock(mem(key = "k", name = "k", relation = "情侣", updatedAt = 1_700_000_000_000L))
        assertTrue(Regex("""关系：情侣（最近更新：\d{2}-\d{2}）""").containsMatchIn(withTime))

        // 没有摘要/评分时，对应的标题一个字都不出现
        val noSummary = Memories.contextBlock(
            mem(key = "k", name = "k", relation = "同事", facts = listOf(MemFact("喜欢猫", 0L)))
        )
        assertFalse(noSummary.contains("摘要"))
        assertFalse(noSummary.contains("攻略度"))
        assertFalse(noSummary.contains("近期对话"))
        assertTrue(noSummary.contains("关键事实：\n- 喜欢猫"))
    }

    @Test
    fun contextBlockRendersEverySection() {
        val b = Memories.contextBlock(
            mem(
                key = "小明",
                name = "小明",
                relation = "情侣",
                summary = "认识三个月，喜欢猫",
                facts = listOf(MemFact("喜欢猫", 0L), MemFact("下周三出差", 0L)),
                // 新的在前：71 最新，62 最早
                scores = listOf(ScorePoint(0L, 71, ""), ScorePoint(0L, 68, ""), ScorePoint(0L, 62, "")),
                turns = listOf(Turn(0L, false, "在吗"), Turn(0L, true, "在"))
            )
        )
        assertTrue(b.startsWith("【记忆】\n"))
        assertTrue(b.contains("摘要：认识三个月，喜欢猫"))
        assertTrue(b.contains("关键事实：\n- 喜欢猫\n- 下周三出差"))
        // 取最近 5 次并翻成时间正序 → 62 → 68 → 71；68→71 差 3 判上升
        assertTrue(b.contains("历次攻略度：62% → 68% → 71%（近 3 次，上升）"))
        // turns 是旧 → 新
        assertTrue(b.contains("近期对话：\n对方：在吗\n我：在"))
    }

    @Test
    fun contextBlockShowsFirstForSingleScore() {
        // 只有 1 次评分：趋势显示"首次"（不足两次没法比，不硬编"持平"骗人）
        val b = Memories.contextBlock(mem(key = "k", name = "k", scores = scoresOf(70).reversed()))
        assertTrue(b.contains("历次攻略度：70%（近 1 次，首次）"))
        // 下降（存储顺序是新的在前，所以 reversed() 后才是 80 → 60）
        val down = Memories.contextBlock(mem(key = "k", name = "k", scores = scoresOf(80, 60).reversed()))
        assertTrue(down.contains("80% → 60%（近 2 次，下降）"))
        // 有真实时间戳时补一句"最近一次 MM-dd"
        val dated = Memories.contextBlock(
            mem(key = "k", name = "k", scores = listOf(ScorePoint(1_700_000_000_000L, 66, "")))
        )
        assertTrue(Regex("""历次攻略度：66%（近 1 次，首次，最近一次 \d{2}-\d{2}）""").containsMatchIn(dated))
    }

    @Test
    fun contextBlockRespectsMaxParams() {
        val m = mem(
            key = "k",
            name = "k",
            relation = "同事",
            facts = (1..5).map { MemFact("f$it", 0L) },
            // 存储顺序是"新的在前"，reversed() 之后才是 56,55,54,53,52,51（ts 给 0，免得断言里混进日期）
            scores = (1..6).map { ScorePoint(0L, 50 + it, "") }.reversed(),
            turns = (1..5).map { Turn(it.toLong(), false, "t$it") }
        )
        val b = Memories.contextBlock(m, maxFacts = 2, maxScores = 3, maxTurns = 2)
        assertTrue(b.contains("关键事实：\n- f1\n- f2\n"))
        assertFalse(b.contains("f3"))
        // 最近 3 次是 56/55/54，正序展示
        assertTrue(b.contains("历次攻略度：54% → 55% → 56%（近 3 次，基本持平）"))
        assertTrue(b.contains("近期对话：\n对方：t4\n对方：t5"))
        assertFalse(b.contains("t3"))

        // 参数为 0 → 整段省略
        val none = Memories.contextBlock(m, maxFacts = 0, maxScores = 0, maxTurns = 0)
        assertEquals("【记忆】\n关系：同事", none)
    }

    // --- 与 Digest.ScreenMsg 对齐 ----------------------------------------

    @Test
    fun turnOfMirrorsScreenMsg() {
        val t = Memories.turnOf(ScreenMsg(true, "好的", "在吗"), 1234L)
        assertEquals(1234L, t.ts)
        assertTrue(t.mine)
        assertEquals("好的（引用：在吗）", t.text)

        val plain = Memories.turnOf(ScreenMsg(false, "在吗"), 1L)
        assertFalse(plain.mine)
        assertEquals("在吗", plain.text)
    }
}
