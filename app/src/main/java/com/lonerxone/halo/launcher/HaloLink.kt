package com.lonerxone.halo.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock

/**
 * The bridge to Halo (the island app). Both apps are signed with the same key
 * and share a signature permission, so only Halo can talk to the launcher and
 * only the launcher can drive Halo.
 *
 * Halo -> launcher: STATE broadcasts with music, unread badges, recent
 * notifications and what's switched on (flashlight, DND...).
 * Launcher -> Halo: COMMAND broadcasts: play/pause/next/prev, open the
 * notification center, open one notification, lock the screen, switch
 * something off, or "send me your state".
 */
object HaloLink {
    const val HALO = "com.lonerxone.halo"
    const val PERMISSION = "com.lonerxone.halo.permission.LINK"
    const val ACTION_STATE = "com.lonerxone.halo.action.STATE"
    const val ACTION_COMMAND = "com.lonerxone.halo.action.COMMAND"

    class Music(
        val title: String,
        val artist: String,
        val app: String,
        val playing: Boolean,
        val art: Bitmap?,
        val accent: Int,
        val durationMs: Long,
        val positionMs: Long,
        val positionAt: Long,
        val speed: Float,
    ) {
        fun position(): Long {
            if (!playing || positionAt <= 0L) return positionMs
            return (positionMs + (SystemClock.elapsedRealtime() - positionAt) * speed).toLong().coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        }
    }

    class Note(val key: String, val app: String, val appName: String, val title: String, val text: String, val time: Long)
    class Quick(val id: String, val label: String)
    /** A song in the player's queue (only when the music app shares it). */
    class Track(val id: Long, val title: String, val sub: String)

    var music: Music? = null
        private set
    var badges: Map<String, Int> = emptyMap()
        private set
    var notes: List<Note> = emptyList()
        private set
    var quick: List<Quick> = emptyList()
        private set
    var queue: List<Track> = emptyList()
        private set
    var queueActive = -1L
        private set
    private var lastState = 0L

    /** Halo answered recently, so it's running and its accessibility service can act. */
    fun alive(): Boolean = SystemClock.elapsedRealtime() - lastState < 10 * 60_000L && lastState > 0L

