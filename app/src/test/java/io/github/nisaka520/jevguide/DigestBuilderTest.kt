package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestBuilderTest {

    private val W = 1080
    private val H = 2400
    private val 同事 = Contact(listOf("张伟", "伟哥"), "同事", "男", "市场部")

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = RawLine(text, l, t, r, b)

    /** 一个典型的微信单聊屏：标题 + 时间戳 + 对方 + 我 + 对方 + 输入框 */
    private val screen = listOf(
        line("张伟", 40, 60, 260, 130),
        line("上午 10:23", 470, 340, 610, 380),
        line("这个报表周五要交", 40, 420, 560, 500),
        line("好的", 700, 540, 1040, 620),
        line("你那边进度怎么样了？", 40, 660, 700, 740),
        line("发送", 40, 2250, 200, 2320)
    )

    @Test
    fun picksTitleAndSplitsMineFromPeer() {
        val d = DigestBuilder.build(screen, W, H)
        assertEquals("张伟", d.title)
        assertEquals(3, d.msgs.size)
        assertFalse(d.msgs[0].mine)
        assertTrue(d.msgs[1].mine)
        assertFalse(d.msgs[2].mine)
        assertEquals("你那边进度怎么样了？", d.latestPeerMessage()!!.text)
    }

    @Test
    fun noiseAndInputAreaAreDropped() {
        val d = DigestBuilder.build(
            screen + listOf(
                line("上午 10:24", 470, 760, 610, 800),
                line("对方撤回了一条消息", 470, 820, 610, 860),
                line("。。。", 40, 880, 200, 920),
                line("😂😂", 40, 940, 200, 980),
                line("输入框占位", 40, 2100, 400, 2150)
            ),
            W, H
        )
        assertFalse(d.msgs.any { it.text.contains("撤回") })
        assertFalse(d.msgs.any { it.text.startsWith("。。") })
        assertFalse(d.msgs.any { it.text.contains("😂") })
        assertEquals(3, d.msgs.size)
    }

    @Test
    fun consecutiveDuplicateBubblesAreMerged() {
        val d = DigestBuilder.build(
            listOf(
                line("张伟", 40, 60, 260, 130),
                line("在吗？", 40, 420, 400, 480),
                line("在吗？", 40, 420, 400, 480),
                line("在吗？", 40, 500, 400, 560)
            ),
            W, H
        )
        assertEquals(2, d.msgs.size)
    }

    @Test
    fun quoteLinkingOnlyWhenEnabled() {
        val rows = listOf(
            line("张伟", 40, 60, 260, 130),
            line("这个报表周五要交", 40, 420, 560, 460),
            line("你那边进度怎么样了？", 40, 462, 560, 520)
        )
        val off = DigestBuilder.build(rows, W, H, linkQuotes = false)
        assertEquals(2, off.msgs.size)

        val on = DigestBuilder.build(rows, W, H, linkQuotes = true)
        assertEquals(1, on.msgs.size)
        assertEquals("这个报表周五要交", on.msgs[0].quoted)
        assertEquals("你那边进度怎么样了？", on.msgs[0].text)
    }

    @Test
    fun groupTitlesAreDetected() {
        val d = DigestBuilder.build(
            listOf(line("项目组 (8)", 40, 60, 400, 130), line("明天开会", 40, 420, 400, 500)),
            W, H
        )
        assertTrue(d.isGroup)
        assertEquals("项目组 (8)", d.title)
    }

    @Test
    fun stateUsesTheSameKeysAsThePlugin() {
        val d = DigestBuilder.build(screen, W, H)
        val state = Json.obj(Json.parse(d.state(同事, maxCtx = 3)))
        assertEquals("同事", Json.str(state["关系"]))
        assertEquals("男", Json.str(state["对方性别"]))
        assertEquals("单聊", Json.str(state["会话类型"]))
        assertEquals("市场部", Json.str(state["背景"]))
        assertEquals("你那边进度怎么样了？", Json.str(state["待分析消息"]))
        val ctx = Json.list(state["最近对话"]).map { Json.str(it) }
        assertEquals(2, ctx.size)
        assertEquals("对方：这个报表周五要交", ctx[0])
        assertEquals("我：好的", ctx[1])
    }

    @Test
    fun contextIsTruncatedToTheRequestedLimit() {
        val d = DigestBuilder.build(screen, W, H)
        val state = Json.obj(Json.parse(d.state(同事, maxCtx = 1)))
        assertEquals(1, Json.list(state["最近对话"]).size)
        assertEquals("我：好的", Json.str(Json.list(state["最近对话"])[0]))
    }

    @Test
    fun emptyWindowProducesEmptyState() {
        val d = DigestBuilder.build(listOf(line("微信", 40, 60, 200, 130)), W, H)
        assertEquals("", d.state(同事, 3))
        assertEquals(null, d.latestPeerMessage())
    }
}
