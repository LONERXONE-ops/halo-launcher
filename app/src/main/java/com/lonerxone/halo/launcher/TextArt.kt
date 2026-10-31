package com.lonerxone.halo.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * Clock faces: real typefaces (SIL Open Font License, from Google Fonts),
 * each shown with the current time in Settings → Clock.
 */
object ClockFaces {
    class Face(val id: String, val name: String, val font: String, val varsity: Boolean = false, val tracking: Float = 0f)

    val ALL = listOf(
        Face("varsity", "Varsity", "graduate", varsity = true),
        Face("poster", "Poster", "clock_poster"),
        Face("bold", "Condensed", "anton"),
        Face("bebas", "Bebas", "clock_bebas"),
        Face("slim", "Slim", "clock_slim"),
        Face("rounded", "Rounded", "clock_rounded"),
        Face("wide", "Wide", "clock_wide"),
        Face("syne", "Syne", "clock_syne"),
        Face("chunky", "Chunky", "clock_chunky", tracking = -0.04f),
        Face("block", "Block", "clock_block"),
        Face("soft", "Soft serif", "clock_soft"),
        Face("serif", "Serif", "clock_serif"),
        Face("editorial", "Editorial", "clock_editorial"),
        Face("script", "Script", "clock_script"),
        Face("hairline", "Hairline", "clock_hairline"),
        Face("thin", "Thin", "inter_light"),
        Face("majormono", "Mono display", "clock_majormono"),
        Face("mono", "Mono", "clock_mono"),
        Face("tech", "Tech", "clock_tech"),
        Face("retro", "Retro", "clock_retro"),
        Face("neon", "Neon", "clock_neon"),
        Face("dots", "Dot matrix", "doto_black"),
        Face("pixel", "Pixel", "clock_pixel"),
    )

    /** The chosen face (older versions stored 0..3). */
    fun current(c: Context): Face {
        val p = Prefs.get(c)
        val id = p.getString(Prefs.CLOCK_FACE, null) ?: when (p.getInt(Prefs.CLOCK_STYLE, 0)) {
            1 -> "dots"
            2 -> "bold"
            3 -> "thin"
            else -> "varsity"
        }
        return ALL.firstOrNull { it.id == id } ?: ALL[0]
    }

    private val cache = HashMap<String, Typeface>()

    fun typeface(c: Context, f: Face): Typeface = cache.getOrPut(f.font) {
        try {
            Typeface.createFromAsset(c.applicationContext.assets, "fonts/${f.font}.ttf")
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }

    /** Text colour from Settings → Clock. */
    fun colour(c: Context): Int = when (Prefs.get(c).getInt(Prefs.CLOCK_COLOR, 0)) {
        1 -> Glass.accent(c)
        2 -> Color.rgb(200, 200, 206)
        3 -> Color.rgb(18, 18, 20)
        4 -> Color.rgb(255, 69, 58)
        5 -> Color.rgb(255, 204, 92)
        6 -> Color.rgb(100, 170, 255)
        7 -> Color.rgb(255, 130, 180)
        8 -> Color.rgb(90, 220, 140)
        else -> Palette.INK
    }

    val COLOUR_NAMES = listOf("White", "Wallpaper", "Grey", "Black", "Red", "Gold", "Blue", "Pink", "Green")

    /** The minutes' colour when "Two-tone" is on. */
    fun minuteColour(c: Context): Int {
        val main = colour(c)
        return when (Prefs.get(c).getInt(Prefs.CLOCK_TWOTONE, 0)) {
            1 -> Color.argb(150, Color.red(main), Color.green(main), Color.blue(main))
            2 -> Glass.accent(c).let { if (it == main) Palette.INK else it }
            else -> main
        }
    }

    /** Soft shadow under the clock, date and weather (for busy wallpapers). */
    fun shadow(c: Context, p: Paint) {
        if (Prefs.get(c).getBoolean(Prefs.CLOCK_SHADOW, false)) p.setShadowLayer(p.textSize * 0.06f, 0f, p.textSize * 0.025f, Color.argb(150, 0, 0, 0))
    }

    /** Liquid Glass digits: follow the theme, or always / never. */
    fun glass(c: Context): Boolean = when (Prefs.get(c).getInt(Prefs.CLOCK_GLASS, 0)) {
        0 -> Glass.enabled(c)
        // "Plain": never glass. (Glass digits only ever come with the Liquid Glass theme.)
        else -> false
    }
}

/**
 * Big text on the wallpaper (the clock, the date, the weather) that can be
 * drawn as Liquid Glass: the letters become glass that bends the picture
 * behind them, frosted a little, catching the light on their top-left edges,
 * like the iOS 26 lock screen clock. Without a picture to bend (or on
 * Android 12) they're frosted glass: milky, with a bright rim.
 *
 * Subclasses lay out "runs" (text or an icon at a place and size); this
 * class fits, draws and glazes them.
 */
abstract class TextArt(context: Context) : View(context) {
    class Run(val text: String?, val paint: Paint, var x: Float, var y: Float, val icon: Drawable? = null, val iconRect: Rect? = null, val varsity: Boolean = false) {
        // Paints derived from [paint], made once (not on every frame).
        val outline: Paint by lazy { Paint(paint).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND } }
        val grey: Paint by lazy { Paint(paint).apply { color = Color.rgb(205, 205, 210) } }
        val white: Paint by lazy { Paint(paint).apply { color = Color.WHITE; clearShadowLayer() } }
    }

