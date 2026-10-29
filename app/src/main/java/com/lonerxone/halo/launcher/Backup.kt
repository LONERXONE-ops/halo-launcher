package com.lonerxone.halo.launcher

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup and restore: everything about your setup (home screen layout,
 * folders, stacks, dock, widgets' settings, theme, clock, icons, weather
 * city, notes) plus your background and Photo widget pictures, in one
 * .json file you keep wherever you like (Drive, Downloads…).
 *
 * Android widgets from other apps can't be carried over (Android ties them
 * to this install), so a restore leaves those out; add them again with +.
 */
object Backup {
    private const val FORMAT = "halo-launcher-backup"
    private val PICTURES = listOf(Prefs.HERO_PIC, PhotoWidget.KEY, Prefs.CAPSULE_PIC)

    fun write(c: Context, to: Uri) {
        val prefs = JSONObject()
        for ((k, v) in Prefs.get(c).all) {
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "S").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            prefs.put(k, e)
        }
        val pics = JSONObject()
        for (key in PICTURES) {
            val f = Prefs.picFile(c, key)
            if (f.exists() && f.length() < 6_000_000) pics.put(key, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("made", System.currentTimeMillis())
            .put("prefs", prefs)
            .put("pictures", pics)
        c.contentResolver.openOutputStream(to, "wt")?.use { it.write(root.toString().toByteArray()) } ?: throw IllegalStateException("Can't write there")
    }

    /** Replaces the current setup with the backup's. */
    fun read(c: Context, from: Uri) {
        val text = c.contentResolver.openInputStream(from)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw IllegalStateException("Can't read that file")
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT) throw IllegalStateException("That isn't a Halo Launcher backup")
        val prefs = root.getJSONObject("prefs")
        val ed = Prefs.get(c).edit().clear()
        for (k in prefs.keys()) {
            val e = prefs.getJSONObject(k)
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "S" -> {
                    val a = e.getJSONArray("v")
                    ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        // Other apps' widgets belong to the old install: leave them out.
        ed.remove(Prefs.WIDGETS)
        prefs.optJSONObject("home_layout")?.optString("v")?.let { lay ->
            ed.putString("home_layout", lay.split('|').filter { !it.startsWith("aw:") }.joinToString("|"))
        }
        ed.commit()
        val pics = root.optJSONObject("pictures")
        for (key in PICTURES) {
            val f: File = Prefs.picFile(c, key)
            val b64 = pics?.optString(key).orEmpty()
            if (b64.isNotEmpty()) f.writeBytes(Base64.decode(b64, Base64.DEFAULT)) else f.delete()
        }
        HomeLayout.forget()
        Icons.forgetAll()
        Glass.invalidate()
    }
}
