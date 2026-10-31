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
/**
 * Halo Launcher's colours. White ink, and greys that (with Adaptive colour
 * on) take on the hue of your wallpaper: tiles, glass, dock, widgets, the
 * app list and settings all shift together, like Material You but subtler.
 */
object Palette {
    const val INK = 0xFFF5F5F7.toInt()
    var SILVER = 0xFFC9C9CE.toInt()
    var GREY = 0xFF8E8E93.toInt()
    var DIM = 0xFF5A5A5F.toInt()
    var GRAPHITE = 0xFF2C2C2E.toInt()
    var TILE = 0xFF141416.toInt()
    var TILE2 = 0xFF1C1C1E.toInt()
    /** The wallpaper's colour, made vivid (white when Adaptive colour is off). */
    var ACCENT = INK
    const val PAPER = 0xFFECECEE.toInt()
    const val RED = 0xFFFF3B30.toInt()
    const val GREEN = 0xFF34C759.toInt()
    // Older names, kept for the screens that use them.
    val WHITE70 get() = SILVER
    val WHITE50 get() = GREY
    val GLASS get() = TILE
    val GLASS_STROKE = Color.argb(30, 255, 255, 255)

    const val ADAPT_OFF = 0
    const val ADAPT_SUBTLE = 1
    const val ADAPT_RICH = 2
    /** Strongest: deeper tint everywhere, and switches and highlights in the wallpaper's colour. */
    const val ADAPT_VIVID = 3
    val ADAPT_NAMES = listOf("Off", "Subtle", "Rich", "Vivid")

    /** On-switches and highlights: the wallpaper's colour with Adaptive colour, else green. */
    var SWITCH = GREEN
    /** Liquid Glass: the hue washed through every pane, and how strongly (0 = Adaptive colour off). */
    var GLASS_TINT = 0xFF202024.toInt()
    var GLASS_TINT_AMT = 0f

    private var lastKey = ""
    /** What the palette was last worked out from (screens compare it to know when to recolour). */
    val key get() = lastKey

