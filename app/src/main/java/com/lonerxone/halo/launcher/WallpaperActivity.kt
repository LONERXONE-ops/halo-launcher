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
    private lateinit var search: EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        build()
        if (intent.getStringExtra("tab") == "saved") showSaved() else load(reset = true)
    }

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(Palette.INK)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = round(Palette.TILE2, dpf(21f))
            setOnClickListener { onBackPressedCompat() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        head.addView(text(26f, Palette.INK, bold = true).apply {
            text = "Wallpapers"
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(13f, Color.BLACK, bold = true).apply {
            text = "Your photos"
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = round(Palette.INK, dpf(18f))
            pressable()
            setOnClickListener { pickPhoto() }
        })
        col.addView(head)

        // Search
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(Palette.TILE2, dpf(22f))
            setPadding(dp(14), 0, dp(10), 0)
        }
        pill.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(Palette.GREY)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        search = EditText(this).apply {
            hint = "Search wallpapers (e.g. Jujutsu Kaisen, rain, neon)"
            setHintTextColor(Palette.GREY)
            setTextColor(Palette.INK)
            textSize = 14.5f
            typeface = Fonts.regular(this@WallpaperActivity)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), 0, 0, 0)
            setOnEditorActionListener { v, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    query = text.toString().trim()
                    getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
                    savedTab = false
                    load(reset = true)
                    true
                } else {
                    false
                }
            }
        }
        pill.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        col.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
        })

        // Topics + Saved
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipRow)
        })
        drawChips()

        // Saved tab extras: daily shuffle
        savedBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(2), dp(14), dp(6))
            visibility = View.GONE
        }
        savedBar.addView(text(13.5f, Palette.SILVER, lines = 2).apply { text = "Change daily: a different saved wallpaper each day" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        @Suppress("UseSwitchCompatOrMaterialCode")
        savedBar.addView(Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(170, 170, 176)))
            trackTintList = ColorStateList(states, intArrayOf(Palette.GREEN, Color.rgb(58, 58, 62)))
            isChecked = Prefs.get(this@WallpaperActivity).getBoolean(Prefs.WALL_DAILY, false)
            setOnCheckedChangeListener { _, on -> Prefs.get(this@WallpaperActivity).edit().putBoolean(Prefs.WALL_DAILY, on).apply() }
        })
        col.addView(savedBar)

        status = text(13f, Palette.GREY, lines = 3).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(30), dp(20), dp(30))
        }
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(40))
            adapter = this@WallpaperActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                val w = items[pos]
                if (w.local != null) {
                    w.local.delete()
                    Toast.makeText(this@WallpaperActivity, "Removed from Saved", Toast.LENGTH_SHORT).show()
                    showSaved()
                }
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {}
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (!savedTab && !loading && total > 0 && first + visible >= total - 6 && page < lastPage) {
                        page++
                        load(reset = false)
                    }
                }
            })
        }
        val body = FrameLayout(this)
        body.addView(grid)
        body.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(text(10.5f, Palette.DIM).apply {
            text = "Wallpapers from wallhaven.cc, by their artists and uploaders"
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        })

        preview = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(0, b.top, 0, b.bottom)
            insets
        }
        setContentView(root)
    }

    private fun drawChips() {
        chipRow.removeAllViews()
        fun add(label: String, on: Boolean, click: () -> Unit) {
            chipRow.addView(text(13f, if (on) Color.BLACK else Palette.INK, bold = true).apply {
                text = label
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = round(if (on) Palette.INK else Palette.GRAPHITE, dpf(17f))
                setOnClickListener {
                    it.bounce()
                    click()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        add("♥ Saved", savedTab) { showSaved() }
        for ((i, t) in topics.withIndex()) add(t.name, !savedTab && query.isEmpty() && i == topic) {
            topic = i
            query = ""
            search.setText("")
            savedTab = false
            load(reset = true)
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
        val sort = if (query.isNotEmpty()) "relevance" else t.sort
        val p = page
        io.execute {
            val result = try {
                val url = "https://wallhaven.cc/api/v1/search?purity=100&ratios=portrait&atleast=1080x1920" +
                    "&categories=$cats&sorting=$sort" + (if (sort == "toplist") "&topRange=1y" else "") +
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
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        preview.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        (thumbs.get(w.local?.path ?: w.thumb))?.let { img.setImageBitmap(it) }
        // Your clock on top, to see how it looks.
        preview.addView(ClockFace(this), FrameLayout.LayoutParams(dp(170), dp(170)).apply {
            topMargin = dp(70)
            leftMargin = dp(22)
        })
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
                ui.post { if (shown === w && b != null) img.setImageBitmap(b) }
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
                ui.post { if (shown === w && b != null) img.setImageBitmap(b) }
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
            apply(w, flags) { ok ->
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
        preview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun closePreview() {
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
    private fun apply(w: Wall, flags: Int, done: (Boolean) -> Unit) {
        io.execute {
            val ok = try {
                setWallpaper(this, save(w), flags)
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
        if (preview.visibility == View.VISIBLE) closePreview() else finish()
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
            val files = File(c.filesDir, "wallpapers").listFiles()?.filter { it.name.endsWith(".jpg") }.orEmpty()
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
