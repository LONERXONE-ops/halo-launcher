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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
 * A folder, in three sizes (on the home screen and in the app list):
 *  - Small (one app's space): a glass square the size of an app icon with
 *    four tiny icons, its name under it when names are on.
 *  - Medium (2x2): a glass card with the name in small capitals at the top
 *    and four big app icons you can open straight away (three and "+N" when
 *    there are more).
 *  - Large (4x2): the same card, wide, with two rows of four.
 * Tap the card (anywhere that isn't an app) to open the whole folder.
 */
@SuppressLint("ViewConstructor")
class FolderTile(
    context: Context,
    val folderId: String,
    /** 0 small, 1 medium, 2 large. */
    private val size: Int,
    private val open: (View) -> Unit,
    private val launch: (App, View) -> Unit,
    /** The home screen's app icon size, so a small folder is exactly as big as an app. */
    private val iconDp: Int = 62,
    /** Names under apps: a small folder gets its name under it too. */
    private val labels: Boolean = false,
) : FrameLayout(context) {
    private val pane = FrameLayout(context)
    // Only apps that are still installed (an uninstalled app used to leave a gap).
    private val apps: List<App> = Groups.folderApps(context, folderId).mapNotNull { AppRepo.byKey(it) }
    private var built = ""
    private val name = if (size == 0 && labels) context.text(11.5f, Palette.INK).apply {
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

    /** Built once it has a real size (and again only if that size changes). */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        val key = "$w|$h"
        if (key == built) return
        built = key
        post { setup(w, h) }
    }

    private fun setup(w: Int, h: Int) {
        pane.removeAllViews()
        if (size == 0) {
            val side = minOf(dp(iconDp), minOf(w, h) - dp(6))
            if (name != null) {
                pane.layoutParams = LayoutParams(side, side, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(7) }
                name.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(7) + side + dp(4) }
            } else {
                pane.layoutParams = LayoutParams(side, side, Gravity.CENTER)
            }
            shape(side * 0.26f)
            smallGrid(side)
            return
        }
        // Medium / large: a card filling the space, with a little breathing room.
        val inset = dp(6)
        pane.layoutParams = LayoutParams(w - inset * 2, h - inset * 2, Gravity.CENTER)
        shape(dpf(26f))
        card(w - inset * 2, h - inset * 2)
    }

    private fun shape(rad: Float) {
        pane.background = pane.surface(rad, 0.16f)
        pane.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, rad)
            }
        }
        pane.clipToOutline = true
    }

    private fun smallGrid(side: Int) {
        val pad = side / 7
        val cellPx = (side - pad * 2) / 2
        val iconDp = (cellPx * 0.86f / resources.displayMetrics.density).toInt().coerceAtLeast(12)
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        for (r in 0 until 2) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
            for (c in 0 until 2) {
                val a = apps.getOrNull(r * 2 + c)
                row.addView(if (a != null) AppIconView(context, a, iconDp, false, liveGlass = false).apply { isClickable = false } else View(context), LinearLayout.LayoutParams(cellPx, cellPx))
            }
            grid.addView(row)
        }
        pane.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(pad, pad, pad, pad) })
    }

    /** The card: the name in small capitals, then the apps, big, ready to open. */
    private fun card(w: Int, h: Int) {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        col.addView(context.text(11f, Palette.SILVER, caps = true).apply {
            text = Groups.folderName(context, folderId)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = 0.12f
        })
        val across = if (size == 2) 4 else 2
        val slots = across * 2
        val show = if (apps.size > slots) apps.take(slots - 1) else apps.take(slots)
        val innerW = w - dp(32)
        val innerH = h - dp(14) - dp(10) - dp(22)
        val cell = minOf(innerW / across, innerH / 2)
        val iconDp = (cell * 0.74f / resources.displayMetrics.density).toInt().coerceIn(20, 72)
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        for (r in 0 until 2) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            for (c in 0 until across) {
                val i = r * across + c
                val v: View = when {
                    i < show.size -> AppIconView(context, show[i], iconDp, false, liveGlass = false).apply {
                        badge = HaloLink.badges[show[i].pkg] ?: 0
                        pressable()
                        setOnClickListener { launch(show[i], it) }
                    }
                    i == slots - 1 && apps.size > slots -> context.text(15f, Palette.INK, bold = true).apply {
                        text = "+${apps.size - (slots - 1)}"
                        gravity = Gravity.CENTER
                        background = context.round(Color.argb(46, 255, 255, 255), dpf(16f))
                        setOnClickListener { open(this@FolderTile) }
                    }
                    else -> View(context)
                }
                val lp = if (v is TextView) LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)).apply {
                    marginStart = (cell - dp(iconDp)) / 2
                    marginEnd = (cell - dp(iconDp)) / 2
                } else LinearLayout.LayoutParams(0, cell, 1f)
                row.addView(v, if (v is TextView) lp else LinearLayout.LayoutParams(0, cell, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, cell))
        }
        col.addView(grid, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        pane.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

/** Folders in the app list (made by you): ids "dfolder:…", newest last. */
object DrawerFolders {
    private const val KEY = "drawer_folders"

    fun ids(c: Context): List<String> = Prefs.list(c, KEY).filter { Groups.folderApps(c, it).isNotEmpty() }

    fun size(c: Context, id: String): Int = Prefs.get(c).getInt("fsize_$id", 0)

    fun setSize(c: Context, id: String, s: Int) {
        Prefs.get(c).edit().putInt("fsize_$id", s.coerceIn(0, 2)).apply()
    }

    /** Every app that sits in one of them (those don't show on their own in the list). */
    fun foldered(c: Context): Set<String> = ids(c).flatMap { Groups.folderApps(c, it) }.toSet()

    fun create(c: Context, apps: List<String>, name: String): String {
        val id = "dfolder:" + System.currentTimeMillis().toString(36)
        Groups.setFolderApps(c, id, apps)
        Groups.setFolderName(c, id, name)
        Prefs.setList(c, KEY, Prefs.list(c, KEY) + id)
        return id
    }

    fun add(c: Context, id: String, app: String) {
        // An app lives in one folder at a time.
        for (other in ids(c)) if (other != id) Groups.setFolderApps(c, other, Groups.folderApps(c, other).filter { it != app })
        Groups.setFolderApps(c, id, Groups.folderApps(c, id) + app)
    }

    fun take(c: Context, id: String, app: String) {
        Groups.setFolderApps(c, id, Groups.folderApps(c, id).filter { it != app })
        if (Groups.folderApps(c, id).isEmpty()) delete(c, id)
    }

    fun delete(c: Context, id: String) {
        Groups.setFolderApps(c, id, emptyList())
        Prefs.setList(c, KEY, Prefs.list(c, KEY).filter { it != id })
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
    /** A folder of the app list (read only): its name and apps. */
    private val groupName: String? = null,
    private val groupApps: List<App>? = null,
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
    private val underline = View(context)
    private val pen = ImageView(context)
    private val title: android.widget.EditText

    init {
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        isClickable = true
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
        })
        card.isClickable = true
        card.background = card.surface(dpf(34f), 0.32f)
        val apps = groupApps ?: Groups.folderApps(context, folderId).mapNotNull { AppRepo.byKey(it) }
        val readOnly = groupApps != null

        // The name: plain big text until you tap it, then you type right there.
        title = android.widget.EditText(context).apply {
            setText(groupName ?: Groups.folderName(context, folderId))
            textSize = 26f
            typeface = Fonts.semibold(context)
            setTextColor(Palette.INK)
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            // Long names stop short of the + button.
            maxWidth = (resources.displayMetrics.widthPixels * 0.55f).toInt()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            isFocusable = false
            isFocusableInTouchMode = false
            if (!readOnly) setOnClickListener { startRename() }
            setOnEditorActionListener { _, _, _ ->
                finishRename()
                true
            }
        }
        // Name, a pen beside it (tap either to rename), and + to add apps.
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        nameBox.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // A line that draws itself under the name while you're renaming.
        underline.apply {
            background = context.round(Palette.INK, dpf(1f))
            scaleX = 0f
            pivotX = 0f
        }
        nameBox.addView(underline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(2) })
        head.addView(nameBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 0f })
        if (!readOnly) {
            pen.apply {
                setImageResource(R.drawable.ic_edit)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.SILVER)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { if (title.hasFocus()) finishRename() else startRename() }
            }
            head.addView(pen, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(6) })
        }
        head.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        if (!readOnly) head.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_plus)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Palette.INK)
            }
            contentDescription = "Add apps"
            pressable()
            setOnClickListener {
                it.bounce()
                host.addApps(this@FolderSheet.folderId)
            }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        card.addView(head)
        card.addView(context.text(12.5f, Palette.GREY).apply {
            text = if (apps.size == 1) "1 app" else "${apps.size} apps"
            setPadding(0, dp(4), 0, dp(16))
        })

        // Hold an app and drag it to put it where you want; hold and let go
        // without moving for its menu (remove from folder).
        val grid = ReorderGrid(context, 4, enabled = !readOnly)
        for ((i, a) in apps.withIndex()) {
            grid.addView(AppIconView(context, a, 56, true, liveGlass = false).apply {
                tag = a.key
                badge = HaloLink.badges[a.pkg] ?: 0
                pressable()
                setOnClickListener { host.launch(a, it) }
                // The apps pop in one after another as the folder opens.
                alpha = 0f
                scaleX = 0.6f
                scaleY = 0.6f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90L + i.coerceAtMost(16) * 18L).setDuration(320L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
            })
        }
        grid.onHoldStill = { v ->
            val a = (v as AppIconView).app
            host.removeFromFolder(this@FolderSheet.folderId, a, v)
        }
        grid.onReordered = { keys ->
            Groups.setFolderApps(context, folderId, keys)
            renamed = true
        }
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

    }

    /** A touch anywhere outside the folder closes it straight away (and never reaches the home screen). */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = android.graphics.Rect()
            card.getHitRect(r)
            if (!r.contains(e.x.toInt(), e.y.toInt())) close()
        }
        return true
    }

    private var closing = false

    private fun saveName() {
        val t = title.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty() && t != Groups.folderName(context, folderId)) {
            Groups.setFolderName(context, folderId, t)
            renamed = true
        }
    }

    private var renamed = false

    /** Tap the name or the pen: the line draws in under it, the pen turns into a tick, type away. */
    private fun startRename() {
        title.isFocusableInTouchMode = true
        title.isFocusable = true
        title.requestFocus()
        title.setSelection(title.text.length)
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(title, 0)
        underline.animate().scaleX(1f).setDuration(260L).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        pen.animate().rotation(-90f).scaleX(0.6f).scaleY(0.6f).setDuration(120L).withEndAction {
            pen.setImageResource(R.drawable.ic_check)
            pen.imageTintList = android.content.res.ColorStateList.valueOf(Palette.GREEN)
            pen.rotation = 90f
            pen.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        }.start()
    }

    /** Done: the name pops into place, the line slides away, the tick turns back into the pen. */
    private fun finishRename() {
        saveName()
        title.clearFocus()
        title.isFocusable = false
        title.isFocusableInTouchMode = false
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(title.windowToken, 0)
        underline.animate().scaleX(0f).setDuration(200L).start()
        title.scaleX = 0.92f
        title.scaleY = 0.92f
        title.pivotX = 0f
        title.animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        pen.animate().scaleX(0.6f).scaleY(0.6f).setDuration(110L).withEndAction {
            pen.setImageResource(R.drawable.ic_edit)
            pen.imageTintList = android.content.res.ColorStateList.valueOf(Palette.SILVER)
            pen.animate().scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        }.start()
    }

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
        if (closing) return
        closing = true
        if (title.hasFocus()) {
            saveName()
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(title.windowToken, 0)
        }
        from?.takeIf { it.isAttachedToWindow }?.let { pivotOn(it) }
        card.animate().scaleX(0.25f).scaleY(0.25f).alpha(0f).setDuration(220).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
        animate().alpha(0f).setDuration(230).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            host.closed()
            if (renamed && groupApps == null) host.rename(folderId)
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

