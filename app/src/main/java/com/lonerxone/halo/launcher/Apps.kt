package com.lonerxone.halo.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import java.text.Collator
import java.util.concurrent.Executors

/** One launchable activity (an app as it appears in the drawer). */
class App(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val category: Int,
    val info: LauncherActivityInfo,
) {
    val pkg: String get() = component.packageName
    /** Stable id for prefs: "pkg/class" (+ "#user" for work profiles). */
    val key: String = component.flattenToShortString() + if (user == Process.myUserHandle()) "" else "#" + user.hashCode()
}

/**
 * All launchable apps for every profile, read through LauncherApps (the same
 * API Launcher3, Olauncher and KISS use), kept fresh with its package callback.
 */
object AppRepo {
    var apps: List<App> = emptyList()
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var registered = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun byKey(key: String): App? = apps.firstOrNull { it.key == key }

    fun load(ctx: Context) {
        val app = ctx.applicationContext
        if (!registered) {
            registered = true
            val la = app.getSystemService(LauncherApps::class.java)
            la.registerCallback(object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String, user: UserHandle) = reload(app, packageName)
                override fun onPackageAdded(packageName: String, user: UserHandle) = reload(app, packageName)
                override fun onPackageChanged(packageName: String, user: UserHandle) = reload(app, packageName)
                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload(app, null)
                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload(app, null)
            }, main)
        }
        reload(app, null)
    }

    private fun reload(ctx: Context, changedPkg: String?) {
        if (changedPkg != null) Icons.forget(changedPkg)
        io.execute {
            val list = query(ctx)
            main.post {
                apps = list
                for (l in listeners.toList()) l()
            }
        }
    }

    private fun query(ctx: Context): List<App> {
        val la = ctx.getSystemService(LauncherApps::class.java)
        val um = ctx.getSystemService(UserManager::class.java)
        val collator = Collator.getInstance()
        val out = ArrayList<App>()
        for (user in um.userProfiles) {
            val acts = try {
                la.getActivityList(null, user)
            } catch (_: Exception) {
                continue
            }
            for (a in acts) {
                try {
                    if (a.componentName.packageName == ctx.packageName) continue
                    out += App(a.label.toString().trim(), a.componentName, user, a.applicationInfo.category, a)
                } catch (_: Exception) {
                }
            }
        }
        out.sortWith { x, y -> collator.compare(x.label, y.label) }
        return out
    }

    /** Opens an app with the scale-up animation from where it was tapped. */
    fun launch(ctx: Context, app: App, from: android.view.View?) {
        val la = ctx.getSystemService(LauncherApps::class.java)
        val bounds = from?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            android.graphics.Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        val opts = from?.let { android.app.ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        try {
            la.startMainActivity(app.component, app.user, bounds, opts)
            countLaunch(ctx, app)
        } catch (_: Exception) {
            android.widget.Toast.makeText(ctx, "Couldn't open ${app.label}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun countLaunch(ctx: Context, app: App) {
        val p = Prefs.get(ctx)
        val k = Prefs.LAUNCHES + ":" + app.key
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
    }

    fun launches(ctx: Context, app: App) = Prefs.get(ctx).getInt(Prefs.LAUNCHES + ":" + app.key, 0)

    /** Groups for the capsule folders, from the category each app declares. */
    fun group(app: App): String = when (app.category) {
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_GAME -> "Entertainment"
        ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_NEWS -> "Social"
        ApplicationInfo.CATEGORY_IMAGE -> "Photos"
        ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_MAPS -> "Tools"
        else -> guess(app)
    }

    /** Many apps don't declare a category; a few well-known names help. */
    private fun guess(app: App): String {
        val p = app.pkg.lowercase()
        return when {
            listOf("whatsapp", "instagram", "facebook", "twitter", "telegram", "snapchat", "tiktok", "musically", "discord", "reddit", "pinterest", "threads", "messenger", "x.android").any { p.contains(it) } -> "Social"
            listOf("spotify", "youtube", "netflix", "music", "boomplay", "audiomack", "crunchyroll", "player", "video", "game").any { p.contains(it) } -> "Entertainment"
            listOf("camera", "gallery", "photo", "lightroom", "snapseed", "picsart", "canva").any { p.contains(it) } -> "Photos"
            else -> "Tools"
        }
    }
}

/**
 * App icons, three looks:
 * - DARK (default): like iOS dark mode. A dark rounded square with the app's
 *   own glyph in its brand colour (the white glyph apps ship for Android's
 *   themed icons, tinted with the colour of their icon background).
 * - MONO: the same glyph in plain white, no tile.
 * - ORIGINAL: the icon as the app made it.
 * The glyph comes from AdaptiveIconDrawable.getMonochrome, or straight from
 * the app's icon XML as Lawnchair does, since some skins wrap the icon.
 */
object Icons {
    const val DARK = 0
    const val MONO = 1
    const val ORIGINAL = 2
    val NAMES = listOf("Dark", "White line", "Original")

    class Result(val bitmap: Bitmap)

    private val cache = object : LruCache<String, Result>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Result) = value.bitmap.byteCount
    }
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun style(ctx: Context): Int = Prefs.get(ctx).getInt(Prefs.ICON_STYLE, DARK).let { if (it in DARK..ORIGINAL) it else DARK }

    fun forget(pkg: String) {
        for (k in cache.snapshot().keys) if (k.contains(pkg)) cache.remove(k)
    }

    fun cached(app: App, style: Int, size: Int): Result? = cache.get(keyOf(app, style, size))

    private fun keyOf(app: App, style: Int, size: Int) = "${app.key}|$style|$size|v2"

    fun load(ctx: Context, app: App, style: Int, size: Int, done: (Result) -> Unit) {
        val k = keyOf(app, style, size)
        cache.get(k)?.let { done(it); return }
        val c = ctx.applicationContext
        io.execute {
            val r = try {
                render(c, app, style, size)
            } catch (_: Throwable) {
                null
            }
            if (r != null) {
                cache.put(k, r)
                main.post { done(r) }
            }
        }
    }

    private fun render(ctx: Context, app: App, style: Int, size: Int): Result? {
        val density = ctx.resources.displayMetrics.densityDpi
        val icon = app.info.getIcon(density) ?: return null
        if (style == ORIGINAL) {
            val badged = ctx.packageManager.getUserBadgedIcon(icon, app.user)
            return Result(draw(badged, size, 1f))
        }
        val glyph: Drawable? = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                (icon as? AdaptiveIconDrawable)?.monochrome ?: monochromeFromXml(ctx, app)
            } else {
                monochromeFromXml(ctx, app)
            }
        } catch (_: Throwable) {
            null
        }
        if (style == MONO) {
            if (glyph != null) {
                glyph.mutate().setTint(Palette.INK)
                glyph.setTintMode(PorterDuff.Mode.SRC_IN)
                return Result(draw(glyph, size, 1.5f))
            }
            return Result(dark(icon, null, size, DARK, 0))
        }
        return Result(dark(icon, glyph, size, style, 0))
    }

    /**
     * iOS 18-style dark icon: the app's own symbol, in its own colours, on a
     * near-black tile.
     *
     * The phone often hands out a finished picture (Samsung does), so the
     * background colour is found on a ring just inside the edge and keyed
     * out, leaving only the symbol:
     * - colourful symbol on white (YouTube, Photos) keeps its colours;
     * - white symbol on colour (WhatsApp, Phone) takes the brand colour;
     * - black symbol on white (X, TikTok) turns white so it shows on black;
     * - busy icons (gradients, photos) use the app's themed symbol in its
     *   brand colour, or the whole icon a little smaller and dimmed.
     * Then every symbol is centred and sized the same, so the dock and the
     * app list look even instead of some icons being boxes and some tiny.
     */
    private fun dark(icon: Drawable, glyph: Drawable?, size: Int, mode: Int, accent: Int): Bitmap {
        val src = draw(icon, size, 1f)
        val px = IntArray(size * size)
        src.getPixels(px, 0, size, 0, 0, size, size)

        // Background: median colour on a ring just inside the edge.
        val ring = ArrayList<Int>()
        val inset = (size * 0.075f).toInt()
        val steps = 64
        for (k in 0 until steps) {
            val a = 2.0 * Math.PI * k / steps
            val rx = (size / 2f + (size / 2f - inset) * Math.cos(a)).toInt().coerceIn(0, size - 1)
            val ry = (size / 2f + (size / 2f - inset) * Math.sin(a)).toInt().coerceIn(0, size - 1)
            val p = px[ry * size + rx]
            if (android.graphics.Color.alpha(p) > 200) ring += p
        }
        val bg = median(ring)
        // Share of the ring that really is background (low when a glyph or gradient crosses it).
        val clean = if (ring.isEmpty()) 0.0 else ring.count { dist(it, bg) < 40.0 }.toDouble() / steps

        // 1. The symbol on its own (transparent around it).
        val sym = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        var fill = 0.62f
        when {
            clean > 0.6 -> {
                val bgSat = sat(bg)
                val brand = brightOnBlack(bg)
                // Only look inside the icon's rounded shape (not the transparent corners).
                val out = IntArray(size * size)
                val lim = size * 0.47f
                for (n in px.indices) {
                    val p = px[n]
                    val a = android.graphics.Color.alpha(p)
                    if (a == 0) continue
                    val x = n % size - size / 2f
                    val y = n / size - size / 2f
                    if (x * x + y * y > lim * lim * 1.9f) continue
                    val d = dist(p, bg)
                    var t = ((d - 30.0) / 70.0).coerceIn(0.0, 1.0)
                    t = t * t * (3 - 2 * t) * a / 255.0
                    if (t <= 0.02) continue
                    var col = p
                    when {
                        // White symbol on a coloured background: paint it in the brand colour.
                        bgSat > 0.25f && luma(p) > 205 && sat(p) < 0.18f -> col = brand
                        // Black symbol: white, or it vanishes on black.
                        luma(p) < 70 && sat(p) < 0.3f -> col = 0xFFEDEDED.toInt()
                    }
                    out[n] = android.graphics.Color.argb((t * 255).toInt(), android.graphics.Color.red(col), android.graphics.Color.green(col), android.graphics.Color.blue(col))
                }
                sym.setPixels(out, 0, size, 0, 0, size, size)
            }
            glyph != null -> {
                val g = glyph.mutate()
                g.setTint(brightOnBlack(average(icon)))
                g.setTintMode(PorterDuff.Mode.SRC_IN)
                drawInto(Canvas(sym), g, size, 1.45f)
            }
            else -> {
                // A busy icon with no symbol to pull out: the whole icon, smaller, slightly dimmed.
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.86f, 0.86f, 0.86f, 1f) })
                }
                val cv = Canvas(sym)
                val path = android.graphics.Path().apply {
                    addRoundRect(android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.2237f, size * 0.2237f, android.graphics.Path.Direction.CW)
                }
                cv.clipPath(path)
                cv.drawBitmap(src, 0f, 0f, p)
                fill = 0.8f
            }
        }
        src.recycle()

        // 2. The tile: near-black, a touch lighter at the top, iOS corner radius.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = size * 0.2237f
        val rect = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
        c.drawRoundRect(rect, r, r, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), 0xFF232325.toInt(), 0xFF0B0B0C.toInt(), android.graphics.Shader.TileMode.CLAMP)
        })

        // 3. The symbol, centred and sized the same for every app.
        val box = bounds(sym)
        if (box != null) {
            val longest = maxOf(box.width(), box.height()).toFloat().coerceAtLeast(1f)
            val scale = (size * fill / longest).coerceIn(0.55f, 1.7f)
            val m = android.graphics.Matrix().apply {
                postTranslate(-box.exactCenterX(), -box.exactCenterY())
                postScale(scale, scale)
                postTranslate(size / 2f, size / 2f)
            }
            val save = c.save()
            c.clipPath(android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) })
            c.drawBitmap(sym, m, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
            c.restoreToCount(save)
        }
        sym.recycle()

        // 4. A faint rim, brighter along the top, so the tile reads on dark pictures.
        val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = size / 72f
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), android.graphics.Color.argb(70, 255, 255, 255), android.graphics.Color.argb(14, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP)
        }
        rect.inset(edge.strokeWidth / 2f, edge.strokeWidth / 2f)
        c.drawRoundRect(rect, r, r, edge)
        return out
    }

    /** The box around everything that isn't (nearly) transparent. */
    private fun bounds(b: Bitmap): android.graphics.Rect? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w
        var t = h
        var r = -1
        var bt = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (android.graphics.Color.alpha(px[y * w + x]) > 50) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > bt) bt = y
            }
        }
        return if (r < l || bt < t) null else android.graphics.Rect(l, t, r + 1, bt + 1)
    }

    private fun shade(c: Int, f: Double): Int = android.graphics.Color.rgb(
        (android.graphics.Color.red(c) * f).toInt().coerceIn(0, 255),
        (android.graphics.Color.green(c) * f).toInt().coerceIn(0, 255),
        (android.graphics.Color.blue(c) * f).toInt().coerceIn(0, 255),
    )

    /** Like mix, but also fades the alpha up (for glyphs on the see-through Clear tile). */
    private fun over(a: Int, b: Int, t: Double): Int {
        val m = mix(a, b, t)
        val al = (android.graphics.Color.alpha(a) * (1 - t) + 255 * t).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(al, android.graphics.Color.red(m), android.graphics.Color.green(m), android.graphics.Color.blue(m))
    }

    private fun median(list: List<Int>): Int {
        if (list.isEmpty()) return android.graphics.Color.WHITE
        val r = list.map { android.graphics.Color.red(it) }.sorted()[list.size / 2]
        val g = list.map { android.graphics.Color.green(it) }.sorted()[list.size / 2]
        val b = list.map { android.graphics.Color.blue(it) }.sorted()[list.size / 2]
        return android.graphics.Color.rgb(r, g, b)
    }

    private fun dist(a: Int, b: Int): Double {
        val dr = (android.graphics.Color.red(a) - android.graphics.Color.red(b)).toDouble()
        val dg = (android.graphics.Color.green(a) - android.graphics.Color.green(b)).toDouble()
        val db = (android.graphics.Color.blue(a) - android.graphics.Color.blue(b)).toDouble()
        return Math.sqrt(dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11) * 1.6
    }

    private fun luma(c: Int) = (android.graphics.Color.red(c) * 0.3 + android.graphics.Color.green(c) * 0.59 + android.graphics.Color.blue(c) * 0.11)

    private fun sat(c: Int): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        return hsv[1]
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        val u = 1.0 - t
        return android.graphics.Color.argb(
            android.graphics.Color.alpha(a),
            (android.graphics.Color.red(a) * u + android.graphics.Color.red(b) * t).toInt(),
            (android.graphics.Color.green(a) * u + android.graphics.Color.green(b) * t).toInt(),
            (android.graphics.Color.blue(a) * u + android.graphics.Color.blue(b) * t).toInt(),
        )
    }

    private fun drawInto(c: Canvas, d: Drawable, size: Int, scale: Float) {
        val extra = ((scale - 1f) * size / 2f).toInt()
        val old = android.graphics.Rect(d.bounds)
        d.setBounds(-extra, -extra, size + extra, size + extra)
        d.draw(c)
        d.bounds = old
    }

    private fun draw(d: Drawable, size: Int, scale: Float): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawInto(Canvas(bmp), d, size, scale)
        return bmp
    }

    /** Average colour of a drawable's visible pixels. */
    private fun average(d: Drawable): Int {
        val s = 16
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val old = android.graphics.Rect(d.bounds)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(b))
        d.bounds = old
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0L
        for (y in 0 until s) for (x in 0 until s) {
            val p = b.getPixel(x, y)
            if (android.graphics.Color.alpha(p) < 128) continue
            r += android.graphics.Color.red(p)
            g += android.graphics.Color.green(p)
            bl += android.graphics.Color.blue(p)
            n++
        }
        b.recycle()
        if (n == 0L) return Palette.SILVER
        return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    private fun isGrey(c: Int): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        return hsv[1] < 0.18f
    }

    /** Keeps the hue, lifts it so it glows on a dark tile (white stays white). */
    private fun brightOnBlack(c: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.18f) return Palette.INK
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceAtLeast(0.9f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** Reads <monochrome> from the app's adaptive-icon XML (skins sometimes hide it). */
    private fun monochromeFromXml(ctx: Context, app: App): Drawable? {
        val pm = ctx.packageManager
        val ai = try {
            app.info.activityInfo
        } catch (_: Throwable) {
            null
        }
        val res = try {
            pm.getResourcesForApplication(app.info.applicationInfo)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val iconRes = (ai?.iconResource ?: 0).takeIf { it != 0 } ?: app.info.applicationInfo.icon
        if (iconRes == 0) return null
        return try {
            val parser = res.getXml(iconRes)
            var drawable: Drawable? = null
            var event = parser.eventType
            var inAdaptive = false
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if (parser.name == "adaptive-icon") inAdaptive = true
                    if (inAdaptive && parser.name == "monochrome") {
                        val id = parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "drawable", 0)
                        if (id != 0) drawable = res.getDrawable(id, null)
                        break
                    }
                }
                event = parser.next()
            }
            parser.close()
            drawable
        } catch (_: Exception) {
            null
        }
    }
}
