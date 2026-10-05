package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆摘要压缩（MemoryUpdater）的纯逻辑部分：提示词拼装 + 宽容解析。
 * refresh() 那条网络链路不在这测 —— 它失败无害，靠真机日志兜底。
 */
class MemoryUpdaterTest {

    private fun mem(
        summary: String = "",
        facts: List<String> = emptyList(),
        turns: List<Turn> = emptyList(),
        relation: String = ""
    ) = ContactMemory(
        key = "张伟", name = "张伟", relation = relation, summary = summary,
        facts = facts.map { MemFact(it, 0L) }, scores = emptyList(), turns = turns, updatedAt = 0L
    )

    // ── buildSystem / buildUser ──

    @Test
    fun systemPromptTellsTheExactOutputShape() {
        val s = MemoryUpdater.buildSystem()
        assertTrue(s.contains("摘要："))
        assertTrue(s.contains("- "))
        assertTrue(s.contains("6"))          // MAX_FACTS 写进了要求里
        assertTrue(s.contains("暂无长期信息"))
    }

    @Test
    fun userPromptCarriesRelationSummaryFactsAndTurns() {
        val u = MemoryUpdater.buildUser(
            mem(
                summary = "上周约了看展",
                facts = listOf("养了只猫叫团子", "下周三出差"),
                turns = listOf(
                    Turn(1, false, "明天有空吗"),
                    Turn(2, true, "有啊怎么了"),
                    Turn(3, false, "想请你吃饭")
                ),
                relation = "普通朋友"
            )
        )
        assertTrue(u.contains("【关系】普通朋友"))
        assertTrue(u.contains("【已有摘要】上周约了看展"))
        assertTrue(u.contains("- 养了只猫叫团子"))
        assertTrue(u.contains("- 下周三出差"))
        assertTrue(u.contains("对方：明天有空吗"))
        assertTrue(u.contains("我：有啊怎么了"))
        assertTrue(u.contains("【最近的对话】"))
    }

    @Test
    fun userPromptFillsDefaultsWhenMemoryIsEmpty() {
        val u = MemoryUpdater.buildUser(mem())
        assertTrue(u.contains("【关系】普通朋友"))   // 关系为空时给默认值
        assertTrue(!u.contains("【已有摘要】"))
        assertTrue(!u.contains("【已有事实】"))
    }

    @Test
    fun userPromptCapsTurnsAt30() {
        val turns = (1..40).map { Turn(it.toLong(), false, "m$it") }
        val u = MemoryUpdater.buildUser(mem(turns = turns))
        assertTrue(u.contains("m40"))
        assertTrue(!u.contains("m10\n"))     // 只喂最后 30 条，最早的被截掉
        assertTrue(u.contains("m11"))
    }

    // ── parse：标准形态 ──

    @Test
    fun parsesCanonicalShape() {
        val (summary, facts) = MemoryUpdater.parse(
            "摘要：两人关系稳定，最近在约周末看展。\n" +
                "- 养了只猫叫团子\n" +
                "- 下周三出差\n"
        )
        assertEquals("两人关系稳定，最近在约周末看展。", summary)
        assertEquals(listOf("养了只猫叫团子", "下周三出差"), facts)
    }

    @Test
    fun parsesHalfWidthColonAndBulletVariants() {
        val (summary, facts) = MemoryUpdater.parse(
            "摘要: 关系升温\n" +
                "• 喜欢喝美式\n" +
                "· 每周五加班\n" +
                "* 对花粉过敏\n" +
                "1. 下月搬家\n"
        )
        assertEquals("关系升温", summary)
        assertEquals(listOf("喜欢喝美式", "每周五加班", "对花粉过敏", "下月搬家"), facts)
    }

    @Test
    fun stripsCodeFencesAndQuotes() {
        val (summary, facts) = MemoryUpdater.parse(
            "```text\n摘要：「在追求阶段，约过两次饭」\n- \"怕狗\"\n```"
        )
        assertEquals("在追求阶段，约过两次饭", summary)
        assertEquals(listOf("怕狗"), facts)
    }

    @Test
    fun englishSummaryHeadIsAccepted() {
        val (summary, _) = MemoryUpdater.parse("Summary: early stage\n- likes hiking")
        assertEquals("early stage", summary)
        assertEquals(listOf("likes hiking"), MemoryUpdater.parse("Summary: early stage\n- likes hiking").second)
    }

    // ── parse：宽容与兜底 ──

    @Test
    fun plainProseWithoutHeadBecomesSummary() {
        val (summary, facts) = MemoryUpdater.parse("最近聊得挺多，约了周末吃饭。")
        assertEquals("最近聊得挺多，约了周末吃饭。", summary)
        assertTrue(facts.isEmpty())
    }

    @Test
    fun summaryLineAfterFactsIsDroppedAsNoise() {
        // 已经在列事实了，后面突然来的散文行不再并进摘要（避免摘要被模型跑题污染）
        val (summary, facts) = MemoryUpdater.parse(
            "摘要：关系平稳\n- 养猫\n这句话是多余的散文。"
        )
        assertEquals("关系平稳", summary)
        assertEquals(listOf("养猫"), facts)
    }

    @Test
    fun duplicateFactsAreKeptOnce() {
        val (_, facts) = MemoryUpdater.parse("摘要：x\n- 养猫\n- 养猫\n- 养狗")
        assertEquals(listOf("养猫", "养狗"), facts)
    }

    @Test
    fun emptyAndGarbageNeverThrow() {
        for (raw in listOf("", "\n\n", "```", "摘要：", "- ", "😊😊", "a", "摘要：\n- 养猫")) {
            val (summary, facts) = MemoryUpdater.parse(raw)
            assertTrue(summary.length <= 600)
            assertTrue(facts.size <= MemoryUpdaterTestMaxFacts)
        }
        assertEquals("" to emptyList<String>(), MemoryUpdater.parse(""))
    }

    @Test
    fun overlongSummaryIsClamped() {
        val long = "长".repeat(2000)
        val (summary, _) = MemoryUpdater.parse("摘要：$long")
        assertEquals(600, summary.length)
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun factsAreCappedAtSix() {
        val bullets = (1..10).joinToString("\n") { "- 事实$it" }
        val (_, facts) = MemoryUpdater.parse("摘要：x\n$bullets")
        assertEquals(6, facts.size)
    }
}

private const val MemoryUpdaterTestMaxFacts = 6
