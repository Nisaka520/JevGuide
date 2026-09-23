package io.github.nisaka520.jevguide

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把微信当前窗口的无障碍树变成 Digest。
 *
 * 只做"读"：不点击、不注入、不截屏。节点里没有文字就什么都没有 —— 这是本项目的
 * 明确取舍（另一条路是截图 + OCR，我们不做，包体和权限都省下来）。
 *
 * ⚠ 两个真机踩过的坑，写在最显眼的地方：
 *  1. **不要用 isVisibleToUser 当门槛**。微信某些版本的节点在无障碍树里可见、
 *     有文字、坐标也正常，但 isVisibleToUser 返回 false —— 一行都读不到，
 *     表现就是"这个窗口里没看到对方发的消息"。改为只看坐标是否合理。
 *  2. 包名必须精确等于 com.tencent.mm；分身/克隆版会变，那时要改订阅与这里。
 */
object WeChatReader {

    private const val MAX_NODES = 3000
    private const val MAX_DEPTH = 40

    private class Stats {
        var nodes = 0
        var withText = 0
        var invisible = 0
    }

    fun capture(service: AccessibilityService, linkQuotes: Boolean): Digest? {
        val root = service.rootInActiveWindow
        if (root == null) {
            AppLog.add("拿不到活动窗口（rootInActiveWindow 为 null）——微信在前台吗？")
            return null
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != WECHAT_PKG) {
            AppLog.add("当前前台不是微信（$pkg），跳过")
            return null
        }
        root.refresh()

        val rr = Rect()
        root.getBoundsInScreen(rr)
        val dm = service.resources.displayMetrics
        val w = if (dm.widthPixels > 0) dm.widthPixels else rr.width()
        val h = if (dm.heightPixels > 0) dm.heightPixels else rr.height()
        if (w <= 0 || h <= 0) {
            AppLog.add("屏幕尺寸异常（${w}x$h），放弃这次抓屏")
            return null
        }

        val lines = ArrayList<RawLine>(64)
        val st = Stats()
        walk(root, lines, 0, service.packageName.toString(), w, h, st)
        AppLog.add(
            "抓屏：节点 ${st.nodes} / 带文字 ${st.withText} / 采用 ${lines.size} 行" +
                "（屏幕 ${w}x$h，其中 isVisibleToUser=false 的 ${st.invisible} 个）"
        )

        val d = DigestBuilder.build(lines, w, h, linkQuotes = linkQuotes)
        if (d.latestPeerMessage() == null) {
            val sample = lines.take(14).joinToString("\n") { l ->
                "  [${l.left},${l.top}-${l.right},${l.bottom}] " + l.text.replace("\n", " ").take(48)
            }
            AppLog.add(
                "没认出对方消息：标题=「${d.title}」读到 ${d.msgs.size} 条；原始行前 14 条：\n" +
                    sample.ifEmpty { "  （一行都没读到 → 微信这版可能不给无障碍树文字）" }
            )
        }
        return d
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        out: MutableList<RawLine>,
        depth: Int,
        ownPkg: String,
        screenW: Int,
        screenH: Int,
        st: Stats
    ) {
        if (node == null || depth > MAX_DEPTH || st.nodes >= MAX_NODES) return
        try {
            st.nodes++
            val pkg = node.packageName?.toString().orEmpty()
            if (pkg == ownPkg) return

            val text = node.text?.toString().orEmpty()
                .ifEmpty { node.contentDescription?.toString().orEmpty() }
            if (text.isNotBlank()) {
                st.withText++
                if (!node.isVisibleToUser) st.invisible++
                val r = Rect()
                node.getBoundsInScreen(r)
                // 只看坐标是否落在屏幕上（不依赖 isVisibleToUser，见文件头注释）
                if (r.width() > 0 && r.height() > 0 &&
                    r.bottom > 0 && r.right > 0 && r.top < screenH && r.left < screenW
                ) {
                    out.add(RawLine(text, r.left, r.top, r.right, r.bottom))
                }
            }

            val n = node.childCount
            for (i in 0 until n) {
                if (st.nodes >= MAX_NODES) break
                walk(node.getChild(i), out, depth + 1, ownPkg, screenW, screenH, st)
            }
        } catch (e: Exception) {
            AppLog.add("遍历节点异常（depth=$depth）：${e.javaClass.simpleName}")
        }
    }

    // ────────────────────────── 抓屏诊断 ──────────────────────────

    /**
     * 把当前窗口的无障碍树原样导出来（给作者排查用）。
     *
     * 这是"读"的延伸，不做任何修改：输出每个节点的类名、viewId、坐标、可见性、
     * 可点击/可滚动标志和文字，**不包含**你的聊天内容以外的任何东西，
     * 也不会自动上传 —— 只有你自己点「分享」才会发出去。
     */
    fun dump(service: AccessibilityService): String {
        val sb = StringBuilder(96 * 1024)
        val dm = service.resources.displayMetrics
        sb.append("弦外之音抓屏诊断 v").append(BuildConfig.VERSION_NAME).append('\n')
        sb.append("时间：")
            .append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            .append('\n')
        sb.append("屏幕：").append(dm.widthPixels).append('x').append(dm.heightPixels)
            .append("  density=").append(dm.density).append('\n')

        val root = service.rootInActiveWindow
        if (root == null) {
            sb.append("rootInActiveWindow = null（微信没在前台？或者这版微信不给无障碍树）\n")
            return sb.toString()
        }
        sb.append("root: pkg=").append(root.packageName)
            .append(" class=").append(root.className)
            .append(" windowId=").append(root.windowId)
            .append(" childCount=").append(root.childCount).append('\n')
        sb.append("──── 节点树（缩进=层级）────\n")
        dumpNode(root, sb, 0, 0)
        sb.append("──── 结束 ────\n")
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, count: Int) {
        if (node == null || depth > MAX_DEPTH || count > MAX_NODES || sb.length > 400_000) return
        try {
            val r = Rect()
            node.getBoundsInScreen(r)
            val cls = node.className?.toString().orEmpty().substringAfterLast('.')
            val id = node.viewIdResourceName?.toString().orEmpty().substringAfterLast('/')
            repeat(depth) { sb.append("  ") }
            sb.append(cls.ifEmpty { "?" }).append('#').append(id.ifEmpty { "-" })
            sb.append(" [").append(r.left).append(',').append(r.top).append(']')
            sb.append('[').append(r.right).append(',').append(r.bottom).append(']')
            sb.append(" vis=").append(if (node.isVisibleToUser) 1 else 0)
            sb.append(" clk=").append(if (node.isClickable) 1 else 0)
            sb.append(" scr=").append(if (node.isScrollable) 1 else 0)
            val t = node.text?.toString().orEmpty()
            if (t.isNotEmpty()) sb.append(" text=\"").append(t.replace("\n", "\\n").take(80)).append('"')
            val cd = node.contentDescription?.toString().orEmpty()
            if (cd.isNotEmpty()) sb.append(" cd=\"").append(cd.replace("\n", "\\n").take(60)).append('"')
            sb.append('\n')
            for (i in 0 until node.childCount) {
                dumpNode(node.getChild(i), sb, depth + 1, count + 1)
            }
        } catch (e: Exception) {
            repeat(depth) { sb.append("  ") }
            sb.append("（读取异常 ").append(e.javaClass.simpleName).append("）\n")
        }
    }

    const val WECHAT_PKG = "com.tencent.mm"
}