    protected var runs: List<Run> = emptyList()
    private var hard: Bitmap? = null
    private var soft: Bitmap? = null
    private var shader: Any? = null
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2)
    private var glassNow = false
    /** In a Settings preview: frosted glass that doesn't follow the screen (matches the miniature background, light to scroll). */
    var preview = false
    /** In the Theme page's previews: draw as that theme, whatever the phone is using now. */
    var themeGlass: Boolean? = null
    /** The lock screen's own layout: alignment (0 left, 1 centre, 2 right) and one line or two. */
    var alignOverride: Int? = null
    var stackedOverride: Boolean? = null
    private val follow = android.view.ViewTreeObserver.OnPreDrawListener {
        if (glassNow && !preview) {
            val x = loc[0]
            val y = loc[1]
            getLocationInWindow(loc)
            if (x != loc[0] || y != loc[1]) invalidate()
        }
        true
    }

    /** Lay out the runs for this size. */
    protected abstract fun layout(w: Int, h: Int): List<Run>

    /** Redo the layout (text changed, or a setting did). */
    fun refresh() {
        if (width <= 0 || height <= 0) return
        runs = layout(width, height)
        // Settings → Clock → Shadow reaches the date and weather too.
        for (r in runs) ClockFaces.shadow(context, r.paint)
        glassNow = themeGlass?.let { it && Prefs.get(context).getInt(Prefs.CLOCK_GLASS, 0) == 0 } ?: ClockFaces.glass(context)
        buildMasks()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(follow)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(follow)
        super.onDetachedFromWindow()
    }

    private fun drawRuns(c: Canvas, plain: Boolean) {
        for (r in runs) {
            if (r.icon != null && r.iconRect != null) {
                r.icon.bounds = r.iconRect
                r.icon.setTint(if (plain) Color.WHITE else r.paint.color)
                r.icon.draw(c)
                continue
            }
            val t = r.text ?: continue
            if (r.varsity && !plain) {
                // College-jersey digits: grey, a black outline, a white one around that.
                val s = r.paint.textSize
                val o = r.outline
                o.color = Color.WHITE
                o.strokeWidth = s * 0.11f
                c.drawText(t, r.x, r.y, o)
                o.color = Color.BLACK
                o.strokeWidth = s * 0.06f
                c.drawText(t, r.x, r.y, o)
                c.drawText(t, r.x, r.y, r.grey)
                continue
            }
            if (plain) {
                c.drawText(t, r.x, r.y, r.white)
            } else {
                c.drawText(t, r.x, r.y, r.paint)
            }
        }
    }

    // Made once per layout, not on every frame (the clock redraws as pages slide).
    private var hardShader: BitmapShader? = null
    private var softShader: BitmapShader? = null
    private val frostPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowFilter = android.graphics.PorterDuffColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
    private var rimPaints: List<Paint> = emptyList()

    private fun buildMasks() {
        // Not recycled by hand: a frame being drawn may still be using the old
        // ones (recycling them early could crash or flash); they're freed normally.
        hard = null
        soft = null
        shader = null
        hardShader = null
        softShader = null
        rimPaints = runs.map { r ->
            Paint(r.paint).apply {
                clearShadowLayer()
                style = Paint.Style.STROKE
                strokeWidth = max(1.2f, textSize * 0.018f)
                color = Color.argb(220, 255, 255, 255)
            }
        }
        if (!glassNow || width <= 0 || height <= 0) return
        val h = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawRuns(Canvas(h), plain = true)
        hard = h
        // A soft copy gives the letters "thickness": its slope is how the glass bends.
        val scale = 3
        val sw = max(1, width / scale)
        val sh = max(1, height / scale)
        val small = Bitmap.createScaledBitmap(h, sw, sh, true)
        val r = (runs.maxOfOrNull { it.paint.textSize } ?: 40f) / scale * 0.07f
        val sb = blurAlpha(small, r.toInt().coerceIn(1, 14))
        soft = sb
        if (small !== h) small.recycle()
        hardShader = BitmapShader(h, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        softShader = BitmapShader(sb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(width / sb.width.toFloat(), height / sb.height.toFloat()) })
        }
    }

    /** Box blur of the alpha, three passes (close to Gaussian). */
    private fun blurAlpha(b: Bitmap, r: Int): Bitmap {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var a = IntArray(w * h) { px[it] ushr 24 }
        var t = IntArray(w * h)
        val div = 2 * r + 1
        repeat(3) {
            for (y in 0 until h) {
                var sum = 0
                for (i in -r..r) sum += a[y * w + i.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    t[y * w + x] = sum / div
                    sum += a[y * w + (x + r + 1).coerceIn(0, w - 1)] - a[y * w + (x - r).coerceIn(0, w - 1)]
                }
            }
            for (x in 0 until w) {
                var sum = 0
                for (i in -r..r) sum += t[i.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    a[y * w + x] = sum / div
                    sum += t[(y + r + 1).coerceIn(0, h - 1) * w + x] - t[(y - r).coerceIn(0, h - 1) * w + x]
                }
            }
        }
        val out = IntArray(w * h) { (a[it].coerceIn(0, 255) shl 24) or 0xFFFFFF }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        if (!glassNow) {
            drawRuns(canvas, plain = false)
            return
        }
        val hb = hard ?: return
        val sb = soft ?: return
        val back = Glass.clear() ?: Glass.sharp()
        getLocationInWindow(loc)
        val hs = hardShader ?: return
        val ss = softShader ?: return
        // Previews use the real lens too (they sample the picture at their place on screen).
        val rs = if (back != null && Build.VERSION.SDK_INT >= 33 && !broken) {
            (sharedText as? RuntimeShader) ?: try {
                RuntimeShader(GLASS_TEXT).also { sharedText = it }
            } catch (_: Throwable) {
                // A GPU that won't take the shader: frosted text instead, never a crash.
                broken = true
                null
            }
        } else {
            null
        }
        if (rs != null && back != null && Build.VERSION.SDK_INT >= 33) {
            rs.setInputShader("image", back)
            rs.setInputShader("hard", hs)
            rs.setInputShader("soft", ss)
            rs.setFloatUniform("origin", loc[0].toFloat(), loc[1].toFloat())
            val big = runs.maxOfOrNull { it.paint.textSize } ?: 60f
            rs.setFloatUniform("bend", big * 0.32f)
            rs.setFloatUniform("stepPx", max(1.5f, big * 0.02f))
            val hc = Palette.GLASS_TINT
            rs.setFloatUniform("hue", Color.red(hc) / 255f, Color.green(hc) / 255f, Color.blue(hc) / 255f, Palette.GLASS_TINT_AMT)
            val gc = Glass.glintColor
            rs.setFloatUniform("glint", Color.red(gc) / 255f, Color.green(gc) / 255f, Color.blue(gc) / 255f, Glass.shine)
            shaderPaint.shader = rs
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
        } else {
            // Frosted: milky letters with a bright rim and a soft shadow under them.
            val p = frostPaint
            p.alpha = 70
            p.colorFilter = shadowFilter
            canvas.drawBitmap(hb, 0f, dpf(1.5f), p)
            p.colorFilter = null
            p.alpha = 150
            canvas.drawBitmap(hb, 0f, 0f, p)
            for ((i, r) in runs.withIndex()) {
                val t = r.text ?: continue
                canvas.drawText(t, r.x, r.y, rimPaints.getOrNull(i) ?: continue)
            }
        }
    }

    companion object {
        private var broken = false
        /** Compiled once for every clock, date and weather (not per view). */
        private var sharedText: Any? = null

        /**
         * Glass letters. "hard" is where the letters are; "soft" is a blurred
         * copy whose slope says which way each point of the glass faces. The
         * picture behind is sampled through that slope (refraction), split a
         * touch by colour at the rims, frosted slightly, lit from the top-left.
         */
        const val GLASS_TEXT = """
            uniform shader image;
            uniform shader hard;
            uniform shader soft;
            uniform float2 origin;
            uniform float bend;
            uniform float stepPx;
            uniform float4 hue;
            uniform float4 glint;

            half4 main(float2 p) {
                float cov = float(hard.eval(p).a);
                if (cov < 0.004) {
                    // Just outside the letters: a soft glow around them.
                    float halo = float(soft.eval(p).a);
                    float ga = halo * halo * 0.30 * (1.0 + glint.a * 0.6);
                    return half4(half3(ga), half(ga));
                }
                // How thick the glass is here (1 deep inside a letter, 0 at its edge)
                // and which way its surface slopes.
                float h = clamp(float(soft.eval(p).a) * 1.25, 0.0, 1.0);
                float hx = float(soft.eval(p + float2(stepPx, 0.0)).a) - float(soft.eval(p - float2(stepPx, 0.0)).a);
                float hy = float(soft.eval(p + float2(0.0, stepPx)).a) - float(soft.eval(p - float2(0.0, stepPx)).a);
                float2 g = float2(hx, hy);
                float gl = length(g) + 0.0001;
                float2 n = g / gl;
                // The same rounded edge as the glass panes: the bend grows steeply
                // towards the rim (a quarter circle), pulling the picture in round it.
                float x = 1.0 - h;
                float curve = 1.0 - sqrt(max(1.0 - x * x, 0.0));
                float2 off = n * curve * bend;
                float2 q = origin + p + off;
                float3 col = float3(image.eval(q).rgb);
                // Vibrancy, then a milky lift so the time always reads.
                float lum = dot(col, float3(0.213, 0.715, 0.072));
                col = mix(float3(lum), col, 1.45);
                col = mix(col, float3(1.0), 0.20 + 0.12 * (1.0 - h));
                col = mix(col, hue.rgb * 1.5, hue.a * 0.6);
                // A crisp line of light along the edges facing the top left (and,
                // fainter, the bottom right), and a soft glow inside the curve.
                float2 L = normalize(float2(-1.0, -1.0));
                float facing = dot(-n, L);
                float lit = pow(abs(facing), 1.2) * (facing > 0.0 ? 1.0 : 0.5) * clamp(gl * 8.0, 0.0, 1.0);
                float edge = 1.0 - smoothstep(0.0, 0.18, h);
                float glow = 1.0 - smoothstep(0.0, 0.55, h);
                float shine = 1.0 + glint.a * 0.8;
                float3 lc = mix(float3(1.0), glint.rgb, glint.a * 0.5);
                col += lc * (edge * 0.55 + glow * glow * 0.18) * lit * shine;
                // The glow inside the letters: their lit faces shine softly all the way in.
                float spec = pow(max(dot(-n, L), 0.0), 2.5) * clamp(gl * 6.0, 0.0, 1.0);
                col += lc * spec * 0.40 * shine;
                // A soft shade on the far side gives the letters depth.
                col *= 1.0 - 0.12 * glow * clamp(-facing, 0.0, 1.0);
                return half4(half3(clamp(col, 0.0, 1.0) * cov), half(cov));
            }
        """

        /** Fits lines into a box: the largest text size where every line fits across and all fit down. */
        fun fit(paint: Paint, lines: List<String>, w: Float, h: Float, gap: Float): Float {
            val probe = Paint(paint).apply { textSize = 100f }
            val r = Rect()
            var widest = 1f
            var tall = 0f
            for (l in lines) {
                probe.getTextBounds(l, 0, l.length, r)
                widest = max(widest, probe.measureText(l))
                tall += r.height()
            }
            tall += gap * 100f * (lines.size - 1)
            return min(w / widest, h / max(1f, tall)) * 100f
        }
    }
}

