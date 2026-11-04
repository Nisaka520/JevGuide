package io.github.nisaka520.jevguide.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 统一组件工厂 —— [dp]/[attr] 的**唯一实现**，卡片/文本/按钮的唯一造型来源。
 *
 * ## 为什么所有方法都只要求任意 `Context`
 *
 * 常驻悬浮条（ScoreOverlay）是用**无障碍服务的 context** 套 `ContextThemeWrapper` 造 View 的，
 * 它拿不到 Activity。所以这里的工厂一律只调 `ctx.theme` / `ctx.resources`，
 * 不做任何 Activity 强转 —— 拿服务 context 直接用也不会崩。
 * （造 View 用哪个 context，调用方自己决定：Activity 场景传 Activity 本身即可。）
 *
 * ## 造型纪律
 *
 * - 卡片一律零阴影、统一圆角（层次靠容器色阶，不靠投影）
 * - 文字一律走 [Design.Type] 的 5 档字阶
 * - 间距一律走 [Design.Space] 的 5 档阶梯
 */
object UiKit {

    /** dp → px 唯一实现。四舍五入对齐物理像素，避免细线发虚 */
    fun dp(ctx: Context, v: Int): Int = Math.round(v * ctx.resources.displayMetrics.density)

    /**
     * 主题属性解析唯一实现。attr 解析不到（值为 0）时回退到 fallback ——
     * fallback 只该在主题没配 MD3 槽位时出现，正常路径永远走 attr。
     */
    fun attr(ctx: Context, @AttrRes id: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(id, tv, true) && tv.data != 0) tv.data else fallback
    }

    /** 文本工厂：字号只认 [Design.Type]，行距固定 1f 倍率 + dp 补偿（与全 App 一致的中文行间） */
    fun text(
        ctx: Context,
        s: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        lineSpacingDp: Int = 4,
    ): TextView = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(ctx, lineSpacingDp).toFloat(), 1f)
    }

    /** 竖排容器 */
    fun column(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    /** 横排容器 */
    fun row(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    /** 卡片工厂：零阴影 + 统一圆角 + 指定容器色。可点击性由调用方接（涟漪是 MaterialCardView 自带的） */
    fun card(ctx: Context, fill: Int, radiusDp: Int): MaterialCardView =
        MaterialCardView(ctx).apply {
            radius = dp(ctx, radiusDp).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(fill)
        }

    /**
     * 状态灯（LED 圆点）。刷新时调 [setColor] 换档即可，不用重建 View ——
     * 首页 onResume 重刷状态用的就是它。
     */
    class Led(context: Context, sizeDp: Int, color: Int) : View(context) {
        private val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        init {
            background = bg
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

        fun setColor(c: Int) {
            bg.setColor(c)
        }
    }

    /**
     * 按钮：filled=true 是主动作（实心），false 是次动作（描边）。
     * 圆角统一 [Design.Radius.SM] —— 仪器面板的"开关"手感。
     * 用 MaterialButton 而不是自画背景，是为了白拿涟漪与无障碍语义。
     */
    fun button(ctx: Context, label: String, filled: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(
            ctx, null,
            if (filled) com.google.android.material.R.attr.materialButtonStyle
            else com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            textSize = Design.Type.BODY
            isAllCaps = false
            cornerRadius = dp(ctx, Design.Radius.SM)
            setOnClickListener { onClick() }
        }

    // ────────────────────────── 设置页控件（阶段二扩展，同一套语法）──────────────────────────

    /**
     * 纯色圆角背景，给**不能套 MaterialCardView 的地方**当 background 用
     * （TextView 的机器输出块、风格 chips）。要建卡片本体请用 [card]。
     */
    fun surface(ctx: Context, fill: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(ctx, radiusDp).toFloat()
    }

    /**
     * 输入框：完整描边盒（OutlinedBox），标签浮在边框上，圆角与 [button] 同档（[Design.Radius.SM]）。
     *
     * 返回**外盒**（TextInputLayout）而不是内层框 —— 密码框的「小眼睛」开关长在外盒上；
     * 要读写文本走 `box.getEditText()`（内层框已挂上，非空）。
     * 密码框必配「小眼睛」：密钥 100 来个字符，遮着填根本没法核对。
     */
    fun input(
        ctx: Context,
        value: String,
        hint: String,
        password: Boolean = false,
        multiline: Boolean = false,
    ): TextInputLayout {
        val box = TextInputLayout(ctx).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(
                dp(ctx, Design.Radius.SM).toFloat(), dp(ctx, Design.Radius.SM).toFloat(),
                dp(ctx, Design.Radius.SM).toFloat(), dp(ctx, Design.Radius.SM).toFloat()
            )
            boxStrokeColor = Design.primary(ctx)
            // 参数名与 TextInputLayout.hint 同名，apply 里裸写 hint 会两边都绑到参数上（val 自赋值），
            // 必须显式走 setter
            setHint(hint)
            if (password) setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE)
        }
        val et = TextInputEditText(box.context).apply {
            setText(value)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Design.Type.BODY)
            setSingleLine(!multiline)
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
            }
        }
        box.addView(et)
        return box
    }

    /**
     * 下拉选择：暴露式菜单（描边盒 + 尾部下拉箭头），圆角与 [input] 同档。
     *
     * 用 `MaterialAutoCompleteTextView` 而不是原生 `Spinner`，两个理由：
     * 1. 长相是 MD3 的（描边盒 + 浮起标签），跟旁边的输入框一致；
     * 2. **没有 Spinner 那个坑**：Spinner 在设置监听器后会为初始选中项补发一次回调，
     *    回调里要是重建界面就是无限循环。AutoCompleteTextView 只在**用户真的点了**才回调。
     */
    fun dropdown(
        ctx: Context,
        label: String,
        options: List<String>,
        index: Int,
        onPick: (Int) -> Unit,
    ): View {
        val box = TextInputLayout(ctx).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(
                dp(ctx, Design.Radius.SM).toFloat(), dp(ctx, Design.Radius.SM).toFloat(),
                dp(ctx, Design.Radius.SM).toFloat(), dp(ctx, Design.Radius.SM).toFloat()
            )
            boxStrokeColor = Design.primary(ctx)
            // 尾部一个下拉箭头：不标的话根本看不出这框能点
            setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU)
        }
        val tv = MaterialAutoCompleteTextView(box.context).apply {
            inputType = InputType.TYPE_NULL                      // 只选不打字
            setSimpleItems(options.toTypedArray())
            // false = 别按输入内容过滤，否则一设值下拉列表就被过滤成空
            setText(options.getOrElse(index) { "" }, false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Design.Type.BODY)
            setPadding(
                dp(ctx, Design.Space.XS), dp(ctx, Design.Space.M),
                dp(ctx, Design.Space.XS), dp(ctx, Design.Space.M)
            )
        }
        // TYPE_NULL 之后点击不会自己弹列表，得手动喊一下
        tv.setOnClickListener { tv.showDropDown() }
        tv.setOnItemClickListener { _, _, position, _ -> onPick(position) }
        box.addView(tv)
        return box
    }

    /**
     * 开关行：左侧标签 + 右侧 MD3 开关。行**不套卡片背景** ——
     * 一屏几十个小盒子会把界面碎成一地，层次交给分区与留白。
     */
    fun switchRow(ctx: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = MaterialSwitch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        return row(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(ctx, Design.Space.L), dp(ctx, Design.Space.M),
                dp(ctx, Design.Space.M), dp(ctx, Design.Space.M)
            )
            addView(
                text(ctx, label, Design.Type.BODY, Design.onSurface(ctx)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(sw)
        }
    }

    /** 从 [input]/[dropdown] 返回的外盒里取内层可编辑框（已 addView，必非空） */
    fun editTextOf(box: TextInputLayout): EditText =
        requireNotNull(box.getEditText()) { "TextInputLayout 还没挂上内层 EditText" }
}
