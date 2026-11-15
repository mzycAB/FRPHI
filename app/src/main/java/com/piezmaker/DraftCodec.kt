package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject

/**
 * 谱面草稿的编解码（纯逻辑，不依赖 Android，可单元测试）。
 *
 * 为什么不用 org.json：一个 1 万音符的谱面，org.json 要建 1 万个 JSONObject + 拼接字符串，
 * 解析和生成各要上百毫秒，而这两件事都在 UI 线程（导入、进编辑页、退出保存）。
 * 这里用逐行文本，生成靠 StringBuilder，解析靠手写扫描，快一个数量级。
 *
 * 格式 v3（行首标记 + 空格分隔）：
 * ```
 * #FRPHI-DRAFT 3
 * L 0 60 0.12 -0.4 0 1.6      # 判定线：id posPct moveX moveY rotate speed [alpha]
 * K 0 1000 0.12 -0.4 0 1 1 1.6 1  # 关键帧：lineId 时间 moveX moveY rotate speed alpha scaleX scaleY
 * N 0 1 1000 1000 0.5 1 1 0   # 音符：lineId type startMs endMs posX pass above fake [spd yOff a size visT]
 * LF 0 2 1                    # 父线：lineId fatherId rotWithFather
 * LC 0 1 120 0.5 2            # ctrl 对象：lineId kind(1=y 2=pos 3=size 4=alpha) x value easing
 * LI 0 1000 2000 30 0 1       # incline：lineId t0 t1 start end easing（毫秒 + 度）
 * LS 0 1 1.6                  # 普通判定线缩放：lineId scaleX scaleY（scaleY 决定线粗细）
 * NA {"Name":".."}            # 噪域：整块原始 JSON，按行一条，导出时原样写回
 * NL <layerId>                # 空噪域图层声明（层里还没有块）
 * ```
 * 兼容：
 *  - v2（只有一条判定线）：`mx / my / rot / spd` 四个全局值 + `K t x y r` + `N type s e x pass`
 *  - 更早的 RPEJSON 风格草稿（以 `{` 开头）会自动识别并走 JSON 分支
 */
object DraftCodec {

    const val HEADER = "#FRPHI-DRAFT 3"

    /** "没有存判定线位置"的哨兵（旧 v2/JSON 草稿）：不能再用 -1，因为屏幕外的线本来就可能是负数 */
    const val POS_UNSET = Int.MIN_VALUE

    /** 解出来的草稿内容（判定线可能有多条） */
    class Draft(
        val lines: List<Line>,
        val notes: List<RecordedNote>,
        val clip: List<RecordView.ClipEntry> = emptyList(),
        /** 噪域（原始 JSON 原样保存在 [NoiseArea.raw]，导出时写回） */
        val noise: List<NoiseArea> = emptyList(),
        /** 空图层声明（没有任何判定线的图层；左滑新建按钮加的） */
        val declaredLayers: Set<Int> = emptySet(),
        /** 空噪域图层声明（层里还没有块时也要显示；NL 行） */
        val declaredNoiseLayers: Set<String> = emptySet(),
        /** 视频背景（Phira extra.json 的 videos） */
        val videos: List<VideoBg> = emptyList()
    ) {
        /** 兼容旧调用：主判定线的关键帧/姿态/流速 */
        val keyframes: List<LineKeyframe> get() = lines.firstOrNull()?.keyframes ?: emptyList()
        val moveX: Float get() = lines.firstOrNull()?.moveX ?: 0f
        val moveY: Float get() = lines.firstOrNull()?.moveY ?: 0f
        val rotate: Float get() = lines.firstOrNull()?.rotate ?: 0f
        val speed: Float get() = lines.firstOrNull()?.speed ?: 1f
    }

