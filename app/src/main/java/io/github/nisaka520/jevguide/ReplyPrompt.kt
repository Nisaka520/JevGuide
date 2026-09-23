package io.github.nisaka520.jevguide

/**
 * 一条候选回复。
 *
 * index 从 0 开始，并且与 STYLE_TITLES 的位置对齐（0=稳妥 / 1=推进 / 2=有趣）：
 * 界面按 index 顺序展示，这样"再刷一次"出来的三条永远是同一种排列，用户不会看花眼。
 */
data class Draft(val index: Int, val title: String, val text: String)

/**
 * 3 条候选回复的提示词 + 宽容解析（纯逻辑，可单测）。
 *
 * 为什么让模型吐"标题行 + `---` 分隔"这种土格式，而不是 JSON：
 *  - 回复文案天生带引号、换行、emoji，塞进 JSON 字符串要靠模型自己转义，实测经常坏（少个引号整条就废）；
 *  - 用户在设置页里预览提示词时，土格式一眼能看懂，JSON 提示词只会让人不敢改；
 *  - 解析端只要够宽容，坏格式也能退化出能用的结果 —— 反正模型排版千奇百怪，这一点是必然要做的。
 *
 * parse 的分段优先级（有结构就按结构切，没结构才兜底，绝不抛异常）：
 *   ① 分隔线 `---` / `***` / `===` / `___` / `———`
 *   ② 标题行 `【稳妥】`（兼容 `1. 【稳妥】`、`**【稳妥】**`、`## 稳妥`）
 *   ③ 行首编号 `1.` / `①` / `（2）`
 *   ④ 整段原文当 1 条（title 用 STYLE_TITLES[0]）
 */
object ReplyPrompt {

    /** 默认选中的三种（**不要动**：单测断言它，而且它是没配置时的行为） */
    val STYLE_TITLES = listOf("稳妥", "推进", "有趣")

    /**
     * 全部可选风格（顺序 = 设置页展示顺序）。
     *
     * 加风格时注意两件事：
     *  - 这里加完，解析器的「光秃秃一行就是风格名」也认它（见 titleOf），否则模型写了
     *    【撒娇】会被当成正文，整段错位；
     *  - 每套说明都要写清**适用边界**。风格只决定「怎么说话」，不放松「什么能说」——
     *    【硬性约束】那一段是所有风格共用的。
     */
    val ALL_STYLE_TITLES = listOf("稳妥", "推进", "有趣", "撒娇", "冷淡", "正经", "长辈")

    /**
     * 每种风格的一句话说明（设置页展示 + 拼进提示词，保证两处口径一致）。
     *
     * 写法要求：必须**可执行**。「接住对方」「不冒险」这种抽象词模型理解不了，
     * 它会自己脑补成客服腔。要说清「给什么、不给什么」。
     */
    private val STYLE_HINTS = linkedMapOf(
        "稳妥" to "先接住对方的情绪或信息，再给一个明确具体的回应；不追问、不冒险，短而稳",
        "推进" to "把话题往前推一步：给一个具体的时间、地点或动作，通常用问句收尾让对方好接",
        "有趣" to "幽默的靶子只能是自己或当下的处境，绝不能是对方本人；优先自嘲、共情式夸张、顺着对方的话往上加码，一句话越短越好；禁网络烂梗（牛马/绝绝子/yyds）、禁给对方贴标签、禁解释笑点",
        "撒娇" to "把姿态放软：叠词、拖长音、小小地抱怨或提要求，让对方想哄你。**只适合已经很亲密的关系**，不熟用会尴尬；不许用「你怎么不理我」这种质问式绑架，也不许涉及身体或性暗示",
        "冷淡" to "话少、不主动追问、不带情绪，用短句把话接住就停；用来降温或对等回应。注意：冷淡**不等于**冷暴力、阴阳怪气、翻旧账，不骂人、不暗示威胁",
        "正经" to "就事论事，把信息说清楚（时间、地点、安排、结论），不闲聊不开玩笑；适合工作、办事、和不太熟的人对接。仍然要像人话，不是公文",
        "长辈" to "对长辈或家人：先报平安或回应关心，再说具体安排，多一句问候；不用网络用语和缩写，不顶嘴、不敷衍，也不用撒娇那套"
    )

