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

    class Result(val bitmap: Bitmap)

    private val cache = object : LruCache<String, Result>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Result) = value.bitmap.byteCount
    }
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun style(ctx: Context): Int = Prefs.get(ctx).getInt(Prefs.ICON_STYLE, DARK)

    fun forget(pkg: String) {
        for (k in cache.snapshot().keys) if (k.contains(pkg)) cache.remove(k)
    }

    fun cached(app: App, style: Int, size: Int): Result? = cache.get(keyOf(app, style, size))

    private fun keyOf(app: App, style: Int, size: Int) = "${app.key}|$style|$size"

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
        val glyph: Drawable? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            (icon as? AdaptiveIconDrawable)?.monochrome ?: monochromeFromXml(ctx, app)
        } else {
            monochromeFromXml(ctx, app)
        }
        if (style == MONO && glyph != null) {
            glyph.mutate().setTint(Palette.INK)
            glyph.setTintMode(PorterDuff.Mode.SRC_IN)
            return Result(draw(glyph, size, 1.5f))
        }
        return Result(dark(icon, glyph, size))
    }

    /** iOS-style dark icon: graphite tile, brand-coloured glyph. */
    private fun dark(icon: Drawable, glyph: Drawable?, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size * 0.27f
        val tile = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), 0xFF2E2E31.toInt(), 0xFF141416.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        val rect = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
        c.drawRoundRect(rect, r, r, tile)
        val adaptive = icon as? AdaptiveIconDrawable
        // Brand colour: the icon's background layer (or the whole icon), made bright enough for black.
        val brandSource = adaptive?.background ?: icon
        var brand = brightOnBlack(average(brandSource))
        if (isGrey(brand)) adaptive?.foreground?.let { fg -> average(fg).takeIf { !isGrey(it) }?.let { brand = brightOnBlack(it) } }
        val save = c.save()
        val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        when {
            glyph != null -> {
                val g = glyph.mutate()
                g.setTint(brand)
                g.setTintMode(PorterDuff.Mode.SRC_IN)
                drawInto(c, g, size, 1.4f)
            }
            adaptive?.foreground != null -> drawInto(c, adaptive.foreground, size, 1.0f)
            else -> {
                // A plain (non-adaptive) icon: shown a touch smaller and dimmed on the tile.
                icon.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.88f, 0.88f, 0.88f, 1f) })
                drawInto(c, icon, size, 0.78f)
                icon.colorFilter = null
            }
        }
        c.restoreToCount(save)
        // A hairline so the tile reads on pure black.
        val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = size / 90f
            color = android.graphics.Color.argb(40, 255, 255, 255)
        }
        rect.inset(edge.strokeWidth / 2f, edge.strokeWidth / 2f)
        c.drawRoundRect(rect, r, r, edge)
        return bmp
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
