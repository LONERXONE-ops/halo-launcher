package com.lonerxone.halo.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The welcome tour: what Halo Launcher does and where each setting lives,
 * page by page, each with a small live scene. The last page offers to make
 * it your home screen. Shown once to everyone (also after this update, for
 * people who set it up before), always skippable with ✕, and available again
 * from Settings → Take the tour.
 */
class OnboardingActivity : Activity() {
    private class Step(val title: String, val body: String, val settings: String?, val page: String?, val scene: (FrameLayout) -> Unit)

    private lateinit var pager: HomePager
    private lateinit var dots: PageDots
    private lateinit var next: TextView
    private val animators = ArrayList<ValueAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        Palette.adapt(this)
        val steps = listOf(
            Step("Welcome to Halo Launcher", "A home screen made of glass, built around your wallpaper, and made to work with Halo, your dynamic island. Here's how it works, in a minute.", null, null, ::sceneWelcome),
            Step("Long-press to edit", "Hold the wallpaper (or any widget) and everything jiggles. Drag things anywhere, on any page; hold at the edge to carry them to the next page. Tap a size pill to resize, the minus to remove, + to add.", "Tip: tap the clock or date while editing to restyle them.", null, ::sceneEdit),
            Step("Widgets, stacks and folders", "Drop a widget onto one of the same size to make a stack (swipe it up and down). Drop an app onto an app to make a folder.", "Settings → Widgets", "widgets", ::sceneStack),
            Step("Liquid Glass and your colours", "Everything sits on real glass that bends your wallpaper. Adaptive colour tints tiles, dock, menus and the app list with your wallpaper's hue.", "Settings → Theme", "theme", ::sceneGlass),
            Step("Clock, icons, wallpapers", "23 clock faces with glass digits, icon packs and Liquid Glass icons, and thousands of wallpapers you can frame by dragging and pinching.", "Settings → Clock & date · Icons · Wallpapers", "clock", ::sceneClock),
            Step("Gestures", "Swipe up: all your apps (with search and a calculator). Swipe down: Halo's notification center. Double-tap: lock. Hold an app: shortcuts, or keep holding and drag it to your home screen.", "Settings → Gestures · Animations", "gestures", ::sceneGestures),
            Step("What's in Settings", "Theme: glass or solid, adaptive colour. Background: the picture behind everything. Clock & date: faces, colours, glass digits. Animations: app opening and page effects. Icons: looks and icon packs. Dock: apps and style. Widgets: sizes, on and off. Backup: your whole setup in one file.", null, null, ::sceneSettings),
            Step("Make it your home screen", "So the home button brings you here. You can change it back any time in your phone's settings.", null, null, ::sceneHome),
        )

