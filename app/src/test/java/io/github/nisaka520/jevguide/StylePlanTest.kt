package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只选一两套风格」的**实测回归**。
 *
 * 背景（真模型实测发现的硬伤）：只选一套风格时，system 里按风格数写「只输出 1 段」、
 * user 里按 draftsN 写「输出 3 段」，两句话直接打架 —— 模型照样输出三条，
 * 然后自己发明标题来区分：【正常】【热情】【爽快】，甚至把关系名【情侣】当标题。
 * 自创标题会显示成一个错的名字，漏进正文时还会被用户原样发给对方。
 *
 * 所以这里钉死两件事：
 *  1. 条数与标题必须来自同一个来源（[ReplyPrompt.planTitles]），system / user 不再各说各的；
 *  2. 解析器不能因为"标题不在白名单里"就把标题行当正文吞下去。
 */
class StylePlanTest {

    // ---------- 条数与标题同源 ----------

    @Test
    fun singleStyleRepeatsItsTitleForEveryDraft() {
        assertEquals(listOf("撒娇", "撒娇", "撒娇"), ReplyPrompt.planTitles(listOf("撒娇"), 3))
    }

    @Test
    fun fewerDraftsThanStylesTakesTheFirstOnes() {
        assertEquals(listOf("稳妥", "推进"), ReplyPrompt.planTitles(listOf("稳妥", "推进", "有趣"), 2))
    }

    @Test
    fun moreDraftsThanStylesRotates() {
        assertEquals(
            listOf("冷淡", "正经", "冷淡", "正经", "冷淡"),
            ReplyPrompt.planTitles(listOf("冷淡", "正经"), 5)
        )
    }

    @Test
    fun zeroCountMeansOnePerStyle() {
        assertEquals(3, ReplyPrompt.planTitles(listOf("稳妥", "推进", "有趣"), 0).size)
        assertEquals(1, ReplyPrompt.planTitles(listOf("长辈"), 0).size)
        assertEquals(1, ReplyPrompt.planTitles(listOf("长辈"), -5).size)
    }

    @Test
    fun junkStylesFallBackToTheDefaultThree() {
        assertEquals(listOf("稳妥", "推进", "有趣"), ReplyPrompt.planTitles(listOf("不存在", "  "), 0))
        assertEquals(listOf("稳妥"), ReplyPrompt.planTitles(emptyList(), 1))
    }

    @Test
    fun systemAndUserAgreeOnHowManyDrafts() {
        val s = ReplyPrompt.buildSystem("（暂无记忆）", "zh", "", listOf("撒娇"), 3)
        val u = ReplyPrompt.buildUser(emptyList(), "{}", 3)
        assertTrue("system 要说 3 段", s.contains("只输出 3 段"))
        assertFalse("不能再出现「只输出 1 段」这种和 user 打架的说法", s.contains("只输出 1 段"))
        assertTrue("三条标题都得逐行钉死", Regex("【撒娇】").findAll(s).count() >= 3)
        assertTrue("user 也要说 3 段", u.contains("输出 3 段"))
    }

    @Test
    fun systemPromptForbidsInventedTitles() {
        val s = ReplyPrompt.buildSystem("（暂无记忆）", "zh", "", listOf("冷淡"), 2)
        assertTrue("要点名禁止自创标题", s.contains("不许自创"))
        assertTrue("要禁止拿关系当标题", s.contains("当标题"))
        assertTrue("要说明同一套风格重复时怎么写", s.contains("重复写"))
    }

    @Test
    fun englishPromptAlsoListsEveryPickedStyle() {
        val s = ReplyPrompt.buildSystem("relationship: partner", "en", "", listOf("冷淡", "长辈"), 3)
        assertTrue("英文路径也要认新风格", s.contains("冷淡") && s.contains("长辈"))
        assertTrue("英文路径也要说 3 段", s.contains("exactly 3 sections"))
        assertTrue("英文路径也要禁止自创标题", s.contains("never invent"))
    }

    /**
     * 上限「同时只能选三套」不能只靠界面拦：配置是纯文本，旧版本或手改都可能塞进来五套。
     * 这里钉的是最后一层 —— 就算真塞进来五套，提示词里也只能出现三套。
     */
    @Test
    fun atMostThreeStylesReachThePrompt() {
        assertEquals(3, ReplyPrompt.MAX_PICK)
        val five = listOf("稳妥", "推进", "有趣", "撒娇", "冷淡")
        assertEquals(listOf("稳妥", "推进", "有趣"), ReplyPrompt.planTitles(five, 0))
        val s = ReplyPrompt.buildSystem("（暂无记忆）", "zh", "", five, 3)
        assertTrue("提示词里不该出现被截掉的风格", !s.contains("【撒娇】"))
        assertTrue("条数仍按 draftsN 说", s.contains("只输出 3 段"))
    }

