package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.util.SizeF
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import kotlin.math.abs

/**
 * Halo Launcher's home screen.
 *
 * Your picture (or wallpaper) fills the whole screen. Everything on it sits
 * on Liquid Glass that bends that picture, or on solid tiles if you prefer.
 *
 * Page 1: the stacked clock, the date and weather, and the music player.
 * Page 2: Halo's own widgets, then any Android widgets.
 * The dock floats above the bottom edge.
 *
 * Long-press anything to edit (iOS style): everything jiggles and can be
 * dragged to a new place, resized or removed.
 */
class LauncherActivity : Activity(), Drawer.Host, WidgetActions, Editor.Host, FolderSheet.Host {

    private lateinit var root: GestureRoot
    private lateinit var bg: BackgroundView
    private lateinit var pager: HomePager
    private lateinit var drawer: Drawer
    private lateinit var dots: PageDots
    private lateinit var dock: DockBar
    private lateinit var widgetHost: AppWidgetHost
    private val ui = Handler(Looper.getMainLooper())
    private var insetTop = 0
    private var insetBottom = 0
    private val dockBody: Int get() = dp(86)
    private val dockGap: Int get() = dp(10)

    // Live views
    /** One grid per home page. */
    private val grids = ArrayList<HomeGrid>()
    private var clockFace: ClockFace? = null
    private var dateFace: DateFace? = null
    private var wxFace: WeatherFace? = null
    private var musicTick: Runnable? = null
    /** An app's long-press menu that turns into a drag if the finger moves on. */
    private var menuDrag: Triple<App, View, HaloMenu>? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private lateinit var editor: Editor
    private lateinit var overlayLayer: FrameLayout
    private lateinit var editBar: LinearLayout
    private val widgets = ArrayList<BaseWidget>()
    private var lastScreenTime = 0L