    /**
     * Works the palette out from the wallpaper's colour. Returns true when it
     * changed (so the home screen redraws in the new colours).
     */
    fun adapt(c: Context): Boolean {
        // The glass's light (shine off bright wallpapers, dispersion) changes with the wallpaper too.
        Glass.updateLight(c)
        val mode = Prefs.get(c).getInt(Prefs.ADAPTIVE, ADAPT_OFF)
        val wc = if (mode == ADAPT_OFF) null else Glass.wallColor(c)
        val key = "$mode/$wc/${Glass.shine}/${Glass.disperse}"
        if (key == lastKey) return false
        lastKey = key
        if (wc == null) {
            SILVER = 0xFFC9C9CE.toInt()
            GREY = 0xFF8E8E93.toInt()
            DIM = 0xFF5A5A5F.toInt()
            GRAPHITE = 0xFF2C2C2E.toInt()
            TILE = 0xFF141416.toInt()
            TILE2 = 0xFF1C1C1E.toInt()
            ACCENT = INK
            SWITCH = GREEN
            GLASS_TINT = 0xFF202024.toInt()
            GLASS_TINT_AMT = 0f
            return true
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(wc, hsv)
        val h = hsv[0]
        // Grey wallpapers stay grey; colourful ones lend their hue.
        // Grey wallpapers stay grey (with just a breath of their tone); colourful ones lend their hue.
        val grey = hsv[1] < 0.12f
        val strength = when (mode) {
            ADAPT_VIVID -> 0.95f
            ADAPT_RICH -> 0.75f
            else -> 0.42f
        }
        val s = if (grey) 0.04f else (hsv[1].coerceIn(0.25f, 0.9f) * strength)
        fun tone(sat: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, sat.coerceIn(0f, 1f), v))
        val lift = when (mode) {
            ADAPT_VIVID -> 0.06f
            ADAPT_RICH -> 0.035f
            else -> 0f
        }
        TILE = tone(s, 0.095f + lift)
        TILE2 = tone(s, 0.125f + lift * 1.2f)
        GRAPHITE = tone(s * 0.9f, 0.21f + lift)
        DIM = tone(s * 0.6f, 0.42f)
        GREY = tone(s * 0.42f, 0.64f)
        SILVER = tone(s * 0.24f, 0.86f)
        ACCENT = if (grey) INK else Color.HSVToColor(floatArrayOf(h, hsv[1].coerceIn(0.45f, 0.78f), 0.97f))
        // Switches: the wallpaper's colour once it's strong enough to read as "on".
        // Glass: a mid-dark version of the hue, washed in more strongly per level.
        GLASS_TINT = if (grey) Color.rgb(40, 40, 44) else Color.HSVToColor(floatArrayOf(h, (hsv[1] * 0.85f).coerceIn(0.3f, 0.85f), 0.42f))
        GLASS_TINT_AMT = if (grey) 0.03f else when (mode) {
            ADAPT_VIVID -> 0.24f
            ADAPT_RICH -> 0.16f
            else -> 0.09f
        }
        SWITCH = if (grey || mode == ADAPT_SUBTLE) GREEN else Color.HSVToColor(floatArrayOf(h, hsv[1].coerceIn(0.5f, 0.8f), 0.88f))
        return true
    }
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
    const val ICON_STYLE = "icon_style" // Icons.ORIGINAL / GLASS / MONO (unset = follow the theme)
    /** Package of the icon pack in use ("" = the apps' own icons). */
    const val ICON_PACK = "icon_pack"
    /** Clock face id (ClockFaces.ALL), layout (0 auto/stacked, 1 one line), alignment (0 start, 1 centre, 2 end). */
    const val CLOCK_FACE = "clock_face"
    const val CLOCK_LAYOUT = "clock_layout"
    const val CLOCK_ALIGN = "clock_align"
    /** 0 white, 1 wallpaper colour, 2 grey, 3 black. */
    const val CLOCK_COLOR = "clock_color"
    /** Liquid Glass text: 0 follow the theme, 1 on, 2 off. */
    const val CLOCK_GLASS = "clock_glass"
    const val DATE_STYLE = "date_style"
    /** Clock extras: hours (0 phone setting, 1 24-hour, 2 12-hour), leading zero, AM/PM, separator, two-tone minutes, shadow. */
    const val CLOCK_HOURS = "clock_hours"
    const val CLOCK_ZERO = "clock_zero"
    const val CLOCK_AMPM = "clock_ampm"
    const val CLOCK_SEP = "clock_sep"
    const val CLOCK_TWOTONE = "clock_twotone"
    const val CLOCK_SHADOW = "clock_shadow"
    /** Wallpapers: shuffle saved ones daily, and the day it last changed. */
    const val WALL_DAILY = "wall_daily"
    const val WALL_DAY = "wall_day"
    /** Adaptive colour: Palette.ADAPT_OFF / SUBTLE / RICH. */
    const val ADAPTIVE = "adaptive"
    /** Motion.OPEN_* and Motion.PAGE_*. */
    const val OPEN_ANIM = "open_anim"
    const val PAGE_EFFECT = "page_effect"
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
    /** Names under apps on the home screen (off by default, like a clean iOS-style screen). */
    const val SHOW_LABELS = "labels"
    /** Home screen app icon size: 0 normal, 1 large (default), 2 extra large. */
    const val HOME_ICON_SIZE = "home_icon_size"
    /** Built-in widgets switched off (comma list of ids). */
    const val WIDGETS_OFF = "widgets_off"
    const val STEP_GOAL = "step_goal"
    const val ANIMATIONS = "animations"
    const val ONBOARDED = "onboarded_v"
    /** Glass.THEME_GLASS or Glass.THEME_SOLID. */
    const val THEME = "theme"
    /** Order of the built-in widgets ("|" list of ids). */
    const val W_ORDER = "w_order"
    /** Dock icon count chosen by the user is just the dock list length; this is whether labels show. */
    const val DOCK_LABELS = "dock_labels"
    /** Page 1's order: "clock|music" or "music|clock". */
    const val P1_ORDER = "p1_order"
    const val MUSIC_OFF = "music_off"
    /** 0 glass, 1 solid, 2 clear (no background), 3 tinted with your wallpaper's colour. */
    const val DOCK_STYLE = "dock_style"
    /** App list: 0 = scroll up and down, 1 = swipe sideways through pages. */
    const val DRAWER_LAYOUT = "drawer_layout"
    /** App list columns: 0 = 4, 1 = 5. */
    const val DRAWER_COLS = "drawer_cols"
    /** Names under apps in the app list. */
    const val DRAWER_NAMES = "drawer_names"
    /** App list icon size: 0 Normal, 1 Large, 2 Extra large. */
    const val DRAWER_SIZE = "drawer_size"
    /** App list arrangement: 0 = A to Z, 1 = grouped into folders by kind (Social, Games…). */
    const val DRAWER_GROUPS = "drawer_groups"
    /** Experimental chromatic dispersion on Liquid Glass. */
    const val GLASS_DISPERSE = "glass_disperse"
    // ----- Lock screen (Settings → Lock screen) -----
    const val LOCK_ON = "lock_on"
    /** 0 Classic (date over the time), 1 Stacked (big hours over minutes), 2 Side (on the left). */
    const val LOCK_LAYOUT = "lock_layout"
    const val LOCK_CLOCK_SIZE = "lock_clock_size"
    /** Up to two widget ids. */
    const val LOCK_WIDGETS = "lock_widgets"
    /** 0 Show, 1 Just how many, 2 Hidden. */
    const val LOCK_NOTES = "lock_notes"
    const val LOCK_MUSIC = "lock_music"
    /** Bottom shortcuts: 0 none, 1 flashlight, 2 camera. */
    const val LOCK_LEFT = "lock_left"
    const val LOCK_RIGHT = "lock_right"
    /** 0 Same picture as home, 1 the phone's lock wallpaper. */
    const val LOCK_WALL = "lock_wall"
    /** Your own order for the app list (app keys and folder ids); empty = A to Z. */
    const val DRAWER_ORDER = "drawer_order"
    /** Notification counts on the dock's apps. */
    const val DOCK_BADGES = "dock_badges"
    /** Colour for Tinted icons (0 = from the wallpaper). */
    const val ICON_TINT = "icon_tint"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences("halo_launcher", Context.MODE_PRIVATE)

