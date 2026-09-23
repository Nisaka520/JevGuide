package io.github.nisaka520.jevguide

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 唯一的输出方式：Toast。
 *
 * 结果**固定 3 条**（口径与插件版一致）；分析开始时那条"分析中…"是第 0 条，可在设置里关掉，
 * 关掉之后一次判读就是干干净净的 3 条。
 * 用 APPLICATION 上下文排队弹，避免 Activity 销毁后 Toast 丢失。
 */
object Toast3 {

    private val main = Handler(Looper.getMainLooper())
    private var showing = false

    fun showLines(app: Context, lines: List<String>, gapMs: Int, onDone: (() -> Unit)? = null) {
        val ctx = app.applicationContext
        showing = true
        lines.forEachIndexed { i, text ->
            main.postDelayed({
                safeToast(ctx, text)
                if (i == lines.lastIndex) {
                    showing = false
                    onDone?.invoke()
                }
            }, (gapMs.toLong() * i))
        }
    }

    fun toast(app: Context, text: String, long: Boolean = false) {
        main.post { safeToast(app.applicationContext, text, long) }
    }

    fun busy(): Boolean = showing

    private fun safeToast(ctx: Context, text: String, long: Boolean = false) {
        try {
            Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLog.add("Toast 失败：${e.message}")
        }
    }
}
