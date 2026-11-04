package io.github.nisaka520.jevguide

/**
 * 「生成候选文案」编排的**唯一实现**：拼提示词 → 调聊天模型 → 解析出 [Draft]。
 *
 * 原来这段编排有两份：[Analyzer.generateDrafts]（判读流程末尾）与
 * ResultActivity.regenerate（结果页「重新生成」按钮）各写各的 ——
 * 改提示词口径得记得改两处，改漏一处就是"自动生成的文案和手动重新生成的不一样"。
 * 现在收敛到这里，两个调用方只做自己的壳：Analyzer 把失败降级成空表
 * （判读结果仍然要给用户），结果页把失败写成状态行的人话。
 *
 * 只打聊天模型，**不碰 Jev** —— 判读与攻略度不在它的职责里（重新生成也因此便宜一次）。
 * 任何异常都吞掉并转成 [DraftGen.Err]：文案挂了不能拖垮判读流程，也不能崩掉结果页的
 * 生成线程（旧版 regenerate 的裸线程没有兜底，异常直接崩进程）。
 */
object DraftGenerator {

    /** 一次文案请求的结果：成功带草稿，失败带可直接展示给人看的简短原因 */
    sealed interface DraftGen {
        data class Ok(val drafts: List<Draft>) : DraftGen
        data class Err(val message: String) : DraftGen
    }

    /**
     * @param n 要几条。判读流程传 cfg.draftsN；结果页传它 onCreate 时记下的 n
     *          （用户半路改设置时，结果页按打开时的口径重新生成）
     * @param lines 喂给文案模型的判读要点。判读流程传判读行（+风险行）；
     *          结果页「重新生成」传空表 —— 与旧 regenerate 逐字一致，只靠 state 与记忆重写。
     */
    fun request(cfg: Config, memBlock: String, lines: List<String>, state: String, n: Int): DraftGen = try {
        val r = ChatHttp.complete(
            cfg.chatBaseUrl, cfg.chatApiKey, cfg.chatModel,
            ReplyPrompt.buildSystem(memBlock, cfg.lang, cfg.promptExtra, cfg.styles(), n),
            ReplyPrompt.buildUser(lines, state, n)
        )
        when (r) {
            is ChatResult.Ok -> DraftGen.Ok(ReplyPrompt.parse(r.text, n))
            is ChatResult.Err -> {
                AppLog.add("文案生成失败：" + r.message)
                DraftGen.Err(r.message)
            }
        }
    } catch (t: Throwable) {
        AppLog.add("文案生成异常：${t.javaClass.simpleName} ${t.message ?: ""}")
        DraftGen.Err(t.javaClass.simpleName + (t.message?.let { " $it" } ?: ""))
    }
}
