package com.lonerxone.halo.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * One UI 9-style search: a small round glass button floating at the bottom.
 * Tap it and it stretches into a search bar that rides on top of the
 * keyboard; ✕ (or putting the keyboard away with nothing typed) shrinks it
 * back. Add it to a FrameLayout with [attach].
 */
@SuppressLint("ViewConstructor")
class FloatingSearch(
    context: Context,
    hint: String,
    private val onSearch: (String) -> Unit,
    private val onClear: () -> Unit = {},
    private val onType: ((String) -> Unit)? = null,
) : LinearLayout(context) {
    val field: EditText
    private val icon: ImageView
    private val clear: ImageView
    var isOpen = false
        private set
    private var anim: ValueAnimator? = null
    private var nav = 0
    private var ime = 0
    private var imeMoving = false
    private val small = dp(56)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        elevation = dpf(12f)
        isClickable = true
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
            }
        }
        restyle()
        icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(Palette.INK)
        }
        addView(icon, LayoutParams(dp(22), dp(22)))
        field = EditText(context).apply {
            this.hint = hint
            setHintTextColor(Palette.WHITE50)
            setTextColor(Palette.INK)
            textSize = 15.5f
            typeface = Fonts.regular(context)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), 0, dp(4), 0)
            visibility = View.GONE
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    val q = text.toString().trim()
                    hideKeyboard()
                    if (q.isNotEmpty()) onSearch(q)
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    onType?.invoke(s?.toString().orEmpty())
                }
            })
        }
        addView(field, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        clear = ImageView(context).apply {
            setImageResource(R.drawable.ic_plus)
            rotation = 45f
            imageTintList = ColorStateList.valueOf(Palette.WHITE70)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
            setOnClickListener { close(clearText = true) }
        }
        addView(clear, LayoutParams(dp(32), dp(32)))
        setOnClickListener { if (!isOpen) open() }

        setOnApplyWindowInsetsListener { _, insets ->
            nav = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            if (!imeMoving) {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                place()
            }
            insets
        }
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(a: WindowInsetsAnimation) {
                if (a.typeMask and WindowInsets.Type.ime() != 0) imeMoving = true
            }

            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (imeMoving) {
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                    place()
                }
                return insets
            }

            override fun onEnd(a: WindowInsetsAnimation) {
                if (a.typeMask and WindowInsets.Type.ime() == 0) return
                imeMoving = false
                ime = rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                place()
                if (ime == 0 && isOpen && field.text.isNullOrEmpty()) close(clearText = false)
            }
        })
    }

    fun restyle() {
        background = if (Glass.enabled(context) && Glass.backdrop != null) {
            GlassDrawable(this, dpf(28f), darkness = 0.3f, bendPx = dpf(10f))
        } else {
            context.round(Palette.TILE2, dpf(28f)).apply { setStroke(dp(1), Color.argb(40, 255, 255, 255)) }
        }
    }

    fun attach(parent: FrameLayout) {
        parent.addView(this, FrameLayout.LayoutParams(small, small, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
    }

    private fun place() {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        lp.bottomMargin = maxOf(nav, ime) + dp(16)
        layoutParams = lp
    }

    fun open() {
        isOpen = true
        field.visibility = View.VISIBLE
        clear.visibility = View.VISIBLE
        field.alpha = 0f
        clear.alpha = 0f
        setPadding(dp(18), 0, dp(10), 0)
        val pw = (parent as? View)?.width ?: 0
        stretch((pw - dp(40)).coerceAtLeast(dp(200)), Spring(0.3f), 460L)
        field.animate().alpha(1f).setStartDelay(120).setDuration(220).start()
        clear.animate().alpha(1f).setStartDelay(160).setDuration(220).start()
        field.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    fun close(clearText: Boolean) {
        if (!isOpen) return
        isOpen = false
        val had = !field.text.isNullOrEmpty()
        if (clearText && had) field.setText("")
        hideKeyboard()
        field.animate().alpha(0f).setStartDelay(0).setDuration(90).withEndAction { field.visibility = View.GONE }.start()
        clear.animate().alpha(0f).setStartDelay(0).setDuration(90).withEndAction { clear.visibility = View.GONE }.start()
        setPadding(0, 0, 0, 0)
        stretch(small, Spring(0.45f), 420L)
        if (clearText) onClear()
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        field.clearFocus()
    }

    private fun stretch(to: Int, interp: android.view.animation.Interpolator, ms: Long) {
        anim?.cancel()
        val from = layoutParams?.width?.takeIf { it > 0 } ?: width
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                val f = it.animatedValue as Float
                layoutParams = layoutParams.apply { width = (from + (to - from) * f).toInt().coerceAtLeast(dp(40)) }
                invalidateOutline()
            }
            start()
        }
    }
}
