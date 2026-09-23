package io.github.nisaka520.jevguide

/**
 * 把字符串里的 `**强调**` 解析成「去掉星号的正文 + 需要加粗的区间」。
 *
 * 为什么需要它：设置页的说明文字里写了不少 `**…**`（本意是加粗），但 TextView 不认 markdown，
 * 结果界面上到处都是裸露的星号。删掉星号最简单，但那些强调是有用的
 * （「不属于任何内置预设」这种），删了等于把语气也删了 —— 所以保留正文、把强调变成真的加粗。
 *
 * 纯函数、不碰任何 Android 类：渲染那一层只剩三行胶水，这个解析逻辑能在 JVM 单测里被钉死。
 * 星号不成对时**原样保留**，绝不吞字符。
 */
object Emphasis {

    /** @return 正文（成对的星号已去掉）+ 需要加粗的区间（按正文下标，闭区间） */
    fun parse(t: String): Pair<String, List<IntRange>> {
        val out = StringBuilder(t.length)
        val bolds = ArrayList<IntRange>()
        var i = 0
        while (i < t.length) {
            val open = t.indexOf("**", i)
            if (open < 0) {
                out.append(t, i, t.length)
                break
            }
            val close = t.indexOf("**", open + 2)
            // 不成对、或者空强调（****）：原样搬过去，继续往后找
            if (close < 0 || close == open + 2) {
                out.append(t, i, open + 2)
                i = open + 2
                continue
            }
            out.append(t, i, open)
            val start = out.length
            out.append(t, open + 2, close)
            bolds.add(start until out.length)
            i = close + 2
        }
        return out.toString() to bolds
    }
}
