package com.lonerxone.halo.launcher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Wallpapers: browse thousands of phone wallpapers (anime, nature, minimal,
 * space…) from wallhaven.cc's free API, search for anything, or use one of
 * your own photos. Preview it with your clock on top, then set it on the
 * home screen, the lock screen or both. Saved ones live in "Saved", and
 * "Change daily" shuffles through them.
 *
 * Setting the home wallpaper also makes it Halo Launcher's background, so
 * Liquid Glass bends it.
 */
class WallpaperActivity : Activity() {
    class Wall(val id: String, val thumb: String, val full: String, val w: Int, val h: Int, val local: File? = null, val uri: Uri? = null)

    private class Topic(val name: String, val q: String, val cats: String = "111", val sort: String = "toplist")

    private val topics = listOf(
        Topic("Top", "", "111", "toplist"),
        Topic("Anime", "", "010", "toplist"),
        Topic("Fresh", "", "111", "date_added"),
        Topic("Nature", "nature"),
        Topic("Minimal", "minimalism"),
        Topic("Dark", "dark"),
        Topic("Space", "space"),
        Topic("Abstract", "abstract"),
        Topic("City", "city"),
        Topic("Cars", "cars"),
        Topic("Gaming", "video games"),
        Topic("Art", "digital art"),
    )

    private val io = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val items = ArrayList<Wall>()
    private val adapter = WallAdapter()
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var savedBar: LinearLayout
    private lateinit var preview: FrameLayout
    private var topic = 0
    private var query = ""
    private var page = 1
    private var lastPage = 1
    private var loading = false
    private var savedTab = false
    private var gen = 0
    /** Random mixes stay the same while you scroll, and change when you refresh. */
    private var seed = newSeed()

    private fun newSeed(): String {
        val a = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { a.random() }.joinToString("")
    }

    /** New wallpapers: a new mix for topics, the latest for Top/Fresh/searches, saved re-read. */
    private fun refreshAll() {
        seed = newSeed()
        if (savedTab) showSaved() else load(reset = true)
        grid.alpha = 0.4f
        grid.animate().alpha(1f).setDuration(400).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        build()
        if (intent.getStringExtra("tab") == "saved") showSaved() else load(reset = true)
    }

    private lateinit var finder: FloatingSearch
    private lateinit var tabs: FrameLayout
    private lateinit var tabPill: View
    private lateinit var tabLabels: List<TextView>
    private lateinit var chipScroll: HorizontalScrollView

