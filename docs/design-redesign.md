# 首页重构 · 设计说明（阶段一）

> 方法：oil-ui（先定北极星，再让构图/字体/色彩/控件都朝它靠拢）。
> 交付物：`ui/Design.kt`（唯一变量源）+ `ui/UiKit.kt`（组件工厂）+ `ui/ThemeExt.kt`（系统环境封装）+ 重写后的 `MainActivity.kt` + 抽取的 `ResultPayload.kt`。

## 一、用户与任务

**产品**：弦外之音（JevGuide）——安卓微信聊天关系进展助手。无障碍读屏 → Jev 判读（意图/情绪/攻略度）→ 生成候选回复，攻略度常驻悬浮条。
**首页用户任务**（按频率排）：

1. **确认"现在能不能用"**——无障碍授权了吗、密钥填了吗、浮条开了吗；
2. **一键启动**（或授权后回来再点一次）；
3. **找到 7 个设置分区**：无障碍/浮条/密钥/关系/记忆/高级/关于。

**主动作：启动判读**——它是全页唯一的实心按钮；其余一切让路。
**隐私红线**（不动，只在视觉上更清楚）：不改微信、不发消息、不注入点击、截图不落盘。

## 二、品类与标杆

**品类**：隐私敏感型关系辅助工具（读屏判读仪器），不是社交/聊天 App。中文界面，安卓 MD3 体系（minSdk 26 / targetSdk 35，DayNight，Material You 动态取色由 `JevApp` 全局打开）。

**标杆拆解**（无人值守环境，未能取到标杆实时截图，以下为**凭记忆拆解**，字体/色彩两栏属待真机验证项）：

1. **系统设置（Android Settings）**——行式结构、组间距、每行标题+摘要+箭头；为什么：入口列表的秩序感是被验证过的扫读效率。沿用：行式入口、分组留白。舍弃：它的灰白无信号色。
2. **日记/情绪类（Daylio、潮汐）**——克制：强调色剂量低、一天一个动作、状态一眼可读。沿用：色彩预算（accent ≤ 画面 10%）、把状态做成第一眼可扫的东西。舍弃：情绪化插画与暖色滤镜。
3. **分数仪表类（健康/电池/信用分）**——大数字 + 红黄绿语义档 + 趋势箭头。沿用：红绿灯语义色、状态词当主角、"数字画成看得见的量（LED 灯）"。舍弃：图表卡片堆砌。

**调性五刻度**：能量=安静（挂在后台干活的仪器）；完成度=精致（MD3 体系、公差统一）；密度=中（一屏装下面板+7 入口，行式紧凑）；分量=轻（零阴影，层次靠色阶）；严肃度=庄重理性（无插画、无游戏感）。

## 三、三个候选方向（全部留档）

### A 「仪表台」——引擎：借仪器的整套表现形式

- **感觉**：一台安静的仪器面板。"能不能用"是一排可扫的灯，"启动"是唯一的总开关。
- **首屏**：品牌大字 → 就绪面板（大号状态词 + 红绿灯 LED + 四条检查行 + 启动/停止）→ 编号入口行三组 → 页脚一句话。
- **字体**：系统黑体；字阶 5 档收敛（32/20/14.5/12.5/11sp）；编号用粗体小字。
- **色彩**：面板中性色走主题 attr（跟 Material You）；信号色（红黄绿）与 7 分区 accent 写死、脱离壁纸取色。
- **主视觉**：状态灯（LED + 状态词），零插画。

### B 「信笺」——引擎：一个具体场景（深夜读信、在字里行间做批注）

- **感觉**：私密、温柔，像读一封恋爱信件的旁批。
- **首屏**：衬线大标题主张句 + 一张"信纸"卡（判读状态写成三行短句）+ 横线便签式入口。
- **字体**：标题系统衬线（serif）、正文黑体。
- **色彩**：暖纸底（固定色）、墨蓝文字、朱砂批注色。
- **主视觉**：手写式下划线/批注符号。
- **风险**：中文衬线在低端机回退不稳；固定暖纸色与全局 DynamicColors 机制相抵；与"实时仪器"气质有偏。

### C 「呼吸灯」——引擎：材质×环境（深夜床头的一盏夜灯）

