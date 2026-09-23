package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厂商预设与邀请码的单测。
 *
 * 这层最容易出的错是"填错一个字，用户就永远调不通"：地址少个 `/v1`、模型名带空格、
 * 两家共用一个 id 导致配置串台。所以这里把能静态检查的都钉住。
 */
class ProvidersTest {

    @Test
    fun idsAreUniqueAndStable() {
        val ids = Providers.ALL.map { it.id }
        assertEquals("id 不能重复（配置里存的就是它）", ids.size, ids.toSet().size)
        assertTrue("必须有 deepseek 这家", ids.contains("deepseek"))
        assertTrue("必须有自定义", ids.contains("custom"))
    }

    @Test
    fun presetUrlsAreHttpsAndTrimmed() {
        Providers.ALL.filter { it !== Providers.CUSTOM }.forEach { p ->
            assertTrue("${p.id} 的地址必须是 https", p.baseUrl.startsWith("https://"))
            assertEquals("${p.id} 的地址末尾不能带斜杠", p.baseUrl.trimEnd('/'), p.baseUrl)
            assertEquals("${p.id} 的地址不能有空格", p.baseUrl.trim(), p.baseUrl)
            assertTrue(
                "${p.id} 的地址要指到接口版本那一级（/v1 或 /v4 或 /v3）",
                Regex("/v\\d+$").containsMatchIn(p.baseUrl)
            )
        }
    }

    @Test
    fun everyPresetHasNameHintAndOfficialEntry() {
        Providers.ALL.forEach { p ->
            assertTrue("${p.id} 缺名字", p.name.isNotBlank())
            assertTrue("${p.id} 缺密钥提示", p.keyHint.isNotBlank())
            assertTrue("${p.id} 缺说明", p.note.isNotBlank())
            if (p !== Providers.CUSTOM) {
                assertTrue("${p.id} 缺官方申请入口", p.keyUrl.startsWith("https://"))
            }
        }
    }

    @Test
    fun chatModelNamesHaveNoWhitespace() {
        Providers.ALL.filter { it.chatModel.isNotBlank() }.forEach { p ->
            assertEquals("${p.id} 的模型名不能有空格", p.chatModel.trim(), p.chatModel)
        }
    }

    @Test
    fun visionCapabilityIsDeclaredHonestly() {
        // DeepSeek 官方没有读图模型：必须标成不支持，否则用户选了它再开视觉读屏会一直失败
        assertFalse(Providers.byId("deepseek").supportsVision)
        // 至少要有几家能看图的，不然视觉读屏没地方配
        assertTrue("至少要有一家支持看图", Providers.ALL.count { it.supportsVision } >= 3)
    }

    @Test
    fun pickListOnlyOffersVisionCapableProviders() {
        // 下拉框只列「有读图模型」的厂商 + 自定义。视觉读屏是本 App 的核心路径，
        // 列一个选完读不了图的厂商等于给用户挖坑（用户要求：如 DeepSeek 直接不要）。
        // 这条测试原来断言的是「不支持看图的要标注」，现在那些厂商根本不进列表，断言跟着改。
        val labels = Providers.labels()
        assertEquals(
            "列表 = 支持看图的 + 自定义",
            Providers.ALL.count { it.supportsVision || it.id == "custom" },
            labels.size
        )
        assertFalse("不支持看图的厂商不该出现在下拉框里", labels.any { it.contains("不支持看图") })
        assertFalse("DeepSeek 没有读图模型，不该出现在列表里", labels.any { it.startsWith("DeepSeek") })
        assertTrue("至少要留几家能看图的，不然视觉读屏没地方配", labels.size >= 3)
        // 但 byId 必须还能查到老配置里存的 id（只是不再出现在下拉框里）—— 删预设不能让老用户崩
        assertEquals("deepseek", Providers.byId("deepseek").id)
        assertFalse(Providers.byId("deepseek").supportsVision)
    }

