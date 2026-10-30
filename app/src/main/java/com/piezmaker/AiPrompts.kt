package com.piezmaker

/**
 * AI 提示词。FEATURE_DOC 是「这个应用能干什么」的说明书：
 * 聊天时让 AI 读懂 FRPHI 的所有功能，生成/修谱/特效时也靠它约束输出。
 */
object AiPrompts {

    const val FEATURE_DOC = """你是 FRPHI 的内置 AI 助手。FRPHI 是一个安卓端 Phira / RPE 制谱器（个人开发，界面横屏）。
【应用功能】
1. 项目：主界面左侧灰条滑出项目抽屉；加号=联机（客户端），长按加号=资源包；日志=导出日志，长按日志=版权声明；项目卡片点=打开、长按=试听、左滑=删除、右滑=弹「谱面操作」（取消/复制/导出 .pez）；项目列表最下方是 AI 按钮（点=聊天，长按=接口设置）。
2. 编辑页（三阶段制谱）：第1次蓝键 Tap、第2次黄键 Drag（按住连续铺）、第3次红键 Flick（按住连续铺）、第4次蓝长条 Hold；另有撤销、复制/剪切/粘贴、框选多选、校准、隐藏上下遮罩、播放/暂停/变速。
3. 全量模式（「更多」里「音效」正下方的开关，默认关闭）：只在播放中（录制模式）生效，暂停时无效。开启后不再分第几次，统一手势：单击=蓝键 Tap；长按不动=长条 Hold（长度=按住时长）；长按后左右拖=沿途按格铺一串黄键 Drag（时间逐格顺延）；短滑（上/下/左/右）=红键 Flick。
4. 判定线：可多条；每条有屏幕纵向位置（0~100%）、全局位移/旋转/流速/透明度，以及关键帧（时间+姿态+流速+透明度），可做变速、旋转、位移、透明度动画；左侧关键帧圆点可选中/长按删除；拖动左侧区域=搓时间轴；拖动中心=移动，拖箭头=旋转。
5. 音符：时间单位毫秒，横向位置 -1（最左）~ +1（最右）；above=正反面（反转音符）；fake=假音符（只显示不判定）；类型 1 Tap / 2 Hold / 3 Flick / 4 Drag；Hold/Drag 有结束时间。
6. 显示与编辑开关：制作线显示（横线/竖线/全部/隐藏）、判定线高亮、快速切换、横向转动、反面音符、假音符显示/框住、重叠框、真实处理（显示/淡化/隐藏）、双押贴图提示、幽灵放置、平滑移动、音符自动移动、触摸兼容（关闭/框架/全部；开启后判定区放大，主界面灰条判定区也变大）、全量模式。
7. 播放：AUTOPLAY 打分打歌（进入自动隐藏上下遮罩、制作线切到隐藏，退出还原），可调延迟/变速；联机打歌（服务端=蓝框副本，客户端=绿框房间，局域网 TCP + 4 位密码）。
8. 导出：.pez 包（RPE chart.json + 背景图 + 音频）；难度留空就导出空字符串；BPM 偏移（毫秒，正数=谱面整体延后，与 Phiedit 的 bpm 偏移一致）。
9. AI：主界面显著是 AI 聊天抽屉，能聊天、写文案、查资料（联网搜索/抓网页）、执行 agent 操作（改设置、改项目信息、新建/复制/删除项目、只保留部分项目、开/关联机、导出、本地生成旋律歌曲、撤回）；编辑页「更多」里有自动写谱、自动修谱、自动缤纷，写谱前会检查设备延迟，完成弹窗可「撤回」。
10. 没配置 AI 接口时，聊天框改为本地知识库检索：把问题里的词和内置功能说明逐行比对，返回相关原文；配置接口后自动切换成 AI 回答。
【回答要求】用简体中文，简洁、直接、可操作；不确定就说不确定，不要编造不存在按钮。"""

    /** 输出语法（写谱/修谱/缤纷共用同一套 JSON Lines 协议） */
    private const val FORMAT_DOC = """【输出格式（必须严格遵守）】
只输出 JSON Lines：每行一个 JSON 对象，不要 markdown 代码块、不要数组、不要解释文字、不要注释。
可用对象：
1) 音符：{"note":类型,"t":起始毫秒,"x":横向位置-1~1,"end":结束毫秒(可省略),"line":判定线id(可省略),"fake":0,"above":1}
2) 新建判定线：{"nl":新id,"pos":纵向百分比0~100,"speed":流速,"name":"名字"}
3) 修改判定线：{"line":id,"pos":0~100,"speed":流速,"rot":角度,"x":-1~1,"y":-1~1}
4) 判定线关键帧：{"kf":判定线id,"t":毫秒,"x":-1~1,"y":-1~1,"r":角度,"sp":流速,"a":透明度0~1}
5) 修正音符：{"fix":音符下标,"t":新毫秒,"x":新位置,"end":新结束毫秒}
6) 删除音符：{"del":下标} 或 {"del":[下标,...]}
7) 标记：{"fake":[下标,...]} 假音符；{"rev":[下标,...]} 反转音符
音符时间必须落在 0~曲长 之间；x 必须在 -1~1；长条(Hold/Drag)要给 end 且 end>t。"""

