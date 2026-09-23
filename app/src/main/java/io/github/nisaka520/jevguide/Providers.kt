package io.github.nisaka520.jevguide

/**
 * 内置模型厂商预设。
 *
 * ## 为什么要做这个
 *
 * 之前用户得自己知道：地址要写到 `/v1`、模型名怎么拼、哪家能看图。
 * 现实是大部分人卡在第一步（填了 `https://api.deepseek.com` 少个 `/v1` 就 404，
 * 填了 `deepseek-chat` 到智谱的地址上也报错）。选一家 → 自动填好地址和两个模型 → 只需要粘密钥。
 *
 * ## 两点说明
 *
 * 1. **模型名会过时**：厂商改名的速度比 App 发版快。所以这里只是"默认值"，
 *    输入框始终可改；报错时去控制台复制当前名字即可（设置页也写了这句）。
 * 2. **看图能力写在预设里**：`visionModel` 空 = 这家没有能读图的模型或没填。
 *    视觉读屏要靠它，所以选择列表里直接标出来，免得用户选了 DeepSeek 才发现读不了图。
 *
 * 邀请码/推广链接统一放在 [Aff] 里，这里只写**官方**入口。
 */
data class Provider(
    /** 稳定 id：写进设置、当 [Aff] 的键，**不要改** */
    val id: String,
    val name: String,
    /** OpenAI 兼容基地址（要填到 `/v1` 这一级；智谱是 `/v4`） */
    val baseUrl: String,
    val chatModel: String,
    /** 能读图的模型名；空 = 这家没有/没填 */
    val visionModel: String,
    /** 申请密钥的官方入口 */
    val keyUrl: String,
    /** 密钥长什么样，方便用户确认自己复制对了 */
    val keyHint: String,
    val note: String
) {
    /** 能看图吗（视觉读屏要用） */
    val supportsVision: Boolean get() = visionModel.isNotBlank()
}

object Providers {

    /** 自定义：什么都不填，用户自己写地址和模型 */
    val CUSTOM = Provider(
        id = "custom",
        name = "自定义（自己填地址和模型）",
        baseUrl = "",
        chatModel = "",
        visionModel = "",
        keyUrl = "",
        keyHint = "看你的服务商文档",
        note = "任何 OpenAI 兼容端点都行：地址要填到 /v1 这一级。"
    )

    /**
     * 国内厂商。顺序＝推荐程度：先放便宜、好申请、有免费额度的。
     */
    val ALL: List<Provider> = listOf(
        Provider(
            id = "deepseek",
            name = "DeepSeek（官方，便宜）",
            baseUrl = "https://api.deepseek.com/v1",
            chatModel = "deepseek-chat",
            visionModel = "",                       // 官方暂无读图模型
            keyUrl = "https://platform.deepseek.com/api_keys",
            keyHint = "sk- 开头",
            note = "生成文案最划算的一家。**不支持看图** —— 想用视觉读屏要另配一家，或者只用无障碍树。"
        ),
        Provider(
            id = "zhipu",
            name = "智谱 GLM（支持看图，默认付费档）",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            chatModel = "glm-5.3",
            visionModel = "glm-ocr",
            keyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
            keyHint = "形如 xxxxxxxx.yyyyyyyy（带一个点）",
            note = "默认走付费档：glm-5.3 写文案 + glm-ocr 读截图（专做 OCR，跟本 App 用途最对口）。" +
                "新账号有赠送额度，够跑很久；想省钱就把上面两个模型改回 glm-4-flash 与 glm-4v-flash（免费档还在）。" +
                "注意地址是 /v4 不是 /v1。"
        ),
        Provider(
            id = "siliconflow",
            name = "硅基流动 SiliconFlow（聚合，支持看图）",
            baseUrl = "https://api.siliconflow.cn/v1",
            chatModel = "Qwen/Qwen2.5-7B-Instruct",
            visionModel = "Qwen/Qwen2.5-VL-72B-Instruct",
            keyUrl = "https://cloud.siliconflow.cn/account/ak",
            keyHint = "sk- 开头",
            note = "一家聚合很多开源模型，模型名要带 `厂商/` 前缀。免费/赠费额度常有活动。"
        ),
        Provider(
            id = "dashscope",
            name = "阿里云百炼（通义千问，支持看图）",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            chatModel = "qwen-plus",
            visionModel = "qwen-vl-max",
            keyUrl = "https://bailian.console.aliyun.com/",
            keyHint = "sk- 开头",
            note = "要走 compatible-mode 这个地址（OpenAI 兼容），别用原生的 dashscope 接口。"
        ),
        Provider(
            id = "kimi",
            name = "月之暗面 Kimi（支持看图）",
            baseUrl = "https://api.moonshot.cn/v1",
            chatModel = "moonshot-v1-8k",
            visionModel = "moonshot-v1-8k-vision-preview",
            keyUrl = "https://platform.moonshot.cn/console/api-keys",
            keyHint = "sk- 开头",
            note = "长上下文是它的强项，判读时塞多几句上下文也不心疼。"
        ),
        Provider(
            id = "volcengine",
            name = "火山方舟（豆包，支持看图）",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            chatModel = "",                          // 火山要先建"推理接入点"，每人 ID 不同
            visionModel = "",
            keyUrl = "https://console.volcengine.com/ark",
            keyHint = "形如一个 UUID，或 AK/SK 换来的 API Key",
            note = "⚠ 火山的模型名是**你自己创建的推理接入点 ID**（形如 `ep-2024xxxx-xxxxx`），" +
                "必须先去控制台创建接入点，把那个 ep-… 填到这里；不填会报模型不存在。"
        ),
        Provider(
            id = "minimax",
            name = "MiniMax（支持看图）",
            baseUrl = "https://api.minimax.chat/v1",
            chatModel = "abab6.5s-chat",
            visionModel = "MiniMax-VL-01",
            keyUrl = "https://platform.minimaxi.com/user-center/basic-information/interface-key",
            keyHint = "一长串 JWT（eyJ 开头）",
            note = "密钥是 JWT 形式，很长，别只复制了一半。"
        ),
        CUSTOM
    )

