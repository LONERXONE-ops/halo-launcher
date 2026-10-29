package com.lonerxone.halo.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.format.DateFormat
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * An app icon (in the chosen style), an optional label, and an iOS-style
 * badge: a red pill on the top-right corner with a thin black rim, never clipped.
 */
@SuppressLint("ViewConstructor")
class AppIconView(
    context: Context,
    val app: App,
    private val sizeDp: Int,
    private val showLabel: Boolean,
    private val labelColor: Int = Palette.INK,
    private val forceStyle: Int = -1,
    /** Liquid Glass tiles bend the background live (home and dock); elsewhere a frosted tile. */
    private val liveGlass: Boolean = true,
) : View(context) {
    private var tile: android.graphics.drawable.Drawable? = null
    private val tileRect = android.graphics.Rect()
    private var result: Icons.Result? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = dpf(11.5f)
        typeface = Fonts.regular(context)
        textAlign = Paint.Align.CENTER
        // Labels sit on your picture, so give them a soft shadow.
        setShadowLayer(dpf(3f), 0f, dpf(1f), Color.argb(150, 0, 0, 0))
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.RED }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(10.5f)
        typeface = Fonts.semibold(context)
        textAlign = Paint.Align.CENTER
    }
    private val px = dp(sizeDp)
    private val iconTop = dpf(7f)
    private val dst = RectF()
    private val pill = RectF()
    private var fade = 1f
    var badge = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private var styleUsed = -1
    private var accentUsed = 0

    init {
        contentDescription = app.label
        reload()
    }

    /** Still drawn in the current icon style (and tint)? */
    fun isCurrent(): Boolean {
        val style = if (forceStyle >= 0) forceStyle else Icons.style(context)
        return style == styleUsed
    }

    fun reload() {
        val style = if (forceStyle >= 0) forceStyle else Icons.style(context)
        styleUsed = style

        Icons.cachedOrDisk(context, app, style, px)?.let { result = it; invalidate(); return }
        fade = 0f
        Icons.load(context, app, style, px) { r ->
            result = r
            // Icons fade in as they finish loading instead of popping.
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                addUpdateListener { fade = it.animatedValue as Float; invalidate() }
            }.start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (iconTop + px).toInt() + if (showLabel) dp(22) else dp(4)
        setMeasuredDimension(resolveSize(px + dp(16), widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        // No name under it: sit in the middle of its space.
        val top = if (!showLabel && height > px) (height - px) / 2f else iconTop
        result?.let {
            iconPaint.alpha = (255 * fade).toInt()
            dst.set(cx - px / 2f, top, cx + px / 2f, top + px)
            if (it.glass) {
                val t = tile ?: (
                    if (liveGlass) GlassDrawable(this, px * 0.2237f, darkness = 0.04f, bendPx = dpf(7f), owned = true)
                    else context.round(Color.argb(40, 255, 255, 255), px * 0.2237f).apply { setStroke(dp(1), Color.argb(60, 255, 255, 255)) }
                    ).also { d -> tile = d }
                dst.round(tileRect)
                t.bounds = tileRect
                t.alpha = (255 * fade).toInt()
                t.draw(canvas)
            }
            canvas.drawBitmap(it.bitmap, null, dst, iconPaint)
        }
        if (showLabel) canvas.drawText(ellipsize(app.label, width - dp(4)), cx, top + px + dpf(16f), labelPaint)
        if (badge > 0) {
            val label = if (badge > 99) "99+" else badge.toString()
            val h = dpf(18f)
            val w = max(h, badgeText.measureText(label) + dpf(10f))
            val right = cx + px / 2f + dpf(5f)
            pill.set(right - w, top - dpf(6f), right, top - dpf(6f) + h)
            val rim = dpf(2f)
            canvas.drawRoundRect(pill.left - rim, pill.top - rim, pill.right + rim, pill.bottom + rim, h / 2f + rim, h / 2f + rim, rimPaint)
            canvas.drawRoundRect(pill, h / 2f, h / 2f, badgePaint)
            canvas.drawText(label, pill.centerX(), pill.centerY() + dpf(3.8f), badgeText)
        }
    }

    private fun ellipsize(s: String, w: Int): String {
        if (labelPaint.measureText(s) <= w) return s
        var e = s
        while (e.length > 1 && labelPaint.measureText("$e…") > w) e = e.dropLast(1)
        return "$e…"
    }
}

/**
 * The big stacked clock, hours over minutes, poster style. Four looks:
 * VARSITY (grey digits with a black and a white outline, like a college
 * jersey), DOTS (dot matrix), BOLD (tall condensed) and THIN. When the
 * minute changes the new digits slide up into place.
 */
class StackedClock(context: Context) : View(context) {
    companion object {
        const val VARSITY = 0
        const val DOTS = 1
        const val BOLD = 2
        const val THIN = 3
        val NAMES = listOf("Varsity", "Dot matrix", "Bold", "Thin")
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    var style = VARSITY
        set(v) {
            field = v
            invalidate()
        }
    private var shown: Pair<String, String>? = null
    private var previous: Pair<String, String>? = null
    private var slide = 1f

    /** Call every minute: animates when the time changed. */
    fun tick() {
        val now = current()
        val old = shown
        if (old == null || !Prefs.animations(context)) {
            shown = now
            invalidate()
            return
        }
        if (old == now) return
        previous = old
        shown = now
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                slide = it.animatedValue as Float
                invalidate()
            }
        }.start()
    }

    private fun current(): Pair<String, String> {
        val d = Date()
        return DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH" else "hh", d).toString() to DateFormat.format("mm", d).toString()
    }

    private fun setup(size: Float) {
        fill.textSize = size
        stroke.textSize = size
        fill.typeface = when (style) {
            DOTS -> Fonts.dots(context)
            BOLD -> Fonts.bold(context)
            THIN -> Fonts.light(context)
            else -> Fonts.varsity(context)
        }
        stroke.typeface = fill.typeface
        fill.letterSpacing = if (style == DOTS) 0f else -0.03f
        stroke.letterSpacing = fill.letterSpacing
    }

    override fun onDraw(canvas: Canvas) {
        val now = shown ?: current().also { shown = it }
        val lineH = height / 2f
        val size = when (style) {
            BOLD -> lineH * 1.12f
            DOTS -> min(lineH * 1.05f, width / 1.35f)
            THIN -> min(lineH * 1.15f, width / 1.15f)
            else -> min(lineH * 1.02f, width / 1.3f)
        }
        setup(size)
        val asc = -fill.fontMetrics.ascent * 0.72f
        for (n in 0..1) {
            val y = n * lineH + (lineH + asc) / 2f
            val newText = if (n == 0) now.first else now.second
            val oldText = previous?.let { if (n == 0) it.first else it.second }
            if (oldText != null && oldText != newText && slide < 1f) {
                // Old digits rise and fade out, new ones rise in.
                canvas.save()
                canvas.clipRect(0f, n * lineH, width.toFloat(), (n + 1) * lineH)
                drawDigits(canvas, oldText, y - slide * lineH * 0.6f, 1f - slide)
                drawDigits(canvas, newText, y + (1f - slide) * lineH * 0.6f, slide)
                canvas.restore()
            } else {
                drawDigits(canvas, newText, y, 1f)
            }
        }
    }

    private fun drawDigits(canvas: Canvas, s: String, y: Float, alpha: Float) {
        val a = (255 * alpha.coerceIn(0f, 1f)).toInt()
        when (style) {
            VARSITY -> {
                stroke.strokeWidth = dpf(6f)
                stroke.color = Color.argb(a, 245, 245, 247)
                canvas.drawText(s, dpf(3f), y, stroke)
                stroke.strokeWidth = dpf(2.6f)
                stroke.color = Color.argb(a, 0, 0, 0)
                canvas.drawText(s, dpf(3f), y, stroke)
                fill.color = Color.argb(a, 142, 142, 147)
                canvas.drawText(s, dpf(3f), y, fill)
            }
            DOTS -> {
                fill.color = Color.argb(a, 245, 245, 247)
                canvas.drawText(s, 0f, y, fill)
            }
            BOLD -> {
                fill.color = Color.argb(a, 245, 245, 247)
                canvas.drawText(s, 0f, y, fill)
            }
            else -> {
                fill.color = Color.argb(a, 245, 245, 247)
                canvas.drawText(s, 0f, y, fill)
            }
        }
    }
}

