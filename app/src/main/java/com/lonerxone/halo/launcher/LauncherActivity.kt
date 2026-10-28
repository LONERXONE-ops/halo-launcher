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
 * Halo Launcher's home screen, poster style: your wallpaper (or a picture
 * you choose) across the top, a solid black sheet with a curved edge below.
 *
 * Page 1: the big stacked clock, date and weather, and a music player made
 * for the sheet. Page 2: a few quiet tiles (weather, calendar, battery,
 * note) and any Android widgets you add. The dock rises out of the bottom
 * edge like an upside-down notch. Swipe up for all apps.
 */
class LauncherActivity : Activity(), Drawer.Host {

    private lateinit var root: GestureRoot
    private lateinit var art: ArtView
    private lateinit var pager: HomePager
    private lateinit var drawer: Drawer
    private lateinit var dots: LinearLayout
    private lateinit var dock: DockTab
    private lateinit var widgetHost: AppWidgetHost
    private val ui = Handler(Looper.getMainLooper())
    private var insetTop = 0
    private var insetBottom = 0
    private val dockBody get() = dp(92)

    // Live views
    private var sheet1: SheetView? = null
    private var sheet2: SheetView? = null
    private var clock: StackedClock? = null
    private var dateLine: TextView? = null
    private var tempText: TextView? = null
    private var tempIcon: ImageView? = null
    private var weatherDesc: TextView? = null
    private var musicBox: FrameLayout? = null
    private var musicBar: BarView? = null
    private var musicTick: Runnable? = null
    private var bigDay: TextView? = null
    private var bigDayName: TextView? = null
    private var weatherTile: LinearLayout? = null
    private var monthView: MonthView? = null
    private var monthTitle: TextView? = null
    private var batteryRing: RingView? = null
    private var batteryPct: TextView? = null
    private var batteryState: TextView? = null
    private var noteText: TextView? = null
    private var widgetsBox: LinearLayout? = null

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
        art = ArtView(this)
        root.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        pager = HomePager(this)
        pager.addView(buildPage1())
        pager.addView(buildPage2())
        pager.onPageChanged = { p -> updateDots(p) }
        pager.onScrolled = { f -> scrollWallpaper(f) }
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        root.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14), Gravity.BOTTOM))

        dock = DockTab(this)
        dock.setOnLongClickListener {
            pickDock()
            true
        }
        root.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dockBody, Gravity.BOTTOM))

        drawer = Drawer(this, this)
        root.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            insetTop = bars.top
            insetBottom = bars.bottom
            layoutForInsets()
            insets
        }
        setContentView(root)
        updateDots(0)
    }

    /** The dock reaches into the gesture bar area; everything else keeps clear of it. */
    private fun layoutForInsets() {
        val dh = dockBody + insetBottom
        (dock.layoutParams as FrameLayout.LayoutParams).height = dh
        dock.setPadding(dp(34), 0, dp(34), insetBottom)
        dock.requestLayout()
        (dots.layoutParams as FrameLayout.LayoutParams).bottomMargin = dh + dp(4)
        dots.requestLayout()
        sheet1?.setPadding(dp(22), dp(24), dp(22), dh + dp(26))
        sheet2?.let { it.setPadding(dp(18), dp(20), dp(18), dh + dp(30)) }
        drawer.setPadding(0, insetTop, 0, 0)
    }

    private fun refreshLook() {
        root.setBackgroundColor(Color.TRANSPARENT)
        clock?.style = Prefs.get(this).getInt(Prefs.CLOCK_STYLE, 0)
        art.picture = loadPic(Prefs.HERO_PIC)
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
                background = round(if (i == page) Palette.INK else Color.argb(90, 255, 255, 255), dpf(3f))
            }, LinearLayout.LayoutParams(if (i == page) dp(14) else dp(5), dp(5)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun scrollWallpaper(f: Float) {
        try {
            val wm = WallpaperManager.getInstance(this)
            val pages = (pager.childCount - 1).coerceAtLeast(1)
            wm.setWallpaperOffsetSteps(1f / pages, 0f)
            wm.setWallpaperOffsets(root.windowToken, (f / pages).coerceIn(0f, 1f), 0f)
        } catch (_: Exception) {
        }
    }

    private fun solid(color: Int = TILE, radius: Float = dpf(26f)) = round(color, radius)

    // ----- Page 1: clock, date + weather, music -----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPage1(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = View(this)
        top.setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }
        page.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.44f))

        val sheet = SheetView(this, highOnLeft = true)
        sheet1 = sheet
        sheet.setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }

        // Clock on the left (where the sheet is high), date and weather on the right (lower).
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val c = StackedClock(this)
        clock = c
        c.setOnClickListener {
            it.bounce()
            openClockApp()
        }
        row.addView(c, LinearLayout.LayoutParams(dp(150), dp(150)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, 0, 0, dp(10))
            setOnClickListener {
                it.bounce()
                pager.snapTo(1)
            }
        }
        val wRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ti = ImageView(this).apply { imageTintList = ColorStateList.valueOf(Palette.INK) }
        tempIcon = ti
        wRow.addView(ti, LinearLayout.LayoutParams(dp(22), dp(22)))
        val tt = text(30f, Palette.INK).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(dp(6), 0, 0, 0)
        }
        tempText = tt
        wRow.addView(tt)
        right.addView(wRow)
        val wd = text(12f, Palette.WHITE50, bold = true).apply { setPadding(0, dp(2), 0, 0) }
        weatherDesc = wd
        right.addView(wd)
        val dl = text(12.5f, Palette.INK, bold = true).apply {
            letterSpacing = 0.14f
            setPadding(0, dp(10), 0, 0)
        }
        dateLine = dl
        right.addView(dl)
        row.addView(right)
        sheet.addView(row)

        val mb = FrameLayout(this)
        musicBox = mb
        sheet.addView(mb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)).apply { topMargin = dp(22) })
        page.addView(sheet, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.56f))
        renderMusic()
        return page
    }

    // ----- Music (from Halo), made for the sheet -----

    private fun renderMusic() {
        val box = musicBox ?: return
        box.removeAllViews()
        musicBar = null
        val m = HaloLink.music
        val card = FrameLayout(this).apply {
            background = solid(TILE, dpf(24f))
            clipToOutline = true
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(14f))
                }
            }
            clipToOutline = true
            if (m?.art != null) {
                setImageBitmap(m.art)
            } else {
                background = round(Color.rgb(44, 44, 46), dpf(14f))
                setImageResource(R.drawable.ic_music)
                imageTintList = ColorStateList.valueOf(Palette.WHITE50)
                scaleType = ImageView.ScaleType.CENTER
            }
        }
        row.addView(cover, LinearLayout.LayoutParams(dp(58), dp(58)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(text(15f, Palette.INK, bold = true).apply {
            text = m?.title ?: "Nothing playing"
        })
        texts.addView(text(12.5f, Palette.WHITE50).apply {
            text = m?.artist?.ifEmpty { null } ?: if (m == null) {
                if (HaloLink.installed(this@LauncherActivity)) "Tap play to resume" else "Install Halo for music here"
            } else {
                ""
            }
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun btn(res: Int, big: Boolean, cmd: String) = ImageView(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(if (big) Color.BLACK else Palette.INK)
            val pad = if (big) dp(11) else dp(8)
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
        row.addView(btn(R.drawable.ic_prev, false, "prev"), LinearLayout.LayoutParams(dp(38), dp(38)))
        row.addView(btn(if (m?.playing == true) R.drawable.ic_pause else R.drawable.ic_play, true, "play_pause"), LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        row.addView(btn(R.drawable.ic_next, false, "next"), LinearLayout.LayoutParams(dp(38), dp(38)))
        card.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Tap the cover or the words to open the player.
        val open = View.OnClickListener {
            it.bounce()
            if (!HaloLink.command(this@LauncherActivity, "open_music")) noHalo()
        }
        cover.setOnClickListener(open)
        texts.setOnClickListener(open)
        if (m != null && m.durationMs > 0) {
            val bar = BarView(this).apply {
                track = Color.argb(30, 255, 255, 255)
                bar = Palette.INK
                fraction = m.position() / m.durationMs.toFloat()
            }
            musicBar = bar
            card.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM).apply {
                marginStart = dp(24)
                marginEnd = dp(24)
                bottomMargin = dp(5)
            })
        }
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

    private fun onHalo() {
        renderMusic()
        updateBadges()
    }

    // ----- Page 2: a few quiet tiles and your widgets -----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPage2(): View {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val h = resources.displayMetrics.heightPixels
        val top = View(this)
        top.setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * 0.30f).toInt()))

        val sheet = SheetView(this, highOnLeft = false)
        sheet2 = sheet
        sheet.minimumHeight = (h * 0.70f).toInt()
        sheet.setOnTouchListener { _, e -> root.gestures.onTouchEvent(e); true }

        // The sheet is high on the right: a big date sits there.
        val dateCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setOnClickListener {
                it.bounce()
                openCalendar()
            }
        }
        val bd = text(52f, Palette.INK).apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL) }
        bigDay = bd
        dateCol.addView(bd)
        val bn = text(12f, Palette.WHITE50, bold = true).apply { letterSpacing = 0.18f }
        bigDayName = bn
        dateCol.addView(bn)
        sheet.addView(dateCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            marginEnd = dp(6)
        })

        val tile = ((resources.displayMetrics.widthPixels - dp(18) * 2 - dp(12)) / 2)
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(weatherTileView(), LinearLayout.LayoutParams(tile, tile))
        r1.addView(calendarTile(), LinearLayout.LayoutParams(tile, tile).apply { marginStart = dp(12) })
        sheet.addView(r1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(batteryTile(), LinearLayout.LayoutParams(tile, tile))
        r2.addView(noteTile(), LinearLayout.LayoutParams(tile, tile).apply { marginStart = dp(12) })
        sheet.addView(r2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val wb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        widgetsBox = wb
        sheet.addView(wb)
        sheet.addView(text(13f, Palette.WHITE70, bold = true).apply {
            text = "+  Add a widget"
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = solid(TILE, dpf(18f))
            setOnClickListener {
                it.bounce()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this@LauncherActivity, WidgetPickActivity::class.java), REQ_WIDGET)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        })

        col.addView(sheet, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(col)
        onWeather()
        return scroll
    }

    private fun weatherTileView(): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = solid()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                it.bounce()
                if (!Weather.hasPlace(this@LauncherActivity)) openSettings() else Weather.refresh(this@LauncherActivity, force = true) { onWeather() }
            }
        }
        weatherTile = t
        return t
    }

    private fun calendarTile(): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = solid(Palette.PAPER)
            setPadding(dp(12), dp(12), dp(12), dp(8))
            setOnClickListener {
                it.bounce()
                openCalendar()
            }
        }
        val mt = text(13f, Color.BLACK, bold = true).apply { setPadding(dp(4), 0, 0, dp(2)) }
        monthTitle = mt
        t.addView(mt)
        val mv = MonthView(this)
        monthView = mv
        t.addView(mv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return t
    }

    private fun batteryTile(): View {
        val t = FrameLayout(this).apply {
            background = solid()
            setOnClickListener {
                it.bounce()
                try {
                    startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                } catch (_: Exception) {
                }
            }
        }
        val ring = RingView(this)
        batteryRing = ring
        t.addView(ring, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val p = text(24f, Palette.INK).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        batteryPct = p
        col.addView(p)
        val s = text(10.5f, Palette.WHITE50, bold = true).apply { letterSpacing = 0.1f }
        batteryState = s
        col.addView(s)
        t.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return t
    }

    private fun noteTile(): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = solid()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { editNote() }
        }
        t.addView(text(11f, Palette.WHITE50, bold = true).apply {
            text = "NOTE"
            letterSpacing = 0.18f
        })
        val nt = text(18f, Palette.INK, lines = 5).apply {
            typeface = Typeface.create("cursive", Typeface.NORMAL)
            setLineSpacing(0f, 1.05f)
            gravity = Gravity.CENTER_VERTICAL
        }
        noteText = nt
        t.addView(nt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        return t
    }

    // ----- Weather -----

    private fun onWeather() {
        val w = Weather.now
        if (w != null) {
            tempIcon?.setImageResource(Weather.icon(w.code, w.isDay))
            tempText?.text = "${w.temp}°"
            weatherDesc?.text = listOf(Weather.describe(w.code), w.city).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            tempIcon?.setImageDrawable(null)
            tempText?.text = ""
            weatherDesc?.text = ""
        }
        val t = weatherTile ?: return
        t.removeAllViews()
        if (w == null) {
            t.addView(ImageView(this).apply {
                setImageResource(R.drawable.w_sun)
                imageTintList = ColorStateList.valueOf(Palette.WHITE50)
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            t.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            t.addView(text(13f, Palette.WHITE70, lines = 3).apply {
                text = if (Weather.hasPlace(this@LauncherActivity)) "Loading…" else "Tap to set your city"
            })
            return
        }
        t.addView(ImageView(this).apply {
            setImageResource(Weather.icon(w.code, w.isDay))
            imageTintList = ColorStateList.valueOf(Palette.INK)
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        t.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        t.addView(text(40f, Palette.INK).apply {
            text = "${w.temp}°"
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        t.addView(text(12.5f, Palette.WHITE70, bold = true).apply {
            text = Weather.describe(w.code)
            setPadding(0, dp(4), 0, 0)
        })
        val today = w.days.firstOrNull()
        t.addView(text(11.5f, Palette.WHITE50).apply {
            text = listOfNotNull(w.city.ifBlank { null }, today?.let { "H ${it.max}°  L ${it.min}°" }).joinToString(" · ")
            setPadding(0, dp(2), 0, 0)
        })
    }

    // ----- Time and battery -----

    private fun onMinute() {
        clock?.invalidate()
        val now = Date()
        dateLine?.text = DateFormat.format("EEE, d MMM", now).toString().uppercase()
        bigDay?.text = DateFormat.format("d", now)
        bigDayName?.text = DateFormat.format("EEEE", now).toString().uppercase()
        monthTitle?.text = DateFormat.format("MMMM", now)
        monthView?.invalidate()
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        batteryRing?.color = if (charging) Palette.GREEN else Palette.INK
        batteryRing?.fraction = pct / 100f
        batteryPct?.text = "$pct%"
        batteryState?.text = if (charging) "CHARGING" else "BATTERY"
    }

    // ----- Apps: the dock -----

    private fun rebuildApps() {
        if (AppRepo.apps.isEmpty()) return
        dock.removeAllViews()
        for (a in dockApps()) {
            dock.addView(iconView(a, 50, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun pickDock() {
        root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (Prefs.list(this, Prefs.DOCK).isEmpty()) Prefs.setList(this, Prefs.DOCK, dockApps().map { it.key })
        startActivity(Intent(this, PickAppsActivity::class.java).putExtra("key", Prefs.DOCK).putExtra("max", 5))
    }

    private fun updateBadges() {
        fun walk(v: View) {
            if (v is AppIconView) v.badge = HaloLink.badges[v.app.pkg] ?: 0
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(dock)
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
        val s = 1f - progress * 0.05f
        for (v in listOf(pager, dock, dots)) {
            v.alpha = 1f - progress * 0.8f
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
                        if (cur.size >= 5) cur.removeAt(cur.size - 1)
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
                background = solid()
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
            if (drawer.isOpen) return false
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
                        val page2AtTop = !(pager.page(1) as ScrollView).canScrollVertically(-1)
                        val allowed = pager.current == 0 || inDock || (dy > 0 && page2AtTop)
                        if (!allowed) return false
                        if (dy < 0) {
                            mode = 1
                            drawer.visibility = View.VISIBLE
                            drawer.refresh()
                            return true
                        } else if (downY < height * 0.7f || inDock) {
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
        /** Solid tile colour on the black sheet. */
        val TILE = Color.rgb(28, 28, 30)
    }
}
