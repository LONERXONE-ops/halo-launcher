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
 * Halo Launcher's own widgets. Built on the iOS widget rules: one idea each,
 * live information (not just a shortcut), 16dp margins, one big number in the
 * dot-matrix face and everything else small, opaque greys for hierarchy, and
 * red only where it means something.
 */
interface WidgetActions {
    fun askPermission(permission: String)
    fun openSettingsPage(page: String)
    fun openCalendar()
    fun editNote()
    fun refreshWeather()
    fun openUsageAccess()
    fun launchPackage(pkg: String, from: View)
}

/** id, name, what it shows, and whether it is wide (2 columns). */
class WidgetSpec(val id: String, val name: String, val about: String, val wide: Boolean)

object HomeWidgets {
    val ALL = listOf(
        WidgetSpec("weather", "Weather", "Now, today's high and low, and the next hours.", true),
        WidgetSpec("battery", "Battery", "Charge in dots, and time until full while charging.", false),
        WidgetSpec("steps", "Steps", "Today's steps against your goal.", false),
        WidgetSpec("agenda", "Up next", "Today's date and your next events.", true),
        WidgetSpec("screen", "Screen time", "Time on your phone today and your top apps.", false),
        WidgetSpec("note", "Note", "A note to yourself, always in view.", false),
    )

    fun create(c: Context, id: String, a: WidgetActions): BaseWidget? = when (id) {
        "weather" -> WeatherWidget(c, a)
        "battery" -> BatteryWidget(c, a)
        "steps" -> StepsWidget(c, a)
        "agenda" -> AgendaWidget(c, a)
        "screen" -> ScreenTimeWidget(c, a)
        "note" -> NoteWidget(c, a)
        else -> null
    }
}

/** The shared card: solid tile, 16dp padding, a small caps title. */
@SuppressLint("ViewConstructor")
abstract class BaseWidget(context: Context, protected val actions: WidgetActions, title: String) : LinearLayout(context) {
    protected val titleView: TextView = context.text(10.5f, Palette.GREY, caps = true).apply { text = title }
    protected val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        orientation = VERTICAL
        background = context.round(Palette.TILE, dpf(24f))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        header.addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(header)
        pressable()
    }

    abstract fun refresh()

    protected fun big(sp: Float = 38f): TextView = context.text(sp, Palette.INK).apply {
        typeface = Fonts.dots(context)
        includeFontPadding = false
    }

    protected fun small(color: Int = Palette.GREY, sp: Float = 11.5f, lines: Int = 2): TextView = context.text(sp, color, lines = lines)

    protected fun spacer() = View(context).also { addView(it, LayoutParams(1, 0, 1f)) }
}

// ---------------------------------------------------------------------------

