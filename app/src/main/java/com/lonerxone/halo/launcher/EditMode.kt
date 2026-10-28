package com.lonerxone.halo.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Editing the home screen, the iOS way (with ideas from Nothing and One UI):
 * long-press the wallpaper or anything on it and everything starts to
 * jiggle. Then:
 *  - drag a widget, the dock's apps or page 1's clock and music player to a
 *    new place; the others slide out of the way as you go;
 *  - tap the size pill on a widget to make it Small, Medium or Large;
 *  - tap the minus to take it off the screen;
 *  - Done (or Home, or Back) to finish.
 */

/** Something on the home screen that can be moved in edit mode. */
@SuppressLint("ViewConstructor")
class Cell(
    context: Context,
    private val editor: Editor,
    val zone: String,
    val itemId: String,
    val content: View?,
    removable: Boolean = true,
    sizeLabel: String? = null,
    /** Long-press starts edit mode (off for app icons, whose long-press opens their menu). */
    private val longPressEdits: Boolean = true,
    /** For icons: put the minus this far left of centre instead of in the corner. */
    minusFromCentre: Int = -1,
) : FrameLayout(context) {
    private val longPress = Runnable {
        if (!editor.editing) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // Keep this finger: moving it now drags this item.
            parent?.requestDisallowInterceptTouchEvent(true)
            editor.enter()
        }
    }
    private val minus: View?
    private val sizePill: TextView?
    private var jiggle: ObjectAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 0.6f
    var onRemove: (() -> Unit)? = null
    var onSize: (() -> Unit)? = null
    /** An empty slot that keeps the place of the item being dragged. */
    val placeholder = content == null

    init {
        clipChildren = false
        clipToPadding = false
        if (content != null) addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        minus = if (removable && content != null) View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 44, 44, 48))
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            foreground = context.getDrawable(R.drawable.ic_minus)
            elevation = dpf(6f)
            visibility = GONE
        } else {
            null
        }
        minus?.let {
            if (minusFromCentre >= 0) {
                addView(it, LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { leftMargin = -minusFromCentre * 2 })
            } else {
                addView(it, LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(6)
                    topMargin = dp(6)
                })
            }
        }
        sizePill = if (sizeLabel != null && content != null) context.text(11f, Color.BLACK, bold = true).apply {
            text = sizeLabel
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = context.round(Palette.INK, dpf(12f))
            elevation = dpf(6f)
            visibility = GONE
        } else {
            null
        }
        sizePill?.let {
            addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, dp(24), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(8)
                bottomMargin = dp(8)
            })
        }
        if (placeholder) {
            background = GradientDrawable().apply {
                cornerRadius = dpf(24f)
                setColor(Color.argb(28, 255, 255, 255))
                setStroke(dp(1), Color.argb(60, 255, 255, 255), dpf(6f), dpf(5f))
            }
        }
        editor.register(this)
    }

    /** Has been on screen (so once it's off screen again, it's gone for good). */
    var wasAttached = false
        private set

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wasAttached = true
        setEditing(editor.editing, animate = false)
    }

    override fun onDetachedFromWindow() {
        jiggle?.cancel()
        jiggle = null
        super.onDetachedFromWindow()
    }

    fun setEditing(want: Boolean, animate: Boolean = true) {
        // While lifted and dragged, it just floats: no badges, no wobble.
        val on = want && !editor.isFloating(this)
        for (v in listOfNotNull(minus, sizePill)) {
            if (on && v.visibility != VISIBLE) {
                v.visibility = VISIBLE
                if (animate) {
                    v.scaleX = 0f
                    v.scaleY = 0f
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(260).setInterpolator(OvershootInterpolator(2.4f)).start()
                }
            } else if (!on && v.visibility == VISIBLE) {
                if (animate) v.animate().scaleX(0f).scaleY(0f).setStartDelay(0).setDuration(160).withEndAction { v.visibility = GONE }.start() else v.visibility = GONE
            }
        }
        if (on && !placeholder && Prefs.animations(context)) startJiggle() else stopJiggle()
    }

    /** A gentle wobble; bigger things wobble less, each slightly out of step. */
    private fun startJiggle() {
        if (jiggle != null) return
        val w = width.takeIf { it > 0 } ?: dp(120)
        val deg = (dpf(70f) / w * 1.6f).coerceIn(0.35f, 1.5f)
        jiggle = ObjectAnimator.ofFloat(this, View.ROTATION, -deg, deg).apply {
            duration = (230 + (itemId.hashCode() and 0x3F)).toLong()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            currentPlayTime = (itemId.hashCode() and 0xFF).toLong()
            start()
        }
    }

    private fun stopJiggle() {
        jiggle?.cancel()
        jiggle = null
        if (rotation != 0f) animate().rotation(0f).setDuration(160).start()
    }

    /**
     * In edit mode the cell takes every touch (its content shouldn't open).
     * Otherwise it watches for a long-press, which starts edit mode; keep
     * holding and moving and you're straight into dragging, like iOS.
     */
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (placeholder) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                if (!editor.editing && longPressEdits) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(e.rawX - downX, e.rawY - downY) > slop * 2.5f) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        if (editor.editing && e.actionMasked != MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return editor.editing
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editor.editing || placeholder) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    dragging = true
                    val x = e.rawX
                    val y = e.rawY
                    // Lift it on the next frame (not in the middle of this touch).
                    editor.willDrag(x, y)
                    post { editor.startDrag(this, x, y) }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    when {
                        hit(minus, e) -> {
                            performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            onRemove?.invoke()
                        }
                        hit(sizePill, e) -> {
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            onSize?.invoke()
                        }
                    }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                // Lifting the cell cancels its own touch; the editor carries on.
                if (!editor.isFloating(this)) animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                dragging = false
            }
        }
        return true
    }

    private fun hit(v: View?, e: MotionEvent): Boolean {
        if (v == null || v.visibility != VISIBLE) return false
        val pad = dp(10)
        return e.x >= v.left - pad && e.x <= v.right + pad && e.y >= v.top - pad && e.y <= v.bottom + pad
    }
}

