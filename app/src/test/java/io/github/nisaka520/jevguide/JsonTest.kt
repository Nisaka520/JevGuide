package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun parsesNestedStructures() {
        val o = Json.obj(Json.parse("""{"a":{"b":[1,2,{"c":"d"}]},"e":true,"f":null}"""))
        assertEquals("d", Json.str(Json.at(o, "a.b.2.c")))
        assertEquals(1.0, Json.num(Json.at(o, "a.b.0"))!!, 1e-9)
        assertEquals("true", Json.str(o["e"]))
        assertNull(o["f"])
        assertEquals(3, Json.list(Json.at(o, "a.b")).size)
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val o = Json.obj(Json.parse("""{"s":"a\"b\\c\nd\u4e2d\u6587"}"""))
        assertEquals("a\"b\\c\nd中文", Json.str(o["s"]))
    }

    @Test
    fun deepNestingDoesNotBlowTheStack() {
        // 畸形/恶意输入能构造出任意深的嵌套，而 StackOverflowError 是 Error ——
        // 上层 catch(Exception) 接不住，只能解析器自己停住（见 Json 里那个 maxDepth）
        Json.parse("[".repeat(100_000))   // 不抛、不爆栈就算过
        // 正常深度完全不受影响
        val o = Json.obj(Json.parse("""{"a":{"b":{"c":[1,{"d":"e"}]}}}"""))
        assertEquals("e", Json.str(Json.at(o, "a.b.c.1.d")))
    }

    @Test
    fun missingPathReturnsNull() {
        val o = Json.obj(Json.parse("""{"a":1}"""))
        assertNull(Json.at(o, "a.b.c"))
        assertNull(Json.at(o, "zzz"))
    }

    @Test
    fun quoteEscapesWhatMustBeEscaped() {
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"))
        assertEquals("\"line\\nbreak\"", Json.quote("line\nbreak"))
        assertTrue(Json.quote("中文").startsWith("\"中文"))
    }

    @Test
    fun writeRoundTrips() {
        val src = mapOf(
            "关系" to "情侣",
            "背景" to "生日三月",
            "最近对话" to listOf("我：在", "对方：在吗"),
            "空" to null,
            "数" to 0.5
        )
        val text = Json.write(src)
        val back = Json.obj(Json.parse(text))
        assertEquals("情侣", Json.str(back["关系"]))
        assertEquals(2, Json.list(back["最近对话"]).size)
        assertEquals(0.5, Json.num(back["数"])!!, 1e-9)
    }

    @Test
    fun parsesRealisticAnswersShape() {
        val body = """
            {"answers":{"intent":{"type":"choice","choice":"打招呼或闲聊",
            "probabilities":{"打招呼或闲聊":0.85,"其他":0.15}},
            "urgency":{"type":"score","score":0.48},
            "need_reply":{"type":"noul","noul":0.65}}}
        """.trimIndent()
        val answers = Json.obj(Json.at(Json.parse(body), "answers"))
        assertEquals("打招呼或闲聊", Json.str(Json.at(answers["intent"], "choice")))
        assertEquals(0.85, Json.num(Json.at(answers["intent"], "probabilities.打招呼或闲聊"))!!, 1e-9)
        assertEquals(0.48, Json.num(Json.at(answers["urgency"], "score"))!!, 1e-9)
        assertEquals(0.65, Json.num(Json.at(answers["need_reply"], "noul"))!!, 1e-9)
    }
}
