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
class FolderTile(context: Context, val folderId: String, private val large: Boolean, private val open: (View) -> Unit, private val launch: (App, View) -> Unit) : FrameLayout(context) {
    init {
        val apps = Groups.folderApps(context, folderId).mapNotNull { AppRepo.byKey(it) }
        val pane = FrameLayout(context)
        addView(pane, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        pane.post {
            // A square pane, the theme's surface.
            val side = minOf(width, height) - if (large) 0 else dp(6)
            pane.layoutParams = LayoutParams(side, side, Gravity.CENTER)
            pane.background = pane.surface(side * if (large) 0.16f else 0.2237f, 0.12f)
            build(pane, apps, side)
        }
        setOnClickListener {
            it.bounce()
            open(this)
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
 * An open folder: the home screen dims, and a pane of glass grows out of the
 * folder with its name (tap to rename) and its apps. Long-press an app to
 * take it out; "+" adds more.
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
        setPadding(dp(18), dp(18), dp(18), dp(16))
    }

    init {
        setBackgroundColor(Color.argb(120, 0, 0, 0))
        isClickable = true
        setOnClickListener { close() }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(22)
            rightMargin = dp(22)
        })
        card.isClickable = true
        card.background = card.surface(dpf(34f), 0.32f)
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(context.text(24f, Palette.INK, bold = true).apply {
            text = Groups.folderName(context, folderId)
            setOnClickListener { host.rename(this@FolderSheet.folderId) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(context.text(13f, Color.BLACK, bold = true).apply {
            text = "+  Add"
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = context.round(Palette.INK, dpf(16f))
            pressable()
            setOnClickListener { host.addApps(this@FolderSheet.folderId) }
        })
        card.addView(head)
        card.addView(context.text(11.5f, Palette.GREY).apply {
            text = "Tap the name to rename. Long-press an app to take it out."
            setPadding(0, dp(4), 0, dp(14))
        })
        val apps = Groups.folderApps(context, folderId).mapNotNull { AppRepo.byKey(it) }
        var row: LinearLayout? = null
        for ((i, a) in apps.withIndex()) {
            if (i % 4 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            row?.addView(AppIconView(context, a, 54, true, liveGlass = false).apply {
                badge = HaloLink.badges[a.pkg] ?: 0
                setOnClickListener { host.launch(a, it) }
                setOnLongClickListener {
                    host.removeFromFolder(this@FolderSheet.folderId, a, it)
                    true
                }
                enter(i * 25L, dpf(10f))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Keep rows of four even.
        val rem = apps.size % 4
        if (rem != 0) repeat(4 - rem) { row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    /** Grows out of [from] (the folder tile). */
    fun show(from: View) {
        alpha = 0f
        animate().alpha(1f).setDuration(200).start()
        val loc = IntArray(2)
        from.getLocationOnScreen(loc)
        card.post {
            val cl = IntArray(2)
            card.getLocationOnScreen(cl)
            card.pivotX = (loc[0] + from.width / 2f - cl[0])
            card.pivotY = (loc[1] + from.height / 2f - cl[1])
            card.scaleX = 0.3f
            card.scaleY = 0.3f
            card.animate().scaleX(1f).scaleY(1f).setInterpolator(Spring(0.45f)).setDuration(460).start()
        }
    }

    fun close() {
        card.animate().scaleX(0.6f).scaleY(0.6f).setDuration(160).start()
        animate().alpha(0f).setDuration(170).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            host.closed()
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
