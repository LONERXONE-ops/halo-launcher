package com.lonerxone.halo.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Weather from Open-Meteo (free, no key, no account). The place comes from
 * the phone's last known coarse location, or a city you type in Settings.
 * Results are cached for 30 minutes.
 */
object Weather {
    class Day(val code: Int, val max: Int, val min: Int, val dayName: String)
    class Hour(val label: String, val code: Int, val temp: Int, val isDay: Boolean)
    class Now(val temp: Int, val code: Int, val isDay: Boolean, val city: String, val days: List<Day>, val hours: List<Hour> = emptyList())

    var now: Now? = null
        private set
    private val main = Handler(Looper.getMainLooper())
    private var loading = false

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorm"
        else -> "—"
    }

    fun icon(code: Int, day: Boolean): Int = when (code) {
        0, 1 -> if (day) R.drawable.w_sun else R.drawable.w_moon
        2, 3 -> R.drawable.w_cloud
        45, 48 -> R.drawable.w_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.w_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.w_snow
        95, 96, 99 -> R.drawable.w_storm
        else -> R.drawable.w_cloud
    }

    fun hasPlace(c: Context): Boolean = Prefs.get(c).contains(Prefs.WEATHER_LAT) || locationAllowed(c)

    fun locationAllowed(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Loads (or reuses) the forecast, then calls [done] on the main thread. */
    fun refresh(c: Context, force: Boolean = false, done: () -> Unit) {
        val p = Prefs.get(c)
        if (now == null) restore(c)
        val fresh = System.currentTimeMillis() - p.getLong(Prefs.WEATHER_AT, 0L) < 30 * 60_000L
        if ((fresh && !force && now != null) || loading) {
            done()
            return
        }
        var lat = p.getFloat(Prefs.WEATHER_LAT, Float.NaN).toDouble()
        var lon = p.getFloat(Prefs.WEATHER_LON, Float.NaN).toDouble()
        var city = p.getString(Prefs.WEATHER_CITY, "") ?: ""
        if (lat.isNaN() && locationAllowed(c)) {
            try {
                val lm = c.getSystemService(LocationManager::class.java)
                @Suppress("MissingPermission")
                val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (loc != null) {
                    lat = loc.latitude
                    lon = loc.longitude
                    city = ""
                }
            } catch (_: SecurityException) {
            }
        }
        if (lat.isNaN()) {
            done()
            return
        }
        loading = true
        val f = p.getBoolean(Prefs.FAHRENHEIT, false)
        Thread {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&hourly=temperature_2m,weather_code,is_day&forecast_hours=7" +
                    "&timezone=auto&forecast_days=4" + if (f) "&temperature_unit=fahrenheit" else ""
                val json = get(url)
                if (city.isEmpty()) city = reverseName(lat, lon)
                p.edit().putString(Prefs.WEATHER_CACHE, json).putLong(Prefs.WEATHER_AT, System.currentTimeMillis())
                    .putString(Prefs.WEATHER_CITY, city).apply()
                val parsed = parse(json, city)
                main.post {
                    now = parsed
                    loading = false
                    done()
                }
            } catch (_: Exception) {
                main.post {
                    loading = false
                    done()
                }
            }
        }.start()
    }

    private fun restore(c: Context) {
        val p = Prefs.get(c)
        val json = p.getString(Prefs.WEATHER_CACHE, null) ?: return
        now = try {
            parse(json, p.getString(Prefs.WEATHER_CITY, "") ?: "")
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(json: String, city: String): Now {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val codes = d.getJSONArray("weather_code")
        val max = d.getJSONArray("temperature_2m_max")
        val min = d.getJSONArray("temperature_2m_min")
        val times = d.getJSONArray("time")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val dayFmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val days = (0 until codes.length()).map { n ->
            val name = try {
                dayFmt.format(fmt.parse(times.getString(n))!!)
            } catch (_: Exception) {
                ""
            }
            Day(codes.getInt(n), Math.round(max.getDouble(n)).toInt(), Math.round(min.getDouble(n)).toInt(), name)
        }
        val hours = ArrayList<Hour>()
        o.optJSONObject("hourly")?.let { hr ->
            val ht = hr.optJSONArray("time")
            val htemp = hr.optJSONArray("temperature_2m")
            val hcode = hr.optJSONArray("weather_code")
            val hday = hr.optJSONArray("is_day")
            val hfIn = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
            val hfOut = java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(App0.ctx ?: return@let)) "HH:mm" else "ha", java.util.Locale.getDefault())
            if (ht != null && htemp != null && hcode != null) {
                for (n in 1 until ht.length()) {
                    val label = try {
                        hfOut.format(hfIn.parse(ht.getString(n))!!).lowercase()
                    } catch (_: Exception) {
                        ""
                    }
                    hours += Hour(label, hcode.getInt(n), Math.round(htemp.getDouble(n)).toInt(), (hday?.optInt(n, 1) ?: 1) == 1)
                }
            }
        }
        return Now(
            hours = hours,
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            city = city,
            days = days,
        )
    }

    /** Finds a city by name (Settings → Weather). */
    fun searchCity(name: String, done: (List<Triple<String, Double, Double>>) -> Unit) {
        Thread {
            val out = try {
                val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=6&name=" + URLEncoder.encode(name, "UTF-8")))
                val arr = json.optJSONArray("results")
                if (arr == null) emptyList() else (0 until arr.length()).map { n ->
                    val r = arr.getJSONObject(n)
                    val label = listOf(r.optString("name"), r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
                    Triple(label, r.getDouble("latitude"), r.getDouble("longitude"))
                }
            } catch (_: Exception) {
                emptyList()
            }
            main.post { done(out) }
        }.start()
    }

    fun setPlace(c: Context, name: String?, lat: Double?, lon: Double?) {
        val e = Prefs.get(c).edit().remove(Prefs.WEATHER_AT)
        if (lat == null || lon == null) {
            e.remove(Prefs.WEATHER_LAT).remove(Prefs.WEATHER_LON).putString(Prefs.WEATHER_CITY, "")
        } else {
            e.putFloat(Prefs.WEATHER_LAT, lat.toFloat()).putFloat(Prefs.WEATHER_LON, lon.toFloat()).putString(Prefs.WEATHER_CITY, name?.substringBefore(',') ?: "")
        }
        e.apply()
        now = null
    }

    /** Forget the cached forecast (units changed). */
    fun invalidate(c: Context) {
        Prefs.get(c).edit().remove(Prefs.WEATHER_AT).apply()
        now = null
    }

    private fun reverseName(lat: Double, lon: Double): String = try {
        @Suppress("DEPRECATION")
        val list = android.location.Geocoder(App0.ctx!!, java.util.Locale.getDefault()).getFromLocation(lat, lon, 1)
        list?.firstOrNull()?.locality ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "HaloLauncher-Android")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}

/** Application context holder (set by the launcher on start). */
object App0 {
    var ctx: Context? = null
}