    fun encode(
        lines: List<Line>,
        notes: List<RecordedNote>,
        clip: List<RecordView.ClipEntry> = emptyList(),
        noise: List<NoiseArea> = emptyList(),
        declaredLayers: Set<Int> = emptySet(),
        declaredNoiseLayers: Set<String> = emptySet(),
        videos: List<VideoBg> = emptyList()
    ): String {
        val sb = StringBuilder(160 + notes.size * 34 + lines.sumOf { it.keyframes.size } * 34)
        sb.append(HEADER).append('\n')
        // LL <图层值>：空图层声明（没有任何判定线的图层，左滑新建按钮加的）
        for (z in declaredLayers) sb.append("LL ").append(z).append('\n')
        // NL <layerId>：空噪域图层声明（层里没有块时也要显示；层 id 可以带空格，取整行剩余）
        for (key in declaredNoiseLayers) {
            if (key.isNotEmpty()) sb.append("NL ").append(key).append('\n')
        }
        // VB 开始ms 缩放方式 透明度 昏暗度 文件名 —— 视频背景（Phira extra.json videos）
        for (v in videos) {
            sb.append("VB ").append(v.startMs).append(' ').append(v.scaleMode).append(' ')
                .append(f(v.alpha)).append(' ').append(f(v.dim)).append(' ')
                .append(if (v.fileName.isEmpty()) "-" else v.fileName.replace(' ', '_')).append('\n')
        }
        for (l in lines) {
            sb.append("L ").append(l.id).append(' ').append(l.posPct).append(' ')
                .append(f(l.moveX)).append(' ').append(f(l.moveY)).append(' ')
                .append(f(l.rotate)).append(' ').append(f(clampStoreSpeed(l.speed))).append(' ')
                .append(f(l.alpha)).append('\n')   // 负 alpha = 隐藏线+音符，原样保存
            for (k in l.keyframes) {
                sb.append("K ").append(l.id).append(' ').append(k.timeMs).append(' ')
                appF(sb, k.moveX); sb.append(' ')
                appF(sb, k.moveY); sb.append(' ')
                appF(sb, k.rotate); sb.append(' ')
                appF(sb, clampStoreSpeed(k.speed)); sb.append(' ')
                appF(sb, k.alpha); sb.append(' ')
                appF(sb, k.scaleX); sb.append(' ')
                appF(sb, k.scaleY); sb.append('\n')
            }
            // 判定线名字（RPE 的 Name）：单独一行，名字里可以有空格，取整行剩余
            if (l.name.isNotEmpty()) {
                sb.append("LN ").append(l.id).append(' ').append(l.name).append('\n')
            }
            // 图片线（LP）/ 文字线（LT）：类型不进 Name，单独记，和 RPE 的 Texture/textEvents 对应
            if (l.lineText.isNotEmpty()) {
                sb.append("LT ").append(l.id).append(' ').append(f(l.scaleX)).append(' ')
                    .append(f(l.scaleY)).append(' ').append(l.lineText).append('\n')
                // LFX lineId 入场特效 出场特效 入场ms 出场ms —— 文字动画设置（单独一行，旧草稿读不到就用默认值）
                sb.append("LFX ").append(l.id).append(' ').append(l.textEnterFx).append(' ')
                    .append(l.textExitFx).append(' ').append(l.textEnterMs).append(' ')
                    .append(l.textExitMs).append('\n')
            } else if (l.texture.isNotEmpty()) {
                sb.append("LP ").append(l.id).append(' ').append(f(l.scaleX)).append(' ')
                    .append(f(l.scaleY)).append(' ')
                    .append(if (l.texture.isEmpty()) "-" else l.texture.replace(' ', '_')).append('\n')
            } else if (Math.abs(l.scaleX - 1f) > 1e-4f || Math.abs(l.scaleY - 1f) > 1e-4f) {
                // LS lineId scaleX scaleY —— 普通判定线的缩放（判定线粗细 = scaleY）。
                // 以前普通线的 scale 根本没存，改完粗细退出再进来就被重置回 1。
                sb.append("LS ").append(l.id).append(' ').append(f(l.scaleX)).append(' ')
                    .append(f(l.scaleY)).append('\n')
            }
            // 父线（RPE father）：位置始终跟随父线，旋转只在 rotWithFather 时叠加；
            // zOrder（图层序）也搭 LF 行（没有父线但 zOrder≠0 的线也要写）
            if (l.fatherId != -1 || l.rotWithFather || l.zOrder != 0) {
                sb.append("LF ").append(l.id).append(' ').append(l.fatherId).append(' ')
                    .append(if (l.rotWithFather) 1 else 0).append(' ')
                    .append(l.zOrder).append('\n')
            }
            // ctrl 对象事件（LC lineId kind x v easing；kind 1=y 2=pos 3=size 4=alpha）
            fun writeCtrl(kind: Int, evts: List<CtrlEvent>) {
                for (e in evts) {
                    sb.append("LC ").append(l.id).append(' ').append(kind).append(' ')
                        .append(f(e.x)).append(' ').append(f(e.value)).append(' ').append(e.easing).append('\n')
                }
            }
            writeCtrl(1, l.ctrlYEvents)
            writeCtrl(2, l.ctrlPosEvents)
            writeCtrl(3, l.ctrlSizeEvents)
            writeCtrl(4, l.ctrlAlphaEvents)
            // 原始文字事件（LX lineId t0 t1 easing font linkgroup | 起字 | 终字）：
            // 图片线上挂的 textEvents 是"副标题"，编辑器不改它，但导出必须原样写回，
            // 否则导入一张带文字事件的图片线、再导出，字就没了。文本里可能有空格和 |，
            // 所以用 '|' 分隔、只切 5 段。
            for (e in l.textEvents) {
                sb.append("LX ").append(l.id).append(' ').append(e.startMs).append(' ').append(e.endMs).append(' ')
                    .append(e.easingType).append(' ').append(e.font.replace(' ', '_')).append(' ')
                    .append(e.linkGroup).append(" |").append(e.start.replace('|', '/')).append('|')
                    .append(e.end.replace('|', '/')).append('\n')
            }
            // 颜色事件（CE lineId t0 t1 rgb0 rgb1 easing）—— 判定线颜色动画
            for (e in l.colorEvents) {
                sb.append("CE ").append(l.id).append(' ').append(e.startMs).append(' ').append(e.endMs).append(' ')
                    .append(e.start).append(' ').append(e.end).append(' ').append(e.easingType).append('\n')
            }
            // isCover（LCV lineId 0|1）：1 = 判定线画在音符上面
            if (l.cover) sb.append("LCV ").append(l.id).append(" 1\n")
            // incline 倾斜事件（LI lineId t0 t1 v0 v1 easing，毫秒 + 度）
            for (e in l.inclineEvents) {
                sb.append("LI ").append(l.id).append(' ').append(e.startMs).append(' ').append(e.endMs).append(' ')
                    .append(f(e.start)).append(' ').append(f(e.end)).append(' ').append(e.easing).append('\n')
            }
        }
        for (n in notes) {
            writeNote(sb, n)
        }
        // 剪贴板（联机同步用）：每条一个 CB 头 + 若干 N。
        // 以前这里**根本没写** clip（decode 那边却早就会读 CB），所以联机时剪贴板永远同步不过去。
        for (e in clip) {
            sb.append("CB ").append(if (e.cut) 1 else 0).append('\n')
            for (n in e.notes) {
                writeNote(sb, n)
            }
        }
        // 噪域：一条一行 `NA <原始JSON>`。
        // 存原始 JSON 而不是拆成字段，是为了「读到什么就写回什么」——编辑后重建的 JSON
        // 也走这里（带 `_frphi`），FRPHI 不认识的字段同样原样保留。
        sb.append(noiseBlock(noise))
        return sb.toString()
    }

