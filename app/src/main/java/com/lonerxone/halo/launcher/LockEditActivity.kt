package com.lonerxone.halo.launcher

import android.app.Activity
import android.app.Dialog
import android.app.WallpaperManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Editing the lock screen like One UI: the lock screen itself in a big card,
 * with every part you can change outlined. Tap the clock, the widget space,
 * the Now Bar or a shortcut and its choices come up from the bottom;
 * "Wallpapers" and "Done" sit on top, and the bar underneath has the
 * picture, layout and depth effect. Every change shows at once.
 */
class LockEditActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var holder: FrameLayout
    private var sw = 0
    private var sh = 0
    private var picture: Bitmap? = null

    private fun p() = Prefs.get(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        Palette.adapt(this)
        val dm = resources.displayMetrics
        sw = dm.widthPixels
        sh = max(dm.heightPixels, windowManager.currentWindowMetrics.bounds.height())
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        loadPicture()
        build(first = true)
    }

    override fun onResume() {
        super.onResume()
        // Back from the wallpaper browser: the picture may have changed.
        if (::holder.isInitialized && Glass.backdrop != null && p().getInt(Prefs.LOCK_WALL, 0) == 0 && picture !== Glass.backdrop) {
            loadPicture()
            build(first = false)
        }
    }

    /** The lock wallpaper (when chosen and Android shares it), else the home picture. */
    private fun loadPicture() {
        picture = null
        if (p().getInt(Prefs.LOCK_WALL, 0) == 1 && Build.VERSION.SDK_INT >= 34) {
            val d: Drawable? = try {
                WallpaperManager.getInstance(this).getDrawable(WallpaperManager.FLAG_LOCK)
            } catch (_: Throwable) {
                null
            }
            if (d != null) {
                val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                val iw = d.intrinsicWidth.coerceAtLeast(1)
                val ih = d.intrinsicHeight.coerceAtLeast(1)
                val s = max(sw.toFloat() / iw, sh.toFloat() / ih)
                val dw = (iw * s).toInt()
                val dh = (ih * s).toInt()
                d.setBounds((sw - dw) / 2, (sh - dh) / 2, (sw - dw) / 2 + dw, (sh - dh) / 2 + dh)
                d.draw(Canvas(b))
                picture = b
            }
        }
        if (picture == null) {
            picture = Glass.backdrop
            if (picture == null) Glass.load(this, sw, sh) {
                picture = Glass.backdrop
                build(first = false)
            }
        }
    }

    // =====================================================================
    // The screen
    // =====================================================================

    private fun build(first: Boolean) {
        root.removeAllViews()
        // Behind everything: the picture, blurred and dimmed.
        picture?.let { pic ->
            root.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(pic)
                alpha = 0.55f
                if (Build.VERSION.SDK_INT >= 31) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(60f, 60f, android.graphics.Shader.TileMode.CLAMP))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(View(this).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, b.top + dp(14), 0, b.bottom + dp(8))
            insets
        }

        // The card: the real lock screen laid out at full size, then shrunk to fit.
        val scale = 0.8f
        val cw = (sw * scale).toInt()
        val ch = (sh * scale).toInt()
        holder = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dpf(34f))
            }
            clipToOutline = true
            elevation = dpf(12f)
        }
        val avail = LinearLayout.LayoutParams(cw, 0, 1f)
        page.addView(holder, avail)
        val screen = screen()
        holder.addView(screen, FrameLayout.LayoutParams(sw, sh))
        holder.post {
            // Fit the card in the space left (keeping the phone's shape).
            val s = min(holder.width / sw.toFloat(), holder.height / sh.toFloat())
            screen.pivotX = 0f
            screen.pivotY = 0f
            screen.scaleX = s
            screen.scaleY = s
            val w = (sw * s).toInt()
            val h = (sh * s).toInt()
            holder.layoutParams = (holder.layoutParams as LinearLayout.LayoutParams).apply {
                width = w
                height = h
                weight = 0f
                topMargin = 0
            }
            holder.requestLayout()
        }

        // The bar at the bottom.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(4))
        }
        bar.addView(tool(R.drawable.ic_image, "Wallpapers") { startActivity(Intent(this, WallpaperActivity::class.java)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(tool(R.drawable.ic_glass, "Picture") { pictureSheet() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(tool(R.drawable.ic_clock, "Layout") { clockSheet() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(tool(R.drawable.ic_widgets, "Depth") { depthSheet() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (first && Prefs.animations(this)) {
            holder.alpha = 0f
            holder.scaleX = 0.9f
            holder.scaleY = 0.9f
            holder.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(1.4f)).start()
            bar.enter(160L, dpf(30f))
        }
    }

    /** One of the bottom bar's buttons: an icon over a word. */
    private fun tool(icon: Int, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        addView(ImageView(this@LockEditActivity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(text(13f, Color.WHITE, bold = true).apply {
            text = label
            setPadding(0, dp(6), 0, 0)
        })
        pressable()
        setOnClickListener { onClick() }
    }

    /** The lock screen at full size, as LockActivity lays it out, with each part outlined and tappable. */
    private fun screen(): FrameLayout {
        val f = FrameLayout(this)
        f.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            picture?.let { setImageBitmap(it) } ?: setBackgroundColor(Color.rgb(30, 30, 34))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(110, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(130, 0, 0, 0)))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val layout = p().getInt(Prefs.LOCK_LAYOUT, 0)
        val size = listOf(0.8f, 1f, 1.22f)[p().getInt(Prefs.LOCK_CLOCK_SIZE, 1).coerceIn(0, 2)]
        val top = if (p().getInt(Prefs.LOCK_CLOCK_POS, 0) == 1) (sh * 0.15f).toInt() else dp(56)
        val side = dp(24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, top + dp(40), side, 0)
        }
        f.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The clock and date in one outlined box.
        val clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = outline()
            setOnClickListener { clockSheet() }
        }
        val date = DateFace(this, forceStyle = if (layout == 0) 1 else -1).apply {
            preview = true
            alignOverride = if (layout == 2) 0 else 1
        }
        val clock = ClockFace(this).apply {
            preview = true
            alignOverride = if (layout == 2) 0 else 1
            stackedOverride = layout != 0
        }
        val clockH = when (layout) {
            0 -> (sh * 0.13f * size).toInt()
            1 -> (sh * 0.27f * size).toInt()
            else -> (sh * 0.24f * size).toInt()
        }
        val clockW = if (layout == 2) ((sw - side * 2) * 0.62f).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        if (layout == 1) {
            clockBox.addView(clock, LinearLayout.LayoutParams(clockW, clockH))
            clockBox.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(4) })
        } else {
            clockBox.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
            clockBox.addView(clock, LinearLayout.LayoutParams(clockW, clockH).apply { topMargin = dp(2) })
        }
        clockBox.pressable()
        col.addView(clockBox)

        // The widget space: what's there, or "Add widgets".
        val ws = Prefs.list(this, Prefs.LOCK_WIDGETS).filter { id -> HomeWidgets.ALL.any { it.id == id } }.take(2)
        val wbox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = outline()
            setOnClickListener { widgetSheet() }
            pressable()
        }
        if (ws.isEmpty()) {
            wbox.addView(text(17f, Color.argb(220, 255, 255, 255), bold = true).apply { text = "+  Add widgets" })
        } else for ((i, id) in ws.withIndex()) {
            wbox.addView(text(16f, Color.WHITE, bold = true).apply {
                text = HomeWidgets.ALL.firstOrNull { it.id == id }?.name.orEmpty()
                gravity = Gravity.CENTER
                background = surface(dpf(26f), 0.32f)
            }, LinearLayout.LayoutParams(0, dp(120), 1f).apply { if (i > 0) marginStart = dp(12) })
        }
        col.addView(wbox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (ws.isEmpty()) dp(72) else ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        // Top: Wallpapers and Done.
        val topRow = FrameLayout(this)
        topRow.addView(pill("Wallpapers") { startActivity(Intent(this, WallpaperActivity::class.java)) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64), Gravity.START))
        topRow.addView(pill("Done") { finish() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64), Gravity.END))
        f.addView(topRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(26), dp(30), dp(26), 0) })

        // Bottom: the shortcuts and the Now Bar between them.
        val bottom = FrameLayout(this)
        f.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132), Gravity.BOTTOM).apply { bottomMargin = dp(12) })
        for ((g, k) in listOf(Gravity.START to Prefs.LOCK_LEFT, Gravity.END to Prefs.LOCK_RIGHT)) {
            val kind = p().getInt(k, if (k == Prefs.LOCK_LEFT) 1 else 2)
            bottom.addView(ImageView(this).apply {
                if (kind == 0) {
                    setImageResource(R.drawable.ic_plus)
                    background = outline(dpf(27f))
                } else {
                    setImageResource(if (kind == 1) R.drawable.ic_flashlight else R.drawable.ic_camera)
                    background = round(Color.argb(120, 30, 30, 34), dpf(27f))
                }
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(dp(15), dp(15), dp(15), dp(15))
                pressable()
                setOnClickListener { shortcutSheet(k, if (g == Gravity.START) "Left shortcut" else "Right shortcut") }
            }, FrameLayout.LayoutParams(dp(54), dp(54), g or Gravity.BOTTOM).apply {
                marginStart = dp(34)
                marginEnd = dp(34)
                bottomMargin = dp(18)
            })
        }
        val now = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = outline(dpf(31f))
            pressable()
            setOnClickListener { nowSheet() }
        }
        val musicIn = p().getBoolean(Prefs.LOCK_MUSIC, true) && p().getInt(Prefs.LOCK_MUSIC_POS, 0) == 0
        val notesIn = p().getInt(Prefs.LOCK_NOTES, 0) == 0
        now.addView(text(14f, Color.WHITE, bold = true).apply {
            text = when {
                musicIn && notesIn -> "Now Bar · music, notifications"
                musicIn -> "Now Bar · music"
                notesIn -> "Now Bar · notifications"
                else -> "Now Bar · off"
            }
        })
        bottom.addView(now, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.BOTTOM).apply {
            marginStart = dp(96)
            marginEnd = dp(96)
            bottomMargin = dp(14)
        })
        return f
    }

    /** The dashed outline One UI draws round each part you can edit. */
    private fun outline(radius: Float = dpf(26f)): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.argb(24, 255, 255, 255))
        setStroke(dp(2), Color.argb(170, 255, 255, 255), dpf(10f), dpf(7f))
    }

    private fun pill(t: String, onClick: () -> Unit): View = text(20f, Color.WHITE, bold = true).apply {
        text = t
        gravity = Gravity.CENTER
        setPadding(dp(28), 0, dp(28), 0)
        background = round(Color.argb(110, 60, 60, 66), dpf(32f))
        pressable()
        setOnClickListener { onClick() }
    }

    private fun changed() {
        HaloLink.command(this, "lockscreen", if (p().getBoolean(Prefs.LOCK_ON, false)) "1" else "0")
        build(first = false)
    }

    // =====================================================================
    // Sheets: the choices for each part, sliding up from the bottom
    // =====================================================================

    private fun sheet(title: String, fill: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(26))
            background = GradientDrawable().apply {
                setColor(Palette.TILE)
                cornerRadii = floatArrayOf(dpf(30f), dpf(30f), dpf(30f), dpf(30f), 0f, 0f, 0f, 0f)
            }
        }
        body.addView(View(this).apply { background = round(Color.argb(90, 255, 255, 255), dpf(2f)) }, LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })
        body.addView(text(20f, Palette.INK, bold = true).apply {
            text = title
            setPadding(dp(4), 0, 0, dp(8))
        })
        fill(body, d)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        val wrap = FrameLayout(this).apply { setOnClickListener { d.dismiss() } }
        wrap.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        d.setContentView(wrap)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.argb(90, 0, 0, 0)))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
        }
        d.show()
        body.translationY = dpf(400f)
        body.animate().translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator(2.4f)).start()
    }

    /** A title and its choices as chips; picking one saves it and redraws the card. */
    private fun chips(col: LinearLayout, title: String, key: String, names: List<String>, def: Int) {
        col.addView(text(13f, Palette.GREY, bold = true).apply {
            text = title
            setPadding(dp(4), dp(14), 0, dp(8))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = p().getInt(key, def)
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            v.setTextColor(if (i == sel) Color.BLACK else Palette.INK)
            v.background = round(if (i == sel) Palette.INK else Palette.TILE2, dpf(18f))
        }
        for ((i, n) in names.withIndex()) {
            val c = text(13.5f, Palette.INK, bold = true).apply {
                text = n
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(11), dp(8), dp(11))
                pressable()
                setOnClickListener {
                    p().edit().putInt(key, i).apply()
                    paint(i)
                    changed()
                }
            }
            views += c
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        paint(cur)
        col.addView(row)
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(col: LinearLayout, title: String, summary: String, key: String, def: Boolean, after: () -> Unit = {}) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = round(Palette.TILE2, dpf(20f))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(15.5f, Palette.INK, bold = true).apply { text = title })
        texts.addView(text(12.5f, Palette.GREY, lines = 3).apply { text = summary })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = p().getBoolean(key, def)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(170, 170, 176)))
            trackTintList = ColorStateList(states, intArrayOf(Palette.GREEN, Color.rgb(58, 58, 62)))
            setOnCheckedChangeListener { _, on ->
                p().edit().putBoolean(key, on).apply()
                changed()
                after()
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun note(col: LinearLayout, t: String) {
        col.addView(text(12.5f, Palette.GREY, lines = 6).apply {
            text = t
            setLineSpacing(0f, 1.2f)
            setPadding(dp(4), dp(10), dp(4), 0)
        })
    }

    private fun clockSheet() = sheet("Clock") { col, _ ->
        chips(col, "Style", Prefs.LOCK_LAYOUT, listOf("Classic", "Stacked", "Side"), 0)
        chips(col, "Size", Prefs.LOCK_CLOCK_SIZE, listOf("Small", "Medium", "Large"), 1)
        chips(col, "Position", Prefs.LOCK_CLOCK_POS, listOf("Top", "Lower"), 0)
        col.addView(text(15f, Color.BLACK, bold = true).apply {
            text = "Faces, colours and glass"
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = round(Palette.INK, dpf(24f))
            pressable()
            setOnClickListener { startActivity(Intent(this@LockEditActivity, SettingsActivity::class.java).putExtra("page", "clock")) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
    }

    private fun depthSheet() = sheet("Depth effect") { col, _ ->
        toggle(col, "Depth effect", "Your wallpaper's subject in front of the time", Prefs.LOCK_DEPTH, false)
        chips(col, "Sensitivity", Prefs.LOCK_DEPTH_SENS, listOf("Gentle", "Normal", "Strong"), 1)
        note(col, "Works best with a clear subject (a person, mountains, a building) against a plain sky or wall, with the clock Stacked and Lower. It finds the subject by colour, so busy pictures may not work, and it switches off when the subject would hide too much of the time. You'll see it on the lock screen itself.")
    }

    private fun pictureSheet() = sheet("Picture") { col, d ->
        chips(col, "Lock screen shows", Prefs.LOCK_WALL, listOf("Home picture", "Lock wallpaper"), 0)
        if (Build.VERSION.SDK_INT < 34) note(col, "Your Android version doesn't share the lock wallpaper with apps, so it uses the home picture.")
        col.addView(text(15f, Color.BLACK, bold = true).apply {
            text = "Browse wallpapers"
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = round(Palette.INK, dpf(24f))
            pressable()
            setOnClickListener {
                d.dismiss()
                startActivity(Intent(this@LockEditActivity, WallpaperActivity::class.java))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
    }

    private fun shortcutSheet(key: String, title: String) = sheet(title) { col, _ ->
        chips(col, "Hold it to use", key, listOf("None", "Flashlight", "Camera"), if (key == Prefs.LOCK_LEFT) 1 else 2)
        note(col, "A tap only shows how, so it never goes off in your pocket.")
    }

    private fun nowSheet() = sheet("Now Bar") { col, _ ->
        toggle(col, "What's playing", "The music player while a song plays", Prefs.LOCK_MUSIC, true)
        chips(col, "Player sits", Prefs.LOCK_MUSIC_POS, listOf("In the Now Bar", "Under the clock"), 0)
        chips(col, "Notifications", Prefs.LOCK_NOTES, listOf("In the Now Bar", "Just how many", "Hidden"), 0)
        note(col, "Swipe the Now Bar sideways to go through what's playing and your notifications. Tap a notification to open it, pull it down to clear it.")
    }

    private fun widgetSheet() = sheet("Widgets") { col, _ ->
        note(col, "Up to two, under the clock. Tap to add or take off.")
        val choices = HomeWidgets.ALL.filter { it.id != "music" && it.id != "photo" && it.id != "note" }
        var row: LinearLayout? = null
        val views = HashMap<String, TextView>()
        fun paint() {
            val chosen = Prefs.list(this, Prefs.LOCK_WIDGETS)
            for ((id, v) in views) {
                val sel = id in chosen
                v.setTextColor(if (sel) Color.BLACK else Palette.INK)
                v.background = round(if (sel) Palette.INK else Palette.TILE2, dpf(18f))
            }
        }
        for ((i, spec) in choices.withIndex()) {
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            }
            val v = text(13f, Palette.INK, bold = true).apply {
                text = spec.name
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(12))
                pressable()
                setOnClickListener {
                    val now = Prefs.list(this@LockEditActivity, Prefs.LOCK_WIDGETS).toMutableList()
                    if (spec.id in now) now.remove(spec.id) else {
                        if (now.size >= 2) now.removeAt(0)
                        now.add(spec.id)
                    }
                    Prefs.setList(this@LockEditActivity, Prefs.LOCK_WIDGETS, now)
                    paint()
                    changed()
                }
            }
            views[spec.id] = v
            row?.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i % 3 > 0) marginStart = dp(8) })
        }
        val rem = choices.size % 3
        if (rem != 0) repeat(3 - rem) { row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }) }
        paint()
    }
}
