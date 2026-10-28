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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
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
 * Page 1: your picture with a wave edge and the big stacked clock, then the
 * music card (from Halo), battery, date and a capsule photo, and your
 * favourite apps. Page 2: Halo's notifications and quick switches, a month
 * calendar, a note, app capsules by kind, the forecast and any Android
 * widgets you add. A floating glass dock sits on top; swipe up for all apps.
 */
class LauncherActivity : Activity(), Drawer.Host {

    private lateinit var root: GestureRoot
    private lateinit var content: LinearLayout
    private lateinit var pager: HomePager
    private lateinit var drawer: Drawer
    private lateinit var dots: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var widgetHost: AppWidgetHost
    private val ui = Handler(Looper.getMainLooper())
    private var insetTop = 0
    private var insetBottom = 0

    // Live views
    private var clock: StackedClock? = null
    private var hero: HeroView? = null
    private var dateLine: TextView? = null
    private var weatherMini: LinearLayout? = null
    private var musicBox: FrameLayout? = null
    private var batteryText: TextView? = null
    private var batteryBar: BarView? = null
    private var batteryIcon: ImageView? = null
    private var dateDay: TextView? = null
    private var dateName: TextView? = null
    private var capsule: CapsuleView? = null
    private var favorites: LinearLayout? = null
    private var monthView: MonthView? = null
    private var monthTitle: TextView? = null
    private var noteText: TextView? = null
    private var haloCard: LinearLayout? = null
    private var folders: LinearLayout? = null
    private var forecast: LinearLayout? = null
    private var widgetsBox: LinearLayout? = null
    private var musicTick: Runnable? = null
    private var musicBar: BarView? = null

