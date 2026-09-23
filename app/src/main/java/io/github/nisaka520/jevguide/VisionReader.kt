package io.github.nisaka520.jevguide

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 视觉读屏：**截屏 → 视觉模型转文字**。
 *
 * ## 为什么需要这条路
 *
 * 微信 8.0.76 把无障碍树整个屏蔽了 —— 实测（2026-09-23，一加 Ace 3 Pro / Android 16）：
 *
 * | 对象 | 带文字的节点数 |
 * |---|---|
 * | 微信聊天页 ChattingUI | 0 |
 * | 微信搜索页 FTSMainUI | 0 |
 * | 系统自带 uiautomator 抓微信 | 0（407 字节空树） |
 * | 系统设置 App | 15 |
 * | 桌面（本服务） | 280 行 |
 *
 * 也就是说：不是本 App 的 bug，是微信自己不给树，**任何**无障碍客户端（包括系统工具）都读不到。
 * 但**截屏是能拍到的**（不是 FLAG_SECURE），所以改从画面走：让一个看得懂的模型把截图念成文字。
 *
 * ## 取舍
 *
 * - 多花一次「带图」的模型调用（约 5~10s，取决于端点）；换来"不依赖微信配合"这件事
 * - 顺带能读出**图片消息里的字**（无障碍树本来也读不到）
 * - 图会先缩到 [MAX_WIDTH] 宽再压 JPEG：一张 1080×2376 的 PNG 是 130KB+ 的 base64，
 *   缩到 900 宽 JPEG80 只要 1/5，模型读中文气泡完全够用
 *
 * ⚠ 前提：截图只发到**你自己配的**那个端点（跟文案用的是同一套配置，可单独覆盖）。
 */
object VisionReader {

    /** 缩图目标宽度：够看清聊天气泡，又别让 base64 太肥 */
    const val MAX_WIDTH = 900

