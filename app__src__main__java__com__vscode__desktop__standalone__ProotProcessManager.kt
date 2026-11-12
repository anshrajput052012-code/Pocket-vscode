package com.vscode.desktop.standalone

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Process-wide, bounded log shared by the provisioner, the PRoot sessions and the UI.
 * (Activities come and go; the log survives for as long as the app process lives.)
 */
object AppLog {
    private const val MAX_LINES = 600
    private val lines = ArrayDeque<String>()

    @Volatile
    var listener: (() -> Unit)? = null

    fun add(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        listener?.invoke()
    }

    fun tail(count: Int): List<String> = synchronized(lines) { lines.toList().takeLast(count) }
}

/** Tiny SharedPreferences wrapper (selected project folder, one-time prompts, last mode). */
class AppPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("pocket_vscode", Context.MODE_PRIVATE)

    var workspace: String?
        get() = sp.getString("workspace_path", null)
        set(value) {
            sp.edit().putString("workspace_path", value).apply()
        }

    var lastMode: String
        get() = sp.getString("last_mode", "x11") ?: "x11"
        set(value) {
            sp.edit().putString("last_mode", value).apply()
        }

    var askedNotificationPermission: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(value) {
            sp.edit().putBoolean("asked_notif", value).apply()
        }

    /** Mode 1 screen quality: [QUALITY_BALANCED], [QUALITY_SHARP] (default) or [QUALITY_NATIVE]. */
    var mode1Quality: String
        get() = when (val v = sp.getString("mode1_quality", QUALITY_SHARP) ?: QUALITY_SHARP) {
            QUALITY_BALANCED, QUALITY_SHARP, QUALITY_NATIVE -> v
            else -> QUALITY_SHARP
        }
        set(value) {
            sp.edit().putString("mode1_quality", value).apply()
        }

    /** Mode 1 viewer: [VIEWER_NATIVE] (default: built-in viewer, real Android copy/paste) or [VIEWER_WEB] (noVNC in a WebView). */
    var mode1Viewer: String
        get() = when (val v = sp.getString("mode1_viewer", VIEWER_NATIVE) ?: VIEWER_NATIVE) {
            VIEWER_NATIVE, VIEWER_WEB -> v
            else -> VIEWER_NATIVE
        }
        set(value) {
            sp.edit().putString("mode1_viewer", value).apply()
        }

    /** Mode 1 runs on the big CPU cores only (faster and smoother, a little more battery). */
    var mode1FastCores: Boolean
        get() = sp.getBoolean("mode1_fast_cores", true)
        set(value) {
            sp.edit().putBoolean("mode1_fast_cores", value).apply()
        }

    /** The one-time gesture hint of the native viewer has been shown. */
    var shownNativeHint: Boolean
        get() = sp.getBoolean("shown_native_hint", false)
        set(value) {
            sp.edit().putBoolean("shown_native_hint", value).apply()
        }

    companion object {
        const val VIEWER_NATIVE = "native"
        const val VIEWER_WEB = "web"

        const val QUALITY_BALANCED = "balanced"
        const val QUALITY_SHARP = "sharp"
        const val QUALITY_NATIVE = "native"

        fun defaultWorkspace(): String =
            File(Environment.getExternalStorageDirectory(), "VSCodeProjects").absolutePath
    }
}

/** Mode 1 (Xtigervnc + Openbox + noVNC + full Electron desktop) — intentionally NOT throttled. */
object Mode1Session {
    @Volatile
    var process: Process? = null

    /** True from the moment the user taps Launch until the process exists (keeps the service alive meanwhile). */
    @Volatile
    var starting: Boolean = false

    val isAlive: Boolean
        get() = process?.isAlive == true

    /** Clears the handle only if [p] is still the current session. Returns true when it was. */
    @Synchronized
    fun clearIfCurrent(p: Process): Boolean {
        if (process === p) {
            process = null
            return true
        }
        return false
    }

    fun stop() {
        val p = process
        process = null
        if (p != null) killProcess(p)
    }
}