@SuppressLint("ViewConstructor")
class WeatherWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Weather") {
    private val range = c.text(10.5f, Palette.GREY, caps = true)
    private val body = LinearLayout(c).apply {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
    }

    init {
        header.addView(range)
        spacer()
        addView(body)
        setOnClickListener {
            if (!Weather.hasPlace(context)) actions.openSettingsPage("weather") else actions.refreshWeather()
        }
        refresh()
    }

    override fun refresh() {
        val w = Weather.now
        body.removeAllViews()
        if (w == null) {
            titleView.text = "WEATHER"
            range.text = ""
            body.addView(ImageView(context).apply {
                setImageResource(R.drawable.w_sun)
                imageTintList = ColorStateList.valueOf(Palette.GREY)
            }, LayoutParams(dp(26), dp(26)))
            body.addView(small(Palette.SILVER, 13f).apply {
                text = if (Weather.hasPlace(context)) "Getting the forecast…" else "Tap to choose your city"
                setPadding(dp(10), 0, 0, dp(3))
            })
            return
        }
        titleView.text = listOf("Weather", w.city).filter { it.isNotBlank() }.joinToString(" · ")
        w.days.firstOrNull()?.let { range.text = "H ${it.max}°  L ${it.min}°" }
        val now = LinearLayout(context).apply { orientation = VERTICAL }
        now.addView(big(44f).apply { text = "${w.temp}°" })
        now.addView(small(Palette.SILVER, 12.5f, 1).apply {
            text = Weather.describe(w.code)
            setPadding(0, dp(6), 0, 0)
        })
        body.addView(now, LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
        for (h in w.hours.take(4)) {
            val col = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(small(Palette.GREY, 11f, 1).apply { text = h.label })
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
            body.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}

@SuppressLint("ViewConstructor")
class BatteryWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Battery") {
    private val pct = big()
    private val dots = DotsView(c)
    private val state = small()

    init {
        spacer()
        addView(pct)
        addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10) })
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
        pct.text = "$level%"
        dots.fraction = level / 100f
        dots.accent = if (level <= 20 && !charging) Palette.RED else null
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
    private val stepsText = big()
    private val dots = DotsView(c).apply {
        count = 12
        accent = Palette.RED
    }
    private val sub = small()

    init {
        spacer()
        addView(stepsText)
        addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10) })
        addView(sub.apply { setPadding(0, dp(8), 0, 0) })
        setOnClickListener {
            if (!Steps.allowed(context)) actions.askPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
        }
        refresh()
    }

    override fun refresh() {
        val goal = Prefs.get(context).getInt(Prefs.STEP_GOAL, 6000)
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
    private val day = c.text(46f, Palette.INK).apply { typeface = Fonts.light(c) }
    private val month = c.text(10.5f, Palette.GREY, caps = true)
    private val list = LinearLayout(c).apply { orientation = VERTICAL }

    init {
        removeView(header)
        header.removeView(titleView)
        val row = LinearLayout(c).apply { orientation = HORIZONTAL }
        val left = LinearLayout(c).apply { orientation = VERTICAL }
        left.addView(titleView)
        left.addView(day, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        left.addView(month.apply { setPadding(0, dp(4), 0, 0) })
        row.addView(left, LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(list, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row)
        setOnClickListener {
            if (!Agenda.allowed(context)) actions.askPermission(android.Manifest.permission.READ_CALENDAR) else actions.openCalendar()
        }
        refresh()
    }

    override fun refresh() {
        val now = Date()
        titleView.text = DateFormat.format("EEEE", now)
        day.text = DateFormat.format("d", now)
        month.text = DateFormat.format("MMMM", now)
        list.removeAllViews()
        if (!Agenda.allowed(context)) {
            list.addView(small(Palette.SILVER, 13f, 3).apply {
                text = "Tap to show your events here"
                setPadding(0, dp(18), 0, 0)
            })
            return
        }
        val events = Agenda.today(context).take(3)
        if (events.isEmpty()) {
            list.addView(small(Palette.SILVER, 13f, 3).apply {
                text = "Nothing else today"
                setPadding(0, dp(18), 0, 0)
            })
            return
        }
        val t = System.currentTimeMillis()
        for (e in events) {
            val r = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(6), 0, dp(4))
            }
            val live = e.begin <= t && e.end > t
            r.addView(View(context).apply {
                background = context.round(if (live) Palette.RED else Palette.GRAPHITE, dpf(2f))
            }, LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
            val texts = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            texts.addView(context.text(13.5f, Palette.INK, bold = true).apply { text = e.title })
            texts.addView(small(if (live) Palette.RED else Palette.GREY, 11.5f, 1).apply {
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
    private val total = big()
    private val apps = LinearLayout(c).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val sub = small()

    init {
        spacer()
        addView(total)
        addView(apps, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply { topMargin = dp(8) })
        addView(sub)
        setOnClickListener {
            if (!ScreenTime.allowed(context)) actions.openUsageAccess()
        }
        refresh()
    }

    override fun refresh() {
        apps.removeAllViews()
        val r = ScreenTime.today(context)
        if (r == null) {
            total.text = "—"
            sub.text = "Tap to allow usage access"
            return
        }
        total.text = ScreenTime.format(r.totalMs)
        sub.text = ""
        for ((pkg, _) in r.top) {
            val app = AppRepo.apps.firstOrNull { it.pkg == pkg } ?: continue
            apps.addView(AppIconView(context, app, 24, false).apply {
                setOnClickListener { actions.launchPackage(pkg, it) }
            }, LayoutParams(dp(36), dp(34)))
        }
    }
}

@SuppressLint("ViewConstructor")
class NoteWidget(c: Context, a: WidgetActions) : BaseWidget(c, a, "Note") {
    private val note = c.text(15f, Palette.INK, lines = 5).apply { setLineSpacing(0f, 1.12f) }

    init {
        addView(note, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        setOnClickListener { actions.editNote() }
        refresh()
    }

    override fun refresh() {
        val t = Prefs.get(context).getString(Prefs.NOTE, "").orEmpty()
        note.text = t.ifEmpty { "Tap to write something" }
        note.setTextColor(if (t.isEmpty()) Palette.GREY else Palette.INK)
    }
}

/** Keeps a view square-ish for "small" widgets. */
class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
    }
}
