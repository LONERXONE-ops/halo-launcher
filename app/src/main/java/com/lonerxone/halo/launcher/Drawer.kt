package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * All your apps. Swipe up and the list rises out of the home screen (which
 * sinks back behind it), following your finger the whole way; let go and it
 * settles with a spring. A search pill, then your apps and folders in the
 * order you arranged them (A to Z until you move something). Swipe down
 * anywhere at the top of the list, or fling down, to close.
 */
@SuppressLint("ViewConstructor")
class Drawer(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun launch(app: App, from: View)
        fun appMenu(app: App, anchor: View)
        fun onDrawerProgress(progress: Float)
        /** Your folders in the app list: open one, or its menu (size, rename, remove). */
        fun openDrawerFolder(id: String, from: View)
        fun drawerFolderMenu(id: String, anchor: View)
    }

    /** One spot in the app list: an app, or one of your folders (0 small, 1 medium, 2 large). */
    private sealed class Entry {
        class AppE(val app: App) : Entry()
        class Folder(val id: String, val size: Int) : Entry()
    }

    /** With folders, the list is packed into a grid where folders take 1x1, 2x2 or 4x2. */
    private var entries: List<Entry> = emptyList()
    private var hasFolders = false
    private lateinit var folderScroll: android.widget.ScrollView
    private lateinit var span: SpanGrid
    private var spanKey = ""

    private val content: LinearLayout
    private val search: EditText
    private val grid: GridView
    private val usedRow: LinearLayout
    private val usedTitle: TextView
    private val pill: LinearLayout
    private val searchIcon: ImageView
    private val searchClose: ImageView
    private var searchOpen = false
    private var searchAnim: android.animation.ValueAnimator? = null
    private var navBottom = 0
    private var imeBottom = 0
    /** Beyond apps: a calculator answer, and searching the web or the Play Store. */
    private val extras: LinearLayout
    /** Arranging: the hint at the top and the Done button. */
    private val arrangeHint: TextView
    private val doneBtn: TextView

    private val pages: HomePager
    private val dots: ListDots
    private var pagesKey = ""
    private val adapter = AppsAdapter()
    private var shown: List<App> = emptyList()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private var downTopZone = false
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var prog = 0f
    var isOpen = false
        private set

    /** The app list's layout, columns and names (Settings → App list). */
    private val horizontal get() = Prefs.get(context).getInt(Prefs.DRAWER_LAYOUT, 0) == 1
    private val cols get() = if (Prefs.get(context).getInt(Prefs.DRAWER_COLS, 0) == 1) 5 else 4
    private val names get() = Prefs.get(context).getBoolean(Prefs.DRAWER_NAMES, true)
    /** Icon size follows Settings → App list → Icon size, sized down a little for 5 across. */
    private val iconDp: Int get() {
        val i = Prefs.get(context).getInt(Prefs.DRAWER_SIZE, 1).coerceIn(0, 2)
        return if (cols == 5) listOf(42, 48, 52)[i] else listOf(48, 54, 62)[i]
    }
    /** Changes when the look of a cell changes; recycled views from another look are thrown away. */
    private val cellKey get() = "$iconDp|$names"
    private var builtCellKey = ""

    /**
     * Liquid Glass over your picture; with Solid, near-black with an even
     * hint of your wallpaper's colour (no glow). The letter you're on, the
     * Done button and the page dots take your wallpaper's colour.
     */
    fun restyle() {
        val wc = Glass.wallColor(context)
        background = if (Glass.enabled(context)) {
            GlassDrawable(this, dpf(2f), darkness = 0.66f, bendPx = 0f, rimLight = false)
        } else {
            val c = wc ?: Color.BLACK
            android.graphics.drawable.ColorDrawable(Color.rgb((Color.red(c) * 0.09f).toInt(), (Color.green(c) * 0.09f).toInt(), (Color.blue(c) * 0.09f).toInt()))
        }
        val accent = if (wc == null) Palette.INK else Glass.accent(context)
        dots.accent = accent
        doneBtn.background = context.round(accent, dpf(22f))
        doneBtn.setTextColor(if (Color.red(accent) * 0.3 + Color.green(accent) * 0.59 + Color.blue(accent) * 0.11 > 150) Color.BLACK else Color.WHITE)
        pill.background = if (Glass.enabled(context)) GlassDrawable(pill, dpf(27f), darkness = 0.3f, bendPx = dpf(10f)) else context.round(Color.rgb(32, 32, 35), dpf(27f)).apply { setStroke(dp(1), Color.argb(30, 255, 255, 255)) }
    }

    init {
        isClickable = true
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        // Grab handle
        content.addView(View(context).apply {
            background = context.round(Color.argb(110, 255, 255, 255), dpf(2f))
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        // Search: a small round button floating at the bottom (One UI 9 style).
        // Tap it and it stretches into a search bar above the keyboard.
        pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = dpf(10f)
            isClickable = true
            setOnClickListener { if (!searchOpen) openSearch() }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
                }
            }
        }
        searchIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.INK)
        }
        pill.addView(searchIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
        search = EditText(context).apply {
            hint = "Search apps"
            setHintTextColor(Palette.WHITE50)
            setTextColor(Palette.INK)
            textSize = 16f
            typeface = Fonts.regular(context)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(12), 0, dp(4), 0)
            visibility = View.GONE
        }
        pill.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        searchClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_plus)
            rotation = 45f
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.WHITE70)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
            setOnClickListener { closeSearch(clear = true) }
        }
        pill.addView(searchClose, LinearLayout.LayoutParams(dp(32), dp(32)))

        usedTitle = context.text(10.5f, Palette.GREY, caps = true)
        usedRow = LinearLayout(context)

        grid = GridView(context).apply {
            numColumns = 4
            verticalSpacing = dp(18)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(14), dp(18), dp(120))
            clipToPadding = false
            // Apps fade out as they scroll up under the top, instead of being cut off.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(42))
            // A fling slows down sooner and stops where you expect.
            setFriction(ViewConfiguration.getScrollFriction() * 1.6f)
            adapter = this@Drawer.adapter
        }
        extras = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(extras, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // With your own folders: one tall grid where folders can be small, medium or large.
        span = SpanGrid(context, 4, dp(100)).apply { setPadding(0, dp(14), dp(18), dp(120)) }
        folderScroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(42))
            clipToPadding = false
            visibility = View.GONE
            addView(span)
            setOnScrollChangeListener { _, _, _, _, _ -> revealOnScroll() }
        }
        content.addView(folderScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // Sideways pages (Settings → App list → Horizontal), with dots under them.
        pages = HomePager(context).apply { visibility = View.GONE }
        pages.onScrolled = { f ->
            dots.position = f
            // Pages ease in and out as you swipe: a touch smaller and softer at the edges.
            for (i in 0 until pages.childCount) {
                val v = pages.getChildAt(i)
                val o = kotlin.math.abs(i - f).coerceAtMost(1f)
                val sc = 1f - 0.05f * o
                v.scaleX = sc
                v.scaleY = sc
                v.alpha = 1f - 0.45f * o
            }
        }
        content.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dots = ListDots(context).apply { visibility = View.GONE }
        content.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).apply { bottomMargin = dp(96) })
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Arranging: a hint at the top and a Done button where the search button sits.
        arrangeHint = context.text(12.5f, Palette.SILVER).apply {
            text = "Hold and drag apps or folders to move them"
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.GONE
        }
        addView(arrangeHint, LayoutParams(LayoutParams.MATCH_PARENT, dp(30), Gravity.TOP).apply { topMargin = dp(14) })
        doneBtn = context.text(15f, Color.BLACK, bold = true).apply {
            text = "Done"
            gravity = Gravity.CENTER
            setPadding(dp(30), 0, dp(30), 0)
            elevation = dpf(6f)
            visibility = View.GONE
            setOnClickListener {
                it.bounce()
                endArrange()
            }
        }
        addView(doneBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        addView(pill, LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        restyle()

        // Ride above the navigation bar, and follow the keyboard as it slides.
        setOnApplyWindowInsetsListener { _, insets ->
            navBottom = insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
            if (!imeAnimating) {
                imeBottom = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                placePill()
            }
            insets
        }
        setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(a: android.view.WindowInsetsAnimation) {
                if (a.typeMask and android.view.WindowInsets.Type.ime() != 0) imeAnimating = true
            }
            override fun onProgress(insets: android.view.WindowInsets, running: MutableList<android.view.WindowInsetsAnimation>): android.view.WindowInsets {
                if (imeAnimating) {
                    imeBottom = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    placePill()
                }
                return insets
            }
            override fun onEnd(a: android.view.WindowInsetsAnimation) {
                if (a.typeMask and android.view.WindowInsets.Type.ime() == 0) return
                imeAnimating = false
                val ime = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                imeBottom = ime
                placePill()
                // Keyboard put away with nothing typed: shrink back to the button.
                if (ime == 0 && searchOpen && search.text.isNullOrEmpty()) closeSearch(clear = false)
            }
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        search.setOnEditorActionListener { v, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                shown.firstOrNull()?.let { host.launch(it, v) }
                true
            } else {
                false
            }
        }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {}
        })
        visibility = View.INVISIBLE
    }

    private var imeAnimating = false

    private fun placePill() {
        val lp = pill.layoutParams as? LayoutParams ?: return
        lp.bottomMargin = maxOf(navBottom, imeBottom) + dp(16)
        pill.layoutParams = lp
    }

    /** The round button stretches into a search bar, then the keyboard comes up. */
    private fun openSearch() {
        searchOpen = true
        search.visibility = View.VISIBLE
        searchClose.visibility = View.VISIBLE
        search.alpha = 0f
        searchClose.alpha = 0f
        pill.setPadding(dp(18), 0, dp(10), 0)
        val to = (width - dp(40)).coerceAtLeast(dp(200))
        stretch(to, Spring(0.3f), 460L)
        search.animate().alpha(1f).setStartDelay(120).setDuration(220).start()
        searchClose.animate().alpha(1f).setStartDelay(160).setDuration(220).start()
        search.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch(clear: Boolean) {
        if (!searchOpen) return
        searchOpen = false
        if (clear && !search.text.isNullOrEmpty()) search.setText("")
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
        search.animate().alpha(0f).setStartDelay(0).setDuration(90).withEndAction { search.visibility = View.GONE }.start()
        searchClose.animate().alpha(0f).setStartDelay(0).setDuration(90).withEndAction { searchClose.visibility = View.GONE }.start()
        pill.setPadding(0, 0, 0, 0)
        stretch(dp(56), Spring(0.45f), 420L)
    }

    private fun stretch(to: Int, interp: android.view.animation.Interpolator, ms: Long) {
        searchAnim?.cancel()
        val from = pill.layoutParams.width.takeIf { it > 0 } ?: pill.width
        searchAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                val f = it.animatedValue as Float
                pill.layoutParams = pill.layoutParams.apply { width = (from + (to - from) * f).toInt().coerceAtLeast(dp(40)) }
                pill.invalidateOutline()
            }
            start()
        }
    }

    fun refresh() {
        grid.numColumns = cols
        if (cellKey != builtCellKey) {
            // Names or size changed: start the list fresh, or recycled cells keep the old look
            // (that's why some apps still showed names after turning them off).
            builtCellKey = cellKey
            grid.verticalSpacing = dp(if (names) 18 else 12)
            grid.adapter = adapter
            pagesKey = ""
        }
        filter()
    }

    /**
     * Horizontal: the apps in pages of whole rows that fit the screen, A to
     * Z. Built once and only rebuilt when the apps, columns, names or the
     * space change (swiping never builds anything).
     */
    private fun buildPages() {
        val h = pages.height
        if (h <= 0) {
            pages.post { if (pages.height > 0) buildPages() }
            return
        }
        val cell = cellH()
        val rows = (h / cell).coerceAtLeast(2)
        val key = entries.joinToString(",") { when (it) { is Entry.AppE -> it.app.key; is Entry.Folder -> it.id + it.size + Groups.folderApps(context, it.id).size } } + "|$cols|$names|$iconDp|$h"
        if (key == pagesKey) return
        pagesKey = key
        pages.removeAllViews()
        // Whole folders never split across pages: a page ends where the next thing doesn't fit.
        val placed = pack(entries, rows)
        val count = (placed.maxOfOrNull { it.page } ?: -1) + 1
        val grids = (0 until count).map { SpanGrid(context, cols, cell).also { g -> g.setPadding(0, dp(10), 0, 0); pages.addView(g) } }
        for (pl in placed) grids[pl.page].add(viewFor(pl.entry), pl.col, pl.row, pl.sx, pl.sy)
        dots.count = pages.childCount
        pages.snapTo(pages.current.coerceAtMost(pages.childCount - 1), animate = false)
    }

    private fun filter() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val hidden = Prefs.list(context, Prefs.HIDDEN).toSet()
        val all = AppRepo.apps.filter { it.key !in hidden || q.isNotEmpty() }
        shown = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
            .sortedBy { if (it.label.lowercase().startsWith(q)) 0 else 1 }
        // Your folders and every app that isn't in one, in the order you
        // arranged them. Until you move something it's folders, then A to Z;
        // anything new (an app you just installed) goes at the end.
        val folderIds = if (q.isEmpty()) DrawerFolders.ids(context) else emptyList()
        hasFolders = folderIds.isNotEmpty()
        val base = if (hasFolders) {
            val inside = DrawerFolders.foldered(context)
            folderIds.map { Entry.Folder(it, DrawerFolders.size(context, it)) } + shown.filter { it.key !in inside }.map { Entry.AppE(it) }
        } else {
            shown.map { Entry.AppE(it) }
        }
        entries = if (q.isEmpty()) ordered(base) else base
        // Searching shows results as a plain list; otherwise your chosen
        // layout (arranging always uses the scrolling one).
        val paged = horizontal && q.isEmpty() && !arranging
        val spanned = !paged && q.isEmpty()
        grid.visibility = if (paged || spanned) View.GONE else View.VISIBLE
        folderScroll.visibility = if (spanned) View.VISIBLE else View.GONE
        if (spanned) buildSpan()
        pages.visibility = if (paged) View.VISIBLE else View.GONE
        dots.visibility = if (paged && pages.childCount > 1) View.VISIBLE else View.GONE
        if (paged) {
            buildPages()
            dots.visibility = if (pages.childCount > 1) View.VISIBLE else View.GONE
        }
        adapter.notifyDataSetChanged()
        showExtras(search.text?.toString()?.trim().orEmpty())
    }

    private fun showExtras(q: String) {
        extras.removeAllViews()
        extras.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        if (q.isEmpty()) return
        fun row(icon: Int, title: String, sub: String, onTap: () -> Unit) {
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = context.round(Palette.TILE2, dpf(18f))
                setOnClickListener {
                    it.bounce()
                    onTap()
                }
            }
            r.addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.INK)
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            val t = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            t.addView(context.text(15f, Palette.INK, bold = true).apply { text = title })
            t.addView(context.text(12f, Palette.GREY).apply { text = sub })
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            extras.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        Calc.eval(q)?.let { v ->
            val shownV = Calc.format(v)
            row(R.drawable.ic_info, "= $shownV", "Tap to copy") {
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("result", shownV))
                android.widget.Toast.makeText(context, "Copied $shownV", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        row(R.drawable.ic_search, "Search the web", "\"$q\"") {
            open(android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q), "https://www.google.com/search?q=" + android.net.Uri.encode(q))
        }
        if (shown.isEmpty()) row(R.drawable.ic_apps, "Find \"$q\" on the Play Store", "Get an app") {
            open(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=" + android.net.Uri.encode(q) + "&c=apps")), "https://play.google.com/store/search?q=" + android.net.Uri.encode(q) + "&c=apps")
        }
    }

    private fun open(i: android.content.Intent, fallback: String) {
        try {
            context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(fallback)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    private fun iconFor(a: App): AppIconView = AppIconView(context, a, iconDp, names, liveGlass = false).apply {
        pressable()
        badge = HaloLink.badges[a.pkg] ?: 0
        // The view's current app (it's reused for other apps as the list scrolls).
        setOnClickListener { if (!arranging) host.launch((it as AppIconView).app, it) }
        setOnLongClickListener {
            if (!arranging) host.appMenu((it as AppIconView).app, it)
            true
        }
    }

    fun updateBadges() {
        adapter.notifyDataSetChanged()
        for (i in 0 until span.childCount) (span.getChildAt(i) as? AppIconView)?.let { it.badge = HaloLink.badges[it.app.pkg] ?: 0 }
        for (pi in 0 until pages.childCount) (pages.getChildAt(pi) as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) (g.getChildAt(i) as? AppIconView)?.let { it.badge = HaloLink.badges[it.app.pkg] ?: 0 } }
        for (i in 0 until usedRow.childCount) (usedRow.getChildAt(i) as? AppIconView)?.let { it.badge = HaloLink.badges[it.app.pkg] ?: 0 }
    }

    private fun cellH() = dp(iconDp + 7 + (if (names) 22 else 4) + 16)

    private class Placed(val entry: Entry, val page: Int, val col: Int, val row: Int, val sx: Int, val sy: Int)

    /**
     * Packs the list into a grid [cols] wide: each thing goes in the first
     * spot it fits, reading left to right, top to bottom. With [rowsPerPage],
     * rows are cut into pages and a folder never splits across two.
     */
    private fun pack(list: List<Entry>, rowsPerPage: Int = Int.MAX_VALUE): List<Placed> {
        val out = ArrayList<Placed>()
        val taken = HashSet<Long>()
        fun k(r: Int, c: Int) = r.toLong() * 64 + c
        var startRow = 0
        for (e in list) {
            val (sx, sy) = when (e) {
                is Entry.AppE -> 1 to 1
                is Entry.Folder -> when (e.size) {
                    2 -> minOf(4, cols) to 2
                    1 -> 2 to 2
                    else -> 1 to 1
                }
            }
            var r = startRow
            search@ while (true) {
                for (c in 0..cols - sx) {
                    // Inside one page only.
                    val page = r / rowsPerPage
                    if ((r + sy - 1) / rowsPerPage != page) continue
                    var free = true
                    for (dr in 0 until sy) for (dc in 0 until sx) if (k(r + dr, c + dc) in taken) free = false
                    if (free) {
                        for (dr in 0 until sy) for (dc in 0 until sx) taken += k(r + dr, c + dc)
                        out += Placed(e, if (rowsPerPage == Int.MAX_VALUE) 0 else page, c, if (rowsPerPage == Int.MAX_VALUE) r else r % rowsPerPage, sx, sy)
                        break@search
                    }
                }
                r++
            }
            // Keep filling from the first row that still has room.
            while ((0 until cols).all { k(startRow, it) in taken }) startRow++
        }
        return out
    }

    private fun viewFor(e: Entry): View = when (e) {
        is Entry.AppE -> iconFor(e.app).also { it.tag = "app:" + e.app.key }
        is Entry.Folder -> FolderTile(context, e.id, e.size, open = { if (!arranging) host.openDrawerFolder(e.id, it) }, launch = { a, v -> if (!arranging) host.launch(a, v) }, iconDp = iconDp, labels = names).apply {
            tag = e.id
            setOnLongClickListener {
                if (!arranging) host.drawerFolderMenu(e.id, it)
                true
            }
        }
    }

    /** Vertical: everything packed into one tall grid (folders take 1x1, 2x2 or 4x2). */
    private fun buildSpan() {
        val key = entries.joinToString(",") { when (it) { is Entry.AppE -> it.app.key; is Entry.Folder -> it.id + it.size + Groups.folderApps(context, it.id).joinToString() } } + "|$cols|$names|$iconDp"
        if (key == spanKey) return
        spanKey = key
        span.removeAllViews()
        span.setup(cols, cellH())
        for (pl in pack(entries)) span.add(viewFor(pl.entry), pl.col, pl.row, pl.sx, pl.sy)
        span.post { revealOnScroll() }
    }

    /**
     * Apps rise and fade in as they scroll up into view from the bottom,
     * instead of just appearing there.
     */
    private fun revealOnScroll() {
        if (arranging || held != null) return
        val vh = folderScroll.height
        if (vh <= 0) return
        val sy = folderScroll.scrollY
        val zone = dpf(110f)
        val bottom = vh - dpf(40f)
        for (i in 0 until span.childCount) {
            val c = span.getChildAt(i)
            val mid = c.top + c.height / 2f - sy
            if (mid < -c.height || mid > vh + c.height) continue
            val f = ((bottom - mid) / zone).coerceIn(0f, 1f)
            val e = 1f - (1f - f) * (1f - f)
            c.alpha = 0.15f + 0.85f * e
            val sc = 0.88f + 0.12f * e
            c.scaleX = sc
            c.scaleY = sc
            c.translationY = (1f - e) * dpf(14f)
        }
    }

    // ----- your own order -----

    private fun keyOf(e: Entry) = when (e) { is Entry.AppE -> e.app.key; is Entry.Folder -> e.id }

    /** [base] in your saved order; things you never placed keep their A to Z spot at the end. */
    private fun ordered(base: List<Entry>): List<Entry> {
        val order = Prefs.list(context, Prefs.DRAWER_ORDER)
        if (order.isEmpty()) return base
        val at = HashMap<String, Int>()
        order.forEachIndexed { i, k -> at.putIfAbsent(k, i) }
        return base.withIndex().sortedWith(compareBy({ at[keyOf(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    private var arranging = false
    private var held: View? = null
    private var heldKey: String? = null
    private var grabX = 0f
    private var grabY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var pickUp: Runnable? = null
    private var arrangeDownX = 0f
    private var arrangeDownY = 0f
    private var lastSwap = 0L

    val isArranging get() = arranging

    /**
     * Arrange mode: hold anything in the list and drag it where you want
     * it; the rest slide out of the way. Tap Done (or go back) to keep it.
     */
    fun arrange() {
        if (arranging) return
        arranging = true
        hideKeyboard()
        if (!search.text.isNullOrEmpty()) search.setText("")
        closeSearch(clear = true)
        spanKey = ""
        filter()
        pill.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(160).withEndAction { pill.visibility = View.GONE }.start()
        doneBtn.visibility = View.VISIBLE
        doneBtn.alpha = 0f
        doneBtn.scaleX = 0.7f
        doneBtn.scaleY = 0.7f
        doneBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        arrangeHint.visibility = View.VISIBLE
        arrangeHint.translationY = -dpf(10f)
        arrangeHint.animate().alpha(1f).translationY(0f).setDuration(260).start()
        // Everything settles in a touch smaller, so it reads as "movable".
        for (i in 0 until span.childCount) span.getChildAt(i).apply { alpha = 1f; translationY = 0f }.animate().scaleX(0.9f).scaleY(0.9f).setDuration(240).setStartDelay((i % 12) * 12L).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** Saves the order and goes back to normal. */
    fun endArrange() {
        if (!arranging) return
        drop()
        arranging = false
        Prefs.setList(context, Prefs.DRAWER_ORDER, entries.map { keyOf(it) })
        for (i in 0 until span.childCount) span.getChildAt(i).animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(220).start()
        doneBtn.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(160).withEndAction { doneBtn.visibility = View.GONE }.start()
        arrangeHint.animate().alpha(0f).setDuration(160).withEndAction { arrangeHint.visibility = View.GONE }.start()
        pill.visibility = View.VISIBLE
        pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(80).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        pagesKey = ""
        postDelayed({
            filter()
            revealOnScroll()
        }, 230)
    }

    private fun spanChildAt(x: Float, y: Float): View? {
        for (i in span.childCount - 1 downTo 0) {
            val c = span.getChildAt(i)
            if (c !== held && x >= c.left && x < c.right && y >= c.top && y < c.bottom) return c
        }
        return null
    }

    /** Finger position inside the grid (it scrolls, so this changes as it does). */
    private fun inSpan(rawX: Float, rawY: Float): Pair<Float, Float> {
        val l = IntArray(2)
        span.getLocationOnScreen(l)
        return (rawX - l[0]) to (rawY - l[1])
    }

    private fun pick(v: View) {
        held = v
        heldKey = v.tag as? String
        val (x, y) = inSpan(fingerX, fingerY)
        grabX = x - v.left
        grabY = y - v.top
        v.bringToFront()
        v.elevation = dpf(12f)
        v.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.92f).setStartDelay(0).setDuration(160).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        postOnAnimation(edgeScroll)
    }

    /** The held item follows the finger; when it's over another spot, things make room. */
    private fun follow() {
        val v = held ?: return
        val (x, y) = inSpan(fingerX, fingerY)
        v.translationX = x - grabX - v.left
        v.translationY = y - grabY - v.top
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSwap < 90) return
        val over = spanChildAt(x, y) ?: return
        val from = entries.indexOfFirst { keyOf(it) == heldKey }
        val to = entries.indexOfFirst { keyOf(it) == over.tag }
        if (from < 0 || to < 0 || from == to) return
        lastSwap = now
        val list = entries.toMutableList()
        list.add(to, list.removeAt(from))
        entries = list
        relayout()
    }

    /** Moves every item to its new spot and slides it there from where it was. */
    private fun relayout() {
        val before = HashMap<View, Pair<Int, Int>>()
        for (i in 0 until span.childCount) span.getChildAt(i).let { before[it] = it.left to it.top }
        val byKey = HashMap<String, View>()
        for (i in 0 until span.childCount) span.getChildAt(i).let { c -> (c.tag as? String)?.let { byKey[it] = c } }
        for (pl in pack(entries)) byKey[keyOf(pl.entry)]?.let { span.place(it, pl.col, pl.row, pl.sx, pl.sy) }
        span.requestLayout()
        span.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                span.viewTreeObserver.removeOnPreDrawListener(this)
                for ((c, old) in before) {
                    if (c === held) continue
                    val dx = (old.first - c.left).toFloat()
                    val dy = (old.second - c.top).toFloat()
                    if (dx == 0f && dy == 0f) continue
                    c.translationX = dx + c.translationX
                    c.translationY = dy + c.translationY
                    c.animate().translationX(0f).translationY(0f).setStartDelay(0).setDuration(260).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)).start()
                }
                follow()
                return true
            }
        })
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Near the top or bottom of the list while holding something: the list scrolls. */
    private val edgeScroll: Runnable = object : Runnable {
        override fun run() {
            if (held == null) return
            val l = IntArray(2)
            folderScroll.getLocationOnScreen(l)
            val zone = dpf(90f)
            val top = l[1] + zone
            val bottom = l[1] + folderScroll.height - zone
            val sp = when {
                fingerY < top -> -((top - fingerY) / zone).coerceAtMost(1f) * dpf(16f)
                fingerY > bottom -> ((fingerY - bottom) / zone).coerceAtMost(1f) * dpf(16f)
                else -> 0f
            }
            if (sp != 0f) {
                folderScroll.scrollBy(0, sp.toInt())
                follow()
            }
            postOnAnimation(this)
        }
    }

    /** Lets go: the item glides into its spot. */
    private fun drop() {
        pickUp?.let { removeCallbacks(it) }
        pickUp = null
        val v = held ?: return
        held = null
        heldKey = null
        removeCallbacks(edgeScroll)
        v.animate().translationX(0f).translationY(0f).scaleX(if (arranging) 0.9f else 1f).scaleY(if (arranging) 0.9f else 1f).alpha(1f)
            .setStartDelay(0).setDuration(280).setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
            .withEndAction { v.elevation = 0f }.start()
    }

    /** Arrange-mode touches: hold to pick up, a quick swipe still scrolls. */
    private fun arrangeTouch(e: MotionEvent): Boolean {
        fingerX = e.rawX
        fingerY = e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                arrangeDownX = e.rawX
                arrangeDownY = e.rawY
                val (x, y) = inSpan(e.rawX, e.rawY)
                val v = if (folderScroll.visibility == View.VISIBLE) spanChildAt(x, y) else null
                if (v != null) {
                    pickUp = Runnable { pickUp = null; pick(v) }.also { postDelayed(it, 170) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (held != null) {
                    follow()
                    return true
                }
                if (pickUp != null && (abs(e.rawX - arrangeDownX) > slop || abs(e.rawY - arrangeDownY) > slop)) {
                    removeCallbacks(pickUp)
                    pickUp = null
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = held != null
                drop()
                return was
            }
        }
        return held != null
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val a = shown[position]
            // Reuse the view that scrolled off (rebuilding a new one for every row made scrolling stutter).
            val old = (convertView as? AppIconView)?.takeIf { it.tag == cellKey }
            val v = old?.also { it.rebind(a) } ?: iconFor(a).also { it.tag = cellKey }
            v.badge = HaloLink.badges[a.pkg] ?: 0
            return v
        }
    }

    // ----- open / close -----

    /** Follows the finger (stops any animation first). */
    fun drag(p: Float) {
        anim?.cancel()
        // Drawn as one layer while following the finger (fading the whole list
        // every frame without one is what made dragging it up stutter).
        if (layerType != View.LAYER_TYPE_HARDWARE) setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setProgress(p)
    }

    /**
     * 0 = closed, 1 = open. The sheet fades in over the home screen while
     * its contents rise and grow into place, so it never looks like a
     * curtain being pulled up.
     */
    fun setProgress(p0: Float) {
        val p = p0.coerceIn(0f, 1f)
        prog = p
        visibility = if (p <= 0.001f) View.INVISIBLE else View.VISIBLE
        alpha = (p * 1.6f).coerceAtMost(1f)
        val e = 1f - p
        content.translationY = e * dpf(90f)
        content.alpha = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
        content.scaleX = 0.94f + 0.06f * p
        content.scaleY = 0.94f + 0.06f * p
        // The search button pops up last.
        val sp = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
        pill.alpha = sp
        pill.scaleX = 0.7f + 0.3f * sp
        pill.scaleY = pill.scaleX
        host.onDrawerProgress(p)
    }

    fun progress(): Float = prog

    fun open() {
        isOpen = true
        visibility = View.VISIBLE
        refresh()
        animateTo(1f)
    }

    /** Closes, keeping your place in the list for next time (search is cleared). */
    fun close() {
        endArrange()
        isOpen = false
        hideKeyboard()
        if (!search.text.isNullOrEmpty()) search.setText("")
        closeSearch(clear = true)
        animateTo(0f)
    }

    private var anim: android.animation.ValueAnimator? = null

    /**
     * One animation at a time (a new open/close cancels the last). Opening
     * settles with a gentle overshoot-free spring curve; closing is quicker.
     */
    fun animateTo(target: Float, velocity: Float = 0f) {
        anim?.cancel()
        val start = prog
        val dist = abs(target - start)
        if (dist < 0.001f) {
            setLayerType(View.LAYER_TYPE_NONE, null)
            setProgress(target)
            isOpen = target > 0.5f
            return
        }
        val a = android.animation.ValueAnimator.ofFloat(start, target)
        val fast = (abs(velocity) / 4000f).coerceIn(0f, 0.5f)
        a.duration = ((if (target > start) 420 else 220) * (0.45f + 0.55f * dist) * (1f - fast)).toLong().coerceAtLeast(120)
        // Glides in and eases to a stop (no bounce past the top); closing is a touch quicker.
        a.interpolator = if (target > start) android.view.animation.PathInterpolator(0.2f, 0.85f, 0.25f, 1f) else android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f)
        a.addUpdateListener { setProgress(it.animatedValue as Float) }
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                setLayerType(View.LAYER_TYPE_NONE, null)
            }
        })
        a.start()
        anim = a
        isOpen = target > 0.5f
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
    }

    // ----- swipe down to close -----
    // From the top area (handle, search, most used) a downward swipe always
    // closes; in the list it closes once the list is at its top. A quick
    // fling down closes even from a short swipe.

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (arranging) return arrangeTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY
                downX = e.rawX
                dragging = false
                val gl = IntArray(2)
                // Whichever list is showing (the others keep a stale spot).
                (if (folderScroll.visibility == View.VISIBLE) folderScroll else if (pages.visibility == View.VISIBLE) pages else grid).getLocationOnScreen(gl)
                downTopZone = e.rawY < gl[1]
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dy = e.rawY - downY
                val dx = e.rawX - downX
                val atTop = if (folderScroll.visibility == View.VISIBLE) !folderScroll.canScrollVertically(-1) else !grid.canScrollVertically(-1)
                if (dy > slop && dy > abs(dx) * 1.2f && (downTopZone || atTop)) {
                    dragging = true
                    downY = e.rawY
                    hideKeyboard()
                    anim?.cancel()
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (arranging) {
            arrangeTouch(e)
            return true
        }
        if (!dragging) return super.onTouchEvent(e)
        tracker?.addMovement(e)
        val h = height.toFloat().coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> setProgress(1f - ((e.rawY - downY) / (h * 0.42f)).coerceAtLeast(0f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                if (vy > 500f || (vy > -500f && prog < 0.75f)) {
                    isOpen = false
                    hideKeyboard()
                    if (!search.text.isNullOrEmpty()) search.setText("")
                    closeSearch(clear = true)
                    animateTo(0f, vy)
                } else {
                    animateTo(1f, vy)
                }
            }
        }
        return true
    }
}