/** The time, in your face, layout, colour and alignment (Settings → Clock). */
class ClockFace(context: Context, private val forceFace: ClockFaces.Face? = null) : TextArt(context) {
    private var lastText = ""

    /** Called each minute. */
    fun tick() {
        if (now().first != lastText) refresh()
    }

    /** "hh:mm" and the AM/PM mark ("" in 24-hour time or when hidden). */
    private fun now(): Pair<String, String> {
        val d = Date()
        val p = Prefs.get(context)
        val h24 = when (p.getInt(Prefs.CLOCK_HOURS, 0)) {
            1 -> true
            2 -> false
            else -> DateFormat.is24HourFormat(context)
        }
        val zero = p.getBoolean(Prefs.CLOCK_ZERO, true)
        val h = DateFormat.format(if (h24) (if (zero) "HH" else "H") else (if (zero) "hh" else "h"), d).toString()
        val m = DateFormat.format("mm", d).toString()
        val ampm = ""
        return "$h:$m" to ampm
    }

    override fun layout(w: Int, h: Int): List<Run> {
        val face = forceFace ?: ClockFaces.current(context)
        val p = Prefs.get(context)
        val (t, ampm) = now()
        lastText = t
        val (hh, mm) = t.split(':')
        val pad = w * 0.02f
        // Room on the right for a small AM/PM.
        val bw = (w - pad * 2) * if (ampm.isNotEmpty()) 0.84f else 1f
        val bh = h - pad * 2
        // Stacked when the box is tall enough for two lines, unless you chose one line.
        val stacked = stackedOverride ?: when (p.getInt(Prefs.CLOCK_LAYOUT, 0)) {
            1 -> false
            else -> h > w * 0.45f
        }
        val sep = when (p.getInt(Prefs.CLOCK_SEP, 0)) {
            1 -> "."
            2 -> " "
            else -> ":"
        }
        val lines = if (stacked) listOf(hh, mm) else listOf("$hh$sep$mm")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = ClockFaces.typeface(context, face)
            color = ClockFaces.colour(context)
            letterSpacing = face.tracking
        }
        val gap = 0.08f
        paint.textSize = TextArt.fit(paint, lines, bw, bh, gap) * if (face.varsity) 0.9f else 1f
        ClockFaces.shadow(context, paint)
        val minPaint = Paint(paint).apply { color = ClockFaces.minuteColour(context) }
        val small = Paint(paint).apply {
            textSize = paint.textSize * 0.26f
            letterSpacing = 0.06f
            typeface = Fonts.semibold(context)
        }
        val ampmW = if (ampm.isNotEmpty()) small.measureText(ampm) + paint.textSize * 0.08f else 0f
        val align = alignOverride ?: p.getInt(Prefs.CLOCK_ALIGN, 0)
        val r = Rect()
        // Measure the real ink height of each line for tight stacking.
        val heights = lines.map { l -> paint.getTextBounds(l, 0, l.length, r); r.height().toFloat() to r.top.toFloat() }
        val total = heights.sumOf { it.first.toDouble() }.toFloat() + paint.textSize * gap * (lines.size - 1)
        var y = pad + (bh - total) / 2f
        val out = ArrayList<Run>()
        for ((i, l) in lines.withIndex()) {
            val lw = paint.measureText(l)
            val last = i == lines.lastIndex
            val x = when (align) {
                1 -> (w - lw - (if (last) ampmW else 0f)) / 2f
                2 -> w - pad - lw - (if (last) ampmW else 0f)
                else -> pad
            }
            val base = y - heights[i].second
            when {
                stacked -> out += Run(l, if (i == 1) minPaint else paint, x, base, varsity = face.varsity)
                else -> {
                    // One line: hours, separator and minutes (so the minutes can be their own colour).
                    val hw = paint.measureText(hh + sep)
                    out += Run(hh + sep, paint, x, base, varsity = face.varsity)
                    out += Run(mm, minPaint, x + hw, base, varsity = face.varsity)
                }
            }
            if (last && ampm.isNotEmpty()) {
                small.getTextBounds(ampm, 0, ampm.length, r)
                // Top-aligned with the last line, just to its right.
                out += Run(ampm, small, x + lw + paint.textSize * 0.08f, y - r.top)
            }
            y += heights[i].first + paint.textSize * gap
        }
        return out
    }
}