/**
 * The background behind both pages: your picture (or your wallpaper, when
 * Android lets us read it) drawn exactly where the glass panes sample it,
 * with a soft shade at the top and bottom so white text always reads. With
 * nothing to draw it stays clear and the phone's own wallpaper shows.
 */
class BackgroundView(context: Context) : View(context) {
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint()
    private val loc = IntArray(2)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shade.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(140, 0, 0, 0)),
            floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = Glass.backdrop
        if (bmp != null) {
            getLocationInWindow(loc)
            canvas.drawBitmap(bmp, -loc[0].toFloat(), -loc[1].toFloat(), p)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
    }
}

/**
 * The dock: a floating pane of glass (or a solid tile) holding 4 to 6 apps.
 * Icons grow or shrink so any count fits with even spacing.
 */
class DockBar(context: Context) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        restyle()
    }

    /** One UI 9-style dock styles: glass, solid, clear, or tinted with the wallpaper's colour. */
    fun restyle() {
        val r = dpf(34f)
        background = when (Prefs.get(context).getInt(Prefs.DOCK_STYLE, GLASS).let { if (it == TINTED) GLASS else it }) {
            SOLID -> context.round(Palette.TILE, r)
            CLEAR -> null
            TINTED -> {
                val wc = Glass.wallColor(context) ?: Palette.GRAPHITE
                context.round(Color.argb(150, (Color.red(wc) * 0.45f).toInt(), (Color.green(wc) * 0.45f).toInt(), (Color.blue(wc) * 0.45f).toInt()), r).apply {
                    setStroke(dp(1), Color.argb(50, 255, 255, 255))
                }
            }
            else -> if (Glass.enabled(context)) GlassDrawable(this, r, 0.22f) else context.round(Palette.TILE, r)
        }
    }

    companion object {
        const val GLASS = 0
        const val SOLID = 1
        const val CLEAR = 2
        const val TINTED = 3
        val NAMES = listOf("Glass", "Solid", "Clear")
    }

    /** Icon size for this many apps. */
    fun iconFor(count: Int): Int = when {
        count <= 4 -> 56
        count == 5 -> 52
        else -> 46
    }
}

