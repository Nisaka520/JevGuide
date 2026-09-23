package io.github.nisaka520.jevguide

/** 从无障碍树里收集出来的一行文本（屏幕坐标，单位 px） */
data class RawLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Float get() = (left + right) / 2f
}

/**
 * 把"屏幕上的若干行文字"组装成一次判读需要的 Digest。
 *
 * 故意做成纯函数：无障碍那层只负责"把节点变成 RawLine"，所有判断（标题、我方/对方、
 * 引用关系、去噪、去重）都在这里，于是可以在电脑上单测，不用真机、不用微信。
 */
object DigestBuilder {

    /**
     * @param screenW/screenH 屏幕像素尺寸
     * @param titleBand       顶部这个比例之内算标题区（微信聊天页标题在顶部）
     * @param bottomBand      这个比例之下算输入区/工具栏，不算消息
     * @param mineRatio       中心点超过屏宽这个比例 → 我发的（单聊里自己的气泡在右）
     * @param linkQuotes      是否尝试把"引用块 + 正文"合成一条（启发式，默认关）
     */
    fun build(
        raw: List<RawLine>,
        screenW: Int,
        screenH: Int,
        titleBand: Float = 0.14f,
        bottomBand: Float = 0.86f,
        mineRatio: Float = 0.62f,
        linkQuotes: Boolean = false
    ): Digest {
        val lines = raw.filter { it.text.isNotBlank() }.sortedBy { it.top }
        val titleTop = (screenH * titleBand).toInt()
        val titleLine = lines.firstOrNull { it.top < titleTop && !ScreenRules.isNoise(it.text) }
        val title = titleLine?.let { ScreenRules.clean(it.text) }.orEmpty()

        val bodyLines = lines.filter { l ->
            val belowTitle = titleLine == null || l.top >= titleLine.bottom - 2
            val aboveInput = l.bottom <= screenH * bottomBand
            belowTitle && aboveInput && !ScreenRules.isNoise(l.text)
        }

        val flagged = ScreenRules.dedupeLines(
            bodyLines.map { it to ScreenRules.isMine(it.centerX, screenW, mineRatio) }
        )
        val msgs = if (linkQuotes) linkQuotes(flagged) else flagged.map {
            ScreenMsg(it.second, ScreenRules.clean(it.first.text))
        }
        return Digest(title, msgs)
    }

    /**
     * 引用块启发式：微信的"引用回复"在无障碍树里常是两个紧贴的节点，
     * 且同一气泡左右边界几乎对齐 → 前一个当引用、后一个当正文。
     * 边界条件写得很紧（左右各 ±6/±8px、垂直间隙 0~10px），宁可不合并也别把两条消息吃掉。
     */
    private fun linkQuotes(flagged: List<Pair<RawLine, Boolean>>): List<ScreenMsg> {
        val out = ArrayList<ScreenMsg>(flagged.size)
        var i = 0
        while (i < flagged.size) {
            val (a, aMine) = flagged[i]
            val next = flagged.getOrNull(i + 1)
            if (next != null) {
                val (b, bMine) = next
                val sameSide = aMine == bMine
                val sameLeft = Math.abs(a.left - b.left) <= 6
                val sameRight = Math.abs(a.right - b.right) <= 8
                val gap = b.top - a.bottom
                if (sameSide && sameLeft && sameRight && gap in 0..10) {
                    out.add(ScreenMsg(bMine, ScreenRules.clean(b.text), ScreenRules.clean(a.text)))
                    i += 2
                    continue
                }
            }
            out.add(ScreenMsg(aMine, ScreenRules.clean(a.text)))
            i++
        }
        return out
    }
}