/** A small calculator for the search box: + - × ÷ ^ %, brackets, decimals. */
object Calc {
    fun eval(src: String): Double? {
        val s = src.replace(" ", "").replace('×', '*').replace('x', '*').replace('÷', '/').replace(',', '.')
        if (s.none { it in "+-*/^%" } || s.any { it !in "0123456789.+-*/^%()" }) return null
        return try {
            val p = P(s)
            val v = p.expr()
            if (p.i != s.length || v.isNaN() || v.isInfinite()) null else v
        } catch (_: Exception) {
            null
        }
    }

    fun format(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')

    private class P(val s: String) {
        var i = 0
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]
                if (op == '%' && (i >= s.length || s[i] in "+-*/)")) {
                    v /= 100.0
                    continue
                }
                val r = power()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }
        fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(b, power())
            }
            return b
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') {
                i++
                return -unary()
            }
            return atom()
        }
        fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }
}


/** Page dots for the sideways app list: the current one is a longer pill in your colour. */
class ListDots(context: Context) : View(context) {
    var count = 0
        set(v) {
            field = v
            invalidate()
        }
    var position = 0f
        set(v) {
            field = v
            invalidate()
        }
    var accent = Palette.INK
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()

    override fun onDraw(c: Canvas) {
        if (count < 2) return
        val d = dpf(6f)
        val gap = dpf(7f)
        val long = dpf(18f)
        val total = (count - 1) * (d + gap) + long
        var x = (width - total) / 2f
        val cy = height / 2f
        for (i in 0 until count) {
            // How "current" this dot is (0..1), so the pill slides between dots as you swipe.
            val k = (1f - abs(position - i)).coerceIn(0f, 1f)
            val w = d + (long - d) * k
            r.set(x, cy - d / 2f, x + w, cy + d / 2f)
            p.color = if (k > 0.5f) accent else Color.argb(90, 255, 255, 255)
            c.drawRoundRect(r, d / 2f, d / 2f, p)
            x += w + gap
        }
    }
}


