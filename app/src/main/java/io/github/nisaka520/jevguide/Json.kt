package io.github.nisaka520.jevguide

/**
 * 极简 JSON：解析 Jev 的 answers、读写本地联系人表。
 *
 * 为什么不引 Gson/Moshi：本项目的设计前提之一是**运行期零第三方依赖**
 * （包体小、构建不需要联网解析依赖、无障碍服务这条命根子上少一层黑盒）。
 * 需要的能力只有「解析任意 JSON」和「拼出 JSON」，一百多行足够了。
 */
object Json {

    fun parse(text: String): Any? = P(text).let { it.ws(); it.value() }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun list(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

    fun str(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    fun num(v: Any?): Double? = when (v) {
        is Double -> v
        is String -> v.toDoubleOrNull()
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }

    /** 取 obj 里 key 对应的值（key 支持 "a.b.2.c" 形式：对象用名字下钻，数组用下标） */
    fun at(root: Any?, path: String): Any? {
        var cur: Any? = root
        for (seg in path.split('.')) {
            val next: Any? = when (val c = cur) {
                is Map<*, *> -> c[seg]
                is List<*> -> seg.toIntOrNull()?.let { c.getOrNull(it) }
                else -> null
            }
            if (next == null) return null
            cur = next
        }
        return cur
    }

    /** 生成 JSON 字符串字面量（含引号） */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 把 map（值可为 String/Double/Boolean/Map/List）拼成 JSON 对象串；顺序即插入顺序 */
    fun write(v: Any?): String {
        val sb = StringBuilder()
        writeTo(sb, v)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> sb.append(quote(v))
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double, is Int, is Long, is Float -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, vv) in v) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(quote(k.toString())).append(':')
                    writeTo(sb, vv)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (vv in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, vv)
                }
                sb.append(']')
            }
            else -> sb.append(quote(v.toString()))
        }
    }

    /** 递归下降解析器 */
    private class P(private val s: String) {
        private var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> objValue()
                '[' -> arrValue()
                '"' -> strValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numValue()
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (s.startsWith(word, i)) {
                i += word.length
                return v
            }
            return numValue()
        }

        private fun objValue(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (i < s.length) {
                ws()
                val k = strValue()
                ws()
                if (i < s.length && s[i] == ':') i++
                m[k] = value()
                ws()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == '}') {
                    i++
                    break
                }
                i++
            }
            return m
        }

        private fun arrValue(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (i < s.length) {
                l.add(value())
                ws()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == ']') {
                    i++
                    break
                }
                i++
            }
            return l
        }

        private fun strValue(): String {
            ws()
            if (i >= s.length || s[i] != '"') return ""
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(i + 1, minOf(i + 5, s.length))
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }

        private fun numValue(): Double? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' ||
                        s[i] == 'e' || s[i] == 'E')
            ) i++
            if (i == start) {
                i++
                return null
            }
            return s.substring(start, i).toDoubleOrNull()
        }
    }
}