    private val appsChanged: () -> Unit = {
        rebuildApps()
        // Home-screen app icons too (an app was added, removed or updated).
        if (!editor.isDragging && HomeLayout.all(this).any { it.id.startsWith("app:") }) rebuildHome()
    }
    private val haloChanged: () -> Unit = { onHalo() }
    private val glassChanged: () -> Unit = {
        bg.invalidate()
        root.invalidateGlass()
        // A new picture can mean new colours (Adaptive colour).
        if (Palette.adapt(this)) refreshLook()
    }

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> refreshWidgets("battery")
                else -> onMinute()
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App0.ctx = applicationContext
        window.setDecorFitsSystemWindows(false)
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        // Colours first (from the remembered wallpaper colour), so the first build is the final one.
        Palette.adapt(this)
        build()
        AppRepo.addListener(appsChanged)
        AppRepo.load(this)
        // Built once here: coming home right after a fresh start doesn't build it all again.
        if (!homeBuilt && AppRepo.apps.isNotEmpty()) {
            rebuildApps()
            rebuildHome()
        }
        dirty = false
        HaloLink.addListener(haloChanged)
        HaloLink.start(this)
        Glass.addListener(glassChanged)
        ScreenTime.listener = { refreshWidgets("screen") }
        // Unlocking the phone: the home screen zooms in, like iOS.
        registerReceiver(unlock, IntentFilter(Intent.ACTION_USER_PRESENT))
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsWatch)
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (_: Exception) {
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(tick, f)
        visibleNow = true
        root.removeCallbacks(quietRebuild)
        // Only rebuild the home screen if a setting changed while we were
        // away; otherwise just bring the live bits up to date (much faster
        // coming back from an app).
        if (dirty) {
            dirty = false
            refreshLook()
        } else {
            // Let the return animation run first; update the live bits a beat later.
            root.postDelayed({
                refreshWidgetsGently()
                loadBackdrop()
            }, 380L)
        }
        root.postDelayed({ onMinute() }, 300L)
        root.postDelayed({ updateMusicPresence() }, 450L)
        Steps.start(this) { refreshWidgets("steps") }
        HaloLink.command(this, "request_state")
        Weather.refresh(this) { onWeather() }
        WallpaperActivity.dailyShuffle(this) { loadBackdrop() }
        if (wasAway) animateIn()
        wasAway = false
    }

    /** Set when a setting changes, so the next return home rebuilds. */
    private var dirty = true
    private var wasAway = true
    private val prefsWatch = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val k = key ?: ""
        // Halo shows the same icons as the home screen: tell it they changed.
        if (k == Prefs.ICON_STYLE || k == Prefs.ICON_PACK || k == Prefs.THEME) HaloLink.command(this, "icons_changed")
        // Counters and caches change all the time and don't change the look.
        // (Steps update as you walk; these used to make every return home rebuild everything.)
        if (k.startsWith("launch") || k.startsWith("w_") || k.startsWith("timer") || k.startsWith("steps_") ||
            k.startsWith("stackpos_") || k.startsWith("cd_") || k == Prefs.WALL_DAY || k == Prefs.NOTE || k == Prefs.ONBOARDED
        ) return@OnSharedPreferenceChangeListener
        dirty = true
        // Out of sight (in Settings): rebuild now, quietly, so coming home shows
        // the new look straight away instead of flickering through a rebuild.
        if (!visibleNow) {
            root.removeCallbacks(quietRebuild)
            root.postDelayed(quietRebuild, 350L)
        }
    }

    private var visibleNow = false
    private val quietRebuild = Runnable {
        if (!visibleNow && dirty && !isFinishing && !isDestroyed) {
            dirty = false
            refreshLook()
        }
    }

    override fun onResume() {
        super.onResume()
        // Never stay zoomed away (an app that didn't open, a quick return).
        if (pager.alpha < 0.99f) root.post { if (pager.alpha < 0.99f) animateIn() }
        startMusicTick()
        // The welcome tour: once for everyone, including people who set up before it existed.
        if (!tourAsked && OnboardingActivity.due(this)) {
            tourAsked = true
            root.postDelayed({
                if (!isFinishing) {
                    startActivity(Intent(this, OnboardingActivity::class.java))
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            }, 700L)
        }
    }

    private var tourAsked = false

    override fun onPause() {
        super.onPause()
        musicTick?.let { ui.removeCallbacks(it) }
    }

    override fun onStop() {
        super.onStop()
        wasAway = true
        visibleNow = false
        updateMusicPresence()
        try {
            unregisterReceiver(tick)
        } catch (_: Exception) {
        }
        try {
            widgetHost.stopListening()
        } catch (_: Exception) {
        }
        Steps.stop(this)
        // Nothing is reset here: coming back from an app (back gesture)
        // returns you exactly where you were, drawer and scroll included.
        drawer.hideKeyboard()
        editor.exit()
    }

    private val unlock = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (hasWindowFocus() && !drawer.isOpen) animateIn()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(unlock)
        } catch (_: Exception) {
        }
        AppRepo.removeListener(appsChanged)
        HaloLink.removeListener(haloChanged)
        Glass.removeListener(glassChanged)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsWatch)
        super.onDestroy()
    }

    /**
     * Home pressed. From inside an app it just brings you home as you left it
     * (the app list stays where you scrolled it). While already home it steps
     * back: closes the app list, then returns to page 1.
     */
    override fun onNewIntent(intent: Intent) {
        val alreadyHome = hasWindowFocus() && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        super.onNewIntent(intent)
        if (!alreadyHome) return
        when {
            editor.editing -> editor.exit()
            drawer.isOpen -> drawer.close()
            pager.current != 0 -> pager.snapTo(0)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheet != null -> sheet?.close()
            editor.editing -> editor.exit()
            drawer.isArranging -> drawer.endArrange()
            drawer.isOpen -> drawer.close()
            pager.current != 0 -> pager.snapTo(0)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Steps.start(this) { refreshWidgets("steps") }
        refreshWidgets(null)
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private fun build() {
        editor = Editor(this)
        root = GestureRoot(this)
        bg = BackgroundView(this)
        root.addView(bg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        pager = HomePager(this)
        buildClockAndMusic()
        pager.onScrolled = { f -> onPagerScroll(f) }
        pager.onPageChanged = { root.postDelayed({ updateMusicPresence() }, 380L) }
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dots = PageDots(this)
        root.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14), Gravity.BOTTOM))

        dock = DockBar(this)
        dock.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            editor.enter()
            true
        }
        root.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dockBody, Gravity.BOTTOM).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
        })

        drawer = Drawer(this, this)
        root.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        editBar = buildEditBar()
        root.addView(editBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Whatever is being dragged floats here, above everything.
        overlayLayer = FrameLayout(this).apply {
            clipChildren = false
            isClickable = false
        }
        root.addView(overlayLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            insetTop = bars.top
            insetBottom = bars.bottom
            layoutForInsets()
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) {
                loadBackdrop()
                fitRows()
            }
        }
        setContentView(root)
    }

    /** The floating dock sits just above the gesture bar; the pages leave room for it. */
    private fun layoutForInsets() {
        val lp = dock.layoutParams as FrameLayout.LayoutParams
        lp.height = dockBody
        lp.bottomMargin = insetBottom + dockGap
        dock.layoutParams = lp
        dock.setPadding(dp(10), 0, dp(10), 0)
        val above = insetBottom + dockGap + dockBody
        (dots.layoutParams as FrameLayout.LayoutParams).bottomMargin = above + dp(8)
        dots.requestLayout()
        for (g in grids) padGrid(g)
        fitRows()
        drawer.setPadding(0, insetTop, 0, 0)
        editBar.setPadding(dp(16), insetTop + dp(8), dp(16), dp(8))
    }

    private fun refreshLook() {
        Palette.adapt(this)
        root.setBackgroundColor(Color.TRANSPARENT)
        dock.restyle()
        drawer.restyle()
        rebuildApps()
        rebuildHome()
        clockFace?.refresh()
        dateFace?.refresh()
        wxFace?.refresh()
        loadBackdrop()
        root.postDelayed({ updateMusicPresence() }, 300L)
    }

    /** Your picture, or the wallpaper, behind everything (the glass bends it). */
    private fun loadBackdrop() {
        root.post {
            if (root.width > 0 && root.height > 0) Glass.load(this, root.width, root.height) { glassChanged() }
        }
    }

    /** Dots follow the finger; page 2's widgets lag behind for depth; glass re-samples as it moves. */
    /** A little "landing" bounce on the icon of the app you just came back from. */
    private fun settle(key: String) {
        val views = ArrayList<View>()
        for (g in grids) for (i in 0 until g.childCount) ((g.getChildAt(i) as? Cell)?.content as? AppIconView)?.let { if (it.app.key == key) views += it }
        for (i in 0 until dock.childCount) ((dock.getChildAt(i) as? Cell)?.content as? AppIconView)?.let { if (it.app.key == key) views += it }
        for (v in views) {
            v.scaleX = 0.8f
            v.scaleY = 0.8f
            v.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
    }

    private fun onPagerScroll(f: Float) {
        dots.position = f
        Motion.applyPages(grids, f, Motion.page(this))
        pager.invalidateGlass()
        try {
            val wm = WallpaperManager.getInstance(this)
            wm.setWallpaperOffsetSteps(1f, 0f)
            wm.setWallpaperOffsets(root.windowToken, f.coerceIn(0f, 1f), 0f)
        } catch (_: Exception) {
        }
    }

    /** The home screen settles in: clock, weather, player, then the dock icons one by one. */
    private fun animateIn() {
        if (!Prefs.animations(this)) return
        if (editor.editing) return
        // The app you were in lands back in its icon.
        lastLaunched?.let { key -> root.postDelayed({ settle(key) }, 170L) }
        lastLaunched = null
        // One cheap zoom of the whole page and dock (drawn as a single layer),
        // like iOS, instead of animating every item: smooth even on slower phones.
        for (v in listOf<View>(pager, dock)) {
            v.animate().cancel()
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // Visible from the first frame (no blank moment), settling into place.
            v.scaleX = 1.06f
            v.scaleY = 1.06f
            v.alpha = 0.35f
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f))
                .withEndAction {
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                    v.invalidateGlass()
                }.start()
        }
    }

    // ----- The clock block and the music player (placed on the grid like widgets) -----

    private fun buildClockAndMusic() {
        clockFace = ClockFace(this).apply {
            setOnClickListener {
                it.bounce()
                openClockApp()
            }
        }
        dateFace = DateFace(this).apply {
            setOnClickListener {
                it.bounce()
                openCalendar()
            }
        }
        wxFace = WeatherFace(this).apply {
            setOnClickListener {
                it.bounce()
                if (!Weather.hasPlace(this@LauncherActivity)) openSettingsPage("weather") else refreshWeather()
            }
        }
    }


    // ----- The home grid -----

    private fun padGrid(g: HomeGrid) {
        val above = insetBottom + dockGap + dockBody
        g.setPadding(dp(10), insetTop + dp(14), dp(10), above + dp(26))
    }

    /** Works out how many rows of square-ish cells fit between the status bar and the dock. */
    private fun fitRows() {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return
        val cell = (w - dp(20)) / HomeLayout.COLS.toFloat()
        val avail = h - (insetTop + dp(14)) - (insetBottom + dockGap + dockBody + dp(26))
        val rows = (avail / cell).toInt().coerceIn(4, 9)
        if (rows != HomeLayout.rows) {
            HomeLayout.setRows(this, rows)
            rebuildHome()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun newGrid(page: Int) = HomeGrid(this, page).apply {
        padGrid(this)
        // Empty space works like the wallpaper: long-press to edit, double-tap to lock, tap to finish editing.
        setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }
    }

    /**
     * Puts every item on its page and cell. While something is being
     * dragged an extra empty page waits at the end, so you can make a new one.
     */
    private var homeBuilt = false

    private fun rebuildHome() {
        homeBuilt = true
        val dragging = editor.draggingZone == "home"
        val n = HomeLayout.pages(this) + if (dragging) 1 else 0
        while (grids.size < n) grids += newGrid(grids.size).also { pager.addView(it) }
        while (grids.size > n) pager.removeView(grids.removeAt(grids.size - 1))
        for (g in grids) {
            g.removeAllViews()
            g.showDots = editor.editing
            g.preview = null
        }
        widgets.clear()
        for (item in HomeLayout.all(this).toList()) {
            if (item.id == editor.draggingId) continue
            val g = grids.getOrNull(item.page) ?: continue
            val cell = cellFor(item) ?: continue
            g.addView(cell, HomeGrid.LP(item.col, item.row, item.sx, item.sy))
        }
        dots.pages = n
        if (pager.current >= n) pager.snapTo(n - 1, animate = false)
        Motion.applyPages(grids, pager.current.toFloat(), Motion.page(this))
    }

    /** A movable Cell for one home item. */
    private fun cellFor(item: HomeLayout.Item): Cell? {
        val id = item.id
        fun changed(change: () -> Unit) {
            change()
            rebuildHome()
        }
        return when {
            id == "clock" || id == "date" || id == "wx" -> {
                val v: TextArt = when (id) {
                    "clock" -> clockFace
                    "date" -> dateFace
                    else -> wxFace
                } ?: return null
                (v.parent as? ViewGroup)?.removeView(v)
                val sizes = TEXT_SIZES[id]!!
                val at = sizes.indexOfFirst { it.first == item.sx && it.second == item.sy }.coerceAtLeast(0)
                Cell(this, editor, "home", id, v, sizeLabel = listOf("Small", "Wide", "Large")[at]).apply {
                    onRemove = { changed { HomeLayout.remove(this@LauncherActivity, id) } }
                    onSize = {
                        val n = sizes[(at + 1) % sizes.size]
                        changed { HomeLayout.resize(this@LauncherActivity, id, n.first, n.second) }
                        snapToItem(id)
                    }
                    // Tap it while editing to restyle it.
                    onEditTap = { openSettingsPage("clock") }
                }
            }
            id.startsWith("app:") -> {
                val app = AppRepo.byKey(id.removePrefix("app:")) ?: return null
                // Bigger and without names by default (both in Settings → Icons).
                val p = Prefs.get(this)
                val labels = p.getBoolean(Prefs.SHOW_LABELS, false)
                val size = listOf(54, 62, 70)[p.getInt(Prefs.HOME_ICON_SIZE, 1).coerceIn(0, 2)] - if (labels) 6 else 0
                val icon = iconView(app, size, labels)
                Cell(this, editor, "home", id, icon, longPressEdits = false, minusFromCentre = dp(size) / 2).apply {
                    onRemove = { changed { HomeLayout.remove(this@LauncherActivity, id) } }
                }
            }
            id.startsWith("w:") -> {
                val wid = id.removePrefix("w:")
                val w = HomeWidgets.create(this, wid, this) ?: return null
                widgets += w
                val size = HomeLayout.sizeOf(item)
                Cell(this, editor, "home", id, w, sizeLabel = HomeWidgets.SIZE_NAMES[size]).apply {
                    onRemove = { changed { HomeLayout.remove(this@LauncherActivity, id) } }
                    onSize = {
                        changed { HomeWidgets.setSize(this@LauncherActivity, wid, (size + 1) % 3) }
                        snapToItem(id)
                    }
                }
            }
            id.startsWith("aw:") -> androidWidgetCell(item)
            id.startsWith("folder:") -> {
                // A folder whose apps were all uninstalled disappears.
                if (Groups.folderApps(this, id).none { AppRepo.byKey(it) != null }) return null
                // Small (1x1), Medium (2x2) or Large (4x2), like widgets.
                val fsz = when {
                    item.sx >= 4 -> 2
                    item.sx >= 2 -> 1
                    else -> 0
                }
                val fp = Prefs.get(this)
                val fLabels = fp.getBoolean(Prefs.SHOW_LABELS, false)
                val fSize = listOf(54, 62, 70)[fp.getInt(Prefs.HOME_ICON_SIZE, 1).coerceIn(0, 2)] - if (fLabels) 6 else 0
                val tile = FolderTile(this, id, fsz, open = { v -> openFolder(id, v) }, launch = { a, v -> launch(a, v) }, iconDp = fSize, labels = fLabels)
                Cell(this, editor, "home", id, tile, sizeLabel = HomeWidgets.SIZE_NAMES[fsz], longPressEdits = true).apply {
                    onRemove = { changed { HomeLayout.remove(this@LauncherActivity, id) } }
                    onSize = {
                        changed {
                            when (fsz) {
                                0 -> HomeLayout.resize(this@LauncherActivity, id, 2, 2)
                                1 -> HomeLayout.resize(this@LauncherActivity, id, 4, 2)
                                else -> HomeLayout.resize(this@LauncherActivity, id, 1, 1)
                            }
                        }
                        snapToItem(id)
                    }
                    onEditTap = { openFolder(id, this) }
                }
            }
            id.startsWith("stack:") -> {
                val list = Groups.stackWidgets(this, id).mapNotNull { wid -> HomeWidgets.create(this, wid, this) }
                if (list.isEmpty()) return null
                val size = HomeLayout.sizeOf(item)
                for (w in list) {
                    w.size = size
                    widgets += w
                }
                val stack = WidgetStack(this, id, list)
                Cell(this, editor, "home", id, stack, sizeLabel = HomeWidgets.SIZE_NAMES[size]).apply {
                    onRemove = { changed { HomeLayout.remove(this@LauncherActivity, id) } }
                    onSize = {
                        val (sx, sy) = HomeLayout.spansFor((size + 1) % 3)
                        changed { HomeLayout.resize(this@LauncherActivity, id, sx, sy) }
                        snapToItem(id)
                    }
                    onEditTap = { stackMenu(id, this) }
                }
            }
            else -> null
        }
    }

    /**
     * Drag an app out of its long-press menu: one already on the home screen
     * just moves; one from the app list or the dock lands as a new home icon.
     */
    private fun dragApp(app: App, anchor: View, x: Float, y: Float) {
        var v: View? = anchor
        var cell: Cell? = null
        while (v != null) {
            if (v is Cell && v.zone == "home") {
                cell = v
                break
            }
            v = v.parent as? View
        }
        editor.enter()
        editor.willDrag(x, y)
        if (cell != null) {
            val c = cell
            c.post { editor.startDrag(c, x, y) }
            return
        }
        if (drawer.isOpen) drawer.close()
        val id = "app:${app.key}"
        if (!HomeLayout.has(this, id)) HomeLayout.add(this, id, 1, 1, preferPage = pager.current)
        rebuildHome()
        val fresh = grids.flatMap { g -> (0 until g.childCount).map { g.getChildAt(it) } }.firstOrNull { (it as? Cell)?.itemId == id } as? Cell ?: return
        afterLayout(fresh) { editor.startDrag(fresh, x, y, centred = true) }
    }

    /** Runs [then] once [v] has been laid out. */
    private fun afterLayout(v: View, then: () -> Unit) {
        if (v.isLaidOut && v.width > 0) {
            v.post(then)
            return
        }
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int) {
                view.removeOnLayoutChangeListener(this)
                view.post(then)
            }
        })
    }

    /** Turn to the page an item is on (after it moved because it didn't fit). */
    private fun snapToItem(id: String) {
        val p = HomeLayout.get(this, id)?.page ?: return
        if (p != pager.current) pager.snapTo(p)
    }

    private fun onHalo() {
        refreshWidgets("music")
        startMusicTick()
        updateBadges()
    }

    private fun startMusicTick() {
        musicTick?.let { ui.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                for (w in widgets) (w as? MusicWidget)?.tick()
                if (HaloLink.music?.playing == true) ui.postDelayed(this, 1000L)
            }
        }
        musicTick = r
        ui.postDelayed(r, 1000L)
    }

    private fun noHalo() {
        Toast.makeText(this, if (HaloLink.installed(this)) "Turn on Halo to control music from here" else "Install Halo to see your music here", Toast.LENGTH_SHORT).show()
    }

    /** "+": put a widget on the home screen, bring back the music player, or add an Android widget. */
    private fun addMenu(anchor: View) {
        val m = HaloMenu(anchor).header("Add to home screen")
        val off = HomeWidgets.ALL.filter { !Prefs.widgetOn(this, it.id) }
        for (spec in off) m.add(spec.name, R.drawable.ic_widgets) {
            val (sx, sy) = HomeLayout.spansFor(spec.defaultSize)
            addItem("w:${spec.id}", sx, sy)
        }
        m.divider()
        if (!HomeLayout.has(this, "clock")) m.add("Clock", R.drawable.ic_clock) { addItem("clock", 2, 2) }
        if (!HomeLayout.has(this, "date")) m.add("Date", R.drawable.ic_clock) { addItem("date", 2, 1) }
        if (!HomeLayout.has(this, "wx")) m.add("Weather on the wallpaper", R.drawable.w_sun) { addItem("wx", 2, 1) }
        m.add("App…", R.drawable.ic_apps) {
            // Open the app list; hold an app there and drag it here (or use its menu).
            editor.exit()
            drawer.visibility = View.VISIBLE
            drawer.open()
            Toast.makeText(this, "Hold an app and drag it onto your home screen", Toast.LENGTH_SHORT).show()
        }
        m.add("Android widget…", R.drawable.ic_plus) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, WidgetPickActivity::class.java), REQ_WIDGET)
        }
        m.show()
    }

    /** Adds to the page you're on (or the next with room) and shows it arriving. */
    private fun addItem(id: String, sx: Int, sy: Int) {
        HomeLayout.add(this, id, sx, sy, preferPage = pager.current)
        rebuildHome()
        snapToItem(id)
        for (g in grids) for (i in 0 until g.childCount) (g.getChildAt(i) as? Cell)?.takeIf { it.itemId == id }?.enter(0, dpf(18f))
    }

    /** Coming home: widgets update one per frame, so the return animation never stutters. */
    private fun refreshWidgetsGently() {
        val list = widgets.toList()
        for ((i, w) in list.withIndex()) {
            root.postDelayed({
                if (w.id == "screen" && System.currentTimeMillis() - lastScreenTime < 60_000L) return@postDelayed
                if (w.id == "screen") lastScreenTime = System.currentTimeMillis()
                w.refresh()
            }, i * 24L)
        }
    }

    private fun refreshWidgets(onlyId: String?) {
        for (w in widgets) {
            val id = w.id
            if (onlyId != null && id != onlyId) continue
            if (id == "screen" && onlyId == null && System.currentTimeMillis() - lastScreenTime < 60_000L) continue
            if (id == "screen") lastScreenTime = System.currentTimeMillis()
            w.refresh()
        }
    }

    // ----- Weather / time -----

    private fun onWeather() {
        wxFace?.refresh()
        refreshWidgets("weather")
    }

    private var lastDay = ""

    private fun onMinute() {
        clockFace?.tick()
        val day = DateFormat.format("yyyyMMdd", Date()).toString()
        if (day != lastDay) {
            lastDay = day
            dateFace?.refresh()
        }
        refreshWidgets("agenda")
        refreshWidgets("battery")
        refreshWidgets("screen")
    }

    // ----- Apps: the dock -----

    private fun rebuildApps() {
        if (AppRepo.apps.isEmpty()) return
        rebuildDock()
        drawer.refresh()
        updateBadges()
    }

    /** The dock's apps, each in a Cell so edit mode can drag or remove it; in edit mode a + slot to add one. */
    private fun rebuildDock() {
        if (AppRepo.apps.isEmpty()) return
        dock.removeAllViews()
        val apps = dockApps()
        val plus = editor.editing && apps.size < DOCK_MAX
        val size = dock.iconFor(apps.size + if (plus) 1 else 0)
        for (a in apps) {
            val cell = if (editor.draggingId == a.key) {
                Cell(this, editor, "dock", a.key, null).apply { background = null }
            } else {
                Cell(this, editor, "dock", a.key, iconView(a, size, false).apply { if (!dockBadges()) badge = 0 }, longPressEdits = false, minusFromCentre = dp(size) / 2).apply {
                    onRemove = {
                        val before = editor.positions("dock")
                        Prefs.setList(this@LauncherActivity, Prefs.DOCK, dockApps().map { it.key }.filter { it != a.key })
                        rebuildDock()
                        editor.glide("dock", before)
                    }
                }
            }
            dock.addView(cell, LinearLayout.LayoutParams(0, dp(size + 14), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        if (plus) {
            dock.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_plus)
                imageTintList = ColorStateList.valueOf(Palette.INK)
                val pad = dp(size) / 4
                setPadding(pad, pad, pad, pad)
                background = round(Color.argb(40, 255, 255, 255), dpf(size * 0.27f))
                pressable()
                setOnClickListener {
                    it.bounce()
                    pickDock()
                }
                enter(0, dpf(8f))
            }, LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(6)
                marginEnd = dp(6)
            })
        }
    }

    private fun iconView(a: App, size: Int, label: Boolean): AppIconView = AppIconView(this, a, size, label).apply {
        pressable()
        badge = HaloLink.badges[a.pkg] ?: 0
        setOnClickListener { launch(a, it) }
        setOnLongClickListener {
            appMenu(a, it)
            true
        }
    }

    private fun dockApps(): List<App> {
        val saved = Prefs.list(this, Prefs.DOCK).mapNotNull { AppRepo.byKey(it) }
        if (saved.isNotEmpty()) return saved.take(DOCK_MAX)
        val picks = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
        ).mapNotNull { i ->
            val pkg = try {
                packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (_: Exception) {
                null
            }
            pkg?.let { p -> AppRepo.apps.firstOrNull { it.pkg == p } }
        }.distinctBy { it.key }
        return (picks + AppRepo.apps.filter { it !in picks }).take(5)
    }

    private fun pickDock() {
        root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (Prefs.list(this, Prefs.DOCK).isEmpty()) Prefs.setList(this, Prefs.DOCK, dockApps().map { it.key })
        startActivity(Intent(this, PickAppsActivity::class.java).putExtra("key", Prefs.DOCK).putExtra("max", DOCK_MAX))
    }

    private fun dockBadges() = Prefs.get(this).getBoolean(Prefs.DOCK_BADGES, true)

    private fun updateBadges() {
        val on = dockBadges()
        for (i in 0 until dock.childCount) ((dock.getChildAt(i) as? Cell)?.content as? AppIconView)?.let { it.badge = if (on) HaloLink.badges[it.app.pkg] ?: 0 else 0 }
        drawer.updateBadges()
    }

    // =====================================================================
    // Drawer.Host
    // =====================================================================

    /** The app opened last, so it can land back in its icon when you return. */
    private var lastLaunched: String? = null

    override fun launch(app: App, from: View) {
        lastLaunched = app.key
        // Zoom: the home screen rushes past you as the app grows out of its icon.
        if (Prefs.animations(this) && Motion.open(this) == Motion.OPEN_ZOOM && !drawer.isOpen && sheet == null) {
            for (v in listOf<View>(pager, dock)) {
                v.animate().cancel()
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                v.animate().scaleX(1.12f).scaleY(1.12f).alpha(0f).setDuration(210)
                    .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
                    .withEndAction { v.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
            }
            // If the app doesn't actually open (or opens over us), come back.
            root.postDelayed({ if (hasWindowFocus() && pager.alpha < 1f) animateIn() }, 1400L)
        }
        AppRepo.launch(this, app, from)
    }

    private var musicHomeSent: Boolean? = null
    private var lastDrawerHalf = false
    private val tmpRect = android.graphics.Rect()

    /**
     * Tells Halo whether the music card is on screen right now (home showing,
     * its page in view, the app list closed), so the island's music steps back
     * and only one player shows.
     */
    private fun updateMusicPresence() {
        val on = visibleNow && drawer.progress() < 0.5f && widgets.any { w ->
            w is MusicWidget && w.isShown && w.getGlobalVisibleRect(tmpRect) && tmpRect.width() > w.width / 2
        }
        if (on != musicHomeSent) {
            musicHomeSent = on
            HaloLink.command(this, "music_home", if (on) "1" else "0")
        }
    }

    override fun onDrawerProgress(progress: Float) {
        if ((progress >= 0.5f) != (lastDrawerHalf)) {
            lastDrawerHalf = progress >= 0.5f
            updateMusicPresence()
        }
        val s = 1f - progress * 0.06f
        for (v in listOf<View>(pager, dock, dots)) {
            v.alpha = 1f - progress
            v.scaleX = s
            v.scaleY = s
        }
    }

    /** Long-press: app shortcuts, then dock / hide / info / uninstall. */
    override fun appMenu(app: App, anchor: View) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val menu = HaloMenu(anchor).header(app.label)
        val la = getSystemService(LauncherApps::class.java)
        val shortcuts = try {
            if (la.hasShortcutHostPermission()) {
                val q = LauncherApps.ShortcutQuery().setPackage(app.pkg).setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
                la.getShortcuts(q, app.user).orEmpty().sortedBy { it.rank }.take(4)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        // The app's own shortcuts first, with their icons.
        for (sc in shortcuts) {
            val icon = try {
                la.getShortcutIconDrawable(sc, resources.displayMetrics.densityDpi)
            } catch (_: Exception) {
                null
            }
            menu.addDrawable((sc.shortLabel ?: sc.longLabel ?: "Shortcut").toString(), icon) {
                try {
                    la.startShortcut(sc, null, null)
                } catch (_: Exception) {
                    Toast.makeText(this, "Couldn't open that shortcut", Toast.LENGTH_SHORT).show()
                }
            }
        }
        menu.divider()
        val inDock = dockApps().any { it.key == app.key }
        val homeId = "app:${app.key}"
        val onHome = HomeLayout.has(this, homeId)
        menu.add(if (onHome) "Remove from home screen" else "Add to home screen", R.drawable.ic_home) {
            if (onHome) {
                HomeLayout.remove(this, homeId)
                rebuildHome()
            } else {
                if (drawer.isOpen) drawer.close()
                addItem(homeId, 1, 1)
            }
        }
        menu.add(if (inDock) "Remove from dock" else "Add to dock", R.drawable.ic_dock) {
            val cur = Prefs.list(this, Prefs.DOCK).ifEmpty { dockApps().map { it.key }.toMutableList() }
            if (inDock) cur.remove(app.key) else {
                if (cur.size >= DOCK_MAX) cur.removeAt(cur.size - 1)
                cur.add(app.key)
            }
            Prefs.setList(this, Prefs.DOCK, cur)
            rebuildApps()
        }
        if (drawer.isOpen) menu.add("Arrange apps", R.drawable.ic_widgets) { drawer.arrange() }
        if (drawer.isOpen) menu.add("Add to folder", R.drawable.ic_apps) {
            val fm = HaloMenu(anchor).header("Add ${app.label} to")
            for (f in DrawerFolders.ids(this)) fm.add(Groups.folderName(this, f), R.drawable.ic_apps) {
                DrawerFolders.add(this, f, app.key)
                drawer.refresh()
            }
            fm.add("New folder", R.drawable.ic_plus) {
                DrawerFolders.create(this, listOf(app.key), AppRepo.group(app).let { if (it == "Other") "Folder" else it })
                drawer.refresh()
                Toast.makeText(this, "Folder made. Add more apps from their menu, or open it and tap Add apps", Toast.LENGTH_LONG).show()
            }
            fm.show()
        }
        menu.add("Edit home screen", R.drawable.ic_edit) {
            if (drawer.isOpen) drawer.close()
            editor.enter()
        }
        menu.divider()
        menu.add("Hide app", R.drawable.ic_eye_off) {
            val h = Prefs.list(this, Prefs.HIDDEN)
            if (app.key !in h) h.add(app.key)
            Prefs.setList(this, Prefs.HIDDEN, h)
            Toast.makeText(this, "${app.label} hidden. Search still finds it.", Toast.LENGTH_LONG).show()
            rebuildApps()
        }
        menu.add("App info", R.drawable.ic_info) {
            try {
                la.startAppDetailsActivity(app.component, app.user, null, null)
            } catch (_: Exception) {
            }
        }
        menu.add("Uninstall", R.drawable.ic_trash, danger = true) {
            try {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            } catch (_: Exception) {
            }
        }
        menu.onDismiss = { if (menuDrag?.third === menu) menuDrag = null }
        menu.show()
        // Keep holding and move: the menu closes and the app comes with your finger.
        menuDrag = Triple(app, anchor, menu)
    }

    // =====================================================================
    // WidgetActions
    // =====================================================================

    override fun askPermission(permission: String) {
        requestPermissions(arrayOf(permission), 77)
    }

    override fun openSettingsPage(page: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", page))
    }

    override fun openCalendar() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis())))
        } catch (_: Exception) {
            try {
                startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            } catch (_: Exception) {
            }
        }
    }

    override fun editNote() {
        val input = EditText(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)).apply {
            setText(Prefs.get(this@LauncherActivity).getString(Prefs.NOTE, ""))
            setSelection(text.length)
            minLines = 3
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Note")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            })
            .setPositiveButton("Save") { _, _ ->
                Prefs.get(this).edit().putString(Prefs.NOTE, input.text.toString().trim()).apply()
                refreshWidgets("note")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun refreshWeather() {
        Weather.refresh(this, force = true) { onWeather() }
    }

    override fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }

    override fun pickPhoto() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PHOTO)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker found", Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves the picked photo (scaled to a sensible size) for the Photo widget. */
    private fun savePhoto(uri: Uri) {
        Thread {
            val ok = try {
                val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1400) {
                        val f = 1400f / longest
                        dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                    }
                }
                Prefs.picFile(this, PhotoWidget.KEY).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                Prefs.get(this).edit().putLong(PhotoWidget.KEY, System.currentTimeMillis()).apply()
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (!ok) Toast.makeText(this, "Couldn't open that photo", Toast.LENGTH_SHORT).show()
                refreshWidgets("photo")
            }
        }.start()
    }

    override fun musicCommand(cmd: String) {
        // "seek:12000", "queue_play:7": a command with its argument.
        val c = cmd.substringBefore(':')
        val arg = if (cmd.contains(':')) cmd.substringAfter(':') else null
        if (!HaloLink.command(this, c, arg)) noHalo()
    }

    override fun launchPackage(pkg: String, from: View) {
        AppRepo.apps.firstOrNull { it.pkg == pkg }?.let { AppRepo.launch(this, it, from) }
    }

    // =====================================================================
    // Android widgets
    // =====================================================================

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PHOTO && resultCode == RESULT_OK) {
            data?.data?.let { savePhoto(it) }
            return
        }
        if (requestCode == REQ_WIDGET && resultCode == RESULT_OK) {
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            if (id > 0) {
                val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
                val hDp = ((info?.minHeight ?: dp(160)) / resources.displayMetrics.density).toInt()
                addItem("aw:$id", 4, HomeLayout.awRows(this, hDp).coerceAtLeast(2))
            }
        }
    }

    private val awNames = listOf("1 row", "2 rows", "3 rows", "4 rows")

    /** An Android widget, full width, 1 to 4 rows tall (the size pill steps through them). */
    private fun androidWidgetCell(item: HomeLayout.Item): Cell? {
        val id = item.id.removePrefix("aw:").toIntOrNull() ?: return null
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return null
        val frame = FrameLayout(this).apply {
            background = surface(dpf(24f))
            clipToOutline = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val hv = try {
            widgetHost.createView(applicationContext, id, info)
        } catch (_: Exception) {
            null
        } ?: return null
        frame.addView(hv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Tell the widget its size once it really has one (and again if it changes).
        frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (android.os.Build.VERSION.SDK_INT >= 31 && r - l > 0 && b - t > 0 && (r - l != orr - ol || b - t != ob - ot)) {
                try {
                    val d = resources.displayMetrics.density
                    hv.updateAppWidgetSize(Bundle(), listOf(SizeF((r - l) / d, (b - t) / d)))
                } catch (_: Throwable) {
                }
            }
        }
        return Cell(this, editor, "home", item.id, frame, sizeLabel = awNames[(item.sy - 1).coerceIn(0, 3)]).apply {
            onRemove = {
                try {
                    widgetHost.deleteAppWidgetId(id)
                } catch (_: Exception) {
                }
                HomeLayout.remove(this@LauncherActivity, item.id)
                rebuildHome()
            }
            onSize = {
                HomeLayout.resize(this@LauncherActivity, item.id, 4, item.sy % 4 + 1)
                rebuildHome()
                snapToItem(item.id)
            }
        }
    }

    // =====================================================================
    // Edit mode (Editor.Host)
    // =====================================================================

    override val overlay: FrameLayout get() = overlayLayer

    override fun rebuildZone(zone: String) {
        when (zone) {
            "home" -> {
                edgeSince = 0L
                dropAt = null
                rebuildHome()
                editor.lastDropped?.let { snapToItem(it) }
            }
            "dock" -> rebuildDock()
        }
    }

    /** Where the dragged item would land: page, column, row. */
    private var dropAt: IntArray? = null
    private var edgeSince = 0L

    /** Where an item dropped right now would go into (a folder or stack), or null. */
    private var mergeInto: String? = null
    private var hoverArgs: FloatArray? = null
    private var hoverId: String? = null
    private var edgeQueued = false

    /**
     * Turning the page while dragging. Holding still at the edge must work
     * too (no finger movement means no new touch events), so this runs on a
     * timer while the finger, or the item itself, is past the edge.
     */
    private val edgeTick: Runnable = Runnable {
        edgeQueued = false
        val a = hoverArgs ?: return@Runnable
        val id = hoverId ?: return@Runnable
        if (editor.draggingZone != "home") return@Runnable
        val dir = edgeDir(id, a[0], a[2])
        if (dir == 0) return@Runnable
        val to = pager.current + dir
        if (to in grids.indices) {
            pager.snapTo(to)
            root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            // Once the page has turned, show where it would land there.
            root.postDelayed({ editor.rehover() }, 320L)
        }
        edgeQueued = true
        root.postDelayed(edgeTickRef, 750L)
    }
    private val edgeTickRef: Runnable = Runnable { edgeTick.run() }

    /** -1 / +1 when the finger (or a good part of the item) is past the left / right edge. */
    private fun edgeDir(id: String, left: Float, fingerX: Float): Int {
        val item = HomeLayout.get(this, id) ?: return 0
        val g = grids.getOrNull(pager.current) ?: return 0
        val w = item.sx * g.cellW()
        val edge = dp(40)
        return when {
            fingerX < edge || left < -w * 0.3f -> -1
            fingerX > root.width - edge || left + w > root.width + w * 0.3f -> 1
            else -> 0
        }
    }

    override fun homeHover(id: String, left: Float, top: Float, fingerX: Float, fingerY: Float) {
        val item = HomeLayout.get(this, id) ?: return
        hoverArgs = floatArrayOf(left, top, fingerX, fingerY)
        hoverId = id
        // Make sure there's an empty page at the end to drag onto.
        if (grids.size < HomeLayout.pages(this) + 1) {
            grids += newGrid(grids.size).also {
                it.showDots = true
                pager.addView(it)
            }
            dots.pages = grids.size
        }
        if (edgeDir(id, left, fingerX) != 0) {
            if (!edgeQueued) {
                edgeQueued = true
                root.postDelayed(edgeTickRef, 380L)
            }
        } else if (edgeQueued) {
            edgeQueued = false
            root.removeCallbacks(edgeTickRef)
        }
        val page = pager.current
        val g = grids.getOrNull(page) ?: return
        val loc = IntArray(2)
        g.getLocationOnScreen(loc)

        // Over another item that this one can go into? (an app onto an app
        // or folder; a widget onto a widget or stack of the same size)
        val fc = g.cellAt(fingerX - loc[0], fingerY - loc[1])
        val under = HomeLayout.all(this).firstOrNull { o ->
            o.id != id && o.page == page && fc.first >= o.col && fc.first < o.col + o.sx && fc.second >= o.row && fc.second < o.row + o.sy
        }
        val canMerge = under != null && (
            (id.startsWith("app:") && (under.id.startsWith("app:") || under.id.startsWith("folder:"))) ||
                (id.startsWith("w:") && (under.id.startsWith("w:") || under.id.startsWith("stack:")) && under.sx == item.sx && under.sy == item.sy)
            )
        if (canMerge && under != null) {
            val r = g.rectOf(under.col, under.row, under.sx, under.sy, android.graphics.RectF())
            val fx = fingerX - loc[0]
            val fy = fingerY - loc[1]
            val inX = abs(fx - r.centerX()) < r.width() * 0.32f
            val inY = abs(fy - r.centerY()) < r.height() * 0.32f
            if (inX && inY) {
                if (mergeInto != under.id) {
                    mergeInto = under.id
                    dropAt = null
                    for (o in grids) o.preview = null
                    g.merging = true
                    g.preview = intArrayOf(under.col, under.row, under.sx, under.sy)
                    g.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                return
            }
        }
        if (mergeInto != null) {
            mergeInto = null
            g.merging = false
        }

        // The cell under the item's top-left corner (rounded to the nearest).
        val col = Math.round((left - loc[0] - g.paddingLeft) / g.cellW())
        val row = Math.round((top - loc[1] - g.paddingTop) / g.cellH())
        val spot = HomeLayout.nearestFree(this, page, col.coerceIn(0, HomeLayout.COLS - item.sx), row.coerceIn(0, HomeLayout.rows - item.sy), item.sx, item.sy, id)
        val next = spot?.let { intArrayOf(page, it.first, it.second) }
        if (next == null || dropAt?.contentEquals(next) != true) {
            if (next != null && dropAt != null) g.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            dropAt = next
            for (o in grids) {
                o.preview = null
                o.merging = false
            }
            if (next != null) g.preview = intArrayOf(next[1], next[2], item.sx, item.sy)
        }
    }

    override fun homeDrop(id: String): IntArray? {
        root.removeCallbacks(edgeTickRef)
        edgeQueued = false
        val m = mergeInto
        mergeInto = null
        for (o in grids) o.merging = false
        if (m != null) {
            val target = HomeLayout.get(this, m)
            merge(id, m)
            return target?.let { screenOf(it.page, it.col, it.row, it.sx, it.sy) }
        }
        val d = dropAt ?: return HomeLayout.get(this, id)?.let { screenOf(it.page, it.col, it.row, it.sx, it.sy) }
        HomeLayout.moveTo(this, id, d[0], d[1], d[2])
        val item = HomeLayout.get(this, id) ?: return null
        return screenOf(d[0], item.col, item.row, item.sx, item.sy)
    }

    /** Puts [id] into [into]: app + app = new folder; app + folder; widget + widget = new stack; widget + stack. */
    private fun merge(id: String, into: String) {
        when {
            id.startsWith("app:") && into.startsWith("app:") -> {
                val f = Groups.newFolderId()
                Groups.setFolderApps(this, f, listOf(into.removePrefix("app:"), id.removePrefix("app:")))
                HomeLayout.swapId(this, into, f)
                HomeLayout.remove(this, id)
                Toast.makeText(this, "Folder made: tap it to name it", Toast.LENGTH_SHORT).show()
            }
            id.startsWith("app:") && into.startsWith("folder:") -> {
                Groups.setFolderApps(this, into, Groups.folderApps(this, into) + id.removePrefix("app:"))
                HomeLayout.remove(this, id)
            }
            id.startsWith("w:") && into.startsWith("w:") -> {
                val st = Groups.newStackId()
                Groups.setStackWidgets(this, st, listOf(into.removePrefix("w:"), id.removePrefix("w:")))
                HomeLayout.swapId(this, into, st)
                HomeLayout.remove(this, id)
                Toast.makeText(this, "Stack made: swipe up or down on it", Toast.LENGTH_SHORT).show()
            }
            id.startsWith("w:") && into.startsWith("stack:") -> {
                Groups.setStackWidgets(this, into, Groups.stackWidgets(this, into) + id.removePrefix("w:"))
                HomeLayout.remove(this, id)
            }
        }
    }

    // ----- folders (FolderSheet.Host) -----

    private var sheet: FolderSheet? = null

    private fun openFolder(id: String, from: View) {
        sheet?.let { overlayLayer.removeView(it) }
        val s = FolderSheet(this, id, this)
        sheet = s
        overlayLayer.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        s.show(from)
    }

    /** The folder's name was changed in the folder itself: just redraw the home screen. */
    override fun rename(id: String) {
        if (id.startsWith("dfolder:")) drawer.refresh() else rebuildHome()
    }

    /** App list folders: open one, or its menu (size, rename, remove). */
    override fun openDrawerFolder(id: String, from: View) = openFolder(id, from)

    override fun drawerFolderMenu(id: String, anchor: View) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val cur = DrawerFolders.size(this, id)
        val m = HaloMenu(anchor).header(Groups.folderName(this, id))
        for ((i, n) in HomeWidgets.SIZE_NAMES.withIndex()) m.add(n, R.drawable.ic_widgets, checked = i == cur) {
            DrawerFolders.setSize(this, id, i)
            drawer.refresh()
        }
        m.divider()
        m.add("Open and rename", R.drawable.ic_edit) { openFolder(id, anchor) }
        m.add("Arrange apps", R.drawable.ic_widgets) { drawer.arrange() }
        m.add("Remove folder", R.drawable.ic_trash, danger = true) {
            DrawerFolders.delete(this, id)
            drawer.refresh()
        }
        m.show()
    }

    private fun renameDialog(id: String) {
        val input = EditText(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)).apply {
            setText(Groups.folderName(this@LauncherActivity, id))
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Folder name")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            })
            .setPositiveButton("Save") { _, _ ->
                Groups.setFolderName(this, id, input.text.toString())
                sheet?.close()
                rebuildHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun addApps(id: String) {
        sheet?.close()
        startActivity(Intent(this, PickAppsActivity::class.java).putExtra("key", "fapps_" + id.removePrefix("folder:")).putExtra("max", 0).putExtra("title", Groups.folderName(this, id)))
    }

    override fun removeFromFolder(id: String, app: App, anchor: View) {
        if (id.startsWith("dfolder:")) {
            HaloMenu(anchor).header(app.label)
                .add("Take out of folder", R.drawable.ic_arrow_up) {
                    DrawerFolders.take(this, id, app.key)
                    sheet?.close()
                    drawer.refresh()
                }
                .add("App info", R.drawable.ic_info) {
                    try {
                        getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, app.user, null, null)
                    } catch (_: Exception) {
                    }
                }
                .show()
            return
        }
        HaloMenu(anchor).header(app.label)
            .add("Take out of folder", R.drawable.ic_arrow_up) {
                val left = Groups.folderApps(this, id).filter { it != app.key }
                Groups.setFolderApps(this, id, left)
                val f = HomeLayout.get(this, id)
                when {
                    left.isEmpty() -> HomeLayout.remove(this, id)
                    // One app left: the folder becomes that app again.
                    left.size == 1 -> HomeLayout.swapId(this, id, "app:" + left[0], 1, 1)
                }
                // The app goes back on the home screen next to where the folder is.
                if (!HomeLayout.has(this, "app:" + app.key)) HomeLayout.add(this, "app:" + app.key, 1, 1, preferPage = f?.page ?: pager.current)
                sheet?.close()
                rebuildHome()
            }
            .add("App info", R.drawable.ic_info) {
                try {
                    getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, app.user, null, null)
                } catch (_: Exception) {
                }
            }
            .show()
    }

    override fun closed() {
        sheet = null
    }

    /** Edit mode, tap a stack: take a widget out, or split the stack up. */
    private fun stackMenu(id: String, anchor: View) {
        val ids = Groups.stackWidgets(this, id)
        val m = HaloMenu(anchor).header("Widget stack")
        fun takeOut(out: List<String>) {
            val st = HomeLayout.get(this, id)
            val keep = ids - out.toSet()
            Groups.setStackWidgets(this, id, keep)
            if (keep.size == 1) HomeLayout.swapId(this, id, "w:" + keep[0])
            if (keep.isEmpty()) HomeLayout.remove(this, id)
            for (w in out) {
                val (sx, sy) = if (st != null) st.sx to st.sy else HomeLayout.spansFor(HomeWidgets.spec(w).defaultSize)
                HomeLayout.add(this, "w:$w", sx, sy, preferPage = st?.page ?: pager.current)
            }
            rebuildHome()
        }
        for (w in ids) m.add("Take out " + HomeWidgets.ALL.firstOrNull { it.id == w }?.name.orEmpty(), R.drawable.ic_arrow_up) { takeOut(listOf(w)) }
        m.divider()
        m.add("Split the stack", R.drawable.ic_widgets) { takeOut(ids.drop(1)) }
        m.show()
    }

    private fun screenOf(page: Int, col: Int, row: Int, sx: Int, sy: Int): IntArray? {
        val g = grids.getOrNull(page) ?: return null
        val loc = IntArray(2)
        g.getLocationOnScreen(loc)
        val r = g.rectOf(col, row, sx, sy, android.graphics.RectF())
        return intArrayOf(loc[0] + r.left.toInt(), loc[1] + r.top.toInt())
    }

    override fun moveItem(zone: String, id: String, targetId: String) {
        fun MutableList<String>.place(): MutableList<String> {
            val j = indexOf(targetId)
            if (j >= 0 && remove(id)) add(j, id)
            return this
        }
        when (zone) {
            "dock" -> Prefs.setList(this, Prefs.DOCK, dockApps().map { it.key }.toMutableList().place())
        }
    }

    override fun scrollerFor(zone: String): ScrollView? = null

    override fun onEditingChanged(on: Boolean) {
        if (on && drawer.isOpen) drawer.close()
        editBar.animate().cancel()
        if (on) {
            editBar.visibility = View.VISIBLE
            editBar.alpha = 0f
            editBar.translationY = -dpf(20f)
            editBar.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.2f, 1f)).start()
        } else {
            editBar.animate().alpha(0f).translationY(-dpf(20f)).setDuration(180).withEndAction { editBar.visibility = View.GONE }.start()
        }
        for (g in grids) g.showDots = on
        rebuildDock()
    }

    /** The bar across the top in edit mode: add, background, settings, Done. */
    private fun buildEditBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        fun pill(label: String, primary: Boolean = false, onClick: (View) -> Unit) = text(13f, if (primary) Color.BLACK else Palette.INK, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = if (primary) round(Palette.INK, dpf(20f)) else surface(dpf(20f), 0.3f)
            pressable()
            setOnClickListener {
                it.bounce()
                onClick(it)
            }
        }
        // Editing the home screen: Wallpaper and Widgets. Settings has its own app now.
        bar.addView(pill("Wallpaper") { startActivity(Intent(this, WallpaperActivity::class.java)) })
        bar.addView(pill("Widgets") { addMenu(it) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(pill("Done", primary = true) { editor.exit() })
        return bar
    }

    // =====================================================================
    // Small actions
    // =====================================================================

    private fun openClockApp() {
        try {
            startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
        } catch (_: Exception) {
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun swipeDown() {
        val useHalo = Prefs.get(this).getInt(Prefs.SWIPE_DOWN, 0) == 0
        if (useHalo && HaloLink.alive() && HaloLink.command(this, "open_panel")) return
        expandShade()
    }

    @SuppressLint("WrongConstant")
    private fun expandShade() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Exception) {
        }
    }

    private fun doubleTap() {
        if (!Prefs.get(this).getBoolean(Prefs.DOUBLE_TAP_LOCK, true)) return
        if (!(HaloLink.alive() && HaloLink.command(this, "lock"))) {
            Toast.makeText(this, "Double-tap to lock needs Halo running", Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================================
    // Gestures: swipe up = apps, swipe down = notifications, double-tap = lock
    // =====================================================================

    @SuppressLint("ViewConstructor")
    inner class GestureRoot(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var mode = 0 // 0 none, 1 drawer drag, 2 swipe-down fired
        private var edgeTouch = false
        private var tracker: android.view.VelocityTracker? = null

        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!editor.editing) doubleTap()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Tapping empty space finishes editing, like iOS.
                if (editor.editing) editor.exit()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                // Long-press the wallpaper: edit the home screen.
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                editor.enter()
            }
        })

        /** While something is being dragged, every touch goes to the editor. */
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                downRawX = e.rawX
                downRawY = e.rawY
                menuDrag = null
            }
            val md = menuDrag
            if (md != null && e.actionMasked == MotionEvent.ACTION_MOVE && Math.hypot((e.rawX - downRawX).toDouble(), (e.rawY - downRawY).toDouble()) > slop * 2.2) {
                menuDrag = null
                md.third.dismiss()
                dragApp(md.first, md.second, e.rawX, e.rawY)
                // Tell whoever had the touch that it's over.
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                return true
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) menuDrag = null
            if (editor.isDragging) {
                editor.onTouch(e)
                return true
            }
            return super.dispatchTouchEvent(e)
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            // An open folder owns every touch (it used to let swipes reach the home screen behind).
            if (drawer.isOpen || editor.editing || sheet != null) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    mode = 0
                    // Touches that start on the phone's own gesture strips (the
                    // home bar at the bottom, the shade at the top) belong to
                    // Android's navigation: never open the app list from there.
                    val gi = rootWindowInsets?.let {
                        val a = it.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures())
                        val b = it.getInsets(android.view.WindowInsets.Type.systemGestures())
                        intArrayOf(maxOf(a.top, b.top), maxOf(a.bottom, b.bottom))
                    }
                    edgeTouch = gi != null && (e.y > height - gi[1] - dp(6) || e.y < gi[0])
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain()
                    tracker?.addMovement(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (mode == 0 && !edgeTouch && abs(dy) > slop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
                        val up = dy < 0
                        if (up) {
                            mode = 1
                            drawer.visibility = View.VISIBLE
                            drawer.refresh()
                            return true
                        } else {
                            mode = 2
                            swipeDown()
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            tracker?.addMovement(e)
            when (mode) {
                1 -> when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> drawer.drag(((downY - e.y) / (height * 0.4f)).coerceIn(0f, 1f))
                    MotionEvent.ACTION_UP -> {
                        tracker?.computeCurrentVelocity(1000)
                        val vy = tracker?.yVelocity ?: 0f
                        if (vy < -500f || (vy < 500f && drawer.progress() > 0.3f)) drawer.open() else drawer.close()
                        mode = 0
                    }
                    // The system took the gesture over (home / recents): put the list away.
                    MotionEvent.ACTION_CANCEL -> {
                        drawer.close()
                        mode = 0
                    }
                }
                else -> if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) mode = 0
            }
            return true
        }
    }

    companion object {
        const val WIDGET_HOST_ID = 4207
        const val DOCK_MAX = 6
        /** Sizes the clock, date and weather text can take (cells across x down). */
        val TEXT_SIZES = mapOf(
            "clock" to listOf(2 to 2, 4 to 2, 4 to 3),
            "date" to listOf(2 to 1, 4 to 1, 4 to 2),
            "wx" to listOf(2 to 1, 4 to 1, 2 to 2),
        )
        private const val REQ_WIDGET = 31
        private const val REQ_PHOTO = 32
    }
}