/**
 * A grid of app icons you can rearrange: hold one and it lifts, drag it and
 * the others slide out of the way, let go and it settles into its new spot.
 * Holding without moving calls [onHoldStill] instead (for a menu).
 */
@SuppressLint("ViewConstructor")
class ReorderGrid(context: Context, private val cols: Int, private val enabled: Boolean) : ViewGroup(context) {
    var onReordered: ((List<String>) -> Unit)? = null
    var onHoldStill: ((View) -> Unit)? = null
    private val order = ArrayList<View>()
    private var held: View? = null
    private var grabX = 0f
    private var grabY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var changed = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var candidate: View? = null
    private val lift = Runnable { candidate?.let { pick(it) } }

    override fun addView(child: View?) {
        super.addView(child)
        if (child != null) order += child
    }

    private fun cellW() = (width - paddingLeft - paddingRight) / cols
    private fun cellH() = order.firstOrNull()?.measuredHeight ?: 0
    private val gap get() = dp(12)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cw = (w - paddingLeft - paddingRight) / cols
        var ch = 0
        for (c in order) {
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            ch = maxOf(ch, c.measuredHeight)
        }
        val rows = (order.size + cols - 1) / cols
        setMeasuredDimension(w, paddingTop + paddingBottom + rows * ch + (rows - 1).coerceAtLeast(0) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellW()
        val ch = cellH()
        for ((i, c) in order.withIndex()) {
            val x = paddingLeft + (i % cols) * cw
            val y = paddingTop + (i / cols) * (ch + gap)
            c.layout(x, y, x + cw, y + c.measuredHeight)
        }
    }

