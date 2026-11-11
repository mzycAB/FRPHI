package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * 从 .pez（Phira / RPE 谱面包）解析出的一条判定线。
 * 纯数据 + 纯 JVM 逻辑，不碰任何 Android API，便于单元测试。
 */
class ImportedLine(
    /** 本项目里的判定线 id（就是 RPE 里的下标） */
    val id: Int,
    /** RPE 里的判定线 Name（原样保留，导出时写回） */
    val name: String = "",
    /** 判定线位置：屏幕纵向百分比 0~100（与设置页「判定线位置」同一含义） */
    val posPct: Int,
    /** 全局偏移（归一化 -1..1）与旋转（度），与 RecordView 的 line.moveX/Y/rotate 一致 */
    val moveX: Float,
    val moveY: Float,
    val rotate: Float,
    /** 没有关键帧时的流速 */
    val speed: Float,
    /** 没有关键帧时的透明度 0~1（RPE alphaEvents / 255） */
    val alpha: Float,
    /** 判定线动画关键帧（含逐帧流速；空 = 全程静止） */
    val keyframes: List<LineKeyframe>,
    val notes: List<RecordedNote>,
    /** 父线 id（RPE father，-1 = 无） */
    val father: Int = -1,
    val rotWithFather: Boolean = false,
    /** ctrl 对象（yControl/posControl/sizeControl/alphaControl），原样保留供导出回写 */
    val ctrlY: List<CtrlEvent> = emptyList(),
    val ctrlPos: List<CtrlEvent> = emptyList(),
    val ctrlSize: List<CtrlEvent> = emptyList(),
    val ctrlAlpha: List<CtrlEvent> = emptyList(),
    /** 倾斜事件（extended.inclineEvents，度） */
    val incline: List<InclineEvent> = emptyList(),
    /** 图片线贴图文件名（RPE Texture ≠ line.png 时非空；转成文件名，路径规范到包根） */
    val texture: String = "",
    /** 图层序（RPE zOrder，Phira 按它排判定线绘制顺序），原样保留供导出回写 */
    val zOrder: Int = 0,
    /** 文字线内容（RPE extended.textEvents 的第一个值；只对 Texture=line.png 的线生效） */
    val lineText: String = "",
    /** 文字事件（RPE extended.textEvents）：图片线挂着它当副标题时，导出要原样写回 */
    val textEvents: List<TextEvent> = emptyList(),
    /** 静态缩放（RPE extended.scaleX/scaleYEvents 的第一个值） */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f
)

/**
 * 从 .pez（Phira / RPE 谱面包）解析出的谱面数据。
 * 纯数据 + 纯 JVM 逻辑，不碰任何 Android API，便于单元测试。
 */
class ImportedChart(
    val name: String,
    val composer: String,
    val charter: String,
    val level: String,
    val bpm: Double,
    val bpmChanges: Int,
    /** 主判定线流速（RPE speed 值，1.0 = 正常） */
    val speed: Double,
    /** 主判定线的位置/偏移/旋转（设置页字段用） */
    val linePosPct: Int,
    val lineMoveX: Float,
    val lineMoveY: Float,
    val lineRotate: Float,
    /** 判定线（至少一条，含各自的关键帧与音符） */
    val lines: List<ImportedLine>,
    /** 横线数：时间量化（每拍几格），由谱面时间分母反推（只影响编辑网格） */
    val hLines: Int,
    /** 竖线数：位置量化，由 positionX 反推（只影响编辑网格） */
    val vLines: Int,
    /** 包内音频条目名（null = 没找到音频） */
    val musicName: String?,
    /** 包内背景图条目名（null = 没找到背景图） */
    val imageName: String?,
    val warnings: List<String>,
    /** 多 BPM 分段（毫秒 + bpm，按时间升序，第一段从 0 开始）；单 BPM 时只有一段 */
    val bpmSegments: List<BpmSegment> = emptyList(),
    /**
     * 噪域（RPE `blockAreaList`）—— **可编辑**：
     * 读到、画出来，编辑页支持拖角/关键帧/显示深浅/新建复制删除/图层，导出写回原始 JSON。
     */
    val noiseAreas: List<NoiseArea> = emptyList(),
    /** 视频背景（Phira `extra.json` 的 `videos`）：文件已抽到项目导入目录 */
    val videos: List<VideoBg> = emptyList()
) {
    /** 全部音符（按时间排序，跨判定线合并） */
    val notes: List<RecordedNote> = lines.flatMap { it.notes }.sortedBy { it.startMs }

    /** 判定线条数 */
    val lineCount: Int get() = lines.size

    /** 兼容旧调用：主判定线的关键帧 */
    val keyframes: List<LineKeyframe> get() = lines.firstOrNull()?.keyframes ?: emptyList()
}

/**
 * PEZ 导入器：解析 RPE JSON 谱面 + 提取音频/背景图。
 *
 * 对照 Phira 解析端 `prpr/src/parse/rpe.rs` 实现，关键点：
 *  - BPMList 支持多 BPM 分段换算（Triple `[i,n,d]` = `i + n/d` 拍）
 *  - META.offset（毫秒）：Phira 里 `谱面时间 = 音乐时间 - offset`，所以音乐时间 = 谱面时间 + offset
 *  - judgeLine 的 moveX/moveY/rotate/speed 事件 → 本项目的全局姿态 + 线性关键帧
 *  - positionX 除以 675 归一化；moveY 除以 450 归一化（RPE_HEIGHT=900 的一半）
 *  - 多层 eventLayers 是**相加**关系（Phira `Anim::chain`）
 */
object PezImporter {

    val AUDIO_EXTS = setOf("mp3", "ogg", "wav", "flac", "aac", "m4a")
    val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp", "avif")
    private val TEXT_EXTS = setOf("json", "txt", "yml", "yaml", "csv", "pec")
    /** info.txt / yml 这类小文本的上限 */
    private const val MAX_TEXT = 2L * 1024 * 1024
    /**
     * 谱面 JSON 的上限。RPE 的 json 是带缩进的美化格式，一条 60 线、26000 事件的谱面
     * 能到 10MB 以上（去掉缩进后才 5MB 左右），所以**不能**用小文本的 8MB 卡死它。
     */
    private const val MAX_JSON = 64L * 1024 * 1024
    /** 横线数上限（再密网格就没有可读性了） */
    private const val MAX_HLINES = 96
    /** 单条判定线的关键帧上限（超过就均匀抽稀，不是砍掉后半段） */
    private const val MAX_KEYFRAMES = 3000
    /**
     * 「保持」缓动：Phira 里事件的**结束关键帧**用 tween 0（值恒等于上一个值），
     * 也就是"上一段结束后到下一段开始前，数值保持不变"，而不是线性过渡。
     * 这里用 -1 表示它（RPE 的 easingType 0 是线性，不能混用）。
     */
    private const val HOLD_EASE = -1
    /** 跳变前插入"保持"关键帧时留的 1ms 间隔（可精确表达跳变，又不产生重复时间码） */
    private const val HOLD_GAP_MS = 1.0
    /**
     * 官方谱 `speedEvents.value` → 本项目（RPE 语义）流速的换算系数。
     *
     * Phira 里两种谱的流速单位不同：
     *  - RPE：`SPEED_RATIO = 10 / 45 / HEIGHT_RATIO ≈ 0.26718`（流速 1 = 13.36% 屏高/秒，本项目的标定）；
     *  - 官谱：`1 / HEIGHT_RATIO ≈ 1.2023`（流速 1 = 60.1% 屏高/秒）。
     * 两者之比 = 1 / (10 / 45) = **4.5**。以前直接用官谱原值喂 RPE 语义，
     * 官谱的下落（含长条长度、判定线冲刺）一律慢 4.5 倍，就是「导入官谱后缩放不对」的根因。
     */
    private const val PGR_SPEED_SCALE = 4.5

