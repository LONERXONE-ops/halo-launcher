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

    /** Experimental chromatic dispersion strength (Settings → Theme), 0 = off. */
    @Volatile var disperse = 0f
    /** How much a bright, reflective wallpaper shines on the glass (0..1), and its light's colour. */
    @Volatile var shine = 0f
    @Volatile var glintColor = Color.WHITE

    /**
     * Works out the shine from the wallpaper's colour: sky blues, golds and
     * yellows, and bright silvers catch the light; dark or muddy ones don't.
     */
    fun updateLight(c: Context) {
        disperse = if (Prefs.get(c).getBoolean(Prefs.GLASS_DISPERSE, false)) 1f else 0f
        val wc = wallColor(c)
        if (wc == null) {
            shine = 0f
            glintColor = Color.WHITE
            return
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(wc, hsv)
        val (h, sat, v) = Triple(hsv[0], hsv[1], hsv[2])
        fun bump(lo: Float, hi: Float): Float {
            if (h < lo - 15f || h > hi + 15f) return 0f
            return when {
                h < lo -> (h - (lo - 15f)) / 15f
                h > hi -> ((hi + 15f) - h) / 15f
                else -> 1f
            }
        }
        fun ramp(x: Float, a: Float, b: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f)
        val sky = bump(180f, 225f)
        val gold = bump(35f, 70f)
        val colourful = maxOf(sky, gold) * ramp(sat, 0.18f, 0.45f) * ramp(v, 0.42f, 0.8f)
        val silver = (1f - ramp(sat, 0.06f, 0.18f)) * ramp(v, 0.72f, 0.92f) * 0.7f
        shine = maxOf(colourful, silver).coerceIn(0f, 1f)
        // The glare takes a little of the wallpaper's colour, like light off it.
        glintColor = Color.HSVToColor(floatArrayOf(h, (sat * 0.35f).coerceAtMost(0.3f), 1f))
    }

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

    private var sharpShader: BitmapShader? = null
    private var sharpOf: Bitmap? = null

    /** The background itself, unblurred (for glass digits, which bend it clearly). */
    fun sharp(): BitmapShader? {
        val b = backdrop ?: return null
        if (sharpOf !== b) {
            sharpOf = b
            sharpShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        return sharpShader
    }

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
        if (pic != null) {
            val col = average(pic)
            rememberColour(c, col)
            return col
        }
        // Not loaded yet (a fresh start): the colour it had last time, if the
        // picture hasn't changed, so the look doesn't jump (and rebuild) a moment later.
        remembered(c)?.let { return it }
        val t = System.currentTimeMillis()
        if (t - wallCheckedAt < 5000 && wallColorId != -1) return wallColorCache
        wallCheckedAt = t
        val id = wallpaperId(c)
        if (id == wallColorId) return wallColorCache
        val col = try {
            // Of the three colours Android picks out, use the most vivid one
            // (the primary is often a dull background tone).
            val wc = android.app.WallpaperManager.getInstance(c).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
            val opts = listOfNotNull(wc?.primaryColor, wc?.secondaryColor, wc?.tertiaryColor).map { it.toArgb() }
            val hsv = FloatArray(3)
            opts.maxByOrNull { o ->
                Color.colorToHSV(o, hsv)
                hsv[1] * (0.4f + hsv[2])
            }?.takeIf { o -> Color.colorToHSV(o, hsv); hsv[1] > 0.2f } ?: opts.firstOrNull()
        } catch (_: Throwable) {
            null
        }
        wallColorCache = col
        wallColorId = id
        return col
    }

    private fun pictureKey(c: Context): String =
        Prefs.get(c).getLong(Prefs.HERO_PIC, 0L).toString() + "/" + wallpaperId(c)

    private var rememberedFor = ""

    private fun rememberColour(c: Context, col: Int) {
        val k = pictureKey(c)
        if (k == rememberedFor) return
        rememberedFor = k
        c.getSharedPreferences("glass_cache", Context.MODE_PRIVATE).edit().putString("key", k).putInt("colour", col).apply()
    }

    private var memoAt = 0L
    private var memo: Int? = null

    private fun remembered(c: Context): Int? {
        // Asking Android for the wallpaper id isn't free: check at most every few seconds.
        val now = System.currentTimeMillis()
        if (now - memoAt < 4000L) return memo
        memoAt = now
        memo = rememberedNow(c)
        return memo
    }

    private fun rememberedNow(c: Context): Int? {
        val p = c.getSharedPreferences("glass_cache", Context.MODE_PRIVATE)
        if (!p.contains("colour") || p.getString("key", "") != pictureKey(c)) return null
        return p.getInt("colour", 0)
    }

    /** A vivid version of the wallpaper's main colour (for the clock and accents). */
    fun accent(c: Context): Int {
        val base = wallColor(c) ?: return 0xFFFFB340.toInt()
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[1] = hsv[1].coerceIn(0.4f, 0.8f)
        hsv[2] = 0.97f
        return Color.HSVToColor(hsv)
    }

    private var avgOf: Bitmap? = null
    private var avg = 0

    /**
     * The wallpaper's main colour: hues are counted (weighted by how vivid
     * and how much of the picture they cover) and the strongest family wins,
     * averaged so it isn't thrown off by one odd pixel. Mostly-grey pictures
     * give their average grey.
     */
    private fun average(b: Bitmap): Int {
        if (avgOf === b) return avg
        val n = 24
        val s = Bitmap.createScaledBitmap(b, n, n, true)
        val bins = 24
        val weight = FloatArray(bins)
        val rs = FloatArray(bins)
        val gs = FloatArray(bins)
        val bs = FloatArray(bins)
        var r = 0L
        var g = 0L
        var bl = 0L
        var colourful = 0f
        val hsv = FloatArray(3)
        for (y in 0 until n) for (x in 0 until n) {
            val p = s.getPixel(x, y)
            r += Color.red(p)
            g += Color.green(p)
            bl += Color.blue(p)
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.18f || hsv[2] < 0.16f) continue
            val w = hsv[1] * hsv[2]
            val k = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            weight[k] += w
            rs[k] += Color.red(p) * w
            gs[k] += Color.green(p) * w
            bs[k] += Color.blue(p) * w
            colourful += w
        }
        // Neighbouring hues count together (a red that drifts into orange is still red).
        var best = -1
        var bestW = 0f
        for (k in 0 until bins) {
            val w = weight[k] + 0.5f * (weight[(k + 1) % bins] + weight[(k + bins - 1) % bins])
            if (w > bestW) {
                bestW = w
                best = k
            }
        }
        val total = (n * n).toFloat()
        avg = if (best >= 0 && colourful / total > 0.025f && weight[best] > 0f) {
            Color.rgb((rs[best] / weight[best]).toInt().coerceIn(0, 255), (gs[best] / weight[best]).toInt().coerceIn(0, 255), (bs[best] / weight[best]).toInt().coerceIn(0, 255))
        } else {
            Color.rgb((r / total).toInt(), (g / total).toInt(), (bl / total).toInt())
        }
        avgOf = b
        if (s !== b) s.recycle()
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
        // 16-bit colour: half the memory of a full-screen picture (a wallpaper
        // has no transparency), so Android is less likely to close the
        // launcher while you're in another app.
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
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
        uniform float4 hue;
        uniform float light;
        // Experimental: how far the colours split at the rim (0 = the usual hint).
        uniform float disperse;
        // Glare off a bright, reflective wallpaper (sky blue, gold, silver): colour and amount.
        uniform float4 glint;

        half4 main(float2 p) {
            float2 c = size * 0.5;
            float2 q = abs(p - c) - (c - radius);
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            // The bevel is a thin rim, never more than a fraction of a small pane
            // (on a folder or icon a wide bevel looked like a pyramid).
            float bevel = min(min(radius, 30.0), min(size.x, size.y) * 0.16);
            float e = clamp(1.0 + d / bevel, 0.0, 1.0);
            // Which way the rim faces, blended smoothly round the corners
            // (no hard seams running in from the corners).
            float2 qq = max(q + bevel, 0.01);
            float2 gv = sign(p - c) * qq * qq * qq;
            float2 g = gv / max(length(gv), 0.000001);
            float k = pow(e, 2.2) * bend;
            float2 s = origin + p - g * k;
            half3 col;
            if (disperse > 0.0) {
                // Chromatic dispersion like real glass: seven samples across the
                // spectrum, each adding to the colours it belongs to, so the rim
                // breaks into a smooth rainbow instead of three ghost copies.
                float spread = k * (0.10 + 0.34 * disperse);
                half3 acc = half3(0.0);
                half3 wsum = half3(0.0);
                for (int i = 0; i < 7; i++) {
                    float t = float(i) / 6.0;
                    half3 w = half3(clamp(1.0 - t * 2.2, 0.0, 1.0) + clamp(t * 3.0 - 2.6, 0.0, 0.35),
                                    clamp(1.0 - abs(t - 0.5) * 2.4, 0.0, 1.0),
                                    clamp(t * 2.2 - 1.2, 0.0, 1.0));
                    half3 smp = image.eval(s + g * spread * (t - 0.5) * 2.0).rgb;
                    acc += smp * w;
                    wsum += w;
                }
                col = acc / max(wsum, half3(0.001));
            } else {
                col.r = image.eval(s - g * k * 0.08).r;
                col.g = image.eval(s).g;
                col.b = image.eval(s + g * k * 0.08).b;
            }
            // Vibrancy: what shows through keeps its colour (like iOS glass), not washed grey.
            half lum = dot(col, half3(0.299, 0.587, 0.114));
            col = mix(half3(lum), col, half(1.2));
            col = mix(col, half3(tint.rgb), half(tint.a));
            // Adaptive colour: a wash of your wallpaper's hue through the whole pane.
            col = mix(col, half3(hue.rgb), half(hue.a));
            col *= 1.04;
            float2 L = normalize(float2(-0.55, -0.85));
            float rim = smoothstep(0.55, 1.0, e) * pow(max(dot(g, L), 0.0), 1.5);
            float rim2 = smoothstep(0.75, 1.0, e) * pow(max(dot(g, -L), 0.0), 2.0);
            float edge = smoothstep(0.9, 1.0, e);
            // The light on the rim picks up a little of the hue too.
            half3 lightCol = mix(half3(1.0), half3(hue.rgb) * 1.6, half(min(hue.a * 1.5, 0.3)));
            col += lightCol * half(rim * 0.40 * light + rim2 * 0.14 * light) + half3(edge * 0.05);
            // A soft sheen across the top third, and a touch of depth at the bottom.
            float y = p.y / size.y;
            col += half3(0.045 * light * (1.0 - smoothstep(0.0, 0.38, y)));
            col *= half(1.0 - 0.06 * smoothstep(0.7, 1.0, y));
            if (disperse > 0.0) {
                // A thin iridescent line just inside the rim, its colour turning
                // with the direction the edge faces.
                float ang = atan(g.y, g.x) / 6.2831853;
                half3 irid = half3(0.5 + 0.5 * cos(6.2831853 * (ang + float3(0.0, 0.33, 0.67))));
                float line = smoothstep(0.62, 0.9, e) * (1.0 - smoothstep(0.93, 1.0, e));
                col += irid * half(line * 0.16 * disperse * (0.5 + 0.5 * light));
            }
            if (glint.a > 0.0) {
                // Shine: a bright wallpaper lights the glass. A soft glare band
                // sweeps diagonally across, a thinner streak beside it, and the
                // lit rim gets a hot highlight in the wallpaper's own light.
                float dpos = (p.x / size.x) * 0.62 + y * 0.38;
                float band = exp(-pow((dpos - 0.30) / 0.075, 2.0)) * 0.11 + exp(-pow((dpos - 0.43) / 0.022, 2.0)) * 0.07;
                float hot = pow(rim, 0.7) * 0.42 + rim2 * 0.16 + edge * 0.05;
                col += half3(glint.rgb) * half((band * (0.6 + 0.4 * light) + hot) * glint.a);
            }
            return half4(clamp(col, half3(0.0), half3(1.0)), 1.0);
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
    /** Drawn by hand inside a view (an icon tile), not as its background. */
    private val owned: Boolean = false,
) : Drawable() {
    private var rimFor = 0L
    private var rimShader: Shader? = null
    private var sheenFor = 0L
    private var sheenShader: Shader? = null
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
        if (!owned && host.background !== this && host.foreground !== this) {
            unhook()
        } else {
            host.getLocationInWindow(now)
            if (now[0] != drawnAt[0] || now[1] != drawnAt[1]) { if (owned) host.invalidate() else invalidateSelf() }
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
                if (owned || host.background === this@GlassDrawable) hook()
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
        // A flat pane (no lens, like the app list's backdrop) doesn't need the
        // lens shader at all: the cheaper frosted path looks the same.
        val rs0 = if (frost != null && bendPx > 0f && Build.VERSION.SDK_INT >= 33 && !shaderBroken) sharedShader() else null
        if (frost != null && rs0 != null && Build.VERSION.SDK_INT >= 33) {
            val rs = rs0
            rs.setInputShader("image", frost)
            rs.setFloatUniform("size", rect.width(), rect.height())
            rs.setFloatUniform("origin", ox, oy)
            rs.setFloatUniform("radius", radius.coerceAtMost(minOf(rect.width(), rect.height()) / 2f))
            rs.setFloatUniform("bend", bendPx)
            // The glass's tint: neutral, or your wallpaper's hue with Adaptive colour.
            val t = Palette.TILE
            rs.setFloatUniform("tint", Color.red(t) / 255f * 0.5f, Color.green(t) / 255f * 0.5f, Color.blue(t) / 255f * 0.5f, darkness)
            val hc = Palette.GLASS_TINT
            rs.setFloatUniform("hue", Color.red(hc) / 255f, Color.green(hc) / 255f, Color.blue(hc) / 255f, Palette.GLASS_TINT_AMT)
            rs.setFloatUniform("light", if (rimLight) 1f else 0.4f)
            rs.setFloatUniform("disperse", Glass.disperse)
            val gc = Glass.glintColor
            rs.setFloatUniform("glint", Color.red(gc) / 255f, Color.green(gc) / 255f, Color.blue(gc) / 255f, Glass.shine * (if (rimLight) 1f else 0.6f))
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
            if (Palette.GLASS_TINT_AMT > 0f) {
                val hc = Palette.GLASS_TINT
                fallback.color = Color.argb((255 * Palette.GLASS_TINT_AMT).toInt(), Color.red(hc), Color.green(hc), Color.blue(hc))
                canvas.drawRoundRect(rect, radius, radius, fallback)
            }
            glare(canvas)
        } else {
            // No picture to bend (the phone's wallpaper is out of reach): smoked
            // glass, see-through and tinted with the wallpaper's colour.
            fallback.shader = null
            // Tinted with your wallpaper's colour only when Adaptive colour is on.
            val wc = if (Palette.GLASS_TINT_AMT > 0f) Palette.GLASS_TINT else Color.rgb(22, 22, 26)
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
            val sk = (rect.top.toLong() shl 32) xor rect.bottom.toLong()
            if (sheenShader == null || sk != sheenFor) {
                sheenFor = sk
                sheenShader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    intArrayOf(Color.argb(46, 255, 255, 255), Color.argb(14, 255, 255, 255), Color.argb(22, 255, 255, 255)),
                    floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
                )
            }
            fallback.shader = sheenShader
            canvas.drawRoundRect(rect, radius, radius, fallback)
            fallback.shader = null
            glare(canvas)
        }
        // A crisp hairline that's bright where the light hits.
        val sw = host.dpf(1.1f)
        rimPaint.strokeWidth = sw
        // Built once per size, not every frame.
        val rk = ((rect.left.toLong() shl 48) xor (rect.top.toLong() shl 32) xor (rect.right.toLong() shl 16) xor rect.bottom.toLong()) xor (Palette.GLASS_TINT.toLong() * 31) xor (Glass.shine * 1000).toLong()
        if (rimShader == null || rk != rimFor) {
            rimFor = rk
            // The far edge glows faintly in the wallpaper's hue with Adaptive colour.
            val far = if (Palette.GLASS_TINT_AMT > 0f) Palette.ACCENT.let { Color.argb(70, Color.red(it), Color.green(it), Color.blue(it)) } else Color.argb(55, 255, 255, 255)
            // A shiny wallpaper makes the lit edge brighter.
            val lit = ((if (rimLight) 120 else 60) + 110 * Glass.shine).toInt().coerceAtMost(235)
            rimShader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(Color.argb(lit, 255, 255, 255), Color.argb(18, 255, 255, 255), far),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        rimPaint.shader = rimShader
        rect.inset(sw / 2f, sw / 2f)
        canvas.drawRoundRect(rect, radius - sw / 2f, radius - sw / 2f, rimPaint)
    }

    private var glareShader: Shader? = null
    private var glareFor = 0L

    /** Older Android: the shine as a soft diagonal glare band (the lens shader does its own). */
    private fun glare(canvas: Canvas) {
        val amt = Glass.shine
        if (amt <= 0f) return
        val gk = ((rect.left.toLong() shl 48) xor (rect.top.toLong() shl 32) xor (rect.right.toLong() shl 16) xor rect.bottom.toLong()) xor (amt * 1000).toLong() xor Glass.glintColor.toLong()
        if (glareShader == null || gk != glareFor) {
            glareFor = gk
            val c = Glass.glintColor
            fun a(v: Float) = Color.argb((255 * v * amt).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
            glareShader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(a(0.10f), a(0f), a(0.16f), a(0f), a(0.08f), a(0f)),
                floatArrayOf(0f, 0.18f, 0.3f, 0.4f, 0.45f, 0.52f), Shader.TileMode.CLAMP,
            )
        }
        fallback.shader = glareShader
        canvas.drawRoundRect(rect, radius, radius, fallback)
        fallback.shader = null
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    companion object {
        @Volatile
        private var shaderBroken = false
        private var shared: Any? = null

        /**
         * One lens shader for every pane, compiled once. (Each pane used to
         * compile its own, dozens at once whenever the home screen was
         * built, which made coming home slow.) Uniforms are set right before
         * each pane draws; Android snapshots them into that draw.
         */
        @android.annotation.TargetApi(33)
        private fun sharedShader(): RuntimeShader? {
            (shared as? RuntimeShader)?.let { return it }
            return try {
                RuntimeShader(Glass.AGSL).also { shared = it }
            } catch (_: Throwable) {
                // A GPU that won't take the shader: frosted glass instead, never a crash.
                shaderBroken = true
                null
            }
        }
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