        val root = FrameLayout(this)
        root.addView(BackgroundView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pager = HomePager(this)
        for (s in steps) pager.addView(page(s, s === steps.last()))
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ✕: nobody is made to sit through it.
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            rotation = 45f
            imageTintList = ColorStateList.valueOf(Palette.INK)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = surface(dpf(21f), 0.3f)
            contentDescription = "Close"
            setOnClickListener {
                it.bounce()
                finishTour()
            }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(48)
            rightMargin = dp(18)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(28))
        }
        dots = PageDots(this).apply { pages = steps.size }
        bottom.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { bottomMargin = dp(16) })
        next = text(16f, Color.BLACK, bold = true).apply {
            text = "Next"
            gravity = Gravity.CENTER
            background = round(Palette.INK, dpf(26f))
            pressable()
            setOnClickListener {
                if (pager.current < steps.size - 1) pager.snapTo(pager.current + 1) else finishTour()
            }
        }
        bottom.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        pager.onScrolled = { f ->
            dots.position = f
            Motion.applyPages((0 until pager.childCount).mapNotNull { pager.page(it) }, f, Motion.PAGE_DEPTH)
        }
        pager.onPageChanged = { p ->
            next.text = if (p == steps.size - 1) "Start" else "Next"
            enter(pager.page(p))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            (close.layoutParams as FrameLayout.LayoutParams).topMargin = b.top + dp(12)
            close.requestLayout()
            bottom.setPadding(dp(24), 0, dp(24), b.bottom + dp(20))
            insets
        }
        setContentView(root)
        root.post { enter(pager.page(0)) }
    }

    override fun onResume() {
        super.onResume()
        // Back from the "default home" screen: show whether it worked.
        homeStatus?.let { updateHomeStatus(it) }
    }

    override fun onDestroy() {
        for (a in animators) a.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (pager.current > 0) pager.snapTo(pager.current - 1) else finishTour()
    }

    private fun finishTour() {
        Prefs.get(this).edit().putInt(Prefs.ONBOARDED, VERSION).apply()
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** The texts slide up after the scene; the scene springs in. */
    private fun enter(p: View?) {
        val page = p as? ViewGroup ?: return
        if (!Prefs.animations(this)) return
        for (i in 0 until page.childCount) {
            val v = page.getChildAt(i)
            v.alpha = 0f
            v.translationY = dpf(24f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 70L).setDuration(460).setInterpolator(Spring(0.35f)).start()
        }
    }

    private fun page(s: Step, last: Boolean): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(110), dp(28), dp(150))
        }
        val stage = FrameLayout(this).apply {
            background = surface(dpf(30f), 0.25f)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(30f))
                }
            }
        }
        s.scene(stage)
        col.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(text(28f, Palette.INK, bold = true, lines = 2).apply {
            text = s.title
            setPadding(0, dp(26), 0, dp(8))
        })
        col.addView(text(15f, Palette.SILVER, lines = 8).apply {
            text = s.body
            setLineSpacing(0f, 1.2f)
        })
        if (s.settings != null) {
            col.addView(text(13f, Palette.INK, bold = true).apply {
                text = s.settings + if (s.page != null) "  ›" else ""
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = round(Color.argb(40, 255, 255, 255), dpf(14f))
                if (s.page != null) setOnClickListener { startActivity(Intent(this@OnboardingActivity, SettingsActivity::class.java).putExtra("page", s.page)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        if (last) col.addView(homeButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        return col
    }

    // ----- default home -----

    private var homeStatus: TextView? = null

    private fun isDefault(): Boolean = try {
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    } catch (_: Exception) {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == packageName
    }

    private fun homeButton(): View {
        val b = text(15f, Palette.INK, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = surface(dpf(24f), 0.2f)
            pressable()
            setOnClickListener { askHome() }
        }
        homeStatus = b
        updateHomeStatus(b)
        return b
    }

    private fun updateHomeStatus(b: TextView) {
        b.text = if (isDefault()) "✓  It's your home screen" else "Make Halo Launcher my home screen"
    }

    private fun askHome() {
        if (isDefault()) return
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 5)
                return
            }
        } catch (_: Exception) {
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        homeStatus?.let { updateHomeStatus(it) }
        // Some phones ignore the request: send people to the right page instead.
        if (requestCode == 5 && !isDefault()) {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    // ----- scenes: small, looping, live -----

    private fun loop(ms: Long, update: (Float) -> Unit) {
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { update(it.animatedValue as Float) }
        }
        animators += a
        a.start()
    }

    private fun tile(c: Context, color: Int = Palette.TILE2, r: Float = c.dpf(16f)): View = View(c).apply { background = c.round(color, r).apply { setStroke(c.dp(1), Color.argb(40, 255, 255, 255)) } }

    private fun sceneWelcome(f: FrameLayout) {
        val logo = ImageView(this).apply { setImageResource(R.drawable.logo_launcher) }
        f.addView(logo, FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER))
        val ring = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dp(2), Color.argb(120, 255, 255, 255))
            }
        }
        f.addView(ring, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        loop(2400) { t ->
            val s = 1f + t * 0.7f
            ring.scaleX = s
            ring.scaleY = s
            ring.alpha = 1f - t
            val b = 1f + 0.04f * Math.sin(t * Math.PI * 2).toFloat()
            logo.scaleX = b
            logo.scaleY = b
        }
    }

    private fun sceneEdit(f: FrameLayout) {
        val grid = FrameLayout(this)
        f.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
        val cells = (0 until 6).map { i -> tile(this, if (i == 4) Palette.GRAPHITE else Palette.TILE2) }
        for (c in cells) grid.addView(c, FrameLayout.LayoutParams(dp(60), dp(60)))
        grid.post {
            val cw = grid.width / 3f
            val ch = grid.height / 2f
            for ((i, c) in cells.withIndex()) {
                c.x = (i % 3) * cw + (cw - dp(60)) / 2f
                c.y = (i / 3) * ch + (ch - dp(60)) / 2f
            }
            val moving = cells[4]
            val sx = moving.x
            val sy = moving.y
            val tx = cells[0].x
            val ty = cells[0].y - ch * 0.35f
            loop(3000) { t ->
                for ((i, c) in cells.withIndex()) if (c !== moving) c.rotation = (if (i % 2 == 0) 1 else -1) * 2.2f * Math.sin(t * Math.PI * 12).toFloat()
                // Lift, glide to a new spot, drop.
                val p = ((t - 0.15f) / 0.6f).coerceIn(0f, 1f)
                val e = (1 - Math.cos(p * Math.PI)).toFloat() / 2f
                moving.x = sx + (tx - sx) * e
                moving.y = sy + (ty - sy) * e
                val lift = if (p in 0.001f..0.999f) 1.12f else 1f
                moving.scaleX = lift
                moving.scaleY = lift
                moving.elevation = if (lift > 1f) dpf(10f) else 0f
            }
        }
    }

    private fun sceneStack(f: FrameLayout) {
        val box = FrameLayout(this).apply { clipChildren = true }
        f.addView(box, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER))
        val names = listOf("Weather", "Battery", "Steps")
        val cards = names.map { n ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = round(Palette.TILE2, dpf(26f)).apply { setStroke(dp(1), Color.argb(40, 255, 255, 255)) }
                addView(text(12f, Palette.SILVER, caps = true).apply { text = n })
                addView(text(40f, Palette.INK).apply {
                    typeface = Fonts.dots(this@OnboardingActivity)
                    text = when (n) {
                        "Weather" -> "27°"
                        "Battery" -> "82%"
                        else -> "6,240"
                    }
                })
            }
        }
        for (c in cards) box.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loop(4500) { t ->
            // Each card rests, then the next springs up from below.
            val pos = t * 3f
            val cur = pos.toInt() % 3
            val frac = pos - pos.toInt()
            val m = ((frac - 0.7f) / 0.3f).coerceIn(0f, 1f)
            val e = Spring(0.3f).getInterpolation(m)
            val h = dpf(170f)
            for ((i, c) in cards.withIndex()) {
                when (i) {
                    cur -> { c.translationY = -h * e; c.alpha = 1f - m * 0.6f }
                    (cur + 1) % 3 -> { c.translationY = h * (1f - e); c.alpha = 1f }
                    else -> { c.translationY = h; c.alpha = 0f }
                }
            }
        }
    }

    private fun sceneGlass(f: FrameLayout) {
        val lens = View(this)
        lens.background = GlassDrawable(lens, dpf(36f), darkness = 0.1f, bendPx = dpf(22f))
        f.addView(lens, FrameLayout.LayoutParams(dp(170), dp(110), Gravity.CENTER))
        loop(5000) { t ->
            lens.translationX = (Math.sin(t * Math.PI * 2) * dpf(50f)).toFloat()
            lens.translationY = (Math.cos(t * Math.PI * 2) * dpf(28f)).toFloat()
        }
    }

    private fun sceneClock(f: FrameLayout) {
        val clock = ClockFace(this)
        f.addView(clock, FrameLayout.LayoutParams(dp(200), dp(170), Gravity.CENTER))
        var i = 0
        loop(1800) { t ->
            if (t < 0.02f) {
                i = (i + 1) % ClockFaces.ALL.size
                f.removeView(f.getChildAt(0))
                f.addView(ClockFace(this, ClockFaces.ALL[i]), 0, FrameLayout.LayoutParams(dp(200), dp(170), Gravity.CENTER))
            }
        }
    }

    private fun sceneGestures(f: FrameLayout) {
        val finger = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.argb(200, 255, 255, 255))
            }
        }
        f.addView(finger, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
        val sheet = tile(this, Palette.TILE, dpf(24f))
        f.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        val label = text(13f, Palette.INK, bold = true).apply { gravity = Gravity.CENTER }
        f.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(18) })
        f.post {
            val h = f.height.toFloat()
            loop(4000) { t ->
                val up = t < 0.5f
                val p = ((if (up) t else t - 0.5f) / 0.5f)
                val e = (1 - Math.cos(p.coerceIn(0f, 0.6f) / 0.6f * Math.PI)).toFloat() / 2f
                label.text = if (up) "Swipe up: all apps" else "Swipe down: notifications"
                finger.translationY = if (up) h * (0.3f - 0.5f * e) else h * (-0.3f + 0.5f * e)
                finger.alpha = if (p > 0.75f) 0f else 1f
                sheet.translationY = if (up) h * (1f - e) else -h * (1f - e)
                sheet.alpha = if (p > 0.85f) 0f else e
            }
        }
    }

    private fun sceneSettings(f: FrameLayout) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val rows = listOf(
            R.drawable.ic_glass to "Theme",
            R.drawable.ic_image to "Background",
            R.drawable.ic_clock to "Clock & date",
            R.drawable.ic_gesture to "Animations",
            R.drawable.ic_apps to "Icons",
            R.drawable.ic_dock to "Dock",
            R.drawable.ic_widgets to "Widgets",
        )
        val views = rows.map { (icon, name) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                addView(ImageView(this@OnboardingActivity).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(Palette.INK)
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = round(Palette.GRAPHITE, dpf(8f))
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
                addView(text(14f, Palette.INK, bold = true).apply { text = name })
            }
        }
        for (v in views) col.addView(v)
        f.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        loop(5600) { t ->
            val on = (t * views.size).toInt().coerceAtMost(views.size - 1)
            for ((i, v) in views.withIndex()) {
                v.background = if (i == on) round(Color.argb(46, 255, 255, 255), dpf(12f)) else null
                v.alpha = if (i == on) 1f else 0.55f
            }
        }
    }

    private fun sceneHome(f: FrameLayout) {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_home)
            imageTintList = ColorStateList.valueOf(Palette.INK)
            setPadding(dp(26), dp(26), dp(26), dp(26))
            background = surface(dpf(34f), 0.2f)
        }
        f.addView(icon, FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER))
        loop(1600) { t ->
            val b = 1f + 0.06f * Math.sin(t * Math.PI * 2).toFloat()
            icon.scaleX = b
            icon.scaleY = b
        }
    }

    companion object {
        /** Bump to show the tour again to everyone after a big update. */
        const val VERSION = 1

        fun due(c: Context) = Prefs.get(c).getInt(Prefs.ONBOARDED, 0) < VERSION
    }
}