    /** 人类可读的三种风格说明，供设置页/提示词复用 */
    /** 全部风格的说明（设置页当参考清单用；buildSystem 只取选中的那几套） */
    fun styleGuide(): String = styleGuide(ALL_STYLE_TITLES)

    /** 只给指定风格生成说明，顺序按 [styles] 来 */
    fun styleGuide(styles: List<String>): String =
        styles.mapIndexed { i, t -> "${i + 1}. 【$t】${STYLE_HINTS[t] ?: ""}" }.joinToString("\n")

    /**
     * 组装 system 提示词：角色 + 三种风格 + 【记忆】+ 输出格式 + 硬性约束。
     *
     * 记忆块单独成段是有原因的：它每次都可能不同（关系、备注、上次结论），
     * 混在风格说明里模型会当成"风格的一部分"而忽略掉；单独一段 + 明确标题，遵守率明显更高。
     *
     * @param memoryBlock 可能为空（没有记忆时给一句"（暂无记忆）"，比留空更不容易被模型脑补）
     * @param lang "zh" 用中文提示词；"en" 用英文提示词，但**仍然要求输出中文文案**
     *             （英文提问只是为了拿更高的遵守率，成品必须是中文，不然发给对方就露馅了）
     */
    fun buildSystem(
        memoryBlock: String,
        lang: String,
        extra: String = "",
        styles: List<String> = STYLE_TITLES
    ): String {
        // Memories.contextBlock 自己会带一个「【记忆】」抬头，这里别再套一层（否则提示词里出现两个标题）
        val memory = memoryBlock.trim().removePrefix("【记忆】").trim().ifEmpty { "（暂无记忆）" }
        // 选中的风格必须先过一遍白名单：配置里可能留着旧名字或错别字，
        // 直接拼进提示词会让模型去写一个不存在的风格。全空则回落默认，保证至少有一种。
        val picked = styles.map { it.trim() }.filter { it in ALL_STYLE_TITLES }.distinct()
            .ifEmpty { STYLE_TITLES }
        val base = if (lang == "en") systemEn(memory, picked) else systemZh(memory, picked)
        val add = extra.trim()
        if (add.isEmpty()) return base
        // 额外要求放在最末尾：模型对「最后一段」的注意力最高，而这段正是用户最在意的个性化部分。
        // 后面那句「不得改变输出格式」是必须的 —— 否则模型很容易顺手把三段并成一段，解析就废了。
        return base + "\n\n【额外要求（用户自定义，优先遵守）】\n" + add +
            "\n（以上额外要求不得改变输出格式与硬性约束。）"
    }

