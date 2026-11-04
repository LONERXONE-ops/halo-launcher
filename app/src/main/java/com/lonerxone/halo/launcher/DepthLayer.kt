package com.lonerxone.halo.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import kotlin.math.abs

/**
 * Depth clock: the subject of your wallpaper (a person, a mountain, a building)
 * drawn again over the clock, so the time sits behind it, like iOS and One UI.
 *
 * There is no AI here: it finds the background by growing out from the top
 * edge through similar colours (sky, a plain wall, a gradient), and keeps
 * what rises from the bottom edge as the subject. That works on pictures with
 * a clear subject against a plain background, and it switches itself off when
 * the subject would hide too much of the time.
 *
 * It only draws inside the clock's box (wherever the clock is right now, so
 * it follows the clock as you swipe), and it scales with the picture [back].
 */
class DepthLayer(c: Context, private val back: View, private val shade: View?, private val clock: View) : View(c) {
    private var subject: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val box = IntArray(2)
    private val me = IntArray(2)
    private var lastKey = 0f
    private var running = false

    /** Whether a subject was found (false: nothing to draw). */
    val active get() = subject != null

    init {
        // Never takes touches.
        isClickable = false
        isFocusable = false
    }

    /**
     * Looks at what [back] shows now and finds the subject. [sens] 0 Gentle,
     * 1 Normal, 2 Strong (how different a colour must be to count as subject).
     * [done] gets whether it found one.
     */
    fun build(sens: Int, done: (Boolean) -> Unit) {
        val w = back.width
        val h = back.height
        if (w <= 0 || h <= 0 || back.visibility != VISIBLE) return done(false)
        // A small copy to look at, and a half size one to cut the subject from.
        val sw = 90
        val sh = (h * sw / w.toFloat()).toInt().coerceAtLeast(2)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(small).apply { scale(sw / w.toFloat(), sh / h.toFloat()) }.also { back.draw(it) }
        val half = Bitmap.createBitmap((w / 2).coerceAtLeast(1), (h / 2).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // With the soft shade on top too, so the cut out part matches what's around it.
        Canvas(half).apply { scale(0.5f, 0.5f) }.also {
            back.draw(it)
            shade?.draw(it)
        }
        // Where the clock is, in the small picture's terms (to check the overlap).
        clock.getLocationInWindow(box)
        back.getLocationInWindow(me)
        val cl = ((box[0] - me[0]) * sw / w.toFloat()).toInt().coerceIn(0, sw - 1)
        val ct = ((box[1] - me[1] - clock.translationY) * sh / h.toFloat()).toInt().coerceIn(0, sh - 1)
        val cr = ((box[0] - me[0] + clock.width) * sw / w.toFloat()).toInt().coerceIn(cl + 1, sw)
        val cb = ((box[1] - me[1] - clock.translationY + clock.height) * sh / h.toFloat()).toInt().coerceIn(ct + 1, sh)
        val main = Handler(Looper.getMainLooper())
        Thread {
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            small.recycle()
            val mask = findSubject(px, sw, sh, intArrayOf(26, 38, 54)[sens.coerceIn(0, 2)])
            var inside = 0
            var all = 0
            var total = 0
            for (y in 0 until sh) for (x in 0 until sw) {
                val on = mask[y * sw + x] != 0.toByte()
                if (on) total++
                if (x in cl until cr && y in ct until cb) {
                    all++
                    if (on) inside++
                }
            }
            // Nothing found, everything found, or the time would mostly vanish: skip it.
            val share = if (all == 0) 0f else inside / all.toFloat()
            val ok = total > sw * sh * 0.02f && total < sw * sh * 0.85f && share in 0.015f..0.5f
            main.post {
                if (!ok) {
                    half.recycle()
                    subject?.recycle()
                    subject = null
                    invalidate()
                    done(false)
                    return@post
                }
                // Cut the subject out: the small mask, stretched with smoothing, keeps the edge soft.
                val m = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
                val a = IntArray(sw * sh) { if (mask[it] != 0.toByte()) -0x1000000 else 0 }
                val tmp = Bitmap.createBitmap(a, sw, sh, Bitmap.Config.ARGB_8888)
                Canvas(m).drawBitmap(tmp, 0f, 0f, null)
                tmp.recycle()
                val cut = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                Canvas(half).drawBitmap(m, Rect(0, 0, sw, sh), Rect(0, 0, half.width, half.height), cut)
                m.recycle()
                subject?.recycle()
                subject = half
                invalidate()
                follow()
                done(true)
            }
        }.start()
    }

    fun clear() {
        subject?.recycle()
        subject = null
        running = false
        invalidate()
    }

    /**
     * The background is what joins the top edge through small colour steps
     * (a sky fading into haze still counts). The subject is what's left that
     * joins the bottom edge, so clouds and birds up in the sky never cover the time.
     */
    private fun findSubject(px: IntArray, w: Int, h: Int, step: Int): ByteArray {
        val bg = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        // The top row's average, so a slow drift can't wander into the subject.
        var r = 0
        var g = 0
        var b = 0
        for (x in 0 until w) {
            val c = px[x]
            r += (c shr 16) and 255
            g += (c shr 8) and 255
            b += c and 255
        }
        r /= w
        g /= w
        b /= w
        val far = step * 5
        for (x in 0 until w) {
            bg[x] = true
            queue[tail++] = x
        }
        // The top quarter of the side edges counts as background too.
        for (y in 1 until h / 4) for (x in intArrayOf(0, w - 1)) {
            val i = y * w + x
            if (!bg[i]) {
                bg[i] = true
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            val c = px[i]
            for (d in 0 until 4) {
                val nx = x + if (d == 0) 1 else if (d == 1) -1 else 0
                val ny = y + if (d == 2) 1 else if (d == 3) -1 else 0
                if (nx !in 0 until w || ny !in 0 until h) continue
                val j = ny * w + nx
                if (bg[j]) continue
                val n = px[j]
                val near = abs(((c shr 16) and 255) - ((n shr 16) and 255)) + abs(((c shr 8) and 255) - ((n shr 8) and 255)) + abs((c and 255) - (n and 255))
                val drift = abs(r - ((n shr 16) and 255)) + abs(g - ((n shr 8) and 255)) + abs(b - (n and 255))
                if (near < step && drift < far) {
                    bg[j] = true
                    queue[tail++] = j
                }
            }
        }
        // Keep only the part of the rest that reaches the bottom edge.
        val out = ByteArray(w * h)
        head = 0
        tail = 0
        for (x in 0 until w) {
            val i = (h - 1) * w + x
            if (!bg[i]) {
                out[i] = 1
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            for (d in 0 until 4) {
                val nx = x + if (d == 0) 1 else if (d == 1) -1 else 0
                val ny = y + if (d == 2) 1 else if (d == 3) -1 else 0
                if (nx !in 0 until w || ny !in 0 until h) continue
                val j = ny * w + nx
                if (bg[j] || out[j] != 0.toByte()) continue
                out[j] = 1
                queue[tail++] = j
            }
        }
        return out
    }

    /** Redraws while the clock or the picture moves (waking, swiping), and stops when still. */
    private fun follow() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (!running || !isAttachedToWindow || subject == null) {
                    running = false
                    return
                }
                clock.getLocationInWindow(box)
                val key = box[0] * 0.37f + box[1] * 1.3f + back.scaleX * 1000f + clock.alpha * 77f + clock.scaleX * 500f
                if (key != lastKey) {
                    lastKey = key
                    invalidate()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val s = subject ?: return
        if (clock.alpha <= 0.01f || clock.visibility != VISIBLE) return
        clock.getLocationInWindow(box)
        getLocationInWindow(me)
        val l = (box[0] - me[0]).toFloat()
        val t = (box[1] - me[1]).toFloat()
        c.save()
        // Only over the clock (scaled as the clock is), so widgets and cards stay on top.
        c.clipRect(l, t, l + clock.width * clock.scaleX, t + clock.height * clock.scaleY)
        // The picture's own zoom (waking, swiping), around its centre.
        c.scale(back.scaleX, back.scaleY, width / 2f, height / 2f)
        src.set(0, 0, s.width, s.height)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.alpha = (255 * clock.alpha).toInt().coerceIn(0, 255)
        c.drawBitmap(s, src, dst, paint)
        c.restore()
    }
}
