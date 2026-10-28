package com.lonerxone.halo.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Halo's palette, shared with the island: black, white and greys. Glass
 * cards are graphite and see-through; "paper" cards are near-white with
 * black ink, for contrast (the pale tiles in the reference designs).
 */
object Palette {
    const val INK = 0xFFFFFFFF.toInt()
    const val SILVER = 0xFFC9C9CE.toInt()
    const val GREY = 0xFF8E8E93.toInt()
    const val GRAPHITE = 0xFF2A2A2D.toInt()
    const val PAPER = 0xFFECECEE.toInt()
    const val GREEN = 0xFF34C759.toInt()
    val GLASS = Color.argb(150, 26, 26, 28)
    val GLASS_STROKE = Color.argb(30, 255, 255, 255)
    val WHITE70 = Color.argb(178, 255, 255, 255)
    val WHITE50 = Color.argb(128, 255, 255, 255)
}

object Prefs {
    const val HERO_PIC = "hero_pic"
    const val CAPSULE_PIC = "capsule_pic"
    const val CLOCK_STYLE = "clock_style" // 0 outline, 1 solid, 2 thin
    const val MONO_ICONS = "mono_icons"
    const val ICON_STYLE = "icon_style" // Icons.DARK / MONO / ORIGINAL
    const val SHOW_WALLPAPER = "show_wallpaper"
    const val DOCK = "dock"
    const val FAVORITES = "favorites"
    const val HIDDEN = "hidden"
    const val SWIPE_DOWN = "swipe_down" // 0 Halo notification center, 1 Android notifications
    const val DOUBLE_TAP_LOCK = "double_tap_lock"
    const val NOTE = "note"
    const val WIDGETS = "widgets"
    const val WEATHER_LAT = "w_lat"
    const val WEATHER_LON = "w_lon"
    const val WEATHER_CITY = "w_city"
    const val WEATHER_CACHE = "w_cache"
    const val WEATHER_AT = "w_at"
    const val FAHRENHEIT = "fahrenheit"
    const val LAUNCHES = "launches"
    const val SHOW_LABELS = "labels"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences("halo_launcher", Context.MODE_PRIVATE)

    fun picFile(c: Context, key: String) = java.io.File(c.filesDir, "$key.jpg")

    /** Component keys ("pkg/class") kept in order. */
    fun list(c: Context, key: String): MutableList<String> =
        get(c).getString(key, "")!!.split('|').filter { it.isNotBlank() }.toMutableList()

    fun setList(c: Context, key: String, v: List<String>) {
        get(c).edit().putString(key, v.joinToString("|")).apply()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = context.dpf(v)

/** A see-through graphite card with a hairline, like the island's open cards. */
fun Context.glass(radius: Float = dpf(28f)): GradientDrawable = GradientDrawable().apply {
    setColor(Palette.GLASS)
    setStroke(dp(1), Palette.GLASS_STROKE)
    cornerRadius = radius
}

/** A near-white card with black ink. */
fun Context.paper(radius: Float = dpf(28f)): GradientDrawable = GradientDrawable().apply {
    setColor(Palette.PAPER)
    cornerRadius = radius
}

fun Context.round(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

fun Context.text(sp: Float, color: Int, bold: Boolean = false, lines: Int = 1): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
    includeFontPadding = false
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

/** A light bounce on tap, used by every widget. */
fun View.bounce() {
    animate().scaleX(0.95f).scaleY(0.95f).setDuration(70L).withEndAction {
        animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
    }.start()
}
