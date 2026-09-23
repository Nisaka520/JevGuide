package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置页说明文字里的 `**强调**` 要变成真加粗，而不是把星号露给用户看。
 *
 * 这里只钉解析（正文 + 加粗区间）；把区间套成 StyleSpan 那一层是 Activity 里的三行胶水。
 */
class EmphasisTest {

    @Test
    fun pairedMarkersAreRemovedAndReported() {
        val (text, bolds) = Emphasis.parse("a**b**c")
        assertEquals("abc", text)
        assertEquals(listOf(1..1), bolds)
    }

    @Test
    fun markersAtTheVeryEdges() {
        val (text, bolds) = Emphasis.parse("**a**")
        assertEquals("a", text)
        assertEquals(listOf(0..0), bolds)
    }

    @Test
    fun severalPairsInOneLine() {
        // "x**yy**z**w**" → 正文 xyyzw：yy 落在 1..2，w 落在 4..4
        val (text, bolds) = Emphasis.parse("x**yy**z**w**")
        assertEquals("xyyzw", text)
        assertEquals(listOf(1..2, 4..4), bolds)
    }

    @Test
    fun unbalancedMarkersAreKeptVerbatim() {
        // 只有开头一对、后面落单：落单的那对原样留着，不能吞字符
        assertEquals("a**b" to emptyList<IntRange>(), Emphasis.parse("a**b"))
        assertEquals("**a" to emptyList<IntRange>(), Emphasis.parse("**a"))
        // 前两个星号是成对的（会被去掉），最后落单的一对留着
        assertEquals("abc**" to listOf(1..1), Emphasis.parse("a**b**c**"))
    }

    @Test
    fun emptyEmphasisIsKeptVerbatim() {
        assertEquals("****" to emptyList<IntRange>(), Emphasis.parse("****"))
    }

    @Test
    fun plainTextPassesThrough() {
        assertEquals("没有星号" to emptyList<IntRange>(), Emphasis.parse("没有星号"))
        assertEquals("" to emptyList<IntRange>(), Emphasis.parse(""))
        assertEquals("一个*星号" to emptyList<IntRange>(), Emphasis.parse("一个*星号"))
    }

    @Test
    fun realSettingsStringsLoseTheirMarkers() {
        val (text, bolds) = Emphasis.parse(
            "⚠ 但你现在保存的地址（http://x）**不属于任何内置预设** —— 换一家要重新填密钥"
        )
        assertEquals("⚠ 但你现在保存的地址（http://x）不属于任何内置预设 —— 换一家要重新填密钥", text)
        assertEquals(1, bolds.size)
        assertEquals("不属于任何内置预设", text.substring(bolds[0].first, bolds[0].last + 1))
    }
}
