package io.github.nisaka520.jevguide

/**
 * 邀请码 / 推广链接集中地 —— **要改就只改这个文件**。
 *
 * ## 怎么用
 *
 * 把下面每个厂商的值换成你自己的邀请链接（整条 URL，不是只填码），例如：
 *
 * ```kotlin
 * "zhipu"       to "https://www.bigmodel.cn/invite?icode=你的码",
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

    /**
     * key = [Provider.id]。空串或空白 = 用官方入口。
     *
     * ## 各家的链接长什么样、去哪儿拿
     *
     * 下面每一家的注释都写了「在哪一页拿」—— 没写的说明我**没有核实过**它有没有邀请活动，
     * 别照着猜格式，去控制台自己翻一眼，有就填、没有就留空（留空完全不影响功能）。
     */
    private val links: Map<String, String> = mapOf(
        // ── 有邀请返利的，把你的链接填在这里 ──

        // 智谱：控制台 → 右上角头像 →「邀请好友」，页面上的链接直接整条复制。
        // icode 里的 %2F %2B 是 URL 编码过的 / 和 +，必须原样保留，别手动解码 —— 解了链接就失效。
        "zhipu" to "https://www.bigmodel.cn/invite?icode=nGBxEgS2nDIiO2pfff%2F%2BN33uFJ1nZ0jLLgipQkYjpcA%3D",

        // 硅基流动：控制台左侧「邀请好友」（或账户页里的邀请卡片），形如
        // https://cloud.siliconflow.cn/i/XXXXXX —— 双方都有额度奖励，以官网当期规则为准。
        "siliconflow" to "https://cloud.siliconflow.cn/i/xNN8gDyn",

        // 阿里云百炼：走的是阿里云账号级的「推荐返利 / 云大使」，链接是一条阿里云的推广短链
        // （不是百炼自己的 icode），在阿里云「云大使」页面生成后整条贴进来。
        "dashscope" to "",

        // 月之暗面 Kimi：**未核实**有没有稳定的邀请链接格式，有就填。
        "kimi" to "",

        // 火山方舟：控制台里有个「限时邀请有礼」活动页，链接从那里拿。
        "volcengine" to "",

        // MiniMax：**未核实**。
        "minimax" to "",

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
