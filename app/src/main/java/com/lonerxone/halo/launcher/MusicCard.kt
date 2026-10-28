package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
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
 * The music player on the black sheet, fed by Halo. Behind it: the cover,
 * blurred, washed with the cover's own colour fading into black (like the
 * island's player). Song changes cross-fade; play/pause and the progress
 * update in place so nothing flickers.
 */
@SuppressLint("ViewConstructor")
class MusicCard(context: Context, private val command: (String) -> Unit) : FrameLayout(context) {
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val wash = View(context)
    private val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dpf(16f))
            }
        }
        clipToOutline = true
        elevation = dpf(8f)
    }
    private val title = context.text(15.5f, Palette.INK, bold = true)
    private val artist = context.text(12.5f, Palette.SILVER)
    private val play = ImageView(context)
    private val bar = BarView(context).apply { track = Color.argb(60, 255, 255, 255) }
    private val elapsed = context.text(10.5f, Palette.SILVER, bold = true)
    private val remain = context.text(10.5f, Palette.SILVER, bold = true)
    private var key: String? = null

    /** Glass or solid, following the theme. */
    fun restyle() {
        background = surface(dpf(28f), 0.24f)
    }

    init {
        restyle()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dpf(28f))
            }
        }
        clipToOutline = true
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(cover, LinearLayout.LayoutParams(dp(60), dp(60)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }
        texts.addView(title)
        texts.addView(artist.apply { setPadding(0, dp(3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(control(R.drawable.ic_prev, "prev"), LinearLayout.LayoutParams(dp(36), dp(36)))
        play.apply {
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.INK)
            }
            setOnClickListener {
                it.bounce()
                command("play_pause")
            }
        }
        row.addView(play, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        row.addView(control(R.drawable.ic_next, "next"), LinearLayout.LayoutParams(dp(36), dp(36)))
        col.addView(row)
        val prog = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prog.addView(elapsed)
        prog.addView(bar, LinearLayout.LayoutParams(0, dp(3), 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        prog.addView(remain)
        col.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        val open = OnClickListener {
            it.bounce()
            command("open_music")
        }
        cover.setOnClickListener(open)
        texts.setOnClickListener(open)
        bind(null)
    }

    private fun control(res: Int, cmd: String) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Palette.INK)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener {
            it.bounce()
            command(cmd)
        }
    }

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
        val art = m?.art
        if (art != null) {
            cover.background = null
            cover.imageTintList = null
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageBitmap(art)
            backdrop.setImageBitmap(blur(art))
        } else {
            cover.background = context.round(Palette.GRAPHITE, dpf(16f))
            cover.setImageResource(R.drawable.ic_music)
            cover.imageTintList = ColorStateList.valueOf(Palette.GREY)
            cover.scaleType = ImageView.ScaleType.CENTER
            backdrop.setImageDrawable(null)
        }
        val c = m?.accent ?: Palette.GRAPHITE
        wash.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(if (art != null) 120 else 255, Color.red(c) / 3, Color.green(c) / 3, Color.blue(c) / 3),
                Color.argb(if (art != null) 205 else 255, 10, 10, 11),
            ),
        )
        // Cross-fade the new song in.
        if (Prefs.animations(context)) {
            for (v in listOf(backdrop, cover, title, artist)) {
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(0).setDuration(420).start()
            }
            cover.scaleX = 0.86f
            cover.scaleY = 0.86f
            cover.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).setDuration(520).start()
        }
        val hasTime = m != null && m.durationMs > 0
        for (v in listOf<View>(bar, elapsed, remain)) v.visibility = if (hasTime) View.VISIBLE else View.INVISIBLE
        tick()
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

    private fun blur(b: Bitmap): Bitmap? = try {
        val tiny = Bitmap.createScaledBitmap(b, 10, 10, true)
        val mid = Bitmap.createScaledBitmap(tiny, 40, 40, true)
        Bitmap.createScaledBitmap(mid, 160, 160, true)
    } catch (_: Throwable) {
        null
    }
}