/**
 * Mode 2 (official `code serve-web` on 127.0.0.1:8080) controller.
 *
 * Battery model: the guest-side watcher in start-vscode-session.sh hibernates (SIGTERM) the server once the app
 * has stopped refreshing the heartbeat file for 10 minutes. The heartbeat is refreshed by [LinuxRuntimeService]
 * only while a Mode 2 client (WorkbenchActivity in the foreground, or an external-browser session) is attached.
 * /proc/net is not readable by apps targeting API 29+, so socket-based idle detection cannot work on Android.
 */
object Mode2Server {
    const val PORT = 8080
    const val URL = "http://127.0.0.1:8080/?folder=/root/Workspace"
    const val HEARTBEAT_NAME = ".pocket-mode2-heartbeat"
    private const val EXTERNAL_SESSION_MAX_MS = 2L * 60 * 60 * 1000

    @Volatile
    var process: Process? = null

    /** Incremented every time a fresh server process is started (WebView reloads when it changes). */
    @Volatile
    var epoch: Int = 0
        private set

    @Volatile
    var foreground: Boolean = false

    @Volatile
    private var externalSessionSince: Long = 0L

    val isProcessAlive: Boolean
        get() = process?.isAlive == true

    val externalSession: Boolean
        get() {
            val since = externalSessionSince
            return since != 0L && System.currentTimeMillis() - since < EXTERNAL_SESSION_MAX_MS
        }

    val clientActive: Boolean
        get() = foreground || externalSession

    fun markExternalSession() {
        externalSessionSince = System.currentTimeMillis()
    }

    fun clearExternalSession() {
        externalSessionSince = 0L
    }