/** The date, in one of four styles (Settings → Clock). */
class DateFace(context: Context, private val forceStyle: Int = -1) : TextArt(context) {
    override fun layout(w: Int, h: Int): List<Run> {
        val style = if (forceStyle >= 0) forceStyle else Prefs.get(context).getInt(Prefs.DATE_STYLE, 0)
        val d = Date()
        val colour = ClockFaces.colour(context)
        val align = alignOverride ?: Prefs.get(context).getInt(Prefs.CLOCK_ALIGN, 0)
        fun paint(tf: Typeface, track: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = tf
            color = colour
            letterSpacing = track
        }
        val pad = w * 0.03f
        fun place(lines: List<Pair<String, Paint>>, gap: Float): List<Run> {
            val r = Rect()
            val hs = lines.map { (t, p) -> p.getTextBounds(t, 0, t.length, r); r.height().toFloat() to r.top.toFloat() }
            val total = hs.sumOf { it.first.toDouble() }.toFloat() + gap * (lines.size - 1)
            var y = (h - total) / 2f
            return lines.mapIndexed { i, (t, p) ->
                val lw = p.measureText(t)
                val x = when (align) {
                    1 -> (w - lw) / 2f
                    2 -> w - pad - lw
                    else -> pad
                }
                Run(t, p, x, y - hs[i].second).also { y += hs[i].first + gap }
            }
        }
        val semi = Fonts.semibold(context)
        return when (style) {
            1 -> {
                // Thursday, 29 October
                val t = DateFormat.format("EEEE, d MMMM", d).toString()
                val p = paint(Fonts.regular(context))
                p.textSize = TextArt.fit(p, listOf(t), w - pad * 2, h * 0.5f, 0f)
                place(listOf(t to p), 0f)
            }
            2 -> {
                // 29, with the day and month beside it
                val big = paint(ClockFaces.typeface(context, ClockFaces.current(context)))
                val day = DateFormat.format("d", d).toString()
                big.textSize = TextArt.fit(big, listOf(day), w * 0.45f, h - pad * 2, 0f)
                val small = paint(semi, 0.12f)
                val l1 = DateFormat.format("EEEE", d).toString().uppercase()
                val l2 = DateFormat.format("MMMM", d).toString().uppercase()
                val bw = big.measureText(day)
                small.textSize = TextArt.fit(small, listOf(l1, l2), w - bw - pad * 4, h * 0.42f, 0.35f).coerceAtMost(big.textSize * 0.32f)
                val r = Rect()
                big.getTextBounds(day, 0, day.length, r)
                val by = (h - r.height()) / 2f - r.top
                val sx = pad + bw + pad * 2
                small.getTextBounds(l1, 0, l1.length, r)
                val sh = r.height()
                val mid = h / 2f
                listOf(
                    Run(day, big, pad, by),
                    Run(l1, small, sx, mid - sh * 0.35f),
                    Run(l2, small, sx, mid + sh * 1.35f),
                )
            }
            3 -> {
                // 29.10 in the clock's face
                val p = paint(ClockFaces.typeface(context, ClockFaces.current(context)))
                val t = DateFormat.format("dd.MM", d).toString()
                p.textSize = TextArt.fit(p, listOf(t), w - pad * 2, h - pad * 2, 0f)
                place(listOf(t to p), 0f)
            }
            4 -> {
                // Thu · 29 Oct
                val t = DateFormat.format("EEE", d).toString() + "  ·  " + DateFormat.format("d MMM", d).toString()
                val p = paint(semi, 0.02f)
                p.textSize = TextArt.fit(p, listOf(t), w - pad * 2, h * 0.4f, 0f)
                ClockFaces.shadow(context, p)
                place(listOf(t to p), 0f)
            }
            5 -> {
                // OCTOBER 29 in the clock's face
                val p = paint(ClockFaces.typeface(context, ClockFaces.current(context)), 0.02f)
                val t = DateFormat.format("MMMM d", d).toString().uppercase()
                p.textSize = TextArt.fit(p, listOf(t), w - pad * 2, h * 0.62f, 0f)
                ClockFaces.shadow(context, p)
                place(listOf(t to p), 0f)
            }
            6 -> {
                // Thursday over 29 October
                val big = paint(Fonts.semibold(context))
                val small = paint(Fonts.regular(context))
                val l1 = DateFormat.format("EEEE", d).toString()
                val l2 = DateFormat.format("d MMMM yyyy", d).toString()
                big.textSize = TextArt.fit(big, listOf(l1), w - pad * 2, h * 0.42f, 0f)
                small.textSize = (big.textSize * 0.48f).coerceAtMost(TextArt.fit(small, listOf(l2), w - pad * 2, h * 0.3f, 0f))
                small.color = Color.argb(190, Color.red(colour), Color.green(colour), Color.blue(colour))
                ClockFaces.shadow(context, big)
                ClockFaces.shadow(context, small)
                place(listOf(l1 to big, l2 to small), h * 0.06f)
            }
            7 -> {
                // 29/10/2026, tidy and numeric
                val p = paint(semi, 0.06f)
                val t = DateFormat.format("dd/MM/yyyy", d).toString()
                p.textSize = TextArt.fit(p, listOf(t), w - pad * 2, h * 0.36f, 0f)
                ClockFaces.shadow(context, p)
                place(listOf(t to p), 0f)
            }
            else -> {
                // THU 29 OCT
                val t = DateFormat.format("EEE d MMM", d).toString().uppercase()
                val p = paint(semi, 0.14f)
                p.textSize = TextArt.fit(p, listOf(t), w - pad * 2, h * 0.36f, 0f)
                place(listOf(t to p), 0f)
            }
        }
    }

