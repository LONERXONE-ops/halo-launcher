package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/**
 * Halo Launcher's own widgets, built on Apple's widget rules: one idea each,
 * live information, 16dp margins, one big number (dot matrix) and everything
 * else small, opaque greys for hierarchy, red only where it means something.
 *
 * Every widget comes in three sizes, like iOS: Small (a square), Medium
 * (full width, one row high) and Large (full width, two rows high). Bigger
 * sizes show more, not just the same thing larger.
 */
interface WidgetActions {
    fun askPermission(permission: String)
    fun openSettingsPage(page: String)
    fun openCalendar()
    fun editNote()
    fun refreshWeather()
    fun openUsageAccess()
    fun launchPackage(pkg: String, from: View)
    fun pickPhoto()
}

class WidgetSpec(val id: String, val name: String, val about: String, val defaultSize: Int)

object HomeWidgets {
    const val SMALL = 0
    const val MEDIUM = 1
    const val LARGE = 2
    val SIZE_NAMES = listOf("Small", "Medium", "Large")

    val ALL = listOf(
        WidgetSpec("weather", "Weather", "Now, today's high and low; bigger sizes add the next hours and days.", MEDIUM),
        WidgetSpec("battery", "Battery", "Charge in dots, and time until full while charging.", SMALL),
        WidgetSpec("steps", "Steps", "Today's steps against your goal.", SMALL),
        WidgetSpec("agenda", "Up next", "Today's date and your next events; Large lists the whole day.", MEDIUM),
        WidgetSpec("screen", "Screen time", "Time on your phone today; bigger sizes list your top apps.", SMALL),
        WidgetSpec("note", "Note", "A note to yourself, always in view.", SMALL),
        WidgetSpec("photo", "Photo", "A picture you love, edge to edge (like Nothing's Collector and iOS Photos).", SMALL),
        WidgetSpec("analog", "Clock face", "An analog clock with a sweeping hour and minute hand.", SMALL),
        WidgetSpec("month", "Month", "This month at a glance, today marked in your colour.", SMALL),
        WidgetSpec("countdown", "Countdown", "Days until something you're waiting for. Tap to set it.", SMALL),
        WidgetSpec("storage", "Storage", "How full your phone is.", SMALL),
        WidgetSpec("timer", "Timer", "One tap starts a countdown (like Nothing's Glyph Timer); your clock app rings when it's done.", SMALL),
    )

    fun spec(id: String) = ALL.first { it.id == id }

    fun create(c: Context, id: String, a: WidgetActions): BaseWidget? = when (id) {
        "weather" -> WeatherWidget(c, a)
        "battery" -> BatteryWidget(c, a)
        "steps" -> StepsWidget(c, a)
        "agenda" -> AgendaWidget(c, a)
        "screen" -> ScreenTimeWidget(c, a)
        "note" -> NoteWidget(c, a)
        "photo" -> PhotoWidget(c, a)
        "timer" -> TimerWidget(c, a)
        "analog" -> AnalogWidget(c, a)
        "month" -> MonthWidget(c, a)
        "countdown" -> CountdownWidget(c, a)
        "storage" -> StorageWidget(c, a)
        else -> null
    }?.also { it.size = sizeOf(c, id) }

    fun sizeOf(c: Context, id: String): Int = HomeLayout.get(c, "w:$id")?.let { HomeLayout.sizeOf(it) } ?: spec(id).defaultSize

    /** Resizes the widget on the home grid (moving it if it no longer fits where it is). */
    fun setSize(c: Context, id: String, size: Int) {
        val (sx, sy) = HomeLayout.spansFor(size.coerceIn(SMALL, LARGE))
        HomeLayout.resize(c, "w:$id", sx, sy)
    }

    /** The user's order (new widgets go at the end). */
    fun order(c: Context): List<String> {
        val saved = Prefs.list(c, Prefs.W_ORDER).filter { id -> ALL.any { it.id == id } }
        return saved + ALL.map { it.id }.filter { it !in saved }
    }

    /** Put [id] where [target] is (dragging in edit mode). */
    fun place(c: Context, id: String, target: String) {
        val o = order(c).toMutableList()
        val j = o.indexOf(target)
        if (j < 0 || !o.remove(id)) return
        o.add(j, id)
        Prefs.setList(c, Prefs.W_ORDER, o)
    }

