package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Halo Launcher's own menus, used for every long-press and "+" menu instead
 * of Android's grey dropdown. A rounded card on the theme's surface (Liquid
 * Glass on the home screen, a tile tinted by Adaptive colour elsewhere),
 * icons on the left, a tick for the chosen option, red for destructive
 * actions. It grows out of whatever you pressed and the rest of the screen
 * dims a touch; tap outside or press back to close.
 */
class HaloMenu(private val anchor: View) {
    private sealed class Entry {
        class Row(val title: String, val icon: Drawable?, val checked: Boolean?, val danger: Boolean, val onClick: () -> Unit) : Entry()
        object Divider : Entry()
        class Header(val text: String) : Entry()
    }

    private val entries = ArrayList<Entry>()
    private var catcher: FrameLayout? = null
    private var card: LinearLayout? = null
    var onDismiss: (() -> Unit)? = null
    val isShowing get() = catcher?.parent != null

    fun header(text: String) = apply { entries += Entry.Header(text) }

    fun add(title: String, icon: Int = 0, checked: Boolean? = null, danger: Boolean = false, onClick: () -> Unit) = apply {
        val d = if (icon != 0) anchor.context.getDrawable(icon) else null
        entries += Entry.Row(title, d, checked, danger, onClick)
    }

    fun addDrawable(title: String, icon: Drawable?, onClick: () -> Unit) = apply { entries += Entry.Row(title, icon, null, false, onClick) }

    fun divider() = apply {
        if (entries.isNotEmpty() && entries.last() !is Entry.Divider) entries += Entry.Divider
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val ctx = anchor.context
        val root = (anchor.rootView as? ViewGroup) ?: return
        val c = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(46, 0, 0, 0))
            isClickable = true
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }
        val k = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true
            elevation = dpf(18f)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(22f))
                }
            }
            clipToOutline = true
        }
        // Real glass on the home screen (it has the picture behind it); a tinted tile elsewhere.
        k.background = if (ctx is LauncherActivity && Glass.enabled(ctx) && Glass.backdrop != null) {
            GlassDrawable(k, dpf(22f), darkness = 0.5f, bendPx = dpf(10f))
        } else {
            ctx.round(Palette.TILE2, dpf(22f)).apply { setStroke(dp(1), Color.argb(34, 255, 255, 255)) }
        }
        var i = 0
        for (e in entries) {
            when (e) {
                is Entry.Header -> k.addView(ctx.text(10.5f, Palette.GREY, caps = true).apply {
                    text = e.text
                    setPadding(dp(14), dp(8), dp(14), dp(6))
                })
                is Entry.Divider -> k.addView(View(ctx).apply { setBackgroundColor(Color.argb(30, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                    marginStart = dp(12)
                    marginEnd = dp(12)
                })
                is Entry.Row -> {
                    val fg = if (e.danger) Palette.RED else Palette.INK
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(11), dp(14), dp(11))
                        background = rowRipple(ctx)
                        setOnClickListener {
                            dismiss()
                            e.onClick()
                        }
                    }
                    if (e.icon != null) {
                        row.addView(ImageView(ctx).apply {
                            setImageDrawable(e.icon)
                            // App shortcut icons keep their colours; ours take the ink.
                            if (e.icon !is android.graphics.drawable.BitmapDrawable && e.icon !is android.graphics.drawable.AdaptiveIconDrawable) imageTintList = ColorStateList.valueOf(fg)
                        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
                    }
                    row.addView(ctx.text(15f, fg).apply { text = e.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (e.checked == true) row.addView(ctx.text(15f, Palette.ACCENT, bold = true).apply { text = "✓" })
                    k.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    // Rows arrive one after another.
                    if (Prefs.animations(ctx)) {
                        row.alpha = 0f
                        row.translationY = -dpf(6f)
                        row.animate().alpha(1f).translationY(0f).setStartDelay(40L + i * 18L).setDuration(180).start()
                    }
                    i++
                }
            }
        }
        c.addView(k, FrameLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(c, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        catcher = c
        card = k
        c.requestFocus()

        // Place it under (or above) what was pressed, inside the screen.
        k.measure(View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val a = IntArray(2)
        anchor.getLocationInWindow(a)
        val rw = root.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val rh = root.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
        val m = dp(12)
        val h = k.measuredHeight
        val below = a[1] + anchor.height + dp(6)
        val above = a[1] - h - dp(6)
        val top = when {
            below + h < rh - dp(24) -> below
            above > dp(24) -> above
            else -> ((rh - h) / 2).coerceAtLeast(m)
        }
        val left = (a[0] + anchor.width / 2 - dp(120)).coerceIn(m, (rw - dp(240) - m).coerceAtLeast(m))
        (k.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = left
            topMargin = top
        }
        // Grow out of the anchor.
        k.pivotX = (a[0] + anchor.width / 2f - left).coerceIn(0f, dpf(240f))
        k.pivotY = if (top >= below) 0f else h.toFloat()
        if (Prefs.animations(ctx)) {
            k.scaleX = 0.82f
            k.scaleY = 0.82f
            k.alpha = 0f
            k.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f)).start()
            c.alpha = 0f
            c.animate().alpha(1f).setDuration(160).start()
        }
    }

    fun dismiss() {
        val c = catcher ?: return
        val k = card
        catcher = null
        card = null
        val done = {
            (c.parent as? ViewGroup)?.removeView(c)
            onDismiss?.invoke()
        }
        if (k != null && Prefs.animations(c.context)) {
            k.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f).setDuration(120).start()
            c.animate().alpha(0f).setDuration(130).withEndAction { done() }.start()
        } else {
            done()
        }
    }

    private fun rowRipple(ctx: android.content.Context): Drawable {
        val mask = ctx.round(Color.WHITE, ctx.dpf(14f))
        return android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), null, mask)
    }

    private fun dp(v: Int) = anchor.context.dp(v)
    private fun dpf(v: Float) = anchor.context.dpf(v)
}

/** Shows a menu from inside an Activity even when there's no handy anchor view. */
fun Activity.haloMenu(anchor: View? = null): HaloMenu = HaloMenu(anchor ?: window.decorView)
