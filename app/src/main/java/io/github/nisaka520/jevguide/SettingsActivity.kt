package io.github.nisaka520.jevguide

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.nisaka520.jevguide.ui.Design
import io.github.nisaka520.jevguide.ui.UiKit
import io.github.nisaka520.jevguide.ui.installSystemBarInsets
import io.github.nisaka520.jevguide.ui.isNight
import io.github.nisaka520.jevguide.ui.playBackTransition
import io.github.nisaka520.jevguide.ui.registerCloseTransition
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 设置页 —— 「仪表台」语言在二级页的延伸。
 *
 * ## 结构（与旧版的本质区别）
 *
 * 每个分区一个 build 函数（状态/密钥/关系/判读/聊天模型/记忆/识图/悬浮条/诊断/关于/高级/维护），
 * [buildPage] 按 `screen` extra **只建当前屏的分区**。旧版是「buildStatic 一次建满 12 个分区，
 * applyScreen 再按下标从后往前删 View」—— 先建后删除了慢，还让每个分区的输出控件
 * （日志块）指着随时会被删掉的 View。现在分区即页面：建多少、显示多少。
 *
 * `screen` 的合法值 **a11y / overlay / key / relation / memory / adv / about** 是与
 * [MainActivity] 入口行的协议，逐字对齐、一个都不能改。不合法（含空）→ 建完整页面：
 * a11y_config.xml 的 settingsActivity 从**系统无障碍设置**跳进来时不带 screen，
 * 用户看到的就是这份完整设置 —— 这条路径必须一直活着。
 *
 * ## 行为红线（重构时逐条核对过的）
 *
 * - 所有 SharedPreferences 读写都走 [Config]，键名一个没动；
 * - 「测试密钥」「测试聊天模型」的请求与提示行为不变，只是裸 Thread 换成了 [background]；
 * - 厂商预设切换、清空设置不再 [recreate]()：前者局部重画输入框区（[fillVendorArea]），
 *   后者按当前屏整体重建 View（[buildPage]），Activity 不重建；
 * - 「← 返回首页」与返回方向转场（API34+ 注册 / 老API finish 时补）保留；
 * - onStart/onStop 压制/恢复常驻浮条，与首页、结果页同一对。
 *
 * ## 视觉（变量全部来自 [Design]/[UiKit]，与首页同一套词汇）
 *
 * 分区头 = 分区色小色点 + accent 粗体标题 —— accent 剂量只花在这一处（≤画面 10%）；
 * 同一屏的分区共用该屏的 accent，从首页入口行的编号色一路认到这里。
 * 说明文字是 BODY 灰字；状态/日志/记忆概况这类「机器输出」统一等宽小字块；
 * 按钮实心=主动作、描边=次动作，与首页的「启动判读/全部停止」同一语法。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var page: LinearLayout

    /** 本页显示哪一屏（"" = 完整页面）。「清空设置」后的局部重建也按它来 */
    private var currentScreen: String = ""

    /** 当前分区的内容容器：[openSection] 之后，sub/edit/dropdown/switchRow/button 都加进它 */
    private lateinit var cur: LinearLayout

    // ── 分区内活引用。刻意可空：只建当前屏，不在本屏的分区引用就是 null，
    //    refreshDynamic 等刷新入口按 ?. 触达，绝不踩空 ──
    private var statusText: TextView? = null
    private var lastText: TextView? = null

    /** 维护分区的日志块（AppLog 的窗口），只有 about 屏（或完整页面）有 */
    private var logText: TextView? = null

    /** 识图分区的测试输出块 —— 旧版这类输出写进维护分区的日志块，子屏模式下用户根本看不到 */
    private var visionOut: TextView? = null

    /** 抓屏诊断分区的输出块 */
    private var dumpOut: TextView? = null

    /** 记忆分区的输出块（「查看记忆」的结果落在这里） */
    private var memoryOut: TextView? = null

    /** 记忆概况块：后台扫描完成后重写，不在主线程上扫 */
    private var memorySummary: TextView? = null

    /** 高级设置里那段完整提示词预览；点风格时同步改，做到「点一下就看到提示词变了」 */
    private var promptPreview: TextView? = null

    /** 聊天模型分区里随厂商变化的可重建段：厂商说明 + 三个输入框 + 保存/测试按钮 */
    private var vendorArea: LinearLayout? = null

    /** 识图分区里随厂商变化的可重建段（applyProvider 会改视觉三项），输入框 + 保存/测试 + 输出块 */
    private var visionArea: LinearLayout? = null

    /** 风格选择器的重画入口：「生成几条候选」改了，下面那行说明也得跟着变 */
    private var repaintStyles: () -> Unit = {}

    /**
     * 记忆份数/内容的缓存：读它要扫整个 memory 目录（每联系人一个文件），
     * 旧版**每次 onResume 都在主线程上同步扫**，记忆多了设置页一打开就卡一下。
     * 现在主线程只读缓存，真扫在 [background] 里做，扫完回来刷新（null = 还没扫出来）。
     */
    @Volatile
    private var memoryListCache: List<ContactMemory>? = null

    @Volatile
    private var memoryCount = -1

    /**
     * 统一后台执行池。旧版三处裸 `Thread{} + runOnUiThread`（扫记忆、测 Jev 密钥、测聊天模型）
     * 各自为政，其中两处连 isFinishing 守卫都没有。现在收口到 [background]：
     * 单一入口、主线程回调、统一生命周期守卫。
     */
    private val bgPool: ExecutorService = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        page = UiKit.column(this).apply {
            setPadding(
                dp(Design.Space.L), dp(Design.Space.L),
                dp(Design.Space.L), dp(Design.Space.XL)
            )
            // 不设背景色：让主题的 windowBackground（colorSurface）来 —— 这样才能跟着壁纸取色走
        }
        val scroll = ScrollView(this).apply {
            addView(page)
            // targetSdk 35 起系统强制全面屏（edge-to-edge），状态栏会直接压在标题上 ——
            // 把系统栏高度吃成内边距（封装在 ThemeExt，与首页同一份）
            installSystemBarInsets()
        }
        // API 34+ 的转场必须在 onCreate 注册才生效（关闭方向）；老 API 那半边在 finish() 里兜底
        registerCloseTransition()
        setContentView(scroll)

        currentScreen = intent.getStringExtra("screen").orEmpty()
        buildPage()   // 末尾统一 refreshDynamic()，状态卡第一次显示就在这里填上
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
        refreshMemoryCountAsync()
    }

    /**
     * 自己的界面在前台时把常驻浮条收起来。
     *
     * 浮条是给「在微信里看聊天」用的；挂在设置页上只会压住标题。
     * 跟首页/结果页的处理保持一致：进自己的界面就收，回微信自动回来。
     */
    override fun onStart() {
        super.onStart()
        ScoreOverlay.setSuppressed(cfg, true)
    }

    override fun onStop() {
        super.onStop()
        ScoreOverlay.setSuppressed(cfg, false)
    }

    /**
     * 返回时反向滑出，跟首页进去的动画对上。
     * 放在 finish() 里而不是按钮回调里：系统返回键、手势返回、页面上的「← 返回首页」
     * 三条路都会走 finish()，改一处就全覆盖（API<34 在这里补关闭动画，34+ 走 onCreate 注册的那份）。
     */
    override fun finish() {
        super.finish()
        playBackTransition()
    }

    override fun onDestroy() {
        super.onDestroy()
        bgPool.shutdown()
    }

    // ────────────────────────── 页面组装：只建当前屏 ──────────────────────────

    /** section 协议的合法值（与 [MainActivity] 入口行逐字对齐，一个都不能改） */
    private val sectionScreens = setOf("a11y", "overlay", "key", "relation", "memory", "adv", "about")

    /**
     * 按 [currentScreen] 组装页面。**没有先建后删**：每个分支只调属于该屏的 build 函数。
     *
     * 屏 → 分区的归属与旧版逐字一致（section 协议不能动）：
     * a11y=状态/判读设置/抓屏诊断 ｜ key=三方模型/聊天模型/识图 ｜ about=关于/维护，
     * 其余屏各一个分区。分区顺序 = 完整页面的顺序（老用户在完整页面里滚到的次序不变）。
     */
    private fun buildPage() {
        page.removeAllViews()
        // 全部活引用清零再重建：跨屏引用（上一次 build 留下的）不能活到这一次
        statusText = null
        lastText = null
        logText = null
        visionOut = null
        dumpOut = null
        memoryOut = null
        memorySummary = null
        promptPreview = null
        vendorArea = null
        visionArea = null
        repaintStyles = {}

        // 合法屏 = 子屏（顶部带「← 返回首页」）；空值（系统无障碍设置入口）与
        // 不认识的值都回退成完整页面 —— 与旧版 applyScreen 的处理一致，且不带返回行
        if (currentScreen in sectionScreens) {
            buildBackRow()
            when (currentScreen) {
                "a11y" -> { buildStatusSection(); buildAnalyzeSection(); buildDumpSection() }
                "key" -> { buildKeySection(); buildChatSection(); buildVisionSection() }
                "relation" -> buildRelationSection()
                "memory" -> buildMemorySection()
                "overlay" -> buildOverlaySection()
                "adv" -> buildAdvSection()
                "about" -> { buildAboutSection(); buildMaintSection() }
            }
        } else {
            buildAllSections()
        }
        refreshDynamic()
    }

    /** 完整页面：12 个分区按旧版 buildStatic 的次序全部建出 */
    private fun buildAllSections() {
        buildPageHeader()
        buildStatusSection()
        buildKeySection()
        buildRelationSection()
        buildAnalyzeSection()
        buildChatSection()
        buildMemorySection()
        buildVisionSection()
        buildOverlaySection()
        buildDumpSection()
        buildAboutSection()
        buildAdvSection()
        buildMaintSection()
    }

    /** 完整页面的页头（子屏没有它：子屏用分区头当标题）。直接加进 [page] —— 此时尚无分区容器 */
    private fun buildPageHeader() {
        page.addView(
            UiKit.text(this, "弦外之音", Design.Type.TITLE, Design.onSurface(this), bold = true),
            wrapTop(Design.Space.S)
        )
        val tv = UiKit.text(this, "", Design.Type.BODY, Design.onSurfaceVariant(this))
        tv.text = emphasis("长按不需要、悬浮窗不需要 —— 在微信里点一下，弹 3 条提示：意图/情绪、着急、建议。")
        tv.setPadding(dp(Design.Space.XS), dp(Design.Space.XS), dp(Design.Space.XS), dp(Design.Space.S))
        page.addView(tv, wrapTop(Design.Space.XS))
    }

    /** 子屏顶部的一行返回出口 */
    private fun buildBackRow() {
        page.addView(
            UiKit.text(this, "← 返回首页", Design.Type.BODY, Design.primary(this)).apply {
                setPadding(0, dp(Design.Space.XS), 0, dp(Design.Space.S))
                setOnClickListener { finish() }
            }
        )
    }

    // ────────────────────────── 分区（顺序 = 完整页面的次序）──────────────────────────

    /** 「状态」分区：服务/密钥/模型三行机器读数 + 最近一次结果 + 两个动作 */
    private fun buildStatusSection() {
        openSection("状态", Design.Section.A11Y)
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
    }

    /** 「三方模型（调 Jev 算攻略度）」分区：Jev 密钥的保存与连通性测试 */
    private fun buildKeySection() {
        openSection("三方模型（调 Jev 算攻略度）", Design.Section.KEY)
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
            background({ JevHttp.test(k) }) { r ->
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
        }
    }

    /** 「关系（联系人表）」分区 */
    private fun buildRelationSection() {
        openSection("关系（联系人表）", Design.Section.RELATION)
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
    }

    /** 「判读设置」分区：判读的口径与提示节奏 */
    private fun buildAnalyzeSection() {
        openSection("判读设置", Design.Section.A11Y)
        dropdown("题目语言", Prompt.LANG_LABELS, Prompt.LANGS.indexOf(cfg.lang).coerceAtLeast(0)) {
            cfg.lang = Prompt.LANGS[it]
            toast("题目语言 → " + cfg.lang)
        }
        dropdown("模型", Prompt.MODELS, Prompt.MODELS.indexOf(cfg.model).coerceAtLeast(0)) {
            cfg.model = Prompt.MODELS[it]
        }
        dropdown("情绪显示条数", listOf("1 条", "3 条", "5 条"), listOf(1, 3, 5).indexOf(cfg.emotionTop).coerceAtLeast(0)) {
            cfg.emotionTop = listOf(1, 3, 5)[it]
        }
        dropdown(
            "上下文句数", listOf("0", "1", "2", "3", "5", "7", "10"),
            listOf(0, 1, 2, 3, 5, 7, 10).indexOf(cfg.contextN).coerceAtLeast(0)
        ) { cfg.contextN = listOf(0, 1, 2, 3, 5, 7, 10)[it] }
        dropdown(
            "3 条之间的间隔", listOf("1200ms", "2000ms", "3000ms"),
            listOf(1200, 2000, 3000).indexOf(cfg.toastGapMs).coerceAtLeast(0)
        ) { cfg.toastGapMs = listOf(1200, 2000, 3000)[it] }
        switchRow("分析中先弹一条「Jev 分析中…」（关掉就只剩 3 条）", cfg.showAnalyzing) { cfg.showAnalyzing = it }
        switchRow("自动判读（检测到对方新消息就分析，默认关）", cfg.autoAnalyze) { cfg.autoAnalyze = it }
        dropdown(
            "自动判读防抖", listOf("600ms", "1200ms", "2000ms"),
            listOf(600, 1200, 2000).indexOf(cfg.autoDebounceMs).coerceAtLeast(0)
        ) { cfg.autoDebounceMs = listOf(600, 1200, 2000)[it] }
        switchRow("尝试把「引用块 + 正文」合成一条（启发式，可能误合并）", cfg.linkQuotes) { cfg.linkQuotes = it }
        switchRow("服务运行时挂一条常驻通知", cfg.showNotification) {
            cfg.showNotification = it
            toast("重启无障碍服务后生效")
        }
    }

    /**
     * 「聊天模型（生成候选回复文案）」分区。
     *
     * 厂商相关的部分（说明 + 三个输入框 + 保存/测试）收进 [vendorArea]：
     * 切换厂商时 [fillVendorArea] 局部重画这一段就够 —— 旧版在这里 [recreate]() 整个
     * Activity，输入框区一闪、滚动位置也丢。applyProvider 已把新厂商的地址/模型写进
     * [cfg]，重画时输入框从 cfg 读，天然就是新值。
     */
    private fun buildChatSection() {
        openSection("聊天模型（生成候选回复文案）", Design.Section.KEY)
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
        // ⚠ AutoCompleteTextView 虽然没有 Spinner 的「补发回调」问题，但这里仍保留
        // 「和已存的一致就什么都不做」的幂等判断：程序化 setText 的路径也该挡住。
        dropdown("模型厂商", Providers.labels(), Providers.indexOf(cfg.providerId)) { idx ->
            val p = Providers.ALL.getOrElse(idx) { Providers.CUSTOM }
            if (!Providers.isSameAs(cfg.providerId, idx)) {
                cfg.applyProvider(p)
                toast("已套用 ${p.name}：地址 + 模型都填好了，记得粘这一家的密钥")
                rebuildVendorDependentAreas()
            }
        }
        vendorArea = UiKit.column(this)
        cur.addView(vendorArea)
        fillVendorArea()
        if (Aff.anyConfigured()) {
            sub(
                "关于链接里的邀请码：本 App 是开源免费的，部分厂商入口带了作者邀请码 —— " +
                    "你通过它注册，作者会获得少量额度奖励，**你的价格和权益不受任何影响**。" +
                    "不想带码的话，直接去厂商官网自己注册也一样能用。"
            )
        }

        switchRow("生成候选文案（关掉就只做判读，省一次调用）", cfg.draftsEnabled) { cfg.draftsEnabled = it }
        dropdown(
            "生成几条候选", listOf("1", "2", "3", "4", "5"),
            listOf(1, 2, 3, 4, 5).indexOf(cfg.draftsN).coerceAtLeast(0)
        ) { cfg.draftsN = listOf(1, 2, 3, 4, 5)[it]; repaintStyles() }

        // 回复风格：7 套里最多同时选三套，点一下立刻生效（不设「保存」按钮）
        sub(
            "回复风格：默认三套（稳妥、推进、有趣），另外四套可以换进来。\n" +
                "最多同时选三套 —— 每套风格对应一条候选文案的语气。点一下立刻生效、也立刻写进提示词，没有保存按钮。"
        )
        repaintStyles = stylePicker()
    }

    /**
     * 重画所有「随厂商变化」的可重建段。当前屏是 key 屏时 [vendorArea]/[visionArea] 都在；
     * 其他屏它们是 null，跳过即可（applyProvider 影响的输入框不在这屏上，无需重画）。
     */
    private fun rebuildVendorDependentAreas() {
        fillVendorArea()
        fillVisionArea()
        refreshDynamic()
    }

    /** [vendorArea] 的内容：厂商说明 + 聊天模型三件套 + 保存/测试。切换厂商时整段重画 */
    private fun fillVendorArea() {
        val area = vendorArea ?: return
        area.removeAllViews()
        val prev = cur
        cur = area
        val chosen = Providers.byId(cfg.providerId)
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
        val chatBase = edit(cfg.chatBaseUrl, "https://…/v1（明文 http 会被拦）")
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
            background({ ChatHttp.test(b, k, m) }) { r ->
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
        }
        cur = prev
    }

    /** 「攻略度与记忆」分区 */
    private fun buildMemorySection() {
        openSection("攻略度与记忆", Design.Section.MEMORY)
        sub(
            "攻略度：让 Jev 就「当前关系进展」给一个 0~100% 的评分（11 档 ×10），结果里会显示与上次的差值。\n" +
                "记忆：每个联系人一份，只存在本机 filesDir/memory/ 下；攒够若干条新对话后自动刷新摘要与关键事实。\n" +
                "记忆会作为「背景」一起交给 Jev 和聊天模型 —— 关掉它，每次分析都等于第一次聊。"
        )
        switchRow("问 Jev 要「攻略度」评分", cfg.guideEnabled) { cfg.guideEnabled = it }
        switchRow("启用本地记忆", cfg.memoryEnabled) { cfg.memoryEnabled = it }
        dropdown(
            "摘要刷新频率", listOf("关", "5 条", "8 条", "15 条", "30 条"),
            listOf(0, 5, 8, 15, 30).indexOf(cfg.summarizeEvery).coerceAtLeast(0)
        ) { cfg.summarizeEvery = listOf(0, 5, 8, 15, 30)[it] }
        dropdown(
            "结果显示方式", listOf("结果页（可点选复制）", "只弹提示"),
            listOf("page", "toast").indexOf(cfg.resultMode).coerceAtLeast(0)
        ) { cfg.resultMode = listOf("page", "toast")[it] }
        button("查看记忆（份数 / 最近一份）") { showMemory() }
        // 记忆内容不在 App 里展开，只报「有哪些人、各多少条」；想看细节就导出成文件自己翻（用户要求）。
        // 概况由 [refreshMemoryCountAsync] 后台扫描后填写，主线程不扫目录。
        memorySummary = body(memorySummaryText(memoryListCache))
        // 用户要的是「能复制的目录」：路径放在可选中复制的 body 里，而不是只给个按钮。
        body(
            "记忆存放目录（可长按选中复制）：\n" +
                java.io.File(getExternalFilesDir(null), "memory").absolutePath + "\n" +
                "原始数据在应用私有目录 filesDir/memory/，第三方文件管理器进不去，" +
                "所以先点下面的按钮复制一份出来，再用文件管理器打开上面这个目录。"
        )
        button("导出记忆文件（复制一份出来，用文件管理器看）", filled = true) {
            val n = exportMemories()
            if (n < 0) toast("导出失败", true) else toast("已导出 " + n + " 个文件")
        }
        button("清空全部记忆") {
            val n = Memories.clearAll(this)
            toast("已清空 $n 份记忆")
            AppLog.add("已清空全部记忆：$n 份")
            // 概况块立即跟上：旧版要等下次进页面才不显示已删的记忆
            memoryListCache = null
            memoryCount = -1
            memorySummary?.text = memorySummaryText(null)
            refreshMemoryCountAsync()
        }
        memoryOut = body("")
    }

    /** 记忆概况块的文案（后台扫描完成后整段重写） */
    private fun memorySummaryText(mems: List<ContactMemory>?): String {
        if (mems == null) return "（正在读取本机记忆…）"
        if (mems.isEmpty()) return "（还没有记忆：在微信里判读一次就有了）"
        val sb = StringBuilder("共 ").append(mems.size).append(" 份记忆：")
        for (m in mems) {
            sb.append("\n· ").append(m.name.ifEmpty { "（没读到名字）" })
                .append("　").append(m.turns.size).append(" 条对话 / ")
                .append(m.facts.size).append(" 条事实 / ")
                .append(m.scores.size).append(" 次评分")
        }
        return sb.toString()
    }

    /** 「识图模型（截图转文字）」分区。applyProvider 会改视觉三项，输入框收进 [visionArea] 以便随厂商重画 */
    private fun buildVisionSection() {
        openSection("识图模型（截图转文字）", Design.Section.KEY)
        sub(
            "微信 8.0.76 起**屏蔽了无障碍树**：实测连系统自带的 uiautomator 抓微信都是 0 个文字节点\n" +
                "（系统设置能读到 15 个、桌面能读到 280 行 —— 所以不是本 App 的问题，是微信不给）。\n" +
                "但**截屏是能拍到的**，于是多了一条路：截屏 → 交给看得懂图的模型念成「谁说了什么」，\n" +
                "后面的判读 / 攻略度 / 文案流程完全不变。代价：每次多一次带图调用（约 5~10 秒）。\n" +
                "「自动」＝先试免费的无障碍树，读空了才走视觉。"
        )
        dropdown(
            "读取方式", listOf("自动（先无障碍，读空转视觉）", "只用无障碍树", "只用视觉读屏"),
            listOf("auto", "a11y", "vision").indexOf(cfg.readMode).coerceAtLeast(0)
        ) { cfg.readMode = listOf("auto", "a11y", "vision")[it] }
        visionArea = UiKit.column(this)
        cur.addView(visionArea)
        fillVisionArea()
    }

    /** [visionArea] 的内容：视觉三件套 + 保存/测试 + 输出块 */
    private fun fillVisionArea() {
        val area = visionArea ?: return
        area.removeAllViews()
        val prev = cur
        cur = area
        val vBase = edit(cfg.visionBaseUrl, "https://…/v1（留空＝跟聊天模型共用）")
        val vKey = edit(cfg.visionApiKey, "留空＝跟聊天模型共用", password = true)
        val vModel = edit(cfg.visionModel, "留空＝跟聊天模型共用（当前 ${cfg.chatModel}）")
        button("保存视觉读屏配置") {
            cfg.visionBaseUrl = vBase.text.toString().trim()
            cfg.visionApiKey = vKey.text.toString().trim()
            cfg.visionModel = vModel.text.toString().trim()
            toast("已保存（模型：" + VisionReader.endpointOf(cfg).third + "）")
            refreshDynamic()
        }
        button("测试视觉读屏（3 秒后截当前屏幕）") {
            if (WatchService.instance == null) {
                toast("无障碍服务没在运行")
                return@button
            }
            if (!VisionReader.available()) {
                toast("系统低于 Android 11，用不了无障碍截图")
                return@button
            }
            // 截的是**当前屏幕**（不限于微信），所以留 3 秒让用户切到要测的界面。
            // 原来立刻截 —— 截到的必然是设置页自己：既测不出东西，还把设置页发给了模型。
            toast("3 秒后截当前屏幕，现在切到要测的界面…", true)
            // 挂在 page 上而不是输出块上：这 3 秒里页面若被重建（清空设置），写的是新输出块
            page.postDelayed({
                val svc = WatchService.instance ?: return@postDelayed
                VisionReader.capture(svc, cfg) { d, err ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (d == null) {
                            visionOut?.text = "视觉读屏失败：\n" + (err ?: "未知原因")
                            toast("失败：" + (err ?: "未知原因"), true)
                        } else {
                            val sb = StringBuilder()
                            sb.append("视觉读屏读到（标题=「").append(d.title).append("」）：\n")
                            sb.append("共 ").append(d.msgs.size).append(" 条，其中对方 ")
                                .append(d.msgs.count { !it.mine }).append(" 条\n\n")
                            d.msgs.forEach { sb.append(if (it.mine) "我：" else "对方：").append(it.text).append('\n') }
                            visionOut?.text = sb.toString()
                            toast("读到 ${d.msgs.size} 条（对方 ${d.msgs.count { !it.mine }} 条）")
                        }
                    }
                }
            }, 3000)
        }
        visionOut = body("")
        cur = prev
    }

    /** 「常驻悬浮条（把攻略度挂在屏幕上）」分区 */
    private fun buildOverlaySection() {
        openSection("常驻悬浮条（把攻略度挂在屏幕上）", Design.Section.OVERLAY)
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
    }

    /** 「抓屏诊断（读不到消息时用这个）」分区 */
    private fun buildDumpSection() {
        openSection("抓屏诊断（读不到消息时用这个）", Design.Section.A11Y)
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
        button("把最近诊断显示在下方") {
            val d = cfg.lastDump
            if (d.isEmpty()) toast("还没有诊断数据") else dumpOut?.text = d
        }
        dumpOut = body("")
    }

    /** 「关于本软件」分区 */
    private fun buildAboutSection() {
        openSection("关于本软件", Design.Section.ABOUT)
        sub(
            "弦外之音 " + BuildConfig.VERSION_NAME + "\n" +
                "做什么：在微信里点一下浮条，读当前聊天 → 算出攻略度 → 生成 3 条候选回复。\n" +
                "隐私：密钥、联系人表、记忆、日志全部只存在本机；没有云端、没有统计、没有任何埋点。\n" +
                "联网只有两处：① 把当前聊天内容发给 Jev 算攻略度；② 发给**你自己配的**聊天模型生成文案。\n" +
                "免责：只读屏幕上已经显示的内容，不代替你说话、不自动发送；聊天记录的去向取决于你配的端点，" +
                "请自行确认对方的隐私政策。请勿用于骚扰、跟踪或任何违法用途。"
        )
        // 开源仓库：想看出处、想自己改、想出问题，都从这里走（MIT 许可，仓库里有完整源码、更新日志与构建方法）
        button("开源仓库与文档（GitHub · MIT）") {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse(Privacy.REPO)
                    )
                )
            } catch (e: Exception) {
                toast("打不开浏览器，地址是：" + Privacy.REPO)
            }
        }
        button("报告问题 / 提需求（Issues）") {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse(Privacy.REPO + "/issues")
                    )
                )
            } catch (e: Exception) {
                toast("打不开浏览器，地址是：" + Privacy.REPO + "/issues")
            }
        }
        // 完整说明（数据种类、去向、保留与删除）—— 上架时商店要的就是这个 URL
        button("隐私政策（完整说明：数据种类、去向、保留与删除）") {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse(Privacy.URL)
                    )
                )
            } catch (e: Exception) {
                toast("打不开浏览器，地址是：" + Privacy.URL)
            }
        }
    }

    /** 「高级设置（给聊天模型加要求）」分区 */
    private fun buildAdvSection() {
        openSection("高级设置（给聊天模型加要求）", Design.Section.ADV)
        sub(
            "下面写的话会作为「额外要求」追加到聊天模型的 system 提示词末尾。\n" +
                "只加不改：输出格式（三段 + 标题行 + --- 分隔）与硬性约束由程序保证，" +
                "改了那部分解析就切不出三段了。"
        )
        val extraBox = edit(cfg.promptExtra, "额外要求（留空 = 不加）", multiline = true)
        button("保存额外要求", filled = true) {
            cfg.promptExtra = extraBox.text.toString().trim()
            refreshPromptPreview()   // 预览就在下面，不跟着改会让人以为没保存上
            toast("已保存，下次生成文案生效")
        }
        button("清空（恢复默认）") {
            extraBox.setText("")
            cfg.promptExtra = ""
            refreshPromptPreview()
            toast("已清空")
        }
        promptPreview = body(
            "当前发给聊天模型的完整提示词（只读；上面填的额外要求会接在最后）：\n\n" +
                ReplyPrompt.buildSystem("（暂无记忆）", cfg.lang, cfg.promptExtra, cfg.styles(), cfg.draftsN)
        )
    }

    /** 「维护」分区：日志与清空入口 */
    private fun buildMaintSection() {
        openSection("维护", Design.Section.ABOUT)
        logText = body("")
        button("刷新日志") { refreshDynamic() }
        button("清空日志") {
            AppLog.clear()
            refreshDynamic()
        }
        button("清空全部设置（含密钥、联系人与记忆）") {
            val mem = Memories.clearAll(this)
            cfg.clearAll()
            toast("已清空设置与 $mem 份记忆，本页已恢复默认")
            // 局部重建：旧版在这里 recreate()（整个 Activity 重建、先建满 12 个分区再删）。
            // 现在按当前屏把 View 树重画一遍就是「恢复默认」，Activity 不动。
            memoryListCache = null
            memoryCount = -1
            buildPage()
        }
        sub(
            "隐私：密钥、联系人表、日志只在本机 SharedPreferences 里；每个联系人的记忆在 filesDir/memory/ 下，" +
                "都是本机文件，卸载即消失。没有云端、没有统计、没有第三方 SDK。\n" +
                "唯一的外部请求：Jev（判读/攻略度）和你自己配的聊天模型（生成文案）—— 都只发当前会话相关内容。"
        )
    }

    // ────────────────────────── 动作 ──────────────────────────

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

    /** 把记忆概况显示到本分区的输出块里 */
    private fun showMemory() {
        val all = memoryListCache ?: Memories.listAll(this)
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
        memoryOut?.text = sb.toString()
        toast("记忆已显示在下方")
    }

    private fun fmtTime(ts: Long): String = try {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
    } catch (e: Exception) {
        "-"
    }

    // ────────────────────────── 刷新 ──────────────────────────

    /**
     * 各分区机器输出块的全量重刷。引用全部可空：不在本屏的分区没有控件，`?.` 跳过即可 ——
     * 旧版这里写的是「已删掉的分区」里的 View，字都写到了看不见的地方。
     */
    private fun refreshDynamic() {
        val on = WatchService.instance != null
        statusText?.text = buildString {
            append("无障碍服务：").append(if (on) "已开启 ✓" else "未开启 ✗（上面那个按钮去开）").append('\n')
            append("Jev 密钥：").append(if (cfg.hasKey()) "已配置（${cfg.apiKey.length} 字符）" else "未配置").append('\n')
            append("聊天模型：").append(
                if (cfg.hasChatKey()) "${cfg.chatModel}（${cfg.chatBaseUrl}）"
                else "未配置（只能判读，不出文案）"
            ).append('\n')
            append("攻略度：").append(if (cfg.guideEnabled) "开" else "关")
            append(" · 记忆：").append(
                if (cfg.memoryEnabled) "开（" + (if (memoryCount >= 0) "$memoryCount 份" else "…") + "）" else "关"
            )
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
        lastText?.text = "最近一次结果：\n" + cfg.lastVerdict.ifEmpty { "(还没有判读过)" }
        logText?.text = "日志（最近 " + AppLog.text().split('\n').size + " 行）：\n" + AppLog.text()
    }

    /** 后台扫一遍 memory 目录，回来刷概况块与状态行（只刷有变化的，改了哪个刷哪个） */
    private fun refreshMemoryCountAsync() {
        if (!cfg.memoryEnabled) return
        background(
            { try { Memories.listAll(this) } catch (t: Throwable) { null } }
        ) { mems ->
            memoryListCache = mems
            val n = mems.size
            if (n != memoryCount) {
                memoryCount = n
                memorySummary?.text = memorySummaryText(mems)
                refreshDynamic()
            }
        }
    }

    /** 提示词预览跟着改：用户要的「实时修改」最终就体现在这一段上 */
    private fun refreshPromptPreview() {
        promptPreview?.text = "当前发给聊天模型的完整提示词（只读；上面填的额外要求会接在最后）：\n\n" +
            ReplyPrompt.buildSystem("（暂无记忆）", cfg.lang, cfg.promptExtra, cfg.styles(), cfg.draftsN)
    }

    // ────────────────────────── 后台执行（统一入口）──────────────────────────

    /**
     * 统一后台执行助手：网络/磁盘活儿丢进 [bgPool]，完成后回主线程回调。
     *
     * 约定：[work] 返回 `null` ＝ 这次没跑成（自己 try-catch 了，或任务自身判了不成立），
     * 就**不回调** —— 失败方各自负责给出人话提示（toast/日志），助手只管线程与守卫。
     * 回调前检查 isFinishing/isDestroyed：页面已经没了就什么都不做，
     * 旧版的裸 Thread 少了这个守卫，回调里摸 View 是一颗延迟雷。
     */
    private fun <T : Any> background(work: () -> T?, onDone: (T) -> Unit) {
        bgPool.execute {
            val r: T? = try {
                work()
            } catch (t: Throwable) {
                AppLog.add("后台任务失败：" + t.javaClass.simpleName)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r != null) onDone(r)
            }
        }
    }

    // ────────────────────────── 控件小工具（全部走 UiKit/Design）──────────────────────────

    /**
     * 分区头 + 内容容器。此后到下一个 [openSection] 之间，[sub]/[body]/[edit]/[dropdown]/
     * [switchRow]/[button] 都加进这个分区的内容容器（[cur]）。
     *
     * 视觉：分区色小色点 + accent 粗体标题。accent 剂量只花在这一处，
     * 同屏分区同色 —— 从首页入口行的编号色一路认到这里。
     */
    private fun openSection(t: String, accent: Design.Section) {
        val color = Design.accent(accent, isNight())
        val head = UiKit.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val dot = android.view.View(this).apply {
            background = UiKit.surface(this@SettingsActivity, color, Design.Radius.PILL)
            layoutParams = LinearLayout.LayoutParams(dp(Design.Space.S), dp(Design.Space.S)).apply {
                marginEnd = dp(Design.Space.M)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        head.addView(dot)
        head.addView(UiKit.text(this, t, Design.Type.BODY, color, bold = true))
        page.addView(head, wrapTop(Design.Space.XL))
        val body = UiKit.column(this)
        page.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        cur = body
    }

    /** 说明文字：BODY 灰字，`**强调**` 走 [Emphasis] 变成真加粗（解析是纯函数、有单测） */
    private fun sub(t: String) {
        val tv = UiKit.text(this, "", Design.Type.BODY, Design.onSurfaceVariant(this))
        tv.text = emphasis(t)
        tv.setPadding(dp(Design.Space.XS), dp(Design.Space.XS), dp(Design.Space.XS), dp(Design.Space.S))
        cur.addView(tv, wrapTop(Design.Space.XS))
    }

    /**
     * 控制台块（状态/最近结果/日志/记忆概况/提示词预览）：统一「低层容器 + 等宽小字」，
     * 一眼是"机器输出"。日志/记忆是要能选中复制的 —— 读不到东西时用户得能把它发给作者。
     */
    private fun body(t: String): TextView = UiKit.text(
        this, t, Design.Type.CAPTION, Design.onSurface(this@SettingsActivity),
        lineSpacingDp = Design.Space.XS,
        mono = true,
        selectable = true,
    ).apply {
        setPadding(dp(Design.Space.L), dp(Design.Space.M), dp(Design.Space.L), dp(Design.Space.M))
        background = UiKit.surface(this@SettingsActivity, Design.containerLow(this@SettingsActivity), Design.Radius.MD)
        cur.addView(this, wrapTop(Design.Space.S))
    }

    /** 输入框（UiKit.input 的外盒加进当前分区，返回内层可编辑框） */
    private fun edit(
        value: String,
        hint: String,
        password: Boolean = false,
        multiline: Boolean = false,
    ): EditText {
        val box = UiKit.input(this, value, hint, password, multiline)
        cur.addView(box, wrapTop(Design.Space.S))
        return UiKit.editTextOf(box)
    }

    /** 下拉选择（暴露式菜单） */
    private fun dropdown(label: String, options: List<String>, index: Int, onPick: (Int) -> Unit) {
        cur.addView(UiKit.dropdown(this, label, options, index, onPick), wrapTop(Design.Space.S))
    }

    /** 开关行 */
    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        cur.addView(UiKit.switchRow(this, label, checked, onChange), wrapTop(Design.Space.S))
    }

    /**
     * 按钮：filled=true 实心主动作、false 描边次动作（与首页「启动判读/全部停止」同一语法）。
     * ⚠ 参数顺序有讲究：`onClick` 必须放最后 —— Kotlin 的尾随 lambda 只能绑到最后一个参数。
     */
    private fun button(label: String, filled: Boolean = false, onClick: () -> Unit) {
        cur.addView(UiKit.button(this, label, filled, onClick), wrapTop(Design.Space.S))
    }

    /**
     * 风格选择器：7 套里**最多同时选三套**，点一下立刻写盘（没有「保存」按钮）。
     *
     * 为什么不用输入框：逗号分隔的框用户实测**直接找不到它**，填错名字、忘了点保存都会
     * 让人以为功能坏了。一次点击就是一个状态：选中=实心主色胶囊，未选=容器色胶囊，
     * 下面一行实时说明会出几条。
     *
     * @return 重画闭包 —— 「生成几条候选」改了以后，那行说明也得跟着变
     */
    private fun stylePicker(): () -> Unit {
        val chips = LinkedHashMap<String, TextView>()
        val live = UiKit.text(this, "", Design.Type.CAPTION, Design.onSurfaceVariant(this), lineSpacingDp = 3)
            .apply { setPadding(dp(Design.Space.XS), dp(Design.Space.S), dp(Design.Space.XS), dp(Design.Space.XS)) }

        val paint: () -> Unit = {
            val picked = cfg.styles()
            for ((name, v) in chips) {
                val on = name in picked
                v.text = if (on) "✓ " + name else name
                v.background = UiKit.surface(
                    this,
                    if (on) Design.primary(this) else Design.containerHigh(this),
                    Design.Radius.PILL
                )
                v.setTextColor(if (on) Design.onPrimary(this) else Design.onSurface(this))
            }
            val titles = ReplyPrompt.planTitles(picked, cfg.draftsN)
            live.text = "当前：" + picked.joinToString("、") + "（最多 " + ReplyPrompt.MAX_PICK + " 套）\n" +
                "会出 " + titles.size + " 条：" + titles.joinToString("、") +
                if (titles.size == picked.size) "" else "（风格不够条数时会轮着用）"
        }

        // 判定抽到 StylePick（纯函数、有单测）：上限/下限都**当场提示**，绝不静默改用户的选择
        val toggle: (String) -> Unit = { name ->
            when (val r = StylePick.toggle(cfg.styles(), name)) {
                is StylePick.Refused -> toast(r.reason)
                is StylePick.Changed -> commitStyles(r.picked, paint)
            }
        }

        val box = UiKit.column(this)
        // 7 套摆两行（4 + 3）。每行等宽 weight，最后一行补空 View，否则剩下三个会被拉宽
        for (rowNames in ReplyPrompt.ALL_STYLE_TITLES.chunked(4)) {
            val row = UiKit.row(this).apply {
                setPadding(0, dp(Design.Space.S), 0, 0)
            }
            for (name in rowNames) {
                val v = UiKit.text(this, name, Design.Type.BODY, Design.onSurface(this)).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(Design.Space.XS), dp(Design.Space.M), dp(Design.Space.XS), dp(Design.Space.M))
                    isClickable = true
                    setOnClickListener { toggle(name) }
                }
                chips[name] = v
                row.addView(
                    v,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(Design.Space.S) }
                )
            }
            repeat(4 - rowNames.size) {
                row.addView(
                    android.view.View(this),
                    LinearLayout.LayoutParams(0, dp(Design.Space.XS), 1f).apply { marginEnd = dp(Design.Space.S) }
                )
            }
            box.addView(row)
        }
        cur.addView(box, wrapTop(Design.Space.S))
        cur.addView(live, wrapTop(Design.Space.S))
        paint()
        return paint
    }

    /** 一次切换的收尾：写盘 + 重画 + 同步提示词预览。三件事必须一起做，少一件就「看起来没生效」 */
    private fun commitStyles(picked: List<String>, paint: () -> Unit) {
        cfg.stylesCsv = picked.joinToString(",")
        paint()
        refreshPromptPreview()
    }

    /**
     * 说明文字里的 `**强调**` 变成**真的加粗**。
     *
     * TextView 不认 markdown，星号会原样显示给用户看。
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

    // ────────────────────────── 杂项 ──────────────────────────

    private fun wrapTop(topDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }

    private fun dp(v: Int): Int = UiKit.dp(this, v)

    private fun toast(t: String, long: Boolean = false) = Toast3.toast(this, t, long)
}