    fun picFile(c: Context, key: String) = java.io.File(c.filesDir, "$key.jpg")

    /** Component keys ("pkg/class") kept in order. */
    fun list(c: Context, key: String): MutableList<String> =
        get(c).getString(key, "")!!.split('|').filter { it.isNotBlank() }.toMutableList()

    fun setList(c: Context, key: String, v: List<String>) {
        get(c).edit().putString(key, v.joinToString("|")).apply()
    }

    /** Widgets that start switched off (newer ones, so an update doesn't crowd your page). */
    private val OFF_BY_DEFAULT = setOf("photo", "timer")
    private const val WIDGETS_ON = "widgets_on"

    /** Is this Halo widget on the home screen? */
    fun widgetOn(c: Context, id: String): Boolean = HomeLayout.has(c, "w:$id") || Groups.inStack(c, id)

    /** Put a Halo widget on the home screen (first free spot from page 2) or take it off. */
    fun setWidget(c: Context, id: String, on: Boolean) {
        if (on) {
            if (widgetOn(c, id)) return
            val (sx, sy) = HomeLayout.spansFor(HomeWidgets.spec(id).defaultSize)
            HomeLayout.add(c, "w:$id", sx, sy, preferPage = 1)
        } else {
            HomeLayout.remove(c, "w:$id")
        }
    }

    /** Before the home grid: whether a widget was switched on (used once, to carry your setup over). */
    fun widgetOnLegacy(c: Context, id: String): Boolean =
        if (id in OFF_BY_DEFAULT) id in get(c).getString(WIDGETS_ON, "")!!.split(',') else id !in get(c).getString(WIDGETS_OFF, "")!!.split(',')