    private val H_CANDIDATES = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96)
    private val V_CANDIDATES = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64)

    /**
     * 解析 pez。
     * @param openZip 每次调用返回一个**新的** ZipInputStream（需要遍历两遍：先读文本，再抽素材）
     * @param saveAsset 抽素材回调：条目名 + 该条目的输入流，返回是否成功（默认不抽）
     */
    fun import(
        openZip: () -> InputStream,
        saveAsset: (String, InputStream) -> Boolean = { _, _ -> false }
    ): ImportedChart {
        val warnings = ArrayList<String>()

        // ★ 整包读进内存做**宽容** zip 解析：Java 21 / 新版 Android 的 ZipInputStream
        //   在 readLOC 阶段对非 UTF-8 文件名的 zip（日文/中文包很常见）直接抛
        //   "malformed input"（用户报的「MALFORMED [1]」）。改用 ZipReader 逐编码探测文件名。
        val zipBytes = openZip().use { it.readBytes() }
        val entries = ZipReader.list(zipBytes)
        if (entries.isEmpty()) throw IllegalArgumentException("压缩包是空的或读不了")

        // ---------- 第一遍：只读文本类条目（谱面 JSON / info.txt） ----------
        val texts = LinkedHashMap<String, ByteArray>()
        val names = ArrayList<String>()
        var jsonTooBig = false
        for (e in entries) {
            val n = cleanName(e.name)
            if (n.isEmpty()) continue
            names.add(n)
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext in TEXT_EXTS) {
                // PEC 谱面也是大文本（几百 KB ~ 几十 MB），不能用小文本上限截断
                val limit = if (ext == "json" || ext == "pec") MAX_JSON else MAX_TEXT
                val b = ZipReader.bytes(zipBytes, e)
                if (b != null && b.size.toLong() <= limit) texts[n] = b
                else if (ext == "json" || ext == "pec") jsonTooBig = true
            }
        }

        // ---------- info.txt（RPE 标准：# 开头 + Key: Value） ----------
        val info = texts.entries.firstOrNull { baseName(it.key).equals("info.txt", true) }
            ?.let { runCatching { parseInfoTxt(decodeText(it.value)) }.getOrNull() }
            ?: emptyMap()

        // ---------- 谱面 JSON / PEC ----------
        // 只嗅探不建树：46MB 级的谱面 JSON 整棵 parse 成 JSONObject 会 OOM
        val pickedName = pickChartName(texts, info["Chart"])
            ?: run {
                // 有些 PEC 谱被导出成 .json 扩展名（内容其实是 `bp` / `n1` 这类文本行），
                // 按扩展名会误判成"没有 JSON"。这里按内容嗅探一次，命中就走 PEC 分支。
                val pec = texts.entries.firstOrNull { looksLikePec(it.value) }
                if (pec != null) {
                    return importPec(pec.value, names, info, warnings, zipBytes, entries, saveAsset)
                }
                // PEC 谱面已支持：zip 里有真正的 .pec 条目就直接导入（以前这条提示"暂不支持"）
                val pecEntry = names.firstOrNull { it.substringAfterLast('.', "").equals("pec", true) }
                    ?.let { texts[it] }
                if (pecEntry != null) {
                    return importPec(pecEntry, names, info, warnings, zipBytes, entries, saveAsset)
                }
                throw IllegalArgumentException(
                    when {
                        jsonTooBig -> "谱面 JSON 太大（超过 64MB），暂时读不了"
                        else -> "压缩包里没有找到谱面 JSON"
                    }
                )
            }
        val raw = texts[pickedName] ?: throw IllegalArgumentException("压缩包里没有找到谱面 JSON")
        // extra.json（视频背景等扩展特性）要在 texts.clear() 之前拿走
        val extraBytes = texts.entries
            .firstOrNull { baseName(it.key).equals("extra.json", true) }?.value
        // 谱面 JSON 可能有几十 MB，其它文本条目用完即弃
        texts.clear()

        return importFromDocument(raw, names, info, warnings, zipBytes, entries, saveAsset, extraBytes)
    }

    /**
     * 谱面 JSON 已经拿到（来自压缩包或单独一个 .json 文件）之后的公共流程：
     * 分派格式 → 解析 → 抽素材 → 拼 ImportedChart。
     *
     * ★ 传原始字节而不是 JSONObject：46MB 级大谱（几万事件）整棵建 DOM 树会 OOM，
     *   RPE 分支全程流式（顶层成员按字节切片、判定线逐条 parse 完即弃）。
     */
    private fun importFromDocument(
        raw0: ByteArray,
        names: List<String>,
        info: Map<String, String>,
        warnings: MutableList<String>,
        zipBytes: ByteArray,
        entries: List<ZipReader.Entry>,
        saveAsset: (String, InputStream) -> Boolean,
        extraBytes: ByteArray? = null
    ): ImportedChart {
        val raw = normalizeJsonBytes(raw0)
        val doc = JsonDoc(raw)

        // ---------- 格式分派：RPE（带 META）还是官谱（Phigros Official JSON） ----------
        // 和 Phira 的 `infer_chart_format` 同一判据：**没有 META 但有 judgeLineList** 就是官谱。
        val isPgr = !doc.has("META") && doc.has("judgeLineList")
        if (isPgr) {
            // 官谱都很小（几 MB 级），整树 parse 没有压力；GBK 转码也在 decodeText 里做
            val o = runCatching { JSONObject(minifyJson(decodeText(raw))) }.getOrNull()
                ?: throw IllegalArgumentException("谱面 JSON 解析失败")
            return importPgr(o, names, info, warnings, zipBytes, entries, saveAsset)
        }

        val meta = doc.member("META")?.let {
            runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull()
        }
        val offsetMs = meta?.optDouble("offset", 0.0)?.takeIf { it.isFinite() } ?: 0.0

        // ---------- BPM ----------
        val bpmItems = ArrayList<Pair<Double, Double>>()   // (beats, bpm)
        val bpmArr = doc.member("BPMList")?.let {
            runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull()
        }
        if (bpmArr != null) for (i in 0 until bpmArr.length()) {
            val o = bpmArr.optJSONObject(i) ?: continue
            val b = o.optDouble("bpm", 120.0)
            if (!b.isFinite() || b <= 0.0) continue
            val st = o.optJSONArray("startTime")
            bpmItems.add((if (st != null) tripleBeats(st) else 0.0) to b)
        }
        if (bpmItems.isEmpty()) bpmItems.add(0.0 to 120.0)
        bpmItems.sortBy { it.first }
        val bpmMap = BpmMap(bpmItems)
        val bpm0 = bpmItems.first().second
        // 多 BPM：把每一段的起始时间（拍 → 毫秒）记下来，导出时原样写回 BPMList
        val segs = ArrayList<BpmSegment>(bpmItems.size)
        for ((beats, b) in bpmItems) {
            segs.add(BpmSegment(Math.round(bpmMap.seconds(beats) * 1000.0), b))
        }
        val bpmSegments = BpmTimeline.normalize(segs, bpm0)
        if (bpmSegments.size > 1) {
            warnings.add(
                "多 BPM 谱面（" + bpmSegments.size + " 段）：已按分段保留，" +
                    "导出时会写回 BPMList（编辑网格仍按首 BPM " + trimNum(bpm0) + " 铺）"
            )
        }

        // ---------- 判定线（流式：逐条切片 parse，完即弃，不整树建 DOM） ----------
        var lineCount = 0
        val parsedLines = ArrayList<ParsedLine>()
        for (slice in doc.arrayElements("judgeLineList")) {
            lineCount++
            val line = runCatching { JSONObject(String(slice, Charsets.UTF_8)) }.getOrNull()
            if (line == null) {
                warnings.add("第 $lineCount 条判定线 JSON 解析失败，已跳过")
                continue
            }
            parseLine(line, bpmMap, offsetMs / 1000.0)?.let { parsedLines.add(it) }
        }
        if (lineCount == 0) throw IllegalArgumentException("谱面里没有判定线")
        if (parsedLines.isEmpty()) throw IllegalArgumentException("谱面里没有可用的判定线")

        val importLines = ArrayList<ImportedLine>()
        var fakeCount = 0
        var backCount = 0
        var propCount = 0
        var dropped = 0
        var emptyLines = 0
        var fatherCount = 0
        var ctrlCount = 0
        var inclineCount = 0
        val beatsForGrid = ArrayList<Double>()
        val denomsForGrid = ArrayList<Int>()
        val posForGrid = ArrayList<Double>()

        for ((li, line) in parsedLines.withIndex()) {
            // attachUI = Phira 里挂界面（暂停键/分数/combo/等级）的"定位线"，
            // Phira 不会把它画成判定线（Chart::new 里会从渲染顺序里剔除），这里同样跳过
            if (line.attachUi.isNotEmpty()) continue
            if (line.father >= 0) fatherCount++
            if (line.ctrlY.isNotEmpty() || line.ctrlPos.isNotEmpty() || line.ctrlSize.isNotEmpty() || line.ctrlAlpha.isNotEmpty()) ctrlCount++
            if (line.incline.isNotEmpty()) inclineCount++
            val pose = buildPose(line, offsetMs / 1000.0, warnings)
            val lineNotes = ArrayList<RecordedNote>()
            for (n in line.rawNotes) {
                val kind = n.type
                if (kind !in 1..4) { dropped++; continue }
                if (n.fake) fakeCount++
                if (!n.above) backCount++
                // Phira：alpha >= 255 视为默认 1；音符级 speed/yOffset/size/visibleTime 原样带进渲染
                // （speed=0 / size=0 是合法值：分别表示"停在线上"和"不可见"，Phira 都原样使用）
                val noteAlpha = if (n.alpha >= 255.0) 1f else (n.alpha.coerceIn(0.0, 255.0) / 255.0).toFloat()
                val noteSpeed = if (n.speed.isFinite()) n.speed else 1f
                val noteVisible = if (n.visibleTime.isFinite() && n.visibleTime >= 0f) n.visibleTime.toFloat() else 999999f
                if (n.speed != 1f || Math.abs(n.yOffset) > 1e-6 || n.alpha < 255.0 ||
                    Math.abs(n.size - 1f) > 1e-6 || n.visibleTime < 999998.0) propCount++

                val startMs = (n.startSec * 1000.0 + offsetMs).let { if (it < 0) 0.0 else it }
                val endMs = if (kind == 2) (n.endSec * 1000.0 + offsetMs).coerceAtLeast(startMs) else startMs
                val posX = (n.positionX / 675.0).coerceIn(-1.0, 1.0)

                lineNotes.add(
                    RecordedNote(
                        type = kind,
                        startMs = Math.round(startMs),
                        endMs = Math.round(endMs),
                        posX = posX.toFloat(),
                        pass = passOf(kind),
                        lineId = li,
                        above = n.above,
                        fake = n.fake,
                        speed = noteSpeed,
                        yOffset = if (n.yOffset.isFinite()) n.yOffset.toFloat() else 0f,
                        alpha = noteAlpha,
                        size = if (n.size.isFinite()) n.size else 1f,
                        visibleTime = noteVisible,
                        judgeArea = n.judgeArea,
                        tint = n.tint,
                        tintHit = n.tintHit,
                        hitsound = n.hitsound
                    )
                )
                beatsForGrid.add(n.startBeats)
                denomsForGrid.add(n.startDenom)
                posForGrid.add(n.positionX / 675.0)
            }
            // 没有音符的判定线也要保留：那是表演用的装饰线（只有关键帧动画，不挂音符）
            if (lineNotes.isEmpty()) emptyLines++
            lineNotes.sortBy { it.startMs }
            importLines.add(
                ImportedLine(
                    id = li,
                    name = line.name,
                    posPct = pose.pct,
                    moveX = pose.moveX,
                    moveY = pose.moveY,
                    rotate = pose.rotate,
                    speed = pose.speed,
                    alpha = pose.alpha,
                    keyframes = pose.keyframes,
                    notes = lineNotes,
                    father = line.father,
                    rotWithFather = line.rotWithFather,
                    ctrlY = line.ctrlY,
                    ctrlPos = line.ctrlPos,
                    ctrlSize = line.ctrlSize,
                    ctrlAlpha = line.ctrlAlpha,
                    incline = line.incline,
                    texture = line.texture,
                    lineText = line.lineText,
                    textEvents = line.textEvents,
                    scaleX = pose.scaleX,
                    scaleY = pose.scaleY,
                    zOrder = line.zOrder
                )
            )
        }
        if (importLines.isEmpty()) throw IllegalArgumentException("谱面里没有可导入的音符")
        if (emptyLines > 0) warnings.add("有 $emptyLines 条判定线没有音符（表演用的装饰线），已按原样保留")
        if (fakeCount > 0) warnings.add("已识别 $fakeCount 个假音符（isFake：只显示、不判定）")
        if (backCount > 0) warnings.add("已识别 $backCount 个反面音符（above=0：从判定线另一侧落下）")
        if (propCount > 0) warnings.add("已识别 $propCount 个带扩展属性的音符（流速/偏移/透明度/大小/可见时间），已按 Phira 语义保留")
        if (ctrlCount > 0) warnings.add("已识别 ctrl 对象（yControl 等 $ctrlCount 条线）：音符按到判定线的距离调制下落/位置/大小/透明度")
        if (inclineCount > 0) warnings.add("已识别倾斜事件（incline，$inclineCount 条线）：非长条音符随下落向判定线中心收缩")
        if (fatherCount > 0) warnings.add("已识别父线（father，$fatherCount 条线）：子线位置/旋转跟随父线")
        if (dropped > 0) warnings.add("已跳过 $dropped 个未知类型音符")

        // ---------- 噪域（blockAreaList）：可编辑 ----------
        // 噪域的区域框/激活区间/事件在这里全部读进来，导出时原样写回；编辑页可以拖角/打帧/分图层。
        // 用 parseAllAuto 而不是 parseAll：有些 .pez 里混了官谱格式的块（百分比 + 秒），
        // 被 RPE 解析器读会退化成「默认矩形 + 永远存在」，编辑页一帧画上万块直接卡死。
        val noiseArr = doc.member("blockAreaList")?.let {
            runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull()
        }
        val noiseAreas = NoiseArea.parseAllAuto(noiseArr, bpmItems, warnings)

        // 视频背景（Phira extra.json）
        val videos = parseExtraVideos(extraBytes, warnings, zipBytes, entries, saveAsset)

        // 主判定线 = 音符最多的一条（设置页的"判定线位置/流速"填它）
        val main = importLines.maxByOrNull { it.notes.size } ?: importLines.first()

        // ---------- 横线 / 竖线 ----------
        val hLines = inferHLines(beatsForGrid, denomsForGrid, warnings)
        val vLines = inferVLines(posForGrid, warnings)

        // ---------- 素材 ----------
        val songName = meta?.optString("song")?.takeIf { it.isNotBlank() }
            ?: info["Song"] ?: info["Music"]
        val bgName = meta?.optString("background")?.takeIf { it.isNotBlank() }
            ?: info["Picture"] ?: info["Image"]

        val musicEntry = pickEntry(names, songName, AUDIO_EXTS)
        val imageEntry = pickEntry(names, bgName, IMAGE_EXTS)
        // 图片线的贴图也要抽出来（Texture 指向的文件），不然导回 .pez 时无图可打
        val lineTexEntries = importLines.mapNotNull { it.texture.takeIf { t -> t.isNotEmpty() } }.toSet()
        if (musicEntry != null || imageEntry != null || lineTexEntries.isNotEmpty()) {
            // 第二遍：只抽需要的条目（ZipReader 宽容解析，不再用 ZipInputStream）
            var want = setOfNotNull(musicEntry, imageEntry) + lineTexEntries
            runCatching {
                for (e in entries) {
                    val n = cleanName(e.name)
                    if (n in want) {
                        ZipReader.bytes(zipBytes, e)?.let { saveAsset(n, java.io.ByteArrayInputStream(it)) }
                        // 一个条目被多个名字引用（Texture 带子目录 / 同名）时只抽一次
                        if (n in lineTexEntries) want -= n
                    }
                }
            }.onFailure { warnings.add("素材提取失败：${it.message}") }
        }

        // ---------- 文本信息 ----------
        val name = meta?.optString("name")?.takeIf { it.isNotBlank() }
            ?: info["Name"]?.takeIf { it.isNotBlank() } ?: "导入谱面"
        val composer = meta?.optString("composer")?.takeIf { it.isNotBlank() }
            ?: info["Composer"] ?: info["Artist"] ?: info["Musician"] ?: ""
        val charter = meta?.optString("charter")?.takeIf { it.isNotBlank() }
            ?: info["Charter"] ?: info["Designer"] ?: ""
        val level = meta?.optString("level")?.takeIf { it.isNotBlank() }
            ?: info["Level"] ?: ""

        return ImportedChart(
            name = name,
            composer = composer,
            charter = charter,
            level = level,
            bpm = bpm0,
            bpmChanges = bpmItems.size,
            speed = main.speed.toDouble(),
            linePosPct = main.posPct.coerceIn(0, 100),   // 设置页字段只接受 0~100
            lineMoveX = main.moveX,
            lineMoveY = main.moveY,
            lineRotate = main.rotate,
            lines = importLines,
            hLines = hLines,
            vLines = vLines,
            musicName = musicEntry,
            imageName = imageEntry,
            warnings = warnings,
            bpmSegments = bpmSegments,
            noiseAreas = noiseAreas,
            videos = videos
        )
    }

    /**
     * extra.json 里的视频背景：`{"bpm": ..., "videos": [{"path","time","scale","alpha","dim"}]}`。
     * `time` 是拍数（按 extra.json 自己的 bpm 换算成毫秒）；视频文件从压缩包里抽到项目目录。
     */
    private fun parseExtraVideos(
        extra: ByteArray?,
        warnings: MutableList<String>,
        zipBytes: ByteArray,
        entries: List<ZipReader.Entry>,
        saveAsset: (String, InputStream) -> Boolean
    ): List<VideoBg> {
        if (extra == null || extra.isEmpty()) return emptyList()
        val root = runCatching { JSONObject(decodeText(extra)) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("videos") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        // 视频时间用的 bpm 表
        val bpmItems = ArrayList<Pair<Double, Double>>()
        when (val bpmNode = root.opt("bpm")) {
            is Number -> bpmItems.add(0.0 to bpmNode.toDouble())
            is JSONArray -> for (i in 0 until bpmNode.length()) {
                val o = bpmNode.optJSONObject(i) ?: continue
                val b = o.optDouble("bpm", 120.0)
                if (!b.isFinite() || b <= 0.0) continue
                val t = o.optJSONArray("time")
                bpmItems.add((if (t != null) tripleBeats(t) else 0.0) to b)
            }
        }
        if (bpmItems.isEmpty()) bpmItems.add(0.0 to 120.0)
        bpmItems.sortBy { it.first }
        val bpmMap = BpmMap(bpmItems)

        val out = ArrayList<VideoBg>()
        val want = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path", "").takeIf { it.isNotBlank() } ?: continue
            val name = cleanName(path)
            if (name.isEmpty()) continue
            val beats = o.optJSONArray("time")?.let { tripleBeats(it) } ?: 0.0
            out.add(
                VideoBg(
                    fileName = name,
                    startMs = Math.round(bpmMap.seconds(beats) * 1000.0).coerceAtLeast(0L),
                    scaleMode = VideoBg.scaleFromKey(o.optString("scale", "cropCenter")),
                    alpha = animValue(o.opt("alpha"), 1f).coerceIn(0f, 1f),
                    dim = animValue(o.opt("dim"), 0f).coerceIn(0f, 1f)
                )
            )
            want.add(name)
        }
        if (out.isEmpty()) return out
        runCatching {
            for (e in entries) {
                val n = cleanName(e.name)
                if (n in want) {
                    ZipReader.bytes(zipBytes, e)?.let { saveAsset(n, java.io.ByteArrayInputStream(it)) }
                }
            }
        }.onFailure { warnings.add("视频提取失败：${it.message}") }
        warnings.add("已识别视频背景 ${out.size} 段（右侧「视频」页签可编辑）")
        return out
    }

    /** extra.json 的 alpha/dim 可以是固定值，也可以是关键帧数组（取最后一帧的 end） */
    private fun animValue(node: Any?, def: Float): Float = when (node) {
        is Number -> node.toFloat()
        is JSONArray -> {
            val o = node.optJSONObject(node.length() - 1)
            o?.optDouble("end", def.toDouble())?.toFloat() ?: def
        }
        else -> def
    }

    // ------------------------------------------------------------------ 官谱（Phigros Official JSON）

    /** 官谱的一小段事件：时间段 + 起止值（线性插值） */
    private class PgrSeg(val t0: Double, val t1: Double, val v0: Double, val v1: Double) {
        fun at(t: Double): Double {
            if (t <= t0) return v0
            if (t >= t1) return v1
            val span = t1 - t0
            if (span <= 1e-9) return v1
            return v0 + (v1 - v0) * ((t - t0) / span)
        }
    }

    /**
     * 官谱事件 → 「时间 → 值」折线点（给 [buildPosePgr] 用）。
     *
     * 官谱的一条轨道是**首尾相接**的段序列，段与段之间**不插值**：
     * 段 i 的结束值只保持到段 i+1 的开始时刻，段 i+1 的起始值从那一刻起**瞬间**生效。
     * 所以点集必须是「t0,v0 → t1,v1 → next.t0,next.v0 …」——
     * 以前把每段的两端都直接堆进点集，段尾值（v1）和下一段段首值（v0）落在**不同**时刻，
     * 中间那 1 个最短时间内会被线性插成一条斜线，整条线就"歪"了（导入后完全错乱的原因之一）。
     * 首尾相接时直接复用「t1,v1」作为下一段起点，同一时刻两个值 → 采样取后一个（跳变），天然正确。
     */
    private fun segPts(segs: List<PgrSeg>): List<Pair<Double, Double>> {
        if (segs.isEmpty()) return emptyList()
        val pts = ArrayList<Pair<Double, Double>>(segs.size + 1)
        pts.add(segs[0].t0 to segs[0].v0)
        for (s in segs) {
            if (pts.isNotEmpty() && Math.abs(pts[pts.size - 1].first - s.t0) < 1e-9) {
                // 和上一段的结束同一时刻：值连续就合并（不加点），不连续就保留**两个重合点**，
                // 让「跳变」这件事在数据里显式存在（buildPosePgr 靠它决定是否补 1ms 保持帧）。
                val last = pts[pts.size - 1]
                if (Math.abs(last.second - s.v0) <= 1e-9) {
                    // 完全连续：什么都不用加
                } else {
                    pts.add(s.t0 to s.v0)   // 同一时刻的第二个值 = 跳变
                }
            } else {
                pts.add(s.t0 to s.v0)
            }
            if (Math.abs(pts[pts.size - 1].first - s.t1) < 1e-9) {
                pts[pts.size - 1] = s.t1 to s.v1
            } else {
                pts.add(s.t1 to s.v1)
            }
        }
        return pts
    }
    /** 分段常量采样（透明度 / 速度）：官谱里这类事件是**阶跃**，段内不插值，只有段首生效 */
    private fun stepPts(segs: List<PgrSeg>): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>(segs.size * 2)
        for (s in segs) {
            pts.add(s.t0 to s.v0)
            pts.add(s.t1 to s.v0)
        }
        return pts
    }

    /**
     * 官谱 `speedEvents` → 分段常量（`[t0, t1)` 一个值）。
     *
     * 字段名是 **`value`**（Phira `PgrSpeedEvent { start_time, end_time, value }`），
     * 和移动/旋转/透明度那套 `start`/`end` 完全不同。读错的话每段值都退化成 0，
     * 整条线的音符会全部挤在判定线上（屏幕上看就是「音符识别混乱」）。
     */
    private fun pgrSpeedSegs(line: JSONObject, unit: Double, chartEndSec: Double): List<PgrSeg> {
        val arr = line.optJSONArray("speedEvents") ?: return emptyList()
        val out = ArrayList<PgrSeg>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = o.optDouble("startTime", 0.0).takeIf { it.isFinite() } ?: 0.0
            var et = o.optDouble("endTime", st).takeIf { it.isFinite() } ?: st
            if (et < st) et = st
            val t0 = (if (st < 0) 0.0 else st) * unit
            if (t0 >= chartEndSec) continue   // ★ 谱尾外整段丢（全是「保持到底」的 1e9 尾巴）
            // value 缺失 / 非有限 → 按 1（不缩不放），绝不按 0（0 会让整条线塌在判定线上）
            var v = o.optDouble("value", Double.NaN)
            if (!v.isFinite()) v = 1.0
            // ★ 官谱的 value 可以是 500 / 1000（判定线瞬移/冲刺）也可以是**负数**（判定线倒着走，
            //   Phigros AT 常规手法，Phira `parse_speed_events` 原样进 AnimFloat 不夹）。
            //   以前「负值夹 0、渲染时再夹 0..100」把 H 积分全毁了 —— 日志实测长条尾巴
            //   top=-91424px，按 500 算应为 -457120px，正好差 5 倍。这里只挡非有限值。
            if (v > 1.0e6) v = 1.0e6
            if (v < -1.0e6) v = -1.0e6
            // 官谱流速单位 ≠ RPE：乘 4.5 才能得到和 Phira 一致的下落速度（见 PGR_SPEED_SCALE）
            v *= PGR_SPEED_SCALE
            out.add(PgrSeg(t0, Math.min(et * unit, chartEndSec), v, v))
        }
        out.sortBy { it.t0 }
        return out
    }

    /**
     * 官谱（Phigros Official JSON，`formatVersion` 1 或 3）→ ImportedChart。
     *
     * 与 RPE 的根本差别（对照 Phira `prpr/src/parse/pgr.rs` + 官方 wiki）：
     *  - **每条判定线自带 BPM**，全谱没有统一的 BPMList；所有时间单位是 `1.875/bpm` 秒（即 32 分音符）；
     *  - 每条线只有**一条轨道**：`judgeLineMoveEvents` / `judgeLineRotateEvents` /
     *    `judgeLineDisappearEvents` / `speedEvents`，没有 eventLayers、没有缓动（全是线性）、
     *    没有 ctrl 对象、没有父线、没有缩放/贴图/文字；
     *  - `notesAbove` = 从上方落下，`notesBelow` = 从下方落下；
     *  - `offset` 单位是**秒**（RPE 的 META.offset 是毫秒），正数表示谱面比音乐快；
     *  - `formatVersion` 1 的移动事件把 x/y 打包成一个整数（`x*1000 + y`），坐标单位分别是 1/880、1/520；
     *    `formatVersion` 3 直接是 -1..1 的浮点；
     *  - 音符 `positionX` 到归一化坐标的系数是 `2 * 9 / 160`（Phira `parse_notes`），
     *    **不是** RPE 的 `/675`（官谱 positionX 的量纲是 ±8.88，RPE 是 ±675，差 76 倍）。
     */
    private fun importPgr(
        doc: JSONObject,
        names: List<String>,
        info: Map<String, String>,
        warnings: MutableList<String>,
        zipBytes: ByteArray,
        entries: List<ZipReader.Entry>,
        saveAsset: (String, InputStream) -> Boolean
    ): ImportedChart {
        val linesArr = doc.optJSONArray("judgeLineList")
            ?: throw IllegalArgumentException("谱面缺少 judgeLineList")
        if (linesArr.length() == 0) throw IllegalArgumentException("谱面里没有判定线")
        val formatVersion = doc.optInt("formatVersion", doc.optInt("formatversion", 3))
        if (formatVersion != 1 && formatVersion != 3) {
            warnings.add("官谱 formatVersion = $formatVersion（只认 1 / 3），按 3 解析")
        }
        // 官谱 offset 单位是秒；本 py 里内部一律用毫秒，最后的音符时间用 (谱面秒 + offset) * 1000
        val offsetSec = doc.optDouble("offset", 0.0).takeIf { it.isFinite() } ?: 0.0

        // ★ 谱尾截断线（对照 Phira `parse_phigros` 的 max_time = 最大音符时间 + 1s）：
        //   官谱四条轨道（speed/move/rotate/disappear）的最后一段都是 `endTime: 1e9`（tick）
        //   ＝「保持到底」的惯例写法。不截断就会给每条线生成一个 t≈85 亿 ms 的垃圾关键帧
        //   （进草稿、进编辑页关键帧列表、拉爆时间轴总量）。官谱各段在该点之后值不变，
        //   截掉不改变谱尾内的任何表现。
        var maxNoteSec = 0.0
        for (li in 0 until linesArr.length()) {
            val line = linesArr.optJSONObject(li) ?: continue
            var bpm = line.optDouble("bpm", 120.0)
            if (!bpm.isFinite() || bpm <= 0.0) bpm = 120.0
            val u = 60.0 / 32.0 / bpm
            for (key in listOf("notesAbove", "notesBelow")) {
                val arr = line.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optDouble("time", Double.NaN)
                    if (t.isFinite() && t * u > maxNoteSec) maxNoteSec = t * u
                }
            }
        }
        // 没有音符的谱面走不到截断（后面会因「谱面里没有音符」报错），用无穷大 = 不截
        val chartEndSec = if (maxNoteSec > 0.0) maxNoteSec + 1.0 else Double.POSITIVE_INFINITY

        val importLines = ArrayList<ImportedLine>()
        var fakeCount = 0
        var backCount = 0
        var dropped = 0
        var emptyLines = 0
        val beatsForGrid = ArrayList<Double>()   // 官谱的"拍"= 32 分音符数（time 字段原值）
        val denomsForGrid = ArrayList<Int>()
        val posForGrid = ArrayList<Double>()
        val lineBpms = ArrayList<Double>()
        var totalNotes = 0

        for (li in 0 until linesArr.length()) {
            val line = linesArr.optJSONObject(li) ?: continue
            var bpm = line.optDouble("bpm", 120.0)
            if (!bpm.isFinite() || bpm <= 0.0) bpm = 120.0
            lineBpms.add(bpm)
            // 官谱时间单位：1 个单位 = 1.875 / bpm 秒
            val unit = 60.0 / 32.0 / bpm

            fun plainSegs(key: String): List<PgrSeg> {
                val arr = line.optJSONArray(key) ?: return emptyList()
                val out = ArrayList<PgrSeg>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val st = o.optDouble("startTime", 0.0).takeIf { it.isFinite() } ?: 0.0
                    var et = o.optDouble("endTime", st).takeIf { it.isFinite() } ?: st
                    val t0 = (if (st < 0) 0.0 else st) * unit
                    if (t0 >= chartEndSec) continue   // ★ 谱尾外整段丢（全是「保持到底」的 1e9 尾巴）
                    if (et < st) et = st
                    val v0 = o.optDouble("start", 0.0).takeIf { it.isFinite() } ?: 0.0
                    val v1 = o.optDouble("end", v0).takeIf { it.isFinite() } ?: v0
                    out.add(PgrSeg(t0, Math.min(et * unit, chartEndSec), v0, v1))
                }
                out.sortBy { it.t0 }
                return out
            }

            // ---- 移动（x / y）----
            val moveX: List<PgrSeg>
            val moveY: List<PgrSeg>
            if (formatVersion == 1) {
                // fv1 的 move 事件把 x/y 打包成一个数：e.start = x*1000 + y。
                // Phira `parse_move_events_fv1`：x = (2·v − 880)/880 = v/440 − 1，y = (2·r − 520)/520 = r/260 − 1。
                // 以前漏了"-1"和 ×2，全部线都挤在右下象限。
                val raw = plainSegs("judgeLineMoveEvents")
                moveX = raw.map {
                    val a = Math.floor(it.v0 / 1000.0) / 440.0 - 1.0
                    val b = Math.floor(it.v1 / 1000.0) / 440.0 - 1.0
                    PgrSeg(it.t0, it.t1, a, b)
                }
                moveY = raw.map {
                    val a = (it.v0 - Math.floor(it.v0 / 1000.0) * 1000.0) / 260.0 - 1.0
                    val b = (it.v1 - Math.floor(it.v1 / 1000.0) * 1000.0) / 260.0 - 1.0
                    PgrSeg(it.t0, it.t1, a, b)
                }
            } else {
                val arr = line.optJSONArray("judgeLineMoveEvents") ?: JSONArray()
                val mx = ArrayList<PgrSeg>(arr.length())
                val my = ArrayList<PgrSeg>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val st = o.optDouble("startTime", 0.0).takeIf { it.isFinite() } ?: 0.0
                    var et = o.optDouble("endTime", st).takeIf { it.isFinite() } ?: st
                    if (et < st) et = st
                    val t0 = (if (st < 0) 0.0 else st) * unit
                    if (t0 >= chartEndSec) continue   // ★ 谱尾外整段丢（全是「保持到底」的 1e9 尾巴）
                    val t1 = Math.min(et * unit, chartEndSec)
                    val x0 = o.optDouble("start", 0.0).takeIf { it.isFinite() } ?: 0.0
                    val x1 = o.optDouble("end", x0).takeIf { it.isFinite() } ?: x0
                    val y0 = o.optDouble("start2", 0.0).takeIf { it.isFinite() } ?: 0.0
                    val y1 = o.optDouble("end2", y0).takeIf { it.isFinite() } ?: y0
                    // Phira `parse_move_events`（fv3）：x、y 都是 value*2 − 1（JSON 里是 0..1）。
                    // 以前直接把 0..1 当 -1..1 用 ⇒ 所有判定线/音符整体偏移半屏。
                    mx.add(PgrSeg(t0, t1, x0 * 2.0 - 1.0, x1 * 2.0 - 1.0))
                    my.add(PgrSeg(t0, t1, y0 * 2.0 - 1.0, y1 * 2.0 - 1.0))
                }
                mx.sortBy { it.t0 }
                my.sortBy { it.t0 }
                moveX = mx
                moveY = my
            }
            val rotate = plainSegs("judgeLineRotateEvents")
            val alpha = plainSegs("judgeLineDisappearEvents")
            // ★ 官谱 speedEvents 的字段是 `value`，**不是** `start`/`end`（Phira `PgrSpeedEvent`：
            //   `{ start_time, end_time, value }`）。以前借用 plainSegs 读 start/end ⇒ 每条都读成
            //   0.0 ⇒ 全谱流速 0 ⇒ 所有音符堆在判定线上、屏幕上看起来「音符全乱/挤成一坨」。
            val speedEv = pgrSpeedSegs(line, unit, chartEndSec)

            val xPts = segPts(moveX)
            val yPts = segPts(moveY)
            val rPts = segPts(rotate)
            // 官谱透明度可能是 0..1，也可能是 0..255（旧谱），两种都见过 —— 大于 1 就按 255 折算。
            // ★ 官谱 alpha 事件和移动/旋转一样是**线性插值**（Phira `parse_float_events` 的 tween 2），
            //   不是阶跃；只有 speed 事件是分段常量（Phira `parse_speed_events` 每段一个固定倍率）。
            val aMax = alpha.maxOfOrNull { Math.max(Math.abs(it.v0), Math.abs(it.v1)) } ?: 1.0
            val aDiv = if (aMax > 1.5) 255.0 else 1.0
            val aPts = segPts(alpha).map { it.first to (it.second / aDiv) }
            // 官谱 line-speed 事件是阶跃（每段一个固定倍率）
            val sPts = stepPts(speedEv)

            val parsed = ParsedLine(
                name = "线${li + 1}",
                texture = "", attachUi = "",
                moveX = LayerAnim(emptyList()), moveY = LayerAnim(emptyList()),
                rotate = LayerAnim(emptyList()), speed = LayerAnim(emptyList()),
                alpha = LayerAnim(emptyList()),
                rawNotes = emptyList(),
                father = -1, rotWithFather = false,
                ctrlY = emptyList(), ctrlPos = emptyList(),
                ctrlSize = emptyList(), ctrlAlpha = emptyList(),
                incline = emptyList(),
                pgrX = xPts, pgrY = yPts, pgrRot = rPts, pgrAlpha = aPts, pgrSpeed = sPts,
                pgrBpm = bpm
            )
            val pose = buildPose(parsed, offsetSec, warnings)

            // ---- 音符：notesAbove（上落）+ notesBelow（下落）----
            val lineNotes = ArrayList<RecordedNote>()
            for ((above, key) in listOf(true to "notesAbove", false to "notesBelow")) {
                val arr = line.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = o.optInt("type", 1)
                    if (kind !in 1..4) { dropped++; continue }
                    val t = o.optDouble("time", Double.NaN)
                    if (!t.isFinite()) { dropped++; continue }
                    // 官谱 type：1 Tap / 2 Drag / 3 Hold / 4 Flick（和 RPE 的 1 Tap / 2 Hold / 3 Flick / 4 Drag 不同！）
                    val type = when (kind) {
                        2 -> 4   // Drag → 本项目 4
                        3 -> 2   // Hold → 本项目 2
                        4 -> 3   // Flick → 本项目 3
                        else -> 1
                    }
                    // holdTime 是"多长个 32 分音符"，不是拍数也不是毫秒
                    val hold = o.optDouble("holdTime", 0.0).takeIf { it.isFinite() } ?: 0.0
                    val startSec = t * unit
                    val endSec = if (type == 2) startSec + hold * unit else startSec
                    // ★ 官谱 positionX → 归一化坐标，直接照 Phira `parse_notes` 的系数：
                    //   translation.x = positionX × (2 × 9 / 160) = positionX × 0.1125，范围 -1..1。
                    //   以前套 RPE 的 /675（假设官谱单位 675×…) 少乘了 20 倍，音符全挤在判定线中间。
                    val posX = (o.optDouble("positionX", 0.0).takeIf { it.isFinite() } ?: 0.0) * 0.1125
                    // 官谱里 speed 是"音符流速倍率"（长条一律按 1），可能是 Infinity（作者用来做瞬移）——夹到可用上限
                    var noteSpeed = o.optDouble("speed", 1.0).takeIf { !it.isNaN() } ?: 1.0
                    if (noteSpeed.isInfinite()) noteSpeed = 50.0
                    if (noteSpeed.isNaN()) noteSpeed = 1.0
                    if (type == 2) noteSpeed = 1.0
                    // 上限放宽到 1e6（官谱 tap/drag 的 speed 出现过 500/1000 量级；Phira 原样保留）
                    noteSpeed = noteSpeed.coerceIn(0.0, 1.0e6)
                    val startMs = (startSec + offsetSec) * 1000.0
                    val endMs = (endSec + offsetSec) * 1000.0
                    lineNotes.add(
                        RecordedNote(
                            type = type,
                            startMs = Math.round(if (startMs < 0) 0.0 else startMs),
                            endMs = Math.round(if (endMs < startMs) startMs else endMs),
                            posX = posX.coerceIn(-1.0, 1.0).toFloat(),
                            pass = passOf(type),
                            lineId = li,
                            above = above,
                            fake = false,
                            speed = noteSpeed.toFloat(),
                            alpha = 1f,
                            visibleTime = 999999f
                        )
                    )
                    if (!above) backCount++
                    beatsForGrid.add(t)
                    denomsForGrid.add(32)
                    posForGrid.add(posX.coerceIn(-1.0, 1.0))
                }
            }
            totalNotes += lineNotes.size
            if (lineNotes.isEmpty()) emptyLines++
            lineNotes.sortBy { it.startMs }
            importLines.add(
                ImportedLine(
                    id = li,
                    name = parsed.name,
                    posPct = pose.pct,
                    moveX = pose.moveX,
                    moveY = pose.moveY,
                    rotate = pose.rotate,
                    speed = pose.speed,
                    alpha = pose.alpha,
                    keyframes = pose.keyframes,
                    notes = lineNotes
                )
            )
        }

        if (totalNotes == 0) throw IllegalArgumentException("谱面里没有音符")
        if (importLines.isEmpty()) throw IllegalArgumentException("谱面里没有可用的判定线")
        if (emptyLines > 0) warnings.add("有 $emptyLines 条判定线没有音符（表演用的装饰线），已按原样保留")
        if (backCount > 0) warnings.add("已识别 $backCount 个从下方落下的音符（notesBelow）")
        if (dropped > 0) warnings.add("已跳过 $dropped 个无法识别的音符")

        val segs = ArrayList<BpmSegment>()
        val distinctBpm = lineBpms.distinct().filter { it > 0 }.sorted()
        val bpm0 = distinctBpm.firstOrNull() ?: 120.0
        segs.add(BpmSegment(0L, bpm0))
        val bpmSegments = BpmTimeline.normalize(segs, bpm0)
        if (lineBpms.distinct().size > 1) {
            warnings.add(
                "官谱每条判定线各有自己的 BPM（共 " + lineBpms.distinct().size + " 种）：" +
                    "音符时间已按各自 BPM 换算成绝对毫秒，导出 RPE 时按首个 BPM 写 BPMList"
            )
        }

        // ---- 素材：官谱的包一般没有 info.txt，音频名靠文件名兜底 ----
        val musicEntry = pickEntry(names, info["Song"] ?: info["Music"], AUDIO_EXTS)
        val imageEntry = pickEntry(names, info["Picture"] ?: info["Image"], IMAGE_EXTS)
        if (musicEntry != null || imageEntry != null) {
            val want = setOfNotNull(musicEntry, imageEntry)
            runCatching {
                for (e in entries) {
                    val n = cleanName(e.name)
                    if (n in want) {
                        ZipReader.bytes(zipBytes, e)?.let { saveAsset(n, java.io.ByteArrayInputStream(it)) }
                    }
                }
            }.onFailure { warnings.add("素材提取失败：${it.message}") }
        }

        // ---- 噪域（blockAreaList）：官谱 4.0.0 起也带这个字段 ----
        // ★ 官谱的噪域结构和 RPE 完全不同（百分比区域 + 秒制时间 + 「终点+时刻」事件），
        //   必须走 parseAllPgr；用 parseAll 会把每一块都读成默认矩形 ⇒ 14355 块全部
        //   「永远存在、画在屏幕正中」，编辑页每帧画一万多个矩形直接卡死。
        val noiseAreas = NoiseArea.parseAllPgr(doc.optJSONArray("blockAreaList"), warnings)
        if (noiseAreas.isNotEmpty()) {
            warnings.add("已识别噪域 ${noiseAreas.size} 块（可在右侧「噪域」页签里编辑）")
        }

        val main = importLines.maxByOrNull { it.notes.size } ?: importLines.first()
        return ImportedChart(
            name = info["Name"]?.takeIf { it.isNotBlank() } ?: "导入的官谱",
            composer = info["Composer"] ?: info["Artist"] ?: "",
            charter = info["Charter"] ?: info["Designer"] ?: "",
            level = info["Level"] ?: "",
            bpm = bpm0,
            bpmChanges = 1,
            speed = main.speed.toDouble(),
            linePosPct = main.posPct.coerceIn(0, 100),
            lineMoveX = main.moveX,
            lineMoveY = main.moveY,
            lineRotate = main.rotate,
            lines = importLines,
            hLines = inferHLines(beatsForGrid, denomsForGrid, warnings),
            vLines = inferVLines(posForGrid, warnings),
            musicName = musicEntry,
            imageName = imageEntry,
            warnings = warnings,
            bpmSegments = bpmSegments,
            noiseAreas = noiseAreas
        )
    }

    // ------------------------------------------------------------------ 判定线

    /** 单条判定线：事件 + 原始音符 */
    private class ParsedLine(
        val name: String,
        val texture: String,
        /** RPE 的 attachUI（pause/score/level/combo/combonumber）：这些线只用来定位 UI，不画成判定线 */
        val attachUi: String,
        val moveX: LayerAnim,
        val moveY: LayerAnim,
        val rotate: LayerAnim,
        val speed: LayerAnim,
        /** 透明度 0~255 的动画（alphaEvents） */
        val alpha: LayerAnim,
        val rawNotes: List<RawNote>,
        /** 父线（RPE father；-1 = 无）与是否跟随父线旋转 */
        val father: Int,
        val rotWithFather: Boolean,
        /** ctrl 对象（原样保留，导出回写；渲染语义见 ValueTrack） */
        val ctrlY: List<CtrlEvent>,
        val ctrlPos: List<CtrlEvent>,
        val ctrlSize: List<CtrlEvent>,
        val ctrlAlpha: List<CtrlEvent>,
        /** 倾斜事件（度，毫秒） */
        val incline: List<InclineEvent>,
        val lineText: String = "",
        /**
         * RPE extended.textEvents 的**全部事件**（start/end 字符串 + 起止拍）。
         * [lineText] 只是"第一个非空值"，用来判断这条线是不是文字线、以及给编辑器显示；
         * 导出图片线时要把这份原样写回去（图片线常常挂 textEvents 当副标题）。
         */
        val textEvents: List<TextEvent> = emptyList(),
        /** RPE extended.scaleXEvents / scaleYEvents（逐事件缩放，控制判定线/图片/文字） */
        val scaleXEv: Anim = Anim(emptyList()),
        val scaleYEv: Anim = Anim(emptyList()),
        /** RPE zOrder（绘制图层序），原样保留供导出回写 */
        val zOrder: Int = 0,
        /**
         * 官谱（Phigros Official JSON）专用：单轨采样模型。
         *
         * 官谱的事件只有「移动 / 旋转 / 透明度 / 速度」四种，没有缓动、没有 eventLayers、
         * 没有 ctrl 对象——所以硬套 RPE 的 LayerAnim 只能是单层，
         * 而单层 `toAnim` 会把事件结束帧标成 HOLD（保持），官谱是**线性插值**，会整条线卡住。
         * 这里单独存一份「按时间采样」，由 `buildPose` 走采样分支。
         *   - [pgrX] / [pgrY] 已经是归一化值（-1..1），y 向上为正
         *   - [pgrRot] 度；[pgrAlpha] 0..1；[pgrSpeed] 倍率
         *   - x/y/rotate 的采样点首尾相接（段尾值与下一段段首值同一时刻 ⇒ 跳变，天然正确）；
         *     alpha/speed 是分段常量（[PezImporter.stepPts]），段内不插值
         */
        val pgrX: List<Pair<Double, Double>>? = null,
        val pgrY: List<Pair<Double, Double>>? = null,
        val pgrRot: List<Pair<Double, Double>>? = null,
        val pgrAlpha: List<Pair<Double, Double>>? = null,
        val pgrSpeed: List<Pair<Double, Double>>? = null,
        /** 官谱单线 BPM（秒）。RPE 谱为 null（走全局 BPMList） */
        val pgrBpm: Double? = null
    )

    private class RawNote(
        val type: Int,
        val startSec: Double,
        val endSec: Double,
        val startBeats: Double,
        val startDenom: Int,
        val above: Boolean,
        val positionX: Double,
        val yOffset: Double,
        val fake: Boolean,
        val alpha: Double,
        val size: Float,
        val speed: Float,
        val visibleTime: Double,
        val judgeArea: Float = 1f,
        val tint: Int = -1,
        val tintHit: Int = -1,
        val hitsound: String = ""
    )

    private fun parseLine(line: JSONObject, bpm: BpmMap, offsetSec: Double): ParsedLine {
        // ★ Texture = "line.png" 就是**普通判定线**（Phira RPE 语义：line.png = 内建线材质）。
        //   以前原样塞进 texture 字段 ⇒ Line.kind = Image ⇒ 整条线被当成"图片线素材"、
        //   不让放音符 —— 用户看到的「判定线全部被识别成图片线」就是它。
        //   只有指向**别的**图片（可带子目录，取文件名）才是真正的图片线。
        val texture = line.optString("Texture", "").let { if (it == "line.png") "" else it.substringAfterLast('/') }

        val moveX = ArrayList<Anim>()
        val moveY = ArrayList<Anim>()
        val rotate = ArrayList<Anim>()
        val speed = ArrayList<Anim>()
        val alphaEv = ArrayList<Anim>()

        val layers = line.optJSONArray("eventLayers")
        if (layers != null) for (i in 0 until layers.length()) {
            val l = layers.optJSONObject(i) ?: continue
            toAnim(bpm, l.optJSONArray("moveXEvents")).takeIf { !it.isEmpty }?.let { moveX.add(it) }
            toAnim(bpm, l.optJSONArray("moveYEvents")).takeIf { !it.isEmpty }?.let { moveY.add(it) }
            toAnim(bpm, l.optJSONArray("rotateEvents")).takeIf { !it.isEmpty }?.let { rotate.add(it) }
            toAnim(bpm, l.optJSONArray("speedEvents")).takeIf { !it.isEmpty }?.let { speed.add(it) }
            toAnim(bpm, l.optJSONArray("alphaEvents")).takeIf { !it.isEmpty }?.let { alphaEv.add(it) }
        }

        // ---- 父线 / ctrl 对象 / incline（Phira parse_judge_line 的对应字段）----
        val father = line.optInt("father", -1).takeIf { it >= -1 } ?: -1
        val rotWithFather = line.optBoolean("rotateWithFather", false)

        // ctrl 事件：{ easing, x, <alpha|pos|size|y> }；x 是"音符到线的落点距离"（RPE px）
        // Phira 短路：恰好 2 个事件、第 1 个 easing=1 且值=1 → 视为默认（恒 1）
        fun parseCtrl(key: String, field: String): List<CtrlEvent> {
            val arr = line.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<CtrlEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val v = o.optDouble(field, Double.NaN)
                if (!v.isFinite()) continue
                val x = o.optDouble("x", 0.0).takeIf { it.isFinite() } ?: 0.0
                out.add(CtrlEvent(x.toFloat(), v.toFloat(), o.optInt("easing", 1)))
            }
            if (out.size == 2 && out[0].easing == 1 && Math.abs(out[0].value - 1f) < 1e-4f) return emptyList()
            return out
        }
        val ctrlY = parseCtrl("yControl", "y")
        val ctrlPos = parseCtrl("posControl", "pos")
        val ctrlSize = parseCtrl("sizeControl", "size")
        val ctrlAlpha = parseCtrl("alphaControl", "alpha")

        // incline：标准 RPE 事件（startTime/endTime/start/end/easingType），单位度
        val incline = ArrayList<InclineEvent>()
        line.optJSONObject("extended")?.optJSONArray("inclineEvents")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val st = o.optJSONArray("startTime") ?: continue
                val et = o.optJSONArray("endTime") ?: st
                incline.add(
                    InclineEvent(
                        Math.round((bpm.seconds(tripleBeats(st)) + offsetSec) * 1000.0),
                        Math.round((bpm.seconds(tripleBeats(et)) + offsetSec) * 1000.0),
                        (o.optDouble("start", 0.0).takeIf { it.isFinite() } ?: 0.0).toFloat(),
                        (o.optDouble("end", 0.0).takeIf { it.isFinite() } ?: 0.0).toFloat(),
                        o.optInt("easingType", 1)
                    )
                )
            }
        }

        // 文字线（extended.textEvents）与逐事件缩放（scaleX/scaleYEvents）。
        // 只对 Texture = line.png 的线有效 —— 和 Phira 一致：text/paint 判定都要求挂在 line.png 上
        val extObj = line.optJSONObject("extended")
        val isLineTex = texture == "" || texture == "line.png"
        var lineText = ""
        // ★ 文字事件对**所有** Texture 都读：图片线（Texture ≠ line.png）常拿它当副标题/贴纸，
        //   只存"第一个非空 start"的话，第一条是空串就整条线一个字的记录都没有，导回去字就没了。
        //   lineText 仍然只给 line.png 的线用（那才是"文字线"的类型判据）。
        val textEvents = ArrayList<TextEvent>()
        extObj?.optJSONArray("textEvents")?.let { tarr ->
            for (i in 0 until tarr.length()) {
                val o = tarr.optJSONObject(i) ?: continue
                val sv = o.optString("start", "")
                val ev = o.optString("end", sv)
                if (isLineTex && lineText.isEmpty() && sv.isNotEmpty()) lineText = sv
                val st = o.optJSONArray("startTime") ?: continue
                val et = o.optJSONArray("endTime") ?: st
                textEvents.add(
                    TextEvent(
                        Math.round((bpm.seconds(tripleBeats(st)) + offsetSec) * 1000.0),
                        Math.round((bpm.seconds(tripleBeats(et)) + offsetSec) * 1000.0),
                        sv, ev,
                        o.optInt("easingType", 0),
                        o.optString("font", ""),
                        o.optInt("linkgroup", 0)
                    )
                )
            }
        }
        // scaleXEvents / scaleYEvents 是**事件数组**（每个事件 start→end 缓动），不是单一值。
        // 以前只取了第一个事件的 start，导致"改缩放全部应用"、关键帧缩放进不来。
        val scaleXEv = toAnim(bpm, extObj?.optJSONArray("scaleXEvents"), 1.0)
        val scaleYEv = toAnim(bpm, extObj?.optJSONArray("scaleYEvents"), 1.0)

        val notes = ArrayList<RawNote>()
        val arr = line.optJSONArray("notes")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = o.optJSONArray("startTime") ?: continue
            val et = o.optJSONArray("endTime") ?: st
            val startSec = bpm.seconds(tripleBeats(st))
            // 颜色：RPE 用 [r, g, b] 数组；也兼容 Phiedit 读的旧字段名 color
            fun rgbOf(key: String): Int {
                val a = o.optJSONArray(key) ?: return -1
                if (a.length() < 3) return -1
                val r = a.optDouble(0, -1.0).toInt().coerceIn(0, 255)
                val g = a.optDouble(1, -1.0).toInt().coerceIn(0, 255)
                val b = a.optDouble(2, -1.0).toInt().coerceIn(0, 255)
                return (r shl 16) or (g shl 8) or b
            }
            var tint = rgbOf("tint")
            if (tint < 0) tint = rgbOf("color")
            notes.add(
                RawNote(
                    type = o.optInt("type", 1),
                    startSec = startSec,
                    endSec = bpm.seconds(tripleBeats(et)),
                    startBeats = tripleBeats(st),
                    startDenom = st.optInt(2, 1),
                    above = o.optInt("above", 1) == 1,
                    positionX = o.optDouble("positionX", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    yOffset = o.optDouble("yOffset", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    fake = o.optInt("isFake", 0) != 0,
                    alpha = o.optDouble("alpha", 255.0).takeIf { it.isFinite() } ?: 255.0,
                    size = (o.optDouble("size", 1.0).takeIf { it.isFinite() } ?: 1.0).toFloat(),
                    speed = (o.optDouble("speed", 1.0).takeIf { it.isFinite() } ?: 1.0).toFloat(),
                    visibleTime = o.optDouble("visibleTime", 999999.0).takeIf { it.isFinite() } ?: 999999.0,
                    judgeArea = (o.optDouble("judgeArea", 1.0).takeIf { it.isFinite() } ?: 1.0)
                        .toFloat().coerceAtLeast(0f),
                    tint = tint,
                    tintHit = rgbOf("tintHitEffects"),
                    hitsound = o.optString("hitsound", "")
                )
            )
        }
        return ParsedLine(
            line.optString("Name", ""),
            texture, line.optString("attachUI", "").trim(),
            LayerAnim(moveX), LayerAnim(moveY), LayerAnim(rotate), LayerAnim(speed),
            LayerAnim(alphaEv), notes,
            father, rotWithFather, ctrlY, ctrlPos, ctrlSize, ctrlAlpha, incline,
            lineText, textEvents, scaleXEv, scaleYEv,
            line.optInt("zOrder", 0)
        )
    }

    private class Pose(
        val pct: Int,
        val moveX: Float,
        val moveY: Float,
        val rotate: Float,
        val speed: Float,
        val alpha: Float,
        /** 没有关键帧时的静态缩放（RPE scaleXEvents/scaleYEvents 的常值） */
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val keyframes: List<LineKeyframe>
    )

    /**
     * 事件 → 本项目姿态 + 流速关键帧：
     *  - 屏幕 y = 判定线基准(百分比) + moveY归一化 × 半屏高
     *  - RPE moveY 向上为正 → 百分比 pct = 50 - moveY/9（与 PezExporter 的 (50-pct)×9 互逆）
     *  - 归一化：moveX / 675、moveY / 450、rotate 取反（Phira 里 rotation = 事件值 × -1）
     *  - 流速：每个采样点的 speed 值写进同一个关键帧 → 导出时就是 RPE 的 speedEvents（变速）
     */
    private fun buildPose(line: ParsedLine, offsetSec: Double, warnings: MutableList<String>): Pose {
        // 官谱（Phigros Official JSON）：单轨采样，走单独的分支
        if (line.pgrX != null) return buildPosePgr(line, offsetSec, warnings)

        val xAt = { t: Double -> line.moveX.at(t) }            // RPE 单位（像素）
        val yAt = { t: Double -> line.moveY.at(t) }            // RPE 单位
        val rAt = { t: Double -> line.rotate.at(t) }           // 度
        val hasSpeedEvents = !line.speed.isEmpty
        val sAt = { t: Double -> if (!hasSpeedEvents) 1f else safeSpeed(line.speed.at(t)) } // 流速倍率
        val hasAlphaEvents = !line.alpha.isEmpty
        val aAt = { t: Double ->
            // 负 alpha 在 Phira 里是"连音符一起隐藏"的特殊值（PE alpha extension），不能一律夹成 0
            if (!hasAlphaEvents) 1f else (line.alpha.at(t) / 255.0).toFloat()
        }
        // 逐事件缩放（RPE extended.scaleXEvents / scaleYEvents）：没有事件时默认 1
        val hasScaleXEv = !line.scaleXEv.isEmpty
        val hasScaleYEv = !line.scaleYEv.isEmpty
        val sxAt = { t: Double -> if (!hasScaleXEv) 1f else safeScale(line.scaleXEv.at(t)) }
        val syAt = { t: Double -> if (!hasScaleYEv) 1f else safeScale(line.scaleYEv.at(t)) }

        val y0 = yAt(0.0)
        // 不夹到 0~100：Phira 里 moveY 可以把判定线放到屏幕外（负 pct / >100），夹了会把它钉在屏幕边缘、
        // 出现"长条特效出现后一直留在边上不消失"的问题
        val pct = Math.round(50.0 - y0 / 9.0).toInt().coerceIn(-2000, 2000)
        // y 归一化：pct/100 + yNorm/2 = 0.5 - v/900  →  yNorm = 1 - v/450 - pct/50
        val yNorm = { t: Double -> (1.0 - yAt(t) / 450.0 - pct / 50.0).toFloat() }
        val xNorm = { t: Double -> (xAt(t) / 675.0).toFloat() }
        val rot = { t: Double -> (-rAt(t)).toFloat() }

        val times = ArrayList<Double>()
        line.moveX.collectTimes(times)
        line.moveY.collectTimes(times)
        line.rotate.collectTimes(times)
        line.speed.collectTimes(times)
        line.alpha.collectTimes(times)
        line.scaleXEv.collectTimes(times)
        line.scaleYEv.collectTimes(times)
        times.add(0.0)
        times.sort()

        val distinct = ArrayList<Double>()
        for (t in times) {
            if (t < 0) continue
            if (distinct.isEmpty() || t - distinct.last() > 1e-6) distinct.add(t)
        }

        val mx0 = xNorm(0.0); val my0 = yNorm(0.0); val r0 = rot(0.0); val s0 = sAt(0.0); val a0 = aAt(0.0)
        val sx0 = sxAt(0.0); val sy0 = syAt(0.0)
        val isStatic = distinct.size <= 1 || distinct.all {
            Math.abs(xNorm(it) - mx0) < 1e-5 && Math.abs(yNorm(it) - my0) < 1e-5 &&
                Math.abs(rot(it) - r0) < 1e-5 && Math.abs(sAt(it) - s0) < 1e-4 &&
                Math.abs(aAt(it) - a0) < 1e-4 &&
                Math.abs(sxAt(it) - sx0) < 1e-4 && Math.abs(syAt(it) - sy0) < 1e-4
        }
        if (isStatic) return Pose(pct, mx0, my0, r0, s0, a0, sx0, sy0, emptyList())

        // 事件太密时**均匀抽稀**（保留首尾），而不是砍掉后半段 —— 否则动画放到一半就定住了
        val idxs: List<Int> = if (distinct.size <= MAX_KEYFRAMES) {
            distinct.indices.toList()
        } else {
            warnings.add(
                "判定线关键帧过多（" + distinct.size + " 个），已均匀抽稀到 " + MAX_KEYFRAMES +
                    " 个（保留首尾，动画形状不变）"
            )
            List(MAX_KEYFRAMES) { k ->
                (k.toLong() * (distinct.size - 1) / (MAX_KEYFRAMES - 1)).toInt()
            }.distinct()
        }
        val kfs = ArrayList<LineKeyframe>(idxs.size * 2)
        // 上一个已写入关键帧的时间（秒），保证时间递增、跳变帧不重复
        var lastT = -1.0
        for (i in idxs) {
            val t = distinct[i]
            // Phira 事件结束后到下一段开始前是「保持」（Hold 关键帧），不是线性过渡。
            // 这里在跳变点前 1ms 补一个"里侧值"关键帧，渲染/导出时就是先保持、再瞬间跳变。
            val tl = (t - HOLD_GAP_MS / 1000.0).coerceAtLeast(0.0)
            if (tl > lastT + 1e-9 && tl < t - 1e-9) {
                val xl = xNorm(tl); val yl = yNorm(tl); val rl = rot(tl)
                val sl = sAt(tl); val al = aAt(tl)
                val sxl = sxAt(tl); val syl = syAt(tl)
                val jumped = Math.abs(xl - xNorm(t)) > 1e-5 || Math.abs(yl - yNorm(t)) > 1e-5 ||
                    Math.abs(rl - rot(t)) > 1e-5 || Math.abs(sl - sAt(t)) > 1e-4 ||
                    Math.abs(al - aAt(t)) > 1e-5 ||
                    Math.abs(sxl - sxAt(t)) > 1e-4 || Math.abs(syl - syAt(t)) > 1e-4
                if (jumped) {
                    kfs.add(
                        LineKeyframe(
                            Math.round((tl + offsetSec) * 1000.0),
                            xl, yl, rl, sl, al, sxl, syl
                        )
                    )
                    lastT = tl
                }
            }
            kfs.add(
                LineKeyframe(
                    Math.round((t + offsetSec) * 1000.0),
                    xNorm(t), yNorm(t), rot(t), sAt(t), aAt(t), sxAt(t), syAt(t)
                )
            )
            lastT = t
        }
        return Pose(pct, mx0, my0, r0, s0, a0, sx0, sy0, kfs)
    }

    private fun safeScale(v: Double): Float =
        if (!v.isFinite() || v <= 0.0) 1f else v.coerceIn(0.02, 20.0).toFloat()
    private fun safeSpeed(v: Double): Float =
        if (!v.isFinite() || v < 0.0) 1f else v.coerceIn(0.0, 100.0).toFloat()

    /**
     * 官谱（Phigros Official JSON）→ 本项目姿态。
     *
     * 和 RPE 的差别（对照 Phira `prpr/src/parse/pgr.rs`）：
     *  - 坐标本来就是归一化的（x、y 都是 -1..1），y 向上为正（和 Phira 的 translation.y 一致）；
     *    但本项目的 moveY 是「相对 posPct、向下为正」的偏移，所以要写成 `y0 − y(t)` 的残量；
     *  - 事件之间是**线性插值**，首尾相接时下一段的值会瞬间接管 ⇒ 在段边界前 1ms 补一个跳变帧；
     *  - 透明度 0..1；没有缩放、没有父线、没有 ctrl，缩放恒 1；
     *  - 流速事件的 value 是官谱单位（≈ 60% 屏高/秒），进本项目前要乘 [PGR_SPEED_SCALE]；
     *  - 旋转是逆时针正，和本项目内部约定一致，**不**像 RPE 那样取反。
     */
    private fun buildPosePgr(line: ParsedLine, offsetSec: Double, warnings: MutableList<String>): Pose {
        val sx = line.pgrX ?: emptyList<Pair<Double, Double>>()
        val sy = line.pgrY ?: emptyList()
        val sr = line.pgrRot ?: emptyList()
        val sa = line.pgrAlpha ?: emptyList()
        val ss = line.pgrSpeed ?: emptyList()

        fun eval(pts: List<Pair<Double, Double>>, t: Double): Double {
            if (pts.isEmpty()) return 0.0
            if (t < pts[0].first) return pts[0].second
            val last = pts[pts.size - 1]
            if (t >= last.first) return last.second
            // 同一时刻可能有多个点（跳变）：取**后一个** ⇒ 边界那一刻新值立刻生效
            var lo = 0
            var hi = pts.size - 1
            while (lo + 1 < hi) {
                val mid = (lo + hi) / 2
                if (pts[mid].first <= t) lo = mid else hi = mid
            }
            val a = pts[lo]
            val b = pts[hi]
            val span = b.first - a.first
            if (span <= 1e-9) return b.second
            return a.second + (b.second - a.second) * ((t - a.first) / span)
        }

        /** [t] 时刻的点集里是否有「两个不同的值」（= 真正的跳变）；首尾相接的连续段已被合并成一个点 */
        fun sameAt(pts: List<Pair<Double, Double>>, t: Double): Boolean {
            var found = false
            var first = 0.0
            for (p in pts) {
                if (Math.abs(p.first - t) > 1e-9) continue
                if (!found) { first = p.second; found = true }
                else if (Math.abs(p.second - first) > 1e-9) return false
            }
            return true
        }

        /** 「t 之前一点点」的值：段边界上用 t 减 1ms 插值，零长度段/无数据时退回最近的点 */
        fun evalJustBefore(pts: List<Pair<Double, Double>>, t: Double): Double {
            if (pts.isEmpty()) return 0.0
            val tl = t - HOLD_GAP_MS / 1000.0
            if (tl <= pts[0].first) return pts[0].second
            // 严格小于 t 的最后一个点（跳过 t 处的重合点）
            var lo = -1
            for (k in 0 until pts.size) {
                if (pts[k].first < t - 1e-9) lo = k else break
            }
            if (lo < 0) return pts[0].second
            if (lo + 1 >= pts.size) return pts[lo].second
            val a = pts[lo]
            val b = pts[lo + 1]
            val span = b.first - a.first
            if (span <= 1e-9) return a.second
            return a.second + (b.second - a.second) * ((tl - a.first) / span).coerceIn(0.0, 1.0)
        }

        val xAt = { t: Double -> eval(sx, t) }
        val yAt = { t: Double -> eval(sy, t) }
        val rAt = { t: Double -> eval(sr, t) }
        // ★ 官谱里**没有** alpha 事件 = 完全不透明（Phigros 默认 a=1）；
        //   speed 事件同理，默认流速是官谱单位的 1，换成本项目单位要乘 [PGR_SPEED_SCALE]。
        //   以前对空轨道一律返回 0 ⇒ 没写 disappearEvents 的线整条隐形（"判定线全没了"的元凶之一）。
        val aAt = { t: Double -> if (sa.isEmpty()) 1.0 else eval(sa, t) }
        val sAt = { t: Double -> if (ss.isEmpty()) PGR_SPEED_SCALE else eval(ss, t) }

        val y0 = yAt(0.0)
        // x 是绝对横向坐标（0 = 判定线中心），乘半屏宽即可
        val xNorm = { t: Double -> xAt(t).coerceIn(-4.0, 4.0).toFloat() }
        // ★ 官谱 y 是**绝对位置**（向上为正，0 = 屏幕底部）；本项目的 moveY 是
        //   「相对 posPct 的偏移、向下为正」（和 RPE 分支一致）。两者必须换算：
        //   关键帧写 (y0 − y(t))，t=0 时为 0（位置完全由 posPct 表示，不会被重复叠加），
        //   y 变大（线往上走）⇒ 值为负 ⇒ 屏幕上向上移动。
        //   以前直接把绝对 y 塞进关键帧，同时 posPct 又按 y0 算了一遍 ⇒ 所有判定线
        //   在 t=0 都被吸到屏幕正中、只显示相对位移，整张官谱的纵向布局被压扁。
        val yNorm = { t: Double -> (y0 - yAt(t)).coerceIn(-4.0, 4.0).toFloat() }
        // 官谱 rotation 是**逆时针**正；本项目 rotate 也是逆时针正（绘制时 canvas.rotate(-rot)），
        // 所以直接用原值。RPE 的正角是顺时针，只有 RPE 分支才需要取反。
        val rot = { t: Double -> rAt(t).toFloat() }
        val alpha = { t: Double -> aAt(t).coerceIn(0.0, 1.0).toFloat() }
        // ★ 官谱流速保符号保幅值（500/1000/负数都是有效数据），只挡非有限值 —— 不能用 RPE 的 safeSpeed
        //   （它把负值改成 1、幅值夹到 0..100，H 积分全错，见 pgrSpeedSegs 处的注释）。
        val speed = { t: Double -> clampStoreSpeed(sAt(t).toFloat()) }

        // 竖直位置（设置页用的判定线位置百分比）：y = 1 在屏幕顶部、y = -1 在底部
        val pct = Math.round(50.0 - y0 * 50.0).toInt().coerceIn(-2000, 2000)

        val times = ArrayList<Double>()
        for (pts in listOf(sx, sy, sr, sa, ss)) for (p in pts) times.add(p.first)
        times.add(0.0)
        times.sort()

        val distinct = ArrayList<Double>()
        for (t in times) {
            if (t < 0) continue
            if (distinct.isEmpty() || t - distinct.last() > 1e-7) distinct.add(t)
        }

        val mx0 = xNorm(0.0); val my0 = yNorm(0.0); val r0 = rot(0.0)
        val s0 = speed(0.0); val a0 = alpha(0.0)
        val isStatic = distinct.size <= 1 || distinct.all {
            Math.abs(xNorm(it) - mx0) < 1e-5 && Math.abs(yNorm(it) - my0) < 1e-5 &&
                Math.abs(rot(it) - r0) < 1e-5 && Math.abs(speed(it) - s0) < 1e-4 &&
                Math.abs(alpha(it) - a0) < 1e-4
        }
        if (isStatic) return Pose(pct, mx0, my0, r0, s0, a0, 1f, 1f, emptyList())

        val idxs: List<Int> = if (distinct.size <= MAX_KEYFRAMES) {
            distinct.indices.toList()
        } else {
            warnings.add(
                "判定线关键帧过多（" + distinct.size + " 个），已均匀抽稀到 " + MAX_KEYFRAMES +
                    " 个（保留首尾，动画形状不变）"
            )
            List(MAX_KEYFRAMES) { k ->
                (k.toLong() * (distinct.size - 1) / (MAX_KEYFRAMES - 1)).toInt()
            }.distinct()
        }

        // 斜率阈值：相邻两帧之间，如果值是"跳"过去而不是"斜"过去，就补一个 1ms 前的保持帧。
        // 以前这里用一张全局跳变时刻表（任何一条轨道的段首都会给**所有**轨道加帧），
        // 66 条线就能堆出 10 万+ 关键帧；现在改成唯一定位：只在真正的不连续处插帧。
        val kfs = ArrayList<LineKeyframe>(idxs.size * 2)
        var lastT = -1.0
        for (i in idxs) {
            val t = distinct[i]
            if (t < 0) continue
            val tl = (t - HOLD_GAP_MS / 1000.0).coerceAtLeast(0.0)
            if (i != idxs.first() && tl > lastT + 1e-9 && tl < t - 1e-9) {
                // 边界前这一刻的真实值（第一段还在斜着走 ⇒ 0.999，不是 0）
                // 注意 y 要换算成和 [yNorm] 相同的「相对 posPct、向下为正」的残量
                val curX = evalJustBefore(sx, t)
                val curY = y0 - evalJustBefore(sy, t)
                val curR = evalJustBefore(sr, t)
                val curA = if (sa.isEmpty()) 1.0 else evalJustBefore(sa, t)
                val curS = if (ss.isEmpty()) PGR_SPEED_SCALE else evalJustBefore(ss, t)
                // 只有在**真正不连续**（段尾值 ≠ 段首值）时才补保持帧。
                // 判据直接看数据：在点集里，t 时刻之前最后一个点的值 与 t 时刻（或之后）第一个点的值 是否不同。
                // 首尾相接的连续段在点集里已经被合并成同一个点 ⇒ 永远相等 ⇒ 天然不补帧（不会爆关键帧）；
                // 真正的跳变（1.0 → 0.25）在 t 处有两个不同值 ⇒ 补一帧保持。
                val jumpX = !sameAt(sx, t)
                val jumpY = !sameAt(sy, t)
                val jumpR = !sameAt(sr, t)
                val jumpA = !sa.isEmpty() && !sameAt(sa, t)
                val jumpS = !ss.isEmpty() && !sameAt(ss, t)
                if (jumpX || jumpY || jumpR || jumpA || jumpS) {
                    kfs.add(
                        LineKeyframe(
                            Math.round((tl + offsetSec) * 1000.0),
                            curX.coerceIn(-4.0, 4.0).toFloat(),
                            curY.coerceIn(-4.0, 4.0).toFloat(),
                            curR.toFloat(),
                            clampStoreSpeed(curS.toFloat()),
                            curA.coerceIn(0.0, 1.0).toFloat(),
                            1f, 1f
                        )
                    )
                    lastT = tl
                }
            }
            kfs.add(
                LineKeyframe(
                    Math.round((t + offsetSec) * 1000.0),
                    xNorm(t), yNorm(t), rot(t), speed(t), alpha(t), 1f, 1f
                )
            )
            lastT = t
        }
        return Pose(pct, mx0, my0, r0, s0, a0, 1f, 1f, kfs)
    }

    // ------------------------------------------------------------------ 量化推断

    private fun inferHLines(beats: List<Double>, denoms: List<Int>, warnings: MutableList<String>): Int {
        if (beats.isEmpty()) return 4
        // 1) 优先沿用谱师自己的量化格：所有时间分母的最小公倍数（如全 4 分格 → 4）
        var lcm = 1L
        for (d in denoms) {
            if (d <= 0) continue
            lcm = lcm / gcd(lcm, d.toLong()) * d
            if (lcm > MAX_HLINES) break
        }
        if (lcm in 1..MAX_HLINES.toLong() && beats.all { nearInt(it * lcm) }) return lcm.toInt()
        // 2) 分母不可用（太大/不自洽）→ 取能精确表示所有时间的最粗格
        for (h in H_CANDIDATES) {
            if (h > MAX_HLINES) break
            if (beats.all { nearInt(it * h) }) return h
        }
        // 3) 都没命中（非标准分母）：取最密格（只影响编辑网格，导出按原时间写出）
        warnings.add("音符时间不在标准横线格上：横线数取 $MAX_HLINES（只影响编辑网格，导出按原时间写出）")
        return MAX_HLINES
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun inferVLines(pos: List<Double>, warnings: MutableList<String>): Int {
        if (pos.isEmpty()) return 8
        for (v in V_CANDIDATES) if (pos.all { nearInt((it + 1.0) * v / 2.0) }) return v
        warnings.add("音符位置不在标准竖线格上：竖线数取 16（只影响编辑网格，导出按原位置写出）")
        return 16
    }

    private fun nearInt(v: Double): Boolean = Math.abs(v - Math.round(v)) < 1e-4

    private fun passOf(type: Int): Int = when (type) {
        4 -> 2      // Drag → 第二阶段
        3 -> 3      // Flick → 第三阶段
        2 -> 4      // Hold → 第四次
        else -> 1   // Tap
    }

    // ------------------------------------------------------------------ 事件求值

    /** 单个事件动画（一条 eventLayer 里的一组事件，Phira `parse_events` 的 keyframe 模型） */
    private class Anim(val kfs: List<KF>) {
        class KF(val t: Double, val v: Double, val ease: Int)

        val isEmpty: Boolean get() = kfs.isEmpty()

        fun at(t: Double): Double {
            if (kfs.isEmpty()) return 0.0
            if (t <= kfs[0].t) return kfs[0].v
            val last = kfs[kfs.size - 1]
            if (t >= last.t) return last.v
            var lo = 0
            var hi = kfs.size - 1
            while (lo + 1 < hi) {
                val mid = (lo + hi) / 2
                if (kfs[mid].t <= t) lo = mid else hi = mid
            }
            val a = kfs[lo]
            val b = kfs[hi]
            val span = b.t - a.t
            if (span <= 1e-9) return b.v
            // Phira 的 Hold 关键帧：在下一段开始前保持原值（事件之间的空档不能线性过渡）
            if (a.ease == HOLD_EASE) return a.v
            val x = ((t - a.t) / span).coerceIn(0.0, 1.0)
            return a.v + (b.v - a.v) * Ease.of(a.ease, x)
        }

        /** 采样点：所有关键帧时间 + 非线性段的中途采样（线性关键帧近似曲线） */
        fun collectTimes(out: MutableList<Double>) {
            for (i in kfs.indices) {
                out.add(kfs[i].t)
                if (i + 1 < kfs.size) {
                    val a = kfs[i]
                    val b = kfs[i + 1]
                    if (b.t - a.t > 1e-9 && Ease.isNonLinear(a.ease)) {
                        for (k in 1..3) out.add(a.t + (b.t - a.t) * k / 4.0)
                    }
                }
            }
        }
    }

    /** 多层 eventLayer：Phira 里是相加 */
    private class LayerAnim(val layers: List<Anim>) {
        val isEmpty: Boolean get() = layers.isEmpty()
        fun at(t: Double): Double = if (layers.isEmpty()) 0.0 else layers.sumOf { it.at(t) }
        fun collectTimes(out: MutableList<Double>) { layers.forEach { it.collectTimes(out) } }
    }

    private fun toAnim(bpm: BpmMap, arr: JSONArray?, def: Double = 0.0): Anim {
        if (arr == null || arr.length() == 0) return Anim(emptyList())
        class Ev(val t0: Double, val t1: Double, val v0: Double, val v1: Double, val ease: Int)
        val evs = ArrayList<Ev>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = o.optJSONArray("startTime") ?: continue
            val et = o.optJSONArray("endTime") ?: st
            evs.add(
                Ev(
                    bpm.seconds(tripleBeats(st)),
                    bpm.seconds(tripleBeats(et)),
                    o.optDouble("start", def).takeIf { it.isFinite() } ?: def,
                    o.optDouble("end", def).takeIf { it.isFinite() } ?: def,
                    o.optInt("easingType", 0)
                )
            )
        }
        evs.sortBy { it.t0 }
        val kfs = ArrayList<Anim.KF>(evs.size * 2)
        for (e in evs) {
            kfs.add(Anim.KF(e.t0, e.v0, e.ease))
            // 结束帧用 Hold（Phira `Keyframe::new(t_end, e.end, 0)`），不是线性
            kfs.add(Anim.KF(e.t1, e.v1, HOLD_EASE))
        }
        return Anim(kfs)
    }

    /** RPE easingType（对应 Phira 的 RPE_TWEEN_MAP，0/1 = 线性）。internal：ValueTrack（ctrl/incline 轨道）也用它 */
    internal object Ease {
        fun isNonLinear(t: Int): Boolean = t != 0 && t != 1 && t != HOLD_EASE

        fun of(type: Int, x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return when (type) {
                2 -> sinOut(t); 3 -> sinIn(t); 6 -> sinInOut(t)
                4 -> quadOut(t); 5 -> quadIn(t); 7 -> quadInOut(t)
                8 -> cubicOut(t); 9 -> cubicIn(t); 12 -> cubicInOut(t)
                10 -> quartOut(t); 11 -> quartIn(t); 13 -> quartInOut(t)
                14 -> quintOut(t); 15 -> quintIn(t)
                16 -> expoOut(t); 17 -> expoIn(t)
                18 -> circOut(t); 19 -> circIn(t); 22 -> circInOut(t)
                20 -> backOut(t); 21 -> backIn(t); 23 -> backInOut(t)
                24 -> elasticOut(t); 25 -> elasticIn(t); 29 -> elasticInOut(t)
                26 -> bounceOut(t); 27 -> bounceIn(t); 28 -> bounceInOut(t)
                else -> t
            }
        }

        private fun sinIn(t: Double) = 1.0 - Math.cos(t * Math.PI / 2.0)
        private fun sinOut(t: Double) = Math.sin(t * Math.PI / 2.0)
        private fun sinInOut(t: Double) = -(Math.cos(Math.PI * t) - 1.0) / 2.0

        private fun quadIn(t: Double) = t * t
        private fun quadOut(t: Double) = 1.0 - (1.0 - t) * (1.0 - t)
        private fun quadInOut(t: Double) = if (t < 0.5) 2.0 * t * t else 1.0 - Math.pow(-2.0 * t + 2.0, 2.0) / 2.0

        private fun cubicIn(t: Double) = t * t * t
        private fun cubicOut(t: Double) = 1.0 - Math.pow(1.0 - t, 3.0)
        private fun cubicInOut(t: Double) =
            if (t < 0.5) 4.0 * t * t * t else 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0

        private fun quartIn(t: Double) = t * t * t * t
        private fun quartOut(t: Double) = 1.0 - Math.pow(1.0 - t, 4.0)
        private fun quartInOut(t: Double) =
            if (t < 0.5) 8.0 * t * t * t * t else 1.0 - Math.pow(-2.0 * t + 2.0, 4.0) / 2.0

        private fun quintIn(t: Double) = t * t * t * t * t
        private fun quintOut(t: Double) = 1.0 - Math.pow(1.0 - t, 5.0)

        private fun expoIn(t: Double) = if (t <= 0.0) 0.0 else Math.pow(2.0, 10.0 * t - 10.0)
        private fun expoOut(t: Double) = if (t >= 1.0) 1.0 else 1.0 - Math.pow(2.0, -10.0 * t)

        private fun circIn(t: Double) = 1.0 - Math.sqrt((1.0 - t * t).coerceAtLeast(0.0))
        private fun circOut(t: Double) = Math.sqrt((1.0 - (t - 1.0) * (t - 1.0)).coerceAtLeast(0.0))
        private fun circInOut(t: Double) = if (t < 0.5) {
            (1.0 - Math.sqrt((1.0 - 4.0 * t * t).coerceAtLeast(0.0))) / 2.0
        } else {
            (Math.sqrt((1.0 - Math.pow(-2.0 * t + 2.0, 2.0)).coerceAtLeast(0.0)) + 1.0) / 2.0
        }

        private const val C1 = 1.70158
        private const val C2 = C1 * 1.525
        private const val C3 = C1 + 1.0
        private fun backIn(t: Double) = C3 * t * t * t - C1 * t * t
        private fun backOut(t: Double) = 1.0 + C3 * Math.pow(t - 1.0, 3.0) + C1 * Math.pow(t - 1.0, 2.0)
        private fun backInOut(t: Double) = if (t < 0.5) {
            Math.pow(2.0 * t, 2.0) * ((C2 + 1.0) * 2.0 * t - C2) / 2.0
        } else {
            (Math.pow(2.0 * t - 2.0, 2.0) * ((C2 + 1.0) * (t * 2.0 - 2.0) + C2) + 2.0) / 2.0
        }

        private fun elasticOut(t: Double): Double {
            if (t <= 0.0) return 0.0
            if (t >= 1.0) return 1.0
            val c4 = 2.0 * Math.PI / 3.0
            return Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0
        }

        private fun elasticIn(t: Double): Double {
            if (t <= 0.0) return 0.0
            if (t >= 1.0) return 1.0
            val c4 = 2.0 * Math.PI / 3.0
            return -Math.pow(2.0, 10.0 * t - 10.0) * Math.sin((t * 10.0 - 10.75) * c4)
        }

        private fun elasticInOut(t: Double): Double {
            if (t <= 0.0) return 0.0
            if (t >= 1.0) return 1.0
            val c5 = 2.0 * Math.PI / 4.5
            return if (t < 0.5) {
                -(Math.pow(2.0, 20.0 * t - 10.0) * Math.sin((20.0 * t - 11.125) * c5)) / 2.0
            } else {
                (Math.pow(2.0, -20.0 * t + 10.0) * Math.sin((20.0 * t - 11.125) * c5)) / 2.0 + 1.0
            }
        }

        private fun bounceOut(t: Double): Double {
            val n1 = 7.5625
            val d1 = 2.75
            return when {
                t < 1.0 / d1 -> n1 * t * t
                t < 2.0 / d1 -> { val x = t - 1.5 / d1; n1 * x * x + 0.75 }
                t < 2.5 / d1 -> { val x = t - 2.25 / d1; n1 * x * x + 0.9375 }
                else -> { val x = t - 2.625 / d1; n1 * x * x + 0.984375 }
            }
        }

        private fun bounceIn(t: Double) = 1.0 - bounceOut(1.0 - t)
        private fun bounceInOut(t: Double) = if (t < 0.5) {
            (1.0 - bounceOut(1.0 - 2.0 * t)) / 2.0
        } else {
            (1.0 + bounceOut(2.0 * t - 1.0)) / 2.0
        }
    }

    // ------------------------------------------------------------------ BPM

    /** BPMList：`(beats, bpm)` 分段，秒数 = 段起点秒 + (beats - 段起点拍) × 60/bpm（与 Phira 一致） */
    private class BpmMap(items: List<Pair<Double, Double>>) {
        private val startBeats = DoubleArray(items.size)
        private val startSec = DoubleArray(items.size)
        private val bpm = DoubleArray(items.size)

        init {
            var time = 0.0
            var lastBeats = 0.0
            var lastBpm = 0.0
            var hasLast = false
            for ((i, it) in items.withIndex()) {
                val (beats, b) = it
                if (hasLast) time += (beats - lastBeats) * (60.0 / lastBpm)
                startBeats[i] = beats
                startSec[i] = time
                bpm[i] = b
                lastBeats = beats
                lastBpm = b
                hasLast = true
            }
        }

        fun seconds(beats: Double): Double {
            var idx = 0
            for (i in startBeats.indices) if (startBeats[i] <= beats) idx = i
            return startSec[idx] + (beats - startBeats[idx]) * (60.0 / bpm[idx])
        }
    }

    // ------------------------------------------------------------------ 工具

    /** Triple `[i, n, d]` = i + n/d（拍） */
    private fun tripleBeats(a: JSONArray): Double {
        val d = a.optInt(2, 1).takeIf { it > 0 } ?: 1
        return a.optInt(0, 0) + a.optInt(1, 0).toDouble() / d
    }

    private fun parseInfoTxt(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.removePrefix("\uFEFF").lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line == "#") continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return map
    }

    // ---------------------------------------------------------------- PEC（Phigros 文本谱）

    /** PEC 是不是文本谱：第一行是偏移毫秒（纯数字），之后是 `bp` / `n1` / `cp` … */
    private fun looksLikePecText(text: String): Boolean {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("{")) return false
            val tk = line.split(Regex("\\s+"))
            if (tk.size == 1 && tk[0].toDoubleOrNull() != null) return true
            return tk[0] == "bp"
        }
        return false
    }

    private fun looksLikePec(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
        return looksLikePecText(head)
    }

    /** PEC 事件：一条属性事件（instant 时 start == end） */
    private class PecEvent(val start: Double, val end: Double, val value: Double)

    /** PEC 属性轨道：事件之间线性过渡（PEC 的 tween 类型这里统一按线性近似） */
    private class PecTrack(events: List<PecEvent>, fallback: Double) {
        private class Seg(val s: Double, val e: Double, val from: Double, val to: Double)
        private val segs = ArrayList<Seg>()
        init {
            val sorted = events.sortedWith(compareBy({ it.start }, { it.end }))
            var cur = fallback
            for (e in sorted) {
                if (!e.start.isFinite() || !e.end.isFinite() || !e.value.isFinite()) continue
                segs.add(Seg(e.start, e.end, cur, e.value))
                cur = e.value
            }
        }
        fun at(t: Double): Double {
            if (segs.isEmpty()) return 0.0
            var lo = 0
            var hi = segs.size - 1
            if (t < segs[0].s) return segs[0].from
            while (lo + 1 < hi) {
                val mid = (lo + hi) / 2
                if (segs[mid].s <= t) lo = mid else hi = mid
            }
            var i = lo
            while (i + 1 < segs.size && segs[i + 1].s <= t) i++
            val g = segs[i]
            if (g.e <= g.s) return g.to
            if (t >= g.e) return g.to
            val u = ((t - g.s) / (g.e - g.s)).coerceIn(0.0, 1.0)
            return g.from + (g.to - g.from) * u
        }
    }

    private class PecLineData {
        val x = ArrayList<PecEvent>()
        val y = ArrayList<PecEvent>()
        val rot = ArrayList<PecEvent>()
        val alpha = ArrayList<PecEvent>()
        val speed = ArrayList<PecEvent>()
        val notes = ArrayList<RecordedNote>()
    }

    /**
     * PEC 导入（按 Phira `prpr/src/parse/pec.rs` 的语义）：
     *  - 首行偏移：`offset = 值/1000 - 0.15`（秒）
     *  - `bp beat bpm`：BPM 分段，beat → 秒
     *  - `n1..n4`：tap / hold / flick / drag；x 除以 1024 归一化；`above` / `fake` 照抄
     *  - `cp/cd/ca/cv`：位置 / 旋转 / 透明度 / 流速的瞬时值
     *  - `cm/cr/cf`：过渡（缓动统一按线性近似，和本项目其他导入一致）
     *  - `cv` 的流速值 / 5.85 是 Phira 单位 → 本项目流速 = ×4.5
     */
    private fun importPec(
        raw0: ByteArray,
        names: List<String>,
        info: Map<String, String>,
        warnings: MutableList<String>,
        zipBytes: ByteArray,
        entries: List<ZipReader.Entry>,
        saveAsset: (String, InputStream) -> Boolean
    ): ImportedChart {
        val text = decodeText(raw0)

        // ---------------- 第一遍：偏移 + BPM ----------------
        var offsetSec = 0.0
        var seenOffset = false
        val bpmBeats = ArrayList<Pair<Double, Double>>()
        for (raw in text.lineSequence()) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            val tk = s.split(Regex("\\s+"))
            if (!seenOffset) {
                tk[0].toDoubleOrNull()?.let { offsetSec = it / 1000.0 - 0.15 }
                seenOffset = true
                continue
            }
            if (tk[0] == "bp" && tk.size >= 3) {
                val b = tk[1].toDoubleOrNull()
                val v = tk[2].toDoubleOrNull()
                if (b != null && v != null && v > 0 && b.isFinite()) bpmBeats.add(b to v)
                continue
            }
            break
        }
        if (bpmBeats.isEmpty()) bpmBeats.add(0.0 to 120.0)
        bpmBeats.sortBy { it.first }
        if (bpmBeats[0].first > 1e-9) bpmBeats.add(0, 0.0 to bpmBeats[0].second)
        val segBase = DoubleArray(bpmBeats.size)
        for (i in 1 until bpmBeats.size) {
            segBase[i] = segBase[i - 1] + (bpmBeats[i].first - bpmBeats[i - 1].first) * 60.0 / bpmBeats[i - 1].second
        }
        val secAt = { beat: Double ->
            var lo = 0
            var hi = bpmBeats.size - 1
            while (lo + 1 < hi) {
                val mid = (lo + hi) / 2
                if (bpmBeats[mid].first <= beat) lo = mid else hi = mid
            }
            val baseBeat = bpmBeats[lo].first
            val bpm = bpmBeats[lo].second
            segBase[lo] + (beat - baseBeat) * 60.0 / bpm
        }

        // ---------------- 第二遍：事件 + 音符 ----------------
        val lines = ArrayList<PecLineData>()
        fun lineAt(id: Int): PecLineData {
            while (lines.size <= id) lines.add(PecLineData())
            return lines[id]
        }
        var totalNotes = 0
        var dropped = 0
        for (raw in text.lineSequence()) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            val tk = s.split(Regex("\\s+"))
            if (tk.size == 1 && tk[0].toDoubleOrNull() != null) continue   // 首行偏移
            val cmd = tk[0]
            if (cmd == "bp") continue
            if (cmd.length == 2 && cmd[0] == 'n' && cmd[1] in '1'..'4') {
                val li = tk.getOrNull(1)?.toIntOrNull() ?: run { dropped++; null } ?: continue
                val beat = tk.getOrNull(2)?.toDoubleOrNull() ?: run { dropped++; null } ?: continue
                val kind = cmd[1] - '0'
                var idx = 3
                val endBeat: Double
                if (kind == 2) {
                    endBeat = tk.getOrNull(idx)?.toDoubleOrNull() ?: beat
                    idx++
                } else endBeat = beat
                val px = tk.getOrNull(idx)?.toDoubleOrNull() ?: 0.0
                idx++
                val above = (tk.getOrNull(idx)?.toIntOrNull() ?: 1) == 1
                idx++
                val fake = (tk.getOrNull(idx)?.toIntOrNull() ?: 0) == 1
                idx++
                var speed = 1.0
                var size = 1.0
                var j = idx
                while (j < tk.size - 1) {
                    if (tk[j] == "#") speed = tk[j + 1].toDoubleOrNull() ?: speed
                    if (tk[j] == "&") size = tk[j + 1].toDoubleOrNull() ?: size
                    j++
                }
                val startMs = ((secAt(beat) + offsetSec) * 1000.0)
                val endMs = ((secAt(endBeat) + offsetSec) * 1000.0)
                val type = kind  // PEC 1/2/3/4 = tap/hold/flick/drag，和本项目一致
                lineAt(li).notes.add(
                    RecordedNote(
                        type = type,
                        startMs = Math.round(if (startMs < 0) 0.0 else startMs),
                        endMs = Math.round(if (endMs < startMs) startMs else endMs),
                        posX = (px / 1024.0).coerceIn(-1.0, 1.0).toFloat(),
                        pass = passOf(type),
                        lineId = li,
                        above = above,
                        fake = fake,
                        speed = speed.toFloat().coerceIn(0f, 1e6f),
                        size = size.toFloat().coerceIn(0.05f, 10f)
                    )
                )
                totalNotes++
                continue
            }
            if (cmd.length != 2 || cmd[0] != 'c') continue
            val li = tk.getOrNull(1)?.toIntOrNull() ?: continue
            val beat = tk.getOrNull(2)?.toDoubleOrNull() ?: continue
            val t = secAt(beat)
            val line = lineAt(li)
            when (cmd) {
                "cp" -> {
                    line.x.add(PecEvent(t, t, tk.getOrNull(3)?.toDoubleOrNull() ?: 0.0))
                    line.y.add(PecEvent(t, t, tk.getOrNull(4)?.toDoubleOrNull() ?: 0.0))
                }
                "cm" -> {
                    val endT = secAt(tk.getOrNull(3)?.toDoubleOrNull() ?: beat)
                    val x = tk.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                    val y = tk.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                    line.x.add(PecEvent(t, endT, x))
                    line.y.add(PecEvent(t, endT, y))
                }
                "cd" -> line.rot.add(PecEvent(t, t, -(tk.getOrNull(3)?.toDoubleOrNull() ?: 0.0)))
                "cr" -> {
                    val endT = secAt(tk.getOrNull(3)?.toDoubleOrNull() ?: beat)
                    line.rot.add(PecEvent(t, endT, -(tk.getOrNull(4)?.toDoubleOrNull() ?: 0.0)))
                }
                "ca" -> line.alpha.add(PecEvent(t, t, tk.getOrNull(3)?.toDoubleOrNull() ?: 0.0))
                "cf" -> {
                    val endT = secAt(tk.getOrNull(3)?.toDoubleOrNull() ?: beat)
                    line.alpha.add(PecEvent(t, endT, tk.getOrNull(4)?.toDoubleOrNull() ?: 0.0))
                }
                "cv" -> line.speed.add(PecEvent(t, t, (tk.getOrNull(3)?.toDoubleOrNull() ?: 0.0) / 5.85))
            }
        }

        if (totalNotes == 0) throw IllegalArgumentException("PEC 谱面里没有音符")
        if (dropped > 0) warnings.add("已跳过 $dropped 个字段不完整的音符")
        if (bpmBeats.size > 1) warnings.add("PEC 谱面包含 ${bpmBeats.size - 1} 次 BPM 变化")
        warnings.add("PEC 谱面的缓动类型已按线性近似（音符时间/位置准确）")

        // ---------------- 每条线 → 姿态关键帧 ----------------
        val importLines = ArrayList<ImportedLine>()
        for (li in lines.indices) {
            val d = lines[li]
            val xT = PecTrack(d.x, 0.0)
            val yT = PecTrack(d.y, 0.0)
            val rT = PecTrack(d.rot, 0.0)
            val aT = PecTrack(d.alpha, 255.0)
            val sT = PecTrack(d.speed, 1.0 / 0.7692308)
            val times = ArrayList<Double>()
            for (list in listOf(d.x, d.y, d.rot, d.alpha, d.speed)) {
                for (e in list) { times.add(e.start); times.add(e.end) }
            }
            times.add(0.0)
            times.sort()
            val distinct = ArrayList<Double>()
            for (t in times) {
                if (!t.isFinite() || t < 0) continue
                if (distinct.isEmpty() || t - distinct.last() > 1e-6) distinct.add(t)
            }
            val xNorm = { t: Double -> (xT.at(t) / 2048.0 * 2.0 - 1.0).toFloat() }
            val yNorm = { t: Double -> (yT.at(t) / 1400.0 * 2.0 - 1.0).toFloat() }
            val rotAt = { t: Double -> rT.at(t).toFloat() }
            val alphaAt = { t: Double -> (aT.at(t) / 255.0).coerceIn(0.0, 1.0).toFloat() }
            val speedAt = { t: Double -> safeSpeed(sT.at(t) * 0.7692308) }
            val x0 = xNorm(0.0); val y0 = yNorm(0.0); val r0 = rotAt(0.0)
            val s0 = speedAt(0.0); val a0 = alphaAt(0.0)
            val isStatic = distinct.size <= 1 || distinct.all {
                Math.abs(xNorm(it) - x0) < 1e-5f && Math.abs(yNorm(it) - y0) < 1e-5f &&
                    Math.abs(rotAt(it) - r0) < 1e-4f && Math.abs(alphaAt(it) - a0) < 1e-4f &&
                    Math.abs(speedAt(it) - s0) < 1e-4f
            }
            val kfs = ArrayList<LineKeyframe>()
            if (!isStatic) {
                val idxs = if (distinct.size <= MAX_KEYFRAMES) distinct.indices.toList() else {
                    List(MAX_KEYFRAMES) { k -> (k.toLong() * (distinct.size - 1) / (MAX_KEYFRAMES - 1)).toInt() }.distinct()
                }
                for (i in idxs) {
                    val t = distinct[i]
                    kfs.add(
                        LineKeyframe(
                            Math.round((t + offsetSec) * 1000.0),
                            xNorm(t), yNorm(t), rotAt(t), speedAt(t), alphaAt(t)
                        )
                    )
                }
            }
            d.notes.sortBy { it.startMs }
            importLines.add(
                ImportedLine(
                    id = li,
                    name = "",
                    posPct = 50,
                    moveX = x0,
                    moveY = y0,
                    rotate = r0,
                    speed = s0,
                    alpha = a0,
                    keyframes = kfs,
                    notes = d.notes
                )
            )
        }

        val bpm0 = bpmBeats[0].second
        val segs = ArrayList<BpmSegment>()
        segs.add(BpmSegment(0L, bpm0))
        for (i in 1 until bpmBeats.size) {
            val ms = Math.round((segBase[i] + offsetSec) * 1000.0)
            segs.add(BpmSegment(ms.coerceAtLeast(1L), bpmBeats[i].second))
        }
        val bpmSegments = BpmTimeline.normalize(segs, bpm0)

        val musicEntry = pickEntry(names, info["Song"] ?: info["Music"], AUDIO_EXTS)
        val imageEntry = pickEntry(names, info["Picture"] ?: info["Image"], IMAGE_EXTS)
        if (musicEntry != null || imageEntry != null) {
            val want = setOfNotNull(musicEntry, imageEntry)
            runCatching {
                for (e in entries) {
                    val n = cleanName(e.name)
                    if (n in want) {
                        ZipReader.bytes(zipBytes, e)?.let { saveAsset(n, java.io.ByteArrayInputStream(it)) }
                    }
                }
            }.onFailure { warnings.add("素材提取失败：${it.message}") }
        }

        val first = importLines.firstOrNull()
        return ImportedChart(
            name = info["Name"] ?: "PEC 谱面",
            composer = info["Composer"] ?: "",
            charter = info["Charter"] ?: info["Designer"] ?: "",
            level = info["Level"] ?: "",
            bpm = bpm0,
            bpmChanges = bpmBeats.size - 1,
            speed = (first?.speed ?: 1f).toDouble(),
            linePosPct = first?.posPct ?: 50,
            lineMoveX = first?.moveX ?: 0f,
            lineMoveY = first?.moveY ?: 0f,
            lineRotate = first?.rotate ?: 0f,
            lines = importLines,
            hLines = 8,
            vLines = 16,
            musicName = musicEntry,
            imageName = imageEntry,
            warnings = warnings,
            bpmSegments = bpmSegments,
            noiseAreas = emptyList()
        )
    }

    /**
     * 找谱面 JSON：info.Chart → chart.json → 任何含 judgeLineList/BPMList 的 json。
     *
     * ★ 只做顶层成员嗅探（JsonDoc 字节扫描），**不整棵 parse**——
     *   46MB 级谱面 JSON 建 DOM 树会 OOM，以前 runCatching 把 OOM 吞掉后
     *   就显示成「压缩包里没有找到谱面 JSON」（big song 那次的根因）。
     *
     * 三种格式都能认（判据与 Phira 的 `infer_chart_format` 一致）：
     *  - **RPE**：有 `META` 对象（pez 的谱面）
     *  - **官谱 Phigros Official**：没有 `META`，但有 `judgeLineList`（`formatVersion` + `offset` + `judgeLineList`）
     *  - `BPMList` 只在 RPE 里出现，留作兜底
     */
    private fun pickChartName(texts: Map<String, ByteArray>, want: String?): String? {
        val cands = texts.keys.filter { it.substringAfterLast('.', "").equals("json", true) }
        val ordered = ArrayList<String>()
        if (!want.isNullOrBlank()) cands.firstOrNull { baseName(it).equals(baseName(want), true) }?.let { ordered.add(it) }
        cands.firstOrNull { baseName(it).equals("chart.json", true) }?.let { ordered.add(it) }
        ordered.addAll(cands)
        for (n in ordered.distinct()) {
            val bytes = texts[n] ?: continue
            val d = JsonDoc(bytes)
            if (d.has("judgeLineList") || d.has("BPMList")) return n
        }
        return null
    }

    fun importText(text: String, name: String): ImportedChart {
        val warnings = ArrayList<String>()
        if (looksLikePecText(text)) {
            return importPec(normalizeJsonBytes(text.toByteArray(Charsets.UTF_8)),
                emptyList(), mapOf("Name" to name), warnings,
                ByteArray(0), emptyList(), { _, _ -> false })
        }
        val bytes = normalizeJsonBytes(text.toByteArray(Charsets.UTF_8))
        val sniff = JsonDoc(bytes)
        if (!sniff.has("judgeLineList") && !sniff.has("BPMList")) {
            throw IllegalArgumentException("这不是谱面 JSON（没有 judgeLineList / BPMList）")
        }
        val noAsset = { _: String, _: InputStream -> false }
        // 官谱 / RPE 都走同一条流式入口（内部分派）
        return importFromDocument(bytes, emptyList(), emptyMap(), warnings, ByteArray(0), emptyList(), noAsset)
    }

    /** 按 info/META 里给的文件名找条目，找不到就按扩展名兜底 */
    private fun pickEntry(names: List<String>, want: String?, exts: Set<String>): String? {
        if (!want.isNullOrBlank()) {
            names.firstOrNull { it.equals(want, true) }?.let { return it }
            names.firstOrNull { baseName(it).equals(baseName(want), true) }?.let { return it }
        }
        // 兜底：优先根目录、常见扩展名
        return names.filter { it.substringAfterLast('.', "").lowercase() in exts }
            .minByOrNull { if (it.contains('/')) 1 else 0 }
    }

    /** 解码文本：优先 UTF-8（去掉 BOM），出现乱码再按 GBK 试一次（RPE 在中文 Windows 上常存 GBK） */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.indexOf('\uFFFD') < 0) return utf8.removePrefix("\uFEFF")
        val gbk = runCatching { String(bytes, charset("GBK")) }.getOrNull()
        return (gbk ?: utf8).removePrefix("\uFEFF")
    }

    /**
     * 去掉 JSON 字符串外的空白（空格 / 制表 / 换行），字符串里的内容原样保留。
     * 只做"压缩"，不改语义 —— 大谱面解析前的必做步骤。
     */
    internal fun minifyJson(s: String): String {
        val sb = StringBuilder(s.length)
        var inStr = false
        var esc = false
        for (ch in s) {
            if (inStr) {
                sb.append(ch)
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
            } else when (ch) {
                '"' -> { inStr = true; sb.append(ch) }
                ' ', '\t', '\r', '\n' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun cleanName(name: String): String {
        var n = name.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        return n.trim()
    }

    private fun baseName(path: String): String = path.replace('\\', '/').substringAfterLast('/')

    /**
     * 谱面 JSON 字节进入流式扫描前的归一化：
     *  1. 去掉 UTF-8 BOM（字节扫描器不认，会看成非法文档头）
     *  2. 非 UTF-8（多半是 GBK，RPE 在中文 Windows 上常这么存）整篇转成 UTF-8——
     *     不只是中文会花：GBK 尾字节可能撞上 0x5C（'\'），字节级跳字符串会被带偏
     */
    private fun normalizeJsonBytes(raw0: ByteArray): ByteArray {
        val noBom = if (raw0.size >= 3 && raw0[0] == 0xEF.toByte() && raw0[1] == 0xBB.toByte() && raw0[2] == 0xBF.toByte())
            raw0.copyOfRange(3, raw0.size) else raw0
        val probe = String(noBom, Charsets.UTF_8)
        if (probe.indexOf('\uFFFD') < 0) return noBom
        val gbk = runCatching { String(noBom, charset("GBK")) }.getOrNull() ?: probe
        return gbk.toByteArray(Charsets.UTF_8)
    }

    /**
     * JSON 顶层成员的字节切片器：只扫不建树。
     *
     * 用途：46MB 级大谱（几万事件）整棵 parse 成 JSONObject 会 OOM——
     * org.json 每个对象一个 HashMap，24 万事件轻松吃掉几百 MB 堆。
     * 这里只在字节层面定位顶层成员的位置，按需把切片交给调用方
     * （判定线逐条 parse、parse 完即弃），峰值内存从「整棵树」降到「单条线」。
     *
     * 只处理顶层：KEY / 值的扫描都跳过字符串字面量（含转义），嵌套结构按深度计数。
     * UTF-8 多字节字符不会产生 0x7B/0x7D/0x22 等 ASCII 字节，所以字节级匹配安全；
     * 调用方负责先去掉 BOM / 做 GBK 转码。
     */
    internal class JsonDoc(private val b: ByteArray) {
        /** key → 值的字节区间（含首尾） */
        val members: Map<String, IntRange> by lazy { scan() }

        fun has(name: String): Boolean = members.containsKey(name)

        /** 顶层成员的原始字节切片（就是 JSON 值本身，含可能的引号/括号） */
        fun member(name: String): ByteArray? = members[name]?.let { b.copyOfRange(it.first, it.last + 1) }

        /** 顶层成员是数组时，逐元素产出切片（惰性：切一条消费一条） */
        fun arrayElements(name: String): Sequence<ByteArray> = sequence {
            val span = members[name] ?: return@sequence
            var i = skipWs(span.first)
            if (i >= b.size || b[i] != '['.code.toByte()) return@sequence
            i++
            while (true) {
                i = skipWs(i)
                if (i >= b.size) return@sequence
                if (b[i] == ']'.code.toByte()) return@sequence
                val s = i
                i = skipValue(i)
                if (i < 0) return@sequence
                yield(b.copyOfRange(s, i))
                i = skipWs(i)
                if (i < b.size && b[i] == ','.code.toByte()) { i++; continue }
                return@sequence
            }
        }

        private fun scan(): Map<String, IntRange> {
            val map = HashMap<String, IntRange>()
            var i = skipWs(0)
            if (i >= b.size || b[i] != '{'.code.toByte()) return map
            i++
            while (true) {
                i = skipWs(i)
                if (i >= b.size) return map
                if (b[i] == '}'.code.toByte()) return map
                if (b[i] != '"'.code.toByte()) return map
                val keyStart = ++i
                i = skipString(i)
                if (i < 0) return map
                val key = String(b, keyStart, i - keyStart, Charsets.UTF_8)
                i++
                i = skipWs(i)
                if (i >= b.size || b[i] != ':'.code.toByte()) return map
                i++
                i = skipWs(i)
                val vStart = i
                i = skipValue(i)
                if (i < 0) return map
                map[key] = IntRange(vStart, i - 1)
                i = skipWs(i)
                if (i < b.size && b[i] == ','.code.toByte()) { i++; continue }
                return map
            }
        }

        /** i 指向值的第一个字节，返回值结束后的下标（越过最后一个字节）；失败返回 -1 */
        private fun skipValue(i0: Int): Int {
            var i = i0
            if (i >= b.size) return -1
            when (b[i]) {
                '"'.code.toByte() -> { i = skipString(i + 1); return if (i < 0) -1 else i + 1 }
                '{'.code.toByte(), '['.code.toByte() -> {
                    var depth = 0
                    while (i < b.size) {
                        val c = b[i]
                        if (c == '"'.code.toByte()) {
                            // skipString 返回闭引号下标：必须越过它，否则闭引号会被当成
                            // 下一个开引号、中间的结构字符（}]）被吞掉，深度永远归不了零
                            val end = skipString(i + 1)
                            if (end < 0) return -1
                            i = end + 1
                            continue
                        }
                        if (c == '{'.code.toByte() || c == '['.code.toByte()) depth++
                        else if (c == '}'.code.toByte() || c == ']'.code.toByte()) {
                            depth--
                            if (depth == 0) return i + 1
                        }
                        i++
                    }
                    return -1
                }
                else -> {
                    // 标量：null / true / false / 数字，读到分隔符为止
                    while (i < b.size) {
                        val c = b[i]
                        if (c == ','.code.toByte() || c == '}'.code.toByte() || c == ']'.code.toByte()) return i
                        if (c == 0x20.toByte() || c == 0x09.toByte() || c == 0x0A.toByte() || c == 0x0D.toByte()) return i
                        i++
                    }
                    return i
                }
            }
        }

        /** i 指向字符串开引号后的第一个字节，返回闭引号下标；失败返回 -1 */
        private fun skipString(i0: Int): Int {
            var i = i0
            while (i < b.size) {
                val c = b[i]
                if (c == '\\'.code.toByte()) { i += 2; continue }
                if (c == '"'.code.toByte()) return i
                i++
            }
            return -1
        }

        private fun skipWs(i0: Int): Int {
            var i = i0
            while (i < b.size) {
                val c = b[i]
                if (c == 0x20.toByte() || c == 0x09.toByte() || c == 0x0A.toByte() || c == 0x0D.toByte()) i++ else break
            }
            return i
        }
    }

    private fun trimNum(v: Double): String {
        val r = Math.round(v * 1000.0) / 1000.0
        return if (r == Math.floor(r) && !r.isInfinite()) r.toLong().toString() else r.toString()
    }
}
