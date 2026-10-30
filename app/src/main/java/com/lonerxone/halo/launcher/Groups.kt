package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Folders and widget stacks: things that hold other things.
 *
 * Folders are made the iOS way (drop an app onto another app on the home
 * screen) but look like Halo: a pane of the theme's surface (Liquid Glass,
 * tinted by Adaptive colour) with the apps' icons inside. Small folders
 * (1x1) open into a sheet; large ones (2x2) let you open three apps
 * straight from the home screen.
 *
 * Widget stacks are made by dropping a widget onto another of the same
 * size; swipe up or down on the stack to flip through them.
 */
object Groups {
    // ----- folders: "folder:<n>" -----

    fun folderApps(c: Context, id: String): MutableList<String> = Prefs.list(c, "fapps_" + id.removePrefix("folder:"))

    fun setFolderApps(c: Context, id: String, keys: List<String>) = Prefs.setList(c, "fapps_" + id.removePrefix("folder:"), keys.distinct())

    fun folderName(c: Context, id: String): String =
        Prefs.get(c).getString("fname_" + id.removePrefix("folder:"), null) ?: suggestName(c, folderApps(c, id))

    fun setFolderName(c: Context, id: String, name: String) {
        Prefs.get(c).edit().putString("fname_" + id.removePrefix("folder:"), name.trim().ifEmpty { "Folder" }).apply()
    }

    /** A name from what's inside (Social, Games…), like iOS does. */
    private fun suggestName(c: Context, keys: List<String>): String {
        val groups = keys.mapNotNull { AppRepo.byKey(it) }.map { AppRepo.group(it) }
        return groups.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Folder"
    }

    fun newFolderId() = "folder:" + System.currentTimeMillis().toString(36)

    // ----- stacks: "stack:<n>" -----

    fun stackWidgets(c: Context, id: String): MutableList<String> = Prefs.list(c, "stack_" + id.removePrefix("stack:"))

    fun setStackWidgets(c: Context, id: String, ids: List<String>) = Prefs.setList(c, "stack_" + id.removePrefix("stack:"), ids.distinct())

    fun newStackId() = "stack:" + System.currentTimeMillis().toString(36)

    /** Is this Halo widget inside any stack on the home screen? */
    fun inStack(c: Context, widgetId: String): Boolean =
        HomeLayout.all(c).any { it.id.startsWith("stack:") && widgetId in stackWidgets(c, it.id) }
}

/**
 * A folder on the home screen. Small: a 2x2 grid of tiny icons. Large: the
 * folder's name, then three apps you can open directly and a "+N" that
 * opens the rest.
 */
