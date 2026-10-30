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
    app: App,
    private val sizeDp: Int,
    private val showLabel: Boolean,
    private val labelColor: Int = Palette.INK,
    private val forceStyle: Int = -1,
    /** Liquid Glass tiles bend the background live (home and dock); elsewhere a frosted tile. */
    private val liveGlass: Boolean = true,
) : View(context) {
    var app: App = app
        private set
    private var tile: android.graphics.drawable.Drawable? = null
    private var sheenTile: LiquidSheen? = null
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

    /** Show another app in this same view (the app list reuses views as you scroll). */
    fun rebind(a: App) {
        if (a.key == app.key && isCurrent()) return
        app = a
        contentDescription = a.label
        result = null
        badge = 0
        reload()
    }

    fun reload() {
        val style = if (forceStyle >= 0) forceStyle else Icons.style(context)
        styleUsed = style
        val forApp = app

        Icons.cachedOrDisk(context, forApp, style, px)?.let { result = it; fade = 1f; invalidate(); return }
        fade = 0f
        invalidate()
        Icons.load(context, forApp, style, px) { r ->
            // Scrolled on to another app meanwhile: this icon isn't ours any more.
            if (app.key != forApp.key) return@load
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
                // Real glass in every theme: on the home screen it bends your
                // background live; in menus it's frosted. Then the light: a
                // bright top edge, a soft sheen across the top, a faint dark
                // bottom edge, and a drop shadow so it floats.
                val t = tile ?: (
                    if (liveGlass) GlassDrawable(this, px * 0.2237f, darkness = 0.02f, bendPx = dpf(8f), owned = true)
                    else LiquidTile(px * 0.2237f)
                    ).also { d -> tile = d }
                dst.round(tileRect)
                val sheen = sheenTile ?: LiquidSheen(px * 0.2237f).also { d -> sheenTile = d }
                sheen.bounds = tileRect
                sheen.alpha = (255 * fade).toInt()
                sheen.drawShadow(canvas)
                t.bounds = tileRect
                t.alpha = (255 * fade).toInt()
                t.draw(canvas)
                sheen.draw(canvas)
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
class BackgroundView(
    context: Context,
    /**
     * A miniature: the whole home-screen background shrunk to fit this view
     * (for the phone previews in Settings), instead of the slice of it that
     * happens to be behind this spot on screen.
     */
    private val miniature: Boolean = false,
) : View(context) {
    private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val m = android.graphics.Matrix()
    private val shade = Paint()
    private val loc = IntArray(2)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shade.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(140, 0, 0, 0)),
            floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private val drawnAt = IntArray(2)
    private val now = IntArray(2)

    /**
     * It shows the part of the picture that's behind it on screen, so when it
     * moves (a page sliding in, scrolling, an animation ending) it must redraw.
     * Without this it kept the spot it had mid-animation: a grey strip down one side.
     */
    private val follow = android.view.ViewTreeObserver.OnPreDrawListener {
        getLocationInWindow(now)
        if (now[0] != drawnAt[0] || now[1] != drawnAt[1]) invalidate()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A miniature doesn't depend on where it is: no redrawing as you scroll.
        if (!miniature) viewTreeObserver.addOnPreDrawListener(follow)
    }

    override fun onDetachedFromWindow() {
        if (!miniature) viewTreeObserver.removeOnPreDrawListener(follow)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = Glass.backdrop
        if (miniature) {
            if (bmp != null && width > 0 && height > 0) {
                // The whole screen's picture, scaled down to this view (cropped to its shape, centred).
                val s = maxOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
                m.setScale(s, s)
                m.postTranslate((width - bmp.width * s) / 2f, (height - bmp.height * s) / 2f)
                canvas.drawBitmap(bmp, m, p)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            return
        }
        getLocationInWindow(loc)
        drawnAt[0] = loc[0]
        drawnAt[1] = loc[1]
        if (bmp != null) canvas.drawBitmap(bmp, -loc[0].toFloat(), -loc[1].toFloat(), p)
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
            // Glass means glass, whatever the theme (the Solid theme used to turn it into a tile).
            else -> GlassDrawable(this, r, 0.22f)
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
    private var widthsBuf = FloatArray(0)

    override fun onDraw(canvas: Canvas) {
        val d = dpf(5f)
        val wide = dpf(16f)
        val gap = dpf(6f)
        if (widthsBuf.size != pages) widthsBuf = FloatArray(pages)
        val widths = widthsBuf
        for (i in 0 until pages) {
            val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            widths[i] = d + (wide - d) * near
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


/** Frosted glass tile for menus and previews (where the background isn't bent live). */
class LiquidTile(private val radius: Float) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var a = 255
    private var shaderFor = 0
    override fun draw(c: Canvas) {
        r.set(bounds)
        if (shaderFor != bounds.height()) {
            shaderFor = bounds.height()
            p.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(92, 255, 255, 255), Color.argb(46, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP)
        }
        p.alpha = a
        c.drawRoundRect(r, radius, radius, p)
    }
    override fun setAlpha(alpha: Int) { a = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * The light on a Liquid Glass icon: a crisp highlight along the top and
 * left edges fading round to nothing, a fainter one bottom right (light
 * passing through), a soft sheen over the top third, and a shadow under
 * the tile. Shaders are made once per size.
 */
class LiquidSheen(private val radius: Float) : android.graphics.drawable.Drawable() {
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var builtFor = -1
    private var a = 255

    private fun build() {
        if (builtFor == bounds.width() * 7 + bounds.height()) return
        builtFor = bounds.width() * 7 + bounds.height()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val l = bounds.left.toFloat()
        val t = bounds.top.toFloat()
        rim.strokeWidth = (w * 0.022f).coerceAtLeast(1.5f)
        rim.shader = android.graphics.LinearGradient(
            l, t, l + w, t + h,
            intArrayOf(Color.argb(230, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(120, 255, 255, 255)),
            floatArrayOf(0f, 0.35f, 0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        gloss.shader = android.graphics.LinearGradient(
            l, t, l, t + h * 0.55f,
            Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255),
            android.graphics.Shader.TileMode.CLAMP,
        )
        shadow.color = Color.argb(70, 0, 0, 0)
        shadow.maskFilter = android.graphics.BlurMaskFilter(w * 0.08f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    fun drawShadow(c: Canvas) {
        build()
        r.set(bounds)
        r.offset(0f, bounds.height() * 0.05f)
        r.inset(bounds.width() * 0.04f, bounds.height() * 0.04f)
        shadow.alpha = (70 * a / 255)
        c.drawRoundRect(r, radius, radius, shadow)
    }

    override fun draw(c: Canvas) {
        build()
        r.set(bounds)
        gloss.alpha = a
        c.drawRoundRect(r, radius, radius, gloss)
        val i = rim.strokeWidth / 2f
        r.inset(i, i)
        rim.alpha = a
        c.drawRoundRect(r, radius - i, radius - i, rim)
    }

    override fun setAlpha(alpha: Int) { a = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
