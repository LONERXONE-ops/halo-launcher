package com.lonerxone.halo.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Halo Launcher settings, in Halo's black and white. */
class SettingsActivity : Activity() {
    private var scroll: ScrollView? = null
    private var pickingKey: String? = null
    private var latest: Updater.Release? = null
    private var updateStatus = "Not checked yet"
    private var downloading = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val keepY = scroll?.scrollY ?: 0
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
        }
        // Brand
        col.addView(ImageView(this).apply { setImageResource(R.drawable.logo_launcher) }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(text(30f, Palette.INK, bold = true).apply {
            text = "Halo Launcher"
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        col.addView(text(13.5f, Palette.WHITE70).apply {
            text = "A home screen that works with Halo"
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        })

        if (!isDefault()) {
            col.addView(button("Make it my home screen") {
                try {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }

        // Halo
        section(col, "Halo")
        val halo = HaloLink.installed(this)
        col.addView(group(
            row(
                if (!halo) "Halo isn't installed" else if (HaloLink.alive()) "Linked to Halo" else "Halo installed, not running",
                if (!halo) "Get it for music, badges, notifications and double-tap to lock." else if (HaloLink.alive()) "Music, badges, notifications, quick switches and lock all come from the island." else "Turn on Halo in Accessibility and notification access.",
                trailing = chip(if (HaloLink.alive()) "On" else "Off", HaloLink.alive()),
            ) {
                if (!halo) open("https://github.com/LONERXONE-ops/halo/releases/latest") else {
                    packageManager.getLaunchIntentForPackage(HaloLink.HALO)?.let { startActivity(it) }
                }
            },
        ))

        // Home
        section(col, "Home")
        col.addView(group(
            pictureRow("Hero picture", "Big picture with the wave edge, behind the clock", Prefs.HERO_PIC),
            pictureRow("Capsule picture", "The tilted pill-shaped photo", Prefs.CAPSULE_PIC),
            row("Clock style", listOf("Outline", "Solid", "Thin")[Prefs.get(this).getInt(Prefs.CLOCK_STYLE, 0)], trailing = chevron()) {
                val p = Prefs.get(this)
                p.edit().putInt(Prefs.CLOCK_STYLE, (p.getInt(Prefs.CLOCK_STYLE, 0) + 1) % 3).apply()
                render()
            },
            switchRow("Show wallpaper", "Your wallpaper behind the widgets instead of pure black", Prefs.SHOW_WALLPAPER, false),
        ))

        // Apps
        section(col, "Apps")
        col.addView(group(
            switchRow("Monochrome icons", "White line icons, like Android's themed icons", Prefs.MONO_ICONS, true),
            row("Dock apps", "Up to 5, in the floating dock", trailing = chevron()) { pickApps(Prefs.DOCK, 5) },
            row("Favourites", "Up to 4, on the first page", trailing = chevron()) { pickApps(Prefs.FAVORITES, 4) },
            row("Hidden apps", "${Prefs.list(this, Prefs.HIDDEN).size} hidden; search still finds them", trailing = chevron()) { pickApps(Prefs.HIDDEN, 0) },
        ))

        // Gestures
        section(col, "Gestures")
        val down = Prefs.get(this).getInt(Prefs.SWIPE_DOWN, 0)
        col.addView(group(
            row("Swipe down", if (down == 0) "Halo's notification center" else "Android's notification shade", trailing = chevron()) {
                Prefs.get(this).edit().putInt(Prefs.SWIPE_DOWN, 1 - down).apply()
                render()
            },
            row("Swipe up", "All apps", trailing = null) {},
            switchRow("Double-tap to lock", "Uses Halo's accessibility service, so fingerprint unlock keeps working", Prefs.DOUBLE_TAP_LOCK, true),
            row("Long-press", "Opens these settings", trailing = null) {},
        ))

        // Weather
        section(col, "Weather")
        val city = Prefs.get(this).getString(Prefs.WEATHER_CITY, "").orEmpty()
        val hasFixed = Prefs.get(this).contains(Prefs.WEATHER_LAT)
        col.addView(group(
            row("City", if (hasFixed) city.ifEmpty { "Set" } else if (Weather.locationAllowed(this)) "Using your location" else "Not set", trailing = chevron()) { askCity() },
            row("Use my location", if (Weather.locationAllowed(this)) "Allowed" else "Approximate location only", trailing = chip(if (Weather.locationAllowed(this)) "On" else "Off", Weather.locationAllowed(this))) {
                Weather.setPlace(this, null, null, null)
                if (!Weather.locationAllowed(this)) requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 5) else render()
            },
            switchRow("Fahrenheit", "Show °F instead of °C", Prefs.FAHRENHEIT, false) { Weather.invalidate(this) },
        ))
        col.addView(text(11.5f, Palette.WHITE50).apply {
            text = "Forecast by Open-Meteo.com"
            setPadding(dp(20), dp(8), 0, 0)
        })

        // Updates
        section(col, "Updates")
        val rel = latest
        val newer = rel != null && rel.build > Updater.buildOf(version())
        col.addView(group(
            row("Version ${version()}", updateStatus, trailing = chip(if (newer) "New" else "Latest", newer)) {
                when {
                    downloading >= 0f -> {}
                    newer && rel != null -> install(rel)
                    else -> check()
                }
            },
        ))

        // About
        section(col, "About")
        col.addView(group(
            row("Made by LONERXONE", "github.com/LONERXONE-ops/halo-launcher", trailing = chevron()) { open("https://github.com/LONERXONE-ops/halo-launcher") },
            row("Thanks", "Ideas from Kvaesitso, Lawnchair, Olauncher and KISS, open-source launchers worth a look.", trailing = null) {},
        ))

        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, b.top, 0, b.bottom)
                insets
            }
        }
        setContentView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, keepY) }
        if (updateStatus == "Not checked yet") check()
    }

    // ----- building blocks -----

    private fun section(col: LinearLayout, t: String) {
        col.addView(text(13f, Palette.WHITE50, bold = true).apply {
            text = t
            setPadding(dp(20), dp(24), 0, dp(8))
        })
    }

    private fun group(vararg rows: View): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.rgb(17, 17, 19), dpf(24f)).apply { setStroke(dp(1), Color.argb(16, 255, 255, 255)) }
            setPadding(0, dp(2), 0, dp(2))
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) c.addView(View(this).apply { setBackgroundColor(Color.argb(14, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
            })
            c.addView(r)
        }
        return c
    }

    private fun row(title: String, summary: String, trailing: View? = null, onClick: () -> Unit): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(18), dp(14))
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
            setOnClickListener { onClick() }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(16f, Palette.INK, bold = true).apply { text = title })
        if (summary.isNotEmpty()) t.addView(text(12.5f, Palette.WHITE50, lines = 3).apply {
            text = summary
            setPadding(0, dp(2), 0, 0)
        })
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) r.addView(trailing)
        return r
    }

    private fun chevron(): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = ColorStateList.valueOf(Color.argb(110, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
    }

    private fun chip(t: String, on: Boolean): TextView = text(12.5f, if (on) Color.BLACK else Palette.INK, bold = true).apply {
        text = t
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = round(if (on) Palette.INK else Palette.GRAPHITE, dpf(12f))
    }

    private fun button(t: String, onClick: () -> Unit): TextView = text(15f, Color.BLACK, bold = true).apply {
        text = t
        gravity = Gravity.CENTER
        background = round(Palette.INK, dpf(25f))
        setOnClickListener { onClick() }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switchRow(title: String, summary: String, key: String, def: Boolean, after: (() -> Unit)? = null): View {
        val p = Prefs.get(this)
        val sw = Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(170, 170, 176)))
            trackTintList = ColorStateList(states, intArrayOf(Color.rgb(120, 120, 128), Color.rgb(58, 58, 62)))
            isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean(key, on).apply()
                after?.invoke()
            }
        }
        val r = row(title, summary, sw) { sw.toggle() }
        return r
    }

    private fun pictureRow(title: String, summary: String, key: String): View {
        val has = Prefs.get(this).getLong(key, 0L) != 0L
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(10f))
                }
            }
            clipToOutline = true
            background = round(Palette.GRAPHITE, dpf(10f))
            if (has) {
                try {
                    setImageBitmap(android.graphics.BitmapFactory.decodeFile(Prefs.picFile(this@SettingsActivity, key).absolutePath))
                } catch (_: Throwable) {
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(40))
        }
        return row(title, if (has) "Tap to change, long-press to remove" else summary, thumb) { pick(key) }.apply {
            setOnLongClickListener {
                Prefs.get(this@SettingsActivity).edit().remove(key).apply()
                Prefs.picFile(this@SettingsActivity, key).delete()
                render()
                true
            }
        }
    }

    // ----- actions -----

    private fun isDefault(): Boolean = try {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    } catch (_: Exception) {
        false
    }

    private fun pickApps(key: String, max: Int) {
        startActivity(Intent(this, PickAppsActivity::class.java).putExtra("key", key).putExtra("max", max))
    }

    private fun askCity() {
        val input = EditText(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)).apply { hint = "City, e.g. Lagos" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Weather city")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            })
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                Weather.searchCity(q) { results ->
                    if (results.isEmpty()) {
                        Toast.makeText(this, "No city found", Toast.LENGTH_SHORT).show()
                        return@searchCity
                    }
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Pick your city")
                        .setItems(results.map { it.first }.toTypedArray()) { _, i ->
                            val r = results[i]
                            Weather.setPlace(this, r.first, r.second, r.third)
                            render()
                        }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun pick(key: String) {
        pickingKey = key
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 9)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker found", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 9 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val key = pickingKey ?: return
        Thread {
            val ok = try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1600) {
                        val f = 1600f / longest
                        dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                    }
                }
                Prefs.picFile(this, key).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Prefs.get(this).edit().putLong(key, System.currentTimeMillis()).apply()
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (!ok) Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
                render()
            }
        }.start()
    }

    private fun open(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (_: Exception) {
        }
    }

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    private fun check() {
        updateStatus = "Checking…"
        Updater.check { r ->
            when (r) {
                is Updater.Check.Ok -> {
                    latest = r.release
                    updateStatus = if (r.release.build > Updater.buildOf(version())) "${r.release.tag} is out, tap to install" else "You're up to date"
                }
                is Updater.Check.NotPublic -> updateStatus = "Updates need the GitHub repo to be public"
                is Updater.Check.Error -> updateStatus = "Couldn't check: ${r.message.take(40)}"
            }
            render()
        }
    }

    private fun install(rel: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Halo Launcher to install updates, then tap again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        downloading = 0f
        updateStatus = "Downloading ${rel.tag}"
        render()
        Updater.install(this, rel, progress = { f ->
            downloading = if (f >= 1f) -1f else f
            updateStatus = if (f >= 1f) "Confirm the install" else "Downloading ${rel.tag} ${(f * 100).toInt()}%"
            render()
        }, failed = { msg ->
            downloading = -1f
            updateStatus = "Failed: ${msg.take(40)}"
            render()
        })
    }
}