    fun move(c: Context, id: String, by: Int) {
        val o = order(c).toMutableList()
        val i = o.indexOf(id)
        val j = (i + by).coerceIn(0, o.size - 1)
        if (i < 0 || i == j) return
        o.removeAt(i)
        o.add(j, id)
        Prefs.setList(c, Prefs.W_ORDER, o)
    }
}

/** The shared card: glass or solid, 16dp padding, a small caps title. */
@SuppressLint("ViewConstructor")
abstract class BaseWidget(context: Context, protected val actions: WidgetActions, title: String) : LinearLayout(context) {
    abstract val id: String
    protected val titleView: TextView = context.text(10.5f, Palette.SILVER, caps = true).apply { text = title }
    protected val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    /** HomeWidgets.SMALL / MEDIUM / LARGE; changing it redraws the widget for that size. */
    var size = HomeWidgets.SMALL
        set(v) {
            field = v
            refresh()
        }

    init {
        orientation = VERTICAL
        background = surface(dpf(26f))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        header.addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(header)
        pressable()
    }

    abstract fun refresh()

    protected fun bigSize(): Float = when (size) {
        HomeWidgets.LARGE -> 56f
        HomeWidgets.MEDIUM -> 46f
        else -> 38f
    }

    protected fun big(): TextView = context.text(38f, Palette.INK).apply {
        typeface = Fonts.dots(context)
        includeFontPadding = false
    }

    protected fun small(color: Int = Palette.SILVER, sp: Float = 11.5f, lines: Int = 2): TextView = context.text(sp, color, lines = lines)

    protected fun spacer(parent: LinearLayout = this) = View(context).also { parent.addView(it, LayoutParams(1, 0, 1f)) }
}

// ---------------------------------------------------------------------------