    private fun build() {
        Palette.adapt(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // Your current background, dimmed, behind everything.
        root.addView(BackgroundView(this).apply { alpha = 0.55f }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(215, 0, 0, 0), Color.argb(245, 0, 0, 0)))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Header: back · title (and where they come from) · your photos.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        fun roundBtn(icon: Int, desc: String, click: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Palette.INK)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = round(Color.argb(46, 255, 255, 255), dpf(22f)).apply { setStroke(dp(1), Color.argb(30, 255, 255, 255)) }
            contentDescription = desc
            pressable()
            setOnClickListener { click() }
        }
        head.addView(roundBtn(R.drawable.ic_back, "Back") { onBackPressedCompat() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        titles.addView(text(26f, Palette.INK, bold = true).apply { text = "Wallpapers" })
        titles.addView(text(11.5f, Palette.GREY).apply { text = "From wallhaven.cc, by their artists" })
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val refreshBtn = roundBtn(R.drawable.ic_refresh, "Refresh") {}
        refreshBtn.setOnClickListener {
            it.animate().rotationBy(360f).setDuration(600).setInterpolator(Spring(0.3f)).start()
            refreshAll()
        }
        head.addView(refreshBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        head.addView(roundBtn(R.drawable.ic_image, "Use one of your photos") { pickPhoto() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(head)

        // Discover | Saved, with a sliding white pill.
        tabs = FrameLayout(this).apply {
            background = round(Color.argb(40, 255, 255, 255), dpf(21f))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        tabPill = View(this).apply { background = round(Palette.INK, dpf(17f)) }
        tabs.addView(tabPill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabLabels = listOf("Discover", "♥  Saved").mapIndexed { i, l ->
            text(13.5f, Palette.INK, bold = true).apply {
                text = l
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (i == 0 && savedTab) {
                        savedTab = false
                        load(reset = true)
                    } else if (i == 1 && !savedTab) {
                        showSaved()
                    }
                }
            }.also { labels.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
        }
        tabs.addView(labels, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            topMargin = dp(12)
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
        tabs.post { moveTab(animate = false) }

        // Topics (Discover only).
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        chipScroll = HorizontalScrollView(this).apply {
            // Fades out at the edges instead of cutting things off.
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength((24 * resources.displayMetrics.density).toInt())
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipRow)
        }
        col.addView(chipScroll)

        // Saved extras: change daily.
        savedBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = round(Color.argb(36, 255, 255, 255), dpf(18f))
            visibility = View.GONE
        }
        val sb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sb.addView(text(14f, Palette.INK, bold = true).apply { text = "Change daily" })
        sb.addView(text(12f, Palette.GREY, lines = 2).apply { text = "A different saved wallpaper each day. Hold one to remove it." })
        savedBar.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        @Suppress("UseSwitchCompatOrMaterialCode")
        savedBar.addView(Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(170, 170, 176)))
            trackTintList = ColorStateList(states, intArrayOf(Palette.SWITCH, Color.rgb(58, 58, 62)))
            isChecked = Prefs.get(this@WallpaperActivity).getBoolean(Prefs.WALL_DAILY, false)
            setOnCheckedChangeListener { _, on -> Prefs.get(this@WallpaperActivity).edit().putBoolean(Prefs.WALL_DAILY, on).apply() }
        })
        col.addView(savedBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
            leftMargin = dp(16)
            rightMargin = dp(16)
        })

        status = text(13.5f, Palette.GREY, lines = 3).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(30))
        }
        grid = GridView(this).apply {
            // Fades out at the edges instead of cutting things off.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((32 * resources.displayMetrics.density).toInt())
            numColumns = 3
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(120))
            adapter = this@WallpaperActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
            setOnItemLongClickListener { _, v, pos, _ ->
                val w = items[pos]
                if (w.local != null) {
                    v.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(200).withEndAction {
                        w.local.delete()
                        Toast.makeText(this@WallpaperActivity, "Removed from Saved", Toast.LENGTH_SHORT).show()
                        showSaved()
                    }.start()
                }
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL && finder.isOpen && finder.field.text.isNullOrEmpty()) finder.close(clearText = false)
                }
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (!savedTab && !loading && total > 0 && first + visible >= total - 6 && page < lastPage) {
                        page++
                        load(reset = false)
                    }
                }
            })
        }
        val body = PullFrame(this) { refreshAll() }
        body.list = grid
        body.addView(grid)
        body.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Search: the floating button at the bottom.
        finder = FloatingSearch(this, "Search: anime, rain, neon…", onSearch = { q ->
            query = q
            savedTab = false
            load(reset = true)
        }, onClear = {
            if (query.isNotEmpty()) {
                query = ""
                if (!savedTab) load(reset = true)
            }
        })
        finder.attach(root)

        preview = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            elevation = dpf(20f)
        }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(0, b.top, 0, 0)
            grid.setPadding(dp(12), dp(4), dp(12), b.bottom + dp(96))
            insets
        }
        setContentView(root)
    }

    private fun moveTab(animate: Boolean) {
        if (!::tabs.isInitialized) return
        val w = (tabs.width - tabs.paddingLeft - tabs.paddingRight) / 2
        if (w <= 0) return
        if (tabPill.layoutParams.width != w) tabPill.layoutParams = tabPill.layoutParams.apply { width = w }
        val x = if (savedTab) w.toFloat() else 0f
        if (animate) tabPill.animate().translationX(x).setDuration(420).setInterpolator(Spring(0.4f)).start() else tabPill.translationX = x
        for ((i, l) in tabLabels.withIndex()) {
            val on = (i == 1) == savedTab
            l.setTextColor(if (on) Color.BLACK else Palette.INK)
        }
    }

    private fun drawChips() {
        moveTab(animate = true)
        chipScroll.visibility = if (savedTab) View.GONE else View.VISIBLE
        chipRow.removeAllViews()
        fun add(label: String, on: Boolean, click: () -> Unit) {
            chipRow.addView(text(13f, if (on) Color.BLACK else Palette.INK, bold = true).apply {
                text = label
                setPadding(dp(15), dp(8), dp(15), dp(8))
                background = if (on) round(Palette.INK, dpf(17f)) else round(Color.argb(22, 255, 255, 255), dpf(17f)).apply { setStroke(dp(1), Color.argb(55, 255, 255, 255)) }
                pressable()
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        // A search you made shows as the first tag; tap it to go back to topics.
        if (query.isNotEmpty()) add("“$query”  ✕", true) {
            query = ""
            finder.close(clearText = true)
            load(reset = true)
        }
        for ((i, t) in topics.withIndex()) add(t.name, query.isEmpty() && i == topic) {
            topic = i
            query = ""
            seed = newSeed()
            if (finder.isOpen) finder.close(clearText = true)
            savedTab = false
            load(reset = true)
            chipScroll.smoothScrollTo(0, 0)
        }
    }

    // ----- Discover (wallhaven.cc) -----

    private fun load(reset: Boolean) {
        if (reset) {
            page = 1
            lastPage = 1
            items.clear()
            adapter.notifyDataSetChanged()
            grid.setSelection(0)
        }
        savedBar.visibility = View.GONE
        drawChips()
        loading = true
        val my = ++gen
        if (items.isEmpty()) showStatus("Loading…")
        val t = topics[topic]
        val q = if (query.isNotEmpty()) query else t.q
        val cats = if (query.isNotEmpty()) "111" else t.cats
        // Topics come as a fresh random mix each time (a new seed on every refresh);
        // Top stays the year's best and Fresh the newest.
        val sort = when {
            query.isNotEmpty() -> "relevance"
            t.name == "Top" || t.name == "Fresh" -> t.sort
            else -> "random"
        }
        val p = page
        io.execute {
            val result = try {
                val url = "https://wallhaven.cc/api/v1/search?purity=100&ratios=portrait&atleast=1080x1920" +
                    "&categories=$cats&sorting=$sort" + (if (sort == "toplist") "&topRange=1y" else "") + (if (sort == "random") "&seed=$seed" else "") +
                    "&page=$p" + (if (q.isNotEmpty()) "&q=" + URLEncoder.encode(q, "UTF-8") else "")
                val j = JSONObject(get(url))
                val data = j.getJSONArray("data")
                val list = (0 until data.length()).map { i ->
                    val o = data.getJSONObject(i)
                    val th = o.getJSONObject("thumbs")
                    Wall(o.getString("id"), th.optString("original", th.optString("large")), o.getString("path"), o.optInt("dimension_x"), o.optInt("dimension_y"))
                }
                val last = j.optJSONObject("meta")?.optInt("last_page", 1) ?: 1
                list to last
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (my != gen) return@post
                loading = false
                if (result == null) {
                    if (items.isEmpty()) showStatus("Couldn't load wallpapers. Check your connection and try again.")
                    return@post
                }
                lastPage = result.second
                items += result.first
                adapter.notifyDataSetChanged()
                showStatus(if (items.isEmpty()) "Nothing found for that. Try another word." else null)
            }
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "HaloLauncher-Android")
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun showStatus(s: String?) {
        status.text = s.orEmpty()
        status.visibility = if (s == null) View.GONE else View.VISIBLE
    }

    // ----- Saved -----

    private fun savedDir() = File(filesDir, "wallpapers").apply { mkdirs() }

    private fun showSaved() {
        savedTab = true
        gen++
        drawChips()
        savedBar.visibility = View.VISIBLE
        items.clear()
        // Older versions kept a second "framed" copy of a wallpaper you moved or
        // zoomed here too, so it showed up twice: those copies are cleared out.
        savedDir().listFiles()?.filter { it.name.contains("_framed") }?.forEach { it.delete() }
        val files = savedDir().listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }.orEmpty()
        for (f in files) items += Wall(f.nameWithoutExtension, "", "", 0, 0, local = f)
        adapter.notifyDataSetChanged()
        showStatus(if (items.isEmpty()) "Wallpapers you set or save (♥) show up here. Long-press one to remove it." else null)
    }

    // ----- Grid -----

    private inner class WallAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val iv = (convertView as? ThumbView) ?: ThumbView(this@WallpaperActivity)
            val w = items[position]
            iv.key = w.id
            iv.setImageBitmap(null)
            val k = w.local?.path ?: w.thumb
            thumbs.get(k)?.let { iv.setImageBitmap(it); return iv }
            io.execute {
                val b = try {
                    if (w.local != null) decodeSmall(w.local) else BitmapFactory.decodeStream((URL(w.thumb).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "HaloLauncher-Android") }.inputStream)
                } catch (_: Exception) {
                    null
                } ?: return@execute
                thumbs.put(k, b)
                ui.post {
                    if (iv.key == w.id) {
                        iv.alpha = 0f
                        iv.setImageBitmap(b)
                        iv.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
            return iv
        }
    }

    private fun decodeSmall(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 300) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** A 9:16 rounded tile. */
    private class ThumbView(c: Context) : ImageView(c) {
        var key: String? = null

        init {
            scaleType = ScaleType.CENTER_CROP
            background = c.round(Palette.TILE, c.dpf(14f))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, c.dpf(14f))
                }
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * 16f / 9f).toInt())
        }
    }

    // ----- Preview & set -----

    private var shown: Wall? = null

    private fun open(w: Wall) {
        shown = w
        preview.removeAllViews()
        // Drag to move, pinch to zoom: what you see is exactly what gets set.
        val img = PanZoomView(this)
        preview.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val hint = text(12.5f, Palette.INK, bold = true).apply {
            text = "Drag to move · pinch to zoom · double-tap to reset"
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = round(Color.argb(150, 0, 0, 0), dpf(15f))
        }
        preview.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(44) })
        hint.animate().alpha(0f).setStartDelay(3500).setDuration(600).start()
        (thumbs.get(w.local?.path ?: w.thumb))?.let { img.setImageBitmap(it) }
        // Your clock on top, to see how it looks: its glass bends this
        // picture and it takes this picture's colours (not the current wallpaper's).
        val clock = ClockFace(this)
        preview.addView(clock, FrameLayout.LayoutParams(dp(170), dp(170)).apply {
            topMargin = dp(70)
            leftMargin = dp(22)
        })
        img.onFramed = { tryOn(img, clock) }
        retry = { tryOn(img, clock) }
        val busy = text(13f, Palette.INK, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = round(Color.argb(150, 0, 0, 0), dpf(16f))
            visibility = View.GONE
        }
        preview.addView(busy, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Full size, when it arrives.
        if (w.local == null && w.uri == null) {
            io.execute {
                val b = try {
                    decodeLarge(URL(w.full).openConnection().apply { setRequestProperty("User-Agent", "HaloLauncher-Android") }.getInputStream().readBytes())
                } catch (_: Exception) {
                    null
                }
                ui.post { if (shown === w && b != null) img.setFull(b) }
            }
        } else {
            io.execute {
                val b = try {
                    if (w.local != null) decodeLarge(w.local.readBytes()) else ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, w.uri!!)) { d, i, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(i.size.width, i.size.height)
                        if (longest > 2400) {
                            val f = 2400f / longest
                            d.setTargetSize((i.size.width * f).toInt(), (i.size.height * f).toInt())
                        }
                    }
                } catch (_: Exception) {
                    null
                }
                ui.post { if (shown === w && b != null) img.setFull(b) }
            }
        }

        // Buttons on a pane of glass at the bottom.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(Color.argb(200, 18, 18, 20), dpf(28f)).apply { setStroke(dp(1), Color.argb(50, 255, 255, 255)) }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, primary: Boolean, click: () -> Unit) = text(14f, if (primary) Color.BLACK else Palette.INK, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = round(if (primary) Palette.INK else Palette.GRAPHITE, dpf(22f))
            pressable()
            setOnClickListener { click() }
        }
        fun set(flags: Int, label: String) {
            busy.text = "Setting…"
            busy.visibility = View.VISIBLE
            apply(w, flags, img) { ok ->
                busy.visibility = View.GONE
                Toast.makeText(this, if (ok) "$label set" else "Couldn't set the wallpaper", Toast.LENGTH_SHORT).show()
                if (ok) closePreview()
            }
        }
        row1.addView(btn("Home", false) { set(WallpaperManager.FLAG_SYSTEM, "Home wallpaper") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(btn("Lock", false) { set(WallpaperManager.FLAG_LOCK, "Lock screen wallpaper") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        row1.addView(btn("Both", true) { set(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK, "Wallpaper") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        bar.addView(row1)
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row2.addView(btn("Close", false) { closePreview() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (w.local == null) row2.addView(btn("♥  Save", false) {
            busy.text = "Saving…"
            busy.visibility = View.VISIBLE
            io.execute {
                val f = try {
                    save(w)
                } catch (_: Exception) {
                    null
                }
                ui.post {
                    busy.visibility = View.GONE
                    Toast.makeText(this, if (f != null) "Saved" else "Couldn't save it", Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        bar.addView(row2)
        preview.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(14), 0, dp(14), dp(36))
        })
        preview.visibility = View.VISIBLE
        preview.alpha = 0f
        preview.scaleX = 0.96f
        preview.scaleY = 0.96f
        preview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Spring(0.35f)).start()
    }

    /** The clock (and colours) as they'd be with the picture as framed right now. */
    private fun tryOn(img: PanZoomView, clock: ClockFace) {
        val k = 0.35f
        val shot = img.snapshot(k) ?: return
        val l = IntArray(2)
        img.getLocationInWindow(l)
        Glass.preview(shot, l[0].toFloat(), l[1].toFloat(), k)
        Palette.adapt(this)
        clock.refresh()
    }

    private var retry: (() -> Unit)? = null

    // Leaving (home, another app): the trial colours never leak onto the
    // home screen; coming back puts them on again.
    override fun onStop() {
        Glass.endPreview()
        Palette.adapt(this)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (shown != null) retry?.invoke()
    }

    override fun onDestroy() {
        Glass.endPreview()
        Palette.adapt(this)
        super.onDestroy()
    }

    private fun closePreview() {
        retry = null
        Glass.endPreview()
        Palette.adapt(this)
        shown = null
        preview.animate().alpha(0f).setDuration(160).withEndAction { preview.visibility = View.GONE }.start()
        if (savedTab) showSaved()
    }

    private fun decodeLarge(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 2400) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Downloads (or reads) the wallpaper into Saved; returns the file. */
    private fun save(w: Wall): File {
        w.local?.let { return it }
        val f = File(savedDir(), "${w.id}.jpg")
        if (f.exists() && f.length() > 0) return f
        val bmp = when {
            w.uri != null -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, w.uri)) { d, i, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(i.size.width, i.size.height)
                if (longest > 2400) {
                    val k = 2400f / longest
                    d.setTargetSize((i.size.width * k).toInt(), (i.size.height * k).toInt())
                }
            }
            else -> decodeLarge(URL(w.full).openConnection().apply { setRequestProperty("User-Agent", "HaloLauncher-Android") }.getInputStream().readBytes())
        } ?: throw IllegalStateException("decode")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f
    }

    /** Sets the wallpaper; home also becomes the launcher's background (for the glass). */
    private fun apply(w: Wall, flags: Int, view: PanZoomView, done: (Boolean) -> Unit) {
        // The part you framed, at the screen's size (only once the full picture is in).
        val framed = if (view.moved && view.hasFull) view.render() else null
        io.execute {
            val ok = try {
                val file = if (framed != null) {
                    // The framed version is only needed to set it (Saved keeps the original once).
                    File(cacheDir, "framed.jpg").also { f -> f.outputStream().use { framed.compress(Bitmap.CompressFormat.JPEG, 93, it) } }
                } else {
                    save(w)
                }
                if (framed != null) save(w) // keep the original in Saved too
                setWallpaper(this, file, flags)
                true
            } catch (_: Throwable) {
                false
            }
            ui.post { done(ok) }
        }
    }

    // ----- Your photos -----

    private fun pickPhoto() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 3)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker found", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            open(Wall("photo_" + System.currentTimeMillis(), "", "", 0, 0, uri = uri))
        }
    }

    private fun onBackPressedCompat() {
        when {
            preview.visibility == View.VISIBLE -> closePreview()
            finder.isOpen -> finder.close(clearText = query.isEmpty())
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = onBackPressedCompat()

    companion object {
        /**
         * Puts [file] on the home and/or lock screen. Home also becomes Halo
         * Launcher's own background, so the glass bends exactly this picture.
         */
        fun setWallpaper(c: Context, file: File, flags: Int) {
            val bmp = BitmapFactory.decodeFile(file.path) ?: throw IllegalStateException("decode")
            if (flags and WallpaperManager.FLAG_SYSTEM != 0) {
                file.copyTo(Prefs.picFile(c, Prefs.HERO_PIC), overwrite = true)
                Prefs.get(c).edit().putLong(Prefs.HERO_PIC, System.currentTimeMillis()).apply()
                Glass.invalidate()
            }
            WallpaperManager.getInstance(c).setBitmap(bmp, null, true, flags)
        }

        /** "Change daily": once a day, the next saved wallpaper (call from the launcher). */
        fun dailyShuffle(c: Context, done: () -> Unit) {
            val p = Prefs.get(c)
            if (!p.getBoolean(Prefs.WALL_DAILY, false)) return
            val today = android.text.format.DateFormat.format("yyyyMMdd", java.util.Date()).toString()
            if (p.getString(Prefs.WALL_DAY, "") == today) return
            p.edit().putString(Prefs.WALL_DAY, today).apply()
            val files = File(c.filesDir, "wallpapers").listFiles()?.filter { it.name.endsWith(".jpg") && !it.name.contains("_framed") }.orEmpty()
            if (files.isEmpty()) return
            val f = files.random()
            Thread {
                try {
                    setWallpaper(c, f, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    Handler(Looper.getMainLooper()).post(done)
                } catch (_: Throwable) {
                }
            }.start()
        }
    }
}


/**
 * The wallpaper preview you can frame: drag to move it, pinch to zoom,
 * double-tap to reset. It always covers the screen (no empty edges), and
 * render() gives back exactly what you see, at screen size.
 */
class PanZoomView(c: Context) : ImageView(c) {
    private val m = android.graphics.Matrix()
    private var bmp: Bitmap? = null
    var hasFull = false
        private set
    /** Whether you moved or zoomed it (otherwise the plain centred crop is used). */
    var moved = false
        private set
    private var lastX = 0f
    private var lastY = 0f
    private var pointer = -1

    private val scaler = android.view.ScaleGestureDetector(c, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            moved = true
            clamp()
            return true
        }
    })
    private val taps = android.view.GestureDetector(c, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
            moved = false
            fit(animate = true)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(b: Bitmap?) {
        // A new picture (or the full-size one replacing the thumbnail) keeps the same framing.
        val old = bmp
        val keep = if (old != null && b != null && moved) {
            val v = FloatArray(9)
            m.getValues(v)
            floatArrayOf(v[android.graphics.Matrix.MSCALE_X] * old.width / b.width, v[android.graphics.Matrix.MTRANS_X], v[android.graphics.Matrix.MTRANS_Y])
        } else {
            null
        }
        bmp = b
        super.setImageBitmap(b)
        if (keep != null) {
            m.setScale(keep[0], keep[0])
            m.postTranslate(keep[1], keep[2])
            clamp()
        } else {
            fit(animate = false)
        }
        post { onFramed?.invoke() }
    }

    fun setFull(b: Bitmap) {
        hasFull = true
        setImageBitmap(b)
    }

    /** Called when the picture or its framing settles (new picture, or a drag or pinch ends). */
    var onFramed: (() -> Unit)? = null

    /** What's on screen at [k] of its size (small: for colours and the clock's glass). */
    fun snapshot(k: Float): Bitmap? {
        val b = bmp ?: return null
        if (width <= 0 || height <= 0) return null
        val out = Bitmap.createBitmap((width * k).toInt().coerceAtLeast(1), (height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.scale(k, k)
        cv.drawBitmap(b, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!moved) fit(animate = false) else clamp()
    }

    /** Centre-crop: the picture just covers the screen. */
    private fun fit(animate: Boolean) {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val s = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val target = android.graphics.Matrix().apply {
            setScale(s, s)
            postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        }
        if (!animate) {
            m.set(target)
            imageMatrix = m
            return
        }
        val from = FloatArray(9).also { m.getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = Spring(0.3f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val v = FloatArray(9) { i -> from[i] + (to[i] - from[i]) * f }
                m.setValues(v)
                imageMatrix = m
            }
            start()
        }
    }

    /** Never smaller than the screen, never showing an edge. */
    private fun clamp() {
        val b = bmp ?: return
        val v = FloatArray(9)
        m.getValues(v)
        val minS = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
        var s = v[android.graphics.Matrix.MSCALE_X]
        if (s < minS) {
            m.postScale(minS / s, minS / s, width / 2f, height / 2f)
            m.getValues(v)
            s = minS
        }
        if (s > minS * 5f) {
            m.postScale(minS * 5f / s, minS * 5f / s, width / 2f, height / 2f)
            m.getValues(v)
            s = minS * 5f
        }
        val bw = b.width * s
        val bh = b.height * s
        val tx = v[android.graphics.Matrix.MTRANS_X].coerceIn(width - bw, 0f)
        val ty = v[android.graphics.Matrix.MTRANS_Y].coerceIn(height - bh, 0f)
        m.postTranslate(tx - v[android.graphics.Matrix.MTRANS_X], ty - v[android.graphics.Matrix.MTRANS_Y])
        imageMatrix = m
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        taps.onTouchEvent(e)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pointer = e.getPointerId(0)
                lastX = e.x
                lastY = e.y
            }
            android.view.MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress) {
                val i = e.findPointerIndex(pointer)
                if (i >= 0) {
                    m.postTranslate(e.getX(i) - lastX, e.getY(i) - lastY)
                    if (kotlin.math.abs(e.getX(i) - lastX) + kotlin.math.abs(e.getY(i) - lastY) > 0.5f) moved = true
                    lastX = e.getX(i)
                    lastY = e.getY(i)
                    clamp()
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> postDelayed({ onFramed?.invoke() }, 340L)
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                // Carry on with the finger that's left.
                val up = e.actionIndex
                if (e.getPointerId(up) == pointer) {
                    val n = if (up == 0) 1 else 0
                    pointer = e.getPointerId(n)
                    lastX = e.getX(n)
                    lastY = e.getY(n)
                }
            }
        }
        return true
    }

    /** What's on screen, as a screen-sized picture. */
    fun render(): Bitmap? {
        val b = bmp ?: return null
        val dm = resources.displayMetrics
        val outW = maxOf(width, 1)
        val outH = maxOf(height, 1)
        // Up to the phone's real resolution for a sharp wallpaper.
        val k = maxOf(1f, minOf(dm.widthPixels.toFloat() / outW, 2f))
        val out = Bitmap.createBitmap((outW * k).toInt(), (outH * k).toInt(), Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.scale(k, k)
        cv.drawBitmap(b, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }
}


/**
 * Pull down at the top of the list to refresh: a round arrow follows your
 * finger, spins once you've pulled far enough, and lets go into a refresh.
 */
@android.annotation.SuppressLint("ViewConstructor")
class PullFrame(c: Context, private val onRefresh: () -> Unit) : FrameLayout(c) {
    var list: AbsListView? = null
    private val arrow = ImageView(c).apply {
        setImageResource(R.drawable.ic_refresh)
        imageTintList = ColorStateList.valueOf(Color.BLACK)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = c.round(Palette.INK, dpf(19f))
        elevation = dpf(8f)
        alpha = 0f
    }
    private var downY = 0f
    private var downX = 0f
    private var pulling = false
    private val far get() = dpf(90f)

    init {
        clipChildren = false
        post { addView(arrow, LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = -dp(44) }) }
    }

    private fun atTop(): Boolean = list?.canScrollVertically(-1) == false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY
                downX = e.rawX
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (!pulling && dy > dp(10) && dy > kotlin.math.abs(e.rawX - downX) * 1.5f && atTop()) {
                    pulling = true
                    downY = e.rawY
                    return true
                }
            }
        }
        return false
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(e)
        val pull = ((e.rawY - downY) * 0.5f).coerceAtLeast(0f)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                arrow.translationY = pull.coerceAtMost(far * 1.2f)
                arrow.alpha = (pull / far).coerceIn(0f, 1f)
                arrow.rotation = pull * 2.5f
                list?.translationY = pull * 0.5f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false
                list?.animate()?.translationY(0f)?.setDuration(360)?.setInterpolator(Spring(0.4f))?.start()
                if (pull >= far && e.actionMasked == MotionEvent.ACTION_UP) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    arrow.animate().rotationBy(540f).setDuration(700).withEndAction {
                        arrow.animate().translationY(0f).alpha(0f).setDuration(220).start()
                    }.start()
                    onRefresh()
                } else {
                    arrow.animate().translationY(0f).alpha(0f).setDuration(220).start()
                }
            }
        }
        return true
    }
}
