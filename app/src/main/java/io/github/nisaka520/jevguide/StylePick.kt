package io.github.nisaka520.jevguide

/**
 * 风格点选器的**判定**部分：点一下之后，是「换成了新的三套」还是「拒绝，并说明为什么」。
 *
 * 为什么把它从界面里抽出来：这两条边界（选满三套再点第四套、想把最后一套点掉）
 * 原来只写在点击回调里，只能靠**在真机上点**来验 —— 而真机上手指会跟自动化抢屏幕，
 * 点歪一次结论就不可信了。抽成纯函数之后，这两条边界能在 JVM 单测里被钉死，
 * 界面那层只剩「照结果办事」。
 *
 * 关键约定：**拒绝时绝不修改任何状态**。静默修正（比如自动把最早选的那套顶掉）
 * 是最难排查的一类问题 —— 用户会以为"点了没反应"或者"自己乱跳"。
 */
object StylePick {

    /** 一次点击的结果。名字避开 `Result` —— 那会跟 kotlin.Result 撞上，编译期直接认错类型 */
    sealed class Outcome

    /** 换成这几套（顺序 = 用户点选的先后顺序） */
    data class Changed(val picked: List<String>) : Outcome()

    /** 拒绝了，reason 是要弹给用户看的话 */
    data class Refused(val reason: String) : Outcome()

    /**
     * @param current 当前选中的几套（顺序有意义，会原样保留）
     * @param name    这次点的名字；不认识的名字直接当"没点"
     * @param max     上限，默认就是 [ReplyPrompt.MAX_PICK]
     */
    fun toggle(current: List<String>, name: String, max: Int = ReplyPrompt.MAX_PICK): Outcome {
        if (name !in ReplyPrompt.ALL_STYLE_TITLES) return Refused("不认识这套风格")
        val picked = current.toMutableList()
        if (name in picked) {
            if (picked.size <= 1) return Refused("至少要留一套风格")
            picked.remove(name)
        } else {
            if (picked.size >= max) return Refused("最多同时选 " + max + " 套：先点掉一套再选新的")
            picked.add(name)
        }
        return Changed(picked)
    }
}
