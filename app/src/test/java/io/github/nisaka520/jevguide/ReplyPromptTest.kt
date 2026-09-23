package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReplyPrompt 的宽容度测试。
 *
 * 模型输出的排版永远比提示词要求的更花：套代码块、加编号、前面来一句"好的以下是"、
 * 段数多一段少一段……解析器只能在"读懂结构"和"别把正文啃掉"之间取舍，
 * 所以把七种典型形态全钉成用例 —— 以后谁动了分段优先级，这些会先叫。
 */
class ReplyPromptTest {

    // ---------- (a) 标准：标题行 + --- 分隔 ----------

    @Test
    fun a_standardTitledSections() {
        val raw = """
            【稳妥】
            在的，我看到了，稍等我确认一下再回你。
            ---
            【推进】
            我下午三点前给你答复。
            ---
            【有趣】
            在在在，你的消息我哪敢不看。
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(3, d.size)
        assertEquals(listOf("稳妥", "推进", "有趣"), d.map { it.title })
        assertEquals(listOf(0, 1, 2), d.map { it.index })
        assertEquals("在的，我看到了，稍等我确认一下再回你。", d[0].text)
        assertEquals("在在在，你的消息我哪敢不看。", d[2].text)
    }

    // ---------- (b) 外面套了 ``` 代码块 ----------

    @Test
    fun b_wrappedInCodeFence() {
        val raw = """
            ```text
            【稳妥】
            好，我知道了。
            ---
            【推进】
            我现在就去办，办完告诉你。
            ---
            【有趣】
            收到收到，比心跳还快。
            ```
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(3, d.size)
        assertEquals(listOf("稳妥", "推进", "有趣"), d.map { it.title })
        assertEquals("好，我知道了。", d[0].text)
        assertEquals("我现在就去办，办完告诉你。", d[1].text)
        assertFalse(d.any { it.text.contains("`") })
    }

    // ---------- (c) 用编号，没有 --- ----------

    @Test
    fun c1_numberedLinesThatStillCarryTitles() {
        val raw = """
            1. 【稳妥】先别急，我看看情况。
            2. 【推进】我十分钟后给你结果。
            3. 【有趣】急啥，天塌不下来。
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(3, d.size)
        assertEquals(listOf("稳妥", "推进", "有趣"), d.map { it.title })
        assertEquals("先别急，我看看情况。", d[0].text)
        assertEquals("我十分钟后给你结果。", d[1].text)
        assertEquals("急啥，天塌不下来。", d[2].text)
    }

    @Test
    fun c2_numberedLinesWithoutTitlesUseStyleTitles() {
        val raw = """
            1. 好的，我马上看，看完给你答复。
            2. 我半小时内回你，先别急。
            3. 在的，别慌，天塌不下来。
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(3, d.size)
        assertEquals(listOf("稳妥", "推进", "有趣"), d.map { it.title })
        assertEquals("好的，我马上看，看完给你答复。", d[0].text)
        assertEquals("在的，别慌，天塌不下来。", d[2].text)
    }

    @Test
    fun c3_decimalIsNotMistakenForAListNumber() {
        val raw = "3.5 折已经很便宜了，真的别砍了。"
        val d = ReplyPrompt.parse(raw)
        assertEquals(1, d.size)
        assertEquals("3.5 折已经很便宜了，真的别砍了。", d[0].text)
    }

    // ---------- (d) 前后有多余的解释性废话 ----------

    @Test
    fun d_extraChitChatAroundIsIgnored() {
        val raw = """
            好的，下面是我给你的三条回复，你挑一条用就行：

            【稳妥】
            我看到了，晚点仔细回你。

            ---

            【推进】
            这事我今天就给你办，最晚六点前有结果。

            ---

            【有趣】
            别催别催，正在给你憋大招呢。

            需要我换个更正式的语气吗？
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(3, d.size)
        assertEquals(listOf("稳妥", "推进", "有趣"), d.map { it.title })
        assertEquals("我看到了，晚点仔细回你。", d[0].text)
        assertFalse(d[0].text.contains("挑一条"))
        assertEquals("别催别催，正在给你憋大招呢。", d[2].text)
        assertFalse(d[2].text.contains("更正式"))
    }

    // ---------- (e) 只给了 1 段或 2 段 ----------

    @Test
    fun e1_twoSectionsStayTwo() {
        val raw = """
            【稳妥】
            在的，你说。
            ---
            【推进】
            我马上处理，稍后同步给你。
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(2, d.size)
        assertEquals(listOf("稳妥", "推进"), d.map { it.title })
        assertEquals("在的，你说。", d[0].text)
        assertEquals("我马上处理，稍后同步给你。", d[1].text)
    }