    private fun childAt(x: Float, y: Float): View? = order.firstOrNull { it !== held && x >= it.left && x < it.right && y >= it.top && y < it.bottom }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!enabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                candidate = childAt(e.x, e.y)
                if (candidate != null) postDelayed(lift, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (held != null) return true
                if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) removeCallbacks(lift)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(lift)
                if (held != null) {
                    finish()
                    return true
                }
            }
        }
        return held != null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val v = held ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) moved = true
                v.translationX = e.x - grabX - v.left
                v.translationY = e.y - grabY - v.top
                val over = childAt(e.x, e.y)
                if (over != null) {
                    val from = order.indexOf(v)
                    val to = order.indexOf(over)
                    if (from >= 0 && to >= 0 && from != to) {
                        order.removeAt(from)
                        order.add(to, v)
                        changed = true
                        slide(e)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
        }
        return true
    }

    private fun pick(v: View) {
        held = v
        candidate = null
        grabX = downX - v.left
        grabY = downY - v.top
        parent?.requestDisallowInterceptTouchEvent(true)
        v.bringToFront()
        v.elevation = dpf(10f)
        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        // Its pressed state and long-press must not fire as well.
        v.isPressed = false
        v.cancelLongPress()
    }

    /** Everyone moves to their new spot, sliding there from where they were. */
    private fun slide(e: MotionEvent) {
        val before = order.associateWith { it.left to it.top }
        requestLayout()
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                for ((c, old) in before) {
                    if (c === held) continue
                    val dx = (old.first - c.left).toFloat()
                    val dy = (old.second - c.top).toFloat()
                    if (dx == 0f && dy == 0f) continue
                    c.translationX += dx
                    c.translationY += dy
                    c.animate().translationX(0f).translationY(0f).setDuration(240).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)).start()
                }
                held?.let {
                    it.translationX = e.x - grabX - it.left
                    it.translationY = e.y - grabY - it.top
                }
                return true
            }
        })
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun finish() {
        val v = held ?: return
        held = null
        v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).withEndAction { v.elevation = 0f }.start()
        if (!moved && !changed) {
            onHoldStill?.invoke(v)
        } else if (changed) {
            changed = false
            onReordered?.invoke(order.mapNotNull { it.tag as? String })
        }
    }
}
