package com.lonerxone.halo.launcher

import android.app.ActivityOptions
import android.content.Context
import android.os.Bundle
import android.view.View
import kotlin.math.abs

/**
 * How things move. What a home-screen app is allowed to animate on Android:
 *
 * - Opening an app: the launcher picks how the app's window arrives (grow
 *   out of the icon, reveal from it, or fade) through ActivityOptions, and
 *   animates its own home screen alongside (here: the home zooms past you,
 *   like iOS). The system-level "fly out of the icon" morph is reserved for
 *   the phone's own launcher, and Samsung doesn't open its closing
 *   animation to other launchers either.
 * - Coming back: the home screen zooms back in and the app you were in
 *   lands back in its icon with a little bounce.
 * - Swiping pages: page effects, like Nova and the old Star launchers
 *   (depth, zoom, cube, cards, carousel).
 * - Unlocking: the home screen zooms in, like iOS.
 */
object Motion {
    const val OPEN_ZOOM = 0
    const val OPEN_REVEAL = 1
    const val OPEN_FADE = 2
    val OPEN_NAMES = listOf("Zoom", "Reveal", "Fade")

    const val PAGE_SLIDE = 0
    const val PAGE_DEPTH = 1
    const val PAGE_ZOOM = 2
    const val PAGE_CUBE = 3
    const val PAGE_CARDS = 4
    const val PAGE_CAROUSEL = 5
    val PAGE_NAMES = listOf("Slide", "Depth", "Zoom", "Cube", "Cards", "Carousel")

    fun open(c: Context) = Prefs.get(c).getInt(Prefs.OPEN_ANIM, OPEN_ZOOM)
    fun page(c: Context) = Prefs.get(c).getInt(Prefs.PAGE_EFFECT, PAGE_DEPTH)

    /** The app window's entrance, from the icon that was tapped. */
    fun launchOptions(c: Context, from: View?): Bundle? {
        if (!Prefs.animations(c)) return null
        return when (open(c)) {
            OPEN_FADE -> ActivityOptions.makeCustomAnimation(c, android.R.anim.fade_in, android.R.anim.fade_out).toBundle()
            OPEN_REVEAL -> from?.let { ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle() }
            else -> from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        }
    }

    /**
     * Page effects. [f] is the pager position (0 = page 1, 1 = page 2…);
     * each page gets its transform from how far it is from the middle.
     */
    fun applyPages(pages: List<View>, f: Float, effect: Int) {
        for ((i, v) in pages.withIndex()) {
            val o = (i - f).coerceIn(-1f, 1f) // -1 left of view, 0 centred, 1 right of view
            val w = v.width.toFloat().coerceAtLeast(1f)
            val h = v.height.toFloat()
            // Reset to plain sliding first.
            v.translationX = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.rotationY = 0f
            v.alpha = 1f
            v.translationZ = 0f
            v.pivotX = w / 2f
            v.pivotY = h / 2f
            if (abs(o) >= 1f || effect == PAGE_SLIDE) continue
            when (effect) {
                PAGE_DEPTH -> if (o > 0f) {
                    // The next page rises from behind instead of sliding in
                    // (drawn underneath, so the page you leave slides over it).
                    // It only appears once the page you're leaving has mostly
                    // gone, so the two never show through each other.
                    v.translationZ = -1f
                    v.translationX = -w * o
                    val s = 0.84f + 0.16f * (1f - o)
                    v.scaleX = s
                    v.scaleY = s
                    v.alpha = ((0.55f - o) / 0.55f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                } else {
                    // The page you leave fades out quickly as it slides away.
                    v.alpha = (1f + o * 2f).coerceIn(0f, 1f)
                }
                PAGE_ZOOM -> {
                    val s = 1f - 0.16f * abs(o)
                    v.scaleX = s
                    v.scaleY = s
                    v.alpha = 1f - 0.55f * abs(o)
                }
                PAGE_CUBE -> {
                    v.cameraDistance = w * 12f
                    v.pivotX = if (o < 0f) w else 0f
                    v.rotationY = 70f * o
                }
                PAGE_CARDS -> if (o < 0f) {
                    // The page you leave stays put and sinks; the next slides over it.
                    v.translationX = -w * o
                    val s = 1f + 0.12f * o
                    v.scaleX = s
                    v.scaleY = s
                    // Gone before the next page covers it, so they don't show through each other.
                    v.alpha = (1f + o * 1.6f).coerceIn(0f, 1f)
                }
                PAGE_CAROUSEL -> {
                    v.cameraDistance = w * 10f
                    v.rotationY = -30f * o
                    val s = 1f - 0.12f * abs(o)
                    v.scaleX = s
                    v.scaleY = s
                }
            }
        }
    }
}
