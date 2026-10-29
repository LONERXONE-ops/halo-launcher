package com.lonerxone.halo.launcher

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.CalendarContract
import java.util.Calendar

/**
 * Steps today from the phone's hardware step counter (it counts since the
 * last reboot, so we keep the count at midnight and subtract). Needs the
 * "physical activity" permission.
 */
object Steps : SensorEventListener {
    var today = -1
        private set
    private var listener: (() -> Unit)? = null
    private var ctx: Context? = null

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun available(c: Context) = c.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun start(c: Context, onChange: () -> Unit) {
        if (!allowed(c)) return
        val sm = c.getSystemService(SensorManager::class.java) ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        ctx = c.applicationContext
        listener = onChange
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop(c: Context) {
        c.getSystemService(SensorManager::class.java)?.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val c = ctx ?: return
        val p = Prefs.get(c)
        val counter = e.values[0].toLong()
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        var base = p.getLong("steps_base", -1L)
        var carry = p.getLong("steps_carry", 0L)
        val last = p.getLong("steps_last", 0L)
        if (p.getInt("steps_day", -1) != day || base < 0) {
            base = counter
            carry = 0L
        } else if (counter < last) {
            // The phone restarted: the counter began again from zero.
            carry += last - base
            base = 0L
        }
        p.edit().putLong("steps_base", base).putLong("steps_carry", carry).putLong("steps_last", counter).putInt("steps_day", day).apply()
        today = (carry + counter - base).toInt().coerceAtLeast(0)
        listener?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Today's events from the phone's calendars (needs calendar permission). */
object Agenda {
    class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean)

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun today(c: Context): List<Event> {
        if (!allowed(c)) return emptyList()
        val now = System.currentTimeMillis()
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(uri, now - 60 * 60_000L)
        android.content.ContentUris.appendId(uri, end)
        val out = ArrayList<Event>()
        try {
            c.contentResolver.query(
                uri.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val e = Event(cur.getString(0) ?: "Event", cur.getLong(1), cur.getLong(2), cur.getInt(3) == 1)
                    if (e.end > now || e.allDay) out += e
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/** Time spent in apps today (needs Usage access, which the user grants in Settings). */
object ScreenTime {
    class Result(val totalMs: Long, val top: List<Pair<String, Long>>)

    fun allowed(c: Context): Boolean = try {
        val ops = c.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    @Volatile
    private var cached: Result? = null
    @Volatile
    private var cachedAt = 0L
    @Volatile
    private var busy = false

    /**
     * Today's screen time. Asking Android for it is slow (it adds up every
     * app's usage), so it's worked out in the background at most once a
     * minute; until then you get the last answer.
     */
    fun today(c: Context): Result? {
        if (!allowed(c)) return null
        val now = System.currentTimeMillis()
        if (now - cachedAt > 60_000L && !busy) {
            busy = true
            val app = c.applicationContext
            Thread {
                val r = compute(app)
                cached = r
                cachedAt = System.currentTimeMillis()
                busy = false
                listener?.let { l -> android.os.Handler(android.os.Looper.getMainLooper()).post(l) }
            }.start()
        }
        return cached
    }

    /** Called (on the main thread) when a fresh answer is ready. */
    var listener: (() -> Unit)? = null

    private fun compute(c: Context): Result? {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        val stats = try {
            usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())
        } catch (_: Exception) {
            return null
        }
        val skip = setOf(c.packageName, "com.android.systemui", "com.sec.android.app.launcher", "android")
        val launchable = AppRepo.apps.map { it.pkg }.toSet()
        val list = stats.values
            .filter { it.packageName !in skip && it.packageName in launchable && it.totalTimeInForeground > 60_000L }
            .map { it.packageName to it.totalTimeInForeground }
            .sortedByDescending { it.second }
        return Result(list.sumOf { it.second }, list.take(3))
    }

    fun format(ms: Long): String {
        val m = ms / 60_000L
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
}
