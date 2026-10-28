package com.lonerxone.halo.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
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
) : View(context) {
    private var result: Icons.Result? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = dpf(11.5f)
        typeface = Fonts.regular(context)
        textAlign = Paint.Align.CENTER
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

    init {
        contentDescription = app.label
        reload()
    }

    fun reload() {
        val style = if (forceStyle >= 0) forceStyle else Icons.style(context)
        Icons.cached(app, style, px)?.let { result = it; invalidate(); return }
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
        result?.let {
            iconPaint.alpha = (255 * fade).toInt()
            dst.set(cx - px / 2f, iconTop, cx + px / 2f, iconTop + px)
            canvas.drawBitmap(it.bitmap, null, dst, iconPaint)
        }
        if (showLabel) canvas.drawText(ellipsize(app.label, width - dp(4)), cx, iconTop + px + dpf(16f), labelPaint)
        if (badge > 0) {
            val label = if (badge > 99) "99+" else badge.toString()
            val h = dpf(18f)
            val w = max(h, badgeText.measureText(label) + dpf(10f))
            val right = cx + px / 2f + dpf(5f)
            pill.set(right - w, iconTop - dpf(6f), right, iconTop - dpf(6f) + h)
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
 * Your picture, fixed behind the pages and filling the top of the screen.
 * With no picture set it stays clear so your phone's wallpaper shows.
 */
class ArtView(context: Context) : View(context) {
    var picture: Bitmap? = null
        set(v) {
            field = v
            shader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    private var shader: BitmapShader? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    /** Small parallax from page swipes (0..1). */
    var drift = 0f
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val bmp = picture ?: return
        val sh = shader ?: return
        val target = height * 0.66f
        val s = max(width * 1.08f / bmp.width, target / bmp.height)
        m.reset()
        m.setScale(s, s)
        val spare = bmp.width * s - width
        m.postTranslate(-spare * (0.5f + (drift - 0.5f) * 0.6f), 0f)
        sh.setLocalMatrix(m)
        p.shader = sh
        canvas.drawRect(0f, 0f, width.toFloat(), bmp.height * s, p)
    }
}

/**
 * A solid black sheet with one smooth curve along its top. Page 1's curve
 * runs high-left to low-right and page 2's low-left to high-right, from the
 * same height, so side by side they make one continuous wave.
 */
class SheetView(context: Context, private val highOnLeft: Boolean) : LinearLayout(context) {
    var curve = dpf(84f)
        set(v) {
            field = v
            invalidate()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val path = Path()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val a = if (highOnLeft) 0f else curve
        val b = if (highOnLeft) curve else 0f
        path.reset()
        path.moveTo(0f, a)
        // Flat at both ends (so the pages meet without a kink), sweeping in the middle.
        path.cubicTo(w * 0.5f, a, w * 0.5f, b, w, b)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        canvas.drawPath(path, fill)
        super.onDraw(canvas)
    }
}

/**
 * The dock: a solid tab rising out of the bottom edge, like a notch turned
 * upside down: rounded on top, flaring into the screen edge with concave
 * curves at its feet.
 */
class DockTab(context: Context) : LinearLayout(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 26) }
    private val path = Path()
    var sideMargin = dpf(18f)

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = sideMargin
        val r = dpf(30f)
        val f = dpf(18f)
        path.reset()
        path.moveTo(m - f, h)
        path.quadTo(m, h, m, h - f)
        path.lineTo(m, r)
        path.quadTo(m, 0f, m + r, 0f)
        path.lineTo(w - m - r, 0f)
        path.quadTo(w - m, 0f, w - m, r)
        path.lineTo(w - m, h - f)
        path.quadTo(w - m, h, w - m + f, h)
        path.close()
        canvas.drawPath(path, fill)
        super.onDraw(canvas)
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