    private val appsChanged: () -> Unit = { rebuildApps() }
    private val haloChanged: () -> Unit = { onHalo() }

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(i)
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
        }
        registerReceiver(tick, f)
        onMinute()
        refreshLook()
        HaloLink.command(this, "request_state")
        Weather.refresh(this) { onWeather() }
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
    }

    override fun onDestroy() {
        AppRepo.removeListener(appsChanged)
        HaloLink.removeListener(haloChanged)
        super.onDestroy()
    }

    /** Home pressed while already home: close the drawer, or go back to page 1. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (drawer.isOpen) drawer.close() else pager.snapTo(0)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            drawer.isOpen -> drawer.close()
            pager.current != 0 -> pager.snapTo(0)
        }
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private fun build() {
        root = GestureRoot(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pager = HomePager(this)
        pager.addView(buildPage1())
        pager.addView(buildPage2())
        pager.onPageChanged = { p -> updateDots(p) }
        pager.onScrolled = { f -> scrollWallpaper(f) }
        content.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        content.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glass(dpf(30f))
            setPadding(dp(10), 0, dp(10), 0)
        }
        content.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)).apply {
            setMargins(dp(18), dp(6), dp(18), dp(10))
        })
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        drawer = Drawer(this, this)
        root.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            insetTop = bars.top
            insetBottom = bars.bottom
            content.setPadding(0, bars.top, 0, bars.bottom)
            drawer.setPadding(0, bars.top, 0, 0)
            insets
        }
        setContentView(root)
        updateDots(0)
    }

    /** Wallpaper or pure black behind everything. */
    private fun refreshLook() {
        val wall = Prefs.get(this).getBoolean(Prefs.SHOW_WALLPAPER, false)
        root.setBackgroundColor(if (wall) Color.argb(70, 0, 0, 0) else Color.BLACK)
        clock?.style = Prefs.get(this).getInt(Prefs.CLOCK_STYLE, 0)
        hero?.picture = loadPic(Prefs.HERO_PIC)
        capsule?.picture = loadPic(Prefs.CAPSULE_PIC)
        noteText?.text = Prefs.get(this).getString(Prefs.NOTE, "").orEmpty().ifEmpty { "Tap to write a note" }
        rebuildApps()
        rebuildWidgets()
    }

    private fun loadPic(key: String) = try {
        val f = Prefs.picFile(this, key)
        if (f.exists() && Prefs.get(this).getLong(key, 0L) != 0L) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (_: Throwable) {
        null
    }

    private fun updateDots(page: Int) {
        dots.removeAllViews()
        for (i in 0 until pager.childCount) {
            dots.addView(View(this).apply {
                background = round(if (i == page) Palette.INK else Color.argb(80, 255, 255, 255), dpf(3f))
            }, LinearLayout.LayoutParams(if (i == page) dp(16) else dp(6), dp(6)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun scrollWallpaper(f: Float) {
        if (!Prefs.get(this).getBoolean(Prefs.SHOW_WALLPAPER, false)) return
        try {
            val wm = WallpaperManager.getInstance(this)
            val pages = (pager.childCount - 1).coerceAtLeast(1)
            wm.setWallpaperOffsetSteps(1f / pages, 0f)
            wm.setWallpaperOffsets(root.windowToken, (f / pages).coerceIn(0f, 1f), 0f)
        } catch (_: Exception) {
        }
    }

    // ----- Page 1 -----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPage1(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        page.setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }

        // Hero: your picture with the wave edge, the clock on it, the weather beside it.
        val heroBox = FrameLayout(this)
        val h = HeroView(this)
        hero = h
        h.setOnClickListener { openSettings() }
        heroBox.addView(h, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val clockCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, 0, dp(16))
        }
        val c = StackedClock(this)
        clock = c
        c.setOnClickListener {
            it.bounce()
            openClockApp()
        }
        clockCol.addView(c, LinearLayout.LayoutParams(dp(150), dp(150)))
        val dl = text(12.5f, Palette.INK, bold = true).apply {
            letterSpacing = 0.14f
            setPadding(dp(2), dp(6), 0, 0)
            setShadowLayer(dpf(6f), 0f, 0f, Color.argb(160, 0, 0, 0))
        }
        dateLine = dl
        clockCol.addView(dl)
        heroBox.addView(clockCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        val wm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, 0, dp(6), dp(14))
            setOnClickListener {
                it.bounce()
                pager.snapTo(1)
            }
        }
        weatherMini = wm
        heroBox.addView(wm, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        page.addView(heroBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.45f))

        // Music card + battery / date / capsule column.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mb = FrameLayout(this)
        musicBox = mb
        row.addView(mb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(batteryWidget(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f))
        side.addView(dateWidget(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        val cap = CapsuleView(this)
        capsule = cap
        cap.setOnClickListener { openSettings() }
        side.addView(cap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f).apply { topMargin = dp(4) })
        row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) })
        page.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        // Favourite apps with labels and badges.
        val fav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        favorites = fav
        page.addView(fav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)).apply { topMargin = dp(12) })
        renderMusic()
        return page
    }

    private fun batteryWidget(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = paper(dpf(30f))
            setPadding(dp(8), dp(8), dp(14), dp(8))
            setOnClickListener {
                it.bounce()
                try {
                    startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                } catch (_: Exception) {
                }
            }
        }
        val pill = FrameLayout(this)
        val bar = BarView(this).apply {
            track = Color.argb(30, 0, 0, 0)
            bar = Color.argb(60, 0, 0, 0)
        }
        batteryBar = bar
        pill.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val t = text(15f, Color.BLACK, bold = true).apply { gravity = Gravity.CENTER }
        batteryText = t
        pill.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(pill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val ic = ImageView(this).apply {
            setImageResource(R.drawable.ic_battery)
            imageTintList = ColorStateList.valueOf(Color.argb(150, 0, 0, 0))
        }
        batteryIcon = ic
        box.addView(ic, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10) })
        return box
    }

    private fun dateWidget(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glass(dpf(26f))
            setOnClickListener {
                it.bounce()
                openCalendar()
            }
        }
        val d = text(28f, Palette.INK, bold = true).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val n = text(28f, Palette.WHITE70, bold = false).apply {
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setPadding(dp(8), 0, 0, 0)
        }
        dateDay = d
        dateName = n
        box.addView(d)
        box.addView(n)
        return box
    }

    // ----- Music (from Halo) -----

    private fun renderMusic() {
        val box = musicBox ?: return
        box.removeAllViews()
        musicBar = null
        val m = HaloLink.music
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dpf(28f))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            clipToOutline = true
        }
        // Cover (or a quiet placeholder), rounded, on top.
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(18f))
                }
            }
            clipToOutline = true
            if (m?.art != null) {
                setImageBitmap(m.art)
            } else {
                background = round(Palette.GRAPHITE, dpf(18f))
                setImageResource(R.drawable.ic_music)
                imageTintList = ColorStateList.valueOf(Palette.WHITE50)
                scaleType = ImageView.ScaleType.CENTER
            }
            setOnClickListener {
                it.bounce()
                if (!HaloLink.command(this@LauncherActivity, "open_music")) noHalo()
            }
        }
        card.addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(text(14.5f, Palette.INK, bold = true).apply {
            text = m?.title ?: if (HaloLink.installed(this@LauncherActivity)) "No music playing" else "Music via Halo"
            setPadding(dp(4), dp(10), 0, 0)
        })
        card.addView(text(12f, Palette.WHITE50).apply {
            text = m?.artist?.ifEmpty { null } ?: if (m == null) "Tap play in any app" else ""
            setPadding(dp(4), dp(3), 0, 0)
        })
        if (m != null && m.durationMs > 0) {
            val bar = BarView(this).apply {
                track = Color.argb(46, 255, 255, 255)
                bar = Palette.INK
                fraction = m.position() / m.durationMs.toFloat()
            }
            musicBar = bar
            card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                topMargin = dp(8)
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        val ctl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        fun btn(res: Int, big: Boolean, cmd: String) = ImageView(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(if (big) Color.BLACK else Palette.INK)
            val pad = if (big) dp(9) else dp(7)
            setPadding(pad, pad, pad, pad)
            if (big) background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.INK)
            }
            setOnClickListener {
                it.bounce()
                if (!HaloLink.command(this@LauncherActivity, cmd)) noHalo()
            }
        }
        ctl.addView(btn(R.drawable.ic_prev, false, "prev"), LinearLayout.LayoutParams(0, dp(36), 1f))
        ctl.addView(btn(if (m?.playing == true) R.drawable.ic_pause else R.drawable.ic_play, true, "play_pause"), LinearLayout.LayoutParams(dp(40), dp(40)))
        ctl.addView(btn(R.drawable.ic_next, false, "next"), LinearLayout.LayoutParams(0, dp(36), 1f))
        card.addView(ctl)
        box.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        startMusicTick()
    }

    private fun startMusicTick() {
        musicTick?.let { ui.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val m = HaloLink.music
                val bar = musicBar
                if (m != null && bar != null && m.durationMs > 0) bar.fraction = m.position() / m.durationMs.toFloat()
                if (m?.playing == true) ui.postDelayed(this, 1000L)
            }
        }
        musicTick = r
        ui.postDelayed(r, 1000L)
    }

    private fun noHalo() {
        Toast.makeText(this, if (HaloLink.installed(this)) "Turn on Halo to control music from here" else "Install Halo to see your music here", Toast.LENGTH_SHORT).show()
    }

    // ----- Page 2 -----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPage2(): View {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        col.setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }
        scroll.addView(col)

        val hc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        haloCard = hc
        col.addView(hc)

        // Calendar (paper) + note (glass), side by side.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = paper(dpf(28f))
            setPadding(dp(12), dp(12), dp(12), dp(10))
            setOnClickListener {
                it.bounce()
                openCalendar()
            }
        }
        val mt = text(16f, Color.BLACK, bold = true).apply { setPadding(dp(4), 0, 0, dp(4)) }
        monthTitle = mt
        cal.addView(mt)
        val mv = MonthView(this)
        monthView = mv
        cal.addView(mv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        row.addView(cal, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f))
        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dpf(28f))
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setOnClickListener { editNote() }
        }
        note.addView(text(11.5f, Color.BLACK, bold = true).apply {
            text = "Note"
            gravity = Gravity.CENTER
            background = round(Palette.SILVER, dpf(12f))
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val nt = text(19f, Palette.INK, lines = 6).apply {
            typeface = Typeface.create("cursive", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
        }
        noteText = nt
        note.addView(nt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        row.addView(note, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) })
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(12) })

        // App capsules by kind.
        val fd = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        folders = fd
        col.addView(fd, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // Forecast.
        val fc = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(dpf(28f))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener {
                it.bounce()
                if (!Weather.hasPlace(this@LauncherActivity)) openSettings() else Weather.refresh(this@LauncherActivity, force = true) { onWeather() }
            }
        }
        forecast = fc
        col.addView(fc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // Android widgets.
        val wb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        widgetsBox = wb
        col.addView(wb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        col.addView(text(14f, Palette.INK, bold = true).apply {
            text = "+  Add a widget"
            gravity = Gravity.CENTER
            background = glass(dpf(24f))
            setOnClickListener {
                it.bounce()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this@LauncherActivity, WidgetPickActivity::class.java), REQ_WIDGET)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })

        renderHalo()
        onWeather()
        return scroll
    }

    // ----- Halo card: notifications + quick switches -----

    private fun onHalo() {
        renderMusic()
        renderHalo()
        updateBadges()
    }

    private fun renderHalo() {
        val hc = haloCard ?: return
        hc.removeAllViews()
        if (!HaloLink.installed(this)) {
            hc.addView(glassRow(R.drawable.ic_halo, "Get Halo", "The island for your camera. It feeds music, badges and notifications into this launcher.") {
                openUrl("https://github.com/LONERXONE-ops/halo/releases/latest")
            })
            return
        }
        val q = HaloLink.quick
        if (q.isNotEmpty()) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (it in q) {
                chips.addView(text(13f, Color.BLACK, bold = true).apply {
                    text = it.label + "  ✕"
                    background = round(Palette.INK, dpf(16f))
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { v ->
                        v.bounce()
                        HaloLink.command(this@LauncherActivity, "quick", it.id)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            hc.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dpf(28f))
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_halo) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        head.addView(text(15f, Palette.INK, bold = true).apply {
            text = "Notifications"
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(12f, Palette.INK, bold = true).apply {
            text = "Open"
            background = round(Palette.GRAPHITE, dpf(12f))
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener {
                it.bounce()
                if (!HaloLink.command(this@LauncherActivity, "open_panel")) noHalo()
            }
        })
        card.addView(head)
        val notes = HaloLink.notes
        if (!HaloLink.alive()) {
            card.addView(text(13f, Palette.WHITE50, lines = 3).apply {
                text = "Turn on Halo (Accessibility and notification access) to see your notifications here."
                setPadding(0, dp(10), 0, dp(6))
            })
        } else if (notes.isEmpty()) {
            card.addView(text(13f, Palette.WHITE50).apply {
                text = "You're all caught up"
                setPadding(0, dp(10), 0, dp(6))
            })
        }
        for (n in notes.take(4)) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
                setOnClickListener {
                    it.bounce()
                    HaloLink.command(this@LauncherActivity, "open_notif", n.key)
                }
            }
            val app = AppRepo.apps.firstOrNull { it.pkg == n.app }
            if (app != null) r.addView(AppIconView(this, app, 30, false), LinearLayout.LayoutParams(dp(40), dp(32)))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            texts.addView(text(13.5f, Palette.INK, bold = true).apply { text = n.title.ifEmpty { n.appName } })
            texts.addView(text(12.5f, Palette.WHITE70).apply {
                text = n.text
                setPadding(0, dp(2), 0, 0)
            })
            r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(n.time, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE)
            r.addView(text(11f, Palette.WHITE50).apply { text = ago })
            card.addView(r)
        }
        hc.addView(card)
    }

    private fun glassRow(icon: Int, title: String, sub: String, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(dpf(26f))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                it.bounce()
                onClick()
            }
        }
        r.addView(ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        t.addView(text(15f, Palette.INK, bold = true).apply { text = title })
        t.addView(text(12.5f, Palette.WHITE70, lines = 3).apply {
            text = sub
            setPadding(0, dp(2), 0, 0)
        })
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    // ----- Weather -----

    private fun onWeather() {
        val w = Weather.now
        weatherMini?.let { box ->
            box.removeAllViews()
            if (w != null) {
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(ImageView(this).apply {
                    setImageResource(Weather.icon(w.code, w.isDay))
                    imageTintList = ColorStateList.valueOf(Palette.INK)
                }, LinearLayout.LayoutParams(dp(22), dp(22)))
                top.addView(text(26f, Palette.INK).apply {
                    text = "${w.temp}°"
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    setPadding(dp(6), 0, 0, 0)
                })
                box.addView(top)
                box.addView(text(11.5f, Palette.WHITE70, bold = true).apply {
                    text = listOf(Weather.describe(w.code), w.city).filter { it.isNotBlank() }.joinToString(" · ")
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }
        val fc = forecast ?: return
        fc.removeAllViews()
        if (w == null) {
            fc.addView(ImageView(this).apply {
                setImageResource(R.drawable.w_sun)
                imageTintList = ColorStateList.valueOf(Palette.WHITE70)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            fc.addView(text(14f, Palette.WHITE70, lines = 2).apply {
                text = if (Weather.hasPlace(this@LauncherActivity)) "Loading the weather…" else "Tap to set your city for the weather"
                setPadding(dp(14), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return
        }
        val now = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        t.addView(ImageView(this).apply {
            setImageResource(Weather.icon(w.code, w.isDay))
            imageTintList = ColorStateList.valueOf(Palette.INK)
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        t.addView(text(38f, Palette.INK).apply {
            text = "${w.temp}°"
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(dp(8), 0, 0, 0)
        })
        now.addView(t)
        now.addView(text(13f, Palette.WHITE70, bold = true).apply {
            text = Weather.describe(w.code)
            setPadding(0, dp(4), 0, 0)
        })
        if (w.city.isNotBlank()) now.addView(text(12f, Palette.WHITE50).apply { text = w.city })
        fc.addView(now, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (d in w.days.drop(1).take(3)) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            c.addView(text(11.5f, Palette.WHITE70, bold = true).apply { text = d.dayName })
            c.addView(ImageView(this).apply {
                setImageResource(Weather.icon(d.code, true))
                imageTintList = ColorStateList.valueOf(Palette.INK)
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            })
            c.addView(text(12f, Palette.INK, bold = true).apply { text = "${d.max}°" })
            c.addView(text(11.5f, Palette.WHITE50).apply { text = "${d.min}°" })
            fc.addView(c, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ----- Time and battery -----

    private fun onMinute() {
        clock?.invalidate()
        val now = Date()
        dateLine?.text = DateFormat.format("EEE, d MMMM", now).toString().uppercase()
        dateDay?.text = DateFormat.format("dd", now)
        dateName?.text = DateFormat.format("EEE", now).toString().uppercase()
        monthTitle?.text = DateFormat.format("MMMM yyyy", now)
        monthView?.invalidate()
        renderHaloTimes()
    }

    private fun renderHaloTimes() {
        // Relative times in the Halo card age with the clock.
        if (pager.current == 1) renderHalo()
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        batteryText?.text = "$pct%"
        batteryBar?.fraction = pct / 100f
        batteryBar?.bar = if (charging) Color.argb(110, 52, 199, 89) else Color.argb(60, 0, 0, 0)
        batteryIcon?.setImageResource(if (charging) R.drawable.ic_bolt else R.drawable.ic_battery)
    }

    // ----- Apps: dock, favourites, capsules -----

    private fun rebuildApps() {
        val apps = AppRepo.apps
        if (apps.isEmpty()) return
        val hidden = Prefs.list(this, Prefs.HIDDEN).toSet()

        dock.removeAllViews()
        for (a in dockApps()) {
            dock.addView(iconView(a, 48, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        favorites?.let { row ->
            row.removeAllViews()
            for (a in favoriteApps()) {
                row.addView(iconView(a, 50, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        folders?.let { box ->
            box.removeAllViews()
            val groups = apps.filter { it.key !in hidden }.groupBy { AppRepo.group(it) }
            val order = listOf("Entertainment", "Social", "Tools", "Photos").filter { (groups[it]?.size ?: 0) > 0 }
            var line: LinearLayout? = null
            for ((n, g) in order.withIndex()) {
                if (n % 2 == 0) {
                    line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (n > 0) topMargin = dp(10) })
                }
                line?.addView(capsuleFolder(g, groups[g].orEmpty()), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (n % 2 == 1) marginStart = dp(12)
                })
            }
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

    /** A capsule with the group's top apps; tap for the whole group. */
    private fun capsuleFolder(name: String, apps: List<App>): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glass(dpf(30f))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val top = apps.sortedByDescending { AppRepo.launches(this, it) }.take(3)
        for (a in top) pill.addView(iconView(a, 40, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (apps.size > 3) {
            pill.addView(text(12f, Palette.INK, bold = true).apply {
                text = "+${apps.size - 3}"
                gravity = Gravity.CENTER
                background = round(Palette.GRAPHITE, dpf(18f))
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        pill.setOnClickListener { openFolder(name, apps) }
        col.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        col.addView(text(11.5f, Palette.WHITE70, bold = true).apply {
            text = name.uppercase()
            letterSpacing = 0.12f
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { openFolder(name, apps) }
        })
        return col
    }

    private fun openFolder(name: String, apps: List<App>) {
        val sheet = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            alpha = 0f
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dpf(32f)).apply { setColor(Color.argb(235, 22, 22, 24)) }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            isClickable = true
        }
        card.addView(text(20f, Palette.INK, bold = true).apply {
            text = name
            setPadding(dp(4), 0, 0, dp(12))
        })
        val grid = android.widget.GridLayout(this).apply { columnCount = 4 }
        for (a in apps) {
            grid.addView(iconView(a, 50, true).apply {
                setOnClickListener {
                    root.removeView(sheet)
                    launch(a, it)
                }
            }, android.widget.GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(0, dp(6), 0, dp(10))
            })
        }
        card.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(20), insetTop + dp(40), dp(20), insetBottom + dp(40))
        })
        sheet.setOnClickListener {
            sheet.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(sheet) }.start()
        }
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        sheet.animate().alpha(1f).setDuration(180).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
    }

    private fun dockApps(): List<App> {
        val saved = Prefs.list(this, Prefs.DOCK).mapNotNull { AppRepo.byKey(it) }
        if (saved.isNotEmpty()) return saved.take(5)
        // First run: phone, messages, browser, camera, gallery.
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

    private fun favoriteApps(): List<App> {
        val saved = Prefs.list(this, Prefs.FAVORITES).mapNotNull { AppRepo.byKey(it) }
        if (saved.isNotEmpty()) return saved.take(4)
        val dockKeys = dockApps().map { it.key }.toSet()
        val known = listOf("com.whatsapp", "com.google.android.youtube", "com.spotify.music", "com.instagram.android", "com.twitter.android", "com.zhiliaoapp.musically")
        val byKnown = known.mapNotNull { p -> AppRepo.apps.firstOrNull { it.pkg == p } }
        return (byKnown + AppRepo.apps.sortedByDescending { AppRepo.launches(this, it) }).filter { it.key !in dockKeys }.distinctBy { it.key }.take(4)
    }

    private fun updateBadges() {
        fun walk(v: View) {
            if (v is AppIconView) v.badge = HaloLink.badges[v.app.pkg] ?: 0
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(content)
        drawer.updateBadges()
    }

    // =====================================================================
    // Drawer.Host
    // =====================================================================

    override fun launch(app: App, from: View) {
        from.bounce()
        AppRepo.launch(this, app, from)
        if (drawer.isOpen) ui.postDelayed({ drawer.close() }, 350L)
    }

    override fun onDrawerProgress(progress: Float) {
        content.alpha = 1f - progress * 0.7f
        val s = 1f - progress * 0.05f
        content.scaleX = s
        content.scaleY = s
    }

    /** Long-press: app shortcuts, then dock / favourites / hide / info / uninstall. */
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
        val dockKeys = Prefs.list(this, Prefs.DOCK)
        val favKeys = Prefs.list(this, Prefs.FAVORITES)
        val inDock = dockApps().any { it.key == app.key }
        val inFav = favoriteApps().any { it.key == app.key }
        menu.menu.add(1, 1, 10, if (inDock) "Remove from dock" else "Add to dock")
        menu.menu.add(1, 2, 11, if (inFav) "Remove from favourites" else "Add to favourites")
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
                    val cur = if (dockKeys.isEmpty()) dockApps().map { it.key }.toMutableList() else dockKeys
                    if (inDock) cur.remove(app.key) else {
                        if (cur.size >= 5) cur.removeAt(cur.size - 1)
                        cur.add(app.key)
                    }
                    Prefs.setList(this, Prefs.DOCK, cur)
                    rebuildApps()
                }
                2 -> {
                    val cur = if (favKeys.isEmpty()) favoriteApps().map { it.key }.toMutableList() else favKeys
                    if (inFav) cur.remove(app.key) else {
                        if (cur.size >= 4) cur.removeAt(cur.size - 1)
                        cur.add(app.key)
                    }
                    Prefs.setList(this, Prefs.FAVORITES, cur)
                    rebuildApps()
                }
                3 -> {
                    val h = Prefs.list(this, Prefs.HIDDEN)
                    if (app.key !in h) h.add(app.key)
                    Prefs.setList(this, Prefs.HIDDEN, h)
                    Toast.makeText(this, "${app.label} hidden. Search finds it; unhide in Settings.", Toast.LENGTH_LONG).show()
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
                rebuildWidgets()
                pager.snapTo(1)
            }
        }
    }

    private fun rebuildWidgets() {
        val box = widgetsBox ?: return
        box.removeAllViews()
        val awm = AppWidgetManager.getInstance(this)
        for (idStr in Prefs.list(this, Prefs.WIDGETS)) {
            val id = idStr.toIntOrNull() ?: continue
            val info = awm.getAppWidgetInfo(id) ?: continue
            val hDp = Prefs.get(this).getInt("wh_$id", (info.minHeight / resources.displayMetrics.density).toInt().coerceIn(90, 420))
            val frame = FrameLayout(this).apply {
                background = glass(dpf(28f))
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
                imageTintList = ColorStateList.valueOf(Palette.WHITE70)
                background = round(Color.argb(150, 0, 0, 0), dpf(12f))
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
            rebuildWidgets()
            true
        }
        m.show()
    }

    // =====================================================================
    // Small actions
    // =====================================================================

    private fun editNote() {
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
                val t = input.text.toString().trim()
                Prefs.get(this).edit().putString(Prefs.NOTE, t).apply()
                noteText?.text = t.ifEmpty { "Tap to write a note" }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openClockApp() {
        try {
            startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
        } catch (_: Exception) {
        }
    }

    private fun openCalendar() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis())))
        } catch (_: Exception) {
            try {
                startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            } catch (_: Exception) {
            }
        }
    }

    private fun openUrl(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (_: Exception) {
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Swipe down: Halo's notification center, or Android's shade. */
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
            if (drawer.isOpen || childCount > 2) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    mode = 0
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain()
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dy) > slop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
                        val inDock = downY > dock.top - dp(24)
                        val allowed = pager.current == 0 || inDock || (dy > 0 && !(pager.page(1) as ScrollView).canScrollVertically(-1))
                        if (!allowed) return false
                        if (dy < 0) {
                            mode = 1
                            drawer.visibility = View.VISIBLE
                            drawer.refresh()
                            return true
                        } else if (downY < height * 0.6f || inDock) {
                            mode = 2
                            swipeDown()
                            return true
                        }
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            tracker?.addMovement(e)
            when (mode) {
                1 -> when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> drawer.setProgress(((downY - e.y) / (height * 0.7f)).coerceIn(0f, 1f))
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracker?.computeCurrentVelocity(1000)
                        val vy = tracker?.yVelocity ?: 0f
                        if (vy < -800f || drawer.progress() > 0.35f) drawer.open() else drawer.close()
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
        private const val REQ_WIDGET = 31
    }
}