    /** 聊天 + 代理操作：给主界面聊天用的系统提示词 */
    fun systemAgentChat() = FEATURE_DOC + "\n\n" + AGENT_DOC

    /** 没接 AI 时本地检索用的知识库全文（功能说明 + 命令说明） */
    fun knowledgeText() = FEATURE_DOC + "\n" + AGENT_DOC

    /** 聊天 + 代理操作（agent）：允许 AI 输出命令 JSON，由主界面执行 */
    private const val AGENT_DOC = """
【助手设定】你首先是 FRPHI 里的 AI 助手：可以闲聊、解释知识、写小说/文案、帮用户查资料，不必只聊制谱；保持助手的语气和边界就行。
【代理操作（agent）】用户要你执行操作时，在回复最后单独输出命令，一行一个 JSON（不要数组、不要解释）：
{"cmd":"setting","key":"compatMode","value":2}     // 设置：compatMode 0关/1框架/2全部；gridMode 0横线/1竖线/2全部/3隐藏；ghostShow 0显示/1淡化；realProcess 0显示/1淡化/2隐藏；以及 smoothMove / autoEdgeScroll / doubleHint / sound / overlapFrame / markReverse / lineHi / placeLines / quickSwitch / hRotate / ghostPlace / ghostFrame / fullMode（全量模式，仅在播放中生效）（value=true/false）
{"cmd":"meta","level":"12","bpm":150,"name":"曲名","speed":1.0,"linePos":60,"delay":0,"bpmOffset":0,"hLines":4,"vLines":8}   // 改当前项目信息（字段都可省略）
{"cmd":"room","action":"open"}                      // 打开联机面板；"close" 关闭当前房间
{"cmd":"project","action":"copy"}                   // "new" 新建项目；"delete" 删除当前项目（可加 "name"/"id" 指定某个）；"delete_all" 清空所有；"delete_except" 只保留 names 里列出的（加 "names":["A","B"]）；"export" 导出 .pez
{"cmd":"song","seconds":30,"bpm":120}               // 本地合成一段简单旋律并设为当前项目的音乐
{"cmd":"search","q":"要搜的内容"}                    // 联网搜索，结果会自动回给你
{"cmd":"fetch","url":"https://..."}                 // 抓网页正文，结果会自动回给你
{"cmd":"clear_chat"}                                // 清空聊天记录
{"cmd":"undo"}                                      // 撤回上一步 AI 操作
说明：这些操作在主界面执行；联网结果会以【工具结果】自动回灌给你，基于结果再回答，不要编造资料。
用户说"做错了/撤回/撤销"时输出 {"cmd":"undo"}。不要假装操作已经完成——执行结果由应用显示给用户。"""

    fun systemWrite() = FEATURE_DOC + "\n\n你现在要【自动写谱】。" + FORMAT_DOC +
        "\n重要：必须一行一个 JSON 对象逐行输出（不要 JSON 数组、不要 markdown 代码块、不要省略号、不要任何解释），" +
        "一直输出到数量够了再停；只允许 note（和少量 nl），不要 fix/del/rev/fake。"

