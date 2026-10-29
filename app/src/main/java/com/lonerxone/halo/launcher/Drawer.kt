package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * All your apps. Swipe up and the list rises out of the home screen (which
 * sinks back behind it), following your finger the whole way; let go and it
 * settles with a spring. A search pill, your most used apps, then A–Z with a
 * letter strip on the right to jump. Swipe down anywhere at the top of the
 * list, or fling down, to close.
 */
@SuppressLint("ViewConstructor")
class Drawer(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun launch(app: App, from: View)
        fun appMenu(app: App, anchor: View)
        fun onDrawerProgress(progress: Float)
    }

    private val content: LinearLayout
    private val search: EditText
    private val grid: GridView
    private val usedRow: LinearLayout
    private val usedTitle: TextView
    private val pill: LinearLayout
    private val letters: LetterStrip
    /** Beyond apps: a calculator answer, and searching the web or the Play Store. */
    private val extras: LinearLayout

    private val bubble: TextView
    private val adapter = AppsAdapter()
    private var shown: List<App> = emptyList()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private var downTopZone = false
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var prog = 0f
    var isOpen = false
        private set

    /** Liquid Glass over your picture, or solid black. */
    fun restyle() {
        background = if (Glass.enabled(context)) GlassDrawable(this, dpf(2f), darkness = 0.66f, bendPx = 0f, rimLight = false) else android.graphics.drawable.ColorDrawable(Color.BLACK)
        pill.background = if (Glass.enabled(context)) GlassDrawable(pill, dpf(24f), darkness = 0.2f, bendPx = dpf(10f)) else context.round(Color.rgb(28, 28, 30), dpf(24f))
    }

    init {
        isClickable = true
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        // Grab handle
        content.addView(View(context).apply {
            background = context.round(Color.argb(110, 255, 255, 255), dpf(2f))
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
        }
        pill.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.WHITE70)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        search = EditText(context).apply {
            hint = "Search apps"
            setHintTextColor(Palette.WHITE50)
            setTextColor(Palette.INK)
            textSize = 16f
            typeface = Fonts.regular(context)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(12), 0, dp(4), 0)
        }
        pill.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        content.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        usedTitle = context.text(10.5f, Palette.GREY, caps = true).apply {
            text = "Most used"
            setPadding(dp(6), dp(18), 0, dp(6))
        }
        content.addView(usedTitle)
        // Suggestions sit straight on the background, like the Pixel and iOS: no box.
        usedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), dp(18), dp(4))
        }
        content.addView(usedRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(View(context).apply { setBackgroundColor(Color.argb(30, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(10)
            marginStart = dp(6)
            marginEnd = dp(24)
        })

        grid = GridView(context).apply {
            numColumns = 4
            verticalSpacing = dp(18)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(14), dp(18), dp(120))
            clipToPadding = false
            adapter = this@Drawer.adapter
        }
        extras = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(extras, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // A–Z strip down the right edge: slide along it to jump.
        letters = LetterStrip(context) { letter -> jumpTo(letter) }
        addView(letters, LayoutParams(dp(22), LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = dp(200)
            bottomMargin = dp(90)
            rightMargin = dp(2)
        })
        bubble = context.text(30f, Color.BLACK, bold = true).apply {
            gravity = Gravity.CENTER
            background = context.round(Palette.INK, dpf(30f))
            visibility = View.INVISIBLE
            elevation = dpf(8f)
        }
        addView(bubble, LayoutParams(dp(60), dp(60), Gravity.END or Gravity.TOP).apply { rightMargin = dp(46) })
        restyle()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        search.setOnEditorActionListener { v, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                shown.firstOrNull()?.let { host.launch(it, v) }
                true
            } else {
                false
            }
        }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {}
        })
        visibility = View.INVISIBLE
    }

    fun refresh() {
        filter()
        usedRow.removeAllViews()
        val hidden = Prefs.list(context, Prefs.HIDDEN).toSet()
        val used = AppRepo.apps.filter { it.key !in hidden }
            .map { it to AppRepo.launches(context, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4)
            .map { it.first }
        val list = used.ifEmpty { AppRepo.apps.filter { it.key !in hidden }.take(4) }
        for (a in list) {
            usedRow.addView(iconFor(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun filter() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val hidden = Prefs.list(context, Prefs.HIDDEN).toSet()
        val all = AppRepo.apps.filter { it.key !in hidden || q.isNotEmpty() }
        shown = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
            .sortedBy { if (it.label.lowercase().startsWith(q)) 0 else 1 }
        val v = if (q.isEmpty()) View.VISIBLE else View.GONE
        usedRow.visibility = v
        usedTitle.visibility = v
        letters.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        letters.letters = shown.map { firstLetter(it.label) }.distinct()
        adapter.notifyDataSetChanged()
        showExtras(search.text?.toString()?.trim().orEmpty())
    }

    private fun showExtras(q: String) {
        extras.removeAllViews()
        extras.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        if (q.isEmpty()) return
        fun row(icon: Int, title: String, sub: String, onTap: () -> Unit) {
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = context.round(Palette.TILE2, dpf(18f))
                setOnClickListener {
                    it.bounce()
                    onTap()
                }
            }
            r.addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.INK)
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            val t = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            t.addView(context.text(15f, Palette.INK, bold = true).apply { text = title })
            t.addView(context.text(12f, Palette.GREY).apply { text = sub })
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            extras.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        Calc.eval(q)?.let { v ->
            val shownV = Calc.format(v)
            row(R.drawable.ic_info, "= $shownV", "Tap to copy") {
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("result", shownV))
                android.widget.Toast.makeText(context, "Copied $shownV", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        row(R.drawable.ic_search, "Search the web", "\"$q\"") {
            open(android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q), "https://www.google.com/search?q=" + android.net.Uri.encode(q))
        }
        if (shown.isEmpty()) row(R.drawable.ic_apps, "Find \"$q\" on the Play Store", "Get an app") {
            open(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=" + android.net.Uri.encode(q) + "&c=apps")), "https://play.google.com/store/search?q=" + android.net.Uri.encode(q) + "&c=apps")
        }
    }

    private fun open(i: android.content.Intent, fallback: String) {
        try {
            context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(fallback)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    private fun firstLetter(s: String): Char {
        val c = s.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    private fun jumpTo(letter: Char?) {
        if (letter == null) {
            bubble.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(160).withEndAction { bubble.visibility = View.INVISIBLE }.start()
            return
        }
        val i = shown.indexOfFirst { firstLetter(it.label) == letter }
        if (i >= 0) grid.setSelection(i)
        bubble.text = letter.toString()
        bubble.translationY = (letters.top + letters.lastY - dp(30)).toFloat()
        if (bubble.visibility != View.VISIBLE) {
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
            bubble.scaleX = 0.6f
            bubble.scaleY = 0.6f
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun iconFor(a: App): AppIconView = AppIconView(context, a, 54, true, liveGlass = false).apply {
        pressable()
        badge = HaloLink.badges[a.pkg] ?: 0
        setOnClickListener { host.launch(a, it) }
        setOnLongClickListener {
            host.appMenu(a, it)
            true
        }
    }

    fun updateBadges() {
        adapter.notifyDataSetChanged()
        for (i in 0 until usedRow.childCount) (usedRow.getChildAt(i) as? AppIconView)?.let { it.badge = HaloLink.badges[it.app.pkg] ?: 0 }
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val a = shown[position]
            val old = convertView as? AppIconView
            val v = if (old != null && old.app.key == a.key && old.isCurrent()) old else iconFor(a)
            v.badge = HaloLink.badges[a.pkg] ?: 0
            return v
        }
    }

    // ----- open / close -----

    /** Follows the finger (stops any animation first). */
    fun drag(p: Float) {
        anim?.cancel()
        setProgress(p)
    }

    /**
     * 0 = closed, 1 = open. The sheet fades in over the home screen while
     * its contents rise and grow into place, so it never looks like a
     * curtain being pulled up.
     */
    fun setProgress(p0: Float) {
        val p = p0.coerceIn(0f, 1f)
        prog = p
        visibility = if (p <= 0.001f) View.INVISIBLE else View.VISIBLE
        alpha = (p * 1.6f).coerceAtMost(1f)
        val e = 1f - p
        content.translationY = e * dpf(90f)
        content.alpha = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
        content.scaleX = 0.94f + 0.06f * p
        content.scaleY = 0.94f + 0.06f * p
        letters.alpha = content.alpha
        host.onDrawerProgress(p)
    }

    fun progress(): Float = prog

    fun open() {
        isOpen = true
        visibility = View.VISIBLE
        refresh()
        animateTo(1f)
    }

    /** Closes, keeping your place in the list for next time (search is cleared). */
    fun close() {
        isOpen = false
        hideKeyboard()
        if (!search.text.isNullOrEmpty()) search.setText("")
        animateTo(0f)
    }

    private var anim: android.animation.ValueAnimator? = null

    /**
     * One animation at a time (a new open/close cancels the last). Opening
     * settles with a gentle overshoot-free spring curve; closing is quicker.
     */
    fun animateTo(target: Float, velocity: Float = 0f) {
        anim?.cancel()
        val start = prog
        val dist = abs(target - start)
        if (dist < 0.001f) {
            setProgress(target)
            isOpen = target > 0.5f
            return
        }
        val a = android.animation.ValueAnimator.ofFloat(start, target)
        val fast = (abs(velocity) / 4000f).coerceIn(0f, 0.5f)
        a.duration = ((if (target > start) 420 else 220) * (0.45f + 0.55f * dist) * (1f - fast)).toLong().coerceAtLeast(120)
        a.interpolator = if (target > start) Spring(0.3f) else android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f)
        a.addUpdateListener { setProgress(it.animatedValue as Float) }
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                setLayerType(View.LAYER_TYPE_NONE, null)
            }
        })
        a.start()
        anim = a
        isOpen = target > 0.5f
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
    }

    // ----- swipe down to close -----
    // From the top area (handle, search, most used) a downward swipe always
    // closes; in the list it closes once the list is at its top. A quick
    // fling down closes even from a short swipe.

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY
                downX = e.rawX
                dragging = false
                val gl = IntArray(2)
                grid.getLocationOnScreen(gl)
                downTopZone = e.rawY < gl[1]
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dy = e.rawY - downY
                val dx = e.rawX - downX
                val atTop = !grid.canScrollVertically(-1)
                if (dy > slop && dy > abs(dx) * 1.2f && (downTopZone || atTop)) {
                    dragging = true
                    downY = e.rawY
                    hideKeyboard()
                    anim?.cancel()
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        tracker?.addMovement(e)
        val h = height.toFloat().coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> setProgress(1f - ((e.rawY - downY) / (h * 0.42f)).coerceAtLeast(0f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                if (vy > 500f || (vy > -500f && prog < 0.75f)) {
                    isOpen = false
                    hideKeyboard()
                    if (!search.text.isNullOrEmpty()) search.setText("")
                    animateTo(0f, vy)
                } else {
                    animateTo(1f, vy)
                }
            }
        }
        return true
    }
}

/** The A–Z strip: touch and slide along it to jump through the list. */
@SuppressLint("ViewConstructor")
class LetterStrip(context: Context, private val onPick: (Char?) -> Unit) : View(context) {
    var letters: List<Char> = emptyList()
        set(v) {
            field = v
            invalidate()
        }
    var lastY = 0
        private set
    private var current: Char? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dpf(10f)
        typeface = Fonts.semibold(context)
        color = Palette.SILVER
    }

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val step = height / letters.size.toFloat()
        val size = step.coerceAtMost(dpf(16f))
        val total = size * letters.size
        val top = (height - total) / 2f
        for ((i, c) in letters.withIndex()) {
            paint.color = if (c == current) Palette.INK else Palette.SILVER
            canvas.drawText(c.toString(), width / 2f, top + size * i + size * 0.75f, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val size = (height / letters.size.toFloat()).coerceAtMost(dpf(16f))
                val top = (height - size * letters.size) / 2f
                val i = ((e.y - top) / size).toInt().coerceIn(0, letters.size - 1)
                lastY = e.y.toInt()
                val c = letters[i]
                if (c != current) {
                    current = c
                    invalidate()
                    onPick(c)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                invalidate()
                onPick(null)
            }
        }
        return true
    }
}

/** A small calculator for the search box: + - × ÷ ^ %, brackets, decimals. */
object Calc {
    fun eval(src: String): Double? {
        val s = src.replace(" ", "").replace('×', '*').replace('x', '*').replace('÷', '/').replace(',', '.')
        if (s.none { it in "+-*/^%" } || s.any { it !in "0123456789.+-*/^%()" }) return null
        return try {
            val p = P(s)
            val v = p.expr()
            if (p.i != s.length || v.isNaN() || v.isInfinite()) null else v
        } catch (_: Exception) {
            null
        }
    }

    fun format(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')

    private class P(val s: String) {
        var i = 0
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]
                if (op == '%' && (i >= s.length || s[i] in "+-*/)")) {
                    v /= 100.0
                    continue
                }
                val r = power()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }
        fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(b, power())
            }
            return b
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') {
                i++
                return -unary()
            }
            return atom()
        }
        fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }
}
