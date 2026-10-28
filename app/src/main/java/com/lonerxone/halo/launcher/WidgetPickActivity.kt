package com.lonerxone.halo.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

/**
 * Picks an Android widget, then follows the same steps as Kvaesitso and
 * Launcher3: allocate an id, ask Android for permission to bind it (a system
 * dialog the first time), run the widget's own setup screen if it has one,
 * and hand the id back to the home screen.
 */
class WidgetPickActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        host = AppWidgetHost(applicationContext, LauncherActivity.WIDGET_HOST_ID)
        val awm = AppWidgetManager.getInstance(this)
        val providers = try {
            awm.installedProviders
        } catch (_: Exception) {
            emptyList()
        }
        val pm = packageManager
        val byApp = providers.groupBy { it.provider.packageName }.toList().sortedBy { (pkg, _) ->
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase()
            } catch (_: Exception) {
                pkg
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        col.addView(text(30f, Palette.INK, bold = true).apply {
            text = "Add a widget"
            setPadding(dp(6), dp(24), 0, dp(4))
        })
        col.addView(text(13.5f, Palette.WHITE70, lines = 3).apply {
            text = "Widgets sit on your second page. Tap one to add it."
            setPadding(dp(6), 0, 0, dp(16))
        })
        val io = Executors.newSingleThreadExecutor()
        for ((pkg, list) in byApp) {
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
            col.addView(text(12.5f, Palette.WHITE50, bold = true).apply {
                text = appName.uppercase()
                letterSpacing = 0.1f
                setPadding(dp(8), dp(16), 0, dp(8))
            })
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = glass(dpf(24f))
            }
            for (info in list) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener { pick(info) }
                }
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = round(Color.argb(30, 255, 255, 255), dpf(12f))
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                row.addView(preview, LinearLayout.LayoutParams(dp(84), dp(64)))
                io.execute {
                    val d = try {
                        info.loadPreviewImage(this, 0) ?: info.loadIcon(this, 0)
                    } catch (_: Exception) {
                        null
                    }
                    runOnUiThread { preview.setImageDrawable(d) }
                }
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                }
                texts.addView(text(15f, Palette.INK, bold = true, lines = 2).apply { text = info.loadLabel(pm) })
                val d = resources.displayMetrics.density
                val cells = "${((info.minWidth / d + 30) / 70).toInt().coerceAtLeast(1)} × ${((info.minHeight / d + 30) / 70).toInt().coerceAtLeast(1)}"
                texts.addView(text(12f, Palette.WHITE50).apply {
                    text = cells
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(row)
            }
            col.addView(card)
        }
        if (byApp.isEmpty()) {
            col.addView(text(14f, Palette.WHITE70).apply { text = "No widgets found on this phone." })
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, b.top, 0, b.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    private fun pick(info: AppWidgetProviderInfo) {
        val awm = AppWidgetManager.getInstance(this)
        val id = host.allocateAppWidgetId()
        pendingId = id
        pendingInfo = info
        val ok = try {
            awm.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            configure(info, id)
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_BIND)
        }
    }

    private fun configure(info: AppWidgetProviderInfo, id: Int) {
        if (info.configure != null) {
            val opts = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            } else {
                null
            }
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, opts)
                return
            } catch (_: Exception) {
            }
        }
        done(id)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingId
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK) {
                val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: pendingInfo
                if (info != null) configure(info, id) else cancel(id)
            } else {
                cancel(id)
            }
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) done(id) else cancel(id)
        }
    }

    private fun done(id: Int) {
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }

    private fun cancel(id: Int) {
        try {
            host.deleteAppWidgetId(id)
        } catch (_: Exception) {
        }
        pendingId = -1
    }

    companion object {
        private const val REQ_BIND = 1
        private const val REQ_CONFIGURE = 2
    }
}