    // ---------------------------------------------------------------- 噪域段缓存
    //
    // 官谱一张能有 14355 块、原始 JSON 十几 MB，每保存一次就重新序列化要 600ms+，
    // 而保存是**在 UI 线程同步写盘**的 ⇒ 点保存/退出就卡半秒。这里缓存拼好的那段文本。
    // ★ 噪域可编辑：不能只看「列表身份 + 首末元素」，编辑中间一块时首末都没变。
    //   必须逐项比较引用（14k 次 identity 比较，远比重新序列化便宜）。
    private var cachedNoiseBlock: String = ""
    private var cachedNoiseList: List<NoiseArea>? = null
    private var cachedNoiseRefs: Array<NoiseArea>? = null

    /** 拼 `NA ...` 段（带缓存）。噪域列表/任一元素变了会自动重建。 */
    private fun noiseBlock(noise: List<NoiseArea>): String {
        if (noise.isEmpty()) return ""
        val n = noise.size
        val refs = cachedNoiseRefs
        if (cachedNoiseList === noise && refs != null && refs.size == n) {
            var same = true
            for (i in 0 until n) {
                if (refs[i] !== noise[i]) { same = false; break }
            }
            if (same) return cachedNoiseBlock
        }
        // 预估别开太大：官谱一块噪域 600~900 字符，但有的块只有几十字符，
        // 按 900 预分配 14355 块会一次吃掉 13MB 堆（低端机直接 OOM）
        val sb = StringBuilder(minOf(n * 420, 4 shl 20))
        for (a in noise) {
            // ★ 只能写 raw 的那版会把「AI 新建 / 手搓 / 改过的」噪域整块丢掉：
            //   AI 的 na / area 命令造出来的 NoiseArea 没有 raw（raw=null, dirty=true），
            //   `raw ?: continue` 直接跳过 ⇒ 草稿里没有它 ⇒ 编辑器里什么都没有，
            //   表现就是「AI 说噪域做好了，但我什么都没看到」（用户点名）。
            //   现在：raw 且没被改过 → 原样写（保住 FRPHI 不认的字段）；
            //         否则按当前字段重建（build() 里带 `_frphi`，毫秒时间/矩形关键帧不会丢）。
            val raw = a.raw?.takeIf { !a.dirty } ?: NoiseArea.toJsonObject(a)
            sb.append("NA ").append(raw.toString().replace('\n', ' ').replace('\r', ' ')).append('\n')
        }
        val s = sb.toString()
        cachedNoiseBlock = s
        cachedNoiseList = noise
        cachedNoiseRefs = Array(n) { noise[it] }
        return s
    }

    /**
     * N 行（v3 基础 8 字段）+ 兼容扩展：spd yOff a size visT + （第二批）judgeArea tint tintHit hitsound。
     * 扩展字段只在有非默认值时追加 —— 老版本解码器会忽略多余字段，双向兼容。
     */
    private fun writeNote(sb: StringBuilder, n: RecordedNote) {
        sb.append("N ").append(n.lineId).append(' ').append(n.type).append(' ')
            .append(n.startMs).append(' ').append(n.endMs).append(' ')
            .append(f(n.posX)).append(' ').append(n.pass).append(' ')
            .append(if (n.above) 1 else 0).append(' ').append(if (n.fake) 1 else 0)
        if (n.speed != 1f || n.yOffset != 0f || n.alpha != 1f || n.size != 1f || n.visibleTime != 999999f ||
            n.hasExtExtras
        ) {
            sb.append(' ').append(f(n.speed)).append(' ').append(f(n.yOffset)).append(' ')
                .append(f(n.alpha)).append(' ').append(f(n.size)).append(' ').append(f(n.visibleTime))
        }
        if (n.hasExtExtras) {
            sb.append(' ').append(f(n.judgeArea)).append(' ').append(n.tint).append(' ')
                .append(n.tintHit).append(' ')
                .append(if (n.hitsound.isEmpty()) "-" else n.hitsound.replace(' ', '_'))
        }
        sb.append('\n')
    }