    /** 按 id 找；找不到给自定义（配置里存的是 id，预设删了也不能崩） */
    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: CUSTOM

    /**
     * 下拉框里能选的厂商：只留「有读图模型」的 + 自定义。
     *
     * 视觉读屏（截图 → 文字）是本 App 的核心路径，列表里混进不支持看图的厂商
     * （比如 DeepSeek 官方至今没有读图模型），用户选完才发现读不了图，只能再回来换一家。
     * 所以直接不列（用户要求：只保留有视觉模型的几家，其他如 DeepSeek 直接不要）。
     * 注意 byId 仍然查 ALL：老配置里存着 deepseek 也不能崩，只是下拉框里不再出现。
     */
    val PICKABLE: List<Provider> = ALL.filter { it.supportsVision || it.id == CUSTOM.id }

    /** 名字里带上"支持看图"，选择时不用去猜 */
    fun labels(): List<String> = PICKABLE.map { p ->
        when {
            p === CUSTOM -> p.name
            p.supportsVision -> p.name
            else -> p.name + "（不支持看图）"
        }
    }

    fun indexOf(id: String): Int = PICKABLE.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /**
     * 选中的这一项跟已存的一致吗？
     *
     * 存在意义：Android 的 `Spinner` 在设置监听器后会**为初始选中项补发一次回调**。
     * 设置页收到回调要 `recreate()` 刷新输入框，不加这层判断就会
     * 「回调 → recreate → onCreate → 补发回调 → …」无限重建。
     * 抽成纯函数是为了能单测 —— 这类死循环 bug 靠手点很难稳定复现。
     */
    fun isSameAs(currentId: String, index: Int): Boolean =
        PICKABLE.getOrElse(index) { CUSTOM }.id == currentId

    /**
     * 从已保存的地址反推是哪一家（认不出来返回 null）。
     *
     * 用途：用户可能用着自建中转站，而下拉框还停在"DeepSeek" —— 那是在骗人。
     * 认不出来时界面会明说"当前地址不是任何内置预设"。
     * 只比到域名那一级：用户可能把 `/v1` 写成 `/v1/`，或者用同域名的另一个路径。
     */
    fun detectByUrl(baseUrl: String): Provider? {
        val u = baseUrl.trim().lowercase()
        if (u.isEmpty()) return null
        return ALL.firstOrNull { p ->
            if (p === CUSTOM || p.baseUrl.isEmpty()) {
                false
            } else {
                val host = hostOf(p.baseUrl)
                // 必须落在域名边界上：api.deepseek.com 不能匹配 api.deepseek.com.evil.com
                u == host || u.startsWith("$host/")
            }
        }
    }

    /** 从 `https://api.deepseek.com/v1` 取出 `https://api.deepseek.com` */
    fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.trimEnd('/')
        val slash = url.indexOf('/', schemeEnd + 3)
        return if (slash < 0) url.trimEnd('/') else url.substring(0, slash)
    }
}