- **感觉**：安静的陪伴，一枚会呼吸的光点是唯一主角。
- **首屏**：近黑画布中央一枚发光圆（状态=光的颜色），一行状态句，入口收进两张低对比列表。
- **字体**：超大细字重标题、大留白。
- **色彩**：单色画布，唯一彩色来自状态光。
- **主视觉**：radial 光晕圆。
- **风险**：深色画布白天强光可读性差；与 DayNight/Material You 机制拧着；7 个入口被藏太深，伤害任务 3。

### 选定：A「仪表台」。理由

1. **任务直译**：首页任务就是"确认就绪 + 启动"，仪表语言把这两件事直接翻译成"灯 + 总开关"，主动作唯一且明显。
2. **全 App 一套词汇**：攻略度浮条与结果页本来就是红黄绿分数仪表（同一 ≥70/≥40 口径），首页用同一语言，App 内不需要第二套视觉词汇。
3. **长在现有机制上**：动态组走 attr 解析（Material You 照常生效）、固定组绕开壁纸取色——完全兼容 JevApp 的全局 DynamicColors，日夜两态都稳，不动任何冻结面。B/C 都要引入固定画布色，与全局动态取色机制相抵，改机制动的是别人的地盘。
4. **可扫读**：系统设置的行式秩序是被验证过的入口列表形态，7 个分区一屏可见。

**差异检验**：A（行式面板+信号灯/黑体/中性面板+信号色/主视觉=状态灯）与 B（信纸卡+衬线+暖纸墨蓝+批注线）、C（单点光晕+细字重+单色画布+光球）四项无一相同；三卡"未看图，凭记忆"已如实标注；A 与标杆不撞脸——系统设置没有语义灯与总判状态词，这是品类基线之上的仪器化偏离。

## 四、设计变量表（唯一来源 `ui/Design.kt`）

### 字阶 Type（5 档）

| 档 | 值 | 用途 |
| --- | --- | --- |
| DISPLAY | 32sp | 首屏品牌字、状态词（一屏最多一两处） |
| TITLE | 20sp | 区块标题（子页/结果页用） |
| BODY | 14.5sp | 正文、卡片标题、按钮 |
| CAPTION | 12.5sp | 辅助说明、标签、编号 |
| MICRO | 11sp | 一屏一两处的微注，正文禁用 |

### 间距 Space（5 档）

`XS=4 / S=8 / M=12 / L=16 / XL=24`（dp）。同组 S，组间 XL，区块内边距 L。

### 圆角 Radius（4 档）

`SM=10`（按钮）/ `MD=16`（入口行卡片）/ `LG=24`（大面板）/ `PILL=999`（胶囊，渐变自动钳半高，浮条同语言）。

### 固定语义色（红绿灯，刻意不跟 Material You）

口径与浮条/结果页一致：**≥70 绿 / ≥40 黄 / 其余红，null=灰**；全部为新定值，不沿用旧色值。

| 色 | 浅色 | 夜间 |
| --- | --- | --- |
| scoreGood | `#147A4E` | `#3FD68F` |
| scoreMid | `#9A6A00` | `#F2C14E` |
| scoreBad | `#B33530` | `#EC7A70` |
| scoreNeutral | `#7D8894` | `#8D99A6` |

### 分区 accent（7 个，日夜两套，剂量 ≤10%，只上编号与小色标）

| Section | 浅色 | 夜间 |
| --- | --- | --- |
| A11Y 无障碍 | `#0E7490` | `#5BD0DB` |
| OVERLAY 悬浮条 | `#4E7A0A` | `#BEDA5E` |
| KEY 密钥 | `#1D5DC6` | `#8AB2F8` |
| RELATION 关系 | `#B03A5B` | `#F08CA6` |
| MEMORY 记忆 | `#6D4FC2` | `#B49FF2` |
| ADV 高级 | `#A8540E` | `#F3A45C` |
| ABOUT 关于 | `#566173` | `#97A2B1` |

### 动态主题组（attr 解析，Material You 生效时跟壁纸走）

`surface / onSurface / onSurfaceVariant / containerHigh / containerLow / outline / primary / onPrimary`——全部经 `UiKit.attr` 解析主题属性，仅保留解析失败的兜底值。