    private fun systemZh(memory: String, styles: List<String>): String = buildString {
        append("你是「微信回复代笔」：直接写出用户可以原样发出去的回复，不是分析、不是建议。\n\n")
        append("【最重要的一条：像真人发微信】\n")
        append("- 短。一条 5~25 个字，最多两句。真人不会在微信里写小作文。\n")
        append("- 口语。用「嗯、诶、行、好呀、哈哈、咋、嘛、啦、呗」这类词，允许省略主语。\n")
        append("- 别用句号收尾（真人很少用），别排比，别堆成语，别写「综上所述/因此/此外」。\n")
        append("- 禁客服腔：「您好，请问有什么可以帮您」「收到，我这边会尽快处理」这类一律不许。\n")
        append("- 不复述对方的话，不写「我理解你的意思，我觉得……」这种铺垫。\n")
        append("- 具体。要约就给出时间/地点/动作（「周六中午？」），不要「有空一起吃个饭」。\n")
        append("- emoji 或颜文字最多一个，也可以完全没有；不要每句都带。\n")
        append("- 称呼按关系来，该叫什么叫什么，别生硬地叫全名。\n\n")
        append("【反例：写成这样就算失败】\n")
        append("对方：今天加班到十点，累死了\n")
        append("× 我理解你的辛苦，加班确实很累，希望你注意休息，保重身体。\n")
        append("√ 这么晚啊，回去路上小心点\n\n")
        append("【有趣的反例：这条最常翻车】\n")
        append("对方：今天加班到十点，累死了\n")
        append("× 加班到十点，你是公司纯度百分之百的牛马本人吧  ← 给对方贴标签、拿他的累开玩笑，会伤人\n")
        append("× 哈哈哈哈哈笑死我了  ← 没有内容，等于没接话\n")
        append("√ 十点？这老板是把你当永动机用啊  ← 骂的是老板，站的是他\n")
        append("√ 我刚到家又想起外卖点到公司了，咱俩今天都挺离谱  ← 自嘲，最安全\n")
        append("√ 这么拼，明天记得找老板要加班费，要不我帮你要  ← 夸张的共情\n")
        append("一句话就够，别解释笑点。\n\n")
        append("【三种风格】\n")
        append(styleGuide(styles)).append("\n\n")
        append("【记忆】\n").append(memory).append("\n\n")
        append("【输出格式】\n")
        append("只输出 ").append(styles.size).append(" 段，每段第一行是标题行，形如")
        append(styles.joinToString("、") { "【$it】" })
        append("，其余行是正文；段与段之间用一行 --- 分隔。\n")
        append("不要解释、不要 markdown 代码块、不要编号列表。每段 1~3 句，直接可以发给对方的成品口吻。\n\n")
        append("【硬性约束】\n")
        append("- 必须遵守上面 Jev 给出的回复姿态与关系设定：关系决定称呼和亲疏，姿态决定语气，不能反过来。\n")
        append("- 不要承诺做不到的事（借钱、担保、拍死的时间点，做不到就别写）。\n")
        append("- 不涉及钱、验证码、账号密码、链接等敏感内容，也不要引导对方提供这些。\n")
        append("- 不编造事实，不虚构没发生过的约定；不确定的事就用「我先确认一下」这种说法。\n")
        append("- 只输出候选回复本身，不要输出任何分析过程或前后缀说明。")
    }

    private fun systemEn(memory: String, styles: List<String>): String = buildString {
        append("You are a \"WeChat reply ghostwriter\": you write finished replies the user can send as-is, not analysis and not advice.\n\n")
        append("[Most important: sound like a real person texting]\n")
        append("- Short. 5-25 characters, at most two sentences. Real people do not write essays on WeChat.\n")
        append("- Colloquial Chinese, subject often omitted; sentence-final particles are fine.\n")
        append("- No full stop at the end, no parallelism, no piled-up idioms, no \"therefore / moreover\".\n")
        append("- No customer-service voice (\"Hello, how may I help you\" / \"Received, I will handle it shortly\").\n")
        append("- Do not echo what they said; no \"I understand how you feel, I think...\" preamble.\n")
        append("- Be concrete: propose a time/place/action, not \"let's grab a meal sometime\".\n")
        append("- At most one emoji, none is fine.\n")
        append("- Use the form of address the relationship implies, not their full name.\n\n")
        append("[Counter-example: this counts as failure]\n")
        append("Them: worked overtime till ten, exhausted\n")
        append("x I understand how hard it is, overtime is really tiring, please rest well and take care.\n")
        append("v That late? Be careful on the way home\n\n")
        append("[Three styles]\n")
        append("1. [稳妥] Steady: pick up what the other person said, give a clear answer, avoid risk and offence.\n")
        append("2. [推进] Advance: move things forward with a plan, a time, or a next step.\n")
        append("3. [有趣] Playful: a light joke that makes them smile, without being creepy or crossing a line.\n\n")
        append("[Memory]\n").append(memory).append("\n\n")
        append("[Output format]\n")
        append("Output exactly ").append(styles.size).append(" sections. The first line of each section is a title line like ")
        append(styles.joinToString(", ") { "【$it】" })
        append("; the remaining lines are the reply body. Separate sections with a single line of ---.\n")
        append("No explanations, no markdown code fences, no numbered lists. Each section is 1-3 sentences in a finished, send-ready tone.\n")
        append("Write the drafts in Chinese.\n\n")
        append("[Hard rules]\n")
        append("- Follow the reply stance and the relationship setting that Jev reported above; the relationship decides the form of address.\n")
        append("- Never promise anything you cannot deliver (no loans, no guarantees, no impossible deadlines).\n")
        append("- No money, verification codes, passwords or links, and never ask the other side for them.\n")
        append("- Do not invent facts or agreements that never happened.\n")
        append("- Output only the drafts themselves, with no analysis and no surrounding commentary.")
    }