    @Test
    fun unknownIdFallsBackToCustomInsteadOfCrashing() {
        assertEquals("custom", Providers.byId("不存在的厂商").id)
        assertEquals(0, Providers.indexOf("不存在的厂商"))
    }

    @Test
    fun affFallsBackToOfficialUrlWhenNotConfigured() {
        // 占位符还没填的时候，必须回落到官方入口 —— 绝不能给出空链接或坏链接
        val zhipu = Providers.byId("zhipu")
        val url = Aff.keyUrl(zhipu)
        assertTrue("zhipu 的入口不能为空", url.isNotBlank())
        assertTrue("zhipu 的入口必须是 https", url.startsWith("https://"))
    }

    @Test
    fun affLinksAreHttpsWhenConfigured() {
        // 一旦填了推广链接，也必须是 https（填错的链接比没填更糟）
        Providers.ALL.forEach { p ->
            val url = Aff.keyUrl(p)
            if (url.isNotBlank()) {
                assertTrue("${p.id} 的入口必须是 https：$url", url.startsWith("https://"))
                assertFalse("${p.id} 的入口不能带空格", url.contains(" "))
            }
        }
    }

    @Test
    fun customHasNoEntryAtAll() {
        assertEquals("", Providers.CUSTOM.baseUrl)
        assertEquals("", Providers.CUSTOM.chatModel)
        assertEquals("", Aff.keyUrl(Providers.CUSTOM))
        assertNotNull(Providers.CUSTOM.note)
    }

    /**
     * 防死循环的核心判断。
     *
     * 真实场景：进设置页 → Spinner 为初始项补发回调 → 若判定为"变了"就会 recreate() →
     * onCreate 又补发一次 → 无限重建。这里把"什么时候算真的换了"钉死。
     */
    @Test
    fun sameSelectionIsDetectedSoTheUiWontRebuildInALoop() {
        // 进页面时补发的那次回调：下标对应的就是已存的 id → 必须判为"没变"。
        // 注意遍历的是 PICKABLE（下拉框真正显示的那一份），不是 ALL ——
        // isSameAs 判的是「下拉框下标」，拿全表下标去对必然错位。
        Providers.PICKABLE.forEachIndexed { i, p ->
            assertTrue("下标 $i（${p.id}）应判为没变", Providers.isSameAs(p.id, i))
        }
        // 用户真的换了另一家 → 判为"变了"，这时候才允许套用预设并重建界面
        val zhipuIdx = Providers.indexOf("zhipu")
        assertFalse(Providers.isSameAs("kimi", zhipuIdx))
        // 兜底：越界下标不许抛异常（预设表将来增删时的保险）
        assertFalse(Providers.isSameAs("deepseek", 999))
        assertFalse(Providers.isSameAs("deepseek", -1))
    }

    @Test
    fun detectsProviderFromSavedUrl() {
        assertEquals("deepseek", Providers.detectByUrl("https://api.deepseek.com/v1")?.id)
        assertEquals("deepseek", Providers.detectByUrl("https://api.deepseek.com/v1/")?.id)
        assertEquals("zhipu", Providers.detectByUrl("https://open.bigmodel.cn/api/paas/v4")?.id)
        assertEquals("deepseek", Providers.detectByUrl("  HTTPS://API.DEEPSEEK.COM/v1  ")?.id)
        // 自建/中转站：认不出来必须返回 null，界面据此提示"以下面输入框为准"
        assertNull(Providers.detectByUrl("http://23.251.34.187:8082/v1"))
        assertNull(Providers.detectByUrl(""))
        assertNull(Providers.detectByUrl("   "))
    }

    @Test
    fun urlDetectionRespectsDomainBoundaries() {
        // 钓鱼式域名不能被当成官方域名（真实攻击面：用户配了个假中转站却显示"DeepSeek"）
        assertNull(Providers.detectByUrl("https://api.deepseek.com.evil.com/v1"))
        assertNull(Providers.detectByUrl("https://notapi.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com", Providers.hostOf("https://api.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com", Providers.hostOf("https://api.deepseek.com"))
    }
}
