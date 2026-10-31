package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * Halo Launcher's lock screen.
 *
 * Android doesn't let any app replace the real lock screen, so this is a
 * screen that sits on top of it: Halo puts it up the moment the screen goes
 * off, so it's already there when you wake the phone. Your PIN, pattern or
 * fingerprint stay Android's own (that's what keeps the phone safe): swipe
 * up and, if the phone is secured, Android asks for them; unlock with your
 * fingerprint and this screen melts away by itself.
 *
 * What's on it (Settings → Lock screen): the clock and date in your face,
 * in one of three layouts and three sizes, up to two of your widgets, what's
 * playing, your notifications (or just how many, for privacy), and two
 * shortcuts at the bottom (flashlight, camera) that you hold to use, like an
 * iPhone. Everything is Liquid Glass with that theme, and it all comes in
 * with a springy entrance each time you wake the phone.
 */
class LockActivity : Activity(), WidgetActions {
    private lateinit var root: FrameLayout
    private lateinit var back: ImageView
    private lateinit var content: FrameLayout
    private lateinit var clock: ClockFace
    private lateinit var date: DateFace
    private var widgets: LinearLayout? = null
    private val built = ArrayList<BaseWidget>()
    private lateinit var lower: LinearLayout
    private lateinit var notesBox: LinearLayout
    private var music: MusicCard? = null
    private lateinit var bottom: FrameLayout
    private lateinit var hint: TextView
    private lateinit var charge: LinearLayout
    private var leaving = false
    private var torchOn = false
    private var ownPicture = false

