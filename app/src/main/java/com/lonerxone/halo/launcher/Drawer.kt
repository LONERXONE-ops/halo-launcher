package com.lonerxone.halo.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
import kotlin.math.abs

/**
 * All your apps, slid up from the bottom: a search pill, a "most used" row
 * and an A–Z grid of monochrome icons. Drag it down (from the top of the
 * list) or press back to close.
 */
@SuppressLint("ViewConstructor")
class Drawer(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun launch(app: App, from: View)
        fun appMenu(app: App, anchor: View)
        fun onDrawerProgress(progress: Float)
    }

    private val search: EditText
    private val grid: GridView
    private val usedRow: LinearLayout
    private val adapter = AppsAdapter()
    private var shown: List<App> = emptyList()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    var isOpen = false
        private set

    /** Liquid Glass over your picture, or solid black. */
    fun restyle() {
        background = if (Glass.enabled(context)) GlassDrawable(this, dpf(34f), darkness = 0.62f, bendPx = dpf(22f)) else android.graphics.drawable.ColorDrawable(Color.BLACK)
        usedRow.background = if (Glass.enabled(context)) GlassDrawable(usedRow, dpf(26f), darkness = 0.2f) else context.round(Color.rgb(18, 18, 20), dpf(26f))
        pill.background = if (Glass.enabled(context)) GlassDrawable(pill, dpf(24f), darkness = 0.2f, bendPx = dpf(10f)) else context.round(Color.rgb(28, 28, 30), dpf(24f))
    }

    private val pill: LinearLayout

    init {
        isClickable = true
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), 0)
        }
        // Grab handle
        col.addView(View(context).apply {
            background = context.round(Color.argb(90, 255, 255, 255), dpf(2f))
        }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.round(Color.rgb(28, 28, 30), dpf(24f))
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
        col.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        col.addView(context.text(10.5f, Palette.GREY, caps = true).apply {
            text = "MOST USED"
            setPadding(dp(6), dp(18), 0, dp(8))
        })
        usedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = context.round(Color.rgb(18, 18, 20), dpf(26f))
            setPadding(dp(6), dp(12), dp(6), dp(8))
        }
        restyle()
        col.addView(usedRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(context.text(10.5f, Palette.GREY, caps = true).apply {
            text = "ALL APPS"
            setPadding(dp(6), dp(18), 0, dp(4))
        })

        grid = GridView(context).apply {
            numColumns = 4
            verticalSpacing = dp(16)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(8), 0, dp(120))
            clipToPadding = false
            adapter = this@Drawer.adapter
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

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
        usedRow.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun iconFor(a: App): AppIconView = AppIconView(context, a, 52, true).apply {
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
            val v = if (old != null && old.app.key == a.key) old else iconFor(a)
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

    fun setProgress(p: Float) {
        val h = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        translationY = h * (1f - p.coerceIn(0f, 1f))
        visibility = if (p <= 0.001f) View.INVISIBLE else View.VISIBLE
        // The glass re-samples the picture as the sheet slides.
        if (background is GlassDrawable) invalidateGlass()
        host.onDrawerProgress(p)
    }

    fun progress(): Float {
        val h = height.takeIf { it > 0 } ?: return if (isOpen) 1f else 0f
        return 1f - translationY / h
    }

    fun open() {
        val wasOpen = isOpen
        isOpen = true
        visibility = View.VISIBLE
        refresh()
        if (!wasOpen && Prefs.animations(context)) {
            // Icons ripple in row by row.
            grid.layoutAnimation = android.view.animation.LayoutAnimationController(
                android.view.animation.AnimationSet(true).apply {
                    addAnimation(android.view.animation.AlphaAnimation(0f, 1f))
                    addAnimation(android.view.animation.TranslateAnimation(0f, 0f, dpf(24f), 0f))
                    duration = 320
                    interpolator = android.view.animation.DecelerateInterpolator(2f)
                },
                0.06f,
            )
            grid.scheduleLayoutAnimation()
            for (i in 0 until usedRow.childCount) usedRow.getChildAt(i).enter(i * 40L, dpf(14f))
        }
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

    /** One animation at a time (a new open/close cancels the last), on a smooth iOS-like curve. */
    fun animateTo(target: Float) {
        anim?.cancel()
        val start = progress()
        val a = android.animation.ValueAnimator.ofFloat(start, target)
        a.duration = (220 + 220 * abs(target - start)).toLong()
        a.interpolator = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
        a.addUpdateListener { setProgress(it.animatedValue as Float) }
        a.start()
        anim = a
        isOpen = target > 0.5f
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
    }

    // Drag down from the top of the list closes the drawer.
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                val atTop = !grid.canScrollVertically(-1)
                if (dy > slop * 2 && atTop) {
                    dragging = true
                    hideKeyboard()
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        val h = height.toFloat().coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> drag(1f - ((e.rawY - downY) / h).coerceAtLeast(0f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (progress() < 0.75f) close() else animateTo(1f)
            }
        }
        return true
    }

    init {
        visibility = View.INVISIBLE
    }
}
