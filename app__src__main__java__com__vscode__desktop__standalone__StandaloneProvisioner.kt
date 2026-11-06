package com.vscode.desktop.standalone

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList

/** Real-time progress of the one-time setup (0..100). */
data class ProvisionProgress(val percent: Int, val step: String, val detail: String)

interface ProvisionListener {
    fun onProgress(progress: ProvisionProgress)
    fun onFinished(success: Boolean, error: String?)
}

/**
 * Unpacks the Debian ARM64 rootfs and the official Microsoft VS Code (linux-arm64) that the CI pre-bundled into
 * the APK assets.
 *
 * The CI normally bakes the whole environment (X11, VNC, git, compilers, ...) into the rootfs (asset marker
 * "rootfs-baked.flag"), so the phone only unpacks: rootfs 0-70 %, VS Code 70-95 %, finishing 95-100 %.
 * For a rootfs that is NOT baked, init-rootfs.sh still runs inside PRoot as before (apt, needs internet):
 * rootfs 0-15 %, VS Code 15-30 %, environment setup 30-100 %.
 * Every phase is resumable: a phase is skipped only when its completion marker exists, and an interrupted
 * extraction is wiped and redone.
 */
class StandaloneProvisioner(
    context: Context,
    private val prootManager: ProotProcessManager
) {
    private val context: Context = context.applicationContext

    // ---------------------------------------------------------------------------------------------
    // Background execution + listeners (survive Activity re-creation)
    // ---------------------------------------------------------------------------------------------

    /** Starts provisioning on a worker thread. Returns false if a run is already in progress. */
    fun startInBackground(): Boolean {
        synchronized(LOCK) {
            if (running) return false
            running = true
            lastError = null
            latest = ProvisionProgress(0, "Preparing…", "")
        }
        LinuxRuntimeService.start(context, LinuxRuntimeService.MODE_PROVISION)
        Thread({
            var ok = false
            var error: String? = null
            try {
                ok = provisionAll({ p -> publish(p) }, { line -> AppLog.add(line) })
                if (!ok) error = failure ?: "Provisioning failed. See the log for details."
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                AppLog.add("ERROR during provisioning: $error")
            }
            synchronized(LOCK) {
                running = false
                lastError = if (ok) null else error
                if (ok) latest = ProvisionProgress(100, "Setup complete", "Ready to launch")
            }
            LinuxRuntimeService.stop(context)
            for (l in listeners) l.onFinished(ok, if (ok) null else error)
        }, "pocket-provisioner").start()
        return true
    }

    private fun publish(p: ProvisionProgress) {
        latest = p
        for (l in listeners) l.onProgress(p)
        LinuxRuntimeService.updateProgress(context, p.percent, p.step)
    }

    @Volatile
    private var failure: String? = null

    /**
     * Blocking provisioning. Prefer [startInBackground]. Returns true when everything is installed.
     */
    fun provisionAll(onProgress: (ProvisionProgress) -> Unit, onLog: (String) -> Unit): Boolean {
        failure = null
        val reporter = Reporter(onProgress)
        val baked = findAsset(BAKED_FLAG) != null
        val wRootfs = if (baked) W_ROOTFS_BAKED else W_ROOTFS
        val wVscode = if (baked) W_VSCODE_BAKED else W_VSCODE
        val wEnv = 100 - wRootfs - wVscode
        return try {
            prootManager.isRootfsProvisioned() // adopts installs made by older builds

            // ---- 1/3 Debian rootfs --------------------------------------------------------------
            if (!prootManager.rootfsMarker.exists()) {
                reporter.update(0, STEP_1, "Preparing…", force = true)
                wipeDirectory(prootManager.rootfsDir)
                prootManager.homeDir.mkdirs()
                val asset = findAsset("debian-rootfs.tar")
                    ?: throw IOException("Pre-bundled debian-rootfs asset not found in the APK")
                onLog("[1/3] Extracting Debian rootfs from APK ($asset)")
                extractAsset(asset, prootManager.rootfsDir, StripMode.AUTO_ROOTFS, 0, wRootfs, STEP_1, reporter)
                prootManager.rootfsMarker.writeText("ok\n")
            } else {
                reporter.update(wRootfs, STEP_1, "Already installed", force = true)
                onLog("[1/3] Debian rootfs already installed.")
            }

            // ---- 2/3 Official VS Code -----------------------------------------------------------
            if (!prootManager.vscodeMarker.exists()) {
                reporter.update(wRootfs, STEP_2, "Preparing…", force = true)
                wipeDirectory(prootManager.vscodeDir)
                prootManager.vscodeDir.mkdirs()
                val asset = findAsset("vscode-linux-arm64")
                    ?: throw IOException("Pre-bundled vscode-linux-arm64 asset not found in the APK")
                onLog("[2/3] Extracting Microsoft VS Code from APK ($asset)")
                extractAsset(
                    asset, prootManager.vscodeDir, StripMode.STRIP_ONE,
                    wRootfs, wVscode, STEP_2, reporter
                )
                if (!File(prootManager.vscodeDir, "bin/code").exists()) {
                    throw IOException("VS Code archive did not contain bin/code")
                }
                prootManager.vscodeMarker.writeText("ok\n")
            } else {
                reporter.update(wRootfs + wVscode, STEP_2, "Already installed", force = true)
                onLog("[2/3] VS Code already installed.")
            }

            // ---- 3/3 Environment (init-rootfs.sh) -----------------------------------------------
            if (!prootManager.setupMarker.exists()) {
                onLog("[3/3] Configuring the Linux environment (internet required for this step)…")
                val base = wRootfs + wVscode
                reporter.update(base, STEP_3, "Starting package setup…", force = true)
                val env = EnvProgress(base, wEnv, reporter)
                val provisionWorkspace = File(prootManager.filesDir, "provision-workspace").apply { mkdirs() }
                val p = prootManager.startProotCommand(
                    command = "bash /root/init-rootfs.sh",
                    workspaceHostPath = provisionWorkspace.absolutePath,
                    displayMode = "x11",
                    onLogLine = { line ->
                        onLog(line)
                        env.onLine(line)
                    }
                )
                val exit = p.waitFor()
                if (exit != 0) throw IOException("Environment setup exited with code $exit (check your internet connection and retry)")
                if (!prootManager.setupMarker.exists()) throw IOException("Environment setup did not finish")
            } else {
                onLog("[3/3] Environment already configured" + if (baked) " (pre-installed in the bundled image)." else ".")
            }

            reporter.update(100, "Setup complete", "Ready to launch", force = true)
            onLog("SUCCESS! Genuine Microsoft VS Code (linux-arm64) is ready.")
            true
        } catch (e: Throwable) {
            failure = e.message ?: e.javaClass.simpleName
            onLog("ERROR during provisioning: $failure")
            false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Progress helpers
    // ---------------------------------------------------------------------------------------------

    private class Reporter(private val sink: (ProvisionProgress) -> Unit) {
        private var lastPercent = -1
        private var lastEmit = 0L

        fun update(percent: Int, step: String, detail: String, force: Boolean = false) {
            val p = percent.coerceIn(0, 100)
            val now = System.currentTimeMillis()
            if (force || p != lastPercent || now - lastEmit >= 250L) {
                lastPercent = p
                lastEmit = now
                sink(ProvisionProgress(p, step, detail))
            }
        }
    }

    /** Turns init-rootfs.sh output (stage markers + apt lines) into smooth 0-100 progress for phase 3. */
    private class EnvProgress(
        private val base: Int,
        private val span: Int,
        private val reporter: Reporter
    ) {
        private var stage = 1
        private var total = 0
        private var gets = 0
        private var unpacks = 0
        private var setups = 0
        private var indexLines = 0
        private var detail = "Updating package lists…"
        private val summary = Regex("""^(\d+) upgraded, (\d+) newly installed""")

        fun onLine(raw: String) {
            val line = raw.trim()
            if (line.startsWith("##STAGE|")) {
                val parts = line.split('|')
                stage = parts.getOrNull(1)?.toIntOrNull() ?: stage
                detail = parts.getOrNull(2) ?: detail
            } else {
                when (stage) {
                    1 -> if (line.startsWith("Get:") || line.startsWith("Hit:") || line.startsWith("Ign:")) indexLines++
                    2 -> {
                        val m = summary.find(line)
                        if (m != null) {
                            total = m.groupValues[1].toInt() + m.groupValues[2].toInt()
                        } else if (total > 0) {
                            when {
                                line.startsWith("Get:") -> gets++
                                line.startsWith("Unpacking ") -> {
                                    unpacks++
                                    detail = "Unpacking " + line.removePrefix("Unpacking ").substringBefore(' ')
                                }
                                line.startsWith("Setting up ") -> {
                                    setups++
                                    detail = "Configuring " + line.removePrefix("Setting up ").substringBefore(' ')
                                }
                            }
                        }
                    }
                }
            }
            reporter.update(base + (span * fraction()).toInt(), STEP_3, detail)
        }

        private fun fraction(): Double = when (stage) {
            1 -> 0.01 + minOf(0.06, indexLines * 0.01)
            2 -> {
                val f = if (total > 0) ((gets + unpacks + setups).toDouble() / (3.0 * total)).coerceIn(0.0, 1.0) else 0.0
                0.08 + 0.87 * f
            }
            3 -> 0.96
            else -> 1.0
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Asset + tar extraction
    // ---------------------------------------------------------------------------------------------

    private fun findAsset(prefix: String): String? =
        (context.assets.list("") ?: emptyArray()).firstOrNull { it.startsWith(prefix) }

    /** Size of the asset as the app will read it (the original .tar.gz/.tar.xz file length). */
    private fun assetLength(name: String): Long {
        try {
            context.assets.openFd(name).use { return it.length }
        } catch (_: Exception) {
        }
        return try {
            context.assets.open(name).use { it.available().toLong() }
        } catch (_: Exception) {
            -1L
        }
    }

    private fun extractAsset(
        assetName: String,
        destDir: File,
        mode: StripMode,
        base: Int,
        span: Int,
        step: String,
        reporter: Reporter
    ) {
        val total = assetLength(assetName)
        context.assets.open(assetName).use { raw ->
            val counting = CountingInputStream(raw)
            extractTar(counting, destDir, mode) { files, bytes ->
                val frac = if (total > 0) (bytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else 0.0
                val detail = if (total > 0) {
                    "$files files · ${bytes / MB} / ${total / MB} MB"
                } else {
                    "$files files extracted"
                }
                reporter.update(base + (span * frac).toInt(), step, detail)
            }
        }
        reporter.update(base + span, step, "Done", force = true)
    }

    private enum class StripMode { AUTO_ROOTFS, STRIP_ONE }

    /** Maps tar entry names to paths relative to the destination (handles ./ prefixes and a wrapper directory). */
    private class PathMapper(private val mode: StripMode) {
        private var wrapper: String? = null
        private var decided = false

        fun map(rawName: String, learn: Boolean): String? {
            var name = rawName.replace('\\', '/')
            while (name.startsWith("./")) name = name.substring(2)
            val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty()) return null

            val kept: List<String> = when (mode) {
                StripMode.STRIP_ONE -> parts.drop(1)
                StripMode.AUTO_ROOTFS -> {
                    if (learn && !decided) {
                        decided = true
                        val first = parts[0]
                        if (first !in STANDARD_DIRS && !first.startsWith(".")) wrapper = first
                    }
                    val w = wrapper
                    if (w != null && parts[0] == w) parts.drop(1) else parts
                }
            }
            return if (kept.isEmpty()) null else kept.joinToString("/")
        }
    }

    private fun extractTar(
        counting: CountingInputStream,
        destDir: File,
        mode: StripMode,
        onEntries: (files: Int, bytes: Long) -> Unit
    ) {
        destDir.mkdirs()
        val root: Path = destDir.canonicalFile.toPath().normalize()

        val pushback = PushbackInputStream(BufferedInputStream(counting, 256 * 1024), 16)
        val header = ByteArray(6)
        var n = 0
        while (n < header.size) {
            val r = pushback.read(header, n, header.size - n)
            if (r < 0) break
            n += r
        }
        if (n > 0) pushback.unread(header, 0, n)

        val isGz = n >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()
        val isXz = n >= 6 && header[0] == 0xFD.toByte() && header[1] == '7'.code.toByte() &&
            header[2] == 'z'.code.toByte() && header[3] == 'X'.code.toByte() &&
            header[4] == 'Z'.code.toByte() && header[5] == 0x00.toByte()

        val decompressed: InputStream = when {
            isXz -> XZCompressorInputStream(pushback)
            isGz -> GzipCompressorInputStream(pushback)
            else -> pushback
        }

        val mapper = PathMapper(mode)
        val buffer = ByteArray(128 * 1024)
        var files = 0
        var skipped = 0

        // One mkdirs() per directory instead of one per file (tar entries of a directory are contiguous).
        var lastParent: Path? = null
        fun ensureParent(target: Path) {
            val parent = target.parent ?: return
            if (parent != lastParent) {
                parent.toFile().mkdirs()
                lastParent = parent
            }
        }

        TarArchiveInputStream(decompressed).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val rel = mapper.map(entry.name, learn = true)
                if (rel != null) {
                    val target = safeResolve(root, rel)
                    try {
                        when {
                            entry.isDirectory -> target.toFile().mkdirs()

                            entry.isSymbolicLink -> {
                                ensureParent(target)
                                Files.deleteIfExists(target)
                                Files.createSymbolicLink(target, Paths.get(entry.linkName))
                            }

                            entry.isLink -> {
                                val linkRel = mapper.map(entry.linkName, learn = false)
                                if (linkRel != null) {
                                    val source = safeResolve(root, linkRel)
                                    ensureParent(target)
                                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                                    if (Files.isExecutable(source)) target.toFile().setExecutable(true, false)
                                }
                            }

                            entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> skipped++

                            else -> {
                                ensureParent(target)
                                if (Files.isSymbolicLink(target)) Files.delete(target)
                                FileOutputStream(target.toFile()).use { out ->
                                    while (true) {
                                        val read = tar.read(buffer)
                                        if (read < 0) break
                                        out.write(buffer, 0, read)
                                    }
                                }
                                if ((entry.mode and 0b001_001_001) != 0) target.toFile().setExecutable(true, false)
                            }
                        }
                    } catch (e: IOException) {
                        if (e.message?.startsWith("Path traversal") == true) throw e
                        skipped++ // a single un-creatable entry (e.g. exotic symlink) must not abort the install
                    }
                }
                files++
                if (files % 50 == 0) onEntries(files, counting.count)
                entry = tar.nextTarEntry
            }
        }
        onEntries(files, counting.count)
        if (skipped > 0) AppLog.add("Skipped $skipped special/unsupported tar entries.")
    }

    /** Lexical zip-slip guard: the resolved path must stay inside [root]. */
    private fun safeResolve(root: Path, relative: String): Path {
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) throw IOException("Path traversal rejected: $relative")
        return resolved
    }

    /** Deletes everything inside [dir] without ever following symlinks (rootfs symlinks may point at host paths). */
    private fun wipeDirectory(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    Files.deleteIfExists(file)
                } catch (_: IOException) {
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                if (d != dir.toPath()) {
                    try {
                        Files.deleteIfExists(d)
                    } catch (_: IOException) {
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
        dir.mkdirs()
    }

    /** Counts bytes consumed from the (compressed) asset stream — the basis of the extraction percentage. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var count: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n.toLong()
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) count += s
            return s
        }
    }

    companion object {
        private const val STEP_1 = "Step 1/3 · Extracting Debian Linux"
        private const val STEP_2 = "Step 2/3 · Extracting Visual Studio Code"
        private const val STEP_3 = "Step 3/3 · Setting up the environment"
        private const val BAKED_FLAG = "rootfs-baked"
        private const val W_ROOTFS = 15
        private const val W_VSCODE = 15
        private const val W_ROOTFS_BAKED = 70
        private const val W_VSCODE_BAKED = 25
        private const val MB = 1024L * 1024L

        private val STANDARD_DIRS = setOf(
            "bin", "sbin", "etc", "usr", "lib", "lib32", "lib64", "libx32", "var", "dev", "proc", "sys",
            "root", "home", "tmp", "opt", "run", "mnt", "media", "srv", "boot"
        )

        private val LOCK = Any()

        @Volatile
        private var running = false

        @Volatile
        private var latest: ProvisionProgress? = null

        @Volatile
        private var lastError: String? = null

        private val listeners = CopyOnWriteArrayList<ProvisionListener>()

        fun isRunning(): Boolean = running
        fun latestProgress(): ProvisionProgress? = latest
        fun lastError(): String? = lastError

        /** Registers [listener] and immediately replays the latest progress if a run is in progress. */
        fun addListener(listener: ProvisionListener) {
            listeners.addIfAbsent(listener)
            val p = latest
            if (running && p != null) listener.onProgress(p)
        }

        fun removeListener(listener: ProvisionListener) {
            listeners.remove(listener)
        }
    }
}
