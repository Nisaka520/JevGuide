package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 提示词的"体检 + 导出"。
 *
 * 为什么要导出：提示词的质量只能在**真模型**上验证（格式守不守、口吻对不对），
 * 而 JVM 单测里不该打真网络。所以这里只做两件事：
 *   1. 断言提示词里必须有的硬约束（记忆块、姿态约束、三种风格、输出格式）；
 *   2. 把拼好的 system/user 落到 `app/build/prompt-dump.txt`，
 *      方便用 curl 拿去打真模型（见 README 的开发一节）。
 */
class PromptDumpTest {

    private val memory = Memories.contextBlock(
        ContactMemory(
            key = "小明", name = "小明", relation = "情侣",
            summary = "认识两个月，聊得比较自然，她养了只猫。",
            facts = listOf(MemFact("养了一只叫团子的猫", 0L), MemFact("下周三出差", 0L)),
            scores = listOf(ScorePoint(0L, 71, ""), ScorePoint(0L, 62, "")),
            turns = listOf(Turn(0L, false, "在干嘛呀"), Turn(0L, true, "刚下班，你呢")),
            updatedAt = 0L
        )
    )

    private val state = Json.write(
        mapOf(
            "关系" to "情侣", "对方性别" to "女", "会话类型" to "单聊",
            "最近对话" to listOf("我：刚下班，你呢", "对方：我也刚到家，团子今天拆家了"),
            "待分析消息" to "你明天有空吗？想让你陪我去看个展",
            "背景" to memory
        )
    )

    private val jevLines = listOf(
        "意图：邀约见面或请客 92%\n情绪：高兴 61% · 平静 30%",
        "着急：一般 · 1.20/3",
        "建议：建议肯定赞赏 78%",
        "攻略度：72%（↑ +10）"
    )

    @Test
    fun systemPromptCarriesMemoryStylesAndHardRules() {
        val s = ReplyPrompt.buildSystem(memory, "zh")
        assertTrue("要带上记忆", s.contains("团子"))
        assertEquals("「【记忆】」抬头只能出现一次", 1, Regex("【记忆】").findAll(s).count())
        assertTrue("要给出三种风格", ReplyPrompt.STYLE_TITLES.all { s.contains(it) })
        assertTrue("要约束格式", s.contains("---"))
        assertTrue("要约束不能乱承诺", s.contains("承诺") || s.contains("做不到"))
    }

    @Test
    fun userPromptCarriesJevVerdictAndConversation() {
        val u = ReplyPrompt.buildUser(jevLines, state, 3)
        assertTrue("要带上攻略度", u.contains("72%"))
        assertTrue("要带上建议姿态", u.contains("肯定赞赏"))
        assertTrue("要带上待回复消息", u.contains("看个展"))
        assertTrue("要说明要几条", u.contains("3"))
    }

    @Test
    fun dumpPromptsForRealModelCheck() {
        val dir = File("build").apply { mkdirs() }
        val f = File(dir, "prompt-dump.txt")
        f.writeText(
            "=== SYSTEM ===\n" + ReplyPrompt.buildSystem(memory, "zh") +
                "\n\n=== USER ===\n" + ReplyPrompt.buildUser(jevLines, state, 3) + "\n",
            Charsets.UTF_8
        )
        assertTrue(f.length() > 200)
    }
}