/**
 * Runs edit mode: which cells exist, the jiggle, and dragging. The dragged
 * cell is lifted into an overlay above everything (still live: glass keeps
 * bending the picture under it) and an empty slot keeps its place. As the
 * finger passes over another item the host reorders, and every cell glides
 * from where it was to where it now is.
 */
class Editor(private val host: Host) {
    interface Host {
        /** The full-screen layer the dragged item floats in. */
        val overlay: FrameLayout
        /** Rebuild a zone; the item being dragged is drawn as an empty slot. */
        fun rebuildZone(zone: String)
        /** Put [id] where [targetId] is (in the same zone). */
        fun moveItem(zone: String, id: String, targetId: String)
        /** The scroller that holds this zone, for scrolling while dragging near an edge. */
        fun scrollerFor(zone: String): ScrollView?
        fun onEditingChanged(on: Boolean)

        /**
         * Free placement on the home grid ("home" zone): the dragged item's
         * top-left on screen and the finger, so the host can show where it
         * would land and turn the page at the screen's edge.
         */
        fun homeHover(id: String, left: Float, top: Float, fingerX: Float)

        /** Commit the drop; returns where (on screen) the item now sits, or null to just rebuild. */
        fun homeDrop(id: String): IntArray?
    }

    var editing = false
        private set
    /** The id being dragged (its zone draws a slot for it), or null. */
    var draggingId: String? = null
        private set
    var draggingZone: String? = null
        private set
    /** The last item dropped (so the host can show its page). */
    var lastDropped: String? = null
        private set

    private val cells = ArrayList<Cell>()
    private var floating: Cell? = null
    private var pendingUp = false

