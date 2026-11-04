package io.github.nisaka520.jevguide.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.nisaka520.jevguide.R

/**
 * 主题/系统环境的小工具：夜间判定、edge-to-edge 内边距安装、页面转场双路径封装。
 *
 * 只收"跟 Activity 生命周期/系统环境打交道"的那部分样板，不掺任何业务判断。
 */
// ────────────────────────── 夜间判定

/**
 * 当前是否夜间模式。固定语义色（[Design]）靠它切日夜两套色值 ——
 * 那组色不走动态取色，只能自己判。
 */
fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

// ────────────────────────── edge-to-edge

/**
 * 把系统栏高度吃成 View 的内边距（targetSdk 35 起系统强制全面屏，
 * 状态栏会直接压在内容上）。顺带 `clipToPadding=false`：
 * 内容延伸到系统栏下面时，别把滚动到最后一屏的内容顶在导航条上方看不见。
 *
 * 装在滚动容器（ScrollView 的直接子层）上，一次安装全页生效。
 */
fun ViewGroup.installSystemBarInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    clipToPadding = false
}

// ────────────────────────── 转场（双路径）

/**
 * Android 14（API 34）起，targetSdk ≥ 34 的应用里 overridePendingTransition **会被系统忽略**
 * （实测「点进去完全没有动画」）；必须改用 overrideActivityTransition，
 * 且要在**转场发生前**（onCreate 里）注册才生效。
 * 老 API（<34）则相反：要在转场那一刻（startActivity/finish 之后）调 overridePendingTransition。
 *
 * 所以封装成两条路径四个函数：
 * - [registerOpenTransition]/[registerCloseTransition]：onCreate 里调，只对 API34+ 干活；
 * - [playForwardTransition]/[playBackTransition]：startActivity/finish 之后调，只对 API<34 干活。
 * 两边同时挂着，任何版本上动画都只有一份。
 */

/** 进入下一层的关闭方向注册（API34+）：新页自右滑入、旧页向左淡出 */
fun Activity.registerOpenTransition() {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN, R.anim.slide_in_right, R.anim.slide_out_left
        )
    }
}

/** 返回上一层的关闭方向注册（API34+）：本页向右滑出、下层自左回来 */
fun Activity.registerCloseTransition() {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.slide_in_left, R.anim.slide_out_right
        )
    }
}

/** startActivity 之后调（API<34 生效）：与 [registerOpenTransition] 同一对动画 */
@Suppress("DEPRECATION")
fun Activity.playForwardTransition() {
    if (Build.VERSION.SDK_INT < 34) {
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }
}

/** finish 之后调（API<34 生效）：与 [registerCloseTransition] 同一对动画 */
@Suppress("DEPRECATION")
fun Activity.playBackTransition() {
    if (Build.VERSION.SDK_INT < 34) {
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
