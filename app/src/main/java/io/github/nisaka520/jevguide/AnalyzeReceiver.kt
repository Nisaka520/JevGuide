package io.github.nisaka520.jevguide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知栏上两个按钮的落地：不能直接起 Activity（会跳界面），
 * 所以走广播 → 找服务实例 → 立刻判读 / 导出一份抓屏诊断。
 */
class AnalyzeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val svc = WatchService.instance
        if (svc == null) {
            Toast3.toast(context, "无障碍服务没在运行：设置里开启「弦外之音 · 微信判读」", true)
            return
        }
        when (action) {
            ACTION_NOW -> svc.analyzeNow(true)
            ACTION_DUMP -> svc.dumpNow()
        }
    }

    companion object {
        const val ACTION_NOW = "io.github.nisaka520.jevguide.NOW"
        const val ACTION_DUMP = "io.github.nisaka520.jevguide.DUMP"
    }
}
