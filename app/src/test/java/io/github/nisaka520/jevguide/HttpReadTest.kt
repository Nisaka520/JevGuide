package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [HttpRead] 是"响应体不许无上限读"这条约束的落点。
 *
 * 这些用例全部离线：不联网、不碰设备。
 */
class HttpReadTest {

    private fun stream(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

    @Test
    fun readsTheWholeBodyWhenItIsShort() {
        assertEquals("""{"a":1}""", HttpRead.text(stream("""{"a":1}""")))
        assertEquals("", HttpRead.text(stream("")))
    }

    @Test
    fun keepsMultiByteCharactersIntact() {
        // 按字符（不是字节）截断，中文与 emoji 都不会被切一半
        val s = "对方：在吗？我：在的，刚忙完 🙂"
        assertEquals(s, HttpRead.text(stream(s)))
    }

    @Test
    fun truncatesInsteadOfLoadingEverything() {
        val huge = "x".repeat(HttpRead.MAX_CHARS * 3)
        val got = HttpRead.text(stream(huge))
        assertEquals(HttpRead.MAX_CHARS, got.length)
        assertTrue(got.all { it == 'x' })
    }

    @Test
    fun respectsAnExplicitSmallerCap() {
        assertEquals("abc", HttpRead.text(stream("abcdef"), 3))
        assertEquals(0, HttpRead.text(stream("abcdef"), 0).length)
    }
}
