package com.piezmaker

/**
 * 一次可撤销操作（纯逻辑，可单元测试）。
 *
 * 内部是**有序的操作列表**，撤销时逆序重放每个操作的逆操作：
 *  - [Op.Added]    追加了某个音符 → 逆操作：从末尾删掉
 *  - [Op.Removed]  在 index 处删了某个音符 → 逆操作：插回 index
 *  - [Op.Modified] index 处的音符被改写 → 逆操作：写回旧值
 *
 * 为什么不只记"加了几个 / 删了哪些下标 / 改了哪些下标"：同一步里既删又改时下标会互相错位
 * （随机对拍测试抓到过），逆序重放则天然正确。
 * 拖动产生的连续改写靠 [noteModified] 去重，每个下标只留第一次的旧值，
 * 所以一次拖动仍然只占一个音符的内存。
 *
 * 判定线（位置/姿态/关键帧/流速/增删线）整体很小（几十个关键帧），直接存整份旧值。
 */
class UndoEntry {

    sealed interface Op {
        class Added(val note: RecordedNote) : Op
        class Removed(val index: Int, val note: RecordedNote) : Op
        /** [newNote] 只在撤销时回填（重做要用），平时是 null */
        class Modified(val index: Int, val old: RecordedNote, var newNote: RecordedNote? = null) : Op
    }

    /** 一条判定线的完整快照 */
    class LineSnap(
        val id: Int,
        val posPct: Int,
        val moveX: Float,
        val moveY: Float,
        val rotate: Float,
        val speed: Float,
        val name: String,
        val keyframes: List<LineKeyframe>,
        val fatherId: Int = -1,
        val rotWithFather: Boolean = false,
        val ctrlY: List<CtrlEvent> = emptyList(),
        val ctrlPos: List<CtrlEvent> = emptyList(),
        val ctrlSize: List<CtrlEvent> = emptyList(),
        val ctrlAlpha: List<CtrlEvent> = emptyList(),
        val incline: List<InclineEvent> = emptyList()
    )

    val ops = ArrayList<Op>()
    private val modifiedIdx = HashSet<Int>()

    /** 判定线被改动前的整份快照（含增删线、打帧、拖线、改流速） */
    var linesBefore: List<LineSnap>? = null

    /** 当前编辑的是哪条线（撤销后要切回它，避免指针悬空） */
    var activeLineBefore: Int = -1

    /** 剪贴板被改动前的整份内容（"清空剪贴板"要能撤销） */
    var clipBefore: List<RecordView.ClipEntry>? = null

    // ---- 以下是"重做"用的：撤销的那一刻把"撤销之后应该回到什么状态"记下来 ----
    /** 撤销前的判定线状态（= 重做后要恢复成的样子） */
    var linesAfter: List<LineSnap>? = null
    var activeLineAfter: Int = -1
    var clipAfter: List<RecordView.ClipEntry>? = null

    fun isEmpty(): Boolean = ops.isEmpty() && linesBefore == null && clipBefore == null

    fun noteAdded(n: RecordedNote) {
        ops.add(Op.Added(n))
    }

    fun noteRemoved(index: Int, n: RecordedNote) {
        ops.add(Op.Removed(index, n))
        modifiedIdx.remove(index)      // 这个下标换了别的音符，之后的改写要重新记
    }

    fun noteModified(index: Int, old: RecordedNote) {
        if (modifiedIdx.add(index)) ops.add(Op.Modified(index, old))
    }

    /** 撤销前先把"改写成了什么"记下来（重做用）。必须在 [undoNotes] 之前调用 */
    fun captureAfter(notes: List<RecordedNote>) {
        for (op in ops) {
            if (op is Op.Modified) op.newNote = notes.getOrNull(op.index)
        }
        modifiedIdx.clear()
    }

    /** 正向重放：把这一笔改动重新做一遍（撤销的逆操作） */
    fun redoNotes(notes: MutableList<RecordedNote>) {
        for (op in ops) {
            when (op) {
                is Op.Added -> notes.add(op.note)
                is Op.Removed -> if (op.index in notes.indices) notes.removeAt(op.index)
                is Op.Modified -> {
                    val n = op.newNote ?: continue
                    if (op.index in notes.indices) notes[op.index] = n
                }
            }
        }
    }

    /** 逆序重放，把音符列表还原到这一步之前 */
    fun undoNotes(notes: MutableList<RecordedNote>) {
        for (i in ops.indices.reversed()) {
            when (val op = ops[i]) {
                is Op.Added -> if (notes.isNotEmpty()) notes.removeAt(notes.lastIndex)
                is Op.Removed -> notes.add(op.index.coerceIn(0, notes.size), op.note)
                is Op.Modified -> if (op.index in notes.indices) notes[op.index] = op.old
            }
        }
    }
}
