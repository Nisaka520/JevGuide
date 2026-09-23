package io.github.nisaka520.jevguide

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 判读流程的编排：抓屏 → 关系匹配 → 取记忆 → 调 Jev（含攻略度）→ 调聊天模型出文案 → 回写记忆 → 出结果。
 *
 * 同一时刻只允许一次判读在跑（无障碍事件很密集，重复触发只会互相踩）；
 * 手动触发（磁贴/无障碍按钮）在忙的时候会明确告诉你"正在判读中"，
 * 而不是静默丢掉 —— 这类工具最忌讳"点了没反应"。
 *
 * 两条外部依赖是**独立降级**的：Jev 挂了就没判读，聊天模型挂了就只剩判读；
 * 任何一个挂掉都不会让整次分析白跑（记忆照常更新）。
 */
object Analyzer {

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "jev-analyze") }
    private val running = AtomicBoolean(false)

    fun run(ctx: Context, digest: Digest, manual: Boolean = true) {
        val cfg = Config(ctx)
        if (!cfg.hasKey()) {
            Toast3.toast(ctx, "还没填 Jev 接口密钥：打开「Jev攻略」设置 → 接口密钥", true)
            AppLog.add("未配置 Jev 密钥，已拒绝判读")
            return
        }
        if (!running.compareAndSet(false, true)) {
            if (manual) Toast3.toast(ctx, "上一次还在判读中…")
            return
        }

        val target = digest.latestPeerMessage()
        if (target == null) {
            running.set(false)
            if (manual) {
                Toast3.toast(
                    ctx,
                    if (digest.msgs.isEmpty()) "这个窗口没读到任何消息文字（点通知栏「诊断抓屏」导出发我）"
                    else "读到了 ${digest.msgs.size} 条，但都像是我自己发的（点「诊断抓屏」导出）",
                    true
                )
            }
            AppLog.add(
                "抓屏结果里没有对方消息（标题=「${digest.title}」，共 ${digest.msgs.size} 条）：" +
                    digest.msgs.takeLast(6).joinToString(" | ") { (if (it.mine) "我:" else "对方:") + it.text.take(20) }
            )
            return
        }

        val contact = Contacts.match(cfg.contacts(), digest.title, digest.peer)
            ?: Contacts.fallback(digest.title)
        val memKey = MemKeys.of(digest.title)
        val mem = if (cfg.memoryEnabled) Memories.load(ctx, memKey, digest.title) else emptyMemory(memKey, digest.title)
        val memBlock = if (cfg.memoryEnabled) Memories.contextBlock(mem) else ""
        val lastScore = mem.scores.firstOrNull()?.score

        val state = digest.state(contact, cfg.contextN, memBlock)
        if (state.isEmpty()) {
            running.set(false)
            Toast3.toast(ctx, "没拼出可判读的内容", true)
            return
        }

        if (cfg.showAnalyzing) Toast3.toast(ctx, "Jev 分析中…")
        AppLog.add(
            "判读开始：标题=${digest.title} 关系=${contact.relation} 上下文=${cfg.contextN} 语言=${cfg.lang} " +
                "记忆=${if (cfg.memoryEnabled) mem.turns.size else 0}条 消息长度=${target.text.length}"
        )

        pool.execute {
            val t0 = System.currentTimeMillis()
            val body = Prompt.requestJson(state, cfg.model, cfg.lang, cfg.guideEnabled)
            val result = JevHttp.analyze(cfg.apiKey, body)
            val jevCost = System.currentTimeMillis() - t0

            when (result) {
                is JevResult.Err -> {
                    AppLog.add("判读失败（${jevCost}ms）：${result.message}")
                    Toast3.toast(ctx, "Jev 失败：" + result.message, true)
                    running.set(false)
                }
                is JevResult.Ok -> {
                    val v = Verdicts.parse(result.body, cfg.lang, cfg.emotionTop)
                    if (v == null) {
                        AppLog.add("响应里没有可用答案（${jevCost}ms）：" + result.body.take(300))
                        Toast3.toast(ctx, "Jev 返回了空结果，看设置里的日志", true)
                        running.set(false)
                        return@execute
                    }
                    val guide = v.guidePercent()
                    val trend = if (guide != null && lastScore != null) guide - lastScore else null
                    val lines = v.lines(cfg.emotionTop) + v.guideLine(trend)

                    // ── 记忆回写（先落盘再出文案：文案失败也不该丢记忆）──
                    if (cfg.memoryEnabled) {
                        try {
                            Memories.appendTurns(ctx, memKey, digest.title,
                                digest.msgs.takeLast(20).map { Turn(System.currentTimeMillis(), it.mine, it.text) })
                            if (guide != null) {
                                Memories.addScore(ctx, memKey, digest.title, guide, v.style.ifEmpty { v.advice })
                            }
                        } catch (t: Throwable) {
                            AppLog.add("记忆回写失败：${t.javaClass.simpleName} ${t.message ?: ""}")
                        }
                    }

                    // ── 候选文案（可选，失败只降级不报错）──
                    val drafts = if (cfg.draftsEnabled && cfg.hasChatKey()) {
                        generateDrafts(cfg, memBlock, lines, state)
                    } else {
                        emptyList()
                    }
                    val cost = System.currentTimeMillis() - t0

                    cfg.lastVerdict = v.detail() + "\n" + lines.last() +
                        "\n（${cost}ms · ${contact.relation} · ${cfg.lang} · 文案 ${drafts.size} 条）"
                    AppLog.add(
                        "判读完成（${cost}ms，Jev ${jevCost}ms）：攻略度=${guide ?: "—"} 文案=${drafts.size} " +
                            lines.joinToString(" / ").replace("\n", " ")
                    )

                    // ── 出结果 ──
                    val payload = ResultPayload(
                        title = digest.title,
                        relation = contact.relation,
                        guidePercent = guide,
                        trend = trend,
                        lines = v.lines(cfg.emotionTop),
                        drafts = drafts,
                        costMs = cost,
                        state = state,
                        memoryBlock = memBlock,
                        chatReady = cfg.hasChatKey()
                    )

                    // 攻略度优先上**常驻浮层**：一眼就能看到，不用弹窗挡着聊天
                    ScoreOverlay.lastPayload = payload
                    val overlayText = ScoreOverlay.format(digest.title, guide, trend)
                    cfg.lastOverlayText = overlayText
                    cfg.lastOverlayPercent = guide ?: -1
                    val svc = WatchService.instance
                    if (svc != null) {
                        ScoreOverlay.show(svc, cfg, overlayText, guide)
                    }

                    // 结果页只在"没开浮层"或"明确要求自动弹"时出现；否则点浮层才打开
                    val wantPage = cfg.resultMode == "page" && (!cfg.overlayEnabled || cfg.overlayAutoResult)
                    when {
                        wantPage -> {
                            if (!ResultActivity.show(ctx, payload)) {
                                notifyFallback(ctx, digest.title, lines, drafts)
                            }
                        }
                        cfg.resultMode != "page" -> Toast3.showLines(ctx, lines, cfg.toastGapMs)
                        else -> Toast3.toast(ctx, "已更新：$overlayText（点浮层看 ${drafts.size} 条文案）")
                    }

                    // ── 攒够新对话就刷新一次长期摘要 ──
                    if (cfg.memoryEnabled && cfg.summarizeEvery > 0) {
                        try {
                            val fresh = Memories.load(ctx, memKey, digest.title)
                            if (fresh.turns.size - fresh.turnsAtSummary >= cfg.summarizeEvery) {
                                MemoryUpdater.refresh(ctx, cfg, fresh)
                            }
                        } catch (t: Throwable) {
                            AppLog.add("摘要刷新判断失败：${t.javaClass.simpleName}")
                        }
                    }
                    running.set(false)
                }
            }
        }
    }

    /** 调聊天模型出候选文案；任何异常都吞掉并返回空表（判读结果仍然要给用户） */
    private fun generateDrafts(cfg: Config, memBlock: String, lines: List<String>, state: String): List<Draft> = try {
        val r = ChatHttp.complete(
            cfg.chatBaseUrl, cfg.chatApiKey, cfg.chatModel,
            ReplyPrompt.buildSystem(memBlock, cfg.lang),
            ReplyPrompt.buildUser(lines, state, cfg.draftsN)
        )
        when (r) {
            is ChatResult.Ok -> ReplyPrompt.parse(r.text, cfg.draftsN)
            is ChatResult.Err -> {
                AppLog.add("文案生成失败：" + r.message)
                emptyList()
            }
        }
    } catch (t: Throwable) {
        AppLog.add("文案生成异常：${t.javaClass.simpleName} ${t.message ?: ""}")
        emptyList()
    }

    private fun emptyMemory(key: String, name: String) =
        ContactMemory(key, name, "", "", emptyList(), emptyList(), emptyList(), System.currentTimeMillis(), 0)

    /** 结果页拉不起来时（后台启动被拦）退化成一条可展开的通知，至少让结果看得见 */
    private fun notifyFallback(ctx: Context, title: String, lines: List<String>, drafts: List<Draft>) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("jevguide_result", "分析结果", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val body = buildString {
                append(lines.joinToString("\n"))
                if (drafts.isNotEmpty()) {
                    append("\n\n候选文案：")
                    drafts.forEach { append("\n【").append(it.title).append("】").append(it.text) }
                }
            }
            val open = PendingIntent.getActivity(
                ctx, 0, android.content.Intent(ctx, SettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = android.app.Notification.Builder(ctx, "jevguide_result")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Jev攻略 · $title")
                .setContentText(lines.firstOrNull().orEmpty())
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(8801, n)
        } catch (t: Throwable) {
            AppLog.add("兜底通知失败：${t.javaClass.simpleName}")
        }
    }

    fun busy(): Boolean = running.get()
}
