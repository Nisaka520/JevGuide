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

        // 阿里云百炼：走的是阿里云**账号级**的「云大使」，不是百炼自己的 icode。
        //   · 云大使首页：https://dashi.aliyun.com/
        //   · 百炼专门活动页「推荐百炼 瓜分万元奖励」：https://dashi.aliyun.com/activity/ai
        //   · 官方「去哪拿我的专属链接」说明：https://help.aliyun.com/zh/document_detail/125340.html
        // **选长链**（整条带 userCode 的 URL），别选短链或口令 —— App 是直接 ACTION_VIEW 打开的，
        // 长链不经过跳转页，最稳。
        // ⚠ 活动类链接**会过期**（贺岁/邀新那种都是限时的）：过期后按钮就点到一个失效页，
        // 而 App 里写死的链接没法自动更新 —— 能拿云大使的长期链接就优先用长期的。
        "dashscope" to "https://dashi.aliyun.com/activity/ai?source=5176.29345612&userCode=i9q3mkfj",  // ⚠ 活动页链接，过期了要换

        // 月之暗面 Kimi：查过开放平台（platform.kimi.com / platform.moonshot.cn）**没有邀请返利** → 留空。
        //   ⚠ 别把「Kimi 会员订阅」的邀请链填这里：那是消费端会员（kimi.com/activities/…），
        //   与这一格的用途（用户是来建 API 密钥的）根本不是一回事，填进去就是货不对板。
        "kimi" to "",

        // 火山方舟：⚠ 原来那个「限时邀请有礼」**已于 2026-04-29 0点下线**，"历史生成的邀请链接与邀请码
        //   将停止生效"（官方公告：https://docs.volcengine.com/docs/82379/2165246?lang=zh）
        //   —— 所以在控制台里翻来翻去找不到，是正常的，不是你没找对地方。
        //
        //   现在还在的两个邀请活动都是**套餐订阅**导向，奖励按"被邀请人订阅套餐"算：
        //     · Agent Plan Small & Medium：2026-07-24 ~ 2026-12-31
        //     · Coding Plan Lite & Pro：2026-05-19 ~ 2026-11-19
        //   入口都是活动页上的「邀请好友」按钮（生成专属链接与邀请码）。
        //   但它们跟这一格的用途（用户是来建 API 密钥的）对不上 —— 和 Kimi 会员那条同理，别填。
        //
        //   想要能对上号的，去控制台「费用中心 → 邀请有礼」看看有没有**账号级**邀请：
        //   https://console.volcengine.com/finance/invite
        "volcengine" to "",

        // MiniMax：开放平台有 Referral Program，在那边生成邀请链接。
        //   官方文档：https://platform.minimax.io/docs/token-plan/promotion
        //   （国内站对应 platform.minimaxi.com，App 里这家用的就是国内站地址）
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
