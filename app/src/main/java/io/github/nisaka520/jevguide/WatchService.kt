package io.github.nisaka520.jevguide

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.io.File

/**
 * 无障碍服务：**只订阅微信**，只读窗口内容。
 *
 * - 自动模式（默认关）：检测到对方最后一条消息变了 → 防抖 → 判读；同一条消息不会重复判读。
 * - 手动模式：无障碍按钮（系统快捷键）/ 通知栏磁贴 / 常驻通知上的按钮 → 立刻判读当前屏。
 * - 不做的事：不点击、不填字、不发送、不截屏、不读其它 App。
 */
class WatchService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastPeerText: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.add("无障碍服务已连接 v" + BuildConfig.VERSION_NAME)
        val cfg = Config(this)
        if (cfg.showNotification) showNotification() else cancelNotification()
        syncButton()
        restoreOverlay(cfg)
    }

    /** 服务重启后把上次的攻略度浮层照原样挂回去，不用等下一次判读 */
    private fun restoreOverlay(cfg: Config) {
        if (!cfg.overlayEnabled) {
            ScoreOverlay.hide()
            return
        }
        val text = cfg.lastOverlayText.ifEmpty { ScoreOverlay.format("微信", null, null) }
        val pct = cfg.lastOverlayPercent.takeIf { it >= 0 }
        ScoreOverlay.show(this, cfg, text, pct)
    }

    /** 系统无障碍按钮/快捷键（三指长按之类）：注册回调才会触发 */
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            AppLog.add("无障碍按钮触发")
            analyzeNow(true)
        }

        override fun onAvailabilityChanged(
            controller: AccessibilityButtonController,
            available: Boolean
        ) {
            AppLog.add("无障碍按钮可用性：" + available)
        }
    }

    /**
     * 注册/注销系统无障碍快捷按钮。
     *
     * ⚠ 两个开关都得管，少一个都不行（实测踩过）：
     * 1. `a11y_config.xml` 里的 `flagRequestAccessibilityButton` —— 只要配置里带这个标志位，
     *    **系统就会自己画一个悬浮圆钮**，跟本 App 注不注册回调无关（`dumpsys` 里 `requestA11yBtn=true`）。
     *    所以这里在运行时用 `setServiceInfo()` 把它置位/清掉。
     * 2. `AccessibilityButtonCallback` 的注册 —— 决定点下去有没有反应。
     *
     * 默认全关：浮条点一下就是同一个功能，屏幕上挂两个只会让人以为程序出了 bug。
     */
    /**
     * 幂等「启动」：先把上一次留下的东西清干净，再按当前配置重新起一遍。
     *
     * 为什么不能真的把服务杀掉重启：无障碍服务由系统托管，App 只能用 disableSelf() 关掉自己，
     * 而**关掉之后没法自己再打开**（必须用户手动去系统设置里勾回来）—— 那样「启动」按钮就变成
     * 「点一下还得去系统设置勾一次」，比不点更糟。
     * 所以这里做的是清旧 + 重挂：浮层、无障碍按钮标志都按最新配置重来一遍，
     * 效果上等于「杀掉前面那个、起了个新的」，但不需要用户再授权。
     *
     * @return 给界面 toast 的一句话
     */
    fun restartSelf(): String {
        return try {
            ScoreOverlay.hide()
            syncButton()
            val cfg = Config(this)
            // 点了启动就要**当场看见**浮条，而不是非得退出这个软件去微信里找。
            // 之前浮条被 MainActivity.onStart 里的 setSuppressed(true) 压着，点完启动屏幕上
            // 什么都没有，用户会以为没生效（用户反馈：希望点了就显现，而不是退出这个软件）。
            ScoreOverlay.setSuppressed(cfg, false)
            restoreOverlay(cfg)
            // 还没判读过就没有结果可恢复 —— 给个占位，至少让人看见「它起来了」
            if (cfg.overlayEnabled && !ScoreOverlay.isShowing()) {
                ScoreOverlay.show(this, cfg, "已启动 · 去微信点我识别", null)
            }
            AppLog.add("重新启动：已清理旧浮层与按钮标志，并按当前配置重挂")
            if (!cfg.overlayEnabled) {
                "已重新启动（浮条开关是关的，去「常驻悬浮条」打开）"
            } else {
                "已启动，浮条已经出现在屏幕上"
            }
        } catch (t: Throwable) {
            AppLog.add("重新启动失败：${t.javaClass.simpleName} ${t.message ?: ""}")
            "重新启动失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 关闭：收起浮条、撤掉常驻通知、把自己从系统里注销（disableSelf）。
     *
     * 为什么是 disableSelf 而不是「杀进程」：Android 不允许 App 杀自己的进程（那是系统的事），
     * 而且杀进程也关不掉无障碍服务 —— 服务由系统托管，只有 disableSelf() 能停掉它。
     * 代价是系统设置里的勾会一起取消，下次要用必须重新点一次「启动」去授权，
     * 这条流程已经在首页的「启动」里做好了（没授权就跳系统设置）。
     */
    fun shutdown(): String {
        var msg = "已关闭"
        try {
            ScoreOverlay.hide()
            cancelNotification()
            disableSelf()
            msg = "已关闭：浮条与通知都收起来了，无障碍服务也注销了"
            AppLog.add("已关闭：浮条收起、通知撤掉、无障碍服务已注销")
        } catch (t: Throwable) {
            msg = "关闭时出错：" + t.javaClass.simpleName
            AppLog.add("关闭失败：" + t.javaClass.simpleName + " " + (t.message ?: ""))
        }
        return msg
    }

    fun syncButton() {
        val want = Config(this).a11yButtonEnabled
        try {
            val info = serviceInfo
            if (info != null) {
                info.flags = if (want) {
                    info.flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
                } else {
                    info.flags and AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON.inv()
                }
                setServiceInfo(info)
            }
            if (want) {
                accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
                AppLog.add(
                    "无障碍按钮：已注册，" + if (accessibilityButtonController.isAccessibilityButtonAvailable) "可用"
                    else "但系统没开（去系统设置 → 无障碍 → 无障碍快捷方式 里绑到本服务）"
                )
            } else {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
                AppLog.add("无障碍按钮：已关（清掉 requestA11yBtn，系统那个悬浮圆钮会消失）")
            }
        } catch (e: Exception) {
            AppLog.add("无障碍按钮切换失败：${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.packageName?.toString() != WeChatReader.WECHAT_PKG) return
        val cfg = Config(this)
        if (!cfg.autoAnalyze) return
        if (Analyzer.busy()) return
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { autoRun() }
        pending = r
        handler.postDelayed(r, cfg.autoDebounceMs.toLong())
    }

    private fun autoRun() {
        pending = null
        val cfg = Config(this)
        val d = WeChatReader.capture(this, cfg.linkQuotes)
        val t = d?.latestPeerMessage()?.text
        if (t == null) {
            // 无障碍树空着（微信屏蔽了无障碍）→ 自动模式下也退到视觉读屏，否则自动判读等于失效
            if (d != null && cfg.readMode != "a11y") {
                AppLog.add("自动模式：无障碍树为空，改用视觉读屏")
                analyzeByVision(cfg, manual = false)
            }
            return
        }
        if (t == lastPeerText) return                       // 没有新消息，别重复判读
        lastPeerText = t
        AppLog.add("自动模式：检测到新消息")
        Analyzer.run(this, d, manual = false)
    }

    /** 手动触发（磁贴 / 无障碍按钮 / 通知按钮 / 设置页） */
    fun analyzeNow(manual: Boolean = true) {
        val cfg = Config(this)
        when (cfg.readMode) {
            "vision" -> analyzeByVision(cfg, manual)
            "a11y" -> analyzeByTree(cfg, manual)
            else -> {
                // auto：先走无障碍树（免费、快）；微信屏蔽了树的时候它必然是空的，那就退到视觉读屏
                val d = WeChatReader.capture(this, cfg.linkQuotes)
                if (d == null) {
                    if (manual) Toast3.toast(this, "现在的前台不是微信")
                    return
                }
                if (d.latestPeerMessage() != null) {
                    Analyzer.run(this, d, manual)
                    return
                }
                AppLog.add("无障碍树里没有对方消息（微信可能屏蔽了无障碍），自动改用视觉读屏")
                analyzeByVision(cfg, manual)
            }
        }
    }

    /** 老路子：从无障碍树读 */
    private fun analyzeByTree(cfg: Config, manual: Boolean) {
        val d = WeChatReader.capture(this, cfg.linkQuotes)
        if (d == null) {
            if (manual) Toast3.toast(this, "现在的前台不是微信")
            return
        }
        Analyzer.run(this, d, manual)
    }

    /**
     * 新路子：截屏 → 视觉模型念成文字。
     *
     * 为什么要有它：微信 8.0.76 不给无障碍树（连系统 uiautomator 都读到 0 个文字节点），
     * 但截屏能拍到 —— 让一个看得懂图的模型把画面转成「谁说了什么」，后面的判读流程完全不用改。
     */
    private fun analyzeByVision(cfg: Config, manual: Boolean) {
        if (!VisionReader.available()) {
            if (manual) Toast3.toast(this, "系统低于 Android 11，视觉读屏用不了（改用无障碍树）", true)
            return
        }
        if (!cfg.hasVisionKey()) {
            if (manual) Toast3.toast(this, "视觉读屏要配聊天模型密钥：设置 → 聊天模型", true)
            return
        }
        if (cfg.showAnalyzing) Toast3.toast(this, "正在截图识别…")
        VisionReader.capture(this, cfg) { d, err ->
            if (d == null) {
                AppLog.add("视觉读屏失败：" + (err ?: "未知原因"))
                Toast3.toast(this, err ?: "视觉读屏失败", true)
            } else {
                AppLog.add("视觉读屏：标题=「${d.title}」读到 ${d.msgs.size} 条（对方 ${d.msgs.count { !it.mine }} 条）")
                Analyzer.run(this, d, manual)
            }
        }
    }

    /**
     * 抓屏诊断：把当前微信窗口的无障碍树原样存一份，供排查"读不到消息"。
     *
     * 只在本地落盘 + 存进设置里，**不会自动发出去**；要发给作者得自己在设置页点「分享」。
     */
    fun dumpNow() {
        try {
            val text = WeChatReader.dump(this)
            val cfg = Config(this)
            cfg.lastDump = text
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "jev-dump-" + System.currentTimeMillis() + ".txt")
            f.writeText(text)
            cfg.lastDumpPath = f.absolutePath
            val n = text.count { it == '\n' }
            AppLog.add("抓屏诊断已保存：${f.absolutePath}（$n 行）")
            Toast3.toast(this, "诊断已保存（$n 行）·  回「Jev攻略」设置页点『分享最近诊断』", true)
        } catch (e: Exception) {
            AppLog.add("抓屏诊断失败：${e.message}")
            Toast3.toast(this, "诊断失败：" + e.message, true)
        }
    }

    /** 延时抓诊断：给"点完按钮再切回微信"留出时间 */
    fun dumpAfter(delayMs: Long) {
        handler.postDelayed({ dumpNow() }, delayMs)
    }

    override fun onInterrupt() {
        AppLog.add("无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        cancelNotification()
        ScoreOverlay.hide()          // 服务没了，浮层也不该留在屏幕上
        AppLog.add("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        ScoreOverlay.hide()
        super.onDestroy()
    }

    // ── 常驻通知：一个顺手的手动入口，可关 ──

    private fun showNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CH_ID, "运行状态", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Jev攻略运行时的常驻入口"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                this, 1, Intent(this, SettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val runNow = PendingIntent.getBroadcast(
                this, 2, Intent(this, AnalyzeReceiver::class.java).setAction(AnalyzeReceiver.ACTION_NOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val dumpNow = PendingIntent.getBroadcast(
                this, 3, Intent(this, AnalyzeReceiver::class.java).setAction(AnalyzeReceiver.ACTION_DUMP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Jev攻略已就绪")
                .setContentText("在微信里点一下就判读当前消息")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_view, "判读一下", runNow).build())
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_search, "诊断抓屏", dumpNow).build())
                .build()
            nm.notify(NOTI_ID, n)
        } catch (e: Exception) {
            AppLog.add("通知创建失败：${e.message}")
        }
    }

    private fun cancelNotification() {
        try {
            getSystemService(NotificationManager::class.java).cancel(NOTI_ID)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CH_ID = "jev_bystander"
        const val NOTI_ID = 1001

        @Volatile
        var instance: WatchService? = null
            private set
    }
}
