package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推广链接这一层最容易出的两种错，都在这里钉住：
 *
 * ① 明明配了邀请链接，界面却还开官方入口 —— 用户白注册、作者的奖励也丢了；
 * ② 留空时反而把官方入口弄丢 —— 「去申请密钥」按钮打开空地址，新用户第一步就卡死。
 *
 * 这两条都是**不依赖当前配了哪几家**的不变式：以后加厂商、清链接，测试都不该因此要改。
 */
class AffTest {

    @Test
    fun configuredInviteWinsAndEmptyFallsBack() {
        for (p in Providers.ALL) {
            val url = Aff.keyUrl(p)
            if (Aff.hasLink(p.id)) {
                assertTrue("${p.id} 配了邀请链接就该用它，而不是回落官方入口", url != p.keyUrl)
            } else {
                assertEquals("${p.id} 没配邀请链接时必须回落官方入口", p.keyUrl, url)
            }
        }
    }

    @Test
    fun configuredLinksAreWholeHttpsUrls() {
        // Aff.kt 的文档写明：要填**整条 URL**，不是只填码 —— 只填码的话按钮会打不开
        for (p in Providers.ALL) {
            if (Aff.hasLink(p.id)) {
                val url = Aff.keyUrl(p)
                assertTrue("${p.id} 的推广链接必须是 https 整条 URL：$url", url.startsWith("https://"))
            }
        }
    }

    @Test
    fun customProviderHasNoEntryPointAndMustNotCrash() {
        // 自定义端点：既没官方入口也没推广链接 → 空串（调用方据此提示「这家没有在线申请页」）
        assertEquals("", Providers.CUSTOM.keyUrl)
        assertFalse(Aff.hasLink(Providers.CUSTOM.id))
        assertEquals("", Aff.keyUrl(Providers.CUSTOM))
    }

    @Test
    fun byIdFallsBackToCustomInsteadOfThrowing() {
        // 配置里存的是 id 字符串，历史 id 失效时不能崩（byId 必须兜到 CUSTOM）
        assertEquals("siliconflow", Providers.byId("siliconflow").id)
        assertEquals(Providers.CUSTOM, Providers.byId("这家已经不存在了"))
    }
}