    fun writeUser(
        bpm: Double, offsetMs: Int, durationMs: Long, level: String, difficulty: Double,
        lines: List<Line>, beats: List<AudioBeats.Beat>, targetNotes: Int
    ): String {
        val sb = StringBuilder()
        sb.append("【曲目】时长 ").append(durationMs).append("ms，BPM ").append(bpm)
        sb.append("，BPM偏移 ").append(offsetMs).append("ms，难度 ").append(level.ifBlank { "未知" })
        sb.append("（数值 ").append(String.format(java.util.Locale.US, "%.1f", difficulty)).append("/20）")
        sb.append("\n【现有判定线】")
        for (l in lines) sb.append("id=").append(l.id).append(" pos=").append(l.posPct)
            .append(" speed=").append(l.speed).append("；")
        sb.append("\n【节拍特征】每行：时间(ms)|强度0-9+主频段(L低/M中/H高)+维持时长(×100ms)")
        sb.append("\n").append(AiChart.beatsText(beats))
        sb.append("\n【怎么从特征选键型】")
        sb.append("\n- 强度高、维持 0~1（干脆的一下）、低频 L 为主 → 1 Tap（蓝键）")
        sb.append("\n- 中频 M 为主、维持 0~2 → 4 Drag（黄键，扫过去）")
        sb.append("\n- 高频 H 为主、强度高、节奏变化激烈 → 3 Flick（红键）")
        sb.append("\n- 维持 ≥ 3（同一个声音持续 300ms 以上）→ 2 Hold（长条，end = 时间 + 维持×100ms）")
        sb.append("\n【谱面手感（很重要）】音符不是随便乱放，要成套，但也别太密：")
        sb.append("\n- 纵连/连打：同一 x 连续 3~5 个；交互：左右交替（如 -0.6 / +0.6 交替，可双押）；")
        sb.append("\n- 楼梯：x 依次 -0.75→-0.5→-0.25→0→0.25→0.5→0.75（或反向），只占约 20%，连续不要超过 6 个；")
        sb.append("\n- 剩下用散点/小跳跃；双押只在重拍（强度 7~9）出现。")
        sb.append("\n- 图案大约每 6~8 个换一组，绝对不要整首只用两个位置左右交替，也不要整首都在爬楼梯。")
        sb.append("\n- 音符之间至少留约半拍（120BPM 约 250ms），不要堆在一起打不了。")
        sb.append("\n【抽象节奏型示例（只有相对位置 x，按时间顺序；请照这种结构成组安排，别逐音符乱放）】\n")
        sb.append(PatternLibrary.abstractExamples).append('\n')
        sb.append("\n【参考示例：来自用户自己的谱面（含镜像版）】\n")
        sb.append(StyleExamples.text()).append('\n')
        sb.append("\n【示例用法（必须遵守）】")
        sb.append("\n1) 不能照抄示例：可以整体左右移动、镜像、缩放位置，间隔改大或改小半拍；")
        sb.append("\n2) 要在示例结构上自己补音符填空档，但相邻音符间隔要和示例接近（约 0.5~2 拍，120BPM 下 250~1000ms），不要比示例更密；")
        sb.append("\n3) 长条/红键/黄键的搭配方式可以直接借用示例里的组合。")
        sb.append("\n【难度控制】难度数值越高：目标音符数越多（本次 ").append(targetNotes)
        sb.append(" 个左右）、多押和楼梯越多、长条越少；难度低则以 Tap 为主、长条多、少双押。")
        sb.append("\n所有音符统一 above=1（正面）、fake=0，绝对不要输出 above=0 或 fake=1。")
        sb.append("\n直接开始输出 JSON Lines，不要解释。")
        return sb.toString()
    }

    fun systemFix() = FEATURE_DOC + "\n\n你现在要【自动修谱】。" + FORMAT_DOC +
        "\n要求：只输出 fix / del 对象，不要新增音符、不要动判定线。"

    fun fixUser(bpm: Double, durationMs: Long, beats: List<AudioBeats.Beat>, digest: String): String {
        return "【曲目】时长 ${durationMs}ms，BPM $bpm\n" +
            "【节拍特征（时间|强度+主频段+维持）】\n" + AiChart.beatsText(beats) + "\n" +
            "【当前谱面】\n" + digest + "\n" +
            "【任务】找出时间明显不准的音符（离最近的重音/整拍超过约 40ms）和时间重叠重复的音符，" +
            "输出 {\"fix\":下标,\"t\":修正后的毫秒}（必要时再给 x/end），重复的用 {\"del\":下标}。" +
            "没有把握就少改。直接开始输出 JSON Lines。"
    }

    fun systemDeco() = FEATURE_DOC + "\n\n你现在要【自动缤纷】给谱面加特效。" + FORMAT_DOC +
        "\n要求：主要输出 kf（判定线关键帧：流速变化、旋转、位移、透明度）、line / nl（多判定线动画），" +
        "可以少量 fake / rev。不要改音符时间和位置。"

    fun decoUser(bpm: Double, durationMs: Long, digest: String, targetFx: Int): String {
        return "【曲目】时长 ${durationMs}ms，BPM $bpm\n" +
            "【当前谱面】\n" + digest + "\n" +
            "【任务】请输出大约 $targetFx 行特效：\n" +
            "1) 给主判定线打关键帧做流速/旋转/位移/透明度变化（kf，时间均匀铺满全曲，流速 0.5~2.5，透明度不要长时间低于 0.3）；\n" +
            "2) 新建 1~2 条判定线（nl，id 不能和现有 id 重复，pos 40~80），并在它们上打关键帧；\n" +
            "3) 少量音符改 fake（比如过门段落），或 rev 反转。\n" +
            "直接开始输出 JSON Lines。"
    }
}
