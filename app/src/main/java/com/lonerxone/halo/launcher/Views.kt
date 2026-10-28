package com.lonerxone.halo.launcher

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
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import java.util.Calendar
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * An app icon (in the chosen style) with an optional label and Halo's unread
 * badge.
 */
@SuppressLint("ViewConstructor")
class AppIconView(context: Context, val app: App, private val sizeDp: Int, private val showLabel: Boolean, private val labelColor: Int = Palette.INK) : View(context) {
    private var result: Icons.Result? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = dpf(11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 69, 58) }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf(10f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val px = dp(sizeDp)
    private val dst = RectF()
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
        val style = Icons.style(context)
        Icons.cached(app, style, px)?.let { result = it; invalidate(); return }
        Icons.load(context, app, style, px) { r ->
            result = r
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = px + if (showLabel) dp(22) else 0
        setMeasuredDimension(resolveSize(px + dp(12), widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        result?.let {
            dst.set(cx - px / 2f, 0f, cx + px / 2f, px.toFloat())
            canvas.drawBitmap(it.bitmap, null, dst, iconPaint)
        }
        if (showLabel) canvas.drawText(ellipsize(app.label, width - dp(4)), cx, px + dpf(16f), labelPaint)
        if (badge > 0) {
            val br = dpf(8.5f)
            val bx = cx + px / 2f - br * 0.4f
            val by = br * 0.5f
            canvas.drawCircle(bx, by, br, badgePaint)
            canvas.drawText(if (badge > 99) "99" else badge.toString(), bx, by + dpf(3.5f), badgeText)
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
 * The big stacked clock: hours over minutes, like a poster. Three looks:
 * outline (grey with a white rim), solid white, and thin.
 */
class StackedClock(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    var style = 0
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val now = Date()
        val h = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH" else "hh", now).toString()
        val m = DateFormat.format("mm", now).toString()
        val lineH = height / 2f
        val size = min(lineH * 1.08f, width / 1.25f)
        fill.textSize = size
        stroke.textSize = size
        fill.typeface = when (style) {
            2 -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
            else -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        stroke.typeface = fill.typeface
        stroke.strokeWidth = dpf(2.2f)
        fill.letterSpacing = -0.02f
        stroke.letterSpacing = -0.02f
        val fm = fill.fontMetrics
        val asc = -fm.ascent * 0.74f
        for ((n, s) in listOf(h, m).withIndex()) {
            val y = n * lineH + (lineH + asc) / 2f
            when (style) {
                0 -> {
                    fill.color = Color.rgb(58, 58, 62)
                    canvas.drawText(s, 0f, y, fill)
                    stroke.color = Color.argb(225, 255, 255, 255)
                    canvas.drawText(s, 0f, y, stroke)
                }
                else -> {
                    fill.color = Palette.INK
                    canvas.drawText(s, 0f, y, fill)
                }
            }
        }
    }
}

/** A rounded progress bar with a label, used by the battery and music widgets. */
class BarView(context: Context) : View(context) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var track = Color.argb(46, 0, 0, 0)
    var bar = Color.BLACK
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

/** A month at a glance, today circled. */
class MonthView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDow = (cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val cols = 7
        val rows = 7 // header + up to 6 weeks
        val cw = width / cols.toFloat()
        val rh = height / rows.toFloat()
        p.textSize = min(rh * 0.6f, dpf(12f))
        val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
        for (c in 0 until cols) {
            val dow = (cal.firstDayOfWeek - 1 + c) % 7 + 1
            p.color = Color.argb(140, 0, 0, 0)
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            canvas.drawText(names[dow].take(1), cw * c + cw / 2f, rh * 0.7f, p)
        }
        for (d in 1..days) {
            val i = firstDow + d - 1
            val c = i % 7
            val r = i / 7 + 1
            val x = cw * c + cw / 2f
            val y = rh * r + rh * 0.7f
            if (d == today) {
                dot.color = Color.BLACK
                canvas.drawCircle(x, y - p.textSize * 0.35f, min(cw, rh) * 0.46f, dot)
                p.color = Color.WHITE
            } else {
                p.color = Color.argb(215, 0, 0, 0)
            }
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText(d.toString(), x, y, p)
        }
    }
}

/**
 * Your picture, fixed behind the pages and centre-cropped to the screen. The
 * black sheets cover the lower part, like the poster-style home screens.
 * With no picture set, it stays clear so your phone's wallpaper shows.
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

    override fun onDraw(canvas: Canvas) {
        val bmp = picture ?: return
        val sh = shader ?: return
        // Fill the top ~60% (what shows above the sheet), anchored to the top.
        val target = height * 0.62f
        val s = max(width.toFloat() / bmp.width, target / bmp.height)
        m.reset()
        m.setScale(s, s)
        m.postTranslate((width - bmp.width * s) / 2f, 0f)
        sh.setLocalMatrix(m)
        p.shader = sh
        canvas.drawRect(0f, 0f, width.toFloat(), bmp.height * s, p)
    }
}

/**
 * A solid black sheet with a soft curved top edge (one side higher than the
 * other), the shape from the poster-style reference. Content sits below the
 * curve.
 */
class SheetView(context: Context, private val highOnLeft: Boolean) : android.widget.LinearLayout(context) {
    /** How far the low side sits below the high side. */
    var curve = dpf(96f)
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
        // One smooth S: flat, then sweeping down (or up), then flat again.
        path.cubicTo(w * 0.52f, a, w * 0.48f, b, w, b)
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
class DockTab(context: Context) : android.widget.LinearLayout(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 30) }
    private val path = Path()
    var sideMargin = dpf(22f)

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
        val f = dpf(20f)
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

/** A thin ring that fills clockwise (battery tile). */
class RingView(context: Context) : View(context) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var color = Palette.INK
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val sw = dpf(7f)
        p.strokeWidth = sw
        r.set(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
        p.color = Color.argb(40, 255, 255, 255)
        canvas.drawArc(r, 0f, 360f, false, p)
        p.color = color
        canvas.drawArc(r, -90f, 360f * fraction, false, p)
    }
}
