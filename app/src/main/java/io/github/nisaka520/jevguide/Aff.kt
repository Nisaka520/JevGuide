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
     * key = [Provider.id]。**没列出来的 = 不出渠道**：按钮回落 [Provider.keyUrl] 的官方入口，功能一模一样。
     *
     * ## 现在出渠道的三家
     *
     * 智谱 / 硅基流动 / 阿里云百炼 —— 每家的注释都写了「在哪一页拿」。
     * DeepSeek 官方没有邀请返利，列在这里只为了记一句「确认过，没有」。
     */
    private val links: Map<String, String> = mapOf(
        // ── 有邀请返利的，把你的链接填在这里 ──

        // 智谱：控制台 → 右上角头像 →「邀请好友」，页面上的链接直接整条复制。
        // icode 里的 %2F %2B 是 URL 编码过的 / 和 +，必须原样保留，别手动解码 —— 解了链接就失效。
        "zhipu" to "https://www.bigmodel.cn/invite?icode=nGBxEgS2nDIiO2pfff%2F%2BN33uFJ1nZ0jLLgipQkYjpcA%3D",

        // 硅基流动：控制台左侧「邀请好友」（或账户页里的邀请卡片），形如
        // https://cloud.siliconflow.cn/i/XXXXXX —— 双方都有额度奖励，以官网当期规则为准。
        "siliconflow" to "https://cloud.siliconflow.cn/i/xNN8gDyn",

        // 阿里云百炼：走的是阿里云**账号级**的「云大使」，不是百炼自己的 icode。
        //   · 云大使首页：https://dashi.aliyun.com/
        //   · 百炼专门活动页「推荐百炼 瓜分万元奖励」：https://dashi.aliyun.com/activity/ai
        //   · 官方「去哪拿我的专属链接」说明：https://help.aliyun.com/zh/document_detail/125340.html
        // **选长链**（整条带 userCode 的 URL），别选短链或口令 —— App 是直接 ACTION_VIEW 打开的，
        // 长链不经过跳转页，最稳。
        // ⚠ 活动类链接**会过期**（贺岁/邀新那种都是限时的）：过期后按钮就点到一个失效页，
        // 而 App 里写死的链接没法自动更新 —— 能拿云大使的长期链接就优先用长期的。
        "dashscope" to "https://dashi.aliyun.com/activity/ai?source=5176.29345612&userCode=i9q3mkfj",  // ⚠ 活动页链接，过期了要换

        // ── 不出渠道的三家（2026-09 定的，别再试图填）──
        //   · Kimi：开放平台没有邀请返利。市面上流传的是「Kimi 会员订阅」邀请（消费端会员），
        //     跟这一格的用途（用户是来建 API 密钥的）不是一回事，填进去就是货不对板。
        //   · 火山方舟：原有的「限时邀请有礼」**已于 2026-04-29 0点下线**（官方公告：
        //     https://docs.volcengine.com/docs/82379/2165246?lang=zh ），历史邀请链接与邀请码全部失效 ——
        //     所以控制台里翻不到是正常的。现存的两个邀请活动都是**套餐订阅**导向
        //     （Agent Plan 2026-07-24~12-31、Coding Plan 2026-05-19~11-19），同样对不上这一格的用途。
        //   · MiniMax：Referral Program 在 platform.minimax.io，而 App 这家用的是国内站 minimaxi.com，
        //     不确定能否互通，先不出。
        //   这三家的按钮照常能用：没配链接就回落 [Provider.keyUrl] 的官方入口（见下面 keyUrl 的 `?: p.keyUrl`）。

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
