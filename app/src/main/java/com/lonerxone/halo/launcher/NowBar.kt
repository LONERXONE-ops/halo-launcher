package com.lonerxone.halo.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The lock screen's Now Bar, like One UI: a pill at the bottom between the
 * two shortcuts. What's playing comes first (tinted with the album's
 * colour); swipe it sideways to go through your notifications, one per
 * page. Tap a notification to unlock and open it, pull it down to clear it.
 * The little icons above it show where you are; tap one to jump there.
 */
@SuppressLint("ViewConstructor")
class NowBar(
    c: Context,
    private val command: (String, String?) -> Unit,
    private val openNote: (HaloLink.Note) -> Unit,
) : LinearLayout(c) {
    /** The row of small icons above the bar (where you are). */
    private val dots = LinearLayout(c).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val track = Track(c)
    private var music: HaloLink.Music? = null
    private var notes: List<HaloLink.Note> = emptyList()
    private var key = ""
    private var page = 0
    private var pageCount = 0

    // The music page's parts, updated in place.
    private var mTitle: TextView? = null
    private var mPlay: ImageView? = null
    private var mArt: ImageView? = null
    private var mPage: View? = null

    /** True when there's something to show. */
    val showing get() = pageCount > 0

    init {
        orientation = VERTICAL
        clipChildren = false
        addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)).apply { bottomMargin = dp(8) })
        addView(track, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
    }

    fun set(m: HaloLink.Music?, list: List<HaloLink.Note>) {
        val k = (if (m != null) "m" + m.title + m.artist + m.app else "") + "|" + list.joinToString { it.key + it.time }
        music = m
        if (k == key) {
            // Same pages: just the play button and art may have changed.
            bindMusic()
            return
        }
        val wasOnKey = currentKey()
        key = k
        notes = list
        rebuild()
        // Stay on the same page if it's still there.
        val again = keys().indexOf(wasOnKey)
        page = if (again >= 0) again else page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        track.place(page, 0f, animate = false)
        paintDots()
    }

    private fun keys(): List<String> = (if (music != null) listOf("music") else emptyList()) + notes.map { it.key }
    private fun currentKey(): String? = keys().getOrNull(page)

    private fun rebuild() {
        track.removeAllViews()
        dots.removeAllViews()
        mTitle = null
        mPlay = null
        mArt = null
        mPage = null
        music?.let { track.addView(musicPage(it), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        for (n in notes) track.addView(notePage(n), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageCount = track.childCount
        // The icons: the album art first, then each notification's app.
        for ((i, k) in keys().withIndex()) {
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
                }
                clipToOutline = true
                if (k == "music") {
                    val art = music?.art
                    if (art != null) setImageBitmap(art) else {
                        setImageResource(R.drawable.ic_play)
                        imageTintList = ColorStateList.valueOf(Color.WHITE)
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        background = context.round(Color.argb(90, 0, 0, 0), dpf(11f))
                    }
                } else {
                    val app = notes.getOrNull(if (music != null) i - 1 else i)?.app
                    setImageDrawable(try {
                        context.packageManager.getApplicationIcon(app ?: "")
                    } catch (_: Exception) {
                        null
                    })
                }
                setOnClickListener {
                    page = i
                    track.place(page, 0f, animate = true)
                    paintDots()
                    tick()
                }
            }
            dots.addView(icon, LayoutParams(dp(20), dp(20)).apply { if (i > 0) marginStart = dp(8) })
        }
        dots.visibility = if (pageCount > 1) VISIBLE else INVISIBLE
        bindMusic()
    }

    /** The icon of the page you're on is full size and bright; the rest smaller and dimmer. */
    private fun paintDots() {
        for (i in 0 until dots.childCount) {
            val v = dots.getChildAt(i)
            val on = i == page
            v.animate().alpha(if (on) 1f else 0.5f).scaleX(if (on) 1.15f else 0.85f).scaleY(if (on) 1.15f else 0.85f)
                .setDuration(300).setInterpolator(OvershootInterpolator(3f)).start()
        }
    }

    private fun tick() = performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)

    // ----- Pages -----

    private fun pill(colour: Int): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(14), 0)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lift(colour, 0.08f), colour)).apply {
            cornerRadius = dpf(31f)
            setStroke(dp(1), Color.argb(40, 255, 255, 255))
        }
        elevation = dpf(4f)
    }

    private fun musicPage(m: HaloLink.Music): View {
        val p = pill(colourOf(m))
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
            setOnClickListener { command("open_music", null) }
        }
        p.addView(art, LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = context.text(12.5f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        col.addView(title, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun ctl(icon: Int, cmd: String): ImageView = ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setOnClickListener {
                it.bounce()
                tick()
                command(cmd, null)
            }
        }
        controls.addView(ctl(R.drawable.ic_prev, "prev"), LayoutParams(dp(32), dp(32)))
        val play = ctl(R.drawable.ic_pause, "play_pause")
        controls.addView(play, LayoutParams(dp(34), dp(34)).apply {
            marginStart = dp(22)
            marginEnd = dp(22)
        })
        controls.addView(ctl(R.drawable.ic_next, "next"), LayoutParams(dp(32), dp(32)))
        col.addView(controls, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
        p.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mTitle = title
        mPlay = play
        mArt = art
        mPage = p
        return p
    }

    private fun bindMusic() {
        val m = music ?: return
        val t = m.title + if (m.artist.isNotEmpty()) " • " + m.artist else ""
        if (mTitle?.text?.toString() != t) mTitle?.text = t
        mPlay?.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (m.art != null) mArt?.setImageBitmap(m.art) else mArt?.setImageDrawable(try {
            context.packageManager.getApplicationIcon(m.app)
        } catch (_: Exception) {
            null
        })
        (mPage?.background as? GradientDrawable)?.let { g ->
            val c = colourOf(m)
            g.colors = intArrayOf(lift(c, 0.08f), c)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun notePage(n: HaloLink.Note): View {
        val p = pill(Color.argb(150, 24, 24, 28))
        p.addView(ImageView(context).apply {
            setImageDrawable(try {
                context.packageManager.getApplicationIcon(n.app)
            } catch (_: Exception) {
                null
            })
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }, LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(8) })
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(context.text(13f, Color.WHITE, bold = true).apply { text = n.title.ifEmpty { n.appName } })
        col.addView(context.text(12f, Color.argb(215, 255, 255, 255)).apply { text = n.text.ifEmpty { n.appName } })
        p.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        p.tag = n
        return p
    }

    /** The album's colour, deep enough for white text on it. */
    private fun colourOf(m: HaloLink.Music): Int {
        var c = if (m.accent != 0 && m.accent != Color.BLACK) m.accent else m.art?.let { average(it) } ?: Color.rgb(40, 40, 46)
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2].coerceIn(0.22f, 0.42f)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(0.85f)
        c = Color.HSVToColor(hsv)
        return c
    }

    private fun average(b: Bitmap): Int = try {
        val s = Bitmap.createScaledBitmap(b, 6, 6, true)
        var r = 0
        var g = 0
        var bl = 0
        for (y in 0 until 6) for (x in 0 until 6) {
            val p = s.getPixel(x, y)
            r += Color.red(p)
            g += Color.green(p)
            bl += Color.blue(p)
        }
        if (s != b) s.recycle()
        Color.rgb(r / 36, g / 36, bl / 36)
    } catch (_: Throwable) {
        Color.rgb(40, 40, 46)
    }

    private fun lift(c: Int, f: Float) = Color.argb(Color.alpha(c), (Color.red(c) + (255 - Color.red(c)) * f).roundToInt(), (Color.green(c) + (255 - Color.green(c)) * f).roundToInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).roundToInt())

    /**
     * The pages side by side, moving with your finger; let go and it springs
     * to the nearest one (a flick goes one further). Pull a notification down
     * to clear it; tap it to open.
     */
    private inner class Track(c: Context) : FrameLayout(c) {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var x0 = 0f
        private var y0 = 0f
        private var mode = 0 // 0 undecided, 1 sideways, 2 down (clear)
        private var vt: VelocityTracker? = null
        private var anim: ValueAnimator? = null
        private var offset = 0f

        init {
            clipChildren = false
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            layoutPages()
        }

        /** Each page sits one width (plus a gap) from the next; the ones off to the side shrink a little. */
        private fun layoutPages() {
            val w = width + dpf(14f)
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                val d = i - page - offset
                v.translationX = d * w
                val s = 1f - (abs(d).coerceAtMost(1f) * 0.08f)
                v.scaleX = s
                v.scaleY = s
                v.alpha = 1f - abs(d).coerceAtMost(1f) * 0.5f
            }
        }

        fun place(p: Int, from: Float, animate: Boolean) {
            anim?.cancel()
            if (!animate) {
                offset = 0f
                layoutPages()
                return
            }
            offset = from
            anim = ValueAnimator.ofFloat(from, 0f).apply {
                duration = 520
                interpolator = OvershootInterpolator(1.6f)
                addUpdateListener {
                    offset = it.animatedValue as Float
                    layoutPages()
                }
                start()
            }
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX
                    y0 = e.rawY
                    mode = 0
                    vt?.recycle()
                    vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    anim?.cancel()
                    getChildAt(page)?.animate()?.scaleX(0.97f)?.scaleY(0.97f)?.setDuration(90)?.start()
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    decide(e)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
            }
            return mode != 0
        }

        private fun decide(e: MotionEvent) {
            if (mode != 0) return
            val dx = e.rawX - x0
            val dy = e.rawY - y0
            if (abs(dx) > slop && abs(dx) > abs(dy)) {
                mode = 1
                parent?.requestDisallowInterceptTouchEvent(true)
            } else if (dy > slop && dy > abs(dx) && getChildAt(page)?.tag is HaloLink.Note) {
                mode = 2
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX
                    y0 = e.rawY
                    mode = 0
                    getChildAt(page)?.animate()?.scaleX(0.97f)?.scaleY(0.97f)?.setDuration(90)?.start()
                }
                MotionEvent.ACTION_MOVE -> {
                    decide(e)
                    val dx = e.rawX - x0
                    if (mode == 1) {
                        // Past the ends it resists, like a rubber band.
                        var f = -dx / (width + dpf(14f))
                        if ((page == 0 && f < 0) || (page == pageCount - 1 && f > 0)) f *= 0.3f
                        offset = f
                        layoutPages()
                    } else if (mode == 2) {
                        val v = getChildAt(page)
                        val dy = (e.rawY - y0).coerceAtLeast(0f)
                        v?.translationY = dy * 0.8f
                        v?.alpha = (1f - dy / dpf(90f)).coerceIn(0.2f, 1f)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == 0) {
                        // A tap: open the notification (music has its own buttons).
                        (getChildAt(page)?.tag as? HaloLink.Note)?.let { openNote(it) }
                    }
                    release()
                }
                MotionEvent.ACTION_CANCEL -> release()
            }
            return true
        }

        private fun release() {
            val v = getChildAt(page)
            v?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(420)?.setInterpolator(OvershootInterpolator(3f))?.start()
            vt?.computeCurrentVelocity(1000)
            val vx = vt?.xVelocity ?: 0f
            val vy = vt?.yVelocity ?: 0f
            vt?.recycle()
            vt = null
            when (mode) {
                1 -> {
                    var target = page
                    if (offset > 0.35f || vx < -900f) target++
                    if (offset < -0.35f || vx > 900f) target--
                    target = target.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    val from = offset + (page - target)
                    if (target != page) tick()
                    page = target
                    place(page, from, animate = true)
                    paintDots()
                }
                2 -> {
                    val n = v?.tag as? HaloLink.Note
                    if (n != null && v != null && (v.translationY > dpf(46f) || vy > 1100f)) {
                        tick()
                        v.animate().translationY(dpf(120f)).alpha(0f).setDuration(200).withEndAction {
                            command("dismiss_notif", n.key)
                            // Gone: the next page slides in (Halo also sends the new list).
                            set(music, notes.filter { it.key != n.key })
                        }.start()
                    } else {
                        v?.animate()?.translationY(0f)?.alpha(1f)?.setDuration(420)?.setInterpolator(OvershootInterpolator(2.4f))?.start()
                    }
                }
            }
            mode = 0
        }
    }
}
