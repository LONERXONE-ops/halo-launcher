package com.lonerxone.halo.launcher

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Side-by-side home pages. Takes over a touch only once it is clearly
 * horizontal, so taps and vertical swipes still reach the widgets, then snaps
 * to the nearest page with a fling.
 */
class HomePager(context: Context) : ViewGroup(context) {
    private val scroller = OverScroller(context, Spring(0.35f))
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 3
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    var current = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null
    /** 0..pages-1 as a float, for the dots and the wallpaper. */
    var onScrolled: ((Float) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (changed) scrollTo(current * w, 0)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                dragging = !scroller.isFinished
                if (dragging) scroller.abortAnimation()
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    dragging = true
                    lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    tracker?.recycle()
                    tracker = null
                }
            }
        }
        return dragging
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    if (abs(e.x - downX) > slop) dragging = true else return true
                }
                val dx = lastX - e.x
                lastX = e.x
                val max = (childCount - 1) * width
                var nx = scrollX + dx
                // Rubber band past the ends.
                if (nx < 0 || nx > max) nx = scrollX + dx * 0.35f
                scrollTo(nx.roundToInt(), 0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                t?.computeCurrentVelocity(1000)
                val vx = t?.xVelocity ?: 0f
                val target = when {
                    vx < -minFling -> current + 1
                    vx > minFling -> current - 1
                    else -> ((scrollX + width / 2f) / width).toInt()
                }
                snapTo(target)
                dragging = false
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    fun snapTo(page: Int, animate: Boolean = true) {
        val p = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = p != current
        current = p
        val dx = p * width - scrollX
        if (animate) {
            scroller.startScroll(scrollX, 0, dx, 0, (300 + abs(dx) / 8).coerceAtMost(420))
            postInvalidateOnAnimation()
        } else {
            scrollTo(p * width, 0)
        }
        if (changed) onPageChanged?.invoke(p)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width > 0) onScrolled?.invoke(l.toFloat() / width)
    }

    fun page(i: Int): View? = getChildAt(i)
}
