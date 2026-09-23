package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点选器的两条边界：选满三套不能再加、最后一套不能点掉。
 * 拒绝时必须**一点状态都不改**（返回 Refused 而不是"悄悄改掉一套"）。
 */
class StylePickTest {

    private val three = listOf("稳妥", "推进", "有趣")

    @Test
    fun tappingAFourthStyleIsRefusedAndChangesNothing() {
        val r = StylePick.toggle(three, "撒娇")
        assertTrue("应该是拒绝", r is StylePick.Refused)
        assertEquals("最多同时选 3 套：先点掉一套再选新的", (r as StylePick.Refused).reason)
    }

    @Test
    fun theLastOneCannotBeRemoved() {
        val r = StylePick.toggle(listOf("长辈"), "长辈")
        assertTrue(r is StylePick.Refused)
        assertEquals("至少要留一套风格", (r as StylePick.Refused).reason)
    }

    @Test
    fun removingAndAddingWorksAndKeepsOrder() {
        val afterRemove = StylePick.toggle(three, "推进") as StylePick.Changed
        assertEquals(listOf("稳妥", "有趣"), afterRemove.picked)

        val afterAdd = StylePick.toggle(afterRemove.picked, "撒娇") as StylePick.Changed
        assertEquals(listOf("稳妥", "有趣", "撒娇"), afterAdd.picked)
    }

    @Test
    fun addingToFewerThanMaxIsFine() {
        val r = StylePick.toggle(listOf("稳妥", "冷淡"), "长辈") as StylePick.Changed
        assertEquals(listOf("稳妥", "冷淡", "长辈"), r.picked)
    }

    @Test
    fun unknownNameIsRefused() {
        assertTrue(StylePick.toggle(three, "幽默") is StylePick.Refused)
        assertTrue(StylePick.toggle(three, "") is StylePick.Refused)
    }

    @Test
    fun everyStyleCanBePickedAndUnpickedAtTheEdges() {
        // 七套逐个当"唯一那套"试一遍：点不掉的是它自己，其它六套都点得进来
        for (only in ReplyPrompt.ALL_STYLE_TITLES) {
            assertTrue("最后一套不该能点掉：" + only, StylePick.toggle(listOf(only), only) is StylePick.Refused)
            for (other in ReplyPrompt.ALL_STYLE_TITLES) {
                if (other == only) continue
                val r = StylePick.toggle(listOf(only), other)
                assertTrue("两套应该能同时选中：" + only + "+" + other, r is StylePick.Changed)
            }
        }
    }
}
