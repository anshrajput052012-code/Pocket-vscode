package com.vscode.desktop.standalone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Pocket VS Code — single-screen flow:
 *
 *   FOLDER  : pick the Android project folder (Step 1)
 *   LOADING : the moment a folder is chosen, Debian + VS Code are extracted from the APK and the environment is set
 *             up in a background thread, with a live 0-100 % progress bar
 *   READY   : choose Mode 1 (full Electron desktop) or Mode 2 (native web host) and launch
 *   MODE1   : embedded noVNC surface (Back returns to READY; Mode 2 opens in WorkbenchActivity)
 */
class MainActivity : AppCompatActivity(), ProvisionListener {

    private enum class Screen { FOLDER, LOADING, READY, MODE1 }

    private lateinit var prootManager: ProotProcessManager
    private lateinit var provisioner: StandaloneProvisioner
    private lateinit var prefs: AppPrefs

    private lateinit var folderScreen: ScrollView
    private lateinit var loadingScreen: ScrollView
    private lateinit var readyScreen: ScrollView
    private lateinit var mode1Container: LinearLayout

    private lateinit var percentText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var stepText: TextView
    private lateinit var detailText: TextView
    private lateinit var loadingLog: TextView
    private lateinit var retryButton: Button

    private lateinit var readyFolderLabel: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var qualityBox: LinearLayout
    private lateinit var qualityGroup: RadioGroup
    private lateinit var launchButton: Button
    private lateinit var verifyButton: Button
    private lateinit var statusText: TextView
    private lateinit var logView: TextView

    private var mode1WebView: WebView? = null
    private var currentScreen = Screen.FOLDER
    private val ui = Handler(Looper.getMainLooper())
    private var logRefreshScheduled = false

