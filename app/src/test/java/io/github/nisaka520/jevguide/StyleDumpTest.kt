package io.github.nisaka520.jevguide

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 把**每一套风格**的 system/user 提示词分别导出到 `app/build/style-dump/N.txt`。
 *
 * 为什么要单独导出：提示词质量只能在**真模型**上验证，而 JVM 单测里不该打真网络。
 * 所以这里只负责"每套风格单独拼一份、落到磁盘"，然后用 curl 拿去逐套实测
 * （见 README 的开发一节）。index.txt 是编号 → 风格名的对照表。
 */
class StyleDumpTest {

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
    fun dumpsEveryStyleForRealModelCheck() {
        val dir = File("build/style-dump").apply { mkdirs() }
        ReplyPrompt.ALL_STYLE_TITLES.forEachIndexed { i, title ->
            val text = "STYLE: " + title + "\n" +
                // count 固定给 3：实测要复现的是「只选一套风格 + 默认要 3 条」这个组合
                "=== SYSTEM ===\n" + ReplyPrompt.buildSystem(memory, "zh", "", listOf(title), 3) +
                "\n\n=== USER ===\n" + ReplyPrompt.buildUser(jevLines, state, 3) + "\n"
            val f = File(dir, (i + 1).toString() + ".txt")
            f.writeText(text, Charsets.UTF_8)
            assertTrue("【" + title + "】的提示词太短，八成没拼进去", f.length() > 200)
        }
        File(dir, "index.txt").writeText(ReplyPrompt.ALL_STYLE_TITLES.joinToString("\n"), Charsets.UTF_8)
    }

    /**
     * 【长辈】这一套只有在"对方确实是长辈"时才谈得上对错。
     * 上面那份夹具的关系是「情侣」，拿它测长辈等于让模型一边被要求用长辈口气、
     * 一边被告知对方是对象 —— 实测出来的就是普通情侣口气，属于夹具自己矛盾。
     * 所以单独再导一份"跟妈妈聊天"的场景。
     */
    @Test
    fun dumpsElderScenarioForRealModelCheck() {
        val elderState = Json.write(
            mapOf(
                "关系" to "母亲", "对方性别" to "女", "会话类型" to "单聊",
                "最近对话" to listOf("我：这周有点忙", "对方：再忙也要好好吃饭啊"),
                "待分析消息" to "最近忙不忙？周末回来吃饭吗，我炖了汤",
                "背景" to "（暂无记忆）"
            )
        )
        val elderLines = listOf(
            "意图：关心起居 / 邀约回家 88%\n情绪：关切 70% · 平静 25%",
            "建议：建议温和回应 74%",
            "攻略度：66%（↑ +3）"
        )
        val dir = File("build/style-dump").apply { mkdirs() }
        val f = File(dir, "elder.txt")
        f.writeText(
            "STYLE: 长辈\n" +
                "=== SYSTEM ===\n" + ReplyPrompt.buildSystem("（暂无记忆）", "zh", "", listOf("长辈"), 3) +
                "\n\n=== USER ===\n" + ReplyPrompt.buildUser(elderLines, elderState, 3) + "\n",
            Charsets.UTF_8
        )
        assertTrue(f.length() > 200)
    }
}
