package com.vscode.desktop.standalone

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Full-screen host for Mode 2 (`code serve-web`).
 *
 * As soon as http://127.0.0.1:8080 answers, the official workbench is loaded in an in-app WebView
 * (domStorage on, in-app navigation not intercepted). If the WebView is unavailable or keeps failing, the same URL
 * is handed to the system browser (ACTION_VIEW) — the user never has to copy a link out of a terminal log.
 *
 * Closing this screen does NOT stop the server: it keeps running for 10 more minutes (so reopening is instant and
 * restores the same file/tab/line), then the guest-side watcher hibernates it to save battery. Reopening after
 * that restarts the server transparently and reloads the page.
 */
class WorkbenchActivity : AppCompatActivity() {

    private lateinit var prootManager: ProotProcessManager
    private lateinit var prefs: AppPrefs

    private lateinit var root: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var spinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var retryButton: Button
    private lateinit var browserButton: Button

    private var webView: WebView? = null

    @Volatile
    private var destroyed = false

    @Volatile
    private var starting = false

    /** Server epoch the WebView currently shows; -1 forces a (re)load once the server is live. */
    private var loadedEpoch = -1
    private var loadRetries = 0
    private val ui = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prootManager = ProotProcessManager(applicationContext)
        prefs = AppPrefs(applicationContext)

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        buildOverlay()

        try {
            val wv = WebView(this)
            wv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            wv.setBackgroundColor(BG)
            configureWebView(wv)
            root.addView(wv)
            webView = wv
        } catch (e: Throwable) {
            AppLog.add("In-app WebView unavailable: ${e.message}")
            webView = null
        }
        root.addView(overlay)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Close the window only; the server stays up for the 10-minute grace period.
                finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        s.setSupportZoom(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)

        installClipboardBridge(wv)

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host
                if (host == "127.0.0.1" || host == "localhost") return false // stay inside the app
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadRetries = 0
                rememberWorkbenchUrl(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                rememberWorkbenchUrl(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) scheduleReload()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 500) scheduleReload()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // Keep the app alive; rebuild the page once the server is confirmed up.
                loadedEpoch = -1
                ensureServer()
                return true
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Continue where you left off: the workbench reopens on the folder it showed last (its tabs, cursor positions and
    // unsaved edits live in the page's own storage and come back with the folder).
    // ---------------------------------------------------------------------------------------------

    /** Reduces a workbench address to http://127.0.0.1:8080/?folder=… (or ?workspace=…); null for anything else. */
    private fun canonicalWorkbenchUrl(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return try {
            val u = Uri.parse(raw)
            if (u.host != "127.0.0.1" || u.port != Mode2Server.PORT) return null
            val key = when {
                !u.getQueryParameter("folder").isNullOrEmpty() -> "folder"
                !u.getQueryParameter("workspace").isNullOrEmpty() -> "workspace"
                else -> return null
            }
            Uri.Builder()
                .scheme("http")
                .encodedAuthority("127.0.0.1:${Mode2Server.PORT}")
                .path("/")
                .appendQueryParameter(key, u.getQueryParameter(key))
                .build()
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun rememberWorkbenchUrl(raw: String?) {
        val url = canonicalWorkbenchUrl(raw) ?: return
        if (url != prefs.mode2LastUrl) prefs.mode2LastUrl = url
    }

    /** The folder as the Linux system sees it, checked on the phone's side. True when unsure. */
    private fun guestPathExists(path: String): Boolean {
        return try {
            val host = if (path == WORKSPACE_GUEST || path.startsWith("$WORKSPACE_GUEST/")) {
                File(prefs.workspace ?: AppPrefs.defaultWorkspace(), path.removePrefix(WORKSPACE_GUEST).trimStart('/'))
            } else {
                File(prootManager.rootfsDir, path.trimStart('/'))
            }
            Files.exists(Paths.get(host.path), LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            true
        }
    }

    /** The remembered folder (if it still exists), otherwise the project folder. */
    private fun startUrl(): String {
        val saved = prefs.mode2LastUrl ?: return Mode2Server.URL
        val folder = Uri.parse(saved).getQueryParameter("folder")
        if (folder != null && folder.startsWith("/") && !guestPathExists(folder)) return Mode2Server.URL
        return saved
    }

    // ---------------------------------------------------------------------------------------------
    // Clipboard bridge (Paste / Copy in the workbench's menus, command palette and terminal)
    // ---------------------------------------------------------------------------------------------

    /**
     * A WebView has no permission prompt for `navigator.clipboard`, so the workbench's own Paste (menu, context menu,
     * terminal) was always refused and there was nowhere to allow it. This gives the page of the local server (and
     * only that origin) the Android clipboard through a message channel: readText / writeText / read / write.
     * Keyboard paste and the long-press paste menu of the WebView keep working as before.
     */
    private fun installClipboardBridge(wv: WebView) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            ) {
                AppLog.add("[mode2] This WebView cannot bridge the clipboard (update Android System WebView).")
                return
            }
            val page = Uri.parse(Mode2Server.URL)
            val origins = setOf("${page.scheme}://${page.host}:${page.port}")
            WebViewCompat.addWebMessageListener(wv, "pocketClipboard", origins) { _, message, _, _, reply ->
                handleClipboardMessage(message.data) { answer -> reply.postMessage(answer) }
            }
            WebViewCompat.addDocumentStartJavaScript(wv, CLIPBOARD_BRIDGE_JS, origins)
        } catch (e: Throwable) {
            AppLog.add("[mode2] Clipboard bridge not installed: ${e.message}")
        }
    }

    private fun handleClipboardMessage(raw: String?, reply: (String) -> Unit) {
        if (raw == null) return
        var id = 0
        try {
            val request = JSONObject(raw)
            id = request.optInt("id")
            val answer = JSONObject().put("id", id)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            when (request.optString("op")) {
                "read" -> {
                    val clip = cm.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
                    } else {
                        ""
                    }
                    answer.put("ok", true).put("text", text)
                }
                "write" -> {
                    cm.setPrimaryClip(ClipData.newPlainText("VS Code", request.optString("text")))
                    answer.put("ok", true).put("text", "")
                }
                else -> answer.put("ok", false)
            }
            reply(answer.toString())
        } catch (e: Exception) {
            try {
                reply(JSONObject().put("id", id).put("ok", false).toString())
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        Mode2Server.foreground = true
        Mode2Server.clearExternalSession()
        Mode2Server.touchHeartbeat(prootManager)
        LinuxRuntimeService.start(this, LinuxRuntimeService.MODE_DIRECT)
        ensureServer()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(pauseRunnable)
        webView?.onResume()
        Mode2Server.touchHeartbeat(prootManager)
    }

    private val pauseRunnable = Runnable { webView?.onPause() }

    override fun onPause() {
        // Mode 2 battery saver: freeze the page's JS/timers while the app is not in front - after a short grace period,
        // so the workbench can still write its latest editor state (it saves it every few seconds).
        ui.postDelayed(pauseRunnable, PAUSE_GRACE_MS)
        super.onPause()
    }

    override fun onStop() {
        Mode2Server.foreground = false
        rememberWorkbenchUrl(webView?.url)
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        ui.removeCallbacksAndMessages(null)
        webView?.let { wv ->
            root.removeView(wv)
            // keep the page alive for a few more seconds so that its last editor state is written before it is torn down
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    wv.destroy()
                } catch (_: Throwable) {
                }
            }, PAUSE_GRACE_MS)
        }
        webView = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Server start / wait / load
    // ---------------------------------------------------------------------------------------------

