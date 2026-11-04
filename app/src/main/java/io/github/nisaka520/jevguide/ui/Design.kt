package io.github.nisaka520.jevguide.ui

import android.content.Context
import androidx.annotation.AttrRes
import com.google.android.material.R as MaterialR

/**
 * 设计变量唯一来源 —— 全 App 的字号、间距、圆角、语义色、分区 accent 都只认这里。
 *
 * ## 视觉方向：「仪表台」
 *
 * 弦外之音是一台挂在后台干活的仪器：无障碍读屏 → Jev 判读 → 攻略度仪表。
 * 首页的任务是"确认能不能用，然后一键启动"，所以整套视觉借的是**仪器面板**的语言：
 * 状态是一排可扫的灯（红绿灯语义色），主动作是一颗总开关（实心按钮），
 * 设置入口是编号行（系统设置的秩序感），装饰为零。
 *
 * 分工（刻意如此，别合并）：
 * - **固定语义色**（红绿灯 + 分区 accent）：写死在这里。它们表达"好坏/归属"，
 *   跟着壁纸变成粉色就失去意义 —— [io.github.nisaka520.jevguide.JevApp] 打开的
 *   Material You 动态取色**故意不覆盖**这一组。
 * - **动态主题组**（surface/onSurface/container…）：走主题 attr 解析。
 *   Android 12+ 上跟壁纸走，与设置页/结果页保持同一套底色系统。
 *
 * 值全部按「仪表台」方向重新校定，不沿用任何旧色值；
 * 与 [io.github.nisaka520.jevguide.ScoreOverlay.colorOf]（单测钉死的历史值）只对齐**阈值口径**（≥70/≥40），不对齐色值。
 */
object Design {

    // ────────────────────────── 字阶（5 档，全 App 文字只允许这 5 个字号）

    object Type {
        /** 首屏主标题、状态词 —— 一屏最多一两处 */
        const val DISPLAY = 32f
        /** 分组/区块标题（设置子页、结果页大标题用） */
        const val TITLE = 20f
        /** 正文、卡片标题、按钮文字 */
        const val BODY = 14.5f
        /** 辅助说明、标签、脚注 */
        const val CAPTION = 12.5f
        /** 仅限一屏一两处的微注（如"点空白处关闭"）—— 正文绝不使用 */
        const val MICRO = 11f
    }

    // ────────────────────────── 间距阶梯（5 档：4/8/12/16/24）

    object Space {
        /** 行内微调：标签与值之间 */
        const val XS = 4
        /** 同组元素之间 */
        const val S = 8
        /** 组内行距 */
        const val M = 12
        /** 区块内边距 / 组外距 */
        const val L = 16
        /** 大区块之间、页面边距 */
        const val XL = 24
    }

    // ────────────────────────── 圆角（4 档）

    object Radius {
        /** 按钮、小控件 —— 仪器面板的"开关"手感，比 MD3 默认方一点 */
        const val SM = 10
        /** 入口行卡片 */
        const val MD = 16
        /** 大面板（就绪面板、英雄区） */
        const val LG = 24
        /** 胶囊（浮条同语言；GradientDrawable 会自动钳到半高） */
        const val PILL = 999
    }

    // ────────────────────────── 固定语义色：红绿灯（刻意不走动态取色）

    /**
     * 分数三档的固定口径（与浮条/结果页一致）：**≥70 好 / ≥40 中 / 其余差，null=还没判**。
     * 夜间换提亮一档 —— 深底上浅色档的浅色变体才看得清（浅色值放深底上发闷）。
     */
    fun scoreGood(night: Boolean): Int = if (night) 0xFF3FD68F.toInt() else 0xFF147A4E.toInt()
    fun scoreMid(night: Boolean): Int = if (night) 0xFFF2C14E.toInt() else 0xFF9A6A00.toInt()
    fun scoreBad(night: Boolean): Int = if (night) 0xFFEC7A70.toInt() else 0xFFB33530.toInt()

