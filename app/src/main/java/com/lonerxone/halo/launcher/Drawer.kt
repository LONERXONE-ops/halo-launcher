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

    init {
        setBackgroundColor(Color.BLACK)
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

        val pill = LinearLayout(context).apply {
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
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(12), 0, dp(4), 0)
        }
        pill.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        col.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        col.addView(context.text(12.5f, Palette.WHITE50, bold = true).apply {
            text = "MOST USED"
            letterSpacing = 0.12f
            setPadding(dp(6), dp(18), 0, dp(8))
        })
        usedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = context.round(Color.rgb(18, 18, 20), dpf(26f))
            setPadding(dp(6), dp(12), dp(6), dp(8))
        }
        col.addView(usedRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(context.text(12.5f, Palette.WHITE50, bold = true).apply {
            text = "ALL APPS"
            letterSpacing = 0.12f
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

    fun setProgress(p: Float) {
        val h = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        translationY = h * (1f - p.coerceIn(0f, 1f))
        visibility = if (p <= 0.001f) View.INVISIBLE else View.VISIBLE
        host.onDrawerProgress(p)
    }

    fun progress(): Float {
        val h = height.takeIf { it > 0 } ?: return if (isOpen) 1f else 0f
        return 1f - translationY / h
    }

    fun open() {
        isOpen = true
        visibility = View.VISIBLE
        refresh()
        animateTo(1f)
    }

    fun close() {
        isOpen = false
        hideKeyboard()
        search.setText("")
        grid.setSelection(0)
        animateTo(0f)
    }

    fun animateTo(target: Float) {
        val start = progress()
        val anim = android.animation.ValueAnimator.ofFloat(start, target)
        anim.duration = (180 + 160 * abs(target - start)).toLong()
        anim.interpolator = android.view.animation.DecelerateInterpolator(2f)
        anim.addUpdateListener { setProgress(it.animatedValue as Float) }
        anim.start()
        isOpen = target > 0.5f
    }

    private fun hideKeyboard() {
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
            MotionEvent.ACTION_MOVE -> setProgress(1f - ((e.rawY - downY) / h).coerceAtLeast(0f))
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
