package io.github.nisaka520.jevguide

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志：Logcat + 内存环形缓冲（设置页能直接看最近 200 条）。
 * 无障碍服务出问题时没有界面可看，这段缓冲就是唯一现场。
 */
object AppLog {

    private const val TAG = "JevGuide"
    private const val MAX = 200

    private val ring = ArrayList<String>(MAX)
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        val line = stamp.format(Date()) + "  " + msg
        Log.i(TAG, msg)
        ring.add(line)
        while (ring.size > MAX) ring.removeAt(0)
    }

    @Synchronized
    fun text(): String = if (ring.isEmpty()) "(暂无日志)" else ring.joinToString("\n")

    @Synchronized
    fun clear() = ring.clear()
}