## 五、关键状态

| 状态 | 总判词 | LED | 判定 |
| --- | --- | --- | --- |
| 已就绪 | 「已就绪」绿 | 绿 | 服务在跑 + Jev 密钥 + 聊天模型齐 |
| 缺配置 | 「缺配置」黄 | 黄 | 服务在跑但配置缺 |
| 未启动 | 「未启动」灰 | 灰 | 服务没连上（含已授权未连上的中间态，由检查行细分） |

- 检查行四项：无障碍服务（运行中 ✓ / 已授权待连 / 未开启）、Jev 密钥、聊天模型、常驻浮条；每行小 LED 同一口径。
- `onResume` 全量重刷（从系统设置授权回来不显示旧状态）；`onStart/onStop` 成对压制/恢复浮条。
- 首启 consent 对话框：不同意即 finish；「打开隐私政策」后 800ms 重新询问。

## 六、验收重点

1. **行为零漂移**：consent 流程、启动四步（通知权限→无障碍设置→返回重试→restartSelf）、`a11yEnabled` 系统检测、`screen` extra 协议（7 个值逐字对齐 `sectionRuns`）、转场双路径、浮条压制对。
2. **日夜两态**：浅色/深色下状态词、LED、编号对比度都够看；Material You 设备上底色跟壁纸、红绿灯不跟。
3. **单测**：167 个 JVM 单测全绿——尤其 ScoreOverlay 的 format()/colorOf() 契约未被触碰（旧色值仍钉在测试里，Design.kt 只对齐阈值口径，不对齐色值）。
4. **视觉主角唯一**：首屏一眼只有"就绪面板"一个主角；入口行安静；accent 剂量不超标。
5. **真机待验**（本次无设备，静态验证为主）：32sp 状态词在长词（"缺配置"）不溢出；窄屏下检查行值两行换行不裁切；`hero_banner.webp` 不再被引用后不影响构建。

## 七、未做与后续

- 本次只重构首页；设置子页、结果页换用 Design.kt 变量属后续阶段（`applyScreen`/section 协议已核对，见上）。
- 标杆拆解未取到真实截图（无人值守），若后续有真机/浏览器条件，建议对 A 方向补一轮"对着真实画面拆标杆"的差异复核。
- `res/drawable-nodpi/hero_banner.webp` 在首页弃用后暂留仓库（仅一个引用点被移除），后续阶段统一清理资源时再删。

---

# 设置子页重构 · 设计说明（阶段二）

> 延续「仪表台」，同一套变量（`ui/Design.kt`）与控件语法（`ui/UiKit.kt`），不是旧布局换色。
> 对象：`SettingsActivity.kt`（旧版 ~1138 行、`buildStatic()` 单函数 ~500 行、先建后删的反模式）。

## 一、任务与结构决定

设置子页的用户任务是「找到那一组设置 → 看懂 → 改掉」。旧版把 12 个分区一次建满再按
`sectionRuns` 下标从后往前删，只留当前屏 —— 慢，且让「输出块」（日志）指着随时会被删掉的
View。阶段二的结构决定：

- **分区即页面**：12 个分区各自一个 build 函数，`buildPage()` 按 `screen` extra 只建当前屏的
  分区。屏→分区归属逐字继承旧版：a11y=状态/判读设置/抓屏诊断｜key=三方模型/聊天模型/识图｜
  about=关于/维护，其余屏单分区。
- **section 协议不动**：a11y/overlay/key/relation/memory/adv/about 七个值与首页入口行逐字对齐；
  空 screen（系统无障碍设置的 settingsActivity 入口）与非法值都回退成完整页面，不带返回行。
- **「← 返回首页」保留**（子屏顶部），返回转场双路径保留（API34+ onCreate 注册 / 老 API finish 补）。

## 二、视觉决定（与首页同一套词汇）