/** A thin rounded progress bar. */
class BarView(context: Context) : View(context) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var track = Color.argb(46, 255, 255, 255)
    var bar = Palette.INK
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val rad = height / 2f
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        p.color = track
        canvas.drawRoundRect(r, rad, rad, p)
        if (fraction > 0f) {
            r.right = max(height.toFloat(), width * fraction)
            p.color = bar
            canvas.drawRoundRect(r, rad, rad, p)
        }
    }
}

/**
 * A row of dots that fill up (battery, steps), Nothing style. The last lit
 * dot can be an accent, and [pulse] makes it breathe (charging).
 */
class DotsView(context: Context) : View(context) {
    var count = 10
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var on = Palette.INK
    var off = Palette.GRAPHITE
    var accent: Int? = null
    var pulse = false
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val gap = width / count.toFloat()
        val r = min(height / 2f, gap * 0.34f)
        val lit = Math.round(fraction * count)
        val t = (System.currentTimeMillis() % 1400L) / 1400f
        for (i in 0 until count) {
            val last = i == lit - 1
            p.color = when {
                i >= lit -> off
                last && accent != null -> accent!!
                else -> on
            }
            if (last && pulse) p.alpha = (110 + 145 * (0.5f + 0.5f * Math.sin(t * 2 * Math.PI).toFloat())).toInt()
            canvas.drawCircle(gap * i + gap / 2f, height / 2f, r, p)
        }
        if (pulse && isShown) postInvalidateDelayed(50L)
    }
}

/**
 * Page dots that follow your finger: the active dot stretches into a pill and
 * slides smoothly between pages as you swipe.
 */
class PageDots(context: Context) : View(context) {
    var pages = 2
        set(v) {
            field = v
            invalidate()
        }
    var position = 0f
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val d = dpf(5f)
        val wide = dpf(16f)
        val gap = dpf(6f)
        val widths = FloatArray(pages) { i ->
            val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            d + (wide - d) * near
        }
        var x = (width - (widths.sum() + gap * (pages - 1))) / 2f
        val y = height / 2f
        for (i in 0 until pages) {
            val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            p.color = Color.argb((90 + 165 * near).toInt(), 245, 245, 247)
            r.set(x, y - d / 2f, x + widths[i], y + d / 2f)
            canvas.drawRoundRect(r, d / 2f, d / 2f, p)
            x += widths[i] + gap
        }
    }
}
