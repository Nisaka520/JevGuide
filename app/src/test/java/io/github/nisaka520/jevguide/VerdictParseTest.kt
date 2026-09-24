package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictParseTest {

    private val zhBody = """
    {"answers":{
      "intent":{"type":"choice","choice":"打招呼或闲聊",
        "probabilities":{"打招呼或闲聊":0.85,"询问信息或情况":0.10,"其他":0.05}},
      "urgency":{"type":"score","score":0.48},
      "need_reply":{"type":"noul","noul":0.65},
      "risk":{"type":"noul","noul":0.09},
      "advice":{"type":"choice","choice":"共情或闲聊式回应即可",
        "probabilities":{"共情或闲聊式回应即可":0.71,"简短确认收到，稍后详答":0.2}},
      "emotion":{"type":"choice","choice":"平静",
        "probabilities":{"平静":0.56,"客套":0.42,"着急":0.02,"高兴":0.001}},
      "reply_style":{"type":"choice","choice":"建议正常交流",
        "probabilities":{"建议正常交流":0.90,"建议礼貌客套":0.07}}
    }}
    """.trimIndent()

    private val enBody = """
    {"answers":{
      "intent":{"type":"choice","choice":"greeting_smalltalk",
        "probabilities":{"greeting_smalltalk":0.85,"asking_information":0.10}},
      "urgency":{"type":"score","score":0.48},
      "need_reply":{"type":"noul","noul":0.65},
      "risk":{"type":"noul","noul":0.09},
      "advice":{"type":"choice","choice":"respond_warmly","probabilities":{"respond_warmly":0.71}},
      "emotion":{"type":"choice","choice":"calm",
        "probabilities":{"calm":0.56,"polite_smalltalk":0.42,"urgent":0.02}},
      "reply_style":{"type":"choice","choice":"normal_chat","probabilities":{"normal_chat":0.90}}
    }}
    """.trimIndent()

    @Test
    fun riskLineFeedsThePromptButNotTheUi() {
        // 低风险（0.09）不占提示词位置；高风险才给出一行可执行的提醒
        assertNull(Verdicts.parse(zhBody, "zh")!!.riskLine())
        val hot = Verdicts.parse(zhBody.replace("0.09", "0.90"), "zh")!!
        val line = hot.riskLine()
        assertNotNull(line)
        assertTrue(line!!.contains("风险：0.90"))
        assertTrue(line.contains("偏高"))
        // 但界面上那三行的口径不动：风险**不并进** lines()
        assertEquals(3, hot.lines(3).size)
        assertFalse(hot.lines(3).any { it.contains("风险") })
    }

    @Test
    fun parsesChineseAnswersAndFormatsThreeLines() {
        val v = Verdicts.parse(zhBody, "zh")!!
        assertEquals("打招呼或闲聊", v.intent)
        assertEquals(0.85, v.intentP, 1e-9)
        assertEquals(listOf("平静", "客套", "着急"), v.emotions.map { it.first })
        assertEquals(0.56, v.emotions[0].second, 1e-9)
        assertEquals("不着急", v.urgencyWord())
        assertEquals(0.65, v.needReply!!, 1e-9)
        assertEquals("共情或闲聊式回应即可", v.advice)

        assertEquals(
            listOf(
                "意图：打招呼或闲聊 85%\n情绪：平静 56% · 客套 42% · 着急 2%",
                "着急：不着急 · 0.48/3",
                "建议：正常交流 90%"
            ),
            v.lines(3)
        )
    }

    @Test
    fun englishAnswersMapToTheSameChineseOutput() {
        val v = Verdicts.parse(enBody, "en")!!
        assertEquals("打招呼或闲聊", v.intent)
        assertEquals(listOf("平静", "客套", "着急"), v.emotions.map { it.first })
        assertEquals("建议正常交流", v.style)
        assertEquals("意图：打招呼或闲聊 85%\n情绪：平静 56% · 客套 42% · 着急 2%", v.coreLine(3))
    }

    @Test
    fun emotionCountIsRespectedAndTinyProbabilitiesDropped() {
        val v = Verdicts.parse(zhBody, "zh")!!
        assertEquals(2, v.lines(2).first().split("\n")[1].split(" · ").size)
        assertEquals(1, v.lines(1).first().split("\n")[1].split(" · ").size)
        // 0.001 的那一项在解析阶段就被过滤掉了
        assertEquals(3, v.emotions.size)
    }

    @Test
    fun fallsBackToSingleEmotionWhenProbabilitiesMissing() {
        val body = """{"answers":{"emotion":{"type":"choice","choice":"生气"},"intent":{"choice":"投诉或表达不满"}}}"""
        val v = Verdicts.parse(body, "zh")!!
        assertEquals(listOf("生气"), v.emotions.map { it.first })
        assertEquals(0.0, v.emotions[0].second, 1e-9)
        assertEquals("意图：投诉或表达不满\n情绪：生气", v.coreLine(3))
    }

    @Test
    fun urgencyWordRoundsToTheFourGrids() {
        fun word(score: Double): String =
            Verdicts.parse("""{"answers":{"urgency":{"score":$score},"intent":{"choice":"其他"}}}""", "zh")!!.urgencyWord()
        assertEquals("不着急", word(0.0))
        assertEquals("不着急", word(0.48))
        assertEquals("一般", word(1.2))
        assertEquals("急", word(2.4))
        assertEquals("非常急", word(3.0))
    }

    @Test
    fun emptyOrUnusableResponseReturnsNull() {
        assertNull(Verdicts.parse("""{"answers":{}}""", "zh"))
        assertNull(Verdicts.parse("", "zh"))
        assertNull(Verdicts.parse("not json", "zh"))
        assertNotNull(Verdicts.parse(zhBody, "zh"))
    }

    @Test
    fun detailContainsRiskAndNeedReply() {
        val d = Verdicts.parse(zhBody, "zh")!!.detail()
        assertEquals(true, d.contains("待回复：0.65"))
        assertEquals(true, d.contains("风险：0.09"))
    }
}