    private fun ensureServer() {
        if (starting) return
        starting = true
        Thread({
            var live = Mode2Server.isServerLive()
            var failure: String? = null
            if (!live) {
                runOnUiThread { showOverlay("Starting VS Code server…", busy = true) }
                if (!Mode2Server.isProcessAlive) {
                    try {
                        Mode2Server.start(prootManager, prefs.workspace ?: AppPrefs.defaultWorkspace())
                    } catch (e: Exception) {
                        failure = e.message ?: "Could not start the VS Code server"
                        AppLog.add("[mode2] $failure")
                    }
                }
                val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
                while (failure == null && !destroyed && SystemClock.elapsedRealtime() < deadline) {
                    if (Mode2Server.isServerLive()) {
                        live = true
                        break
                    }
                    if (!Mode2Server.isProcessAlive) {
                        failure = "The VS Code server stopped unexpectedly"
                        break
                    }
                    val last = AppLog.tail(1).firstOrNull()
                    if (last != null) runOnUiThread { logText.text = last }
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            starting = false
            runOnUiThread {
                if (!destroyed) {
                    if (live) onServerReady() else onServerFailed(failure ?: "Timed out waiting for the server")
                }
            }
        }, "mode2-starter").start()
    }

    private fun onServerReady() {
        overlay.visibility = View.GONE
        val wv = webView
        if (wv == null) {
            openInBrowser("The in-app WebView is not available on this device")
            return
        }
        if (loadedEpoch != Mode2Server.epoch) {
            loadedEpoch = Mode2Server.epoch
            loadRetries = 0
            wv.loadUrl(startUrl())
        }
    }

    private fun onServerFailed(reason: String) {
        showOverlay("Could not start the VS Code server.\n$reason", busy = false)
        logText.text = AppLog.tail(6).joinToString("\n")
    }

    private fun scheduleReload() {
        if (destroyed) return
        loadRetries++
        if (loadRetries > MAX_LOAD_RETRIES) {
            openInBrowser("The in-app WebView could not load the workbench")
            return
        }
        ui.postDelayed({
            if (!destroyed) {
                loadedEpoch = -1 // re-check that the server is alive, then reload
                ensureServer()
            }
        }, 1500L)
    }

    /** Fallback: hand the workbench URL to the system browser. The server is kept alive for that session. */
    private fun openInBrowser(reason: String) {
        AppLog.add("[mode2] Opening in the system browser: $reason")
        Mode2Server.markExternalSession()
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(Mode2Server.URL)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
            finish()
        } catch (_: Exception) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("VS Code", Mode2Server.URL))
            } catch (_: Exception) {
            }
            Toast.makeText(this, "No browser found. Link copied: ${Mode2Server.URL}", Toast.LENGTH_LONG).show()
            showOverlay("No browser available.\nOpen ${Mode2Server.URL} in any browser.", busy = false)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Overlay UI (shown only while the server is starting or has failed)
    // ---------------------------------------------------------------------------------------------

