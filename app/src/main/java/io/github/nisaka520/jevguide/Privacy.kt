package io.github.nisaka520.jevguide

/**
 * 首启须知与隐私政策的**唯一出处**。
 *
 * 为什么要单独一个文件：同一段披露要出现在三处（首启弹窗、设置页「关于」、README 与落地页），
 * 分散着写迟早会漂移 —— 而「声明与实际行为不一致」正是无障碍类 App 被拒绝上架的头号原因
 * （本项目的无障碍说明就曾经写着「也不截屏」，而视觉读屏实际会截屏）。
 *
 * 改动须知内容时：把 [VERSION] 加一，用户下次打开会重新被问一次（见 MainActivity.maybeAskConsent）。
 */
object Privacy {

    /** 须知版本：**改了披露内容就 +1** */
    const val VERSION = 1

    /** 隐私政策地址（GitHub Pages，静态 HTML） */
    const val URL = "https://nisaka520.github.io/JevGuide/privacy.html"

    /** 开源仓库（MIT）——「关于」里的入口、报 issue、看更新日志都从这里走 */
    const val REPO = "https://github.com/Nisaka520/JevGuide"

    /**
     * 首启须知正文。
     *
     * 注意：AlertDialog **不渲染 markdown**，所以正文用纯文本 + 换行，别写 `**加粗**`。
     */
    fun disclosure(): String = buildString {
        append("这个 App 要读你在微信里当前打开的那个聊天窗口的内容，")
        append("用来判断关系进展（攻略度）并生成回复候选。请先确认这几件事：\n\n")
        append("1. 怎么读：无障碍服务读当前聊天窗口里已显示的文字；")
        append("微信屏蔽无障碍树时会截屏（仅当前台就是微信，且你选了视觉读屏）交给模型识别，截图不落盘。\n")
        append("2. 内容会离开这台手机：聊天内容、联系人关系与长期记忆会发给你自己配置的两个接口 —— ")
        append("Jev（判读/攻略度）和你自己的聊天模型（出文案）。密钥与接口费用都由你自己承担。\n")
        append("3. 其中包含「对方」说的话：请自行确认你有权这样处理；")
        append("请勿用于骚扰、跟踪或任何违法用途。\n")
        append("4. 不修改微信、不自动发送任何消息；结果是模型判断，不是测量，仅供参考。\n")
        append("5. 密钥与记忆只存在本机，卸载即消失；设置页有「清空全部记忆与设置」。\n\n")
        append("完整说明（数据种类、去向、保留与删除）：")
        append(URL)
    }
}
