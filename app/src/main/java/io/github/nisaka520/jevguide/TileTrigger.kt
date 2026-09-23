package io.github.nisaka520.jevguide

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：下拉通知栏点一下 → 判读当前屏。
 * 这是"零悬浮窗"方案里最顺手的手动入口（另一条是无障碍快捷键）。
 */
class TileTrigger : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (WatchService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "攻略一下"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val svc = WatchService.instance
        if (svc == null) {
            Toast3.toast(this, "先开启无障碍服务（设置 → 无障碍 → 弦外之音）", true)
            return
        }
        svc.analyzeNow(true)
    }
}