    fun isFloating(c: Cell) = floating === c
    val isDragging: Boolean get() = floating != null || pendingDrag
    private var pendingDrag = false

    /** Asked to lift (the lift happens next frame); the root routes touches to us from now on. */
    fun willDrag(rawX: Float, rawY: Float) {
        pendingDrag = true
        pendingUp = false
        lastX = rawX
        lastY = rawY
    }

    /** The root sends every touch here while something is being dragged. */
    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.rawX
                lastY = e.rawY
                if (floating != null) dragTo(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (floating != null) drop() else pendingUp = true
                pendingDrag = false
            }
        }
    }
    private var grabX = 0f
    private var grabY = 0f
    private var lastSwap = 0L
    private var lastX = 0f
    private var lastY = 0f
    private val autoScroll = object : Runnable {
        override fun run() {
            val zone = draggingZone ?: return
            val sv = host.scrollerFor(zone) ?: return
            val loc = IntArray(2)
            sv.getLocationOnScreen(loc)
            val edge = sv.dpf(90f)
            val dy = when {
                lastY < loc[1] + edge -> -(sv.dpf(14f))
                lastY > loc[1] + sv.height - edge -> sv.dpf(14f)
                else -> 0f
            }
            if (dy != 0f && sv.canScrollVertically(if (dy > 0) 1 else -1)) {
                sv.scrollBy(0, dy.toInt())
                findTarget()
            }
            sv.postOnAnimation(this)
        }
    }

    fun register(c: Cell) {
        cells.removeAll { it.wasAttached && !it.isAttachedToWindow && it.parent == null && it !== floating }
        cells += c
    }

    private fun live(zone: String) = cells.filter { it.zone == zone && it.isAttachedToWindow && it !== floating }

    fun enter() {
        if (editing) return
        editing = true
        for (c in cells.filter { it.isAttachedToWindow }) c.setEditing(true)
        host.onEditingChanged(true)
    }

    fun exit() {
        if (!editing) return
        if (floating != null) drop()
        editing = false
        for (c in cells.filter { it.isAttachedToWindow }) c.setEditing(false)
        host.onEditingChanged(false)
    }

    // ----- dragging -----

    fun startDrag(c: Cell, rawX: Float, rawY: Float) {
        pendingDrag = false
        if (floating != null || !editing) return
        if (pendingUp) {
            // The finger lifted before we got going.
            pendingUp = false
            c.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            return
        }
        val parent = c.parent as? ViewGroup ?: return
        c.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val loc = IntArray(2)
        c.getLocationOnScreen(loc)
        val oloc = IntArray(2)
        host.overlay.getLocationOnScreen(oloc)
        val w = c.width
        val h = c.height
        val idx = parent.indexOfChild(c)
        val lp = c.layoutParams
        parent.removeViewAt(idx)
        draggingId = c.itemId
        draggingZone = c.zone
        floating = c
        // In a row (the dock) leave an empty slot where it was; the home grid
        // shows an outline where it will land instead.
        if (c.zone != "home") {
            val slot = Cell(c.context, this, c.zone, c.itemId, null)
            parent.addView(slot, idx, lp)
        }
        // Lift the real thing above everything.
        c.rotation = 0f
        host.overlay.addView(c, FrameLayout.LayoutParams(w, h))
        c.translationX = (loc[0] - oloc[0]).toFloat()
        c.translationY = (loc[1] - oloc[1]).toFloat()
        c.elevation = c.dpf(16f)
        c.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.96f).setDuration(160).start()
        grabX = rawX - loc[0]
        grabY = rawY - loc[1]
        dragTo(lastX, lastY)
        host.scrollerFor(c.zone)?.postOnAnimation(autoScroll)
    }

    fun dragTo(rawX: Float, rawY: Float) {
        val c = floating ?: return
        lastX = rawX
        lastY = rawY
        val oloc = IntArray(2)
        host.overlay.getLocationOnScreen(oloc)
        c.translationX = rawX - grabX - oloc[0]
        c.translationY = rawY - grabY - oloc[1]
        findTarget()
    }

    /** Whichever item's centre is closest to the dragged item's centre (and close enough) takes its place. */
    private fun findTarget() {
        val c = floating ?: return
        val zone = draggingZone ?: return
        if (zone == "home") {
            host.homeHover(c.itemId, lastX - grabX, lastY - grabY, lastX)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSwap < 180) return
        val cx = lastX - grabX + c.width / 2f
        val cy = lastY - grabY + c.height / 2f
        var best: Cell? = null
        var bestD = Float.MAX_VALUE
        val loc = IntArray(2)
        for (o in live(zone)) {
            if (o.itemId == draggingId) continue
            o.getLocationOnScreen(loc)
            val ox = loc[0] + o.width / 2f
            val oy = loc[1] + o.height / 2f
            // Inside the other item's middle area.
            if (abs(cx - ox) > o.width * 0.42f || abs(cy - oy) > o.height * 0.42f) continue
            val d = hypot(cx - ox, cy - oy)
            if (d < bestD) {
                bestD = d
                best = o
            }
        }
        val target = best ?: return
        lastSwap = now
        val before = positions(zone)
        host.moveItem(zone, c.itemId, target.itemId)
        host.rebuildZone(zone)
        glide(zone, before)
        c.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    fun drop() {
        val c = floating ?: return
        val zone = draggingZone ?: return
        host.scrollerFor(zone)?.removeCallbacks(autoScroll)
        val slot = live(zone).firstOrNull { it.placeholder && it.itemId == c.itemId }
        val target: IntArray? = if (zone == "home") host.homeDrop(c.itemId) else slot?.let { s -> IntArray(2).also { s.getLocationOnScreen(it) } }
        val loc = IntArray(2)
        val oloc = IntArray(2)
        host.overlay.getLocationOnScreen(oloc)
        lastDropped = c.itemId
        val finish = {
            host.overlay.removeView(c)
            floating = null
            draggingId = null
            draggingZone = null
            cells.remove(c)
            host.rebuildZone(zone)
        }
        if (target == null) {
            finish()
            return
        }
        loc[0] = target[0]
        loc[1] = target[1]
        c.animate()
            .translationX((loc[0] - oloc[0]).toFloat())
            .translationY((loc[1] - oloc[1]).toFloat())
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setInterpolator(PathInterpolator(0.2f, 0.9f, 0.2f, 1f))
            .setDuration(240)
            .withEndAction { finish() }
            .start()
    }

    /** Remember where each item is on screen, before a change. */
    fun positions(zone: String): Map<String, IntArray> {
        val m = HashMap<String, IntArray>()
        for (o in live(zone)) {
            val loc = IntArray(2)
            o.getLocationOnScreen(loc)
            m[o.itemId] = loc
        }
        return m
    }

    /** After a change, slide every item from where it was to where it now is. */
    fun glide(zone: String, before: Map<String, IntArray>) {
        val any = live(zone).firstOrNull() ?: host.overlay
        val vto = any.viewTreeObserver
        vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (vto.isAlive) vto.removeOnPreDrawListener(this) else any.viewTreeObserver.removeOnPreDrawListener(this)
                val loc = IntArray(2)
                for (o in live(zone)) {
                    val old = before[o.itemId] ?: continue
                    o.getLocationOnScreen(loc)
                    val dx = (old[0] - loc[0]).toFloat()
                    val dy = (old[1] - loc[1]).toFloat()
                    if (dx == 0f && dy == 0f) continue
                    o.translationX = dx
                    o.translationY = dy
                    o.animate().translationX(0f).translationY(0f)
                        .setInterpolator(PathInterpolator(0.2f, 0.9f, 0.2f, 1f))
                        .setDuration(280).start()
                }
                return true
            }
        })
    }
}
