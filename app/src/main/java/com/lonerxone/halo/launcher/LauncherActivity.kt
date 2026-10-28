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
import android.widget.PopupMenu
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
 * Page 2: the date, Halo's own widgets (resizable, reorderable), then any
 * Android widgets. The dock floats above the bottom edge.
 */
class LauncherActivity : Activity(), Drawer.Host, WidgetActions {

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
    private var page1: LinearLayout? = null
    private var page2Col: LinearLayout? = null
    private var clock: StackedClock? = null
    private var sideInfo: LinearLayout? = null
    private var dateLine: TextView? = null
    private var tempText: TextView? = null
    private var tempIcon: ImageView? = null
    private var weatherDesc: TextView? = null
    private var music: MusicCard? = null
    private var musicTick: Runnable? = null
    private var dayName: TextView? = null
    private var dayBig: TextView? = null
    private var widgetGrid: LinearLayout? = null
    private val widgets = ArrayList<BaseWidget>()
    private var androidWidgets: LinearLayout? = null
    private var lastScreenTime = 0L

    private val appsChanged: () -> Unit = { rebuildApps() }
    private val haloChanged: () -> Unit = { onHalo() }
    private val glassChanged: () -> Unit = {
        bg.invalidate()
        root.invalidateGlass()
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
        build()
        AppRepo.addListener(appsChanged)
        AppRepo.load(this)
        HaloLink.addListener(haloChanged)
        HaloLink.start(this)
        Glass.addListener(glassChanged)
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
        refreshLook()
        onMinute()
        Steps.start(this) { refreshWidgets("steps") }
        HaloLink.command(this, "request_state")
        Weather.refresh(this) { onWeather() }
        animateIn()
    }

    override fun onResume() {
        super.onResume()
        startMusicTick()
    }

    override fun onPause() {
        super.onPause()
        musicTick?.let { ui.removeCallbacks(it) }
    }

    override fun onStop() {
        super.onStop()
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
    }