    /** 无分数/未判读时的中性灰（灯不亮的状态） */
    fun scoreNeutral(night: Boolean): Int = if (night) 0xFF8D99A6.toInt() else 0xFF7D8894.toInt()

    /** 攻略度 → 三档色。所有分数类界面（浮条/结果页/首页状态）共用这一个入口 */
    fun scoreColor(percent: Int?, night: Boolean): Int = when {
        percent == null -> scoreNeutral(night)
        percent >= 70 -> scoreGood(night)
        percent >= 40 -> scoreMid(night)
        else -> scoreBad(night)
    }

    // ────────────────────────── 分区 accent（7 个设置分区各一色，低饱和、日夜两套）

    /**
     * 分区身份色：只用在编号、小色标上（剂量预算 ≤ 画面 10%），
     * 让用户从首页到设置页靠同一颜色认出"这是哪一分区"。
     */
    enum class Section { A11Y, OVERLAY, KEY, RELATION, MEMORY, ADV, ABOUT }

    fun accent(section: Section, night: Boolean): Int = when (section) {
        Section.A11Y -> if (night) 0xFF5BD0DB.toInt() else 0xFF0E7490.toInt()       // 青：无障碍/连接
        Section.OVERLAY -> if (night) 0xFFBEDA5E.toInt() else 0xFF4E7A0A.toInt()    // 黄绿：常驻悬浮条
        Section.KEY -> if (night) 0xFF8AB2F8.toInt() else 0xFF1D5DC6.toInt()        // 蓝：密钥与模型
        Section.RELATION -> if (night) 0xFFF08CA6.toInt() else 0xFFB03A5B.toInt()   // 玫红：关系
        Section.MEMORY -> if (night) 0xFFB49FF2.toInt() else 0xFF6D4FC2.toInt()     // 紫：记忆
        Section.ADV -> if (night) 0xFFF3A45C.toInt() else 0xFFA8540E.toInt()        // 橙：高级调校
        Section.ABOUT -> if (night) 0xFF97A2B1.toInt() else 0xFF566173.toInt()      // 石板灰：关于
    }

    // ────────────────────────── 动态主题组（attr 解析，Material You 生效时跟壁纸走）

    /** 页面底色（windowBackground 同源） */
    fun surface(ctx: Context): Int = UiKit.attr(ctx, MaterialR.attr.colorSurface, 0xFFF5F7F7.toInt())
    /** 主文字 */
    fun onSurface(ctx: Context): Int = UiKit.attr(ctx, MaterialR.attr.colorOnSurface, 0xFF20242A.toInt())
    /** 次级文字 */
    fun onSurfaceVariant(ctx: Context): Int = UiKit.attr(ctx, MaterialR.attr.colorOnSurfaceVariant, 0xFF5A6470.toInt())
    /** 高层容器（入口行卡片） */
    fun containerHigh(ctx: Context): Int = UiKit.attr(ctx, MaterialR.attr.colorSurfaceContainerHigh, 0xFFE9ECEC.toInt())
    /** 低层容器（大面板底） */
    fun containerLow(ctx: Context): Int = UiKit.attr(ctx, MaterialR.attr.colorSurfaceContainerLow, 0xFFEFF2F2.toInt())
    /** 描边/分隔 */
    fun outline(ctx: Context): Int = UiKit.attr(ctx, MaterialR.attr.colorOutline, 0xFF7C8489.toInt())
    /** 动作色（主按钮） */
    fun primary(ctx: Context): Int = UiKit.attr(ctx, MaterialR.attr.colorPrimary, 0xFF5E6B6A.toInt())
    /** 动作色上的文字 */
    fun onPrimary(ctx: Context): Int = UiKit.attr(ctx, MaterialR.attr.colorOnPrimary, 0xFFFFFFFF.toInt())
}
