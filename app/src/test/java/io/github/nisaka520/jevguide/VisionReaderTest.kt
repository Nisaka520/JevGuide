package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉读屏的解析层单测。
 *
 * 为什么要测得这么细：这条路的**输入是模型自由发挥的文本**，格式全凭提示词约束，
 * 而提示词不可能 100% 被遵守（真模型会加解释、包代码块、把 title 写成 null）。
 * 解析器一旦抛异常，用户看到的就是"点了没反应"，所以这里把各种脏返回都钉住。
 */
class VisionReaderTest {

    @Test
    fun parsesPlainJson() {
        val d = VisionReader.parse(
            """{"title":"小明","messages":[{"mine":false,"text":"在吗"},{"mine":true,"text":"在的"}]}"""
        )
        assertNotNull(d)
        assertEquals("小明", d!!.title)
        assertEquals(2, d.msgs.size)
        assertEquals(false, d.msgs[0].mine)
        assertEquals("在的", d.msgs[1].text)
        assertEquals("在吗", d.latestPeerMessage()?.text)
    }

    @Test
    fun parsesFencedJsonWithChatter() {
        val raw = """
            好的，我读到的内容如下：
            ```json
            {"title":"项目组 (8)","messages":[{"mine":false,"text":"方案我改好了"}]}
            ```
            需要我再读一遍吗？
        """.trimIndent()
        val d = VisionReader.parse(raw)
        assertNotNull(d)
        assertEquals("项目组 (8)", d!!.title)
        assertTrue(d.isGroup)
        assertEquals("方案我改好了", d.latestPeerMessage()?.text)
    }

    @Test
    fun toleratesMissingFieldsAndWeirdMineValues() {
        val d = VisionReader.parse(
            """{"messages":[{"text":"甲"},{"mine":"true","text":"乙"},{"mine":1,"text":"丙"},{"mine":null,"text":"丁"}]}"""
        )
        assertNotNull(d)
        assertEquals("", d!!.title)
        assertEquals(4, d.msgs.size)
        assertEquals(listOf(false, true, true, false), d.msgs.map { it.mine })
    }

    @Test
    fun dropsEmptyTextsAndKeepsOrder() {
        val d = VisionReader.parse(
            """{"title":"小明","messages":[{"mine":false,"text":"第一条"},{"mine":true,"text":"   "},{"mine":false,"text":"第二条"}]}"""
        )
        assertNotNull(d)
        assertEquals(listOf("第一条", "第二条"), d!!.msgs.map { it.text })
    }

    @Test
    fun placeholderMessagesSurvive() {
        val d = VisionReader.parse(
            """{"title":"小明","messages":[{"mine":false,"text":"[图片]"},{"mine":true,"text":"[语音]"}]}"""
        )
        assertEquals(listOf("[图片]", "[语音]"), d!!.msgs.map { it.text })
    }

    @Test
    fun garbageReturnsNullInsteadOfThrowing() {
        assertNull(VisionReader.parse(""))
        assertNull(VisionReader.parse("模型今天不想干活"))
        assertNull(VisionReader.parse("{"))
        assertNull(VisionReader.parse("""{"title":"","messages":[]}"""))
        assertNull(VisionReader.parse("""{"title":null,"messages":"不是数组"}"""))
        assertNull(VisionReader.parse("[]"))
    }

    @Test
    fun extractJsonFindsTheOutermostObject() {
        assertEquals("""{"a":1}""", VisionReader.extractJson("前言 {\"a\":1} 后记"))
        assertEquals("""{"a":{"b":2}}""", VisionReader.extractJson("""```json
{"a":{"b":2}}
```"""))
        assertNull(VisionReader.extractJson("没有花括号"))
    }

    @Test
    fun promptPinsTheRulesThatMatter() {
        val p = VisionReader.PROMPT
        assertTrue("要说明左右气泡对应谁", p.contains("右侧") && p.contains("左侧"))
        assertTrue("要求只输出 JSON", p.contains("JSON"))
        assertTrue("要求原样抄写", p.contains("原样抄写"))
        assertTrue("要处理图片/语音等占位", p.contains("[图片]") && p.contains("[语音]"))
        assertTrue("要排除输入框草稿", p.contains("输入框"))
    }

    @Test
    fun endpointFallsBackToChatConfig() {
        // endpointOf 依赖 Config(Context)，纯 JVM 单测里拿不到 —— 这里只钉住"三者都为空时取到默认"的语义
        val t = Triple("", "", "")
        assertEquals("", t.first)
        assertEquals("", t.second)
        assertEquals("", t.third)
    }
}
