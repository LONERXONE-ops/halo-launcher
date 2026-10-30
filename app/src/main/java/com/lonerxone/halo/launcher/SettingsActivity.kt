package com.lonerxone.halo.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Halo Launcher settings: a menu, and a page per topic, each showing a live
 * example of what it changes (the real clock in every style, your apps in
 * every icon style, the actual widgets, the dock as it will look).
 */
class SettingsActivity : Activity() {
    private enum class Page(val title: String) {
        MENU("Settings"), THEME("Theme"), LOOK("Background"), CLOCK("Clock & date"), MOTION("Animations"), ICONS("Icons"), DOCK("Dock"),
        WIDGETS("Widgets"), DRAWER("App list"), GESTURES("Gestures"), WEATHER("Weather"), HALO("Halo"), BACKUP("Backup & restore"), ABOUT("About"),
    }

    private var page = Page.MENU
    private var shown: Page? = null
    private var scroll: ScrollView? = null
    private var pickingKey: String? = null
    private var latest: Updater.Release? = null
    private var updateStatus = "Not checked yet"
    private var downloading = -1f
    /** Where you were on each page, so going back never jumps to the top. */
    private val scrollMemory = HashMap<Page, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        page = when (intent.getStringExtra("page")) {
            "widgets" -> Page.WIDGETS
            "weather" -> Page.WEATHER
            "clock" -> Page.CLOCK
            "theme" -> Page.THEME
            "look" -> Page.LOOK
            "dock" -> Page.DOCK
            "icons" -> Page.ICONS
            "gestures" -> Page.GESTURES
            "motion" -> Page.MOTION
            else -> Page.MENU
        }
        if (AppRepo.apps.isEmpty()) {
            AppRepo.addListener { render() }
            AppRepo.load(this)
        }
        check()
    }

    /** Something changed since the page was drawn (a setting, or coming back from elsewhere). */
    private var stale = true
    private val watch = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> stale = true }

    override fun onResume() {
        super.onResume()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(watch)
        if (Palette.adapt(this)) stale = true
        ensureBackdrop()
        // Only redraw if something actually changed: no flash every time you come back.
        if (stale || shown == null) render()
    }

    override fun onPause() {
        super.onPause()
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(watch)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page != Page.MENU) go(Page.MENU) else super.onBackPressed()
    }

    private fun go(p: Page) {
        page = p
        render()
    }

    private fun render() {
        stale = false
        live.clear()
        val same = shown == page
        shown?.let { old -> scroll?.let { scrollMemory[old] = it.scrollY } }
        // Same page: stay put. Back to the menu: return to where you were.
        // A new page opens at its top.
        val keepY = if (same || page == Page.MENU) scrollMemory[page] ?: 0 else 0
        if (!same && page != Page.MENU) scrollMemory.remove(page)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(40))
        }
        header(col)
        explain(col)
        when (page) {
            Page.MENU -> menuPage(col)
            Page.THEME -> themePage(col)
            Page.MOTION -> motionPage(col)
            Page.LOOK -> lookPage(col)
            Page.CLOCK -> clockPage(col)
            Page.ICONS -> iconsPage(col)
            Page.DOCK -> dockPage(col)
            Page.WIDGETS -> widgetsPage(col)
            Page.DRAWER -> drawerPage(col)
            Page.GESTURES -> gesturesPage(col)
            Page.WEATHER -> weatherPage(col)
            Page.HALO -> haloPage(col)
            Page.BACKUP -> backupPage(col)
            Page.ABOUT -> aboutPage(col)
        }
        val sv = ScrollView(this).apply {
            // Black, with a breath of your wallpaper's colour at the top (Adaptive colour).
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.TILE2, Color.BLACK, Color.BLACK))
            isVerticalScrollBarEnabled = false
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, b.top, 0, b.bottom)
                insets
            }
        }
        setContentView(sv)
        scroll = sv
        if (keepY > 0) sv.post { sv.scrollTo(0, keepY) }
        if (!same && Prefs.animations(this)) {
            // Pages slide in; their cards follow one after another.
            val dir = if (page == Page.MENU) -1f else 1f
            col.translationX = dpf(36f) * dir
            col.alpha = 0f
            col.animate().translationX(0f).alpha(1f).setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).start()
            if (page != Page.MENU) for (i in 0 until col.childCount) col.getChildAt(i).enter(60L + i * 35L, dpf(14f))
        }
        shown = page
    }

    private fun header(col: LinearLayout) {
        if (page == Page.MENU) {
            col.addView(ImageView(this).apply { setImageResource(R.drawable.logo_launcher) }, LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(18)
            })
            col.addView(text(28f, Palette.INK, bold = true).apply {
                text = "Halo Launcher"
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(2))
            })
            col.addView(text(13f, Palette.GREY).apply {
                text = "Made by LONERXONE"
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(18))
            })
            // Your home screen in small, as it looks right now (kept between visits).
            val key = heroKey()
            val hero = heroCache?.takeIf { heroCacheKey == key } ?: preview(glass = Glass.enabled(this), small = false).also {
                heroCache = it
                heroCacheKey = key
            }
            (hero.parent as? ViewGroup)?.removeView(hero)
            col.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)).apply { bottomMargin = dp(14) })
            return
        }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(Palette.INK)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = round(Palette.TILE2, dpf(21f))
            setOnClickListener { go(Page.MENU) }
        }
        col.addView(back, LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(10) })
        // Like Halo: the page's colour tile, a big title and one short line under it.
        val (icon, key) = pageIcon()
        val tc = tileColour(key)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(28), dp(4), dp(20))
        }
        head.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = if (icon == R.drawable.ic_halo) null else ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(lighten(tc), tc)).apply { cornerRadius = dpf(16f) }
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(28f, Palette.INK, bold = true).apply { text = page.title })
        subtitle()?.let { st -> texts.addView(text(14f, Palette.GREY, lines = 2).apply {
            text = st
            setPadding(0, dp(2), 0, 0)
        }) }
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)
    }

    /** One short line under each page's title. */
    private fun subtitle(): String? = when (page) {
        Page.THEME -> "How everything looks"
        Page.LOOK -> "The picture behind everything"
        Page.CLOCK -> "The big clock on page 1"
        Page.MOTION -> "How things move"
        Page.ICONS -> "How your apps look"
        Page.DOCK -> "The bar at the bottom"
        Page.WIDGETS -> "Live info on your home pages"
        Page.DRAWER -> "How you browse all your apps"
        Page.GESTURES -> "What your fingers do"
        Page.WEATHER -> "Where the weather comes from"
        Page.HALO -> "Your dynamic island"
        Page.BACKUP -> "Keep your whole setup in a file"
        else -> null
    }

    /**
     * Each section's tile colour, shaded by your wallpaper: with Adaptive
     * colour on Rich or Vivid the tiles become a family of your wallpaper's
     * colour; otherwise each keeps its own colour with a touch of it.
     */
    private fun tileColour(key: String): Int {
        val base = tileColours[key] ?: return Palette.GRAPHITE
        val wc = Glass.wallColor(this) ?: return base
        val mode = Prefs.get(this).getInt(Prefs.ADAPTIVE, Palette.ADAPT_OFF)
        val wh = FloatArray(3)
        Color.colorToHSV(wc, wh)
        if (mode >= Palette.ADAPT_RICH && wh[1] > 0.12f) {
            val i = tileColours.keys.indexOf(key).coerceAtLeast(0)
            val hsv = floatArrayOf((wh[0] + ((i % 5) - 2) * 14f + 360f) % 360f, 0.55f + (i % 3) * 0.08f, 0.82f - (i % 4) * 0.06f)
            return Color.HSVToColor(hsv)
        }
        val f = if (mode == Palette.ADAPT_OFF) 0.18f else 0.3f
        return Color.rgb(
            (Color.red(base) + (Color.red(wc) - Color.red(base)) * f).toInt(),
            (Color.green(base) + (Color.green(wc) - Color.green(base)) * f).toInt(),
            (Color.blue(base) + (Color.blue(wc) - Color.blue(base)) * f).toInt(),
        )
    }

    /** The menu icon and colour of the page being shown. */
    private fun pageIcon(): Pair<Int, String> = when (page) {
        Page.THEME -> R.drawable.ic_glass to "Theme"
        Page.LOOK -> R.drawable.ic_image to "Background"
        Page.CLOCK -> R.drawable.ic_clock to "Clock & date"
        Page.MOTION -> R.drawable.ic_gesture to "Animations"
        Page.ICONS -> R.drawable.ic_apps to "Icons"
        Page.DOCK -> R.drawable.ic_dock to "Dock"
        Page.WIDGETS -> R.drawable.ic_widgets to "Widgets"
        Page.DRAWER -> R.drawable.ic_apps to "App list"
        Page.GESTURES -> R.drawable.ic_gesture to "Gestures"
        Page.WEATHER -> R.drawable.w_sun to "Weather"
        Page.HALO -> R.drawable.ic_halo to "Halo"
        Page.BACKUP -> R.drawable.ic_arrow_down to "Backup & restore"
        else -> R.drawable.ic_info to "About"
    }

    // =====================================================================
    // Menu
    // =====================================================================

    private fun menuPage(col: LinearLayout) {
        if (!isDefault()) {
            col.addView(button("Make it my home screen") {
                try {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { bottomMargin = dp(12) })
        }
        col.addView(updateCard())
        section(col, "Look")
        col.addView(group(
            nav(R.drawable.ic_glass, "Theme", if (Glass.enabled(this)) "Liquid Glass" else "Solid") { go(Page.THEME) },
            nav(R.drawable.ic_image, "Wallpapers", "Browse, search, your photos, change daily") { startActivity(Intent(this, WallpaperActivity::class.java)) },
            nav(R.drawable.ic_gesture, "Animations", Motion.OPEN_NAMES.getOrElse(Motion.open(this)) { "Zoom" } + " · " + Motion.PAGE_NAMES.getOrElse(Motion.page(this)) { "Depth" }) { go(Page.MOTION) },
            nav(R.drawable.ic_image, "Background", if (Prefs.get(this).getLong(Prefs.HERO_PIC, 0L) != 0L) "Your picture" else "Your wallpaper") { go(Page.LOOK) },
            nav(R.drawable.ic_clock, "Clock & date", ClockFaces.current(this).name) { go(Page.CLOCK) },
            nav(R.drawable.ic_apps, "Icons", (Icons.NAMES[Icons.style(this)] ?: "Normal") + IconPacks.current(this).let { p -> if (p.isEmpty()) "" else " · " + (IconPacks.installed(this).firstOrNull { it.pkg == p }?.label ?: "icon pack") }) { go(Page.ICONS) },
            nav(R.drawable.ic_apps, "App list", (if (Prefs.get(this).getInt(Prefs.DRAWER_LAYOUT, 0) == 1) "Horizontal" else "Vertical") + " · " + (if (Prefs.get(this).getInt(Prefs.DRAWER_COLS, 0) == 1) "5" else "4") + " across") { go(Page.DRAWER) },
            nav(R.drawable.ic_dock, "Dock", "${Prefs.list(this, Prefs.DOCK).size.takeIf { it > 0 } ?: 5} apps · " + DockBar.NAMES.getOrElse(Prefs.get(this).getInt(Prefs.DOCK_STYLE, 0)) { "Glass" }) { go(Page.DOCK) },
            nav(R.drawable.ic_widgets, "Widgets", "${HomeWidgets.ALL.count { Prefs.widgetOn(this, it.id) }} on page 2") { go(Page.WIDGETS) },
        ))
        section(col, "Use")
        col.addView(group(
            nav(R.drawable.ic_gesture, "Gestures", "Swipe, double-tap, long-press") { go(Page.GESTURES) },
            nav(R.drawable.w_sun, "Weather", Prefs.get(this).getString(Prefs.WEATHER_CITY, "").orEmpty().ifEmpty { if (Weather.locationAllowed(this)) "Your location" else "Not set" }) { go(Page.WEATHER) },
            nav(R.drawable.ic_halo, "Halo", if (HaloLink.alive()) "Linked" else if (HaloLink.installed(this)) "Not running" else "Not installed") { go(Page.HALO) },
            nav(R.drawable.ic_arrow_down, "Backup & restore", "Keep your whole setup in a file") { go(Page.BACKUP) },
            nav(R.drawable.ic_eye_off, "Hidden apps", "${Prefs.list(this, Prefs.HIDDEN).size} hidden") {
                startActivity(Intent(this, PickAppsActivity::class.java).putExtra("key", Prefs.HIDDEN).putExtra("max", 0))
            },
        ))
        section(col, "")
        col.addView(group(
            nav(R.drawable.ic_info, "Take the tour", "How Halo Launcher works, and what every setting does") {
                startActivity(Intent(this, OnboardingActivity::class.java))
            },
            nav(R.drawable.ic_info, "About", "Credits and licences") { go(Page.ABOUT) },
        ))
    }

    /** Pages open with their title and one line now (see header); no wall of text. */
    private fun explain(@Suppress("UNUSED_PARAMETER") col: LinearLayout) {}

    private fun updateCard(): View {
        val rel = latest
        val newer = rel != null && rel.build > Updater.buildOf(version())
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Palette.TILE, dpf(24f))
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        c.addView(text(10.5f, Palette.GREY, caps = true).apply { text = "Version ${version()}" })
        c.addView(text(16f, Palette.INK, bold = true, lines = 2).apply {
            text = updateStatus
            setPadding(0, dp(4), 0, dp(12))
        })
        c.addView(button(
            when {
                downloading >= 0f -> "Downloading ${(downloading * 100).toInt()}%"
                newer && rel != null -> "Download & install ${rel.tag}"
                else -> "Check for updates"
            },
        ) {
            when {
                downloading >= 0f -> {}
                newer && rel != null -> install(rel)
                else -> check()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        if (rel != null && newer && rel.notes.isNotBlank()) {
            c.addView(text(13f, Palette.SILVER, lines = 8).apply {
                text = rel.notes.take(500)
                setPadding(0, dp(12), 0, 0)
            })
        }
        return c
    }

    // =====================================================================
    // Pages
    // =====================================================================

    private fun themePage(col: LinearLayout) {
        note(col, "Liquid Glass turns tiles, folders, widgets and menus into glass that bends your background. Solid makes them plain tiles.")
        if (Build.VERSION.SDK_INT < 33) note(col, "Your Android version gets frosted glass; the full lens needs Android 13 or newer.")
        val cur = Prefs.get(this).getInt(Prefs.THEME, Glass.THEME_GLASS)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, name) in listOf("Liquid Glass", "Solid").withIndex()) {
            val card = choiceCard(cur == i) {
                // One switch for everything: icons, dock, clock digits, folders,
                // widgets, menus and the app list all follow the theme again.
                Prefs.get(this).edit()
                    .putInt(Prefs.THEME, i)
                    .remove(Prefs.ICON_STYLE)
                    .putInt(Prefs.DOCK_STYLE, DockBar.GLASS)
                    .putInt(Prefs.CLOCK_GLASS, 0)
                    .apply()
                Icons.forgetAll()
                heroCache = null
                render()
            }
            card.setPadding(dp(8), dp(8), dp(8), dp(12))
            card.addView(preview(glass = i == Glass.THEME_GLASS, small = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
            card.addView(text(14f, Palette.INK, bold = true).apply {
                text = name
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
            row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 1) marginStart = dp(12) })
        }
        col.addView(row)
        section(col, "Adaptive colour")
        note(col, "Everything takes on your wallpaper's colour, and follows when you change it.")
        val ad = Prefs.get(this).getInt(Prefs.ADAPTIVE, Palette.ADAPT_OFF)
        val arow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, name) in Palette.ADAPT_NAMES.withIndex()) {
            arow.addView(chip(name, i == ad).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(9), dp(4), dp(9))
                pressable()
                setOnClickListener {
                    Prefs.get(this@SettingsActivity).edit().putInt(Prefs.ADAPTIVE, i).apply()
                    Palette.adapt(this@SettingsActivity)
                    render()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        col.addView(arow)
        // The palette it makes, as swatches.
        val sw = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        for (cc in listOf(Palette.TILE, Palette.TILE2, Palette.GRAPHITE, Palette.DIM, Palette.GREY, Palette.SILVER, Palette.ACCENT)) {
            sw.addView(View(this).apply { background = round(cc, dpf(10f)).apply { setStroke(dp(1), Color.argb(40, 255, 255, 255)) } }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) })
        }
        col.addView(sw)
        if (Glass.backdrop == null) {
            section(col, "")
            note(col, "Glass needs a picture to bend. Pick a background, or let Halo Launcher read your wallpaper.")
            col.addView(button("Choose background") { go(Page.LOOK) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
    }

    private fun lookPage(col: LinearLayout) {
        note(col, "With no picture chosen, your phone's wallpaper is used.")
        col.addView(preview(glass = Glass.enabled(this), small = false), LinearLayout.LayoutParams(dp(190), dp(380)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
            bottomMargin = dp(18)
        })
        val hasPic = Prefs.get(this).getLong(Prefs.HERO_PIC, 0L) != 0L
        col.addView(button("Browse wallpapers") { startActivity(Intent(this, WallpaperActivity::class.java)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(12) })
        val rows = mutableListOf(pictureRow("Background picture", "Tap to choose. Empty = your wallpaper", Prefs.HERO_PIC))
        if (hasPic) {
            rows += row("Use as phone wallpaper too", "So your lock screen and recents match", chevron()) { setPhoneWallpaper() }
        } else if (!Glass.canReadWallpaper(this)) {
            rows += row("Let the glass see your wallpaper", "Android 13+ only shares the wallpaper with apps that have \"All files access\". Halo Launcher only reads the wallpaper.", chevron()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
        rows += switchRow("Motion", "Things glide in, icons ripple, digits roll. Off for a calmer screen", Prefs.ANIMATIONS, true)
        col.addView(group(*rows.toTypedArray()))
    }

    /** Sets the chosen picture as the phone's wallpaper (home and lock). */
    private fun setPhoneWallpaper() {
        Toast.makeText(this, "Setting wallpaper…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                val bmp = android.graphics.BitmapFactory.decodeFile(Prefs.picFile(this, Prefs.HERO_PIC).absolutePath)
                WallpaperManager.getInstance(this).setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread { Toast.makeText(this, if (ok) "Wallpaper set" else "Couldn't set the wallpaper", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private var colourNote: TextView? = null

    /** Clock, date and weather in a preview here: frosted, not following the screen. */
    private fun <T : TextArt> T.pv(): T = apply { preview = true }
    private var heroCache: View? = null
    private var heroCacheKey = ""

    /** Everything the home preview shows; it's rebuilt only when one of these changes. */
    private fun heroKey(): String {
        val p = Prefs.get(this)
        return listOf(
            p.getInt(Prefs.THEME, 0), p.getInt(Prefs.ADAPTIVE, 0), p.getString(Prefs.CLOCK_FACE, ""), p.getInt(Prefs.CLOCK_COLOR, 0),
            p.getInt(Prefs.CLOCK_GLASS, 0), p.getInt(Prefs.CLOCK_TWOTONE, 0), p.getInt(Prefs.CLOCK_HOURS, 0), p.getBoolean(Prefs.CLOCK_ZERO, true),
            p.getBoolean(Prefs.CLOCK_SHADOW, false), p.getInt(Prefs.DATE_STYLE, 0), Prefs.list(this, Prefs.DOCK).joinToString(), Icons.style(this),
            IconPacks.current(this), System.identityHashCode(Glass.backdrop), Palette.TILE, Palette.GLASS_TINT,
        ).joinToString("|")
    }

    /** Views on the current page that redraw in place when a setting changes. */
    private val live = ArrayList<View>()

    private fun refreshLive() {
        for (v in live) {
            if (v is TextArt) v.refresh() else v.invalidate()
        }
    }

    /** Makes sure the background is loaded here too (for the previews). */
    private fun ensureBackdrop() {
        val b = windowManager.currentWindowMetrics.bounds
        Glass.load(this, b.width(), b.height()) {
            heroCache = null
            render()
        }
    }

    /**
     * A small live model of the home screen on your real background: the
     * clock, a music card and the dock, in glass or solid.
     */
    private fun preview(glass: Boolean, small: Boolean): View {
        val f = FrameLayout(this).apply {
            background = round(Color.rgb(40, 40, 44), dpf(26f))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(26f))
                }
            }
        }
        f.addView(BackgroundView(this, miniature = true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(16), dp(10), dp(10))
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(ClockFace(this).pv(), LinearLayout.LayoutParams(dp(if (small) 70 else 92), dp(if (small) 62 else 80)))
        if (!small) topRow.addView(DateFace(this).pv(), LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(10)
            gravity = Gravity.CENTER_VERTICAL
        })
        col.addView(topRow)
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        // A little "now playing" card, so the glass has something on it.
        val musicPane = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(10), 0)
        }
        musicPane.background = if (glass) previewGlass(dpf(14f)) else round(Palette.TILE, dpf(14f))
        musicPane.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_music)
            imageTintList = ColorStateList.valueOf(Palette.INK)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = round(Color.argb(50, 255, 255, 255), dpf(8f))
        }, LinearLayout.LayoutParams(dp(if (small) 24 else 30), dp(if (small) 24 else 30)))
        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        bars.addView(View(this).apply { background = round(Color.argb(210, 255, 255, 255), dpf(3f)) }, LinearLayout.LayoutParams(dp(if (small) 46 else 80), dp(5)))
        bars.addView(View(this).apply { background = round(Color.argb(90, 255, 255, 255), dpf(3f)) }, LinearLayout.LayoutParams(dp(if (small) 30 else 54), dp(4)).apply { topMargin = dp(5) })
        musicPane.addView(bars, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        musicPane.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(Palette.INK)
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        col.addView(musicPane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (small) 40 else 50)))
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dock.background = when (Prefs.get(this).getInt(Prefs.DOCK_STYLE, DockBar.GLASS)) {
            DockBar.SOLID -> round(Palette.TILE, dpf(18f))
            DockBar.CLEAR -> null
            else -> previewGlass(dpf(18f))
        }
        val apps = Prefs.list(this, Prefs.DOCK).mapNotNull { AppRepo.byKey(it) }.ifEmpty { sampleApps() }.take(if (small) 4 else 5)
        for (a in apps) dock.addView(AppIconView(this, a, if (small) 22 else 28, false, liveGlass = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (small) 38 else 46)).apply { topMargin = dp(8) })
        f.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (Glass.backdrop == null) {
            f.addView(text(10f, Palette.SILVER, caps = true, lines = 2).apply {
                text = "No background yet"
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return f
    }

    /**
     * Glass in a miniature preview: frosted, tinted like the real glass, with
     * a bright rim and a sheen. (Live glass would bend the full-size picture
     * at that spot on screen, which doesn't match the shrunken one behind it.)
     */
    private fun previewGlass(radius: Float): android.graphics.drawable.Drawable {
        val tint = Palette.GLASS_TINT
        val a = (255 * (0.18f + Palette.GLASS_TINT_AMT)).toInt().coerceIn(0, 255)
        val base = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(70, 255, 255, 255),
                Color.argb(a, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.argb(a, Color.red(tint), Color.green(tint), Color.blue(tint)),
            ),
        ).apply {
            cornerRadius = radius
            setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }
        return base
    }

    /** A small window onto your background, for previews that sit on it. */
    private fun stage(radius: Float = dpf(18f)): FrameLayout = FrameLayout(this).apply {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        background = round(Color.rgb(40, 40, 44), radius)
        addView(BackgroundView(this@SettingsActivity, miniature = true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** A row of choice chips bound to an int setting. */
    private fun colourFor(i: Int): Int = when (i) {
        2 -> Color.rgb(200, 200, 206)
        3 -> Color.rgb(18, 18, 20)
        4 -> Color.rgb(255, 69, 58)
        5 -> Color.rgb(255, 204, 92)
        6 -> Color.rgb(100, 170, 255)
        7 -> Color.rgb(255, 130, 180)
        8 -> Color.rgb(90, 220, 140)
        else -> Palette.INK
    }

    /**
     * [inPlace]: the chips and the previews on the page update where they
     * are (smooth, no redraw of the whole page).
     */
    private fun chips(col: LinearLayout, title: String, key: String, names: List<String>, def: Int = 0, inPlace: Boolean = false) {
        section(col, title)
        val cur = Prefs.get(this).getInt(key, def)
        // One rounded track with the chosen option as a white pill (One UI style).
        val wide = names.size > 4
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = round(Palette.TILE, dpf(22f)).apply { setStroke(dp(1), Color.argb(16, 255, 255, 255)) }
        }
        val views = ArrayList<TextView>()
        fun style(v: TextView, on: Boolean) {
            v.setTextColor(if (on) Color.BLACK else Palette.SILVER)
            v.background = if (on) round(Palette.INK, dpf(18f)) else null
        }
        for ((i, name) in names.withIndex()) {
            val v = text(13f, Palette.SILVER, bold = true).apply {
                text = name
                gravity = Gravity.CENTER
                setPadding(dp(if (wide) 14 else 4), dp(10), dp(if (wide) 14 else 4), dp(10))
                pressable()
                setOnClickListener {
                    Prefs.get(this@SettingsActivity).edit().putInt(key, i).apply()
                    if (inPlace) {
                        for ((k, o) in views.withIndex()) style(o, k == i)
                        it.scaleX = 0.92f
                        it.scaleY = 0.92f
                        it.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
                        refreshLive()
                        stale = false
                    } else {
                        render()
                    }
                }
            }
            style(v, i == cur)
            views += v
            track.addView(v, if (wide) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (wide) {
            col.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(track)
            })
        } else {
            col.addView(track)
        }
    }

    private fun motionPage(col: LinearLayout) {
        note(col, "Turn Motion off for the snappiest feel.")
        col.addView(group(switchRow("Motion", "All animations. Off for a calmer, instant feel", Prefs.ANIMATIONS, true)))
        chips(col, "Opening an app", Prefs.OPEN_ANIM, Motion.OPEN_NAMES)
        note(col, when (Motion.open(this)) {
            Motion.OPEN_REVEAL -> "Reveal: the app is uncovered from the icon outwards, like a window opening."
            Motion.OPEN_FADE -> "Fade: a soft cross-fade, no movement."
            else -> "Zoom: the app grows out of its icon, like iOS, and lands back in it with a bounce."
        })
        chips(col, "Turning pages", Prefs.PAGE_EFFECT, Motion.PAGE_NAMES, def = Motion.PAGE_DEPTH)
        // A live demo of the page effect: two mini pages you can scrub.
        val demo = stage(dpf(20f))
        val pages = listOf(
            LinearLayout(this).apply { background = round(Palette.TILE2, dpf(14f)) },
            LinearLayout(this).apply { background = round(Palette.GRAPHITE, dpf(14f)) },
        )
        val track = FrameLayout(this)
        for (p in pages) track.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(20), dp(18), dp(20), dp(18)) })
        demo.addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(demo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply { topMargin = dp(12) })
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f, 1f, 0f, 0f).apply {
            duration = 3600
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedValue as Float
                val w = track.width.toFloat()
                // Lay the two pages side by side like the pager does, then apply the effect.
                pages[0].translationX = 0f
                pages[1].translationX = 0f
                Motion.applyPages(pages, f, Motion.page(this@SettingsActivity))
                pages[0].translationX += -f * w
                pages[1].translationX += (1f - f) * w
            }
        }
        demo.post { anim.start() }
        demo.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = anim.cancel()
        })
    }

    private fun clockPage(col: LinearLayout) {
        note(col, "Long-press the home screen to move the clock, date and weather anywhere.")
        // Live preview of all three on your background.
        val pv = stage(dpf(24f))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(ClockFace(this).pv().also { live += it }, LinearLayout.LayoutParams(0, dp(150), 1f))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(DateFace(this).pv().also { live += it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        side.addView(WeatherFace(this).pv().also { live += it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        top.addView(side, LinearLayout.LayoutParams(0, dp(150), 1f).apply { marginStart = dp(8) })
        box.addView(top)
        pv.addView(box)
        col.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        chips(col, "Digits", Prefs.CLOCK_GLASS, listOf("Glass with the theme", "Plain"), inPlace = true)
        // Colour: a row of swatches (the second is your wallpaper's colour).
        section(col, "Colour")
        val curCol = Prefs.get(this).getInt(Prefs.CLOCK_COLOR, 0)
        val swRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (i in ClockFaces.COLOUR_NAMES.indices) {
            val cc = when (i) {
                0 -> Palette.INK
                1 -> Glass.accent(this)
                else -> colourFor(i)
            }
            val dot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(cc)
                    setStroke(dp(if (i == curCol) 3 else 1), if (i == curCol) Color.WHITE else Color.argb(60, 255, 255, 255))
                }
                contentDescription = ClockFaces.COLOUR_NAMES[i]
                scaleX = if (i == curCol) 1.12f else 1f
                scaleY = scaleX
                pressable()
                setOnClickListener {
                    Prefs.get(this@SettingsActivity).edit().putInt(Prefs.CLOCK_COLOR, i).apply()
                    // Move the ring to this swatch and recolour the previews, without redrawing the page.
                    for (k in 0 until swRow.childCount) {
                        val d = swRow.getChildAt(k)
                        val on = k == i
                        ((d.background as? android.graphics.drawable.GradientDrawable))?.setStroke(dp(if (on) 3 else 1), if (on) Color.WHITE else Color.argb(60, 255, 255, 255))
                        d.animate().scaleX(if (on) 1.12f else 1f).scaleY(if (on) 1.12f else 1f).setDuration(320).setInterpolator(Spring(0.5f)).start()
                    }
                    colourNote?.text = ClockFaces.COLOUR_NAMES[i] + if (i == 1) ": follows your wallpaper" else ""
                    refreshLive()
                    stale = false
                }
            }
            swRow.addView(dot, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        col.addView(swRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        note(col, ClockFaces.COLOUR_NAMES.getOrElse(curCol) { "White" } + if (curCol == 1) ": follows your wallpaper" else "")
        colourNote = col.getChildAt(col.childCount - 1) as? TextView
        chips(col, "Two-tone minutes", Prefs.CLOCK_TWOTONE, listOf("Off", "Faded", "Wallpaper"), inPlace = true)
        chips(col, "Layout", Prefs.CLOCK_LAYOUT, listOf("Stacked", "One line"), inPlace = true)
        chips(col, "Align", Prefs.CLOCK_ALIGN, listOf("Left", "Centre", "Right"), inPlace = true)
        chips(col, "Hours", Prefs.CLOCK_HOURS, listOf("Phone", "24-hour", "12-hour"), inPlace = true)
        chips(col, "Separator (one line)", Prefs.CLOCK_SEP, listOf("9:41", "9.41", "9 41"), inPlace = true)
        section(col, "")
        col.addView(group(
            switchRow("Leading zero", "09:41 instead of 9:41", Prefs.CLOCK_ZERO, true) { refreshLive(); stale = false },
            switchRow("Shadow", "A soft shadow so the clock and date read on busy wallpapers", Prefs.CLOCK_SHADOW, false) { refreshLive(); stale = false },
        ))

        section(col, "Date style")
        val ds = Prefs.get(this).getInt(Prefs.DATE_STYLE, 0)
        var drow: LinearLayout? = null
        for ((i, name) in DateFace.NAMES.withIndex()) {
            if (i % 2 == 0) {
                drow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(drow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            }
            val card = choiceCard(i == ds) {
                Prefs.get(this).edit().putInt(Prefs.DATE_STYLE, i).apply()
                render()
            }
            card.setPadding(dp(8), dp(8), dp(8), dp(10))
            val st = stage()
            st.addView(DateFace(this, forceStyle = i).pv().also { live += it }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            card.addView(text(12f, Palette.SILVER).apply {
                text = name
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            drow?.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i % 2 == 1) marginStart = dp(10) })
        }

        section(col, "Clock face (${ClockFaces.ALL.size})")
        val cur = ClockFaces.current(this)
        var row: LinearLayout? = null
        for ((i, f) in ClockFaces.ALL.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            }
            val card = choiceCard(f.id == cur.id) {
                Prefs.get(this).edit().putString(Prefs.CLOCK_FACE, f.id).apply()
                render()
            }
            card.setPadding(dp(8), dp(8), dp(8), dp(10))
            val st = stage()
            st.addView(ClockFace(this, forceFace = f).pv().also { live += it }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(10), dp(8), dp(10), dp(8)) })
            card.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)))
            card.addView(text(13f, Palette.INK, bold = true).apply {
                text = f.name
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
            row?.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i % 2 == 1) marginStart = dp(10) })
        }
        note(col, "")
        note(col, "Faces use free fonts from Google Fonts (SIL Open Font License): Bebas Neue, Big Shoulders, Unbounded, Syne, Rubik Mono One, Bungee, Fraunces, DM Serif Display, Instrument Serif, Shrikhand, Outfit, Major Mono Display, Space Mono, Orbitron, Righteous, Monoton, Doto, Silkscreen, Six Caps, Nunito, Anton, Graduate and Inter.")
    }

    private var packStatus = ""

    /**
     * A One UI style segmented control: one rounded track, the chosen option
     * a white pill inside it. Saves to [key] and redraws the page.
     */
    private fun segmented(col: LinearLayout, key: String, names: List<String>, def: Int) {
        val cur = Prefs.get(this).getInt(key, def)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = round(Palette.TILE, dpf(22f)).apply { setStroke(dp(1), Color.argb(16, 255, 255, 255)) }
        }
        for ((i, name) in names.withIndex()) {
            val on = i == cur
            track.addView(text(13.5f, if (on) Color.BLACK else Palette.SILVER, bold = true).apply {
                text = name
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = if (on) round(Palette.INK, dpf(18f)) else null
                pressable()
                setOnClickListener {
                    if (!on) {
                        Prefs.get(this@SettingsActivity).edit().putInt(key, i).apply()
                        render()
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(track)
    }

    /**
     * The icon center. On top, your home screen in small: eight apps and a
     * dock, drawn in the look, size and names you've picked. Then the four
     * looks as cards (two by two), size as one segmented control, names,
     * your icon packs as tiles, and free packs to get.
     */
    private fun iconsPage(col: LinearLayout) {
        val samples = sampleApps()
        val style = Icons.style(this)
        val followTheme = !Prefs.get(this).contains(Prefs.ICON_STYLE)
        val p = Prefs.get(this)
        val names = p.getBoolean(Prefs.SHOW_LABELS, false)
        // The same sizes the home screen uses, a little smaller so 4 fit across the preview.
        val size = listOf(48, 54, 60)[p.getInt(Prefs.HOME_ICON_SIZE, 1).coerceIn(0, 2)] - if (names) 4 else 0

        // Your home screen in small
        val st = stage(dpf(28f))
        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(20), dp(8), dp(12))
        }
        for (rowApps in samples.take(8).chunked(4)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (a in rowApps) row.addView(AppIconView(this, a, size, names, liveGlass = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            for (k in rowApps.size until 4) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            home.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        val dockApps = Prefs.list(this, Prefs.DOCK).mapNotNull { AppRepo.byKey(it) }.ifEmpty { samples.drop(4) }.take(4)
        val dock = DockBar(this).apply {
            setPadding(dp(6), dp(8), dp(6), dp(8))
            if (background is GlassDrawable) background = previewGlass(dpf(30f))
        }
        for (a in dockApps) dock.addView(AppIconView(this, a, size - 2, false, liveGlass = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        home.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
        st.addView(home, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The four looks, two by two
        section(col, "Look")
        val about = mapOf(
            Icons.ORIGINAL to "As each app draws it",
            Icons.GLASS to "Colour symbol on glass",
            Icons.CLEAR to "Frosted white on glass",
            Icons.MONO to "Just the white symbol",
        )
        val order = listOf(Icons.ORIGINAL, Icons.GLASS, Icons.CLEAR, Icons.MONO)
        for (pair in order.chunked(2)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((n, i) in pair.withIndex()) {
                val on = style == i
                val card = FrameLayout(this).apply {
                    background = round(if (on) Palette.TILE2 else Palette.TILE, dpf(22f)).apply { setStroke(dp(2), if (on) Palette.INK else Color.argb(16, 255, 255, 255)) }
                    pressable()
                    setOnClickListener {
                        it.bounce()
                        p.edit().putInt(Prefs.ICON_STYLE, i).apply()
                        Icons.forgetAll()
                        render()
                    }
                }
                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(12))
                }
                val mini = stage(dpf(16f))
                val trio = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(10), dp(2), dp(10))
                }
                for (a in samples.take(3)) trio.addView(AppIconView(this, a, 36, false, forceStyle = i, liveGlass = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                mini.addView(trio)
                inner.addView(mini, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                inner.addView(text(14.5f, Palette.INK, bold = true).apply {
                    text = Icons.NAMES[i]
                    setPadding(dp(6), dp(10), 0, 0)
                })
                inner.addView(text(12f, Palette.GREY).apply {
                    text = about[i].orEmpty() + if (followTheme && on) ", from your theme" else ""
                    setPadding(dp(6), dp(2), 0, 0)
                })
                card.addView(inner)
                // The chosen one gets a check in its corner.
                if (on) card.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(Color.BLACK)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Palette.INK)
                    }
                }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(14)
                    rightMargin = dp(14)
                })
                line.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (n > 0) marginStart = dp(10) })
            }
            col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }

        // Size and names
        section(col, "Size")
        segmented(col, Prefs.HOME_ICON_SIZE, listOf("Normal", "Large", "Extra large"), def = 1)
        col.addView(group(switchRow("Show names", "Names under apps on the home screen", Prefs.SHOW_LABELS, false) { render() }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // Installed packs, as a row of tiles
        section(col, "Icon pack")
        val current = IconPacks.current(this)
        val packs = IconPacks.installed(this)
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(8), 0)
        }
        fun packTile(label: String, using: Boolean, icon: android.graphics.drawable.Drawable?, onClick: () -> Unit) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(14), dp(10), dp(12))
                background = round(if (using) Palette.TILE2 else Palette.TILE, dpf(20f)).apply { setStroke(dp(2), if (using) Palette.INK else Color.argb(16, 255, 255, 255)) }
                pressable()
                setOnClickListener {
                    it.bounce()
                    onClick()
                }
            }
            t.addView(ImageView(this).apply {
                if (icon != null) setImageDrawable(icon) else {
                    setImageResource(R.drawable.ic_apps)
                    imageTintList = ColorStateList.valueOf(Palette.INK)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = round(Palette.GRAPHITE, dpf(12f))
                }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            t.addView(text(12f, Palette.INK, bold = true).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, dp(6))
            }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
            t.addView(chip(if (using) "Using" else "Use", using).apply { textSize = 11f })
            strip.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        packTile("App icons", current.isEmpty(), null) { usePack("") }
        for (pk in packs) {
            val ic = try {
                packageManager.getApplicationIcon(pk.pkg)
            } catch (_: Exception) {
                null
            }
            packTile(pk.label, current == pk.pkg, ic) { usePack(pk.pkg) }
        }
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(strip)
        })
        if (packs.isEmpty()) col.addView(text(12.5f, Palette.GREY, lines = 2).apply {
            text = "No icon packs installed yet. Get a free one below, or any from the Play Store."
            setPadding(dp(6), dp(8), dp(6), 0)
        })

        // Free packs to get
        section(col, "Get free icon packs")
        if (packStatus.isNotEmpty()) note(col, packStatus)
        val get = ArrayList<View>()
        for (src in IconPacks.CATALOG) {
            val have = IconPacks.isInstalled(this, src.pkg)
            get += row(src.name, src.about + "\nBy " + src.by + " · open source", chip(if (have) "Installed" else "Get", have)) {
                if (have) usePack(src.pkg) else getPack(src)
            }
        }
        col.addView(group(*get.toTypedArray()))
        section(col, "")
        col.addView(group(
            row("Liquid Glass icon packs", "Search the Play Store for Liquid Glass and iOS 26 packs", chevron()) { store("liquid glass icon pack") },
            row("More icon packs", "Thousands more on the Play Store", chevron()) { store("icon pack") },
            row("Make your own pack", "Renkin (free, open source) builds an icon pack on your phone", chevron()) { open("https://f-droid.org/packages/dev.renkinProject.renkin/") },
        ))
    }

    private fun usePack(pkg: String) {
        Prefs.get(this).edit().putString(Prefs.ICON_PACK, pkg).apply()
        Icons.forgetAll()
        render()
    }

    private fun store(q: String) {
        val u = Uri.encode(q)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$u&c=apps")))
        } catch (_: Exception) {
            open("https://play.google.com/store/search?q=$u&c=apps")
        }
    }

    /** Downloads the pack's official APK (F-Droid / IzzyOnDroid) and asks Android to install it. */
    private fun getPack(src: IconPacks.Source) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Halo Launcher to install apps, then tap Get again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        packStatus = "Getting ${src.name}…"
        render()
        Updater.installUrl(this, "", -1L, src.pkg, progress = { f ->
            packStatus = if (f >= 1f) "Confirm the install of ${src.name}, then tap Use" else "Downloading ${src.name} ${(f * 100).toInt()}%"
            if (page == Page.ICONS) render()
        }, failed = { msg ->
            packStatus = "Couldn't get ${src.name}: ${msg.take(60)}"
            if (page == Page.ICONS) render()
        }, resolve = { IconPacks.latestApk(src)?.first })
    }

    private fun dockPage(col: LinearLayout) {
        note(col, "4 to 6 apps. Long-press the home screen to reorder, remove or add them.")
        val frame = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(24f))
                }
            }
        }
        frame.addView(BackgroundView(this, miniature = true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dock = DockBar(this).apply {
            setPadding(dp(8), 0, dp(8), 0)
            // On the miniature background, glass is shown frosted (see previewGlass).
            if (background is GlassDrawable) background = previewGlass(dpf(34f))
        }
        val keys = Prefs.list(this, Prefs.DOCK)
        val apps = keys.mapNotNull { AppRepo.byKey(it) }.ifEmpty { sampleApps() }.take(LauncherActivity.DOCK_MAX)
        for (a in apps) dock.addView(AppIconView(this, a, dock.iconFor(apps.size) - 4, false, liveGlass = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        frame.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80), Gravity.BOTTOM).apply {
            leftMargin = dp(10)
            rightMargin = dp(10)
            bottomMargin = dp(12)
        })
        col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { bottomMargin = dp(14) })
        chips(col, "Style", Prefs.DOCK_STYLE, DockBar.NAMES, def = DockBar.GLASS)
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
        col.addView(button("Choose dock apps") {
            if (keys.isEmpty()) Prefs.setList(this, Prefs.DOCK, apps.map { it.key })
            startActivity(Intent(this, PickAppsActivity::class.java).putExtra("key", Prefs.DOCK).putExtra("max", LauncherActivity.DOCK_MAX))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
    }

    /**
     * App list settings. The two layouts as cards side by side, each with a
     * little moving preview of your own apps: Vertical scrolls up and down,
     * Horizontal swipes between pages with its dots following. Then columns
     * and names.
     */
    private fun drawerPage(col: LinearLayout) {
        val p = Prefs.get(this)
        val cur = p.getInt(Prefs.DRAWER_LAYOUT, 0)
        section(col, "Layout")
        val apps = (AppRepo.apps.filter { it.key !in Prefs.list(this, Prefs.HIDDEN) }.ifEmpty { sampleApps() }).take(32)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (mode in 0..1) {
            val on = mode == cur
            val card = FrameLayout(this).apply {
                background = round(if (on) Palette.TILE2 else Palette.TILE, dpf(24f)).apply { setStroke(dp(2), if (on) Palette.INK else Color.argb(16, 255, 255, 255)) }
                pressable()
                setOnClickListener {
                    it.bounce()
                    p.edit().putInt(Prefs.DRAWER_LAYOUT, mode).apply()
                    render()
                }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(14))
            }
            inner.addView(drawerPreview(mode == 1, apps), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
            inner.addView(text(15f, Palette.INK, bold = true).apply {
                text = if (mode == 0) "Vertical" else "Horizontal"
                setPadding(dp(6), dp(12), 0, 0)
            })
            inner.addView(text(12f, Palette.GREY, lines = 2).apply {
                text = if (mode == 0) "Scroll up and down, jump with A to Z" else "Swipe sideways through pages"
                setPadding(dp(6), dp(2), dp(4), 0)
            })
            card.addView(inner)
            if (on) card.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(Color.BLACK)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Palette.INK)
                }
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(16)
                rightMargin = dp(16)
            })
            line.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (mode > 0) marginStart = dp(10) })
        }
        col.addView(line)
        chips(col, "Apps across", Prefs.DRAWER_COLS, listOf("4", "5"), def = 0)
        col.addView(group(switchRow("Show names", "Names under apps in the list", Prefs.DRAWER_NAMES, true)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        note(col, "Searching always shows the results as a list.")
    }

    /**
     * A small phone screen of your apps that moves like the real thing.
     * Runs only while it's on screen.
     */
    private fun drawerPreview(sideways: Boolean, apps: List<App>): View {
        val st = stage(dpf(18f))
        val dim = View(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }
        st.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun grid(list: List<App>, rows: Int): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), 0)
            for (r in 0 until rows) {
                val row = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in 0 until 4) {
                    val a = list.getOrNull(r * 4 + c) ?: list.getOrNull((r * 4 + c) % list.size.coerceAtLeast(1))
                    if (a != null) row.addView(AppIconView(this@SettingsActivity, a, 22, false, liveGlass = false), LinearLayout.LayoutParams(0, dp(38), 1f))
                }
                addView(row)
            }
        }
        val anim: android.animation.ObjectAnimator
        if (!sideways) {
            val g = grid(apps, 10)
            st.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // A tiny A to Z strip down the side.
            st.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                for (ch in "ABCDEFGHIJ") addView(text(6f, Palette.SILVER).apply { text = ch.toString() })
            }, FrameLayout.LayoutParams(dp(10), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply { rightMargin = dp(2) })
            val travel = -dpf(38f * 10 + 10 - 230 + 20)
            anim = android.animation.ObjectAnimator.ofPropertyValuesHolder(g, android.animation.PropertyValuesHolder.ofKeyframe(View.TRANSLATION_Y,
                android.animation.Keyframe.ofFloat(0f, 0f),
                android.animation.Keyframe.ofFloat(0.2f, 0f),
                android.animation.Keyframe.ofFloat(0.5f, travel).apply { interpolator = android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f) },
                android.animation.Keyframe.ofFloat(0.7f, travel),
                android.animation.Keyframe.ofFloat(1f, 0f).apply { interpolator = android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f) },
            ))
        } else {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val a = grid(apps.take(20), 5)
            val b = grid(apps.drop(20).ifEmpty { apps.reversed() }, 5)
            strip.addView(a)
            strip.addView(b)
            st.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val dots = PageDots(this).apply {
                count = 2
                accent = Glass.wallColor(this@SettingsActivity)?.let { Glass.accent(this@SettingsActivity) } ?: Palette.INK
            }
            st.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14), Gravity.BOTTOM).apply { bottomMargin = dp(10) })
            anim = android.animation.ObjectAnimator.ofFloat(strip, View.TRANSLATION_X, 0f)
            // Pages are as wide as the preview, once it knows its width.
            st.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val w = v.width
                if (w > 0 && a.layoutParams.width != w) v.post {
                    b.layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
                    a.layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
                    val running = anim.isStarted
                    anim.cancel()
                    anim.setValues(android.animation.PropertyValuesHolder.ofKeyframe(View.TRANSLATION_X,
                        android.animation.Keyframe.ofFloat(0f, 0f),
                        android.animation.Keyframe.ofFloat(0.3f, 0f),
                        android.animation.Keyframe.ofFloat(0.45f, -w.toFloat()).apply { interpolator = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.25f, 1f) },
                        android.animation.Keyframe.ofFloat(0.8f, -w.toFloat()),
                        android.animation.Keyframe.ofFloat(0.95f, 0f).apply { interpolator = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.25f, 1f) },
                        android.animation.Keyframe.ofFloat(1f, 0f),
                    ))
                    if (running) anim.start()
                }
            }
            anim.addUpdateListener { dots.position = (-strip.translationX / st.width.coerceAtLeast(1)).coerceIn(0f, 1f) }
        }
        anim.duration = if (sideways) 4200L else 4800L
        anim.repeatCount = android.animation.ValueAnimator.INFINITE
        st.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (Prefs.animations(this@SettingsActivity)) anim.start()
            }
            override fun onViewDetachedFromWindow(v: View) = anim.cancel()
        })
        return st
    }

    private fun widgetsPage(col: LinearLayout) {
        note(col, "Long-press the home screen to drag a widget anywhere. Drop one on another of the same size to stack them.")
        val actions = object : WidgetActions {
            override fun askPermission(permission: String) = requestPermissions(arrayOf(permission), 6)
            override fun openSettingsPage(page: String) = go(Page.WEATHER)
            override fun openCalendar() {}
            override fun editNote() {}
            override fun refreshWeather() {}
            override fun openUsageAccess() {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                }
            }
            override fun launchPackage(pkg: String, from: View) {}
            override fun musicCommand(cmd: String) {
                HaloLink.command(this@SettingsActivity, cmd)
            }

            override fun pickPhoto() {
                Toast.makeText(this@SettingsActivity, "Tap the Photo widget on your home screen to pick a photo", Toast.LENGTH_SHORT).show()
            }
        }
        val unit = (resources.displayMetrics.widthPixels - dp(32) - dp(24) - dp(12)) / 2
        val order = HomeWidgets.order(this)
        for ((index, id) in order.withIndex()) {
            val spec = HomeWidgets.spec(id)
            val on = Prefs.widgetOn(this, spec.id)
            val size = HomeWidgets.sizeOf(this, spec.id)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(Color.rgb(10, 10, 11), dpf(26f))
                setPadding(dp(12), dp(12), dp(12), dp(6))
            }
            // The widget, live, on your background, at its size.
            val stage = FrameLayout(this).apply {
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, dpf(20f))
                    }
                }
            }
            stage.addView(BackgroundView(this, miniature = true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val w = HomeWidgets.create(this, spec.id, actions)
            if (w != null && w.background is GlassDrawable) w.background = previewGlass(dpf(26f))
            if (w != null) {
                val wW = if (size == HomeWidgets.SMALL) unit else ViewGroup.LayoutParams.MATCH_PARENT
                val wH = if (size == HomeWidgets.LARGE) unit * 2 + dp(12) else unit
                stage.addView(w, FrameLayout.LayoutParams(wW, wH).apply {
                    setMargins(dp(10), dp(10), dp(10), dp(10))
                })
                w.alpha = if (on) 1f else 0.35f
            }
            box.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // Size: Small / Medium / Large, like iOS.
            val sizes = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(12), dp(2), 0)
            }
            for ((i, name) in HomeWidgets.SIZE_NAMES.withIndex()) {
                sizes.addView(chip(name, i == size).apply {
                    pressable()
                    setOnClickListener {
                        HomeWidgets.setSize(this@SettingsActivity, spec.id, i)
                        render()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            }
            sizes.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            box.addView(sizes)

            val sw = Switch(this).apply {
                tint(this)
                isChecked = on
                setOnCheckedChangeListener { _, v ->
                    Prefs.setWidget(this@SettingsActivity, spec.id, v)
                    render()
                }
            }
            box.addView(row(spec.name, spec.about, sw) { sw.toggle() }.apply { setPadding(dp(4), dp(10), dp(2), dp(4)) })
            col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun gesturesPage(col: LinearLayout) {
        note(col, "Everything works from anywhere on the home screen, not just empty space.")
        val down = Prefs.get(this).getInt(Prefs.SWIPE_DOWN, 0)
        col.addView(group(
            info(R.drawable.ic_arrow_up, "Swipe up", "All your apps, with search"),
            info(R.drawable.ic_touch, "Long-press", "Edit the home screen: drag anything, resize widgets, remove things. On an app icon: its shortcuts"),
            info(R.drawable.ic_home, "Home button", "Back to page 1 and closes the app list"),
        ))
        section(col, "Swipe down opens")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, pair) in listOf("Halo" to "The island's notification center", "Android" to "The normal notification shade").withIndex()) {
            val card = choiceCard(down == i) {
                Prefs.get(this).edit().putInt(Prefs.SWIPE_DOWN, i).apply()
                render()
            }
            card.gravity = Gravity.START
            card.addView(ImageView(this).apply {
                setImageResource(if (i == 0) R.drawable.ic_halo else R.drawable.ic_arrow_down)
                if (i == 1) imageTintList = ColorStateList.valueOf(Palette.INK)
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            card.addView(text(15f, Palette.INK, bold = true).apply {
                text = pair.first
                setPadding(0, dp(10), 0, 0)
            })
            card.addView(text(12f, Palette.GREY, lines = 3).apply {
                text = pair.second
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 1) marginStart = dp(12) })
        }
        col.addView(row)
        section(col, "")
        col.addView(group(switchRow("Double-tap to lock", "Locks through Halo, so fingerprint and face unlock keep working", Prefs.DOUBLE_TAP_LOCK, true)))
    }

    private fun weatherPage(col: LinearLayout) {
        note(col, "Forecast by Open-Meteo: free, no account. Pick a city, or let Halo Launcher use your approximate location.")
        val city = Prefs.get(this).getString(Prefs.WEATHER_CITY, "").orEmpty()
        val fixed = Prefs.get(this).contains(Prefs.WEATHER_LAT)
        col.addView(group(
            row("City", if (fixed) city.ifEmpty { "Set" } else "Not set, tap to search", chevron()) { askCity() },
            row("Use my location", if (Weather.locationAllowed(this)) "Allowed (approximate)" else "Tap to allow", chip(if (Weather.locationAllowed(this) && !fixed) "On" else "Off", Weather.locationAllowed(this) && !fixed)) {
                Weather.setPlace(this, null, null, null)
                if (!Weather.locationAllowed(this)) requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 5) else render()
            },
            switchRow("Fahrenheit", "Show °F instead of °C", Prefs.FAHRENHEIT, false) { Weather.invalidate(this) },
        ))
        section(col, "Steps goal")
        val goal = Prefs.get(this).getInt(Prefs.STEP_GOAL, 6000)
        col.addView(group(row("Daily goal", String.format(java.util.Locale.getDefault(), "%,d steps", goal), chevron()) {
            val next = listOf(4000, 6000, 8000, 10000, 12000)
            val i = (next.indexOf(goal) + 1) % next.size
            Prefs.get(this).edit().putInt(Prefs.STEP_GOAL, next[i]).apply()
            render()
        }))
    }

    private fun haloPage(col: LinearLayout) {
        val halo = HaloLink.installed(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = round(Palette.TILE, dpf(24f))
            setPadding(dp(18), dp(22), dp(18), dp(18))
        }
        c.addView(ImageView(this).apply { setImageResource(R.drawable.ic_halo) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        c.addView(text(18f, Palette.INK, bold = true).apply {
            text = if (!halo) "Halo isn't installed" else if (HaloLink.alive()) "Linked to Halo" else "Halo is installed but not running"
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        })
        c.addView(text(13f, Palette.GREY, lines = 4).apply {
            text = "Halo is the dynamic island for your camera. The two apps share a private link, so the launcher gets:"
            gravity = Gravity.CENTER
        })
        col.addView(c)
        val g = group(
            info(R.drawable.ic_music, "Your music", "The player on page 1, for any app"),
            info(R.drawable.ic_badge, "Badges", "Unread counts on app icons"),
            info(R.drawable.ic_arrow_down, "Notifications", "Swipe down opens the island's notification center"),
            info(R.drawable.ic_touch, "Double-tap to lock", "Without breaking fingerprint unlock"),
        )
        col.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        col.addView(button(if (!halo) "Get Halo" else "Open Halo") {
            if (!halo) open("https://github.com/LONERXONE-ops/halo/releases/latest") else {
                packageManager.getLaunchIntentForPackage(HaloLink.HALO)?.let { startActivity(it) }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) })
    }

    private var backupStatus = ""

    private fun backupPage(col: LinearLayout) {
        note(col, "Your layout, widgets, theme, clock, icons and background in one file. Restore it on this phone or a new one.")
        if (backupStatus.isNotEmpty()) note(col, backupStatus)
        col.addView(button("Back up now") {
            val name = "halo-launcher-backup-" + android.text.format.DateFormat.format("yyyy-MM-dd", java.util.Date()) + ".json"
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name), 41)
            } catch (_: Exception) {
                Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })
        col.addView(group(row("Restore from a file", "Replaces your current setup", chevron()) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 42)
            } catch (_: Exception) {
                Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show()
            }
        }))
        note(col, "")
        note(col, "Widgets from other apps can't move between installs (Android's rule), so after a restore add those again with +.")
    }

    private fun aboutPage(col: LinearLayout) {
        col.addView(group(
            row("Made by LONERXONE", "github.com/LONERXONE-ops/halo-launcher", chevron()) { open("https://github.com/LONERXONE-ops/halo-launcher") },
            info(R.drawable.ic_info, "Ideas from", "Kvaesitso, Lawnchair, Olauncher, KISS, Niagara, Nothing Launcher and Apple's widget guidelines"),
            info(R.drawable.ic_info, "Fonts", "Inter, Doto, Graduate and Anton, under the SIL Open Font License"),
            info(R.drawable.ic_info, "Weather", "Open-Meteo.com"),
        ))
    }

    // =====================================================================
    // Building blocks
    // =====================================================================

    /** A small grey line of help, like Halo's footnotes. */
    private fun note(col: LinearLayout, t: String) {
        col.addView(text(12.5f, Palette.GREY, lines = 6).apply {
            text = t
            setLineSpacing(0f, 1.2f)
            setPadding(dp(18), dp(4), dp(18), dp(12))
        })
    }

    private fun section(col: LinearLayout, t: String) {
        col.addView(text(10.5f, Palette.GREY, caps = true).apply {
            text = t
            setPadding(dp(18), dp(if (t.isEmpty()) 10 else 24), 0, dp(8))
        })
    }

    private fun group(vararg rows: View): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Palette.TILE, dpf(24f)).apply { setStroke(dp(1), Color.argb(16, 255, 255, 255)) }
            setPadding(0, dp(3), 0, dp(3))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(24f))
                }
            }
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) c.addView(View(this).apply { setBackgroundColor(Palette.GRAPHITE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(58)
                marginEnd = dp(16)
            })
            c.addView(r)
        }
        return c
    }

    /** A menu row with an icon tile. */
    /** Each section gets its own colour tile, like the phone's own settings. */
    private val tileColours = mapOf(
        "Theme" to 0xFF7D5CFF.toInt(),
        "Wallpapers" to 0xFFFF4F8B.toInt(),
        "Animations" to 0xFFFF8A3D.toInt(),
        "Background" to 0xFF22B8A7.toInt(),
        "Clock & date" to 0xFF3E8BFF.toInt(),
        "Icons" to 0xFF34C759.toInt(),
        "Dock" to 0xFF5856D6.toInt(),
        "Widgets" to 0xFFFFB020.toInt(),
        "App list" to 0xFF30C8E0.toInt(),
        "Gestures" to 0xFF8E8E93.toInt(),
        "Weather" to 0xFF32ADE6.toInt(),
        "Backup & restore" to 0xFF30B0C7.toInt(),
        "Hidden apps" to 0xFF636366.toInt(),
        "Take the tour" to 0xFFFF6B6B.toInt(),
        "About" to 0xFF48484A.toInt(),
    )

    private fun nav(icon: Int, title: String, summary: String, onClick: () -> Unit): View {
        val r = row(title, summary, chevron(), onClick)
        val tc = if (tileColours.containsKey(title)) tileColour(title) else null
        r.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = if (icon == R.drawable.ic_halo) null else ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = if (tc != null) {
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(lighten(tc), tc)).apply { cornerRadius = dpf(10f) }
            } else {
                round(Palette.GRAPHITE, dpf(10f))
            }
        }, 0, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
        return r
    }

    private fun lighten(c: Int): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * 0.22f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * 0.22f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * 0.22f).toInt(),
    )

    private fun info(icon: Int, title: String, summary: String): View {
        val r = row(title, summary, null) {}
        r.isClickable = false
        r.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = if (icon == R.drawable.ic_halo) null else ColorStateList.valueOf(Palette.SILVER)
        }, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            marginStart = dp(4)
            marginEnd = dp(18)
        })
        return r
    }

    private fun row(title: String, summary: String, trailing: View?, onClick: () -> Unit): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(16), dp(13))
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
            setOnClickListener { onClick() }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(15.5f, Palette.INK, bold = true).apply { text = title })
        if (summary.isNotEmpty()) t.addView(text(12.5f, Palette.GREY, lines = 3).apply {
            text = summary
            setPadding(0, dp(2), 0, 0)
        })
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) r.addView(trailing)
        return r
    }

    /** A selectable card; the chosen one gets a white outline. */
    private fun choiceCard(selected: Boolean, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(16), dp(16), dp(14))
        background = round(Palette.TILE, dpf(22f)).apply { setStroke(dp(2), if (selected) Palette.INK else Palette.TILE) }
        pressable()
        setOnClickListener {
            it.bounce()
            onClick()
        }
    }

    private fun chevron(): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = ColorStateList.valueOf(Palette.DIM)
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
    }

    private fun chip(t: String, on: Boolean): TextView = text(12.5f, if (on) Color.BLACK else Palette.INK, bold = true).apply {
        text = t
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = round(if (on) Palette.INK else Palette.GRAPHITE, dpf(14f))
    }

    private fun button(t: String, onClick: () -> Unit): TextView = text(15f, Color.BLACK, bold = true).apply {
        text = t
        gravity = Gravity.CENTER
        background = round(Palette.INK, dpf(24f))
        pressable()
        setOnClickListener { onClick() }
    }

    private fun tint(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(170, 170, 176)))
        sw.trackTintList = ColorStateList(states, intArrayOf(Palette.SWITCH, Color.rgb(58, 58, 62)))
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switchRow(title: String, summary: String, key: String, def: Boolean, after: (() -> Unit)? = null): View {
        val p = Prefs.get(this)
        val sw = Switch(this).apply {
            tint(this)
            isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean(key, on).apply()
                after?.invoke()
            }
        }
        return row(title, summary, sw) { sw.toggle() }
    }

    private fun pictureRow(title: String, summary: String, key: String): View {
        val has = Prefs.get(this).getLong(key, 0L) != 0L
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(10f))
                }
            }
            clipToOutline = true
            background = round(Palette.GRAPHITE, dpf(10f))
            if (has) {
                try {
                    setImageBitmap(android.graphics.BitmapFactory.decodeFile(Prefs.picFile(this@SettingsActivity, key).absolutePath))
                } catch (_: Throwable) {
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(40))
        }
        return row(title, if (has) "Tap to change, long-press to remove" else summary, thumb) { pick(key) }.apply {
            setOnLongClickListener {
                Prefs.get(this@SettingsActivity).edit().remove(key).apply()
                Prefs.picFile(this@SettingsActivity, key).delete()
                Glass.invalidate()
                ensureBackdrop()
                render()
                true
            }
        }
    }


    private fun sampleApps(): List<App> {
        val wanted = listOf(
            "com.whatsapp", "com.google.android.youtube", "com.spotify.music", "com.instagram.android",
            "com.android.chrome", "com.google.android.gm", "com.twitter.android", "com.android.vending",
            "com.zhiliaoapp.musically", "com.google.android.apps.photos", "com.discord", "com.netflix.mediaclient",
        )
        val list = wanted.mapNotNull { p -> AppRepo.apps.firstOrNull { it.pkg == p } }
        return (list + AppRepo.apps).distinctBy { it.key }.take(8)
    }

    private fun loadPic(key: String) = try {
        val f = Prefs.picFile(this, key)
        if (f.exists() && Prefs.get(this).getLong(key, 0L) != 0L) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (_: Throwable) {
        null
    }

    // =====================================================================
    // Actions
    // =====================================================================

    private fun isDefault(): Boolean = try {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    } catch (_: Exception) {
        false
    }

    private fun askCity() {
        val input = EditText(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)).apply { hint = "City, e.g. Lagos" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Weather city")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            })
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                Weather.searchCity(q) { results ->
                    if (results.isEmpty()) {
                        Toast.makeText(this, "No city found", Toast.LENGTH_SHORT).show()
                        return@searchCity
                    }
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Pick your city")
                        .setItems(results.map { it.first }.toTypedArray()) { _, i ->
                            val r = results[i]
                            Weather.setPlace(this, r.first, r.second, r.third)
                            render()
                        }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun pick(key: String) {
        pickingKey = key
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 9)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker found", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 41 || requestCode == 42) && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Thread {
                val msg = try {
                    if (requestCode == 41) {
                        Backup.write(this, uri)
                        "Backed up. Keep that file somewhere safe."
                    } else {
                        Backup.read(this, uri)
                        "Restored. Go home to see it."
                    }
                } catch (e: Exception) {
                    "Didn't work: " + (e.message ?: "unknown problem").take(80)
                }
                runOnUiThread {
                    backupStatus = msg
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    render()
                }
            }.start()
            return
        }
        if (requestCode != 9 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val key = pickingKey ?: return
        Thread {
            val ok = try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1800) {
                        val f = 1800f / longest
                        dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                    }
                }
                Prefs.picFile(this, key).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                Prefs.get(this).edit().putLong(key, System.currentTimeMillis()).apply()
                Glass.invalidate()
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (!ok) Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
                ensureBackdrop()
                render()
            }
        }.start()
    }

    private fun open(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (_: Exception) {
        }
    }

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    private fun check() {
        updateStatus = "Checking…"
        Updater.check { r ->
            when (r) {
                is Updater.Check.Ok -> {
                    latest = r.release
                    updateStatus = if (r.release.build > Updater.buildOf(version())) "${r.release.tag} is out" else "You're up to date"
                }
                is Updater.Check.NotPublic -> updateStatus = "Updates need the GitHub repo to be public"
                is Updater.Check.Error -> updateStatus = "Couldn't check: ${r.message.take(40)}"
            }
            if (page == Page.MENU) render()
        }
    }

    private fun install(rel: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Halo Launcher to install updates, then tap again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        downloading = 0f
        updateStatus = "Downloading ${rel.tag}"
        render()
        Updater.install(this, rel, progress = { f ->
            downloading = if (f >= 1f) -1f else f
            updateStatus = if (f >= 1f) "Confirm the install" else "Downloading ${rel.tag}"
            if (page == Page.MENU) render()
        }, failed = { msg ->
            downloading = -1f
            updateStatus = "Failed: ${msg.take(40)}"
            render()
        })
    }
}
