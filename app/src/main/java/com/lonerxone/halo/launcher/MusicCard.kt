package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The music player, fed by Halo. It lays itself out for its size:
 * - wide (4 cells): big cover on the left, title, artist, controls and
 *   progress beside it;
 * - square (2x2): cover and title on top, controls under them.
 * It sits on the theme's surface (Liquid Glass or solid) with a soft glow
 * of the cover's colour from the left, and song changes cross-fade.
 */
@SuppressLint("ViewConstructor")
class MusicCard(context: Context, private val command: (String) -> Unit) : FrameLayout(context) {
    private val glow = View(context)
    private val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dpf(14f))
            }
        }
        clipToOutline = true
        elevation = dpf(6f)
    }
    private val app = context.text(10f, Palette.SILVER, caps = true)
    private val title = context.text(17f, Palette.INK, bold = true, lines = 2)
    private val artist = context.text(13f, Palette.SILVER)
    private val prev = control(R.drawable.ic_prev, "prev")
    private val next = control(R.drawable.ic_next, "next")
    private val play = ImageView(context).apply {
        imageTintList = ColorStateList.valueOf(Color.BLACK)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.INK)
        }
        setOnClickListener {
            it.bounce()
            command("play_pause")
        }
    }
    private val bar = BarView(context).apply { track = Color.argb(60, 255, 255, 255) }
    private val elapsed = context.text(10.5f, Palette.SILVER, bold = true)
    private val remain = context.text(10.5f, Palette.SILVER, bold = true)
    private var key: String? = null
    private var wide = true

    /** Glass or solid, following the theme. */
    fun restyle() {
        background = surface(dpf(28f), 0.18f)
    }

    init {
        restyle()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dpf(28f))
            }
        }
        clipToOutline = true
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val open = OnClickListener {
            it.bounce()
            command("open_music")
        }
        cover.setOnClickListener(open)
        title.setOnClickListener(open)
        build(true)
        bind(null)
    }

    private fun control(res: Int, cmd: String) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Palette.INK)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        setOnClickListener {
            it.bounce()
            command(cmd)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val isWide = w > h * 1.5f
        if (isWide != wide || childCount < 2) post { build(isWide) }
    }

    private fun detach(vararg v: View) {
        for (x in v) (x.parent as? ViewGroup)?.removeView(x)
    }

    /** Lays the parts out for a wide or a square card. */
    private fun build(isWide: Boolean) {
        wide = isWide
        while (childCount > 1) removeViewAt(1)
        detach(cover, app, title, artist, prev, play, next, bar, elapsed, remain)
        val pad = dp(16)
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(prev, LinearLayout.LayoutParams(dp(38), dp(38)))
        controls.addView(play, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        controls.addView(next, LinearLayout.LayoutParams(dp(38), dp(38)))
        val progress = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progress.addView(elapsed)
        progress.addView(bar, LinearLayout.LayoutParams(0, dp(4), 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        progress.addView(remain)
        if (isWide) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
            }
            // The cover fills the card's height, square.
            val coverBox = object : FrameLayout(context) {
                override fun onMeasure(wm: Int, hm: Int) {
                    val s = MeasureSpec.getSize(hm)
                    super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY))
                }
            }
            coverBox.addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            row.addView(coverBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            col.addView(app)
            col.addView(title.apply { maxLines = 2 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            col.addView(artist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            col.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = -dp(8) })
            col.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(10))
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(cover, LinearLayout.LayoutParams(dp(54), dp(54)))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            texts.addView(title.apply { maxLines = 2 })
            texts.addView(artist)
            top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(top)
            col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            col.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            col.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            title.textSize = 14.5f
            elapsed.visibility = View.GONE
            remain.visibility = View.GONE
        }
        if (isWide) {
            title.textSize = 17f
        }
        key = null
        quiet = true
        bind(HaloLink.music)
        quiet = false
    }

    private var quiet = false

    fun bind(m: HaloLink.Music?) {
        val k = m?.let { "${it.app}|${it.title}|${it.artist}|${it.art != null}" }
        play.setImageResource(if (m?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        if (k == key && key != null) {
            tick()
            return
        }
        key = k
        title.text = m?.title ?: "Nothing playing"
        artist.text = m?.artist?.ifEmpty { null } ?: if (m == null) "Play something and it shows up here" else ""
        app.text = m?.let { appName(it.app) }.orEmpty()
        app.visibility = if (app.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val art = m?.art
        if (art != null) {
            cover.background = null
            cover.imageTintList = null
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageBitmap(art)
        } else {
            cover.background = context.round(Color.argb(50, 255, 255, 255), dpf(14f))
            cover.setImageResource(R.drawable.ic_music)
            cover.imageTintList = ColorStateList.valueOf(Palette.SILVER)
            cover.scaleType = ImageView.ScaleType.CENTER
        }
        // A soft glow of the song's colour from the cover's side.
        val c = m?.accent ?: Color.TRANSPARENT
        glow.background = if (m == null) {
            null
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(120, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(36, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(0, 0, 0, 0)),
            )
        }
        if (Prefs.animations(context) && isAttachedToWindow && !quiet) {
            for (v in listOf(glow, cover, title, artist)) {
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(0).setDuration(380).start()
            }
            cover.scaleX = 0.88f
            cover.scaleY = 0.88f
            cover.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).setDuration(460).start()
        }
        val hasTime = m != null && m.durationMs > 0
        bar.visibility = if (hasTime) View.VISIBLE else View.INVISIBLE
        for (v in listOf<View>(elapsed, remain)) v.visibility = if (hasTime && wide) View.VISIBLE else View.GONE
        tick()
    }

    private fun appName(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        ""
    }

    /** Called every second while playing. */
    fun tick() {
        val m = HaloLink.music ?: return
        if (m.durationMs <= 0) return
        val pos = m.position()
        bar.fraction = pos / m.durationMs.toFloat()
        elapsed.text = fmt(pos)
        remain.text = "-" + fmt(m.durationMs - pos)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
