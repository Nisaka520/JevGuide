package io.github.nisaka520.jevguide

import android.content.Context

/**
 * 本机设置。全部存在 SharedPreferences 里（`getSharedPreferences("jevguide")`），
 * 密钥与联系人表都只在本机，随卸载一起消失；不写外部存储、不上传。
 */
class Config(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("jevguide", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "").orEmpty()
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", Prompt.MODELS[0]).orEmpty().ifEmpty { Prompt.MODELS[0] }
        set(v) = sp.edit().putString("model", v).apply()

    /** 题目语言：zh / mix / en（见 docs/实验-题目语言AB.md） */
    var lang: String
        get() = sp.getString("lang", "zh").orEmpty().ifEmpty { "zh" }
        set(v) = sp.edit().putString("lang", v).apply()

    /** 情绪显示条数（1 / 3 / 5） */
    var emotionTop: Int
        get() = sp.getInt("emotion_top", 3).coerceIn(1, 5)
        set(v) = sp.edit().putInt("emotion_top", v.coerceIn(1, 5)).apply()

    /** 上下文句数 */
    var contextN: Int
        get() = sp.getInt("context_n", 3).coerceIn(0, 10)
        set(v) = sp.edit().putInt("context_n", v.coerceIn(0, 10)).apply()

    /** 3 条结果之间的间隔 */
    var toastGapMs: Int
        get() = sp.getInt("toast_gap_ms", 2000).coerceIn(600, 6000)
        set(v) = sp.edit().putInt("toast_gap_ms", v.coerceIn(600, 6000)).apply()

    /** 分析一开始先弹一条"分析中…"（第 0 条，可关；关了就只有 3 条结果） */
    var showAnalyzing: Boolean
        get() = sp.getBoolean("show_analyzing", true)
        set(v) = sp.edit().putBoolean("show_analyzing", v).apply()

    /** 检测到对方新消息就自动判读（默认关，避免打扰） */
    var autoAnalyze: Boolean
        get() = sp.getBoolean("auto_analyze", false)
        set(v) = sp.edit().putBoolean("auto_analyze", v).apply()

    /** 自动模式的防抖：新消息停下多久后才分析 */
    var autoDebounceMs: Int
        get() = sp.getInt("auto_debounce_ms", 500).coerceIn(400, 8000)
        set(v) = sp.edit().putInt("auto_debounce_ms", v.coerceIn(400, 8000)).apply()

    /** 是否尝试把引用块合成一条（启发式，默认关） */
    var linkQuotes: Boolean
        get() = sp.getBoolean("link_quotes", false)
        set(v) = sp.edit().putBoolean("link_quotes", v).apply()

    /** 服务运行时是否挂一条常驻通知当手动入口 */
    var showNotification: Boolean
        get() = sp.getBoolean("show_notification", true)
        set(v) = sp.edit().putBoolean("show_notification", v).apply()

    /** 是否把最近一次结果留在设置页（方便回头细看） */
    var lastVerdict: String
        get() = sp.getString("last_verdict", "").orEmpty()
        set(v) = sp.edit().putString("last_verdict", v).apply()

    /** 最近一次抓屏诊断的原文（只在本机；分享与否由你决定） */
    var lastDump: String
        get() = sp.getString("last_dump", "").orEmpty()
        set(v) = sp.edit().putString("last_dump", v.take(400_000)).apply()

    /** 最近一次诊断文件的路径 */
    var lastDumpPath: String
        get() = sp.getString("last_dump_path", "").orEmpty()
        set(v) = sp.edit().putString("last_dump_path", v).apply()

    var contactsJson: String
        get() = sp.getString("contacts", "[]").orEmpty().ifEmpty { "[]" }
        set(v) = sp.edit().putString("contacts", v).apply()

    // ══════════════════ 聊天模型（生成 3 条候选回复文案）══════════════════
    //
    // 与 Jev 分开配置：Jev 只负责"判读"（意图/情绪/攻略度），文案由这个通用聊天模型生成。
    // 任何 OpenAI 兼容端点都能用（智谱 GLM / 你自己的中转站），所以 base_url 可填。

    /** OpenAI 兼容端点，**要填到 /v1 或 /v4**（例：https://open.bigmodel.cn/api/paas/v4 —— 智谱是 /v4，不是 /v1） */
    var chatBaseUrl: String
        get() = sp.getString("chat_base_url", DEFAULT_CHAT_BASE).orEmpty().ifEmpty { DEFAULT_CHAT_BASE }
        set(v) = sp.edit().putString("chat_base_url", v.trim()).apply()

    var chatApiKey: String
        get() = sp.getString("chat_api_key", "").orEmpty()
        set(v) = sp.edit().putString("chat_api_key", v.trim()).apply()

    var chatModel: String
        get() = sp.getString("chat_model", DEFAULT_CHAT_MODEL).orEmpty().ifEmpty { DEFAULT_CHAT_MODEL }
        set(v) = sp.edit().putString("chat_model", v.trim()).apply()

    /** 是否生成候选文案（关掉就只做判读，省一次调用） */
    var draftsEnabled: Boolean
        get() = sp.getBoolean("drafts_enabled", true)
        set(v) = sp.edit().putBoolean("drafts_enabled", v).apply()

    /** 生成几条候选（默认 3） */
    var draftsN: Int
        get() = sp.getInt("drafts_n", 3).coerceIn(1, 5)
        set(v) = sp.edit().putInt("drafts_n", v.coerceIn(1, 5)).apply()

    /** 选中的风格，逗号分隔。默认就是原来的三种 */
    var stylesCsv: String
        get() = sp.getString("styles_csv", "稳妥,推进,有趣").orEmpty()
        set(v) = sp.edit().putString("styles_csv", v).apply()

    /**
     * 解析成风格列表：逗号（中英文都认）分隔、去空白、丢不认识的名字、去重、最多取三套。
     * 全空或全是不认识的名字 → 回落默认三种，**保证永远至少有一种可用**。
     */
    fun styles(): List<String> {
        val known = ReplyPrompt.ALL_STYLE_TITLES
        val picked = stylesCsv.split(',', '，', '、', ' ').map { it.trim() }.filter { it in known }.distinct()
            // 上限在这里也要拦一道：配置是纯文本，界面拦不住的（旧版本/手改）这里兜住
            .take(ReplyPrompt.MAX_PICK)
        return picked.ifEmpty { ReplyPrompt.STYLE_TITLES }
    }

    // ══════════════════ 攻略度 & 记忆 ══════════════════

    /** 是否问 Jev 要「攻略度」百分比 */
    var guideEnabled: Boolean
        get() = sp.getBoolean("guide_enabled", true)
        set(v) = sp.edit().putBoolean("guide_enabled", v).apply()

    /** 是否启用按联系人的本地记忆（关掉则每次都当第一次聊） */
    var memoryEnabled: Boolean
        get() = sp.getBoolean("memory_enabled", true)
        set(v) = sp.edit().putBoolean("memory_enabled", v).apply()

    /** 攒够多少条新对话就刷新一次摘要（每刷一次 = 一次聊天模型调用） */
    var summarizeEvery: Int
        get() = sp.getInt("summarize_every", 8).coerceIn(0, 50)
        set(v) = sp.edit().putInt("summarize_every", v.coerceIn(0, 50)).apply()

    /** 结果显示方式：page=结果页（可点选复制）｜toast=只弹提示（与旧版一致） */
    var resultMode: String
        get() = sp.getString("result_mode", "page").orEmpty().ifEmpty { "page" }
        set(v) = sp.edit().putString("result_mode", v).apply()

    // ══════════════════ 读取方式（无障碍树 / 视觉模型）══════════════════
    //
    // 为什么会有这个开关：微信 8.0.76 把无障碍树整个屏蔽了 —— 实测连系统自带的 uiautomator
    // 抓微信都是 0 个文字节点（407 字节空树），所以"从无障碍树读消息"在新版微信上必然读空。
    // 但截屏是能拍到的（不是 FLAG_SECURE），于是多了"截图 → 视觉模型念成文字"这条路。

    /** auto=先试无障碍树，读空就自动退到视觉 ｜ a11y=只读无障碍树 ｜ vision=只用视觉 */
    var readMode: String
        get() = sp.getString("read_mode", "auto").orEmpty().ifEmpty { "auto" }
        set(v) = sp.edit().putString("read_mode", v).apply()

    /** 视觉读屏单独指定端点；留空就跟「聊天模型」共用一套配置 */
    var visionBaseUrl: String
        get() = sp.getString("vision_base_url", "").orEmpty()
        set(v) = sp.edit().putString("vision_base_url", v.trim()).apply()

    var visionApiKey: String
        get() = sp.getString("vision_api_key", "").orEmpty()
        set(v) = sp.edit().putString("vision_api_key", v.trim()).apply()

    /** 视觉读屏的模型名（必须是**看得懂图**的模型）；留空就跟文案用同一个 */
    var visionModel: String
        get() = sp.getString("vision_model", "").orEmpty()
        set(v) = sp.edit().putString("vision_model", v.trim()).apply()

    /** 视觉读屏的密钥是否可用（单独填了用单独的，否则看聊天模型那把） */
    fun hasVisionKey(): Boolean = (visionApiKey.ifEmpty { chatApiKey }).length >= 20

    // ══════════════════ 常驻悬浮条（把攻略度一直挂在屏幕上）══════════════════

    /** 常驻显示当前联系人的攻略度（无障碍浮层，不需要"显示在其他应用上层"权限） */
    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", true)
        set(v) = sp.edit().putBoolean("overlay_enabled", v).apply()

    /** 判读完是否自动弹结果页（默认关：攻略度已经在浮层上了，要看文案再点浮层） */
    var overlayAutoResult: Boolean
        get() = sp.getBoolean("overlay_auto_result", false)
        set(v) = sp.edit().putBoolean("overlay_auto_result", v).apply()

    /** 浮层位置（拖动后记住） */
    var overlayX: Int
        get() = sp.getInt("overlay_x", 24)
        set(v) = sp.edit().putInt("overlay_x", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlay_y", 420)
        set(v) = sp.edit().putInt("overlay_y", v).apply()

    /** 最近一次读到的联系人名：视觉模型偶尔不返回标题，用它兜住，免得浮条只显示"微信" */
    /**
     * 追加到聊天模型 system 提示词末尾的「额外要求」（高级设置里填）。
     *
     * 为什么只做「追加」不做「整段替换」：输出格式（三段 + 标题行 + --- 分隔）和硬性约束
     * 是解析端的契约，用户把那段改掉之后 ReplyPrompt.parse 就切不出三段了，
     * 界面上只会剩一条糊在一起的文案 —— 那不是「自定义」，是坏掉。
     */
    var promptExtra: String
        get() = sp.getString("prompt_extra", "") ?: ""
        set(v) = sp.edit().putString("prompt_extra", v).apply()

    var lastContactName: String
        get() = sp.getString("last_contact_name", "").orEmpty()
        set(v) = sp.edit().putString("last_contact_name", v).apply()

    /** 浮层上最近显示的文字与分数：服务重启后照原样恢复，不用等下次判读 */
    var lastOverlayText: String
        get() = sp.getString("last_overlay_text", "").orEmpty()
        set(v) = sp.edit().putString("last_overlay_text", v).apply()

    /** -1 表示还没有过分数 */
    var lastOverlayPercent: Int
        get() = sp.getInt("last_overlay_percent", -1)
        set(v) = sp.edit().putInt("last_overlay_percent", v).apply()

    /**
     * 系统无障碍快捷按钮（屏幕边上一个圆钮，点了触发判读）。
     *
     * **默认关**：无障碍服务一旦注册了 AccessibilityButtonCallback，系统就会自己画一个悬浮按钮出来 ——
     * 它跟常驻浮条功能完全重复（浮条点一下也是判读），两个都挂着只会让人问"怎么有两个东西"。
     * 需要的话在设置里打开（有些 ROM 还要求去系统设置里把"无障碍快捷方式"绑到本服务）。
     */
    var a11yButtonEnabled: Boolean
        get() = sp.getBoolean("a11y_button_enabled", false)
        set(v) = sp.edit().putBoolean("a11y_button_enabled", v).apply()

    /** 聊天模型是否可用（与 Jev 同判据：≥20 字符） */
    fun hasChatKey(): Boolean = chatApiKey.length >= 20

    // ══════════════════ 厂商预设 ══════════════════

    /** 上次选的厂商 id（[Providers.ALL] 里的那个） */
    var providerId: String
        get() = sp.getString("provider_id", "zhipu").orEmpty().ifEmpty { "zhipu" }
        set(v) = sp.edit().putString("provider_id", v).apply()

    /**
     * 套用一家厂商的预设：地址 + 聊天模型 + 视觉模型。
     *
     * 刻意**不动密钥**：换厂商时把用户已经粘好的密钥清掉是最讨厌的行为之一
     * （万一是同一家的第二个账号呢）。界面上会提示"记得换成这一家的密钥"。
     * 视觉端点也留空，让它跟聊天模型共用同一套（同一家通常同一把密钥）。
     */
    fun applyProvider(p: Provider) {
        providerId = p.id
        if (p.baseUrl.isNotBlank()) chatBaseUrl = p.baseUrl
        if (p.chatModel.isNotBlank()) chatModel = p.chatModel
        visionBaseUrl = ""
        visionApiKey = ""
        visionModel = p.visionModel
    }

    fun contacts(): List<Contact> = Contacts.fromJson(contactsJson)

    fun saveContacts(list: List<Contact>) {
        contactsJson = Contacts.toJson(list)
    }

    /** 密钥是否可用（与插件版同一判据：≥20 字符） */
    fun hasKey(): Boolean = apiKey.length >= 20

    fun clearAll() = sp.edit().clear().apply()

    companion object {
        // 默认一家走通全程：智谱 glm-5.3-flash（文本，flash 档更快更便宜）+ glm-ocr（视觉，专做 OCR），
        // 一个密钥就够，用户不用先充钱。想换别家随时在设置里改。
        const val DEFAULT_CHAT_BASE = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_CHAT_MODEL = "glm-5.3-flash"
    }
}
