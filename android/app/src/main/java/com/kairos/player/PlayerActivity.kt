package com.kairos.player

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject
import java.util.Locale

/**
 * Player nativo a schermo intero (ExoPlayer o VLC).
 * Riceve dal player web: lista canali/titolo, indice, posizione di partenza e preferenze.
 */
class PlayerActivity : ComponentActivity(), EngineListener {

    companion object {
        /** payload passato in memoria (le liste canali possono essere troppo grandi per un Intent) */
        @Volatile var pending: String? = null
        const val RESULT = "kx_result"
        private val ACCENT = Color.parseColor("#E5202B")
    }

    private data class Item(val id: String, val title: String, val url: String, val alt: String, val num: Int, val group: String)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var spinner: ProgressBar
    private lateinit var osd: LinearLayout
    private lateinit var brandView: TextView
    private lateinit var tBadge: TextView
    private lateinit var tTitle: TextView
    private lateinit var tSub: TextView
    private lateinit var tTime: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bar: LinearLayout
    private lateinit var bPause: TextView
    private lateinit var bEngine: TextView
    private lateinit var zapView: TextView
    private lateinit var msgView: TextView

    private var engine: Engine? = null
    private var items: List<Item> = emptyList()
    private var index = 0
    private var kind = "live"
    private var engineName = "exo"
    private var userChangedEngine = false
    private var aspect = "fit"
    private var brand = "KAIROS"
    private lateinit var prefs: PlayPrefs
    private var currentUrl = ""
    private var triedAlt = false
    private var triedFallback = false
    private var retries = 0
    private var resumeMs = 0L
    private var numBuf = ""

