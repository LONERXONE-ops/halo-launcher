package com.lonerxone.halo.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The welcome tour. Each page shows a little phone (on your own wallpaper,
 * with your own app icons) acting out one idea, with a short line under it.
 * The last page offers to make Halo Launcher your home screen.
 *
 * Shown once to everyone (including people who set up before it existed),
 * always skippable with ✕, and available again from Settings → Take the tour.
 */
class OnboardingActivity : Activity() {

    /** One page's little film: [enter] replays its intro, [loops] run only while it's on screen. */
    private inner class Scene {
        val loops = ArrayList<ValueAnimator>()
        var enter: () -> Unit = {}
    }

    private class Step(val title: String, val body: String, val where: String?, val page: String?, val build: (FrameLayout, Scene) -> Unit)

    private lateinit var pager: HomePager
    private lateinit var next: TextView
    private lateinit var back: ImageView
    private val segments = ArrayList<View>()
    private val scenes = ArrayList<Scene>()
    private val phones = ArrayList<View>()
    private val texts = ArrayList<View>()
    private var apps: List<App> = emptyList()
    private var active = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        Palette.adapt(this)
        if (AppRepo.apps.isEmpty()) AppRepo.load(this)
        apps = AppRepo.apps.filter { it.pkg != packageName }.sortedByDescending { AppRepo.launches(this, it) }.take(16)

        val steps = listOf(
            Step("Welcome to\nHalo Launcher", "A home screen made of glass, built around your wallpaper, and made to work with Halo. Here's how it works.", null, null, ::sceneWelcome),
            Step("Hold to edit", "Long-press the wallpaper and everything wiggles. Drag anything anywhere; hold it at the edge to carry it to the next page.", "Tip: tap the clock while editing to restyle it", null, ::sceneEdit),
            Step("Stacks and folders", "Drop a widget on one of the same size to stack them, then swipe through. Drop an app on an app to make a folder.", "Settings › Widgets", "widgets", ::sceneStack),
            Step("Glass that follows\nyour colours", "Tiles, dock and menus are real glass that bends your wallpaper. Adaptive colour tints them with your wallpaper's shade.", "Settings › Theme", "theme", ::sceneGlass),
            Step("Clocks and icons", "23 clock faces with glass digits. Icons in Normal, Liquid Glass, White line or any icon pack. Halo uses the same icons.", "Settings › Clock & date · Icons", "clock", ::sceneClock),
            Step("Swipe around", "Swipe up for all your apps, with search and a calculator. Swipe down for notifications. Double-tap to lock.", "Settings › Gestures", "gestures", ::sceneGestures),
            Step("What each setting does", "Every settings page also starts with a short note explaining it.", null, null, ::sceneSettings),
            Step("Make it home", "So the home button always brings you here. You can switch back any time in your phone's settings.", null, null, ::sceneHome),
        )

