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
            iv.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(iv.dpf(36f), iv.dpf(36f), android.graphics.Shader.TileMode.CLAMP))
        } else {
            val small = Bitmap.createScaledBitmap(art, 16, 16, true)
            iv.setImageBitmap(Bitmap.createScaledBitmap(small, 160, 160, true))
        }
        iv.scaleX = 1.25f
        iv.scaleY = 1.25f
    }

    /** A dark wash over the blurred cover, tinted with the song's colour, so white text always reads. */
    fun scrim(accent: Int): GradientDrawable {
        val r = Color.red(accent)
        val g = Color.green(accent)
        val b = Color.blue(accent)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(120, r / 4, g / 4, b / 4), Color.argb(150, r / 6, g / 6, b / 6), Color.argb(200, r / 8, g / 8, b / 8)),
        )
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
                it.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Spring(0.6f)).start()
            }.start()
            command("play_pause")
        }
    }
    private val wave = WaveBar(context)
    private val elapsed = context.text(10.5f, Color.argb(210, 255, 255, 255), bold = true)
    private val total = context.text(10.5f, Color.argb(210, 255, 255, 255), bold = true)
    private var key: String? = null
    private var layoutKind = -1

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
            HaloLink.music?.let { m -> if (m.durationMs > 0) command("seek:" + (m.durationMs * f).toLong()) }
        }
        wave.onScrub = { f ->
            val m = HaloLink.music
            if (f != null && m != null) elapsed.text = MusicArt.fmt((m.durationMs * f).toLong()) else tick()
        }
        bind(null, force = true)
    }

    private fun openPlayer() {
        val m = HaloLink.music
        if (m == null) {
            command("open_music")
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

    private fun build(kind: Int) {
        layoutKind = kind
        while (childCount > 2) removeViewAt(2)
        detach(cover, appIcon, app, queueBtn, title, artist, prev, play, next, wave, elapsed, total)
        val pad = dp(16)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(14), pad, dp(12))
        }
        // App · queue
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(appIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(7) })
        top.addView(app, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(queueBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        // Cover · song
        val song = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title)
        texts.addView(artist.apply { maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        // Time · wave · time
        val prog = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prog.addView(elapsed)
        prog.addView(wave, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        prog.addView(total)
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
        controls.addView(play, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            marginStart = dp(18)
            marginEnd = dp(18)
        })
        controls.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))

        when (kind) {
            0 -> {
                // Short and wide: cover, song and controls in a row, the wave under them.
                col.setPadding(dp(14), dp(12), dp(14), dp(6))
                song.addView(cover, LinearLayout.LayoutParams(dp(50), dp(50)))
                song.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                controls.removeView(prev)
                controls.removeView(next)
                (play.layoutParams as LinearLayout.LayoutParams).apply { width = dp(44); height = dp(44); marginStart = 0; marginEnd = dp(4) }
                controls.addView(next, LinearLayout.LayoutParams(dp(40), dp(40)))
                song.addView(controls)
                col.addView(song)
                col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
                col.addView(prog)
            }
            1 -> {
                col.addView(top)
                song.addView(cover, LinearLayout.LayoutParams(dp(56), dp(56)))
                song.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                col.addView(song, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
                col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
                col.addView(prog)
                col.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            3 -> {
                // Large: a big cover on top.
                col.addView(top)
                val coverBox = object : FrameLayout(context) {
                    override fun onMeasure(wm: Int, hm: Int) {
                        val s = minOf(MeasureSpec.getSize(wm), MeasureSpec.getSize(hm))
                        super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY))
                    }
                }
                coverBox.addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                col.addView(coverBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                })
                col.addView(texts)
                col.addView(prog)
                col.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            else -> {
                // Square: song on top, wave and controls under it.
                col.addView(top)
                song.addView(cover, LinearLayout.LayoutParams(dp(44), dp(44)))
                song.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                col.addView(song, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
                elapsed.visibility = View.GONE
                total.visibility = View.GONE
                col.addView(prog)
                for (v in listOf(prev, next)) (v.layoutParams as LinearLayout.LayoutParams).apply { width = dp(38); height = dp(38) }
                (play.layoutParams as LinearLayout.LayoutParams).apply { width = dp(44); height = dp(44); marginStart = dp(8); marginEnd = dp(8) }
                col.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        title.textSize = if (kind == 3 || kind == 1) 17f else 15f
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bind(HaloLink.music, force = true)
    }

    fun bind(m: HaloLink.Music?, force: Boolean = false) {
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
        val showTimes = hasTime && layoutKind != 2
        for (v in listOf<View>(elapsed, total)) v.visibility = if (showTimes) View.VISIBLE else View.GONE
        tick()
    }

    /** Called every second while playing. */
    fun tick() {
        val m = HaloLink.music ?: return
        if (m.durationMs <= 0) return
        val pos = m.position()
        wave.fraction = pos / m.durationMs.toFloat()
        elapsed.text = MusicArt.fmt(pos)
        total.text = MusicArt.fmt(m.durationMs)
    }
}

/**
 * The full player, popping up over the home screen when you tap the music
 * card: big cover on the blurred backdrop, the wave, the controls, and
 * the songs coming up next (when the music app shares its queue; tap one
 * to play it). Swipe down, tap outside or press back to close.
 */
class MusicSheet(private val anchor: View, private val command: (String) -> Unit) {
    private var root: FrameLayout? = null
    private var sheet: FrameLayout? = null
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

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val host = (anchor.rootView as? ViewGroup) ?: return
        val r = FrameLayout(c).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
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
        val s = FrameLayout(c).apply {
            isClickable = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height + dp(40), dpf(32f))
                }
            }
            clipToOutline = true
            setBackgroundColor(Color.rgb(16, 16, 18))
        }
        back = ImageView(c)
        scrim = View(c)
        s.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        s.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(18))
        }
        val handle = View(c).apply { background = c.round(Color.argb(140, 255, 255, 255), dpf(2f)) }
        col.addView(handle, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })
        // App row with "open the app".
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        app = c.text(11f, Color.WHITE, bold = true, caps = true)
        top.addView(app, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(c.text(12.5f, Color.WHITE, bold = true).apply {
            text = "Open app  ›"
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = c.round(Color.argb(50, 255, 255, 255), dpf(14f))
            pressable()
            setOnClickListener {
                command("open_music")
                dismiss()
            }
        })
        col.addView(top)
        // Cover and song.
        val song = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cover = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(16f))
                }
            }
            clipToOutline = true
            elevation = dpf(10f)
        }
        song.addView(cover, LinearLayout.LayoutParams(dp(96), dp(96)))
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        title = c.text(20f, Color.WHITE, bold = true, lines = 2)
        artist = c.text(14.5f, Color.argb(210, 255, 255, 255))
        texts.addView(title)
        texts.addView(artist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        song.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(song, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        // Wave and times.
        wave = WaveBar(c)
        wave.onSeek = { f -> HaloLink.music?.let { m -> if (m.durationMs > 0) command("seek:" + (m.durationMs * f).toLong()) } }
        wave.onScrub = { f ->
            val m = HaloLink.music
            if (f != null && m != null) elapsed.text = MusicArt.fmt((m.durationMs * f).toLong()) else tick()
        }
        col.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(18) })
        val times = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        elapsed = c.text(11.5f, Color.argb(200, 255, 255, 255), bold = true)
        total = c.text(11.5f, Color.argb(200, 255, 255, 255), bold = true)
        times.addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(total)
        col.addView(times)
        // Controls.
        val controls = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun ctl(res: Int, cmd: String) = ImageView(c).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                it.bounce()
                command(cmd)
            }
        }
        play = ImageView(c).apply {
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener {
                it.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Spring(0.6f)).start()
                }.start()
                command("play_pause")
            }
        }
        controls.addView(ctl(R.drawable.ic_prev, "prev"), LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(play, LinearLayout.LayoutParams(dp(70), dp(70)).apply {
            marginStart = dp(26)
            marginEnd = dp(26)
        })
        controls.addView(ctl(R.drawable.ic_next, "next"), LinearLayout.LayoutParams(dp(56), dp(56)))
        col.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        // Up next.
        col.addView(c.text(11f, Color.argb(190, 255, 255, 255), bold = true, caps = true).apply {
            text = "Up next"
            setPadding(dp(4), dp(18), 0, dp(8))
        })
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val sc = android.widget.ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        val maxList = (c.resources.displayMetrics.heightPixels * 0.3f).toInt()
        col.addView(object : FrameLayout(c) {
            override fun onMeasure(wm: Int, hm: Int) {
                super.onMeasure(wm, MeasureSpec.makeMeasureSpec(maxList, MeasureSpec.AT_MOST))
            }
        }.apply { addView(sc) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        s.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        r.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        host.addView(r, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = r
        sheet = s
        r.requestFocus()
        // Above the navigation bar.
        val nav = host.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
        col.setPadding(dp(22), dp(10), dp(22), dp(18) + nav)

        // Drag down from the top part to close.
        var downY = 0f
        var vt: android.view.VelocityTracker? = null
        val drag = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    vt?.recycle()
                    vt = android.view.VelocityTracker.obtain()
                    vt?.addMovement(e)
                    s.animate().cancel()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - downY
                    s.translationY = if (dy > 0) dy else dy * 0.15f
                    r.alpha = 1f - (dy / s.height.coerceAtLeast(1)).coerceIn(0f, 1f) * 0.6f
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    if (s.translationY > s.height * 0.22f || v > 1200f) {
                        dismiss()
                    } else {
                        s.animate().translationY(0f).setDuration(380).setInterpolator(Spring(0.4f)).start()
                        r.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
            true
        }
        handle.setOnTouchListener(drag)
        top.setOnTouchListener(drag)
        song.setOnTouchListener(drag)

        update()
        HaloLink.addListener(listener)
        ui.post(ticker)
        // In: the sheet springs up, the cover pops.
        s.translationY = c.resources.displayMetrics.heightPixels * 0.6f
        s.animate().translationY(0f).setDuration(480).setInterpolator(Spring(0.3f)).start()
        r.alpha = 0f
        r.animate().alpha(1f).setDuration(200).start()
        cover.scaleX = 0.7f
        cover.scaleY = 0.7f
        cover.animate().scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(560).setInterpolator(Spring(0.45f)).start()
    }

    fun dismiss() {
        val r = root ?: return
        val s = sheet
        root = null
        HaloLink.removeListener(listener)
        ui.removeCallbacks(ticker)
        s?.animate()?.translationY(s.height.toFloat() + dp(40))?.setDuration(220)?.setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))?.start()
        r.animate().alpha(0f).setDuration(220).withEndAction { (r.parent as? ViewGroup)?.removeView(r) }.start()
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
            } else {
                cover.setImageResource(R.drawable.ic_music)
                cover.imageTintList = ColorStateList.valueOf(Color.WHITE)
                cover.scaleType = ImageView.ScaleType.CENTER
                cover.background = c.round(Color.argb(50, 255, 255, 255), dpf(16f))
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
            list.addView(c.text(13.5f, Color.argb(200, 255, 255, 255), lines = 4).apply {
                text = "$name doesn't share its queue with other apps, so the songs coming up can't be shown here. Tap Open app to see them."
                setLineSpacing(0f, 1.15f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = c.round(Color.argb(40, 255, 255, 255), dpf(16f))
            })
            return
        }
        val at = q.indexOfFirst { it.id == HaloLink.queueActive }
        for ((i, t) in q.withIndex()) {
            val now = i == at
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = if (now) c.round(Color.argb(56, 255, 255, 255), dpf(14f)) else null
                alpha = if (at >= 0 && i < at) 0.5f else 1f
                setOnClickListener {
                    it.bounce()
                    command("queue_play:" + t.id)
                }
            }
            row.addView(if (now) ImageView(c).apply {
                setImageResource(R.drawable.ic_music)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            } else c.text(12f, Color.argb(160, 255, 255, 255), bold = true).apply {
                text = (i + 1).toString()
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            val tx = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            tx.addView(c.text(14.5f, Color.WHITE, bold = now).apply { text = t.title })
            if (t.sub.isNotEmpty()) tx.addView(c.text(12f, Color.argb(170, 255, 255, 255)).apply { text = t.sub })
            row.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
            // Rows slide in one after another.
            row.translationY = dpf(14f)
            row.alpha = 0f
            row.animate().translationY(0f).alpha(if (at >= 0 && i < at) 0.5f else 1f).setStartDelay(80L + minOf(i, 12) * 30L).setDuration(360).setInterpolator(Spring(0.35f)).start()
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
        elapsed.text = MusicArt.fmt(pos)
        total.text = MusicArt.fmt(m.durationMs)
    }
}
