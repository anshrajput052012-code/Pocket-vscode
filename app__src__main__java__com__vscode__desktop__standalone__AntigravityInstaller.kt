package com.vscode.desktop.standalone

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Mode 3: installs the Antigravity IDE (Google's VS Code based editor, Linux ARM64 tarball) into the Linux system as
 * /opt/antigravity. Nothing of it is bundled in the APK. The tarball is either found in the project folder (the user can
 * drop "Antigravity IDE.tar.gz" there) or downloaded from Google (resumable). Plain JDK + commons-compress only - no
 * Android calls - so the whole thing can be tested on a normal JVM.
 */
object AntigravityInstaller {

    /** Where the IDE lives inside the Linux system. */
    const val GUEST_DIR = "/opt/antigravity"

    // The release listed on https://antigravity.google/download?os=linux when this was written; used when the page cannot
    // be read. The address of the newest release is taken from that page first.
    private const val PINNED_URL =
        "https://edgedl.me.gvt1.com/edgedl/release2/j0qc3/antigravity/stable/2.7.0-4507555968450560/linux-arm/Antigravity%20IDE.tar.gz"
    private const val DOWNLOAD_PAGE = "https://antigravity.google/download?os=linux"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux aarch64) PocketVSCode"
    private const val MIN_TARBALL_BYTES = 50L * 1024 * 1024
    private const val MB = 1024L * 1024
    private val ALLOWED_URL_PREFIXES = listOf(
        "https://edgedl.me.gvt1.com/",
        "https://storage.googleapis.com/antigravity-public/"
    )
    private val URL_REGEX = Regex(
        """https://edgedl\.me\.gvt1\.com/edgedl/release2/[A-Za-z0-9_./-]+/linux-arm/Antigravity%20IDE\.tar\.gz"""
    )
    private const val ELF_MACHINE_ARM64 = 183
    private const val ELF_MACHINE_X86_64 = 62

    /** A failure with a message that is fit to be shown on the screen. */
    class InstallException(message: String) : Exception(message)

    fun installDir(rootfsDir: File): File = File(rootfsDir, "opt/antigravity")

    private fun marker(rootfsDir: File): File = File(installDir(rootfsDir), ".pocket-installed")

    fun isInstalled(rootfsDir: File): Boolean = marker(rootfsDir).isFile

    /**
     * Installs the IDE. [projectDir] is searched for a tarball first; otherwise it is downloaded into [tmpDir] (and removed
     * again after unpacking). [onStatus] gets short progress lines, [log] the details. [urlOverride] and [minTarballBytes]
     * are for tests.
     */
    fun install(
        rootfsDir: File,
        tmpDir: File,
        projectDir: File?,
        onStatus: (String) -> Unit,
        log: (String) -> Unit = {},
        urlOverride: String? = null,
        minTarballBytes: Long = MIN_TARBALL_BYTES
    ) {
        tmpDir.mkdirs()
        val local = findLocalTarball(projectDir, minTarballBytes)
        val tarball: File
        var downloaded = false
        if (local != null) {
            log("Using the tarball in your project folder: ${local.name}")
            ensureSpace(rootfsDir, local.length() * 5 / 2)
            tarball = local
        } else {
            val url = urlOverride ?: resolveUrl(log)
            log("Downloading $url")
            tarball = File(tmpDir, "antigravity-ide.tar.gz")
            download(url, tarball, onStatus, log) { total -> ensureSpace(tmpDir, total * 7 / 2) }
            downloaded = true
        }
        if (tarball.length() < minTarballBytes) {
            if (downloaded) tarball.delete()
            throw InstallException(
                "The Antigravity file is too small (${sizeText(tarball.length())}) - it is not the IDE archive. " +
                    "Download \"Antigravity IDE.tar.gz\" for Linux ARM64 yourself and put it into the project folder."
            )
        }

        val target = installDir(rootfsDir)
        val staging = File(target.parentFile, "antigravity.new")
        val mainRel: String
        try {
            onStatus("Unpacking Antigravity…")
            extract(tarball, staging, onStatus, log)
            val appDir = findAppDir(staging)
                ?: throw InstallException(
                    "The archive does not contain an Electron app (no resources/app folder). " +
                        "Make sure it is \"Antigravity IDE.tar.gz\" for Linux ARM64."
                )
            val main = findMainBinary(appDir)
                ?: throw InstallException("The archive has no program file (no ELF binary in ${appDir.name}).")
            val machine = elfMachine(main)
            if (machine != ELF_MACHINE_ARM64) {
                val what = if (machine == ELF_MACHINE_X86_64) "the x64 (Intel/AMD) build" else "machine type $machine"
                throw InstallException("This is $what, not the ARM64 build. This phone needs \"Antigravity IDE.tar.gz\" for Linux ARM64.")
            }
            mainRel = staging.toPath().relativize(main.toPath()).toString()
            File(staging, ".pocket-installed").writeText("main=$mainRel\nsource=${local?.name ?: "download"}\n")
        } catch (e: InstallException) {
            deleteTree(staging)
            throw e
        } catch (e: IOException) {
            deleteTree(staging)
            if (downloaded) tarball.delete()
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.contains("No space left", ignoreCase = true)) {
                throw InstallException("The phone ran out of storage while unpacking Antigravity. Free up some space and try again.")
            }
            throw InstallException("Unpacking failed: $msg" + if (downloaded) " (the damaged download was removed; try again)" else "")
        }

        onStatus("Finishing the installation…")
        deleteTree(target)
        if (!staging.renameTo(target)) {
            deleteTree(staging)
            throw InstallException("Could not move the unpacked Antigravity into place.")
        }
        if (downloaded) tarball.delete()
        log("Antigravity installed: $GUEST_DIR/${mainRel.substringBefore('/', "")}")
    }

    // ---------------------------------------------------------------------------------------------
    // Finding the archive
    // ---------------------------------------------------------------------------------------------

    /** A tarball the user put into the project folder, e.g. "Antigravity IDE.tar.gz" (newest first). */
    fun findLocalTarball(dir: File?, minBytes: Long): File? {
        val files = try {
            dir?.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return null
        val nameOk = Regex("""(?i)^antigravity.*\.(tar\.gz|tgz)$""")
        return files
            .filter { it.isFile && nameOk.matches(it.name) && it.length() >= minBytes }
            .maxByOrNull { it.lastModified() }
    }

    /** The address of the newest ARM64 IDE tarball: read from Google's download page, otherwise the pinned one. */
    private fun resolveUrl(log: (String) -> Unit): String {
        try {
            val conn = URL(DOWNLOAD_PAGE).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        val sb = StringBuilder()
                        var total = 0
                        while (total < 3 * 1024 * 1024) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                            total += n
                        }
                        sb.toString()
                    }
                    val m = URL_REGEX.find(text.replace("\\/", "/"))
                    if (m != null && ALLOWED_URL_PREFIXES.any { m.value.startsWith(it) }) return m.value
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log("Could not read the download page (${e.message}); using the known release.")
        }
        return PINNED_URL
    }

    // ---------------------------------------------------------------------------------------------
    // Download (resumable)
    // ---------------------------------------------------------------------------------------------

    private fun download(
        url: String,
        dest: File,
        onStatus: (String) -> Unit,
        log: (String) -> Unit,
        spaceCheck: (Long) -> Unit
    ) {
        var failures = 0
        var checked = false
        while (true) {
            val existing = if (dest.isFile) dest.length() else 0L
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
                val code = conn.responseCode
                if (code == 416 && existing > 0) return // the file is complete already
                if (code in 400..499) {
                    throw InstallException(
                        "Google's server answered HTTP $code for this download. Download \"Antigravity IDE.tar.gz\" " +
                            "(Linux ARM64) from antigravity.google/download yourself and put it into the project folder."
                    )
                }
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                val append = code == 206
                val start = if (append) existing else 0L
                val total: Long = if (append) {
                    conn.getHeaderField("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                        ?: (existing + conn.contentLengthLong)
                } else {
                    conn.contentLengthLong
                }
                if (!checked && total > 0) {
                    spaceCheck(total)
                    checked = true
                }
                FileOutputStream(dest, append).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var done = start
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 700) {
                                lastReport = now
                                onStatus(progressText(done, total))
                            }
                        }
                        if (total > 0 && done < total) throw IOException("the connection closed early")
                    }
                }
                onStatus(progressText(dest.length(), if (total > 0) total else dest.length()))
                return
            } catch (e: IOException) {
                failures++
                if (failures > 8) {
                    throw InstallException(
                        "The download failed (${e.message}). Check the internet connection and try again - it continues where it stopped."
                    )
                }
                log("Download interrupted (${e.message}); continuing (attempt $failures of 8).")
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    throw InstallException("The download was cancelled.")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun sizeText(bytes: Long): String = if (bytes >= MB) "${bytes / MB} MB" else "${bytes / 1024} KB"

    private fun progressText(done: Long, total: Long): String =
        if (total > 0) "Downloading Antigravity… ${done / MB} of ${total / MB} MB (${done * 100 / total} %)"
        else "Downloading Antigravity… ${done / MB} MB"

    private fun ensureSpace(dir: File, needed: Long) {
        val free = dir.usableSpace
        if (free in 1 until needed) {
            throw InstallException(
                "Not enough free storage for Antigravity: about ${needed / MB} MB are needed, ${free / MB} MB are free."
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Unpacking
    // ---------------------------------------------------------------------------------------------

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var count = 0L

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }
    }

    private fun extract(tarball: File, destDir: File, onStatus: (String) -> Unit, log: (String) -> Unit) {
        deleteTree(destDir)
        destDir.mkdirs()
        val root: Path = destDir.canonicalFile.toPath().normalize()
        val size = tarball.length().coerceAtLeast(1L)
        val counting = CountingInputStream(FileInputStream(tarball))
        val gz = GzipCompressorInputStream(BufferedInputStream(counting, 256 * 1024))
        val buffer = ByteArray(128 * 1024)
        var entries = 0
        var skipped = 0
        var lastParent: Path? = null
        var lastReport = 0L
        // one mkdirs() per directory instead of one per file (the entries of a directory follow each other)
        fun ensureParent(target: Path) {
            val parent = target.parent ?: return
            if (parent != lastParent) {
                parent.toFile().mkdirs()
                lastParent = parent
            }
        }
        try {
            val tar = TarArchiveInputStream(gz)
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val target = safeResolve(root, entry.name)
                try {
                    when {
                        entry.isDirectory -> target.toFile().mkdirs()

                        entry.isSymbolicLink -> {
                            ensureParent(target)
                            Files.deleteIfExists(target)
                            Files.createSymbolicLink(target, Paths.get(entry.linkName))
                        }

                        entry.isLink -> {
                            val source = safeResolve(root, entry.linkName)
                            ensureParent(target)
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                            if (Files.isExecutable(source)) target.toFile().setExecutable(true, false)
                        }

                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> skipped++

                        else -> {
                            ensureParent(target)
                            if (Files.isSymbolicLink(target)) Files.delete(target)
                            FileOutputStream(target.toFile()).use { out ->
                                while (true) {
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                            }
                            if ((entry.mode and 0b001_001_001) != 0) target.toFile().setExecutable(true, false)
                        }
                    }
                } catch (e: IOException) {
                    if (e.message?.startsWith("Path traversal") == true) throw e
                    if (e.message?.contains("No space left", ignoreCase = true) == true) throw e
                    skipped++ // one entry that cannot be created (e.g. an odd symlink) must not abort the install
                }
                entries++
                val now = System.currentTimeMillis()
                if (now - lastReport > 700) {
                    lastReport = now
                    onStatus("Unpacking Antigravity… ${counting.count * 100 / size} %")
                }
                entry = tar.nextTarEntry
            }
            // read the rest (end-of-archive blocks) so that the gzip checksum is verified too
            while (gz.read(buffer) >= 0) {
                // nothing: reading is the point
            }
        } finally {
            gz.close()
        }
        if (skipped > 0) log("Skipped $skipped special/unsupported entries while unpacking.")
        log("Unpacked $entries entries.")
    }

    private fun safeResolve(root: Path, relative: String): Path {
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) throw IOException("Path traversal rejected: $relative")
        return resolved
    }

    // ---------------------------------------------------------------------------------------------
    // Looking inside the unpacked IDE
    // ---------------------------------------------------------------------------------------------

    /** The folder that holds the program: the one that contains resources/app (or resources/app.asar). */
    internal fun findAppDir(root: File): File? {
        fun isApp(d: File): Boolean {
            val res = File(d, "resources")
            return res.isDirectory && (File(res, "app").isDirectory || File(res, "app.asar").isFile)
        }
        var level = listOf(root)
        for (depth in 0..3) {
            level.firstOrNull { isApp(it) }?.let { return it }
            level = level.flatMap { d -> d.listFiles { f -> f.isDirectory && !Files.isSymbolicLink(f.toPath()) }?.toList() ?: emptyList() }
            if (level.isEmpty()) break
        }
        return null
    }

    /** The Electron binary: the biggest ELF file that sits directly in the app folder (the shorter name wins a tie). */
    internal fun findMainBinary(appDir: File): File? =
        appDir.listFiles { f -> f.isFile && !Files.isSymbolicLink(f.toPath()) && elfMachine(f) != -1 }
            ?.sortedWith(compareByDescending<File> { it.length() }.thenBy { it.name.length }.thenBy { it.name })
            ?.firstOrNull()

    /** The ELF machine id of [file] (183 = ARM64, 62 = x86-64), or -1 when it is not a little-endian ELF file. */
    internal fun elfMachine(file: File): Int {
        return try {
            FileInputStream(file).use { input ->
                val h = ByteArray(20)
                var n = 0
                while (n < h.size) {
                    val r = input.read(h, n, h.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n < 20 || h[0] != 0x7F.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'L'.code.toByte() ||
                    h[3] != 'F'.code.toByte() || h[5] != 1.toByte()
                ) {
                    -1
                } else {
                    (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)
                }
            }
        } catch (_: IOException) {
            -1
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Deletes [dir] with everything in it, never following symlinks. */
    internal fun deleteTree(dir: File) {
        if (!Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    Files.deleteIfExists(file)
                } catch (_: IOException) {
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                try {
                    Files.deleteIfExists(d)
                } catch (_: IOException) {
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