    override fun onDestroy() {
        AppRepo.removeListener(appsChanged)
        HaloLink.removeListener(haloChanged)
        Glass.removeListener(glassChanged)
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
            drawer.isOpen -> drawer.close()
            pager.current != 0 -> pager.snapTo(0)
            else -> (pager.page(1) as? ScrollView)?.smoothScrollTo(0, 0)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
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
        root = GestureRoot(this)
        bg = BackgroundView(this)
        root.addView(bg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        pager = HomePager(this)
        pager.addView(buildPage1())
        pager.addView(buildPage2())
        pager.onScrolled = { f -> onPagerScroll(f) }
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dots = PageDots(this)
        root.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14), Gravity.BOTTOM))

        dock = DockBar(this)
        dock.setOnLongClickListener {
            pickDock()
            true
        }
        root.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dockBody, Gravity.BOTTOM).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
        })

        drawer = Drawer(this, this)
        root.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            insetTop = bars.top
            insetBottom = bars.bottom
            layoutForInsets()
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) loadBackdrop()
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
        page1?.setPadding(dp(20), insetTop + dp(26), dp(20), above + dp(30))
        page2Col?.setPadding(dp(16), insetTop + dp(22), dp(16), above + dp(36))
        drawer.setPadding(0, insetTop, 0, 0)
    }

    private fun refreshLook() {
        root.setBackgroundColor(Color.TRANSPARENT)
        clock?.style = Prefs.get(this).getInt(Prefs.CLOCK_STYLE, StackedClock.VARSITY)
        dock.restyle()
        music?.restyle()
        drawer.restyle()
        restyleButtons()
        rebuildApps()
        rebuildWidgets()
        rebuildAndroidWidgets()
        loadBackdrop()
    }

    /** Your picture, or the wallpaper, behind everything (the glass bends it). */
    private fun loadBackdrop() {
        root.post {
            if (root.width > 0 && root.height > 0) Glass.load(this, root.width, root.height) { glassChanged() }
        }
    }

    /** Dots follow the finger; page 2's widgets lag behind for depth; glass re-samples as it moves. */
    private fun onPagerScroll(f: Float) {
        dots.position = f
        val g = widgetGrid
        if (g != null) {
            val off = (1f - f).coerceIn(0f, 1f)
            for (i in 0 until g.childCount) g.getChildAt(i).translationX = off * dpf(40f) * (1 + i * 0.35f)
        }
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
        clock?.enter(0)
        sideInfo?.enter(70)
        music?.enter(140)
        for (i in 0 until dock.childCount) dock.getChildAt(i).enter(180L + i * 40L, dpf(16f))
        if (pager.current == 1) widgetGrid?.let { g -> for (i in 0 until g.childCount) g.getChildAt(i).enter(i * 60L) }
    }

    // ----- Page 1: clock, date + weather, music -----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPage1(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page1 = page
        page.setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val c = StackedClock(this)
        clock = c
        c.setOnClickListener {
            it.bounce()
            openClockApp()
        }
        row.addView(c, LinearLayout.LayoutParams(dp(170), dp(176)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, dp(8), dp(2), 0)
            setOnClickListener {
                it.bounce()
                pager.snapTo(1)
            }
        }
        sideInfo = right
        val dl = text(12f, Palette.INK, caps = true)
        dateLine = dl
        right.addView(dl)
        val wRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val ti = ImageView(this).apply { imageTintList = ColorStateList.valueOf(Palette.INK) }
        tempIcon = ti
        wRow.addView(ti, LinearLayout.LayoutParams(dp(20), dp(20)))
        val tt = text(34f, Palette.INK).apply {
            typeface = Fonts.dots(this@LauncherActivity)
            setPadding(dp(8), 0, 0, 0)
        }
        tempText = tt
        wRow.addView(tt)
        right.addView(wRow)
        val wd = text(10.5f, Palette.SILVER, caps = true).apply { setPadding(0, dp(6), 0, 0) }
        weatherDesc = wd
        right.addView(wd)
        row.addView(right)
        page.addView(row)

        // The middle stays open: the picture is the point.
        page.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val m = MusicCard(this) { cmd -> if (!HaloLink.command(this, cmd)) noHalo() }
        music = m
        page.addView(m, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))
        return page
    }

    private fun onHalo() {
        music?.bind(HaloLink.music)
        startMusicTick()
        updateBadges()
    }

    private fun startMusicTick() {
        musicTick?.let { ui.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                music?.tick()
                if (HaloLink.music?.playing == true) ui.postDelayed(this, 1000L)
            }
        }
        musicTick = r
        ui.postDelayed(r, 1000L)
    }

    private fun noHalo() {
        Toast.makeText(this, if (HaloLink.installed(this)) "Turn on Halo to control music from here" else "Install Halo to see your music here", Toast.LENGTH_SHORT).show()
    }

    // ----- Page 2: the date, Halo's widgets, Android widgets -----

    private val buttons = ArrayList<TextView>()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPage2(): View {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            setOnScrollChangeListener { v, _, _, _, _ -> v.invalidateGlass() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page2Col = col
        col.setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }

        val dateCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setOnClickListener {
                it.bounce()
                openCalendar()
            }
        }
        val dn = text(10.5f, Palette.SILVER, caps = true)
        dayName = dn
        dateCol.addView(dn)
        val db = text(40f, Palette.INK).apply {
            typeface = Fonts.light(this@LauncherActivity)
            setPadding(0, dp(2), 0, 0)
        }
        dayBig = db
        dateCol.addView(db)
        col.addView(dateCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            marginEnd = dp(6)
        })

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        widgetGrid = grid
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val aw = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        androidWidgets = aw
        col.addView(aw)

        val add = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun pill(label: String, onClick: () -> Unit) = text(12.5f, Palette.INK, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(11), dp(18), dp(11))
            pressable()
            setOnClickListener {
                it.bounce()
                onClick()
            }
            buttons += this
        }
        add.addView(pill("+  Widget") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this@LauncherActivity, WidgetPickActivity::class.java), REQ_WIDGET)
        })
        add.addView(pill("Edit") { openSettingsPage("widgets") }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        col.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        col.addView(text(11f, Palette.SILVER).apply {
            text = "Long-press a widget to resize or move it"
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        scroll.addView(col)
        return scroll
    }

    private fun restyleButtons() {
        for (b in buttons) b.background = b.surface(dpf(20f), 0.3f)
    }

    /**
     * Lays Halo's widgets out on an iOS-style grid. One unit is half the width
     * (minus the gap): Small = 1x1 (two side by side), Medium = 2x1, Large = 2x2.
     */
    private fun rebuildWidgets() {
        val grid = widgetGrid ?: return
        grid.removeAllViews()
        widgets.clear()
        val gap = dp(12)
        val unit = (resources.displayMetrics.widthPixels - dp(32) - gap) / 2
        var pending: BaseWidget? = null
        fun flushSmall(a: BaseWidget, b: BaseWidget?) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(b ?: View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = gap })
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, unit).apply { topMargin = gap })
        }
        for (id in HomeWidgets.order(this)) {
            if (!Prefs.widgetOn(this, id)) continue
            val w = HomeWidgets.create(this, id, this) ?: continue
            widgets += w
            w.setOnLongClickListener {
                editWidget(w)
                true
            }
            if (w.size == HomeWidgets.SMALL) {
                val p = pending
                if (p == null) pending = w else {
                    flushSmall(p, w)
                    pending = null
                }
            } else {
                pending?.let { flushSmall(it, null); pending = null }
                val h = if (w.size == HomeWidgets.LARGE) unit * 2 + gap else unit
                grid.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = gap })
            }
        }
        pending?.let { flushSmall(it, null) }
    }

    /** Long-press a widget: pick its size, move it, or take it off the page. */
    private fun editWidget(w: BaseWidget) {
        w.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        w.animate().scaleX(0.96f).scaleY(0.96f).setDuration(140).withEndAction {
            w.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
        val m = PopupMenu(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault), w)
        HomeWidgets.SIZE_NAMES.forEachIndexed { i, name ->
            m.menu.add(0, 10 + i, i, name).apply {
                isCheckable = true
                isChecked = w.size == i
            }
        }
        m.menu.setGroupCheckable(0, true, true)
        val order = HomeWidgets.order(this).filter { Prefs.widgetOn(this, it) }
        val at = order.indexOf(w.id)
        if (at > 0) m.menu.add(1, 2, 10, "Move up")
        if (at in 0 until order.size - 1) m.menu.add(1, 3, 11, "Move down")
        m.menu.add(1, 4, 12, "Remove from home")
        m.menu.add(1, 5, 13, "Edit widgets")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                in 10..12 -> HomeWidgets.setSize(this, w.id, item.itemId - 10)
                2 -> HomeWidgets.move(this, w.id, stepsTo(w.id, -1))
                3 -> HomeWidgets.move(this, w.id, stepsTo(w.id, 1))
                4 -> Prefs.setWidget(this, w.id, false)
                5 -> {
                    openSettingsPage("widgets")
                    return@setOnMenuItemClickListener true
                }
            }
            rebuildWidgets()
            widgets.firstOrNull { it.id == w.id }?.enter(0, dpf(12f))
            true
        }
        m.show()
    }

    /** How far to move in the full order so the widget passes the next one that's switched on. */
    private fun stepsTo(id: String, dir: Int): Int {
        val all = HomeWidgets.order(this)
        var i = all.indexOf(id)
        var steps = 0
        while (true) {
            i += dir
            steps += dir
            if (i !in all.indices) return steps - dir
            if (Prefs.widgetOn(this, all[i])) return steps
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
        val w = Weather.now
        if (w != null) {
            tempIcon?.setImageResource(Weather.icon(w.code, w.isDay))
            tempText?.text = "${w.temp}°"
            weatherDesc?.text = listOf(Weather.describe(w.code), w.city).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            tempIcon?.setImageDrawable(null)
            tempText?.text = ""
            weatherDesc?.text = if (Weather.hasPlace(this)) "" else "Set your city"
        }
        refreshWidgets("weather")
    }

    private fun onMinute() {
        clock?.tick()
        val now = Date()
        dateLine?.text = DateFormat.format("EEE d MMM", now)
        dayName?.text = DateFormat.format("EEEE", now)
        dayBig?.text = DateFormat.format("d MMM", now)
        refreshWidgets("agenda")
        refreshWidgets("battery")
        refreshWidgets("screen")
    }

    // ----- Apps: the dock -----

    private fun rebuildApps() {
        if (AppRepo.apps.isEmpty()) return
        dock.removeAllViews()
        val apps = dockApps()
        val size = dock.iconFor(apps.size)
        for (a in apps) {
            dock.addView(iconView(a, size, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        drawer.refresh()
        updateBadges()
    }

    private fun iconView(a: App, size: Int, label: Boolean): AppIconView = AppIconView(this, a, size, label).apply {
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

    private fun updateBadges() {
        for (i in 0 until dock.childCount) (dock.getChildAt(i) as? AppIconView)?.let { it.badge = HaloLink.badges[it.app.pkg] ?: 0 }
        drawer.updateBadges()
    }

    // =====================================================================
    // Drawer.Host
    // =====================================================================

    override fun launch(app: App, from: View) {
        AppRepo.launch(this, app, from)
    }

    override fun onDrawerProgress(progress: Float) {
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
        val menu = PopupMenu(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault), anchor)
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
        shortcuts.forEachIndexed { i, s -> menu.menu.add(0, 100 + i, i, s.shortLabel ?: s.longLabel ?: "Shortcut") }
        val inDock = dockApps().any { it.key == app.key }
        menu.menu.add(1, 1, 10, if (inDock) "Remove from dock" else "Add to dock")
        menu.menu.add(1, 6, 11, "Change dock apps")
        menu.menu.add(1, 3, 12, "Hide app")
        menu.menu.add(1, 4, 13, "App info")
        menu.menu.add(1, 5, 14, "Uninstall")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                in 100..199 -> try {
                    la.startShortcut(shortcuts[item.itemId - 100], null, null)
                } catch (_: Exception) {
                    Toast.makeText(this, "Couldn't open that shortcut", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    val cur = Prefs.list(this, Prefs.DOCK).ifEmpty { dockApps().map { it.key }.toMutableList() }
                    if (inDock) cur.remove(app.key) else {
                        if (cur.size >= DOCK_MAX) cur.removeAt(cur.size - 1)
                        cur.add(app.key)
                    }
                    Prefs.setList(this, Prefs.DOCK, cur)
                    rebuildApps()
                }
                6 -> pickDock()
                3 -> {
                    val h = Prefs.list(this, Prefs.HIDDEN)
                    if (app.key !in h) h.add(app.key)
                    Prefs.setList(this, Prefs.HIDDEN, h)
                    Toast.makeText(this, "${app.label} hidden. Search still finds it.", Toast.LENGTH_LONG).show()
                    rebuildApps()
                }
                4 -> try {
                    la.startAppDetailsActivity(app.component, app.user, null, null)
                } catch (_: Exception) {
                }
                5 -> try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                } catch (_: Exception) {
                }
            }
            true
        }
        menu.show()
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
        if (requestCode == REQ_WIDGET && resultCode == RESULT_OK) {
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            if (id > 0) {
                val ids = Prefs.list(this, Prefs.WIDGETS)
                ids.add(id.toString())
                Prefs.setList(this, Prefs.WIDGETS, ids)
                rebuildAndroidWidgets()
                pager.snapTo(1)
            }
        }
    }

    private fun rebuildAndroidWidgets() {
        val box = androidWidgets ?: return
        box.removeAllViews()
        val awm = AppWidgetManager.getInstance(this)
        for (idStr in Prefs.list(this, Prefs.WIDGETS)) {
            val id = idStr.toIntOrNull() ?: continue
            val info = awm.getAppWidgetInfo(id) ?: continue
            val hDp = Prefs.get(this).getInt("wh_$id", (info.minHeight / resources.displayMetrics.density).toInt().coerceIn(90, 420))
            val frame = FrameLayout(this).apply {
                background = surface(dpf(24f))
                clipToOutline = true
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val hv = try {
                widgetHost.createView(applicationContext, id, info)
            } catch (_: Exception) {
                null
            } ?: continue
            frame.addView(hv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_more)
                imageTintList = ColorStateList.valueOf(Palette.SILVER)
                background = round(Color.argb(170, 0, 0, 0), dpf(12f))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener { widgetMenu(id, it) }
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
            box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(hDp)).apply { topMargin = dp(12) })
            frame.post {
                try {
                    val d = resources.displayMetrics.density
                    hv.updateAppWidgetSize(Bundle(), listOf(SizeF(frame.width / d, frame.height / d)))
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun widgetMenu(id: Int, anchor: View) {
        val m = PopupMenu(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault), anchor)
        m.menu.add(0, 1, 0, "Taller")
        m.menu.add(0, 2, 1, "Shorter")
        m.menu.add(0, 3, 2, "Remove")
        m.setOnMenuItemClickListener { item ->
            val p = Prefs.get(this)
            val cur = p.getInt("wh_$id", 160)
            when (item.itemId) {
                1 -> p.edit().putInt("wh_$id", (cur + 40).coerceAtMost(520)).apply()
                2 -> p.edit().putInt("wh_$id", (cur - 40).coerceAtLeast(70)).apply()
                3 -> {
                    try {
                        widgetHost.deleteAppWidgetId(id)
                    } catch (_: Exception) {
                    }
                    Prefs.setList(this, Prefs.WIDGETS, Prefs.list(this, Prefs.WIDGETS).filter { it != id.toString() })
                }
            }
            rebuildAndroidWidgets()
            true
        }
        m.show()
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
        private var tracker: android.view.VelocityTracker? = null

        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                doubleTap()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                openSettings()
            }
        })

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (drawer.isOpen) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    mode = 0
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain()
                    tracker?.addMovement(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (mode == 0 && abs(dy) > slop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
                        val inDock = downY > dock.top - dp(20)
                        val scroll = pager.page(1) as ScrollView
                        val up = dy < 0
                        val allowed = when {
                            inDock || pager.current == 0 -> true
                            up -> !scroll.canScrollVertically(1)
                            else -> !scroll.canScrollVertically(-1)
                        }
                        if (!allowed) return false
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
                    MotionEvent.ACTION_MOVE -> drawer.drag(((downY - e.y) / (height * 0.6f)).coerceIn(0f, 1f))
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracker?.computeCurrentVelocity(1000)
                        val vy = tracker?.yVelocity ?: 0f
                        if (vy < -700f || (vy < 700f && drawer.progress() > 0.3f)) drawer.open() else drawer.close()
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
        private const val REQ_WIDGET = 31
    }
}
