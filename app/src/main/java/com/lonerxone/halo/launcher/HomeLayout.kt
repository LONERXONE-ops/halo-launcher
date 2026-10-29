package com.lonerxone.halo.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where everything on the home screen lives: a grid of 4 columns per page,
 * like iOS and One UI. Each item has a page, a cell and a size in cells, so
 * you can put anything anywhere and leave gaps where you want them.
 *
 * Items: "clock", "music", "w:<id>" (Halo widgets), "aw:<appWidgetId>"
 * (Android widgets).
 */
object HomeLayout {
    const val COLS = 4

    class Item(val id: String, var page: Int, var col: Int, var row: Int, var sx: Int, var sy: Int) {
        fun copy() = Item(id, page, col, row, sx, sy)
        fun overlaps(o: Item) = page == o.page && col < o.col + o.sx && o.col < col + sx && row < o.row + o.sy && o.row < row + sy
    }

    private const val KEY = "home_layout"
    /** Rows per page; set from the real screen by the launcher. */
    var rows = 7
        private set
    private var items: MutableList<Item>? = null

    /** Rows that fit the screen (estimated until the launcher measures it). */
    fun estimateRows(c: Context): Int {
        val dm = c.resources.displayMetrics
        val wDp = dm.widthPixels / dm.density
        val hDp = dm.heightPixels / dm.density
        val cell = (wDp - 32f) / COLS
        // Status bar, top padding, dots, the dock and its gap, the gesture bar.
        val avail = hDp + 48f - (40f + 16f + 26f + 96f + 48f)
        return (avail / cell).toInt().coerceIn(5, 9)
    }

    private var measured = false

    fun setRows(c: Context, r: Int) {
        if (r < 4 || (measured && r == rows)) return
        measured = true
        rows = r
        all(c) // make sure loaded
        normalize(c)
    }

    fun all(c: Context): MutableList<Item> {
        items?.let { return it }
        if (!measured) rows = estimateRows(c)
        val raw = Prefs.get(c).getString(KEY, null)
        val list = if (raw == null) defaults(c) else parse(raw)
        items = list
        if (raw == null) save(c)
        upgrade(c)
        normalize(c)
        return list
    }

    fun get(c: Context, id: String): Item? = all(c).firstOrNull { it.id == id }

    fun has(c: Context, id: String) = get(c, id) != null

    fun pages(c: Context): Int = max(2, (all(c).maxOfOrNull { it.page } ?: 0) + 1)

    fun save(c: Context) {
        val list = items ?: return
        Prefs.get(c).edit().putString(KEY, list.joinToString("|") { "${it.id}@${it.page},${it.col},${it.row},${it.sx},${it.sy}" }).apply()
    }

    private fun parse(raw: String): MutableList<Item> = raw.split('|').mapNotNull { e ->
        val at = e.indexOf('@')
        if (at <= 0) return@mapNotNull null
        val n = e.substring(at + 1).split(',').mapNotNull { it.toIntOrNull() }
        if (n.size != 5) null else Item(e.substring(0, at), n[0], n[1], n[2], n[3], n[4])
    }.distinctBy { it.id }.toMutableList()

    /** Widget size names → cells: Small 2x2, Medium 4x2, Large 4x4. */
    fun spansFor(size: Int): Pair<Int, Int> = when (size) {
        HomeWidgets.LARGE -> 4 to 4
        HomeWidgets.MEDIUM -> 4 to 2
        else -> 2 to 2
    }

    fun sizeOf(it: Item): Int = when {
        it.sx >= 4 && it.sy >= 4 -> HomeWidgets.LARGE
        it.sx >= 4 -> HomeWidgets.MEDIUM
        else -> HomeWidgets.SMALL
    }

    /** First layout (and the move from older versions): clock up top and music at the bottom of page 1, widgets on page 2. */
    private fun defaults(c: Context): MutableList<Item> {
        val p = Prefs.get(c)
        val list = mutableListOf<Item>()
        items = list
        list += Item("clock", 0, 0, 0, 2, 2)
        list += Item("date", 0, 2, 0, 2, 1)
        list += Item("wx", 0, 2, 1, 2, 1)
        if (!p.getBoolean(Prefs.MUSIC_OFF, false)) list += Item("w:music", 0, 0, rows - 2, 4, 2)
        for (id in HomeWidgets.order(c)) {
            if (!Prefs.widgetOnLegacy(c, id)) continue
            val (sx, sy) = spansFor(p.getInt("wsize_$id", HomeWidgets.spec(id).defaultSize))
            place(c, Item("w:$id", 1, 0, 0, sx, sy), preferPage = 1)
        }
        for (idStr in Prefs.list(c, Prefs.WIDGETS)) {
            val hDp = p.getInt("wh_$idStr", 180)
            place(c, Item("aw:$idStr", 1, 0, 0, 4, awRows(c, hDp)), preferPage = 1)
        }
        return list
    }