@SuppressLint("ViewConstructor")
class WeatherWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Weather") {
    override val id = "weather"
    private val range = c.text(10.5f, Palette.SILVER, caps = true)
    private val body = LinearLayout(c).apply { orientation = VERTICAL }

    init {
        header.addView(range)
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setOnClickListener {
            if (!Weather.hasPlace(context)) actions.openSettingsPage("weather") else actions.refreshWeather()
        }
        refresh()
    }

    override fun refresh() {
        val w = Weather.now
        body.removeAllViews()
        spacer(body)
        if (w == null) {
            titleView.text = "Weather"
            range.text = ""
            body.addView(ImageView(context).apply {
                setImageResource(R.drawable.w_sun)
                imageTintList = ColorStateList.valueOf(Palette.SILVER)
            }, LayoutParams(dp(26), dp(26)))
            body.addView(small(Palette.INK, 13f).apply {
                text = if (Weather.hasPlace(context)) "Getting the forecast…" else "Tap to choose your city"
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        titleView.text = if (size == HomeWidgets.SMALL) w.city.ifBlank { "Weather" } else listOf("Weather", w.city).filter { it.isNotBlank() }.joinToString(" · ")
        w.days.firstOrNull()?.let { range.text = if (size == HomeWidgets.SMALL) "" else "H ${it.max}°  L ${it.min}°" }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val now = LinearLayout(context).apply { orientation = VERTICAL }
        now.addView(ImageView(context).apply {
            setImageResource(Weather.icon(w.code, w.isDay))
            imageTintList = ColorStateList.valueOf(Palette.INK)
        }, LayoutParams(dp(22), dp(22)).apply { bottomMargin = dp(6) })
        now.addView(big().apply {
            textSize = bigSize()
            text = "${w.temp}°"
        })
        now.addView(small(Palette.INK, 12.5f, 1).apply {
            text = Weather.describe(w.code)
            setPadding(0, dp(6), 0, 0)
        })
        if (size == HomeWidgets.SMALL) w.days.firstOrNull()?.let { d ->
            now.addView(small(Palette.SILVER, 11.5f, 1).apply { text = "H ${d.max}°  L ${d.min}°" })
        }
        row.addView(now, LayoutParams(if (size == HomeWidgets.SMALL) ViewGroup.LayoutParams.MATCH_PARENT else dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (size != HomeWidgets.SMALL) {
            for (h in w.hours.take(if (size == HomeWidgets.LARGE) 5 else 4)) row.addView(hourCol(h), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(row)
        if (size == HomeWidgets.LARGE) {
            body.addView(View(context).apply { setBackgroundColor(android.graphics.Color.argb(40, 255, 255, 255)) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(14)
                bottomMargin = dp(6)
            })
            for (d in w.days.drop(1).take(3)) {
                val r = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                r.addView(small(Palette.INK, 13.5f, 1).apply { text = d.dayName }, LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
                r.addView(ImageView(context).apply {
                    setImageResource(Weather.icon(d.code, true))
                    imageTintList = ColorStateList.valueOf(Palette.INK)
                }, LayoutParams(dp(18), dp(18)))
                r.addView(small(Palette.SILVER, 12.5f, 1).apply { text = Weather.describe(d.code); setPadding(dp(10), 0, 0, 0) }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(small(Palette.INK, 13f, 1).apply { text = "${d.max}°  "; typeface = Fonts.semibold(context) })
                r.addView(small(Palette.SILVER, 13f, 1).apply { text = "${d.min}°" })
                body.addView(r)
            }
        }
    }

    private fun hourCol(h: Weather.Hour): View {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(small(Palette.SILVER, 11f, 1).apply { text = h.label })
        col.addView(ImageView(context).apply {
            setImageResource(Weather.icon(h.code, h.isDay))
            imageTintList = ColorStateList.valueOf(Palette.INK)
        }, LayoutParams(dp(18), dp(18)).apply {
            topMargin = dp(7)
            bottomMargin = dp(7)
        })
        col.addView(small(Palette.INK, 12f, 1).apply {
            text = "${h.temp}°"
            typeface = Fonts.semibold(context)
        })
        return col
    }
}

@SuppressLint("ViewConstructor")
class BatteryWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Battery") {
    override val id = "battery"
    private val pct = big()
    private val dots = DotsView(c)
    private val state = small()

    init {
        spacer()
        addView(pct)
        addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(10) })
        addView(state.apply { setPadding(0, dp(8), 0, 0) })
        setOnClickListener {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    override fun refresh() {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        pct.textSize = bigSize()
        pct.text = "$level%"
        dots.count = if (size == HomeWidgets.SMALL) 10 else 20
        dots.fraction = level / 100f
        dots.accent = if (level <= 20 && !charging) Palette.RED else if (charging) Palette.GREEN else null
        dots.pulse = charging
        val left = if (charging) bm.computeChargeTimeRemaining() else -1L
        state.text = when {
            level >= 100 -> "Fully charged"
            charging && left > 0 -> "Charging · full in " + ScreenTime.format(left)
            charging -> "Charging"
            level <= 20 -> "Low, plug in soon"
            else -> "On battery"
        }
    }
}

@SuppressLint("ViewConstructor")
class StepsWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Steps") {
    override val id = "steps"
    private val stepsText = big()
    private val dots = DotsView(c).apply { accent = if (Palette.ACCENT == Palette.INK) Palette.RED else Palette.ACCENT }
    private val sub = small()

    init {
        spacer()
        addView(stepsText)
        addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(10) })
        addView(sub.apply { setPadding(0, dp(8), 0, 0) })
        setOnClickListener {
            if (!Steps.allowed(context)) actions.askPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
        }
        refresh()
    }

    override fun refresh() {
        val goal = Prefs.get(context).getInt(Prefs.STEP_GOAL, 6000)
        stepsText.textSize = bigSize()
        dots.count = if (size == HomeWidgets.SMALL) 12 else 24
        when {
            !Steps.available(context) -> {
                stepsText.text = "—"
                dots.fraction = 0f
                sub.text = "This phone has no step sensor"
            }
            !Steps.allowed(context) -> {
                stepsText.text = "0"
                dots.fraction = 0f
                sub.text = "Tap to count your steps"
            }
            else -> {
                val s = Steps.today.coerceAtLeast(0)
                stepsText.text = String.format(java.util.Locale.getDefault(), "%,d", s)
                dots.fraction = s / goal.toFloat()
                sub.text = "${(s * 100 / goal).coerceAtMost(999)}% of ${String.format(java.util.Locale.getDefault(), "%,d", goal)}"
            }
        }
    }
}

@SuppressLint("ViewConstructor")
class AgendaWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Today") {
    override val id = "agenda"
    private val day = c.text(46f, Palette.INK).apply { typeface = Fonts.light(c) }
    private val month = c.text(10.5f, Palette.SILVER, caps = true)
    private val list = LinearLayout(c).apply { orientation = VERTICAL }
    private val left = LinearLayout(c).apply { orientation = VERTICAL }
    private val row = LinearLayout(c)

    init {
        removeView(header)
        header.removeView(titleView)
        left.addView(titleView)
        left.addView(day, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        left.addView(month.apply { setPadding(0, dp(4), 0, 0) })
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setOnClickListener {
            if (!Agenda.allowed(context)) actions.askPermission(android.Manifest.permission.READ_CALENDAR) else actions.openCalendar()
        }
        refresh()
    }

    override fun refresh() {
        val now = Date()
        titleView.text = DateFormat.format("EEEE", now)
        day.text = DateFormat.format("d", now)
        day.textSize = if (size == HomeWidgets.LARGE) 54f else 46f
        month.text = DateFormat.format("MMMM", now)
        row.removeAllViews()
        list.removeAllViews()
        (left.parent as? ViewGroup)?.removeView(left)
        (list.parent as? ViewGroup)?.removeView(list)
        if (size == HomeWidgets.MEDIUM) {
            row.orientation = HORIZONTAL
            row.addView(left, LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(list, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            row.orientation = VERTICAL
            row.addView(left)
            row.addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val max = when (size) {
            HomeWidgets.LARGE -> 6
            HomeWidgets.MEDIUM -> 3
            else -> 1
        }
        if (!Agenda.allowed(context)) {
            list.addView(small(Palette.INK, 13f, 3).apply {
                text = "Tap to show your events here"
                setPadding(0, if (size == HomeWidgets.MEDIUM) dp(18) else 0, 0, 0)
            })
            return
        }
        val events = Agenda.today(context).take(max)
        if (events.isEmpty()) {
            list.addView(small(Palette.SILVER, 13f, 3).apply {
                text = "Nothing else today"
                setPadding(0, if (size == HomeWidgets.MEDIUM) dp(18) else 0, 0, 0)
            })
            return
        }
        val t = System.currentTimeMillis()
        for (e in events) {
            val r = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(5), 0, dp(4))
            }
            val live = e.begin <= t && e.end > t
            r.addView(View(context).apply {
                background = context.round(if (live) Palette.RED else android.graphics.Color.argb(90, 255, 255, 255), dpf(2f))
            }, LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
            val texts = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            texts.addView(context.text(13.5f, Palette.INK, bold = true).apply { text = e.title })
            texts.addView(small(if (live) Palette.RED else Palette.SILVER, 11.5f, 1).apply {
                text = if (e.allDay) "All day" else if (live) "Now · until " + DateFormat.getTimeFormat(context).format(Date(e.end)) else DateFormat.getTimeFormat(context).format(Date(e.begin))
                setPadding(0, dp(2), 0, 0)
            })
            r.addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(r)
        }
    }
}

@SuppressLint("ViewConstructor")
class ScreenTimeWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Screen time") {
    override val id = "screen"
    private val total = big()
    private val apps = LinearLayout(c).apply { orientation = VERTICAL }
    private val sub = small()

    init {
        spacer()
        addView(total)
        addView(apps, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(sub)
        setOnClickListener {
            if (!ScreenTime.allowed(context)) actions.openUsageAccess()
        }
        refresh()
    }

    override fun refresh() {
        apps.removeAllViews()
        total.textSize = bigSize()
        val r = ScreenTime.today(context)
        if (r == null) {
            total.text = "—"
            sub.text = "Tap to allow usage access"
            return
        }
        total.text = ScreenTime.format(r.totalMs)
        sub.text = ""
        if (size == HomeWidgets.SMALL) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((pkg, _) in r.top) {
                val app = AppRepo.apps.firstOrNull { it.pkg == pkg } ?: continue
                row.addView(AppIconView(context, app, 24, false, liveGlass = false).apply {
                    setOnClickListener { actions.launchPackage(pkg, it) }
                }, LayoutParams(dp(36), dp(34)))
            }
            apps.addView(row)
            return
        }
        // Medium / Large: a row per app with a bar for its share of the day.
        val rows = if (size == HomeWidgets.LARGE) r.top.size else 2
        val max = r.top.firstOrNull()?.second?.coerceAtLeast(1L) ?: 1L
        for ((pkg, ms) in r.top.take(rows)) {
            val app = AppRepo.apps.firstOrNull { it.pkg == pkg } ?: continue
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            line.addView(AppIconView(context, app, 22, false, liveGlass = false), LayoutParams(dp(34), dp(30)))
            val mid = LinearLayout(context).apply { orientation = VERTICAL }
            mid.addView(small(Palette.INK, 12.5f, 1).apply { text = app.label })
            mid.addView(BarView(context).apply {
                fraction = ms / max.toFloat()
                track = android.graphics.Color.argb(40, 255, 255, 255)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(4) })
            line.addView(mid, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
            line.addView(small(Palette.SILVER, 11.5f, 1).apply {
                text = ScreenTime.format(ms)
                setPadding(dp(10), 0, 0, 0)
            })
            line.setOnClickListener { actions.launchPackage(pkg, it) }
            apps.addView(line)
        }
    }
}

@SuppressLint("ViewConstructor")
class NoteWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Note") {
    override val id = "note"
    private val note = c.text(15f, Palette.INK, lines = 5).apply { setLineSpacing(0f, 1.12f) }

    init {
        addView(note, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        setOnClickListener { actions.editNote() }
        refresh()
    }

    override fun refresh() {
        val t = Prefs.get(context).getString(Prefs.NOTE, "").orEmpty()
        note.text = t.ifEmpty { "Tap to write something" }
        note.setTextColor(if (t.isEmpty()) Palette.SILVER else Palette.INK)
        note.maxLines = when (size) {
            HomeWidgets.LARGE -> 14
            HomeWidgets.MEDIUM -> 4
            else -> 5
        }
        note.textSize = if (size == HomeWidgets.LARGE) 17f else 15f
    }
}

@SuppressLint("ViewConstructor")
class PhotoWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Photo") {
    override val id = "photo"
    private val image = ImageView(c).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val hint = small(Palette.INK, 13f, 3)

    init {
        // The photo fills the whole card, corner to corner.
        setPadding(0, 0, 0, 0)
        removeView(header)
        header.removeView(titleView)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dpf(26f))
            }
        }
        val frame = FrameLayout(c)
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(LinearLayout(c).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(titleView)
            addView(View(c), LayoutParams(1, 0, 1f))
            addView(hint)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(frame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setOnClickListener { actions.pickPhoto() }
        refresh()
    }

    override fun refresh() {
        val f = Prefs.picFile(context, KEY)
        val bmp = if (f.exists() && Prefs.get(context).getLong(KEY, 0L) != 0L) {
            try {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        image.setImageBitmap(bmp)
        titleView.visibility = if (bmp == null) VISIBLE else GONE
        hint.visibility = if (bmp == null) VISIBLE else GONE
        hint.text = "Tap to pick a photo"
    }

    companion object {
        const val KEY = "w_photo"
    }
}

/**
 * Countdown in one tap. The phone's clock app runs the real timer (so it
 * rings even if the launcher is closed); this shows it counting down.
 */
@SuppressLint("ViewConstructor")
class TimerWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Timer") {
    override val id = "timer"
    private val time = big()
    private val sub = small()
    private val chips = LinearLayout(c).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (running()) postDelayed(this, 1000L)
        }
    }

    init {
        spacer()
        addView(time)
        addView(sub.apply { setPadding(0, dp(4), 0, 0) })
        addView(chips, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        setOnClickListener {
            if (running()) {
                Prefs.get(context).edit().remove(END).apply()
                refresh()
            } else {
                start(Prefs.get(context).getInt(LAST, 5))
            }
        }
        refresh()
    }

    private fun running() = Prefs.get(context).getLong(END, 0L) > System.currentTimeMillis()

    private fun start(min: Int) {
        val end = System.currentTimeMillis() + min * 60_000L
        Prefs.get(context).edit().putLong(END, end).putInt(LAST, min).apply()
        try {
            context.startActivity(
                android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                    .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, min * 60)
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Halo Launcher")
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "No clock app to run the timer", android.widget.Toast.LENGTH_SHORT).show()
        }
        bounce()
        refresh()
        removeCallbacks(ticker)
        postDelayed(ticker, 1000L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        if (running()) postDelayed(ticker, 1000L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun refresh() {
        time.textSize = bigSize()
        val p = Prefs.get(context)
        val end = p.getLong(END, 0L)
        val left = end - System.currentTimeMillis()
        chips.removeAllViews()
        if (left > 0) {
            val s = (left + 999) / 1000
            time.text = if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
            sub.text = "Ends " + DateFormat.getTimeFormat(context).format(Date(end)) + " · tap to hide"
            return
        }
        val last = p.getInt(LAST, 5)
        time.text = String.format(java.util.Locale.US, "%02d:00", last)
        sub.text = "Tap to start"
        val choices = when (size) {
            HomeWidgets.SMALL -> listOf(5, 10, 25)
            else -> listOf(1, 3, 5, 10, 25, 60)
        }
        for (m in choices) {
            chips.addView(context.text(12f, if (m == last) android.graphics.Color.BLACK else Palette.INK, bold = true).apply {
                text = if (m >= 60) "${m / 60}h" else "${m}m"
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                background = context.round(if (m == last) Palette.INK else android.graphics.Color.argb(46, 255, 255, 255), dpf(14f))
                setOnClickListener { start(m) }
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (chips.childCount > 0) marginStart = dp(6) })
        }
    }

    companion object {
        const val END = "timer_end"
        const val LAST = "timer_last"
    }
}

/** Keeps a view square ("Small" widgets). */
class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
    }
}


/** An analog clock: a clean face, twelve ticks, hour and minute hands, your colour on the centre. */
@SuppressLint("ViewConstructor")
class AnalogWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Clock") {
    override val id = "analog"
    private val face = object : View(c) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND }
        override fun onDraw(cv: android.graphics.Canvas) {
            val r = minOf(width, height) / 2f * 0.94f
            val cx = width / 2f
            val cy = height / 2f
            p.style = android.graphics.Paint.Style.STROKE
            for (i in 0 until 60) {
                val major = i % 5 == 0
                p.color = if (major) Palette.INK else android.graphics.Color.argb(70, 255, 255, 255)
                p.strokeWidth = if (major) r * 0.035f else r * 0.015f
                val a = Math.toRadians(i * 6.0)
                val o = r
                val inner = r * if (major) 0.84f else 0.9f
                cv.drawLine(cx + (Math.sin(a) * inner).toFloat(), cy - (Math.cos(a) * inner).toFloat(), cx + (Math.sin(a) * o).toFloat(), cy - (Math.cos(a) * o).toFloat(), p)
            }
            val t = java.util.Calendar.getInstance()
            val m = t.get(java.util.Calendar.MINUTE) + t.get(java.util.Calendar.SECOND) / 60f
            val h = t.get(java.util.Calendar.HOUR) + m / 60f
            fun hand(deg: Float, len: Float, w: Float, col: Int) {
                p.color = col
                p.strokeWidth = w
                val a = Math.toRadians(deg.toDouble())
                cv.drawLine(cx, cy, cx + (Math.sin(a) * len).toFloat(), cy - (Math.cos(a) * len).toFloat(), p)
            }
            hand(h * 30f, r * 0.5f, r * 0.075f, Palette.INK)
            hand(m * 6f, r * 0.76f, r * 0.05f, Palette.INK)
            p.style = android.graphics.Paint.Style.FILL
            p.color = if (Palette.ACCENT == Palette.INK) Palette.RED else Palette.ACCENT
            cv.drawCircle(cx, cy, r * 0.07f, p)
        }
    }

    init {
        removeView(header)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(face, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setOnClickListener {
            try {
                context.startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    override fun refresh() {
        face.invalidate()
    }
}

/** This month: a small calendar with today circled in your colour. */
@SuppressLint("ViewConstructor")
class MonthWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Month") {
    override val id = "month"
    private val grid = object : View(c) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = Fonts.semibold(c)
        }
        override fun onDraw(cv: android.graphics.Canvas) {
            val cal = java.util.Calendar.getInstance()
            val today = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val days = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            val first = cal.firstDayOfWeek
            val offset = Math.floorMod(cal.get(java.util.Calendar.DAY_OF_WEEK) - first, 7)
            val rows = (offset + days + 6) / 7 + 1
            val cw = width / 7f
            val ch = height / rows.toFloat()
            p.textSize = minOf(cw, ch) * 0.52f
            val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
            for (i in 0 until 7) {
                p.color = Palette.GREY
                val wd = ((first - 1 + i) % 7) + 1
                cv.drawText(names[wd].take(1).uppercase(), cw * (i + 0.5f), ch * 0.7f, p)
            }
            for (d in 1..days) {
                val pos = offset + d - 1
                val x = cw * (pos % 7 + 0.5f)
                val y = ch * (pos / 7 + 1) + ch * 0.68f
                if (d == today) {
                    p.color = if (Palette.ACCENT == Palette.INK) Palette.RED else Palette.ACCENT
                    cv.drawCircle(x, y - p.textSize * 0.35f, minOf(cw, ch) * 0.46f, p)
                    p.color = android.graphics.Color.WHITE
                } else {
                    p.color = Palette.INK
                }
                cv.drawText(d.toString(), x, y, p)
            }
        }
    }

    init {
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        setOnClickListener { actions.openCalendar() }
        refresh()
    }

    override fun refresh() {
        titleView.text = DateFormat.format("MMMM", Date())
        grid.invalidate()
    }
}

/** Days until a date you pick ("Exams", "Birthday"…). */
@SuppressLint("ViewConstructor")
class CountdownWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Countdown") {
    override val id = "countdown"
    private val days = big()
    private val sub = small()

    init {
        spacer()
        addView(days)
        addView(sub.apply { setPadding(0, dp(6), 0, 0) })
        setOnClickListener { pick() }
        refresh()
    }

    private fun pick() {
        val cal = java.util.Calendar.getInstance()
        val at = Prefs.get(context).getLong("cd_date", 0L)
        if (at > 0) cal.timeInMillis = at
        android.app.DatePickerDialog(context, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, m, d ->
            val t = java.util.Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val input = android.widget.EditText(android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)).apply {
                hint = "What is it? (e.g. Exams)"
                setText(Prefs.get(context).getString("cd_title", ""))
            }
            android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Counting down to")
                .setView(FrameLayout(context).apply {
                    setPadding(dp(20), dp(8), dp(20), 0)
                    addView(input)
                })
                .setPositiveButton("Save") { _, _ ->
                    Prefs.get(context).edit().putLong("cd_date", t.timeInMillis).putString("cd_title", input.text.toString().trim()).apply()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    override fun refresh() {
        val p = Prefs.get(context)
        val at = p.getLong("cd_date", 0L)
        days.textSize = bigSize()
        if (at == 0L) {
            titleView.text = "Countdown"
            days.text = "—"
            sub.text = "Tap to pick a date"
            return
        }
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val n = Math.round((at - today) / 86_400_000.0).toInt()
        titleView.text = p.getString("cd_title", "").orEmpty().ifEmpty { "Countdown" }
        days.text = kotlin.math.abs(n).toString()
        sub.text = when {
            n == 0 -> "It's today"
            n == 1 -> "day to go · " + DateFormat.format("d MMM", Date(at))
            n > 0 -> "days to go · " + DateFormat.format("d MMM", Date(at))
            else -> "days ago · " + DateFormat.format("d MMM", Date(at))
        }
    }
}

/** Storage: used and free, in dots. */
@SuppressLint("ViewConstructor")
class StorageWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Storage") {
    override val id = "storage"
    private val pct = big()
    private val dots = DotsView(c)
    private val sub = small()

    init {
        spacer()
        addView(pct)
        addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(10) })
        addView(sub.apply { setPadding(0, dp(8), 0, 0) })
        setOnClickListener {
            try {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    override fun refresh() {
        val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val total = st.totalBytes.toDouble()
        val free = st.availableBytes.toDouble()
        val used = ((total - free) / total).coerceIn(0.0, 1.0)
        pct.textSize = bigSize()
        pct.text = "${(used * 100).toInt()}%"
        dots.count = if (size == HomeWidgets.SMALL) 10 else 20
        dots.fraction = used.toFloat()
        dots.accent = if (used > 0.9) Palette.RED else null
        sub.text = String.format(java.util.Locale.getDefault(), "%.0f GB free of %.0f GB", free / 1e9, total / 1e9)
    }
}
