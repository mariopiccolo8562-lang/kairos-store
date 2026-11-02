package com.kairos.player

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * KAIROS Player — contenitore Android.
 * - interfaccia web locale (assets/www) oppure la versione aggiornata scaricata dal pannello (files/www)
 * - player nativo a schermo intero (PlayerActivity) e anteprima nativa nel riquadro della Live TV
 * - aggiornamento dell'APK, speed test e richieste HTTP native
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var lastBack = 0L
    private var uiOk = false

    companion object {
        private const val HOME = "file:///android_asset/www/index.html"
    }

    private val otaDir: File get() = File(filesDir, "www")
    private val otaFile: File get() = File(otaDir, "index.html")

    /** ritorno dal player nativo: posizione, durata, motore scelto, risoluzione */
    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val r = res.data?.getStringExtra(PlayerActivity.RESULT) ?: return@registerForActivityResult
        js("window.kxPlayerClosed && window.kxPlayerClosed(${JSONObject.quote(r)})")
    }

    private fun js(code: String) { runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("kairos", MODE_PRIVATE)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isFocusable = false }
        root.addView(previewBox, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        hideSystemUi()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true // consente al player di leggere playlist da qualsiasi server
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString KairosPlayer/${BuildConfig.VERSION_NAME}"
        }
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.addJavascriptInterface(Bridge(), "KairosNative")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            // il player non deve mai navigare fuori dai propri file
            private fun allowed(u: String) = u.startsWith("file:///android_asset/") || u.startsWith("file://" + otaDir.absolutePath)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !allowed(request.url.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !allowed(url)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recreate()
                return true
            }
        }

        loadUi()
        web.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.kxBack && window.kxBack()) ? 1 : 0") { r ->
                    if (r != "1") {
                        val now = System.currentTimeMillis()
                        if (now - lastBack < 2000) finish()
                        else {
                            lastBack = now
                            Toast.makeText(this@MainActivity, "Premi di nuovo Indietro per uscire", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    /* ------------------------------------------------------------------ */
    /* Interfaccia aggiornabile (OTA)                                      */
    /* ------------------------------------------------------------------ */
    private fun loadUi() {
        val fails = prefs.getInt("ui_fail", 0)
        val sameApk = prefs.getInt("ui_apk", -1) == BuildConfig.VERSION_CODE
        val useOta = otaFile.exists() && sameApk && fails < 2
        if (otaFile.exists() && !useOta) resetOta()
        uiOk = false
        if (useOta) {
            prefs.edit().putInt("ui_fail", fails + 1).apply()
            web.loadUrl("file://" + otaFile.absolutePath)
            // se la versione scaricata non parte entro 15 secondi, torna a quella inclusa nell'APK
            ui.postDelayed({ if (!uiOk) { resetOta(); web.loadUrl(HOME) } }, 15_000)
        } else web.loadUrl(HOME)
    }

    private fun resetOta() {
        otaDir.deleteRecursively()
        prefs.edit().remove("ui_ver").remove("ui_apk").putInt("ui_fail", 0).apply()
    }

    /* ------------------------------------------------------------------ */
    /* Anteprima nativa nel riquadro della Live TV                         */
    /* ------------------------------------------------------------------ */
    private var previewEngine: ExoEngine? = null
    private var previewId = ""
    private var previewAlt = ""
    private var previewTriedAlt = false

    private val previewListener = object : EngineListener {
        override fun onBuffering(buffering: Boolean) { js("window.kxPreviewState && kxPreviewState('${if (buffering) "loading" else "ready"}')") }
        override fun onReady() {
            val h = previewEngine?.videoHeight() ?: 0
            js("window.kxPreviewState && kxPreviewState('ready'); window.kxPreviewInfo && kxPreviewInfo(${JSONObject.quote(previewId)}, $h)")
        }
        override fun onError(message: String) {
            ui.post {
                if (!previewTriedAlt && previewAlt.isNotEmpty()) { previewTriedAlt = true; previewEngine?.play(previewAlt, 0) }
                else js("window.kxPreviewState && kxPreviewState('error')")
            }
        }
        override fun onEnded() {}
    }

    private fun showPreview(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val vw = o.optDouble("vw", 0.0)
        if (vw <= 0 || web.width == 0) return
        val scale = web.width / vw
        val lp = FrameLayout.LayoutParams((o.optInt("w") * scale).toInt(), (o.optInt("h") * scale).toInt())
        lp.leftMargin = (o.optInt("x") * scale).toInt()
        lp.topMargin = (o.optInt("y") * scale).toInt()
        previewBox.layoutParams = lp
        previewBox.visibility = View.VISIBLE
        val e = previewEngine ?: ExoEngine(this, previewListener, userAgent(), PlayPrefs("auto", "fast", "fit"), overlay = true).also {
            previewEngine = it
            previewBox.addView(it.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        previewId = o.optString("id")
        previewAlt = o.optString("a")
        previewTriedAlt = false
        e.play(o.optString("u"), 0)
    }

    private fun stopPreview() {
        previewEngine?.stop()
        previewBox.visibility = View.GONE
    }

    private fun userAgent(): String = try {
        WebSettings.getDefaultUserAgent(this) + " KairosPlayer/" + BuildConfig.VERSION_NAME
    } catch (e: Exception) { "Mozilla/5.0 (Linux; Android) KairosPlayer/" + BuildConfig.VERSION_NAME }

    /* ------------------------------------------------------------------ */
    /* Aggiornamento APK                                                   */
    /* ------------------------------------------------------------------ */
    private fun downloadAndInstall(url: String) {
        thread(name = "kx-apk") {
            try {
                val dir = if (Build.VERSION.SDK_INT >= 24) File(cacheDir, "apk") else (externalCacheDir ?: cacheDir)
                dir.mkdirs()
                val out = File(dir, "kairos-update.apk")
                var target = URL(url)
                var conn = target.openConnection() as HttpURLConnection
                var hops = 0
                while (true) {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location")
                    if (code in 300..399 && loc != null && hops < 8) {
                        conn.disconnect(); target = URL(target, loc); hops++
                        conn = target.openConnection() as HttpURLConnection
                        continue
                    }
                    if (code != 200) throw Exception("HTTP $code")
                    break
                }
                val total = conn.contentLength.toLong()
                var done = 0L
                var last = -1
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { fo ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            fo.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = ((done * 100) / total).toInt().coerceAtMost(99)
                                if (p != last) { last = p; js("window.kxApkCb && kxApkCb($p)") }
                            }
                        }
                    }
                }
                conn.disconnect()
                js("window.kxApkCb && kxApkCb(100)")
                runOnUiThread { launchInstaller(out) }
            } catch (e: Exception) {
                js("window.kxApkCb && kxApkCb(-1)")
            }
        }
    }

    private fun launchInstaller(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Consenti a ${getString(R.string.app_name)} di installare app, poi torna indietro", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            pendingApk = apk
            return
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(this, "$packageName.files", apk) else Uri.fromFile(apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "Installazione non disponibile su questo dispositivo", Toast.LENGTH_LONG).show() }
    }

    private var pendingApk: File? = null

    /* ------------------------------------------------------------------ */
    /* Speed test                                                          */
    /* ------------------------------------------------------------------ */
    private fun runSpeedTest() {
        thread(name = "kx-speed") {
            fun send(o: JSONObject) = js("window.kxSpeedCb && kxSpeedCb(${JSONObject.quote(o.toString())})")
            try {
                // latenza: media di 3 richieste vuote
                var pingSum = 0L
                repeat(3) {
                    val t0 = System.nanoTime()
                    val c = URL("https://speed.cloudflare.com/__down?bytes=0").openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    c.inputStream.use { it.read() }
                    c.disconnect()
                    pingSum += (System.nanoTime() - t0) / 1_000_000
                }
                val ping = pingSum / 3.0
                send(JSONObject().put("ping", ping).put("mbps", 0))
                // download: fino a 8 secondi
                val c = URL("https://speed.cloudflare.com/__down?bytes=200000000").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                val start = System.nanoTime()
                var got = 0L
                var lastSend = 0L
                c.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        got += n
                        val el = (System.nanoTime() - start) / 1_000_000
                        if (el - lastSend > 250) { lastSend = el; send(JSONObject().put("ping", ping).put("mbps", got * 8.0 / (el / 1000.0) / 1e6)) }
                        if (el > 8000) break
                    }
                }
                c.disconnect()
                val sec = (System.nanoTime() - start) / 1e9
                send(JSONObject().put("done", true).put("ping", ping).put("mbps", got * 8.0 / sec / 1e6))
            } catch (e: Exception) {
                send(JSONObject().put("error", e.javaClass.simpleName))
            }
        }
    }

    /* ------------------------------------------------------------------ */

    /** Tasti canale +/- del telecomando → zapping nel player */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> "ChannelUp"
            KeyEvent.KEYCODE_CHANNEL_DOWN -> "ChannelDown"
            KeyEvent.KEYCODE_MENU -> "ContextMenu"
            else -> null
        }
        if (key != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'$key',bubbles:true}))", null)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onPause() {
        stopPreview()
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.requestFocus()
        // tornando dalle impostazioni "installa app sconosciute"
        val apk = pendingApk
        if (apk != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) { pendingApk = null; launchInstaller(apk) }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        previewEngine?.release()
        previewEngine = null
        web.destroy()
        super.onDestroy()
    }

    /** Funzioni esposte al player come window.KairosNative */
    inner class Bridge {
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun versionCode(): Int = BuildConfig.VERSION_CODE
        @JavascriptInterface fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(70)
        @JavascriptInterface fun exit() { runOnUiThread { finish() } }

        /** l'interfaccia è partita correttamente */
        @JavascriptInterface fun uiReady() { uiOk = true; prefs.edit().putInt("ui_fail", 0).apply() }
        @JavascriptInterface fun uiVersion(): String = prefs.getString("ui_ver", "") ?: ""

        /** installa una nuova versione dell'interfaccia scaricata dal pannello e la ricarica */
        @JavascriptInterface fun applyUi(html: String, version: String) {
            try {
                otaDir.mkdirs()
                File(otaDir, "index.tmp").writeText(html, Charsets.UTF_8)
                val cfg = try { assets.open("www/config.js").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
                File(otaDir, "config.js").writeText(cfg, Charsets.UTF_8)
                File(otaDir, "index.tmp").renameTo(otaFile)
                prefs.edit().putString("ui_ver", version).putInt("ui_apk", BuildConfig.VERSION_CODE).putInt("ui_fail", 0).apply()
                runOnUiThread { loadUi() }
            } catch (e: Exception) { resetOta() }
        }

        /** Avvia il player nativo (ExoPlayer / VLC) */
        @JavascriptInterface fun play(payload: String) {
            PlayerActivity.pending = payload
            runOnUiThread {
                stopPreview()
                playerLauncher.launch(Intent(this@MainActivity, PlayerActivity::class.java))
            }
        }

        @JavascriptInterface fun preview(json: String) { runOnUiThread { showPreview(json) } }
        @JavascriptInterface fun previewStop() { runOnUiThread { stopPreview() } }
        @JavascriptInterface fun installApk(url: String) { downloadAndInstall(url) }
        @JavascriptInterface fun speedTest() { runSpeedTest() }

        /** Richiesta HTTP fatta dall'app (senza limiti CORS). Risposta in window.kxHttpCb(id, status, body). */
        @JavascriptInterface fun http(id: String, method: String, url: String, body: String, ua: String) {
            thread(name = "kx-http") {
                var status = -1
                var text: String
                try {
                    var target = URL(url)
                    var hops = 0
                    var conn: HttpURLConnection = target.openConnection() as HttpURLConnection
                    while (true) {
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 30_000
                        conn.requestMethod = if (method == "POST") "POST" else "GET"
                        conn.setRequestProperty("User-Agent", ua.ifBlank { "KairosPlayer/" + BuildConfig.VERSION_NAME })
                        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                        if (method == "POST") {
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        }
                        val code = conn.responseCode
                        val loc = conn.getHeaderField("Location")
                        if (code in 300..399 && loc != null && hops < 5 && method != "POST") {
                            conn.disconnect()
                            target = URL(target, loc)
                            hops++
                            conn = target.openConnection() as HttpURLConnection
                            continue
                        }
                        status = code
                        break
                    }
                    val stream = if (status >= 400) conn.errorStream else conn.inputStream
                    val out = ByteArrayOutputStream()
                    stream?.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 80 * 1024 * 1024) break
                            out.write(buf, 0, n)
                        }
                    }
                    conn.disconnect()
                    text = out.toString("UTF-8")
                } catch (e: Exception) {
                    status = -1
                    text = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                }
                val js = "window.kxHttpCb && window.kxHttpCb(${JSONObject.quote(id)}, $status, ${JSONObject.quote(text)})"
                runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }
            }
        }
    }
}
