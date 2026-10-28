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
 * An app icon with its label and Halo's unread badge. Monochrome glyphs are
 * drawn bare (white line icons); greyscale fallbacks sit in a soft circle so
 * mixed icon styles still look like one set.
 */
@SuppressLint("ViewConstructor")
class AppIconView(context: Context, val app: App, private val sizeDp: Int, private val showLabel: Boolean, private val labelColor: Int = Palette.INK) : View(context) {
    private var result: Icons.Result? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = dpf(11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.INK }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = dpf(10f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val px = dp(sizeDp)
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
        val mono = Prefs.get(context).getBoolean(Prefs.MONO_ICONS, true)
        val size = (px * if (mono) 0.78f else 1f).toInt()
        Icons.cached(app, mono, size)?.let { result = it; invalidate(); return }
        Icons.load(context, app, mono, size) { r ->
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
        val top = 0f
        val r = result
        val mono = Prefs.get(context).getBoolean(Prefs.MONO_ICONS, true)
        if (r != null) {
            if (mono && !r.glyph) canvas.drawCircle(cx, top + px / 2f, px / 2f, bgPaint)
            val inner = if (mono && !r.glyph) px * 0.64f else if (mono) px * 0.78f else px.toFloat()
            val src = r.bitmap
            val dst = RectF(cx - inner / 2f, top + (px - inner) / 2f, cx + inner / 2f, top + (px + inner) / 2f)
            canvas.drawBitmap(src, null, dst, iconPaint)
        }
        if (showLabel) {
            val label = ellipsize(app.label, width - dp(4))
            canvas.drawText(label, cx, top + px + dp(16), labelPaint)
        }
        if (badge > 0) {
            val br = dpf(8.5f)
            val bx = cx + px / 2f - br * 0.5f
            val by = top + br * 0.6f
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

/**
 * Your picture with a soft wave cut along the bottom (the curved block from
 * the references), fading into the black below. Without a picture it shows
 * a quiet graphite gradient with a halo ring.
 */
class HeroView(context: Context) : View(context) {
    var picture: Bitmap? = null
        set(v) {
            field = v
            shader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    private var shader: BitmapShader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val m = Matrix()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = dpf(34f)
        val wf = w.toFloat()
        val hf = h.toFloat()
        path.reset()
        path.moveTo(0f, r)
        path.quadTo(0f, 0f, r, 0f)
        path.lineTo(wf - r, 0f)
        path.quadTo(wf, 0f, wf, r)
        // Right side runs down to ~62%, then a smooth S-curve sweeps to the bottom-left.
        path.lineTo(wf, hf * 0.62f - r)
        path.quadTo(wf, hf * 0.62f, wf - r, hf * 0.62f)
        path.lineTo(wf * 0.62f, hf * 0.62f)
        path.cubicTo(wf * 0.48f, hf * 0.62f, wf * 0.46f, hf, wf * 0.30f, hf)
        path.lineTo(r, hf)
        path.quadTo(0f, hf, 0f, hf - r)
        path.close()
        shade.shader = LinearGradient(0f, 0f, 0f, hf, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.argb(150, 0, 0, 0)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = picture
        val sh = shader
        if (bmp != null && sh != null) {
            val s = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            m.reset()
            m.setScale(s, s)
            m.postTranslate((width - bmp.width * s) / 2f, (height - bmp.height * s) / 2f)
            sh.setLocalMatrix(m)
            paint.shader = sh
        } else {
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(44, 44, 48), Color.rgb(14, 14, 16), Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, paint)
        canvas.drawPath(path, shade)
        if (bmp == null) {
            // A halo ring floating top-right, a nod to the logo.
            ring.strokeWidth = dpf(7f)
            ring.color = Color.argb(60, 255, 255, 255)
            val cx = width * 0.74f
            val cy = height * 0.22f
            canvas.save()
            canvas.rotate(-8f, cx, cy)
            canvas.drawOval(cx - dpf(62f), cy - dpf(16f), cx + dpf(62f), cy + dpf(16f), ring)
            canvas.restore()
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

/** A picture in a capsule (pill) frame, the island's own shape. */
class CapsuleView(context: Context) : View(context) {
    var picture: Bitmap? = null
        set(v) {
            field = v
            shader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    var tilt = -18f
    private var shader: BitmapShader? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.rotate(tilt, width / 2f, height / 2f)
        val inset = dpf(6f)
        r.set(inset, height * 0.16f, width - inset, height * 0.84f)
        val rad = r.height() / 2f
        val bmp = picture
        val sh = shader
        if (bmp != null && sh != null) {
            val s = max(r.width() / bmp.width, r.height() / bmp.height)
            m.reset()
            m.setScale(s, s)
            m.postTranslate(r.centerX() - bmp.width * s / 2f, r.centerY() - bmp.height * s / 2f)
            sh.setLocalMatrix(m)
            p.shader = sh
        } else {
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(70, 70, 76), Color.rgb(24, 24, 26), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(r, rad, rad, p)
        rim.strokeWidth = dpf(2f)
        rim.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(r, rad, rad, rim)
        canvas.restore()
    }
}