    // ---------- 解析器：自创标题不能漏进正文 ----------

    @Test
    fun inventedTitleIsTakenAsTitleNotAsBodyText() {
        val raw = "【热情】\n明天可以，几点\n---\n【正常】\n有空呀，几点？"
        val d = ReplyPrompt.parse(raw, 3)
        assertEquals(2, d.size)
        assertEquals(listOf("热情", "正常"), d.map { it.title })
        assertFalse("标题不能漏进正文", d.any { it.text.contains("【") })
    }

    @Test
    fun relationshipNameAsTitleDoesNotLeakIntoBody() {
        val d = ReplyPrompt.parse("【情侣】\n有空呀，看什么展，几点？", 1)
        assertEquals(1, d.size)
        assertEquals("有空呀，看什么展，几点？", d[0].text)
    }

    @Test
    fun bracketInsideASentenceIsNotATitle() {
        // 顶格才算标题：句子中间的【】不能被当成标题，否则前半句会被直接丢掉
        val d = ReplyPrompt.parse("明天【有空】吗", 1)
        assertEquals(1, d.size)
        assertEquals("明天【有空】吗", d[0].text)
    }

    @Test
    fun longBracketAtLineStartIsStillBodyText() {
        val d = ReplyPrompt.parse("【明天下午三点美术馆门口】见", 1)
        assertEquals(1, d.size)
        assertEquals("【明天下午三点美术馆门口】见", d[0].text)
    }

    @Test
    fun boldWrappedTitleIsStillRecognised() {
        val d = ReplyPrompt.parse("**【推进】**\n我十分钟后给你结果。", 1)
        assertEquals(1, d.size)
        assertEquals("推进", d[0].title)
        assertEquals("我十分钟后给你结果。", d[0].text)
    }

    // ---------- 真模型实测原样贴回来（glm-5.3-flash，reasoning_effort=low） ----------

    /**
     * 下面五段是**真模型实际吐出来的原文**，不是编的。实测里三种排版都出现过：
     * 标题独占一行 / 标题和正文同一行 / 空行里夹着 --- 。
     * 用真输出当回归用例，比手写的"标准格式"更能挡住真实世界的花样。
     */
    @Test
    fun realModelOutputParsesCleanlyForEveryStyle() {
        val samples = mapOf(
            "撒娇" to "【撒娇】有空呀，正好想跟你出去逛逛，看完得请我喝奶茶哦\n---\n" +
                "【撒娇】有空的嘛，不过你来了团子在家没人陪它拆家了怎么办呀\n---\n" +
                "【撒娇】明天下班我都在，不过去之前先说好，我走累了你可要背我哦",
            "冷淡" to "【冷淡】\n有啊，明天几点\n\n---\n\n【冷淡】\n嗯可以，展在哪\n\n---\n\n【冷淡】\n行，明天团子又拆家了？",
            "推进" to "【推进】\n明天可以呀，下午两点展馆门口见？\n\n【推进】\n有空！几点开场，我去接你\n\n" +
                "【推进】\n好呀，正好你出差前陪我逛逛，下午走？",
            "长辈" to "【长辈】\n不太忙，您放心，周末我回去喝汤\n\n【长辈】\n妈，还行，不算太累，汤我惦记着呢，周末回\n\n" +
                "【长辈】\n不忙不忙，这周就忙那两天，周日回去吃饭",
            "有趣" to "【有趣】\n有空，正好出来躲躲团子拆家现场\n\n【有趣】\n行啊，看完展回家你负责收拾团子的残局\n\n" +
                "【有趣】\n必须有空，谁让你家的猫先动的手"
        )
        for ((style, raw) in samples) {
            val d = ReplyPrompt.parse(raw, 3)
            assertEquals("【" + style + "】该解析出 3 条", 3, d.size)
            assertEquals("【" + style + "】三条标题都该是风格名", List(3) { style }, d.map { it.title })
            for (x in d) {
                assertTrue("【" + style + "】正文不能是空的", x.text.isNotEmpty())
                assertFalse(
                    "【" + style + "】正文里不能残留标题或分隔线：" + x.text,
                    x.text.contains("【") || x.text.contains("---")
                )
            }
        }
    }
}
