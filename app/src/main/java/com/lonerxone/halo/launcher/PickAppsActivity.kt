package com.lonerxone.halo.launcher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Choose apps for the dock (max 5), favourites (max 4) or the hidden list. */
class PickAppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val key = intent.getStringExtra("key") ?: Prefs.HIDDEN
        val max = intent.getIntExtra("max", 0)
        val title = when (key) {
            Prefs.DOCK -> "Dock apps"
            Prefs.FAVORITES -> "Favourites"
            else -> "Hidden apps"
        }
        val chosen = Prefs.list(this, key)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }
        col.addView(text(30f, Palette.INK, bold = true).apply {
            text = title
            setPadding(dp(6), dp(20), 0, dp(4))
        })
        col.addView(text(13.5f, Palette.WHITE70, lines = 2).apply {
            text = if (max > 0) "Pick up to $max, in the order you want them." else "Hidden apps leave the drawer and home screen; search still finds them."
            setPadding(dp(6), 0, 0, dp(16))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.rgb(17, 17, 19), dpf(24f))
        }
        for (a in AppRepo.apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(16), dp(6))
            }
            row.addView(AppIconView(this, a, 38, false), LinearLayout.LayoutParams(dp(50), dp(40)))
            row.addView(text(15f, Palette.INK, bold = true).apply {
                text = a.label
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val cb = CheckBox(this).apply {
                isChecked = a.key in chosen
                buttonTintList = ColorStateList.valueOf(Palette.INK)
                setOnCheckedChangeListener { b, on ->
                    if (on) {
                        if (max > 0 && chosen.size >= max) {
                            b.isChecked = false
                            Toast.makeText(this@PickAppsActivity, "Up to $max apps", Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }
                        if (a.key !in chosen) chosen.add(a.key)
                    } else {
                        chosen.remove(a.key)
                    }
                    Prefs.setList(this@PickAppsActivity, key, chosen)
                }
            }
            row.addView(cb)
            row.setOnClickListener { cb.toggle() }
            card.addView(row)
        }
        col.addView(card)
        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
            setOnApplyWindowInsetsListener { v: View, insets: WindowInsets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, b.top, 0, b.bottom)
                insets
            }
        }
        setContentView(sv)
    }
}