        val root = FrameLayout(this)
        root.addView(BackgroundView(this), match())
        // Darker towards the bottom so the words read on any wallpaper.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(90, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(235, 0, 0, 0)))
        }, match())

        pager = HomePager(this)
        for ((i, s) in steps.withIndex()) pager.addView(page(s, i == steps.lastIndex))
        root.addView(pager, match())

        // Top: progress bars and ✕.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(14), 0)
        }
        val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in steps.indices) {
            val track = FrameLayout(this).apply { background = round(Color.argb(60, 255, 255, 255), dpf(2f)) }
            val fill = View(this).apply {
                background = round(Color.WHITE, dpf(2f))
                pivotX = 0f
                scaleX = if (i == 0) 1f else 0f
            }
            track.addView(fill, match())
            segments += fill
            bars.addView(track, LinearLayout.LayoutParams(0, dp(3), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        topBar.addView(bars, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            rotation = 45f
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = round(Color.argb(70, 255, 255, 255), dpf(20f))
            contentDescription = "Close"
            pressable()
            setOnClickListener { finishTour() }
        }
        topBar.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(14) })
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP))

        // Bottom: back and next.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = round(Color.argb(60, 255, 255, 255), dpf(27f))
            contentDescription = "Back"
            pressable()
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
            setOnClickListener { if (pager.current > 0) pager.snapTo(pager.current - 1) }
        }
        bottom.addView(back, LinearLayout.LayoutParams(dp(54), dp(54)))
        next = text(16f, Color.BLACK, bold = true).apply {
            text = "Get started"
            gravity = Gravity.CENTER
            background = round(Color.WHITE, dpf(27f))
            pressable()
            setOnClickListener { if (pager.current < steps.lastIndex) pager.snapTo(pager.current + 1) else finishTour() }
        }
        bottom.addView(next, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(12) })
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        pager.onScrolled = { f -> scrolled(f) }
        pager.onPageChanged = { p -> show(p, steps.size) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = b.top + dp(6)
            topBar.requestLayout()
            bottom.setPadding(dp(24), 0, dp(24), b.bottom + dp(18))
            for (i in 0 until pager.childCount) pager.getChildAt(i).setPadding(dp(26), b.top + dp(70), dp(26), b.bottom + dp(100))
            insets
        }
        setContentView(root)
        root.post {
            val m = windowManager.currentWindowMetrics.bounds
            Glass.load(this, m.width(), m.height()) { root.invalidate() }
            show(0, steps.size)
        }
    }

    override fun onResume() {
        super.onResume()
        homeStatus?.let { updateHomeStatus(it) }
        if (active >= 0) scenes.getOrNull(active)?.loops?.forEach { if (it.isPaused) it.resume() }
    }

    override fun onPause() {
        super.onPause()
        for (s in scenes) for (a in s.loops) if (a.isRunning) a.pause()
    }

    override fun onDestroy() {
        for (s in scenes) for (a in s.loops) a.cancel()
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
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ----- pages -----

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun page(s: Step, last: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stage = FrameLayout(this).apply { clipChildren = false }
        val phone = Phone(this)
        stage.addView(phone, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        col.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        phones += phone
        val scene = Scene()
        scenes += scene
        // Build once the little screen has a size.
        phone.screen.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (r - l <= 0) return
                v.removeOnLayoutChangeListener(this)
                v.post {
                    s.build(phone.screen, scene)
                    if (scenes.indexOf(scene) == active) {
                        scene.enter()
                        for (a in scene.loops) a.start()
                    }
                }
            }
        })

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, 0)
        }
        words.addView(text(28f, Color.WHITE, bold = true, lines = 2).apply {
            text = s.title
            setLineSpacing(0f, 1.05f)
        })
        words.addView(text(15f, Color.argb(205, 255, 255, 255), lines = 5).apply {
            text = s.body
            setLineSpacing(0f, 1.22f)
            setPadding(0, dp(10), 0, 0)
        })
        if (s.where != null) {
            words.addView(text(12.5f, Color.WHITE, bold = true).apply {
                text = s.where + if (s.page != null) "  ›" else ""
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = round(Color.argb(46, 255, 255, 255), dpf(14f))
                if (s.page != null) {
                    pressable()
                    setOnClickListener { startActivity(Intent(this@OnboardingActivity, SettingsActivity::class.java).putExtra("page", s.page)) }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        if (last) words.addView(homeButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(16) })
        col.addView(words)
        texts += words
        return col
    }

    /** Parallax: the phone drifts slower than the words, and both fade at the edges. */
    private fun scrolled(f: Float) {
        for ((i, fill) in segments.withIndex()) fill.scaleX = (f + 1f - i).coerceIn(0f, 1f)
        val w = pager.width.toFloat()
        for (i in phones.indices) {
            val o = i - f
            val a = (1f - abs(o) * 1.2f).coerceIn(0f, 1f)
            phones[i].translationX = -o * w * 0.45f
            phones[i].alpha = a
            val s = 1f - abs(o).coerceAtMost(1f) * 0.12f
            phones[i].scaleX = s
            phones[i].scaleY = s
            texts[i].alpha = a
            texts[i].translationX = -o * w * 0.1f
        }
        val b = f.coerceIn(0f, 1f)
        back.alpha = b
        back.scaleX = 0.6f + 0.4f * b
        back.scaleY = back.scaleX
    }

    private fun show(p: Int, count: Int) {
        if (p == active) return
        active = p
        for ((i, s) in scenes.withIndex()) {
            if (i == p) {
                s.enter()
                for (a in s.loops) if (a.isPaused) a.resume() else if (!a.isStarted) a.start()
            } else {
                for (a in s.loops) if (a.isRunning) a.pause()
            }
        }
        val label = when (p) {
            0 -> "Get started"
            count - 1 -> "Done"
            else -> "Next"
        }
        if (next.text != label) {
            next.animate().alpha(0f).setDuration(90).withEndAction {
                next.text = label
                next.animate().alpha(1f).setDuration(160).start()
            }.start()
        }
        // The words of the new page slide up.
        (texts.getOrNull(p) as? ViewGroup)?.let { w ->
            for (i in 0 until w.childCount) {
                val v = w.getChildAt(i)
                v.translationY = dpf(18f)
                v.alpha = 0f
                v.animate().translationY(0f).alpha(1f).setStartDelay(60L + i * 60L).setDuration(520).setInterpolator(Spring(0.3f)).start()
            }
        }
    }

    // ----- default home -----

    private var homeStatus: TextView? = null

    private fun isDefault(): Boolean = try {
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    } catch (_: Exception) {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == packageName
    }

    private fun homeButton(): View {
        val b = text(15f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = round(Color.argb(56, 255, 255, 255), dpf(27f)).apply { setStroke(dp(1), Color.argb(70, 255, 255, 255)) }
            pressable()
            setOnClickListener { askHome() }
        }
        homeStatus = b
        updateHomeStatus(b)
        return b
    }

    private fun updateHomeStatus(b: TextView) {
        b.text = if (isDefault()) "✓  Halo Launcher is your home screen" else "Make Halo Launcher my home screen"
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
        openHomeSettings()
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        homeStatus?.let { updateHomeStatus(it) }
        // Some phones ignore the request: send people to the right page instead.
        if (requestCode == 5 && resultCode != RESULT_OK && !isDefault()) openHomeSettings()
    }

    // ----- the little phone -----

    /** A phone outline showing your wallpaper through it, with a tiny island on top. */
    private inner class Phone(c: Context) : FrameLayout(c) {
        val screen = FrameLayout(c).apply { clipChildren = false }
        private val r = dpf(30f)

        init {
            background = round(Color.argb(60, 0, 0, 0), r).apply { setStroke(dp(2), Color.argb(110, 255, 255, 255)) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, r)
                }
            }
            clipToOutline = true
            elevation = dpf(16f)
            addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(10), dp(26), dp(10), dp(12)) })
            // Halo's island.
            addView(View(c).apply { background = round(Color.BLACK, dpf(6f)) }, FrameLayout.LayoutParams(dp(54), dp(13), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val aw = MeasureSpec.getSize(widthMeasureSpec)
            val ah = MeasureSpec.getSize(heightMeasureSpec)
            var h = ah.toFloat()
            var w = h * 0.5f
            if (w > aw * 0.72f) {
                w = aw * 0.72f
                h = w / 0.5f
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.toInt(), MeasureSpec.EXACTLY))
        }
    }

    // ----- helpers for the scenes -----

    private fun loop(sc: Scene, ms: Long, update: (Float) -> Unit) {
        sc.loops += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { update(it.animatedValue as Float) }
        }
    }

    /** 0..1 progress of t between a and b. */
    private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    private val springs = HashMap<Float, Spring>()
    private fun spring(x: Float, bounce: Float = 0.35f) = springs.getOrPut(bounce) { Spring(bounce) }.getInterpolation(x)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** An app icon (yours, in your style) or a plain tile if the list isn't ready. */
    private fun icon(i: Int, px: Int, style: Int = -1): View {
        if (apps.isEmpty()) return View(this).apply { background = round(Color.argb(90, 255, 255, 255), px * 0.24f) }
        val sizeDp = (px / resources.displayMetrics.density).toInt().coerceAtLeast(16)
        return AppIconView(this, apps[i % apps.size], sizeDp, showLabel = false, forceStyle = style, liveGlass = Glass.backdrop != null)
    }

    private fun place(parent: FrameLayout, v: View, x: Float, y: Float, w: Int, h: Int) {
        parent.addView(v, FrameLayout.LayoutParams(w, h))
        v.x = x
        v.y = y
    }

    private fun glassTile(host: View, radius: Float, dark: Float = 0.2f) =
        if (Glass.backdrop != null) GlassDrawable(host, radius, darkness = dark, bendPx = dpf(10f)) else round(Color.argb(70, 255, 255, 255), radius).apply { setStroke(dp(1), Color.argb(80, 255, 255, 255)) }

    /** Clock, a grid of icons and a dock: the home screen in small. Returns the grid icons and dock. */
    private fun miniHome(f: FrameLayout, rows: Int = 2, clock: Boolean = true): Pair<List<View>, View> {
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val cell = w / 4f
        val ip = (cell * 0.66f).toInt()
        if (clock) {
            val c = ClockFace(this)
            place(f, c, w * 0.1f, h * 0.04f, (w * 0.8f).toInt(), (h * 0.2f).toInt())
        }
        val icons = ArrayList<View>()
        val top = h * 0.36f
        for (r in 0 until rows) for (k in 0 until 4) {
            val v = icon(r * 4 + k, ip)
            place(f, v, k * cell, top + r * cell * 1.05f, cell.toInt(), cell.toInt())
            icons += v
        }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        place(f, dock, w * 0.04f, h - cell * 1.12f, (w * 0.92f).toInt(), (cell * 1.0f).toInt())
        dock.background = glassTile(dock, cell * 0.36f)
        for (k in 0 until 4) dock.addView(icon(8 + k, (cell * 0.6f).toInt()), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return icons to dock
    }

    private fun riseIn(views: List<View>, delay: Long = 0L) {
        for ((i, v) in views.withIndex()) {
            v.alpha = 0f
            v.translationY = dpf(20f)
            v.scaleX = 0.85f
            v.scaleY = 0.85f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(delay + i * 35L).setDuration(560).setInterpolator(Spring(0.4f)).start()
        }
    }

    private fun finger(f: FrameLayout): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(150, 255, 255, 255))
            setStroke(dp(2), Color.WHITE)
        }
        alpha = 0f
        elevation = dpf(20f)
        f.addView(this, FrameLayout.LayoutParams(dp(26), dp(26)))
    }

    // ----- the scenes -----

    private fun sceneWelcome(f: FrameLayout, sc: Scene) {
        val (icons, dock) = miniHome(f, rows = 2)
        val all = ArrayList<View>()
        for (i in 0 until f.childCount) all += f.getChildAt(i)
        sc.enter = { riseIn(all, 120L) }
        // Icons breathe very gently, one after another.
        loop(sc, 3600) { t ->
            for ((i, v) in icons.withIndex()) {
                val s = 1f + 0.035f * sin((t * 2f * Math.PI - i * 0.5).toFloat())
                v.scaleX = s
                v.scaleY = s
            }
            dock.translationY = dpf(1.5f) * sin((t * 2f * Math.PI).toFloat())
        }
    }

    private fun sceneEdit(f: FrameLayout, sc: Scene) {
        val (icons, _) = miniHome(f, rows = 3, clock = true)
        val w = f.width.toFloat()
        val cell = w / 4f
        val done = text(11f, Color.BLACK, bold = true).apply {
            text = "Done"
            gravity = Gravity.CENTER
            background = round(Color.WHITE, dpf(11f))
        }
        place(f, done, w - dp(58), 0f, dp(52), dp(22))
        val mover = icons[5]
        val sx = mover.x
        val sy = mover.y
        // An empty spot on the last row.
        val gap = icons[11]
        gap.visibility = View.INVISIBLE
        val tx = gap.x
        val ty = gap.y
        val dot = finger(f)
        sc.enter = { riseIn(listOf(done), 200L) }
        loop(sc, 4800) { t ->
            for ((i, v) in icons.withIndex()) if (v !== mover) v.rotation = (if (i % 2 == 0) 1.6f else -1.6f) * sin((t * 26f * Math.PI + i).toFloat())
            // Lift, glide over, drop; rest; and back again.
            val up = smooth(seg(t, 0.08f, 0.16f) - seg(t, 0.42f, 0.5f) + seg(t, 0.58f, 0.66f) - seg(t, 0.92f, 1f))
            val p = smooth(seg(t, 0.16f, 0.42f)) - smooth(seg(t, 0.66f, 0.92f))
            mover.x = lerp(sx, tx, p)
            mover.y = lerp(sy, ty, p)
            val sc2 = 1f + 0.18f * up
            mover.scaleX = sc2
            mover.scaleY = sc2
            mover.rotation = 4f * up * sin((t * 8f * Math.PI).toFloat())
            mover.elevation = dpf(14f) * up
            dot.alpha = up * 0.9f
            dot.x = mover.x + cell / 2f - dp(13)
            dot.y = mover.y + cell / 2f - dp(13)
        }
    }

    private fun sceneStack(f: FrameLayout, sc: Scene) {
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val cell = w / 4f
        // The stack: a 2×2 widget with three faces, swiped through.
        val card = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, cell * 0.3f)
                }
            }
            clipToOutline = true
        }
        val cw = (cell * 2.6f).toInt()
        place(f, card, (w - cw) / 2f, h * 0.06f, cw, cw)
        card.background = glassTile(card, cell * 0.3f)
        val faces = listOf("Weather" to "27°", "Battery" to "82%", "Steps" to "6,240").map { (n, v) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(text(10f, Color.argb(200, 255, 255, 255), bold = true, caps = true).apply {
                    text = n
                    gravity = Gravity.CENTER
                })
                addView(text(30f, Color.WHITE).apply {
                    typeface = Fonts.dots(this@OnboardingActivity)
                    text = v
                    gravity = Gravity.CENTER
                })
            }
        }
        for (fc in faces) card.addView(fc, match())
        val pips = faces.indices.map {
            View(this).apply { background = round(Color.WHITE, dpf(2f)) }.also { p -> card.addView(p, FrameLayout.LayoutParams(dp(4), dp(10), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8); topMargin = (it - 1) * dp(14) }) }
        }
        // The folder: one app dropped onto another.
        val ip = (cell * 0.72f).toInt()
        val a = icon(0, ip)
        val b = icon(1, ip)
        val folder = FrameLayout(this)
        val fy = h * 0.66f
        val ax = w * 0.26f - cell / 2f
        val bx = w * 0.74f - cell / 2f
        place(f, folder, w / 2f - cell * 0.5f, fy, cell.toInt(), cell.toInt())
        folder.background = glassTile(folder, cell * 0.26f)
        val minis = (0 until 4).map { k -> icon(k, (cell * 0.3f).toInt()) }
        for ((k, m) in minis.withIndex()) {
            folder.addView(m, FrameLayout.LayoutParams((cell * 0.42f).toInt(), (cell * 0.42f).toInt()).apply {
                leftMargin = (cell * (0.08f + 0.42f * (k % 2))).toInt()
                topMargin = (cell * (0.08f + 0.42f * (k / 2))).toInt()
            })
        }
        place(f, a, ax, fy, cell.toInt(), cell.toInt())
        place(f, b, bx, fy, cell.toInt(), cell.toInt())
        val dot = finger(f)
        loop(sc, 6000) { t ->
            // Stack: rest, swipe up to the next face (springy), three times a loop.
            val pos = t * 3f
            val cur = pos.toInt() % 3
            val sm = spring(seg(pos - pos.toInt(), 0.62f, 1f), 0.3f)
            val ch = card.height.toFloat()
            for ((i, fc) in faces.withIndex()) {
                when (i) {
                    cur -> { fc.translationY = -ch * sm; fc.alpha = 1f - 0.7f * sm.coerceIn(0f, 1f) }
                    (cur + 1) % 3 -> { fc.translationY = ch * (1f - sm); fc.alpha = 1f }
                    else -> { fc.translationY = ch; fc.alpha = 0f }
                }
            }
            val on = if (sm > 0.5f) (cur + 1) % 3 else cur
            for ((i, p) in pips.withIndex()) p.alpha = if (i == on) 1f else 0.3f

            // Folder: B is carried onto A, they melt into a folder, it rests, then resets.
            val fx = w / 2f - cell / 2f
            val carry = smooth(seg(t, 0.08f, 0.38f))
            val lifted = carry > 0.001f && carry < 0.999f
            val m = smooth(seg(t, 0.42f, 0.55f))
            val r = seg(t, 0.88f, 1f)
            a.x = lerp(ax, fx, m)
            b.x = if (m > 0f) lerp(ax, fx, m) else lerp(bx, ax, carry)
            val hover = if (carry >= 0.8f && m == 0f) 1.12f else 1f
            a.scaleX = hover * (1f - m)
            a.scaleY = a.scaleX
            b.scaleX = (if (lifted) 1.1f else 1f) * (1f - m)
            b.scaleY = b.scaleX
            a.alpha = 1f - m
            b.alpha = 1f - m
            b.elevation = if (lifted) dpf(12f) else 0f
            val fIn = spring(seg(t, 0.46f, 0.72f), 0.45f)
            folder.alpha = seg(t, 0.46f, 0.52f) * (1f - r)
            folder.scaleX = 0.5f + 0.5f * fIn
            folder.scaleY = folder.scaleX
            if (r > 0f) {
                a.x = ax
                b.x = bx
                a.alpha = r
                b.alpha = r
                a.scaleX = r; a.scaleY = r; b.scaleX = r; b.scaleY = r
            }
            dot.alpha = if (lifted) 0.9f else 0f
            dot.x = b.x + cell / 2f - dp(13)
            dot.y = fy + cell / 2f - dp(13)
        }
    }

    private fun sceneGlass(f: FrameLayout, sc: Scene) {
        val (_, dock) = miniHome(f, rows = 1, clock = true)
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val lens = View(this)
        val lw = (w * 0.62f).toInt()
        val lh = (w * 0.34f).toInt()
        place(f, lens, (w - lw) / 2f, h * 0.48f, lw, lh)
        lens.background = glassTile(lens, lh * 0.4f, dark = 0.06f)
        lens.elevation = dpf(8f)
        val colours = intArrayOf(Glass.wallColor(this) ?: Palette.ACCENT, 0xFF4F8CFF.toInt(), 0xFFFF6B9A.toInt(), 0xFF3CCB86.toInt(), 0xFFFFB347.toInt())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dots = colours.map { c ->
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(2), Color.argb(0, 255, 255, 255))
                }
            }.also { row.addView(it, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(5); marginEnd = dp(5) }) }
        }
        place(f, row, 0f, h * 0.36f, w.toInt(), dp(22))
        val dockBase = dock.background
        var shown = -1
        loop(sc, 7000) { t ->
            val a = (t * 2f * Math.PI).toFloat()
            lens.translationX = w * 0.16f * sin(a)
            lens.translationY = h * 0.05f * sin(2f * a)
            val k = (t * colours.size).toInt().coerceAtMost(colours.size - 1)
            if (k != shown) {
                shown = k
                for ((i, d) in dots.withIndex()) {
                    val s = if (i == k) 1.3f else 1f
                    d.animate().scaleX(s).scaleY(s).setDuration(420).setInterpolator(Spring(0.5f)).start()
                    ((d.background as GradientDrawable)).setStroke(dp(2), if (i == k) Color.WHITE else Color.TRANSPARENT)
                }
                // The dock takes the colour (Adaptive colour does this with your wallpaper's shade).
                val c = colours[k]
                dock.background = android.graphics.drawable.LayerDrawable(arrayOf(dockBase, round(Color.argb(110, Color.red(c), Color.green(c), Color.blue(c)), dock.height * 0.36f)))
                lens.invalidate()
            }
        }
        sc.enter = { riseIn(listOf(row, lens), 100L) }
    }

    private fun sceneClock(f: FrameLayout, sc: Scene) {
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val faces = ClockFaces.ALL
        val cw = (w * 0.88f).toInt()
        val chh = (h * 0.3f).toInt()
        fun clock(i: Int): View = ClockFace(this, faces[i % faces.size]).also { place(f, it, w * 0.06f, h * 0.05f, cw, chh) }
        var fi = 0
        var cur = clock(0)
        var incoming = clock(1).apply { alpha = 0f }
        val name = text(11f, Color.WHITE, bold = true, caps = true).apply {
            gravity = Gravity.CENTER
            text = faces[0].name
        }
        place(f, name, 0f, h * 0.37f, w.toInt(), dp(18))
        // The same icons in Normal and in Liquid Glass, flipping between the two.
        val cell = w / 4f
        val ip = (cell * 0.66f).toInt()
        val flips = (0 until 4).map { k ->
            val normal = icon(k, ip, Icons.ORIGINAL)
            val glass = icon(k, ip, Icons.GLASS)
            place(f, normal, k * cell, h * 0.56f, cell.toInt(), cell.toInt())
            place(f, glass, k * cell, h * 0.56f, cell.toInt(), cell.toInt())
            normal to glass
        }
        val style = text(11f, Color.WHITE, bold = true, caps = true).apply {
            gravity = Gravity.CENTER
            text = "Normal"
        }
        place(f, style, 0f, h * 0.56f + cell + dp(4), w.toInt(), dp(18))
        var slot = 0
        loop(sc, 6000) { t ->
            // Clock: every 1.5 s the next face fades in over the last.
            val st = (t * 4f).toInt()
            if (st != slot) {
                slot = st
                f.removeView(cur)
                cur = incoming
                cur.alpha = 1f
                fi = (fi + 1) % faces.size
                incoming = clock(fi + 1).apply { alpha = 0f }
                name.text = faces[fi].name
            }
            val x = smooth(seg(t * 4f - st, 0.7f, 1f))
            incoming.alpha = x
            cur.alpha = 1f - x
            // Icons: Normal → Liquid Glass in the first half, back in the second, rippling left to right.
            val half = t < 0.5f
            val local = (t % 0.5f) * 2f
            for ((k, p) in flips.withIndex()) {
                val q = seg(local, 0.3f + k * 0.06f, 0.45f + k * 0.06f)
                val sx = abs(cos(q * Math.PI.toFloat()))
                val glassNow = if (half) q >= 0.5f else q < 0.5f
                p.first.scaleX = if (glassNow) 0f else sx
                p.second.scaleX = if (glassNow) sx else 0f
            }
            val glassLabel = if (half) local >= 0.4f else local < 0.4f
            val want = if (glassLabel) "Liquid Glass" else "Normal"
            if (style.text != want) style.text = want
        }
        sc.enter = { riseIn(listOf(name, style), 150L) }
    }

    private fun sceneGestures(f: FrameLayout, sc: Scene) {
        val (icons, dock) = miniHome(f, rows = 2)
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val home = ArrayList<View>(icons).apply { add(dock) }
        // All apps sheet.
        val drawer = FrameLayout(this)
        place(f, drawer, 0f, 0f, w.toInt(), h.toInt())
        drawer.background = glassTile(drawer, dpf(18f), dark = 0.5f)
        drawer.elevation = dpf(10f)
        val search = View(this).apply { background = round(Color.argb(60, 255, 255, 255), dpf(10f)) }
        drawer.addView(search, FrameLayout.LayoutParams((w * 0.84f).toInt(), dp(20)).apply { leftMargin = (w * 0.08f).toInt(); topMargin = dp(12) })
        val cell = w / 4f
        for (k in 0 until 16) {
            drawer.addView(icon(k, (cell * 0.6f).toInt()), FrameLayout.LayoutParams(cell.toInt(), cell.toInt()).apply {
                leftMargin = ((k % 4) * cell).toInt()
                topMargin = dp(40) + (k / 4) * cell.toInt()
            })
        }
        // Notifications sheet.
        val notes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        place(f, notes, 0f, 0f, w.toInt(), (h * 0.55f).toInt())
        notes.background = round(Color.argb(225, 14, 14, 16), dpf(18f))
        notes.elevation = dpf(10f)
        for (k in 0 until 3) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = round(Color.argb(40, 255, 255, 255), dpf(12f))
            }
            row.addView(icon(k + 2, dp(20)), LinearLayout.LayoutParams(dp(26), dp(26)))
            val lines = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
            }
            lines.addView(View(this).apply { background = round(Color.argb(200, 255, 255, 255), dpf(3f)) }, LinearLayout.LayoutParams((w * (0.3f + 0.1f * k)).toInt(), dp(6)))
            lines.addView(View(this).apply { background = round(Color.argb(90, 255, 255, 255), dpf(3f)) }, LinearLayout.LayoutParams((w * 0.5f).toInt(), dp(5)).apply { topMargin = dp(5) })
            row.addView(lines)
            notes.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        val caption = text(11f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = round(Color.argb(150, 0, 0, 0), dpf(10f))
            elevation = dpf(12f)
        }
        f.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = (h * 0.28f).toInt() })
        drawer.translationY = h
        notes.translationY = -h
        val dot = finger(f)
        var cap = ""
        loop(sc, 7000) { t ->
            // 0–0.5: swipe up, apps rise; 0.5–1: swipe down, notifications drop.
            val firstHalf = t < 0.5f
            val local = if (firstHalf) t * 2f else (t - 0.5f) * 2f
            val drag = smooth(seg(local, 0.1f, 0.35f))
            val open = spring(seg(local, 0.18f, 0.5f), 0.25f)
            val close = smooth(seg(local, 0.8f, 0.95f))
            val shown = (open - close).coerceIn(-0.1f, 1.1f)
            if (firstHalf) {
                drawer.translationY = h * (1f - shown)
                drawer.alpha = 1f
                notes.translationY = -h
                dot.x = w / 2f - dp(13)
                dot.y = lerp(h * 0.9f, h * 0.45f, drag)
            } else {
                notes.translationY = -notes.height * (1f - shown)
                drawer.translationY = h
                dot.x = w / 2f - dp(13)
                dot.y = lerp(h * 0.05f, h * 0.45f, drag)
            }
            for (v in home) v.alpha = 1f - 0.6f * shown.coerceIn(0f, 1f)
            dot.alpha = if (local in 0.08f..0.38f) 0.9f else 0f
            val c = if (firstHalf) "Swipe up · all apps" else "Swipe down · notifications"
            if (c != cap) {
                cap = c
                caption.text = c
                caption.alpha = 0f
                caption.animate().alpha(1f).setDuration(200).start()
            }
            caption.alpha = (1f - shown.coerceIn(0f, 1f) * 1.4f).coerceIn(0f, 1f)
        }
    }

    private fun sceneSettings(f: FrameLayout, sc: Scene) {
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val rows = listOf(
            Triple(R.drawable.ic_glass, "Theme", "Glass or solid tiles, and Adaptive colour from your wallpaper."),
            Triple(R.drawable.ic_image, "Background", "The picture behind your home and apps: your wallpaper or a photo."),
            Triple(R.drawable.ic_clock, "Clock & date", "Clock face, colour, glass digits, and how the date looks."),
            Triple(R.drawable.ic_gesture, "Animations", "How apps open and close, and how pages turn."),
            Triple(R.drawable.ic_apps, "Icons", "Normal, Liquid Glass, White line or an icon pack. Labels and size."),
            Triple(R.drawable.ic_dock, "Dock", "Which apps sit in the floating bar, and its style."),
            Triple(R.drawable.ic_widgets, "Widgets", "Turn widgets on or off; they appear on your pages."),
            Triple(R.drawable.ic_arrow_down, "Backup", "Your whole setup in one file, for a new phone."),
        )
        val rh = ((h * 0.6f) / rows.size).toInt()
        val hl = View(this).apply { background = round(Color.argb(56, 255, 255, 255), dpf(10f)) }
        place(f, hl, 0f, 0f, w.toInt(), rh)
        val views = rows.mapIndexed { i, (ic, name, _) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                addView(ImageView(this@OnboardingActivity).apply {
                    setImageResource(ic)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    background = round(Color.argb(60, 255, 255, 255), dpf(7f))
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
                addView(text(12.5f, Color.WHITE, bold = true).apply { text = name })
            }.also { place(f, it, 0f, (i * rh).toFloat(), w.toInt(), rh) }
        }
        val explain = text(12f, Color.WHITE, lines = 4).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        place(f, explain, 0f, h * 0.64f, w.toInt(), (h * 0.34f).toInt())
        explain.background = glassTile(explain, dpf(14f), dark = 0.45f)
        var on = -1
        loop(sc, rows.size * 1700L) { t ->
            val k = (t * rows.size).toInt().coerceAtMost(rows.size - 1)
            if (k != on) {
                on = k
                hl.animate().y((k * rh).toFloat()).setDuration(460).setInterpolator(Spring(0.35f)).start()
                for ((i, v) in views.withIndex()) v.animate().alpha(if (i == k) 1f else 0.5f).setDuration(200).start()
                explain.animate().alpha(0f).translationY(dpf(6f)).setDuration(110).withEndAction {
                    explain.text = rows[k].second + "\n" + rows[k].third
                    explain.animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(Spring(0.35f)).start()
                }.start()
            }
        }
        sc.enter = { riseIn(views + explain, 80L) }
    }

    private fun sceneHome(f: FrameLayout, sc: Scene) {
        miniHome(f, rows = 2)
        val all = ArrayList<View>()
        for (i in 0 until f.childCount) all += f.getChildAt(i)
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        // The home handle at the bottom of the phone, glowing.
        val handle = View(this).apply { background = round(Color.WHITE, dpf(3f)) }
        place(f, handle, w * 0.32f, h - dp(5), (w * 0.36f).toInt(), dp(5))
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.WHITE)
            }
        }
        val rs = (w * 0.3f).toInt()
        place(f, ring, (w - rs) / 2f, h - rs / 2f - dp(3), rs, rs)
        sc.enter = { riseIn(all, 100L) }
        loop(sc, 2200) { t ->
            val s = 0.3f + 1.2f * smooth(t)
            ring.scaleX = s
            ring.scaleY = s
            ring.alpha = (1f - t) * 0.8f
            handle.alpha = 0.6f + 0.4f * sin((t * Math.PI).toFloat())
        }
    }

    companion object {
        /** Bump to show the tour again to everyone after a big update. */
        const val VERSION = 2

        fun due(c: Context) = Prefs.get(c).getInt(Prefs.ONBOARDED, 0) < VERSION
    }
}