@SuppressLint("ViewConstructor")
class FolderTile(
    context: Context,
    val folderId: String,
    private val large: Boolean,
    private val open: (View) -> Unit,
    private val launch: (App, View) -> Unit,
    /** The home screen's app icon size, so a small folder is exactly as big as an app. */
    private val iconDp: Int = 62,
    /** Names under apps on the home screen: the folder gets its name under it too. */
    private val labels: Boolean = false,
) : FrameLayout(context) {
    private val pane = FrameLayout(context)
    // Only apps that are still installed (an uninstalled app used to leave a gap).
    private val apps: List<App> = Groups.folderApps(context, folderId).mapNotNull { AppRepo.byKey(it) }
    private var builtSide = 0
    private val name = if (!large && labels) context.text(11.5f, Palette.INK).apply {
        text = Groups.folderName(context, folderId)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setShadowLayer(dpf(3f), 0f, dpf(1f), Color.argb(150, 0, 0, 0))
    } else null

    init {
        addView(pane, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        name?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)) }
        setOnClickListener {
            it.bounce()
            open(this)
        }
    }

    /**
     * Built when it actually has a size (not "a moment later", which can be
     * before the home screen is even laid out when it's rebuilt in the
     * background, and left the folder empty).
     */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // Small: the same size as an app icon on your home screen (it used to
        // fill its whole cell and look bigger than the apps around it).
        val side = if (large) minOf(w, h) else minOf(dp(iconDp), minOf(w, h) - dp(6))
        if (side <= 0 || side == builtSide) return
        builtSide = side
        post { setup(side) }
    }

    private fun setup(side: Int) {
        run {
            if (name != null) {
                // Sits where an app's icon sits, with its name under it, like the apps.
                pane.layoutParams = LayoutParams(side, side, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(7) }
                name.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(7) + side + dp(4) }
            } else {
                pane.layoutParams = LayoutParams(side, side, Gravity.CENTER)
            }
            // Rounded like an app icon (clipped too, so nothing ever pokes out square).
            val rad = side * if (large) 0.18f else 0.26f
            pane.background = pane.surface(rad, 0.12f)
            pane.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, rad)
                }
            }
            pane.clipToOutline = true
            build(pane, apps, side)
        }
    }

    private fun build(pane: FrameLayout, apps: List<App>, side: Int) {
        pane.removeAllViews()
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val pad = if (large) side / 10 else side / 7
        if (large) {
            grid.addView(context.text(11f, Palette.SILVER, caps = true).apply {
                text = Groups.folderName(context, folderId)
                setPadding(dp(4), 0, 0, dp(6))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val cellPx = (side - pad * 2 - (if (large) dp(22) else 0)) / 2
        val iconDp = (cellPx * (if (large) 0.78f else 0.86f) / resources.displayMetrics.density).toInt().coerceAtLeast(14)
        val show = if (large && apps.size > 4) apps.take(3) else apps.take(4)
        for (r in 0 until 2) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (c in 0 until 2) {
                val i = r * 2 + c
                val v: View = when {
                    i < show.size -> AppIconView(context, show[i], iconDp, false, liveGlass = false).apply {
                        badge = if (large) HaloLink.badges[show[i].pkg] ?: 0 else 0
                        if (large) {
                            setOnClickListener { launch(show[i], it) }
                        } else {
                            isClickable = false
                        }
                    }
                    large && i == 3 && apps.size > 4 -> context.text(15f, Palette.INK, bold = true).apply {
                        text = "+${apps.size - 3}"
                        gravity = Gravity.CENTER
                        background = context.round(Color.argb(40, 255, 255, 255), dpf(14f))
                        setOnClickListener { open(this@FolderTile) }
                    }
                    else -> View(context)
                }
                row.addView(v, LinearLayout.LayoutParams(cellPx, cellPx))
            }
            grid.addView(row)
        }
        pane.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(pad, pad, pad, pad) })
    }
}

/**
 * An open folder. The home screen dims and a pane of glass grows out of the
 * folder tile: the name big at the top (tap it to type a new one right
 * there), how many apps, then the apps four across (scrolling, with soft
 * edges, when there are many), and an Add apps button. Long-press an app to
 * take it out. Closing shrinks it back into the folder.
 */