    /** 兼容旧调用：单判定线 */
    fun encode(
        notes: List<RecordedNote>,
        keyframes: List<LineKeyframe>,
        moveX: Float,
        moveY: Float,
        rotate: Float,
        speed: Float
    ): String {
        val l = Line(0)
        l.moveX = moveX; l.moveY = moveY; l.rotate = rotate; l.speed = speed
        l.keyframes.addAll(keyframes)
        l.invalidate()
        return encode(listOf(l), notes)
    }

    /** 解析草稿；无法识别返回 null（调用方保持原样即可） */
    fun decode(text: String): Draft? {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (t.isEmpty()) return null
        return if (t.startsWith("{")) decodeJson(t) else decodeText(t)
    }

    // ------------------------------------------------------------------ 文本（v2 / v3）

    private fun decodeText(text: String): Draft? {
        var mx = 0f
        var my = 0f
        var rot = 0f
        var spd = 1f
        val notes = ArrayList<RecordedNote>()
        val lines = LinkedHashMap<Int, Line>()
        var sawHeader = false
        var sawLineHeader = false
        var clipBuf: MutableList<RecordedNote>? = null
        var clipCut = false
        val clipEntries = ArrayList<RecordView.ClipEntry>()
        // 噪域：整块原始 JSON，解码时直接塞回 NoiseArea.raw，导出原样写回
        val noiseRaw = ArrayList<JSONObject>()
        // 空图层声明
        val declared = LinkedHashSet<Int>()
        // 空噪域图层声明（NL 行）
        val declaredNoise = LinkedHashSet<String>()
        // 视频背景（VB 行）
        val videos = ArrayList<VideoBg>()
        // 旧草稿的 K 行没有 scaleX/scaleY：等全部行读完（LS/LP/LT 可能在 K 之后）再回填整条线的缩放
        val kfNeedGlobalScale = ArrayList<Pair<Line, Int>>()

        fun lineOf(id: Int): Line = lines.getOrPut(id) { Line(id) }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line[0] == '#') { sawHeader = true; continue }
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val key = line.substring(0, sp)
            val rest = line.substring(sp + 1)
            when (key) {
                // NA <原始JSON>：噪域。解析失败就丢掉这一块，绝不影响草稿其余部分
                "NA" -> runCatching { JSONObject(rest) }.getOrNull()?.let { noiseRaw.add(it) }
                // NL <layerId>：空噪域图层声明（层里还没有块）
                "NL" -> rest.trim().takeIf { it.isNotEmpty() }?.let { declaredNoise.add(it) }
                // VB 开始ms 缩放方式 透明度 昏暗度 文件名 —— 视频背景
                "VB" -> {
                    val p = rest.split(' ', limit = 5)
                    if (p.size >= 5) {
                        val name = p[4].takeIf { it.isNotEmpty() && it != "-" }
                        if (name != null) {
                            videos.add(
                                VideoBg(
                                    fileName = name,
                                    startMs = p[0].toLongOrNull() ?: 0L,
                                    scaleMode = (p[1].toIntOrNull() ?: 0).coerceIn(0, 2),
                                    alpha = (p[2].toFloatOrNull() ?: 1f).coerceIn(0f, 1f),
                                    dim = (p[3].toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
                                )
                            )
                        }
                    }
                }
                "mx" -> mx = rest.toFloatOrNull() ?: mx
                "my" -> my = rest.toFloatOrNull() ?: my
                "rot" -> rot = rest.toFloatOrNull() ?: rot
                "spd" -> spd = rest.toFloatOrNull() ?: spd
                // v3：L id posPct mx my rot spd
                // LL <图层值>：空图层声明
                "LL" -> rest.trim().toIntOrNull()?.let { declared.add(it) }
                "L" -> {
                    val p = rest.split(' ')
                    if (p.size >= 6) {
                        val id = p[0].toIntOrNull() ?: 0
                        val pct = p[1].toIntOrNull() ?: 60
                        val l = lineOf(id)
                        // 允许屏幕外（Phira 的 moveY 可以把判定线放到屏幕外）
                        l.posPct = pct.coerceIn(-2000, 2000)
                        l.moveX = p[2].toFloatOrNull() ?: 0f
                        l.moveY = p[3].toFloatOrNull() ?: 0f
                        l.rotate = p[4].toFloatOrNull() ?: 0f
                        l.speed = clampStoreSpeed(p[5].toFloatOrNull() ?: 1f)
                        l.alpha = p.getOrNull(6)?.toFloatOrNull() ?: 1f
                        sawLineHeader = true
                    }
                }
                // LN lineId name…（名字可以带空格）
                "LN" -> {
                    val p = rest.split(' ', limit = 2)
                    val lid = p.getOrNull(0)?.toIntOrNull()
                    val nm = p.getOrNull(1) ?: ""
                    if (lid != null) lineOf(lid).name = nm else lines.values.lastOrNull()?.name = nm
                }
                // v3：K lineId t mx my rot spd ；v2：K t mx my rot
                // ★ 热路径：big song 级谱面这一分支要跑 44 万次。旧实现 rest.split(' ')
                //   产生 ~800 万个临时 String（仅 decode 就 890ms）；这里就地扫描数字，
                //   不是简单十进制形态时退回 substring + 标准解析，兼容性不变。
                "K" -> {
                    var nTok = 1
                    var q = sp + 1
                    while (q < line.length) { if (line[q] == ' ') nTok++; q++ }
                    val sc = Toks(line, sp + 1)
                    if (nTok >= 6) {
                        val id = sc.nextInt() ?: 0
                        val t = sc.nextLong()
                        val x = sc.nextFloat()
                        val y = sc.nextFloat()
                        val r = sc.nextFloat()
                        val s = sc.nextFloat()
                        if (t != null && x != null && y != null && r != null) {
                            // 负 alpha = Phira 的"隐藏线和它的音符"（PE alpha extension），
                            // 不能夹成 0~1：夹了之后线隐了、音符却还在（长条特效残留的元凶）
                            val al = sc.nextFloat()
                            val sx = sc.nextFloat()
                            val sy = sc.nextFloat()
                            val ln = lineOf(id)
                            ln.keyframes.add(
                                LineKeyframe(t, x, y, r, clampStoreSpeed(s ?: 1f), al ?: 1f, sx ?: 1f, sy ?: 1f)
                            )
                            if (sx == null || sy == null) kfNeedGlobalScale.add(ln to ln.keyframes.size - 1)
                        }
                    } else if (nTok >= 4) {
                        val t = sc.nextLong()
                        val x = sc.nextFloat()
                        val y = sc.nextFloat()
                        val r = sc.nextFloat()
                        if (t != null && x != null && y != null && r != null) {
                            lineOf(0).keyframes.add(LineKeyframe(t, x, y, r))
                        }
                    }
                }
                // LF lineId fatherId rotWith [zOrder] —— 父线（RPE father）+ 图层序（可缺省，旧草稿没有）
                "LF" -> {
                    val p = rest.split(' ')
                    val id = p.getOrNull(0)?.toIntOrNull() ?: continue
                    lineOf(id).fatherId = p.getOrNull(1)?.toIntOrNull() ?: -1
                    lineOf(id).rotWithFather = (p.getOrNull(2)?.toIntOrNull() ?: 0) != 0
                    lineOf(id).zOrder = p.getOrNull(3)?.toIntOrNull() ?: 0
                }
                // LP lineId scaleX scaleY 贴图文件名 —— 图片线（RPE 的 Texture ≠ line.png）
                "LP" -> {
                    val p = rest.split(' ')
                    val id = p.getOrNull(0)?.toIntOrNull() ?: continue
                    val l = lineOf(id)
                    l.scaleX = p.getOrNull(1)?.toFloatOrNull() ?: 1f
                    l.scaleY = p.getOrNull(2)?.toFloatOrNull() ?: 1f
                    l.texture = p.getOrNull(3)?.takeIf { it.isNotEmpty() && it != "-" } ?: ""
                }
                // LS lineId scaleX scaleY —— 普通判定线的缩放（判定线粗细 = scaleY）
                "LS" -> {
                    val p = rest.split(' ')
                    val id = p.getOrNull(0)?.toIntOrNull() ?: continue
                    val l = lineOf(id)
                    l.scaleX = p.getOrNull(1)?.toFloatOrNull() ?: 1f
                    l.scaleY = p.getOrNull(2)?.toFloatOrNull() ?: 1f
                }
                // LT lineId scaleX scaleY 文字… —— 文字线（RPE 的 extended.textEvents，可含空格）
                "LT" -> {
                    val p = rest.split(' ', limit = 4)
                    val id = p.getOrNull(0)?.toIntOrNull() ?: continue
                    val l = lineOf(id)
                    l.scaleX = p.getOrNull(1)?.toFloatOrNull() ?: 1f
                    l.scaleY = p.getOrNull(2)?.toFloatOrNull() ?: 1f
                    l.lineText = p.getOrNull(3) ?: ""
                }
                // LFX lineId 入场特效 出场特效 入场ms 出场ms —— 文字动画
                "LFX" -> {
                    val p = rest.split(' ')
                    val id = p.getOrNull(0)?.toIntOrNull() ?: continue
                    val l = lineOf(id)
                    l.textEnterFx = TextFx.clampFx(p.getOrNull(1)?.toIntOrNull() ?: 0)
                    l.textExitFx = TextFx.clampFx(p.getOrNull(2)?.toIntOrNull() ?: 0)
                    l.textEnterMs = (p.getOrNull(3)?.toLongOrNull() ?: 300L).coerceAtLeast(0L)
                    l.textExitMs = (p.getOrNull(4)?.toLongOrNull() ?: 300L).coerceAtLeast(0L)
                }
                // LX lineId t0 t1 easing font linkgroup |起字|终字 —— 原始文字事件
                "LX" -> {
                    val bar = rest.indexOf('|')
                    if (bar < 0) continue
                    val head = rest.substring(0, bar).trim().split(' ')
                    val body = rest.substring(bar + 1)
                    val bar2 = body.indexOf('|')
                    val id = head.getOrNull(0)?.toIntOrNull() ?: continue
                    val t0 = head.getOrNull(1)?.toLongOrNull() ?: continue
                    val t1 = head.getOrNull(2)?.toLongOrNull() ?: t0
                    lineOf(id).textEvents.add(
                        TextEvent(
                            t0, t1,
                            if (bar2 >= 0) body.substring(0, bar2) else body,
                            if (bar2 >= 0) body.substring(bar2 + 1) else body,
                            head.getOrNull(3)?.toIntOrNull() ?: 0,
                            head.getOrNull(4)?.takeIf { it != "-" }?.replace('_', ' ') ?: "",
                            head.getOrNull(5)?.toIntOrNull() ?: 0
                        )
                    )
                }
                // CE lineId t0 t1 rgb0 rgb1 easing —— 颜色事件
                "CE" -> {
                    val p = rest.split(' ')
                    val id = p.getOrNull(0)?.toIntOrNull() ?: continue
                    val t0 = p.getOrNull(1)?.toLongOrNull() ?: continue
                    val t1 = p.getOrNull(2)?.toLongOrNull() ?: t0
                    val c0 = p.getOrNull(3)?.toIntOrNull() ?: continue
                    val c1 = p.getOrNull(4)?.toIntOrNull() ?: c0
                    lineOf(id).colorEvents.add(
                        ColorEvent(t0, t1, c0, c1, p.getOrNull(5)?.toIntOrNull() ?: 1)
                    )
                }
                // LCV lineId 0|1 —— isCover（1 = 线画在音符上面）
                "LCV" -> {
                    val p = rest.split(' ')
                    val id = p.getOrNull(0)?.toIntOrNull() ?: continue
                    lineOf(id).cover = (p.getOrNull(1)?.toIntOrNull() ?: 0) == 1
                }
                // LC lineId kind x v easing —— ctrl 对象（kind 1=y 2=pos 3=size 4=alpha）
                "LC" -> {
                    val p = rest.split(' ')
                    val id = p.getOrNull(0)?.toIntOrNull()
                    val kind = p.getOrNull(1)?.toIntOrNull()
                    val x = p.getOrNull(2)?.toFloatOrNull()
                    val v = p.getOrNull(3)?.toFloatOrNull()
                    val e = p.getOrNull(4)?.toIntOrNull() ?: 1
                    if (id != null && kind != null && x != null && v != null) {
                        val ev = CtrlEvent(x, v, e)
                        when (kind) {
                            1 -> lineOf(id).ctrlYEvents.add(ev)
                            2 -> lineOf(id).ctrlPosEvents.add(ev)
                            3 -> lineOf(id).ctrlSizeEvents.add(ev)
                            4 -> lineOf(id).ctrlAlphaEvents.add(ev)
                        }
                    }
                }
                // LI lineId t0 t1 v0 v1 easing —— incline 倾斜事件（毫秒 + 度）
                "LI" -> {
                    val p = rest.split(' ')
                    val id = p.getOrNull(0)?.toIntOrNull()
                    val t0 = p.getOrNull(1)?.toLongOrNull()
                    val t1 = p.getOrNull(2)?.toLongOrNull()
                    val v0 = p.getOrNull(3)?.toFloatOrNull()
                    val v1 = p.getOrNull(4)?.toFloatOrNull()
                    val e = p.getOrNull(5)?.toIntOrNull() ?: 1
                    if (id != null && t0 != null && t1 != null && v0 != null && v1 != null) {
                        lineOf(id).inclineEvents.add(InclineEvent(t0, t1, v0, v1, e))
                    }
                }
                // v3：N lineId type s e x pass above fake [spd yOff a size visT] ；v2：N type s e x pass
                "CB" -> {
                    clipBuf?.let { if (it.isNotEmpty()) clipEntries.add(RecordView.ClipEntry(it, clipCut)) }
                    clipCut = (rest.split(' ').firstOrNull()?.toIntOrNull() ?: 0) != 0
                    clipBuf = ArrayList()
                }
                "N" -> {
                    val p = rest.split(' ')
                    if (p.size >= 8) {
                        val id = p[0].toIntOrNull() ?: 0
                        val ty = p[1].toIntOrNull()
                        val st = p[2].toLongOrNull()
                        val en = p[3].toLongOrNull()
                        val x = p[4].toFloatOrNull()
                        if (ty != null && st != null && en != null && x != null) {
                            // v3 扩展（13 个字段起）：音符级 speed/yOffset/alpha/size/visibleTime
                            val spdN = p.getOrNull(8)?.toFloatOrNull() ?: 1f
                            val yOffN = p.getOrNull(9)?.toFloatOrNull() ?: 0f
                            val aN = p.getOrNull(10)?.toFloatOrNull() ?: 1f
                            val szN = p.getOrNull(11)?.toFloatOrNull() ?: 1f
                            val vtN = p.getOrNull(12)?.toFloatOrNull() ?: 999999f
                            // 第二批扩展（17 个字段起）：judgeArea / tint / tintHit / hitsound
                            val jaN = p.getOrNull(13)?.toFloatOrNull() ?: 1f
                            val tintN = p.getOrNull(14)?.toIntOrNull() ?: -1
                            val tintHitN = p.getOrNull(15)?.toIntOrNull() ?: -1
                            val hsRaw = p.getOrNull(16)
                            val hsN = if (hsRaw == null || hsRaw == "-") "" else hsRaw.replace('_', ' ')
                            val clipTarget = clipBuf
                            if (clipTarget != null) {
                                clipTarget.add(
                                    RecordedNote(
                                        type = ty, startMs = st, endMs = en, posX = x,
                                        pass = p[5].toIntOrNull() ?: 1, lineId = id,
                                        above = (p[6].toIntOrNull() ?: 1) != 0,
                                        fake = (p[7].toIntOrNull() ?: 0) != 0,
                                        speed = spdN, yOffset = yOffN, alpha = aN,
                                        size = szN, visibleTime = vtN,
                                        judgeArea = jaN, tint = tintN, tintHit = tintHitN, hitsound = hsN
                                    )
                                )
                                continue
                            }
                            notes.add(
                                RecordedNote(
                                    type = ty, startMs = st, endMs = en, posX = x,
                                    pass = p[5].toIntOrNull() ?: 1, lineId = id,
                                    above = (p[6].toIntOrNull() ?: 1) != 0,
                                    fake = (p[7].toIntOrNull() ?: 0) != 0,
                                    speed = spdN, yOffset = yOffN, alpha = aN,
                                    size = szN, visibleTime = vtN,
                                    judgeArea = jaN, tint = tintN, tintHit = tintHitN, hitsound = hsN
                                )
                            )
                        }
                    } else if (p.size >= 5) {
                        val ty = p[0].toIntOrNull()
                        val st = p[1].toLongOrNull()
                        val en = p[2].toLongOrNull()
                        val x = p[3].toFloatOrNull()
                        if (ty != null && st != null && en != null && x != null) {
                            notes.add(RecordedNote(ty, st, en, x, p[4].toIntOrNull() ?: 1))
                        }
                    }
                }
            }
        }
        clipBuf?.let { if (it.isNotEmpty()) clipEntries.add(RecordView.ClipEntry(it, clipCut)) }
        clipBuf = null
        if (!sawHeader && notes.isEmpty() && lines.isEmpty()) return null