    private fun showOverlay(message: String, busy: Boolean) {
        statusText.text = message
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        retryButton.visibility = if (busy) View.GONE else View.VISIBLE
        browserButton.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(0xFF3C3C3C.toInt())
            cornerRadius = dp(10).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun buildOverlay() {
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            setPadding(dp(28), dp(28), dp(28), dp(28))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true // swallow touches while visible
        }

        spinner = ProgressBar(this).apply { isIndeterminate = true }
        overlay.addView(spinner)

        statusText = TextView(this).apply {
            setTextColor(0xFFD4D4D4.toInt())
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
            text = "Starting VS Code server…"
        }
        overlay.addView(statusText)

        logText = TextView(this).apply {
            setTextColor(0xFF858585.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 8
        }
        overlay.addView(logText)

        retryButton = overlayButton("Retry") { ensureServer() }
        overlay.addView(retryButton)

        browserButton = overlayButton("Open in browser instead") { openInBrowser("Requested by the user") }
        overlay.addView(browserButton)

        overlay.addView(overlayButton("Stop server & close") {
            Mode2Server.stop()
            LinuxRuntimeService.stop(this)
            finish()
        })
    }

    companion object {
        /**
         * Runs at document start in the workbench page: replaces navigator.clipboard (read/write text) and the
         * clipboard permission query by calls over the "pocketClipboard" message channel. If the channel is missing
         * the page keeps the browser's own behaviour.
         */
        private const val CLIPBOARD_BRIDGE_JS = """
(function () {
  try {
    var port = window.pocketClipboard;
    if (!port || !window.navigator || window.__pocketClipboardBridge) return;
    window.__pocketClipboardBridge = true;
    var orig = navigator.clipboard;
    var pending = {};
    var seq = 0;
    function denied() {
      try { return new DOMException('Clipboard is not available', 'NotAllowedError'); }
      catch (e) { return new Error('Clipboard is not available'); }
    }
    port.onmessage = function (ev) {
      try {
        var m = JSON.parse(ev.data);
        var p = pending[m.id];
        if (!p) return;
        delete pending[m.id];
        clearTimeout(p.t);
        if (m.ok) p.res(m.text || ''); else p.rej(denied());
      } catch (e) {}
    };
    function call(op, text) {
      return new Promise(function (res, rej) {
        var id = ++seq;
        var t = setTimeout(function () {
          if (pending[id]) { delete pending[id]; rej(denied()); }
        }, 8000);
        pending[id] = { res: res, rej: rej, t: t };
        try {
          port.postMessage(JSON.stringify({ id: id, op: op, text: text === undefined ? '' : text }));
        } catch (e) {
          clearTimeout(t); delete pending[id]; rej(denied());
        }
      });
    }
    function viaOriginal(name, args) {
      if (orig && typeof orig[name] === 'function') return orig[name].apply(orig, args);
      return Promise.reject(denied());
    }
    var clip = {
      readText: function () { return call('read'); },
      writeText: function (t) { return call('write', String(t)).then(function () {}); },
      read: function () {
        if (typeof ClipboardItem !== 'function') return viaOriginal('read', arguments);
        return call('read').then(function (t) {
          return [new ClipboardItem({ 'text/plain': new Blob([t], { type: 'text/plain' }) })];
        });
      },
      write: function (items) {
        try {
          var it = items && items[0];
          if (it && it.types && it.types.indexOf('text/plain') >= 0) {
            return it.getType('text/plain')
              .then(function (b) { return b.text(); })
              .then(function (t) { return call('write', t); })
              .then(function () {});
          }
        } catch (e) {}
        return viaOriginal('write', arguments);
      },
      addEventListener: function () {},
      removeEventListener: function () {},
      dispatchEvent: function () { return false; }
    };
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true, enumerable: true, get: function () { return clip; }
    });
    if (navigator.permissions && typeof navigator.permissions.query === 'function') {
      var origQuery = navigator.permissions.query.bind(navigator.permissions);
      navigator.permissions.query = function (d) {
        if (d && (d.name === 'clipboard-read' || d.name === 'clipboard-write')) {
          return Promise.resolve({
            name: d.name, state: 'granted', onchange: null,
            addEventListener: function () {}, removeEventListener: function () {}
          });
        }
        return origQuery(d);
      };
    }
  } catch (e) {}
})();
"""

        private const val BG = 0xFF1E1E1E.toInt()
        private const val START_TIMEOUT_MS = 180_000L
        private const val MAX_LOAD_RETRIES = 6
        private const val PAUSE_GRACE_MS = 8_000L
        private const val WORKSPACE_GUEST = "/root/Workspace"
    }
}