    fun awRows(c: Context, hDp: Int): Int {
        val dm = c.resources.displayMetrics
        val cellDp = (dm.widthPixels / dm.density - 32f) / COLS
        return (hDp / cellDp).roundToInt().coerceIn(1, 4)
    }

    /**
     * Older layouts had the date and weather inside the clock (4 wide).
     * Now they're their own items: shrink the clock and put them beside it.
     */
    private fun upgrade(c: Context) {
        val p = Prefs.get(c)
        // The music player became a widget like the others.
        items?.let { list ->
            val i = list.indexOfFirst { it.id == "music" }
            if (i >= 0) {
                val o = list[i]
                list[i] = Item("w:music", o.page, o.col, o.row, o.sx, o.sy)
                save(c)
            }
        }
        if (p.getBoolean("layout_v2", false)) return
        val list = items ?: return
        val clock = list.firstOrNull { it.id == "clock" }
        if (clock != null && list.none { it.id == "date" }) {
            if (clock.sx == 4) clock.sx = 2
            place(c, Item("date", clock.page, 2, clock.row, 2, 1), clock.page, 2 to clock.row)
            place(c, Item("wx", clock.page, 2, clock.row + 1, 2, 1), clock.page, 2 to clock.row + 1)
        }
        p.edit().putBoolean("layout_v2", true).apply()
        save(c)
    }

    /** Pull anything that no longer fits (fewer rows) back onto the grid. */
    private fun normalize(c: Context) {
        val list = items ?: return
        val bad = list.filter { it.row + it.sy > rows || it.col + it.sx > COLS || it.col < 0 || it.row < 0 || list.any { o -> o !== it && o.overlaps(it) && list.indexOf(o) < list.indexOf(it) } }
        if (bad.isEmpty()) return
        list.removeAll(bad)
        for (b in bad) {
            b.sy = b.sy.coerceAtMost(rows)
            place(c, b, preferPage = b.page, near = b.col to b.row.coerceAtMost(rows - b.sy))
        }
        save(c)
    }

    fun fits(c: Context, page: Int, col: Int, row: Int, sx: Int, sy: Int, ignore: String?): Boolean {
        if (col < 0 || row < 0 || col + sx > COLS || row + sy > rows) return false
        val probe = Item("", page, col, row, sx, sy)
        return all(c).none { it.id != ignore && it.overlaps(probe) }
    }

    /** The free spot on [page] closest to (col, row), or null if the page is full. */
    fun nearestFree(c: Context, page: Int, col: Int, row: Int, sx: Int, sy: Int, ignore: String?): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestD = Float.MAX_VALUE
        for (r in 0..rows - sy) for (cc in 0..COLS - sx) {
            if (!fits(c, page, cc, r, sx, sy, ignore)) continue
            val d = abs(cc - col) * 1.2f + abs(r - row).toFloat()
            if (d < bestD) {
                bestD = d
                best = cc to r
            }
        }
        return best
    }

    /** Adds [it] at the nearest free spot, on [preferPage] or the next page with room. */
    fun place(c: Context, it: Item, preferPage: Int, near: Pair<Int, Int> = 0 to 0) {
        val list = all(c)
        list.removeAll { o -> o.id == it.id }
        var page = preferPage.coerceAtLeast(0)
        while (true) {
            val spot = nearestFree(c, page, near.first, near.second, it.sx, it.sy, it.id)
            if (spot != null) {
                it.page = page
                it.col = spot.first
                it.row = spot.second
                list += it
                return
            }
            page++
            if (page > 20) return
        }
    }

    fun add(c: Context, id: String, sx: Int, sy: Int, preferPage: Int) {
        place(c, Item(id, preferPage, 0, 0, sx, sy), preferPage)
        save(c)
    }

    fun remove(c: Context, id: String) {
        all(c).removeAll { it.id == id }
        compactPages(c)
        save(c)
    }

    /** Resize in place if there's room, else move to the nearest place that fits. */
    fun resize(c: Context, id: String, sx: Int, sy: Int) {
        val it = get(c, id) ?: return
        val col = it.col.coerceAtMost(COLS - sx)
        if (fits(c, it.page, col, it.row, sx, sy, id)) {
            it.col = col
            it.sx = sx
            it.sy = sy
        } else {
            val moved = it.copy().apply {
                this.sx = sx
                this.sy = sy
            }
            place(c, moved, it.page, it.col to it.row)
        }
        save(c)
    }

    /** Gives an item a new id in the same place (an app becoming a folder, a widget a stack). */
    fun swapId(c: Context, old: String, new: String, sx: Int? = null, sy: Int? = null) {
        val list = all(c)
        val i = list.indexOfFirst { it.id == old }
        if (i < 0) return
        val o = list[i]
        list[i] = Item(new, o.page, o.col, o.row, sx ?: o.sx, sy ?: o.sy)
        save(c)
    }

    /** Drop the cached layout (after a restore). */
    fun forget() {
        items = null
    }

    fun moveTo(c: Context, id: String, page: Int, col: Int, row: Int) {
        val it = get(c, id) ?: return
        it.page = page
        it.col = col
        it.row = row
        compactPages(c)
        save(c)
    }

    /** Drop empty pages past page 2, so there are no blank pages in the middle. */
    private fun compactPages(c: Context) {
        val list = all(c)
        val used = list.map { it.page }.toSortedSet()
        var next = 0
        val remap = HashMap<Int, Int>()
        for (p in 0..(used.maxOrNull() ?: 0)) {
            if (p in used || p < 2) {
                remap[p] = next++
            }
        }
        for (it in list) it.page = remap[it.page] ?: it.page
    }
}

