package io.github.nisaka520.jevguide

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 应用入口 —— 只干一件事：打开 **Material You 壁纸取色**。
 *
 * ## 为什么要它
 *
 * Android 12+ 会把用户壁纸的主色调算出一整套配色，App 用它就等于"自动跟着主人的手机换皮肤"，
 * 而且不花一行代码去画图。`applyToActivitiesIfAvailable` 内部自己判版本，
 * 低于 12 或用户关了取色时静默回落到 `themes.xml` 里那套青绿兜底色板 —— 不需要我们写分支。
 *
 * ## 为什么放在 Application 而不是每个 Activity
 *
 * 放这里一次性对所有 Activity 生效（含结果页），漏一个就会出现"设置页跟壁纸走了、结果页还是老配色"
 * 这种最难查的不一致。
 *
 * ⚠ 浮条（ScoreOverlay）不在覆盖范围内：它不是 Activity，而是无障碍服务加的窗口，
 * 所以它自己用 `ContextThemeWrapper` 套 `Theme.JevGuide.Overlay` 取色。
 */
class JevApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
