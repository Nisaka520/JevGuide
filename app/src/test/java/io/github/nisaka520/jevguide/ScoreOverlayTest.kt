package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常驻浮条的纯逻辑单测：写什么字、什么颜色。
 *
 * 浮条是"一直挂在屏幕上"的东西，文案写错会一直错在眼前 —— 所以这几种组合都钉住。
 */
class ScoreOverlayTest {

    @Test
    fun noScoreYetInvitesATap() {
        assertEquals("小明 · 点我判读", ScoreOverlay.format("小明", null, null))
        assertEquals("微信 · 点我判读", ScoreOverlay.format("", null, null))
        assertEquals("微信 · 点我判读", ScoreOverlay.format("   ", null, null))
    }

    @Test
    fun firstScoreHasNoTrend() {
        assertEquals("小明 · 攻略度 72%", ScoreOverlay.format("小明", 72, null))
    }

    @Test
    fun trendArrowsAreHumanReadable() {
        assertEquals("小明 · 攻略度 72% ↑8", ScoreOverlay.format("小明", 72, 8))
        assertEquals("小明 · 攻略度 72% ↓5", ScoreOverlay.format("小明", 72, -5))
        assertEquals("小明 · 攻略度 72% →", ScoreOverlay.format("小明", 72, 0))
    }

    @Test
    fun colorsFollowTheSameBandsAsTheResultPage() {
        assertEquals(0xFF7EE0A8.toInt(), ScoreOverlay.colorOf(70))
        assertEquals(0xFF7EE0A8.toInt(), ScoreOverlay.colorOf(100))
        assertEquals(0xFFFFD54F.toInt(), ScoreOverlay.colorOf(40))
        assertEquals(0xFFFFD54F.toInt(), ScoreOverlay.colorOf(69))
        assertEquals(0xFFE57373.toInt(), ScoreOverlay.colorOf(0))
        assertEquals(0xFFE57373.toInt(), ScoreOverlay.colorOf(39))
        assertEquals(0xFF9AA8BB.toInt(), ScoreOverlay.colorOf(null))
    }

    @Test
    fun titleIsTrimmedButKeptVerbatim() {
        val s = ScoreOverlay.format("  项目组 (8) ", 55, 3)
        assertTrue(s.startsWith("项目组 (8) · 攻略度 55% ↑3"))
    }
}