        // 没有 L 行 = v2 草稿（只有 mx/my/rot/spd 全局值 + 单线关键帧）→ 折成唯一一条判定线
        if (!sawLineHeader) {
            if (lines.isEmpty()) lines[0] = Line(0)
            val l = lines[0]!!
            l.posPct = POS_UNSET                // v2 草稿没存位置 → 沿用设置页那条线的位置
            l.moveX = mx; l.moveY = my; l.rotate = rot; l.speed = clampStoreSpeed(spd)
        }
        val list = lines.values.toMutableList()
        list.sortBy { it.id }
        // 旧草稿回填：没有逐帧缩放的帧沿用整条线的缩放（否则升级后缩放会被重置成 1）
        for ((ln, idx) in kfNeedGlobalScale) {
            val k = ln.keyframes.getOrNull(idx) ?: continue
            ln.keyframes[idx] = k.copy(scaleX = ln.scaleX, scaleY = ln.scaleY)
        }
        list.forEach { it.invalidate() }

        // 音符的 lineId 可能指向不存在的线（手改文件）→ 归到第一条
        val ids = list.map { it.id }.toHashSet()
        val firstId = list.first().id
        val fixed = notes.map { if (it.lineId in ids) it else it.copy(lineId = firstId) }
        // 噪域重建：时间字段在草稿里没有 BPM 表，直接用「1 拍 = 500ms」的反向近似换算。
        // 这只影响编辑器里的预览框位置；导出永远走 raw（原始 JSON），不会错位。
        val noise = if (noiseRaw.isEmpty()) emptyList() else
            NoiseArea.parseAllAuto(JSONArray(noiseRaw))
        return Draft(list, fixed, clipEntries, noise, declared, declaredNoise, videos)
    }

    // ------------------------------------------------------------------ 旧 JSON

    private fun decodeJson(text: String): Draft? {
        return try {
            val doc = org.json.JSONObject(text)
            val notes = ArrayList<RecordedNote>()
            doc.optJSONArray("notes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    notes.add(
                        RecordedNote(
                            o.optInt("t", 1),
                            o.optLong("s", 0L),
                            o.optLong("e", 0L),
                            o.optDouble("x", 0.0).toFloat(),
                            o.optInt("p", 1),
                            lineId = o.optInt("l", 0),
                            above = o.optInt("ab", 1) != 0,
                            fake = o.optInt("fk", 0) != 0
                        )
                    )
                }
            }
            val l = Line(0)
            l.moveX = doc.optDouble("mx", 0.0).toFloat()
            l.moveY = doc.optDouble("my", 0.0).toFloat()
            l.rotate = doc.optDouble("rot", 0.0).toFloat()
            l.speed = clampStoreSpeed(doc.optDouble("spd", 1.0).toFloat())
            l.posPct = doc.optInt("pct", POS_UNSET).let { if (it == -1) POS_UNSET else it }
            doc.optJSONArray("kf")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    l.keyframes.add(
                        LineKeyframe(
                            o.optLong("t", 0L),
                            o.optDouble("x", 0.0).toFloat(),
                            o.optDouble("y", 0.0).toFloat(),
                            o.optDouble("r", 0.0).toFloat(),
                            clampStoreSpeed(o.optDouble("s", 1.0).toFloat())
                        )
                    )
                }
            }
            l.invalidate()
            Draft(listOf(l), notes)
        } catch (_: Exception) {
            null
        }
    }

    /** 浮点转文本：最多 4 位小数，去掉多余的 0 */
    private fun f(v: Float): String {
        val sb = StringBuilder(16)
        appF(sb, v)
        return sb.toString()
    }

    /**
     * [f] 的热路径版本：直接把数字 append 进 sb，不产生中间 String。
     *
     * big song 级谱面一次 encode 要写 ~350 万个浮点（44 万关键帧 × 8 字段），
     * 旧实现每个值都走 Float.toString 造一个 String，仅 encode 就 550ms + 一大波 GC。
     * 数值语义与旧 f() **完全一致**：都是 round(v × 1e4) 的十进制展开
     * （decode 端 toFloatOrNull 只认数值，两种写法解出来的 Float 相同）。
     */
    private fun appF(sb: StringBuilder, v: Float) {
        if (!v.isFinite()) { sb.append('0'); return }
        val n = Math.round(v * 10000f).toLong()
        var mag = n
        if (mag < 0) { sb.append('-'); mag = -mag }
        sb.append(mag / 10000L)
        var frac = (mag % 10000L).toInt()
        if (frac != 0) {
            val digits = charArrayOf('0', '0', '0', '0')
            for (i in 3 downTo 0) { digits[i] = ('0' + frac % 10); frac /= 10 }
            var end = 4
            while (end > 0 && digits[end - 1] == '0') end--
            sb.append('.').append(digits, 0, end)
        }
    }

    /**
     * 草稿 K 行热路径的 token 扫描器：按空格切分（与 `split(' ')` 同语义，含空 token），
     * 但**简单十进制数字就地解析**，不为每个字段分配 substring。
     * 遇到非简单十进制 token（科学计数法 / 乱码 / 超 15 位数）退回 substring + 标准解析。
     */
    private class Toks(private val s: String, start: Int = 0) {
        private var i = start
        private var ts = 0
        private var te = -1

        /** 定位下一个 token 到 [ts, te)；没有更多返回 false */
        private fun advance(): Boolean {
            if (i > s.length) return false
            ts = i
            val e = s.indexOf(' ', i)
            te = if (e < 0) { i = s.length + 1; s.length } else { i = e + 1; e }
            return true
        }

        fun nextFloat(): Float? {
            if (!advance()) return null
            return numAsFloat(s, ts, te) ?: s.substring(ts, te).trim().toFloatOrNull()
        }

        fun nextInt(): Int? {
            if (!advance()) return null
            return longAt(s, ts, te)?.toInt() ?: s.substring(ts, te).trim().toIntOrNull()
        }

        fun nextLong(): Long? {
            if (!advance()) return null
            return longAt(s, ts, te) ?: s.substring(ts, te).trim().toLongOrNull()
        }
    }

    /** 简单十进制 float 就地解析；不是这个形态（或整数部分超出 float 精度）返回 null 走标准解析 */
    private fun numAsFloat(s: String, st: Int, en: Int): Float? {
        var j = st
        var neg = false
        if (j < en && (s[j] == '-' || s[j] == '+')) { neg = s[j] == '-'; j++ }
        var m = 0L
        var scale = 0L
        var seenDigit = false
        var seenDot = false
        while (j < en) {
            val c = s[j]
            when {
                c in '0'..'9' -> {
                    seenDigit = true
                    m = m * 10 + (c - '0')
                    if (seenDot) scale++
                    if (m < 0L) return null          // 溢出：走标准解析
                }
                c == '.' && !seenDot -> seenDot = true
                else -> return null                  // 指数 / 乱码：走标准解析
            }
            j++
        }
        if (!seenDigit) return null
        // m 必须 < 2^24 才能 float(m) 精确、一次除法就和 Float.parseFloat 逐位一致
        if (m >= (1L shl 24)) return null
        val v = if (scale == 0L) m.toFloat() else m.toFloat() / POW10F[scale.toInt()]
        return if (neg) -v else v
    }

    /** 简单十进制 long 就地解析；不是这个形态（或溢出）返回 null 走标准解析 */
    private fun longAt(s: String, st: Int, en: Int): Long? {
        var j = st
        var neg = false
        if (j < en && (s[j] == '-' || s[j] == '+')) { neg = s[j] == '-'; j++ }
        if (j >= en) return null
        var m = 0L
        while (j < en) {
            val c = s[j]
            if (c !in '0'..'9') return null
            m = m * 10 + (c - '0')
            if (m < 0L) return null
            j++
        }
        return if (neg) -m else m
    }

    private val POW10F = floatArrayOf(1f, 10f, 100f, 1000f, 10000f, 100000f)
}
