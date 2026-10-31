package com.lonerxone.halo.launcher

import android.os.Build
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * How Liquid Glass reacts to your finger, like iOS 26: press and the glass
 * swells a little, lights up under your finger (a soft glow that follows
 * it) and bulges like a drop of water; drag and it leans and stretches
 * after you, resisting more the further you pull; let go and it springs
 * back with a small wobble. Driven by real spring physics (not a fixed
 * curve), so a quick tap and a long hold both feel natural.
 *
 * The idea follows the open source Backdrop catalog by Kyant (Apache 2.0):
 * grow by about 4dp, lean with tanh, stretch along the drag, springs with
 * damping 0.5 and stiffness 300.
 */
class LiquidTouch(private val view: View) : Runnable {
    private var pressT = 0f
    private var pressV = 0f
    private var pressTarget = 0f
    private var offX = 0f
    private var offY = 0f
    private var offVX = 0f
    private var offVY = 0f
    private var offTargetX = 0f
    private var offTargetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastFrame = 0L
    private var running = false
    private var baseSX = 1f
    private var baseSY = 1f
    private var baseTX = 0f
    private var baseTY = 0f
    private var active = false

    /** The glass pane this view draws: its background, or an icon's own glass tile. */
    private fun surface(): GlassDrawable? = (view.background as? GlassDrawable) ?: (view as? AppIconView)?.glassTile()

    fun down(e: MotionEvent) {
        if (!active) {
            baseSX = view.scaleX
            baseSY = view.scaleY
            baseTX = view.translationX
            baseTY = view.translationY
        }
        active = true
        downX = e.x
        downY = e.y
        offTargetX = 0f
        offTargetY = 0f
        pressTarget = 1f
        surface()?.let {
            it.touchX = e.x
            it.touchY = e.y
        }
        start()
    }

    fun move(e: MotionEvent) {
        if (!active) return
        offTargetX = e.x - downX
        offTargetY = e.y - downY
        surface()?.let {
            it.touchX = e.x - view.translationX + baseTX
            it.touchY = e.y - view.translationY + baseTY
        }
        start()
    }

    fun up() {
        if (!active) return
        pressTarget = 0f
        offTargetX = 0f
        offTargetY = 0f
        start()
    }

    private fun start() {
        if (running) return
        running = true
        lastFrame = 0L
        view.postOnAnimation(this)
    }

    override fun run() {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1e9f).coerceIn(0.001f, 0.032f)
        lastFrame = now
        // Damped springs: snappy (stiffness 420) and bouncy (damping 0.36),
        // so it overshoots and wobbles a couple of times before settling.
        val k = 420f
        val c = 2f * 0.36f * kotlin.math.sqrt(k)
        fun step(x: Float, v: Float, target: Float): Pair<Float, Float> {
            val a = -k * (x - target) - c * v
            val nv = v + a * dt
            return (x + nv * dt) to nv
        }
        step(pressT, pressV, pressTarget).let { pressT = it.first; pressV = it.second }
        step(offX, offVX, offTargetX).let { offX = it.first; offVX = it.second }
        step(offY, offVY, offTargetY).let { offY = it.first; offVY = it.second }
        apply()
        val settled = abs(pressT - pressTarget) < 0.002f && abs(pressV) < 0.01f &&
            abs(offX - offTargetX) < 0.3f && abs(offY - offTargetY) < 0.3f && abs(offVX) < 1f && abs(offVY) < 1f
        if (settled) {
            pressT = pressTarget
            offX = offTargetX
            offY = offTargetY
            apply()
            running = false
            if (pressTarget == 0f) {
                active = false
                view.scaleX = baseSX
                view.scaleY = baseSY
                view.translationX = baseTX
                view.translationY = baseTY
            }
            return
        }
        view.postOnAnimation(this)
    }

    private fun apply() {
        val w = view.width.toFloat().coerceAtLeast(1f)
        val h = view.height.toFloat().coerceAtLeast(1f)
        val minD = minOf(w, h)
        val maxD = maxOf(w, h)
        val p = pressT.coerceIn(-0.35f, 1.4f)
        // Swells by about 4dp (a touch more for small things like icons).
        val grow = 1f + view.dpf(if (minD < view.dpf(90f)) 9f else 6f) / h * p
        // Leans after the finger, with growing resistance.
        view.translationX = baseTX + minD * tanh(0.07f * offX / minD)
        view.translationY = baseTY + minD * tanh(0.07f * offY / minD)
        // Stretches along the drag.
        val ang = atan2(offY, offX)
        val stretch = view.dpf(7f) / h
        view.scaleX = baseSX * (grow + stretch * abs(cos(ang) * offX / maxD) * (w / h).coerceAtMost(1f))
        view.scaleY = baseSY * (grow + stretch * abs(sin(ang) * offY / maxD) * (h / w).coerceAtMost(1f))
        surface()?.press = p.coerceIn(0f, 1f)
        view.invalidate()
    }

    companion object {
        /** Liquid touch needs the lens shader (Android 13+) and the Liquid Glass theme. */
        fun usable(v: View): Boolean = Build.VERSION.SDK_INT >= 33 && Glass.enabled(v.context) && Glass.backdrop != null &&
            ((v.background is GlassDrawable) || (v as? AppIconView)?.glassTile() != null)
    }
}