    /** 截图 + 模型调用都比较慢，给足超时（端点是自己的，超了也比截断强） */
    private const val TIMEOUT_MS = 60000

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "jevguide-vision") }

    /** 无障碍截图要 API 30+（`AccessibilityService.takeScreenshot`） */
    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 前台是不是微信 —— 截图之前必须先确认，否则会把别的 App 的画面发出去 */
    fun foregroundIsWeChat(service: AccessibilityService): Boolean = try {
        service.rootInActiveWindow?.packageName?.toString() == WeChatReader.WECHAT_PKG
    } catch (t: Throwable) {
        false
    }

    /** 视觉读屏用哪个端点：单独填了就用单独的，否则跟文案共用一套 */
    fun endpointOf(cfg: Config): Triple<String, String, String> {
        val base = cfg.visionBaseUrl.ifEmpty { cfg.chatBaseUrl }
        val key = cfg.visionApiKey.ifEmpty { cfg.chatApiKey }
        val model = cfg.visionModel.ifEmpty { cfg.chatModel }
        return Triple(base, key, model)
    }

    /**
     * 截屏 → 视觉模型 → [Digest]。
     *
     * 全程在后台线程，结果通过 [done] 回调（主线程无关，调用方自己切）。
     * 任何一步失败都给**人话**错误，而不是静默返回 null。
     */
    fun capture(service: AccessibilityService, cfg: Config, done: (Digest?, String?) -> Unit) {
        if (!available()) {
            done(null, "系统低于 Android 11，不支持无障碍截图")
            return
        }
        val (base, key, model) = endpointOf(cfg)
        if (key.isBlank()) {
            done(null, "还没配聊天模型密钥（视觉读屏要用它读图）")
            return
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, pool, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val b64 = try {
                        val hw = result.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        if (bmp == null) {
                            done(null, "截图解码失败")
                            return
                        }
                        val out = toJpegBase64(bmp)
                        bmp.recycle()
                        out
                    } catch (t: Throwable) {
                        done(null, "截图处理失败：${t.javaClass.simpleName} ${t.message ?: ""}")
                        return
                    }
                    askModel(base, key, model, b64, done)
                }

                override fun onFailure(errorCode: Int) {
                    // 常见：系统/厂商限制了截图、屏幕正在被别的安全界面挡住
                    done(null, "系统拒绝截图（错误码 $errorCode）—— 厂商限制或当前界面禁止截屏")
                }
            })
        } catch (t: Throwable) {
            done(null, "发起截图失败：${t.javaClass.simpleName} ${t.message ?: ""}")
        }
    }

    /** 缩到 [maxWidth] 宽 + JPEG 80 + base64（不缩的话 base64 能到 130KB 以上） */
    fun toJpegBase64(bmp: Bitmap, maxWidth: Int = MAX_WIDTH): String {
        val scaled = if (bmp.width > maxWidth && bmp.width > 0) {
            val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
        } else {
            bmp
        }
        val bos = ByteArrayOutputStream(256 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun askModel(base: String, key: String, model: String, b64: String, done: (Digest?, String?) -> Unit) {
        val messages = listOf(
            linkedMapOf<String, Any?>(
                "role" to "user",
                "content" to listOf(
                    linkedMapOf<String, Any?>("type" to "text", "text" to PROMPT),
                    linkedMapOf<String, Any?>(
                        "type" to "image_url",
                        "image_url" to linkedMapOf<String, Any?>("url" to "data:image/jpeg;base64,$b64")
                    )
                )
            )
        )
        val r = ChatHttp.completeMessages(base, key, model, messages, TIMEOUT_MS, 1200, 0.0)
        when (r) {
            is ChatResult.Err -> done(null, "视觉读屏失败：" + r.message)
            is ChatResult.Ok -> {
                val d = parse(r.text)
                if (d == null) {
                    AppLog.add("视觉读屏解析失败，原始返回：" + r.text.take(300))
                    done(null, "模型没按格式返回（已记日志）")
                } else {
                    done(d, null)
                }
            }
        }
    }

    /**
     * 让模型"念"截图的提示词。
     *
     * 设计要点：
     * - **只要 JSON**，不要散文：解析成本最低，出错时也一眼看得出
     * - 明确"右侧=我 / 左侧=对方"：这是本项目判定 mine 的唯一依据
     * - 明确"原样抄写，不要翻译/改写/补全"：模型很容易顺手润色，那会污染后面的判读
     * - 图片/语音/文件用方括号占位：让"这条是图片"这件事也能进上下文
     */
    val PROMPT: String = buildString {
        append("你在做「微信截图转文字」。把这张安卓微信聊天截图里**可见的**内容转成结构化数据。\n")
        append("只输出一个 JSON 对象，不要 markdown 代码块、不要任何解释：\n")
        append("""{"title":"会话标题","messages":[{"mine":false,"text":"消息原文"}]}""").append('\n')
        append("规则：\n")
        append("1. title：**必须**填标题栏中间那个名字（单聊＝对方昵称；群聊照抄，形如「项目组 (8)」）。\n")
        append("   标题栏在屏幕**最上方那一行**，字号很小，请专门抬头看一眼 —— 它一定有名字。\n")
        append("   只有整张图里确实看不到标题栏时才填空串；不要写「会话标题」「微信」这类占位。\n")
        append("2. messages：按屏幕**从上到下**排列（旧的在前，新的在后）。\n")
        append("3. mine：true = 这条是**我**发的（右侧、绿色气泡）；false = 对方发的（左侧、白色气泡）。\n")
        append("4. text：只写气泡里的正文。不要写时间、昵称、「以下为新消息」这类系统提示。\n")
        append("5. 图片/语音/视频/文件/表情消息，分别写 [图片] [语音] [视频] [文件] [表情]。\n")
        append("6. **原样抄写**：不要翻译、不要改写、不要补全、不要加标点。看不清的字用 □ 代替。\n")
        append("7. 输入框里还没发出去的字**不算**消息，不要收进去。\n")
        append("8. 确实没有消息就给空数组。")
    }

    /**
     * 解析模型返回。宽容到底：
     * 去掉 ``` 代码块 → 取第一个 `{` 到最后一个 `}` → 解析 → 缺字段当空。
     * 解析不出来返回 null（调用方会记日志），**绝不抛异常**。
     */
    fun parse(raw: String): Digest? {
        val json = extractJson(raw) ?: return null
        val root = try {
            Json.obj(Json.parse(json))
        } catch (t: Throwable) {
            return null
        }
        if (root.isEmpty()) return null

        val title = Json.str(root["title"]).trim()
        val msgs = Json.list(root["messages"]).mapNotNull { item ->
            val o = Json.obj(item)
            val text = Json.str(o["text"]).trim()
            if (text.isEmpty()) null else ScreenMsg(truthy(o["mine"]), ScreenRules.clean(text))
        }
        if (title.isEmpty() && msgs.isEmpty()) return null
        return Digest(title, msgs)
    }

    /** 从一堆废话里抠出 JSON 对象：优先 ``` 块，其次第一个 { 到最后一个 } */
    fun extractJson(raw: String): String? {
        val text = raw.replace("\r", "").trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
        val body = fenced?.groupValues?.get(1)?.trim() ?: text
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return body.substring(start, end + 1)
    }

    /** mine 字段宽容判定：true / "true" / 1 / "是" 都算我方 */
    private fun truthy(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.trim().lowercase() in setOf("true", "1", "yes", "y", "是", "我")
        else -> false
    }
}