    @Test
    fun e2_singleSectionStillParses() {
        val raw = """
            【有趣】
            在呢，说吧，什么事这么神秘。
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(1, d.size)
        assertEquals("有趣", d[0].title)
        assertEquals(0, d[0].index)
        assertEquals("在呢，说吧，什么事这么神秘。", d[0].text)
    }

    // ---------- (f) 给了 4 段以上：只取前 n 段 ----------

    @Test
    fun f_moreSectionsThanRequestedKeepsFirstN() {
        val raw = """
            【稳妥】A1
            ---
            【推进】A2
            ---
            【有趣】A3
            ---
            【直接】A4
        """.trimIndent()

        val three = ReplyPrompt.parse(raw)
        assertEquals(3, three.size)
        assertEquals(listOf("稳妥", "推进", "有趣"), three.map { it.title })
        assertFalse(three.any { it.text.contains("A4") })

        val two = ReplyPrompt.parse(raw, 2)
        assertEquals(2, two.size)
        assertEquals(listOf("稳妥", "推进"), two.map { it.title })

        val one = ReplyPrompt.parse(raw, 1)
        assertEquals(1, one.size)
        assertEquals("A1", one[0].text)
    }

    @Test
    fun f2_requestingMoreThanProvidedIsFine() {
        val raw = "【稳妥】\n只有这一条。"
        val d = ReplyPrompt.parse(raw, 5)
        assertEquals(1, d.size)
        assertEquals("只有这一条。", d[0].text)
    }

    // ---------- (g) 完全没有可识别结构：整段兜底 ----------

    @Test
    fun g_noStructureFallsBackToWholeText() {
        val raw = "抱歉，我这边现在没法帮你处理这件事，你找别人试试吧。"
        val d = ReplyPrompt.parse(raw)
        assertEquals(1, d.size)
        assertEquals(0, d[0].index)
        assertEquals(ReplyPrompt.STYLE_TITLES[0], d[0].title)
        assertEquals(raw, d[0].text)
    }

    @Test
    fun g2_multiLineWithoutStructureAlsoFallsBack() {
        val raw = "在的。\n我刚看到消息。\n你说吧。"
        val d = ReplyPrompt.parse(raw)
        assertEquals(1, d.size)
        assertEquals("稳妥", d[0].title)
        assertEquals("在的。\n我刚看到消息。\n你说吧。", d[0].text)
    }

    // ---------- 两个更像真实模型输出的样例 ----------

    @Test
    fun realisticFenceBoldAndEmoji() {
        val raw = """
            ```markdown
            【稳妥】
            嗯嗯，我看到你发的了，我先把手上这点事收个尾，晚点认真回你～

            ---

            **【推进】**
            我下午两点前给你准信，你要是急也可以先打电话给我。

            ---

            【有趣】
            收到！你的消息已加入今日待办，优先级：最高 😂
            ```
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(3, d.size)
        assertEquals(listOf("稳妥", "推进", "有趣"), d.map { it.title })
        assertEquals("嗯嗯，我看到你发的了，我先把手上这点事收个尾，晚点认真回你～", d[0].text)
        assertEquals("我下午两点前给你准信，你要是急也可以先打电话给我。", d[1].text)
        assertTrue(d[2].text.endsWith("😂"))
        assertFalse(d.any { it.text.contains("*") || it.text.contains("`") || it.text.contains("---") })
    }

