package com.piezmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import java.io.File

/**
 * 一个音符（录制产物）。
 * type: 1=Tap(click) 2=Hold 3=Flick 4=Drag —— 与 RPE / Phira 一致
 * pass: 1=第一阶段(drag/hold) 2=第二阶段(click) 3=第三阶段(flick)
 * lineId: 属于哪条判定线
 * above: true=正面（从判定线上方落下）false=反面（RPE above=0，从另一侧落下、贴图上下翻转）
 * fake: 假音符（RPE isFake=1，只显示不判定）
 * speed/yOffset/alpha/size/visibleTime: RPE 音符级属性（与 Phira 渲染语义一致）：
 *  - speed: 下落速度倍率（乘音符到判定线的距离）
 *  - yOffset: 纵向偏移（RPE 像素，正值 = 沿下落方向远离判定线）
 *  - alpha: 透明度 0~1（RPE alpha/255，255 及以上 = 1）
 *  - size: 宽度倍率
 *  - visibleTime: 提前可见的秒数（999999 = 永远可见；Phira 里从此刻往回淡入）
 *  - judgeArea: 判定范围（RPE judgeArea，1 = 正常；只影响打歌判定宽度，不影响显示）
 *  - tint: 音符颜色（RPE tint，0xRRGGBB；-1 = 原色，导出时不写）
 *  - tintHit: 打击特效颜色（RPE tintHitEffects；-1 = 原色）
 *  - hitsound: 打击音效（RPE hitsound，空 = 默认音效）
 */
data class RecordedNote(
    val type: Int,
    val startMs: Long,
    val endMs: Long,
    val posX: Float,          // 归一化 [-1, 1]（对应 RPE positionX/675）
    val pass: Int = 1,
    val lineId: Int = 0,      // 属于哪条判定线
    val above: Boolean = true,  // false = 反面音符（RPE above=0，绕判定线转 180°）
    val fake: Boolean = false,  // 假音符（RPE isFake=1，只显示不判定）
    val speed: Float = 1f,
    val yOffset: Float = 0f,
    val alpha: Float = 1f,
    val size: Float = 1f,
    val visibleTime: Float = 999999f,
    val judgeArea: Float = 1f,
    val tint: Int = -1,
    val tintHit: Int = -1,
    val hitsound: String = ""
) {
    /** 有没有 phiedit 那一套"扩展属性"（导出/草稿只在有值时才写，保持文件干净） */
    val hasExtExtras: Boolean
        get() = judgeArea != 1f || tint >= 0 || tintHit >= 0 || hitsound.isNotEmpty()
}

/** 触点状态 */
private class FingerState {
    var downRealMs: Long = 0
    var downPlayMs: Long = 0
    var upPlayMs: Long = 0
    var downX: Float = 0f
    var downY: Float = 0f
    var curX: Float = 0f
    var curY: Float = 0f
    var active: Boolean = false

    var lastFillSlot: Long = -1                        // 第二/三次：上次填充的时间槽
    var lineId: Int = 0                                // 本次按下要放音符的判定线（=当前选中的线）
    var downLineId: Int = 0                            // 按下点离哪条判定线最近（长按编辑用）

    // 编辑模式（暂停时）
    var mode: Int = 0                  // 0=未定 1=点击放置 2=拖动音符 3=滑动时间 4=框选
    var targetNote: Int = -1
    var prevY: Float = 0f
    var groupDrag = false              // 多选整体拖动中
    var groupOrigin: MutableMap<Int, com.piezmaker.RecordedNote>? = null   // 拖动前各音符原值
    var willDelete = false             // 按下时音符已在红框内（松手未拖动 → 删除）
    var moved = false                  // 本次按下后是否真的拖动过（拖过就不删）
    var above: Boolean = true          // 本次手势要放的音符是正面还是反面（按落点算）
    var extraDt: Long = 0L             // 拖到遮罩自动滚时间时，补偿给"整组拖动"的时间量
    var lineDown = false               // 按在判定线本体上（长按进入判定线编辑）
    var lineNear = false               // 靠近判定线（快速切换用，判定带更宽）
    var chainCell: Int = -1            // 全量模式：黄键链上一格的位置格号（-1 = 还没开始）
}

/**
 * 三阶段制谱视图（Phigros 官方编辑器的流程）：
 *
 *  第一次：蓝键 Tap（点一下）+ 蓝长条 Hold（长按不动）。**没有连续放功能**。
 *  第二次：黄键 Drag；按住不放 → 按最小横线间隔连续填充。
 *  第三次：红键 Flick；按住不放 → 按最小横线间隔连续填充。
 *         第二/三次会把前面放好的音符以半透明渲染出来做参考。
 *  第四次：长条 Hold；拖动放（拖动方向决定正面/反面）+ 长按。
 *
 * 音符按时间轴定位：未到的音符在判定线上方，过了判定线向下移动，出屏即消失。
 * 判定线可以有多条（每条有独立的位置/姿态/关键帧/流速），音符挂在各自的线上。
 */
class RecordView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val TAG = "FRPHI"
        /** Autoplay 只有落在这段窗口内的音符才会发声/计分，更旧的一律静默跳过 */
        const val AUTO_HIT_WINDOW_MS = 220L
        /** 所有"淡化"统一用这个系数（30%）：线高亮关闭时的其它线音符、假音符淡化、真实处理淡化 */
        const val DIM_ALPHA = 0.3f
        /** 最多记多少步撤销（每步只存增量，200 步也很省） */
        const val UNDO_LIMIT = 200
        /** 撤销栈里线快照的引用总量预算（见 [trimHistoryBudget]）：150 万引用 ≈ 12MB */
        const val UNDO_SNAPSHOT_REFS = 1_500_000L
        /** 判定线数量不设上限（用户点名）：32 条的老上限已删除，多少条都能加 */
        /** Phira 音符宽度：note_scale(1.0) × NOTE_WIDTH_RATIO_BASE（占谱面视口宽度的比例） */
        const val NOTE_W_RATIO = 0.13175016f
        /** Phira 谱面视口宽高比：16:9 */
        const val CHART_ASPECT = 16f / 9f
        /** Phira 判定线长度：info.yml 的 lineLength（默认 6 = 单边 6 个半屏宽，旋转时就是贯穿屏幕的长条） */
        const val LINE_LENGTH = 6f
        /** 单一音效最多同时几条流（快速连点时掐最旧的，保证总有声音） */
        const val SND_MAX_PER_TYPE = 8
        /** 剪贴板最多存多少条 */
        const val CLIP_MAX = 30
        /** 反向音符框：蓝 */
        const val COLOR_REVERSE = 0xFF00B0FF.toInt()
        /** 幽灵音符框：绿 */
        const val COLOR_GHOST = 0xFF00E676.toInt()
        /** 重叠（被后来的音符盖住）框：橙 */
        const val COLOR_OVERLAP = 0xFFFF9800.toInt()
    }

    // ----- 配置 -----
    var bpm: Double = 120.0
    /** BPM 偏移（ms，支持 3 位小数）：正数 = 谱面整体比音乐延后（和 Phiedit 的"bpm偏移"一致）。
     *  时间网格原点、吸附格点、导出时的音符时间都按它平移。 */
    var bpmOffsetMs: Double = 0.0
    var hLines: Int = 4          // 横线数：每拍分几格（时间量化）
    var vLines: Int = 8          // 竖线数：位置量化
    var trackLengthMs: Long = 0  // 音频总时长（来自播放页），用于打歌 HUD 进度条

    // ----- 判定线（多条）-----
    /** 全部判定线（至少一条，id 唯一） */
    val lines: MutableList<Line> = mutableListOf(Line(0).also { it.name = "line" })
    private var nextLineId = 1
    private var activeLineId = 0

    /**
     * 噪域（RPE `blockAreaList`）—— **可编辑**。
     * 支持读入、画框、拖动四角、矩形关键帧、显示/深浅、新建/复制/删除、图层（layerId），
     * 导出时写回原始 JSON（不认识的字段不丢）。数据源：导入（.pez/官谱）、草稿恢复、
     * 以及 [createNoiseFromScreen] 等编辑入口；所有改动都走 [noiseMutate] 进撤销栈。
     */
    val noiseAreas: MutableList<NoiseArea> = mutableListOf()

    /** 噪域概览是否显示（默认显示；导入的谱面有噪域时自动打开） */
    var noiseOverlay = true

    /**
     * 噪域运行时（打歌用）：负责「这块区域此刻怎么变形、多亮」以及「这根手指断没断」。
     * 噪域被编辑后标脏，下一次真正用到时再按 [noiseAreas] 重建 —— 拖角时每帧重建
     * 会让 14k 块的排序反复跑，编辑手感会掉。
     */
    private var noiseRuntimeCache = NoiseRuntime(emptyList())
    private var noiseRuntimeStale = false
    var noiseRuntime: NoiseRuntime
        get() {
            if (noiseRuntimeStale) {
                noiseRuntimeCache = NoiseRuntime(noiseAreas)
                noiseRuntimeStale = false
            }
            return noiseRuntimeCache
        }
        private set(value) {
            noiseRuntimeCache = value
            noiseRuntimeStale = false
        }

    /**
     * 噪域断触是否生效（默认开）。关掉只是不掐手指，视觉照旧。
     * 新手打谱 / 只想看效果时用。
     */
    var noiseJamEnabled = true

    /** 每帧的噪域采样结果（复用数组，避免每帧 new 一堆对象） */
    private val noiseSamples = ArrayList<NoiseRuntime.Sample>(4)

    /**
     * 噪域「按时间取可见块」的索引。
     *
     * 官谱噪域能有**一万四千多块**（ハテ AT = 14355），每帧全量遍历 + 每块新建
     * `DashPathEffect` + 拼标题字符串 ⇒ 进编辑页直接卡死。这里把区域按 `appearMs`
     * 排序，配合逐步收窄右边界，只取「此刻落在生命周期内」的少数几块。
     */
    private val noiseIndex = NoiseTimeIndex()

    /** 本帧可见的噪域（复用，不每帧 new） */
    private val noiseVisible = ArrayList<NoiseArea>(32)

    /** [noiseVisible] 每项在 [noiseAreas] 里的下标（画标题用「噪域 N」） */
    private val noiseVisibleIdx = NoiseTimeIndex.IntList(32)

    /** 本帧「时间可见 + 屏幕可见」的噪域（复用，不每帧 new） */
    private val noiseOnScreen = ArrayList<NoiseArea>(64)

    /** [noiseOnScreen] 每项在原列表里的下标 */
    private val noiseOnScreenIdx = NoiseTimeIndex.IntList(64)

    /** 长按命中检测用的临时列表（和绘制列表分开，避免互相清空） */
    private val noiseHitCand = ArrayList<NoiseArea>(64)
    private val noiseHitCandIdx = NoiseTimeIndex.IntList(64)
    /** 命中检测复用的运行时（只调无状态的 sample，不保存手指状态） */
    private val noiseProbeRuntime = NoiseRuntime(emptyList())

    /** 虚线效果缓存（每块噪域新建一个 PathEffect 是主要的内存分配源之一） */
    private var noiseDash: android.graphics.DashPathEffect? = null

    // ---------------------------------------------------------------- 噪域编辑
    /** 正在编辑噪域（暂停/editMode 下）；进入后底部 4 个按钮变成四个角、右上角出现输入面板 */
    var noiseEditActive = false
        private set
    /** 正在编辑的噪域在 [noiseAreas] 里的下标 */
    var noiseEditIndex = -1
        private set
    /** 当前编辑的角：0=左上 1=右上 2=左下 3=右下 */
    var noiseCorner = 0
        private set
    /** 当前选中的噪域关键帧时间（左侧时间条红框）；-1 = 没选 */
    private var noiseSelectedKfMs = -1L
    /** 编辑视图：把选中的角平移到屏幕中心并放大（画布平移缩放） */
    private var noiseViewZoom = 1f
    private var noiseViewPanX = 0f
    private var noiseViewPanY = 0f
    /** 正在操作左侧时间条（触摸期间一直吃掉事件） */
    private var noiseBarActive = false
    private var noiseBarDragging = false
    private var noiseBarCommit = false
    private var noiseBarLastY = 0f
    /** 正在拖角 */
    private var noiseCornerDragging = false
    /** 长按"＋新建噪域"后，下一次按下开始画四边形 */
    private var noiseCreatePending = false
    private var noiseCreateStartX = 0f
    private var noiseCreateStartY = 0f
    private var noiseCreateCurX = 0f
    private var noiseCreateCurY = 0f
    /** 进入/退出噪域编辑（Activity 用来切底部按钮文字与右上角输入面板） */
    var onNoiseEditChanged: ((Boolean) -> Unit)? = null
    /** 噪域编辑状态变化（角、X/Y、显示/深浅）→ 刷新输入框 */
    var onNoiseEditInfo: ((NoiseArea, Int) -> Unit)? = null
    /** 请求 Activity 退出 Autoplay（噪域编辑不能带着 playMode 的 HUD 进去） */
    var onRequestExitPlay: (() -> Unit)? = null


    /** 块数超过这个数量就不写标题（一万多个字符串每帧拼接本身就卡） */
    private val MAX_NOISE_LABELS = 64

    /**
     * 编辑页**单帧最多画多少块**噪域。
     *
     * 实测官谱密集段同一瞬间有一千四百块重叠（ハテ AT 全谱 14355 块），
     * 全画 = 每帧一千四百个填充矩形 + 一千四百个虚线描边，手机上必然掉帧。
     * 截断只影响视觉（少画几块叠在后面的），判定与导出完全不受影响。
     */
    private val MAX_NOISE_DRAWN = 240

    /**
     * 打歌时**单帧最多画多少块**噪域。
     *
     * 官谱一帧里能同时活跃上千块（ハテ AT 有 14355 块，密集段同一瞬间几十上百块重叠），
     * 每块要铺一千多个像素格 + 5 层光晕描边 + clipPath，全画必然掉帧到个位数。
     * 这个上限只影响**视觉**（少画几个叠加的方块），判定完全不受影响
     * ——[NoiseRuntime.updateFinger] 走的是全部区域的命中检测，不看这个值。
     */
    private val MAX_NOISE_PLAY_DRAW = 24


    /** 当前在编辑/放置音符时优先使用的判定线 */
    val activeLine: Line
        get() {
            lines.firstOrNull { it.id == activeLineId }?.let { return it }
            val l = lines.firstOrNull() ?: Line(0).also { lines.add(it) }
            activeLineId = l.id
            return l
        }

    /** 判定线增删/切换时通知 Activity（刷新按钮文字） */
    var onLinesChanged: (() -> Unit)? = null
    /** 谱面/剪贴板变了（联机同步用；一次触摸 = 一次回调，因为都收在 endTx 里） */
    var onChartChanged: (() -> Unit)? = null

    /** 现在有手指按在屏幕上（联机收到远端草稿时要等松手再套用，否则音符下标会错位） */
    fun isTouchingNow(): Boolean {
        for (i in 0 until fingers.size()) if (fingers.valueAt(i).active) return true
        return false
    }

    /**
     * 判定线高亮（开=编辑模式下所有判定线都正常显示；关=只显示选中那条，其它变淡）。
     * 打歌（Autoplay）模式永远全部正常显示。默认关（这样一眼能看出正在编辑哪条线）。
     */
    var highlightAllLines: Boolean = false
        set(value) { field = value; invalidate() }

    /** 是否隐藏上下遮罩（由 Activity 控制实际控件可见性） */
    var hideBars: Boolean = false
        set(value) { field = value; invalidate() }

    /** 重叠音符：开=斜 45° 并套橙框；关=只斜 45°（默认关） */
    var overlapFrame: Boolean = false
        set(value) { field = value; invalidate() }

    /** 幽灵按键（fake）：开启后新放的音符全部是幽灵音符（只显示不判定） */
    var ghostPlace: Boolean = false
        set(value) { field = value; invalidate() }

    /** 假音符显示方式：0=显示（正常） 1=淡化（30%）。没有"隐藏"，也没有"框住"（框住是单独的开关） */
    var ghostShow: Int = 0
        set(value) { field = value.coerceIn(0, 1); invalidate() }

    /** 框住假音符：给假音符套一圈绿框（默认关） */
    var ghostFrame: Boolean = false
        set(value) { field = value; invalidate() }

    /** 显示反面音符：开启后把所有 above=0 的音符用蓝框圈出来（默认关） */
    var markReverse: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * 「真实处理」：编辑/录制模式里，**已经被处理过的音符**（越过判定线，或刚在 Autoplay 里被打掉的）
     * 怎么画：0=显示 1=淡化 2=隐藏。默认 0（跟以前完全一样）。
     */
    var realProcess: Int = 0
        set(value) { field = value.coerceIn(0, 2); invalidate() }

    /**
     * 反面音符（默认关）：
     *  - 开：判定线上方放 = 正面，判定线下方放 = 反面（位置决定正反）
     *  - 关：点哪儿都放正面（线下 = 过去的时间，老习惯）
     */
    var reverseEnabled: Boolean = false
        set(value) { field = value; invalidate() }

    /** 判定线放置线：false=只画选中那条（默认）true=所有判定线都画 */
    var placeLinesAll: Boolean = false
        set(value) { field = value; invalidate() }

    /** 横向转动：开启后横向时间网格跟着"选中的判定线"一起转（默认关） */
    var hGridRotate: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * 制作线显示：0=横线（时间格线，默认）1=竖线（竖向放置线）2=全部 3=隐藏
     */
    var gridMode: Int = 0
        set(value) { field = value.coerceIn(0, 3); invalidate() }

    // ----- 屏幕右侧的判定线抽屉（按住浅灰条上/下滑打开，列出所有判定线）-----
    private var panelOpen = false
    private var panelAnim = 0f              // 0=收起 1=完全展开（动画插值）
    private var panelScroll = 0f
    private var panelScrolling = false
    private var headerSwipeDown = false      // 这次触摸按在窗口顶部（页签条）
    private var multiTabX = 0f               // 双指切页签：按下时的参考 x
    private var multiTabArmed = false
    /** 页签切换：dx = 当前横移量；phase 0=静止/跟手拖动 1=旧页签划出 2=新页签划入 */
    private var panelTabDx = 0f
    private var panelTabPhase = 0
    private var panelTabDir = 1f          // +1 = 内容向右滑出（切上一页），-1 = 向左滑出
    private var panelTabPending = -1
    /** 正在横拖页签（内容跟手）；[panelTabDragFromX] = 这次拖动的参考起点 x */
    private var panelTabDragging = false
    private var panelTabDragFromX = 0f
    private var panelDownRow = -1
    private var panelDownY = 0f
    private var panelLastY = 0f
    /** 把手按下但还没打开抽屉（判断是"点"还是"上下滑"） */
    private var barPressed = false
    private var barDownY = 0f
    /** 打开抽屉时算一次各线的音符数（抽屉打开期间不会改谱，够用） */
    private var panelNoteCounts = HashMap<Int, Int>()   // lineId → 音符数
    private var panelDownTime = 0L
    // 判定线行左右滑：左滑=复制（两个圆圈）／右滑=删除
    private var panelDownX = 0f
    // 判定线行上下拖动排序
    private var rowMoveActive = false
    private var rowMoveMoved = false
    private var rowMoveFrom = -1        // 按下时在第几行
    private var rowMoveTarget = -1      // 松手要插到第几行
    private var rowMoveStartY = 0f
    private var rowMoveDy = 0f
    private var rowMoveLastY = 0f
    private var rowMovePending: Runnable? = null
    /** 抽屉页脚"＋ 新建判定线"的左右滑（左=图片线 / 右=文字线） */
    private var footerDragActive = false
    private var footerDragDx = 0f
    // 判定线行：左右滑（左=复制圆圈 / 右=删除）
    /** 判定线多选（抽屉里点出来的红框） */
    val selectedLines = LinkedHashSet<Int>()

    /** 当前要写值的判定线：多选时=选中的全部，否则=活动线 */
    private val editTargetLines: List<Line>
        get() = if (selectedLines.size > 1) lines.filter { it.id in selectedLines } else listOf(activeLine)

    /** 判定线抽屉 / 剪贴板列表：拖动排序时其它行的"动画位移"（缓动到目标，避免瞬间跳一格） */
    private var rowShiftAnim = FloatArray(0)
    private var clipShiftAnim = FloatArray(0)

    private var rowDragActive = false
    private var rowDragRow = -1
    private var rowDragDx = 0f
    private var rowDragTarget = 0        // 0=无 1=复制(不带音符) 2=复制(带音符)

    // ---------- 剪贴板面板（屏幕左侧，与判定线窗口中心对称；左侧灰条滑出） ----------
    private var clipOpen = false
    private var clipAnim = 0f
    private var clipScroll = 0f
    private var clipScrolling = false
    private var clipDownRow = -1
    private var clipDownY = 0f
    private var clipLastY = 0f
    private var clipDownX = 0f
    private var clipMovePending: Runnable? = null
    private var clipMoveActive = false
    private var clipMoveRow = -1
    private var clipMoveStartY = 0f
    private var clipMoveDy = 0f
    private var clipMoveTarget = -1
    private var clipSwipeRow = -1
    private var clipSwipeDx = 0f
    private var clipBarPressed = false
    private var clipBarDownY = 0f
    /** 面板里点"导入" */
    var onClipImport: (() -> Unit)? = null
    /** 面板里右滑某条 → 导出 */
    var onClipExport: ((Int) -> Unit)? = null
    /** 面板里右滑某条 → 导出 */

    // 拖音符碰到遮罩时自动滚时间
    private var edgeScrollActive = false
    private var edgeScrollSpeed = 0f
    /** 抽屉开/关（Activity 用它把顶部/底部按钮的点击让给"关闭抽屉"） */
    var onPanelStateChanged: ((Boolean) -> Unit)? = null

    /** 选中的关键帧时间（-1 = 没选中）；后续的拖动/输入框都改这一帧 */
    private var selectedKfTime: Long = -1L

    /**
     * 面板当前编辑的关键帧（-1 = 改整条线的全局值）。
     * 打关键帧后自动指向这一帧（但不显示红圈），这样刚打完直接改输入框改的就是这一帧；
     * 搓时间轴 / 切判定线就清掉。
     */
    private var editKfTime: Long = -1L
    private val kfEditTime: Long get() = if (selectedKfTime >= 0) selectedKfTime else editKfTime

    /** 正在编辑的判定线姿态：选中关键帧时改关键帧，否则改全局姿态 */
    private fun editTargetPose(): FloatArray {
        val l = activeLine
        if (kfEditTime >= 0) {
            l.keyframes.firstOrNull { it.timeMs == kfEditTime }?.let {
                return floatArrayOf(it.moveX, it.moveY, it.rotate)
            }
        }
        return floatArrayOf(l.moveX, l.moveY, l.rotate)
    }

    // ----- 判定线姿态（活动线；输入面板直接读写它）-----
    var lineMoveX: Float
        get() = editTargetPose()[0]
        set(v) = setActivePose(x = v.coerceIn(-1f, 1f))
    var lineMoveY: Float
        get() = editTargetPose()[1]
        set(v) = setActivePose(y = v.coerceIn(-1f, 1f))
    var lineRotate: Float
        get() = editTargetPose()[2]
        set(v) = setActivePose(r = v)

    /** 判定线流速（选中关键帧时改那一帧的流速，否则改线的基准流速） */
    /** 判定线透明度 0~1（选中关键帧时改那一帧） */
    var lineAlpha: Float
        get() {
            val l = activeLine
            if (kfEditTime >= 0) {
                l.sortedKeyframes().firstOrNull { it.timeMs == kfEditTime }?.let { return it.alpha }
            }
            return l.alpha
        }
        set(v) {
            val a = v.coerceIn(0f, 1f)
            panelEdit {
                for (l in editTargetLines) {
                    if (kfEditTime >= 0 && l.setKeyframeAlpha(kfEditTime, a)) {
                        // 改的是选中帧
                    } else {
                        l.alpha = a
                    }
                }
            }
        }

    var lineSpeed: Float
        get() = if (kfEditTime >= 0)
            (activeLine.keyframes.firstOrNull { it.timeMs == kfEditTime }?.speed ?: activeLine.speed)
        else activeLine.speed
        set(v) {
            val sp = clampLineSpeed(v)
            panelEdit {
                for (l in editTargetLines) {
                    if (kfEditTime >= 0 && l.setKeyframeSpeed(kfEditTime, sp)) {
                        // 改了选中帧的流速
                    } else {
                        l.speed = sp
                        l.invalidate()
                    }
                }
            }
        }

    /** 判定线位置（活动线） */
    var linePosPct: Int
        get() = activeLine.posPct
        set(v) { activeLine.posPct = v.coerceIn(0, 100); invalidate() }

    /**
     * AI「应用项目信息」改判定线位置：**进撤销**。
     * 载入工程时的 [linePosPct] 赋值不走这里（那不是用户操作，不该占撤销步）。
     * 这是撤销覆盖审计补上的唯一遗漏：posPct 在 LineSnap 里本来就支持，只是没接事务。
     */
    fun applyLinePosPct(p: Int) {
        panelEdit { activeLine.posPct = p.coerceIn(0, 100) }
    }

    /**
     * 图片/文字/判定线的"编辑缩放"（右上角输入框 + 画布上的四向箭头）。
     * 1 = 默认大小，小于 1 缩小，大于 1 放大。内部仍写到 RPE 的 scaleX/scaleY。
     *
     * 图片线的默认大小本来是"宽度铺满谱面"（1350 ÷ 图宽），
     * 所以这里把那个值当作基准，输入框里看到的仍然是 1（用户要求默认 = 1）。
     */
    private fun scaleBaseXOf(l: Line): Float {
        if (l.kind != LineKind.Image) return 1f
        val bmp = lineBitmap(l) ?: return 1f
        if (bmp.width <= 0) return 1f
        return (1350f / bmp.width).coerceIn(0.02f, 20f)
    }

    private fun scaleBaseYOf(l: Line): Float {
        if (l.kind != LineKind.Image) return 1f
        val bmp = lineBitmap(l) ?: return 1f
        if (bmp.height <= 0) return 1f
        return (1350f / bmp.width).coerceIn(0.02f, 20f)
    }

    var lineScale: Float
        get() {
            val l = activeLine
            val base = scaleBaseXOf(l)
            val sx = scaleEditValueOf(l)
            return if (base > 0.0001f) (sx / base).coerceIn(0.05f, 20f) else sx.coerceIn(0.05f, 20f)
        }
        set(v) { applyLineScaleToTargets(v.coerceIn(0.05f, 20f)) }

    /**
     * 缩放要落到哪一帧：选中/刚打的帧优先；否则是**此刻正在生效的那一帧**
     * （`time <= 当前时间` 的最后一帧）。整条线没有关键帧时返回 -1 = 改整条线的基准缩放。
     *
     * 注意不能返回 -1 了事：有关键帧时 `scaleAt` 只读关键帧，写整条线的全局值画面根本不变，
     * 以前就是靠"把每一帧都刷成同一个值"来让它看得见 —— 那正是"改一次全部应用"的来源。
     */
    private fun scaleEditKfTime(l: Line): Long {
        val t = kfEditTime
        if (t >= 0 && l.keyframes.any { it.timeMs == t }) return t
        return l.activeKeyframeTimeAt(playMs())
    }

    /** 缩放输入框当前显示的值（= 画面上的值） */
    private fun scaleEditValueOf(l: Line): Float {
        val t = scaleEditKfTime(l)
        if (t < 0) return l.scaleX
        return l.keyframes.firstOrNull { it.timeMs == t }?.scaleX ?: l.scaleX
    }

    /** 把"编辑缩放"写到所有目标线：只改落点那一帧，别的帧一律不动 */
    private fun applyLineScaleToTargets(s: Float) {
        panelEdit {
            for (l in editTargetLines) writeLineScale(l, s)
        }
    }

    private fun writeLineScale(l: Line, s: Float) {
        val sx = scaleBaseXOf(l) * s
        val sy = scaleBaseYOf(l) * s
        // 有关键帧 → 只改这一帧（选中帧 / 此刻生效的那一帧）；没有关键帧 → 改整条线。
        // 绝不再把每一帧都刷成同一个值（那会让缩放看起来"加不进关键帧"）。
        val t = scaleEditKfTime(l)
        if (t >= 0 && l.setKeyframeScale(t, sx, sy)) {
            // 基准值跟着走：以后把关键帧全删了也不会突然弹回旧尺寸
            l.scaleX = sx
            l.scaleY = sy
            return
        }
        l.scaleX = sx
        l.scaleY = sy
        l.invalidate()
    }

    private fun setActivePose(x: Float? = null, y: Float? = null, r: Float? = null) {
        val l = activeLine
        val cur = editTargetPose()
        val nx = x ?: cur[0]
        val ny = y ?: cur[1]
        val nr = r ?: cur[2]
        panelEdit {
            for (ln in editTargetLines) {
                if (kfEditTime >= 0 && ln.setKeyframePose(kfEditTime, nx, ny, nr)) {
                    // 改的是选中帧的姿态
                } else {
                    ln.moveX = nx; ln.moveY = ny; ln.rotate = nr
                }
            }
        }
    }

    var editMode: Boolean = false
        set(value) { field = value; invalidate() }
    /** 判定线编辑模式（长按判定线 0.5s 激活）：拖中心方框=移动，拖箭头=旋转，点方块=打关键帧 */
    var lineEditActive: Boolean = false
        set(value) {
            if (field != value) {
                if (!value) {
                    // 退出判定线编辑前，先把右上角输入框里还没失焦的数值落盘
                    // （拖时间轴/点背景都会走到这里；不落盘会看着像"刚改完就还原"）
                    onLineEditSave?.invoke()
                    selectedKfTime = -1
                    editKfTime = -1              // 退出 = 结束这一帧的编辑，下次改线改的是全局/新帧
                }
                field = value; onLineEditChanged?.invoke(value); invalidate()
            }
        }
    var onLineEditChanged: ((Boolean) -> Unit)? = null      // 编辑模式开/关（Activity 显隐输入面板）
    /** 退出判定线/关键帧编辑前，让 Activity 把输入框里未失焦的数值写回 */
    var onLineEditSave: (() -> Unit)? = null
    var onLinePoseChanged: (() -> Unit)? = null             // 姿态变化（Activity 刷新输入框）
    /** 长按判定线进编辑时需要暂停播放（Activity 绑：暂停 + 按钮文字改「继续」） */
    var onRequestPauseForEdit: (() -> Unit)? = null

    /** 打歌时点了左上角暂停按钮（Phira 同款）：Activity 暂停音乐并弹出 继续/重来/退出 菜单 */
    var onPauseTap: (() -> Unit)? = null
    /** 时间轴缩放（横线视觉距离缩放），默认 0.1，由输入框控制 */
    var timelineScale: Float = 0.1f
    var lineEditHand = 0                 // 0=无 1=移动 2=旋转 3=缩放
    var lineEditStartRot = 0f            // 按下时手指相对线中心的角度（度）
    var lineEditStartAng = 0f            // 按下时判定线旋转角
    var lineEditStartScale = 1f          // 按下时的"编辑缩放"（1 = 默认大小）
    var lineEditStartDist = 1f           // 按下时手指到线中心的距离（缩放用）

    // ---------------------------------------------------------------- 撤销（可回退多次）
    private val undoStack = ArrayDeque<UndoEntry>()
    /** 重做栈：撤销过的步骤；一旦有新改动就清空（标准的两栈撤销/重做） */
    private val redoStack = ArrayDeque<UndoEntry>()
    private var tx: UndoEntry? = null       // 当前手势的事务
    private var undoing = false

    /**
     * 联机锁：在房间里时**不能撤销**（远端更新是整份谱面替换，本地撤销栈的下标会错位）。
     * 本地制谱（不在房间）撤销功能不受影响，满血可用。
     */
    var coopLocked = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {                     // 进房间：旧历史一律作废，避免出来后撤出诡异结果
                    undoStack.clear()
                    redoStack.clear()
                }
            }
        }

    /** 栈深（给 Activity 显示/判断用） */
    val undoDepth: Int get() = if (coopLocked) 0 else undoStack.size
    val redoDepth: Int get() = if (coopLocked) 0 else redoStack.size

    /**
     * 面板输入框改判定线（角度/坐标/流速）也要能撤销：
     * 这些改动不在触摸手势里，所以自己开/关一个事务。
     */
    private fun panelEdit(block: () -> Unit) {
        val own = tx == null
        if (own) beginTx()
        try {
            linesMutate(block)
        } finally {
            if (own) endTx()
        }
        onLinePoseChanged?.invoke()
        invalidate()
    }

    /** 一次触摸（或一次按钮操作）= 一个撤销步 */
    private fun beginTx(init: ((UndoEntry) -> Unit)? = null) {
        if (!undoing && tx == null) {
            tx = UndoEntry()
            init?.invoke(tx!!)
        }
    }

    private fun endTx() {
        val t = tx ?: return
        tx = null
        if (t.isEmpty()) return
        undoStack.addLast(t)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        trimHistoryBudget()
        redoStack.clear()      // 有了新改动，之前的"重做"就作废了
        notifyChartChanged()
    }

    /**
     * 撤销栈的快照内存预算：线快照是**引用拷贝**（LineKeyframe 不可变），本身很快，
     * 但 big song 级谱面一份 = 44 万引用 ≈ 3.5MB，before+after 一步 ~7MB；
     * 200 步全保留最坏 1.4GB 必 OOM。超预算就从最旧开始丢
     * （小谱面几百 KB，永远碰不到预算，撤销深度不受影响）。
     */
    private fun trimHistoryBudget() {
        var refs = 0L
        for (e in undoStack) refs += e.snapRefs()
        while (refs > UNDO_SNAPSHOT_REFS && undoStack.size > 1) {
            refs -= undoStack.removeFirst().snapRefs()
        }
    }

    /** 重做栈同理（undo 时 linesAfter 是当场补拍的，重做栈也要按预算丢） */
    private fun trimHistoryRedoBudget() {
        var refs = 0L
        for (e in redoStack) refs += e.snapRefs()
        while (refs > UNDO_SNAPSHOT_REFS && redoStack.size > 1) {
            refs -= redoStack.removeFirst().snapRefs()
        }
    }

    /** 谱面（含剪贴板）变了 —— 联机模式下用来把整份草稿推给对面 */
    private fun notifyChartChanged() {
        onChartChanged?.invoke()
    }

    /** 追加音符（所有放置路径都走这里） */
    private fun addNote(n: RecordedNote, brandGhost: Boolean = true) {
        // 图片线/文字线不参与判定：任何路径（粘贴、AI、连续铺）都别往上面挂音符
        if (lineOf(n).kind != LineKind.Normal) return
        // 幽灵按键开关打开时，新放的一律标成 fake（粘贴/恢复不受影响）
        val note = if (brandGhost && ghostPlace && !n.fake) n.copy(fake = true) else n
        val wasOverlapSynced = overlapBuilt == noteIdxVersion
        notes.add(note)
        bumpNoteIndex()
        // 只是"追加"一个音符：重叠标记可以增量更新，不用每帧整份重扫
        if (wasOverlapSynced) {
            if (overlapFlagsCache.size < notes.size) {
                overlapFlagsCache = overlapFlagsCache.copyOf(
                    maxOf(notes.size, overlapFlagsCache.size * 2, 64)
                )
            }
            val i = notes.size - 1
            val prev = overlapLast.put(overlapKey(note), i)
            if (prev != null && prev < overlapFlagsCache.size) overlapFlagsCache[prev] = true
            overlapBuilt = noteIdxVersion
        }
        if (!undoing) tx?.noteAdded(note)
        // 长按连续铺谱时一帧就能放几十个：只记前 300 个，别让日志拖慢手感
        if (notes.size <= 300) DebugLog.add(
            "place",
            "type=${note.type} line=${note.lineId} t=${note.startMs} end=${note.endMs} x=${DebugLog.f3(note.posX)} " +
                "above=${if (note.above) 1 else 0} fake=${if (note.fake) 1 else 0} now=${playMs()} " +
                "revSw=${reverseEnabled} ghostSw=$ghostPlace edit=${editMode}"
        )
    }

    /** 按给定下标删除音符（内部降序删，撤销时按原下标插回） */
    private fun removeNotesAt(indices: Collection<Int>) {
        val t = if (undoing) null else tx
        for (i in indices.sortedDescending()) {
            if (i !in notes.indices) continue
            val n = notes.removeAt(i)
            bumpNoteIndex()
            t?.noteRemoved(i, n)
        }
    }

    /** 改写音符（拖动移动），同一手势里同一下标只记第一次旧值 */
    private fun setNote(idx: Int, n: RecordedNote) {
        if (idx !in notes.indices) return
        if (!undoing) tx?.noteModified(idx, notes[idx])
        // 时间/位置/判定线任一变化，重叠判定和分线下标都可能失效 → 重建（每帧最多重建一次）
        val old = notes[idx]
        if (old.lineId != n.lineId || old.startMs != n.startMs || old.posX != n.posX) bumpNoteIndex()
        notes[idx] = n
    }

    /** 改动判定线（打帧/拖线/改流速/增删线）前先整份快照 */
    private fun linesMutate(block: () -> Unit) {
        if (!undoing) tx?.let {
            if (it.linesBefore == null) {
                it.linesBefore = lines.map { l -> l.snapshot() }
                it.activeLineBefore = activeLineId
            }
            // 图层操作（新建空层 / 合并 / 删除）也搭判定线快照一起回退。
            // 集合很小（通常个位数），每次线改动顺手拷一份，省得每个入口单独记。
            if (it.layersBefore == null) it.layersBefore = LinkedHashSet(declaredLayers)
        }
        block()
    }

    /** 改动噪域（拖角/关键帧/显示/深浅/新建/复制/删除/图层）前先整份快照 */
    private fun noiseMutate(block: () -> Unit) {
        if (!undoing) tx?.let {
            if (it.noiseBefore == null) {
                it.noiseBefore = UndoEntry.NoiseSnap(
                    ArrayList(noiseAreas),
                    LinkedHashSet(declaredNoiseLayers),
                    ArrayList(noiseLayerOrder)
                )
            }
        }
        block()
        // 噪域列表/对象被换过：时间索引与运行时都要重建（运行时是惰性的，见 [noiseRuntime]）
        noiseIndex.invalidate()
        noiseRuntimeStale = true
    }

    /** 撤销/重做时整份还原噪域（列表、空层声明、层顺序），并让时间索引/运行时失效 */
    private fun applyNoiseSnap(snap: UndoEntry.NoiseSnap) {
        noiseAreas.clear()
        noiseAreas.addAll(snap.areas)
        declaredNoiseLayers.clear()
        declaredNoiseLayers.addAll(snap.declaredLayers)
        noiseLayerOrder.clear()
        noiseLayerOrder.addAll(snap.order)
        // 编辑中的块可能被删/换了位置：下标失效就退出编辑，避免指针悬空
        if (noiseEditActive && noiseEditIndex !in noiseAreas.indices) stopNoiseEdit()
        noiseIndex.invalidate()
        noiseRuntimeStale = true
        invalidateNoiseRows()
    }

    /** 这个音符要不要被吸附（纯逻辑在 Calibrate 里，方便单测） */
    private fun calibrateTarget(n: RecordedNote, cell: Double, hl: Int): Long =
        Calibrate.target(n.startMs, cell, hl)

    // ---------------------------------------------------------------- 复制 / 剪切 / 粘贴 / 剪贴板面板
    /** 一条剪贴板内容（绝对时间；粘贴时按"中心对齐播放头/屏幕中心"平移） */
    data class ClipEntry(val notes: List<RecordedNote>, val cut: Boolean = false, val id: Int = 0)

    /** 剪贴板条目，**末尾 = 最新**；面板里倒序显示 */
    private val clipEntries = ArrayList<ClipEntry>()


    /** 面板显示用的标题 */
    /** 条目编号（创建时定死，删别的条目不会改变它的名字） */
    private var clipSeq = 0
    private fun newClipId(): Int = ++clipSeq

    /**
     * 剪贴板也要能撤销：把"改之前"的剪贴板记到当前事务上。
     * 触摸开的 tx 和自己开的 tx 都能记（原来的 beginTx{...} 在已有 tx 时会被忽略）。
     */
    private fun clipSnapshot() {
        if (undoing) return
        tx?.let { if (it.clipBefore == null) it.clipBefore = ArrayList(clipEntries) }
    }

    /**
     * 第 [viewIndex] 行（0 = 最新一条）的标题。
     * 面板只画可见的那几行，所以按行算就行 —— 原来是每帧给整张表（最多 30 条）建一串字符串。
     */
    private fun clipLabelAt(viewIndex: Int): String {
        val e = clipEntries.getOrNull(clipEntries.size - 1 - viewIndex) ?: return "剪贴板"
        return "剪贴板 #" + e.id + " · " + e.notes.size + " 个音符" + (if (e.cut) " · 剪切" else "")
    }

    /** 复制选中的音符（追加一条剪贴板记录），复制完清掉红框。 */
    fun copySelection(): Int {
        if (selectedSet.isEmpty()) return 0
        val list = selectedSet.sorted().mapNotNull { notes.getOrNull(it) }
        selectedSet.clear()
        if (list.isEmpty()) { invalidate(); return 0 }
        clipSnapshot()
        clipEntries.add(ClipEntry(list, cut = false, id = newClipId()))
        while (clipEntries.size > CLIP_MAX) clipEntries.removeAt(0)
        invalidate()
        notifyChartChanged()          // 剪贴板也要同步
        return list.size
    }

    /** 长按"复制" = 剪切：复制 + 删掉原音符（一步撤销） */
    fun cutSelection(): Int {
        if (selectedSet.isEmpty()) return 0
        val idx = selectedSet.sorted()
        val list = idx.mapNotNull { notes.getOrNull(it) }
        selectedSet.clear()
        if (list.isEmpty()) { invalidate(); return 0 }
        clipSnapshot()
        clipEntries.add(ClipEntry(list, cut = true, id = newClipId()))
        while (clipEntries.size > CLIP_MAX) clipEntries.removeAt(0)
        beginTx()
        removeNotesAt(idx)
        endTx()
        invalidate()
        return list.size
    }

    /** 粘贴最新的一条 */
    fun pasteClipboard(): Int = pasteEntryIndex(clipEntries.lastIndex)

    /** 粘贴第 [entryIndex] 条（0 = 最旧） */
    fun pasteEntryIndex(entryIndex: Int): Int {
        val e = clipEntries.getOrNull(entryIndex) ?: return 0
        return pasteNotes(e.notes)
    }

    /**
     * 把一组音符整体平移，使**时间中心落在播放头**、**横向中心落在屏幕正中**，
     * 落到当前判定线上，并设为选中（红框出现，可以接着拖动微调）。
     */
    private fun pasteNotes(list: List<RecordedNote>): Int {
        if (list.isEmpty()) return 0
        val minT = list.minOf { it.startMs }
        val maxT = list.maxOf { it.startMs }
        val minX = list.minOf { it.posX }
        val maxX = list.maxOf { it.posX }
        val dT = playMs() - (minT + maxT) / 2
        val dX = -(minX + maxX) / 2f
        val target = activeLineId
        beginTx()
        val added = ArrayList<Int>(list.size)
        for (cn in list) {
            val st = (cn.startMs + dT).coerceAtLeast(0L)
            val en = if (cn.type == 2) (cn.endMs + dT).coerceAtLeast(st) else st
            val px = (cn.posX + dX).coerceIn(-1f, 1f)
            addNote(cn.copy(startMs = st, endMs = en, posX = px, lineId = target), brandGhost = false)
            added.add(notes.lastIndex)
        }
        endTx()
        selectedSet.clear()
        selectedSet.addAll(added)
        DebugLog.add("paste", "粘贴 ${added.size} 个 dT=$dT dX=${DebugLog.f3(dX)} line=$target")
        invalidate()
        return added.size
    }

    fun removeClipEntry(entryIndex: Int) {
        if (entryIndex !in clipEntries.indices) return
        clipSnapshot()
        clipEntries.removeAt(entryIndex)
        invalidate()
        notifyChartChanged()
    }

    /** 清空剪贴板（可撤销） */
    fun clearClipboard() {
        if (clipEntries.isEmpty()) return
        beginTx()
        clipSnapshot()
        clipEntries.clear()
        endTx()
        invalidate()
    }

    /** 导出某条的文本（.frclip） */
    fun exportClipEntry(entryIndex: Int): String? {
        val e = clipEntries.getOrNull(entryIndex) ?: return null
        return ClipCodec.encode(e.notes)
    }

    /** 导入 .frclip 文本，成功返回新增音符数 */
    fun importClipText(text: String): Int {
        val list = ClipCodec.decode(text)
        if (list.isEmpty()) return 0
        clipSnapshot()
        clipEntries.add(ClipEntry(list, cut = false, id = newClipId()))
        while (clipEntries.size > CLIP_MAX) clipEntries.removeAt(0)
        invalidate()
        notifyChartChanged()
        return list.size
    }

    fun loadClipEntries(list: List<ClipEntry>) {
        clipEntries.clear()
        // 同步过来的条目没有本地编号（id=0）→ 现场发一个，不然面板里全叫「#0」
        for (e in list) clipEntries.add(if (e.id == 0) e.copy(id = newClipId()) else e)
        while (clipEntries.size > CLIP_MAX) clipEntries.removeAt(0)
        invalidate()
    }

    /** 自动校准会影响多少个音符（只算不改，给确认弹窗用） */
    fun countCalibratable(): Int {
        val hl = hLines.coerceAtLeast(1)
        if (hl < 2) return 0
        val cell = cellMs()
        if (cell <= 0.0) return 0
        var n = 0
        for (x in notes) if (calibrateTarget(x, cell, hl) >= 0) n++
        return n
    }

    /**
     * 自动校准：实时放置时手抖，音符容易落在"离粗线一格"的细线上。
     * 把这些音符吸附到最近的那条粗线（长条只挪头，长度保持不变）。
     * 整批算**一个撤销步**。返回改动的音符数。
     */
    fun autoCalibrate(): Int {
        val hl = hLines.coerceAtLeast(1)
        if (hl < 2) return 0
        val cell = cellMs()
        if (cell <= 0.0) return 0
        var changed = 0
        val moved = BooleanArray(notes.size)          // 被校准挪过的音符
        beginTx()
        for (i in notes.indices) {
            val n = notes[i]
            val t = calibrateTarget(n, cell, hl)
            if (t < 0) continue
            val delta = t - n.startMs
            val end = if (n.type == 2) (n.endMs + delta).coerceAtLeast(t) else t
            setNote(i, n.copy(startMs = t, endMs = end))
            moved[i] = true
            changed++
        }
        // 校准完可能和别的音符完全重合（同一条线 + 同类型 + 同一时间 + 同一横坐标）
        // → 删掉**被校准的那个**，留下原来就在那儿的，避免出现两个叠在一起的蓝键/黄键/长条
        if (changed > 0) {
            // 被校准挪到粗线上的音符 = 用户刚放的那个；原来就在粗线上的那个是旧的。
            // 两者现在完全重合（同线/同类型/同时间/同横坐标）→ **删掉旧的（粗线上那个）**，
            // 保留刚校准过来的音符（它的 pass/长条长度/正反面才是最新的）。
            val movedKeys = HashSet<Long>()
            for (i in notes.indices) if (moved[i]) movedKeys.add(dupKey(notes[i]))
            val drop = ArrayList<Int>()
            for (i in notes.indices) {
                if (moved[i] || i >= notes.size) continue
                if (dupKey(notes[i]) in movedKeys) drop.add(i)
            }
            if (drop.isNotEmpty()) {
                removeNotesAt(drop)
                changed -= drop.size
                DebugLog.add("calibrate", "删除重合音符 ${drop.size} 个")
            }
        }
        endTx()
        DebugLog.add("calibrate", "hLines=$hl 吸附 $changed / ${notes.size} 个音符")
        invalidate()
        return changed
    }

    /** 判断"完全重合"用的 key：同一条线 + 同类型 + 同一时间 + 同一横坐标 */
    private fun dupKey(n: RecordedNote): Long {
        val pos = ((n.posX + 1f) * 1000f).toInt().coerceIn(0, 2000).toLong()
        return ((n.lineId.toLong() * 31L + n.type) * 1_000_000_007L + n.startMs) * 4096L + pos
    }

    /**
     * 整谱替换（AI 写谱 / 修谱 / 缤纷）之后，把「替换前」的谱面记成一步撤销，
     * 这样普通的「撤销」按钮也能回退 AI 的改动（applyDraft 本身会清空撤销栈）。
     */
    fun seedUndoForReplace(beforeDraft: String) {
        val d = DraftCodec.decode(beforeDraft) ?: return
        val e = UndoEntry()
        // 撤销是**逆序**重放：先删掉当前音符，再按原下标插回替换前的音符。
        // 因此存储顺序必须是：替换前音符（下标倒序）在前，当前音符在后。
        for (i in d.notes.indices.reversed()) e.noteRemoved(i, d.notes[i])
        for (n in notes) e.noteAdded(n)
        e.linesBefore = d.lines.map { it.snapshot() }
        e.layersBefore = LinkedHashSet(d.declaredLayers)
        // 噪域图层：空层声明 + 按草稿里的块顺序重建层顺序（撤销后要原样回来）
        val noiseOrder = LinkedHashSet<String>()
        for (a in d.noise) noiseOrder.add(a.layerId)
        noiseOrder.addAll(d.declaredNoiseLayers)
        e.noiseBefore = UndoEntry.NoiseSnap(
            ArrayList(d.noise),
            LinkedHashSet(d.declaredNoiseLayers),
            ArrayList(noiseOrder)
        )
        e.activeLineBefore = activeLineId
        undoStack.addLast(e)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
    }

    /** 把判定线整份还原成快照（撤销/重做共用） */
    private fun applyLineSnaps(snaps: List<UndoEntry.LineSnap>, activeId: Int) {
        val byId = snaps.associateBy { it.id }
        // 删掉的线要补回来、加的线要删掉
        lines.removeAll { it.id !in byId }
        for (s in snaps) {
            val l = lines.firstOrNull { it.id == s.id } ?: Line(s.id).also { lines.add(it) }
            l.restore(s)
        }
        lines.sortBy { it.id }
        if (lines.isEmpty()) lines.add(Line(0))
        activeLineId = if (byId.containsKey(activeId)) activeId else lines[0].id
    }

    /** 撤销/重做之后的收尾（选中态、索引、通知、重绘） */
    private fun finishHistoryStep() {
        selectedSet.clear()
        selectedKfTime = -1
        playSortedIdx = IntArray(0)
        // 噪域撤销后时间索引/行缓存都要重建；编辑中且下标还有效就刷新右上角信息
        noiseIndex.invalidate()
        invalidateNoiseRows()
        if (noiseEditActive) notifyNoiseInfo()
        onLinesChanged?.invoke()
        onLinePoseChanged?.invoke()
        notifyChartChanged()          // 撤销也要同步给对面，否则两边会不一致
        invalidate()
    }

    /** 撤销一步；没有可撤销的返回 false */
    fun undo(): Boolean {
        if (coopLocked) return false          // 联机中不撤销
        val e = undoStack.removeLastOrNull() ?: return false
        DebugLog.add("undo", "ops=${e.ops.size} lines=${e.linesBefore?.size ?: 0}")
        // 撤销前先记住"撤销之后应该回到什么状态"，这样长按撤销 = 重做
        e.captureAfter(notes)
        e.linesAfter = lines.map { it.snapshot() }
        e.layersAfter = HashSet(declaredLayers)
        e.activeLineAfter = activeLineId
        e.clipAfter = ArrayList(clipEntries)
        e.noiseAfter = UndoEntry.NoiseSnap(
            ArrayList(noiseAreas),
            LinkedHashSet(declaredNoiseLayers),
            ArrayList(noiseLayerOrder)
        )
        undoing = true
        try {
            // 音符：撤追加 → 还原改写 → 按原下标插回删除的（见 UndoEntry.undoNotes）
            e.undoNotes(notes)
            bumpNoteIndex()
            e.clipBefore?.let { clipEntries.clear(); clipEntries.addAll(it) }
            // 判定线：整份还原（含增删线 / 关键帧 / 流速）；空图层声明一并还原
            e.linesBefore?.let { applyLineSnaps(it, e.activeLineBefore) }
            e.layersBefore?.let { declaredLayers.clear(); declaredLayers.addAll(it) }
            e.noiseBefore?.let { applyNoiseSnap(it) }
        } finally {
            undoing = false
        }
        finishHistoryStep()
        redoStack.addLast(e)
        while (redoStack.size > UNDO_LIMIT) redoStack.removeFirst()
        trimHistoryRedoBudget()
        return true
    }

    /** 重做一步（长按「撤销」）：把刚才撤掉的那一步再做回来 */
    fun redo(): Boolean {
        if (coopLocked) return false
        val e = redoStack.removeLastOrNull() ?: return false
        DebugLog.add("redo", "ops=${e.ops.size} lines=${e.linesAfter?.size ?: 0}")
        undoing = true
        try {
            e.redoNotes(notes)
            bumpNoteIndex()
            e.clipAfter?.let { clipEntries.clear(); clipEntries.addAll(it) }
            e.linesAfter?.let { applyLineSnaps(it, e.activeLineAfter) }
            e.layersAfter?.let { declaredLayers.clear(); declaredLayers.addAll(it) }
            e.noiseAfter?.let { applyNoiseSnap(it) }
        } finally {
            undoing = false
        }
        finishHistoryStep()
        undoStack.addLast(e)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        trimHistoryBudget()
        return true
    }

    /** 编辑模式：被框住的音符集合（画红框），可多选；点红框音符=删除全部，拖红框音符=整体移动 */
    private val selectedSet = mutableSetOf<Int>()
    private var boxActive = false          // 长按1s膨胀动画进行中
    private var boxStartedAt = 0L
    private var boxX0 = 0f; private var boxY0 = 0f
    private var boxX1 = 0f; private var boxY1 = 0f
    private var selectingBox = false       // 正在拉白色预览框
    /**
     * 制作谱子界面是否把双押音符换成 *_mh 贴图（默认关）。
     * 打歌（Autoplay）界面不受这个开关影响，始终用 mh 贴图。
     */
    var editDoubleHint: Boolean = false
        set(value) { field = value; invalidate() }

    /** 平滑移动：开启后放置/移动不吸附横竖线交叉点（默认吸附） */
    var smoothMove: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * 快速切换：开启后，在编辑模式下点判定线本体 = 切到那条线（不再放音符）；
     * 关闭时只能用右侧抽屉换线。默认关。
     */
    var quickSwitch: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * 放置兼容（针对手机误触）：
     *  0 = 兼容关闭：拖动/删除只能点中音符本身
     *  1 = 红框兼容：音符已出现红框后，红框及其内部区域都能拖动/删除；第一次选中仍要点到音符
     *  2 = 全部兼容：红框区域本身就能触发选中（不用点中音符），之后红框及内部都能拖动/删除
     */
    var compatMode: Int = 0
        set(value) { field = value.coerceIn(0, 2); invalidate() }

    /**
     * 全量模式（给用户自己写谱用，和 AI 无关）：
     * 单击 = 蓝键；长按不动 = 长条；长按并左右拖 = 连在一起的黄键；
     * 短滑（上/下/左/右）= 红键。
     */
    var fullMode: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * 音符列表模式（「更多」里开关，默认关）。
     * 打开后：点红框里的音符**不再删除**，改为在音符右侧弹出信息面板
     * （删除 + 类型/时间/X/速度/大小/透明度/偏移/可见时间/判定范围/颜色/音效…）。
     * 框中一群音符时，面板改的是**全部**被框住的音符。
     */
    var noteInfoMode: Boolean = false
        set(value) { field = value; invalidate() }

    /** 音符信息面板请求弹出：参数 = 锚点（音符中心，本 View 坐标） */
    var onNoteInfo: ((Float, Float) -> Unit)? = null

    /** 判定线抽屉页脚：左滑"新建判定线"= 选图片（Activity 弹文件选择） */
    var onRequestImageLine: (() -> Unit)? = null

    /** 判定线抽屉页脚：右滑"新建判定线"= 新建文字线（Activity 弹输入框） */
    var onRequestTextLine: (() -> Unit)? = null


    /** 想在图片线/文字线上放音符时提示（这类线不参与判定，放了也没用） */
    var onPlaceBlocked: ((LineKind) -> Unit)? = null
    /** 时间→像素比例（活动判定线在 now 时刻的瞬时值；onDraw 刷新） */
    var pxPerMs: Float = 0.1f * (600f / 720f) * 0.1f

    /**
     * 拖动/搓时间轴用的「每毫秒多少像素」。
     *
     * ★ 不能用 `pxPerMs`：那是**活动判定线此刻的瞬时值**（画网格/关键帧用它是对的），
     * 官谱的 speedEvents 大量是
     * 0 或 0.001（用来做定格），那一刻 pxPerMs ≈ 0 ⇒ 除以 1e-4 之后
     * **手指划 1px 就是 10 秒**，一划就飞到谱面尽头（而拖进度条是绝对定位，所以正常）。
     * 这里改成「不跟流速走的固定标尺」：只跟缩放和屏幕高度走，且夹在一个能用的区间里。
     * 视觉上音符确实可能比这个快/慢，但拖动的手感首先要稳定。
     */
    private val scrubPxPerMs: Float
        get() = (0.16f * (height / 720f) * timelineScale).coerceIn(
            0.004f,    // 最慢：0.25ms/px，一屏(约 2000px) 走 500ms 一级，够细
            0.16f      // 最快：6.25ms/px，划 1px 不会跳几十秒
        )

    // ----- 打歌模式（点"AUTOPLAY"后从当前位置开始自动演出）-----
    var playMode: Boolean = false
        private set

    /** 已判定的音符下标：用 BooleanArray + 计数，不用 HashSet<Int>。
     * 打歌时每帧要按音符逐个查（几千个音符 × 60fps），HashSet<Int> 的 `i in set`
     * 每次都要把下标装箱（超过 127 就是新对象），一秒能堆出几十万个小对象。
     */
    private var judgedFlags = BooleanArray(0)
    private var judgedCount = 0

    private fun resetJudged() {
        if (judgedFlags.size != notes.size) judgedFlags = BooleanArray(notes.size)
        else java.util.Arrays.fill(judgedFlags, false)
        judgedCount = 0
    }

    private fun markJudged(i: Int) {
        if (i < 0 || i >= judgedFlags.size) return
        if (!judgedFlags[i]) { judgedFlags[i] = true; judgedCount++ }
    }

    private fun judgedAt(i: Int): Boolean = i >= 0 && i < judgedFlags.size && judgedFlags[i]

    // 每条判定线自己的音符下标（画的时候只遍历自己那几个，不用把所有音符扫一遍）
    private var noteIdxVersion = 0
    private var noteIdxBuilt = -1
    private var noteIdxIds = IntArray(0)
    private var noteIdxArrays = arrayOfNulls<IntArray>(0)
    private val noteIdxEmpty = IntArray(0)

    private fun bumpNoteIndex() {
        noteIdxVersion++
        hudTotal = -1                    // 音符变了：HUD 的音符总数缓存作废
    }

    // ----- HUD 缓存：分数/连击每帧都要画，别每帧 String.format + 扫一遍所有音符 -----
    /** 参与计分的音符数（-1 = 还没算过） */
    private var hudTotal = -1
    private var hudScoreValue = -1
    private var hudScoreText = "0000000"
    private var hudComboValue = -1
    private var hudComboText = "0"

    private var overlapBuilt = -1
    private var overlapFlagsCache = BooleanArray(0)
    /** 每个重叠 key 最后出现在哪个下标：追加音符时可以增量维护，不用整份重扫 */
    private val overlapLast = HashMap<Long, Int>(128)

    /**
     * 哪些音符"被后来放的音符完全盖住"（同一判定线 + 同一时间 + 同一横向位置）。
     * 这些音符渲染时斜 45° 并套橙框，最新放的那个保持正常。
     */
    private fun overlapFlags(): BooleanArray {
        if (overlapBuilt != noteIdxVersion) {
            if (overlapFlagsCache.size < notes.size) {
                overlapFlagsCache = BooleanArray(
                    maxOf(notes.size, overlapFlagsCache.size * 2, 64)
                )
            } else {
                java.util.Arrays.fill(overlapFlagsCache, 0, notes.size, false)
            }
            overlapLast.clear()
            for (i in notes.indices) {
                val prev = overlapLast.put(overlapKey(notes[i]), i)
                if (prev != null) overlapFlagsCache[prev] = true
            }
            overlapBuilt = noteIdxVersion
        }
        return overlapFlagsCache
    }

    private fun overlapKey(n: RecordedNote): Long {
        val posBucket = ((n.posX + 1f) * 1000f).toInt().coerceIn(0, 2000)
        return (n.lineId.toLong() * 1_000_000_007L + n.startMs) * 4096L + posBucket
    }

    private fun indexesForLine(lineId: Int): IntArray {
        if (noteIdxBuilt != noteIdxVersion) rebuildNoteIndex()
        // 线数通常个位数，但大谱面能到 66 条以上、每帧每条线都要来一次 ⇒ 线性扫不划算。
        // 少量线时直接线性扫（比建 HashMap 便宜），多了才用查表。
        if (noteIdxIds.size <= 8) {
            for (k in noteIdxIds.indices) if (noteIdxIds[k] == lineId) return noteIdxArrays[k] ?: noteIdxEmpty
            return noteIdxEmpty
        }
        val m = noteIdxMap
        if (m != null) {
            val k = m[lineId]
            if (k != null && k >= 0 && k < noteIdxArrays.size) return noteIdxArrays[k] ?: noteIdxEmpty
            return noteIdxEmpty
        }
        for (k in noteIdxIds.indices) if (noteIdxIds[k] == lineId) return noteIdxArrays[k] ?: noteIdxEmpty
        return noteIdxEmpty
    }

    /**
     * 按判定线分组音符下标：两趟计数 + 原生 IntArray。
     * 以前是 HashMap<Int, ArrayList<Int>>：拖动音符时每帧都要重建一次，
     * 几千个音符就是几千次装箱 + 几千个 ArrayList 节点，GC 一抖手感就慢半拍。
     *
     * ★ 每条线内**按 startMs 升序**排。这是视锥剔除的前提：
     *   `drawNotesOfLine` 拿到该线此刻在屏幕上的时间窗后，可以直接二分出
     *   「第一个进窗的音符」和「最后一个」，中间的才需要画。官谱一张 1397 个音符 /
     *   66 条线，不做剔除时每条线每帧都要把该线全部音符过一遍 `drawNoteAt`
     *   （里面还有 3 次迭代的流速逆积分数值解）。
     */
    private fun rebuildNoteIndex() {
        val n = notes.size
        val ids = ArrayList<Int>(8)
        val counts = ArrayList<Int>(8)
        for (i in 0 until n) {
            val id = notes[i].lineId
            var k = 0
            while (k < ids.size && ids[k] != id) k++
            if (k == ids.size) { ids.add(id); counts.add(0) }
            counts[k] = counts[k] + 1
        }
        val arrays = arrayOfNulls<IntArray>(ids.size)
        val fill = IntArray(ids.size)
        for (k in ids.indices) arrays[k] = IntArray(counts[k])
        for (i in 0 until n) {
            val id = notes[i].lineId
            var k = 0
            while (k < ids.size && ids[k] != id) k++
            if (k < ids.size) arrays[k]?.set(fill[k]++, i)
        }
        // 线内按 startMs 升序。用手写快排而不是 sortBy：
        // sortBy 会把 IntArray 装箱成 Array<Int>，一万个音符就是一万个 Integer。
        // 这里排序键读的是 notes[idx].startMs，比较时按「时间 + 原下标」做稳定键，
        // 免得同一时刻的音符互换位置让「后来的盖住先来的」重叠判定跟着抖。
        for (k in ids.indices) {
            val arr = arrays[k] ?: continue
            sortIdxByStart(arr)
        }
        noteIdxIds = ids.toIntArray()
        noteIdxArrays = arrays
        // 线多的时候建一张 id → 下标 的查表（避免每帧每条线都线性扫一遍）
        noteIdxMap = if (ids.size > 8) {
            val m = HashMap<Int, Int>(ids.size * 2)
            for (k in ids.indices) m[ids[k]] = k
            m
        } else null
        noteIdxBuilt = noteIdxVersion
    }

    /** 线的 id → 它在 [noteIdxIds] 里的下标（线少时为 null，走线性扫） */
    private var noteIdxMap: HashMap<Int, Int>? = null

    /**
     * 每条线上**最长的那根长条**有多长（毫秒）。
     *
     * 视锥剔除按 `startMs` 二分，但长条是「头部时间 → 尾部时间」的一段：
     * 头部早已出窗、尾巴还挂在屏幕上时必须照画。所以下界要往回退这么多。
     * 跟着 [noteIdxVersion] 缓存，音符变了才重算。
     */
    private var holdLenByLine: HashMap<Int, Long>? = null
    private var holdLenBuilt = -1
    /** 每条线上最大的 |yOffset|（RPE 像素）：视锥剔除要把它折算成额外的时间余量 */
    private var maxYOffByLine: HashMap<Int, Float>? = null

    private fun maxHoldLenOf(lineId: Int): Long {
        if (holdLenBuilt != noteIdxVersion) {
            holdLenByLine = NoteFrustum.maxHoldLenByLine(notes)
            val yo = HashMap<Int, Float>(16)
            for (n in notes) {
                val a = if (n.yOffset.isFinite()) Math.abs(n.yOffset) else 0f
                if (a <= 0f) continue
                val cur = yo[n.lineId]
                if (cur == null || a > cur) yo[n.lineId] = a
            }
            maxYOffByLine = yo
            holdLenBuilt = noteIdxVersion
        }
        return holdLenByLine?.get(lineId) ?: 0L
    }

    /** 这条线上最大的 |yOffset|（没有就是 0） */
    private fun maxYOffsetOf(lineId: Int): Float {
        if (holdLenBuilt != noteIdxVersion) maxHoldLenOf(lineId)
        return maxYOffByLine?.get(lineId) ?: 0f
    }

    /** 线内按下标排序（实现搬到 [NoteFrustum.sortIdxByStart]，纯 JVM 可单测） */
    private fun sortIdxByStart(arr: IntArray) = NoteFrustum.sortIdxByStart(arr, notes)

    /** 每帧复用的姿态/坐标缓存（避免每帧、每音符 new 数组） */
    private val framePose = FloatArray(3)
    private val scratchPos = FloatArray(2)
    private val scratchPose = FloatArray(3)

    /** 打歌时按时间排序的音符下标（双押判定用；开场建一次，谱面没变就一直复用） */
    private var playSortedIdx: IntArray = IntArray(0)
    private var combo = 0
    private var hitCount = 0
    private var lastJudgeNow = 0L
    /** 正在通过判定线的长条持续特效（头部到线 → 尾部离线的整段时间） */
    private class HoldFx(val idx: Int, val untilMs: Long)
    private val holdFxList = mutableListOf<HoldFx>()
    private val judgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    /** 打歌时每秒记一次"实际画出来的长条"（排查长条残留） */
    private var dbgLogFrame = false
    /** 判定线抽屉的文字笔 */
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- 判定线几何
    /** 判定线基准 y（屏幕坐标，未叠加关键帧位移） */
    private fun lineBaseY(l: Line): Float = height * (l.posPct / 100f)

    /** 活动线基准 y（网格/关键帧条用） */
    private val judgeLineY: Float get() = lineBaseY(activeLine)

    /** 父线合成用的临时缓冲（递归时父子各用一个，互不踩） */
    private val frameTmp = FloatArray(3)

    /**
     * 判定线的最终屏幕姿态（含父线合成，Phira fetch_pos / fetch_rot 语义）：
     * out[0]=中心x out[1]=中心y out[2]=旋转（度，逆时针为正）。
     *  - 位置：父线位置 + 按**父线完整旋转**旋转过的自身平移（始终跟随父线，与 rotWithFather 无关）
     *  - 旋转：自身旋转 +（rotWithFather 时叠加父线完整旋转）
     *  无父线时与"posPct + moveX/moveY/rotate"完全一致（旧谱面行为不变）。
     */
    private fun lineFrameOf(l: Line, now: Long, out: FloatArray, depth: Int = 0) {
        poseOf(l, now, out)
        // 自身平移（px，相对屏幕中心）：RPE 没有百分比位置，全靠 moveY —— 这里的
        // (lineBaseY − 半屏高) 就是 posPct 相对屏幕中心的偏移，跟随父线时一起被旋转
        val tx = out[0] * width / 2f
        val ty = (lineBaseY(l) - height / 2f) + out[1] * height / 2f
        var cx = width / 2f + tx
        var cy = height / 2f + ty
        var rot = out[2]
        val fid = l.fatherId
        if (fid >= 0 && fid != l.id && depth < 8) {
            lines.firstOrNull { it.id == fid }?.let { p ->
                lineFrameOf(p, now, frameTmp, depth + 1)
                // 逆时针旋转（屏幕 y 向下）：(x cos + y sin, −x sin + y cos)
                val a = Math.toRadians(frameTmp[2].toDouble())
                val cos = Math.cos(a).toFloat()
                val sin = Math.sin(a).toFloat()
                cx = frameTmp[0] + tx * cos + ty * sin
                cy = frameTmp[1] - tx * sin + ty * cos
                if (l.rotWithFather) rot += frameTmp[2]
            }
        }
        out[0] = cx; out[1] = cy; out[2] = rot
    }

    // ------------------------------------------------- Phira 兼容：音符级属性 / ctrl 对象 / incline

    /**
     * 音符到判定线的当前距离（RPE 落点距离 px，正 = 还没到线 = 在线上方）。
     * 即 Phira 的 (note.height − line.height) × 450：1 流速秒 = 120px，所以 1ms × 流速 1 = 0.12px。
     * **不含**音符自己的 speed/ctrl 乘数（ctrl 对象就是按这个原始距离求值的）。
     */
    private fun noteDistRpe(l: Line, noteMs: Long, now: Long): Float {
        // 单槽缓存：同一帧里 ctrl 求值/位移/绘制会反复问同一个 (线, 音符, 时刻)，
        // heightAt 是 O(log n) 二分，热路径上能省一半以上
        if (l === distMemoLine && noteMs == distMemoNoteMs && now == distMemoNow) return distMemoVal
        val v = ((l.speedTrack().heightAt(noteMs) - l.speedTrack().heightAt(now)) * 0.12).toFloat()
        distMemoLine = l; distMemoNoteMs = noteMs; distMemoNow = now; distMemoVal = v
        return v
    }

    private var distMemoLine: Line? = null
    private var distMemoNoteMs: Long = 0
    private var distMemoNow: Long = -1
    private var distMemoVal = 0f

    /** ctrl 轨道的求值坐标：落点距离 + yOffset（Phira init_ctrl_obj） */
    private fun ctrlEvalX(l: Line, n: RecordedNote, now: Long): Float =
        noteDistRpe(l, n.startMs, now) + n.yOffset

    private fun ctrlYOf(l: Line, n: RecordedNote, now: Long): Float =
        l.ctrlYTrack()?.valueAt(ctrlEvalX(l, n, now)) ?: 1f

    private fun ctrlPosOf(l: Line, n: RecordedNote, now: Long): Float =
        l.ctrlPosTrack()?.valueAt(ctrlEvalX(l, n, now)) ?: 1f

    private fun ctrlSizeOf(l: Line, n: RecordedNote, now: Long): Float =
        l.ctrlSizeTrack()?.valueAt(ctrlEvalX(l, n, now)) ?: 1f

    private fun ctrlAlphaOf(l: Line, n: RecordedNote, now: Long): Float =
        l.ctrlAlphaTrack()?.valueAt(ctrlEvalX(l, n, now)) ?: 1f

    /**
     * 非长条音符的横向位置乘数 = posControl × incline_val（Phira now_transform；
     * 长条整体（头/身/尾）都用 base=0 的变换，不吃 incline 和 posControl）。
     * incline_val = 1 − sin(incline) × 视觉落点距离 / 360 —— 越远越向判定线中心收缩。
     */
    private fun noteXFactor(l: Line, n: RecordedNote, now: Long): Float {
        if (n.type == 2) return 1f
        var f = ctrlPosOf(l, n, now)
        val deg = l.inclineAt(now)
        if (deg != 0f) {
            val sin = Math.sin(Math.toRadians(deg.toDouble())).toFloat()
            // (base×aspect + tr.y) × 450 = R×spd + yOffset×speed（RPE 落点距离）
            val visual = noteDistRpe(l, n.startMs, now) * (n.speed * ctrlYOf(l, n, now)) + n.yOffset * n.speed
            f *= 1f - sin * visual / 360f
        }
        return f
    }

    /** 任意线的姿态写进 out[0..2]；正在编辑的那条线若在编辑关键帧，就显示该帧的姿态 */
    fun poseOf(l: Line, now: Long, out: FloatArray) {
        if (lineEditActive && l.id == activeLineId) {
            val t = kfEditTime
            if (t >= 0) {
                val kf = l.keyframes.firstOrNull { it.timeMs == t }
                if (kf != null) { out[0] = kf.moveX; out[1] = kf.moveY; out[2] = kf.rotate; return }
            }
            out[0] = l.moveX; out[1] = l.moveY; out[2] = l.rotate
            return
        }
        l.poseInto(now, out)
    }

    /** 任意线的缩放写进 out[0..1]（正在编辑关键帧时显示该帧的缩放，否则显示此刻生效的缩放） */
    private val scaleScratch = FloatArray(2)

    fun scaleOf(l: Line, now: Long, out: FloatArray) {
        val t = if (lineEditActive && l.id == activeLineId) kfEditTime else -1L
        if (t >= 0) {
            val kf = l.keyframes.firstOrNull { it.timeMs == t }
            if (kf != null) { out[0] = kf.scaleX; out[1] = kf.scaleY; return }
        }
        // 没选帧：按时间轴取关键帧插值（没有关键帧时就是整条线的基准缩放）。
        // 以前这里直接读基准值，于是"有关键帧的线改缩放画面不动"，才逼着写入端把所有帧一起改。
        l.scaleAt(now, out)
    }

    /** 此刻画面上真正显示的透明度（正在编辑某一帧时就是那一帧的值）——打关键帧时用它钉住，别把渐变打断 */
    private fun alphaShownOf(l: Line, now: Long): Float {
        val t = kfEditTime
        if (t >= 0) l.keyframes.firstOrNull { it.timeMs == t }?.let { return it.alpha }
        return l.alphaAt(now)
    }

    /** 判定线中心屏幕坐标（含父线合成） */
    fun lineCenterOf(l: Line, now: Long): FloatArray {
        lineFrameOf(l, now, scratchPose)
        return floatArrayOf(scratchPose[0], scratchPose[1])
    }

    /** 点到旋转后判定线（无限长直线）的距离（含父线合成） */
    fun distToLineOf(l: Line, x: Float, y: Float, now: Long): Float {
        lineFrameOf(l, now, scratchPose)
        val lcX = scratchPose[0]
        val lcY = scratchPose[1]
        val a = Math.toRadians(-scratchPose[2].toDouble())
        val dx = Math.cos(a).toFloat()
        val dy = Math.sin(a).toFloat()
        return Math.abs((x - lcX) * dy - (y - lcY) * dx)
    }

    /** 离触点最近的判定线（放音符/长按编辑都按这条线） */
    fun nearestLine(x: Float, y: Float, now: Long): Line {
        var best = lines[0]
        var bestD = Float.MAX_VALUE
        for (l in lines) {
            val d = distToLineOf(l, x, y, now)
            if (d < bestD) { bestD = d; best = l }
        }
        return best
    }

    fun lineOf(n: RecordedNote): Line = lines.firstOrNull { it.id == n.lineId } ?: lines[0]

    fun activateLine(id: Int) {
        editKfTime = -1L
        if (lines.none { it.id == id }) return
        if (activeLineId != id) {
            DebugLog.add("line", "activate id=$id")
            activeLineId = id
            selectedKfTime = -1
            onLinePoseChanged?.invoke()
            onLinesChanged?.invoke()
            invalidate()
        }
    }

    /**
     * 聚焦一条判定线并进入编辑 —— **画布上长按判定线** 和 **抽屉里长按判定线框** 都走这里，
     * 两边行为必须完全一致：切活动线（视角/网格/音符跟着它）+ 调出右上角 5 个输入框 +
     * 显示时间轴与关键帧 + 隐藏左侧灰条与剪贴板手势 + 把时间轴挪到它的关键帧上。
     */
    private fun focusLineForEdit(id: Int) {
        if (lines.none { it.id == id }) return
        activateLine(id)
        if (playMode) return            // 打歌模式不切编辑（只切活动线）
        // 从画布长按进编辑时，右侧抽屉里对应的判定线框也要同步"框住"（和抽屉里长按同一入口、
        // 同一状态）：否则打开抽屉看不到正在编辑哪条线。写进 selectedLines 而不是靠
        // activeLineId 常驻红框，取消框选时才能真正去掉（不会留下"取消不掉的框"）。
        selectedLines.add(id)
        // 编辑要在暂停状态下进行：录制/播放中长按 → 先让 Activity 暂停（按钮文字同步变「继续」）
        if (!editMode) {
            onRequestPauseForEdit?.invoke()
            editMode = true
        }
        // 不强制跳回"判定线"页签：长按图片线/文字线就留在图片/文字页签（用户点名）
        lineEditActive = true
        lineEditHand = 0
        // 抽屉**不关**（用户要求：进编辑不用收窗口）。红框留在列表里，点抽屉空白处可以取消框选。
        seekToLineKeyframe(activeLine)
        onLinePoseChanged?.invoke()
        onLinesChanged?.invoke()
        invalidate()
    }

    /**
     * 把时间轴挪到判定线位置：跳到离当前播放点最近的那个关键帧。
     * 关键帧条是以判定线中心为原点按时间画的，跳过去之后那颗关键帧正好压在判定线上，
     * 「显示时间轴和关键帧」才有东西可看（没有关键帧就不动时间）。
     */
    private fun seekToLineKeyframe(l: Line) {
        val kfs = l.sortedKeyframes()
        if (kfs.isEmpty()) return
        val now = playMs()
        var best = kfs[0]
        var bestD = Math.abs(best.timeMs - now)
        for (k in kfs) {
            val d = Math.abs(k.timeMs - now)
            if (d < bestD) { bestD = d; best = k }
        }
        val maxMs = if (trackLengthMs > 0) trackLengthMs else best.timeMs
        val target = best.timeMs.coerceIn(0L, maxMs.coerceAtLeast(0L))
        editSeekOffset = 0
        onSeekAbs?.invoke(target.toInt())
        invalidate()
    }

    /**
     * 长按判定线框 = 框住/取消框住，并聚焦到这条线进入编辑。
     * 同时编辑多条时焦点永远给"最新出现红框"的那条；取消框住正在编辑的那条 →
     * 焦点交给最后框住的一条，一条都不剩就退出编辑。
     */
    private fun toggleFrameAndFocus(id: Int) {
        if (id in selectedLines) {
            selectedLines.remove(id)
            // 取消框住：抽屉保持打开（方便接着取消别的），编辑焦点让给最后框住的那条；
            // 一条都不剩就继续编辑当前这条（不强行退出编辑，避免输入面板突然消失）
            if (lineEditActive && id == activeLineId) {
                val last = selectedLines.lastOrNull()
                if (last != null) {
                    activateLine(last)
                    onLinePoseChanged?.invoke()
                }
            }
        } else {
            selectedLines.add(id)
            focusLineForEdit(id)
        }
        onLinesChanged?.invoke()
        invalidate()
    }

    /** 取消所有"被框住"的判定线（红框）。不退出判定线编辑：当前聚焦的那条还能继续改 */
    private fun clearFrameSelection() {
        if (selectedLines.isEmpty()) return
        selectedLines.clear()
        // 取消框选 = 也退出判定线编辑界面（用户点名：别留着右上角输入面板）
        if (lineEditActive) lineEditActive = false
        onLinesChanged?.invoke()
        invalidate()
    }

    /**
     * 新建判定线（含图片线/文字线）放进的图层 = **当前选中判定线所在的图层**（用户点名）。
     * 因为"选中的图层"天然等于"活动线所在图层"（点夹子只展开/收起，不选层），
     * 所以不需要单独的选层状态，也没有"默认最高层"之说。
     */
    private fun activeLineLayer(): Int = activeLine.zOrder

    /** 在屏幕 y 处新增一条判定线；返回新线（数量不设上限） */
    fun addLineAtScreenY(sy: Float): Line? {
        val own = tx == null
        if (own) beginTx()
        var line: Line? = null
        linesMutate {
            val l = Line(nextLineId++)
            l.name = perKindName(LineKind.Normal)
            l.zOrder = activeLineLayer()
            l.posPct = Math.round((sy / height.toFloat().coerceAtLeast(1f)) * 100f).coerceIn(0, 100)
            l.speed = activeLine.speed
            lines.add(l)
            line = l
        }
        if (own) endTx()
        line?.let {
            DebugLog.add("line", "add id=${it.id} pct=${it.posPct}（共 ${lines.size} 条）")
            activeLineId = it.id
            selectedKfTime = -1
            onLinesChanged?.invoke()
            onLinePoseChanged?.invoke()
        }
        invalidate()
        return line
    }

    /** 判定线条数 */
    val lineCount: Int get() = lines.size

    /** 活动判定线在第几条（从 0 开始） */
    val activeLineIndex: Int get() = lines.indexOfFirst { it.id == activeLineId }.coerceAtLeast(0)

    /** 当前正在编辑的关键帧时间（-1 = 没在编辑帧）；新建的帧也包含在内 */
    val selectedKeyframeMs: Long get() = kfEditTime

    // ---------------------------------------------------------------- 音符位置

    /** 基础比例：不含流速（px / ms） */
    private fun basePxPerMs(): Float = 0.16f * (height / 720f) * timelineScale

    /** Phira 的谱面视口：居中的 16:9 区域（放不下时按屏宽缩），宽 = min(屏宽, 屏高×16/9) */
    fun chartWidthPx(): Float =
        minOf(width.toFloat(), height.toFloat() * CHART_ASPECT).coerceAtLeast(1f)

    fun chartHeightPx(): Float = chartWidthPx() / CHART_ASPECT

    /** Phira 的音符宽度：固定占谱面视口宽度的 13.175%，跟横线数（网格）无关 */
    fun noteWidthPx(): Float = chartWidthPx() * NOTE_W_RATIO

    /** 某条线在 now 时刻的瞬时比例（px / ms） */
    fun linePxPerMs(l: Line, now: Long): Float = basePxPerMs() * l.speedAt(now)

    /**
     * 音符到判定线的纵向距离（像素，正=已经过线=屏幕下方）。
     * 走流速积分 ∫speed dt，所以有变速时距离是折线而不是直线（与 Phira height 一致）。
     * 再乘音符自己的 speed × ctrl.yControl（Phira 的 spd），并叠加 yOffset（沿下落方向远离判定线）。
     */
    private fun distPx(l: Line, n: RecordedNote, noteMs: Long, now: Long): Float {
        val tr = l.speedTrack()
        var d = ((tr.heightAt(now) - tr.heightAt(noteMs)) * basePxPerMs() * (n.speed * ctrlYOf(l, n, now))).toFloat()
        // Phira: translation.y = yOffset/450 × 音符流速（世界坐标向上为正）→ 折算 px 后从距离里减掉
        if (n.yOffset != 0f) d -= n.yOffset * n.speed * height / 900f
        return d
    }

    /** 音符在判定线局部坐标系里的 y（含正面/反面镜像） */
    private fun noteLocalY(l: Line, n: RecordedNote, noteMs: Long, now: Long): Float {
        val d = distPx(l, n, noteMs, now)
        return if (n.above) d else -d
    }

    /**
     * 屏幕纵向距离（px，正 = 还没到线/未来）→ 音符时间。
     *
     * 必须对流速积分求逆（[SpeedTrack.timeAtHeight]）。以前是
     * `now - 距离 / 当前瞬时流速`，只对常速成立；判定线变速（两个关键帧流速不同）时
     * 放下来的音符会被算到错误的时间，显示位置就偏了。
     */
    private fun timeForDistPx(l: Line, n: RecordedNote, now: Long, distPx: Float): Long {
        val spd = if (n.speed.isFinite() && n.speed > 1e-4f) n.speed else 0f
        // 音符自己流速为 0：位置与时间无关，保持原时间（拖动没有意义）
        if (spd <= 0f) return n.startMs
        val base = basePxPerMs() * spd
        if (base <= 1e-6f) return now
        val tr = l.speedTrack()
        var t = now
        // ctrl 轨道按"音符到线的距离"取值，距离又由时间决定 → 迭代几次即可收敛
        repeat(3) {
            val probe = n.copy(startMs = t, endMs = t)
            val ctrl = ctrlYOf(l, probe, now).coerceAtLeast(1e-6f)
            val yOffPx = n.yOffset * spd * height / 900f
            val h = tr.heightAt(now) - (distPx + yOffPx).toDouble() / (base * ctrl).toDouble()
            t = Math.round(tr.timeAtHeight(h)).coerceAtLeast(0L)
        }
        return t
    }

    /** 不分配版本：调用方先把判定线中心/旋转算好（热路径用） */
    fun noteScreenPosInto(n: RecordedNote, now: Long, out: FloatArray) {
        val l = lineOf(n)
        lineFrameOf(l, now, scratchPose)
        noteScreenPosInto(n, now, scratchPose[0], scratchPose[1], scratchPose[2], out)
    }

    fun noteScreenPosInto(n: RecordedNote, now: Long, lcX: Float, lcY: Float, rotDeg: Float, out: FloatArray) {
        val l = lineOf(n)
        val a = Math.toRadians(-rotDeg.toDouble())
        val cosA = Math.cos(a).toFloat()
        val sinA = Math.sin(a).toFloat()
        val xl = ((n.posX + 1f) / 2f * width - width / 2f) * noteXFactor(l, n, now)
        val yl = noteLocalY(l, n, n.startMs, now)
        out[0] = lcX + xl * cosA - yl * sinA
        out[1] = lcY + xl * sinA + yl * cosA
    }

    var pass: Int = 1            // 当前编辑阶段 1/2/3/4
    var playheadProvider: (() -> Long)? = null
    var playingProvider: (() -> Boolean)? = null
    /** 打歌几何诊断：上次写"可见线"日志的秒数 */
    private var lastPlayLogSec: Long = -1

    var holdMsThreshold: Long = 240

    /** 音符自动移动：拖动音符/框选贴到上/下遮罩边缘时自动滚时间轴（默认开；关掉就只能用播放条拖） */
    var autoEdgeScroll: Boolean = true

    /**
     * 开始一次 Autoplay。
     * @param startAtMs 按下 AUTOPLAY 时的音乐位置：比它更早的音符直接视为"已过去"，
     *                  否则第一帧会把整张谱所有音符瞬间打一遍。
     */
    fun startPlaySession(startAtMs: Long = playMs()) {
        playMode = true
        // 打歌时退出噪域编辑（编辑视图的平移缩放不能带进 Autoplay）
        if (noiseEditActive) stopNoiseEdit()
        noiseCreatePending = false
        closeLinePanel()
        // 打歌几何写进日志：缩放 / 每秒下落像素 / 音符宽度（排查"速度对不上 Phira"时直接看这行）
        val pxPerSec = basePxPerMs() * 1000.0 * activeLine.speedAt(startAtMs)
        DebugLog.add(
            "autoplay",
            "start at=${startAtMs}ms notes=${notes.size} scale=$timelineScale " +
                "px/s=${Math.round(pxPerSec)} 屏高=${height} 视口高=${Math.round(chartHeightPx())} " +
                "音符宽=${Math.round(noteWidthPx())} 流速=${activeLine.speedAt(startAtMs)}"
        )
        resetJudged()
        combo = 0
        hitCount = 0
        noiseJamCount = 0
        noiseRuntime = NoiseRuntime(noiseAreas)
        lastJudgeNow = startAtMs
        holdFxList.clear()
        playSortedIdx = IntArray(0)          // 编辑期间可能拖动过音符，重建时间索引
        // 从谱子中间开始：比起点更早的音符算"已经打过了"（计分但不出声、不加连击）。
        // 不算进去的话，最终分数永远差一截（以前从中间进 AUTOPLAY，打完也只有一半分）。
        hitCount = seedPassedNotes(startAtMs)
        Log.i(TAG, "startPlaySession startAt=${startAtMs}ms skipped=$judgedCount/${notes.size} sound=$soundEnabled")
        invalidate()
    }

    /** 把开始时间之前已经过去的音符标记为"已处理"（不出声、不算连击），返回个数（用于补分数） */
    private fun seedPassedNotes(startAtMs: Long): Int {
        val cut = startAtMs - AUTO_HIT_WINDOW_MS
        var n = 0
        for (i in notes.indices) {
            if (notes[i].startMs < cut) { markJudged(i); n++ }
        }
        return n
    }

    fun stopPlaySession() {
        playMode = false
        noiseRuntime.clear()
        invalidate()
    }

    /** 长按音效按钮时弹出，便于在手机上排查声音/判定问题 */
    // ----- 音符数据 -----
    val notes: MutableList<RecordedNote> = mutableListOf()

    private val fingers = SparseArray<FingerState>()

    // ----- 绘制资源 -----
    private var bgBitmap: Bitmap? = null
    private var bgDst: RectF? = null
    /** 打歌时用的"模糊背景"（原图缩小到 1/20，再用双线性放大 → 和 Phira 一样的柔和模糊） */
    private var bgBlurSmall: Bitmap? = null
    private val gridPaint = Paint().apply { color = intColor(0x66FFFFFF); strokeWidth = 2f }
    private val linePaint = Paint().apply { color = Color.WHITE; strokeWidth = 6f }
    /** 拍号数字：垂直居中贴在粗线上，避免看起来属于相邻的细线 */
    private val beatNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = intColor(0xCCFFFFFF)
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val texPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }

    /**
     * 音符颜色滤镜缓存（RPE tint）。绘制是每帧热路径：同一个颜色只建一个 ColorFilter，
     * 颜色种类通常只有几个，表永远不会大。
     */
    private val tintFilterCache = HashMap<Int, PorterDuffColorFilter>(8)
    private fun tintFilterOf(c: Int): PorterDuffColorFilter =
        tintFilterCache.getOrPut(c) {
            PorterDuffColorFilter(c or 0xFF000000.toInt(), PorterDuff.Mode.MULTIPLY)
        }

    private var hitFxBmp: Bitmap? = null
    private var tapBmp: Bitmap? = null
    private var holdBmp: Bitmap? = null
    private var flickBmp: Bitmap? = null
    private var dragBmp: Bitmap? = null
    // Phira 双押提示贴图（*_mh：比普通贴图多一圈提示花纹，宽约 1.15 倍）
    /** 当前生效的 Phira 资源包（null = 内置贴图/音效） */
    private var resPack: ResPack? = null

    private var tapMh: Bitmap? = null
    private var holdMh: Bitmap? = null
    private var flickMh: Bitmap? = null
    private var dragMh: Bitmap? = null
    // 双押贴图相对普通贴图的宽度倍率（Phira: note_style_mh.click.width / note_style.click.width）
    // 默认 1：拿不到比例时按"和普通音符一样大"画，绝不能默认放大
    private var tapMhScale = 1f
    private var holdMhScale = 1f
    private var flickMhScale = 1f
    private var dragMhScale = 1f

    /**
     * 资源包里的贴图优先；没选资源包时才读内置 assets。
     * 选了资源包但文件不存在 → **不回退**（用户要求：空资源包就是空的，什么音符都没有）。
     */
    private fun loadTex(n: String): Bitmap? {
        val pack = resPack
        if (pack != null) {
            val f = java.io.File(pack.dir, "$n.png")
            if (!f.isFile) return null
            return try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
        }
        return try {
            context.assets.open("notes/$n.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }

    /**
     * 双押贴图/普通贴图的宽度比（拿不到就按 1 = 和普通音符一样大）。
     *
     * 这个比例必须由**贴图本身**决定：Phira 默认包的双押贴图比普通贴图宽 1.15 倍，
     * 但多出来的那 15% 是四周的光晕留白，音符本体还是一样大；如果资源包里的
     * *_mh 和普通贴图同尺寸，比例就是 1，不能硬套 1.15 把音符放大。
     */
    private fun texScale(normal: Bitmap?, mh: Bitmap?): Float {
        if (normal == null || mh == null || normal.width <= 0) return 1f
        return (mh.width.toFloat() / normal.width.toFloat()).coerceIn(1f, 2f)
    }

    // 打击音效（Phira ogg；与 judge.rs 映射一致：Click→click / Flick→flick / Drag→drag / Hold→click）
    private var soundPool: SoundPool? = null
    private var sndClick = 0
    private var sndDrag = 0
    private var sndFlick = 0
    /** 长条音效（资源包 hold 文件；没有就回退 click） */
    private var sndHold = 0
    private var soundPlayFailLogged = false

    /** 打击音效开关（默认开） */
    /** 打音符的打击音效：编辑界面默认**关**，进 Autoplay 时自动打开（退出再还原） */
    var soundEnabled: Boolean = false
        set(value) { field = value; invalidate() }

    /** 资源包里的音效优先（ogg/wav/mp3）；没选资源包时才用内置音效。选了包但没有音效文件 → 静音 */
    private fun loadSfx(sp: SoundPool, name: String, asset: String): Int {
        val pack = resPack
        if (pack != null) {
            val f = pack.sfx(name) ?: return 0
            return sp.load(f.absolutePath, 1)
        }
        return sp.load(context.assets.openFd(asset), 1)
    }

    /** 进页面就预加载音效：别等第一下点击才初始化 SoundPool（第一下会明显延迟） */
    fun preloadSounds() {
        ensureSounds()
    }

    private fun ensureSounds() {
        if (soundPool != null) return
        try {
            val sp = SoundPool.Builder()
                .setMaxStreams(64)   // 支持多种音符同时命中并发播放（快速连点也不掉）
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            sp.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) Log.w(TAG, "SoundPool load failed sample=$sampleId status=$status")
            }
            sndClick = loadSfx(sp, "click", "sounds/click.ogg")
            sndDrag = loadSfx(sp, "drag", "sounds/drag.ogg")
            sndFlick = loadSfx(sp, "flick", "sounds/flick.ogg")
            // 长条音效：资源包有 hold 文件就用它，没有则播放时回退 click
            sndHold = loadSfx(sp, "hold", "sounds/click.ogg")
            soundPool = sp
            Log.i(TAG, "SoundPool preload ids=$sndClick/$sndDrag/$sndFlick/$sndHold")
        } catch (e: Exception) {
            Log.e(TAG, "SoundPool init failed", e)
            soundPool = null
        }
    }

    /** 每种音效最近用过的流 id：超过上限就先 stop 最旧的，防止快速连点把流抢光后没声音 */
    private val sndStreams = HashMap<Int, ArrayDeque<Int>>()

    fun playHitSound(type: Int) {
        if (!soundEnabled) return
        ensureSounds()
        val sp = soundPool ?: return
        // 蓝键 click / 黄键 drag / 长条 hold（缺失回退 click）/ 红键 flick
        val id = when (type) {
            3 -> sndFlick
            4 -> if (sndDrag != 0) sndDrag else sndClick
            2 -> if (sndHold != 0) sndHold else sndClick
            else -> sndClick
        }
        if (id == 0) return             // 资源包里没有对应音效 → 静音
        try {
            val q = sndStreams.getOrPut(type) { ArrayDeque() }
            // 同一个音效最多留 8 条并发，超了就把最旧的掐掉，保证新的一下一定有流可用
            while (q.size >= SND_MAX_PER_TYPE) sp.stop(q.removeFirst())
            // priority 给高一点，池满时优先保新的
            val stream = sp.play(id, 1f, 1f, 3, 0, 1f)
            if (stream != 0) {
                q.addLast(stream)
            } else if (!soundPlayFailLogged) {
                soundPlayFailLogged = true
                Log.w(TAG, "SoundPool.play returned 0 (stream pool full / sample not ready) id=$id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SoundPool.play failed", e)
        }
    }

    fun releaseSounds() {
        soundPool?.release()
        soundPool = null
        sndStreams.clear()
    }

    // 打击特效（Phira hit_fx 精灵图 8x7 = 56 帧）
    private class HitFx(val x: Float, val y: Float, val at: Long)
    private val hitFxList = mutableListOf<HitFx>()
    // 打击特效参数（默认 = 内置贴图 8x7 / 460ms / 0.9 倍；导入资源包后按 info.yml 覆盖）
    private var hitFxCols = 8
    private var hitFxRows = 7
    private var hitFxDurMs = 460L
    private var hitFxScale = 0.9f

    init {
        isClickable = true
        setBackgroundColor(Color.parseColor("#14161E"))
        loadSkin()
        ensureSounds()   // 提前加载，第一次打击就有声音
    }

    /** 切换资源包（null = 内置贴图与音效）。导入/恢复默认后立即生效 */
    fun setResourcePack(pack: ResPack?) {
        resPack = pack
        loadSkin()
        releaseSounds()
        ensureSounds()
        invalidate()
    }

    private fun loadSkin() {
        tapBmp = loadTex("click")
        holdBmp = loadTex("hold")
        flickBmp = loadTex("flick")
        dragBmp = loadTex("drag")
        hitFxBmp = loadTex("hit_fx")
        tapMh = loadTex("click_mh")
        holdMh = loadTex("hold_mh")
        flickMh = loadTex("flick_mh")
        dragMh = loadTex("drag_mh")
        tapMhScale = texScale(tapBmp, tapMh)
        holdMhScale = texScale(holdBmp, holdMh)
        flickMhScale = texScale(flickBmp, flickMh)
        dragMhScale = texScale(dragBmp, dragMh)
        val pack = resPack
        hitFxCols = pack?.hitFxCols ?: 8
        hitFxRows = pack?.hitFxRows ?: 7
        hitFxDurMs = pack?.hitFxDurationMs ?: 460L
        hitFxScale = pack?.hitFxScale ?: 0.9f
    }

    /** 在 (x,y) 播一次 Phira 打击特效 */
    fun spawnHitFx(x: Float, y: Float) {
        if (hitFxList.size > 14) hitFxList.removeAt(0)
        hitFxList.add(HitFx(x, y, SystemClock.elapsedRealtime()))
    }

    fun setBackgroundImage(uri: Uri) {
        try {
            val res = context.contentResolver
            // 原图可能 4000×4000：先读尺寸，按屏幕大小降采样解码。
            val dm = resources.displayMetrics
            val targetW = dm.widthPixels.coerceAtLeast(1)
            val targetH = dm.heightPixels.coerceAtLeast(1)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            res.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                    sample *= 2
                }
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = res.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return
            bgBitmap = bmp
            bgDst = null
            bgBlurSmall = null
            postInvalidate()
        } catch (_: Exception) {}
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /**
     * 打歌背景高斯模糊（Phira 风格）：先把背景缩到 1/16 小图，
     * 再做 3 次可分离盒式滤波（≈ 高斯，纯像素运算在 [BgBlur]，可单测），
     * 最后画回大图时双线性放大。只在换背景时算一次并缓存，每帧零开销。
     *
     * 旧实现只做「缩到 1/20 再放大」（双线性 = 像素模糊），块状感明显。
     */
    private fun makeBlurredBg(src: Bitmap): Bitmap? {
        return try {
            val tw = (src.width / 16).coerceAtLeast(8)
            val th = (src.height / 16).coerceAtLeast(8)
            val small = Bitmap.createScaledBitmap(src, tw, th, true)
            val w = small.width
            val h = small.height
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            // 模糊半径：图越小相对越糊；三次盒式的合成核接近 σ≈r×1.7 的高斯
            val r = (minOf(w, h) / 5).coerceIn(2, 14)
            BgBlur.blur(px, w, h, r)
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) { null }
    }

    /** 触摸兼容（框架/全部）下把判定半径放大，手机上更好点 */
    private fun hit(r: Float): Float = if (compatMode >= 1) r * 1.6f else r
    private fun intColor(v: Long): Int = v.toInt()
    /** 编辑模式搓时间：拖动过程累积的偏移（渲染用虚拟时间，松手才写回播放器） */
    var editSeekOffset: Long = 0
    var onSeekAbs: ((Int) -> Unit)? = null

    /**
     * 拖进度条时让谱面**立刻跟随**：只改"预览偏移"，不动播放器（拖动中反复 seek 会卡）。
     * 松手后播放器 seek 到目标位置，等它到位了再 [dropSeekPreview]。
     */
    fun seekPreviewTo(t: Long) {
        val base = playheadProvider?.invoke() ?: 0L
        editSeekOffset = t - base
        invalidate()
    }

    /** 结束拖动预览（不重绘：在绘制/时间读取路径里调用，避免递归 invalidate） */
    fun dropSeekPreview() {
        editSeekOffset = 0
    }

    private fun playMs(): Long = (playheadProvider?.invoke() ?: 0L) + editSeekOffset

    /** 编辑模式：累加搓动偏移（线/数字/音符都平滑跟随，不触发真实 seek） */
    fun addEditSeek(dt: Int) {
        if (dt != 0) {
            // 拖动左侧时间轴 = 离开这一帧：先把输入框数值写回当前帧，再退出关键帧编辑
            if (selectedKfTime >= 0 || editKfTime >= 0) {
                onLineEditSave?.invoke()
                selectedKfTime = -1L
                editKfTime = -1L
                onLinePoseChanged?.invoke()
            }
        }
        editSeekOffset += dt
        invalidate()
    }

    /** 编辑模式：松手后把最终时间写回播放器并清零偏移 */
    fun flushEditSeek() {
        if (editSeekOffset == 0L) return
        val base = playheadProvider?.invoke() ?: 0L
        // 用 Long 相加再夹到 Int 范围：偏移可能远超 2^31（以前的 .toInt() 会溢出成负数，
        // 表现就是「拖到某处后一下子跳回开头」）
        val target = (base + editSeekOffset).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        editSeekOffset = 0
        onSeekAbs?.invoke(target)
        invalidate()
    }

    /** 一格毫秒（最小横线间隔） */
    private fun cellMs(): Double = (60000.0 / bpm / hLines.coerceAtLeast(1)).coerceAtLeast(1.0)

    /** 毫秒量化到格点（BPM 偏移会把格点整体平移） */
    private fun quantMs(ms: Long): Long {
        val c = cellMs()
        val off = bpmOffsetMs
        return (Math.round((ms - off) / c) * c + off).toLong()
    }

    /** 屏幕 x 量化到竖线格，映射 [-1,1] */
    fun quantizeX(screenX: Float): Float {
        val w = width.toFloat().coerceAtLeast(1f)
        val p = (screenX / w).coerceIn(0f, 1f)
        val cell = 1f / vLines
        val q = Math.round(p / cell).coerceIn(0, vLines) * cell
        return q * 2f - 1f
    }

    /** 屏幕 x 直接映射 [-1,1]（不平滑吸附） */
    private fun rawPosX(screenX: Float): Float =
        (screenX / width.toFloat() * 2f - 1f).coerceIn(-1f, 1f)

    /**
     * 判定线**局部坐标**的 x → 归一化 posX。
     * 判定线旋转后，音符是画在线自己的坐标系里的，所以吸附也必须在线性坐标系里算，
     * 否则"竖向放置线"转了、吸附格子却没转。
     */
    private fun posXFromLocal(xl: Float): Float {
        val halfW = (width / 2f).coerceAtLeast(1f)
        if (smoothMove) return (xl / halfW).coerceIn(-1f, 1f)
        val cell = width.toFloat() / vLines.coerceAtLeast(1)
        val q = Math.round((xl + halfW) / cell) * cell - halfW
        return (q / halfW).coerceIn(-1f, 1f)
    }

    /** 贴图目标矩形：每帧复用，避免"每个音符 new 一个 RectF"的垃圾 */
    private val texDst = RectF()
    /** 打击特效的 src/dst 复用对象：每帧几十个特效时不再反复 new Rect/RectF（减少 GC 卡顿） */
    private val fxSrc = Rect()
    private val fxDst = RectF()
    private val pathTmp = Path()

    private fun drawTex(canvas: Canvas, bmp: Bitmap?, l: Float, t: Float, r: Float, b: Float, alpha: Int = 255) {
        if (bmp == null || r <= l || b <= t || alpha <= 0) return
        texPaint.alpha = alpha
        texDst.set(l, t, r, b)
        canvas.drawBitmap(bmp, null, texDst, texPaint)
    }

    /** 拍号数字的复用缓冲（drawText 直接吃 char[]，每帧几十根拍线不再各 new 一个 String） */
    private val beatLabelBuf = CharArray(20)

    /** 把 [v] 倒写进 [buf]，返回有效内容的起始下标 */
    private fun beatLabelInto(buf: CharArray, v: Long): Int {
        var i = buf.size
        var n = v
        if (n == 0L) buf[--i] = '0'
        else while (n > 0) {
            buf[--i] = ('0' + (n % 10L).toInt())
            n /= 10L
        }
        return i
    }

    /** Phiedit 风格横线：每拍 = 主线(白20%) + 拍号数字；每格 = 次线(白10%) */
    private fun drawHLine(canvas: Canvas, left: Float, right: Float, y: Float, gi: Long, labelX: Float) {
        val hl = hLines.coerceAtLeast(1).toLong()
        val isBeat = Math.floorMod(gi, hl) == 0L
        if (isBeat) {
            gridPaint.alpha = 51                    // EDITOR_VIEW_HORIZONTAL_MAIN_LINE_COLOR = 0.2
            gridPaint.strokeWidth = dp(3f)
            val beatNo = Math.floorDiv(gi, hl)
            if (beatNo >= 0) {
                val start = beatLabelInto(beatLabelBuf, beatNo)
                val len = beatLabelBuf.size - start
                beatNumPaint.textSize = dp(12f)
                beatNumPaint.style = Paint.Style.STROKE
                beatNumPaint.strokeWidth = dp(3f)
                beatNumPaint.color = intColor(0xDD000000)
                val fm = beatNumPaint.fontMetrics
                val baseline = y - (fm.ascent + fm.descent) / 2f
                canvas.drawText(beatLabelBuf, start, len, labelX, baseline, beatNumPaint)
                beatNumPaint.style = Paint.Style.FILL
                beatNumPaint.color = intColor(0xCCFFFFFF)
                canvas.drawText(beatLabelBuf, start, len, labelX, baseline, beatNumPaint)
            }
        } else {
            gridPaint.alpha = 26                    // EDITOR_VIEW_HORIZONTAL_LINE_COLOR = 0.1
            gridPaint.strokeWidth = dp(1.5f)
        }
        canvas.drawLine(left, y, right, y, gridPaint)
        gridPaint.strokeWidth = dp(2f)
    }

    // ------------------------------------------------------------ 绘制
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        bgBitmap?.let { bmp ->
            val dst = bgDst ?: run {
                val scale = maxOf(w / bmp.width, h / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val left = (w - dw) / 2f
                val top = (h - dh) / 2f
                RectF(left, top, left + dw, top + dh).also { bgDst = it }
            }
            // 打歌时模糊背景（Phira 风格的高斯模糊），退出 autoplay 恢复原图
            if (playMode) {
                val small = bgBlurSmall ?: makeBlurredBg(bmp)?.also { bgBlurSmall = it } ?: bmp
                texPaint.alpha = 255
                canvas.drawBitmap(small, null, dst, texPaint)
            } else {
                canvas.drawBitmap(bmp, null, dst, null)
            }
            canvas.drawColor(intColor(0x99000000))
        }

        // 噪域编辑：整个谱面内容跟着"平移 + 居中缩放"一起变换（HUD/侧边面板不参与）
        val noiseView = noiseEditActive
        if (noiseView) {
            canvas.save()
            canvas.translate(noiseViewPanX, noiseViewPanY)
            canvas.scale(noiseViewZoom, noiseViewZoom, w / 2f, h / 2f)
        }

        val now = playMs()
        // 网格/交互换算用"活动判定线"的瞬时比例（有变速时这也是它此刻的视觉比例）
        pxPerMs = linePxPerMs(activeLine, now)
        // 打歌：**先判定再画** —— 这一帧刚过线的音符当帧就不再画，
        // 打击特效/音效/音符消失完全落在同一帧（以前是画完再判，差一帧）
        if (playMode) tickAutoPlay(now)
        val cellW = w / vLines.toFloat()
        // 音符宽度按 Phira：固定占谱面视口的 13.175%，不跟横线数走（以前 vLines=16 时只有 Phira 的一半大）
        val noteW = noteWidthPx()
        val cMs = cellMs().toFloat()
        // 网格原点是判定线的**真实中心**（含 moveY/关键帧位移/父线合成），
        // 之前用的是 posPct 换算出的基准高度，判定线一移动横线就和音符对不上
        lineFrameOf(activeLine, now, framePose)
        val activeCx = framePose[0]
        val activeCy = framePose[1]
        val judgeY = activeCy

        // ---- 横线（Phiedit 风格：每拍主线 + 拍号，每格次线；与音符同向滚动）----
        // 制作线显示：0=横线 1=竖线 2=全部 3=隐藏
        val showH = gridMode == 0 || gridMode == 2
        val showV = gridMode == 1 || gridMode == 2
        val gridPx = cMs * pxPerMs
        val hl = hLines.coerceAtLeast(1).toLong()
        var stepCells = 1L
        if (gridPx < dp(2f)) stepCells = hl
        while (gridPx * stepCells < dp(2f) && stepCells < 1_000_000L) stepCells *= 2
        val stepPx = (gridPx * stepCells).toFloat()

        val gi0 = Math.floor((now - bpmOffsetMs) / cellMs()).toLong()
        if (showH && stepPx > 0.5f) {
            if (hGridRotate) {
                // 横向转动：时间网格画进"选中判定线"的局部坐标系 —— 线转到哪，横线就跟到哪
                canvas.save()
                canvas.translate(activeCx, activeCy)
                canvas.rotate(-framePose[2])
                val half = w * 1.6f
                var gi = Math.floorDiv(gi0, stepCells) * stepCells
                var y = ((now - bpmOffsetMs - gi * cellMs()) * pxPerMs).toFloat()
                while (y < h * 1.6f) {
                    drawHLine(canvas, -half, half, y, gi, -half + dp(5f)); gi -= stepCells; y += stepPx
                }
                gi = Math.floorDiv(gi0, stepCells) * stepCells + stepCells
                y = ((now - bpmOffsetMs - gi * cellMs()) * pxPerMs).toFloat()
                while (y > -h * 1.6f) {
                    drawHLine(canvas, -half, half, y, gi, -half + dp(5f)); gi += stepCells; y -= stepPx
                }
                canvas.restore()
            } else {
                var gi = Math.floorDiv(gi0, stepCells) * stepCells
                var y = (judgeY + (now - bpmOffsetMs - gi * cellMs()) * pxPerMs).toFloat()
                while (y < h + 10f) { drawHLine(canvas, 0f, w, y, gi, dp(5f)); gi -= stepCells; y += stepPx }
                gi = Math.floorDiv(gi0, stepCells) * stepCells + stepCells
                y = (judgeY + (now - bpmOffsetMs - gi * cellMs()) * pxPerMs).toFloat()
                while (y > -10f) { drawHLine(canvas, 0f, w, y, gi, dp(5f)); gi += stepCells; y -= stepPx }
            }
        }

        // ---- 噪域（打歌时）----
        // 画在音符**下面**：噪域是场景层，音符要压在它上面才看得清；
        // Autoplay 不画任何跟随手指的特效（手指位置只用于噪域命中判定）。
        if (playMode) drawNoisePlay(canvas, now, w, h)

        // ---- 逐条判定线：线 + 竖线刻度 + 挂在它上面的音符 ----
        val multi = lines.size > 1
        // 双押标记每帧只算一次（多线共用）；布尔数组是复用的，不产生垃圾
        val dhAll = if (playMode || editDoubleHint) doubleHints(now) else emptyDh
        // 非选中的判定线变淡：只在编辑模式、且（关掉"判定线高亮" 或 正在翻判定线列表）时
        // 打歌（Autoplay）永远全部正常显示
        val dimOthers = editMode && !playMode && (!highlightAllLines || panelOpen)
        // 打歌时每秒记一行"当前可见的判定线"（alpha / 旋转），排查"某条线/长条不消失"用
        val sec = now / 1000
        val logVisible = playMode && sec != lastPlayLogSec
        dbgLogFrame = logVisible
        val visSb = if (logVisible) StringBuilder() else null
        for (li in lines.indices) {
            val l = lines[li]
            lineFrameOf(l, now, framePose)
            val lcX = framePose[0]
            val lcY = framePose[1]
            val rot = framePose[2]
            // 逐关键帧缩放（RPE scaleXEvents/scaleYEvents）；编辑选中帧时显示该帧的值
            scaleOf(l, now, scaleScratch)
            val lScaleX = scaleScratch[0]
            val lScaleY = scaleScratch[1]
            val isActive = l.id == activeLineId
            val dimmed = dimOthers && !isActive

            val editingThis = lineEditActive && isActive
            linePaint.color = when {
                editingThis -> intColor(0xFFFFEB3B)
                isActive -> Color.WHITE
                dimmed -> intColor(0x44FFFFFF)
                else -> intColor(0x99FFFFFF)
            }
            // Phira line.rs 的判定线粗细（完全复刻）：
            //   line_scaled = |scaleY − 1| > 1e-4
            //   粗细参数（world 单位）= line_scaled ? 0.0076 : 0.01
            //   而 draw_line 是在 now_scale 坐标系里画的 ⇒ 实际粗细 = 参数 × |scaleY|
            // world → 屏幕像素：屏幕高 = 2 world，所以粗细 = world × h/2。
            // 即：默认（scaleY=1）恒为 0.01 world = 0.5% 屏高；一旦有缩放就切到 0.0076×|scaleY|。
            val lineScaled = Math.abs(lScaleY - 1f) > 1e-4f
            val worldStroke = if (lineScaled) 0.0076f else 0.01f
            linePaint.strokeWidth = worldStroke * (if (lineScaled) Math.abs(lScaleY) else 1f) * (h / 2f)
            // 判定线透明度（RPE alphaEvents）：0 时整条隐藏；负数 = Phira 的"连音符一起隐藏"（PE alpha extension）
            val lineA = l.alphaAt(now)
            val lineHiddenByNegAlpha = playMode && lineA < 0f
            linePaint.alpha = (linePaint.alpha * lineA.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(lcX, lcY)
            canvas.rotate(-rot)
            // 判定线长度按 Phira：line_length = 6 是 **world 单位**（不是"半屏宽个数"），
            // 屏幕半宽 = aspect world，所以单边 = line_length × w / (2 × aspect)；
            // 且普通 line.png 线在 rpe.rs 解析时 scaleX 会再乘 0.5（无 text/paint/attachUI 时）。
            // 综合下来默认单边 ≈ 0.84 屏宽（原来错算成 3 屏宽，长了 3.5 倍）。
            val lineScaleX = lScaleX.coerceIn(0.02f, 20f)
            val lineHalfLen = w * LINE_LENGTH / (2f * CHART_ASPECT) * 0.5f * lineScaleX
            if (lineA > 0.004f) {
                when (l.kind) {
                    // 图片线：按 RPE 语义画（1 像素 = 1 单位，缩放乘在尺寸上），原点在线中心
                    LineKind.Image -> {
                        val bmp = lineBitmap(l)
                        val k = w / 1350f                       // 谱面单位 → 屏幕像素
                        if (bmp != null) {
                            val iw = bmp.width * lScaleX * k
                            val ih = bmp.height * lScaleY * k
                            // 「淡化非当前判定线」对图片线同样生效（以前只淡化普通线和音符，图不淡 → 突兀）
                            val dimF = if (dimmed) DIM_ALPHA else 1f
                            texPaint.alpha = (255 * lineA.coerceIn(0f, 1f) * dimF).toInt().coerceIn(0, 255)
                            texDst.set(-iw / 2f, -ih / 2f, iw / 2f, ih / 2f)
                            canvas.drawBitmap(bmp, null, texDst, texPaint)
                        } else {
                            // 图没了（换机器/被删）：画个占位框，别让人以为线消失了
                            livePaint.style = Paint.Style.STROKE
                            livePaint.strokeWidth = dp(2f)
                            livePaint.color = intColor(0x88FF9800)
                            canvas.drawRect(-dp(160f), -dp(90f), dp(160f), dp(90f), livePaint)
                            livePaint.style = Paint.Style.FILL
                        }
                    }
                    // 文字线：Phira 用自带字体按 textSize（谱面单位）渲染，预览取 Phiedit 默认 50
                    LineKind.Text -> {
                        val k = w / 1350f
                        beatNumPaint.textSize = (50f * lScaleY * k).coerceAtLeast(dp(6f))
                        // RPE：height 由 scaleY 决定，width 由 scaleX 决定（等比缩放时两者相消 = 1）
                        beatNumPaint.textScaleX =
                            if (Math.abs(lScaleY) > 1e-5f) (lScaleX / lScaleY).coerceIn(0.05f, 20f) else 1f
                        beatNumPaint.textAlign = Paint.Align.CENTER
                        beatNumPaint.style = Paint.Style.FILL
                        beatNumPaint.color = intColor(0xFFFFFFFF)
                        val dimF = if (dimmed) DIM_ALPHA else 1f
                        beatNumPaint.alpha = (255 * lineA.coerceIn(0f, 1f) * dimF).toInt().coerceIn(0, 255)
                        val fm = beatNumPaint.fontMetrics
                        canvas.drawText(l.lineText, 0f, -(fm.ascent + fm.descent) / 2f, beatNumPaint)
                        beatNumPaint.alpha = 255
                        beatNumPaint.textScaleX = 1f
                    }
                    else -> canvas.drawLine(-lineHalfLen, 0f, lineHalfLen, 0f, linePaint)
                }
            }

            // 竖线刻度（垂直判定线，随线旋转）+ 竖向放置线（跟着判定线一起转）
            // 制作线显示=隐藏时，这些短竖线和中心刻度全部不画
            // 图片线/文字线不画（它们是"素材"，画上刻度只会挡住图）
            if (gridMode != 3 && l.kind == LineKind.Normal) {
                gridPaint.alpha = if (isActive) 50 else if (dimmed) 16 else 26
                for (k in 1 until vLines) {
                    val x = cellW * k - w / 2f
                    canvas.drawLine(x, -dp(16f), x, dp(16f), gridPaint)
                }
                gridPaint.alpha = 100
                canvas.drawLine(0f, -dp(12f), 0f, dp(12f), gridPaint)
                // 竖向放置线：在判定线自己的坐标系里画（旋转时跟着转）；
                // placeLinesAll=true 时每条判定线都画，否则只画选中的那条
                if (showV && (placeLinesAll || isActive)) {
                    gridPaint.alpha = 32
                    gridPaint.strokeWidth = dp(1.5f)
                    val cover = h * 1.2f
                    for (k in 1 until vLines) {
                        val x = cellW * k - w / 2f
                        canvas.drawLine(x, -cover, x, cover, gridPaint)
                    }
                    gridPaint.alpha = 100
                    gridPaint.strokeWidth = dp(2f)
                }
            }
            canvas.restore()

            // 多条线时标个号，方便区分（活动线高亮）；打歌时不画（Phira 没有这些编号）
            if (multi && !playMode) {
                beatNumPaint.textSize = dp(11f)
                beatNumPaint.style = Paint.Style.FILL
                beatNumPaint.color = when {
                    isActive -> intColor(0xFF00E5FF)
                    dimmed -> intColor(0x40FFFFFF)
                    else -> intColor(0x80FFFFFF)
                }
                canvas.drawText("L$li", dp(6f), lcY - dp(10f), beatNumPaint)
            }

            // 这里传的是音符宽度（不是网格格宽）：音符/外框都用 Phira 的固定宽度
            // 负 alpha 的线在 Phira 里连音符一起不画；编辑时仍画，方便改谱
            if (!lineHiddenByNegAlpha) drawNotesOfLine(canvas, l, now, noteW, lcX, lcY, rot, dhAll, dimmed)
            if (visSb != null && lineA > 0.004f) {
                visSb.append(" L").append(l.id).append("(a=").append(Math.round(lineA * 100f) / 100f)
                    .append(",r=").append(Math.round(rot)).append(",p=").append(l.posPct).append(")")
            }
        }
        if (logVisible) {
            lastPlayLogSec = sec
            if (visSb != null && visSb.isNotEmpty()) DebugLog.add("autoplay", "t=${sec}s 可见线$visSb")
            // 同一秒里"可能在画面上的长条"（排查长条残留）
            val hs = StringBuilder()
            var hn = 0
            for (n in notes) {
                if (n.type != 2 || hn >= 16) continue
                if (now < n.startMs - 1500 || now > n.endMs + 500) continue
                hs.append(" H(").append(n.lineId).append(',').append(Math.round(n.posX * 100f) / 100f)
                    .append(',').append(n.startMs).append('-').append(n.endMs).append(')')
                hn++
            }
            if (hs.isNotEmpty()) DebugLog.add("autoplay", "t=${sec}s 长条$hs")
        }
        linePaint.strokeWidth = dp(6f)

        // 判定线编辑模式：中心白色方框 + 旋转箭头 + 关键帧方块 + 左侧时间条
        if (lineEditActive) {
            lineFrameOf(activeLine, now, framePose)
            val lcX = framePose[0]
            val lcY = framePose[1]
            drawLineEditUi(canvas, lcX, lcY, now, w, h)
        }

        // ---- 编辑模式框选：长按1s膨胀动画 + 框选白色预览框 ----
        if (editMode) {
            val nowR = SystemClock.elapsedRealtime()
            if (boxActive) {
                val p = ((nowR - boxStartedAt).toFloat() / 300f).coerceIn(0f, 1f)
                val size = dp(150f) * p
                livePaint.style = Paint.Style.STROKE
                livePaint.strokeWidth = dp(2.5f)
                livePaint.color = intColor(0xDDFFFFFF)
                canvas.drawRoundRect(boxX0 - size / 2, boxY0 - size / 2, boxX0 + size / 2, boxY0 + size / 2, dp(14f) * p, dp(14f) * p, livePaint)
                livePaint.style = Paint.Style.FILL
                livePaint.color = ((50 * (1f - p / 2f)).toInt() shl 24) or 0xFFFFFF
                canvas.drawRoundRect(boxX0 - size / 2, boxY0 - size / 2, boxX0 + size / 2, boxY0 + size / 2, dp(14f) * p, dp(14f) * p, livePaint)
                livePaint.style = Paint.Style.FILL
                if (p >= 1f) boxActive = false
            } else if (selectingBox) {
                val rx0 = minOf(boxX0, boxX1); val ry0 = minOf(boxY0, boxY1)
                val rx1 = maxOf(boxX0, boxX1); val ry1 = maxOf(boxY0, boxY1)
                livePaint.color = intColor(0x40FFFFFF)
                canvas.drawRect(rx0, ry0, rx1, ry1, livePaint)
                livePaint.style = Paint.Style.STROKE
                livePaint.strokeWidth = dp(2.5f)
                livePaint.color = intColor(0xCCFFFFFF)
                canvas.drawRect(rx0, ry0, rx1, ry1, livePaint)
                livePaint.style = Paint.Style.FILL
            }

            // 编辑模式 + 第四次：按住时画长条放置预览（起点=按下，随手指拉伸）
            if (pass == 4) {
                for (i in 0 until fingers.size()) {
                    val f = fingers.valueAt(i)
                    if (!f.active || f.mode != 1) continue
                    val y1 = f.downY
                    val y2 = f.curY
                    // 拖动方向决定正面/反面：从上往下拖 = 反面（倒着放）
                    val rev = Math.abs(y2 - y1) >= dp(6f) && y2 > y1
                    if (Math.abs(y2 - y1) < dp(6f)) {
                        livePaint.style = Paint.Style.STROKE
                        livePaint.strokeWidth = dp(2f)
                        livePaint.color = intColor(0xAAFFFFFF)
                        canvas.drawRoundRect(f.downX - cellW * 0.55f, y1 - dp(10f), f.downX + cellW * 0.55f, y1 + dp(10f), dp(4f), dp(4f), livePaint)
                        livePaint.style = Paint.Style.FILL
                    } else {
                        val x1 = f.downX
                        val bw = cellW * 0.55f
                        drawTex(canvas, holdBmp, x1 - bw, minOf(y1, y2), x1 + bw, maxOf(y1, y2), 110)
                        livePaint.color = if (rev) intColor(0x66FF5252) else intColor(0x6600E5FF)
                        canvas.drawRect(x1 - cellW * 0.6f, y1 - dp(2f), x1 + cellW * 0.6f, y1 + dp(2f), livePaint)
                        canvas.drawRect(x1 - cellW * 0.6f, y2 - dp(2f), x1 + cellW * 0.6f, y2 + dp(2f), livePaint)
                    }
                }
            }
        }

        // ---- 实时预览 ----
        val nowReal = SystemClock.elapsedRealtime()

        // 打击特效：Phira hit_fx 精灵图（8 列 x 7 行 = 56 帧）
        val fxBmp = hitFxBmp
        if (fxBmp != null && hitFxList.isNotEmpty()) {
            val fw = fxBmp.width / hitFxCols
            val fh = fxBmp.height / hitFxRows
            val total = hitFxCols * hitFxRows
            val size = dp(150f) * hitFxScale
            // 打击特效播放速度 ×2（时长减半，逐帧更快）
            val dur = (hitFxDurMs / 2).coerceAtLeast(1L)
            val it = hitFxList.iterator()
            while (it.hasNext()) {
                val fx = it.next()
                val age = nowReal - fx.at
                if (age > dur) { it.remove(); continue }
                if (age < 0) continue
                val idx = (age * total / dur).toInt().coerceIn(0, total - 1)
                val col = idx % hitFxCols
                val row = idx / hitFxCols
                texPaint.alpha = 255
                fxSrc.set(col * fw, row * fh, (col + 1) * fw, (row + 1) * fh)
                fxDst.set(fx.x - size / 2, fx.y - size / 2, fx.x + size / 2, fx.y + size / 2)
                canvas.drawBitmap(fxBmp, fxSrc, fxDst, texPaint)
            }
        }

        // 编辑模式不显示手指光圈（避免遮挡/误认）
        if (!editMode) for (i in 0 until fingers.size()) {
            val f = fingers.valueAt(i)
            if (!f.active) continue
            val pulse = 0.5f + 0.5f * Math.sin(nowReal / 110.0).toFloat()
            livePaint.style = Paint.Style.FILL
            livePaint.color = intColor(0x3000E5FF)
            canvas.drawCircle(f.curX, f.curY, dp(34f) + dp(6f) * pulse, livePaint)
            livePaint.style = Paint.Style.STROKE
            livePaint.strokeWidth = dp(2.5f)
            livePaint.color = intColor(0xCC00E5FF)
            canvas.drawCircle(f.curX, f.curY, dp(22f), livePaint)
            livePaint.style = Paint.Style.FILL
        }

        // 长按填充（第二/三次；全量模式下交给自己的手势，不能再用旧的铺点逻辑）
        if (editMode) checkBoxLongPress() else if (!fullMode) tickFill()

        // 噪域：编辑模式画在音符之上方便核对「哪里亮、什么时候亮」；选中/编辑时强制显示
        if (!playMode && (noiseOverlay || noiseEditActive || noiseCreatePending || noiseEditIndex >= 0)) {
            if (noiseAreas.isNotEmpty() || noiseEditActive) drawNoiseAreas(canvas, now, w, h)
            if (noiseCreatePending) drawNoiseCreatePreview(canvas)
        }

        // Autoplay 不下判断、也不给触摸反馈：手指按在噪域上照样「断触」（不影响成绩，纯演出），
        // 但**不画任何跟随手指的特效** —— 都 autoplay 了，屏幕上不该出现玩家操作痕迹。

        if (noiseView) canvas.restore()

        // 噪域编辑：左侧时间条（和判定线编辑一样，画在未缩放的屏幕坐标里，触摸也用它）
        if (noiseEditActive) drawNoiseTimeBar(canvas, now, h)

        // 打歌 HUD（判定已在帧首做完了）
        if (playMode) drawJudgeOverlay(canvas, now, w, h)

        // 右侧判定线抽屉 + 左侧剪贴板面板（画在最上层）
        drawLineSwitcher(canvas)
        drawClipboard(canvas)

        // 只在"画面确实会变"的时候请求下一帧：
        // 暂停编辑时画面是静止的，没必要 60fps 满负荷重画（白白占满 CPU/GPU，触摸事件都被拖慢）
        if (needsNextFrame()) postInvalidateOnAnimation()
    }

    /** 下一帧还需要重画吗（播放/手指按着/特效/面板滑动/框选动画/行位移动画） */
    private fun needsNextFrame(): Boolean {
        if (playMode || playingProvider?.invoke() == true) return true
        if (isTouchingNow()) return true
        if (hitFxList.isNotEmpty() || holdFxList.isNotEmpty()) return true
        if (boxActive || selectingBox) return true
        if (rowMoveActive || rowDragActive || clipMoveActive) return true
        val pTarget = if (panelOpen) 1f else 0f
        if (Math.abs(panelAnim - pTarget) > 0.004f) return true
        val cTarget = if (clipOpen) 1f else 0f
        if (Math.abs(clipAnim - cTarget) > 0.004f) return true
        for (v in rowShiftAnim) if (v != 0f) return true
        for (v in clipShiftAnim) if (v != 0f) return true
        return false
    }

    // ---------------------------------------------------------------- 噪域
    /** 噪域框专用画笔（复用一个，避免每帧 new Paint） */
    private val noisePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Autoplay 时把触摸事件喂给噪域：手指按在亮着的噪域里 → 这根手指失效。
     * 失效的手指数用来让正在按的长条断掉、并且触发断触特效（见 [NoiseRuntime]）。
     */
    private fun trackNoiseFingers(e: android.view.MotionEvent) {
        if (noiseAreas.isEmpty()) return
        val now = playMs().toDouble()
        val cw = chartWidthPx()
        val ch = chartHeightPx()
        val w = width.toFloat()
        val h = height.toFloat()
        val rt = noiseRuntime
        val action = e.actionMasked
        when (action) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) e.actionIndex else 0
                val id = e.getPointerId(idx)
                val before = rt.jammedCount()
                rt.updateFinger(id, true, e.getX(idx), e.getY(idx), now, cw, ch, w, h)
                if (rt.jammedCount() > before) onNoiseJam(now)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // 已经在断触的手指不会因为划出去而恢复（Phigros：松开前一直失效），
                // 但「新按住、还没注册过」的手指要在这里补检测
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val before = rt.jammedCount()
                    rt.updateFinger(id, true, e.getX(i), e.getY(i), now, cw, ch, w, h)
                    if (rt.jammedCount() > before) onNoiseJam(now)
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (action == android.view.MotionEvent.ACTION_POINTER_UP) e.actionIndex else 0
                val id = e.getPointerId(idx)
                rt.updateFinger(id, false, e.getX(idx), e.getY(idx), now, cw, ch, w, h)
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                rt.clear()
            }
        }
        invalidate()
    }

    /** 打歌时各手指的当前位置（只用于画断触红圈，键 = pointerId） */

    /** 新发生一次断触：抖一下 + 记日志（音效由 Activity 侧的 [onNoiseJamSound] 钩子处理） */
    private fun onNoiseJam(nowMs: Double) {
        jamPulseAt = SystemClock.elapsedRealtime()
        noiseJamCount++
        onNoiseJamSound?.invoke()
        DebugLog.add("noise", "断触 at=${nowMs.toLong()}ms 累计=$noiseJamCount")
    }

    /** 断触音效（由 Activity 接到自己的音效通道） */
    var onNoiseJamSound: (() -> Unit)? = null

    /** 本次打歌一共断触几次（结算/调试看） */
    var noiseJamCount = 0
        private set

    /** 最近一次断触的真实时刻（画抖动用） */
    private var jamPulseAt = 0L

    /**
     * 噪域在打歌时的完整视觉：
     *  1. 区域内铺红黑像素块（Phigros 那种「数据方块」），亮度随激活状态起落；
     *  2. 边缘红色光晕 + 一圈描边；
     *  3. 激活时画面轻微位移 / 色散（只在区域内做，用 canvas 裁剪保证不外溢）；
     *  4. 断触的手指周围画红圈（与噪域同色），松手前一直存在。
     *
     * 全部用 Canvas 画，不引 GL/着色器；但官谱噪域能有一万多块，
     * 所以这里靠 [noiseIndex] 的按时间索引 + [MAX_NOISE_PLAY_DRAW] 上限控制每帧开销。
     */
    private fun drawNoisePlay(canvas: Canvas, now: Long, w: Float, h: Float) {
        if (noiseAreas.isEmpty() || !noiseOverlay) return
        val cw = chartWidthPx()
        val ch = chartHeightPx()
        val t = now.toDouble()
        noiseSamples.clear()
        // ★ 只采样「此刻活着」的区域：官谱一万四千多块全量采样 = 每帧 4 万多个 FloatArray，
        //   必然 OOM。索引 + [MAX_NOISE_PLAY_DRAW] 上限之外，优先级按「亮着的先画」。
        noiseIndex.build(noiseAreas)
        noiseIndex.query(t, noiseVisible, noiseVisibleIdx)
        // 亮着的排前面（Phigros 的噪域重点就是那些正在掐手指的块）。
        // 顺序只对绘制有意义，这里就地重排 [noiseVisible]（下标表不再使用）。
        val vis = noiseVisible
        var start = 0
        for (k in vis.indices) {
            if (vis[k].activeAt(t)) {
                val a = vis[start]; vis[start] = vis[k]; vis[k] = a
                start++
            }
        }
        val limit = if (vis.size > MAX_NOISE_PLAY_DRAW) MAX_NOISE_PLAY_DRAW else vis.size
        for (k in 0 until limit) {
            noiseRuntime.sample(vis[k], t, cw, ch, w, h)?.let { noiseSamples.add(it) }
        }
        if (noiseSamples.isEmpty()) return

        // 画面抖动：断触瞬间整体轻轻一震（Phigros 的红色特效伴随震动）
        val sinceJam = SystemClock.elapsedRealtime() - jamPulseAt
        val shake = if (sinceJam in 0..220) {
            val k = 1f - sinceJam / 220f
            Math.sin(sinceJam * 0.09).toFloat() * dp(3f) * k
        } else 0f

        val pw = w
        val ph = h
        for (s in noiseSamples) {
            if (!s.alive) continue
            // ---- 屏幕包围盒粗裁 ----
            // 噪域被 move/scale 事件挪出屏幕后没必要再铺像素格（每块一千多个格子 + 5 层光晕）。
            // 必须在 clipPath 之前判：clipPath 本身也要开销。
            var bl = minOf(minOf(s.xs[0], s.xs[1]), minOf(s.xs[2], s.xs[3]))
            var br = maxOf(maxOf(s.xs[0], s.xs[1]), maxOf(s.xs[2], s.xs[3]))
            var bt = minOf(minOf(s.ys[0], s.ys[1]), minOf(s.ys[2], s.ys[3]))
            var bb = maxOf(maxOf(s.ys[0], s.ys[1]), maxOf(s.ys[2], s.ys[3]))
            if (br < 0f || bl > pw || bb < 0f || bt > ph) continue
            // 警示区：只有浅红色框，里面**不铺噪点特效**（用户点名）
            if (s.warning) {
                noisePaint.style = Paint.Style.FILL
                noisePaint.color = intColor(0x14FF8A80)
                pathTmp.reset()
                pathTmp.moveTo(s.xs[0], s.ys[0])
                for (i in 1 until 4) pathTmp.lineTo(s.xs[i], s.ys[i])
                pathTmp.close()
                canvas.drawPath(pathTmp, noisePaint)
                noisePaint.style = Paint.Style.STROKE
                noisePaint.strokeWidth = dp(2f)
                noisePaint.color = intColor(0x99FF8A80)
                canvas.drawPath(pathTmp, noisePaint)
                continue
            }
            canvas.save()
            if (shake != 0f) canvas.translate(shake, 0f)
            // 用四边形做裁剪：区域旋转后也不会画到外面
            pathTmp.reset()
            pathTmp.moveTo(s.xs[0], s.ys[0])
            for (i in 1 until 4) pathTmp.lineTo(s.xs[i], s.ys[i])
            pathTmp.close()
            canvas.clipPath(pathTmp)

            // ---- 1. 红黑像素块底 ----
            val left = bl
            val right = br
            val top = bt
            val bottom = bb
            val cell = Math.max(dp(9f), (right - left) / 26f)          // 单元格边长：块不要太碎
            val glow = s.intensity * (if (s.warning) 0.45f else 1f)   // 警示区画得更浅
            // 底色：深红 / 近黑交替（Phigros 的噪域底色就是这么一块块拼的）
            noisePaint.style = Paint.Style.FILL
            noisePaint.color = intColor(0xFF1A0507)
            noisePaint.alpha = (255 * (0.55f + 0.4f * glow)).toInt().coerceIn(0, 255)
            canvas.drawRect(left, top, right, bottom, noisePaint)

            // 块：用「伪随机但每帧稳定」的哈希决定哪块亮，避免每帧全屏重排导致闪烁
            val seed = ((s.cx * 7.13f + s.cy * 3.71f).toInt())
            val step = cell
            // ★ 只在「区域 ∩ 屏幕」的范围内铺格子。
            //   噪域被 scale 放大后可能横跨几十个屏宽，按整块面积铺就是几万次 drawRect，
            //   而且全落在屏幕外 —— 纯浪费。
            //   `row/col` 必须仍按「从 left/top 数第几格」算，否则画面一滚动哈希基准
            //   就变了，格子会整体跳一下（看起来像噪点在乱闪）。
            val gxEnd = minOf(right, pw)
            val gyEnd = minOf(bottom, ph)
            if (gyEnd <= top || gxEnd <= left) {
                canvas.restore()
                continue
            }
            // 屏幕上方有多少整格要跳过（top 可能远在屏幕之上）
            val rowSkip = if (top < 0f) ceil((0f - top) / step).toInt() else 0
            var row = rowSkip
            var gy = top + rowSkip * step
            while (gy < gyEnd) {
                // 屏幕左侧有多少整格要跳过（left 可能远在屏幕之外）
                val colSkip = if (left < 0f) ceil((0f - left) / step).toInt() else 0
                var col = colSkip
                var gx = left + colSkip * step
                while (gx < gxEnd) {
                    val rnd = hash01(seed, row, col, (t / 130.0).toInt())     // 每 130ms 换一批，形成流动感
                    if (rnd > 0.74f - 0.22f * glow) {
                        // 亮块：越亮越多、越鲜艳（阈值高一点，块之间才留得住黑底，不会糊成一片）
                        val a = (0.14f + 0.46f * glow) * (0.35f + 0.65f * rnd)
                        noisePaint.color = intColor(0xFFFF2A32)
                        noisePaint.alpha = (255 * a).toInt().coerceIn(0, 255)
                        canvas.drawRect(gx, gy, gx + step * 0.90f, gy + step * 0.90f, noisePaint)
                    } else if (rnd > 0.52f) {
                        // 暗块：只有淡淡一层，主要用来做「数据块」的层次
                        noisePaint.color = intColor(0xFF5C0D10)
                        noisePaint.alpha = (255 * (0.06f + 0.16f * glow)).toInt().coerceIn(0, 255)
                        canvas.drawRect(gx, gy, gx + step * 0.90f, gy + step * 0.90f, noisePaint)
                    }
                    gx += step
                    col++
                }
                gy += step
                row++
            }

            // ---- 2. 边缘光晕：贴着四条边**向内**衰减的红光 ----
            if (glow > 0.02f) {
                val band = Math.min(minOf(right - left, bottom - top) * 0.18f, dp(26f))
                if (band > 1f) {
                    // 外→内四条渐变带（用若干层递减的描边近似，避免再开一个 shader）
                    val layers = 5
                    noisePaint.style = Paint.Style.STROKE
                    for (k in layers downTo 1) {
                        noisePaint.strokeWidth = band * k / layers
                        noisePaint.color = intColor(0xFFFF2A32)
                        // 越靠外越淡（向内衰减）
                        noisePaint.alpha = (255 * (0.055f * glow * (layers - k + 1) / layers)).toInt().coerceIn(0, 255)
                        canvas.drawRect(
                            left + band * k / layers / 2f, top + band * k / layers / 2f,
                            right - band * k / layers / 2f, bottom - band * k / layers / 2f, noisePaint
                        )
                    }
                }
            }
            // 描边：外框线压在光晕上面
            noisePaint.style = Paint.Style.STROKE
            noisePaint.strokeWidth = dp(1.6f)
            noisePaint.color = intColor(0xFFFF5454)
            noisePaint.alpha = (255 * (0.35f + 0.6f * glow)).toInt().coerceIn(0, 255)
            canvas.drawRect(left + dp(0.8f), top + dp(0.8f), right - dp(0.8f), bottom - dp(0.8f), noisePaint)
            canvas.restore()
        }
        noisePaint.alpha = 255
        noisePaint.style = Paint.Style.FILL
    }

    /** 稳定的伪随机（同一 (seed,row,col,epoch) 每帧结果一致，块才不会乱跳） */
    private fun hash01(seed: Int, row: Int, col: Int, epoch: Int): Float {
        var x = seed xor (row * 374761393) xor (col * 668265263) xor (epoch * 2147483647)
        x = (x xor (x shr 13)) * 1274126177
        x = x xor (x shr 16)
        return ((x and 0x7FFFFFFF).toFloat() / 2147483647f)
    }

    /**
     * 画出谱面里的噪域概览（RPE `blockAreaList`）。
     *
     * 这里只负责概览层（编辑手势见 [onNoiseEditTouch]）。三个信息：
     *  1. 区域框（世界坐标 → 屏幕）：左上角标序号与名字；
     *  2. 激活状态：此刻正在激活 → 填实色；只是存在 → 极淡的填充 + 虚线框；
     *  3. 激活区间条：贴在框的内侧顶部，一段一段画，能一眼看出「什么时候亮」。
     *
     * 位置换算：RPE 世界 x ∈ ±675、y ∈ ±450（y 向上为正），屏幕上对应
     * [chartWidthPx]/[chartHeightPx] 这个 16:9 视口，所以
     * `px = cx + wx × chartWidthPx / 1350`、`py = cy − wy × chartHeightPx / 900`。
     */
    private fun drawNoiseAreas(canvas: Canvas, now: Long, w: Float, h: Float) {
        val cw = chartWidthPx()
        val ch = chartHeightPx()
        if (cw <= 0f || ch <= 0f) return
        val sx = cw / 1350f
        val sy = ch / 900f
        val ox = w / 2f
        val oy = h / 2f
        val t = now.toDouble()

        // ★ 性能：官谱一张就能有一万四千多块噪域（ハテ AT = 14355 块），
        //   以前这里每帧遍历全部区域、每块新建一个 DashPathEffect + 拼一个标题字符串，
        //   进编辑页直接卡死 / OOM。改法：
        //   1. 用按时间的活动窗口索引，只取「此刻真的可见」的少数几块；
        //   2. 数量上限 [MAX_NOISE_DRAWN]，超了就只画边框不写字；
        //   3. 虚线效果缓存复用，标题字符串按「序号+名字」缓存。
        noiseIndex.build(noiseAreas)
        noiseIndex.query(t, noiseVisible, noiseVisibleIdx)
        if (noiseVisible.isEmpty()) return

        // ---- 屏幕包围盒粗裁（视锥剔除的第一步）----
        // 噪域可能被 move/scale 事件挪走、也可能本来就画在谱面视口之外。
        // 先把「整块都在屏幕外」的过滤掉，再做数量截断 —— 顺序反了会出问题：
        // 要是先截断成前 240 块、而这 240 块恰好都在屏幕外，屏幕上就一块噪域都不剩。
        // 这里只用未变换的矩形（比实际渲染范围保守），真正的位置由 activeAt 之后的
        // 绘制流程决定 —— 噪域概览画的是「原始区域」，不做 move/scale 变换（那是打歌时的事）。
        val marginX = dp(40f)
        val marginY = dp(40f)
        noiseOnScreen.clear()
        noiseOnScreenIdx.clear()
        for (k in noiseVisible.indices) {
            val a = noiseVisible[k]
            val rc = a.rectAt(t)
            val l = ox + rc[0] * sx
            val r = ox + rc[2] * sx
            val top = oy - rc[3] * sy
            val bot = oy - rc[1] * sy
            if (r < -marginX || l > w + marginX || bot < -marginY || top > h + marginY) continue
            noiseOnScreen.add(a)
            noiseOnScreenIdx.add(noiseVisibleIdx[k])
            // 攒够上限就不再收（截断发生在这里，而不是在绘制循环里）
            if (noiseOnScreen.size >= MAX_NOISE_DRAWN) break
        }
        if (noiseOnScreen.isEmpty()) return

        val n = noiseOnScreen.size
        // 块数超过 [MAX_NOISE_LABELS] 就不写标题（一万多个字符串每帧拼接本身就卡）
        val labelBudget = if (n > MAX_NOISE_LABELS) 0 else n
        val dash = noiseDash ?: android.graphics.DashPathEffect(
            floatArrayOf(dp(7f), dp(5f)), 0f
        ).also { noiseDash = it }

        for (k in 0 until n) {
            val a = noiseOnScreen[k]
            val idx = noiseOnScreenIdx[k]
            val rc = a.rectAt(t)
            val l = ox + rc[0] * sx
            val r = ox + rc[2] * sx
            val top = oy - rc[3] * sy
            val bot = oy - rc[1] * sy

            val active = a.activeAt(t)
            val dimHidden = !a.visible

            // 填充：激活 = 明显的暖红（Phira Pro 噪域的调性），未激活 = 极淡；
            // 隐藏的噪域在编辑页仍然画出来（否则找不到它），但压暗成"幽灵框"
            noisePaint.style = Paint.Style.FILL
            noisePaint.color = when {
                dimHidden -> intColor(0x10FFFFFF)
                active && a.warning -> intColor(0x14FF8A80)
                active -> intColor(0x33FF3B30)
                else -> intColor(0x0DFFFFFF)
            }
            canvas.drawRect(l, top, r, bot, noisePaint)

            // 边框：激活实线，未激活虚线
            noisePaint.style = Paint.Style.STROKE
            noisePaint.strokeWidth = dp(if (active) 1.8f else 1f)
            noisePaint.color = when {
                dimHidden -> intColor(0x55FFFFFF)
                active && a.warning -> intColor(0x99FF8A80)
                active -> intColor(0xCCFF6B60L)
                else -> intColor(0x66FFFFFF)
            }
            noisePaint.pathEffect = if (active && !dimHidden) null else dash
            canvas.drawRect(l, top, r, bot, noisePaint)

            // 激活区间条：框内侧顶部一条 3dp 的细带，按整个生命周期归一化
            val span = (a.disappearMs - a.appearMs)
            if (a.intervals.isNotEmpty() && span > 0 && span < Double.MAX_VALUE / 2 && bot - top > dp(10f)) {
                val barY = top + dp(3f)
                val barH = Math.max(dp(2f), Math.min(dp(4f), (bot - top) * 0.04f))
                for (iv in a.intervals) {
                    val x0 = l + ((iv.startMs - a.appearMs) / span).toFloat().coerceIn(0f, 1f) * (r - l)
                    val x1 = l + ((iv.endMs - a.appearMs) / span).toFloat().coerceIn(0f, 1f) * (r - l)
                    val inside = t >= iv.startMs && t < iv.endMs
                    noisePaint.style = Paint.Style.FILL
                    noisePaint.pathEffect = null
                    noisePaint.color = if (inside) intColor(0xFFFFD24AL) else intColor(0x59FFD24AL)
                    canvas.drawRect(x0, barY, Math.max(x1, x0 + dp(1.5f)), barY + barH, noisePaint)
                }
            }

            // 标题：序号 + 名字（没名字就只写序号），再标一下此刻状态
            // 块数过多时不写标题（一万多个字符串每帧拼接本身就是卡顿源）
            if (k < labelBudget) {
                val label = buildString {
                    append("噪域 ").append(idx + 1)
                    if (a.name.isNotEmpty()) append(' ').append(a.name)
                    if (a.invert) append(" ·反")
                    if (!a.visible) append(" ·隐")
                    if (a.warning) append(" ·警示")
                    if (active) append(" ·亮")
                }
                noisePaint.style = Paint.Style.FILL
                noisePaint.pathEffect = null
                noisePaint.color = if (active) intColor(0xFFFF8A80L) else intColor(0x99FFFFFF)
                noisePaint.textSize = dp(10f)
                noisePaint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, l + dp(4f), top - dp(4f), noisePaint)
            }

            // 选中的噪域（只在右侧窗口点了项目，还没进编辑）：蓝色框，**不画四角圆圈**
            if (!noiseEditActive && noiseEditIndex == idx) {
                noisePaint.style = Paint.Style.STROKE
                noisePaint.strokeWidth = dp(2f)
                noisePaint.pathEffect = null
                noisePaint.color = intColor(0xFF2196F3)
                canvas.drawRect(l, top, r, bot, noisePaint)
                noisePaint.pathEffect = null
            }
            // 正在编辑的噪域：青色描边 + 四个角手柄（选中的角更大、实心青色）
            if (noiseEditActive && idx == noiseEditIndex) {
                noisePaint.style = Paint.Style.STROKE
                noisePaint.strokeWidth = dp(2.5f)
                noisePaint.pathEffect = null
                noisePaint.color = intColor(0xFF00E5FF)
                canvas.drawRect(l, top, r, bot, noisePaint)
                for (c in 0 until 4) {
                    val px = if (c == 1 || c == 3) r else l
                    val py = if (c == 2 || c == 3) bot else top
                    val sel = c == noiseCorner
                    val rad = dp(if (sel) 11f else 7f)
                    noisePaint.style = Paint.Style.FILL
                    noisePaint.color = if (sel) intColor(0xFF00E5FF) else intColor(0xCC1A1A1A)
                    canvas.drawCircle(px, py, rad, noisePaint)
                    noisePaint.style = Paint.Style.STROKE
                    noisePaint.strokeWidth = dp(1.5f)
                    noisePaint.color = intColor(0xFFFFFFFF)
                    canvas.drawCircle(px, py, rad, noisePaint)
                }
            }
        }
        noisePaint.pathEffect = null
        noisePaint.textAlign = Paint.Align.LEFT
    }

    /**
     * 噪域编辑的左侧时间条：显示这块噪域的矩形关键帧（和判定线编辑的时间条同一套外观），
     * 画在**未缩放**的屏幕坐标里，所以触摸用原始 e.x/e.y 判定（见 onNoiseEditTouch）。
     */
    private fun drawNoiseTimeBar(canvas: Canvas, now: Long, h: Float) {
        val a = noiseAreaNow() ?: return
        val barX = dp(8f)
        gridPaint.alpha = 60
        canvas.drawLine(barX, dp(40f), barX, h - dp(120f), gridPaint)
        for (k in a.rectKeys) {
            val ky = h / 2f - (k.timeMs - now) * pxPerMs
            if (ky < dp(30f) || ky > h - dp(130f)) continue
            livePaint.style = Paint.Style.FILL
            livePaint.color = intColor(0xFF00E5FF)
            canvas.save()
            canvas.translate(barX, ky)
            canvas.rotate(45f)
            canvas.drawRect(-dp(7f), -dp(7f), dp(7f), dp(7f), livePaint)
            canvas.restore()
            if (k.timeMs == noiseSelectedKfMs) {
                livePaint.style = Paint.Style.STROKE
                livePaint.strokeWidth = dp(2.5f)
                livePaint.color = intColor(0xFFFF5252)
                canvas.drawRoundRect(
                    barX - dp(16f), ky - dp(16f), barX + dp(16f), ky + dp(16f),
                    dp(5f), dp(5f), livePaint
                )
                livePaint.style = Paint.Style.FILL
            }
        }
        livePaint.style = Paint.Style.FILL
    }

    /**
     * 噪域时间条触摸（和判定线编辑用同一套"预览偏移"机制，拖动中不反复 seek 播放器）：
     * 点圆点 = 选中并跳到那一帧；再点已选中的圆点 = 删掉这一帧；
     * 点/拖空白 = 连续拖动播放头，松手才真正 seek。
     */
    private fun handleNoiseTimeBarTouch(e: android.view.MotionEvent, now: Long, h: Float): Boolean {
        val a = noiseAreaNow() ?: return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val hitR = dp(26f)
                for (k in a.rectKeys) {
                    val ky = h / 2f - (k.timeMs - now) * pxPerMs
                    if (ky < dp(30f) || ky > h - dp(130f)) continue
                    if (Math.abs(e.y - ky) < hitR) {
                        if (noiseSelectedKfMs == k.timeMs) {
                            // 再点一下 = 删这一帧
                            val keys = ArrayList(a.rectKeys).also { it.removeAll { one -> one.timeMs == k.timeMs } }
                            val edited = a.edited(rectKeys = keys)
                            edited.raw = NoiseArea.toJsonObject(edited)
                            noiseMutate { noiseAreas[noiseEditIndex] = edited }
                            invalidateNoiseRows()
                            noiseSelectedKfMs = -1L
                            DebugLog.add("noise", "delete key t=${k.timeMs}")
                        } else {
                            noiseSelectedKfMs = k.timeMs
                            seekPreviewTo(k.timeMs)
                            noiseBarCommit = true
                        }
                        notifyNoiseInfo()
                        invalidate()
                        return true
                    }
                }
                // 空白处：开始拖动播放头。**相对拖动**（从当前播放位置继续，不是按屏幕中心绝对跳），
                // 和判定线编辑的 addEditSeek 完全同一套；拖动中只改预览偏移，松手才 seek。
                noiseBarDragging = true
                noiseBarCommit = true
                noiseBarLastY = e.y
            }
            android.view.MotionEvent.ACTION_MOVE -> if (noiseBarDragging) {
                val dt = (e.y - noiseBarLastY) / pxPerMs       // 往下拖 = 往后
                noiseBarLastY = e.y
                if (dt != 0f) {
                    noiseSelectedKfMs = -1L
                    addEditSeek(dt.toInt())
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                noiseBarDragging = false
                if (noiseBarCommit) {
                    noiseBarCommit = false
                    flushEditSeek()
                }
            }
        }
        return true
    }

    private fun drawNoiseCreatePreview(canvas: Canvas) {
        val l = minOf(noiseCreateStartX, noiseCreateCurX)
        val r = maxOf(noiseCreateStartX, noiseCreateCurX)
        val top = minOf(noiseCreateStartY, noiseCreateCurY)
        val bot = maxOf(noiseCreateStartY, noiseCreateCurY)
        noisePaint.style = Paint.Style.STROKE
        noisePaint.strokeWidth = dp(2f)
        noisePaint.pathEffect = noiseDash
        noisePaint.color = intColor(0xFF00E5FF)
        canvas.drawRect(l, top, r, bot, noisePaint)
        noisePaint.pathEffect = null
    }

    // ---------------------------------------------------------------- 噪域编辑
    /** 噪域世界坐标 → 基准屏幕坐标（不含噪域编辑的平移缩放） */
    private fun nWorldX(wx: Float): Float = width / 2f + wx * (chartWidthPx() / 1350f)
    private fun nWorldY(wy: Float): Float = height / 2f - wy * (chartHeightPx() / 900f)
    private fun nScreenX(sx: Float): Float = (sx - width / 2f) / (chartWidthPx() / 1350f)
    private fun nScreenY(sy: Float): Float = -(sy - height / 2f) / (chartHeightPx() / 900f)

    /** 触摸点 → 基准屏幕坐标（把"平移+居中缩放"的编辑视图逆变换回去） */
    private fun noiseViewInvX(x: Float): Float =
        (x - noiseViewPanX - width / 2f) / noiseViewZoom.coerceAtLeast(0.05f) + width / 2f
    private fun noiseViewInvY(y: Float): Float =
        (y - noiseViewPanY - height / 2f) / noiseViewZoom.coerceAtLeast(0.05f) + height / 2f

    private fun noiseAreaNow(): NoiseArea? = noiseAreas.getOrNull(noiseEditIndex)
    private fun noiseRectNow(a: NoiseArea): FloatArray = a.rectAt(playMs().toDouble())

    /** 四个角的基准屏幕坐标（0=左上 1=右上 2=左下 3=右下） */
    private fun noiseCornerScreen(rc: FloatArray, corner: Int): FloatArray {
        val wx = if (corner == 1 || corner == 3) rc[2] else rc[0]
        val wy = if (corner == 2 || corner == 3) rc[1] else rc[3]
        return floatArrayOf(nWorldX(wx), nWorldY(wy))
    }

    /** 把选中角平移到屏幕中心，并放大 2 倍（画布平移缩放） */
    private fun centerNoiseView(notify: Boolean = true) {
        val a = noiseAreaNow() ?: return
        val p = noiseCornerScreen(noiseRectNow(a), noiseCorner)
        val cx = width / 2f
        val cy = height / 2f
        noiseViewZoom = 2f
        noiseViewPanX = -noiseViewZoom * (p[0] - cx)
        noiseViewPanY = -noiseViewZoom * (p[1] - cy)
        if (notify) notifyNoiseInfo()
        invalidate()
    }

    private fun notifyNoiseInfo() {
        val a = noiseAreaNow() ?: return
        onNoiseEditInfo?.invoke(a, noiseCorner)
    }

    /**
     * 把原始 move/scale 事件在当前时刻"烘焙"进矩形（只用于进入编辑的那一帧）。
     * 旋转忽略（矩形保持轴对齐），这一步之后噪域只由矩形关键帧驱动，编辑结果所见即所得。
     */
    private fun bakeNoiseTrackRect(a: NoiseArea, rc: FloatArray, t: Double): FloatArray {
        val l = rc[0]; val b = rc[1]; val r = rc[2]; val top = rc[3]
        val cx = (l + r) / 2f
        val cy = (b + top) / 2f
        val scx = a.trackValue("scaleXEvents", t, 1f)
        val scy = a.trackValue("scaleYEvents", t, 1f)
        val mx = a.trackValue("moveXEvents", t, cx) - cx
        val my = a.trackValue("moveYEvents", t, cy) - cy
        return floatArrayOf(
            cx + (l - cx) * scx + mx,
            cy + (b - cy) * scy + my,
            cx + (r - cx) * scx + mx,
            cy + (top - cy) * scy + my
        )
    }

    /** 进入噪域编辑（画布长按噪域 / 窗口里长按噪域行都走这里） */
    fun focusNoiseForEdit(index: Int) {
        if (index !in noiseAreas.indices) return
        if (playMode) onRequestExitPlay?.invoke()      // 打歌中长按噪域：先退出 Autoplay
        if (!editMode) { onRequestPauseForEdit?.invoke(); editMode = true }
        lineEditActive = false
        selectedLines.clear()
        noiseEditActive = true
        noiseEditIndex = index
        noiseCorner = 0
        noiseCornerDragging = false
        // 画布长按进入时，编辑手势的旧状态（框选/手指）不能残留
        fingers.clear()
        boxActive = false
        selectingBox = false
        // 摊平原动画：把当前变换烘进矩形；没有关键帧就补一个"当前时刻"的帧
        val a = noiseAreas[index]
        val now = playMs()
        val rc = bakeNoiseTrackRect(a, noiseRectNow(a), now.toDouble())
        val keys = ArrayList(a.rectKeys)
        if (keys.isEmpty()) {
            keys.add(NoiseArea.RectKey(now, rc[0], rc[1], rc[2], rc[3]))
        }
        val edited = a.edited(rectKeys = keys, tracks = emptyMap())
        edited.raw = NoiseArea.toJsonObject(edited)
        // 摊平原动画会丢掉事件轨道 / 补第一帧，是真正的数据改动 → 记一个撤销步，
        // 免得「只是长按看了一眼」就把导入的动画永久烘平。没有轨道且已有关键帧时
        // 数据不变，不产生空的撤销记录。
        if (a.tracks.isNotEmpty() || a.rectKeys.isEmpty()) {
            val own = tx == null
            if (own) beginTx()
            noiseMutate { noiseAreas[index] = edited }
            if (own) endTx()
        }
        invalidateNoiseRows()
        // 进入编辑**不放大屏幕**（用户点名）；要看清某个角，长按底部"左上角/右上角…"按钮再放大
        noiseViewZoom = 1f
        noiseViewPanX = 0f
        noiseViewPanY = 0f
        noiseSelectedKfMs = -1L
        onNoiseEditChanged?.invoke(true)
        notifyNoiseInfo()
        DebugLog.add("noise", "edit start idx=$index keys=${keys.size}")
        invalidate()
    }

    /** 退出噪域编辑（不删除数据） */
    fun stopNoiseEdit() {
        if (!noiseEditActive) return
        noiseEditActive = false
        noiseEditIndex = -1
        noiseCornerDragging = false
        noiseCreatePending = false
        fingers.clear()
        boxActive = false
        selectingBox = false
        noiseViewZoom = 1f
        noiseViewPanX = 0f
        noiseViewPanY = 0f
        // 时间条拖动留下的预览偏移，退出前写回播放器（否则画面停在预览时间、播放器没动）
        if (editSeekOffset != 0L) flushEditSeek()
        onNoiseEditChanged?.invoke(false)
        invalidate()
    }

    /** 选角（Activity 底部 4 个按钮的**短按**）：只换当前角，不动视角 */
    fun selectNoiseCorner(corner: Int) {
        if (!noiseEditActive) return
        noiseCorner = corner.coerceIn(0, 3)
        notifyNoiseInfo()
        invalidate()
    }

    /** 选角并放大居中（底部 4 个按钮的**长按**）；已经放大在同一角 → 再长按缩小还原 */
    fun zoomToNoiseCorner(corner: Int) {
        if (!noiseEditActive) return
        if (noiseViewZoom > 1.01f && noiseCorner == corner) {
            noiseCorner = corner.coerceIn(0, 3)
            noiseViewZoom = 1f
            noiseViewPanX = 0f
            noiseViewPanY = 0f
            notifyNoiseInfo()
            invalidate()
            return
        }
        noiseCorner = corner.coerceIn(0, 3)
        centerNoiseView()
        notifyNoiseInfo()
        invalidate()
    }

    /** 右上角 X/Y 输入框：改当前角（同时写入当前时刻的矩形关键帧） */
    fun setNoiseCornerX(v: Float) = applyNoiseCorner(v, null)
    fun setNoiseCornerY(v: Float) = applyNoiseCorner(null, v)

    /** 右上角输入框用：当前角的 X / Y（世界坐标） */
    fun currentNoiseCornerXY(): FloatArray {
        val a = noiseAreaNow() ?: return floatArrayOf(0f, 0f)
        val rc = noiseRectNow(a)
        val x = if (noiseCorner == 1 || noiseCorner == 3) rc[2] else rc[0]
        val y = if (noiseCorner == 2 || noiseCorner == 3) rc[1] else rc[3]
        return floatArrayOf(x, y)
    }

    private fun applyNoiseCorner(nx: Float?, ny: Float?) {
        val a = noiseAreaNow() ?: return
        val rc = noiseRectNow(a).copyOf()
        if (nx != null && nx.isFinite()) {
            if (noiseCorner == 1 || noiseCorner == 3) rc[2] = nx else rc[0] = nx
        }
        if (ny != null && ny.isFinite()) {
            if (noiseCorner == 2 || noiseCorner == 3) rc[1] = ny else rc[3] = ny
        }
        // 输入框直接调用时没有触摸事务，自己开一个撤销步；拖角时已在触摸事务里
        val own = tx == null
        if (own) beginTx()
        writeNoiseRect(rc[0], rc[1], rc[2], rc[3])
        if (own) endTx()
    }

    /** 写入当前时刻的矩形关键帧（同一毫秒已有帧就替换；其余帧保留，中间线性插值） */
    private fun writeNoiseRect(l: Float, b: Float, r: Float, t: Float) {
        val a = noiseAreaNow() ?: return
        var nl = l; var nr = r; var nb = b; var nt = t
        if (nr < nl) { val tmp = nl; nl = nr; nr = tmp }
        if (nt < nb) { val tmp = nb; nb = nt; nt = tmp }
        if (nr - nl < 1f) nr = nl + 1f
        if (nt - nb < 1f) nt = nb + 1f
        val tMs = playMs()
        val keys = ArrayList(a.rectKeys)
        val i = keys.indexOfFirst { it.timeMs == tMs }
        val key = NoiseArea.RectKey(tMs, nl, nb, nr, nt)
        if (i >= 0) keys[i] = key else keys.add(key)
        keys.sortBy { it.timeMs }
        val edited = a.edited(rectKeys = keys)
        edited.raw = NoiseArea.toJsonObject(edited)
        noiseMutate { noiseAreas[noiseEditIndex] = edited }
        invalidateNoiseRows()
        notifyNoiseInfo()
        invalidate()
    }

    /** 右上角「关键帧」按钮：在当前播放时间打一帧（坐标不变） */
    fun addNoiseKeyframe() {
        val a = noiseAreaNow() ?: return
        val rc = noiseRectNow(a)
        val own = tx == null
        if (own) beginTx()
        writeNoiseRect(rc[0], rc[1], rc[2], rc[3])
        if (own) endTx()
        DebugLog.add("noise", "keyframe idx=$noiseEditIndex t=${playMs()} l=${rc[0]} b=${rc[1]} r=${rc[2]} t=${rc[3]}")
    }

    /** 「显示」按钮：整块噪域显示/隐藏（同步到整个噪域，不是单个角） */
    fun toggleNoiseVisible() {
        val a = noiseAreaNow() ?: return
        val e = a.edited(visible = !a.visible)
        e.raw = NoiseArea.toJsonObject(e)
        val own = tx == null
        if (own) beginTx()
        noiseMutate { noiseAreas[noiseEditIndex] = e }
        if (own) endTx()
        invalidateNoiseRows()
        notifyNoiseInfo()
        invalidate()
    }

    /** 「深浅」按钮：警示区（只闪不掐手） ↔ 真噪域（激活时掐真实手指） */
    fun toggleNoiseWarning() {
        val a = noiseAreaNow() ?: return
        val e = a.edited(warning = !a.warning)
        e.raw = NoiseArea.toJsonObject(e)
        val own = tx == null
        if (own) beginTx()
        noiseMutate { noiseAreas[noiseEditIndex] = e }
        if (own) endTx()
        noiseRuntime = NoiseRuntime(noiseAreas)
        notifyNoiseInfo()
        invalidate()
    }

    /** 长按「＋新建噪域」后进入"画四边形"状态；下一次按下开始拖出区域 */
    fun beginNoiseCreate() {
        if (playMode) onRequestExitPlay?.invoke()
        if (!editMode) { onRequestPauseForEdit?.invoke(); editMode = true }
        if (noiseEditActive) stopNoiseEdit()
        noiseCreatePending = true
        DebugLog.add("noise", "create armed at=${playMs()}ms")
        invalidate()
    }

    private fun createNoiseFromScreen(x0: Float, y0: Float, x1: Float, y1: Float) {
        val wx0 = nScreenX(x0); val wy0 = nScreenY(y0)
        val wx1 = nScreenX(x1); val wy1 = nScreenY(y1)
        val l = minOf(wx0, wx1); val r = maxOf(wx0, wx1)
        val b = minOf(wy0, wy1); val t = maxOf(wy0, wy1)
        val now = playMs()
        val a = NoiseArea(
            name = "",
            left = l, bottom = b, right = r, top = t,
            appearMs = now.toDouble(),
            disappearMs = Double.MAX_VALUE,
            intervals = listOf(NoiseArea.Interval(now.toDouble(), Double.MAX_VALUE, 0.5)),
            rectKeys = listOf(NoiseArea.RectKey(now, l, b, r, t)),
            dirty = true
        )
        a.raw = NoiseArea.toJsonObject(a)
        noiseMutate { noiseAreas.add(a) }
        invalidateNoiseRows()
        DebugLog.add("noise", "create idx=${noiseAreas.size - 1} l=$l b=$b r=$r t=$t")
        focusNoiseForEdit(noiseAreas.size - 1)
    }

    /** 噪域编辑触摸：拖角 / 画新噪域（坐标都先逆变换回基准屏幕坐标） */
    private fun onNoiseEditTouch(e: android.view.MotionEvent): Boolean {
        // 左侧时间条（未缩放，用原始坐标）：点关键帧 = 跳过去；再点 = 删
        if (noiseBarActive || (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && e.x < dp(44f))) {
            noiseBarActive = true
            handleNoiseTimeBarTouch(e, playMs(), height.toFloat())
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP ||
                e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) noiseBarActive = false
            return true
        }
        val sx = noiseViewInvX(e.x)
        val sy = noiseViewInvY(e.y)
        if (noiseCreatePending) {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    noiseCreateStartX = sx; noiseCreateStartY = sy
                    noiseCreateCurX = sx; noiseCreateCurY = sy
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    noiseCreateCurX = sx; noiseCreateCurY = sy
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    noiseCreatePending = false
                    if (e.actionMasked == android.view.MotionEvent.ACTION_UP &&
                        Math.abs(noiseCreateCurX - noiseCreateStartX) > dp(18f) &&
                        Math.abs(noiseCreateCurY - noiseCreateStartY) > dp(18f)
                    ) {
                        createNoiseFromScreen(noiseCreateStartX, noiseCreateStartY, noiseCreateCurX, noiseCreateCurY)
                    }
                }
            }
            invalidate()
            return true
        }
        val a = noiseAreaNow() ?: run { stopNoiseEdit(); return true }
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val rc = noiseRectNow(a)
                var best = -1
                var bestD = Float.MAX_VALUE
                for (c in 0 until 4) {
                    val p = noiseCornerScreen(rc, c)
                    val d = Math.hypot((sx - p[0]).toDouble(), (sy - p[1]).toDouble()).toFloat()
                    if (d < bestD) { bestD = d; best = c }
                }
                if (bestD <= dp(36f)) {
                    noiseCorner = best
                    noiseCornerDragging = true
                    // 直接拖角时不重新居中（否则角会先跳到屏幕中心、再跟着手指跳一下）；
                    // 只有点底部"左上角/右上角…"按钮才做"视角切换"
                    notifyNoiseInfo()
                } else {
                    // 离四个角都远：点在噪域外 = 退出编辑（和判定线编辑"点背景退出"一致）；
                    // 点在噪域内部就忽略（避免误触）
                    val l = nWorldX(rc[0]); val r = nWorldX(rc[2])
                    val top = nWorldY(rc[3]); val bot = nWorldY(rc[1])
                    if (sx < l - dp(20f) || sx > r + dp(20f) || sy < top - dp(20f) || sy > bot + dp(20f)) {
                        stopNoiseEdit()
                    }
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (noiseCornerDragging) {
                    applyNoiseCorner(nScreenX(sx), nScreenY(sy))
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                noiseCornerDragging = false
                notifyNoiseInfo()
            }
        }
        invalidate()
        return true
    }

    /** 画布长按噪域进入编辑：按下时记住命中块，移动/抬手取消 */
    private var noiseLongPressRun: Runnable? = null
    private var noiseDownIndex = -1
    private var noiseDownX = 0f
    private var noiseDownY = 0f

    private fun trackNoiseLongPress(e: android.view.MotionEvent) {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                cancelNoiseLongPress()
                val idx = noiseHitIndex(e.x, e.y)
                if (idx < 0) return
                noiseDownIndex = idx
                noiseDownX = e.x
                noiseDownY = e.y
                val r = Runnable {
                    val id = noiseDownIndex
                    noiseLongPressRun = null
                    if (id >= 0) focusNoiseForEdit(id)
                }
                noiseLongPressRun = r
                postDelayed(r, 500)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (noiseLongPressRun != null &&
                    Math.hypot((e.x - noiseDownX).toDouble(), (e.y - noiseDownY).toDouble()) > dp(8f).toDouble()
                ) cancelNoiseLongPress()
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> cancelNoiseLongPress()
        }
    }

    private fun cancelNoiseLongPress() {
        noiseLongPressRun?.let { removeCallbacks(it) }
        noiseLongPressRun = null
        noiseDownIndex = -1
    }

    /**
     * 屏幕点命中的噪域下标（只查此刻活着的；没命中 -1）。
     *
     * 先用 [noiseIndex] 把 14k 块粗筛到「此刻活着」的几十块，再判包围盒/四边形；
     * 以前这里直接遍历全部区域，官谱长按一下要扫一万四千块。
     */
    private fun noiseHitIndex(px: Float, py: Float): Int {
        val now = playMs().toDouble()
        val cw = chartWidthPx()
        val ch = chartHeightPx()
        val w = width.toFloat()
        val h = height.toFloat()
        noiseIndex.build(noiseAreas)
        noiseIndex.query(now, noiseHitCand, noiseHitCandIdx)
        for (k in 0 until noiseHitCand.size) {
            val a = noiseHitCand[k]
            val rc = a.rectAt(now)
            val l = nWorldX(rc[0]); val r = nWorldX(rc[2])
            val top = nWorldY(rc[3]); val bot = nWorldY(rc[1])
            if (px < l || px > r || py < top || py > bot) continue
            // 精确：用运行时的四边形判定（有旋转/缩放也准）
            val s = noiseProbeRuntime.sample(a, now, cw, ch, w, h) ?: continue
            if (s.contains(px, py)) return noiseHitCandIdx[k]
        }
        return -1
    }

    // ---------------------------------------------------------------- 右侧判定线抽屉
    /** 灰条本体：屏幕右侧、纵向居中（长度 264dp，圆角 2.5dp） */
    private fun edgeBarRect(): RectF {
        val bw = dp(10f)
        val bh = dp(264f)
        val right = width.toFloat() - dp(2f)
        val top = (height - bh) / 2f
        return RectF(right - bw, top, right, top + bh)
    }

    /** 灰条的判定范围：跟"触摸兼容"绑定 —— 框架/全部兼容时明显加大，方便按住 */
    private fun edgeBarHitRect(): RectF {
        val r = edgeBarRect()
        val growH = if (compatMode >= 1) dp(22f) else dp(4f)
        val growV = if (compatMode >= 1) dp(30f) else dp(6f)
        return RectF(r.left - growH, r.top - growV, width.toFloat(), r.bottom + growV)
    }

    private fun panelRect(): RectF {
        val pw = minOf(dp(214f), width * 0.66f)
        val right = width.toFloat() - dp(6f)
        var top = dp(112f)
        var bottom = height.toFloat() - dp(124f)
        if (bottom - top < dp(240f)) {          // 小屏兜底
            top = dp(70f)
            bottom = height.toFloat() - dp(80f)
        }
        return RectF(right - pw, top, right, bottom)
    }

    private fun panelHeaderH(): Float = dp(40f)
    private fun panelFooterH(): Float = dp(48f)
    private fun panelRowH(): Float = dp(48f)
    /** 图层收纳夹行高：比普通行大 5%（用户点名：收纳夹大小增加 5%） */
    private fun panelFolderH(): Float = panelRowH() * 1.05f

    private fun panelListTop(r: RectF): Float = r.top + panelHeaderH()
    private fun panelListBottom(r: RectF): Float = r.bottom - panelFooterH()

    // ---- 图层分组的条目模型 ----
    // 窗口的上下位置 = 判定线图层（zOrder）：zOrder 大的在最上面；
    // 同一图层的线收纳成一行"图层X"夹（双层包边、高 5%、点击展开），
    // 只有一条图层线（没分层）时不收纳，按老样子平铺。
    /**
     * 窗口里的一行。
     * - [lineIndex] >= 0：普通判定线/图片线/文字线；
     * - [isFolder]：图层收纳夹（[layer] = 图层值）；
     * - [noiseIndex] >= 0：噪域页签的噪域行（[lineIndex] = -1）。
     */
    private class PanelEntry(
        val layer: Int,
        val lineIndex: Int,
        val isFolder: Boolean,
        val noiseIndex: Int = -1,
        /** 噪域图层夹的图层 id（空字符串也可以是合法图层） */
        val noiseLayer: String = "",
        /** 这一行是噪域图层夹（而不是判定线收纳夹） */
        val noiseFolder: Boolean = false
    )

    /** 收纳夹的展开状态（layer 值 → 展开） */
    private val panelExpandedLayers = HashSet<Int>()

    private fun panelGrouped(): Boolean = tabLayers().size > 1

    /** 右侧窗口的页签：0=判定线 1=图片线 2=文字线 3=噪域（用户点名，从左到右） */
    var panelTab: Int = 0
    private val tabNames = arrayOf("判定线", "图片线", "文字线", "噪域")

    /** 当前页签对应的线类型（噪域页签没有线） */
    private fun tabKind(): LineKind = when (panelTab) {
        1 -> LineKind.Image
        2 -> LineKind.Text
        else -> LineKind.Normal
    }

    /** 当前页签要显示的线（噪域页签 = 空） */
    private fun tabLines(): List<Line> {
        if (panelTab == 3) return emptyList()
        val k = tabKind()
        return lines.filter { it.kind == k }
    }

    /** 当前页签里存在的图层（降序）；空图层声明在判定线/图片线/文字线三个页签都显示（图层是全局的） */
    private fun tabLayers(): List<Int> {
        if (panelTab == 3) return emptyList()
        return (tabLines().map { it.zOrder } + declaredLayers).distinct().sortedDescending()
    }

    /** 正在竖拖的文件夹封面所属图层（null = 没在拖文件夹）；拖动期间窗口只显示图层夹 */
    var rowMoveLayer: Int? = null

    /**
     * **空图层**（没有任何判定线的图层）。图层在数据上只由线的 zOrder 隐式存在，
     * "增加一个图层"（左滑新建按钮）就需要显式声明一层：存 zOrder 值，
     * 进草稿（LL 行）、进撤销快照。层里一旦有线条，这层自然存在（声明继续保留无害）。
     */
    val declaredLayers = LinkedHashSet<Int>()

    /** 窗口/数据里实际存在的全部图层（线上的 + 声明的空层），降序 */
    fun allLayers(): List<Int> =
        (lines.map { it.zOrder } + declaredLayers).distinct().sortedDescending()

    /**
     * 增加一个图层（左滑「＋新建判定线」按钮，用户点名）：层级 = 当前最高层 + 1。
     * 空层可以在窗口里看到、可以被拖线/输入框选为归属层，也能被右滑删掉。一个撤销步。
     */
    fun addLayer(): Int {
        val newLayer = (allLayers().maxOrNull() ?: -1) + 1
        val own = tx == null
        if (own) beginTx()
        linesMutate { declaredLayers.add(newLayer) }
        if (own) endTx()
        DebugLog.add("line", "layer add $newLayer (空层)")
        onLinesChanged?.invoke()
        invalidate()
        return newLayer
    }

    /** 这个图层是否被整体框住（层内所有判定线都在红框里） */
    private fun isLayerFramed(layer: Int): Boolean {
        var any = false
        for (l in layerMembersOfTab(layer)) {
            if (l.id !in selectedLines) return false
            any = true
        }
        return any
    }

    /** 噪域列表行缓存（噪域数量/顺序/编辑状态变化时清空） */
    private var noiseRowCache: List<PanelEntry>? = null
    private fun invalidateNoiseRows() { noiseRowCache = null }
    /** 噪域图层：展开的层、当前框住的层、层的显示顺序（没有非空 layerId 的官谱不用这套） */
    private val noiseExpandedLayers = HashSet<String>()
    private val noiseSelectedLayers = HashSet<String>()
    private val noiseLayerOrder = ArrayList<String>()
    /** 左滑「＋」加出来的空噪域图层（层里没有块时也要在窗口里显示） */
    private val declaredNoiseLayers = LinkedHashSet<String>()

    /** 有非空 layerId / 手工声明过图层才启用"图层夹"（官谱 blockAreaList 没有图层 ⇒ 保持平铺） */
    private fun noiseHasLayers(): Boolean =
        declaredNoiseLayers.isNotEmpty() || noiseAreas.any { it.layerId.isNotEmpty() }

    private fun ensureNoiseLayerOrder() {
        val keys = LinkedHashSet<String>()
        for (a in noiseAreas) keys.add(a.layerId)
        for (k in declaredNoiseLayers) keys.add(k)
        noiseLayerOrder.removeAll { it !in keys }
        for (k in keys) if (k !in noiseLayerOrder) noiseLayerOrder.add(k)
    }

    /** 左滑页脚「＋新建噪域」= 增加一个空噪域图层（和判定线的 addLayer 同款交互） */
    fun addNoiseLayer(): String {
        ensureNoiseLayerOrder()
        val max = noiseLayerOrder.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: -1
        val key = (max + 1).toString()
        val own = tx == null
        if (own) beginTx()
        noiseMutate {
            declaredNoiseLayers.add(key)
            noiseLayerOrder.add(key)
        }
        if (own) endTx()
        invalidateNoiseRows()
        DebugLog.add("noise", "layer add '$key'")
        invalidate()
        return key
    }

    private fun noiseLayerMembers(key: String): List<Int> =
        noiseAreas.indices.filter { noiseAreas[it].layerId == key }

    private fun noiseLayerFramed(key: String): Boolean {
        val members = noiseLayerMembers(key)
        return members.isNotEmpty() && members.all { noiseAreas[it].layerId in noiseSelectedLayers }
    }

    /** 复制一块噪域（右侧窗口左滑 60%） */
    private fun copyNoiseArea(index: Int): Int? {
        val a = noiseAreas.getOrNull(index) ?: return null
        val c = NoiseArea(
            name = a.name, left = a.left, bottom = a.bottom, right = a.right, top = a.top,
            appearMs = a.appearMs, disappearMs = a.disappearMs, invert = a.invert,
            intervals = a.intervals, tracks = a.tracks, layerId = a.layerId,
            rectKeys = a.rectKeys, visible = a.visible, warning = a.warning, dirty = true
        )
        c.raw = NoiseArea.toJsonObject(c)
        val at = index + 1
        noiseMutate { noiseAreas.add(at.coerceIn(0, noiseAreas.size), c) }
        if (noiseEditIndex >= at) noiseEditIndex++
        invalidateNoiseRows()
        noiseRuntime = NoiseRuntime(noiseAreas)
        DebugLog.add("noise", "copy $index -> $at")
        invalidate()
        return at
    }

    /** 删除一块噪域（右侧窗口右滑 60%） */
    private fun removeNoiseArea(index: Int) {
        if (index !in noiseAreas.indices) return
        noiseMutate { noiseAreas.removeAt(index) }
        if (noiseEditIndex == index) stopNoiseEdit()
        if (noiseEditIndex > index) noiseEditIndex--
        invalidateNoiseRows()
        noiseRuntime = NoiseRuntime(noiseAreas)
        DebugLog.add("noise", "delete idx=$index")
        invalidate()
    }

    /** 把第 [index] 块噪域挪到图层 [key]（RPE layerId；官谱留空） */
    private fun setNoiseLayer(index: Int, key: String) {
        val a = noiseAreas.getOrNull(index) ?: return
        if (a.layerId == key) return
        val e = NoiseArea(
            name = a.name, left = a.left, bottom = a.bottom, right = a.right, top = a.top,
            appearMs = a.appearMs, disappearMs = a.disappearMs, invert = a.invert,
            intervals = a.intervals, tracks = a.tracks, layerId = key,
            rectKeys = a.rectKeys, visible = a.visible, warning = a.warning, dirty = true
        )
        e.raw = NoiseArea.toJsonObject(e)
        noiseMutate { noiseAreas[index] = e }
        invalidateNoiseRows()
        DebugLog.add("noise", "layer move idx=$index -> '$key'")
    }

    /** 把 [fromKey] 整层并到 [toKey] */
    private fun mergeNoiseLayer(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val own = tx == null
        if (own) beginTx()
        for (i in noiseAreas.indices) if (noiseAreas[i].layerId == fromKey) setNoiseLayer(i, toKey)
        // 合并后源图层空了：和判定线图层一致，把声明/顺序里那一层也清掉
        noiseMutate {
            noiseSelectedLayers.remove(fromKey)
            declaredNoiseLayers.remove(fromKey)
            noiseLayerOrder.remove(fromKey)
        }
        if (own) endTx()
        invalidateNoiseRows()
    }

    /** 拖图层夹 = 调整层的显示顺序（数据顺序同步重排，导出顺序也跟着变） */
    private fun reorderNoiseLayer(fromKey: String, toKey: String) {
        ensureNoiseLayerOrder()
        if (fromKey !in noiseLayerOrder || toKey !in noiseLayerOrder) return
        val selArea = noiseAreas.getOrNull(noiseEditIndex)
        val own = tx == null
        if (own) beginTx()
        noiseMutate {
            noiseLayerOrder.remove(fromKey)
            val at = noiseLayerOrder.indexOf(toKey).let { if (it < 0) noiseLayerOrder.size else it }
            noiseLayerOrder.add(at, fromKey)
            val sorted = noiseAreas.sortedBy { noiseLayerOrder.indexOf(it.layerId) }
            noiseAreas.clear(); noiseAreas.addAll(sorted)
        }
        if (selArea != null) noiseEditIndex = noiseAreas.indexOfFirst { it === selArea }
        if (own) endTx()
        invalidateNoiseRows()
        DebugLog.add("noise", "layer reorder '$fromKey' -> '$toKey'")
    }

    private fun panelEntries(): List<PanelEntry> {
        val out = ArrayList<PanelEntry>(lines.size + 8)
        // 噪域页签：按导入/创建顺序平铺列出（没有图层概念，也不支持拖动排序）。
        // 官谱噪域能有一万四千多块，每帧重建 1.4 万个对象会直接把 GC 打爆 ⇒ 缓存一份。
        if (panelTab == 3) {
            noiseRowCache?.let { return it }
            val rows = ArrayList<PanelEntry>(noiseAreas.size)
            ensureNoiseLayerOrder()
            if (noiseHasLayers()) {
                for (key in noiseLayerOrder) {
                    val members = noiseLayerMembers(key)
                    // 空图层（左滑新建出来的）也要显示，只是成员数 0
                    rows.add(PanelEntry(0, -1, true, -1, key, true))
                    if (key in noiseExpandedLayers) for (i in members) rows.add(PanelEntry(0, -1, false, i))
                }
            } else {
                for (i in noiseAreas.indices) rows.add(PanelEntry(0, -1, false, i))
            }
            noiseRowCache = rows
            return rows
        }
        if (rowMoveLayer != null) {
            // 拖文件夹中：只显示图层夹（其它夹子做躲避动画；成员行松手后回来）
            for (layer in tabLayers()) out.add(PanelEntry(layer, -1, true))
            return out
        }
        if (!panelGrouped()) {
            for (i in lines.indices) {
                if (panelTab != 3 && lines[i].kind == tabKind()) out.add(PanelEntry(lines[i].zOrder, i, false))
            }
            return out
        }
        for (layer in tabLayers()) {
            out.add(PanelEntry(layer, -1, true))
            if (layer in panelExpandedLayers) {
                for (i in lines.indices) {
                    if (lines[i].zOrder == layer && panelTab != 3 && lines[i].kind == tabKind()) {
                        out.add(PanelEntry(layer, i, false))
                    }
                }
            }
        }
        return out
    }

    private fun entryH(e: PanelEntry): Float = if (e.isFolder) panelFolderH() else panelRowH()

    private fun panelContentH(): Float {
        var h = 0f
        for (e in panelEntries()) h += entryH(e)
        return h
    }

    private fun panelMaxScroll(): Float {
        val r = panelRect()
        return maxOf(0f, panelContentH() - (panelListBottom(r) - panelListTop(r)))
    }

    /**
     * 面板列表里的第几个**条目**（-1 = 标题/页脚/面板外）。
     * **必须同时校验 x**：面板只在右侧一条，左边画布上同一个高度不算点行，
     * 否则"点背景关窗口"会变成"点到窗口里的判定线按钮"。
     */
    private fun panelRowAt(x: Float, y: Float): Int {
        val r = panelRect()
        if (x < r.left || x > r.right) return -1
        if (y < panelListTop(r) || y > panelListBottom(r)) return -1
        val rel = y - (panelListTop(r) - panelScroll)
        if (rel < 0f) return -1
        var acc = 0f
        val es = panelEntries()
        for ((i, e) in es.withIndex()) {
            acc += entryH(e)
            if (rel < acc) return i
        }
        return -1
    }

    /** 页脚"＋ 新建判定线"的矩形 */
    private fun panelAddRect(): RectF {
        val r = panelRect()
        return RectF(r.left + dp(8f), r.bottom - panelFooterH() + dp(4f), r.right - dp(8f), r.bottom - dp(4f))
    }

    /** 当前活动线的 zOrder（没有活动线返回 null） */
    fun activeLineZOrder(): Int? = lines.firstOrNull { it.id == activeLineId }?.zOrder

    /** 当前活动线的 id（Activity 的「图层」输入框用） */
    val activeLineIdForEdit: Int get() = activeLineId

    /** 当前页签类型下的层内线（图片页签只管图片线，文字页签只管文字线；判���线页签管普通线） */
    private fun layerMembersOfTab(layer: Int): List<Line> {
        val k = tabKind()
        return lines.filter { it.zOrder == layer && it.kind == k }
    }

    /** 图层内所有线的 id（按 id 序，按页签类型过滤） */
    private fun layerMemberIds(layer: Int): List<Int> = layerMembersOfTab(layer).map { it.id }

    /**
     * 合并图层：把 fromLayer 的所有线并入 toLayer（拖动文件夹封面到另一个图层，用户点名）。
     * 一个撤销步（线快照含 zOrder）。
     */
    fun mergeLayer(fromLayer: Int, toLayer: Int): Int {
        if (fromLayer == toLayer) return 0
        val members = layerMembersOfTab(fromLayer)
        if (members.isEmpty()) return 0
        val own = tx == null
        if (own) beginTx()
        linesMutate {
            for (l in members) l.zOrder = toLayer
            // 合并后源图层空了 → 空层文件夹一并删掉（用户点名）
            declaredLayers.remove(fromLayer)
        }
        if (own) endTx()
        DebugLog.add("line", "layer merge $fromLayer -> $toLayer (${members.size} 条)")
        onLinesChanged?.invoke()
        invalidate()
        return members.size
    }

    /**
     * 删除整个图层（右滑收纳夹封面，用户点名）：层内所有判定线连同音符一起删，
     * 至少保留一条判定线。一个撤销步。
     */
    fun removeLayer(layer: Int): Int {
        val victims = layerMemberIds(layer)
        // 空图层（没有线）：只删声明（也要能撤销）
        if (victims.isEmpty()) {
            if (layer in declaredLayers) {
                val own = tx == null
                if (own) beginTx()
                linesMutate { declaredLayers.remove(layer) }
                if (own) endTx()
                DebugLog.add("line", "layer remove(空层) $layer")
                onLinesChanged?.invoke()
                invalidate()
            }
            return 0
        }
        if (lines.size <= victims.size) return 0
        val own = tx == null
        if (own) beginTx()
        for (vid in victims) {
            if (lines.size <= 1) break
            val victim = lines.firstOrNull { it.id == vid } ?: continue
            linesMutate {
                removeNotesAt(notes.indices.filter { notes[it].lineId == vid })
                lines.remove(victim)
            }
        }
        if (lines.none { it.id == activeLineId }) activeLineId = lines.first().id
        if (own) endTx()
        DebugLog.add("line", "layer remove $layer 剩 ${lines.size} 条")
        onLinesChanged?.invoke()
        invalidate()
        return victims.size
    }

    /**
     * 改变图层位置（拖动**没有被框住**的图层上下移动，用户点名）：
     * 把 dragLayer 从当前图层顺序里抽出、插到 targetLayer 的位置，
     * 然后**按新顺序重排全部 zOrder**（顶层值最大）——Phira 只看 zOrder 的相对顺序，
     * 重排后各层上下关系与窗口一致。一个撤销步。
     */
    fun repositionLayer(dragLayer: Int, targetLayer: Int): Int {
        val order = lines.map { it.zOrder }.distinct().sortedDescending().toMutableList()
        val fromIdx = order.indexOf(dragLayer)
        if (fromIdx < 0 || dragLayer == targetLayer) return 0
        order.removeAt(fromIdx)
        val toIdx = order.indexOf(targetLayer)
        order.add(toIdx.coerceIn(0, order.size), dragLayer)
        val own = tx == null
        if (own) beginTx()
        linesMutate {
            // 顶层（位置 0）拿最大值，往下递减
            val top = order.size
            for ((i, layer) in order.withIndex()) {
                val z = top - i
                for (l in lines) if (l.zOrder == layer) l.zOrder = z
            }
        }
        if (own) endTx()
        DebugLog.add("line", "layer reposition $dragLayer -> pos $toIdx (order=$order)")
        onLinesChanged?.invoke()
        invalidate()
        return order.size
    }

    /**
     * 复制整个图层文件夹（左滑收纳夹封面，用户点名）：层内所有判定线（可选连音符）
     * 复制一份放到**新的图层**（当前最大层 +1，否则叠在原层上互相看不见）。一个撤销步。
     */
    fun copyLayer(layer: Int, withNotes: Boolean): Int {
        val members = layerMembersOfTab(layer)
        if (members.isEmpty()) return 0
        val newLayer = (lines.maxOfOrNull { it.zOrder } ?: -1) + 1
        val own = tx == null
        if (own) beginTx()
        linesMutate {
            for (src in members) {
                val nl = Line(nextLineId++)
                nl.name = LineNames.next(lines, src.kind)
                nl.posPct = src.posPct
                nl.moveX = src.moveX; nl.moveY = src.moveY; nl.rotate = src.rotate
                nl.speed = src.speed; nl.alpha = src.alpha
                nl.keyframes.addAll(src.keyframes)
                nl.texture = src.texture; nl.texturePath = src.texturePath
                nl.lineText = src.lineText
                nl.scaleX = src.scaleX; nl.scaleY = src.scaleY
                nl.zOrder = newLayer
                nl.invalidate()
                lines.add(nl)
                if (withNotes) {
                    for (n in notes.filter { it.lineId == src.id }) {
                        addNote(n.copy(lineId = nl.id), brandGhost = false)
                    }
                }
            }
        }
        if (own) endTx()
        DebugLog.add("line", "layer copy $layer -> $newLayer (${members.size} 条, 音符=$withNotes)")
        onLinesChanged?.invoke()
        invalidate()
        return members.size
    }

    /** 改某条判定线的图层（zOrder，可撤销） */
    fun setLineZOrder(id: Int, value: Int): Boolean {
        val l = lines.firstOrNull { it.id == id } ?: return false
        if (l.zOrder == value) return true
        val own = tx == null
        if (own) beginTx()
        linesMutate { l.zOrder = value }
        if (own) endTx()
        DebugLog.add("line", "layer set id=$id zOrder=$value")
        onLinesChanged?.invoke()
        invalidate()
        return true
    }

    private fun fmt2(v: Float): String {
        val r = Math.round(v * 100f) / 100f
        return if (r == Math.floor(r.toDouble()).toFloat()) r.toInt().toString() else r.toString()
    }

    /** 打开抽屉（fromY = 手指按下的 y，用来接着上下滑滚动列表） */
    private fun openPanel(fromY: Float) {
        // 已经退出判定线编辑时再打开抽屉 → 上次的"框住"红框不该还留着（用户要求）：
        // 正在编辑（lineEditActive）时打开则保留，多选状态不能丢
        if (!lineEditActive) selectedLines.clear()
        panelOpen = true
        panelScroll = 0f
        panelDownRow = -1
        panelDownY = fromY
        panelLastY = fromY
        panelScrolling = true          // 从把手划开的这一下不算"点行"
        barPressed = false
        panelNoteCounts = HashMap<Int, Int>().also { m -> for (n in notes) m[n.lineId] = (m[n.lineId] ?: 0) + 1 }
        onPanelStateChanged?.invoke(true)
        invalidate()
    }

    /**
     * 收起判定线抽屉（点编辑区 / 点顶部底部按钮都会调它）。
     * [keepSelection] = true 时保留"被框住"的红框（长按进编辑要收抽屉，但多选状态不能丢）。
     */
    fun closeLinePanel(keepSelection: Boolean = false) {
        if (!keepSelection) selectedLines.clear()
        if (!panelOpen) return
        panelOpen = false
        onPanelStateChanged?.invoke(false)
        invalidate()
    }

    /** 抽屉当前是否展开 */
    val isLinePanelOpen: Boolean get() = panelOpen

    /** 新建判定线：插到现有判定线之间最大的空隙正中 */
    fun addLineSmart(): Line? {
        val used = lines.map { it.posPct }.sorted()
        var bestGap = -1
        var bestPct = 50
        var prev = 0
        for (p in used + 100) {
            val gap = p - prev
            if (gap > bestGap) { bestGap = gap; bestPct = (prev + p) / 2 }
            prev = p
        }
        val added = addLineAtScreenY(height.toFloat() * bestPct / 100f) ?: return null
        // 抽屉保持打开：刷新计数并滚到新加的那一行
        panelNoteCounts = HashMap<Int, Int>().also { m -> for (n in notes) m[n.lineId] = (m[n.lineId] ?: 0) + 1 }
        panelScroll = panelMaxScroll()
        return added
    }

    // ---------------------------------------------------------------- 图片线 / 文字线
    /**
     * 新建一条"附加"判定线（图片线/文字线共用的脚手架）。
     * 命名规则和 Phiedit / Phira 一致：**Name 只用自动名 lineN**，类型由 texture / lineText 决定，
     * 绝不把"图片/文字"写进 Name（否则导进 RPE 后名字就脏了，还会和用户自己起的名字混在一起）。
     */
    private fun newExtraLine(kind: LineKind): Line {
        val l = Line(nextLineId++)
        // 用户要求：图片/文字线一建出来就在屏幕正中间
        l.posPct = 50
        l.speed = 1f
        l.alpha = 1f
        l.name = perKindName(kind)
        // 图片线/文字线同样放进**当前选中判定线所在的图层**（用户点名，与新建判定线一致）
        l.zOrder = activeLineLayer()
        return l
    }

    /**
     * 每类线**各自独立编号**的自动名（用户点名）：判定线/图片线/文字线的序号互不相干。
     * 实现在 [LineNames]（纯 JVM 可单测）；名字进草稿（LN 行）和导出 Name，所见即所得。
     */
    private fun perKindName(kind: LineKind): String = LineNames.next(lines, kind)

    private fun commitExtraLine(l: Line) {
        val own = tx == null
        if (own) beginTx()
        linesMutate { lines.add(l) }
        if (own) endTx()
        DebugLog.add("line", "add ${l.kind} id=${l.id} pct=${l.posPct} tex=${l.texture}（共 ${lines.size} 条）")
        activeLineId = l.id
        selectedLines.clear()
        selectedKfTime = -1
        panelNoteCounts = HashMap<Int, Int>().also { m -> for (n in notes) m[n.lineId] = (m[n.lineId] ?: 0) + 1 }
        panelScroll = panelMaxScroll()
        onLinesChanged?.invoke()
        onLinePoseChanged?.invoke()
        invalidate()
    }

    /**
     * 新建图片线。[textureName] = 导出进谱面包的文件名，[path] = 本机文件。
     * 缩放按 RPE 语义算：图片以"1 像素 = 1 单位"画在 1350×900 的谱面坐标系里，
     * 所以"宽度铺满谱面"= 1350 ÷ 图宽（和 Phiedit / Phira 的 Texture 渲染一致）。
     */
    fun addImageLine(textureName: String, path: String, imgW: Int, imgH: Int): Line? {
        val l = newExtraLine(LineKind.Image)
        l.texture = textureName
        l.texturePath = path
        val s = if (imgW > 0 && imgH > 0) (1350f / imgW).coerceIn(0.02f, 20f) else 1f
        l.scaleX = s
        l.scaleY = s
        commitExtraLine(l)
        return l
    }

    /** 新建文字线（RPE：Texture=line.png + extended.textEvents） */
    fun addTextLine(text: String): Line? {
        val l = newExtraLine(LineKind.Text)
        l.lineText = text
        l.scaleX = 1f
        l.scaleY = 1f
        commitExtraLine(l)
        return l
    }

    /** 改当前文字线的内容（图片线改不了内容，只能换图） */
    fun setLineText(id: Int, text: String): Boolean {
        val l = lines.firstOrNull { it.id == id } ?: return false
        if (l.kind != LineKind.Text) return false
        val own = tx == null
        if (own) beginTx()
        linesMutate { l.lineText = text; l.invalidate() }
        if (own) endTx()
        onLinesChanged?.invoke()
        invalidate()
        return true
    }

    /** 当前活动的文字线（Activity 用它决定"改文字"按钮显不显示 / 预填什么） */
    fun activeTextLine(): Line? = activeLine.takeIf { it.kind == LineKind.Text }

    /** 活动判定线的种类（Activity 用来给输入面板打类型标记） */
    val activeLineKind: LineKind get() = activeLine.kind

    // ---- 图片贴图缓存（按路径 + 文件长度缓存，换图/删图会重载） ----
    private val lineTexCache = HashMap<String, Bitmap?>()
    private val lineTexLen = HashMap<String, Long>()

    /**
     * 图片线贴图的解析目录（RecordActivity 在载入草稿前设置）。
     * 草稿只存贴图**文件名**（texturePath 是本机绝对路径，跨设备没意义、不进草稿），
     * 所以每次载入草稿后要按这些目录重新找文件：
     * `filesDir/lineimg`（本机新建的图）+ 项目导入目录（.pez 解包出来的图）。
     */
    var lineTextureDirs: List<File> = emptyList()

    /** texturePath 为空或文件已丢的图片线，按解析目录重建路径；找不到保持空（画占位框） */
    fun resolveLineTextures() = LineTextureResolver.resolve(lines, lineTextureDirs)

    private fun lineBitmap(l: Line): Bitmap? {
        val p = l.texturePath
        if (p.isEmpty()) return null
        val len = try { File(p).length() } catch (_: Exception) { 0L }
        if (len <= 0L) return null
        if (lineTexLen[p] == len) return lineTexCache[p]
        val bmp = try { BitmapFactory.decodeFile(p) } catch (_: Exception) { null }
        lineTexCache[p] = bmp
        lineTexLen[p] = len
        return bmp
    }

    /**
     * 手指是否落在图片线/文字线的**实际显示区域**里。
     * 这类线本体只是一条细线，图片/文字可能占很大一块；长按整张图都能进入编辑。
     * 优先活动线，其次按画的顺序从后往前找。
     */
    private fun extraLineAt(x: Float, y: Float, now: Long): Line? {
        if (lines.isEmpty()) return null
        val ordered = ArrayList<Line>(lines.size)
        lines.firstOrNull { it.id == activeLineId }?.let { ordered.add(it) }
        for (i in lines.indices.reversed()) {
            val l = lines[i]
            if (l.id != activeLineId) ordered.add(l)
        }
        val k = width / 1350f
        for (l in ordered) {
            if (l.kind == LineKind.Normal) continue
            val p = FloatArray(3)
            lineFrameOf(l, now, p)
            val dx = x - p[0]
            val dy = y - p[1]
            val rad = Math.toRadians(p[2].toDouble())
            val cos = Math.cos(rad).toFloat()
            val sin = Math.sin(rad).toFloat()
            // canvas.rotate(-rot) 的逆变换
            val lx = dx * cos - dy * sin
            val ly = dx * sin + dy * cos
            val pad = dp(6f)
            when (l.kind) {
                LineKind.Image -> {
                    val bmp = lineBitmap(l)
                    scaleOf(l, now, scaleScratch)
                    val iw = if (bmp != null) bmp.width * scaleScratch[0] * k else dp(320f)
                    val ih = if (bmp != null) bmp.height * scaleScratch[1] * k else dp(180f)
                    if (Math.abs(lx) <= iw / 2f + pad && Math.abs(ly) <= ih / 2f + pad) return l
                }
                LineKind.Text -> {
                    scaleOf(l, now, scaleScratch)
                    beatNumPaint.textSize = (50f * scaleScratch[1] * k).coerceAtLeast(dp(6f))
                    val tw = beatNumPaint.measureText(l.lineText)
                    val fm = beatNumPaint.fontMetrics
                    val th = (fm.descent - fm.ascent).coerceAtLeast(dp(8f))
                    if (Math.abs(lx) <= tw / 2f + dp(8f) && Math.abs(ly) <= th / 2f + dp(8f)) return l
                }
                else -> {}
            }
        }
        return null
    }

    /** 右侧浅灰把手 + 判定线列表抽屉 */
    // ---------------------------------------------------------------- 剪贴板面板
    private fun clipBarRect(): RectF {
        val bw = dp(10f)
        val bh = dp(264f)
        val top = (height - bh) / 2f
        return RectF(dp(2f), top, dp(2f) + bw, top + bh)
    }

    private fun clipBarHitRect(): RectF {
        val r = clipBarRect()
        val growH = if (compatMode >= 1) dp(22f) else dp(4f)
        val growV = if (compatMode >= 1) dp(30f) else dp(6f)
        return RectF(0f, r.top - growV, r.right + growH, r.bottom + growV)
    }

    private fun clipPanelRect(): RectF {
        val pw = minOf(dp(214f), width * 0.66f)
        var top = dp(112f)
        var bottom = height.toFloat() - dp(124f)
        if (bottom - top < dp(240f)) { top = dp(70f); bottom = height.toFloat() - dp(80f) }
        return RectF(dp(6f), top, dp(6f) + pw, bottom)
    }

    private fun clipRowH(): Float = dp(52f)
    private fun clipHeaderH(): Float = dp(40f)
    private fun clipRowCount(): Int = 2 + clipEntries.size      // 0=清空 1=导入 2..=每条
    private fun clipListTop(r: RectF): Float = r.top + clipHeaderH()
    private fun clipContentH(): Float = clipRowCount() * clipRowH()

    private fun clipMaxScroll(): Float {
        val r = clipPanelRect()
        return maxOf(0f, clipContentH() - (r.bottom - dp(6f) - clipListTop(r)))
    }

    private fun clipRowAt(x: Float, y: Float): Int {
        val r = clipPanelRect()
        if (x < r.left || x > r.right) return -1
        val top = clipListTop(r)
        if (y < top || y > r.bottom - dp(6f)) return -1
        val rel = y - (top - clipScroll)
        if (rel < 0f) return -1
        val i = (rel / clipRowH()).toInt()
        return if (i in 0 until clipRowCount()) i else -1
    }

    private fun openClipPanel(fromY: Float) {
        if (panelOpen) closeLinePanel()          // 两边只开一个
        clipOpen = true
        clipScroll = 0f
        clipDownRow = -1
        clipDownY = fromY
        clipLastY = fromY
        clipScrolling = true
        clipBarPressed = false
        invalidate()
    }

    fun closeClipPanel() {
        if (!clipOpen) return
        clipOpen = false
        invalidate()
    }

    private fun drawClipboard(canvas: Canvas) {
        val target = if (clipOpen) 1f else 0f
        clipAnim += (target - clipAnim) * 0.3f
        if (Math.abs(target - clipAnim) < 0.005f) clipAnim = target

        // 左侧灰条（面板打开时淡出）
        val barA = 1f - clipAnim
        if (barA > 0.02f && !lineEditActive && !noiseEditActive && !(playMode && hideBars)) {     // 编辑判定线/噪域、Autoplay 收起遮罩时把左侧长条藏起来
            val br = clipBarRect()
            livePaint.style = Paint.Style.FILL
            livePaint.color = ((0xB4 * barA).toInt() shl 24) or 0xC8CDD6
            canvas.drawRoundRect(br, dp(2.5f), dp(2.5f), livePaint)
        }
        if (clipAnim <= 0.01f) return

        val r = clipPanelRect()
        canvas.save()
        canvas.translate(-(1f - clipAnim) * (r.width() + dp(10f)), 0f)

        livePaint.style = Paint.Style.FILL
        livePaint.color = intColor(0xF014161E)
        canvas.drawRoundRect(r, dp(14f), dp(14f), livePaint)
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(1.5f)
        livePaint.color = intColor(0x66FFFFFF)
        canvas.drawRoundRect(r, dp(14f), dp(14f), livePaint)
        livePaint.style = Paint.Style.FILL

        panelPaint.textAlign = Paint.Align.LEFT
        panelPaint.textSize = dp(13f)
        panelPaint.color = intColor(0xFFFFFFFF)
        canvas.drawText("剪贴板 ${clipEntries.size} 条", r.left + dp(12f), r.top + dp(25f), panelPaint)

        val top = clipListTop(r)
        val bot = r.bottom - dp(6f)
        val rowH = clipRowH()
        canvas.save()
        canvas.clipRect(r.left + dp(2f), top, r.right - dp(2f), bot)
        if (clipShiftAnim.size != clipRowCount()) clipShiftAnim = FloatArray(clipRowCount())
        for (i in 0 until clipRowCount()) {
            // 被跨过的行：目标位移 ±rowH，按帧缓动走过去（原来是一瞬间跳一格）
            var targetShift = 0f
            if (clipMoveActive && clipMoveTarget >= 0 && i >= 2) {
                when {
                    clipMoveRow < clipMoveTarget && i in (clipMoveRow + 1)..clipMoveTarget -> targetShift = -rowH
                    clipMoveRow > clipMoveTarget && i in clipMoveTarget until clipMoveRow -> targetShift = rowH
                }
            }
            val prevS = clipShiftAnim[i]
            val nextS = prevS + (targetShift - prevS) * 0.35f
            clipShiftAnim[i] = if (Math.abs(targetShift - nextS) < 0.5f) targetShift else nextS
            var ry = top - clipScroll + i * rowH + clipShiftAnim[i]
            if (ry > bot || ry + rowH < top) continue
            val swiping = clipSwipeRow == i && clipSwipeDx != 0f
            val moving = clipMoveActive && i == clipMoveRow
            if (moving) ry += clipMoveDy
            val rr = RectF(r.left + dp(8f), ry + dp(3f), r.right - dp(8f), ry + rowH - dp(3f))
            if (swiping) {
                // 和判定线抽屉一致：滑到 60% 才出现整条直角底色
                val del = clipSwipeDx < 0f
                if (Math.abs(clipSwipeDx) >= r.width() * 0.6f) {
                    livePaint.color = if (del) intColor(0x55FF5252) else intColor(0x5500C853)
                    canvas.drawRect(r.left + dp(2f), ry, r.right - dp(2f), ry + rowH, livePaint)
                    panelPaint.textAlign = if (del) Paint.Align.RIGHT else Paint.Align.LEFT
                    panelPaint.textSize = dp(13f)
                    panelPaint.color = intColor(0xFFFFFFFF)
                    val tx = if (del) r.right - dp(16f) else r.left + dp(16f)
                    canvas.drawText(if (del) "松手删除" else "松手导出", tx, ry + rowH / 2f + dp(5f), panelPaint)
                }
            }
            canvas.save()
            if (moving) canvas.scale(1.1f, 1.1f, r.centerX(), ry + rowH / 2f)
            if (swiping) canvas.translate(clipSwipeDx, 0f)
            livePaint.color = if (i < 2) intColor(0x1A00E5FF) else intColor(0x14FFFFFF)
            canvas.drawRoundRect(rr, dp(9f), dp(9f), livePaint)
            panelPaint.textAlign = Paint.Align.LEFT
            panelPaint.textSize = dp(13f)
            panelPaint.color = intColor(0xFFFFFFFF)
            val title = when (i) {
                0 -> "清空剪贴板"
                1 -> "导入 .frclip"
                else -> clipLabelAt(i - 2)
            }
            canvas.drawText(title, rr.left + dp(11f), rr.top + dp(21f), panelPaint)
            if (i >= 2) {
                val e = clipEntries.getOrNull(clipEntries.size - (i - 2) - 1)
                if (e != null) {
                    panelPaint.textSize = dp(10f)
                    panelPaint.color = intColor(0x99FFFFFF)
                    val first = e.notes.minOfOrNull { it.startMs } ?: 0L
                    canvas.drawText(
                        "${e.notes.size} 个音符 · 起点 ${fmt2(first / 1000f)}s · 点击粘贴",
                        rr.left + dp(11f), rr.top + dp(37f), panelPaint
                    )
                }
            }
            canvas.restore()
        }
        canvas.restore()

        // 滚动条
        val maxS = clipMaxScroll()
        if (maxS > 0f) {
            val trackH = bot - top
            val thumbH = trackH * trackH / clipContentH()
            val thumbY = top + (trackH - thumbH) * (clipScroll / maxS)
            livePaint.color = intColor(0x33FFFFFF)
            canvas.drawRoundRect(r.right - dp(5f), thumbY, r.right - dp(3f), thumbY + thumbH, dp(2f), dp(2f), livePaint)
        }
        canvas.restore()
    }

    /** 面板/灰条的事件；返回 true = 吃掉了 */
    private fun handleClipTouch(e: MotionEvent): Boolean {
        // 编辑判定线 / 编辑噪域 / Autoplay 收起遮罩时左侧灰条隐藏，也不能吃掉触摸
        if (lineEditActive || noiseEditActive || (playMode && hideBars)) return false
        if (!clipOpen) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    var busy = false
                    for (i in 0 until fingers.size()) if (fingers.valueAt(i).active) busy = true
                    if (!busy && clipBarHitRect().contains(e.x, e.y)) {
                        clipBarPressed = true
                        clipBarDownY = e.y
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!clipBarPressed) return false
                    if (Math.abs(e.y - clipBarDownY) >= dp(6f)) openClipPanel(clipBarDownY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!clipBarPressed) return false
                    if (e.actionMasked == MotionEvent.ACTION_UP) openClipPanel(clipBarDownY)
                    clipBarPressed = false
                    return true
                }
            }
            return false
        }
        val r = clipPanelRect()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val x = e.getX(idx)
                val y = e.getY(idx)
                clipDownRow = clipRowAt(x, y)
                clipDownY = y
                clipLastY = y
                clipDownX = x
                clipScrolling = false
                clipMoveActive = false
                clipMovePending?.let { removeCallbacks(it); clipMovePending = null }
                if (clipDownRow >= 2) {
                    val pr = Runnable {
                        clipMoveActive = true
                        clipMoveRow = clipDownRow
                        clipMoveStartY = clipLastY
                        clipMoveDy = 0f
                        invalidate()
                    }
                    clipMovePending = pr
                    postDelayed(pr, 500)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val x = e.getX(0)
                val y = e.getY(0)
                val dx = x - clipDownX
                if (clipSwipeRow < 0 && !clipMoveActive && clipDownRow >= 2 &&
                    Math.abs(dx) > dp(16f) && Math.abs(dx) > Math.abs(y - clipDownY) * 1.4f
                ) {
                    clipSwipeRow = clipDownRow
                    clipMovePending?.let { removeCallbacks(it); clipMovePending = null }
                }
                if (clipSwipeRow >= 0) {
                    clipSwipeDx = dx
                } else if (clipMoveActive) {
                    clipMoveDy = y - clipMoveStartY
                    val rowH2 = clipRowH()
                    clipMoveTarget = (clipMoveRow + Math.round(clipMoveDy / rowH2))
                        .coerceIn(2, clipRowCount() - 1)
                } else {
                    if (Math.abs(y - clipDownY) > dp(8f)) {
                        clipScrolling = true
                        clipMovePending?.let { removeCallbacks(it); clipMovePending = null }
                    }
                    if (clipScrolling) clipScroll = (clipScroll - (y - clipLastY)).coerceIn(0f, clipMaxScroll())
                    clipLastY = y
                }
                invalidate()      // 剪贴板面板的滑动/左右滑不在 fingers 里，要自己请求重画
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = e.actionMasked == MotionEvent.ACTION_UP
                clipMovePending?.let { removeCallbacks(it); clipMovePending = null }
                val swipeRow = clipSwipeRow
                val swipeDx = clipSwipeDx
                val need = clipPanelRect().width() * 0.6f
                val moveFrom = clipMoveRow
                val moveTo = clipMoveTarget
                // 先无条件复位：任何分支都不会卡住
                clipMoveActive = false
                clipMoveRow = -1
                clipMoveTarget = -1
                clipMoveDy = 0f
                clipSwipeRow = -1
                clipSwipeDx = 0f
                if (moveFrom >= 2) {
                    if (up && moveTo >= 2 && moveTo != moveFrom) {
                        val a = clipEntries.size - (moveFrom - 2) - 1
                        val b = clipEntries.size - (moveTo - 2) - 1
                        if (a in clipEntries.indices && b in clipEntries.indices) {
                            clipEntries.add(b, clipEntries.removeAt(a))
                        }
                    }
                    clipDownRow = -1
                    invalidate()
                    return true
                }
                if (swipeRow >= 2) {
                    val entryIdx = clipEntries.size - (swipeRow - 2) - 1
                    if (up && swipeDx <= -need && entryIdx in clipEntries.indices) {
                        removeClipEntry(entryIdx)
                    } else if (up && swipeDx >= need && entryIdx in clipEntries.indices) {
                        onClipExport?.invoke(entryIdx)
                    }
                } else if (!clipScrolling && clipDownRow >= 0) {
                    when (clipDownRow) {
                        0 -> clearClipboard()
                        1 -> onClipImport?.invoke()
                        else -> {
                            val entryIdx = clipEntries.size - (clipDownRow - 2) - 1
                            // 粘贴后剪贴板保持打开：可以连着粘好几条（以前粘一次就收起来了）
                            if (up) pasteEntryIndex(entryIdx)
                        }
                    }
                } else if (!clipScrolling && clipDownRow < 0) {
                    if (!r.contains(e.getX(e.actionIndex), e.getY(e.actionIndex))) closeClipPanel()
                }
                clipDownRow = -1
            }
        }
        invalidate()
        return true
    }

    /** 左滑时两个复制圆圈的圆心：x1/y1 = 复制(不带音符)，x2/y2 = 复制(带音符) */
    private fun copyCircleCenters(rowTop: Float, r: RectF): FloatArray {
        val rowH = panelRowH()
        val cy = panelListTop(r) - panelScroll + rowTop + rowH / 2f
        val xNear = r.left - dp(36f)
        val xFar = r.left - dp(84f)
        return floatArrayOf(xNear, cy, xFar, cy)
    }

    private fun copyCircleRadius(): Float = dp(19f)

    /** 左滑删除 / 右滑复制的阈值：行宽的 60%（够不到就取屏幕能到的最大值） */
    private fun rowDeleteThreshold(r: RectF): Float {
        val rowW = r.width() - dp(16f)
        val rowLeft = r.left + dp(8f)
        val reachable = width - rowLeft - dp(16f)
        return minOf(rowW * 0.6f, maxOf(reachable, dp(60f)))
    }

    /** 没滑到阈值：平滑退回原位（自己负责复位状态） */
    private fun springBackRow(from: Float) {
        if (from == 0f) {
            rowDragActive = false
            rowDragRow = -1
            rowDragDx = 0f
            invalidate()
            return
        }
        val anim = android.animation.ValueAnimator.ofFloat(from, 0f)
        anim.duration = 150
        anim.addUpdateListener {
            rowDragDx = it.animatedValue as Float
            invalidate()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                rowDragActive = false
                rowDragRow = -1
                rowDragDx = 0f
                invalidate()
            }
        })
        anim.start()
    }

    /** 复制目标圆圈（手指压上去时放大 20%） */
    private fun drawCopyCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, active: Boolean, label: String) {
        val k = if (active) 1.2f else 1f
        val rr = radius * k
        livePaint.style = Paint.Style.FILL
        livePaint.color = if (active) intColor(0xCC00E5FF) else intColor(0x3300E5FF)
        canvas.drawCircle(cx, cy, rr, livePaint)
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(2f)
        livePaint.color = intColor(0xFF00E5FF)
        canvas.drawCircle(cx, cy, rr, livePaint)
        livePaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, rr * 0.42f, livePaint)
        panelPaint.textAlign = Paint.Align.CENTER
        panelPaint.textSize = dp(10f)
        panelPaint.color = intColor(0xFF00E5FF)
        canvas.drawText(label, cx, cy + rr + dp(12f), panelPaint)
    }

    /** 复制一条判定线（[withNotes]=是否连音符一起复制），插到列表末尾 */
    fun copyLineById(id: Int, withNotes: Boolean): Line? {
        // 有多选就整批复制（复制的线放在列表末尾）
        val ids = if (selectedLines.isNotEmpty()) selectedLines.toList() else listOf(id)
        var last: Line? = null
        for (one in ids) last = copyOneLine(one, withNotes) ?: last
        return last
    }

    private fun copyOneLine(id: Int, withNotes: Boolean): Line? {
        val src = lines.firstOrNull { it.id == id } ?: return null
        var created: Line? = null
        beginTx()
        linesMutate {
            val nl = Line(nextLineId++)
            nl.name = perKindName(src.kind)
            nl.posPct = src.posPct
            nl.moveX = src.moveX
            nl.moveY = src.moveY
            nl.rotate = src.rotate
            nl.speed = src.speed
            nl.keyframes.addAll(src.keyframes)
            // 图片线/文字线一起复制（贴图文件共用，不复制文件本身）
            nl.texture = src.texture
            nl.texturePath = src.texturePath
            nl.lineText = src.lineText
            nl.scaleX = src.scaleX
            nl.scaleY = src.scaleY
            nl.zOrder = src.zOrder
            nl.invalidate()
            lines.add(nl)
            created = nl
            if (withNotes) {
                for (n in notes.filter { it.lineId == id }) {
                    addNote(n.copy(lineId = nl.id), brandGhost = false)
                }
            }
        }
        endTx()
        DebugLog.add("line", "copy id=$id withNotes=$withNotes -> ${created?.id}")
        onLinesChanged?.invoke()
        invalidate()
        return created
    }

    /** 删掉指定的判定线（连带它上面的音符），可撤销 */
    fun removeLineById(id: Int): Boolean {
        val ids = if (selectedLines.isNotEmpty()) selectedLines.toList() else listOf(id)
        var any = false
        for (one in ids) if (removeOneLine(one)) any = true
        selectedLines.clear()
        return any
    }

    private fun removeOneLine(id: Int): Boolean {
        if (lines.size <= 1) return false
        val victim = lines.firstOrNull { it.id == id } ?: return false
        val own = tx == null
        if (own) beginTx()
        linesMutate {
            removeNotesAt(notes.indices.filter { notes[it].lineId == id })
            lines.remove(victim)
            if (activeLineId == id) activeLineId = lines.first().id
        }
        if (own) endTx()
        DebugLog.add("line", "remove id=$id 剩余 ${lines.size} 条")
        onLinesChanged?.invoke()
        invalidate()
        return true
    }

    /**
     * 抽屉里显示的判定线名字。
     * **不能用"第几个"**：拖动排序后序号会跟着位置走，看起来就像改名了。
     * 新命名规则（判定线 N / 图片线 N / 文字线 N，每类独立编号）直接显示名字本身；
     * 旧的自动名 `line / lineN` 兼容显示成 `判定线 (id+1)`（id 永不随排序变化），
     * 导入的谱面自带名字（如 spin）则原样显示。
     */
    private fun lineLabel(l: Line): String {
        val n = l.name
        val tag = when (l.kind) {
            LineKind.Image -> "图片线"
            LineKind.Text -> "文字线"
            else -> ""
        }
        // 新命名规则的自动名（带类型前缀 + 数字）：就是它本身，直接显示
        val prefix = if (tag.isEmpty()) "判定线 " else "$tag "
        if (n.startsWith(prefix) && n.length > prefix.length && n.drop(prefix.length).all { it.isDigit() }) {
            return n
        }
        val okGenerated = n.isEmpty() || n == "line" || (n.startsWith("line") && n.drop(4).all { it.isDigit() })
        // 旧自动名的线按类型显示；用户自己起的名字原样显示，只在后面补一个类型标记
        val gen = "判定线 ${l.id + 1}"
        return when {
            tag.isEmpty() -> if (okGenerated) gen else n
            okGenerated -> "$tag ${l.id + 1}"
            else -> "$n（$tag）"
        }
    }

    /** 画抽屉里的某一行（[dx] 用于左右滑时整体平移） */
    private fun drawOneRow(canvas: Canvas, r: RectF, e: PanelEntry, top: Float, dx: Float, fromMove: Boolean = false) {
        val l = lines.getOrNull(e.lineIndex) ?: return
        val rowH = panelRowH()
        val isSel = l.id == activeLineId
        canvas.save()
        if (dx != 0f) canvas.translate(dx, 0f)
        // 长按进入拖动状态的那一行放大 10%（用户点名）
        if (fromMove) canvas.scale(1.1f, 1.1f, r.centerX(), top + rowH / 2f)
        val rr = RectF(r.left + dp(8f), top + dp(3f), r.right - dp(8f), top + rowH - dp(3f))
        if (isSel) {
            livePaint.color = intColor(0x3300E5FF)
            canvas.drawRoundRect(rr, dp(9f), dp(9f), livePaint)
            livePaint.style = Paint.Style.STROKE
            livePaint.strokeWidth = dp(2f)
            livePaint.color = intColor(0xFF00E5FF)
            canvas.drawRoundRect(rr, dp(9f), dp(9f), livePaint)
            livePaint.style = Paint.Style.FILL
        } else {
            livePaint.color = intColor(0x14FFFFFF)
            canvas.drawRoundRect(rr, dp(9f), dp(9f), livePaint)
        }
        panelPaint.textAlign = Paint.Align.LEFT
        panelPaint.textSize = dp(13f)
        panelPaint.color = if (isSel) intColor(0xFF00E5FF) else intColor(0xFFFFFFFF)
        canvas.drawText(lineLabel(l), rr.left + dp(11f), rr.top + dp(20f), panelPaint)
        panelPaint.textSize = dp(10f)
        panelPaint.color = intColor(0x99FFFFFF)
        val nc = panelNoteCounts[l.id] ?: 0
        canvas.drawText(
            "${l.posPct}% · 流速×${fmt2(l.speedAt(playMs()))} · 帧${l.keyframes.size} · 音$nc",
            rr.left + dp(11f), rr.top + dp(36f), panelPaint
        )
        // 被框住：行内容（含"当前选中"的青色）画完之后，最后在外面套一圈纯描边红框。
        // 红色只用**框**表示，不给行改颜色 —— 否则"当前选中"的蓝色会把"被框住"的红色盖掉。
        // 外扩 3dp 留出缝隙，红框只是刚好包住这一行（相邻两行都框住时仍留 2dp 间隔）。
        if (isLineFramed(l)) {
            val pad = dp(3f)
            livePaint.style = Paint.Style.STROKE
            livePaint.strokeWidth = dp(2f)
            livePaint.color = intColor(0xFFFF5252)
            canvas.drawRoundRect(
                rr.left - pad, rr.top - pad, rr.right + pad, rr.bottom + pad,
                dp(12f), dp(12f), livePaint
            )
            livePaint.style = Paint.Style.FILL
        }
        canvas.restore()
    }

    /** 噪域页签的一行（[index] = noiseAreas 下标）；[fromMove] = 长按拖动中（整行放大 10%） */
    private fun drawNoiseRow(canvas: Canvas, r: RectF, index: Int, top: Float, dx: Float = 0f, fromMove: Boolean = false) {
        val a = noiseAreas.getOrNull(index) ?: return
        val rowH = panelRowH()
        val isEditing = noiseEditActive && noiseEditIndex == index
        // 编辑中的那行 / 整层被框住的行 → 红框（和判定线编辑一致）
        val framed = isEditing || a.layerId in noiseSelectedLayers
        canvas.save()
        if (dx != 0f) canvas.translate(dx, 0f)
        // 和判定线行完全一致：按住/拖动时**以行中心放大 1.1 倍**（不是外扩矩形）
        if (fromMove) canvas.scale(1.1f, 1.1f, r.centerX(), top + rowH / 2f)
        val rr = RectF(r.left + dp(8f), top + dp(3f), r.right - dp(8f), top + rowH - dp(3f))
        if (noiseEditIndex == index) {
            // "当前选中"的青色（和判定线行的 isSel 一样）
            livePaint.color = intColor(0x3300E5FF)
            canvas.drawRoundRect(rr, dp(9f), dp(9f), livePaint)
            livePaint.style = Paint.Style.STROKE
            livePaint.strokeWidth = dp(2f)
            livePaint.color = intColor(0xFF00E5FF)
            canvas.drawRoundRect(rr, dp(9f), dp(9f), livePaint)
            livePaint.style = Paint.Style.FILL
        } else {
            livePaint.color = intColor(0x14FFFFFF)
            canvas.drawRoundRect(rr, dp(9f), dp(9f), livePaint)
        }
        panelPaint.textAlign = Paint.Align.LEFT
        panelPaint.textSize = dp(13f)
        panelPaint.color = if (noiseEditIndex == index) intColor(0xFF00E5FF) else intColor(0xFFFFFFFF)
        val title = if (a.name.isNotEmpty()) "噪域 ${index + 1} · ${a.name}" else "噪域 ${index + 1}"
        canvas.drawText(title, rr.left + dp(11f), rr.top + dp(20f), panelPaint)
        panelPaint.textSize = dp(10f)
        panelPaint.color = intColor(0x99FFFFFF)
        val flags = buildString {
            append(if (a.visible) "显示" else "隐藏")
            append(if (a.warning) " · 警示区" else " · 真噪域")
            append(" · 关键帧${a.rectKeys.size}")
            append(" · 激活${a.intervals.size}段")
        }
        canvas.drawText(flags, rr.left + dp(11f), rr.top + dp(36f), panelPaint)
        // 被框住：**只在行外圈加一道纯红描边**（不改变行内颜色、外扩 3dp，和判定线一模一样）
        if (framed) {
            val pad = dp(3f)
            livePaint.style = Paint.Style.STROKE
            livePaint.strokeWidth = dp(2f)
            livePaint.color = intColor(0xFFFF5252)
            canvas.drawRoundRect(rr.left - pad, rr.top - pad, rr.right + pad, rr.bottom + pad, dp(12f), dp(12f), livePaint)
            livePaint.style = Paint.Style.FILL
        }
        canvas.restore()
    }

    /** 噪域图层夹行（RPE 有 layerId 时启用；官谱没有图层不会出现） */
    private fun drawNoiseFolderRow(canvas: Canvas, r: RectF, e: PanelEntry, top: Float, fromMove: Boolean = false) {
        val h = panelFolderH()
        val rr = RectF(r.left + dp(6f), top + dp(2f), r.right - dp(6f), top + h - dp(2f))
        if (fromMove) rr.inset(-h * 0.05f, -h * 0.05f)
        val key = e.noiseLayer
        val members = noiseLayerMembers(key)
        val expanded = key in noiseExpandedLayers
        val framed = noiseLayerFramed(key)
        val active = noiseEditActive && noiseEditIndex in members
        val accent = when {
            framed -> 0xFFFF5252
            active -> 0xFF00E5FF
            else -> 0x66FFFFFF
        }
        val accentSoft = if (active) 0x8800E5FF else 0x33FFFFFF
        livePaint.style = Paint.Style.FILL
        livePaint.color = if (active) intColor(0x1F00E5FF) else intColor(0x14FFFFFF)
        canvas.drawRoundRect(rr, dp(10f), dp(10f), livePaint)
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(1.5f)
        livePaint.color = intColor(accent)
        canvas.drawRoundRect(rr, dp(10f), dp(10f), livePaint)
        val inset = dp(2.5f)
        livePaint.strokeWidth = dp(1f)
        livePaint.color = intColor(accentSoft)
        canvas.drawRoundRect(rr.left + inset, rr.top + inset, rr.right - inset, rr.bottom - inset, dp(8f), dp(8f), livePaint)
        livePaint.style = Paint.Style.FILL
        panelPaint.textAlign = Paint.Align.LEFT
        panelPaint.textSize = dp(13f)
        panelPaint.color = intColor(0xFFFFFFFF)
        val name = if (key.isEmpty()) "默认" else "图层 $key"
        canvas.drawText(name, rr.left + dp(12f), rr.centerY() + dp(5f), panelPaint)
        panelPaint.textAlign = Paint.Align.RIGHT
        panelPaint.textSize = dp(11f)
        panelPaint.color = intColor(0xAAFFFFFF)
        canvas.drawText("${members.size} 块", rr.right - dp(30f), rr.centerY() + dp(5f), panelPaint)
        panelPaint.textSize = dp(13f)
        panelPaint.color = intColor(0xFFFFFFFF)
        canvas.drawText(if (expanded) "▾" else "▸", rr.right - dp(10f), rr.centerY() + dp(5f), panelPaint)
        panelPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * 收纳夹行（收起状态）：**双层包边**（内圈 + 外圈两道圆角描边），行高比普通行大 5%，
     * 名字 = "图层X"，右侧显示层数与展开箭头。点击整行 = 展开 / 收起。
     */
    private fun drawFolderRow(canvas: Canvas, r: RectF, e: PanelEntry, top: Float, dx: Float = 0f, fromMove: Boolean = false) {
        val h = panelFolderH()
        val rr = RectF(r.left + dp(6f), top + dp(2f), r.right - dp(6f), top + h - dp(2f))
        if (dx != 0f) rr.offset(dx, 0f)
        if (fromMove) { rr.inset(-h * 0.05f, -h * 0.05f) }   // 拖动中的夹子同样放大 10%
        drawFolderChrome(canvas, r, e, top, h, rr)
    }

    /** 展开状态的收纳夹标题条（高度不缩；成员行跟在后面，外"边"由列表循环统一描） */
    private fun drawFolderHeader(canvas: Canvas, r: RectF, e: PanelEntry, top: Float) {
        val h = panelFolderH()
        drawFolderChrome(canvas, r, e, top, h, RectF(r.left + dp(6f), top + dp(2f), r.right - dp(6f), top + h - dp(2f)))
    }

    private fun drawFolderChrome(canvas: Canvas, r: RectF, e: PanelEntry, top: Float, h: Float, rr: RectF) {
        canvas.save()
        val members = layerMembersOfTab(e.layer).size
        val expanded = e.layer in panelExpandedLayers
        // 框住的图层：**只有最外层那条（大 5% 的）边变红**，里面那圈边和夹子本身仍按蓝色（用户点名）
        val framedLayer = isLayerFramed(e.layer)
        val hasActive = layerMembersOfTab(e.layer).any { it.id == activeLineId }
        // 外圈：被框住 → 红；否则按含活动线的蓝 / 中性
        val cAccent = when {
            framedLayer -> 0xFFFF5252
            hasActive -> 0xFF00E5FF
            else -> 0x66FFFFFF
        }
        // 内圈（"里面的那条边"）：**永远不因框住而变红**，仍然跟随蓝色高亮规则
        val cAccentSoft = if (hasActive) 0x8800E5FF else 0x33FFFFFF
        livePaint.style = Paint.Style.FILL
        livePaint.color = if (hasActive) intColor(0x1F00E5FF) else intColor(0x14FFFFFF)
        canvas.drawRoundRect(rr, dp(10f), dp(10f), livePaint)
        // 双层包边：外圈描边 + 内缩 2.5dp 的第二道描边
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(1.5f)
        livePaint.color = intColor(cAccent)
        canvas.drawRoundRect(rr, dp(10f), dp(10f), livePaint)
        val inset = dp(2.5f)
        livePaint.strokeWidth = dp(1f)
        livePaint.color = intColor(cAccentSoft)
        canvas.drawRoundRect(
            rr.left + inset, rr.top + inset, rr.right - inset, rr.bottom - inset,
            dp(8f), dp(8f), livePaint
        )
        livePaint.style = Paint.Style.FILL
        panelPaint.textAlign = Paint.Align.LEFT
        panelPaint.textSize = dp(13f)
        panelPaint.color = if (hasActive) intColor(0xFF00E5FF) else intColor(0xFFFFFFFF)
        if (expanded) {
            canvas.drawText("图层${e.layer}", rr.left + dp(11f), rr.centerY() + dp(5f), panelPaint)
        } else {
            canvas.drawText("图层${e.layer}", rr.left + dp(11f), rr.top + h / 2f - dp(3f), panelPaint)
            panelPaint.textSize = dp(10f)
            panelPaint.color = intColor(0xAAFFFFFF)
            canvas.drawText("$members 条", rr.left + dp(11f), rr.top + h / 2f + dp(12f), panelPaint)
        }
        // 展开箭头
        panelPaint.textAlign = Paint.Align.RIGHT
        panelPaint.textSize = dp(13f)
        panelPaint.color = if (hasActive) intColor(0xFF00E5FF) else intColor(0xFFFFFFFF)
        canvas.drawText(if (expanded) "▾" else "▸", rr.right - dp(12f), rr.centerY() + dp(5f), panelPaint)
        panelPaint.textAlign = Paint.Align.LEFT
        canvas.restore()
    }

    /**
     * 是否在抽屉里给这条线画红框。
     * 判定线编辑期间，正在编辑的那条线**时刻**框住（即使 selectedLines 里没有它），
     * 这样从画布长按进入编辑后再打开右侧窗口，也能一眼看到编辑的是哪条线。
     */
    private fun isLineFramed(l: Line): Boolean = l.id in selectedLines
    // ★ 以前这里还有 `|| (lineEditActive && l.id == activeLineId)`：正在编辑的活动线**常驻一圈红框**，
    // 于是"取消框选后红框还在"，看起来像没取消（用户报的 bug）。编辑中的线用行上的青色高亮表示就够了。

    /** 右侧浅灰把手 + 判定线列表抽屉 */
    private fun drawLineSwitcher(canvas: Canvas) {
        val target = if (panelOpen) 1f else 0f
        panelAnim += (target - panelAnim) * 0.3f
        if (Math.abs(target - panelAnim) < 0.005f) panelAnim = target

        // 把手（抽屉打开时淡出），圆角 2.5dp、没有花纹
        val barA = 1f - panelAnim
        if (barA > 0.02f && !(playMode && hideBars)) {
            val br = edgeBarRect()
            livePaint.style = Paint.Style.FILL
            livePaint.color = ((0xB4 * barA).toInt() shl 24) or 0xC8CDD6
            canvas.drawRoundRect(br, dp(2.5f), dp(2.5f), livePaint)
        }
        if (panelAnim <= 0.01f) return

        val r = panelRect()
        canvas.save()
        canvas.translate((1f - panelAnim) * (r.width() + dp(10f)), 0f)

        // 面板背景
        livePaint.style = Paint.Style.FILL
        livePaint.color = intColor(0xF014161E)
        canvas.drawRoundRect(r, dp(14f), dp(14f), livePaint)
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(1.5f)
        livePaint.color = intColor(0x66FFFFFF)
        canvas.drawRoundRect(r, dp(14f), dp(14f), livePaint)
        livePaint.style = Paint.Style.FILL

        // 标题 = 当前页签（顶部左右滑可切换：判定线/图片线/文字线/噪域）
        // 和下面的列表一起跟手横移（切页签时标题/圆点也滑出去、新页签的标题再滑进来）
        canvas.save()
        canvas.clipRect(r.left + dp(2f), r.top + dp(2f), r.right - dp(2f), panelListTop(r))
        canvas.translate(panelTabDx, 0f)
        panelPaint.textAlign = Paint.Align.LEFT
        panelPaint.textSize = dp(13f)
        panelPaint.color = intColor(0xFFFFFFFF)
        val tabTitle = when (panelTab) {
            3 -> "噪域 ${noiseAreas.size} 块"
            else -> "${tabNames[panelTab]} ${tabLines().size} 条"
        }
        canvas.drawText(tabTitle, r.left + dp(12f), r.top + dp(25f), panelPaint)
        // 页签圆点（从左到右 4 个，当前页签高亮）
        val dotY = r.top + dp(30f)
        var dotX = r.left + dp(14f)
        for (ti in tabNames.indices) {
            livePaint.style = Paint.Style.FILL
            livePaint.color = if (ti == panelTab) intColor(0xFF00E5FF) else intColor(0x44FFFFFF)
            canvas.drawCircle(dotX, dotY, dp(2.6f), livePaint)
            dotX += dp(11f)
        }
        canvas.restore()

        // 列表（被拖动的那一行不在这里画，等会儿画在裁剪区外面）
        val listTop = panelListTop(r)
        val listBottom = panelListBottom(r)
        // 页签切换动画：旧页签先整体划出 → 切页签 → 新页签从另一侧划入
        val slideSpan = r.width() * 0.9f
        var es = panelEntries()
        if (panelTabPhase == 1) {
            panelTabDx += (panelTabDir * slideSpan - panelTabDx) * 0.3f
            if (Math.abs(panelTabDir * slideSpan - panelTabDx) < dp(4f)) {
                panelTabDx = -panelTabDir * slideSpan       // 新页签从对侧就位
                panelTab = panelTabPending
                panelTabPending = -1
                panelTabPhase = 2
                es = panelEntries()
            }
            postInvalidateOnAnimation()
            invalidate()
        } else if (panelTabPhase == 2) {
            panelTabDx += (0f - panelTabDx) * 0.3f
            if (Math.abs(panelTabDx) < dp(1.5f)) { panelTabDx = 0f; panelTabPhase = 0 }
            postInvalidateOnAnimation()
        }
        canvas.save()
        // 先裁在面板范围内再横移：否则划出时会画到窗口外面（用户报的"超出窗口"）
        canvas.clipRect(r.left + dp(2f), listTop, r.right - dp(2f), listBottom)
        canvas.translate(panelTabDx, 0f)
        if (rowShiftAnim.size != es.size) rowShiftAnim = FloatArray(es.size)
        var acc = 0f
        val groupBorders = ArrayList<Pair<RectF, Int>>()   // 展开的收纳夹：外"边"范围 + 图层值
        if (rowShiftAnim.size != es.size) rowShiftAnim = FloatArray(es.size)
        for ((i, e) in es.withIndex()) {
            // 被跨过的条目：让位躲避动画（拖文件夹 = 其它夹子让位；拖线 = 其它行让位）。
            // **合并模式（拖被框住的图层）/拖被框住的判定线 都不做躲避动画**（用户点名）
            val mergeMode = (rowMoveLayer != null && isLayerFramed(rowMoveLayer!!)) ||
                (rowMoveLayer == null && es.getOrNull(rowMoveFrom)?.lineIndex
                    ?.takeIf { it >= 0 }?.let { lines.getOrNull(it)?.id }?.let { it in selectedLines } == true)
            var targetShift = 0f
            if (!mergeMode && rowMoveActive && rowMoveTarget >= 0 && i != rowMoveFrom) {
                val dH = entryH(es.getOrNull(rowMoveFrom) ?: e)
                when {
                    rowMoveFrom < rowMoveTarget && i in (rowMoveFrom + 1)..rowMoveTarget -> targetShift = -dH
                    rowMoveFrom > rowMoveTarget && i in rowMoveTarget until rowMoveFrom -> targetShift = dH
                }
            }
            val prevS = rowShiftAnim[i]
            val nextS = prevS + (targetShift - prevS) * 0.35f
            rowShiftAnim[i] = if (Math.abs(targetShift - nextS) < 0.5f) targetShift else nextS
            val top = listTop - panelScroll + acc + rowShiftAnim[i]
            acc += entryH(e)
            if (top > listBottom || top + entryH(e) < listTop) continue
            if (rowDragActive && i == rowDragRow) continue        // 左右拖动的那行单独画（跟手）
            if (rowMoveActive && i == rowMoveFrom) continue      // 上下拖动的那行单独画（跟手）
            if (e.noiseFolder) {
                drawNoiseFolderRow(canvas, r, e, top)
                continue
            }
            if (e.noiseIndex >= 0) {
                drawNoiseRow(canvas, r, e.noiseIndex, top, dx = 0f)
                continue
            }
            if (!e.isFolder) {
                drawOneRow(canvas, r, e, top, 0f)
                continue
            }
            if (e.layer !in panelExpandedLayers) {
                drawFolderRow(canvas, r, e, top)                  // 收起：整个夹子一行
            } else {
                drawFolderHeader(canvas, r, e, top)               // 展开：只剩窄标题条
                // 记下"外边"范围：夹条 + 后面连续的本层成员行（成员行已按顺序紧跟在夹条后）
                var h = entryH(e)
                var j = i + 1
                while (j < es.size && !es[j].isFolder && es[j].layer == e.layer) {
                    h += entryH(es[j]); j++
                }
                groupBorders.add(Pair(
                    RectF(r.left + dp(4f), top + dp(1f), r.right - dp(4f), top + h - dp(1f)),
                    e.layer
                ))
            }
        }
        // 展开收纳夹的外"边"最后描（包住夹条 + 所有成员行）；包含选中线的层用蓝色
        for (g in groupBorders) {
            val br = g.first
            val framedLayer = isLayerFramed(g.second)
            val hasActive = layerMembersOfTab(g.second).any { it.id == activeLineId }
            livePaint.style = Paint.Style.STROKE
            livePaint.strokeWidth = dp(1.5f)
            livePaint.color = when {
                framedLayer -> intColor(0xFFFF5252)
                hasActive -> intColor(0xFF00E5FF)
                else -> intColor(0x66FFFFFF)
            }
            canvas.drawRoundRect(br, dp(10f), dp(10f), livePaint)
            val inset = dp(2.5f)
            livePaint.strokeWidth = dp(1f)
            livePaint.color = if (hasActive) intColor(0x8800E5FF) else intColor(0x33FFFFFF)
            canvas.drawRoundRect(
                br.left + inset, br.top + inset, br.right - inset, br.bottom - inset,
                dp(8f), dp(8f), livePaint
            )
            livePaint.style = Paint.Style.FILL
        }
        canvas.restore()

        // 滚动条
        val maxS = panelMaxScroll()
        if (maxS > 0f) {
            val trackTop = listTop
            val trackH = listBottom - listTop
            val thumbH = trackH * trackH / panelContentH()
            val thumbY = trackTop + (trackH - thumbH) * (panelScroll / maxS)
            livePaint.color = intColor(0x33FFFFFF)
            canvas.drawRoundRect(r.right - dp(5f), thumbY, r.right - dp(3f), thumbY + thumbH, dp(2f), dp(2f), livePaint)
        }

        // 页脚：＋ 新建判定线（左滑 = 选图片，右滑 = 新建文字线）
        // 也跟手横移：切页签时按钮随页面一起滑出/滑入（裁在页脚区内，不会画到列表上）
        canvas.save()
        canvas.clipRect(r.left + dp(2f), listBottom, r.right - dp(2f), r.bottom - dp(2f))
        canvas.translate(panelTabDx, 0f)
        val addR0 = panelAddRect()
        val addR = if (footerDragActive) RectF(addR0).also { it.offset(footerDragDx, 0f) } else addR0
        // 滑到按钮宽度的 60% 才算触发（和判定线行右滑删除的阈值规则一致）
        val footerThreshold = panelAddRect().width() * 0.6f
        val footerArmed = footerDragActive && footerDragDx <= -footerThreshold
        livePaint.style = Paint.Style.FILL
        if (footerDragActive) {
            // 左右滑都铺绿色底（离手越近越深）；滑动本身不再改按钮颜色
            livePaint.color = if (footerArmed) intColor(0x5500C853) else intColor(0x3300C853)
            canvas.drawRect(
                r.left + dp(2f), addR0.top - dp(2f), r.right - dp(2f), addR0.bottom + dp(2f),
                livePaint
            )
            if (footerArmed) {
                panelPaint.textAlign = Paint.Align.RIGHT
                panelPaint.textSize = dp(11f)
                panelPaint.color = intColor(0xFFFFFFFF)
                canvas.drawText("松手新建图层", r.right - dp(14f), addR0.centerY() + dp(4f), panelPaint)
            }
        }
        livePaint.color = intColor(0x1A00E5FF)
        canvas.drawRoundRect(addR, dp(9f), dp(9f), livePaint)
        panelPaint.textAlign = Paint.Align.CENTER
        panelPaint.textSize = dp(13f)
        panelPaint.color = intColor(0xFF00E5FF)
        val addLabel = when (panelTab) {
            1 -> "＋ 新建图片"
            2 -> "＋ 新建文字"
            3 -> "＋ 新建噪域"
            else -> "＋ 新建判定线"
        }
        canvas.drawText(addLabel, addR.centerX(), addR.centerY() + dp(5f), panelPaint)
        canvas.restore()

        if (rowMoveActive && rowMoveFrom in es.indices) {
            // 上下拖动：整行跟着手指走（同样裁在列表区域内 —— 拖出窗口时暂时看不见，但不能画到窗口外面）
            canvas.save()
            canvas.clipRect(r.left + dp(2f), listTop, r.right - dp(2f), listBottom)
            val moveTop = listTop - panelScroll + panelEntryTop(es, rowMoveFrom) + rowMoveDy
            val me = es[rowMoveFrom]
            if (me.noiseIndex >= 0) drawNoiseRow(canvas, r, me.noiseIndex, moveTop, dx = 0f, fromMove = true)
            else if (me.noiseFolder) drawNoiseFolderRow(canvas, r, me, moveTop, fromMove = true)
            else if (me.isFolder) drawFolderRow(canvas, r, me, moveTop, fromMove = true)
            else drawOneRow(canvas, r, me, moveTop, 0f, fromMove = true)
            canvas.restore()
        }
        // 左滑：复制圆圈（普通线 = 不带音符/带音符；图层文件夹 = 是否复制层内音符；
        // 图片线/文字线跟音符无关，不画圆圈 —— 用户点名）
        if (rowDragActive && rowDragDx < 0f && rowDragRow in es.indices) {
            val dragE = es[rowDragRow]
            val dragLine = dragE.lineIndex.takeIf { it >= 0 }?.let { lines.getOrNull(it) }
            val showCircles = dragE.isFolder || dragLine?.kind == LineKind.Normal
            if (showCircles) {
                val c = copyCircleCenters(panelEntryTop(es, rowDragRow), r)
                val rr = copyCircleRadius()
                drawCopyCircle(canvas, c[0], c[1], rr, rowDragTarget == 1, "不带音符")
                drawCopyCircle(canvas, c[2], c[3], rr, rowDragTarget == 2, "带音符")
            }
        }
        if (rowDragActive && rowDragRow in es.indices) {
            // 横向拖动的那一行/夹：跟手平移（不裁剪，出屏才看不见）
            val dragTop = listTop - panelScroll + panelEntryTop(es, rowDragRow)
            val de = es[rowDragRow]
            if (de.noiseFolder) drawNoiseFolderRow(canvas, r, de, dragTop)
            else if (de.noiseIndex >= 0) drawNoiseRow(canvas, r, de.noiseIndex, dragTop, dx = rowDragDx)
            else if (de.isFolder) drawFolderRow(canvas, r, de, dragTop, rowDragDx)
            else drawOneRow(canvas, r, de, dragTop, rowDragDx)
            val swipeThreshold = r.width() * 0.6f
            val isNoise = de.noiseIndex >= 0
            val showCircles = de.isFolder || (de.lineIndex >= 0 && lines.getOrNull(de.lineIndex)?.kind == LineKind.Normal)
            // 左滑（没有复制圆圈的条目：噪域/图片线/文字线）→ 绿色"松手复制"提示
            if (!showCircles && rowDragDx < 0f && -rowDragDx >= swipeThreshold) {
                canvas.save()
                canvas.clipRect(r.left + dp(6f), dragTop, r.right - dp(6f), dragTop + panelRowH())
                livePaint.color = intColor(0x4400C853)
                canvas.drawRect(r.left, dragTop, r.right, dragTop + panelRowH(), livePaint)
                panelPaint.textAlign = Paint.Align.LEFT
                panelPaint.textSize = dp(12f)
                panelPaint.color = intColor(0xFF69F0AE)
                canvas.drawText("松手复制", r.left + dp(12f), dragTop + panelRowH() / 2f + dp(4f), panelPaint)
                canvas.restore()
            }
            val delThreshold = if (isNoise) swipeThreshold else rowDeleteThreshold(r)
            if (rowDragDx > 0f && rowDragDx >= delThreshold) {
                canvas.save()
                canvas.clipRect(r.left + dp(6f), dragTop, r.right - dp(6f), dragTop + panelRowH())
                livePaint.color = intColor(0x55FF5252)
                canvas.drawRect(r.left, dragTop, r.right, dragTop + panelRowH(), livePaint)
                panelPaint.textAlign = Paint.Align.LEFT
                panelPaint.textSize = dp(12f)
                panelPaint.color = intColor(0xFFFF5252)
                canvas.drawText(if (de.isFolder) "松手删除图层" else "松手删除", r.left + dp(12f), dragTop + panelRowH() / 2f + dp(4f), panelPaint)
                canvas.restore()
            }
        }
        canvas.restore()
    }

    /**
     * 跟手拖动页签：内容横移量直接等于手指位移（不是等到阈值再"整页跳"）。
     * 到头/到尾没有相邻页签时加阻尼，拖不动太多，松手回弹。
     */
    private fun followTabDrag(dx: Float) {
        val maxSpan = panelRect().width() * 0.9f      // 和划出动画同一个跨度
        val dir = if (dx >= 0f) 1 else -1
        val hasNeighbor = panelTab + (if (dir > 0) -1 else 1) in 0..3
        panelTabDx = if (hasNeighbor) dx.coerceIn(-maxSpan, maxSpan)
        else (dx * 0.18f).coerceIn(-dp(36f), dp(36f))
    }

    /**
     * 松手结算：拖过**窗口宽度的 60%**才换页（继续滑出 → 换内容 → 从另一侧滑入），
     * 没到就弹回当前页。
     */
    private fun settleTabDrag(cancelled: Boolean) {
        panelTabDragging = false
        if (cancelled) {
            panelTabPhase = 2
            panelTabPending = -1
            invalidate()
            return
        }
        val threshold = panelRect().width() * 0.6f
        val dir = if (panelTabDx >= 0f) 1 else -1
        val next = panelTab + (if (dir > 0) -1 else 1)
        if (Math.abs(panelTabDx) >= threshold && next in 0..3) {
            panelTabPhase = 1
            panelTabDir = dir.toFloat()
            panelTabPending = next
            panelScroll = 0f
        } else {
            panelTabPhase = 2            // pending = -1 ⇒ 只弹回，不换页
            panelTabPending = -1
        }
        invalidate()
    }

    /** 第 index 个条目的列表内顶部 y 偏移（不含 listTop / 滚动） */
    private fun panelEntryTop(es: List<PanelEntry>, index: Int): Float {
        var acc = 0f
        for (i in 0 until index.coerceAtMost(es.size)) acc += entryH(es[i])
        return acc
    }

    /** 抽屉/把手的事件处理；返回 true 表示这次事件被吃掉了 */
    private fun handleLineSwitcherTouch(e: MotionEvent): Boolean {
        // Autoplay 收起上下遮罩时，右侧灰条也隐藏并停止接管触摸
        if (playMode && hideBars) return false
        if (!panelOpen) {
            // 收起状态：只在灰条（判定范围）内接管 —— 按住上/下滑 = 打开并滚动列表，点一下也打开
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    var busy = false
                    for (i in 0 until fingers.size()) if (fingers.valueAt(i).active) busy = true
                    if (!busy && edgeBarHitRect().contains(e.x, e.y)) {
                        barPressed = true
                        barDownY = e.y
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!barPressed) return false
                    if (Math.abs(e.y - barDownY) >= dp(6f)) openPanel(barDownY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!barPressed) return false
                    if (e.actionMasked == MotionEvent.ACTION_UP) openPanel(barDownY)
                    barPressed = false
                    return true
                }
            }
            return false
        }
        // 打开状态：所有手指事件都归抽屉
        val r = panelRect()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val x = e.getX(idx)
                val y = e.getY(idx)
                panelDownTime = SystemClock.elapsedRealtime()
                // 页签条：顶部标题行、窗口内没有项目的空白处、以及双指横滑都能切换
                headerSwipeDown = y < panelListTop(panelRect())
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    multiTabArmed = e.pointerCount >= 2
                    multiTabX = e.getX(0)
                }
                panelDownRow = panelRowAt(x, y)
                panelDownY = y
                panelDownX = x
                panelLastY = y
                panelScrolling = false
                rowMoveActive = false
                rowMoveMoved = false
                rowDragActive = false
                rowDragDx = 0f
                rowDragTarget = 0
                rowMovePending?.let { removeCallbacks(it) }
                rowMovePending = null
                rowMoveStartY = y
                rowMoveLastY = y          // 必须在这里记，否则长按触发时用的是上一次的残留值 → 一按就跳
                if (panelDownRow >= 0) {
                    val pr = Runnable {
                        if (panelDownRow >= 0 && !panelScrolling) startRowMove(rowMoveStartY)
                    }
                    rowMovePending = pr
                    postDelayed(pr, 500)
                }
                // 页脚的新建按钮单独记（用 -2 表示）
                if (panelAddRect().contains(x, y)) panelDownRow = -2
            }
            MotionEvent.ACTION_MOVE -> {
                val x = e.getX(0)
                val y = e.getY(0)
                val dx = x - panelDownX
                // 已经在跟手拖页签：内容持续跟着手指（鼠标/手指回拉也会反向跟手）
                if (panelTabDragging) {
                    followTabDrag(x - panelTabDragFromX)
                    invalidate()
                    return true
                }
                // 双指横滑 = 切换页签（任何位置都行）
                if (multiTabArmed && e.pointerCount >= 2 && panelTabPhase == 0) {
                    val mdx = x - multiTabX
                    if (Math.abs(mdx) > dp(8f) && Math.abs(mdx) >= Math.abs(y - panelDownY)) {
                        panelTabDragging = true
                        panelTabDragFromX = multiTabX
                        panelScrolling = false
                        followTabDrag(mdx)
                        invalidate()
                        return true
                    }
                }
                // 顶部标题栏 / 窗口内空白处横滑 = 切换页签（跟手；松手时超过 60% 才换）
                val onEmptyArea = panelDownRow == -1 && !panelAddRect().contains(x, y) && panelRect().contains(x, y)
                if ((headerSwipeDown || onEmptyArea) && !footerDragActive && !rowMoveActive && !rowDragActive &&
                    panelTabPhase == 0 && Math.abs(dx) > dp(8f) &&
                    Math.abs(dx) > Math.abs(y - panelDownY) * 1.2f
                ) {
                    headerSwipeDown = false
                    panelTabDragging = true
                    panelTabDragFromX = panelDownX
                    panelScrolling = false
                    followTabDrag(dx)
                    invalidate()
                    panelDownRow = -1
                    return true
                }
                // 页脚"＋ 新建判定线"：**左滑 = 增加一个图层**（用户点名）；
                // 右滑暂时屏蔽（原"新建文字线"）；左滑也不再是"新建图片线"
                if (panelDownRow == -2 && !rowDragActive && !rowMoveActive &&
                    dx < 0f && Math.abs(dx) > dp(12f) && Math.abs(dx) > Math.abs(y - panelDownY) * 1.3f
                ) {
                    footerDragActive = true
                    panelScrolling = false
                    rowMovePending?.let { removeCallbacks(it); rowMovePending = null }
                }
                if (footerDragActive) {
                    footerDragDx = dx
                    invalidate()
                    return true
                }
                // 横向意图明显 → 左右滑（复制/删除）
                val dragEntry = panelEntries().getOrNull(panelDownRow)
                val rowDragAllowed = dragEntry != null && !dragEntry.noiseFolder &&
                    (panelTab != 3 || dragEntry.noiseIndex >= 0)
                if (!rowDragActive && !rowMoveActive && panelDownRow >= 0 && rowDragAllowed &&
                    Math.abs(dx) > dp(10f) && Math.abs(dx) > Math.abs(y - panelDownY) * 1.4f
                ) {
                    rowDragActive = true
                    rowDragRow = panelDownRow
                    panelScrolling = false
                    rowMovePending?.let { removeCallbacks(it); rowMovePending = null }
                }
                if (rowDragActive) {
                    rowDragDx = dx
                    rowDragTarget = 0
                    if (dx < 0f) {
                        val dragEntry = panelEntries().getOrNull(rowDragRow)
                        val dragLineIdx = dragEntry?.lineIndex ?: -1
                        // 圆圈只给：普通判定线（带/不带音符）和图层文件夹（= 是否复制层内音符）；
                        // 图片线/文字线跟音符无关，没有圆圈（用户点名）
                        val showCircles = dragEntry?.isFolder == true ||
                            (dragLineIdx >= 0 && lines.getOrNull(dragLineIdx)?.kind == LineKind.Normal)
                        if (showCircles) {
                            val c = copyCircleCenters(panelEntryTop(panelEntries(), rowDragRow), r)
                            val reach = copyCircleRadius() * 1.5f
                            if (Math.hypot((x - c[0]).toDouble(), (y - c[1]).toDouble()) < reach) rowDragTarget = 1
                            else if (Math.hypot((x - c[2]).toDouble(), (y - c[3]).toDouble()) < reach) rowDragTarget = 2
                        }
                    }
                } else if (rowMoveActive) {
                    rowMoveLastY = y
                    updateRowMove(y)
                } else {
                    if (Math.abs(y - panelDownY) > dp(8f)) {
                        panelScrolling = true
                        rowMovePending?.let { removeCallbacks(it); rowMovePending = null }
                    }
                    if (panelScrolling) {
                        panelScroll = (panelScroll - (y - panelLastY)).coerceIn(0f, panelMaxScroll())
                    }
                    panelLastY = y
                }
                invalidate()      // 抽屉里的滑动/左右滑不在 fingers 里，要自己请求重画
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = e.actionIndex
                val x = e.getX(idx)
                val y = e.getY(idx)
                rowMovePending?.let { removeCallbacks(it); rowMovePending = null }
                if (panelTabDragging) {
                    settleTabDrag(e.actionMasked == MotionEvent.ACTION_CANCEL)
                    multiTabArmed = false
                    panelDownRow = -1
                    invalidate()
                    return true
                }
                if (footerDragActive) {
                    val d = footerDragDx
                    footerDragActive = false
                    footerDragDx = 0f
                    panelDownRow = -1
                    val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                    if (!cancelled && d <= -panelAddRect().width() * 0.6f) {
                        // 左滑 = 增加一个图层（判定线/图片线/文字线/噪域四个页签都支持）
                        if (panelTab == 3) addNoiseLayer() else addLayer()
                    }
                    invalidate()
                    return true
                }
                if (rowMoveActive) {
                    endRowMove()
                    panelDownRow = -1
                    return true
                }
                if (rowDragActive) {
                    val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                    val dragE = panelEntries().getOrNull(rowDragRow)
                    val dragLine = dragE?.lineIndex?.takeIf { it >= 0 }?.let { lines.getOrNull(it) }
                    val id = dragLine?.id
                    val dx = rowDragDx
                    val target = rowDragTarget
                    val reset = {
                        rowDragActive = false
                        rowDragRow = -1
                        rowDragDx = 0f
                        rowDragTarget = 0
                    }
                    panelDownRow = -1
                    val swipeThreshold = panelRect().width() * 0.6f
                    when {
                        // 噪域行：左滑 60% = 复制，右滑 60% = 删除
                        !cancelled && dragE != null && dragE.noiseIndex >= 0 && dx < 0f && -dx >= swipeThreshold ->
                            { reset(); copyNoiseArea(dragE.noiseIndex) }
                        !cancelled && dragE != null && dragE.noiseIndex >= 0 && dx > 0f && dx >= swipeThreshold ->
                            { reset(); removeNoiseArea(dragE.noiseIndex) }
                        // 图片线/文字线：左滑 60% = 复制（它们没有音符，直接复制本身）
                        !cancelled && dx < 0f && dragLine != null && dragLine.kind != LineKind.Normal &&
                            -dx >= swipeThreshold && id != null -> { reset(); copyLineById(id, false) }
                        // 图层文件夹：左滑 = 复制整个图层（带/不带层内音符），右滑 = 删除整个图层
                        !cancelled && dx < 0f && target == 1 && dragE?.isFolder == true -> { reset(); copyLayer(dragE.layer, false) }
                        !cancelled && dx < 0f && target == 2 && dragE?.isFolder == true -> { reset(); copyLayer(dragE.layer, true) }
                        !cancelled && dx > 0f && dx >= rowDeleteThreshold(r) && dragE?.isFolder == true -> { reset(); removeLayer(dragE.layer) }
                        !cancelled && dx < 0f && target == 1 && id != null -> { reset(); copyLineById(id, false) }
                        !cancelled && dx < 0f && target == 2 && id != null -> { reset(); copyLineById(id, true) }
                        !cancelled && dx > 0f && dx >= rowDeleteThreshold(r) && id != null -> { reset(); removeLineById(id) }
                        else -> springBackRow(dx)     // 没滑到位：弹回（自己复位）
                    }
                    invalidate()
                    return true
                }
                if (!panelScrolling) {
                    val longPress = SystemClock.elapsedRealtime() - panelDownTime >= 500
                    // 噪域页签：长按页脚「＋新建噪域」= 进入"画四边形"状态（收窗口，下次按下拖出区域）
                    if (panelTab == 3 && panelDownRow == -2 && longPress) {
                        closeLinePanel(keepSelection = true)
                        beginNoiseCreate()
                        panelDownRow = -1
                        return true
                    }
                    // 噪域行：长按优先进入编辑（即使当前有判定线红框也照常生效）
                    val lpEntry = panelEntries().getOrNull(panelDownRow)
                    if (lpEntry != null && lpEntry.noiseIndex >= 0 && longPress) {
                        focusNoiseForEdit(lpEntry.noiseIndex)
                        panelDownRow = -1
                        return true
                    }
                    val hasAnyFrame = selectedLines.isNotEmpty() || noiseSelectedLayers.isNotEmpty() || noiseEditActive
                    if (hasAnyFrame && !rowMoveActive && !rowMoveMoved && !rowDragActive &&
                        panelRect().contains(x, y)
                    ) {
                        // **有红框时，窗口内"没拖动"的一下（不论按多久、按在哪里）只取消框选**：
                        // 判定线和噪域都适用；噪域同时退出编辑。点窗口外只关窗口、不取消框住。
                        // 判定故意做松（只看"没有位移"），不会顶掉"长按框住第二个/拖动改图层"。
                        clearFrameSelection()
                        if (noiseSelectedLayers.isNotEmpty() || noiseEditActive) {
                            noiseSelectedLayers.clear()
                            if (noiseEditActive) stopNoiseEdit()
                            noiseEditIndex = -1
                            invalidateNoiseRows()
                            invalidate()
                        }
                    } else if (panelDownRow == -2 && panelAddRect().contains(x, y)) {
                        // 页脚按钮按页签动作
                        when (panelTab) {
                            1 -> onRequestImageLine?.invoke()
                            2 -> onRequestTextLine?.invoke()
                            3 -> {
                                // 噪域页签：点一下 = 进入"画新噪域"状态（长按同样支持）
                                closeLinePanel(keepSelection = true)
                                beginNoiseCreate()
                            }
                            else -> addLineSmart()
                        }
                    } else if (panelDownRow >= 0 && panelRowAt(x, y) == panelDownRow) {
                        val entry = panelEntries().getOrNull(panelDownRow)
                        if (entry != null && entry.isFolder) {
                            if (entry.noiseFolder) {
                                // 噪域图层夹：点击 = 展开/收起
                                if (entry.noiseLayer in noiseExpandedLayers) noiseExpandedLayers.remove(entry.noiseLayer)
                                else noiseExpandedLayers.add(entry.noiseLayer)
                                invalidateNoiseRows()
                                invalidate()
                            } else {
                                // 收纳夹：点击 = 展开 / 收起（向下展开同图层的判定线框）。
                                if (entry.layer in panelExpandedLayers) panelExpandedLayers.remove(entry.layer)
                                else panelExpandedLayers.add(entry.layer)
                                invalidate()
                            }
                        } else if (entry != null) {
                            if (entry.noiseIndex >= 0) {
                                // 短按 = 只选中/高亮；长按 = 进入噪域编辑（用户点名）
                                if (longPress || noiseEditActive) focusNoiseForEdit(entry.noiseIndex)
                                else {
                                    noiseEditIndex = entry.noiseIndex
                                    notifyNoiseInfo()
                                    invalidate()
                                }
                                return true
                            }
                            val target = lines.getOrNull(entry.lineIndex)
                            if (target != null) {
                                if (longPress) {
                                    // 长按 = 框住 / 取消框住（红框）：在已框住的基础上**再框一个**，
                                    // 并进入编辑（框住多条时输入面板同时作用于全部被框住的线）
                                    toggleFrameAndFocus(target.id)
                                } else if (selectedLines.isNotEmpty()) {
                                    // 有红框时，点窗口内**任意地方**（含行上）= 取消所有框住
                                    clearFrameSelection()
                                } else {
                                    activateLine(target.id)       // 无红框：单击 = 切换判定线
                                }
                                onLinesChanged?.invoke()
                            }
                        }
                    } else if (panelRect().contains(x, y)) {
                        // 点窗口内空白：取消所有框住（窗口保持打开）
                        if (selectedLines.isNotEmpty()) clearFrameSelection()
                    } else if (selectedLines.isNotEmpty()) {
                        // 点窗口外（背景）：**只关窗口，不取消框住**（用户点名）
                        closeLinePanel(keepSelection = true)
                    } else {
                        closeLinePanel()
                    }
                }
                panelDownRow = -1
            }
        }
        invalidate()
        return true
    }

    /** 拖动音符时手指贴在遮罩边上 → 持续滚动时间轴（越靠边越快） */
    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            if (!edgeScrollActive) return
            val dt = edgeScrollSpeed.toInt()
            if (dt != 0) {
                addEditSeek(dt)
                // 让被拖的音符"钉"在手指下面：
                //  - 整组拖动：用的是相对按下点的差值 → 补 extraDt
                //  - 单个拖动：时间是从指尖位置重算的 → 直接把它的时间也推 dt（下一次 MOVE 会重算，不会叠加）
                for (i in 0 until fingers.size()) {
                    val f = fingers.valueAt(i)
                    if (!f.active || f.mode != 2) continue
                    if (f.groupDrag) {
                        f.extraDt += dt
                    } else {
                        val idx = f.targetNote
                        if (idx in notes.indices) {
                            val n = notes[idx]
                            val ns = (n.startMs + dt).coerceAtLeast(0L)
                            val dur = (n.endMs - n.startMs).coerceAtLeast(0L)
                            setNote(idx, n.copy(startMs = ns, endMs = ns + dur))
                        }
                    }
                }
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    /** 手指贴到上/下遮罩 → 设置时间轴自动滚动速度（拖音符、框选共用） */
    private fun updateEdgeScroll(y: Float) {
        val topEdge = dp(98f)
        val botEdge = height - dp(102f)
        val k = when {
            y < topEdge -> ((topEdge - y) / dp(76f)).coerceIn(0f, 1f)
            y > botEdge -> -((y - botEdge) / dp(76f)).coerceIn(0f, 1f)
            else -> 0f
        }
        edgeScrollSpeed = if (autoEdgeScroll) k * 18f else 0f
        if (edgeScrollSpeed != 0f) {
            if (!edgeScrollActive) {
                edgeScrollActive = true
                postOnAnimation(edgeScrollRunnable)
            }
        } else {
            edgeScrollActive = false
        }
    }

    /** 判定线行上下拖动时，贴到列表上下边缘持续滚动 */
    private val rowMoveScrollRunnable = object : Runnable {
        override fun run() {
            if (!rowMoveActive) return
            val r = panelRect()
            val top = panelListTop(r)
            val bottom = panelListBottom(r)
            val zone = dp(70f)
            val dy = when {
                rowMoveLastY < top + zone -> -(((top + zone - rowMoveLastY) / zone).coerceIn(0f, 1f) * dp(9f))
                rowMoveLastY > bottom - zone -> ((rowMoveLastY - (bottom - zone)) / zone).coerceIn(0f, 1f) * dp(9f)
                else -> 0f
            }
            if (dy != 0f) {
                val before = panelScroll
                panelScroll = (panelScroll + dy).coerceIn(0f, panelMaxScroll())
                // 列表滚了多少，就从"拖动起点"里扣掉同样的量 → 被拖的那一行**始终钉在手指下面**
                // （不补偿的话，一贴边自动滚动，行就会自己往上下飘，越拖越和手指错位）
                val scrolled = panelScroll - before
                if (scrolled != 0f) rowMoveStartY -= scrolled
                updateRowMove(rowMoveLastY)
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    /** 按住一行/一个图层夹 0.3s 后上下拖动：线=改图层；文件夹封面=移位/合并（见 endRowMove） */
    private fun startRowMove(y: Float) {
        val es = panelEntries()
        if (panelDownRow < 0 || panelDownRow >= es.size) return
        rowMoveActive = true
        rowMoveMoved = false
        rowMoveFrom = panelDownRow
        rowMoveTarget = panelDownRow
        rowMoveLayer = if (es[panelDownRow].isFolder && !es[panelDownRow].noiseFolder) es[panelDownRow].layer else null
        rowMoveStartY = rowMoveLastY   // 用触发瞬间的手指位置，偏移从 0 起算，不会跳
        rowMoveDy = 0f
        rowMoveLastY = y
        postOnAnimation(rowMoveScrollRunnable)
        invalidate()
    }

    /** 只更新"跟手偏移 + 目标条目位置"，不动数据（松手才提交图层修改） */
    private fun updateRowMove(y: Float) {
        if (!rowMoveActive) return
        rowMoveLastY = y
        rowMoveDy = y - rowMoveStartY
        if (Math.abs(rowMoveDy) > dp(3f)) rowMoveMoved = true
        val r = panelRect()
        val listTop = panelListTop(r)
        val es = panelEntries()
        val center = listTop - panelScroll + panelEntryTop(es, rowMoveFrom) + panelRowH() / 2f + rowMoveDy
        var acc = 0f
        var hit = rowMoveTarget
        for ((i, e) in es.withIndex()) {
            acc += entryH(e)
            if (center < listTop - panelScroll + acc) { hit = i; break }
        }
        rowMoveTarget = hit.coerceIn(0, es.size - 1)
        invalidate()
    }

    private fun endRowMove() {
        rowMoveActive = false
        rowMoveScrollRunnable.let { removeCallbacks(it) }
        val from = rowMoveFrom
        val to = rowMoveTarget
        // 先取条目（还在"只显示图层夹"的拖动状态里），再清拖动标记
        val es = panelEntries()
        val src = es.getOrNull(from)
        rowMoveDy = 0f
        rowMoveFrom = -1
        rowMoveTarget = -1
        rowMoveLayer = null
        invalidate()
        if (src != null && src.noiseIndex >= 0) {
            if (rowMoveMoved) {
                if (noiseHasLayers() && to in es.indices && to != from) {
                    // 有图层：拖到目标行/目标图层夹 = 这块噪域改到那个图层
                    val dst = es[to]
                    val targetKey = if (dst.isFolder) dst.noiseLayer
                    else noiseAreas.getOrNull(dst.noiseIndex)?.layerId ?: ""
                    setNoiseLayer(src.noiseIndex, targetKey)
                } else if (to in es.indices && es[to].noiseIndex >= 0 && to != from) {
                    // 官谱没有图层：只调整窗口/数据里的顺序
                    val fromN = src.noiseIndex
                    val toN = es[to].noiseIndex
                    if (fromN in noiseAreas.indices) {
                        noiseMutate {
                            val moved = noiseAreas.removeAt(fromN)
                            noiseAreas.add(toN.coerceIn(0, noiseAreas.size), moved)
                        }
                        val sel = noiseEditIndex
                        if (sel >= 0) {
                            noiseEditIndex = when {
                                sel == fromN -> toN
                                fromN < toN && sel in (fromN + 1)..toN -> sel - 1
                                toN < fromN && sel in toN until fromN -> sel + 1
                                else -> sel
                            }
                        }
                        invalidateNoiseRows()
                        noiseRuntime = NoiseRuntime(noiseAreas)
                        DebugLog.add("noise", "reorder $fromN -> $toN")
                    }
                }
            } else {
                focusNoiseForEdit(src.noiseIndex)
            }
            rowMoveMoved = false
            invalidate()
            return
        }
        if (src != null && src.noiseFolder) {
            // 噪域图层夹：长按不动 = 框住整层（再点取消）；拖动 = 整层合并 / 调层顺序
            val key = src.noiseLayer
            if (!rowMoveMoved) {
                if (key in noiseSelectedLayers) {
                    noiseSelectedLayers.remove(key)
                } else {
                    noiseSelectedLayers.add(key)
                    noiseLayerMembers(key).lastOrNull()?.let { focusNoiseForEdit(it) }
                }
            } else if (to in es.indices && to != from) {
                val dst = es[to]
                val targetKey = if (dst.isFolder) dst.noiseLayer
                else noiseAreas.getOrNull(dst.noiseIndex)?.layerId
                if (targetKey != null && targetKey != key) {
                    if (key in noiseSelectedLayers) mergeNoiseLayer(key, targetKey)
                    else reorderNoiseLayer(key, targetKey)
                }
            }
            rowMoveMoved = false
            invalidate()
            return
        }
        if (src != null && src.isFolder) {
            val members = layerMembersOfTab(src.layer)
            val framed = members.isNotEmpty() && members.all { it.id in selectedLines }
            // 空图层（0 条线）：没有可框住的对象，长按什么都不做（以前 members.first() 会崩）
            if (members.isEmpty()) {
                rowMoveMoved = false
                invalidate()
                return
            }
            if (!rowMoveMoved) {
                // 长按文件夹封面松手 = **红框框住 / 取消框住整个图层**（用户点名）
                val ids = members.map { it.id }.toSet()
                if (framed) selectedLines.removeAll(ids) else {
                    // 只把层内所有判定线框住，**夹子不展开**（用户点名）
                    selectedLines.addAll(ids)
                    // 框住图层 = 进入编辑：活动线 = **最后框住的那条**（用户点名），
                    // 输入面板同时编辑层内所有判定线（editTargetLines）
                    focusLineForEdit(members.last().id)
                }
                onLinesChanged?.invoke()
            } else if (to in es.indices && to != from) {
                val dst = es[to]
                if (framed) {
                    // 拖动**被框住**的图层 = 合并到目标图层（不改变图层位置）
                    if (dst.layer != src.layer) mergeLayer(src.layer, dst.layer)
                } else {
                    // 拖动**没有被框住**的图层 = 改变图层位置（其它图层躲避让位）
                    repositionLayer(src.layer, dst.layer)
                }
            }
        } else if (rowMoveMoved && src != null && !src.isFolder && to in es.indices) {
            val dst = es[to]
            // 拖动判定线框 = 修改图层（拖到图层夹封面、或拖进展开的夹子内部都算——目标位置所在图层）；
            // 如果这条线在被框住的集合里，**框住的线一起改层**（用户点名）
            val newLayer = dst.layer
            val l = lines.getOrNull(src.lineIndex)
            if (l != null) {
                val targets = if (l.id in selectedLines) lines.filter { it.id in selectedLines } else listOf(l)
                if (targets.any { it.zOrder != newLayer }) {
                    val own = tx == null
                    if (own) beginTx()
                    linesMutate { for (one in targets) one.zOrder = newLayer }
                    if (own) endTx()
                    DebugLog.add("line", "layer move ${targets.size} 条 -> $newLayer")
                    onLinesChanged?.invoke()
                }
            }
        } else if (!rowMoveMoved && src != null && !src.isFolder) {
            // 长按但没拖动 = **框住 / 取消框住** + 聚焦进编辑（不再弹删除确认框；删除走右滑 80%）
            val id = lines.getOrNull(src.lineIndex)?.id
            if (id != null) toggleFrameAndFocus(id)
        }
        rowMoveMoved = false
        invalidate()
    }

    /**
     * 画某条判定线自己的音符（含正面/反面、假音符、双押提示、红框）。
     *
     * ★ 视锥剔除：先用 [NoteFrustum] 算出这条线此刻的可见时间窗，再在**按 startMs
     * 升序**的下标数组上二分，只遍历窗内的音符。官谱一张 1397 个音符 / 66 条线，
     * 每个音符的纵向位置都要反解流速积分（3 次迭代），不裁剪时每帧就是上万次数值解。
     *
     * 窗外的音符**一定**在屏幕外（窗口按 [NoteFrustum] 里的规则放宽过），
     * 真正上屏前的精确纵向判定仍然保留在 [drawNoteAt] 里。
     */
    private fun drawNotesOfLine(
        canvas: Canvas, l: Line, now: Long, cellW: Float,
        lcX: Float, lcY: Float, rot: Float, dh: BooleanArray, dimmed: Boolean = false
    ) {
        val sel = if (editMode) selectedSet else null
        val id = l.id
        // 这两个"每帧都一样"的东西提到循环外面：overlapFlags()/数组长度检查原来每个音符都要来一遍
        val ov = overlapFlags()
        val dhN = dh.size
        fun isDh(i: Int) = i < dhN && dh[i]
        val all = indexesForLine(id)
        if (all.isEmpty()) return

        // ---- 视锥剔除：二分出「这条线此刻可能上屏」的那一段 ----
        val win = NoteFrustum.window(
            l.speedTrack(), basePxPerMs(), height.toFloat(), l.minPositiveSpeed(), now,
            hasCtrl = l.hasDistCtrl(),
            // yOffset 是固定像素平移，与流速无关 —— 缩放拉大时窗口只有 0.1 秒，
            // 不把它算进去会把「屏幕上明明看得到」的音符剔掉
            maxAbsYOffset = maxYOffsetOf(id)
        )
        val from: Int
        val to: Int
        if (win == null) {
            // 流速退化（0 / 非有限）：所有音符都挤在一起，照旧全画
            from = 0; to = all.size
        } else {
            // 下界要往回退「最长的那根长条」：长条按 startMs 排序，但它的尾巴可能
            // 还在屏幕上（头部早就出窗了）。退回 maxHoldLen 保证不误剔。
            val holdBack = maxHoldLenOf(id)
            from = NoteFrustum.lowerBound(all, notes, win.first - holdBack)
            to = NoteFrustum.upperBound(all, notes, win.last)
        }

        for (k in from until to) {
            val i = all[k]
            val n = notes[i]
            if (playMode && judgedAt(i)) {
                // 已判定的长条：Phira 是到结束时间就消失（`if res.time >= end_time { return }`），
                // 这里也按结束时间截断，避免判定线流速低/旋转时长条残留在屏幕上不消失
                if (n.type == 2 && !n.fake && now < n.endMs) drawNoteAt(canvas, n, now, cellW, 255, isDh(i), l, lcX, lcY, rot)
                continue
            }
            var alpha = 255
            if (dimmed) alpha = (alpha * DIM_ALPHA).toInt()   // 线高亮关闭时：非选中线的音符淡化
            if (n.fake && ghostShow == 1) alpha = (alpha * DIM_ALPHA).toInt()   // 假音符 = 淡化
            // 真实处理（仅编辑/录制模式）：打过的音符 = 已经过判定线的（含 Autoplay 打掉的）
            if (!playMode && realProcess != 0 && (judgedAt(i) || n.startMs < now - AUTO_HIT_WINDOW_MS)) {
                if (realProcess == 2) continue                // 隐藏
                alpha = (alpha * DIM_ALPHA).toInt()           // 淡化
            }
            val tilt = !playMode && ov[i]                     // 被后来的音符盖住 → 斜 45° + 橙框（打歌不斜）
            drawNoteAt(canvas, n, now, cellW, alpha, isDh(i), l, lcX, lcY, rot, tilt)
            // 反向=蓝框 / 幽灵=绿框 / 重叠=橙框（斜 45°）；两个都是就左上蓝、右下绿。
            // 打歌时这些都是编辑辅助框，Phira 不画 —— 打歌一律不显示
            val needBlue = !playMode && markReverse && !n.above
            val needGreen = !playMode && n.fake && ghostFrame
            val isSel = sel != null && i in sel
            if (tilt) {
                noteScreenPosInto(n, now, lcX, lcY, rot, scratchPos)
                val fx = scratchPos[0]
                val fy = scratchPos[1]
                val fRot = -rot + (if (n.above) 0f else 180f) + 45f
                // 内层蓝/绿缩小，外面套橙框（选中时：左上红 + 右下橙）
                if (needBlue || needGreen) {
                    drawFlagFrames(canvas, fx, fy, cellW, fRot, needBlue, needGreen, scale = 0.55f)
                }
                if (overlapFrame && !playMode) {
                    if (isSel) drawSplitFrame(canvas, fx, fy, cellW, fRot, 0xFFFF5252.toInt(), COLOR_OVERLAP, 1f)
                    else drawNoteFrame(canvas, fx, fy, cellW, fRot, COLOR_OVERLAP, 1f)
                } else if (isSel) {
                    drawSelectFrame(canvas, fx, fy, cellW, fRot)
                }
            } else if (needBlue || needGreen) {
                noteScreenPosInto(n, now, lcX, lcY, rot, scratchPos)
                drawFlagFrames(
                    canvas, scratchPos[0], scratchPos[1], cellW,
                    -rot + if (n.above) 0f else 180f,
                    needBlue, needGreen, scale = if (isSel) 0.76f else 1f
                )
            }
            if (isSel) {
                noteScreenPosInto(n, now, lcX, lcY, rot, scratchPos)
                drawSelectFrame(canvas, scratchPos[0], scratchPos[1], cellW, -rot + if (n.above) 0f else 180f)
            }
        }

        // 长条与判定线接触期间：持续打击特效（音效只响一次，见 tickAutoPlay）
        val holdFxFxBmp = hitFxBmp
        if (playMode && holdFxFxBmp != null && holdFxList.isNotEmpty()) {
            val fw = holdFxFxBmp.width / hitFxCols
            val fh = holdFxFxBmp.height / hitFxRows
            val it = holdFxList.iterator()
            while (it.hasNext()) {
                val fx = it.next()
                if (fx.idx !in notes.indices || now > fx.untilMs) { it.remove(); continue }
                val n = notes[fx.idx]
                if (lineOf(n).id != id) continue
                val a = Math.toRadians(-rot.toDouble())
                val xl = (n.posX + 1f) / 2f * width - width / 2f
                val px = lcX + (xl * Math.cos(a)).toFloat()
                val py = lcY + (xl * Math.sin(a)).toFloat()
                val pulse = 0.82f + 0.18f * Math.sin(now / 160.0).toFloat()
                val size = dp(150f) * hitFxScale * pulse
                val idx = hitFxCols * hitFxRows / 2          // 取中间一帧（常亮）
                val col = idx % hitFxCols
                val row = idx / hitFxCols
                texPaint.alpha = 215
                fxSrc.set(col * fw, row * fh, (col + 1) * fw, (row + 1) * fh)
                fxDst.set(px - size / 2, py - size / 2, px + size / 2, py + size / 2)
                canvas.drawBitmap(holdFxFxBmp, fxSrc, fxDst, texPaint)
            }
        }
    }

    /**
     * 音符：画在判定线局部坐标系（跟随判定线姿态平移/旋转）。
     * 局部 x = 沿线方向（-w/2..w/2），局部 y = 垂直法线方向（未来在上）。
     * 反面音符（above=false）：y 取反（从线的另一侧过来），贴图再转 180°（与 Phira 一致）。
     */
    private fun drawNoteAt(
        canvas: Canvas, n: RecordedNote, now: Long, cellW: Float, alpha: Int,
        isDouble: Boolean = false, l: Line, lcX: Float = 0f, lcY: Float = 0f, rotDeg: Float = 0f,
        tilt: Boolean = false
    ) {
        val h = height.toFloat()
        // ---- Phira 打歌语义（只影响 Autoplay；编辑模式保持"自由"，不隐藏）----
        if (playMode) {
            // 假音符到点就消失（Phira note.rs：fake && res.time >= self.time，长条除外）
            if (n.fake && now >= n.startMs && n.type != 2) return
            // 长条整根到尾就消失（Phira hold 块：res.time >= end_time → return，假长条同理）
            if (n.type == 2 && now >= n.endMs) return
        }
        // Phira now_transform：非长条的横向位置乘 posControl × incline；长条恒 1
        val xFac = noteXFactor(l, n, now)
        // 音符宽度 × note.size × ctrl.sizeControl（长条也吃缩放）
        val cellW2 = cellW * n.size * ctrlSizeOf(l, n, now)
        val yStartL = noteLocalY(l, n, n.startMs, now)
        if (n.type == 2) {
            // 长条要看**整条**的范围：只看头部的话，长条刚探出屏幕一半就会整根被砍掉
            val yEndL = noteLocalY(l, n, n.endMs, now)
            val topY = minOf(yStartL, yEndL)
            val botY = maxOf(yStartL, yEndL)
            if (topY > h + 120 || botY < -h - 120) return
        } else if (yStartL > h + 120 || yStartL < -h - 120) return

        // 假音符的显示交给判定线 alpha 管：alpha 为负时连音符一起隐藏（Phira PE alpha extension），
        // 这里不再自己加"流速"规则，否则会把谱面本来该出现的长条也压掉

        // ---- 透明度：编辑/录制的淡化（alpha 参数）× 音符 alpha × ctrl.alphaControl × visibleTime 淡入 ----
        var aMul = n.alpha * ctrlAlphaOf(l, n, now)
        // Phira：visibleTime 有限时音符按 (0,0)→(起始-可见, alpha) 线性淡入
        if (n.visibleTime < n.startMs / 1000f) {
            val full = n.startMs - (n.visibleTime * 1000f).toLong()
            if (full > 0) aMul *= (now.toFloat() / full).coerceIn(0f, 1f)
        }
        val finalAlpha = (alpha * aMul).toInt().coerceIn(0, 255)
        if (finalAlpha <= 0) return

        // 局部横向位置（判定线坐标系里，原点 = 判定线中心）
        val x = ((n.posX + 1f) / 2f * width - width / 2f) * xFac

        // ---- 横向（视锥）剔除 ----
        // 局部 x / 局部 y 都已经算好，直接投到屏幕看有没有出屏，出屏就 return ——
        // 不要进 canvas.save/translate/rotate + 贴图绘制。
        // 判定线可以旋转，所以按「局部框的两端」投到屏幕再判，而不是只判局部 x。
        run {
            val w = width.toFloat()
            // 半宽：贴图实际宽度的一半。cellW 是「音符宽度」，有些贴图（双押 mh）会再放大，
            // 斜 45° 摆的时候外接框还要按对角线（≈1.41 倍）算 ⇒ 这里直接给 1.6 倍余量。
            // 宁可多画一个屏外的音符，也不能把贴边的那一个裁掉（裁掉就是"音符凭空消失"）。
            val hw = cellW2 * 1.6f
            val ra = Math.toRadians(-rotDeg.toDouble())
            val c = Math.cos(ra).toFloat()
            val s = Math.sin(ra).toFloat()
            val sx0 = lcX + (x - hw) * c - yStartL * s
            val sx1 = lcX + (x + hw) * c - yStartL * s
            val pad = w * 0.25f
            val left = if (sx0 < sx1) sx0 else sx1
            val right = if (sx0 < sx1) sx1 else sx0
            // 长条顺着局部 y 延伸，横向范围与头部相同，所以只判横向就够
            if (right < -pad || left > w + pad) return
        }

        canvas.save()
        canvas.translate(lcX, lcY)
        canvas.rotate(-rotDeg)
        // 音符颜色（RPE tint）：正片叠底，和 Phiedit 的 addColor 一个意思；
        // 没有 tint 的音符（绝大多数）只多做一次 null 判断，不加滤镜。
        // ColorFilter 走缓存：每帧、每个带色音符都 new 一个太浪费（颜色种类通常就几种）
        val texTint = n.tint
        if (texTint >= 0) {
            texPaint.colorFilter = tintFilterOf(texTint)
        } else if (texPaint.colorFilter != null) {
            texPaint.colorFilter = null
        }

        when (n.type) {
            1 -> {
                val bmp = if (isDouble) tapMh ?: tapBmp else tapBmp
                val nw = cellW2 * (if (isDouble) tapMhScale else 1f)
                val nh = nw * (bmp?.height?.toFloat() ?: 100f) / (bmp?.width?.toFloat() ?: 998f)
                if (!n.above) canvas.rotate(180f, x, yStartL)
                if (tilt) canvas.rotate(45f, x, yStartL)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, finalAlpha)
            }
            3 -> {
                val bmp = if (isDouble) flickMh ?: flickBmp else flickBmp
                val nw = cellW2 * (if (isDouble) flickMhScale else 1f)
                val nh = nw * (bmp?.height?.toFloat() ?: 172f) / (bmp?.width?.toFloat() ?: 998f)
                if (!n.above) canvas.rotate(180f, x, yStartL)
                if (tilt) canvas.rotate(45f, x, yStartL)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, finalAlpha)
            }
            4 -> {
                val bmp = if (isDouble) dragMh ?: dragBmp else dragBmp
                val nw = cellW2 * (if (isDouble) dragMhScale else 1f)
                val nh = nw * (bmp?.height?.toFloat() ?: 50f) / (bmp?.width?.toFloat() ?: 948f)
                if (!n.above) canvas.rotate(180f, x, yStartL)
                if (tilt) canvas.rotate(45f, x, yStartL)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, finalAlpha)
            }
            2 -> {
                // Hold 只画 hold.png 长条：贴图本身自带头部渐变，不在上面叠 Tap
                val yEndL = noteLocalY(l, n, n.endMs, now)
                var bodyTop = minOf(yStartL, yEndL)
                var bodyBot = maxOf(yStartL, yEndL)
                // 流速 = 0 / 特别短的长条：厚度为 0 看不见也点不到 → 先给个最小高度。
                // 注意这步必须在"裁剪"**之前**：以前是裁完再撑，整条过了判定线就会被撑成
                // 贴在线上的一小条（就是"打击后缩成一小条"的元凶）。
                if (bodyBot - bodyTop < dp(9f)) {
                    val mid = (bodyTop + bodyBot) / 2f
                    bodyTop = mid - dp(4.5f)
                    bodyBot = mid + dp(4.5f)
                }
                // 打歌：越过判定线的部分隐藏（整条都过线了就整条不画）。
                // 编辑模式**不裁**：判定线不影响长条，一条长条会正常穿过判定线显示。
                // 实时录制（点"继续"边播边放）也不裁，否则整条都过线、放下去完全看不到。
                if (playMode) {
                    if (n.above) bodyBot = minOf(bodyBot, 0f) else bodyTop = maxOf(bodyTop, 0f)
                }
                val bw = cellW2 * (if (isDouble) holdMhScale else 1f)
                // 只要还有厚度就画（出屏与否由本函数开头的"整条范围"判断负责）。
                // 以前这里写死 -300 / h+300：那是**音符本地坐标**，屏幕顶大约在 -0.6h，
                // 于是长条下半截一越过判定线上方 300px 就整根被砍掉 —— 上移一半就消失就是这个。
                if (dbgLogFrame) {
                    DebugLog.add(
                        "autoplay",
                        "画长条 line=${l.id} x=${Math.round(n.posX * 100f) / 100f} ${n.startMs}-${n.endMs} " +
                            "top=${Math.round(bodyTop)} bot=${Math.round(bodyBot)} above=${n.above} " +
                            "fake=${n.fake} spd=${Math.round(l.speedAt(now) * 100f) / 100f} now=$now"
                    )
                }
                if (bodyBot > bodyTop + 0.5f) {
                    if (!n.above) canvas.rotate(180f, x, (bodyTop + bodyBot) / 2f)
                    if (tilt) canvas.rotate(45f, x, (bodyTop + bodyBot) / 2f)
                    drawTex(canvas, if (isDouble) holdMh ?: holdBmp else holdBmp, x - bw / 2, bodyTop, x + bw / 2, bodyBot, finalAlpha)
                }
            }
        }
        if (texTint >= 0) texPaint.colorFilter = null      // 别把颜色带到下一个音符
        canvas.restore()
    }

    /** 找 (x,y) 附近的音符（含下落中的），返回下标，未找到 -1 */
    private fun findNoteAt(x: Float, y: Float, now: Long): Int {
        var best = -1
        var bestD2 = Float.MAX_VALUE
        val baseHit = dp(26f)        // 普通音符：碰到才算
        val noteW = noteWidthPx()    // 音符宽度（Phira 固定比例，和横线数无关）
        // 每个触摸事件都要跑一遍：重叠标记缓存提到循环外，距离比较用平方（不算 sqrt、不装箱）
        val ov = overlapFlags()
        val ovN = ov.size
        for (l in lines) {
            // 快速切换关着 = 其他线上的音符看得到、点不到
            if (!quickSwitch && l.id != activeLineId) continue
            lineFrameOf(l, now, scratchPose)
            val lcX = scratchPose[0]
            val lcY = scratchPose[1]
            val a = Math.toRadians(-scratchPose[2].toDouble())
            val cosA = Math.cos(a).toFloat()
            val sinA = Math.sin(a).toFloat()
            val halfW = width / 2f
            for (i in indexesForLine(l.id)) {
                val n = notes[i]
                val xl = ((n.posX + 1f) / 2f * width - halfW) * noteXFactor(l, n, now)
                val yl = noteLocalY(l, n, n.startMs, now)
                val px = lcX + xl * cosA - yl * sinA
                val dx = x - px
                // 斜置（重叠）的音符框更大，命中范围也跟着放大（和橙框一样大），否则点橙框选不中
                val limit = if (i < ovN && ov[i]) noteW * 0.85f else baseHit
                if (Math.abs(dx) > limit) continue          // 横向就不在范围内，不用再算 y
                val dy = y - (lcY + xl * sinA + yl * cosA)
                if (Math.abs(dy) > limit) continue
                val d2 = dx * dx + dy * dy
                if (d2 <= limit * limit && d2 < bestD2) { bestD2 = d2; best = i }
            }
        }
        return best
    }

    /** 红框矩形：与 drawSelectFrame 完全一致，保证"看到的框"就是"能点的框" */
    private fun drawSelectFrame(canvas: Canvas, x: Float, y: Float, cellW: Float, rot: Float = 0f) {
        drawNoteFrame(canvas, x, y, cellW, rot, 0xFFFF5252.toInt())
    }

    /**
     * 音符外框（红=选中 / 蓝=反面 / 绿=幽灵 / 橙=重叠）。
     * [scale] 用来"被别的框套住时缩小一点"，免得几个框完全重合看不出来。
     */
    private fun drawNoteFrame(
        canvas: Canvas, x: Float, y: Float, cellW: Float, rot: Float,
        color: Int, scale: Float = 1f
    ) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rot)                       // 框跟随判定线旋转
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(3f)
        livePaint.color = color
        val halfW = cellW * 0.72f * scale
        val halfH = dp(18f) * scale
        canvas.drawRoundRect(-halfW, -halfH, halfW, halfH, dp(9f) * scale, dp(9f) * scale, livePaint)
        livePaint.style = Paint.Style.FILL
        canvas.restore()
    }

    /** 对角双色框：左上 [colorA]、右下 [colorB] */
    private fun drawSplitFrame(
        canvas: Canvas, x: Float, y: Float, cellW: Float, rot: Float,
        colorA: Int, colorB: Int, scale: Float = 1f
    ) {
        val halfW = cellW * 0.72f * scale
        val halfH = dp(18f) * scale
        var sc = canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rot)
        pathTmp.reset()
        pathTmp.moveTo(-halfW, -halfH)
        pathTmp.lineTo(halfW, -halfH)
        pathTmp.lineTo(-halfW, halfH)
        pathTmp.close()
        canvas.clipPath(pathTmp)
        drawNoteFrame(canvas, 0f, 0f, cellW, 0f, colorA, scale)
        canvas.restoreToCount(sc)
        sc = canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rot)
        pathTmp.reset()
        pathTmp.moveTo(halfW, -halfH)
        pathTmp.lineTo(halfW, halfH)
        pathTmp.lineTo(-halfW, halfH)
        pathTmp.close()
        canvas.clipPath(pathTmp)
        drawNoteFrame(canvas, 0f, 0f, cellW, 0f, colorB, scale)
        canvas.restoreToCount(sc)
    }

    /** 蓝（反向）/绿（幽灵）/两个都有（左上蓝 + 右下绿） */
    private fun drawFlagFrames(
        canvas: Canvas, x: Float, y: Float, cellW: Float, rot: Float,
        blue: Boolean, green: Boolean, scale: Float = 1f
    ) {
        if (!blue && !green) return
        if (blue && green) {
            drawSplitFrame(canvas, x, y, cellW, rot, COLOR_REVERSE, COLOR_GHOST, scale)
        } else {
            drawNoteFrame(canvas, x, y, cellW, rot, if (blue) COLOR_REVERSE else COLOR_GHOST, scale)
        }
    }

    /** 判定线编辑 UI：中心白色方框、旋转箭头、关键帧方块、左侧时间条 */
    private fun drawLineEditUi(canvas: Canvas, cx: Float, cy: Float, now: Long, w: Float, h: Float) {
        // 中心白色方框（拖动=移动判定线）
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(2.5f)
        livePaint.color = Color.WHITE
        canvas.drawRect(cx - dp(16f), cy - dp(16f), cx + dp(16f), cy + dp(16f), livePaint)
        livePaint.style = Paint.Style.FILL
        livePaint.color = intColor(0x50FFFFFF)
        canvas.drawRect(cx - dp(16f), cy - dp(16f), cx + dp(16f), cy + dp(16f), livePaint)

        // 左上/右下：旋转箭头（保持原位置）
        drawArrow(canvas, cx - dp(40f), cy - dp(40f), -135f)
        drawArrow(canvas, cx + dp(40f), cy + dp(40f), 45f)

        // 上/下/左/右：四个向外的缩放箭头（拖动 = 等比缩放；只有这 4 个往外挪）
        drawScaleArrow(canvas, cx, cy - dp(96f), 0f)
        drawScaleArrow(canvas, cx, cy + dp(96f), 180f)
        drawScaleArrow(canvas, cx - dp(96f), cy, -90f)
        drawScaleArrow(canvas, cx + dp(96f), cy, 90f)

        // 左下/右上：关键帧小方块（点击=打关键帧；保持原位置）
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(2f)
        livePaint.color = intColor(0xFF00E5FF)
        drawDiamond(canvas, cx - dp(40f), cy + dp(40f))
        drawDiamond(canvas, cx + dp(40f), cy - dp(40f))
        livePaint.style = Paint.Style.FILL

        // 左侧关键帧时间条 + 圆点
        val barX = dp(8f)
        gridPaint.alpha = 60
        canvas.drawLine(barX, dp(40f), barX, h - dp(120f), gridPaint)
        val kfs = activeLine.sortedKeyframes()
        for ((i, k) in kfs.withIndex()) {
            val ky = cy - (k.timeMs - now) * pxPerMs   // 以判定线真实中心为时间原点
            if (ky < dp(30f) || ky > h - dp(130f)) continue
            livePaint.style = Paint.Style.FILL
            livePaint.color = intColor(0xFF00E5FF)
            canvas.save()
            canvas.translate(barX, ky)
            canvas.rotate(45f)
            canvas.drawRect(-dp(7f), -dp(7f), dp(7f), dp(7f), livePaint)
            canvas.restore()
            if (k.timeMs == selectedKfTime) {
                livePaint.style = Paint.Style.STROKE
                livePaint.strokeWidth = dp(2.5f)
                livePaint.color = intColor(0xFFFF5252)
                // 框住这一帧（不是只画个圆圈），一眼能看出正在编辑哪一帧
                canvas.drawRoundRect(
                    barX - dp(16f), ky - dp(16f), barX + dp(16f), ky + dp(16f),
                    dp(5f), dp(5f), livePaint
                )
                livePaint.style = Paint.Style.FILL
            }
        }
        livePaint.style = Paint.Style.FILL
    }

    /** 旋转箭头（三角） */
    private fun drawArrow(canvas: Canvas, x: Float, y: Float, rot: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rot)
        livePaint.style = Paint.Style.FILL
        livePaint.color = intColor(0xCCFFFFFF)
        val p = android.graphics.Path()
        p.moveTo(0f, -dp(12f)); p.lineTo(dp(12f), dp(6f)); p.lineTo(-dp(12f), dp(6f)); p.close()
        canvas.drawPath(p, livePaint)
        canvas.restore()
        livePaint.style = Paint.Style.FILL
    }

    /** 缩放箭头（绿色三角 + 一小段指向外侧的线，和白色旋转箭头区分开） */
    private fun drawScaleArrow(canvas: Canvas, x: Float, y: Float, rot: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rot)
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(1.5f)
        livePaint.color = intColor(0xCC69F0AE)
        canvas.drawLine(0f, dp(6f), 0f, dp(14f), livePaint)
        livePaint.style = Paint.Style.FILL
        livePaint.color = intColor(0xCC69F0AE)
        val p = android.graphics.Path()
        p.moveTo(0f, -dp(9f)); p.lineTo(dp(9f), dp(5f)); p.lineTo(-dp(9f), dp(5f)); p.close()
        canvas.drawPath(p, livePaint)
        canvas.restore()
        livePaint.style = Paint.Style.FILL
    }

    /** 菱形小方块 */
    private fun drawDiamond(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(45f)
        canvas.drawRect(-dp(8f), -dp(8f), dp(8f), dp(8f), livePaint)
        canvas.restore()
    }

    /** 红框命中区域（框线 + 框内） */
    /** 红框命中（只按 x/y 判断矩形，不含音符姿态的话会错位，所以按线分组算一次姿态） */
    private fun findFrameNoteAt(x: Float, y: Float, now: Long, onlySelected: Boolean): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        val halfW = (width.toFloat().coerceAtLeast(1f) / vLines.coerceAtLeast(1)) * 0.72f
        val halfH = dp(18f)
        for (l in lines) {
            if (!quickSwitch && l.id != activeLineId) continue
            lineFrameOf(l, now, scratchPose)
            val lcX = scratchPose[0]
            val lcY = scratchPose[1]
            val cosA = Math.cos(Math.toRadians(-scratchPose[2].toDouble())).toFloat()
            val sinA = Math.sin(Math.toRadians(-scratchPose[2].toDouble())).toFloat()
            val halfScreenW = width / 2f
            val idx = if (onlySelected) selectedSet.toIntArray() else indexesForLine(l.id)
            for (i in idx) {
                if (i !in notes.indices) continue
                val n = notes[i]
                if (n.lineId != l.id) continue
                val xl = ((n.posX + 1f) / 2f * width - halfScreenW) * noteXFactor(l, n, now)
                val yl = noteLocalY(l, n, n.startMs, now)
                val px = lcX + xl * cosA - yl * sinA
                val py = lcY + xl * sinA + yl * cosA
                if (x < px - halfW || x > px + halfW || y < py - halfH || y > py + halfH) continue
                val d = (x - px) * (x - px) + (y - py) * (y - py)
                if (d < bestD) { bestD = d; best = i }
            }
        }
        return best
    }

    /** 第二/三次长按：按最小横线间隔填充 */
    private fun tickFill() {
        if (pass == 4) return
        if (fullMode) return        // 全量模式自己铺，别被旧的三阶段填充逻辑插一脚
        val now = playMs()
        val slot = cellMs()
        for (i in 0 until fingers.size()) {
            val f = fingers.valueAt(i)
            if (!f.active) continue
            val held = SystemClock.elapsedRealtime() - f.downRealMs
            if (held < holdMsThreshold) continue
            val curSlot = Math.round((now - bpmOffsetMs) / slot)
            var s = f.lastFillSlot + 1
            var guard = 0
            while (s <= curSlot && guard < 64) {
                val t = ((s * slot) + bpmOffsetMs).toLong()
                addNote(RecordedNote(passType(pass), t, t, quantizeX(f.curX), pass, f.lineId, f.above))
                f.lastFillSlot = s
                s++
                guard++
            }
        }
    }

    /** 阶段 -> RPE 音符类型 */
    private fun passType(p: Int): Int = when (p) {
        1 -> 1      // 蓝键 Tap
        2 -> 4      // 黄键 Drag
        3 -> 3      // 红键 Flick
        else -> 2   // 第四次：蓝长条 Hold
    }

    /** 手势结束：定稿 */
    private fun finishGesture(f: FingerState, upRealMs: Long) {
        val dur = (upRealMs - f.downRealMs).coerceAtLeast(0L)
        val upMs = if (f.upPlayMs > 0) f.upPlayMs else quantMs(playMs())

        if (pass == 4) {
            // 第四次：点按 = 1 个横线间隔的短长条；长按 = 按住时长
            val s0 = quantMs(f.downPlayMs)
            val minLen = (cellMs() * 2).toLong()   // 短长条 = 2 个横线间隔
            val e0 = if (dur >= holdMsThreshold) maxOf(quantMs(upMs), s0 + minLen) else s0 + minLen
            addNote(RecordedNote(2, s0, e0, quantizeX(f.downX), 4, f.lineId, f.above))
        } else if (dur >= holdMsThreshold) {
            // 第一~三次：长按结束，补齐最后一格
            val slot = cellMs()
            val curSlot = Math.round(upMs / slot)
            var sIdx = f.lastFillSlot + 1
            var guard = 0
            while (sIdx <= curSlot && guard < 64) {
                val t = (sIdx * slot).toLong()
                if (t <= upMs) {
                    addNote(RecordedNote(passType(pass), t, t, quantizeX(f.curX), pass, f.lineId, f.above))
                    f.lastFillSlot = sIdx
                }
                sIdx++
                guard++
            }
        }
    }

    /** 撤销一步（按钮）；没有可撤销的返回 false */
    fun undoLast(): Boolean = undo()

    /**
     * 音符统计（一次遍历）：[0]=总数 [1..3]=pass 1/2/3 的数量 [4]=假音符数。
     * 原来状态行要连做 4 遍 count，这里合成一遍。
     */
    fun noteStats(): IntArray {
        val out = IntArray(5)
        out[0] = notes.size
        for (n in notes) {
            if (n.pass in 1..3) out[n.pass]++
            if (n.fake) out[4]++
        }
        return out
    }

    // ---------------------------------------------------------------- 手势
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 一次按下 → 抬起 = 一个撤销步（多指同时操作也算一步，撤销时整组一起回退）
        val isDown = event.actionMasked == MotionEvent.ACTION_DOWN
        val isUp = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        if (isDown) beginTx()
        val handled = handleTouch(event)
        if (isUp) endTx()
        return handled
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val nowReal = SystemClock.elapsedRealtime()

        // 右侧判定线抽屉 / 左侧剪贴板面板：各自吃掉自己的触摸
        if (handleLineSwitcherTouch(event)) return true
        if (handleClipTouch(event)) return true

        // 噪域编辑 / 等待画新噪域：所有触摸都归噪域手势（拖角、画矩形）
        if (noiseEditActive || noiseCreatePending) return onNoiseEditTouch(event)

        // Autoplay：左上角暂停按钮 **1 秒内点两下** 才弹菜单（防误触：第一下不反应）；
        // 其余触摸不判分，但要喂给噪域做「断触」——手指压在亮着的噪域上，那根手指记作失效
        if (playMode && !editMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && inPauseButton(event.x, event.y)) {
                val real = SystemClock.elapsedRealtime()
                if (real - lastPauseTapReal <= 1000L) {
                    lastPauseTapReal = 0L
                    onPauseTap?.invoke()
                } else {
                    lastPauseTapReal = real
                }
            }
            if (noiseJamEnabled) trackNoiseFingers(event)
            return true
        }

        // 编辑模式（暂停）：判定线编辑 / 框选多选编辑（全量模式只在录制时生效）
        if (editMode) {
            if (lineEditActive) return onLineEditTouch(event, nowReal)
            // 画布长按噪域 = 进入噪域编辑（不消费事件，只是起一个长按计时）
            trackNoiseLongPress(event)
            return onEditTouch(event, nowReal)
        }

        // 全量模式：只在播放中（录制模式）生效，且不再走"第几次"的逻辑
        if (fullMode) return onFullModeTouch(event, nowReal)

        // ---- 普通录制模式 ----
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id] ?: FingerState().also { fingers.put(id, it) }
                val x = event.getX(idx); val y = event.getY(idx)
                f.downRealMs = nowReal
                f.downPlayMs = playMs()
                f.downX = x; f.downY = y
                f.curX = x; f.curY = y
                f.active = true
                f.mode = 0
                f.lastFillSlot = -1
                // 音符放在当前选中的判定线上（换线用右侧抽屉 / 编辑模式的快速切换）
                f.lineId = activeLineId
                f.downLineId = activeLineId
                // 反面开关打开时：落点在判定线下方 = 反面（音符会从下方顶上来），上方 = 正面
                f.above = true
                if (reverseEnabled) {
                    val ln = activeLine
                    lineFrameOf(ln, playMs(), scratchPose)
                    val cxx = scratchPose[0]
                    val cyy = scratchPose[1]
                    val ang = Math.toRadians(scratchPose[2].toDouble())
                    val yl = ((x - cxx) * Math.sin(ang) + (y - cyy) * Math.cos(ang)).toFloat()
                    f.above = yl < 0f
                }

                spawnHitFx(x, y)
                ensureSounds()
                playHitSound(pass)

                if (pass == 4) {
                    // 第四次：长条。按下不放任何东西，抬手生成（finishGesture）
                } else {
                    // 第一~三次：按下立即放一个（1=Tap 2=Drag 3=Flick），长按按格铺
                    val q = quantMs(f.downPlayMs)
                    addNote(RecordedNote(passType(pass), q, q, quantizeX(x), pass, f.lineId, f.above))
                    f.lastFillSlot = Math.round((q - bpmOffsetMs) / cellMs())
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val f = fingers[id] ?: continue
                    if (!f.active) continue
                    f.curX = event.getX(i); f.curY = event.getY(i)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id]
                if (f != null && f.active) {
                    f.active = false
                    f.upPlayMs = playMs()
                    f.curX = event.getX(idx); f.curY = event.getY(idx)
                    finishGesture(f, nowReal)
                    invalidate()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    fingers.clear()
                }
                return true
            }
        }
        return true
    }

    /**
     * 全量模式手势（用户自己写谱用）：
     * 单击 = 蓝键；长按不动 = 长条（按住多久就多长）；
     * 长按并左右移动 = 沿途铺一串同时刻的黄键；短滑（上/下/左/右）= 红键。
     */
    private fun onFullModeTouch(event: MotionEvent, nowReal: Long): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id] ?: FingerState().also { fingers.put(id, it) }
                val x = event.getX(idx); val y = event.getY(idx)
                f.downRealMs = nowReal
                f.downPlayMs = playMs()
                f.downX = x; f.downY = y; f.curX = x; f.curY = y
                f.active = true
                f.mode = 6
                f.moved = false
                f.chainCell = -1
                f.lineId = activeLineId
                f.above = true
                spawnHitFx(x, y); ensureSounds(); playHitSound(pass)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val f = fingers[id] ?: continue
                    if (!f.active) continue
                    val x = event.getX(i); val y = event.getY(i)
                    f.curX = x; f.curY = y
                    val held = nowReal - f.downRealMs
                    val dx = x - f.downX
                    val dy = y - f.downY
                    if (f.mode == 6) {
                        if (held >= 300 && Math.abs(dx) > dp(26f) && Math.abs(dy) <= Math.abs(dx)) {
                            f.mode = 8                  // 长按 + 左右移动 = 黄键链
                            fillDragChain(f)
                        }
                    } else if (f.mode == 8) {
                        fillDragChain(f)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id]
                if (f != null && f.active) {
                    f.active = false
                    f.curX = event.getX(idx); f.curY = event.getY(idx)
                    if (event.actionMasked != MotionEvent.ACTION_CANCEL) finishFullModeGesture(f, nowReal)
                    invalidate()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    fingers.clear()
                }
                return true
            }
        }
        return true
    }

    private fun finishFullModeGesture(f: FingerState, nowReal: Long) {
        val held = (nowReal - f.downRealMs).coerceAtLeast(0L)
        val dx = f.curX - f.downX
        val dy = f.curY - f.downY
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val q = quantMs(f.downPlayMs)
        when (f.mode) {
            8 -> { /* 链已经边拖边放好了 */ }
            6 -> when {
                // 长按（不管手指有没有轻微移动）都算长条，长度 = 按住时长
                held >= 300L -> {
                    addNote(RecordedNote(2, q, q + held.coerceAtLeast(150L), quantizeX(f.downX), 4, f.lineId, true))
                }
                dist >= dp(30f) && held < 320L -> {
                    // 短滑（上下左右）= 红键
                    addNote(RecordedNote(3, q, q, quantizeX(f.curX), 3, f.lineId, true))
                }
                else -> {
                    // 单击（或很短的小幅移动）= 蓝键
                    addNote(RecordedNote(1, q, q, quantizeX(f.downX), 1, f.lineId, true))
                }
            }
        }
        f.mode = 0
        f.chainCell = -1
    }

    /** 全量模式：手指横向移动时，按位置格补一串 Drag（黄键），时间随流速往后排（不是平行一坨） */
    private fun fillDragChain(f: FingerState) {
        val cellW = 2f / vLines.coerceAtLeast(1)
        fun cellOf(x: Float): Int =
            Math.round((quantizeX(x) + 1f) / cellW).toInt().coerceIn(0, vLines)
        val baseQ = quantMs(f.downPlayMs)
        val step = cellMs().toLong().coerceAtLeast(1L)
        var lastT = if (f.lastFillSlot >= 0L) f.lastFillSlot else baseQ
        if (f.chainCell < 0) {
            val start = cellOf(f.downX)
            addNote(RecordedNote(4, baseQ, baseQ, -1f + start * cellW, 2, f.lineId, true))
            f.chainCell = start
            f.lastFillSlot = baseQ
            lastT = baseQ
        }
        val cur = cellOf(f.curX)
        if (cur == f.chainCell) return
        val dir = if (cur > f.chainCell) 1 else -1
        var c = f.chainCell + dir
        while (true) {
            var q = quantMs(playMs())
            if (q < lastT + step) q = lastT + step       // 至少隔一格，连起来往上升
            addNote(RecordedNote(4, q, q, -1f + c * cellW, 2, f.lineId, true))
            lastT = q
            if (c == cur) break
            c += dir
        }
        f.chainCell = cur
        f.lastFillSlot = lastT
    }

    /** 编辑模式手势：点背景=放置/搓时间；点音符=单选红框；红框内再点=删除全部；拖红框=整体移动；长按=框选 */
    private fun onEditTouch(event: MotionEvent, nowReal: Long): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id] ?: FingerState().also { fingers.put(id, it) }
                val x = event.getX(idx); val y = event.getY(idx)
                f.downRealMs = nowReal
                f.downPlayMs = playMs()
                f.downX = x; f.downY = y
                f.curX = x; f.curY = y
                f.prevY = y
                f.active = true
                f.mode = 0; f.targetNote = -1
                f.groupDrag = false; f.groupOrigin = null; f.willDelete = false; f.moved = false
                f.lineDown = false
                // 多指：第二根手指按下 = 搓时间轴（这样第四次"放长条"时也能拖屏幕）
                var otherScrub = false
                var otherPlacing: FingerState? = null
                for (i in 0 until fingers.size()) {
                    val o = fingers.valueAt(i)
                    if (!o.active || o === f) continue
                    if (o.mode == 3) otherScrub = true else otherPlacing = o
                }
                if (otherScrub) { f.mode = 0; invalidate(); return true }
                if (otherPlacing != null) {
                    otherPlacing.active = false      // 取消第一根手指正在做的放置
                    otherPlacing.mode = 0
                    f.mode = 3
                    f.prevY = y
                    invalidate()
                    return true
                }
                val nowMs = playMs()
                val nearL = nearestLine(x, y, nowMs)
                // 音符默认放在"当前选中的判定线"上（换线只能靠快速切换/右侧抽屉，避免误换）
                f.lineId = activeLineId
                // 判定线长按激活检测（编辑模式）：按在【旋转后的判定线】附近 0.5s 进入编辑
                f.downLineId = nearL.id
                val distLine = distToLineOf(nearL, x, y, nowMs)
                // 长按编辑：只有"碰到判定线本体"才算；开触摸兼容时加宽容错
                val lineHalf = dp(3f)                       // 判定线描边 6dp 的一半
                // 框架 = 判定线上下各加粗 50%；全部 = 判定线左右各再加一倍线宽（= 判定带总宽 3 倍）
                val editBand = lineHalf + when {
                    compatMode >= 2 -> dp(6f)
                    compatMode == 1 -> dp(6f) * 0.5f
                    else -> 0f
                }
                f.lineDown = distLine < editBand
                // 快速切换用稍宽的一条带（否则很难点中）
                f.lineNear = distLine < hit(dp(30f))

                var hit = findNoteAt(x, y, nowMs)
                if (hit < 0 && compatMode >= 1) {
                    // 「框架」和「全部」唯一的区别就在这里：
                    //  框架 = 放大后的框**不能**用来选音符/放音符（只有已选中的红框能抓）；
                    //  全部 = 点未选中音符的框区域也能选中它（因此那里不再放音符）。
                    val fh = findFrameNoteAt(x, y, nowMs, onlySelected = compatMode == 1)
                    if (fh >= 0) hit = fh
                    else if (compatMode == 1 && findFrameNoteAt(x, y, nowMs, onlySelected = false) >= 0) {
                        f.mode = 0
                        invalidate()
                        return true
                    }
                }
                // 图片线/文字线：长按整个图片/文字区域都能进入编辑（线本体太细，按图中间会按不到）
                val extra = if (hit < 0) extraLineAt(x, y, nowMs) else null
                if (extra != null) {
                    f.downLineId = extra.id
                    f.lineDown = true
                    f.lineNear = true
                }
                // 快速切换：点在判定线本体上（附近没有音符）= 切到这条线，这一次不放置音符
                if (hit < 0 && extra != null) {
                    activateLine(extra.id)
                    f.lineId = extra.id
                    selectedSet.clear()
                    f.mode = 6                    // 素材线：不落音符，但保留长按进编辑
                    invalidate()
                    return true
                }
                if (hit < 0 && quickSwitch && f.lineNear) {
                    activateLine(nearL.id)
                    f.lineId = nearL.id
                    f.mode = 5
                    invalidate()
                    return true
                }
                // 点到的音符属于哪条线，就把那条线设为当前线（面板/高亮跟着走）
                if (hit >= 0 && hit in notes.indices && notes[hit].lineId != activeLineId) {
                    activateLine(notes[hit].lineId)
                    f.lineId = activeLineId
                }
                if (hit >= 0) {
                    spawnHitFx(x, y); ensureSounds(); playHitSound(pass)
                    if (hit in selectedSet) {
                        f.willDelete = true          // 点红框内 → 松手未拖动则删除全部
                        f.mode = 2
                        f.targetNote = hit
                        f.groupOrigin = selectedSet.associateWith { notes[it] }.toMutableMap()
                    } else {
                        selectedSet.clear(); selectedSet.add(hit)   // 首次点击：只出红框
                        f.mode = 2
                        f.targetNote = hit
                        f.groupOrigin = selectedSet.associateWith { notes[it] }.toMutableMap()
                    }
                } else {
                    if (selectedSet.isNotEmpty()) {
                        selectedSet.clear()          // 点背景（有红框）→ 只取消，不放置
                        f.mode = 0
                        invalidate()
                        return true
                    }
                    // 点背景（无选中）→ 放置/搓时间（也给一声，反馈才跟得上手）
                    // 图片线/文字线是"素材"，上面放音符没有意义（Phira 里也只能当装饰）→ 直接提示并放弃
                    val target = lines.firstOrNull { it.id == activeLineId }
                    if (target != null && target.kind != LineKind.Normal) {
                        onPlaceBlocked?.invoke(target.kind)
                        f.mode = 6          // 不落音符，但保留"长按这条素材线进编辑"
                        invalidate()
                        return true
                    }
                    ensureSounds(); playHitSound(pass)
                    f.mode = 1
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val f = fingers[id] ?: continue
                    if (!f.active) continue
                    val x = event.getX(i); val y = event.getY(i)
                    f.curX = x; f.curY = y

                    when (f.mode) {
                        4 -> {
                            boxX1 = x; boxY1 = y; selectingBox = true
                            // 框选碰到上/下遮罩也自动滚时间轴（和拖音符一致）
                            updateEdgeScroll(y)
                        }
                        2 -> {
                            if (Math.abs(y - f.downY) > dp(6f) || Math.abs(x - f.downX) > dp(6f)) f.moved = true
                            if (!f.moved) continue
                            // 手指贴到上/下遮罩 → 持续滚动时间轴（越贴边越快）
                            updateEdgeScroll(y)
                            if (selectedSet.size > 1 && !f.groupDrag) {
                                f.groupDrag = true
                                f.groupOrigin = selectedSet.associateWith { notes[it] }.toMutableMap()
                            }
                            if (f.groupDrag) {
                                val o = f.groupOrigin ?: continue
                                val dT: Long
                                val dX: Float
                                if (smoothMove) {
                                    dT = ((f.downY - y) / scrubPxPerMs).toLong()
                                    dX = (x - f.downX) / width.toFloat() * 2f
                                } else {
                                    val gridPx2 = (cellMs().toFloat() * pxPerMs).coerceAtLeast(0.01f)
                                    val cellPx = width / vLines.toFloat()
                                    val dCells = Math.round(f.downY / gridPx2) - Math.round(y / gridPx2)
                                    dT = dCells * cellMs().toLong()
                                    dX = (Math.round(x / cellPx) - Math.round(f.downX / cellPx)) * (2f / vLines)
                                }
                                for ((idx0, n0) in o) {
                                    if (idx0 !in notes.indices) continue
                                    val ns = (n0.startMs + dT + f.extraDt).coerceAtLeast(0L)
                                    val dur = (n0.endMs - n0.startMs).coerceAtLeast(0L)
                                    setNote(idx0, n0.copy(startMs = ns, endMs = ns + dur, posX = (n0.posX + dX).coerceIn(-1f, 1f)))
                                }
                            } else {
                                val idx0 = f.targetNote
                                if (idx0 in notes.indices) {
                                    val n = notes[idx0]
                                    val ln = lineOf(n)
                                    val dur = (n.endMs - n.startMs).coerceAtLeast(0L)
                                    // 屏幕坐标 → 判定线局部坐标（先平移线中心，再反向旋转）
                                    lineFrameOf(ln, playMs(), scratchPose)
                                    val lcX = scratchPose[0]
                                    val lcY = scratchPose[1]
                                    val ang = Math.toRadians(scratchPose[2].toDouble())
                                    val dx = x - lcX; val dy = y - lcY
                                    val xl = (dx * Math.cos(ang) - dy * Math.sin(ang)).toFloat()
                                    val yl = (dx * Math.sin(ang) + dy * Math.cos(ang)).toFloat()
                                    // 反面音符的 y 是镜像的，换回时间要再乘回符号
                                    val yForTime = if (n.above) yl else -yl
                                    // 显示时横向位置乘过 noteXFactor（posControl/incline），换回 posX 要除回去
                                    val xf = noteXFactor(ln, n, playMs()).coerceAtLeast(1e-6f)
                                    val pos = if (smoothMove) rawPosX(xl / xf + width / 2f) else quantizeX(xl / xf + width / 2f)
                                    // 变速线：按流速积分求逆，而不是"距离 ÷ 当前瞬时流速"
                                    val rawT = timeForDistPx(ln, n, playMs(), yForTime)
                                    val t = if (smoothMove) rawT else quantMs(rawT)
                                    setNote(idx0, n.copy(startMs = t, endMs = t + dur, posX = pos))
                                }
                            }
                        }
                        1, 5 -> {
                            // 1=放置背景；5=已用快速切换换了线。上下拖过 8dp 都当搓时间
                            val canScrub = if (f.mode == 5) true else pass != 4
                            if (canScrub && Math.abs(y - f.downY) > dp(8f)) { f.mode = 3; f.prevY = y }
                        }
                        3 -> {
                            val dy = y - f.prevY
                            f.prevY = y
                            val dt = Math.round(dy / scrubPxPerMs).toInt()
                            if (dt != 0) addEditSeek(dt)
                        }
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id]
                if (f != null && f.active) {
                    f.active = false
                    if (f.mode == 2 || f.mode == 4) edgeScrollActive = false
                    if (f.mode == 4) finishBoxSelect()
                    else if (f.mode == 2) {
                        if (!f.moved) {
                            // 音符列表模式：点红框里的音符不删除，改为弹信息面板（改的是全部被框住的音符）
                            if (noteInfoMode) {
                                val anchor = selectionAnchor()
                                val cb = onNoteInfo
                                if (anchor != null && cb != null) cb(anchor[0], anchor[1])
                            } else if (f.willDelete) deleteSelected()
                        }
                    }
                    else if (f.mode == 3) flushEditSeek()
                    else if (f.mode == 1) {
                        // 放置位置按"按下点所属判定线"换算（点背景前已经取过最近线）
                        val ln = lines.firstOrNull { it.id == f.lineId }
                            ?: nearestLine(f.downX, f.downY, playMs())
                        lineFrameOf(ln, playMs(), scratchPose)
                        val lcX = scratchPose[0]
                        val lcY = scratchPose[1]
                        val C = Math.cos(Math.toRadians(scratchPose[2].toDouble())).toFloat()
                        val S = Math.sin(Math.toRadians(scratchPose[2].toDouble())).toFloat()
                        val dragging = Math.abs(f.curY - f.downY) >= dp(6f)
                        // 显示时横向位置乘过 noteXFactor（posControl/incline）：换回 posX 前先除掉
                        val placedType = if (pass == 4) 2 else passType(pass)
                        val xfAt = { tMs: Long ->
                            noteXFactor(ln, RecordedNote(placedType, tMs, tMs, 0f, pass, ln.id, true), playMs()).coerceAtLeast(1e-6f)
                        }
                        // 反转开关关着 → 一律正面（above=true）；
                        // 开着 → 落在线上方是正面、线下方是反面。
                        // ⚠️ 这里以前写成 `reverseEnabled && yl < 0`，开关一关就恒为 false，
                        //    等于把所有音符都当成了"反面"，于是全都往上飘（bug）。
                        val probeX = if (dragging) (f.downX + f.curX) / 2f else f.curX
                        val probeY = if (dragging) (f.downY + f.curY) / 2f else f.curY
                        val ylAt = { xx: Float, yy: Float -> (xx - lcX) * S + (yy - lcY) * C }
                        val aboveForPlace = if (!reverseEnabled) true else ylAt(probeX, probeY) < 0f
                        // 正面：线上方 = 未来 → y 为负；反面：线下方才是未来 → 符号相反
                        val sign = if (aboveForPlace) 1f else -1f
                        // 屏幕点 → 判定线局部坐标
                        val xlAt = { xx: Float, yy: Float ->
                            val dx = xx - lcX
                            val dy = yy - lcY
                            dx * C - dy * S
                        }
                        val yTime = { yy: Float ->
                            // 屏幕点 → 判定线局部坐标的 y（xl 不影响 y，但旋转后要一起算）
                            val dx = f.downX - lcX
                            val dy = yy - lcY
                            val yl = dx * S + dy * C
                            // 变速线必须对流速积分求逆（现在挖的这个坑就是按瞬时流速除法算的）
                            val probe = RecordedNote(placedType, 0L, 0L, 0f, pass, ln.id, aboveForPlace)
                            val raw = timeForDistPx(ln, probe, playMs(), sign * yl)
                            if (smoothMove) raw else quantMs(raw)
                        }
                        if (pass == 4 && dragging) {
                            val t1 = yTime(f.downY); val t2 = yTime(f.curY)
                            val start = minOf(t1, t2)
                            val end = maxOf(t1, t2).coerceAtLeast(start + cellMs().toLong())
                            // 用判定线局部 x 吸附，判定线旋转后落点仍然贴手
                            val pos = posXFromLocal(xlAt(f.downX, f.downY) / xfAt(start))
                            addNote(RecordedNote(2, start, end, pos, 4, ln.id, aboveForPlace))
                            spawnHitFx(f.curX, f.curY)
                        } else {
                            val t = yTime(f.curY)
                            val pos = posXFromLocal(xlAt(f.curX, f.curY) / xfAt(t))
                            if (pass == 4) addNote(RecordedNote(2, t, t + (cellMs() * 2).toLong(), pos, 4, ln.id, aboveForPlace))
                            else addNote(RecordedNote(passType(pass), t, t, pos, pass, ln.id, aboveForPlace))
                            spawnHitFx(f.curX, f.curY)
                        }
                    }
                    f.mode = 0; f.groupDrag = false; f.groupOrigin = null
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    fingers.clear()
                    selectingBox = false
                }
                invalidate()
                return true
            }
        }
        return true
    }

    /** 长按检测：判定线长按 0.5s → 判定线编辑；普通长按 0.5s → 框选膨胀 */
    private fun checkBoxLongPress() {
        val nowR = SystemClock.elapsedRealtime()
        for (i in 0 until fingers.size()) {
            val f = fingers.valueAt(i)
            if (!f.active || f.mode == 0 || f.mode == 3 || f.mode == 4) continue
            val moved = Math.abs(f.curY - f.downY) > dp(6f) || Math.abs(f.curX - f.downX) > dp(6f)
            if (moved || nowR - f.downRealMs < 500) continue
            if (f.lineDown) {
                // 长按判定线本体 = 进编辑；和抽屉里长按判定线框走同一个入口（行为必须一致）
                focusLineForEdit(f.downLineId)
                f.lineDown = false; f.mode = 0
                continue
            }
            boxActive = true
            boxStartedAt = nowR
            boxX0 = f.downX; boxY0 = f.downY
            boxX1 = f.downX; boxY1 = f.downY
            selectedSet.clear()
            selectingBox = false
            f.mode = 4
        }
    }

    /** 框选结束：框内音符全部进选中集 */
    private fun finishBoxSelect() {
        val now = playMs()
        val x0 = minOf(boxX0, boxX1); val x1 = maxOf(boxX0, boxX1)
        val y0 = minOf(boxY0, boxY1); val y1 = maxOf(boxY0, boxY1)
        // 「全部」模式：判定区 = 音符外面那圈红框，框碰到红框就算选中
        val frameBox = compatMode >= 2
        val fw = (width.toFloat().coerceAtLeast(1f) / vLines.coerceAtLeast(1)) * 0.72f
        val fh = dp(18f)
        selectedSet.clear()
        for (l in lines) {
            // 线高亮关着时，别的判定线是"看得到点不到"，框选也一样只框当前那条线
            if (!highlightAllLines && l.id != activeLineId) continue
            lineFrameOf(l, now, scratchPose)
            val lcX = scratchPose[0]
            val lcY = scratchPose[1]
            val a = Math.toRadians(-scratchPose[2].toDouble())
            val cosA = Math.cos(a).toFloat()
            val sinA = Math.sin(a).toFloat()
            val halfW = width / 2f
            for (i in indexesForLine(l.id)) {
                val n = notes[i]
                val xl = ((n.posX + 1f) / 2f * width - halfW) * noteXFactor(l, n, now)
                val yl = noteLocalY(l, n, n.startMs, now)
                val nx = lcX + xl * cosA - yl * sinA
                val ny = lcY + xl * sinA + yl * cosA
                if (!frameBox) {
                    if (nx >= x0 && nx <= x1 && ny >= y0 && ny <= y1) selectedSet.add(i)
                    continue
                }
                // 红框四角（和 findFrameNoteAt 的判定框一样大）在屏幕上的包围盒
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                    val ox = sx * fw; val oy = sy * fh
                    val cxp = nx + ox * cosA - oy * sinA
                    val cyp = ny + ox * sinA + oy * cosA
                    if (cxp < minX) minX = cxp; if (cxp > maxX) maxX = cxp
                    if (cyp < minY) minY = cyp; if (cyp > maxY) maxY = cyp
                }
                if (maxX >= x0 && minX <= x1 && maxY >= y0 && minY <= y1) selectedSet.add(i)
            }
        }
        boxActive = false
        selectingBox = false
    }

    /** 删除所有被框住（红框）的音符 */
    private fun deleteSelected() {
        removeNotesAt(selectedSet)
        selectedSet.clear()
    }

    // ---------------------------------------------------------------- 音符信息面板（音符列表模式）
    /** 被框住的音符（按列表顺序；没有选中返回空） */
    fun selectedNotes(): List<RecordedNote> =
        selectedSet.sorted().mapNotNull { notes.getOrNull(it) }

    /** 被框住的数量 */
    fun selectedCount(): Int = selectedSet.count { it in notes.indices }

    /** 选中音符的屏幕中心（本 View 坐标）；没有选中返回 null。面板靠它决定"弹在音符右侧" */
    fun selectionAnchor(): FloatArray? {
        if (selectedSet.isEmpty()) return null
        val now = playMs()
        var sx = 0f; var sy = 0f; var cnt = 0
        for (i in selectedSet) {
            val n = notes.getOrNull(i) ?: continue
            noteScreenPosInto(n, now, scratchPos)
            sx += scratchPos[0]; sy += scratchPos[1]; cnt++
        }
        if (cnt == 0) return null
        return floatArrayOf(sx / cnt, sy / cnt)
    }

    /**
     * 面板改音符：对**所有被框住的音符**套用 [edit]，整批算一个撤销步。
     * 返回真正被改动的个数（edit 返回原值 = 没变，不计）。
     */
    fun applyNoteInfo(edit: (RecordedNote) -> RecordedNote): Int {
        if (selectedSet.isEmpty()) return 0
        var changed = 0
        beginTx()
        try {
            for (i in selectedSet.toIntArray()) {
                val old = notes.getOrNull(i) ?: continue
                val n = edit(old)
                if (n == old) continue
                setNote(i, n)
                changed++
            }
        } finally {
            endTx()
        }
        invalidate()
        return changed
    }

    /** 面板里的「删除」：删掉所有被框住的音符（一次撤销步） */
    fun deleteSelectionByPanel(): Int {
        if (selectedSet.isEmpty()) return 0
        val n = selectedSet.count { it in notes.indices }
        beginTx()
        try { deleteSelected() } finally { endTx() }
        invalidate()
        return n
    }

    /** 面板里的「X 镜像」：所有被框住的音符沿判定线中心左右翻（phiedit 的 Alt+A 同款） */
    fun mirrorSelection(): Int = applyNoteInfo { it.copy(posX = -it.posX.coerceIn(-1f, 1f)) }

    // ---------------------------------------------------------------- 判定线编辑
    /** 判定线编辑：拖中心方框=移动，拖箭头=旋转，点小方块=打关键帧，点左侧圆点=跳转/长按红框/再点删除，点背景=退出 */
    private fun onLineEditTouch(event: MotionEvent, nowReal: Long): Boolean {
        val now = playMs()
        val l = activeLine
        val c = lineCenterOf(l, now)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id] ?: FingerState().also { fingers.put(id, it) }
                val x = event.getX(idx); val y = event.getY(idx)
                f.downRealMs = nowReal
                f.downX = x; f.downY = y
                f.curX = x; f.curY = y
                f.active = true

                // 1) 中心方框 → 移动（**不受触摸兼容放大**，否则容易误拖整条线）
                if (Math.abs(x - c[0]) < dp(36f) && Math.abs(y - c[1]) < dp(36f)) {
                    lineEditHand = 1; return true
                }
                // 2) 旋转箭头（左上/右下）—— 判定线上的按钮不参与触摸兼容
                val arrowA = Math.hypot((x - (c[0] - dp(40f))).toDouble(), (y - (c[1] - dp(40f))).toDouble())
                val arrowB = Math.hypot((x - (c[0] + dp(40f))).toDouble(), (y - (c[1] + dp(40f))).toDouble())
                if (arrowA < dp(34f) || arrowB < dp(34f)) {
                    lineEditHand = 2
                    lineEditStartRot = Math.toDegrees(Math.atan2((y - c[1]).toDouble(), (x - c[0]).toDouble())).toFloat()
                    lineEditStartAng = editTargetPose()[2]
                    return true
                }
                // 3) 上/下/左/右的缩放箭头（同样不参与触摸兼容；只有这 4 个外扩到 96dp）
                val scU = Math.hypot((x - c[0]).toDouble(), (y - (c[1] - dp(96f))).toDouble())
                val scD = Math.hypot((x - c[0]).toDouble(), (y - (c[1] + dp(96f))).toDouble())
                val scL = Math.hypot((x - (c[0] - dp(96f))).toDouble(), (y - c[1]).toDouble())
                val scR = Math.hypot((x - (c[0] + dp(96f))).toDouble(), (y - c[1]).toDouble())
                if (scU < dp(26f) || scD < dp(26f) || scL < dp(26f) || scR < dp(26f)) {
                    lineEditHand = 3
                    lineEditStartScale = lineScale
                    lineEditStartDist = Math.hypot((x - c[0]).toDouble(), (y - c[1]).toDouble())
                        .toFloat().coerceAtLeast(dp(1f))
                    return true
                }
                // 4) 关键帧小方块（左下/右上）→ 打关键帧
                val d1 = Math.hypot((x - (c[0] - dp(40f))).toDouble(), (y - (c[1] + dp(40f))).toDouble())
                val d2 = Math.hypot((x - (c[0] + dp(40f))).toDouble(), (y - (c[1] - dp(40f))).toDouble())
                if (d1 < dp(26f) || d2 < dp(26f)) {
                    addKeyframe()
                    spawnHitFx(x, y)
                    invalidate()
                    return true
                }
                // 4) 左侧关键帧圆点：点一下=选中/取消选中（面板跟着改这一帧的姿态和流速）；长按=删除
                if (x < hit(dp(40f))) {
                    val kfs = l.sortedKeyframes()
                    val hitR = hit(dp(26f))
                    for (k in kfs) {
                        val ky = c[1] - (k.timeMs - now) * pxPerMs
                        if (ky < dp(30f) || ky > height - dp(130f)) continue
                        if (Math.abs(y - ky) < hitR) {
                            if (selectedKfTime == k.timeMs) {
                                // 已经选中（红框）→ 再短按一下 = 删掉这一帧
                                linesMutate { l.removeKeyframeAt(k.timeMs) }
                                selectedKfTime = -1
                                editKfTime = -1
                                onLinePoseChanged?.invoke()
                                invalidate()
                                return true
                            }
                            // 第一次短按 = 选中（红框），并把时间跳到这一帧
                            selectedKfTime = k.timeMs
                            editKfTime = -1L
                            onSeekAbs?.invoke(k.timeMs.toInt())
                            onLinePoseChanged?.invoke()
                            invalidate()
                            return true
                        }
                    }
                    // 没点到圆点，但按在左侧关键帧条上 → 拖动搓时间轴（触摸兼容会放大这条判定区）
                    f.mode = 3
                    f.prevY = y
                    return true
                }
                // 5) 命中判定线任意位置 → 打关键帧（任何时间点都能打，无需先有帧）
                if (distToLineOf(l, x, y, playMs()) < dp(30f)) {
                    addKeyframe()
                    spawnHitFx(x, y)
                    invalidate()
                    return true
                }
                // 6) 点远处背景 → 退出判定线编辑（应用全局姿态）
                lineEditActive = false
                lineEditHand = 0
                selectedKfTime = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val f = fingers[id] ?: continue
                    if (!f.active) continue
                    val x = event.getX(i); val y = event.getY(i)
                    f.curX = x; f.curY = y
                    if (f.mode == 3) {
                        // 左侧关键帧条上拖动 = 搓时间轴
                        val dy = y - f.prevY
                        f.prevY = y
                        val dt = Math.round(dy / scrubPxPerMs).toInt()
                        if (dt != 0) addEditSeek(dt)
                        continue
                    }
                    when (lineEditHand) {
                        1 -> linesMutate {
                            val nx = ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                            val ny = ((y - judgeLineY) / (height / 2f)).coerceIn(-1f, 1f)
                            if (kfEditTime >= 0) {
                                val cur = editTargetPose()
                                l.setKeyframePose(kfEditTime, nx, ny, cur[2])
                            } else {
                                l.moveX = nx; l.moveY = ny
                            }
                        }
                        2 -> {
                            val ang = Math.toDegrees(Math.atan2((y - c[1]).toDouble(), (x - c[0]).toDouble())).toFloat()
                            linesMutate {
                                val nr = lineEditStartAng - (ang - lineEditStartRot)
                                if (kfEditTime >= 0) {
                                    val cur = editTargetPose()
                                    l.setKeyframePose(kfEditTime, cur[0], cur[1], nr)
                                } else l.rotate = nr
                            }
                        }
                        3 -> {
                            // 离中心越远越大：从按下时的比例开始等比缩放
                            val dist = Math.hypot((x - c[0]).toDouble(), (y - c[1]).toDouble())
                                .toFloat().coerceAtLeast(dp(1f))
                            val s = (lineEditStartScale * (dist / lineEditStartDist))
                                .coerceIn(0.05f, 20f)
                            linesMutate {
                                // 手拖只动**当前那条**（批量缩放只靠右上角输入框，用户点名）
                                writeLineScale(l, s)
                            }
                        }
                    }
                    onLinePoseChanged?.invoke()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id]
                if (f != null && f.active) {
                    if (f.mode == 3) flushEditSeek()     // 搓完把时间写回播放器
                    f.mode = 0
                    f.active = false
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    fingers.clear()
                    lineEditHand = 0
                }
                invalidate()
                return true
            }
        }
        return true
    }

    /** 打一个关键帧（当前时间 + 当前姿态 + 此刻生效的流速/缩放/透明度） */
    private fun addKeyframe() {
        val t = playMs()
        val l = activeLine
        var lx = 0f
        var ly = 0f
        var lr = 0f
        var lsp = 1f
        linesMutate {
            val cur = editTargetPose()
            // 新帧默认沿用"此刻真正生效的流速"（关键帧插值后的值），要变速就选中它再改流速输入框
            val sp = l.speedAt(t)
            // 缩放和透明度按"画面上此刻看到的值"钉住：正在编辑某一帧时就是那一帧的值，
            // 否则取时间轴上的值。这样打帧不会把正在进行的缩放/淡入淡出打断。
            scaleOf(l, t, scaleScratch)
            lx = cur[0]; ly = cur[1]; lr = cur[2]; lsp = sp
            l.putKeyframe(t, cur[0], cur[1], cur[2], sp, scaleScratch[0], scaleScratch[1], alphaShownOf(l, t))
            editKfTime = t          // 打完帧直接改输入框 = 改这一帧
            // 打了帧之后，全局姿态也跟随这帧（第一个帧时尤其重要，否则拖线看起来没反应）
            l.moveX = cur[0]; l.moveY = cur[1]; l.rotate = cur[2]
        }
        // 打完帧**不自动选中**：想要红圈就点一下它，再点一下才删除
        DebugLog.add(
            "keyframe",
            "add line=${l.id} t=$t x=${DebugLog.f3(lx)} y=${DebugLog.f3(ly)} " +
                "r=${DebugLog.f3(lr)} sp=${DebugLog.f3(lsp)} 共 ${l.keyframes.size} 帧"
        )
        onLinePoseChanged?.invoke()
    }

    // ---------------------------------------------------------------- Autoplay 判定与 HUD
    /**
     * 双押标记（Phira multiple_hint）：同一时刻（半格以内）有两个以上音符就都算双押。
     * 假音符不参与。
     *
     * 返回的是**每帧复用的布尔数组**（不 new 集合、不装箱下标）；没有双押时返回一个
     * 全 false 的数组，调用方用 `dh[i]` 查即可（越界就是没有）。
     */
    private var dhMark = BooleanArray(0)
    private var dhLive = IntArray(0)
    /** 不需要双押标记时用的空数组（长度 0 → dh[i] 永远越界 = 没有任何一个音符是双押） */
    private val emptyDh = BooleanArray(0)

    private fun doubleHints(now: Long): BooleanArray {
        val n = notes.size
        if (dhMark.size != n) {
            dhMark = BooleanArray(n)
            dhLive = IntArray(n)
        }
        java.util.Arrays.fill(dhMark, false)
        if (n < 2 || pxPerMs <= 0f) return dhMark
        val span = (height / pxPerMs).toLong().coerceAtLeast(1000L) + 1000L
        NoteAlgos.markDoubleHints(
            sortedIdx = sortedPlayIndex(),
            fromMs = now - span,
            toMs = now + span,
            win = (cellMs() * 0.5).toLong(),
            timeOf = { notes[it].startMs },
            isJudged = { judgedAt(it) || notes[it].fake },
            mark = dhMark,
            liveBuf = dhLive
        )
        return dhMark
    }

    /** 音符下标按 startMs 升序（打歌期间音符不变，建一次就够；变了自动重建） */
    private fun sortedPlayIndex(): IntArray {
        if (playSortedIdx.size != notes.size) {
            playSortedIdx = notes.indices.sortedBy { notes[it].startMs }.toIntArray()
        }
        return playSortedIdx
    }

    /** Autoplay：音符到判定线自动 PERFECT + 特效 + 音效。
     *  只在「落线后 AUTO_HIT_WINDOW_MS 内」判定；太旧音符静默跳过；回拖进度条自动重置判定。
     *  假音符（fake）只显示，不判定、不出声。 */
    private fun tickAutoPlay(now: Long) {
        if (playingProvider?.invoke() != true) { lastJudgeNow = now; return }
        if (now + 300 < lastJudgeNow) {
            resetJudged()
            combo = 0
            noiseJamCount = 0
            noiseRuntime.clear()
            hitCount = seedPassedNotes(now)      // 回拖进度条：前面的音符同样算进分数
        }
        // ★ Autoplay 不因为噪域漏判：Phigros 的判定是**垂直（时间）判定** —— 音符只看
        // 「有没有在正确时间落到判定线」，落点横向在哪、噪域盖住音符本身都不影响命中，
        // 真人可以把手落在噪域外面照样打中它。Autoplay 的"虚拟手指"同理可以选一个噪域外的落点，
        // 所以这里不能用真实手指的断触状态来清零连击（以前只要有手指被掐着就整段时间不判，
        // 音符从噪域内部经过时 Autoplay 会莫名断连）。噪域仍照常画、真实手指仍会亮红圈/播音效。
        for (i in notes.indices) {
            if (judgedAt(i)) continue
            val n = notes[i]
            // 假音符：不判定、不计分，但**要飞到判定线才消失**
            // （Phira: `fake && res.time >= self.time` 才裁掉；原来这里一进打歌就全部标掉，一开场所有假音符就没了）
            // 长条按尾判：整条飞到尾才消失（否则头一过线整条就没了，看着像突然消失）
            if (n.fake) { if (now >= (if (n.type == 2) n.endMs else n.startMs)) markJudged(i); continue }
            // 判定时间 = 音符时间。以前按列位置错峰最多 +60ms，
            // 会导致"音符已经贴到判定线了但特效/音效还没响"，现在不做错峰。
            val judgeAt = n.startMs
            val age = now - judgeAt
            if (age < 0) continue
            if (age > AUTO_HIT_WINDOW_MS) { markJudged(i); continue }       // 已过去太久：只标记跳过
            if (hitCount <= 200) {
                DebugLog.add("judge", "#$i type=${n.type} t=${n.startMs} now=$now age=$age line=${n.lineId} above=${if (n.above) 1 else 0}")
            }
            judgeHit(i, now)
        }
        lastJudgeNow = now
    }

    /** 命中一个音符：计分 + 打点特效 + 音效（长条再登记持续特效）。自动/手动判定共用 */
    private fun judgeHit(i: Int, now: Long) {
        val n = notes[i]
        markJudged(i)
        hitCount++
        combo++
        // 特效打在判定线上（音符沿线位置、线本身），避免音符过线后特效偏下
        val l = lineOf(n)
        lineFrameOf(l, now, scratchPose)
        val lcX = scratchPose[0]
        val lcY = scratchPose[1]
        val rotA = Math.toRadians(-scratchPose[2].toDouble())
        val cosA = Math.cos(rotA).toFloat()
        val sinA = Math.sin(rotA).toFloat()
        val xl = ((n.posX + 1f) / 2f * width - width / 2f) * noteXFactor(l, n, now)
        spawnHitFx(lcX + xl * cosA, lcY + xl * sinA)
        playHitSound(n.type)
        // 长条：登记持续特效（直到尾部离开判定线）；音效只此一次
        if (n.type == 2) holdFxList.add(HoldFx(i, n.endMs))
    }

    /** 左上角暂停按钮上一次按下的时刻（双击防误触用） */
    private var lastPauseTapReal = 0L

    /** 左上角暂停按钮的命中区（与 drawJudgeOverlay 画的图标对齐，稍微放宽好按） */
    private fun inPauseButton(x: Float, y: Float): Boolean =
        x >= dp(4f) && x <= dp(44f) && y >= dp(16f) && y <= dp(56f)

    /** Phira 风格 HUD：右上角 7 位分数、上方中间 Combo（≥3）、顶端 Bar 进度条 */
    private fun drawJudgeOverlay(canvas: Canvas, now: Long, w: Float, h: Float) {
        // 左上角暂停键（Phira 风格）：播放中 = 两根竖条，暂停 = 播放三角
        val bx = dp(16f); val by = dp(26f)
        val bw = dp(5f); val bh = dp(20f); val gap = dp(6f)
        judgePaint.color = intColor(0xE6FFFFFF)
        if (playingProvider?.invoke() == true) {
            canvas.drawRoundRect(bx, by, bx + bw, by + bh, dp(2f), dp(2f), judgePaint)
            canvas.drawRoundRect(bx + bw + gap, by, bx + 2 * bw + gap, by + bh, dp(2f), dp(2f), judgePaint)
        } else {
            pathTmp.reset()
            pathTmp.moveTo(bx, by)
            pathTmp.lineTo(bx, by + bh)
            pathTmp.lineTo(bx + dp(15f), by + bh / 2f)
            pathTmp.close()
            canvas.drawPath(pathTmp, judgePaint)
        }
        judgePaint.alpha = 255

        if (trackLengthMs > 0) {
            val p = (now.toFloat() / trackLengthMs).coerceIn(0f, 1f)
            val barH = dp(5f)
            val barY = dp(10f)
            judgePaint.color = intColor(0x99FFFFFF)
            canvas.drawRect(0f, barY, w * p, barY + barH, judgePaint)
            judgePaint.color = Color.WHITE
            canvas.drawRect(w * p - dp(1f), barY - dp(1.5f), w * p + dp(1f), barY + barH + dp(1.5f), judgePaint)
            judgePaint.alpha = 255
        }

        if (hudTotal < 0) hudTotal = notes.count { !it.fake }
        val total = hudTotal
        val score = if (total == 0) 0 else {
            Math.round(1000000.0 * hitCount / total).toInt().coerceIn(0, 1000000)
        }
        if (score != hudScoreValue) {
            hudScoreValue = score
            hudScoreText = score.toString().padStart(7, '0')
        }
        judgePaint.color = Color.WHITE
        judgePaint.textSize = dp(32f)
        judgePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(hudScoreText, w - dp(8f), dp(40f), judgePaint)
        judgePaint.color = intColor(0xAAFFFFFF)
        judgePaint.textSize = dp(14f)
        canvas.drawText("SCORE", w - dp(8f), dp(60f), judgePaint)
        judgePaint.alpha = 255

        if (combo >= 3) {
            if (combo != hudComboValue) {
                hudComboValue = combo
                hudComboText = combo.toString()
            }
            // COMBO 挪到最上面，和分数同一行：数字基线与分数完全相同（dp(40)），字号也一致
            judgePaint.textAlign = Paint.Align.CENTER
            judgePaint.color = Color.WHITE
            judgePaint.textSize = dp(32f)
            canvas.drawText(hudComboText, w / 2f, dp(40f), judgePaint)
            judgePaint.color = intColor(0xAAFFFFFF)
            judgePaint.textSize = dp(14f)
            canvas.drawText("COMBO", w / 2f, dp(60f), judgePaint)
        }
        judgePaint.alpha = 255
    }

    // ---------------------------------------------------------------- 草稿
    /** 序列化草稿（DraftCodec：StringBuilder 逐行写，1 万音符 ~10ms，org.json 要上百毫秒） */
    /** 剪贴板不写进草稿：退出编辑后一律清空（用户要求） */
    fun saveDraft(): String =
        DraftCodec.encode(lines, notes, emptyList(), noiseAreas, declaredLayers, declaredNoiseLayers)

    /** 联机同步用：草稿 + 剪贴板一起序列化（本地草稿仍然不存剪贴板） */
    fun draftWithClip(): String =
        DraftCodec.encode(lines, notes, clipEntries, noiseAreas, declaredLayers, declaredNoiseLayers)

    /** 加载草稿（文本 v2/v3 或旧的 JSON 格式都能读） */
    fun loadDraft(text: String) {
        val d = DraftCodec.decode(text) ?: return
        applyDraft(d)
    }

    /** 应用已解析好的草稿（解析可以在后台线程做，这里必须在 UI 线程） */
    fun applyDraft(d: DraftCodec.Draft) {
        // 对面（或本地）更新谱面时，已经打开的判定线抽屉 / 剪贴板抽屉**不要被关掉**（用户要求）。
        // 先把界面状态记下来，重建完再按"这条线还在不在"恢复。
        val keepPanel = panelOpen
        val keepPanelScroll = panelScroll
        val keepClip = clipOpen
        val keepClipScroll = clipScroll
        val keepEdit = lineEditActive
        val keepKf = selectedKfTime
        val keepActive = activeLineId
        val keepFrames = ArrayList(selectedLines)

        // v2 草稿没存判定线位置：沿用进页面时设置页给的那条线的位置
        val fallbackPct = activeLine.posPct
        lines.clear()
        for (l in d.lines) {
            if (l.posPct == DraftCodec.POS_UNSET) l.posPct = fallbackPct
            lines.add(l)
        }
        if (lines.isEmpty()) lines.add(Line(0))
        // 图片线贴图路径：草稿只存文件名，按解析目录重新找（导入目录 / lineimg）
        resolveLineTextures()
        activeLineId = if (lines.any { it.id == keepActive }) keepActive else lines[0].id
        nextLineId = (lines.maxOfOrNull { it.id } ?: 0) + 1
        notes.clear()
        notes.addAll(d.notes)
        // 空图层声明（左滑新建按钮加出来的层）
        declaredLayers.clear()
        declaredLayers.addAll(d.declaredLayers)
        // 噪域：跟着草稿走；换了数据先退出编辑（下标会失效）
        if (noiseEditActive) stopNoiseEdit()
        noiseExpandedLayers.clear()
        noiseSelectedLayers.clear()
        noiseLayerOrder.clear()
        declaredNoiseLayers.clear()
        noiseAreas.clear()
        noiseAreas.addAll(d.noise)
        declaredNoiseLayers.addAll(d.declaredNoiseLayers)
        ensureNoiseLayerOrder()
        invalidateNoiseRows()
        noiseIndex.invalidate()          // 噪域换了：时间索引必须重建
        noiseRuntime = NoiseRuntime(noiseAreas)
        if (d.clip.isNotEmpty()) loadClipEntries(d.clip)   // 联机同步的剪贴板
        bumpNoteIndex()
        // 悬空 lineId 归到第一条线
        val ids = lines.map { it.id }.toHashSet()
        val firstId = lines[0].id
        for (i in notes.indices) {
            if (notes[i].lineId !in ids) notes[i] = notes[i].copy(lineId = firstId)
        }
        selectedSet.clear()
        // 框选（红框）：只留还存在的线
        selectedLines.clear()
        for (id in keepFrames) if (lines.any { it.id == id }) selectedLines.add(id)
        // 判定线编辑态：活动线还在就保持，关键帧没了就退回整条线
        lineEditActive = keepEdit && lines.any { it.id == activeLineId }
        selectedKfTime = if (lineEditActive) keepKf else -1
        // 抽屉恢复
        panelOpen = keepPanel
        // 抽屉没开就不用算每行的音符数。
        // 开的时候也只扫一遍音符（原来 `lines × notes.count{}` 是大谱面上的乘法）
        if (keepPanel) {
            panelNoteCounts = HashMap<Int, Int>(lines.size * 2)
            for (n in notes) panelNoteCounts[n.lineId] = (panelNoteCounts[n.lineId] ?: 0) + 1
        }
        panelScroll = keepPanelScroll.coerceIn(0f, panelMaxScroll())
        clipOpen = keepClip
        clipScroll = keepClipScroll.coerceIn(0f, clipMaxScroll())
        resetJudged()
        playSortedIdx = IntArray(0)
        undoStack.clear()
        redoStack.clear()
        tx = null
        onLinesChanged?.invoke()
        onLinePoseChanged?.invoke()
        invalidate()
    }
}
