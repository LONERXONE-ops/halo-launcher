package com.lonerxone.halo.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View

/**
 * Liquid Glass, after Apple's material: a pane that bends the picture behind
 * it. The middle is softly frosted for legibility; towards the rim the glass
 * thickens and refracts, pulling the background in like a lens, with a hint
 * of colour fringing; light catches the top-left edge and a fainter glint
 * the bottom-right.
 *
 * Android can't see other windows, so Halo Launcher draws its own background
 * (your picture, or your wallpaper when Android lets us read it) and every
 * glass pane samples that same image at its exact place on screen. On
 * Android 13+ the lens runs as an AGSL shader on the GPU; older phones get
 * frosted glass with the same rim light.
 */
object Glass {
    /** The background as drawn on screen (window-sized), and a blurred copy for the frost. */
    var backdrop: Bitmap? = null
        private set
    private var blurred: Bitmap? = null
    private var blurredShader: BitmapShader? = null
    private const val SCALE = 4
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun enabled(c: Context) = Prefs.get(c).getInt(Prefs.THEME, THEME_GLASS) == THEME_GLASS

    const val THEME_GLASS = 0
    const val THEME_SOLID = 1

    /** Sets the picture behind everything (null = the system wallpaper shows instead). */
    fun setBackdrop(src: Bitmap?, w: Int, h: Int) {
        if (src == null || w <= 0 || h <= 0) {
            backdrop = null
            blurred = null
            blurredShader = null
            for (l in listeners.toList()) l()
            return
        }
        // Centre-crop the picture to the window.
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        backdrop = out
        val small = Bitmap.createScaledBitmap(out, (w / SCALE).coerceAtLeast(1), (h / SCALE).coerceAtLeast(1), true)
        val b = boxBlur(small, 4)
        blurred = b
        blurredShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(SCALE.toFloat(), SCALE.toFloat()) })
        }
        for (l in listeners.toList()) l()
    }

    fun frost(): BitmapShader? = blurredShader

    /** The blurred copy (1/SCALE size) for drawables that need their own shader. */
    fun blurredBitmap(): Bitmap? = blurred

    const val SCALE_F = 4f

    private var loadedKey = ""
    private var wallColorCache: Int? = null
    private var wallColorId = -1
    private var wallCheckedAt = 0L

    /**
     * Your wallpaper's main colour. Android shares this with every app (no
     * special access), so even when the wallpaper itself is out of reach the
     * glass and Tinted icons can match it.
     */
    fun wallColor(c: Context): Int? {
        // A picture you chose wins.
        val pic = backdrop
        if (pic != null) return average(pic)
        val t = System.currentTimeMillis()
        if (t - wallCheckedAt < 5000 && wallColorId != -1) return wallColorCache
        wallCheckedAt = t
        val id = wallpaperId(c)
        if (id == wallColorId) return wallColorCache
        val col = try {
            android.app.WallpaperManager.getInstance(c).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
        } catch (_: Throwable) {
            null
        }
        wallColorCache = col
        wallColorId = id
        return col
    }

    private var avgOf: Bitmap? = null
    private var avg = 0

    private fun average(b: Bitmap): Int {
        if (avgOf === b) return avg
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0L
        var g = 0L
        var bl = 0L
        var best = 0
        var bestSat = -1f
        val hsv = FloatArray(3)
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = s.getPixel(x, y)
            r += Color.red(p)
            g += Color.green(p)
            bl += Color.blue(p)
            Color.colorToHSV(p, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestSat) {
                bestSat = score
                best = p
            }
        }
        // The most colourful patch if there is one, else the average.
        avg = if (bestSat > 0.18f) best else Color.rgb((r / 64).toInt(), (g / 64).toInt(), (bl / 64).toInt())
        avgOf = b
        return avg
    }
    private var pendingKey = ""

    /**
     * Works out the background off the main thread: your picture if you set
     * one, otherwise the phone's wallpaper when Android lets us read it.
     * Calls back on the main thread (only when something changed).
     */
    fun load(c: Context, w: Int, h: Int, done: () -> Unit) {
        val app = c.applicationContext
        val picAt = Prefs.get(app).getLong(Prefs.HERO_PIC, 0L)
        val key = "$picAt/$w/$h/" + wallpaperId(app) + "/" + android.os.Environment.isExternalStorageManager()
        if (key == loadedKey) return
        if (key == pendingKey) return
        pendingKey = key
        Thread {
            val src: Bitmap? = try {
                val f = Prefs.picFile(app, Prefs.HERO_PIC)
                if (picAt != 0L && f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else wallpaperBitmap(app)
            } catch (_: Throwable) {
                null
            }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                // Do the heavy work here, then hand over.
                val prepared = src?.let { prepare(it, w, h) }
                main.post {
                    loadedKey = key
                    if (pendingKey == key) pendingKey = ""
                    install(prepared)
                    done()
                }
            } catch (_: Throwable) {
                main.post {
                    loadedKey = key
                    if (pendingKey == key) pendingKey = ""
                    install(null)
                    done()
                }
            }
        }.start()
    }

    /** Forget the cached key so the next load() redoes it (after picking a new picture). */
    fun invalidate() {
        loadedKey = ""
        pendingKey = ""
    }

    private fun wallpaperId(c: Context): Int = try {
        android.app.WallpaperManager.getInstance(c).getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM)
    } catch (_: Throwable) {
        0
    }

    /** Whether Android lets us read the wallpaper (Android 13+ needs "All files access"). */
    fun canReadWallpaper(c: Context): Boolean = try {
        android.app.WallpaperManager.getInstance(c).drawable != null
    } catch (_: Throwable) {
        false
    }

    @Suppress("MissingPermission")
    private fun wallpaperBitmap(c: Context): Bitmap? = try {
        val wm = android.app.WallpaperManager.getInstance(c)
        if (wm.wallpaperInfo != null) {
            null // a live wallpaper: nothing still to bend
        } else {
            val d = wm.drawable ?: return null
            if (d is android.graphics.drawable.BitmapDrawable) {
                d.bitmap
            } else {
                val bw = d.intrinsicWidth.coerceIn(1, 2400)
                val bh = d.intrinsicHeight.coerceIn(1, 2400)
                Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                    d.setBounds(0, 0, bw, bh)
                    d.draw(Canvas(it))
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private class Prepared(val full: Bitmap, val blur: Bitmap)

    private fun prepare(src: Bitmap, w: Int, h: Int): Prepared {
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val small = Bitmap.createScaledBitmap(out, (w / SCALE).coerceAtLeast(1), (h / SCALE).coerceAtLeast(1), true)
        return Prepared(out, boxBlur(small, 4))
    }

    private fun install(p: Prepared?) {
        if (p == null) {
            backdrop = null
            blurred = null
            blurredShader = null
        } else {
            backdrop = p.full
            blurred = p.blur
            blurredShader = BitmapShader(p.blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(SCALE.toFloat(), SCALE.toFloat()) })
            }
        }
        for (l in listeners.toList()) l()
    }

    /** Three box-blur passes each way (close to a Gaussian), on the small copy. */
    private fun boxBlur(src: Bitmap, r: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h)
        repeat(3) {
            pass(a, b, w, h, r, true)
            pass(b, a, w, h, r, false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sr = 0
            var sg = 0
            var sb = 0
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            for (i in -r..r) {
                val p = at(i)
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                val idx = if (horizontal) line * w + i else i * w + line
                dst[idx] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = at(i - r)
                val inn = at(i + r + 1)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /**
     * The lens. p is the pixel inside the pane; origin is the pane's place on
     * screen. A rounded-rectangle distance field gives how close we are to the
     * rim and which way it faces; near the rim the sample point is pushed
     * outward-in (refraction), split slightly per colour (dispersion), and the
     * rim catches light from the top-left.
     */
    const val AGSL = """
        uniform shader image;
        uniform float2 size;
        uniform float2 origin;
        uniform float radius;
        uniform float bend;
        uniform float4 tint;
        uniform float light;

        half4 main(float2 p) {
            float2 c = size * 0.5;
            float2 q = abs(p - c) - (c - radius);
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            float bevel = min(radius, 30.0);
            float e = clamp(1.0 + d / bevel, 0.0, 1.0);
            float2 g;
            if (q.x > 0.0 && q.y > 0.0) {
                g = normalize(sign(p - c) * max(q, 0.0));
            } else if (q.x > q.y) {
                g = float2(sign(p.x - c.x), 0.0);
            } else {
                g = float2(0.0, sign(p.y - c.y));
            }
            float k = pow(e, 2.2) * bend;
            float2 s = origin + p - g * k;
            half3 col;
            col.r = image.eval(s - g * k * 0.08).r;
            col.g = image.eval(s).g;
            col.b = image.eval(s + g * k * 0.08).b;
            col = mix(col, half3(tint.rgb), half(tint.a));
            col *= 1.04;
            float2 L = normalize(float2(-0.55, -0.85));
            float rim = smoothstep(0.55, 1.0, e) * pow(max(dot(g, L), 0.0), 1.5);
            float rim2 = smoothstep(0.75, 1.0, e) * pow(max(dot(g, -L), 0.0), 2.0);
            float edge = smoothstep(0.9, 1.0, e);
            col += half3(rim * 0.40 * light + rim2 * 0.14 * light + edge * 0.05);
            return half4(col, 1.0);
        }
    """
}

/**
 * A pane of Liquid Glass as a View background. It finds its place on screen
 * each time it draws, so it stays correct while pages slide.
 */
class GlassDrawable(
    private val host: View,
    private val radius: Float,
    /** How dark the glass is tinted (0..1) for legible white text. */
    private val darkness: Float = 0.28f,
    /** Lens strength in px at the rim. */
    private val bendPx: Float = host.dpf(16f),
    private val rimLight: Boolean = true,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val loc = IntArray(2)
    private var runtime: Any? = null
    private var oldShader: BitmapShader? = null
    private var oldBmp: Bitmap? = null
    private val drawnAt = IntArray(2)
    private val now = IntArray(2)

    /**
     * Before every frame, check whether the pane moved on screen (page swipe,
     * scroll, an animation) and if so redraw it so the lens follows.
     */
    private val follow = android.view.ViewTreeObserver.OnPreDrawListener {
        if (host.background !== this && host.foreground !== this) {
            unhook()
        } else {
            host.getLocationInWindow(now)
            if (now[0] != drawnAt[0] || now[1] != drawnAt[1]) invalidateSelf()
        }
        true
    }
    private var hooked: android.view.ViewTreeObserver? = null

    private fun hook() {
        val vto = host.viewTreeObserver
        if (hooked === vto && vto.isAlive) return
        unhook()
        vto.addOnPreDrawListener(follow)
        hooked = vto
    }

    private fun unhook() {
        hooked?.let { if (it.isAlive) it.removeOnPreDrawListener(follow) }
        hooked = null
    }

    init {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (host.background === this@GlassDrawable) hook()
            }

            override fun onViewDetachedFromWindow(v: View) = unhook()
        })
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        if (rect.isEmpty) return
        host.getLocationInWindow(loc)
        drawnAt[0] = loc[0]
        drawnAt[1] = loc[1]
        if (host.isAttachedToWindow) hook()
        val ox = loc[0] + b.left.toFloat()
        val oy = loc[1] + b.top.toFloat()
        val frost = Glass.frost()
        if (frost != null && Build.VERSION.SDK_INT >= 33) {
            val rs = (runtime as? RuntimeShader) ?: RuntimeShader(Glass.AGSL).also { runtime = it }
            rs.setInputShader("image", frost)
            rs.setFloatUniform("size", rect.width(), rect.height())
            rs.setFloatUniform("origin", ox, oy)
            rs.setFloatUniform("radius", radius.coerceAtMost(minOf(rect.width(), rect.height()) / 2f))
            rs.setFloatUniform("bend", bendPx)
            rs.setFloatUniform("tint", 0.04f, 0.04f, 0.05f, darkness)
            rs.setFloatUniform("light", if (rimLight) 1f else 0.4f)
            paint.shader = rs
            canvas.save()
            canvas.translate(rect.left, rect.top)
            rect.offsetTo(0f, 0f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
            rect.set(b)
        } else if (frost != null) {
            // Older Android: frosted glass, same rim light.
            val bmp = Glass.blurredBitmap()
            if (bmp != null) {
                val own = (oldShader?.takeIf { oldBmp === bmp }) ?: BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    oldShader = it
                    oldBmp = bmp
                }
                own.setLocalMatrix(Matrix().apply {
                    setScale(Glass.SCALE_F, Glass.SCALE_F)
                    postTranslate(-ox + rect.left, -oy + rect.top)
                })
                paint.shader = own
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            fallback.shader = null
            fallback.color = Color.argb((255 * darkness).toInt(), 10, 10, 12)
            canvas.drawRoundRect(rect, radius, radius, fallback)
        } else {
            // No picture to bend (the phone's wallpaper is out of reach): smoked
            // glass, see-through and tinted with the wallpaper's colour.
            fallback.shader = null
            val wc = Glass.wallColor(host.context) ?: Color.rgb(22, 22, 26)
            val a = (40 + 150 * darkness).toInt().coerceAtMost(200)
            fallback.color = Color.argb(
                a,
                (Color.red(wc) * 0.22f + 14 * 0.78f).toInt(),
                (Color.green(wc) * 0.22f + 14 * 0.78f).toInt(),
                (Color.blue(wc) * 0.22f + 18 * 0.78f).toInt(),
            )
            canvas.drawRoundRect(rect, radius, radius, fallback)
            // A pale sheen, brightest at the top, so it reads as glass and
            // not as a solid tile.
            fallback.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(Color.argb(46, 255, 255, 255), Color.argb(14, 255, 255, 255), Color.argb(22, 255, 255, 255)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, fallback)
            fallback.shader = null
        }
        // A crisp hairline that's bright where the light hits.
        val sw = host.dpf(1.1f)
        rimPaint.strokeWidth = sw
        rimPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(Color.argb(if (rimLight) 120 else 60, 255, 255, 255), Color.argb(18, 255, 255, 255), Color.argb(55, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        rect.inset(sw / 2f, sw / 2f)
        canvas.drawRoundRect(rect, radius - sw / 2f, radius - sw / 2f, rimPaint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The theme's surface for a card: Liquid Glass or a solid tile. */
fun View.surface(radius: Float, darkness: Float = 0.28f): Drawable =
    if (Glass.enabled(context)) GlassDrawable(this, radius, darkness) else context.round(Palette.TILE, radius)

/** Glass panes sample the background at their place on screen, so redraw them when they move. */
fun View.invalidateGlass() {
    if (background is GlassDrawable) invalidate()
    if (this is android.view.ViewGroup) for (i in 0 until childCount) getChildAt(i).invalidateGlass()
}