@SuppressLint("ViewConstructor")
class FolderSheet(
    context: Context,
    private val folderId: String,
    private val host: Host,
) : FrameLayout(context) {
    interface Host {
        fun launch(app: App, from: View)
        fun rename(id: String)
        fun addApps(id: String)
        fun removeFromFolder(id: String, app: App, anchor: View)
        fun closed()
    }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(18))
    }
    private var from: View? = null
    private val title: android.widget.EditText

    init {
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        isClickable = true
        setOnClickListener { close() }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
        })
        card.isClickable = true
        card.background = card.surface(dpf(34f), 0.32f)
        val apps = Groups.folderApps(context, folderId).mapNotNull { AppRepo.byKey(it) }

        // The name: plain big text until you tap it, then you type right there.
        title = android.widget.EditText(context).apply {
            setText(Groups.folderName(context, folderId))
            textSize = 26f
            typeface = Fonts.semibold(context)
            setTextColor(Palette.INK)
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener {
                isFocusableInTouchMode = true
                isFocusable = true
                requestFocus()
                setSelection(text.length)
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(this, 0)
            }
            setOnEditorActionListener { v, _, _ ->
                saveName()
                v.clearFocus()
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
        }
        card.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(context.text(12.5f, Palette.GREY).apply {
            text = (if (apps.size == 1) "1 app" else "${apps.size} apps") + "  ·  tap the name to rename"
            setPadding(0, dp(4), 0, dp(16))
        })

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        for ((i, a) in apps.withIndex()) {
            if (i % 4 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            }
            row?.addView(AppIconView(context, a, 56, true, liveGlass = false).apply {
                badge = HaloLink.badges[a.pkg] ?: 0
                pressable()
                setOnClickListener { host.launch(a, it) }
                setOnLongClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    host.removeFromFolder(this@FolderSheet.folderId, a, it)
                    true
                }
                // The apps pop in one after another as the folder opens.
                alpha = 0f
                scaleX = 0.6f
                scaleY = 0.6f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90L + i.coerceAtMost(16) * 18L).setDuration(320L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val rem = apps.size % 4
        if (rem != 0) repeat(4 - rem) { row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
        // Many apps: the grid scrolls inside the folder (with soft edges) instead of running off the screen.
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val scroller = object : android.widget.ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(grid)
        }
        card.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Add apps: one wide button at the bottom.
        card.addView(context.text(14.5f, Color.BLACK, bold = true).apply {
            text = "Add apps"
            gravity = Gravity.CENTER
            background = context.round(Palette.INK, dpf(23f))
            pressable()
            setOnClickListener { host.addApps(this@FolderSheet.folderId) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
    }

    private fun saveName() {
        val t = title.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty() && t != Groups.folderName(context, folderId)) {
            Groups.setFolderName(context, folderId, t)
            renamed = true
        }
    }

    private var renamed = false

    /** Grows out of [from] (the folder tile). */
    fun show(from: View) {
        this.from = from
        alpha = 0f
        animate().alpha(1f).setDuration(200).start()
        card.post {
            pivotOn(from)
            card.scaleX = 0.25f
            card.scaleY = 0.25f
            card.alpha = 0.4f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(Spring(0.45f)).setDuration(460).start()
        }
    }

    private fun pivotOn(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val cl = IntArray(2)
        card.getLocationOnScreen(cl)
        card.pivotX = (loc[0] + v.width / 2f - cl[0])
        card.pivotY = (loc[1] + v.height / 2f - cl[1])
    }

    /** Shrinks back into the folder it came from. */
    fun close() {
        if (title.hasFocus()) {
            saveName()
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(title.windowToken, 0)
        }
        from?.takeIf { it.isAttachedToWindow }?.let { pivotOn(it) }
        card.animate().scaleX(0.25f).scaleY(0.25f).alpha(0f).setDuration(220).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
        animate().alpha(0f).setDuration(230).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            host.closed()
            if (renamed) host.rename(folderId)
        }.start()
    }
}

/**
 * Several widgets in one spot. Swipe up or down to flip; dots on the side
 * show which one you're on. Sideways swipes still turn the home page.
 */
@SuppressLint("ViewConstructor")
class WidgetStack(context: Context, val stackId: String, private val widgets: List<BaseWidget>) : FrameLayout(context) {
    private var index = 0
    private val dots = object : View(context) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: android.graphics.Canvas) {
            val n = widgets.size
            if (n < 2) return
            val r = dpf(2.6f)
            val gap = dpf(9f)
            val top = height / 2f - (n - 1) * gap / 2f
            for (i in 0 until n) {
                p.color = if (i == index) Palette.INK else Color.argb(90, 255, 255, 255)
                c.drawCircle(width / 2f, top + i * gap, r, p)
            }
        }
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    init {
        clipChildren = true
        // Clip to the widget's rounded shape: a widget flipping in or out
        // stays inside the stack instead of sliding over its neighbours.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dpf(26f))
            }
        }
        clipToOutline = true
        index = Prefs.get(context).getInt("stackpos_" + stackId, 0).coerceIn(0, (widgets.size - 1).coerceAtLeast(0))
        for ((i, w) in widgets.withIndex()) {
            addView(w, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            w.visibility = if (i == index) VISIBLE else INVISIBLE
        }
        addView(dots, LayoutParams(dp(12), LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(2) })
    }

    fun flip(dir: Int) {
        if (widgets.size < 2) return
        val from = widgets[index]
        index = Math.floorMod(index + dir, widgets.size)
        val to = widgets[index]
        Prefs.get(context).edit().putInt("stackpos_" + stackId, index).apply()
        val h = height.toFloat()
        to.visibility = VISIBLE
        to.translationY = h * dir
        to.alpha = 0.4f
        to.animate().translationY(0f).alpha(1f).setDuration(420).setInterpolator(Spring(0.4f)).start()
        from.animate().translationY(-h * dir * 0.6f).alpha(0f).setDuration(260).withEndAction {
            from.visibility = INVISIBLE
            from.translationY = 0f
            from.alpha = 1f
        }.start()
        dots.invalidate()
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                swiping = false
                // Ask the home screen to wait: the swipe may be ours.
                if (widgets.size > 1) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (widgets.size > 1 && !swiping) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        // Sideways: let the page turn.
                        parent?.requestDisallowInterceptTouchEvent(false)
                    } else if (abs(dy) > slop * 1.5f && abs(dy) > abs(dx)) {
                        swiping = true
                        return true
                    }
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            val dy = e.rawY - downY
            if (abs(dy) > dp(24)) flip(if (dy < 0) 1 else -1)
            swiping = false
        }
        return true
    }
}