    private fun setWidgetLegacy(c: Context, id: String, on: Boolean) {
        val off = get(c).getString(WIDGETS_OFF, "")!!.split(',').filter { it.isNotBlank() }.toMutableSet()
        val onSet = get(c).getString(WIDGETS_ON, "")!!.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (on) {
            off.remove(id)
            onSet.add(id)
        } else {
            off.add(id)
            onSet.remove(id)
        }
        get(c).edit().putString(WIDGETS_OFF, off.joinToString(",")).putString(WIDGETS_ON, onSet.joinToString(",")).apply()
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
/**
 * A tap's little dip. It never grows past its real size (growing past it,
 * even briefly, is what made things overlap their neighbours).
 */
fun View.bounce() {
    // Liquid Glass answers a tap with a quick swell and a springy settle.
    if (LiquidTouch.usable(this)) {
        animate().setStartDelay(0).scaleX(1.09f).scaleY(1.09f).setDuration(110L).setInterpolator(DecelerateInterpolator(2f)).withEndAction {
            animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(3.2f)).setDuration(560L).start()
        }.start()
        return
    }
    animate().setStartDelay(0).scaleX(0.94f).scaleY(0.94f).setDuration(80L).withEndAction {
        animate().scaleX(1f).scaleY(1f).setInterpolator(DecelerateInterpolator(2f)).setDuration(220L).start()
    }.start()
}

/**
 * Shrinks while pressed and eases back on release. The dip waits a moment
 * (Android's tap delay), so starting a scroll never makes whatever was under
 * your finger dip and pop; and it never grows past its size on the way back.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable() {
    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    val press = Runnable { animate().setStartDelay(0).scaleX(0.94f).scaleY(0.94f).setInterpolator(DecelerateInterpolator(2f)).setDuration(90L).start() }
    fun release(v: View) {
        v.removeCallbacks(press)
        if (v.scaleX != 1f || v.scaleY != 1f) v.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setInterpolator(DecelerateInterpolator(2f)).setDuration(220L).start()
    }
    // On Liquid Glass the press is the liquid one: the glass swells and
    // follows your finger instead of dipping (decided at each touch, so a
    // theme change applies straight away).
    var liquid: LiquidTouch? = null
    var liquidNow = false
    setOnTouchListener { v, e ->
        if (e.actionMasked == MotionEvent.ACTION_DOWN) liquidNow = LiquidTouch.usable(v)
        if (liquidNow) {
            val l = liquid ?: LiquidTouch(v).also { liquid = it }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> l.down(e)
                MotionEvent.ACTION_MOVE -> l.move(e)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> l.up()
            }
            return@setOnTouchListener false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                v.postDelayed(press, android.view.ViewConfiguration.getTapTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) release(v)
            MotionEvent.ACTION_UP -> {
                // A quick tap: show the dip now, then come back.
                if (v.scaleX == 1f && v.handler?.hasCallbacks(press) == true) {
                    v.removeCallbacks(press)
                    v.animate().setStartDelay(0).scaleX(0.94f).scaleY(0.94f).setDuration(70L).withEndAction { release(v) }.start()
                } else {
                    release(v)
                }
            }
            MotionEvent.ACTION_CANCEL -> release(v)
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

/**
 * A spring, as an interpolator: it shoots slightly past the target and
 * settles, the way iOS and One UI motion feels. [bounce] 0 = no overshoot,
 * 1 = lively. Fast at the start, so things feel snappy.
 */
class Spring(private val bounce: Float = 0.5f) : android.view.animation.Interpolator {
    // Tuned so the overshoot stays small (about 2-6%): lively, but nothing
    // swings far enough past its place to cross into its neighbours.
    private val k = 9f - 2f * bounce
    private val w = 7.5f + 2f * bounce
    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        return (1.0 - Math.exp((-k * t).toDouble()) * Math.cos((w * t).toDouble())).toFloat()
    }
}