/**
 * A grid where each child takes [sx] x [sy] cells (an app 1x1, a folder 1x1,
 * 2x2 or 4x2). [cols] across, each row [cellH] tall.
 */
class SpanGrid(context: Context, private var cols: Int, private var cellH: Int) : ViewGroup(context) {
    private class Spot(var col: Int, var row: Int, var sx: Int, var sy: Int) : ViewGroup.LayoutParams(0, 0)

    fun setup(cols: Int, cellH: Int) {
        this.cols = cols
        this.cellH = cellH
        requestLayout()
    }

    fun add(v: View, col: Int, row: Int, sx: Int, sy: Int) = addView(v, Spot(col, row, sx, sy))

    /** Moves a child to another spot (call requestLayout after moving them all). */
    fun place(v: View, col: Int, row: Int, sx: Int, sy: Int) {
        (v.layoutParams as? Spot)?.let {
            it.col = col
            it.row = row
            it.sx = sx
            it.sy = sy
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cw = (w - paddingLeft - paddingRight) / cols.coerceAtLeast(1)
        var rows = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val s = c.layoutParams as Spot
            rows = maxOf(rows, s.row + s.sy)
            c.measure(MeasureSpec.makeMeasureSpec(cw * s.sx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH * s.sy, MeasureSpec.EXACTLY))
        }
        val h = MeasureSpec.getMode(heightMeasureSpec).let { m -> if (m == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else rows * cellH + paddingTop + paddingBottom }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l - paddingLeft - paddingRight) / cols.coerceAtLeast(1)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val s = c.layoutParams as Spot
            val x = paddingLeft + s.col * cw
            val y = paddingTop + s.row * cellH
            c.layout(x, y, x + cw * s.sx, y + cellH * s.sy)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is Spot
}