    /** True once the server answers HTTP on 127.0.0.1:8080 (a bare TCP accept is not enough on first run). */
    fun isServerLive(timeoutMs: Int = 1500): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://127.0.0.1:$PORT/").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.responseCode in 100..499
        } catch (_: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun touchHeartbeat(manager: ProotProcessManager) {
        try {
            val f = File(manager.tmpDir, HEARTBEAT_NAME)
            if (!f.exists()) f.createNewFile()
            f.setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    /** Starts the server (no-op if already running). Safe to call from any thread. */
    @Synchronized
    fun start(manager: ProotProcessManager, workspaceHostPath: String): Process {
        process?.let { if (it.isAlive) return it }

        manager.ensureSharedState()
        manager.ensureMode2Settings()
        touchHeartbeat(manager)
        val p = manager.startProotCommand(
            command = "bash /root/start-vscode-session.sh direct",
            workspaceHostPath = workspaceHostPath,
            displayMode = "direct",
            onLogLine = { line -> AppLog.add(line) }
        )
        process = p
        epoch += 1
        Thread({
            try {
                p.waitFor()
            } catch (_: InterruptedException) {
            }
            synchronized(this) {
                if (process === p) {
                    process = null
                    clearExternalSession()
                }
            }
            AppLog.add("[mode2] VS Code server stopped (hibernated or closed).")
            manager.releaseServiceIfIdle()
        }, "mode2-exit-watcher").start()
        return p
    }

    fun stop() {
        val p = process
        process = null
        clearExternalSession()
        if (p != null) killProcess(p)
    }
}

private fun killProcess(p: Process) {
    p.destroy()
    Thread({
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Exception) {
        }
    }, "proot-reaper").start()
}

/**
 * Self-contained Linux Userspace Execution Engine.
 * Executes commands inside an embedded Debian ARM64 rootfs using bundled libproot.so
 * from nativeLibraryDir — requiring ZERO Termux installation.
 */
class ProotProcessManager(context: Context) {

    private val context: Context = context.applicationContext

    val nativeLibDir: File = File(this.context.applicationInfo.nativeLibraryDir)
    val filesDir: File = this.context.filesDir
    val rootfsDir: File = File(filesDir, "rootfs")
    val tmpDir: File = File(filesDir, "tmp")
    val homeDir: File = File(rootfsDir, "root")

    val prootBinary: File = File(nativeLibDir, "libproot.so")
    val prootLoader: File = File(nativeLibDir, "libproot-loader.so")

    /** Written only after the corresponding extraction finished completely (crash-safe resume). */
    val rootfsMarker: File = File(rootfsDir, ".pocket-rootfs-extracted")
    val vscodeDir: File = File(rootfsDir, "opt/vscode-arm64")
    val vscodeMarker: File = File(vscodeDir, ".pocket-vscode-extracted")

    /** Written by init-rootfs.sh as its very last command. */
    val setupMarker: File = File(homeDir, ".pocket-setup-done")

    init {
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()
        if (!homeDir.exists()) homeDir.mkdirs()
    }

    /**
     * Installs from older builds had no markers. If the rootfs, VS Code and the init script's last artifact
     * (argv.json) are all there, adopt that installation instead of wiping and re-extracting it.
     */
    private fun adoptLegacyInstall() {
        if (rootfsMarker.exists() || vscodeMarker.exists() || setupMarker.exists()) return
        val bash = File(rootfsDir, "bin/bash")
        val code = File(vscodeDir, "bin/code")
        val argv = File(homeDir, ".vscode/argv.json")
        if (bash.exists() && code.exists() && argv.exists()) {
            try {
                rootfsMarker.writeText("legacy\n")
                vscodeMarker.writeText("legacy\n")
                setupMarker.writeText("legacy\n")
            } catch (_: Exception) {
            }
        }
    }

    fun isRootfsProvisioned(): Boolean {
        adoptLegacyInstall()
        return rootfsMarker.exists() && vscodeMarker.exists() && setupMarker.exists()
    }

    /** Copies the launcher scripts from the APK into the rootfs (always, so app updates ship script fixes). */
    fun syncAssetScripts() {
        try {
            val dir = File(rootfsDir, "root").apply { mkdirs() }
            for (name in ASSET_SCRIPTS) {
                val out = File(dir, name)
                val text = context.assets.open(name).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                    .replace("\r\n", "\n")
                FileOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                out.setExecutable(true, false)
            }
        } catch (e: Exception) {
            AppLog.add("Warning: could not sync launcher scripts: ${e.message}")
        }
    }

    /**
     * Returns a usable project directory. If the requested one cannot be written (e.g. "All files access" was
     * declined) the app-specific external folder is used instead so PRoot can always bind a workspace.
     */
    fun effectiveWorkspace(requested: String): File {
        val primary = File(requested)
        try {
            if (!primary.exists()) primary.mkdirs()
        } catch (_: Exception) {
        }
        if (primary.isDirectory && primary.canWrite()) return primary

        val fallback = File(context.getExternalFilesDir(null) ?: filesDir, "Workspace").apply { mkdirs() }
        AppLog.add(
            "Project folder '$requested' is not writable (grant 'All files access'); " +
                "using ${fallback.absolutePath} instead."
        )
        return fallback
    }

    private fun writeEtcFile(name: String, text: String) {
        try {
            val etc = File(rootfsDir, "etc").apply { mkdirs() }
            val f = File(etc, name)
            // rootfs images may ship /etc/resolv.conf as an absolute symlink that does not exist on the host
            if (Files.isSymbolicLink(f.toPath())) Files.delete(f.toPath())
            f.writeText(text)
        } catch (_: Exception) {
        }
    }

    private val supportsSysvipc: Boolean by lazy {
        try {
            String(prootBinary.readBytes(), Charsets.ISO_8859_1).contains("--sysvipc")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Mode 1 and Mode 2 share settings, keybindings, snippets and extensions: Mode 2's server data folders are
     * symbolic links to the desktop (Mode 1) folders. The links are relative, so they resolve on the phone and
     * inside PRoot alike. The app runs only one mode at a time, so the shared databases are never open twice.
     * Anything Mode 2 already had is kept (moved over, or set aside as ".pre-share-…").
     */
    fun ensureSharedState() {
        try {
            val sharedUser = File(homeDir, ".config/Code/User").apply { mkdirs() }
            val sharedExtensions = File(homeDir, ".vscode/extensions").apply { mkdirs() }
            File(homeDir, ".vscode-server-data/data").mkdirs()
            linkToShared(File(homeDir, ".vscode-server-data/data/User"), "../../.config/Code/User", sharedUser)
            linkToShared(File(homeDir, ".vscode-server-data/extensions"), "../.vscode/extensions", sharedExtensions)
        } catch (e: Exception) {
            AppLog.add("Warning: could not share settings/extensions between the modes: ${e.message}")
        }
    }

    private fun linkToShared(link: File, relativeTarget: String, shared: File) {
        val path = link.toPath()
        if (Files.isSymbolicLink(path)) return
        if (link.exists()) {
            val movedOver = link.isDirectory && shared.list().isNullOrEmpty() && shared.delete() && link.renameTo(shared)
            if (!movedOver) {
                shared.mkdirs()
                if (!link.renameTo(File(link.parentFile, link.name + ".pre-share-" + System.currentTimeMillis()))) {
                    AppLog.add("Warning: ${link.name} could not be shared between the modes (kept as it is).")
                    return
                }
            }
        }
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(path, Paths.get(relativeTarget))
    }

    // ---------------------------------------------------------------------------------------------
    // Storage housekeeping. Only regenerable leftovers are removed: old Mode 2 server downloads (the newest is always
    // kept), old logs, download and compiler caches. Settings, extensions, projects and Mode 2's behaviour are untouched.
    // ---------------------------------------------------------------------------------------------

    /** Deletes a file or folder (never following symbolic links). Returns the bytes freed. */
    private fun deleteTree(root: File): Long {
        var freed = 0L
        val start = root.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return 0L
        try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    dir.toFile().setWritable(true, true)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    try {
                        val size = attrs.size()
                        Files.delete(file)
                        freed += size
                    } catch (_: IOException) {
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    try {
                        Files.delete(dir)
                    } catch (_: IOException) {
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            AppLog.add("Cleanup: ${e.message}")
        }
        return freed
    }

    /** Deletes the entries of [dir] that were last changed more than [olderThanMs] ago (0 = all of them). */
    private fun deleteChildren(dir: File, olderThanMs: Long): Long {
        val kids = dir.listFiles() ?: return 0L
        val cutoff = System.currentTimeMillis() - olderThanMs
        var freed = 0L
        for (k in kids) if (olderThanMs <= 0L || k.lastModified() < cutoff) freed += deleteTree(k)
        return freed
    }

    /**
     * `code serve-web` (Mode 2) keeps up to five downloaded server versions (a few hundred MB each) and never uses the
     * old ones again. Everything except the newest version is deleted once it is [minAgeMs] old. Skipped while Mode 2
     * is running. The CLI copes with missing folders (it simply downloads again if it ever needs one).
     */
    fun pruneOldServerVersions(minAgeMs: Long): Long {
        if (Mode2Server.isProcessAlive || Mode2Server.externalSession) return 0L
        val base = File(homeDir, ".vscode/cli/serve-web")
        val all = base.listFiles() ?: return 0L
        val hex = Regex("[0-9a-f]{40}")
        val versions = all.filter { it.isDirectory && hex.matches(it.name) }
        val keep = HashSet<String>()
        try {
            val lru = JSONArray(File(base, "lru.json").readText())
            if (lru.length() > 0) keep.add(lru.getString(0))
        } catch (_: Exception) {
        }
        versions.maxByOrNull { it.lastModified() }?.let { keep.add(it.name) }
        val cutoff = System.currentTimeMillis() - minAgeMs
        var freed = 0L
        for (v in versions) {
            if (v.name !in keep && v.lastModified() < cutoff) freed += deleteTree(v)
        }
        // half-finished downloads of earlier attempts
        for (f in all) {
            if (f.name.contains(".staging-") && f.lastModified() < System.currentTimeMillis() - 24L * 3600 * 1000) {
                freed += deleteTree(f)
            }
        }
        if (freed > 0) AppLog.add("Cleanup: removed old Mode 2 server downloads (${freed / (1024 * 1024)} MB).")
        return freed
    }

    /** Quiet, automatic part: old Mode 2 server versions (a day old or more, the newest stays) and logs older than three days. */
    fun autoHousekeeping() {
        try {
            var freed = pruneOldServerVersions(24L * 3600 * 1000)
            val day = 24L * 3600 * 1000
            freed += deleteChildren(File(homeDir, ".config/Code/logs"), 3 * day)
            freed += deleteChildren(File(homeDir, ".vscode-server-data/data/logs"), 3 * day)
            freed += deleteChildren(File(homeDir, ".config/Code/CachedExtensionVSIXs"), day)
            freed += deleteChildren(File(homeDir, ".npm/_logs"), 7 * day)
            freed += trimElectronLocales()
            freed += dedupeVsCodeFiles()
            if (freed > 0) AppLog.add("Cleanup: freed ${freed / (1024 * 1024)} MB of old leftovers.")
        } catch (e: Exception) {
            AppLog.add("Cleanup skipped: ${e.message}")
        }
    }

    /**
     * The "Free up space" button: everything in [autoHousekeeping] right away, plus download / compiler caches and, while
     * Mode 1 is not running, VS Code's own caches (they are rebuilt on the next start). Returns the bytes freed.
     */
    fun freeUpSpace(): Long {
        var freed = 0L
        try {
            freed += pruneOldServerVersions(0L)
            freed += trimElectronLocales()
            freed += dedupeVsCodeFiles()
            freed += deleteChildren(File(homeDir, ".config/Code/logs"), 0L)
            freed += deleteChildren(File(homeDir, ".config/Code/CachedExtensionVSIXs"), 0L)
            freed += deleteChildren(File(homeDir, ".config/Code/Crashpad"), 0L)
            freed += deleteChildren(File(homeDir, ".npm/_logs"), 0L)
            freed += deleteTree(File(homeDir, ".npm/_cacache"))
            freed += deleteTree(File(homeDir, ".cache/pip"))
            freed += deleteTree(File(homeDir, ".cache/node-gyp"))
            if (!Mode2Server.isProcessAlive) freed += deleteChildren(File(homeDir, ".vscode-server-data/data/logs"), 0L)
            if (!Mode1Session.isAlive) {
                for (n in listOf("Cache", "Code Cache", "GPUCache", "DawnCache", "CachedData")) {
                    freed += deleteTree(File(homeDir, ".config/Code/$n"))
                }
            }
            freed += deleteChildren(context.cacheDir, 0L)
            // package-manager download cache, trash and tool caches inside the Linux system (all rebuilt on demand)
            freed += deleteTree(File(rootfsDir, "var/cache/apt"))
            freed += deleteTree(File(homeDir, ".local/share/Trash"))
            if (!Mode1Session.isAlive && !Mode2Server.isProcessAlive) freed += deleteChildren(File(homeDir, ".cache"), 0L)
            // the Mode 2 web page's own caches (the page is fetched again from the local server)
            if (!Mode2Server.isProcessAlive) {
                val wv = File(context.applicationInfo.dataDir, "app_webview/Default")
                for (n in listOf("Cache", "Code Cache", "GPUCache", "Service Worker/CacheStorage", "Service Worker/ScriptCache")) {
                    freed += deleteTree(File(wv, n))
                }
            }
        } catch (e: Exception) {
            AppLog.add("Free up space: ${e.message}")
        }
        AppLog.add("Free up space: ${freed / (1024 * 1024)} MB freed.")
        return freed
    }

    /**
     * Mode 2's downloaded server contains most of the same files as the VS Code desktop in /opt (same built-in
     * extensions, same JavaScript for unchanged parts). Identical files (same content, same permissions) are replaced by
     * hard links to one copy: nothing changes for the programs, the space is only used once. Never runs while VS Code is
     * running; every file is swapped with an atomic rename. Returns the bytes saved.
     */
    fun dedupeVsCodeFiles(): Long {
        if (Mode1Session.isAlive || Mode2Server.isProcessAlive || Mode2Server.externalSession) return 0L
        if (!vscodeMarker.exists()) return 0L
        val serveWeb = File(homeDir, ".vscode/cli/serve-web")
        val hex = Regex("[0-9a-f]{40}")
        val serverDirs = (serveWeb.listFiles() ?: emptyArray()).filter { it.isDirectory && hex.matches(it.name) }.sortedBy { it.name }
        val signature = "v1;" + vscodeMarker.lastModified() + ";" + serverDirs.joinToString(",") { it.name + ":" + it.lastModified() }
        val stateFile = File(homeDir, ".pocket-dedupe-state")
        try {
            if (stateFile.exists() && stateFile.readText() == signature) return 0L
        } catch (_: Exception) {
        }

        class Item(val path: Path, val inode: Long, val mode: Int, val links: Long)

        val bySize = HashMap<Long, MutableList<Item>>()
        for (root in listOf(vscodeDir) + serverDirs) {
            if (!root.exists()) continue
            try {
                Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile && attrs.size() >= 16 * 1024) {
                            try {
                                val st = android.system.Os.lstat(file.toString())
                                bySize.getOrPut(attrs.size()) { ArrayList() }.add(Item(file, st.st_ino, st.st_mode and 4095, st.st_nlink))
                            } catch (_: Throwable) {
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            } catch (_: Exception) {
            }
        }

        fun sha256(path: Path): String? = try {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { String.format("%02x", it) }
        } catch (_: Exception) {
            null
        }

        var saved = 0L
        var failures = 0
        var linked = 0
        outer@ for ((size, items) in bySize) {
            if (items.size < 2 || items.map { it.inode }.toSet().size < 2) continue
            val groups = HashMap<String, MutableList<Item>>()
            for (item in items) {
                val h = sha256(item.path) ?: continue
                groups.getOrPut(h + "/" + item.mode) { ArrayList() }.add(item)
            }
            for (g in groups.values) {
                if (g.size < 2) continue
                val keeper = g[0]
                for (other in g.drop(1)) {
                    if (other.inode == keeper.inode) continue
                    val tmp = other.path.resolveSibling(other.path.fileName.toString() + ".pocket-link")
                    try {
                        Files.deleteIfExists(tmp)
                        Files.createLink(tmp, keeper.path)
                        Files.move(tmp, other.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        if (other.links <= 1L) saved += size
                        linked++
                    } catch (_: Exception) {
                        try {
                            Files.deleteIfExists(tmp)
                        } catch (_: Exception) {
                        }
                        failures++
                        if (linked == 0 && failures >= 5) break@outer // hard links are not allowed here: stop trying
                    }
                }
            }
        }
        try {
            stateFile.writeText(signature)
        } catch (_: Exception) {
        }
        if (saved > 0) AppLog.add("Cleanup: identical VS Code files now share space ($linked files, ${saved / (1024 * 1024)} MB saved).")
        return saved
    }

    /** VS Code (Electron) falls back to en-US when a language file is missing: the other ~45 MB of translations are not needed. */
    fun trimElectronLocales(): Long {
        val dir = File(vscodeDir, "locales")
        val marker = File(vscodeDir, ".pocket-locales-trimmed")
        if (marker.exists() || !File(dir, "en-US.pak").exists()) return 0L
        var freed = 0L
        for (f in dir.listFiles() ?: emptyArray()) {
            if (f.isFile && f.name.endsWith(".pak") && f.name != "en-US.pak") {
                val len = f.length()
                if (f.delete()) freed += len
            }
        }
        try {
            marker.writeText("1\n")
        } catch (_: Exception) {
        }
        if (freed > 0) AppLog.add("Cleanup: removed unused Electron translations (${freed / (1024 * 1024)} MB).")
        return freed
    }

    /**
     * "What is using the space": the app file, the Linux system's biggest folders (down to three levels) and the other
     * app folders, largest first. Takes a few seconds (one walk over the files), so call it off the main thread.
     */
    fun storageReport(): List<String> {
        val mb = 1024L * 1024
        // what the file really occupies on the storage (whole 4 KB blocks), which is what Android's "app size" counts
        fun onDisk(file: Path, attrs: BasicFileAttributes): Long = try {
            android.system.Os.lstat(file.toString()).st_blocks * 512L
        } catch (_: Throwable) {
            attrs.size()
        }
        val sizes = HashMap<String, Long>()
        val bigFiles = ArrayList<Pair<Long, String>>()
        var total = 0L
        val rootPath = rootfsDir.toPath()
        try {
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        val disk = onDisk(file, attrs)
                        val rel = rootPath.relativize(file)
                        val top = rel.getName(0).toString()
                        val dirs = minOf(rel.nameCount - 1, if (top == "opt" || top == "root") 5 else 3)
                        if (disk >= 8 * mb) {
                            bigFiles.add(disk to "/" + rel.toString())
                            if (bigFiles.size > 400) {
                                bigFiles.sortByDescending { it.first }
                                while (bigFiles.size > 40) bigFiles.removeAt(bigFiles.size - 1)
                            }
                        }
                        val key = StringBuilder()
                        for (i in 0 until dirs) {
                            key.append('/').append(rel.getName(i).toString())
                            sizes[key.toString()] = (sizes[key.toString()] ?: 0L) + disk
                        }
                        total += disk
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (e: Exception) {
            AppLog.add("Storage report: ${e.message}")
        }

        fun treeSize(dir: File): Long {
            var n = 0L
            if (!dir.exists()) return 0L
            try {
                Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) n += onDisk(file, attrs)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            } catch (_: Exception) {
            }
            return n
        }

        val out = ArrayList<String>()
        val apk = File(context.applicationInfo.sourceDir).length()
        val dataDir = File(context.applicationInfo.dataDir)
        val otherFiles = treeSize(filesDir) - total
        out.add("== Storage report ==")
        out.add("App file (APK, cannot be reduced): ${apk / mb} MB")
        out.add("Linux system + VS Code + your files, together: ${total / mb} MB")
        out.add("Other app files: ${otherFiles / mb} MB · web page data: ${treeSize(File(dataDir, "app_webview")) / mb} MB · cache: ${treeSize(context.cacheDir) / mb} MB")
        out.add("-- biggest folders inside the Linux system (MB on disk) --")
        sizes.entries.filter { it.value >= 20 * mb }.sortedByDescending { it.value }.take(60)
            .forEach { out.add(String.format("%6d  %s", it.value / mb, it.key)) }
        out.add("-- biggest single files (MB) --")
        bigFiles.sortedByDescending { it.first }.take(30).forEach { out.add(String.format("%6d  %s", it.first / mb, it.second)) }
        return out
    }

    /** Mode 1: tells the start script whether to keep the desktop on the big CPU cores. */
    fun writeMode1FastCores(on: Boolean) {
        try {
            homeDir.mkdirs()
            File(homeDir, ".pocket-fastcores").writeText(if (on) "1\n" else "0\n")
        } catch (e: Exception) {
            AppLog.add("Warning: could not store the fast-cores choice: ${e.message}")
        }
    }

    /** Mode 1: tells the start script whether the app shows the desktop itself (native: no noVNC / websockify needed). */
    fun writeMode1Viewer(native: Boolean) {
        try {
            homeDir.mkdirs()
            File(homeDir, ".pocket-viewer").writeText(if (native) "native\n" else "web\n")
        } catch (e: Exception) {
            AppLog.add("Warning: could not store the Mode 1 viewer choice: ${e.message}")
        }
    }

    /**
     * Mode 1: tells the start script which virtual screen size to use (read from /root/.pocket-geometry).
     * Takes effect the next time Mode 1 is started.
     */
    fun writeMode1Geometry(width: Int, height: Int) {
        try {
            homeDir.mkdirs()
            File(homeDir, ".pocket-geometry").writeText("${width}x${height}\n")
        } catch (e: Exception) {
            AppLog.add("Warning: could not store the Mode 1 screen size: ${e.message}")
        }
    }

    /**
     * Mode 2 only: makes sure the server-side settings files carry 0.5 s auto-save, hot exit and window restore
     * so a closed/hibernated session reopens on the same file, tab and line. Existing user settings are kept.
     */
    fun ensureMode2Settings() {
        val base = File(rootfsDir, "root/.vscode-server-data/data")
        for (sub in listOf("Machine", "User")) {
            try {
                val dir = File(base, sub).apply { mkdirs() }
                mergeMode2Settings(File(dir, "settings.json"))
            } catch (e: Exception) {
                AppLog.add("Warning: could not write Mode 2 $sub settings: ${e.message}")
            }
        }
    }

    private fun mergeMode2Settings(file: File) {
        val obj = readJsonLenient(file) ?: JSONObject()

        // forced
        obj.put("files.autoSave", "afterDelay")
        obj.put("files.autoSaveDelay", 500)
        obj.put("files.hotExit", "onExitAndWindowClose")
        obj.put("window.restoreWindows", "all")

        // defaults (never override a value the user chose)
        if (!obj.has("workbench.startupEditor")) obj.put("workbench.startupEditor", "none")
        if (!obj.has("telemetry.telemetryLevel")) obj.put("telemetry.telemetryLevel", "off")
        if (!obj.has("extensions.autoCheckUpdates")) obj.put("extensions.autoCheckUpdates", false)
        if (!obj.has("extensions.autoUpdate")) obj.put("extensions.autoUpdate", false)
        if (!obj.has("update.mode")) obj.put("update.mode", "none")
        if (!obj.has("files.watcherExclude")) {
            val watcher = JSONObject()
            watcher.put("**/.git/objects/**", true)
            watcher.put("**/.git/subtree-cache/**", true)
            watcher.put("**/node_modules/*/**", true)
            watcher.put("**/build/**", true)
            watcher.put("**/.gradle/**", true)
            obj.put("files.watcherExclude", watcher)
        }

        file.writeText(obj.toString(2) + "\n")
    }

    /** Parses settings.json, tolerating VS Code's comments / trailing commas. Unparseable files are backed up. */
    private fun readJsonLenient(file: File): JSONObject? {
        if (!file.exists()) return null
        val text = file.readText()
        if (text.isBlank()) return null
        try {
            return JSONObject(text)
        } catch (_: JSONException) {
        }
        try {
            return JSONObject(stripJsonComments(text))
        } catch (_: JSONException) {
        }
        try {
            file.copyTo(File(file.parentFile, file.name + ".bak-" + System.currentTimeMillis()), overwrite = true)
        } catch (_: Exception) {
        }
        return null
    }

    private fun stripJsonComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                inString = true
                sb.append(c)
                i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().replace(Regex(",(\\s*[}\\]])"), "$1")
    }

    /** Stops the foreground service once neither session nor the provisioner needs it. */
    fun releaseServiceIfIdle() {
        if (!isAnySessionAlive() && !StandaloneProvisioner.isRunning()) {
            LinuxRuntimeService.stop(context)
        }
    }

    /**
     * Spawns a command inside the self-contained ARM64 Linux environment via libproot.so.
     */
    @Throws(IOException::class)
    fun startProotCommand(
        command: String,
        workspaceHostPath: String,
        displayMode: String = "x11",
        onLogLine: (String) -> Unit
    ): Process {
        if (!prootBinary.exists()) {
            throw IOException("libproot.so is missing from ${nativeLibDir.absolutePath} (APK built without native binaries?)")
        }
        val workspaceFile = effectiveWorkspace(workspaceHostPath)
        syncAssetScripts()

        // Ensure basic /etc/resolv.conf and /etc/hosts exist inside rootfs
        writeEtcFile("resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        writeEtcFile("hosts", "127.0.0.1 localhost\n::1 localhost\n")

        val cmdList = mutableListOf(
            prootBinary.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${tmpDir.absolutePath}:/tmp",
            "-b", "${tmpDir.absolutePath}:/dev/shm",
            "-b", "${workspaceFile.absolutePath}:/root/Workspace",
            "-w", "/root/Workspace",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/opt/vscode-arm64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "XDG_RUNTIME_DIR=/tmp",
            "ELECTRON_DISABLE_SANDBOX=1",
            "DISPLAY=:1",
            "VSCODE_MODE=$displayMode",
            "/bin/bash", "-c", command
        )

        // SysV shared memory emulation (Android blocks the real one). X11 / Electron need it; only used when this
        // proot build actually offers the option, so an older binary can never fail to start because of it.
        if (supportsSysvipc) cmdList.add(1, "--sysvipc")

        val pb = ProcessBuilder(cmdList)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = prootLoader.absolutePath
        env["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
        pb.redirectErrorStream(true)

        val process = pb.start()
        Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        try {
                            onLogLine(line)
                        } catch (_: Exception) {
                        }
                        line = reader.readLine()
                    }
                }
            } catch (_: IOException) {
                // stream closed because the process was destroyed — expected
            }
        }, "proot-log-reader").start()

        return process
    }

    companion object {
        val ASSET_SCRIPTS: List<String> = listOf("init-rootfs.sh", "start-vscode-session.sh", "verify-vscode.sh")

        fun isAnySessionAlive(): Boolean = Mode1Session.isAlive || Mode1Session.starting || Mode2Server.isProcessAlive
    }
}
