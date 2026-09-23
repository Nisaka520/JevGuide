package io.github.nisaka520.jevguide

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 端到端冒烟：真的打一次 api.typesafe.ai，把「抓屏 → 关系匹配 → 拼 state →
 * 构造 7 题请求 → 真接口 → 解析答案 → 3 条提示」整条链走通。
 *
 * 默认**跳过**（不设密钥就不会偷偷发请求）；要跑就：
 *   PowerShell:  $env:JEV_KEY='apikey_…';  ./gradlew testDebugUnitTest --tests '*LiveJevSmokeTest*'
 *   bash:        JEV_KEY=apikey_… ./gradlew testDebugUnitTest --tests '*LiveJevSmokeTest*'
 */
class LiveJevSmokeTest {

    @Test
    fun realEndpointProducesThreeToastLines() {
        val key = System.getenv("JEV_KEY").orEmpty()
        assumeTrue("没有设置 JEV_KEY，跳过真接口测试", key.length >= 20)

        // 一个典型的微信单聊屏
        val digest = DigestBuilder.build(
            listOf(
                RawLine("张伟", 40, 60, 260, 130),
                RawLine("这个报表周五要交", 40, 420, 560, 500),
                RawLine("好的", 700, 540, 1040, 620),
                RawLine("你那边进度怎么样了？", 40, 660, 700, 740)
            ),
            screenW = 1080, screenH = 2400
        )
        val contact = Contact(listOf("张伟", "伟哥"), "同事", "男", "市场部")
        val state = digest.state(contact, maxCtx = 3)
        assertTrue("state 不该为空", state.isNotEmpty())

        val body = Prompt.requestJson(state, Prompt.MODELS[0], "zh")
        val result = JevHttp.analyze(key, body, timeoutMs = 40000)

        when (result) {
            is JevResult.Err -> throw AssertionError("真接口调用失败：" + result.message)
            is JevResult.Ok -> {
                val v = Verdicts.parse(result.body, "zh", 3)
                assertNotNull("解析不出结果，原始响应：" + result.body.take(300), v)
                val lines = v!!.lines(3)

                println("=== 真接口返回的 3 条 ===")
                lines.forEach { println(it) }
                println("--- 详情：" + v.detail().replace("\n", " ｜ "))

                assertTrue("第 1 条应以「意图：」开头，实际：" + lines[0], lines[0].startsWith("意图："))
                assertTrue("第 1 条应含「情绪：」，实际：" + lines[0], lines[0].contains("情绪："))
                assertTrue("第 2 条应以「着急：」开头，实际：" + lines[1], lines[1].startsWith("着急："))
                assertTrue("第 3 条应以「建议：」开头，实际：" + lines[2], lines[2].startsWith("建议："))
                assertTrue("着急分应在 0~3，实际：" + v.urgency, v.urgency == null || v.urgency in 0.0..3.0)
            }
        }
    }
}
