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
        // 阶段三起 colorOf 转发 Design.scoreColor 的唯一口径（阈值不变：≥70 好 / ≥40 中 / 其余差，
        // null=没判），固定用 Design 的夜间提亮档（浮条叠在任意内容上，亮档深浅底都读得清）。
        // 这里钉住转发后的具体色值：改色必须是有意的设计决定（改 Design.kt + 改这里），不许顺手漂移。
        assertEquals(0xFF3FD68F.toInt(), ScoreOverlay.colorOf(70))
        assertEquals(0xFF3FD68F.toInt(), ScoreOverlay.colorOf(100))
        assertEquals(0xFFF2C14E.toInt(), ScoreOverlay.colorOf(40))
        assertEquals(0xFFF2C14E.toInt(), ScoreOverlay.colorOf(69))
        assertEquals(0xFFEC7A70.toInt(), ScoreOverlay.colorOf(0))
        assertEquals(0xFFEC7A70.toInt(), ScoreOverlay.colorOf(39))
        assertEquals(0xFF8D99A6.toInt(), ScoreOverlay.colorOf(null))
    }

    @Test
    fun titleIsTrimmedButKeptVerbatim() {
        val s = ScoreOverlay.format("  项目组 (8) ", 55, 3)
        assertTrue(s.startsWith("项目组 (8) · 攻略度 55% ↑3"))
    }
}