| 元素 | 语法 | 理由 |
| --- | --- | --- |
| 分区头 | 分区色小色点（8dp）+ accent 粗体 BODY 标题 | accent 剂量只花在这一处；同屏分区共用该屏 accent，从首页入口行的编号色一路认到这里 |
| 说明文字 | BODY 14.5sp `onSurfaceVariant`，`**强调**` 真加粗 | 正文级可读性；星号不再原样露出 |
| 机器输出块（状态/日志/记忆概况/提示词预览） | 等宽 CAPTION + `containerLow` 底 + MD 圆角，可选中复制 | 「机器输出」一眼可辨；复制是硬需求 |
| 输入框/下拉 | 描边盒，圆角 SM（10dp）——与按钮同档 | 控件语法与首页按钮一致（旧 12dp 收进 Radius.SM） |
| 按钮 | `UiKit.button`：实心=主动作、描边=次动作 | 与首页「启动判读/全部停止」同一语法（旧版次按钮是 tonal，统一成描边） |
| 风格 chips | 胶囊（PILL） | 浮条同语言；选中=实心主色、未选=容器色 |
| 输出块归属 | 每个分区的输出块建在该分区内 | 旧版子屏模式下「查看记忆/视觉测试/诊断显示」写入**已被删除的维护分区日志块**，用户永远看不到 —— 输出块归位顺带修掉这四个死点 |

分区 accent 全部来自 `Design.accent(Section, night)`（旧版 4 处硬编码色值清零），
动态主题组照旧走 attr（Material You 生效）、红绿灯与 accent 刻意脱离壁纸取色。

## 三、行为决定（与视觉无关但写在同一页重构里）

1. **裸 Thread 收口**：扫记忆、测 Jev 密钥、测聊天模型三处裸 `Thread{} + runOnUiThread`
   统一换成 `background(work, onDone)`（cachedThreadPool + 主线程回调 +
   `isFinishing/isDestroyed` 守卫；work 返回 null = 失败不回调）。测试按钮的请求与提示逐字不变。
2. **消灭 recreate()**：厂商预设切换 → `fillVendorArea()`/`fillVisionArea()` 局部重画输入框区
   （applyProvider 已把新值写进 Config，重画即新值；识图三项也随厂商变，一并重画）；
   清空设置 → `buildPage()` 按当前屏重建 View 树，Activity 不动、滚动不闪。
3. **记忆扫描出主线程**：旧版 `buildStatic` 里同步 `Memories.listAll`（主线程扫目录）改为
   缓存 + 后台扫描后回填（「正在读取本机记忆…」→ 概况）。
4. **文案随行为更新的四处**：导出失败提示（memory 屏没有"下面的日志区"）、清空设置提示
   （"重开本页恢复默认"→"本页已恢复默认"）、诊断/记忆输出提示（"显示在下方"）。
   其余 249 个用户可见中文串逐字未动（diff 校验）。
5. **SharedPreferences 全走 Config**：键名零接触；设置页内无任何直接 SP 访问。

## 四、验收（本阶段实际执行）