    private val item: Item get() = items[index]
    private val isLive: Boolean get() = kind == "live"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = pending
        pending = null
        if (payload == null) { finish(); return }
        try {
            val j = JSONObject(payload)
            kind = j.optString("kind", "live")
            index = j.optInt("index", 0)
            engineName = j.optString("engine", "exo")
            aspect = j.optString("aspect", "fit")
            brand = j.optString("brand", "KAIROS")
            prefs = PlayPrefs(j.optString("quality", "auto"), j.optString("buffer", "balanced"), aspect)
            resumeMs = j.optLong("start", 0L)
            val arr = j.getJSONArray("items")
            items = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(o.optString("id"), o.optString("t"), o.optString("u"), o.optString("a"), o.optInt("n"), o.optString("g"))
            }
        } catch (e: Exception) { finish(); return }
        if (items.isEmpty()) { finish(); return }
        index = index.coerceIn(0, items.size - 1)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        hideSystemUi()
        startEngine(engineName)
        playCurrent(resumeMs)
        showOsd(false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (osd.visibility == View.VISIBLE && bar.hasFocus()) hideOsd() else finishWithResult()
            }
        })
    }

    /* ------------------------------------------------------------------ */
    /* Interfaccia                                                         */
    /* ------------------------------------------------------------------ */
    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }

    private fun button(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        val normal = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.argb(70, 255, 255, 255)) }
        val focused = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE) }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE)))
        setOnClickListener { onClick(); touchOsd() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        root.setOnClickListener { if (osd.visibility == View.VISIBLE) hideOsd() else showOsd(true) }

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            visibility = View.GONE
        }
        msgView = text(18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.argb(200, 20, 20, 24)) }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            visibility = View.GONE
        }
        zapView = text(64f, Color.WHITE, true).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(40), 0) }
            visibility = View.GONE
        }
        brandView = text(26f, ACCENT, true).apply {
            text = brand.uppercase(Locale.ROOT)
            letterSpacing = 0.18f
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(40), dp(26), 0, 0) }
        }

        // pannello in basso
        osd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(80), dp(40), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.TRANSPARENT))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        tBadge = text(12f, Color.WHITE, true).apply {
            letterSpacing = 0.2f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(ACCENT) }
        }
        val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(tBadge) }
        tTitle = text(30f, Color.WHITE, true).apply { maxLines = 1; setPadding(0, dp(8), 0, 0) }
        tSub = text(16f, Color.argb(200, 255, 255, 255))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(16) }
        }
        tTime = text(13f, Color.argb(190, 255, 255, 255)).apply { setPadding(0, dp(6), 0, dp(14)) }

        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bPause = button("Pausa") { engine?.togglePause(); ui.postDelayed({ updatePauseLabel() }, 150) }
        if (!isLive) bar.addView(bPause)
        bar.addView(button("Audio") { menuAudio() })
        bar.addView(button("Qualità") { menuQuality() })
        bar.addView(button("Sottotitoli") { menuSubtitles() })
        bar.addView(button("Formato") { menuAspect() })
        bEngine = button("") { menuEngine() }
        bar.addView(bEngine)
        if (!isLive) {
            bar.addView(button("−10 s") { engine?.seekBy(-10_000) })
            bar.addView(button("+30 s") { engine?.seekBy(30_000) })
        }
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }

        osd.addView(badgeRow)
        osd.addView(tTitle)
        osd.addView(tSub)
        osd.addView(progress)
        osd.addView(tTime)
        osd.addView(scroller)

        root.addView(osd)
        root.addView(brandView)
        root.addView(zapView)
        root.addView(spinner)
        root.addView(msgView)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private val hideRunnable = Runnable { hideOsd() }
    private val tickRunnable = object : Runnable {
        override fun run() { updateTime(); ui.postDelayed(this, 500) }
    }

    private fun showOsd(focusBar: Boolean) {
        updateInfo()
        osd.visibility = View.VISIBLE
        brandView.visibility = View.VISIBLE
        if (focusBar && bar.childCount > 0 && !bar.hasFocus()) bar.getChildAt(0).requestFocus()
        ui.removeCallbacks(tickRunnable); ui.post(tickRunnable)
        touchOsd()
    }

    private fun touchOsd() {
        ui.removeCallbacks(hideRunnable)
        ui.postDelayed(hideRunnable, if (bar.hasFocus()) 8000L else 4500L)
    }

    private fun hideOsd() {
        osd.visibility = View.GONE
        brandView.visibility = View.GONE
        ui.removeCallbacks(tickRunnable)
        root.requestFocus()
    }

    private fun engineLabel(n: String) = if (n == "vlc") "VLC" else "ExoPlayer"

    private fun updateInfo() {
        tBadge.text = if (isLive) "● IN DIRETTA" else "FILM · SERIE"
        tTitle.text = if (isLive && item.num > 0) "${item.num}  ${item.title}" else item.title
        tSub.text = listOf(item.group, engineLabel(engineName)).filter { it.isNotBlank() }.joinToString("  ·  ")
        bEngine.text = "Motore: ${engineLabel(engineName)}"
        progress.visibility = if (isLive) View.GONE else View.VISIBLE
        updatePauseLabel()
        updateTime()
    }

    private fun updatePauseLabel() { bPause.text = if (engine?.isPlaying() == false) "Riproduci" else "Pausa" }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    private fun updateTime() {
        val e = engine ?: return
        if (isLive) {
            tTime.text = "▲▼ cambia canale  ·  OK menu  ·  Indietro per uscire"
        } else {
            val d = e.duration(); val p = e.position()
            progress.progress = if (d > 0) ((p * 1000) / d).toInt() else 0
            tTime.text = "${fmt(p)} / ${if (d > 0) fmt(d) else "--:--"}   ·   ◀ −10 s   ▶ +30 s   ·   OK menu"
        }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun showMsg(msg: String?) {
        if (msg == null) { msgView.visibility = View.GONE; return }
        msgView.text = msg; msgView.visibility = View.VISIBLE
    }

    /* ------------------------------------------------------------------ */
    /* Riproduzione                                                        */
    /* ------------------------------------------------------------------ */
    private fun userAgent(): String = try {
        WebSettings.getDefaultUserAgent(this) + " KairosPlayer/" + BuildConfig.VERSION_NAME
    } catch (e: Exception) { "Mozilla/5.0 (Linux; Android) KairosPlayer/" + BuildConfig.VERSION_NAME }

    private fun startEngine(name: String) {
        engine?.let { root.removeView(it.view); it.release() }
        val e: Engine = try {
            if (name == "vlc") VlcEngine(this, this, "", prefs.copy(aspect = aspect)) else ExoEngine(this, this, userAgent(), prefs.copy(aspect = aspect))
        } catch (t: Throwable) {
            // se un motore non si avvia su questo dispositivo, usa l'altro
            if (name == "vlc") ExoEngine(this, this, userAgent(), prefs.copy(aspect = aspect)) else VlcEngine(this, this, "", prefs.copy(aspect = aspect))
        }
        engineName = e.name
        engine = e
        root.addView(e.view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    private fun playCurrent(startMs: Long) {
        triedAlt = false; triedFallback = false; retries = 0
        startUrl(item.url, startMs)
    }

    private fun startUrl(url: String, startMs: Long) {
        currentUrl = url
        showMsg(null)
        spinner.visibility = View.VISIBLE
        engine?.play(url, startMs)
    }

    private fun zap(delta: Int) {
        if (items.size < 2) return
        index = (index + delta + items.size) % items.size
        playCurrent(0)
        showOsd(false)
        flashNumber(if (item.num > 0) item.num.toString() else "")
    }

    private fun flashNumber(t: String) {
        if (t.isEmpty()) return
        zapView.text = t; zapView.visibility = View.VISIBLE
        ui.removeCallbacks(hideZap); ui.postDelayed(hideZap, 1500)
    }
    private val hideZap = Runnable { zapView.visibility = View.GONE }

    private val numRunnable = Runnable {
        val n = numBuf.toIntOrNull(); numBuf = ""
        zapView.visibility = View.GONE
        val i = items.indexOfFirst { it.num == n }
        if (i >= 0 && i != index) { index = i; playCurrent(0); showOsd(false) } else if (n != null) toast("Canale $n non trovato")
    }

    private fun switchEngine(name: String, byUser: Boolean) {
        val pos = if (isLive) 0L else engine?.position() ?: 0L
        if (byUser) userChangedEngine = true
        startEngine(name)
        startUrl(currentUrl, pos)
        updateInfo()
    }

    /* EngineListener */
    override fun onBuffering(buffering: Boolean) { ui.post { spinner.visibility = if (buffering) View.VISIBLE else View.GONE } }

    override fun onReady() { ui.post { spinner.visibility = View.GONE; showMsg(null); retries = 0 } }

    override fun onEnded() {
        ui.post {
            if (isLive) { if (retries < 3) { retries++; startUrl(currentUrl, 0) } }
            else finishWithResult()
        }
    }

    override fun onError(message: String) {
        ui.post {
            val pos = if (isLive) 0L else engine?.position() ?: 0L
            when {
                !triedAlt && item.alt.isNotEmpty() && currentUrl != item.alt -> { triedAlt = true; startUrl(item.alt, pos) }
                !triedFallback -> {
                    triedFallback = true
                    val other = if (engineName == "vlc") "exo" else "vlc"
                    toast("Provo con ${engineLabel(other)}…")
                    startEngine(other)
                    startUrl(currentUrl, pos)
                    updateInfo()
                }
                isLive && retries < 3 -> { retries++; ui.postDelayed({ startUrl(currentUrl, 0) }, 1500L * retries) }
                else -> { spinner.visibility = View.GONE; showMsg("Impossibile riprodurre questo contenuto\n($message)") }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Menu                                                                */
    /* ------------------------------------------------------------------ */
    private fun choose(title: String, list: List<Track>, empty: String, onPick: (Track) -> Unit) {
        if (list.isEmpty()) { toast(empty); return }
        val labels = list.map { it.label }.toTypedArray()
        val checked = list.indexOfFirst { it.selected }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { d, which -> onPick(list[which]); d.dismiss(); touchOsd() }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun menuAudio() {
        val list = engine?.audioTracks().orEmpty()
        if (list.size == 1) { toast("Questo contenuto ha una sola traccia audio: ${list[0].label}"); return }
        choose("Traccia audio", list, "Nessuna traccia audio disponibile (attendi l'avvio del video)") { engine?.selectAudio(it.id); toast("Audio: ${it.label}") }
    }

    private fun menuQuality() = choose("Qualità video", engine?.qualities().orEmpty(), "Qualità non disponibile") {
        engine?.selectQuality(it.id); toast("Qualità: ${it.label}")
    }

    private fun menuSubtitles() = choose("Sottotitoli", engine?.subtitles().orEmpty(), "Nessun sottotitolo disponibile") {
        engine?.selectSubtitle(it.id)
    }

    private fun menuAspect() {
        val opts = listOf(Track("fit", "Adatta allo schermo", aspect == "fit"), Track("fill", "Riempi (deforma)", aspect == "fill"), Track("zoom", "Zoom (taglia i bordi)", aspect == "zoom"))
        choose("Formato immagine", opts, "") { aspect = it.id; engine?.setAspect(it.id) }
    }

    private fun menuEngine() {
        val opts = listOf(Track("exo", "ExoPlayer — più fluido (consigliato)", engineName == "exo"), Track("vlc", "VLC — legge più formati", engineName == "vlc"))
        choose("Motore di riproduzione", opts, "") { if (it.id != engineName) { switchEngine(it.id, true); toast("Motore: ${engineLabel(it.id)}") } }
    }

    /* ------------------------------------------------------------------ */
    /* Telecomando                                                         */
    /* ------------------------------------------------------------------ */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val barFocused = osd.visibility == View.VISIBLE && bar.hasFocus()
        if (barFocused) {
            touchOsd()
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { hideOsd(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> if (isLive) zap(-1) else showOsd(false)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (isLive) zap(1) else showOsd(true)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (isLive) showOsd(false) else { engine?.seekBy(-10_000); showOsd(false) }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (isLive) showOsd(false) else { engine?.seekBy(30_000); showOsd(false) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> showOsd(true)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE -> { engine?.togglePause(); showOsd(false) }
            KeyEvent.KEYCODE_CAPTIONS -> menuSubtitles()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> menuAudio()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (!isLive) return true
                numBuf += (keyCode - KeyEvent.KEYCODE_0).toString()
                zapView.text = numBuf; zapView.visibility = View.VISIBLE
                ui.removeCallbacks(numRunnable); ui.postDelayed(numRunnable, 1500)
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /* ------------------------------------------------------------------ */
    /* Ciclo di vita                                                       */
    /* ------------------------------------------------------------------ */
    private var stoppedAt = -1L

    override fun onStop() {
        super.onStop()
        val e = engine ?: return
        stoppedAt = if (isLive) 0L else e.position()
        e.stop()
    }

    override fun onRestart() {
        super.onRestart()
        if (stoppedAt >= 0 && currentUrl.isNotEmpty()) startUrl(currentUrl, stoppedAt)
        stoppedAt = -1L
    }

    private fun finishWithResult() {
        val e = engine
        val r = JSONObject()
            .put("kind", kind)
            .put("id", item.id)
            .put("pos", (e?.position() ?: 0L) / 1000)
            .put("dur", (e?.duration() ?: 0L) / 1000)
            .put("height", e?.videoHeight() ?: 0)
        if (userChangedEngine) r.put("engine", engineName)
        setResult(android.app.Activity.RESULT_OK, Intent().putExtra(RESULT, r.toString()))
        finish()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        engine?.release()
        engine = null
        super.onDestroy()
    }
}