    fun installed(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(HALO, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STATE) return
            read(intent)
            music?.let { remember(context, it) }
            for (l in listeners.toList()) l()
        }
    }
    private var registered = false

    fun start(c: Context) {
        if (!registered) {
            registered = true
            val f = IntentFilter(ACTION_STATE)
            if (Build.VERSION.SDK_INT >= 33) {
                c.applicationContext.registerReceiver(receiver, f, PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                c.applicationContext.registerReceiver(receiver, f, PERMISSION, null)
            }
        }
        command(c, "request_state")
    }

    @Suppress("DEPRECATION")
    private fun read(i: Intent) {
        lastState = SystemClock.elapsedRealtime()
        val title = i.getStringExtra("m_title")
        music = if (title.isNullOrEmpty()) null else Music(
            title = title,
            artist = i.getStringExtra("m_artist").orEmpty(),
            app = i.getStringExtra("m_app").orEmpty(),
            playing = i.getBooleanExtra("m_playing", false),
            art = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("m_art", Bitmap::class.java) else i.getParcelableExtra("m_art"),
            accent = i.getIntExtra("m_accent", Palette.GRAPHITE),
            durationMs = i.getLongExtra("m_dur", 0L),
            positionMs = i.getLongExtra("m_pos", 0L),
            positionAt = i.getLongExtra("m_pos_at", 0L),
            speed = i.getFloatExtra("m_speed", 1f),
        )
        val b = HashMap<String, Int>()
        val pk = i.getStringArrayExtra("b_pkg") ?: emptyArray()
        val ct = i.getIntArrayExtra("b_count") ?: IntArray(0)
        for (n in pk.indices) if (n < ct.size) b[pk[n]] = ct[n]
        badges = b
        val keys = i.getStringArrayExtra("n_key") ?: emptyArray()
        val apps = i.getStringArrayExtra("n_app") ?: emptyArray()
        val names = i.getStringArrayExtra("n_app_name") ?: emptyArray()
        val titles = i.getStringArrayExtra("n_title") ?: emptyArray()
        val texts = i.getStringArrayExtra("n_text") ?: emptyArray()
        val times = i.getLongArrayExtra("n_time") ?: LongArray(0)
        notes = keys.indices.map { n ->
            Note(keys[n], apps.getOrElse(n) { "" }, names.getOrElse(n) { "" }, titles.getOrElse(n) { "" }, texts.getOrElse(n) { "" }, times.getOrElse(n) { 0L })
        }
        val tid = i.getLongArrayExtra("q_ids") ?: LongArray(0)
        val tt = i.getStringArrayExtra("q_titles") ?: emptyArray()
        val ts = i.getStringArrayExtra("q_subs") ?: emptyArray()
        queue = tid.indices.map { n -> Track(tid[n], tt.getOrElse(n) { "" }, ts.getOrElse(n) { "" }) }
        queueActive = i.getLongExtra("q_active", -1L)
        val qid = i.getStringArrayExtra("q_id") ?: emptyArray()
        val qlabel = i.getStringArrayExtra("q_label") ?: emptyArray()
        quick = qid.indices.map { n -> Quick(qid[n], qlabel.getOrElse(n) { qid[n] }) }
    }

    // ----- The last song, remembered (so the music card isn't blank when nothing plays) -----

    private var cachedMusic: Music? = null
    private var cacheLoaded = false
    private var cacheKey = ""

    /** The last song that played, shown paused; null if there's never been one. */
    fun lastSong(c: Context): Music? {
        if (!cacheLoaded) {
            cacheLoaded = true
            try {
                val p = c.getSharedPreferences("music_cache", Context.MODE_PRIVATE)
                val t = p.getString("title", null)
                if (!t.isNullOrEmpty()) {
                    val f = java.io.File(c.cacheDir, "last_cover.png")
                    val art = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path) else null
                    cachedMusic = Music(t, p.getString("artist", "").orEmpty(), p.getString("app", "").orEmpty(), false, art,
                        p.getInt("accent", Palette.GRAPHITE), p.getLong("dur", 0L), p.getLong("pos", 0L), 0L, 1f)
                    cacheKey = "$t|${p.getString("artist", "")}"
                }
            } catch (_: Exception) {
            }
        }
        return cachedMusic
    }

    private fun remember(c: Context, m: Music) {
        val k = "${m.title}|${m.artist}"
        val newSong = k != cacheKey
        cacheKey = k
        cachedMusic = Music(m.title, m.artist, m.app, false, m.art, m.accent, m.durationMs, m.position(), 0L, 1f)
        cacheLoaded = true
        val app = c.applicationContext
        val art = m.art
        val pos = m.position()
        Thread {
            try {
                app.getSharedPreferences("music_cache", Context.MODE_PRIVATE).edit()
                    .putString("title", m.title).putString("artist", m.artist).putString("app", m.app)
                    .putInt("accent", m.accent).putLong("dur", m.durationMs).putLong("pos", pos).apply()
                if (newSong && art != null) java.io.File(app.cacheDir, "last_cover.png").outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (_: Exception) {
            }
        }.start()
    }

    /** After you seek: show the new spot straight away (Halo confirms a moment later). */
    fun localSeek(ms: Long) {
        val m = music ?: return
        music = Music(m.title, m.artist, m.app, m.playing, m.art, m.accent, m.durationMs, ms, SystemClock.elapsedRealtime(), m.speed)
    }

    /** Sends a command to Halo; returns false when Halo isn't installed. */
    fun command(c: Context, cmd: String, arg: String? = null): Boolean {
        if (!installed(c)) return false
        val i = Intent(ACTION_COMMAND).setPackage(HALO).putExtra("cmd", cmd)
        if (arg != null) i.putExtra("arg", arg)
        c.sendBroadcast(i, PERMISSION)
        return true
    }
}