    // ---------------------------------------------------------------------------------------------
    // Activity-result launchers (must be registered before the Activity is STARTED)
    // ---------------------------------------------------------------------------------------------

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) applyWorkspace(resolveUriToPath(uri))
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { launchFolderPicker() }

    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { launchFolderPicker() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prootManager = ProotProcessManager(applicationContext)
        provisioner = StandaloneProvisioner(applicationContext, prootManager)
        prefs = AppPrefs(applicationContext)

        buildUi()
        registerBackHandler()
        requestNotificationPermissionOnce()
        decideInitialScreen()
    }

    override fun onStart() {
        super.onStart()
        StandaloneProvisioner.addListener(this)
        AppLog.listener = { runOnUiThread { scheduleLogRefresh() } }
        refreshLogViews()
        syncScreenWithState()
    }

    override fun onStop() {
        StandaloneProvisioner.removeListener(this)
        AppLog.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        mode1WebView?.destroy()
        mode1WebView = null
        super.onDestroy()
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    currentScreen == Screen.MODE1 -> showReady()
                    StandaloneProvisioner.isRunning() -> moveTaskToBack(true) // keep extracting in the background
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.askedNotificationPermission) {
            prefs.askedNotificationPermission = true
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Screen state machine
    // ---------------------------------------------------------------------------------------------

    private fun decideInitialScreen() {
        val workspace = prefs.workspace
        when {
            StandaloneProvisioner.isRunning() -> showScreen(Screen.LOADING)
            workspace != null && prootManager.isRootfsProvisioned() -> showReady()
            // A folder was chosen before but the one-time setup never finished (e.g. the app was closed): resume.
            workspace != null -> startProvisioning()
            else -> showScreen(Screen.FOLDER)
        }
    }

    /** Re-aligns the visible screen with reality after the app returns from the background. */
    private fun syncScreenWithState() {
        if (currentScreen == Screen.MODE1) return
        when {
            StandaloneProvisioner.isRunning() -> {
                if (currentScreen != Screen.LOADING) showScreen(Screen.LOADING)
            }
            prefs.workspace != null && prootManager.isRootfsProvisioned() -> {
                if (currentScreen == Screen.LOADING || currentScreen == Screen.FOLDER) showReady()
            }
            currentScreen == Screen.LOADING -> showProvisionFailure(
                StandaloneProvisioner.lastError() ?: "Setup was interrupted."
            )
        }
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        folderScreen.visibility = if (screen == Screen.FOLDER) View.VISIBLE else View.GONE
        loadingScreen.visibility = if (screen == Screen.LOADING) View.VISIBLE else View.GONE
        readyScreen.visibility = if (screen == Screen.READY) View.VISIBLE else View.GONE
        mode1Container.visibility = if (screen == Screen.MODE1) View.VISIBLE else View.GONE
    }

    private fun showReady() {
        readyFolderLabel.text = prefs.workspace ?: AppPrefs.defaultWorkspace()
        when (prefs.lastMode) {
            "direct" -> modeGroup.check(ID_MODE2)
            else -> modeGroup.check(ID_MODE1)
        }
        qualityBox.visibility = if (modeGroup.checkedRadioButtonId == ID_MODE2) View.GONE else View.VISIBLE
        launchButton.isEnabled = true
        launchButton.alpha = 1f
        statusText.text = "Setup complete · 100%. Choose a mode and launch."
        showScreen(Screen.READY)
        refreshLogViews()
    }

    // ---------------------------------------------------------------------------------------------
    // Step 1: folder selection  ->  automatic provisioning
    // ---------------------------------------------------------------------------------------------

    private fun pickFolderFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) launchFolderPicker() else requestAllFilesAccess()
        } else {
            val missing = listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) launchFolderPicker() else legacyStorageLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestAllFilesAccess() {
        Toast.makeText(
            this,
            "Allow \"All files access\" for Pocket VS Code, then go back to choose your folder.",
            Toast.LENGTH_LONG
        ).show()
        try {
            allFilesAccessLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                launchFolderPicker()
            }
        }
    }

    private fun launchFolderPicker() {
        try {
            folderPicker.launch(null)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No folder picker available on this device. Using the default folder.", Toast.LENGTH_LONG).show()
            applyWorkspace(AppPrefs.defaultWorkspace())
        }
    }

    /** Called the moment a folder is chosen: remember it and start the one-time setup automatically. */
    private fun applyWorkspace(path: String) {
        prefs.workspace = path
        AppLog.add("Selected Android Project Folder: $path")
        if (prootManager.isRootfsProvisioned()) showReady() else startProvisioning()
    }

    private fun startProvisioning() {
        percentText.text = "0%"
        progressBar.progress = 0
        stepText.text = "Starting setup…"
        detailText.text = "Keep the app open — this runs only once."
        retryButton.visibility = View.GONE
        showScreen(Screen.LOADING)
        AppLog.add("Starting self-contained installation inside the app's private storage…")
        StandaloneProvisioner.addListener(this)
        provisioner.startInBackground()
    }

    override fun onProgress(progress: ProvisionProgress) {
        runOnUiThread {
            if (currentScreen != Screen.LOADING) showScreen(Screen.LOADING)
            percentText.text = "${progress.percent}%"
            progressBar.progress = progress.percent
            stepText.text = progress.step
            detailText.text = progress.detail
        }
    }

    override fun onFinished(success: Boolean, error: String?) {
        runOnUiThread {
            if (success) {
                percentText.text = "100%"
                progressBar.progress = 100
                prefs.workspace = prefs.workspace ?: AppPrefs.defaultWorkspace()
                showReady()
            } else {
                showProvisionFailure(error ?: "Setup failed.")
            }
        }
    }

    private fun showProvisionFailure(message: String) {
        stepText.text = "Setup could not finish"
        detailText.text = message
        retryButton.visibility = View.VISIBLE
        showScreen(Screen.LOADING)
    }

    // ---------------------------------------------------------------------------------------------
    // Step 2: launch
    // ---------------------------------------------------------------------------------------------

    private fun launchSelected() {
        if (!prootManager.isRootfsProvisioned()) {
            Toast.makeText(this, "Setup is not finished yet.", Toast.LENGTH_SHORT).show()
            startProvisioning()
            return
        }
        if (modeGroup.checkedRadioButtonId == ID_MODE2) {
            prefs.lastMode = "direct"
            launchMode2()
        } else {
            prefs.lastMode = "x11"
            launchMode1()
        }
    }

    /** Mode 2: WorkbenchActivity starts/attaches to `code serve-web` and opens it as soon as port 8080 is live. */
    private fun launchMode2() {
        // one mode at a time: the two modes share settings and extensions
        if (Mode1Session.isAlive) AppLog.add("Stopping Mode 1 (Mode 2 shares its settings and extensions).")
        Mode1Session.stop()
        LinuxRuntimeService.start(this, LinuxRuntimeService.MODE_DIRECT)
        startActivity(Intent(this, WorkbenchActivity::class.java))
    }

    /** Mode 1: full Electron desktop through Xtigervnc + Openbox + noVNC (not throttled). */
    private fun launchMode1() {
        val workspace = prefs.workspace ?: AppPrefs.defaultWorkspace()
        launchButton.isEnabled = false
        launchButton.alpha = 0.5f
        statusText.text = "Starting Mode 1 (Xtigervnc + Openbox + VS Code)…"
        // one mode at a time: the two modes share settings and extensions
        Mode1Session.starting = true
        if (Mode2Server.isProcessAlive) AppLog.add("Stopping Mode 2 (Mode 1 shares its settings and extensions).")
        Mode2Server.stop()
        LinuxRuntimeService.start(this, LinuxRuntimeService.MODE_X11)

        val (screenW, screenH) = mode1ScreenSize()

        Thread({
            var started: Process? = null
            var errorMessage: String? = null
            try {
                Mode1Session.stop()
                prootManager.ensureSharedState()
                prootManager.writeMode1Geometry(screenW, screenH)
                val p = prootManager.startProotCommand(
                    command = "bash /root/start-vscode-session.sh x11",
                    workspaceHostPath = workspace,
                    displayMode = "x11",
                    onLogLine = { line ->
                        AppLog.add(line)
                        if (line.contains("VSCODE_DISPLAY_READY")) runOnUiThread { showMode1Surface() }
                    }
                )
                started = p
                Mode1Session.process = p
                Mode1Session.starting = false
                p.waitFor()
            } catch (e: Exception) {
                Mode1Session.starting = false
                errorMessage = "Could not start Mode 1: ${e.message}"
                AppLog.add("Mode 1 failed: ${e.message}")
            }
            val current = started
            val wasCurrent = if (current != null) Mode1Session.clearIfCurrent(current) else true
            if (wasCurrent) {
                prootManager.releaseServiceIfIdle()
                runOnUiThread {
                    launchButton.isEnabled = true
                    launchButton.alpha = 1f
                    if (currentScreen == Screen.MODE1) showReady()
                    statusText.text = errorMessage ?: "Mode 1 session ended."
                }
            }
        }, "mode1-session").start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createMode1WebView(): WebView? {
        return try {
            val wv = WebView(this)
            wv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            wv.settings.allowFileAccess = false
            wv.settings.useWideViewPort = true
            wv.settings.loadWithOverviewMode = true
            wv.settings.setSupportZoom(true)
            wv.webChromeClient = WebChromeClient()
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            mode1Container.addView(wv)
            wv
        } catch (e: Throwable) {
            statusText.text = "WebView is not available: ${e.message}"
            AppLog.add("WebView unavailable: ${e.message}")
            null
        }
    }

    private fun showMode1Surface() {
        val wv: WebView = mode1WebView ?: createMode1WebView() ?: return
        mode1WebView = wv
        val firstShow = currentScreen != Screen.MODE1
        showScreen(Screen.MODE1)
        if (firstShow) Toast.makeText(this, "Back = Setup / Logs", Toast.LENGTH_SHORT).show()
        // resize=scale: keep the fixed (light) server resolution and let the WebView scale it. "remote" would make the
        // server grow to the tablet's full pixel size, which is much heavier. Local loopback: low compression = less CPU.
        wv.loadUrl("http://127.0.0.1:6080/vnc.html?autoconnect=true&resize=scale&quality=6&compression=2")
    }

    private fun runVerification() {
        verifyButton.isEnabled = false
        verifyButton.alpha = 0.5f
        statusText.text = "Running verification inside embedded Debian ARM64…"
        Thread({
            var exit = -1
            try {
                val p = prootManager.startProotCommand(
                    command = "bash /root/verify-vscode.sh",
                    workspaceHostPath = prefs.workspace ?: AppPrefs.defaultWorkspace(),
                    displayMode = "x11",
                    onLogLine = { line -> AppLog.add(line) }
                )
                exit = p.waitFor()
            } catch (e: Exception) {
                AppLog.add("Verification failed to start: ${e.message}")
            }
            runOnUiThread {
                verifyButton.isEnabled = true
                verifyButton.alpha = 1f
                statusText.text = if (exit == 0) "Verification passed ✔" else "Verification finished with exit code $exit"
            }
        }, "verify").start()
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun resolveUriToPath(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val colon = docId.indexOf(':')
            val volume = if (colon >= 0) docId.substring(0, colon) else docId
            val relative = if (colon >= 0) docId.substring(colon + 1) else ""
            when {
                volume.equals("primary", ignoreCase = true) ->
                    File(Environment.getExternalStorageDirectory(), relative).absolutePath
                volume.equals("raw", ignoreCase = true) && relative.startsWith("/") -> relative
                volume.isNotEmpty() && File("/storage/$volume").exists() -> File("/storage/$volume", relative).absolutePath
                else -> AppPrefs.defaultWorkspace()
            }
        } catch (_: Exception) {
            AppPrefs.defaultWorkspace()
        }
    }

    private fun scheduleLogRefresh() {
        if (logRefreshScheduled) return
        logRefreshScheduled = true
        ui.postDelayed({
            logRefreshScheduled = false
            refreshLogViews()
        }, 250L)
    }

    private fun refreshLogViews() {
        loadingLog.text = AppLog.tail(6).joinToString("\n")
        logView.text = AppLog.tail(120).joinToString("\n")
    }

    // ---------------------------------------------------------------------------------------------
    // UI construction (programmatic, VS Code dark theme)
    // ---------------------------------------------------------------------------------------------

    private fun qualityRadio(radioId: Int, label: String): RadioButton = RadioButton(this).apply {
        id = radioId
        text = label
        setTextColor(TEXT)
        buttonTintList = ColorStateList.valueOf(ACCENT)
    }

    /** This screen's pixel size as a landscape size, rounded so the VNC server accepts it (width % 8, height % 2). */
    private fun nativeScreenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        var w = maxOf(dm.widthPixels, dm.heightPixels)
        var h = minOf(dm.widthPixels, dm.heightPixels)
        w = (w.coerceIn(800, 2560) / 8) * 8
        h = (h.coerceIn(500, 1600) / 2) * 2
        return w to h
    }

    private fun mode1ScreenSize(): Pair<Int, Int> = when (prefs.mode1Quality) {
        AppPrefs.QUALITY_BALANCED -> 1440 to 900
        AppPrefs.QUALITY_NATIVE -> nativeScreenSize()
        else -> 1920 to 1200
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        stateListAnimator = null
        minimumHeight = dp(52)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        val shape = GradientDrawable().apply {
            setColor(if (primary) ACCENT else 0xFF3C3C3C.toInt())
            cornerRadius = dp(10).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun makeText(value: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun makeLogText(): TextView = TextView(this).apply {
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(GREEN)
        setBackgroundColor(0xFF141414.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        text = "Ready. Zero external dependencies (libproot.so bundled in APK)."
    }

    private fun column(gravity: Int = Gravity.START): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun buildUi() {
        val rootFrame = FrameLayout(this).apply { setBackgroundColor(BG) }

        // ----- FOLDER (Step 1) -----
        val folderColumn = column(Gravity.CENTER_HORIZONTAL)
        folderColumn.addView(makeText("Pocket VS Code", 28f, Color.WHITE, bold = true))
        folderColumn.addView(
            makeText("Genuine Visual Studio Code on your phone — no Termux, 100% inside this app.", 14f, MUTED)
                .apply { setPadding(0, dp(6), 0, dp(28)) }
        )
        folderColumn.addView(makeText("Step 1", 13f, ACCENT, bold = true))
        folderColumn.addView(makeText("Select your Android project folder", 18f, TEXT, bold = true))
        folderColumn.addView(
            makeText(
                "As soon as you pick a folder, Linux and VS Code are set up automatically in the background. " +
                    "You do not need to press anything else.",
                13f, MUTED
            ).apply { setPadding(0, dp(6), 0, dp(8)) }
        )
        folderColumn.addView(makeButton("Select Project Folder", primary = true) { pickFolderFlow() })
        folderColumn.addView(
            makeButton("Use default folder (${AppPrefs.defaultWorkspace()})", primary = false) {
                applyWorkspace(AppPrefs.defaultWorkspace())
            }
        )
        folderScreen = ScrollView(this).apply {
            isFillViewport = true
            addView(folderColumn)
        }

        // ----- LOADING -----
        val loadingColumn = column(Gravity.CENTER)
        loadingColumn.addView(makeText("Setting up Pocket VS Code", 20f, Color.WHITE, bold = true))
        percentText = makeText("0%", 56f, ACCENT, bold = true).apply { setPadding(0, dp(16), 0, dp(8)) }
        loadingColumn.addView(percentText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3C3C3C.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        }
        loadingColumn.addView(progressBar)
        stepText = makeText("Starting setup…", 15f, TEXT, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) }
        loadingColumn.addView(stepText)
        detailText = makeText("", 13f, MUTED).apply { gravity = Gravity.CENTER }
        loadingColumn.addView(detailText)
        retryButton = makeButton("Retry setup", primary = true) { startProvisioning() }
        retryButton.visibility = View.GONE
        loadingColumn.addView(retryButton)
        loadingLog = makeLogText().apply {
            maxLines = 6
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        loadingColumn.addView(loadingLog)
        loadingScreen = ScrollView(this).apply {
            isFillViewport = true
            addView(loadingColumn)
        }

        // ----- READY -----
        val readyColumn = column()
        readyColumn.addView(makeText("Pocket VS Code", 24f, Color.WHITE, bold = true))
        readyColumn.addView(makeText("Project folder (bound to /root/Workspace):", 12f, MUTED).apply { setPadding(0, dp(14), 0, dp(2)) })
        readyFolderLabel = makeText("", 14f, 0xFF9CDCFE.toInt())
        readyColumn.addView(readyFolderLabel)
        readyColumn.addView(makeButton("Change project folder", primary = false) { pickFolderFlow() })

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(
                RadioButton(this@MainActivity).apply {
                    id = ID_MODE1
                    text = "Mode 1 — Full Electron Desktop\nXtigervnc + Openbox + VS Code (full speed, not throttled)"
                    setTextColor(TEXT)
                    buttonTintList = ColorStateList.valueOf(ACCENT)
                    isChecked = true
                }
            )
            addView(
                RadioButton(this@MainActivity).apply {
                    id = ID_MODE2
                    text = "Mode 2 — High-FPS Native Web Host\ncode serve-web · opens automatically · 0.5 s auto-save · battery saver"
                    setTextColor(TEXT)
                    buttonTintList = ColorStateList.valueOf(ACCENT)
                }
            )
        }
        readyColumn.addView(modeGroup)

        val (nativeW, nativeH) = nativeScreenSize()
        qualityBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        qualityBox.addView(makeText("Mode 1 screen quality (used the next time Mode 1 starts):", 12f, MUTED))
        qualityGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(qualityRadio(ID_Q_BALANCED, "Balanced · 1440×900\nlighter, text a little soft"))
            addView(qualityRadio(ID_Q_SHARP, "Sharp · 1920×1200\nclearer text"))
            addView(qualityRadio(ID_Q_NATIVE, "Native · ${nativeW}×${nativeH}\nthis screen's own size: sharpest, heaviest"))
        }
        qualityGroup.check(
            when (prefs.mode1Quality) {
                AppPrefs.QUALITY_BALANCED -> ID_Q_BALANCED
                AppPrefs.QUALITY_NATIVE -> ID_Q_NATIVE
                else -> ID_Q_SHARP
            }
        )
        qualityGroup.setOnCheckedChangeListener { _, id ->
            prefs.mode1Quality = when (id) {
                ID_Q_BALANCED -> AppPrefs.QUALITY_BALANCED
                ID_Q_NATIVE -> AppPrefs.QUALITY_NATIVE
                else -> AppPrefs.QUALITY_SHARP
            }
        }
        qualityBox.addView(qualityGroup)
        readyColumn.addView(qualityBox)
        // the quality only matters for Mode 1
        modeGroup.setOnCheckedChangeListener { _, id ->
            qualityBox.visibility = if (id == ID_MODE2) View.GONE else View.VISIBLE
        }

        launchButton = makeButton("Launch VS Code", primary = true) { launchSelected() }
        readyColumn.addView(launchButton)
        verifyButton = makeButton("Verify installation (VS Code, Git, node-pty, extensions)", primary = false) { runVerification() }
        readyColumn.addView(verifyButton)

        statusText = makeText("", 13f, GREEN).apply { setPadding(0, dp(14), 0, dp(6)) }
        readyColumn.addView(statusText)
        logView = makeLogText()
        readyColumn.addView(logView)
        readyScreen = ScrollView(this).apply { addView(readyColumn) }

        // ----- MODE 1 surface (WebView is created lazily) -----
        mode1Container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        for (v in listOf<View>(folderScreen, loadingScreen, readyScreen, mode1Container)) {
            v.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            rootFrame.addView(v)
        }
        setContentView(rootFrame)
    }

    companion object {
        private const val ID_MODE1 = 1001
        private const val ID_MODE2 = 1002
        private const val ID_Q_BALANCED = 1101
        private const val ID_Q_SHARP = 1102
        private const val ID_Q_NATIVE = 1103
        private const val BG = 0xFF1E1E1E.toInt()
        private const val ACCENT = 0xFF007ACC.toInt()
        private const val TEXT = 0xFFD4D4D4.toInt()
        private const val MUTED = 0xFF858585.toInt()
        private const val GREEN = 0xFF4EC9B0.toInt()
    }
}
