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
 * App icons. The source is the app's own icon, or your icon pack's (see
 * IconPacks). Three looks on top of that:
 * - Normal: the icon as it is.
 * - Liquid Glass: the app's symbol floating on a live pane of glass that
 *   bends your background (the tile is drawn by AppIconView).
 * - White line: every symbol in plain white.
 */
object Icons {
    const val MONO = 1
    const val ORIGINAL = 2
    /** Liquid Glass: the app's symbol floating on a pane of glass that bends your background (drawn live). */
    const val GLASS = 5
    val NAMES = mapOf(ORIGINAL to "Normal", GLASS to "Liquid Glass", MONO to "White line")

    /** [glass] = draw a glass tile behind the (transparent) symbol. */
    class Result(val bitmap: Bitmap, val glass: Boolean = false)

    private val cache = object : LruCache<String, Result>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Result) = value.bitmap.byteCount
    }
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var pack = ""

    /**
     * The icon look. Not chosen yet = follow the theme (Liquid Glass theme →
     * glass icons), because a theme should reach everything.
     */
    fun style(ctx: Context): Int {
        pack = IconPacks.current(ctx)
        val p = Prefs.get(ctx)
        if (!p.contains(Prefs.ICON_STYLE)) return if (Glass.enabled(ctx)) GLASS else ORIGINAL
        return when (val v = p.getInt(Prefs.ICON_STYLE, ORIGINAL)) {
            MONO, GLASS, ORIGINAL -> v
            else -> ORIGINAL
        }
    }

    fun forget(pkg: String) {
        for (k in cache.snapshot().keys) if (k.contains(pkg)) cache.remove(k)
    }

    fun forgetAll() {
        cache.evictAll()
        IconPacks.forget()
    }

    fun cached(app: App, style: Int, size: Int): Result? = cache.get(keyOf(app, style, size))

    private fun keyOf(app: App, style: Int, size: Int) = "${app.key}|$style|$size|$pack|v3"

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
        val own = app.info.getIcon(density) ?: return null
        // The icon pack's version of this app, if a pack is chosen and has one.
        val packed = try {
            IconPacks.iconFor(ctx, app, own, size)
        } catch (_: Throwable) {
            null
        }
        val icon = packed ?: own
        when (style) {
            MONO -> {
                if (packed == null) {
                    val glyph = monoGlyph(ctx, app, own)
                    if (glyph != null) {
                        glyph.mutate().setTint(Palette.INK)
                        glyph.setTintMode(PorterDuff.Mode.SRC_IN)
                        return Result(draw(glyph, size, 1.5f))
                    }
                }
                // Any icon as a white symbol.
                val sym = symbol(icon, null, size, keepColour = false)
                return Result(sym)
            }
            GLASS -> {
                val glyph = if (packed == null) monoGlyph(ctx, app, own) else null
                return Result(symbol(icon, glyph, size, keepColour = true), glass = true)
            }
            else -> {
                val badged = if (packed == null) ctx.packageManager.getUserBadgedIcon(icon, app.user) else icon
                return Result(draw(badged, size, 1f))
            }
        }
    }

    private fun monoGlyph(ctx: Context, app: App, icon: Drawable): Drawable? = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            (icon as? AdaptiveIconDrawable)?.monochrome ?: monochromeFromXml(ctx, app)
        } else {
            monochromeFromXml(ctx, app)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * The app's symbol on its own (transparent around it), centred and
     * sized the same for every app, for Liquid Glass and White line.
     *
     * Phones often hand out a finished picture (Samsung does), so the
     * background colour is found on a ring just inside the edge and keyed
     * out:
     * - colourful symbol on white (YouTube, Photos) keeps its colours;
     * - white symbol on colour (WhatsApp, Phone) takes the brand colour;
     * - black symbol on white (X, TikTok) turns white so it shows;
     * - icons that are already just a symbol (line icon packs) are used as they are;
     * - busy icons (gradients, photos) use the app's themed symbol, or the
     *   whole icon, smaller.
     */
    private fun symbol(icon: Drawable, glyph: Drawable?, size: Int, keepColour: Boolean): Bitmap {
        val src = draw(icon, size, 1f)
        val px = IntArray(size * size)
        src.getPixels(px, 0, size, 0, 0, size, size)

        val ring = ArrayList<Int>()
        val inset = (size * 0.075f).toInt()
        val steps = 64
        var clear = 0
        for (k in 0 until steps) {
            val a = 2.0 * Math.PI * k / steps
            val rx = (size / 2f + (size / 2f - inset) * Math.cos(a)).toInt().coerceIn(0, size - 1)
            val ry = (size / 2f + (size / 2f - inset) * Math.sin(a)).toInt().coerceIn(0, size - 1)
            val p = px[ry * size + rx]
            if (android.graphics.Color.alpha(p) > 200) ring += p
            if (android.graphics.Color.alpha(p) < 40) clear++
        }
        val bg = median(ring)
        val clean = if (ring.isEmpty()) 0.0 else ring.count { dist(it, bg) < 40.0 }.toDouble() / steps

        val sym = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        var fill = 0.6f
        when {
            // Already a symbol on nothing (line icon packs): use it as it is.
            clear > steps * 0.7 -> {
                sym.setPixels(px, 0, size, 0, 0, size, size)
                fill = 0.66f
            }
            clean > 0.6 -> {
                val bgSat = sat(bg)
                val brand = brightOnBlack(bg)
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
                        bgSat > 0.25f && luma(p) > 205 && sat(p) < 0.18f -> col = brand
                        luma(p) < 70 && sat(p) < 0.3f -> col = 0xFFF2F2F2.toInt()
                    }
                    out[n] = android.graphics.Color.argb((t * 255).toInt(), android.graphics.Color.red(col), android.graphics.Color.green(col), android.graphics.Color.blue(col))
                }
                sym.setPixels(out, 0, size, 0, 0, size, size)
            }
            glyph != null -> {
                val g = glyph.mutate()
                g.setTint(if (keepColour) brightOnBlack(average(icon)) else Palette.INK)
                g.setTintMode(PorterDuff.Mode.SRC_IN)
                drawInto(Canvas(sym), g, size, 1.45f)
            }
            else -> {
                val cv = Canvas(sym)
                cv.clipPath(android.graphics.Path().apply {
                    addRoundRect(android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.2237f, size * 0.2237f, android.graphics.Path.Direction.CW)
                })
                cv.drawBitmap(src, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                fill = 0.74f
            }
        }
        src.recycle()

        // White line: every symbol pure white.
        if (!keepColour) {
            val w = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(w).drawBitmap(sym, 0f, 0f, android.graphics.Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(Palette.INK, PorterDuff.Mode.SRC_IN)
            })
            sym.recycle()
            return centred(w, size, fill)
        }
        return centred(sym, size, fill)
    }

    /** Centres a symbol and scales it so its longest side is [fill] of the icon. */
    private fun centred(sym: Bitmap, size: Int, fill: Float): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val box = bounds(sym) ?: return sym
        val longest = maxOf(box.width(), box.height()).toFloat().coerceAtLeast(1f)
        val scale = (size * fill / longest).coerceIn(0.5f, 1.8f)
        val m = android.graphics.Matrix().apply {
            postTranslate(-box.exactCenterX(), -box.exactCenterY())
            postScale(scale, scale)
            postTranslate(size / 2f, size / 2f)
        }
        Canvas(out).drawBitmap(sym, m, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
        sym.recycle()
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