- `./gradlew.bat --console=plain -I ../mirror.init.gradle testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL；
  JUnit XML：total=167 failures=0 errors=0 skipped=1（跳过项为 LiveJevSmokeTest，需 JEV_KEY、按 assumeTrue 设计跳过，存量行为）。
- 冻结面 git 复核为零触碰（A 级 22 文件、a11y_config.xml、WatchService/Analyzer/JevApp/ScoreOverlay、构建文件）。
- 真机/截图验证未做（无人值守无设备）：设置页日夜两态、Material You 底色跟随属待真机复核项。

---

# 结果页与常驻浮条重构 · 设计说明（阶段三）

> 延续「仪表台」：结果页（浮层）与常驻浮条接入 `ui/Design.kt` / `ui/UiKit.kt`；
> 好/中/差语义色彻底归一；「生成候选文案」编排收敛为单一实现。

## 一、结果页（ResultActivity）

- **主角仍是攻略度英雄卡**。大号百分比从旧 52sp 收进字阶 `Type.DISPLAY`（32sp，一屏最多一两处的主角档），
  "%" 用 TITLE，趋势尾注用 BODY；判读行/文案卡正文统一 BODY；状态行与「点四周空白处关闭」用 MICRO。
- **英雄卡保留**「容器色 + 10%~22% 分数色渐变」的做法（`blend` 只在本页保留），
  色值改由 `Design.containerLow` + `Design.scoreColor` 现算；圆角走 `Radius.LG`（24）。
- **语义色归一**：删掉本页自带的三对好/中/差色值（旧日间红 0xFFB3261E），
  趋势 ↑/↓、进度条、百分比全部走 `Design.scoreColor`/`scoreGood`/`scoreBad`。
- **按钮语法**与首页对齐：复制全部=实心主动作、重新生成=描边次动作（旧版两颗都是 tonal）。
- **行为逐字保留**：singleInstance + onNewIntent→recreate、`setFinishOnTouchOutside`、
  点文案卡复制并自动 finish、重新生成只重打聊天模型（传空 lines 与旧实现逐字一致）、
  onStart/onStop 浮条压制对、古诗行不设点击、Intent extra 键名零改动。

## 二、常驻浮条（ScoreOverlay）

- `format()` 一字未动；`colorOf()` 签名与纯度不变，实现改为转发 `Design.scoreColor`，
  **固定夜间提亮档**（night=true）：浮条叠在任意内容上，亮一档深浅底都读得清
  （旧口径本来就是固定亮档，只是色值各写一套）。`main` Handler 保持 `by lazy`（JVM 单测可加载）。
- View 构建段接 token：容器/描边走 `Design.containerHigh`/`outline`（attr 解析，日夜跟随），
  文字走 `UiKit.text` + `Type.CAPTION`（行距 0，免得把单行胶囊撑高）；
  胶囊几何（内距 14/9、色环 10/环宽 3、分隔线 1×18、图标 20dp、圆角 40、右端 **52dp 识别区**）逐字保留。
- `ContextThemeWrapper(R.style.Theme_JevGuide_Overlay)` 链原样保留；
  DragTap 三手势零改动：拖动>12px 记坐标入 Config、800ms 长按隐藏、右侧 52dp 识别区。
- 删除本文件私有的 `dp()`/`themedColor()` 重复实现（统一走 UiKit）。

## 三、编排收敛与全局清理

- 新 `DraftGenerator`：「拼提示词 → ChatHttp.complete → ReplyPrompt.parse」的唯一实现，
  失败（含异常）转 `DraftGen.Err` 带人话原因。`Analyzer.generateDrafts` 变薄转发
  （Err→空表，失败日志两条原样搬进 DraftGenerator）；结果页「重新生成」调同一份
  （Err→状态行），顺带补上旧裸线程缺的异常兜底与 isFinishing 守卫（旧版线程体抛异常会崩进程）。
- WatchService 最后一处裸 `android.widget.Toast` → `Toast3`（文案不变）；
  通知构建段未动（平台通知无 UiKit 造型可言，动它只会碰事件/防抖红线）。
- res 清理：`hero_on_banner` 与旧语义色 6 色（bg/card/brand/text/text_dim/warn，日夜两份）
  全局无引用后删除；`launcher_bg`（图标在用）、全部 `md_*` 槽位、themes.xml 的
  `AppTheme` 别名与 values-night 双色板结构原样保留。
- MainActivity/SettingsActivity 的私有 `dp()` 是 UiKit.dp 的单行转发（阶段一/二的有意保留），
  不属于"重复实现"，未动。

## 四、验收（本阶段实际执行）

- `./gradlew.bat --console=plain -I ../mirror.init.gradle testDebugUnitTest assembleDebug` → **BUILD SUCCESSFUL**；
  JUnit XML 统计：total=167 failures=0 errors=0 skipped=1（跳过项 LiveJevSmokeTest 需 JEV_KEY、
  按 assumeTrue 设计跳过，存量行为）；app-debug.apk 已产出（7,071,037 字节）。
- 冻结面 git 复核：A 级 22 文件、a11y_config.xml、AndroidManifest、JevApp、构建文件零触碰；
  Analyzer 仅 `generateDrafts` 一处薄转发 + 一行 import、WatchService 仅 Toast 一块
  （均为任务明确允许的改动）。`ScoreOverlayTest` 钉死色值更新为 Design 夜档四值
  （0xFF3FD68F/0xFFF2C14E/0xFFEC7A70/0xFF8D99A6），阈值断言边界不变，测试总数仍 167。
- 真机/截图未做（无人值守无设备）：结果页日夜两态、浮条在微信上的实机观感属待真机复核项。