/**
 * One home page: lays its children out on the grid by their HomeLayout cell,
 * with even gaps. In edit mode it also draws faint cell dots and, while
 * dragging, an outline where the item will land.
 */
class HomeGrid(context: Context, val page: Int) : ViewGroup(context) {
    class LP(var col: Int, var row: Int, var sx: Int, var sy: Int) : ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    private val gap = dpf(12f)
    var showDots = false
        set(v) {
            field = v
            invalidate()
        }
    /** Where the dragged item would land (cells), or null. */
    var preview: IntArray? = null
        set(v) {
            field = v
            invalidate()
        }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
        color = Color.argb(200, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(dpf(8f), dpf(6f)), 0f)
    }
    private val previewFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 255, 255, 255) }
    /** True when the drop will put the item into another (a folder or a stack). */
    var merging = false
        set(v) {
            field = v
            invalidate()
        }
    private val mergeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val mergeRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val r = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    fun cellW() = (width - paddingLeft - paddingRight).toFloat() / HomeLayout.COLS
    fun cellH() = (height - paddingTop - paddingBottom).toFloat() / HomeLayout.rows

    /** The cell under a point in this view's coordinates. */
    fun cellAt(x: Float, y: Float): Pair<Int, Int> =
        ((x - paddingLeft) / cellW()).toInt() to ((y - paddingTop) / cellH()).toInt()

    fun rectOf(col: Int, row: Int, sx: Int, sy: Int, out: RectF): RectF {
        val cw = cellW()
        val ch = cellH()
        out.set(
            paddingLeft + col * cw + gap / 2f,
            paddingTop + row * ch + gap / 2f,
            paddingLeft + (col + sx) * cw - gap / 2f,
            paddingTop + (row + sy) * ch - gap / 2f,
        )
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - paddingLeft - paddingRight).toFloat() / HomeLayout.COLS
        val ch = (h - paddingTop - paddingBottom).toFloat() / HomeLayout.rows
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as? LP ?: continue
            v.measure(
                MeasureSpec.makeMeasureSpec((lp.sx * cw - gap).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((lp.sy * ch - gap).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, rr: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as? LP ?: continue
            rectOf(lp.col, lp.row, lp.sx, lp.sy, r)
            v.layout(r.left.roundToInt(), r.top.roundToInt(), r.left.roundToInt() + v.measuredWidth, r.top.roundToInt() + v.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (showDots) {
            val cw = cellW()
            val ch = cellH()
            for (row in 1 until HomeLayout.rows) for (col in 1 until HomeLayout.COLS) {
                canvas.drawCircle(paddingLeft + col * cw, paddingTop + row * ch, dpf(1.6f), dotPaint)
            }
        }
        preview?.let { p ->
            rectOf(p[0], p[1], p[2], p[3], r)
            if (merging) {
                // Grows a little, solid: "drop to put it in here".
                r.inset(-dpf(4f), -dpf(4f))
                mergeRim.strokeWidth = dpf(2.5f)
                canvas.drawRoundRect(r, dpf(24f), dpf(24f), mergeFill)
                canvas.drawRoundRect(r, dpf(24f), dpf(24f), mergeRim)
            } else {
                canvas.drawRoundRect(r, dpf(26f), dpf(26f), previewFill)
                canvas.drawRoundRect(r, dpf(26f), dpf(26f), previewPaint)
            }
        }
    }
}
