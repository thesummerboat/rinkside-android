package se.jonasp.rinkside

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import se.jonasp.rinkside.engine.Snapshot
import se.jonasp.rinkside.engine.unwrapJsResult

/**
 * The Rinkside web app in a full-screen WebView, plus the plumbing for notifications:
 * after a page has loaded (and when the app is paused) a small script reads the followed teams and their games from the page
 * (assets/read-favourites.js). That is all the web app has to tell the native side; the site itself is not changed.
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_MATCH_ID = "se.jonasp.rinkside.MATCH_ID"
        private const val REQUEST_NOTIFICATIONS = 100
        private const val BACKGROUND = "#0B1119"
        private val MATCH_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        // The site defers its scripts: the globals may not exist yet right after onPageFinished, so ask again a few times.
        private val RETRY_DELAYS_MS = longArrayOf(800L, 2_500L, 6_000L, 12_000L, 25_000L, 45_000L)

        // While the app is open the followed teams and games are read again this often, so a star tapped during a live game
        // (follow or unfollow that one game) reaches the watch service within seconds, not only when the app is left.
        private const val PERIODIC_READ_MS = 8_000L
    }

    private lateinit var webView: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var readScript: String? = null
    private var attempt = 0
    private val retryRead = Runnable { readFavourites(retry = true) }
    private val pauseWebView = Runnable { webView.onPause() }
    private var lastFollowKey: String? = null
    private val periodicRead = object : Runnable {
        override fun run() {
            refreshIfChanged()
            ui.postDelayed(this, PERIODIC_READ_MS)
        }
    }
    private val baseHost: String = Uri.parse(BuildConfig.BASE_URL).host ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val background = Color.parseColor(BACKGROUND)

        val root = FrameLayout(this)
        root.setBackgroundColor(background)
        webView = WebView(this)
        webView.setBackgroundColor(background) // the site is dark: no white flash while it loads
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Android 15 draws apps edge to edge (targetSdk 35): keep the page clear of the system bars, the camera cut-out and the keyboard.
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        configureWebView()

        var restored = false
        if (savedInstanceState != null) {
            try {
                restored = webView.restoreState(savedInstanceState) != null
            } catch (e: Exception) {
                restored = false
            }
        }
        if (!restored) {
            val match = matchIdFrom(intent)
            webView.loadUrl(BuildConfig.BASE_URL + "/" + (if (match != null) "#match/$match" else ""))
        }

        askForNotificationsOnce()
        Scheduler.scheduleNext(this) // alarms from a previous run are still known; this keeps them current
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val req = request ?: return false
                if (!req.isForMainFrame) return false
                val uri = req.url ?: return false
                if (isInternal(uri)) return false
                openExternally(uri)
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                scheduleRead()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // The page's renderer died (or was killed for memory). Without this the whole app, and the watch service in it, would crash.
                Store.log(applicationContext, "WebView renderer gone, restarting the screen")
                ui.post { recreate() }
                return true
            }
        }
    }

    /** The Rinkside host stays in the WebView; everything else (news articles, nhl.com, shl.se, club sites, mailto:) opens in the other apps. */
    private fun isInternal(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") return true
        if (scheme != "https" && scheme != "http") return false
        return baseHost.isNotEmpty() && baseHost.equals(uri.host, ignoreCase = true)
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            // no app can open it: nothing to do
        }
    }

    // ---- deep link from a notification ----

    private fun matchIdFrom(intent: Intent?): String? {
        val id = intent?.getStringExtra(EXTRA_MATCH_ID) ?: return null
        return if (MATCH_ID.matches(id)) id else null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val match = matchIdFrom(intent) ?: return
        webView.loadUrl(BuildConfig.BASE_URL + "/#match/" + match) // only the fragment differs: the page navigates itself
    }

    // ---- reading the followed teams and games from the page ----

    private fun scheduleRead() {
        ui.removeCallbacks(retryRead)
        attempt = 0
        ui.postDelayed(retryRead, RETRY_DELAYS_MS[0])
    }

    private fun readFavourites(retry: Boolean) {
        runReadScript { snapshot ->
            if (snapshot != null) {
                Store.saveSnapshot(this, snapshot)
                Scheduler.scheduleNext(this)
            } else if (retry && attempt + 1 < RETRY_DELAYS_MS.size) {
                attempt++
                ui.postDelayed(retryRead, RETRY_DELAYS_MS[attempt])
            }
        }
    }

    /** Saves the followed games only when the list changed (teams or game ids), so the alarm is not set again every few seconds. */
    private fun refreshIfChanged() {
        runReadScript { snapshot ->
            if (snapshot == null) return@runReadScript
            val key = snapshot.teams.joinToString(",") + "|" + snapshot.games.joinToString(",") { it.id }
            if (key == lastFollowKey) return@runReadScript
            lastFollowKey = key
            Store.saveSnapshot(this, snapshot)
            Scheduler.scheduleNext(this)
        }
    }

    private fun runReadScript(callback: (Snapshot?) -> Unit) {
        val script = readScript ?: loadReadScript()
        if (script == null) {
            callback(null)
            return
        }
        readScript = script
        try {
            webView.evaluateJavascript(script) { value ->
                callback(Snapshot.fromPageScript(unwrapJsResult(value), System.currentTimeMillis()))
            }
        } catch (e: Exception) {
            callback(null)
        }
    }

    private fun loadReadScript(): String? = try {
        assets.open("read-favourites.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        null
    }

    // ---- notification permission (Android 13+) ----

    private fun askForNotificationsOnce() {
        Notifier.ensureChannels(this) // Android shows the permission dialog only for an app that has a channel
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (Store.notifAsked(this)) return
        Store.setNotifAsked(this)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    // ---- lifecycle ----

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(pauseWebView)
        webView.onResume()
        ui.removeCallbacks(periodicRead)
        ui.postDelayed(periodicRead, PERIODIC_READ_MS)
    }

    override fun onPause() {
        // The favourites may have changed while the app was open: read them once more before the page is paused.
        ui.removeCallbacks(periodicRead)
        readFavourites(retry = false)
        ui.postDelayed(pauseWebView, 1_500L)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            webView.saveState(outState)
        } catch (e: Exception) {
            // a dead renderer cannot be saved; the page is loaded again
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
