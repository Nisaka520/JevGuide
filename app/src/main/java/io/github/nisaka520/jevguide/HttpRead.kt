package io.github.nisaka520.jevguide

import java.io.InputStream
import java.io.InputStreamReader

/**
 * 读 HTTP 响应体，**带上限**。
 *
 * 为什么不能直接 `readText()`：响应体是外部输入（Jev 接口、以及用户自己填的聊天模型端点），
 * 遇到畸形或恶意的大响应时会一次性全读进内存 —— 上限原来是"没有"，等于把 OOM 的机会交给对方。
 * 而 `OutOfMemoryError` 是 Error，`catch (Exception)` 接不住，会把判读线程整个带走。
 *
 * 超长时**截断**而不是报错：调用方拿到的仍是可诊断的一小段（错误文案本来就只 take 160 字）。
 */
internal object HttpRead {

    /**
     * 单次响应最多读这么多字符。
     *
     * 正常响应几 KB（Jev 的 answers 更小、文案也就几百字），512K 是给"模型抽风长篇大论"留的余量；
     * 按 UTF-16 算最多约 1MB 内存，比"不设限"安全得多。
     */
    const val MAX_CHARS = 512 * 1024

    fun text(stream: InputStream, max: Int = MAX_CHARS): String {
        val sb = StringBuilder(minOf(max, 64 * 1024))
        InputStreamReader(stream, Charsets.UTF_8).use { r ->
            val buf = CharArray(8192)
            while (sb.length < max) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, minOf(n, max - sb.length))
            }
        }
        return sb.toString()
    }
}
