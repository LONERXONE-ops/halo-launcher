package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/**
 * Halo's palette: black, white and opaque greys (no see-through white, as the
 * widget guidelines advise), with one accent, red, used sparingly: badges,
 * low battery, "now" in the agenda.
 */
object Palette {
    const val INK = 0xFFF5F5F7.toInt()
    const val SILVER = 0xFFC9C9CE.toInt()
    const val GREY = 0xFF8E8E93.toInt()
    const val DIM = 0xFF5A5A5F.toInt()
    const val GRAPHITE = 0xFF2C2C2E.toInt()
    const val TILE = 0xFF141416.toInt()
    const val TILE2 = 0xFF1C1C1E.toInt()
    const val PAPER = 0xFFECECEE.toInt()
    const val RED = 0xFFFF3B30.toInt()
    const val GREEN = 0xFF34C759.toInt()
    // Older names, kept for the screens that use them.
    val WHITE70 = SILVER
    val WHITE50 = GREY
    val GLASS = TILE
    val GLASS_STROKE = Color.argb(30, 255, 255, 255)
}

/**
 * Bundled fonts (SIL Open Font License, see docs/licenses):
 * Inter for text, Doto (dot matrix, the Nothing-style numbers), Graduate
 * (varsity poster clock) and Anton (bold condensed clock).
 */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(c: Context, name: String, fallback: Typeface): Typeface = cache.getOrPut(name) {
        try {
            Typeface.createFromAsset(c.applicationContext.assets, "fonts/$name.ttf")
        } catch (_: Exception) {
            fallback
        }
    }

    fun regular(c: Context) = load(c, "inter_regular", Typeface.DEFAULT)
    fun semibold(c: Context) = load(c, "inter_semibold", Typeface.DEFAULT_BOLD)
    fun light(c: Context) = load(c, "inter_light", Typeface.DEFAULT)
    fun dots(c: Context) = load(c, "doto_black", Typeface.MONOSPACE)
    fun varsity(c: Context) = load(c, "graduate", Typeface.DEFAULT_BOLD)
    fun bold(c: Context) = load(c, "anton", Typeface.DEFAULT_BOLD)
}

object Prefs {
    const val HERO_PIC = "hero_pic"
    const val CAPSULE_PIC = "capsule_pic"
    const val CLOCK_STYLE = "clock_style" // StackedClock.VARSITY / DOTS / BOLD / THIN
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
    /** Built-in widgets switched off (comma list of ids). */
    const val WIDGETS_OFF = "widgets_off"
    const val STEP_GOAL = "step_goal"
    const val ANIMATIONS = "animations"
    /** Glass.THEME_GLASS or Glass.THEME_SOLID. */
    const val THEME = "theme"
    /** Order of the built-in widgets ("|" list of ids). */
    const val W_ORDER = "w_order"
    /** Dock icon count chosen by the user is just the dock list length; this is whether labels show. */
    const val DOCK_LABELS = "dock_labels"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences("halo_launcher", Context.MODE_PRIVATE)

    fun picFile(c: Context, key: String) = java.io.File(c.filesDir, "$key.jpg")

    /** Component keys ("pkg/class") kept in order. */
    fun list(c: Context, key: String): MutableList<String> =
        get(c).getString(key, "")!!.split('|').filter { it.isNotBlank() }.toMutableList()

    fun setList(c: Context, key: String, v: List<String>) {
        get(c).edit().putString(key, v.joinToString("|")).apply()
    }

    fun widgetOn(c: Context, id: String) = id !in get(c).getString(WIDGETS_OFF, "")!!.split(',')

    fun setWidget(c: Context, id: String, on: Boolean) {
        val off = get(c).getString(WIDGETS_OFF, "")!!.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (on) off.remove(id) else off.add(id)
        get(c).edit().putString(WIDGETS_OFF, off.joinToString(",")).apply()
    }

    fun animations(c: Context) = get(c).getBoolean(ANIMATIONS, true)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = context.dpf(v)

fun Context.glass(radius: Float = dpf(28f)): GradientDrawable = round(Palette.TILE, radius)

fun Context.paper(radius: Float = dpf(28f)): GradientDrawable = round(Palette.PAPER, radius)

fun Context.round(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

/** Text in Inter. [caps] makes the small spaced labels widgets use. */
fun Context.text(sp: Float, color: Int, bold: Boolean = false, lines: Int = 1, caps: Boolean = false): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (bold || caps) Fonts.semibold(this@text) else Fonts.regular(this@text)
    includeFontPadding = false
    if (caps) {
        isAllCaps = true
        letterSpacing = 0.14f
    }
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

/** A light bounce on tap. */
fun View.bounce() {
    animate().setStartDelay(0).scaleX(0.94f).scaleY(0.94f).setDuration(80L).withEndAction {
        animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.5f)).setDuration(260L).start()
    }.start()
}

/** Shrinks while pressed and springs back on release, like iOS widgets. Keeps clicks working. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().setStartDelay(0).scaleX(0.96f).scaleY(0.96f).setInterpolator(DecelerateInterpolator()).setDuration(120L).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(300L).start()
        }
        false
    }
}

/** Fades and lifts a view in, after [delay] ms. */
fun View.enter(delay: Long, distance: Float = dpf(22f)) {
    if (!Prefs.animations(context)) return
    alpha = 0f
    translationY = distance
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(460L)
        .setInterpolator(DecelerateInterpolator(2.2f)).start()
}
