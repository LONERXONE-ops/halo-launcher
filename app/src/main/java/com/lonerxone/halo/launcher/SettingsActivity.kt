package com.lonerxone.halo.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
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
        MENU("Settings"), LOOK("Home screen"), CLOCK("Clock"), ICONS("Icons"), DOCK("Dock"),
        WIDGETS("Widgets"), GESTURES("Gestures"), WEATHER("Weather"), HALO("Halo"), ABOUT("About"),
    }

    private var page = Page.MENU
    private var shown: Page? = null
    private var scroll: ScrollView? = null
    private var pickingKey: String? = null
    private var latest: Updater.Release? = null
    private var updateStatus = "Not checked yet"
    private var downloading = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        page = when (intent.getStringExtra("page")) {
            "widgets" -> Page.WIDGETS
            "weather" -> Page.WEATHER
            "clock" -> Page.CLOCK
            else -> Page.MENU
        }
        if (AppRepo.apps.isEmpty()) {
            AppRepo.addListener { render() }
            AppRepo.load(this)
        }
        check()
    }

    override fun onResume() {
        super.onResume()
        render()
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
        val same = shown == page
        val keepY = if (same) scroll?.scrollY ?: 0 else 0
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(40))
        }
        header(col)
        when (page) {
            Page.MENU -> menuPage(col)
            Page.LOOK -> lookPage(col)
            Page.CLOCK -> clockPage(col)
            Page.ICONS -> iconsPage(col)
            Page.DOCK -> dockPage(col)
            Page.WIDGETS -> widgetsPage(col)
            Page.GESTURES -> gesturesPage(col)
            Page.WEATHER -> weatherPage(col)
            Page.HALO -> haloPage(col)
            Page.ABOUT -> aboutPage(col)
        }
        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
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
        if (same) {
            sv.post { sv.scrollTo(0, keepY) }
        } else if (Prefs.animations(this)) {
            // Pages slide in; their cards follow one after another.
            val dir = if (page == Page.MENU) -1f else 1f
            col.translationX = dpf(36f) * dir
            col.alpha = 0f
            col.animate().translationX(0f).alpha(1f).setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).start()
            for (i in 0 until col.childCount) col.getChildAt(i).enter(60L + i * 35L, dpf(14f))
        }
        shown = page
    }

    private fun header(col: LinearLayout) {
        if (page == Page.MENU) {
            col.addView(ImageView(this).apply { setImageResource(R.drawable.logo_launcher) }, LinearLayout.LayoutParams(dp(92), dp(92)).apply {
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
        col.addView(text(30f, Palette.INK, bold = true).apply {
            text = page.title
            setPadding(dp(4), dp(26), 0, dp(14))
        })
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
            nav(R.drawable.ic_image, "Home screen", "Top picture, motion") { go(Page.LOOK) },
            nav(R.drawable.ic_clock, "Clock", StackedClock.NAMES[clockStyle()]) { go(Page.CLOCK) },
            nav(R.drawable.ic_apps, "Icons", listOf("Dark", "White line", "Original")[Icons.style(this)]) { go(Page.ICONS) },
            nav(R.drawable.ic_dock, "Dock", "${Prefs.list(this, Prefs.DOCK).size.takeIf { it > 0 } ?: 5} apps") { go(Page.DOCK) },
            nav(R.drawable.ic_widgets, "Widgets", "${HomeWidgets.ALL.count { Prefs.widgetOn(this, it.id) }} on page 2") { go(Page.WIDGETS) },
        ))
        section(col, "Use")
        col.addView(group(
            nav(R.drawable.ic_gesture, "Gestures", "Swipe, double-tap, long-press") { go(Page.GESTURES) },
            nav(R.drawable.w_sun, "Weather", Prefs.get(this).getString(Prefs.WEATHER_CITY, "").orEmpty().ifEmpty { if (Weather.locationAllowed(this)) "Your location" else "Not set" }) { go(Page.WEATHER) },
            nav(R.drawable.ic_halo, "Halo", if (HaloLink.alive()) "Linked" else if (HaloLink.installed(this)) "Not running" else "Not installed") { go(Page.HALO) },
            nav(R.drawable.ic_eye_off, "Hidden apps", "${Prefs.list(this, Prefs.HIDDEN).size} hidden") {
                startActivity(Intent(this, PickAppsActivity::class.java).putExtra("key", Prefs.HIDDEN).putExtra("max", 0))
            },
        ))
        section(col, "")
        col.addView(group(nav(R.drawable.ic_info, "About", "Credits and licences") { go(Page.ABOUT) }))
    }

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

    private fun lookPage(col: LinearLayout) {
        note(col, "Your wallpaper fills the top of the screen. Pick a picture to show there instead; it stays still while the pages move.")
        col.addView(preview(), LinearLayout.LayoutParams(dp(170), dp(330)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
            bottomMargin = dp(18)
        })
        col.addView(group(
            pictureRow("Top picture", "Shown above the black sheet. Empty = your wallpaper", Prefs.HERO_PIC),
            switchRow("Motion", "Things glide in, icons ripple, digits roll. Off for a calmer screen", Prefs.ANIMATIONS, true),
        ))
    }

    /** A small live model of the home screen. */
    private fun preview(): View {
        val f = FrameLayout(this).apply {
            background = round(Color.rgb(40, 40, 44), dpf(26f))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(26f))
                }
            }
        }
        val art = ArtView(this)
        art.picture = loadPic(Prefs.HERO_PIC)
        f.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (art.picture == null) {
            f.addView(text(10f, Palette.SILVER, caps = true).apply {
                text = "Your wallpaper"
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)))
        }
        val sheet = SheetView(this, highOnLeft = true).apply {
            curve = dpf(34f)
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val clock = StackedClock(this).apply { style = clockStyle() }
        sheet.addView(clock, LinearLayout.LayoutParams(dp(72), dp(76)))
        sheet.addView(View(this).apply { background = round(Palette.TILE, dpf(12f)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        f.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200), Gravity.BOTTOM))
        val dock = DockTab(this).apply { sideMargin = dpf(10f) }
        f.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.BOTTOM))
        return f
    }

    private fun clockPage(col: LinearLayout) {
        note(col, "The big clock on page 1. Tap a style; the digits roll up each minute.")
        val cur = clockStyle()
        var row: LinearLayout? = null
        for ((i, name) in StackedClock.NAMES.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            }
            val card = choiceCard(i == cur) {
                Prefs.get(this).edit().putInt(Prefs.CLOCK_STYLE, i).apply()
                render()
            }
            card.addView(StackedClock(this).apply { style = i }, LinearLayout.LayoutParams(dp(110), dp(118)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            card.addView(text(13f, Palette.INK, bold = true).apply {
                text = name
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
            row?.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i % 2 == 1) marginStart = dp(12) })
        }
    }

    private fun iconsPage(col: LinearLayout) {
        note(col, "How app icons look in the dock and the app list. Here are your own apps in each style.")
        val samples = sampleApps()
        val names = listOf("Dark" to "Like iOS dark mode: each app's symbol in its own colour on a dark tile.", "White line" to "Just the white symbol, like Android's themed icons.", "Original" to "Icons as the apps made them.")
        for ((i, pair) in names.withIndex()) {
            val card = choiceCard(Icons.style(this) == i) {
                Prefs.get(this).edit().putInt(Prefs.ICON_STYLE, i).apply()
                render()
            }
            card.gravity = Gravity.START
            card.addView(text(15f, Palette.INK, bold = true).apply { text = pair.first })
            card.addView(text(12.5f, Palette.GREY, lines = 2).apply {
                text = pair.second
                setPadding(0, dp(3), 0, dp(10))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (a in samples) row.addView(AppIconView(this, a, 46, false, forceStyle = i), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun dockPage(col: LinearLayout) {
        note(col, "The dock rises out of the bottom edge like a notch turned upside down. Up to 5 apps. You can also long-press the dock on the home screen.")
        val frame = FrameLayout(this).apply { background = round(Color.BLACK, dpf(20f)) }
        val dock = DockTab(this).apply { setPadding(dp(30), dp(6), dp(30), dp(10)) }
        val keys = Prefs.list(this, Prefs.DOCK)
        val apps = keys.mapNotNull { AppRepo.byKey(it) }.ifEmpty { sampleApps() }
        for (a in apps.take(5)) dock.addView(AppIconView(this, a, 48, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        frame.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92), Gravity.BOTTOM))
        col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)).apply { bottomMargin = dp(14) })
        col.addView(button("Choose dock apps") {
            if (keys.isEmpty()) Prefs.setList(this, Prefs.DOCK, apps.map { it.key })
            startActivity(Intent(this, PickAppsActivity::class.java).putExtra("key", Prefs.DOCK).putExtra("max", 5))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
    }

    private fun widgetsPage(col: LinearLayout) {
        note(col, "Page 2's widgets, live. Switch off what you don't need; each shows one thing and keeps itself up to date. Android widgets can be added from page 2 too.")
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
        }
        for (spec in HomeWidgets.ALL) {
            val on = Prefs.widgetOn(this, spec.id)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(Color.rgb(10, 10, 11), dpf(26f))
                setPadding(dp(12), dp(12), dp(12), dp(6))
            }
            val w = HomeWidgets.create(this, spec.id, actions)
            if (w != null) {
                val wrap: View = if (spec.wide) w else SquareFrame(this).apply { addView(w) }
                box.addView(wrap, LinearLayout.LayoutParams(if (spec.wide) ViewGroup.LayoutParams.MATCH_PARENT else dp(160), if (spec.wide) dp(150) else ViewGroup.LayoutParams.WRAP_CONTENT))
                wrap.alpha = if (on) 1f else 0.35f
            }
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
            info(R.drawable.ic_touch, "Long-press", "These settings (or an app's shortcuts on an icon)"),
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

    private fun note(col: LinearLayout, t: String) {
        col.addView(text(13.5f, Palette.GREY, lines = 5).apply {
            text = t
            setLineSpacing(0f, 1.15f)
            setPadding(dp(4), 0, dp(4), dp(10))
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
            background = round(Palette.TILE, dpf(22f))
            setPadding(0, dp(2), 0, dp(2))
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) c.addView(View(this).apply { setBackgroundColor(Color.rgb(34, 34, 37)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(58)
                marginEnd = dp(16)
            })
            c.addView(r)
        }
        return c
    }

    /** A menu row with an icon tile. */
    private fun nav(icon: Int, title: String, summary: String, onClick: () -> Unit): View {
        val r = row(title, summary, chevron(), onClick)
        r.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = if (icon == R.drawable.ic_halo) null else ColorStateList.valueOf(Palette.INK)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = round(Palette.GRAPHITE, dpf(10f))
        }, 0, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) })
        return r
    }

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

    private fun chip(t: String, on: Boolean): TextView = text(12f, if (on) Color.BLACK else Palette.INK, bold = true).apply {
        text = t
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = round(if (on) Palette.INK else Palette.GRAPHITE, dpf(12f))
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
        sw.trackTintList = ColorStateList(states, intArrayOf(Palette.GREEN, Color.rgb(58, 58, 62)))
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
                render()
                true
            }
        }
    }

    private fun clockStyle() = Prefs.get(this).getInt(Prefs.CLOCK_STYLE, StackedClock.VARSITY).coerceIn(0, 3)

    private fun sampleApps(): List<App> {
        val wanted = listOf("com.whatsapp", "com.google.android.youtube", "com.spotify.music", "com.android.vending", "com.instagram.android", "com.twitter.android")
        val list = wanted.mapNotNull { p -> AppRepo.apps.firstOrNull { it.pkg == p } }
        return (list + AppRepo.apps).distinctBy { it.key }.take(5)
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
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (!ok) Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
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