    /** 组装 user 提示词：把 Jev 分析结论 + 当前对话 state 交给模型（state 是中文原文，不翻译） */
    fun buildUser(jevLines: List<String>, state: String, n: Int = 3): String = buildString {
        append("【Jev 分析结论】\n")
        if (jevLines.isEmpty()) {
            append("（无）\n")
        } else {
            for (line in jevLines) append("- ").append(line.trim()).append('\n')
        }
        append("\n【当前对话 state】\n")
        append(state.trim().ifEmpty { "（无）" })
        append("\n\n请按 system 里的格式输出 ").append(n.coerceAtLeast(1)).append(" 段候选回复。")
    }

    /**
     * 宽容解析模型输出 → 最多 n 条 Draft。**任何输入都不抛异常**：
     * 调用方在无障碍服务里跑，抛出去就是一次崩溃弹窗，而用户只是想让模型写句话。
     */
    fun parse(raw: String, n: Int = 3): List<Draft> {
        val want = n.coerceAtLeast(1)
        return try {
            val out = ArrayList<Draft>(want)
            for (section in sections(unfence(raw))) {
                if (out.size >= want) break
                val draft = toDraft(section, out.size) ?: continue
                out.add(draft)
            }
            if (out.isEmpty()) fallback(raw) else out
        } catch (e: Exception) {
            // 兜底本身也可能踩到奇怪输入，那就干脆认输返回空表 —— 也绝不往外抛
            try {
                fallback(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // ---------------- 解析内部 ----------------

    /** 分段中间产物：title 可能为 null（模型没写标题），正文交给 cleanBody 清洗 */
    private class Section(val title: String?, val body: String)

    /** 分隔线：模型各写各的，常见几种都认（"---" 后面跟说明文字的不算，那多半是正文） */
    private val SEPARATOR = Regex("""^[-*_=~—－]{3,}$""")

    /** 行首的编号 / 项目符号 / 引用符号 / markdown 井号："1. "、"①"、"- "、"> "、"（2）"、"## " */
    private val LEADING_MARK = Regex("""^\s*(?:#+\s*|[-*•·>]\s+|\d{1,2}[.、)）]\s+|\d{1,2}[、)）]\s*|[①-⑳]\s*|[（(]\d{1,2}[)）]\s*)""")

    /** 纯编号行（用于"既没有标题也没有分隔线"时的最后一种切分依据） */
    private val NUMBER_HEAD = Regex("""^\s*(?:(\d{1,2})[.、)）]\s*|([①-⑳])\s*|[（(]\d{1,2}[)）]\s*)""")

    /** `【标题】`（标题里不该有换行，也不该长到 20 字以上） */
    private val BRACKET_TITLE = Regex("""【([^】\n]{1,20})】""")

    /**
     * 段尾的解释性废话。只在**最后一行**、且正文还剩别的内容时才削，
     * 并且刻意写得很窄：像"希望你能理解"这种正经正文不会被误伤。
     */
    private val TRAILING_NOISE = Regex(
        """^(?:以上|希望(?:这些|以上|能帮|对你有帮|有所帮助)|如需|如果需要|需要我|要不要我|注[:：]|说明[:：]|备注[:：]|温馨提示|供你参考|你可以挑|挑一条)"""
    )

    /** 去掉 ``` 围栏行（` ```json ` 这种带语言标记的一并去掉），围栏内容原样留下 */
    private fun unfence(raw: String): String =
        raw.split('\n').filterNot { it.trim().startsWith("```") }.joinToString("\n")

    /** 按优先级挑一种切法；返回空表表示"看不出任何结构"，交给 fallback */
    private fun sections(text: String): List<Section> {
        val lines = text.split('\n')

        // ① 分隔线：最可靠的信号，模型只要照格式写就一定有
        var bySep = splitBy(lines) { SEPARATOR.matches(it.trim()) }.map { sectionOf(it) }
        if (bySep.size >= 2) {
            // 第一个分隔线之前的孤立小块通常是"以下是三条回复："这类前言，后面有正经段落就丢掉
            val firstTitled = bySep.indexOfFirst { it.title != null }
            if (firstTitled > 0) bySep = bySep.drop(firstTitled)
            return bySep
        }

        // ② 标题行（顺带把标题之前的废话丢掉）
        val byTitle = splitByTitle(lines)
        if (byTitle.isNotEmpty()) return byTitle

        // ③ 行首编号（标题用 STYLE_TITLES 按位置补）
        val byNumber = splitByNumber(lines)
        if (byNumber.isNotEmpty()) return byNumber

        return emptyList()
    }

    private fun splitBy(lines: List<String>, cut: (String) -> Boolean): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (line in lines) {
            if (cut(line)) {
                if (cur.isNotEmpty()) out.add(cur)
                cur = ArrayList()
            } else {
                cur.add(line)
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** 一个分隔块 → Section：块里第一个标题行之前的内容算模型的前言，丢掉 */
    private fun sectionOf(chunk: List<String>): Section {
        for (i in chunk.indices) {
            val t = titleOf(chunk[i]) ?: continue
            val body = ArrayList<String>()
            if (t.second.isNotEmpty()) body.add(t.second)
            body.addAll(chunk.subList(i + 1, chunk.size))
            return Section(t.first, body.joinToString("\n"))
        }
        return Section(null, chunk.joinToString("\n"))
    }

    /** 按标题行切：第一个标题之前的行（前言）直接丢，标题行同一行剩下的文字算正文第一行 */
    private fun splitByTitle(lines: List<String>): List<Section> {
        val out = ArrayList<Section>()
        var title: String? = null
        var body = ArrayList<String>()
        var started = false
        for (line in lines) {
            val t = titleOf(line)
            if (t != null) {
                if (started) out.add(Section(title, body.joinToString("\n")))
                started = true
                title = t.first
                body = ArrayList()
                if (t.second.isNotEmpty()) body.add(t.second)
            } else if (started) {
                body.add(line)
            }
        }
        if (started) out.add(Section(title, body.joinToString("\n")))
        return out
    }

    /** 按行首编号切（`1.` / `①` / `（2）`）；编号行本身也可能带【标题】 */
    private fun splitByNumber(lines: List<String>): List<Section> {
        val out = ArrayList<Section>()
        var title: String? = null
        var body = ArrayList<String>()
        var started = false
        for (line in lines) {
            val rest = numberRest(line)
            if (rest == null) {
                if (started) body.add(line)
                continue
            }
            if (started) out.add(Section(title, body.joinToString("\n")))
            started = true
            title = null
            body = ArrayList()
            val t = titleOf(rest)
            if (t != null) {
                title = t.first
                if (t.second.isNotEmpty()) body.add(t.second)
            } else if (rest.isNotEmpty()) {
                body.add(rest)
            }
        }
        if (started) out.add(Section(title, body.joinToString("\n")))
        return out
    }

    /**
     * 编号行 → 去掉编号后的正文；不是编号行返回 null。
     * "3.5 折给你" 这种必须排除掉：编号后面紧跟数字/小数点就不是列表项，否则正文会被啃掉一截。
     */
    private fun numberRest(rawLine: String): String? {
        val line = rawLine.trim()
        val m = NUMBER_HEAD.find(line) ?: return null
        val rest = line.substring(m.value.length)
        val head = rest.firstOrNull() ?: return ""
        if (head.isDigit() || head == '.' || head == '%') return null
        return rest
    }

    /** 标题行 → (标题, 同一行剩下的正文)；不是标题行返回 null */
    private fun titleOf(rawLine: String): Pair<String, String>? {
        var line = rawLine.trim()
        val mark = LEADING_MARK.find(line)
        if (mark != null) line = line.substring(mark.value.length).trim()
        if (line.isEmpty()) return null

        val br = BRACKET_TITLE.find(line)
        if (br != null) {
            val title = cleanTitle(br.groupValues[1])
            if (title.isNotEmpty()) return title to line.substring(br.range.last + 1).trim()
        }
        // 没有【】时也认"光秃秃一行就是风格名"（"## 稳妥"、"**稳妥**"）
        val bare = cleanTitle(line)
        // 必须认**全部**风格名：只认默认三种的话，模型写【撒娇】会被当成正文，整段错位
        return if (bare in ALL_STYLE_TITLES) bare to "" else null
    }

    private fun cleanTitle(s: String): String =
        s.replace("**", "").replace("*", "").replace("`", "")
            .replace("【", "").replace("】", "")
            .trim()
            .trimEnd('：', ':', '。', '.', '、', '-', '—', ' ', '　')

    private fun toDraft(section: Section, index: Int): Draft? {
        val text = cleanBody(section.body)
        if (text.isEmpty()) return null // 空段丢弃：标题写对了但正文是空的，展示出来只会占位置
        val title = section.title?.takeIf { it.isNotEmpty() }
            ?: STYLE_TITLES.getOrElse(index) { "方案${index + 1}" }
        return Draft(index, title, text)
    }

    /**
     * 正文清洗：去 markdown 强调符、去行首编号/项目符号、分隔线当空行、折叠多余空行、削段尾废话。
     * 顺序不能反 —— 先去掉 `**` 才能让 `**1.**` 这种"加粗的编号"被识别成编号。
     */
    private fun cleanBody(raw: String): String {
        val lines = ArrayList<String>()
        for (line in raw.split('\n')) {
            var s = stripMd(line).trim()
            var guard = 0
            while (guard++ < 3) {
                val m = LEADING_MARK.find(s) ?: break
                s = s.substring(m.value.length).trim()
            }
            // 正文里残留的分隔线（模型多写了一条）不能当内容展示
            if (SEPARATOR.matches(s)) s = ""
            lines.add(s)
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        while (lines.size > 1 && TRAILING_NOISE.containsMatchIn(lines.last())) {
            lines.removeAt(lines.size - 1)
            while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        }

        val sb = StringBuilder()
        var pendingBlank = false
        for (l in lines) {
            if (l.isEmpty()) {
                if (sb.isNotEmpty()) pendingBlank = true
                continue
            }
            if (sb.isNotEmpty()) sb.append('\n')
            if (pendingBlank) {
                sb.append('\n')
                pendingBlank = false
            }
            sb.append(l)
        }
        return sb.toString().trim()
    }

    /** 去掉 markdown 强调符（`**`、`*`、反引号）—— 在回复文案里它们只可能是模型的排版，不是内容 */
    private fun stripMd(s: String): String =
        s.replace("**", "").replace("`", "").replace("*", "")

    /** 兜底：看不出结构时，整段原文当 1 条，标题用 STYLE_TITLES[0] */
    private fun fallback(raw: String): List<Draft> {
        val text = cleanBody(unfence(raw))
        if (text.isEmpty()) return emptyList()
        return listOf(Draft(0, STYLE_TITLES[0], text))
    }
}
