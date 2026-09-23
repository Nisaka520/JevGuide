package io.github.nisaka520.jevguide

/**
 * 邀请码 / 推广链接集中地 —— **要改就只改这个文件**。
 *
 * ## 怎么用
 *
 * 把下面每个厂商的值换成你自己的邀请链接（整条 URL，不是只填码），例如：
 *
 * ```kotlin
 * "zhipu"       to "https://www.bigmodel.cn/glm-coding?ic=你的码",
 * "siliconflow" to "https://cloud.siliconflow.cn/i/你的码",
 * ```
 *
 * 留空 = 用 [Provider.keyUrl] 里的**官方普通入口**（不推广，也不影响功能）。
 * 没有邀请返利的厂商（比如 DeepSeek）留空即可，别硬编。
 *
 * ## 界面上的义务
 *
 * 设置页会明写一句"通过此链接注册，作者会得到少量额度奖励，不影响你的价格与权益"。
 * 这条不能删 —— 藏着的推广链接比放着的更容易招骂，而且用户有权知道。
 *
 * ## 为什么不做成"点一下自动注册"
 *
 * 那需要把用户带去浏览器以外的流程，既不合规也没必要：用户本来就要自己去控制台建密钥。
 * 我们能做的是"少填三个字段 + 顺手带上邀请码"，仅此而已。
 */
object Aff {

    /** key = [Provider.id]。空串或空白 = 用官方入口 */
    private val links: Map<String, String> = mapOf(
        // ── 有邀请返利的，把你的链接填在这里 ──
        "zhipu" to "",        // 例：https://www.bigmodel.cn/glm-coding?ic=XXXX
        "siliconflow" to "",  // 例：https://cloud.siliconflow.cn/i/XXXX
        "dashscope" to "",    // 阿里云百炼的邀请链接
        "kimi" to "",         // 月之暗面的邀请链接
        "volcengine" to "",   // 火山方舟的邀请链接
        "minimax" to "",      // MiniMax 的邀请链接

        // ── 官方没有邀请返利，留空就行 ──
        "deepseek" to ""
    )

    /** 这家配了推广链接吗（设置页据此显示"含邀请"标记） */
    fun hasLink(id: String): Boolean = !links[id].isNullOrBlank()

    /**
     * 申请密钥该打开的地址：配了推广链接就用它，否则回落到官方入口。
     * 自定义（没有 keyUrl）返回空串，调用方自己判断。
     */
    fun keyUrl(p: Provider): String =
        links[p.id]?.takeIf { it.isNotBlank() } ?: p.keyUrl

    /** 有没有任何一家配了推广链接（决定设置页要不要显示那行说明） */
    fun anyConfigured(): Boolean = links.values.any { it.isNotBlank() }
}
