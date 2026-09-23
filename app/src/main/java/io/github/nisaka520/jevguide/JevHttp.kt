package io.github.nisaka520.jevguide

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** 调 Jev 的结果：要么拿到响应体，要么带一句人话的错误 */
sealed class JevResult {
    data class Ok(val body: String) : JevResult()
    data class Err(val message: String) : JevResult()
}

/**
 * 唯一的网络出口：POST https://api.typesafe.ai/v1/systemone
 *
 * 用 HttpURLConnection 而不是 OkHttp/Retrofit —— 整包运行期零第三方依赖，
 * 无障碍服务这种常驻进程里少带一个库就少一份自己控制不了的东西。
 */
object JevHttp {

    const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"

    fun analyze(apiKey: String, requestBody: String, timeoutMs: Int = 25000): JevResult =
        post(apiKey, requestBody, timeoutMs)

    /** 设置页"测试密钥"用：一条极小的请求，只验证密钥与连通性 */
    fun test(apiKey: String): JevResult {
        val state = Json.write(
            mapOf(
                "关系" to "普通朋友",
                "对方性别" to "未知",
                "会话类型" to "单聊",
                "最近对话" to emptyList<String>(),
                "待分析消息" to "在吗？"
            )
        )
        val body = Prompt.requestJson(state, Prompt.MODELS[0], "zh")
        return post(apiKey, body, 20000)
    }

    private fun post(apiKey: String, body: String, timeoutMs: Int): JevResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpsURLConnection).apply {
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
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            if (code in 200..299) {
                JevResult.Ok(text)
            } else {
                JevResult.Err(explain(code, text))
            }
        } catch (e: java.net.SocketTimeoutException) {
            JevResult.Err("请求超时（网络慢或接口忙），过一会儿再试")
        } catch (e: javax.net.ssl.SSLException) {
            JevResult.Err("TLS 握手失败：${e.message ?: ""}")
        } catch (e: Exception) {
            JevResult.Err("网络失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun explain(code: Int, body: String): String {
        val brief = body.replace(Regex("""\s+"""), " ").take(160)
        return when (code) {
            401, 403 -> "密钥无效或被撤销（HTTP $code）—— 去 console.typesafe.ai/api-keys 确认"
            404 -> "接口地址 404，检查是不是官方端点变了"
            429 -> "被限流了（HTTP 429），等一会儿再试"
            in 500..599 -> "接口服务端错误 HTTP $code"
            else -> "HTTP $code" + if (brief.isEmpty()) "" else "：$brief"
        }
    }
}
