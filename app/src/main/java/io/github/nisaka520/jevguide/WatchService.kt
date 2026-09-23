package io.github.nisaka520.jevguide

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
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
        if (Config(this).showNotification) showNotification() else cancelNotification()
        registerButton()
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

    private fun registerButton() {
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
            AppLog.add(
                "无障碍按钮：" + if (accessibilityButtonController.isAccessibilityButtonAvailable) "可用"
                else "当前不可用（去系统设置里把本服务设成无障碍快捷键）"
            )
        } catch (e: Exception) {
            AppLog.add("注册无障碍按钮失败：${e.javaClass.simpleName}")
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
        val d = WeChatReader.capture(this, cfg.linkQuotes) ?: return
        val t = d.latestPeerMessage()?.text ?: return
        if (t == lastPeerText) return                       // 没有新消息，别重复判读
        lastPeerText = t
        AppLog.add("自动模式：检测到新消息")
        Analyzer.run(this, d, manual = false)
    }

    /** 手动触发（磁贴 / 无障碍按钮 / 通知按钮 / 设置页） */
    fun analyzeNow(manual: Boolean = true) {
        val cfg = Config(this)
        val d = WeChatReader.capture(this, cfg.linkQuotes)
        if (d == null) {
            if (manual) Toast3.toast(this, "现在的前台不是微信")
            return
        }
        Analyzer.run(this, d, manual)
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
        AppLog.add("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
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
