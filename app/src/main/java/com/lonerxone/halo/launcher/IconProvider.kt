package com.lonerxone.halo.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.LauncherApps
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File

/**
 * Hands Halo the icons the launcher is showing right now (icon pack, Liquid
 * Glass, White line), so the island and the notification center look like
 * the home screen. Only Halo can read it: it's guarded by the signature
 * permission both apps share.
 *
 * content://com.lonerxone.halo.launcher.icons/<package>  → a PNG
 * call("version")  → a key that changes whenever the icon look changes
 */
class IconProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        if (method != "version") return null
        return Bundle().apply { putString("v", version(c)) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        val pkg = uri.lastPathSegment ?: return null
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) return null
        val v = version(c)
        val dir = File(c.cacheDir, "halo_icons").apply { mkdirs() }
        val f = File(dir, "$pkg.${v.hashCode()}.png")
        if (!f.exists()) {
            val bmp = render(c, pkg) ?: return null
            dir.listFiles()?.filter { it.name.startsWith("$pkg.") }?.forEach { it.delete() }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun version(c: android.content.Context): String =
        "${Icons.style(c)}|${IconPacks.current(c)}|${Glass.enabled(c)}"

    /** Glass icons are drawn live on the home screen; for Halo they get a frosted tile baked in. */
    private fun render(c: android.content.Context, pkg: String): Bitmap? {
        val la = c.getSystemService(LauncherApps::class.java) ?: return null
        val info = la.getActivityList(pkg, Process.myUserHandle()).firstOrNull() ?: return null
        val app = App(info.label.toString(), info.componentName, Process.myUserHandle(), info.applicationInfo.category, info)
        val size = 144
        val style = Icons.style(c)
        val r = Icons.renderNow(c, app, style, size) ?: return null
        if (!r.glass && style != Icons.MONO) return r.bitmap
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val box = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val rad = size * 0.3f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), Color.argb(255, 64, 64, 70), Color.argb(255, 28, 28, 32), android.graphics.Shader.TileMode.CLAMP)
        cv.drawRoundRect(box, rad, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = size * 0.02f
        p.color = Color.argb(70, 255, 255, 255)
        box.inset(p.strokeWidth / 2, p.strokeWidth / 2)
        cv.drawRoundRect(box, rad, rad, p)
        cv.drawBitmap(r.bitmap, 0f, 0f, null)
        return out
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