    companion object {
        val NAMES = listOf("THU 29 OCT", "Thursday, 29 October", "Big day", "29.10", "Thu · 29 Oct", "OCTOBER 29", "Day over date", "29/10/2026")
    }
}

/** The weather as text on the wallpaper: icon, temperature, sky and place. */
class WeatherFace(context: Context) : TextArt(context) {
    override fun layout(w: Int, h: Int): List<Run> {
        val now = Weather.now
        val colour = ClockFaces.colour(context)
        val align = alignOverride ?: Prefs.get(context).getInt(Prefs.CLOCK_ALIGN, 0)
        val pad = w * 0.03f
        if (now == null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Fonts.semibold(context)
                color = colour
                letterSpacing = 0.1f
            }
            val t = if (Weather.hasPlace(context)) "WEATHER…" else "SET YOUR CITY"
            p.textSize = TextArt.fit(p, listOf(t), w - pad * 2, h * 0.28f, 0f)
            val r = Rect()
            p.getTextBounds(t, 0, t.length, r)
            return listOf(Run(t, p, if (align == 2) w - pad - p.measureText(t) else pad, (h - r.height()) / 2f - r.top))
        }
        val temp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ClockFaces.typeface(context, ClockFaces.current(context))
            color = colour
        }
        val tt = "${now.temp}°"
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.semibold(context)
            color = colour
            letterSpacing = 0.12f
        }
        val st = listOf(Weather.describe(now.code), now.city).filter { it.isNotBlank() }.joinToString(" · ").uppercase()
        temp.textSize = TextArt.fit(temp, listOf(tt), w * 0.5f, h * 0.56f, 0f)
        sub.textSize = TextArt.fit(sub, listOf(st), w - pad * 2, h * 0.17f, 0f).coerceAtMost(temp.textSize * 0.3f)
        val r = Rect()
        temp.getTextBounds(tt, 0, tt.length, r)
        val iconSize = (r.height() * 0.8f).toInt()
        val tw = temp.measureText(tt)
        val rowW = iconSize + iconSize * 0.3f + tw
        val x0 = when (align) {
            1 -> (w - rowW) / 2f
            2 -> w - pad - rowW
            else -> pad
        }
        val top = h * 0.12f
        val icon = context.getDrawable(Weather.icon(now.code, now.isDay))?.mutate()
        val iy = (top + (r.height() - iconSize) / 2f).toInt()
        val ix = x0.toInt()
        val out = ArrayList<Run>()
        if (icon != null) out += Run(null, temp, 0f, 0f, icon, Rect(ix, iy, ix + iconSize, iy + iconSize))
        out += Run(tt, temp, x0 + iconSize * 1.3f, top - r.top)
        val sr = Rect()
        sub.getTextBounds(st, 0, st.length, sr)
        val sw = sub.measureText(st)
        val sx = when (align) {
            1 -> (w - sw) / 2f
            2 -> w - pad - sw
            else -> pad
        }
        out += Run(st, sub, sx, top + r.height() + h * 0.12f - sr.top)
        return out
    }
}