    private val keyguard get() = getSystemService(KeyguardManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                // Unlocked another way (fingerprint, face): melt away.
                Intent.ACTION_USER_PRESENT -> leave()
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    clock.tick()
                    date.refresh()
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_BATTERY_CHANGED -> {
                    showCharge()
                    for (w in built) if (w.id == "battery") w.refresh()
                }
            }
        }
    }

    private val linkListener: () -> Unit = { root.post { onHalo() } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(false)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        Palette.adapt(this)
        build()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(receiver, f)
        HaloLink.addListener(linkListener)
        HaloLink.start(this)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        HaloLink.removeListener(linkListener)
        if (ownPicture) Glass.endPreview()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Put up again (the screen went off while it was showing): start fresh.
        leaving = false
        content.translationY = 0f
        content.alpha = 1f
    }

    override fun onResume() {
        super.onResume()
        // Nothing to cover (no lock at all, and already unlocked a while): go.
        leaving = false
        applyBars()
        clock.tick()
        date.refresh()
        for (w in built) w.refresh()
        onHalo()
        showCharge()
        enter()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Back doesn't skip the lock screen.
    }

    /** With Halo's own status bar on, the phone's one hides here (Halo draws its own over this screen). */
    private fun applyBars() {
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsets.Type.navigationBars())
        if (HaloLink.sbOn) c.hide(WindowInsets.Type.statusBars()) else c.show(WindowInsets.Type.statusBars())
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private fun p() = Prefs.get(this)

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = maxOf(dm.heightPixels, windowManager.currentWindowMetrics.bounds.height())
        root = SwipeRoot(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        // The picture: your lock wallpaper (or the home one), and the glass bends it.
        back = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loadPicture(sw, sh)
        // Soft shade at the top and bottom so the clock and cards always read.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(110, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(130, 0, 0, 0)))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        content = FrameLayout(this).apply { clipChildren = false }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val layout = p().getInt(Prefs.LOCK_LAYOUT, 0)
        val size = listOf(0.8f, 1f, 1.22f)[p().getInt(Prefs.LOCK_CLOCK_SIZE, 1).coerceIn(0, 2)]
        val top = dp(56)
        val side = dp(24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(side, top, side, 0)
        }
        content.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        date = DateFace(this, forceStyle = if (layout == 0) 1 else -1).apply {
            alignOverride = if (layout == 2) 0 else 1
        }
        clock = ClockFace(this).apply {
            alignOverride = if (layout == 2) 0 else 1
            stackedOverride = layout != 0
        }
        val clockH = when (layout) {
            0 -> (sh * 0.13f * size).toInt()
            1 -> (sh * 0.27f * size).toInt()
            else -> (sh * 0.24f * size).toInt()
        }
        val clockW = if (layout == 2) ((sw - side * 2) * 0.62f).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        if (layout == 1) {
            // Stacked: the big time first, the date under it.
            col.addView(clock, LinearLayout.LayoutParams(clockW, clockH))
            col.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(4) })
        } else {
            col.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
            col.addView(clock, LinearLayout.LayoutParams(clockW, clockH).apply { topMargin = dp(2) })
        }

        // Charging: a little glass pill with a battery that fills.
        charge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(14), dp(7))
            background = surface(dpf(18f), 0.35f)
            visibility = View.GONE
        }
        col.addView(charge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
            gravity = if (layout == 2) Gravity.START else Gravity.CENTER_HORIZONTAL
        })

        // Your widgets (up to two), side by side.
        val ids = Prefs.list(this, Prefs.LOCK_WIDGETS).filter { id -> HomeWidgets.ALL.any { it.id == id } && id != "music" }.take(2)
        if (ids.isNotEmpty()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            for ((i, id) in ids.withIndex()) {
                val w = HomeWidgets.create(this, id, this) ?: continue
                w.size = HomeWidgets.SMALL
                built += w
                row.addView(w, LinearLayout.LayoutParams(0, dp(150), 1f).apply { if (i > 0) marginStart = dp(12) })
            }
            // One widget: half the width, centred (or on the left with the side layout).
            if (ids.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            widgets = row
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        }

        // The lower part: what's playing, then your notifications, above the shortcuts.
        lower = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(14), 0, dp(14), 0)
        }
        content.addView(lower, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(124) })
        notesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        lower.addView(notesBox)

        // Bottom: two shortcuts you hold to use, and the swipe-up hint between them.
        bottom = FrameLayout(this).apply { clipChildren = false }
        content.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.BOTTOM).apply { bottomMargin = dp(18) })
        shortcut(Gravity.START, p().getInt(Prefs.LOCK_LEFT, 1))
        shortcut(Gravity.END, p().getInt(Prefs.LOCK_RIGHT, 2))
        hint = text(13f, Color.argb(220, 255, 255, 255), bold = true).apply {
            text = "Swipe up to open"
            gravity = Gravity.CENTER
        }
        bottom.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(22) })
        val handle = View(this).apply { background = round(Color.argb(220, 255, 255, 255), dpf(3f)) }
        bottom.addView(handle, FrameLayout.LayoutParams(dp(120), dp(5), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(6) })
        // The hint breathes gently, inviting the swipe.
        android.animation.ObjectAnimator.ofFloat(hint, View.TRANSLATION_Y, 0f, -dpf(5f), 0f).apply {
            duration = 1800L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** Your lock wallpaper if Android lets us read it (and you chose it), else the home picture. */
    private fun loadPicture(sw: Int, sh: Int) {
        val home = Glass.backdrop
        if (p().getInt(Prefs.LOCK_WALL, 0) == 1) {
            val lock = try {
                if (android.os.Build.VERSION.SDK_INT >= 34) WallpaperManager.getInstance(this).getDrawable(WallpaperManager.FLAG_LOCK) else null
            } catch (_: Throwable) {
                null
            }
            if (lock != null) {
                val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                val iw = lock.intrinsicWidth.coerceAtLeast(1)
                val ih = lock.intrinsicHeight.coerceAtLeast(1)
                val s = max(sw.toFloat() / iw, sh.toFloat() / ih)
                val dw = (iw * s).toInt()
                val dh = (ih * s).toInt()
                lock.setBounds((sw - dw) / 2, (sh - dh) / 2, (sw - dw) / 2 + dw, (sh - dh) / 2 + dh)
                lock.draw(Canvas(b))
                back.setImageBitmap(b)
                // The glass bends this picture while the lock screen is up.
                Glass.showPicture(b)
                ownPicture = true
                Palette.adapt(this)
                return
            }
        }
        if (home != null) back.setImageBitmap(home)
    }

    /** A round glass button at the bottom corner: hold it to use (a tap just shows how). */
    private fun shortcut(gravity: Int, kind: Int) {
        if (kind == 0) return
        val btn = ImageView(this).apply {
            setImageResource(if (kind == 1) R.drawable.ic_flashlight else R.drawable.ic_camera)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            val pad = dp(15)
            setPadding(pad, pad, pad, pad)
            background = if (Glass.enabled(this@LockActivity) && Glass.backdrop != null) GlassDrawable(this, dpf(27f), darkness = 0.32f, bendPx = dpf(12f)) else round(Color.argb(120, 30, 30, 34), dpf(27f))
            contentDescription = if (kind == 1) "Flashlight" else "Camera"
            setOnClickListener {
                hint.text = "Hold to use the " + (if (kind == 1) "flashlight" else "camera")
                hint.animate().cancel()
                root.postDelayed({ hint.text = "Swipe up to open" }, 1800L)
                it.animate().translationX(dpf(5f)).setDuration(60).withEndAction {
                    it.animate().translationX(0f).setDuration(320).setInterpolator(OvershootInterpolator(4f)).start()
                }.start()
            }
            setOnLongClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                // A big liquid swell as it fires.
                animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(560).setInterpolator(OvershootInterpolator(3.5f)).start()
                }.start()
                if (kind == 1) {
                    torchOn = !torchOn
                    HaloLink.command(this@LockActivity, "torch", if (torchOn) "1" else "0")
                    imageTintList = ColorStateList.valueOf(if (torchOn) Color.BLACK else Color.WHITE)
                    background = if (torchOn) round(Color.WHITE, dpf(27f)) else (if (Glass.enabled(this@LockActivity) && Glass.backdrop != null) GlassDrawable(this, dpf(27f), darkness = 0.32f, bendPx = dpf(12f)) else round(Color.argb(120, 30, 30, 34), dpf(27f)))
                } else {
                    try {
                        startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                }
                true
            }
        }
        btn.pressable()
        bottom.addView(btn, FrameLayout.LayoutParams(dp(54), dp(54), gravity or Gravity.BOTTOM).apply {
            marginStart = dp(34)
            marginEnd = dp(34)
            bottomMargin = dp(6)
        })
    }

    // =====================================================================
    // Live parts
    // =====================================================================

    private fun onHalo() {
        if (leaving) return
        applyBars()
        // What's playing.
        val m = HaloLink.music
        if (p().getBoolean(Prefs.LOCK_MUSIC, true) && m != null) {
            if (music == null) {
                val card = MusicCard(this) { cmd -> HaloLink.command(this, cmd) }
                music = card
                lower.addView(card, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { bottomMargin = dp(10) })
                card.alpha = 0f
                card.translationY = dpf(30f)
                card.animate().alpha(1f).translationY(0f).setDuration(520).setInterpolator(OvershootInterpolator(1.4f)).start()
            }
            music?.bind(m)
        } else if (music != null) {
            val card = music
            music = null
            card?.animate()?.alpha(0f)?.translationY(dpf(20f))?.setDuration(220)?.withEndAction { lower.removeView(card) }?.start()
        }
        drawNotes()
    }

    private var notesKey = ""

    /**
     * Your notifications as glass cards (newest first, up to three, with how
     * many more), or just how many with privacy on. Tap one to unlock and
     * open it; swipe it sideways to clear it.
     */
    private fun drawNotes() {
        val mode = p().getInt(Prefs.LOCK_NOTES, 0)
        val all = HaloLink.notes.sortedByDescending { it.time }
        val key = mode.toString() + all.joinToString { it.key + it.time }
        if (key == notesKey) return
        notesKey = key
        notesBox.removeAllViews()
        if (mode == 2 || all.isEmpty()) return
        if (mode == 1) {
            val apps = all.map { it.app }.distinct().size
            notesBox.addView(text(14f, Color.WHITE, bold = true).apply {
                text = if (all.size == 1) "1 notification" else "${all.size} notifications" + if (apps > 1) " from $apps apps" else ""
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = surface(dpf(22f), 0.34f)
                setOnClickListener { unlock(null) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            return
        }
        val shown = all.take(3)
        for ((i, n) in shown.withIndex()) {
            val card = noteCard(n)
            notesBox.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(8) })
            card.alpha = 0f
            card.translationY = dpf(24f)
            card.animate().alpha(1f).translationY(0f).setStartDelay(i * 50L).setDuration(480).setInterpolator(OvershootInterpolator(1.5f)).start()
        }
        if (all.size > shown.size) notesBox.addView(text(12.5f, Color.argb(200, 255, 255, 255), bold = true).apply {
            text = "+${all.size - shown.size} more"
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun noteCard(n: HaloLink.Note): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = surface(dpf(22f), 0.34f)
        }
        val icon = ImageView(this).apply {
            setImageDrawable(try {
                packageManager.getApplicationIcon(n.app)
            } catch (_: Exception) {
                null
            })
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(13.5f, Color.WHITE, bold = true).apply { text = n.title.ifEmpty { n.appName } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(11.5f, Color.argb(170, 255, 255, 255)).apply {
            text = if (n.time > 0) DateUtils.getRelativeTimeSpanString(n.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE) else ""
        })
        texts.addView(head)
        texts.addView(text(13f, Color.argb(225, 255, 255, 255), lines = 2).apply { text = n.text.ifEmpty { n.appName } })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Tap: unlock, then open it. Sideways: clear it.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var dx0 = 0f
        var dy0 = 0f
        var sliding = false
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx0 = e.rawX
                    dy0 = e.rawY
                    sliding = false
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dx0
                    if (!sliding && abs(dx) > slop && abs(dx) > abs(e.rawY - dy0)) {
                        sliding = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (sliding) {
                        v.translationX = dx
                        v.alpha = (1f - abs(dx) / v.width * 0.9f).coerceIn(0.1f, 1f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - dx0
                    v.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(OvershootInterpolator(3f)).start()
                    if (sliding && abs(dx) > v.width * 0.3f) {
                        v.animate().translationX(Math.signum(dx) * v.width * 1.1f).alpha(0f).setDuration(160).withEndAction {
                            HaloLink.command(this, "dismiss_notif", n.key)
                            notesBox.removeView(v)
                        }.start()
                    } else if (sliding) {
                        v.animate().translationX(0f).alpha(1f).setDuration(420).setInterpolator(OvershootInterpolator(1.8f)).start()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawY - dy0) < slop) {
                        unlock { HaloLink.command(this, "open_notif", n.key) }
                    }
                }
            }
            true
        }
        return card
    }

    /** Charging: the glass pill shows the level with a battery that fills, and how long to full. */
    private fun showCharge() {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val bm = getSystemService(android.os.BatteryManager::class.java)
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        if (!plugged) {
            if (charge.visibility == View.VISIBLE) charge.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(200).withEndAction { charge.visibility = View.GONE }.start()
            return
        }
        charge.removeAllViews()
        val cap = Capsule(this).apply {
            color = Palette.GREEN
            fraction = 0f
            charging = true
        }
        charge.addView(cap, LinearLayout.LayoutParams(dp(30), dp(14)))
        val left = bm?.computeChargeTimeRemaining() ?: -1L
        charge.addView(text(13f, Color.WHITE, bold = true).apply {
            text = "$level%" + if (left > 0) "  ·  full in " + ScreenTime.format(left) else "  ·  charging"
            setPadding(dp(8), 0, 0, 0)
        })
        val was = charge.visibility == View.VISIBLE
        charge.visibility = View.VISIBLE
        android.animation.ValueAnimator.ofFloat(0f, level / 100f).apply {
            duration = 900
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { cap.fraction = it.animatedValue as Float }
            start()
        }
        if (!was) {
            charge.alpha = 0f
            charge.scaleX = 0.6f
            charge.scaleY = 0.6f
            charge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(2.4f)).start()
        }
    }

    // =====================================================================
    // Motion
    // =====================================================================

    /** Each time you wake the phone: the clock drops in, then the rest follows, springy. */
    private fun enter() {
        if (!Prefs.animations(this)) return
        content.translationY = 0f
        content.alpha = 1f
        content.scaleX = 1f
        content.scaleY = 1f
        back.scaleX = 1.08f
        back.scaleY = 1.08f
        back.animate().scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(DecelerateInterpolator(2f)).start()
        val items = ArrayList<View>()
        items += date
        items += clock
        if (charge.visibility == View.VISIBLE) items += charge
        widgets?.let { row -> for (i in 0 until row.childCount) items += row.getChildAt(i) }
        items += lower
        for ((i, v) in items.withIndex()) {
            v.alpha = 0f
            v.translationY = if (v === lower) dpf(40f) else -dpf(24f)
            v.scaleX = 0.94f
            v.scaleY = 0.94f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(40L + i * 55L).setDuration(620)
                .setInterpolator(OvershootInterpolator(1.3f)).start()
        }
        for (i in 0 until bottom.childCount) {
            val v = bottom.getChildAt(i)
            v.alpha = 0f
            v.scaleX = 0.5f
            v.scaleY = 0.5f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(220L + i * 50L).setDuration(560).setInterpolator(OvershootInterpolator(2.6f)).start()
        }
    }

    /** Follows your finger up; [f] 0..1 of the way to unlocking. */
    private fun drag(dy: Float) {
        val h = root.height.toFloat().coerceAtLeast(1f)
        val up = (-dy).coerceAtLeast(0f)
        val f = (up / (h * 0.35f)).coerceIn(0f, 1f)
        content.translationY = -up * 0.85f
        content.alpha = 1f - f * 0.75f
        val s = 1f - 0.06f * f
        content.scaleX = s
        content.scaleY = s
        back.scaleX = 1f + 0.06f * f
        back.scaleY = 1f + 0.06f * f
    }

    private fun springBack() {
        content.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(560).setInterpolator(OvershootInterpolator(1.6f)).start()
        back.animate().scaleX(1f).scaleY(1f).setDuration(500).start()
    }

    /**
     * Unlock: if the phone is secured, Android asks for your PIN, pattern or
     * fingerprint (only it can); once you're in, [then] runs (open a
     * notification) and this screen lifts away. With no PIN it just lifts.
     */
    private fun unlock(then: (() -> Unit)?) {
        if (leaving) return
        content.animate().translationY(-root.height * 0.5f).alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(320)
            .setInterpolator(PathInterpolator(0.3f, 0f, 0.2f, 1f)).start()
        val km = keyguard
        if (km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    then?.invoke()
                    leave()
                }

                override fun onDismissCancelled() = springBack()

                override fun onDismissError() = springBack()
            })
        } else {
            then?.invoke()
            leave()
        }
    }

    /** Lifts away like liquid (the picture swells and fades) and closes. */
    private fun leave() {
        if (leaving) return
        leaving = true
        content.animate().translationY(-root.height * 0.6f).alpha(0f).setDuration(300).setInterpolator(PathInterpolator(0.3f, 0f, 0.2f, 1f)).start()
        back.animate().scaleX(1.15f).scaleY(1.15f).alpha(0f).setDuration(340).withEndAction {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }.start()
    }

    /**
     * The whole screen: a swipe up anywhere (even starting on a card) lifts
     * it towards unlocking; let go far enough, or flick, and it unlocks.
     */
    private inner class SwipeRoot(c: Context) : FrameLayout(c) {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var downY = 0f
        private var downX = 0f
        private var dragging = false
        private var vt: VelocityTracker? = null

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    downX = e.rawX
                    dragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - downY
                    if (!dragging && -dy > slop * 1.5f && abs(dy) > abs(e.rawX - downX) * 1.3f) {
                        dragging = true
                        content.animate().cancel()
                        return true
                    }
                }
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    downX = e.rawX
                    vt?.recycle()
                    vt = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && -dy > slop) dragging = true
                    if (dragging) drag(dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    val dy = e.rawY - downY
                    if (dragging && (-dy > height * 0.18f || v < -1200f)) unlock(null) else if (dragging) springBack()
                    dragging = false
                }
            }
            return true
        }
    }

    // =====================================================================
    // Widget actions: anything that needs the phone unlocks first.
    // =====================================================================

    override fun askPermission(permission: String) = unlock(null)
    override fun openSettingsPage(page: String) = unlock { startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    override fun openCalendar() = unlock {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
    override fun editNote() = unlock(null)
    override fun refreshWeather() {
        Weather.refresh(this, force = true) { for (w in built) w.refresh() }
    }
    override fun openUsageAccess() = unlock(null)
    override fun launchPackage(pkg: String, from: View) = unlock {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    override fun pickPhoto() = unlock(null)
    override fun musicCommand(cmd: String) {
        if (cmd == "open_music") unlock { HaloLink.command(this, cmd) } else HaloLink.command(this, cmd)
    }
}
