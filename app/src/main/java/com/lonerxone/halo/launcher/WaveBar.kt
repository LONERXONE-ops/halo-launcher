package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * The song's progress as a wave, like Samsung and Android's media player:
 * the part already played ripples while the music plays (and flattens out
 * when paused), the rest is a quiet straight line. Drag along it to seek.
 */
class WaveBar(context: Context) : View(context) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var playing = false
        set(v) {
            field = v
            postInvalidateOnAnimation()
        }
    var color = Color.WHITE
    /** Called with 0..1 when you let go after dragging. */
    var onSeek: ((Float) -> Unit)? = null
    /** While dragging: 0..1 (for the time labels), or null. */
    var onScrub: ((Float?) -> Unit)? = null

    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rest = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var phase = 0f
    private var amp = 0f
    private var last = 0L
    private var dragging = false
    private var dragFrac = 0f
    private var lastLiveSeek = 0L

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        // The ripple grows in when playing and settles flat when paused.
        val target = if (playing && !dragging) 1f else 0f
        amp += (target - amp) * (1f - Math.exp((-8f * dt).toDouble()).toFloat())
        if (playing) phase += dt * 2f * PI.toFloat() * 0.9f

        val stroke = dpf(3.2f)
        val pad = dpf(8f)
        val w = width - pad * 2
        val cy = height / 2f
        val f = if (dragging) dragFrac else fraction
        val x = pad + w * f
        val a = dpf(3.2f) * amp
        val wave = dpf(15f)

        played.strokeWidth = stroke
        played.color = color
        rest.strokeWidth = stroke
        rest.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
        thumb.color = color

        path.reset()
        path.moveTo(pad, cy)
        var px = pad
        val step = dpf(2f)
        while (px < x) {
            val yy = cy + a * sin(((px - pad) / wave) * 2f * PI.toFloat() - phase)
            path.lineTo(px, yy)
            px += step
        }
        path.lineTo(x, cy + a * sin(((x - pad) / wave) * 2f * PI.toFloat() - phase))
        canvas.drawPath(path, played)
        if (x < pad + w) canvas.drawLine(x + dpf(6f), cy, pad + w, cy, rest)
        // The thumb: a short upright pill.
        val th = dpf(if (dragging) 22f else 16f)
        val tw = dpf(5f)
        canvas.drawRoundRect(x - tw / 2f, cy - th / 2f, x + tw / 2f, cy + th / 2f, tw / 2f, tw / 2f, thumb)

        if ((playing || amp > 0.01f) && isShown) postInvalidateOnAnimation() else last = 0L
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (onSeek == null) return false
        val pad = dpf(8f)
        val f = ((e.x - pad) / (width - pad * 2)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // The home screen's hold-to-edit mustn't fire while you scrub.
                var pp = parent
                while (pp is View) {
                    pp.cancelLongPress()
                    pp = pp.parent
                }
                dragging = true
                dragFrac = f
                onScrub?.invoke(f)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                dragFrac = f
                onScrub?.invoke(f)
                // Seek as you drag (a few times a second), so you hear where you are.
                val now = System.currentTimeMillis()
                if (now - lastLiveSeek > 250) {
                    lastLiveSeek = now
                    onSeek?.invoke(f)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                fraction = f
                onScrub?.invoke(null)
                onSeek?.invoke(f)
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onScrub?.invoke(null)
                invalidate()
            }
        }
        return true
    }
}
