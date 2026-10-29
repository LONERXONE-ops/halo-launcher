package com.lonerxone.halo.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Icon packs: any pack installed on the phone that follows the standard
 * format every launcher uses (Nova, Lawnchair, ADW...), read from its
 * appfilter.xml. That covers the thousands of packs on the Play Store and
 * F-Droid, including Liquid Glass-style packs, and any pack you make.
 *
 * Apps the pack doesn't draw get the pack's own frame (iconback / iconmask
 * / iconupon) so they still match, like Nova does.
 */
object IconPacks {
    class Pack(val pkg: String, val label: String)

    private class Loaded(
        val res: Resources,
        val pkg: String,
        val map: Map<String, String>,
        val backs: List<String>,
        val mask: String?,
        val upon: String?,
        val scale: Float,
    )

    /** The intents icon packs announce themselves with. */
    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "org.adw.ActivityStarter.THEMES",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
        "com.dlto.atom.launcher.THEME",
        "app.lawnchair.icons.THEMED_ICON",
    )

    fun installed(c: Context): List<Pack> {
        val pm = c.packageManager
        val found = LinkedHashMap<String, Pack>()
        for (a in ACTIONS) {
            val list = try {
                pm.queryIntentActivities(Intent(a), PackageManager.GET_META_DATA)
            } catch (_: Exception) {
                emptyList()
            }
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == c.packageName || pkg in found) continue
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                found[pkg] = Pack(pkg, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** The pack in use ("" = the apps' own icons). */
    fun current(c: Context): String = Prefs.get(c).getString(Prefs.ICON_PACK, "").orEmpty()

    fun isInstalled(c: Context, pkg: String) = try {
        c.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private val loaded = HashMap<String, Loaded?>()

    @Synchronized
    private fun load(c: Context, pkg: String): Loaded? {
        if (loaded.containsKey(pkg)) return loaded[pkg]
        val l = try {
            parse(c, pkg)
        } catch (_: Throwable) {
            null
        }
        loaded[pkg] = l
        return l
    }

    @Synchronized
    fun forget() {
        loaded.clear()
    }

    private fun parse(c: Context, pkg: String): Loaded? {
        val pc = c.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
        val res = pc.resources
        val parser: XmlPullParser = run {
            val id = res.getIdentifier("appfilter", "xml", pkg)
            if (id != 0) {
                res.getXml(id)
            } else {
                val f = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                f.setInput(pc.assets.open("appfilter.xml"), "UTF-8")
                f
            }
        }
        val map = HashMap<String, String>()
        val backs = ArrayList<String>()
        var mask: String? = null
        var upon: String? = null
        var scale = 1f
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val comp = parser.getAttributeValue(null, "component")
                        val dr = parser.getAttributeValue(null, "drawable")
                        if (comp != null && dr != null) {
                            // ComponentInfo{pkg/cls} → pkg/cls
                            val k = comp.substringAfter('{', "").substringBefore('}', "")
                            if (k.isNotEmpty() && k !in map) map[k] = dr
                        }
                    }
                    "iconback" -> for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i).startsWith("img")) backs += parser.getAttributeValue(i)
                    "iconmask" -> mask = parser.getAttributeValue(null, "img1")
                    "iconupon" -> upon = parser.getAttributeValue(null, "img1")
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
            ev = parser.next()
        }
        return Loaded(res, pkg, map, backs, mask, upon, scale)
    }

    private fun drawable(l: Loaded, name: String, dpi: Int): Drawable? {
        val id = l.res.getIdentifier(name, "drawable", l.pkg).takeIf { it != 0 } ?: l.res.getIdentifier(name, "mipmap", l.pkg)
        if (id == 0) return null
        return try {
            l.res.getDrawableForDensity(id, dpi, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The pack's icon for an app, or (when the pack has a frame) the app's
     * own icon dressed in it. Null = use the app's icon as it is.
     */
    fun iconFor(c: Context, app: App, own: Drawable, size: Int): Drawable? {
        val pkg = current(c)
        if (pkg.isEmpty()) return null
        val l = load(c, pkg) ?: return null
        val dpi = c.resources.displayMetrics.densityDpi
        val key = app.component.packageName + "/" + app.component.className
        l.map[key]?.let { name -> drawable(l, name, dpi)?.let { return it } }
        // Some packs list the activity with a leading dot or just the package.
        l.map.entries.firstOrNull { it.key.startsWith(app.component.packageName + "/") }?.let { e ->
            drawable(l, e.value, dpi)?.let { return it }
        }
        if (l.backs.isEmpty()) return null
        // Not in the pack: the app's icon, scaled, masked and framed the pack's way.
        val back = drawable(l, l.backs[Math.floorMod(app.pkg.hashCode(), l.backs.size)], dpi) ?: return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        back.setBounds(0, 0, size, size)
        back.draw(cv)
        val inner = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val ic = Canvas(inner)
        val s = (size * l.scale).toInt()
        val o = (size - s) / 2
        own.setBounds(o, o, o + s, o + s)
        own.draw(ic)
        l.mask?.let { drawable(l, it, dpi) }?.let { m ->
            val mb = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            m.setBounds(0, 0, size, size)
            m.draw(Canvas(mb))
            ic.drawBitmap(mb, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        cv.drawBitmap(inner, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        l.upon?.let { drawable(l, it, dpi) }?.let { u ->
            u.setBounds(0, 0, size, size)
            u.draw(cv)
        }
        return BitmapDrawable(c.resources, out)
    }

    // ---------------------------------------------------------------------
    // Real, free icon packs you can get from inside the launcher. They're
    // open-source projects published on F-Droid / IzzyOnDroid; the launcher
    // downloads the official APK from there and Android installs it.
    // ---------------------------------------------------------------------

    class Source(
        val pkg: String,
        val name: String,
        val about: String,
        val by: String,
        val repo: String,
        /** "fdroid" or "izzy" */
        val store: String,
    )

    val CATALOG = listOf(
        Source("website.leifs.delta.foss", "Delta", "Soft pastel colour icons, 14,000+ apps.", "Delta Icons team", "https://github.com/Delta-Icons/android", "fdroid"),
        Source("com.donnnno.arcticons", "Arcticons", "Clean white line icons, 15,000+ apps.", "Arcticons team", "https://github.com/Arcticons-Team/Arcticons", "fdroid"),
        Source("app.lawnchair.lawnicons", "Lawnicons", "Google-style line icons, the Lawnchair team's pack.", "Lawnchair team", "https://github.com/LawnchairLauncher/lawnicons", "izzy"),
        Source("dev.appstract.iconpack", "Appstract", "Abstract, hand-drawn shapes for popular apps.", "Appstract", "https://f-droid.org/packages/dev.appstract.iconpack/", "fdroid"),
        Source("de.moooon.acrylicons", "Acrylicons", "Real acrylic paintings as icons (public domain).", "Mondstern", "https://f-droid.org/packages/de.moooon.acrylicons/", "fdroid"),
    )

    /** Finds the latest APK of a catalog pack (on a background thread). */
    fun latestApk(s: Source): Pair<String, Long>? {
        val api = if (s.store == "izzy") "https://apt.izzysoft.de/fdroid/api/v1/packages/${s.pkg}" else "https://f-droid.org/api/v1/packages/${s.pkg}"
        val repo = if (s.store == "izzy") "https://apt.izzysoft.de/fdroid/repo/" else "https://f-droid.org/repo/"
        val c = URL(api).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "HaloLauncher-Android")
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val j = JSONObject(body)
        val vc = j.optLong("suggestedVersionCode", -1L).takeIf { it > 0 } ?: j.optJSONArray("packages")?.optJSONObject(0)?.optLong("versionCode") ?: return null
        return "$repo${s.pkg}_$vc.apk" to -1L
    }
}
