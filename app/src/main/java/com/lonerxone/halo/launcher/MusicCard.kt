package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/** Shared bits for the music card and the full player. */
object MusicArt {
    /** The cover, blurred and enlarged, as a backdrop (live blur on Android 12+, a soft scaled copy before). */
    fun backdrop(iv: ImageView, art: Bitmap?) {
        if (art == null) {
            iv.setImageDrawable(null)
            return
        }
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        if (Build.VERSION.SDK_INT >= 31) {
            iv.setImageBitmap(art)
            // Just softened, so the cover is clearly there (not a blur of colour).
            iv.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(iv.dpf(2.5f), iv.dpf(2.5f), android.graphics.Shader.TileMode.CLAMP))
        } else {
            iv.setImageBitmap(art)
        }
        iv.scaleX = 1.04f
        iv.scaleY = 1.04f
    }

    /**
     * Over the cover: light at the top so the picture shows, deepening to the
     * song's own colour at the bottom where the controls sit (the gradient
     * from the cover), so white text always reads.
     */
    fun scrim(accent: Int): android.graphics.drawable.Drawable {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[1] = hsv[1].coerceIn(0.25f, 0.7f)
        hsv[2] = 0.22f
        val deep = Color.HSVToColor(hsv)
        val r = Color.red(deep)
        val g = Color.green(deep)
        val b = Color.blue(deep)
        val down = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(60, 0, 0, 0), Color.argb(120, r, g, b), Color.argb(225, r, g, b)),
        )
        // And a little darker on the left, behind the song's name.
        val side = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(90, 0, 0, 0), Color.argb(0, 0, 0, 0)),
        )
        return android.graphics.drawable.LayerDrawable(arrayOf(down, side))
    }

    fun appName(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        ""
    }

    fun appIcon(c: Context, pkg: String): android.graphics.drawable.Drawable? = try {
        c.packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * The music player, fed by Halo, in the style of Samsung's media player:
 * the cover blurred behind everything, the app at the top, cover and song,
 * a wave that ripples along the song as it plays (drag it to seek), and
 * the controls. Tap it for the full player with what's coming up next.
 */
@SuppressLint("ViewConstructor")
class MusicCard(context: Context, private val command: (String) -> Unit) : FrameLayout(context) {
    private val back = ImageView(context)
    private val scrim = View(context)
    private val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dpf(12f))
            }
        }
        clipToOutline = true
        elevation = dpf(6f)
    }
    private val appIcon = ImageView(context)
    private val app = context.text(10.5f, Palette.INK, bold = true, caps = true)
    private val queueBtn = ImageView(context).apply {
        setImageResource(R.drawable.ic_queue)
        imageTintList = ColorStateList.valueOf(Palette.INK)
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = context.round(Color.argb(50, 255, 255, 255), dpf(15f))
        contentDescription = "Up next"
        setOnClickListener {
            it.bounce()
            openPlayer()
        }
    }
    private val title = context.text(16f, Palette.INK, bold = true, lines = 1)
    private val artist = context.text(13f, Color.argb(200, 255, 255, 255))
    private val prev = control(R.drawable.ic_prev, "prev")
    private val next = control(R.drawable.ic_next, "next")
    private val play = ImageView(context).apply {
        imageTintList = ColorStateList.valueOf(Color.BLACK)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        setOnClickListener {
            it.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }.start()
            command("play_pause")
        }
    }
    private val wave = WaveBar(context)
    private val elapsed = context.text(10.5f, Color.argb(210, 255, 255, 255), bold = true)
    private val total = context.text(10.5f, Color.argb(210, 255, 255, 255), bold = true)
    private var key: String? = null
    private var layoutKind = -1
    private var cardScrubbing = false

    fun restyle() {
        bind(HaloLink.music, force = true)
    }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dpf(28f))
            }
        }
        clipToOutline = true
        addView(back, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // A light rim, like glass.
        foreground = context.round(Color.TRANSPARENT, dpf(28f)).apply { setStroke(dp(1), Color.argb(46, 255, 255, 255)) }
        setOnClickListener {
            it.bounce()
            openPlayer()
        }
        wave.onSeek = { f ->
            HaloLink.music?.let { m ->
                if (m.durationMs > 0) {
                    val ms = (m.durationMs * f).toLong()
                    HaloLink.localSeek(ms)
                    command("seek:$ms")
                }
            }
        }
        wave.onScrub = { f ->
            cardScrubbing = f != null
            val m = HaloLink.music
            if (f != null && m != null) elapsed.text = MusicArt.fmt((m.durationMs * f).toLong()) else tick()
        }
        bind(null, force = true)
    }

    private fun openPlayer() {
        val m = HaloLink.music
        if (m == null) {
            // The remembered song: open its app (or wake the last player).
            val pkg = HaloLink.lastSong(context)?.app
            val i = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
            if (i != null) context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) else command("open_music")
            return
        }
        MusicSheet(this, command).show()
    }

    private fun control(res: Int, cmd: String) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener {
            it.bounce()
            command(cmd)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val kind = when {
            w > h * 1.5f && h < dp(150) -> 0 // short and wide
            w > h * 1.5f -> 1 // wide
            h > dp(250) -> 3 // large square
            else -> 2 // square
        }
        if (kind != layoutKind) post { build(kind) }
    }

    private fun detach(vararg v: View) {
        for (x in v) (x.parent as? ViewGroup)?.removeView(x)
    }

    /**
     * The cover is the card's background, so there's no small cover here:
     * app on top, the song, the controls and the wave. Every size keeps all
     * of it inside the card.
     */
    private fun build(kind: Int) {
        layoutKind = kind
        while (childCount > 2) removeViewAt(2)
        detach(cover, appIcon, app, queueBtn, title, artist, prev, play, next, wave, elapsed, total)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(8))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(appIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        top.addView(app, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(queueBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title)
        texts.addView(artist.apply { maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
        val prog = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prog.addView(elapsed)
        // A taller touch area than it looks (easier to grab), without taking more room.
        prog.addView(wave, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
            topMargin = -dp(7)
            bottomMargin = -dp(7)
        })
        prog.addView(total)
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val big = kind == 1 || kind == 3
        val ctl = dp(if (big) 42 else 36)
        val pl = dp(if (big) 48 else 40)
        controls.addView(prev, LinearLayout.LayoutParams(ctl, ctl))
        controls.addView(play, LinearLayout.LayoutParams(pl, pl).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        controls.addView(next, LinearLayout.LayoutParams(ctl, ctl))
        play.setPadding(pl / 4, pl / 4, pl / 4, pl / 4)
        for (v in listOf(prev, next)) v.setPadding(ctl / 4, ctl / 4, ctl / 4, ctl / 4)

        when (kind) {
            0, 1 -> {
                // Wide: [app · up next] / [song ........ ◀ ▶ ▶] / [time ~~~~ time]
                if (kind == 1) col.addView(top)
                col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
                val mid = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                mid.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
                mid.addView(controls)
                col.addView(mid)
                col.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            }
            else -> {
                // Square: [app · up next] / song / wave / controls, centred.
                col.setPadding(dp(14), dp(12), dp(14), dp(10))
                col.addView(top)
                col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
                col.addView(texts)
                elapsed.visibility = View.GONE
                total.visibility = View.GONE
                col.addView(prog)
                col.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            }
        }
        title.textSize = if (big) 17f else 15f
        artist.textSize = if (big) 13.5f else 12.5f
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bind(HaloLink.music, force = true)
    }

    fun bind(live: HaloLink.Music?, force: Boolean = false) {
        // Nothing playing: keep showing the last song (paused) instead of going blank.
        val m = live ?: HaloLink.lastSong(context)
        play.setImageResource(if (m?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        wave.playing = m?.playing == true
        val k = m?.let { "${it.app}|${it.title}|${it.artist}|${it.art != null}" }
        if (!force && k == key && key != null) {
            tick()
            return
        }
        val changed = k != key && key != null
        key = k
        title.text = m?.title ?: "Nothing playing"
        artist.text = m?.artist?.ifEmpty { null } ?: if (m == null) "Play something and it shows up here" else ""
        app.text = m?.let { MusicArt.appName(context, it.app) }.orEmpty().ifEmpty { "Music" }
        appIcon.setImageDrawable(m?.let { MusicArt.appIcon(context, it.app) })
        appIcon.visibility = if (m == null) View.GONE else View.VISIBLE
        queueBtn.visibility = if (m == null) View.INVISIBLE else View.VISIBLE
        val art = m?.art
        if (art != null) {
            cover.background = null
            cover.imageTintList = null
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageBitmap(art)
            MusicArt.backdrop(back, art)
            scrim.background = MusicArt.scrim(m.accent)
            background = null
        } else {
            cover.background = context.round(Color.argb(50, 255, 255, 255), dpf(12f))
            cover.setImageResource(R.drawable.ic_music)
            cover.imageTintList = ColorStateList.valueOf(Palette.SILVER)
            cover.scaleType = ImageView.ScaleType.CENTER
            MusicArt.backdrop(back, null)
            scrim.background = null
            background = surface(dpf(28f), 0.25f)
        }
        if (changed && Prefs.animations(context) && isAttachedToWindow) {
            // New song: the cover pops and the backdrop cross-fades.
            back.alpha = 0f
            back.animate().alpha(1f).setDuration(500).start()
            for (v in listOf(title, artist)) {
                v.alpha = 0f
                v.translationX = dpf(12f)
                v.animate().alpha(1f).translationX(0f).setDuration(420).setInterpolator(Spring(0.35f)).start()
            }
            cover.scaleX = 0.85f
            cover.scaleY = 0.85f
            cover.animate().scaleX(1f).scaleY(1f).setInterpolator(Spring(0.45f)).setDuration(520).start()
        }
        val hasTime = m != null && m.durationMs > 0
        wave.visibility = if (hasTime) View.VISIBLE else View.INVISIBLE
        val showTimes = hasTime && (layoutKind == 0 || layoutKind == 1)
        for (v in listOf<View>(elapsed, total)) v.visibility = if (showTimes) View.VISIBLE else View.GONE
        tick()
    }

    /** Called every second while playing. */
    fun tick() {
        val m = HaloLink.music ?: HaloLink.lastSong(context) ?: return
        if (m.durationMs <= 0) return
        val pos = m.position()
        wave.fraction = pos / m.durationMs.toFloat()
        if (!cardScrubbing) elapsed.text = MusicArt.fmt(pos)
        total.text = MusicArt.fmt(m.durationMs)
    }
}

/**
 * The player that drops down from Halo's island when you tap the music card:
 * a compact card at the top (like the reply sheet, not the whole screen)
 * with the cover, the song, the wave (drag to seek), the controls and
 * what's coming up next when the music app shares it. Swipe it up, tap
 * outside or press back and it shrinks back into the island.
 */
class MusicSheet(private val anchor: View, private val command: (String) -> Unit) {
    private var root: FrameLayout? = null
    private var card: FrameLayout? = null
    private lateinit var content: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var back: ImageView
    private lateinit var scrim: View
    private lateinit var title: android.widget.TextView
    private lateinit var artist: android.widget.TextView
    private lateinit var app: android.widget.TextView
    private lateinit var play: ImageView
    private lateinit var wave: WaveBar
    private lateinit var elapsed: android.widget.TextView
    private lateinit var total: android.widget.TextView
    private lateinit var list: LinearLayout
    private var scrubbing = false
    private var key: String? = null
    private var queueKey = ""
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val listener: () -> Unit = { ui.post { update() } }
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (root != null) ui.postDelayed(this, 500L)
        }
    }

    private val c get() = anchor.context
    private fun dp(v: Int) = c.dp(v)
    private fun dpf(v: Float) = c.dpf(v)

    /** A frame as tall as its content (the last child); the backdrop layers just fill it. */
    private class ContentSized(c: Context) : FrameLayout(c) {
        override fun onMeasure(wm: Int, hm: Int) {
            val w = MeasureSpec.getSize(wm)
            val content = getChildAt(childCount - 1) ?: return super.onMeasure(wm, hm)
            content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(hm), MeasureSpec.AT_MOST))
            val h = content.measuredHeight
            for (i in 0 until childCount - 1) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }
    }

    /** 0 = the island's pill, 1 = the whole card. */
    private var reveal = 1f
    private var revealAnim: android.animation.ValueAnimator? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val host = (anchor.rootView as? ViewGroup) ?: return
        val r = FrameLayout(c).apply {
            setBackgroundColor(Color.argb(60, 0, 0, 0))
            isClickable = true
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
            setOnKeyListener { _, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
            elevation = dpf(30f)
        }
        val radius = dpf(32f)
        val k = ContentSized(c).apply {
            isClickable = true
            // The outline grows from the island's pill to the full card (and back).
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    val f = reveal
                    val pw = dpf(124f)
                    val ph = dpf(36f)
                    val l = lerp(v.width / 2f - pw / 2f, 0f, f)
                    val rr = lerp(v.width / 2f + pw / 2f, v.width.toFloat(), f)
                    val b = lerp(ph, v.height.toFloat(), f)
                    o.setRoundRect(l.toInt(), 0, rr.toInt(), b.toInt(), lerp(ph / 2f, radius, f))
                }
            }
            clipToOutline = true
            setBackgroundColor(Color.rgb(14, 14, 16))
            elevation = dpf(16f)
        }
        back = ImageView(c)
        scrim = View(c)
        k.addView(back)
        k.addView(scrim)

        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        content = col
        // Cover · song · open the app
        val song = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cover = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(12f))
                }
            }
            clipToOutline = true
        }
        song.addView(cover, LinearLayout.LayoutParams(dp(50), dp(50)))
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        app = c.text(10f, Color.argb(190, 255, 255, 255), bold = true, caps = true)
        title = c.text(16f, Color.WHITE, bold = true, lines = 1)
        artist = c.text(13f, Color.argb(205, 255, 255, 255))
        texts.addView(app)
        texts.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
        texts.addView(artist)
        song.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        song.addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_arrow_up_right)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = c.round(Color.argb(50, 255, 255, 255), dpf(16f))
            contentDescription = "Open the app"
            setOnClickListener {
                it.bounce()
                command("open_music")
                dismiss()
            }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        col.addView(song)
        // Wave · times
        val prog = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        elapsed = c.text(10.5f, Color.argb(205, 255, 255, 255), bold = true)
        total = c.text(10.5f, Color.argb(205, 255, 255, 255), bold = true)
        wave = WaveBar(c)
        wave.onSeek = { f ->
            HaloLink.music?.let { m ->
                if (m.durationMs > 0) {
                    val ms = (m.durationMs * f).toLong()
                    HaloLink.localSeek(ms)
                    command("seek:$ms")
                }
            }
        }
        wave.onScrub = { f ->
            scrubbing = f != null
            val m = HaloLink.music
            if (f != null && m != null) elapsed.text = MusicArt.fmt((m.durationMs * f).toLong()) else tick()
        }
        prog.addView(elapsed)
        // A taller touch area than it looks (easier to grab), without taking more room.
        prog.addView(wave, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
            topMargin = -dp(7)
            bottomMargin = -dp(7)
        })
        prog.addView(total)
        col.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // Controls
        val controls = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun ctl(res: Int, cmd: String) = ImageView(c).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                it.bounce()
                command(cmd)
            }
        }
        play = ImageView(c).apply {
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener {
                it.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
                }.start()
                command("play_pause")
            }
        }
        controls.addView(ctl(R.drawable.ic_prev, "prev"), LinearLayout.LayoutParams(dp(44), dp(44)))
        controls.addView(play, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
            marginStart = dp(20)
            marginEnd = dp(20)
        })
        controls.addView(ctl(R.drawable.ic_next, "next"), LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(controls)
        // Up next: a few rows in a soft inset panel (scrolls for more).
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val sc = android.widget.ScrollView(c).apply {
            // Fades out at the edges instead of cutting things off.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((32 * resources.displayMetrics.density).toInt())
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        val maxList = dp(156)
        col.addView(object : FrameLayout(c) {
            override fun onMeasure(wm: Int, hm: Int) {
                super.onMeasure(wm, MeasureSpec.makeMeasureSpec(maxList, MeasureSpec.AT_MOST))
            }
        }.apply {
            addView(sc)
            background = c.round(Color.argb(46, 0, 0, 0), dpf(18f))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val handle = View(c).apply { background = c.round(Color.argb(130, 255, 255, 255), dpf(2f)) }
        col.addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        k.addView(col)

        // Right where the island sits, like Halo's own cards.
        r.addView(k, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dp(8)
            leftMargin = dp(10)
            rightMargin = dp(10)
        })
        host.addView(r, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = r
        card = k
        r.requestFocus()

        // Swipe it up to send it back into the island.
        var downY = 0f
        var vt: android.view.VelocityTracker? = null
        val drag = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    vt?.recycle()
                    vt = android.view.VelocityTracker.obtain()
                    vt?.addMovement(e)
                    k.animate().cancel()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - downY
                    k.translationY = if (dy < 0) dy * 0.6f else dy * 0.12f
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    if (k.translationY < -dpf(40f) || v < -1000f) dismiss() else k.animate().translationY(0f).setDuration(380).setInterpolator(Spring(0.4f)).start()
                }
            }
            true
        }
        handle.setOnTouchListener(drag)
        song.setOnTouchListener(drag)

        update()
        HaloLink.addListener(listener)
        ui.post(ticker)
        r.alpha = 0f
        r.animate().alpha(1f).setDuration(240).start()
        reveal = 0f
        content.alpha = 0f
        k.post { animateReveal(k, 1f) }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Grows out of (or back into) the island: the shape morphs, the contents fade and settle. */
    private fun animateReveal(k: View, to: Float, end: (() -> Unit)? = null) {
        revealAnim?.cancel()
        val from = reveal
        val opening = to > from
        if (!Prefs.animations(c)) {
            reveal = to
            k.invalidateOutline()
            content.alpha = if (opening) 1f else 0f
            end?.invoke()
            return
        }
        revealAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = if (opening) 620L else 280L
            interpolator = if (opening) Spring(0.22f) else android.view.animation.PathInterpolator(0.5f, 0f, 0.7f, 1f)
            addUpdateListener {
                reveal = it.animatedValue as Float
                k.invalidateOutline()
                // Contents appear once there's room, and drop the last few px into place.
                val f = ((reveal - 0.35f) / 0.65f).coerceIn(0f, 1f)
                content.alpha = f
                content.translationY = -dpf(12f) * (1f - f)
            }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = end()
            })
            start()
        }
        // The cover pops as the card opens.
        if (opening) {
            cover.scaleX = 0.6f
            cover.scaleY = 0.6f
            cover.animate().scaleX(1f).scaleY(1f).setStartDelay(160).setDuration(560).setInterpolator(Spring(0.45f)).start()
        }
    }

    fun dismiss() {
        val r = root ?: return
        val k = card
        root = null
        HaloLink.removeListener(listener)
        ui.removeCallbacks(ticker)
        r.animate().alpha(0f).setStartDelay(80).setDuration(220).start()
        if (k != null) {
            k.animate().translationY(minOf(k.translationY, 0f) * 0.5f).setDuration(280).start()
            animateReveal(k, 0f) { (r.parent as? ViewGroup)?.removeView(r) }
        } else {
            (r.parent as? ViewGroup)?.removeView(r)
        }
    }

    private fun update() {
        val m = HaloLink.music
        if (m == null) {
            dismiss()
            return
        }
        play.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        wave.playing = m.playing
        val k = "${m.app}|${m.title}|${m.artist}|${m.art != null}"
        if (k != key) {
            val first = key == null
            key = k
            title.text = m.title
            artist.text = m.artist
            app.text = MusicArt.appName(c, m.app).ifEmpty { "Music" }
            if (m.art != null) {
                cover.setImageBitmap(m.art)
                cover.imageTintList = null
                cover.background = null
                cover.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                cover.setImageResource(R.drawable.ic_music)
                cover.imageTintList = ColorStateList.valueOf(Color.WHITE)
                cover.scaleType = ImageView.ScaleType.CENTER
                cover.background = c.round(Color.argb(50, 255, 255, 255), dpf(14f))
            }
            MusicArt.backdrop(back, m.art)
            scrim.background = MusicArt.scrim(m.accent)
            if (!first) {
                cover.scaleX = 0.85f
                cover.scaleY = 0.85f
                cover.animate().scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(Spring(0.45f)).start()
                back.alpha = 0f
                back.animate().alpha(1f).setDuration(500).start()
            }
        }
        val qk = HaloLink.queue.joinToString { "${it.id}" } + "|" + HaloLink.queueActive
        if (qk != queueKey) {
            queueKey = qk
            drawQueue(m)
        }
        tick()
    }

    private fun drawQueue(m: HaloLink.Music) {
        list.removeAllViews()
        val q = HaloLink.queue
        if (q.isEmpty()) {
            val name = MusicArt.appName(c, m.app).ifEmpty { "This app" }
            list.addView(c.text(12f, Color.argb(170, 255, 255, 255), lines = 2).apply {
                text = "$name doesn't share what's up next."
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), 0)
            })
            return
        }
        list.addView(c.text(10.5f, Color.argb(190, 255, 255, 255), bold = true, caps = true).apply {
            text = "Up next"
            setPadding(dp(6), dp(4), 0, dp(6))
        })
        val at = q.indexOfFirst { it.id == HaloLink.queueActive }
        for ((i, t) in q.withIndex()) {
            // The song playing is already at the top of the card.
            if (at >= 0 && i <= at) continue
            val now = i == at
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = if (now) c.round(Color.argb(50, 255, 255, 255), dpf(14f)) else null
                setOnClickListener {
                    it.bounce()
                    command("queue_play:" + t.id)
                }
            }
            row.addView(if (now) ImageView(c).apply {
                setImageResource(R.drawable.ic_music)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            } else c.text(11.5f, Color.argb(160, 255, 255, 255), bold = true).apply {
                text = (i - maxOf(at, 0)).toString()
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            val tx = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            tx.addView(c.text(14f, Color.WHITE, bold = now).apply { text = t.title })
            if (t.sub.isNotEmpty()) tx.addView(c.text(11.5f, Color.argb(170, 255, 255, 255)).apply { text = t.sub })
            row.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
    }

    private fun tick() {
        val m = HaloLink.music ?: return
        if (m.durationMs <= 0) {
            wave.visibility = View.INVISIBLE
            return
        }
        wave.visibility = View.VISIBLE
        val pos = m.position()
        wave.fraction = pos / m.durationMs.toFloat()
        if (!scrubbing) elapsed.text = MusicArt.fmt(pos)
        total.text = MusicArt.fmt(m.durationMs)
    }
}