    @Test
    fun realisticCircledNumbersAndPreamble() {
        val raw = """
            我先按你说的关系（情侣）来写，三条风格各一条：

            ①【稳妥】
            别生气啦，我刚刚真的在忙，不是故意不回你的。

            ②【推进】
            我知道你在等我，十点前我一定把这件事跟你说清楚。

            ③【有趣】
            好啦好啦，我认错，罚我给你点一杯奶茶行不行？
        """.trimIndent()

        val d = ReplyPrompt.parse(raw)
        assertEquals(3, d.size)
        assertEquals(listOf("稳妥", "推进", "有趣"), d.map { it.title })
        assertTrue(d[0].text.startsWith("别生气啦"))
        assertFalse(d[0].text.contains("我先按你说的关系"))
        assertEquals("好啦好啦，我认错，罚我给你点一杯奶茶行不行？", d[2].text)
    }

    // ---------- 绝不抛异常 ----------

    @Test
    fun neverThrowsOnGarbage() {
        val inputs = listOf(
            "", " ", "\n\n\n", "---", "---\n---\n---", "```", "```json\n```",
            "【】", "【】\n---\n【】", "【稳妥】", "1.", "①②③", "1.\n2.\n3.",
            "\u0000乱码\u0001", "【a】b【c】d", "好的。", "😀😀😀"
        )
        for (s in inputs) {
            val out = ReplyPrompt.parse(s)
            assertTrue("段数不该超过 n：$s", out.size <= 3)
            for (d in out) assertTrue("空段必须被丢弃：$s", d.text.isNotEmpty())
        }
    }

    // ---------- 提示词 ----------

    @Test
    fun styleGuideListsAllThreeStyles() {
        val g = ReplyPrompt.styleGuide()
        for (t in ReplyPrompt.STYLE_TITLES) assertTrue(g.contains(t))
    }

    @Test
    fun systemPromptCarriesMemoryAndHardRules() {
        val s = ReplyPrompt.buildSystem("关系：情侣；她讨厌被已读不回", "zh")
        assertTrue(s.contains("【记忆】"))
        assertTrue(s.contains("关系：情侣；她讨厌被已读不回"))
        assertTrue(s.contains("【稳妥】"))
        assertTrue(s.contains("---"))
        assertTrue(s.contains("不要承诺做不到的事"))
        assertTrue(s.contains("验证码"))

        // 没有记忆时也要有一段明确的占位，别让模型自己脑补关系
        assertTrue(ReplyPrompt.buildSystem("", "zh").contains("（暂无记忆）"))
        assertTrue(ReplyPrompt.buildSystem("   ", "zh").contains("（暂无记忆）"))

        val en = ReplyPrompt.buildSystem("relationship: partner", "en")
        assertTrue(en.contains("relationship: partner"))
        assertTrue(en.contains("Write the drafts in Chinese"))
    }

    @Test
    fun userPromptCarriesJevLinesAndState() {
        val state = Json.write(
            mapOf(
                "关系" to "同事",
                "会话类型" to "单聊",
                "待分析消息" to "方案好了吗"
            )
        )
        val u = ReplyPrompt.buildUser(
            listOf("意图：催促进度 88%", "着急：急 · 2.10/3", "建议：先问清细节 71%"),
            state,
            3
        )
        assertTrue(u.contains("意图：催促进度 88%"))
        assertTrue(u.contains("待分析消息"))
        assertTrue(u.contains("3 段"))

        val empty = ReplyPrompt.buildUser(emptyList(), "", 1)
        assertTrue(empty.contains("（无）"))
        assertTrue(empty.contains("1 段"))
    }
}

/**
 * ChatHttp 的参数自检。
 *
 * 这里不会真的联网：三个必填项只要有一个是空的，complete() 在开连接之前就返回了，
 * 所以放 JVM 单测里跑是安全的（真正发请求的部分留给手测/集成测）。
 */
class ChatHttpTest {

    @Test
    fun rejectsBlankArgumentsBeforeAnyNetworkCall() {
        val r1 = ChatHttp.complete("", "k", "m", "s", "u")
        assertTrue(r1 is ChatResult.Err)
        assertEquals("base_url 没填", (r1 as ChatResult.Err).message)

        val r2 = ChatHttp.complete("https://api.deepseek.com/v1", "", "m", "s", "u")
        assertTrue(r2 is ChatResult.Err)
        assertEquals("API Key 没填", (r2 as ChatResult.Err).message)

        val r3 = ChatHttp.test("https://api.deepseek.com/v1", "k", "   ")
        assertTrue(r3 is ChatResult.Err)
        assertEquals("模型名没填", (r3 as ChatResult.Err).message)
    }
}
