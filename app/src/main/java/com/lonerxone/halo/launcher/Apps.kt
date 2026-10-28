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
 * App icons. "Monochrome" uses the white glyph apps ship for Android 13's
 * themed icons (AdaptiveIconDrawable.getMonochrome, or the <monochrome> layer
 * read straight from the app's icon XML as Lawnchair does, since some skins
 * wrap the icon). Apps without one get a desaturated version of their icon.
 */
object Icons {
    class Result(val bitmap: Bitmap, val glyph: Boolean)

    private val cache = object : LruCache<String, Result>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Result) = value.bitmap.byteCount
    }
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun forget(pkg: String) {
        for (k in cache.snapshot().keys) if (k.contains(pkg)) cache.remove(k)
    }

    fun cached(app: App, mono: Boolean, size: Int): Result? = cache.get(keyOf(app, mono, size))

    private fun keyOf(app: App, mono: Boolean, size: Int) = "${app.key}|$mono|$size"

    fun load(ctx: Context, app: App, mono: Boolean, size: Int, done: (Result) -> Unit) {
        val k = keyOf(app, mono, size)
        cache.get(k)?.let { done(it); return }
        val c = ctx.applicationContext
        io.execute {
            val r = try {
                render(c, app, mono, size)
            } catch (_: Throwable) {
                null
            }
            if (r != null) {
                cache.put(k, r)
                main.post { done(r) }
            }
        }
    }

    private fun render(ctx: Context, app: App, mono: Boolean, size: Int): Result? {
        val density = ctx.resources.displayMetrics.densityDpi
        val icon = app.info.getIcon(density) ?: return null
        if (!mono) {
            val badged = ctx.packageManager.getUserBadgedIcon(icon, app.user)
            return Result(draw(badged, size, 1f, null), false)
        }
        val glyph: Drawable? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            (icon as? AdaptiveIconDrawable)?.monochrome ?: monochromeFromXml(ctx, app)
        } else {
            monochromeFromXml(ctx, app)
        }
        if (glyph != null) {
            // Adaptive layers are 108dp with the art in the middle; draw at 1.5x.
            glyph.mutate().setTint(Palette.INK)
            glyph.setTintMode(PorterDuff.Mode.SRC_IN)
            return Result(draw(glyph, size, 1.5f, null), true)
        }
        // No glyph: a soft greyscale of the real icon, so the home screen stays monochrome.
        val grey = ColorMatrix().apply { setSaturation(0f) }
        val contrast = ColorMatrix(floatArrayOf(
            1.15f, 0f, 0f, 0f, -10f,
            0f, 1.15f, 0f, 0f, -10f,
            0f, 0f, 1.15f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f,
        ))
        grey.postConcat(contrast)
        return Result(draw(icon, size, 1f, ColorMatrixColorFilter(grey)), false)
    }

    private fun draw(d: Drawable, size: Int, scale: Float, filter: ColorMatrixColorFilter?): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val extra = ((scale - 1f) * size / 2f).toInt()
        val old = d.bounds
        d.setBounds(-extra, -extra, size + extra, size + extra)
        if (filter != null) d.colorFilter = filter
        d.draw(c)
        d.bounds = old
        return bmp
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
