package com.piezmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/**
 * 一个音符（录制产物）。
 * type: 1=Tap(click) 2=Hold 3=Flick 4=Drag —— 与 RPE / Phira 一致
 * pass: 1=第一阶段(drag/hold) 2=第二阶段(click) 3=第三阶段(flick)
 * lineId: 属于哪条判定线
 * above: true=正面（从判定线上方落下）false=反面（RPE above=0，从另一侧落下、贴图上下翻转）
 * fake: 假音符（RPE isFake=1，只显示不判定）
 */
data class RecordedNote(
    val type: Int,
    val startMs: Long,
    val endMs: Long,
    val posX: Float,          // 归一化 [-1, 1]（对应 RPE positionX/675）
    val pass: Int = 1,
    val lineId: Int = 0,      // 属于哪条判定线
    val above: Boolean = true,  // false = 反面音符（RPE above=0，绕判定线转 180°）
    val fake: Boolean = false   // 假音符（RPE isFake=1，只显示不判定）
)

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
        /** 判定线最多几条（再多屏幕上也看不清） */
        const val MAX_LINES = 32
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
    /** BPM 偏移（ms）：正数 = 谱面整体比音乐延后（和 Phiedit 的"bpm偏移"一致）。
     *  时间网格原点、吸附格点、导出时的音符时间都按它平移。 */
    var bpmOffsetMs: Int = 0
    var hLines: Int = 4          // 横线数：每拍分几格（时间量化）
    var vLines: Int = 8          // 竖线数：位置量化
    var trackLengthMs: Long = 0  // 音频总时长（来自播放页），用于打歌 HUD 进度条

    // ----- 判定线（多条）-----
    /** 全部判定线（至少一条，id 唯一） */
    val lines: MutableList<Line> = mutableListOf(Line(0).also { it.name = "line" })
    private var nextLineId = 1
    private var activeLineId = 0

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
    private var panelDownRow = -1
    private var panelDownY = 0f
    private var panelLastY = 0f
    /** 把手按下但还没打开抽屉（判断是"点"还是"上下滑"） */
    private var barPressed = false
    private var barDownY = 0f
    /** 打开抽屉时算一次各线的音符数（抽屉打开期间不会改谱，够用） */
    private var panelNoteCounts = IntArray(0)
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
                if (!value) selectedKfTime = -1     // 退出判定线编辑 = 取消关键帧选中
                field = value; onLineEditChanged?.invoke(value); invalidate()
            }
        }
    var onLineEditChanged: ((Boolean) -> Unit)? = null      // 编辑模式开/关（Activity 显隐输入面板）
    var onLinePoseChanged: (() -> Unit)? = null             // 姿态变化（Activity 刷新输入框）
    /** 长按判定线进编辑时需要暂停播放（Activity 绑：暂停 + 按钮文字改「继续」） */
    var onRequestPauseForEdit: (() -> Unit)? = null
    /** 时间轴缩放（横线视觉距离缩放），默认 0.1，由输入框控制 */
    var timelineScale: Float = 0.1f
    var lineEditHand = 0                 // 0=无 1=移动 2=旋转
    var lineEditStartRot = 0f            // 按下时手指相对线中心的角度（度）
    var lineEditStartAng = 0f            // 按下时判定线旋转角

    // ---------------------------------------------------------------- 撤销（可回退多次）
    private val undoStack = ArrayDeque<UndoEntry>()
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
                if (value) undoStack.clear()      // 进房间：旧历史一律作废，避免出来后撤出诡异结果
            }
        }

    /** 栈深（给 Activity 显示/判断用） */
    val undoDepth: Int get() = if (coopLocked) 0 else undoStack.size

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
        notifyChartChanged()
    }

    /** 谱面（含剪贴板）变了 —— 联机模式下用来把整份草稿推给对面 */
    private fun notifyChartChanged() {
        onChartChanged?.invoke()
    }

    /** 追加音符（所有放置路径都走这里） */
    private fun addNote(n: RecordedNote, brandGhost: Boolean = true) {
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
        }
        block()
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
        e.activeLineBefore = activeLineId
        undoStack.addLast(e)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
    }

    /** 撤销一步；没有可撤销的返回 false */
    fun undo(): Boolean {
        if (coopLocked) return false          // 联机中不撤销
        val e = undoStack.removeLastOrNull() ?: return false
        DebugLog.add("undo", "ops=${e.ops.size} lines=${e.linesBefore?.size ?: 0}")
        undoing = true
        try {
            // 音符：撤追加 → 还原改写 → 按原下标插回删除的（见 UndoEntry.undoNotes）
            e.undoNotes(notes)
            bumpNoteIndex()
            e.clipBefore?.let { clipEntries.clear(); clipEntries.addAll(it) }
            // 判定线：整份还原（含增删线 / 关键帧 / 流速）
            e.linesBefore?.let { snaps ->
                val byId = snaps.associateBy { it.id }
                // 删掉的线要补回来、加的线要删掉
                lines.removeAll { it.id !in byId }
                for (s in snaps) {
                    val l = lines.firstOrNull { it.id == s.id } ?: Line(s.id).also { lines.add(it) }
                    l.restore(s)
                }
                lines.sortBy { it.id }
                if (lines.isEmpty()) lines.add(Line(0))
                if (byId.containsKey(e.activeLineBefore)) activeLineId = e.activeLineBefore
                else activeLineId = lines[0].id
            }
        } finally {
            undoing = false
        }
        selectedSet.clear()
        selectedKfTime = -1
        playSortedIdx = IntArray(0)
        onLinesChanged?.invoke()
        onLinePoseChanged?.invoke()
        notifyChartChanged()          // 撤销也要同步给对面，否则两边会不一致
        invalidate()
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
    /** 时间→像素比例（活动判定线在 now 时刻的瞬时值；onDraw 刷新） */
    var pxPerMs: Float = 0.1f * (600f / 720f) * 0.1f

    /** 流速可以是 0（音符静止贴在线上），所有除法都要走这个，避免除零 */
    private val pxPerMsSafe: Float get() = pxPerMs.coerceAtLeast(1e-4f)

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
        for (k in noteIdxIds.indices) if (noteIdxIds[k] == lineId) return noteIdxArrays[k] ?: noteIdxEmpty
        return noteIdxEmpty
    }

    /**
     * 按判定线分组音符下标：两趟计数 + 原生 IntArray。
     * 以前是 HashMap<Int, ArrayList<Int>>：拖动音符时每帧都要重建一次，
     * 几千个音符就是几千次装箱 + 几千个 ArrayList 节点，GC 一抖手感就慢半拍。
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
        noteIdxIds = ids.toIntArray()
        noteIdxArrays = arrays
        noteIdxBuilt = noteIdxVersion
    }

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
    /** 判定线抽屉的文字笔 */
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- 判定线几何
    /** 判定线基准 y（屏幕坐标，未叠加关键帧位移） */
    private fun lineBaseY(l: Line): Float = height * (l.posPct.coerceIn(0, 100) / 100f)

    /** 活动线基准 y（网格/关键帧条用） */
    private val judgeLineY: Float get() = lineBaseY(activeLine)

    /** 任意线的姿态写进 out[0..2]；正在编辑的那条线若选中关键帧，就显示该帧的姿态 */
    fun poseOf(l: Line, now: Long, out: FloatArray) {
        if (lineEditActive && l.id == activeLineId) {
            val t = selectedKfTime
            if (t >= 0) {
                val kf = l.keyframes.firstOrNull { it.timeMs == t }
                if (kf != null) { out[0] = kf.moveX; out[1] = kf.moveY; out[2] = kf.rotate; return }
            }
            out[0] = l.moveX; out[1] = l.moveY; out[2] = l.rotate
            return
        }
        l.poseInto(now, out)
    }

    /** 判定线中心屏幕坐标 */
    fun lineCenterOf(l: Line, now: Long): FloatArray {
        poseOf(l, now, scratchPose)
        return floatArrayOf(width / 2f + scratchPose[0] * width / 2f, lineBaseY(l) + scratchPose[1] * height / 2f)
    }

    /** 点到旋转后判定线（无限长直线）的距离 */
    fun distToLineOf(l: Line, x: Float, y: Float, now: Long): Float {
        poseOf(l, now, scratchPose)
        val lcX = width / 2f + scratchPose[0] * width / 2f
        val lcY = lineBaseY(l) + scratchPose[1] * height / 2f
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
        // 编辑要在暂停状态下进行：录制/播放中长按 → 先让 Activity 暂停（按钮文字同步变「继续」）
        if (!editMode) {
            onRequestPauseForEdit?.invoke()
            editMode = true
        }
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
        onLinesChanged?.invoke()
        invalidate()
    }

    /** 在屏幕 y 处新增一条判定线；返回新线（达到上限返回 null） */
    fun addLineAtScreenY(sy: Float): Line? {
        if (lines.size >= MAX_LINES) return null
        val own = tx == null
        if (own) beginTx()
        var line: Line? = null
        linesMutate {
            val l = Line(nextLineId++)
            l.name = "line${l.id}"
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

    /** 当前选中的关键帧时间（-1 = 没选中） */
    val selectedKeyframeMs: Long get() = selectedKfTime

    // ---------------------------------------------------------------- 音符位置

    /** 基础比例：不含流速（px / ms） */
    private fun basePxPerMs(): Float = 0.16f * (height / 720f) * timelineScale

    /** 某条线在 now 时刻的瞬时比例（px / ms） */
    fun linePxPerMs(l: Line, now: Long): Float = basePxPerMs() * l.speedAt(now)

    /**
     * 音符到判定线的纵向距离（像素，正=已经过线=屏幕下方）。
     * 走流速积分 ∫speed dt，所以有变速时距离是折线而不是直线（与 Phira height 一致）。
     */
    private fun distPx(l: Line, noteMs: Long, now: Long): Float {
        val tr = l.speedTrack()
        return ((tr.heightAt(now) - tr.heightAt(noteMs)) * basePxPerMs()).toFloat()
    }

    /** 音符在判定线局部坐标系里的 y（含正面/反面镜像） */
    private fun noteLocalY(l: Line, n: RecordedNote, noteMs: Long, now: Long): Float {
        val d = distPx(l, noteMs, now)
        return if (n.above) d else -d
    }

    /** 不分配版本：调用方先把判定线中心/旋转算好（热路径用） */
    fun noteScreenPosInto(n: RecordedNote, now: Long, out: FloatArray) {
        val l = lineOf(n)
        poseOf(l, now, scratchPose)
        val lcX = width / 2f + scratchPose[0] * width / 2f
        val lcY = lineBaseY(l) + scratchPose[1] * height / 2f
        noteScreenPosInto(n, now, lcX, lcY, scratchPose[2], out)
    }

    fun noteScreenPosInto(n: RecordedNote, now: Long, lcX: Float, lcY: Float, rotDeg: Float, out: FloatArray) {
        val l = lineOf(n)
        val a = Math.toRadians(-rotDeg.toDouble())
        val cosA = Math.cos(a).toFloat()
        val sinA = Math.sin(a).toFloat()
        val xl = (n.posX + 1f) / 2f * width - width / 2f
        val yl = noteLocalY(l, n, n.startMs, now)
        out[0] = lcX + xl * cosA - yl * sinA
        out[1] = lcY + xl * sinA + yl * cosA
    }

    var pass: Int = 1            // 当前编辑阶段 1/2/3/4
    var playheadProvider: (() -> Long)? = null
    var playingProvider: (() -> Boolean)? = null

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
        closeLinePanel()
        DebugLog.add("autoplay", "start at=${startAtMs}ms notes=${notes.size}")
        resetJudged()
        combo = 0
        hitCount = 0
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
        invalidate()
    }

    /** 长按音效按钮时弹出，便于在手机上排查声音/判定问题 */
    // ----- 音符数据 -----
    val notes: MutableList<RecordedNote> = mutableListOf()

    private val fingers = SparseArray<FingerState>()

    // ----- 绘制资源 -----
    private var bgBitmap: Bitmap? = null
    private var bgDst: RectF? = null
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
    private var tapMhScale = 1.15f
    private var holdMhScale = 1.15f
    private var flickMhScale = 1.15f
    private var dragMhScale = 1.15f

    /** 资源包里的贴图优先；没有（或内置）就读 assets */
    private fun loadTex(n: String): Bitmap? {
        val f = resPack?.let { java.io.File(it.dir, "$n.png") }
        if (f != null && f.isFile) {
            try { return BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) {}
        }
        return try {
            context.assets.open("notes/$n.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }

    /** 双押贴图/普通贴图的宽度比（拿不到就按 Phira 的 1.15） */
    private fun texScale(normal: Bitmap?, mh: Bitmap?): Float {
        if (normal == null || mh == null || normal.width <= 0) return 1.15f
        return (mh.width.toFloat() / normal.width.toFloat()).coerceIn(1f, 2f)
    }

    // 打击音效（Phira ogg；与 judge.rs 映射一致：Click→click / Flick→flick / Drag→drag / Hold→click）
    private var soundPool: SoundPool? = null
    private var sndClick = 0
    private var sndDrag = 0
    private var sndFlick = 0
    private var soundPlayFailLogged = false

    /** 打击音效开关（默认开） */
    /** 打音符的打击音效：编辑界面默认**关**，进 Autoplay 时自动打开（退出再还原） */
    var soundEnabled: Boolean = false
        set(value) { field = value; invalidate() }

    /** 资源包里的音效优先（ogg/wav/mp3）；没有就用 assets 里的内置音效 */
    private fun loadSfx(sp: SoundPool, name: String, asset: String): Int {
        val f = resPack?.sfx(name)
        return if (f != null) sp.load(f.absolutePath, 1) else sp.load(context.assets.openFd(asset), 1)
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
            soundPool = sp
            Log.i(TAG, "SoundPool preload ids=$sndClick/$sndDrag/$sndFlick")
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
        val id = when (type) {
            3 -> sndFlick
            4 -> sndDrag
            else -> sndClick        // Tap / Hold 都用 click（Phira 同款映射）
        }
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
            postInvalidate()
        } catch (_: Exception) {}
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    /** 触摸兼容（框架/全部）下把判定半径放大，手机上更好点 */
    private fun hit(r: Float): Float = if (compatMode >= 1) r * 1.6f else r
    private fun intColor(v: Long): Int = v.toInt()
    /** 编辑模式搓时间：拖动过程累积的偏移（渲染用虚拟时间，松手才写回播放器） */
    var editSeekOffset: Long = 0
    var onSeekAbs: ((Int) -> Unit)? = null

    private fun playMs(): Long = (playheadProvider?.invoke() ?: 0L) + editSeekOffset

    /** 编辑模式：累加搓动偏移（线/数字/音符都平滑跟随，不触发真实 seek） */
    fun addEditSeek(dt: Int) {
        if (dt != 0) editKfTime = -1L   // 搓了时间轴就不再"顺手改上一帧"
        editSeekOffset += dt
        invalidate()
    }

    /** 编辑模式：松手后把最终时间写回播放器并清零偏移 */
    fun flushEditSeek() {
        if (editSeekOffset == 0L) return
        val base = playheadProvider?.invoke() ?: 0L
        val target = (base + editSeekOffset).toInt()
        editSeekOffset = 0
        onSeekAbs?.invoke(target)
        invalidate()
    }

    /** 一格毫秒（最小横线间隔） */
    private fun cellMs(): Double = (60000.0 / bpm / hLines.coerceAtLeast(1)).coerceAtLeast(1.0)

    /** 毫秒量化到格点（BPM 偏移会把格点整体平移） */
    private fun quantMs(ms: Long): Long {
        val c = cellMs()
        val off = bpmOffsetMs.toDouble()
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

    /** Phiedit 风格横线：每拍 = 主线(白20%) + 拍号数字；每格 = 次线(白10%) */
    private fun drawHLine(canvas: Canvas, left: Float, right: Float, y: Float, gi: Long, labelX: Float) {
        val hl = hLines.coerceAtLeast(1).toLong()
        val isBeat = Math.floorMod(gi, hl) == 0L
        if (isBeat) {
            gridPaint.alpha = 51                    // EDITOR_VIEW_HORIZONTAL_MAIN_LINE_COLOR = 0.2
            gridPaint.strokeWidth = dp(3f)
            val beatNo = Math.floorDiv(gi, hl)
            if (beatNo >= 0) {
                val label = beatNo.toString()
                beatNumPaint.textSize = dp(12f)
                beatNumPaint.style = Paint.Style.FILL
                val fm = beatNumPaint.fontMetrics
                val baseline = y - (fm.ascent + fm.descent) / 2f
                beatNumPaint.style = Paint.Style.STROKE
                beatNumPaint.strokeWidth = dp(3f)
                beatNumPaint.color = intColor(0xDD000000)
                canvas.drawText(label, labelX, baseline, beatNumPaint)
                beatNumPaint.style = Paint.Style.FILL
                beatNumPaint.color = intColor(0xCCFFFFFF)
                canvas.drawText(label, labelX, baseline, beatNumPaint)
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
            canvas.drawBitmap(bmp, null, dst, null)
            canvas.drawColor(intColor(0x99000000))
        }

        val now = playMs()
        // 网格/交互换算用"活动判定线"的瞬时比例（有变速时这也是它此刻的视觉比例）
        pxPerMs = linePxPerMs(activeLine, now)
        // 打歌：**先判定再画** —— 这一帧刚过线的音符当帧就不再画，
        // 打击特效/音效/音符消失完全落在同一帧（以前是画完再判，差一帧）
        if (playMode) tickAutoPlay(now)
        val cellW = w / vLines.toFloat()
        val cMs = cellMs().toFloat()
        // 网格原点是判定线的**真实中心**（含 moveY/关键帧位移），
        // 之前用的是 posPct 换算出的基准高度，判定线一移动横线就和音符对不上
        poseOf(activeLine, now, framePose)
        val activeCx = w / 2f + framePose[0] * w / 2f
        val activeCy = lineBaseY(activeLine) + framePose[1] * h / 2f
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

        // ---- 逐条判定线：线 + 竖线刻度 + 挂在它上面的音符 ----
        val multi = lines.size > 1
        // 双押标记每帧只算一次（多线共用）；布尔数组是复用的，不产生垃圾
        val dhAll = if (playMode || editDoubleHint) doubleHints(now) else emptyDh
        // 非选中的判定线变淡：只在编辑模式、且（关掉"判定线高亮" 或 正在翻判定线列表）时
        // 打歌（Autoplay）永远全部正常显示
        val dimOthers = editMode && !playMode && (!highlightAllLines || panelOpen)
        for (li in lines.indices) {
            val l = lines[li]
            poseOf(l, now, framePose)
            val lcX = w / 2f + framePose[0] * w / 2f
            val lcY = lineBaseY(l) + framePose[1] * h / 2f
            val rot = framePose[2]
            val isActive = l.id == activeLineId
            val dimmed = dimOthers && !isActive

            val editingThis = lineEditActive && isActive
            linePaint.color = when {
                editingThis -> intColor(0xFFFFEB3B)
                isActive -> Color.WHITE
                dimmed -> intColor(0x44FFFFFF)
                else -> intColor(0x99FFFFFF)
            }
            linePaint.strokeWidth = if (isActive) dp(6f) else dp(3f)
            // 判定线透明度（RPE alphaEvents）：0 时整条隐藏
            val lineA = l.alphaAt(now)
            linePaint.alpha = (linePaint.alpha * lineA).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(lcX, lcY)
            canvas.rotate(-rot)
            if (lineA > 0.004f) canvas.drawLine(-w, 0f, w, 0f, linePaint)

            // 竖线刻度（垂直判定线，随线旋转）+ 竖向放置线（跟着判定线一起转）
            // 制作线显示=隐藏时，这些短竖线和中心刻度全部不画
            if (gridMode != 3) {
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

            // 多条线时标个号，方便区分（活动线高亮）
            if (multi) {
                beatNumPaint.textSize = dp(11f)
                beatNumPaint.style = Paint.Style.FILL
                beatNumPaint.color = when {
                    isActive -> intColor(0xFF00E5FF)
                    dimmed -> intColor(0x40FFFFFF)
                    else -> intColor(0x80FFFFFF)
                }
                canvas.drawText("L$li", dp(6f), lcY - dp(10f), beatNumPaint)
            }

            drawNotesOfLine(canvas, l, now, cellW, lcX, lcY, rot, dhAll, dimmed)
        }
        linePaint.strokeWidth = dp(6f)

        // 判定线编辑模式：中心白色方框 + 旋转箭头 + 关键帧方块 + 左侧时间条
        if (lineEditActive) {
            poseOf(activeLine, now, framePose)
            val lcX = w / 2f + framePose[0] * w / 2f
            val lcY = lineBaseY(activeLine) + framePose[1] * h / 2f
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

    private fun panelListTop(r: RectF): Float = r.top + panelHeaderH()
    private fun panelListBottom(r: RectF): Float = r.bottom - panelFooterH()

    private fun panelContentH(): Float = lines.size * panelRowH()

    private fun panelMaxScroll(): Float {
        val r = panelRect()
        return maxOf(0f, panelContentH() - (panelListBottom(r) - panelListTop(r)))
    }

    /**
     * 面板列表里的第几行（-1 = 标题/页脚/面板外）。
     * **必须同时校验 x**：面板只在右侧一条，左边画布上同一个高度不算点行，
     * 否则"点背景关窗口"会变成"点到窗口里的判定线按钮"。
     */
    private fun panelRowAt(x: Float, y: Float): Int {
        val r = panelRect()
        if (x < r.left || x > r.right) return -1
        if (y < panelListTop(r) || y > panelListBottom(r)) return -1
        val rel = y - (panelListTop(r) - panelScroll)
        if (rel < 0f) return -1
        val i = (rel / panelRowH()).toInt()
        return if (i in lines.indices) i else -1
    }

    /** 页脚"＋ 新建判定线"的矩形 */
    private fun panelAddRect(): RectF {
        val r = panelRect()
        return RectF(r.left + dp(8f), r.bottom - panelFooterH() + dp(4f), r.right - dp(8f), r.bottom - dp(4f))
    }

    private fun fmt2(v: Float): String {
        val r = Math.round(v * 100f) / 100f
        return if (r == Math.floor(r.toDouble()).toFloat()) r.toInt().toString() else r.toString()
    }

    /** 打开抽屉（fromY = 手指按下的 y，用来接着上下滑滚动列表） */
    private fun openPanel(fromY: Float) {
        panelOpen = true
        panelScroll = 0f
        panelDownRow = -1
        panelDownY = fromY
        panelLastY = fromY
        panelScrolling = true          // 从把手划开的这一下不算"点行"
        barPressed = false
        panelNoteCounts = IntArray(lines.size) { k -> notes.count { it.lineId == lines[k].id } }
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
        panelNoteCounts = IntArray(lines.size) { k -> notes.count { it.lineId == lines[k].id } }
        panelScroll = panelMaxScroll()
        return added
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
        if (barA > 0.02f && !lineEditActive) {     // 编辑判定线时把左侧长条藏起来
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
        // 编辑判定线时左侧灰条隐藏，也不能吃掉触摸（否则没法拖时间轴）
        if (lineEditActive) return false
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
    private fun copyCircleCenters(rowIndex: Int, r: RectF): FloatArray {
        val rowH = panelRowH()
        val cy = panelListTop(r) - panelScroll + rowIndex * rowH + rowH / 2f
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
        if (lines.size >= MAX_LINES) return null
        val src = lines.firstOrNull { it.id == id } ?: return null
        var created: Line? = null
        beginTx()
        linesMutate {
            val nl = Line(nextLineId++)
            nl.name = "line${nl.id}"
            nl.posPct = src.posPct
            nl.moveX = src.moveX
            nl.moveY = src.moveY
            nl.rotate = src.rotate
            nl.speed = src.speed
            nl.keyframes.addAll(src.keyframes)
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
     * 自动生成的 `line / lineN` 一律显示成 `判定线 (id+1)`（id 永不随排序变化），
     * 导入的谱面自带名字（如 spin）则原样显示。
     */
    private fun lineLabel(l: Line): String {
        val n = l.name
        val okGenerated = n.isEmpty() || n == "line" || (n.startsWith("line") && n.drop(4).all { it.isDigit() })
        return if (okGenerated) "判定线 ${l.id + 1}" else n
    }

    /** 画抽屉里的某一行（[dx] 用于左右滑时整体平移） */
    private fun drawOneRow(canvas: Canvas, r: RectF, i: Int, top: Float, dx: Float) {
        val l = lines.getOrNull(i) ?: return
        val rowH = panelRowH()
        val isSel = l.id == activeLineId
        canvas.save()
        if (dx != 0f) canvas.translate(dx, 0f)
        if (rowMoveActive && i == rowMoveFrom) canvas.scale(1.1f, 1.1f, r.centerX(), top + rowH / 2f)
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
        val nc = panelNoteCounts.getOrNull(i) ?: 0
        canvas.drawText(
            "${l.posPct}% · 流速×${fmt2(l.speedAt(playMs()))} · 帧${l.keyframes.size} · 音$nc",
            rr.left + dp(11f), rr.top + dp(36f), panelPaint
        )
        // 被框住：行内容（含"当前选中"的青色）画完之后，最后在外面套一圈纯描边红框。
        // 红色只用**框**表示，不给行改颜色 —— 否则"当前选中"的蓝色会把"被框住"的红色盖掉。
        // 外扩 3dp 留出缝隙，红框只是刚好包住这一行（相邻两行都框住时仍留 2dp 间隔）。
        if (l.id in selectedLines) {
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

    /** 右侧浅灰把手 + 判定线列表抽屉 */
    private fun drawLineSwitcher(canvas: Canvas) {
        val target = if (panelOpen) 1f else 0f
        panelAnim += (target - panelAnim) * 0.3f
        if (Math.abs(target - panelAnim) < 0.005f) panelAnim = target

        // 把手（抽屉打开时淡出），圆角 2.5dp、没有花纹
        val barA = 1f - panelAnim
        if (barA > 0.02f) {
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

        // 标题
        panelPaint.textAlign = Paint.Align.LEFT
        panelPaint.textSize = dp(13f)
        panelPaint.color = intColor(0xFFFFFFFF)
        canvas.drawText("判定线 ${lines.size} 条", r.left + dp(12f), r.top + dp(25f), panelPaint)

        // 列表（被拖动的那一行不在这里画，等会儿画在裁剪区外面）
        val listTop = panelListTop(r)
        val listBottom = panelListBottom(r)
        val rowH = panelRowH()
        canvas.save()
        canvas.clipRect(r.left + dp(2f), listTop, r.right - dp(2f), listBottom)
        if (rowShiftAnim.size != lines.size) rowShiftAnim = FloatArray(lines.size)
        for (i in lines.indices) {
            // 被跨过的行：目标位移 ±rowH，按帧缓动走过去（原来是一瞬间跳一格）
            var targetShift = 0f
            if (rowMoveActive && rowMoveTarget >= 0) {
                when {
                    rowMoveFrom < rowMoveTarget && i in (rowMoveFrom + 1)..rowMoveTarget -> targetShift = -rowH
                    rowMoveFrom > rowMoveTarget && i in rowMoveTarget until rowMoveFrom -> targetShift = rowH
                }
            }
            val prevS = rowShiftAnim[i]
            val nextS = prevS + (targetShift - prevS) * 0.35f
            rowShiftAnim[i] = if (Math.abs(targetShift - nextS) < 0.5f) targetShift else nextS
            val top = listTop - panelScroll + i * rowH + rowShiftAnim[i]
            if (top > listBottom || top + rowH < listTop) continue
            if (rowDragActive && i == rowDragRow) continue        // 左右拖动的那行单独画（跟手）
            if (rowMoveActive && i == rowMoveFrom) continue      // 上下拖动的那行单独画（跟手）
            drawOneRow(canvas, r, i, top, 0f)
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

        // 页脚：＋ 新建判定线
        val addR = panelAddRect()
        livePaint.color = intColor(0x1A00E5FF)
        canvas.drawRoundRect(addR, dp(9f), dp(9f), livePaint)
        panelPaint.textAlign = Paint.Align.CENTER
        panelPaint.textSize = dp(13f)
        panelPaint.color = intColor(0xFF00E5FF)
        canvas.drawText("＋ 新建判定线", addR.centerX(), addR.centerY() + dp(5f), panelPaint)

        if (rowMoveActive && rowMoveFrom in lines.indices) {
            // 上下拖动：整行跟着手指走（同样裁在列表区域内 —— 拖出窗口时暂时看不见，但不能画到窗口外面）
            canvas.save()
            canvas.clipRect(r.left + dp(2f), listTop, r.right - dp(2f), listBottom)
            val moveTop = listTop - panelScroll + rowMoveFrom * rowH + rowMoveDy
            drawOneRow(canvas, r, rowMoveFrom, moveTop, 0f)
            canvas.restore()
        }
        // 左滑：两个复制圆圈（不带音符 / 带音符）
        if (rowDragActive && rowDragDx < 0f && rowDragRow in lines.indices) {
            val c = copyCircleCenters(rowDragRow, r)
            val rr = copyCircleRadius()
            drawCopyCircle(canvas, c[0], c[1], rr, rowDragTarget == 1, "不带音符")
            drawCopyCircle(canvas, c[2], c[3], rr, rowDragTarget == 2, "带音符")
        }
        if (rowDragActive && rowDragRow in lines.indices) {
            // 横向拖动的那一行：跟手平移（不裁剪，出屏才看不见）
            val dragTop = listTop - panelScroll + rowDragRow * rowH
            drawOneRow(canvas, r, rowDragRow, dragTop, rowDragDx)
            if (rowDragDx > 0f && rowDragDx >= rowDeleteThreshold(r)) {
                canvas.save()
                canvas.clipRect(r.left + dp(6f), dragTop, r.right - dp(6f), dragTop + rowH)
                livePaint.color = intColor(0x55FF5252)
                canvas.drawRect(r.left, dragTop, r.right, dragTop + rowH, livePaint)
                panelPaint.textAlign = Paint.Align.LEFT
                panelPaint.textSize = dp(12f)
                panelPaint.color = intColor(0xFFFF5252)
                canvas.drawText("松手删除", r.left + dp(12f), dragTop + rowH / 2f + dp(4f), panelPaint)
                canvas.restore()
            }
        }
        canvas.restore()
    }

    /** 抽屉/把手的事件处理；返回 true 表示这次事件被吃掉了 */
    private fun handleLineSwitcherTouch(e: MotionEvent): Boolean {
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
                // 横向意图明显 → 左右滑（复制/删除）
                if (!rowDragActive && !rowMoveActive && panelDownRow >= 0 &&
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
                        val c = copyCircleCenters(rowDragRow, r)
                        val reach = copyCircleRadius() * 1.5f
                        if (Math.hypot((x - c[0]).toDouble(), (y - c[1]).toDouble()) < reach) rowDragTarget = 1
                        else if (Math.hypot((x - c[2]).toDouble(), (y - c[3]).toDouble()) < reach) rowDragTarget = 2
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
                if (rowMoveActive) {
                    endRowMove()
                    panelDownRow = -1
                    return true
                }
                if (rowDragActive) {
                    val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                    val id = lines.getOrNull(rowDragRow)?.id
                    val dx = rowDragDx
                    val target = rowDragTarget
                    val reset = {
                        rowDragActive = false
                        rowDragRow = -1
                        rowDragDx = 0f
                        rowDragTarget = 0
                    }
                    panelDownRow = -1
                    when {
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
                    if (panelDownRow == -2 && panelAddRect().contains(x, y)) {
                        addLineSmart()               // 加线，抽屉保持打开
                    } else if (panelDownRow >= 0 && panelRowAt(x, y) == panelDownRow) {
                        val target = lines.getOrNull(panelDownRow)
                        if (target != null) {
                            if (longPress) {
                                // 长按 = 框住 / 取消框住（红框，可多条）+ 直接进入这条线的编辑
                                // （与"长按画布上的判定线本体"完全一致：输入框/时间轴/关键帧一起出来）
                                toggleFrameAndFocus(target.id)
                            } else if (selectedLines.isNotEmpty() && target.id !in selectedLines) {
                                // 有红框时点别的行 = 和点抽屉空白一个意思：**先取消框选**（不切也不加）
                                clearFrameSelection()
                            } else if (target.id in selectedLines) {
                                focusLineForEdit(target.id)   // 点自己（已框住的）→ 进入编辑
                            } else {
                                activateLine(target.id)       // 单击 = 切换判定线
                            }
                            onLinesChanged?.invoke()
                        }
                    } else if (!panelRect().contains(x, y)) {
                        // 点抽屉外面：先取消框选，没有框选才收起抽屉
                        if (selectedLines.isNotEmpty()) {
                            clearFrameSelection()
                        } else {
                            closeLinePanel()
                        }
                    } else {
                        // 点抽屉里的空白/标题 = 取消框选（窗口保持打开，和主界面项目抽屉一致）
                        clearFrameSelection()
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

    /** 按住一行 0.3s 后上下拖动 = 调整判定线顺序（实时跟手） */
    private fun startRowMove(y: Float) {
        if (panelDownRow < 0 || panelDownRow >= lines.size) return
        rowMoveActive = true
        rowMoveMoved = false
        rowMoveFrom = panelDownRow
        rowMoveTarget = panelDownRow
        rowMoveStartY = rowMoveLastY   // 用触发瞬间的手指位置，偏移从 0 起算，不会跳
        rowMoveDy = 0f
        rowMoveLastY = y
        postOnAnimation(rowMoveScrollRunnable)
        invalidate()
    }

    /** 只更新"跟手偏移 + 目标插入位置"，不动列表（松手才提交） */
    private fun updateRowMove(y: Float) {
        if (!rowMoveActive) return
        rowMoveLastY = y
        rowMoveDy = y - rowMoveStartY
        if (Math.abs(rowMoveDy) > dp(3f)) rowMoveMoved = true
        val r = panelRect()
        val listTop = panelListTop(r)
        val rowH = panelRowH()
        val center = listTop - panelScroll + rowMoveFrom * rowH + rowH / 2f + rowMoveDy
        rowMoveTarget = (((center - listTop + panelScroll) / rowH).toInt()).coerceIn(0, lines.size - 1)
        invalidate()
    }

    private fun endRowMove() {
        rowMoveActive = false
        rowMoveScrollRunnable.let { removeCallbacks(it) }
        val from = rowMoveFrom
        val to = rowMoveTarget
        rowMoveDy = 0f
        rowMoveFrom = -1
        rowMoveTarget = -1
        if (rowMoveMoved && to != from && from in lines.indices) {
            val own = tx == null
            if (own) beginTx()
            linesMutate {
                val item = lines.removeAt(from)
                lines.add(to.coerceIn(0, lines.size), item)
            }
            if (own) endTx()
            DebugLog.add("line", "reorder $from -> $to")
            onLinesChanged?.invoke()
        } else if (!rowMoveMoved) {
            // 长按但没拖动 = **框住 / 取消框住** + 聚焦进编辑（不再弹删除确认框；删除走右滑 80%）
            val id = lines.getOrNull(from)?.id
            if (id != null) toggleFrameAndFocus(id)
        }
        rowMoveMoved = false
        invalidate()
    }

    /** 画某条判定线自己的音符（含正面/反面、假音符、双押提示、红框） */
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
        for (i in indexesForLine(id)) {
            val n = notes[i]
            if (playMode && judgedAt(i)) {
                // 已判定的长条：整个长条移出屏幕边缘才消失（drawNoteAt 内部有出屏裁剪）
                if (n.type == 2 && !n.fake) drawNoteAt(canvas, n, now, cellW, 255, isDh(i), l, lcX, lcY, rot)
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
            val tilt = ov[i]                                  // 被后来的音符盖住 → 斜 45° + 橙框
            drawNoteAt(canvas, n, now, cellW, alpha, isDh(i), l, lcX, lcY, rot, tilt)
            // 反向=蓝框 / 幽灵=绿框 / 重叠=橙框（斜 45°）；两个都是就左上蓝、右下绿
            val needBlue = markReverse && !n.above
            val needGreen = n.fake && ghostFrame
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
                if (overlapFrame) {
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
        val yStartL = noteLocalY(l, n, n.startMs, now)
        if (n.type == 2) {
            // 长条要看**整条**的范围：只看头部的话，长条刚探出屏幕一半就会整根被砍掉
            val yEndL = noteLocalY(l, n, n.endMs, now)
            val topY = minOf(yStartL, yEndL)
            val botY = maxOf(yStartL, yEndL)
            if (topY > h + 120 || botY < -h - 120) return
        } else if (yStartL > h + 120 || yStartL < -h - 120) return

        canvas.save()
        canvas.translate(lcX, lcY)
        canvas.rotate(-rotDeg)
        val x = (n.posX + 1f) / 2f * width - width / 2f

        when (n.type) {
            1 -> {
                val bmp = if (isDouble) tapMh ?: tapBmp else tapBmp
                val nw = cellW * 1.05f * (if (isDouble) tapMhScale else 1f)
                val nh = nw * (bmp?.height?.toFloat() ?: 100f) / (bmp?.width?.toFloat() ?: 998f)
                if (!n.above) canvas.rotate(180f, x, yStartL)
                if (tilt) canvas.rotate(45f, x, yStartL)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, alpha)
            }
            3 -> {
                val bmp = if (isDouble) flickMh ?: flickBmp else flickBmp
                val nw = cellW * 1.05f * (if (isDouble) flickMhScale else 1f)
                val nh = nw * (bmp?.height?.toFloat() ?: 172f) / (bmp?.width?.toFloat() ?: 998f)
                if (!n.above) canvas.rotate(180f, x, yStartL)
                if (tilt) canvas.rotate(45f, x, yStartL)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, alpha)
            }
            4 -> {
                val bmp = if (isDouble) dragMh ?: dragBmp else dragBmp
                val nw = cellW * 1.05f * (if (isDouble) dragMhScale else 1f)
                val nh = nw * (bmp?.height?.toFloat() ?: 50f) / (bmp?.width?.toFloat() ?: 948f)
                if (!n.above) canvas.rotate(180f, x, yStartL)
                if (tilt) canvas.rotate(45f, x, yStartL)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, alpha)
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
                val bw = cellW * 1.05f * (if (isDouble) holdMhScale else 1f)
                // 只要还有厚度就画（出屏与否由本函数开头的"整条范围"判断负责）。
                // 以前这里写死 -300 / h+300：那是**音符本地坐标**，屏幕顶大约在 -0.6h，
                // 于是长条下半截一越过判定线上方 300px 就整根被砍掉 —— 上移一半就消失就是这个。
                if (bodyBot > bodyTop + 0.5f) {
                    if (!n.above) canvas.rotate(180f, x, (bodyTop + bodyBot) / 2f)
                    if (tilt) canvas.rotate(45f, x, (bodyTop + bodyBot) / 2f)
                    drawTex(canvas, if (isDouble) holdMh ?: holdBmp else holdBmp, x - bw / 2, bodyTop, x + bw / 2, bodyBot, alpha)
                }
            }
        }
        canvas.restore()
    }

    /** 找 (x,y) 附近的音符（含下落中的），返回下标，未找到 -1 */
    private fun findNoteAt(x: Float, y: Float, now: Long): Int {
        var best = -1
        var bestD2 = Float.MAX_VALUE
        val baseHit = dp(26f)        // 普通音符：碰到才算（音符宽≈1.05格）
        val cellW = width.toFloat() / vLines.coerceAtLeast(1)
        // 每个触摸事件都要跑一遍：重叠标记缓存提到循环外，距离比较用平方（不算 sqrt、不装箱）
        val ov = overlapFlags()
        val ovN = ov.size
        for (l in lines) {
            // 快速切换关着 = 其他线上的音符看得到、点不到
            if (!quickSwitch && l.id != activeLineId) continue
            poseOf(l, now, scratchPose)
            val lcX = width / 2f + scratchPose[0] * width / 2f
            val lcY = lineBaseY(l) + scratchPose[1] * height / 2f
            val a = Math.toRadians(-scratchPose[2].toDouble())
            val cosA = Math.cos(a).toFloat()
            val sinA = Math.sin(a).toFloat()
            val halfW = width / 2f
            for (i in indexesForLine(l.id)) {
                val n = notes[i]
                val xl = (n.posX + 1f) / 2f * width - halfW
                val yl = noteLocalY(l, n, n.startMs, now)
                val px = lcX + xl * cosA - yl * sinA
                val dx = x - px
                // 斜置（重叠）的音符框更大，命中范围也跟着放大（和橙框一样大），否则点橙框选不中
                val limit = if (i < ovN && ov[i]) cellW * 0.85f else baseHit
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

        // 左上/右下：旋转箭头
        drawArrow(canvas, cx - dp(40f), cy - dp(40f), -135f)
        drawArrow(canvas, cx + dp(40f), cy + dp(40f), 45f)

        // 左下/右上：关键帧小方块（点击=打关键帧）
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
                livePaint.strokeWidth = dp(3f)
                livePaint.color = intColor(0xFFFF5252)
                canvas.drawCircle(barX, ky, dp(16f), livePaint)
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
            poseOf(l, now, scratchPose)
            val lcX = width / 2f + scratchPose[0] * width / 2f
            val lcY = lineBaseY(l) + scratchPose[1] * height / 2f
            val cosA = Math.cos(Math.toRadians(-scratchPose[2].toDouble())).toFloat()
            val sinA = Math.sin(Math.toRadians(-scratchPose[2].toDouble())).toFloat()
            val halfScreenW = width / 2f
            val idx = if (onlySelected) selectedSet.toIntArray() else indexesForLine(l.id)
            for (i in idx) {
                if (i !in notes.indices) continue
                val n = notes[i]
                if (n.lineId != l.id) continue
                val xl = (n.posX + 1f) / 2f * width - halfScreenW
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
                val t = (s * slot).toLong() + bpmOffsetMs
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

        // Autoplay：玩家无需操作，触摸忽略（缩放由上方拖动条控制）
        if (playMode && !editMode) return true

        // 编辑模式（暂停）：判定线编辑 / 框选多选编辑（全量模式只在录制时生效）
        if (editMode) {
            if (lineEditActive) return onLineEditTouch(event, nowReal)
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
                    poseOf(ln, playMs(), scratchPose)
                    val cxx = width / 2f + scratchPose[0] * width / 2f
                    val cyy = lineBaseY(ln) + scratchPose[1] * height / 2f
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
                // 快速切换：点在判定线本体上（附近没有音符）= 切到这条线，这一次不放置音符
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
                                    dT = ((f.downY - y) / pxPerMsSafe).toLong()
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
                                    poseOf(ln, playMs(), scratchPose)
                                    val lcX = width / 2f + scratchPose[0] * width / 2f
                                    val lcY = lineBaseY(ln) + scratchPose[1] * height / 2f
                                    val ang = Math.toRadians(scratchPose[2].toDouble())
                                    val dx = x - lcX; val dy = y - lcY
                                    val xl = (dx * Math.cos(ang) - dy * Math.sin(ang)).toFloat()
                                    val yl = (dx * Math.sin(ang) + dy * Math.cos(ang)).toFloat()
                                    // 反面音符的 y 是镜像的，换回时间要再乘回符号
                                    val yForTime = if (n.above) yl else -yl
                                    val pxL = linePxPerMs(ln, playMs()).coerceAtLeast(1e-6f)
                                    val pos = if (smoothMove) rawPosX(xl + width / 2f) else quantizeX(xl + width / 2f)
                                    val t = if (smoothMove)
                                        (playMs() - (yForTime / pxL).toLong()).coerceAtLeast(0L)
                                    else
                                        quantMs((playMs() - (yForTime / pxL).toLong()).coerceAtLeast(0L))
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
                            val dt = Math.round(dy / pxPerMsSafe).toInt()
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
                    else if (f.mode == 2) { if (!f.moved && f.willDelete) deleteSelected() }
                    else if (f.mode == 3) flushEditSeek()
                    else if (f.mode == 1) {
                        // 放置位置按"按下点所属判定线"换算（点背景前已经取过最近线）
                        val ln = lines.firstOrNull { it.id == f.lineId }
                            ?: nearestLine(f.downX, f.downY, playMs())
                        poseOf(ln, playMs(), scratchPose)
                        val lcX = width / 2f + scratchPose[0] * width / 2f
                        val lcY = lineBaseY(ln) + scratchPose[1] * height / 2f
                        val C = Math.cos(Math.toRadians(scratchPose[2].toDouble())).toFloat()
                        val S = Math.sin(Math.toRadians(scratchPose[2].toDouble())).toFloat()
                        val pxL = linePxPerMs(ln, playMs()).coerceAtLeast(1e-6f)
                        val dragging = Math.abs(f.curY - f.downY) >= dp(6f)
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
                            val raw = (playMs() - (sign * yl / pxL).toLong()).coerceAtLeast(0L)
                            if (smoothMove) raw else quantMs(raw)
                        }
                        if (pass == 4 && dragging) {
                            val t1 = yTime(f.downY); val t2 = yTime(f.curY)
                            val start = minOf(t1, t2)
                            val end = maxOf(t1, t2).coerceAtLeast(start + cellMs().toLong())
                            // 用判定线局部 x 吸附，判定线旋转后落点仍然贴手
                            val pos = posXFromLocal(xlAt(f.downX, f.downY))
                            addNote(RecordedNote(2, start, end, pos, 4, ln.id, aboveForPlace))
                            spawnHitFx(f.curX, f.curY)
                        } else {
                            val t = yTime(f.curY)
                            val pos = posXFromLocal(xlAt(f.curX, f.curY))
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
            poseOf(l, now, scratchPose)
            val lcX = width / 2f + scratchPose[0] * width / 2f
            val lcY = lineBaseY(l) + scratchPose[1] * height / 2f
            val a = Math.toRadians(-scratchPose[2].toDouble())
            val cosA = Math.cos(a).toFloat()
            val sinA = Math.sin(a).toFloat()
            val halfW = width / 2f
            for (i in indexesForLine(l.id)) {
                val n = notes[i]
                val xl = (n.posX + 1f) / 2f * width - halfW
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
                // 2) 旋转箭头（左上/右下）
                val arrowA = Math.hypot((x - (c[0] - dp(40f))).toDouble(), (y - (c[1] - dp(40f))).toDouble())
                val arrowB = Math.hypot((x - (c[0] + dp(40f))).toDouble(), (y - (c[1] + dp(40f))).toDouble())
                if (arrowA < hit(dp(34f)) || arrowB < hit(dp(34f))) {
                    lineEditHand = 2
                    lineEditStartRot = Math.toDegrees(Math.atan2((y - c[1]).toDouble(), (x - c[0]).toDouble())).toFloat()
                    lineEditStartAng = editTargetPose()[2]
                    return true
                }
                // 3) 关键帧小方块（左下/右上）→ 打关键帧
                val d1 = Math.hypot((x - (c[0] - dp(40f))).toDouble(), (y - (c[1] + dp(40f))).toDouble())
                val d2 = Math.hypot((x - (c[0] + dp(40f))).toDouble(), (y - (c[1] - dp(40f))).toDouble())
                if (d1 < hit(dp(26f)) || d2 < hit(dp(26f))) {
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
                                onLinePoseChanged?.invoke()
                                invalidate()
                                return true
                            }
                            // 第一次短按 = 选中（红框），并把时间跳到这一帧
                            selectedKfTime = k.timeMs
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
                        val dt = Math.round(dy / pxPerMsSafe).toInt()
                        if (dt != 0) addEditSeek(dt)
                        continue
                    }
                    when (lineEditHand) {
                        1 -> linesMutate {
                            val nx = ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                            val ny = ((y - judgeLineY) / (height / 2f)).coerceIn(-1f, 1f)
                            if (selectedKfTime >= 0) {
                                val cur = editTargetPose()
                                l.setKeyframePose(selectedKfTime, nx, ny, cur[2])
                            } else {
                                l.moveX = nx; l.moveY = ny
                            }
                        }
                        2 -> {
                            val ang = Math.toDegrees(Math.atan2((y - c[1]).toDouble(), (x - c[0]).toDouble())).toFloat()
                            linesMutate {
                                val nr = lineEditStartAng - (ang - lineEditStartRot)
                                if (selectedKfTime >= 0) {
                                    val cur = editTargetPose()
                                    l.setKeyframePose(selectedKfTime, cur[0], cur[1], nr)
                                } else l.rotate = nr
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

    /** 打一个关键帧（当前时间 + 当前姿态 + 此刻生效的流速） */
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
            lx = cur[0]; ly = cur[1]; lr = cur[2]; lsp = sp
            l.putKeyframe(t, cur[0], cur[1], cur[2], sp)
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
            hitCount = seedPassedNotes(now)      // 回拖进度条：前面的音符同样算进分数
        }
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
        poseOf(l, now, scratchPose)
        val lcX = width / 2f + scratchPose[0] * width / 2f
        val lcY = lineBaseY(l) + scratchPose[1] * height / 2f
        val rotA = Math.toRadians(-scratchPose[2].toDouble())
        val cosA = Math.cos(rotA).toFloat()
        val sinA = Math.sin(rotA).toFloat()
        val xl = (n.posX + 1f) / 2f * width - width / 2f
        spawnHitFx(lcX + xl * cosA, lcY + xl * sinA)
        playHitSound(n.type)
        // 长条：登记持续特效（直到尾部离开判定线）；音效只此一次
        if (n.type == 2) holdFxList.add(HoldFx(i, n.endMs))
    }

    /** Phira 风格 HUD：右上角 7 位分数、上方中间 Combo（≥3）、顶端 Bar 进度条 */
    private fun drawJudgeOverlay(canvas: Canvas, now: Long, w: Float, h: Float) {
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
    fun saveDraft(): String = DraftCodec.encode(lines, notes)

    /** 联机同步用：草稿 + 剪贴板一起序列化（本地草稿仍然不存剪贴板） */
    fun draftWithClip(): String = DraftCodec.encode(lines, notes, clipEntries)

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
            if (l.posPct < 0) l.posPct = fallbackPct
            lines.add(l)
        }
        if (lines.isEmpty()) lines.add(Line(0))
        activeLineId = if (lines.any { it.id == keepActive }) keepActive else lines[0].id
        nextLineId = (lines.maxOfOrNull { it.id } ?: 0) + 1
        notes.clear()
        notes.addAll(d.notes)
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
        // 抽屉没开就不用算每行的音符数（这是 线条×音符 的一遍扫描）
        if (keepPanel) panelNoteCounts = IntArray(lines.size) { k -> notes.count { it.lineId == lines[k].id } }
        panelScroll = keepPanelScroll.coerceIn(0f, panelMaxScroll())
        clipOpen = keepClip
        clipScroll = keepClipScroll.coerceIn(0f, clipMaxScroll())
        resetJudged()
        playSortedIdx = IntArray(0)
        undoStack.clear()
        tx = null
        onLinesChanged?.invoke()
        onLinePoseChanged?.invoke()
        invalidate()
    }
}
