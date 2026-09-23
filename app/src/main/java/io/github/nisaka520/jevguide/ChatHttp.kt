package io.github.nisaka520.jevguide

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** 调聊天模型的结果：要么拿到一段文本，要么带一句人话的错误 */
sealed class ChatResult {
    data class Ok(val text: String) : ChatResult()
    data class Err(val message: String) : ChatResult()
}

/**
 * OpenAI 兼容的 `/chat/completions` 客户端（DeepSeek、OpenAI、本地 llama.cpp / Ollama 都是这一套）。
 *
 * 与 JevHttp 同源：只用 HttpURLConnection，整包运行期零第三方依赖。
 * 唯一刻意的区别是这里用它的**父类** HttpURLConnection 而不是 HttpsURLConnection ——
 * base_url 允许填 `http://127.0.0.1:8080/v1` 这类本地推理服务，写死 https 会把本地模型挡在门外。
 *
 * 错误一律翻译成人话，并且**必须**带上 HTTP code 与响应体开头：路径少个 /v1、模型名拼错、
 * 额度用完、密钥被撤销，这几种故障光看一句"请求失败"是查不出来的，用户把这句话贴出来就能定位。
 */
object ChatHttp {

    /**
     * 发一次 chat 补全请求，成功时返回模型文本（已 trim）。
     *
     * ⚠ base_url **要填到 /v1**，例如 `https://api.deepseek.com/v1` 或 `https://api.openai.com/v1`
     * （末尾有没有 `/` 都行）。这里**不会**自动补 `/v1`：自动猜会让"填错地址"表现成"偶发 404"，
     * 排查成本远高于让用户照抄一次文档。已经填成完整的 `.../chat/completions` 时直接沿用，不再拼一层。
     *
     * @param maxTokens 上限给足（900 够写 3 条短回复）；某些服务把它当硬上限，截断的文案不如没有。
     */
    fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        timeoutMs: Int = 30000,
        maxTokens: Int = 900,
        temperature: Double = 0.8
    ): ChatResult {
        // 先把"根本发不出去"的配置拦下来：这三条错误比任何 HTTP 报错都好懂
        if (baseUrl.isBlank()) return ChatResult.Err("base_url 没填")
        if (apiKey.isBlank()) return ChatResult.Err("API Key 没填")
        if (model.isBlank()) return ChatResult.Err("模型名没填")

        val messages = listOf(
            linkedMapOf<String, Any?>("role" to "system", "content" to system),
            linkedMapOf<String, Any?>("role" to "user", "content" to user)
        )
        val payload = linkedMapOf<String, Any?>(
            "model" to model,
            "messages" to messages,
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "stream" to false
        )

        val raw = post(endpoint(baseUrl), apiKey, Json.write(payload), timeoutMs)
        if (raw is ChatResult.Err) return raw
        val body = (raw as ChatResult.Ok).text

        val content = contentOf(body)
            ?: return ChatResult.Err("响应里没有 choices[0].message.content（HTTP 200）：" + brief(body))
        val text = content.trim()
        // 空文本比报错更坑：界面上会显示成"模型什么都没说"，不如直接说清是模型返回了空的
        return if (text.isEmpty()) ChatResult.Err("模型返回了空内容") else ChatResult.Ok(text)
    }

    /** 设置页"测试"用：极小请求，验证 base_url / key / model 三者可用 */
    fun test(baseUrl: String, apiKey: String, model: String): ChatResult =
        complete(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            system = "你是连通性自检助手，只按指令回复，不要多说一个字。",
            user = "回复两个字：可用",
            timeoutMs = 15000,
            maxTokens = 16,
            temperature = 0.0
        )

    /**
     * 端点拼接：`baseUrl.trimEnd('/') + "/chat/completions"`。
     * 用户可能直接从服务商文档里复制了完整地址，那就别再拼 —— 拼出 `.../chat/completions/chat/completions`
     * 的 404 最容易被误判成"服务商挂了"。
     */
    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** 发请求并交出**原始响应体**（Ok 里放的是还没解析的 body），解析交给 complete 做 */
    private fun post(url: String, apiKey: String, body: String, timeoutMs: Int): ChatResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            // 非 2xx 时 body 在 errorStream 里，那里面往往正是"为什么失败"的原文
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            if (code in 200..299) ChatResult.Ok(text) else ChatResult.Err(explain(code, text))
        } catch (e: java.net.SocketTimeoutException) {
            ChatResult.Err("请求超时（模型慢或网络差），调大超时或稍后再试")
        } catch (e: javax.net.ssl.SSLException) {
            ChatResult.Err("TLS 握手失败：${e.message ?: ""}".trim())
        } catch (e: Exception) {
            ChatResult.Err("网络失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 取 `choices[0].message.content`。
     * 少数服务（或开了多模态/推理的模型）把它给成数组，元素是 `{"type":"text","text":"..."}`，
     * 这种就按顺序把各段 text 拼起来 —— 不兼容这一步的话，用户只会看到"响应里没有 content"。
     */
    private fun contentOf(json: String): String? {
        val content = Json.at(Json.parse(json), "choices.0.message.content") ?: return null
        return when (content) {
            is String -> content
            is List<*> -> content.joinToString("") { seg ->
                when (seg) {
                    is String -> seg
                    is Map<*, *> -> Json.str(Json.at(seg, "text"))
                    else -> ""
                }
            }
            else -> Json.str(content)
        }
    }

    /** 响应体压成一行、截断，塞进错误信息里给人看 */
    private fun brief(body: String): String = body.replace(Regex("""\s+"""), " ").trim().take(160)

    private fun explain(code: Int, body: String): String {
        val head = when (code) {
            401, 403 -> "密钥无效或被撤销"
            404 -> "接口地址 404，检查 base_url 是否少/多了 /v1"
            429 -> "被限流，等一会儿再试"
            in 500..599 -> "服务端错误"
            else -> ""
        }
        // 404 那句自己就带了码，别写成"404……（HTTP 404）"这种叠字
        val withCode = when {
            head.isEmpty() -> "HTTP $code"
            head.contains(code.toString()) -> head
            else -> "$head（HTTP $code）"
        }
        val detail = brief(body)
        return if (detail.isEmpty()) withCode else "$withCode：$detail"
    }
}
