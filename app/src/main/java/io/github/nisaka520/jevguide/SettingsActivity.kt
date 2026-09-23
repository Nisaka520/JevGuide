package io.github.nisaka520.jevguide

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 设置页。
 *
 * 布局仍然用代码搭（这个 App 只有两屏界面，为它养一套 XML 布局不划算），
 * 但**控件换成 Material 3**：卡片用色阶分层、开关是 MD3 开关、输入框是描边盒、
 * 下拉是暴露式菜单。整套颜色走主题属性 → 在 Android 12+ 上会跟着壁纸走（Material You）。
 *
 * 页面结构：状态 → 接口密钥 → 关系（联系人表）→ 判读设置 → 维护（日志）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var page: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var lastText: TextView
    private lateinit var logText: TextView

    /** 风格选择器的重画入口：「生成几条候选」改了，下面那行说明也得跟着变 */
    private var repaintStyles: () -> Unit = {}

    /** 高级设置里那段完整提示词预览；点风格时同步改，做到「点一下就看到提示词变了」 */
    private var promptPreview: TextView? = null

    /** 每个分区标题在 page 里的下标 → 它属于哪一屏。首页进来时按这个把别屏的分区删掉 */
    private val sectionRuns = ArrayList<Pair<Int, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
            // 不设背景色：让主题的 windowBackground（colorSurface）来 —— 这样才能跟着壁纸取色走
        }
        val scroll = ScrollView(this).apply {
            addView(page)
            // targetSdk 35 起系统强制全面屏（edge-to-edge），状态栏会直接压在标题上 ——
            // 实测截图里「16:07」和「Jev攻略」是叠在一起的。所以自己把系统栏高度吃成内边距。
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            // 内容延伸到系统栏下面时，别把最后一屏内容顶到导航条上
            clipToPadding = false
        }
        // 同上：API 34+ 用 overrideActivityTransition 才生效（关闭方向）。
        // 老的 overridePendingTransition 留在 finish() 里，给 API 33 及以下兜底。
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE, R.anim.slide_in_left, R.anim.slide_out_right
            )
        }
        setContentView(scroll)
        buildStatic()
        applyScreen(intent.getStringExtra("screen").orEmpty())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } catch (_: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDynamic()
    }

    /**
     * 自己的界面在前台时把常驻浮条收起来。
     *
     * 浮条是给「在微信里看聊天」用的；挂在设置页上只会压住标题 ——
     * 实测截图里它正好盖住「Jev攻略」，视觉模型第一眼就把它标成了 bug。
     * 跟结果页的处理保持一致：进自己的界面就收，回微信自动回来。
     */
    /**
     * 返回时反向滑出，跟首页进去的动画对上。
     * 放在 finish() 里而不是按钮回调里：系统返回键、手势返回、页面上的「← 返回首页」
     * 三条路都会走 finish()，改一处就全覆盖。
     */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onStart() {
        super.onStart()
        ScoreOverlay.setSuppressed(cfg, true)
    }

    override fun onStop() {
        super.onStop()
        ScoreOverlay.setSuppressed(cfg, false)
    }

    // ────────────────────────── 页面

    private fun buildStatic() {
        title("弦外之音")
        sub("长按不需要、悬浮窗不需要 —— 在微信里点一下，弹 3 条提示：意图/情绪、着急、建议。")

        // 状态
        section("状态", "a11y")
        statusText = body("")
        lastText = body("")
        button("打开无障碍设置") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                AppLog.add("打不开无障碍设置：${e.message}")
            }
        }
        button("立即判读一次", filled = true) {
            val svc = WatchService.instance
            if (svc == null) {
                toast("无障碍服务没在运行")
            } else {
                svc.analyzeNow(true)
            }
        }

        // 密钥
        section("三方模型（调 Jev 算攻略度）", "key", 0xFF6FD3C7.toInt())
        sub("填自己的 TypeSafe/Jev 密钥（apikey_… 开头，约 100 字符）。没有的话：console.typesafe.ai 用 Google 或邮箱验证码登录 → API Keys → 新建，复制过来贴上。")
        val key = edit(cfg.apiKey, "apikey_…（只存在本机）", password = true)
        button("保存密钥", filled = true) {
            cfg.apiKey = key.text.toString().trim()
            toast(if (cfg.hasKey()) "已保存（${cfg.apiKey.length} 字符）" else "太短了，密钥一般 100 字符左右")
            refreshDynamic()
        }
        button("测试密钥", filled = true) {
            val k = key.text.toString().trim()
            if (k.length < 20) {
                toast("先填密钥")
                return@button
            }
            cfg.apiKey = k
            toast("正在测试…")
            Thread {
                val r = JevHttp.test(k)
                runOnUiThread {
                    when (r) {
                        is JevResult.Ok -> {
                            AppLog.add("密钥测试成功")
                            toast("密钥可用 ✓")
                        }
                        is JevResult.Err -> {
                            AppLog.add("密钥测试失败：${r.message}")
                            toast("失败：" + r.message, true)
                        }
                    }
                    refreshDynamic()
                }
            }.start()
        }

        // 关系
        section("关系（联系人表）", "relation")
        sub(
            "一行一个，格式：名字,别名,别名=关系/性别/备注\n" +
                "例：妈妈,妈,老妈=家人/女/生日三月\n" +
                "例：张伟,伟哥,老张=同事/男/市场部\n" +
                "判定时用会话标题（或昵称）去匹配，命中最长的别名生效；匹配不到就当「普通朋友/未知」。\n" +
                "关系档位：" + Contacts.RELATIONS.joinToString("、")
        )
        val rel = edit(
            cfg.contacts().joinToString("\n") { Contacts.formatLine(it) },
            "名字,别名=关系/性别/备注",
            multiline = true
        )
        button("保存联系人表", filled = true) {
            val list = rel.text.toString().split('\n').mapNotNull { Contacts.parseLine(it) }
            cfg.saveContacts(list)
            toast("已保存 ${list.size} 位联系人")
            AppLog.add("联系人表已更新：${list.size} 条")
            rel.setText(list.joinToString("\n") { Contacts.formatLine(it) })
        }

        // 判读设置
        section("判读设置", "a11y")
        spinner("题目语言", Prompt.LANG_LABELS, Prompt.LANGS.indexOf(cfg.lang).coerceAtLeast(0)) {
            cfg.lang = Prompt.LANGS[it]
            toast("题目语言 → " + cfg.lang)
        }
        spinner("模型", Prompt.MODELS, Prompt.MODELS.indexOf(cfg.model).coerceAtLeast(0)) {
            cfg.model = Prompt.MODELS[it]
        }
        spinner("情绪显示条数", listOf("1 条", "3 条", "5 条"), listOf(1, 3, 5).indexOf(cfg.emotionTop).coerceAtLeast(0)) {
            cfg.emotionTop = listOf(1, 3, 5)[it]
        }
        spinner(
            "上下文句数", listOf("0", "1", "2", "3", "5", "7", "10"),
            listOf(0, 1, 2, 3, 5, 7, 10).indexOf(cfg.contextN).coerceAtLeast(0)
        ) { cfg.contextN = listOf(0, 1, 2, 3, 5, 7, 10)[it] }
        spinner(
            "3 条之间的间隔", listOf("1200ms", "2000ms", "3000ms"),
            listOf(1200, 2000, 3000).indexOf(cfg.toastGapMs).coerceAtLeast(0)
        ) { cfg.toastGapMs = listOf(1200, 2000, 3000)[it] }
        switchRow("分析中先弹一条「Jev 分析中…」（关掉就只剩 3 条）", cfg.showAnalyzing) { cfg.showAnalyzing = it }
        switchRow("自动判读（检测到对方新消息就分析，默认关）", cfg.autoAnalyze) { cfg.autoAnalyze = it }
        spinner(
            "自动判读防抖", listOf("600ms", "1200ms", "2000ms"),
            listOf(600, 1200, 2000).indexOf(cfg.autoDebounceMs).coerceAtLeast(0)
        ) { cfg.autoDebounceMs = listOf(600, 1200, 2000)[it] }
        switchRow("尝试把「引用块 + 正文」合成一条（启发式，可能误合并）", cfg.linkQuotes) { cfg.linkQuotes = it }
        switchRow("服务运行时挂一条常驻通知", cfg.showNotification) {
            cfg.showNotification = it
            toast("重启无障碍服务后生效")
        }

        // 聊天模型（生成候选文案）
        section("聊天模型（生成候选回复文案）", "key", 0xFF7FB3FF.toInt())
        sub(
            "判读由 Jev 负责；回复文案由这个通用聊天模型生成（任何 OpenAI 兼容端点都行）。\n" +
                "地址填到 /v1 为止，例如：http://ABC.com/v1 ｜ 你自己的中转站也行。\n" +
                "它的密钥跟 Jev 的密钥是两回事，也只存在本机。"
        )

        // ── 厂商预设：选一家就自动填好地址和两个模型，省得用户猜 /v1 和模型名 ──
        sub(
            "**懒得填就直接选一家**：地址、聊天模型、视觉模型都会自动填好，你只需要去粘密钥。\n" +
                "模型名会随厂商更新，报错就去控制台复制当前的名字。"
        )
        var chosen = Providers.byId(cfg.providerId)
        spinner("模型厂商", Providers.labels(), Providers.indexOf(cfg.providerId)) { idx ->
            // ⚠ Spinner 会在设置监听器后**为初始选中项补发一次回调**，而这里要 recreate() 刷新输入框，
            // 不加这层判断就会「回调 → recreate → onCreate → 补发回调 → …」无限重建。
            // 用"和已存的一致就什么都不做"来挡，天然幂等。
            val p = Providers.ALL.getOrElse(idx) { Providers.CUSTOM }
            if (!Providers.isSameAs(cfg.providerId, idx)) {
                cfg.applyProvider(p)
                chosen = p
                toast("已套用 ${p.name}：地址 + 模型都填好了，记得粘这一家的密钥")
                recreate()
            }
        }
        if (chosen !== Providers.CUSTOM) {
            sub("当前：**${chosen.name}**\n${chosen.note}\n密钥长这样：${chosen.keyHint}")
            // 别骗人：地址是自建中转站时，下拉框还停在预设名上会让人以为配错了
            if (Providers.detectByUrl(cfg.chatBaseUrl) == null) {
                sub(
                    "⚠ 但你现在保存的地址（${cfg.chatBaseUrl}）**不属于任何内置预设** —— " +
                        "说明你在用自建/中转站，那就以下面输入框里的地址为准，下拉框只是预设入口。"
                )
            }
            button("去申请密钥 / 打开控制台") {
                val url = Aff.keyUrl(chosen)
                if (url.isBlank()) {
                    toast("这家没有在线申请页，看文档吧")
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (t: Throwable) {
                        toast("打不开浏览器，地址：$url", true)
                    }
                }
            }
            if (Aff.hasLink(chosen.id)) {
                sub("上面那个入口**带作者的邀请码**：你注册后作者会拿到少量额度奖励，不影响你的价格与权益。")
            }
        }
        if (Aff.anyConfigured()) {
            sub(
                "关于链接里的邀请码：本 App 是开源免费的，部分厂商入口带了作者邀请码 —— " +
                    "你通过它注册，作者会获得少量额度奖励，**你的价格和权益不受任何影响**。" +
                    "不想带码的话，直接去厂商官网自己注册也一样能用。"
            )
        }

        val chatBase = edit(cfg.chatBaseUrl, "http://ABC.com/v1")
        val chatKey = edit(cfg.chatApiKey, "sk-…（只存在本机）", password = true)
        val chatModel = edit(cfg.chatModel, "glm-5.3-flash")
        button("保存聊天模型配置") {
            cfg.chatBaseUrl = chatBase.text.toString().trim()
            cfg.chatApiKey = chatKey.text.toString().trim()
            cfg.chatModel = chatModel.text.toString().trim()
            toast(if (cfg.hasChatKey()) "已保存：${cfg.chatModel}" else "密钥太短（一般 30 字符以上）")
            refreshDynamic()
        }
        button("测试聊天模型") {
            val b = chatBase.text.toString().trim()
            val k = chatKey.text.toString().trim()
            val m = chatModel.text.toString().trim()
            if (k.length < 20) {
                toast("先填聊天模型的密钥")
                return@button
            }
            cfg.chatBaseUrl = b
            cfg.chatApiKey = k
            cfg.chatModel = m
            toast("正在测试…")
            Thread {
                val r = ChatHttp.test(b, k, m)
                runOnUiThread {
                    when (r) {
                        is ChatResult.Ok -> {
                            AppLog.add("聊天模型测试成功：$m")
                            toast("可用 ✓：" + r.text.take(40))
                        }
                        is ChatResult.Err -> {
                            AppLog.add("聊天模型测试失败：${r.message}")
                            toast("失败：" + r.message, true)
                        }
                    }
                    refreshDynamic()
                }
            }.start()
        }
        switchRow("生成候选文案（关掉就只做判读，省一次调用）", cfg.draftsEnabled) { cfg.draftsEnabled = it }
        spinner(
            "生成几条候选", listOf("1", "2", "3", "4", "5"),
            listOf(1, 2, 3, 4, 5).indexOf(cfg.draftsN).coerceAtLeast(0)
        ) { cfg.draftsN = listOf(1, 2, 3, 4, 5)[it]; repaintStyles() }

        // 回复风格：7 套里最多同时选三套，点一下立刻生效（不设「保存」按钮）
        sub(
            "回复风格：默认三套（稳妥、推进、有趣），另外四套可以换进来。\n" +
                "最多同时选三套 —— 每套风格对应一条候选文案的语气。点一下立刻生效、也立刻写进提示词，没有保存按钮。"
        )
        repaintStyles = stylePicker()

        // 攻略度与记忆
        section("攻略度与记忆", "memory")
        sub(
            "攻略度：让 Jev 就「当前关系进展」给一个 0~100% 的评分（11 档 ×10），结果里会显示与上次的差值。\n" +
                "记忆：每个联系人一份，只存在本机 filesDir/memory/ 下；攒够若干条新对话后自动刷新摘要与关键事实。\n" +
                "记忆会作为「背景」一起交给 Jev 和聊天模型 —— 关掉它，每次分析都等于第一次聊。"
        )
        switchRow("问 Jev 要「攻略度」评分", cfg.guideEnabled) { cfg.guideEnabled = it }
        switchRow("启用本地记忆", cfg.memoryEnabled) { cfg.memoryEnabled = it }
        spinner(
            "摘要刷新频率", listOf("关", "5 条", "8 条", "15 条", "30 条"),
            listOf(0, 5, 8, 15, 30).indexOf(cfg.summarizeEvery).coerceAtLeast(0)
        ) { cfg.summarizeEvery = listOf(0, 5, 8, 15, 30)[it] }
        spinner(
            "结果显示方式", listOf("结果页（可点选复制）", "只弹提示"),
            listOf("page", "toast").indexOf(cfg.resultMode).coerceAtLeast(0)
        ) { cfg.resultMode = listOf("page", "toast")[it] }
        button("查看记忆（份数 / 最近一份）") { showMemory() }
        // 记忆内容不在 App 里展开，只报「有哪些人、各多少条」；想看细节就导出成文件自己翻（用户要求）。
        val mems = Memories.listAll(this)
        if (mems.isEmpty()) {
            sub("（还没有记忆：在微信里判读一次就有了）")
        } else {
            val sb = StringBuilder("共 ").append(mems.size).append(" 份记忆：")
            for (m in mems) {
                sb.append("\n· ").append(m.name.ifEmpty { "（没读到名字）" })
                    .append("　").append(m.turns.size).append(" 条对话 / ")
                    .append(m.facts.size).append(" 条事实 / ")
                    .append(m.scores.size).append(" 次评分")
            }
            body(sb.toString())
        }
        // 用户要的是「能复制的目录」：路径放在可选中复制的 body 里，而不是只给个按钮。
        body(
            "记忆存放目录（可长按选中复制）：\n" +
                java.io.File(getExternalFilesDir(null), "memory").absolutePath + "\n" +
                "原始数据在应用私有目录 filesDir/memory/，第三方文件管理器进不去，" +
                "所以先点下面的按钮复制一份出来，再用文件管理器打开上面这个目录。"
        )
        button("导出记忆文件（复制一份出来，用文件管理器看）", filled = true) {
            val n = exportMemories()
            if (n < 0) toast("导出失败，看下面的日志", true) else toast("已导出 " + n + " 个文件")
        }
        button("清空全部记忆") {
            val n = Memories.clearAll(this)
            toast("已清空 $n 份记忆")
            AppLog.add("已清空全部记忆：$n 份")
        }

        // 读取方式
        section("识图模型（截图转文字）", "key", 0xFFB79CFF.toInt())
        sub(
            "微信 8.0.76 起**屏蔽了无障碍树**：实测连系统自带的 uiautomator 抓微信都是 0 个文字节点\n" +
                "（系统设置能读到 15 个、桌面能读到 280 行 —— 所以不是本 App 的问题，是微信不给）。\n" +
                "但**截屏是能拍到的**，于是多了一条路：截屏 → 交给看得懂图的模型念成「谁说了什么」，\n" +
                "后面的判读 / 攻略度 / 文案流程完全不变。代价：每次多一次带图调用（约 5~10 秒）。\n" +
                "「自动」＝先试免费的无障碍树，读空了才走视觉。"
        )
        spinner(
            "读取方式", listOf("自动（先无障碍，读空转视觉）", "只用无障碍树", "只用视觉读屏"),
            listOf("auto", "a11y", "vision").indexOf(cfg.readMode).coerceAtLeast(0)
        ) { cfg.readMode = listOf("auto", "a11y", "vision")[it] }
        val vBase = edit(cfg.visionBaseUrl, "http://ABC.com/v1（留空＝跟聊天模型共用）")
        val vKey = edit(cfg.visionApiKey, "留空＝跟聊天模型共用", password = true)
        val vModel = edit(cfg.visionModel, "留空＝跟聊天模型共用（当前 ${cfg.chatModel}）")
        button("保存视觉读屏配置") {
            cfg.visionBaseUrl = vBase.text.toString().trim()
            cfg.visionApiKey = vKey.text.toString().trim()
            cfg.visionModel = vModel.text.toString().trim()
            toast("已保存（模型：" + VisionReader.endpointOf(cfg).third + "）")
            refreshDynamic()
        }
        button("测试视觉读屏（现在截一张，看读到什么）") {
            val svc = WatchService.instance
            if (svc == null) {
                toast("无障碍服务没在运行")
                return@button
            }
            if (!VisionReader.available()) {
                toast("系统低于 Android 11，用不了无障碍截图")
                return@button
            }
            toast("正在截图识别…")
            VisionReader.capture(svc, cfg) { d, err ->
                runOnUiThread {
                    if (d == null) {
                        logText.text = "视觉读屏失败：\n" + (err ?: "未知原因")
                        toast("失败：" + (err ?: "未知原因"), true)
                    } else {
                        val sb = StringBuilder()
                        sb.append("视觉读屏读到（标题=「").append(d.title).append("」）：\n")
                        sb.append("共 ").append(d.msgs.size).append(" 条，其中对方 ")
                            .append(d.msgs.count { !it.mine }).append(" 条\n\n")
                        d.msgs.forEach { sb.append(if (it.mine) "我：" else "对方：").append(it.text).append('\n') }
                        logText.text = sb.toString()
                        toast("读到 ${d.msgs.size} 条（对方 ${d.msgs.count { !it.mine }} 条）")
                    }
                }
            }
        }

        // 常驻悬浮条
        section("常驻悬浮条（把攻略度挂在屏幕上）", "overlay")
        sub(
            "判读出来的攻略度会一直挂成一个小条（浮在微信上面），而不是弹个窗看一眼就没了。\n" +
                "操作：**拖动**挪位置（位置会记住）｜**点一下**打开结果页看 3 条文案（还没有结果时点一下就是判读一次）｜**长按**隐藏。\n" +
                "用的是无障碍浮层（TYPE_ACCESSIBILITY_OVERLAY），**不需要**「显示在其他应用上层」权限；\n" +
                "服务被系统杀掉时它会一起消失（没有服务也就没有数据）。\n\n" +
                "⚠ 屏幕边上如果还有个圆钮，那是**系统**给无障碍服务画的「无障碍快捷按钮」：\n" +
                "本 App 一注册回调它就会冒出来，它不是本 App 画的窗口，所以关浮条关不掉它。\n" +
                "它跟浮条功能重复（点一下都是判读），已默认注销；要留着就在下面打开。"
        )
        switchRow("常驻显示攻略度浮条", cfg.overlayEnabled) {
            cfg.overlayEnabled = it
            val svc = WatchService.instance
            if (svc == null) {
                toast("无障碍服务没在运行，开服务后生效")
            } else if (it) {
                val t = cfg.lastOverlayText.ifEmpty { ScoreOverlay.format("微信", null, null) }
                ScoreOverlay.show(svc, cfg, t, cfg.lastOverlayPercent.takeIf { p -> p >= 0 })
                toast("已显示")
            } else {
                ScoreOverlay.hide()
                toast("已隐藏")
            }
        }
        switchRow("判读完自动弹结果页（默认关：攻略度已经在浮条上了）", cfg.overlayAutoResult) { cfg.overlayAutoResult = it }
        switchRow("系统无障碍快捷按钮（和浮条功能重复，默认关）", cfg.a11yButtonEnabled) {
            cfg.a11yButtonEnabled = it
            WatchService.instance?.syncButton()
            toast(if (it) "已注册（若没出现，去系统设置→无障碍→无障碍快捷方式 里绑本服务）" else "已注销，屏幕边上那个圆钮会消失")
        }
        button("把浮条拉回左上角") {
            cfg.overlayX = 24
            cfg.overlayY = 420
            val svc = WatchService.instance
            if (svc != null && cfg.overlayEnabled) {
                ScoreOverlay.hide()
                val t = cfg.lastOverlayText.ifEmpty { ScoreOverlay.format("微信", null, null) }
                ScoreOverlay.show(svc, cfg, t, cfg.lastOverlayPercent.takeIf { p -> p >= 0 })
            }
            toast("位置已重置")
        }
        button("手动显示 / 隐藏浮条") {
            val svc = WatchService.instance
            if (svc == null) {
                toast("无障碍服务没在运行")
            } else if (ScoreOverlay.isShowing()) {
                ScoreOverlay.hide()
                toast("已隐藏（开关仍为开，重启服务会回来）")
            } else {
                val t = cfg.lastOverlayText.ifEmpty { ScoreOverlay.format("微信", null, null) }
                ScoreOverlay.show(svc, cfg, t, cfg.lastOverlayPercent.takeIf { p -> p >= 0 })
                toast("已显示")
            }
        }

        // 抓屏诊断
        section("抓屏诊断（读不到消息时用这个）", "a11y")
        sub(
            "用法：在微信聊天页拉下通知栏点「诊断抓屏」；或者点下面这个按钮，然后 3 秒内切回微信。\n" +
                "导出的是当前窗口的节点结构（类名 / viewId / 坐标 / 文字标志），用来排查『为什么读不到消息』。\n" +
                "只存在本机，不会自动上传 —— 只有你点「分享」才会发出去。"
        )
        button("3 秒后抓取微信窗口") {
            val svc = WatchService.instance
            if (svc == null) {
                toast("无障碍服务没在运行")
            } else {
                svc.dumpAfter(3000)
                toast("好，3 秒内切回微信聊天页")
            }
        }
        button("分享最近一次诊断") { shareDump() }
        button("把最近诊断显示在日志区") {
            val d = cfg.lastDump
            if (d.isEmpty()) toast("还没有诊断数据") else logText.text = d
        }

        // 维护
        // ── 关于（首页第五个入口指向这里）──
        section("关于本软件", "about")
        sub(
            "弦外之音 " + BuildConfig.VERSION_NAME + "\n" +
                "做什么：在微信里点一下浮条，读当前聊天 → 算出攻略度 → 生成 3 条候选回复。\n" +
                "隐私：密钥、联系人表、记忆、日志全部只存在本机；没有云端、没有统计、没有任何埋点。\n" +
                "联网只有两处：① 把当前聊天内容发给 Jev 算攻略度；② 发给**你自己配的**聊天模型生成文案。\n" +
                "免责：只读屏幕上已经显示的内容，不代替你说话、不自动发送；聊天记录的去向取决于你配的端点，" +
                "请自行确认对方的隐私政策。请勿用于骚扰、跟踪或任何违法用途。"
        )

        // ── 高级设置（首页第七个入口）──
        section("高级设置（给聊天模型加要求）", "adv", 0xFF7FB3FF.toInt())
        sub(
            "下面写的话会作为「额外要求」追加到聊天模型的 system 提示词末尾。\n" +
                "只加不改：输出格式（三段 + 标题行 + --- 分隔）与硬性约束由程序保证，" +
                "改了那部分解析就切不出三段了。"
        )
        val extraBox = edit(cfg.promptExtra, "额外要求（留空 = 不加）", multiline = true)
        button("保存额外要求", filled = true) {
            cfg.promptExtra = extraBox.text.toString().trim()
            refreshPromptPreview()   // 预览就在下面，不跟着改会让人以为没保存上
            android.widget.Toast.makeText(this, "已保存，下次生成文案生效", android.widget.Toast.LENGTH_SHORT).show()
        }
        button("清空（恢复默认）") {
            extraBox.setText("")
            cfg.promptExtra = ""
            refreshPromptPreview()
            android.widget.Toast.makeText(this, "已清空", android.widget.Toast.LENGTH_SHORT).show()
        }
        promptPreview = body(
            "当前发给聊天模型的完整提示词（只读；上面填的额外要求会接在最后）：\n\n" +
                ReplyPrompt.buildSystem("（暂无记忆）", cfg.lang, cfg.promptExtra, cfg.styles(), cfg.draftsN)
        )

        section("维护", "about")
        logText = body("")
        button("刷新日志") { refreshDynamic() }
        button("清空日志") {
            AppLog.clear()
            refreshDynamic()
        }
        button("清空全部设置（含密钥、联系人与记忆）") {
            val mem = Memories.clearAll(this)
            cfg.clearAll()
            toast("已清空设置与 $mem 份记忆，重开本页恢复默认")
            recreate()
        }
        sub(
            "隐私：密钥、联系人表、日志只在本机 SharedPreferences 里；每个联系人的记忆在 filesDir/memory/ 下，" +
                "都是本机文件，卸载即消失。没有云端、没有统计、没有第三方 SDK。\n" +
                "唯一的外部请求：Jev（判读/攻略度）和你自己配的聊天模型（生成文案）—— 都只发当前会话相关内容。"
        )
    }

    private fun shareDump() {
        val d = cfg.lastDump
        if (d.isEmpty()) {
            toast("还没有诊断数据：先去微信里点通知栏「诊断抓屏」", true)
            return
        }
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "弦外之音抓屏诊断")
                putExtra(Intent.EXTRA_TEXT, d)
            }
            startActivity(Intent.createChooser(i, "把诊断发给作者"))
        } catch (e: Exception) {
            toast("分享失败：${e.message}", true)
        }
    }

    private fun refreshDynamic() {
        val on = WatchService.instance != null
        statusText.text = buildString {
            append("无障碍服务：").append(if (on) "已开启 ✓" else "未开启 ✗（上面那个按钮去开）").append('\n')
            append("Jev 密钥：").append(if (cfg.hasKey()) "已配置（${cfg.apiKey.length} 字符）" else "未配置").append('\n')
            append("聊天模型：").append(
                if (cfg.hasChatKey()) "${cfg.chatModel}（${cfg.chatBaseUrl}）"
                else "未配置（只能判读，不出文案）"
            ).append('\n')
            append("攻略度：").append(if (cfg.guideEnabled) "开" else "关")
            append(" · 记忆：").append(if (cfg.memoryEnabled) "开（${Memories.listAll(this@SettingsActivity).size} 份）" else "关")
            append('\n')
            append("读取方式：").append(
                when (cfg.readMode) {
                    "vision" -> "只用视觉读屏（${VisionReader.endpointOf(cfg).third}）"
                    "a11y" -> "只用无障碍树"
                    else -> "自动（无障碍优先，读空转视觉）"
                }
            ).append('\n')
            append("厂商：").append(Providers.byId(cfg.providerId).name)
            if (cfg.visionModel.isBlank()) append("（没配读图模型，视觉读屏会退回用聊天模型）")
            append('\n')
            append("当前设置：").append(cfg.lang).append(" · ").append(cfg.model)
            append(" · 情绪 ").append(cfg.emotionTop).append(" 条 · 上下文 ").append(cfg.contextN).append(" 句")
            append(" · 自动判读 ").append(if (cfg.autoAnalyze) "开" else "关")
            append(" · 结果 ").append(if (cfg.resultMode == "page") "结果页" else "提示")
            append('\n').append("版本：").append(BuildConfig.VERSION_NAME)
        }
        lastText.text = "最近一次结果：\n" + cfg.lastVerdict.ifEmpty { "(还没有判读过)" }
        logText.text = "日志（最近 " + AppLog.text().split('\n').size + " 行）：\n" + AppLog.text()
    }

    /** 把记忆概况显示到下面的日志区（不额外做界面，省得再养一套 UI） */
    private fun showMemory() {
        val all = Memories.listAll(this)
        if (all.isEmpty()) {
            toast("还没有任何记忆（判读一次就有了）")
            return
        }
        val sb = StringBuilder("共 ${all.size} 份记忆（只在本机 filesDir/memory/）\n")
        all.take(10).forEach { m ->
            sb.append("\n── ").append(m.name).append("（").append(m.relation.ifEmpty { "未设关系" }).append("）──\n")
            sb.append("摘要：").append(m.summary.ifEmpty { "（还没生成，攒够对话会自动刷）" }).append('\n')
            if (m.facts.isNotEmpty()) {
                sb.append("事实：").append(m.facts.joinToString("；") { it.text }).append('\n')
            }
            sb.append("评分：").append(
                if (m.scores.isEmpty()) "（还没有）"
                else m.scores.take(6).reversed().joinToString(" → ") { it.score.toString() + "%" }
            ).append('\n')
            sb.append("对话：").append(m.turns.size).append(" 条 · 更新 ").append(fmtTime(m.updatedAt)).append('\n')
        }
        logText.text = sb.toString()
        toast("记忆已显示在下面的日志区")
    }

    private fun fmtTime(ts: Long): String = try {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
    } catch (e: Exception) {
        "-"
    }

    // ────────────────────────── 小工具（MD3）

    /**
     * 取**主题属性**里的颜色。
     *
     * ⚠ 别直接写 `R.color.brand`：那套是"不支持壁纸取色时的兜底色"，写死的。
     * 只有走主题属性才能拿到 Material You 从壁纸算出来的配色 —— 否则会出现
     * "Material 控件跟着壁纸变色、我手搓的标题还是老青绿"这种最难看的不一致。
     */
    /**
     * 只留下属于 [screen] 这一屏的分区，其余整段删掉，并在最上面加一行「返回首页」。
     *
     * 为什么不是「滚到对应分区」：那样五个入口进去看到的是**同一张长表单**，
     * 用户会觉得点哪个都一样（用户原话：进去后再没有修改前的一个统一界面）。
     * 现在每个入口是一张独立页面：标题、内容、长度都不一样。
     *
     * 归属判定：每个子 View 属于「它前面最近的那个分区标题」；第一个分区之前的
     * （大标题、总说明）在指定了 screen 时一并删掉 —— 各屏自己有标题。
     */
    private fun applyScreen(screen: String) {
        if (screen.isEmpty()) return
        if (sectionRuns.none { it.second == screen }) return
        val keep = HashSet<Int>()
        var current = ""
        for (i in 0 until page.childCount) {
            sectionRuns.firstOrNull { it.first == i }?.let { current = it.second }
            if (current == screen) keep.add(i)
        }
        // 从后往前删：否则删掉一个，后面所有下标都往前挪一位
        for (i in page.childCount - 1 downTo 0) {
            if (i !in keep) page.removeViewAt(i)
        }
        // 顶上加一行返回：系统返回键也能用，但页面上得有个看得见的出口
        page.addView(TextView(this).apply {
            text = "← 返回首页"
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener { finish() }
        }, 0)
    }

    private fun attr(@androidx.annotation.AttrRes id: Int, fallback: Int = R.color.text): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(id, tv, true) && tv.data != 0) tv.data else color(fallback)
    }

    private val cPrimary: Int get() = attr(com.google.android.material.R.attr.colorPrimary)
    private val cOnPrimary: Int get() = attr(com.google.android.material.R.attr.colorOnPrimary)
    private val cOnSurface: Int get() = attr(com.google.android.material.R.attr.colorOnSurface)
    private val cOnSurfaceVariant: Int get() = attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val cContainerHigh: Int get() = attr(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    private val cContainerLow: Int get() = attr(com.google.android.material.R.attr.colorSurfaceContainerLow)

    private fun title(t: String) = page.addView(TextView(this).apply {
        text = t
        setTextColor(cOnSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(4))
    })

    /**
     * 分区标题。screen 非空表示「这个分区属于哪一屏」：首页点某个入口进来时，
     * 只保留属于那一屏的分区，其余整段删掉。
     */
    private fun section(t: String, screen: String = "", accent: Int = 0) = page.addView(TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        if (accent == 0) {
            setTextColor(cPrimary)
            setPadding(0, dp(22), 0, dp(4))
        } else {
            // 模型配置那三块用「带色底的分组头」区分：色底是 14% 的强调色 + 1dp 描边，
            // 不刺眼但一眼能看出这是三组不同的东西，而不是一长串同款设置项。
            setTextColor(accent)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22); bottomMargin = dp(2) }
            background = GradientDrawable().apply {
                setColor(withAlpha(accent, 0.14f))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), withAlpha(accent, 0.35f))
            }
        }
    }).also {
        sectionRuns.add(page.childCount - 1 to screen)
    }

    /** 把颜色按比例压成半透明（M3 的 container 色本质就是主色降透明度） */
    private fun withAlpha(color: Int, f: Float): Int = android.graphics.Color.argb(
        (255 * f).toInt(),
        android.graphics.Color.red(color),
        android.graphics.Color.green(color),
        android.graphics.Color.blue(color)
    )

    private fun sub(t: String) = page.addView(TextView(this).apply {
        text = emphasis(t)
        setTextColor(cOnSurfaceVariant)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(2), dp(2), dp(2), dp(8))
    })

    /**
     * 说明文字里的 `**强调**` 变成**真的加粗**。
     *
     * TextView 不认 markdown，原来那些星号是原样显示给用户看的（实测截图里满屏 `**`）。
     * 解析放在 [Emphasis]（纯函数、有单测），这里只负责套 StyleSpan。
     */
    private fun emphasis(t: String): CharSequence {
        val (plain, bolds) = Emphasis.parse(t)
        if (bolds.isEmpty()) return plain
        return android.text.SpannableString(plain).apply {
            for (r in bolds) {
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    r.first, r.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        setTextColor(cOnSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = android.graphics.Typeface.MONOSPACE
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = card(cContainerLow)
        // 日志/记忆是要能选中复制的 —— 读不到东西时用户得能把它发给作者
        setTextIsSelectable(true)
        page.addView(this, margins())
    }

    private fun edit(
        value: String,
        hint: String,
        password: Boolean = false,
        multiline: Boolean = false
    ): EditText {
        val box = TextInputLayout(this).apply {
            // 描边盒（OutlinedBox）是 MD3 里最"表单"的一种，标签会浮到边框上，省一行标题
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            // 一次设四个角：Material 1.12 里单独设 TopEnd/BottomStart/BottomEnd 的 setter 已被移除
            // （写成属性会报 'val' cannot be reassigned），setBoxCornerRadii 才是稳的写法
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), 0f, 0f)
        }
        val et = TextInputEditText(box.context).apply {
            setText(value)
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(!multiline)
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
            }
        }
        box.addView(et)
        page.addView(box, margins())
        return et
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 开关行不自己套一层卡片：一屏几十个小盒子会让界面碎成一地（实测截图后视觉模型也这么判）
            background = null
            setPadding(dp(14), dp(10), dp(10), dp(10))
            addView(TextView(this@SettingsActivity).apply {
                text = label
                setTextColor(cOnSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }
        page.addView(row, margins())
    }

    /**
     * 下拉选择。
     *
     * 用 `MaterialAutoCompleteTextView` 而不是原生 `Spinner`，两个理由：
     * 1. 长相是 MD3 的（描边盒 + 浮起标签），跟旁边的输入框一致
     * 2. **没有 Spinner 那个坑**：Spinner 在设置监听器后会为初始选中项补发一次回调，
     *    而"模型厂商"那一项收到回调要 `recreate()` 刷新输入框 —— 用 Spinner 就是无限重建。
     *    AutoCompleteTextView 只在**用户真的点了**才回调，从根上没有这个问题。
     */
    private fun spinner(label: String, options: List<String>, index: Int, onPick: (Int) -> Unit) {
        val box = TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            // 一次设四个角：Material 1.12 里单独设 TopEnd/BottomStart/BottomEnd 的 setter 已被移除
            // （写成属性会报 'val' cannot be reassigned），setBoxCornerRadii 才是稳的写法
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), 0f, 0f)
        }
        val tv = MaterialAutoCompleteTextView(box.context).apply {
            inputType = InputType.TYPE_NULL                      // 只选不打字
            setSimpleItems(options.toTypedArray())
            // false = 别按输入内容过滤，否则一设值下拉列表就被过滤成空
            setText(options.getOrElse(index) { "" }, false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(4), dp(14), dp(4), dp(14))
        }
        // TYPE_NULL 之后点击不会自己弹列表，得手动喊一下
        tv.setOnClickListener { tv.showDropDown() }
        tv.setOnItemClickListener { _, _, position, _ -> onPick(position) }
        box.addView(tv)
        page.addView(box, margins())
    }

    /**
     * ⚠ 参数顺序有讲究：`onClick` 必须放最后。
     * Kotlin 的尾随 lambda（`button("x") { ... }`）只能绑到**最后一个参数**，
     * 把 `filled` 放最后会让所有 `button("x") { }` 的调用点编译不过（lambda 被当成 Boolean）。
     */
    private fun button(label: String, filled: Boolean = false, onClick: () -> Unit) {
        val b = MaterialButton(this, null, if (filled) com.google.android.material.R.attr.materialButtonStyle else android.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 13.5f
            isAllCaps = false                 // 中文按钮千万别跟着英文规则全大写
            cornerRadius = dp(12)
            if (!filled) {
                // MD3 的「tonal」按钮：形状跟 filled 一样，但用 secondaryContainer 这类低饱和容器色 ——
                // 设置页一屏几十个按钮，全用实心主色会吵得没法看。
                // Material 1.12 没暴露 tonal 的 style 属性（materialButtonTonalStyle 不存在），所以自己染。
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    0x00000000
                )
                setTextColor(cPrimary)
            }
            setOnClickListener { onClick() }
        }
        page.addView(b, margins())
    }

    /** 统一的卡片底：MD3 靠**色阶差**分层，不靠阴影（深色下阴影几乎看不见） */
    private fun card(fill: Int = cContainerHigh): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(20).toFloat()
    }

    private fun margins(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    /**
     * 风格选择器：7 套里**最多同时选三套**，点一下立刻写盘（没有「保存」按钮）。
     *
     * 为什么不再用输入框：原来那是个「逗号分隔 + 保存」的框，用户实测**直接找不到它**
     * （它还被我放错在「攻略度与记忆」分区里），而且填错名字、忘了点保存都会让人以为功能坏了。
     * 现在一次点击就是一个状态：选中=实心主色，未选=卡片色，下面一行实时说明会出几条。
     *
     * @return 重画闭包 —— 「生成几条候选」改了以后，那行说明也得跟着变
     */
    private fun stylePicker(): () -> Unit {
        val chips = LinkedHashMap<String, TextView>()
        val live = TextView(this).apply {
            setTextColor(cOnSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(2), dp(6), dp(2), dp(2))
        }

        val paint: () -> Unit = {
            val picked = cfg.styles()
            for ((name, v) in chips) {
                val on = name in picked
                v.text = if (on) "✓ " + name else name
                v.background = chipBg(on)
                v.setTextColor(if (on) cOnPrimary else cOnSurface)
            }
            val titles = ReplyPrompt.planTitles(picked, cfg.draftsN)
            live.text = "当前：" + picked.joinToString("、") + "（最多 " + ReplyPrompt.MAX_PICK + " 套）\n" +
                "会出 " + titles.size + " 条：" + titles.joinToString("、") +
                if (titles.size == picked.size) "" else "（风格不够条数时会轮着用）"
        }

        // 上限/下限都在这里当场提示，而不是默默改掉用户的选择 —— 静默修正最难排查
        val toggle: (String) -> Unit = { name ->
            val picked = cfg.styles().toMutableList()
            if (name in picked) {
                if (picked.size <= 1) toast("至少要留一套风格")
                else { picked.remove(name); commitStyles(picked, paint) }
            } else {
                if (picked.size >= ReplyPrompt.MAX_PICK) {
                    toast("最多同时选 " + ReplyPrompt.MAX_PICK + " 套：先点掉一套再选新的")
                } else { picked.add(name); commitStyles(picked, paint) }
            }
        }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 7 套摆两行（4 + 3）。每行等宽 weight，最后一行补空 View，否则剩下三个会被拉宽
        for (rowNames in ReplyPrompt.ALL_STYLE_TITLES.chunked(4)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            for (name in rowNames) {
                val v = TextView(this).apply {
                    text = name
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setPadding(dp(4), dp(11), dp(4), dp(11))
                    isClickable = true
                    setOnClickListener { toggle(name) }
                }
                chips[name] = v
                row.addView(
                    v,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                )
            }
            repeat(4 - rowNames.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginEnd = dp(6) })
            }
            box.addView(row)
        }
        page.addView(box, margins())
        page.addView(live, margins())
        paint()
        return paint
    }

    /** 一次切换的收尾：写盘 + 重画 + 同步提示词预览。三件事必须一起做，少一件就「看起来没生效」 */
    private fun commitStyles(picked: List<String>, paint: () -> Unit) {
        cfg.stylesCsv = picked.joinToString(",")
        paint()
        refreshPromptPreview()
    }

    private fun chipBg(on: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (on) cPrimary else cContainerHigh)
        cornerRadius = dp(14).toFloat()
    }

    /** 提示词预览跟着改：用户要的「实时修改」最终就体现在这一段上 */
    private fun refreshPromptPreview() {
        promptPreview?.text = "当前发给聊天模型的完整提示词（只读；上面填的额外要求会接在最后）：\n\n" +
            ReplyPrompt.buildSystem("（暂无记忆）", cfg.lang, cfg.promptExtra, cfg.styles(), cfg.draftsN)
    }

    /**
     * 把 filesDir/memory 里的 json 复制到外部私有目录（不需要任何存储权限，文件管理器能翻），
     * 返回复制了几个文件，失败返回 -1。
     *
     * 为什么不「直接打开 filesDir/memory」：那是应用私有目录，任何文件管理器都进不去，
     * 系统也不会把打开它的权限给第三方 App —— 用户想看，只能由我们复制一份出来。
     */
    private fun exportMemories(): Int {
        var n = -1
        try {
            val src = java.io.File(filesDir, "memory")
            val dst = java.io.File(getExternalFilesDir(null), "memory")
            if (dst.exists() || dst.mkdirs()) {
                n = 0
                val list = src.listFiles()
                if (list != null) {
                    for (f in list) {
                        if (f.isFile && f.name.endsWith(".json")) {
                            java.io.File(dst, f.name).writeBytes(f.readBytes())
                            n = n + 1
                        }
                    }
                }
            }
            AppLog.add("导出记忆：复制了 " + n + " 个文件到外部私有目录")
        } catch (t: Throwable) {
            AppLog.add("导出记忆失败：" + t.javaClass.simpleName)
            n = -1
        }
        return n
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    private fun color(id: Int): Int = if (Build.VERSION.SDK_INT >= 23) getColor(id) else Color.WHITE

    private fun toast(t: String, long: Boolean = false) = Toast3.toast(this, t, long)
}
