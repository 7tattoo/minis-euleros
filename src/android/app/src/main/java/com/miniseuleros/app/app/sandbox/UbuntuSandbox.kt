package com.miniseuleros.app.sandbox

import android.content.Context
import android.system.Os
import android.util.Log
import com.miniseuleros.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Secondary Ubuntu 24.04 (arm64) sandbox for apt-type tasks.
 *
 * Runs beside the main EulerOS rootfs via the same libproot shipped in the APK
 * (no emulation — aarch64 native). The host [workspaceHostDir] — the same
 * directory the main sandbox mounts at /var/minis-euleros/workspace — is bound
 * at /workspace, so files written with file_write/file_edit are directly
 * executable here. Nothing installed inside this sandbox touches the main
 * sandbox image.
 */
object UbuntuSandbox {

    private const val TAG = "UbuntuSandbox"
    private const val ROOTFS_DIR = "ubuntu-rootfs"
    private const val ARCHIVE_NAME = "ubuntu-base-24.04.3-base-arm64.tar.gz"
    private const val MIN_ARCHIVE_BYTES = 20L * 1024 * 1024

    /** China-first mirrors, official cdimage as the last resort. */
    private val MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$ARCHIVE_NAME",
        "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$ARCHIVE_NAME",
        "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$ARCHIVE_NAME",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/$ARCHIVE_NAME",
    )

    /** arm64 packages live in ubuntu-ports; archive.ubuntu.com does not carry arm64. */
    private const val APT_SOURCES = """## Managed by minis-euleros — arm64 uses ubuntu-ports.
Types: deb
URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
Suites: noble noble-updates noble-backports noble-security
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
"""

    private const val BASHRC = """export PS1='\u@ubuntu:\w\$ '
alias ll='ls -alF'
export EDITOR=vi
"""

    // ---------------------------------------------------------------- status

    sealed class InstallState {
        data object Idle : InstallState()
        data class Downloading(val bytes: Long, val totalBytes: Long) : InstallState()
        data object Extracting : InstallState()
        data object Patching : InstallState()
        data object Success : InstallState()
        data class Failed(val message: String) : InstallState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()
    private val installing = AtomicBoolean(false)

    fun rootfsDir(context: Context): File = File(context.filesDir, ROOTFS_DIR)

    /**
     * Host directory shared with the main sandbox: it mounts at
     * /var/minis-euleros/workspace there and at /workspace here.
     */
    fun workspaceHostDir(context: Context): File =
        File(context.filesDir, "minis-global/workspace").apply { mkdirs() }

    fun isInstalled(context: Context): Boolean =
        File(rootfsDir(context), "bin/sh").isFile

    // [T-ubuntu-sandbox-size-crash] The status row shows the rootfs size on
    // every screen entry. Walking a freshly-extracted Ubuntu rootfs means
    // tens of thousands of small files — seconds of flash I/O — so the value
    // is cached for the process lifetime and invalidated on install/delete.
    @Volatile
    private var cachedSizeBytes: Long = -1L

    fun invalidateSizeCache() {
        cachedSizeBytes = -1L
    }

    /**
     * Total on-disk size of the installed rootfs (bytes). Must run off the
     * main thread (walks the whole rootfs on a cache miss). Returns 0 when
     * the rootfs is not installed — FileTreeWalk asserts on a missing
     * rootDir, which crashed the sandbox settings screen before install.
     */
    fun rootfsSizeBytes(context: Context): Long {
        cachedSizeBytes.takeIf { it >= 0L }?.let { return it }
        val dir = rootfsDir(context)
        if (!dir.isDirectory) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            .also { cachedSizeBytes = it }
    }

    // --------------------------------------------------------------- install

    fun startInstall(context: Context) {
        val app = context.applicationContext
        if (!installing.compareAndSet(false, true)) return
        scope.launch {
            try {
                AppLogger.info(TAG, "install started (alreadyInstalled=${isInstalled(app)})")
                val rootfs = rootfsDir(app)
                rootfs.deleteRecursively()
                // The old rootfs (if any) is gone — a cached size is now wrong.
                invalidateSizeCache()
                rootfs.mkdirs()
                _installState.value = InstallState.Downloading(0, 0)
                val archive = download(app) { bytes, total ->
                    _installState.value = InstallState.Downloading(bytes, total)
                }
                _installState.value = InstallState.Extracting
                withContext(Dispatchers.IO) { extractTarGz(archive, rootfs) }
                _installState.value = InstallState.Patching
                withContext(Dispatchers.IO) { patch(app) }
                archive.delete()
                File(app.cacheDir, ARCHIVE_NAME + ".part").delete()
                if (!isInstalled(app)) throw IOException("rootfs incomplete after extraction (bin/sh missing)")
                // Compute (and cache) the final size while already on IO.
                val installedBytes = rootfsSizeBytes(app)
                AppLogger.info(TAG, "install finished, rootfs size=$installedBytes")
                _installState.value = InstallState.Success
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                AppLogger.error(TAG, "install failed: ${e.message ?: e.javaClass.simpleName}")
                _installState.value = InstallState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                installing.set(false)
            }
        }
    }

    fun delete(context: Context) {
        val app = context.applicationContext
        scope.launch {
            withContext(Dispatchers.IO) {
                rootfsDir(app).deleteRecursively()
                invalidateSizeCache()
                finalArchive(app).delete()
                File(app.cacheDir, ARCHIVE_NAME + ".part").delete()
            }
            _installState.value = InstallState.Idle
        }
    }

    private fun finalArchive(context: Context): File = File(context.cacheDir, ARCHIVE_NAME)

    /**
     * Cheap integrity probe: gzip magic + parseable first tar header. Catches
     * truncated downloads, HTML error pages saved as .part, and garbage
     * resumed tails — without shipping a hash table.
     */
    private fun archiveLooksValid(file: File): Boolean {
        if (!file.isFile || file.length() < 1024) return false
        return try {
            java.io.FileInputStream(file).use { raw ->
                java.util.zip.GZIPInputStream(raw, 1 shl 16).use { gz ->
                    val hdr = ByteArray(512)
                    var off = 0
                    while (off < 512) {
                        val n = gz.read(hdr, off, 512 - off)
                        if (n < 0) return false
                        off += n
                    }
                    val name = String(hdr, 0, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    val sizeStr = String(hdr, 124, 12, Charsets.UTF_8).trim('\u0000', ' ')
                    name.startsWith("./") && sizeStr.isNotEmpty() && sizeStr.all { it in '0'..'7' }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun download(context: Context, onProgress: (Long, Long) -> Unit): File {
        val final = finalArchive(context)
        // A previously downloaded archive (e.g. after a failed extraction) is
        // still good — retrying must not re-download 28 MB.
        if (archiveLooksValid(final)) return final
        val partial = File(context.cacheDir, "$ARCHIVE_NAME.part")
        val errors = mutableListOf<String>()
        for (mirror in MIRRORS) {
            val host = runCatching { URL(mirror).host }.getOrDefault(mirror)
            try {
                if (downloadOne(mirror, partial, onProgress) && archiveLooksValid(partial)) {
                    final.delete()
                    if (!partial.renameTo(final)) partial.copyTo(final, overwrite = true)
                    AppLogger.info(TAG, "downloaded ${final.length()} bytes from $host")
                    return final
                }
                errors += "$host: incomplete download"
                AppLogger.warning(TAG, "mirror incomplete: $mirror")
            } catch (e: Exception) {
                errors += "$host: ${e.message ?: e.javaClass.simpleName}"
                AppLogger.warning(TAG, "mirror failed: $mirror: ${e.message}")
            }
            // A corrupt partial must not poison the next mirror's resume.
            if (partial.isFile && !archiveLooksValid(partial)) partial.delete()
        }
        // Keep a valid partial on disk so the next retry resumes instead of
        // starting from zero (416-on-resume is handled in [downloadOne]).
        throw IOException(
            "all mirrors failed — " + errors.takeLast(4).joinToString("; ")
        )
    }

    /** @return true when the archive looks complete. Supports resume via Range. */
    private fun downloadOne(url: String, partial: File, onProgress: (Long, Long) -> Unit): Boolean {
        val existing = if (partial.exists()) partial.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            conn.connect()
            val code = conn.responseCode
            if (code == 416) {
                // Range start == file size on the server: the partial file is
                // already complete (retry after a failed extraction).
                return existing >= MIN_ARCHIVE_BYTES
            }
            if (code != 200 && code != 206) return false
            val resuming = code == 206 && existing > 0
            val total = if (resuming) existing + conn.contentLengthLong
                        else conn.contentLengthLong.let { if (it > 0) it else -1L }
            var written = if (resuming) existing else 0L
            if (!resuming) partial.delete()
            conn.inputStream.use { input ->
                FileOutputStream(partial, resuming).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written - lastReport > 512 * 1024) {
                            lastReport = written
                            onProgress(written, total)
                        }
                    }
                    onProgress(written, total)
                }
            }
            if (total > 0 && written != total) {
                AppLogger.warning(TAG, "truncated download: $written / $total")
                return false
            }
            return written >= MIN_ARCHIVE_BYTES
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ extraction

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("unexpected EOF in tar stream")
            off += n
        }
    }

    private fun skipFully(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("unexpected EOF while skipping")
            remaining -= n
        }
    }

    private fun safeTarget(rootfs: File, entryName: String): File? {
        val clean = entryName.removePrefix("./").trimStart('/')
        if (clean.isEmpty()) return null
        val f = File(rootfs, clean)
        val rootPath = rootfs.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(rootPath)) {
            Log.w(TAG, "skip traversal entry: $entryName")
            return null
        }
        return f
    }

    private fun extractTarGz(archive: File, rootfs: File) {
        BufferedInputStream(FileInputStream(archive), 1 shl 16).use { raw ->
            GZIPInputStream(raw, 1 shl 16).use { input ->
                val header = ByteArray(512)
                var longName: String? = null
                var paxPath: String? = null
                var paxLink: String? = null
                while (true) {
                    readFully(input, header)
                    if (header.all { it == 0.toByte() }) break
                    val rawName = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    val size = String(header, 124, 12, Charsets.UTF_8).trim('\u0000', ' ').toLongOrNull(8) ?: 0L
                    val mode = String(header, 100, 8, Charsets.UTF_8).trim('\u0000', ' ').toLongOrNull(8) ?: 0L
                    val type = header[156].toInt().toChar()
                    var linkName = String(header, 157, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')

                    var entryName = paxPath ?: longName ?: rawName
                    if (paxLink != null) linkName = paxLink
                    longName = null; paxPath = null; paxLink = null

                    val dataStart = ((size + 511) / 512) * 512

                    when (type) {
                        'L' -> { // GNU long name
                            val data = ByteArray(size.toInt())
                            readFully(input, data)
                            longName = String(data, Charsets.UTF_8).trimEnd('\u0000')
                            skipPadding(input, size, dataStart)
                        }
                        'x', 'g' -> { // PAX header / global header
                            if (type == 'x') {
                                val data = ByteArray(size.toInt())
                                readFully(input, data)
                                String(data, Charsets.UTF_8).lineSequence().forEach { rec ->
                                    val sp = rec.indexOf(' ')
                                    val kv = if (sp >= 0) rec.substring(sp + 1) else rec
                                    val eq = kv.indexOf('=')
                                    if (eq > 0) {
                                        val key = kv.substring(0, eq)
                                        val value = kv.substring(eq + 1)
                                        if (key == "path") paxPath = value
                                        if (key == "linkpath") paxLink = value
                                    }
                                }
                                skipPadding(input, size, dataStart)
                            } else {
                                skipFully(input, dataStart)
                            }
                        }
                        '5' -> {
                            skipFully(input, dataStart)
                            safeTarget(rootfs, entryName)?.let { dir ->
                                dir.mkdirs()
                                chmod(dir, mode)
                            }
                        }
                        '2' -> { // symlink
                            skipFully(input, dataStart)
                            safeTarget(rootfs, entryName)?.let { link ->
                                link.delete()
                                link.parentFile?.mkdirs()
                                runCatching { Os.symlink(linkName, link.absolutePath) }
                                    .onFailure { Log.w(TAG, "symlink failed: $entryName -> $linkName") }
                            }
                        }
                        '1' -> { // hardlink — copy the referenced file instead
                            skipFully(input, dataStart)
                            safeTarget(rootfs, entryName)?.let { target ->
                                val source = safeTarget(rootfs, linkName) ?: File(rootfs, linkName.removePrefix("./"))
                                target.parentFile?.mkdirs()
                                if (source.isFile) {
                                    target.delete()
                                    source.copyTo(target, overwrite = true)
                                    chmod(target, mode)
                                } else {
                                    Log.w(TAG, "hardlink source missing: $entryName -> $linkName")
                                }
                            }
                        }
                        '0', '\u0000', '7' -> { // regular file
                            safeTarget(rootfs, entryName)?.let { file ->
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { out ->
                                    var remaining = size
                                    val buf = ByteArray(64 * 1024)
                                    while (remaining > 0) {
                                        val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                                        if (n < 0) throw IOException("unexpected EOF in file data of $entryName")
                                        out.write(buf, 0, n)
                                        remaining -= n
                                    }
                                }
                                chmod(file, mode)
                            }
                            // Consume the 512-block padding after file data —
                            // without this the stream desyncs on the first file
                            // whose size is not a multiple of 512. (Note: the
                            // old `?: skipFully(dataStart)` was dead code — let
                            // returns Unit so elvis never fired.)
                            skipPadding(input, size, dataStart)
                        }
                        else -> skipFully(input, dataStart)
                    }
                }
            }
        }
    }

    /** Skip the tar 512-block padding after [consumed] bytes up to [dataStart]. */
    private fun skipPadding(input: java.io.InputStream, consumed: Long, dataStart: Long) {
        val pad = dataStart - consumed
        if (pad > 0) skipFully(input, pad)
    }

    private fun chmod(file: File, mode: Long) {
        runCatching { Os.chmod(file.absolutePath, (mode and 0xFFF).toInt()) }
    }

    // ----------------------------------------------------------------- patch

    private fun patch(context: Context) {
        val rootfs = rootfsDir(context)
        runCatching {
            val resolv = File(rootfs, "etc/resolv.conf")
            resolv.delete() // may be a dangling symlink in some base images
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n")

            File(rootfs, "etc/hosts").writeText(
                "127.0.0.1 localhost\n127.0.1.1 ubuntu-sandbox\n::1 localhost ip6-localhost\n"
            )
            File(rootfs, "etc/hostname").writeText("ubuntu-sandbox\n")

            // apt: arm64 needs ubuntu-ports; point at a fast CN mirror
            val aptDir = File(rootfs, "etc/apt/sources.list.d")
            aptDir.mkdirs()
            File(aptDir, "ubuntu.sources").writeText(APT_SOURCES)
            File(rootfs, "etc/apt/sources.list").takeIf { it.isFile }?.writeText("")
            File(rootfs, "etc/apt/apt.conf.d/99retries").writeText(
                "Acquire::Retries \"3\";\n"
            )

            // shell conveniences
            File(rootfs, "root").mkdirs()
            File(rootfs, "root/.bashrc").writeText(BASHRC)
            File(rootfs, "root/.profile").writeText("export LANG=C.UTF-8\n")

            // standard group names so proot's root-id mapping resolves cleanly
            val group = File(rootfs, "etc/group")
            if (group.isFile) {
                val text = group.readText()
                val extra = buildString {
                    if (!text.contains("aid_inet")) append("aid_inet:x:3003:\n")
                    if (!text.contains("aid_net_raw")) append("aid_net_raw:x:3004:\n")
                    if (!text.contains("aid_inet_raw")) append("aid_inet_raw:x:3005:\n")
                }
                if (extra.isNotEmpty()) group.appendText(extra)
            }

            // tmp dirs with sticky bit
            File(rootfs, "tmp").mkdirs(); chmod(File(rootfs, "tmp"), 1023L)
            File(rootfs, "var/tmp").mkdirs(); chmod(File(rootfs, "var/tmp"), 1023L)
            File(rootfs, "dev").mkdirs()
            File(rootfs, "proc").mkdirs()
            File(rootfs, "sys").mkdirs()
            File(rootfs, "workspace").mkdirs()
        }.onFailure { Log.w(TAG, "patch failed: ${it.message}") }
    }

    // ----------------------------------------------------------- run command

    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
    )

    /**
     * Execute [command] inside the Ubuntu sandbox via proot. Blocks the calling
     * thread — call from Dispatchers.IO. [lineCallback] receives streamed merged
     * output lines while the command runs.
     */
    fun runCommand(
        context: Context,
        command: String,
        cwd: String = "/workspace",
        timeoutMs: Long = 900_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): CommandResult {
        val app = context.applicationContext
        val rootfs = rootfsDir(app)
        if (!isInstalled(app)) {
            return CommandResult(-1, "Ubuntu sandbox is not installed (Settings → Ubuntu 沙箱)", false)
        }
        val proot = File(app.applicationInfo.nativeLibraryDir, "libproot.so")
        if (!proot.isFile) {
            return CommandResult(-1, "libproot.so missing from nativeLibraryDir", false)
        }
        val tmp = File(app.cacheDir, "ubuntu-proot").apply { mkdirs() }
        val ws = workspaceHostDir(app)

        val cmd = mutableListOf(
            proot.absolutePath,
            "--root-id", "--link2symlink", "--kill-on-exit",
            "-r", rootfs.absolutePath,
            "-w", cwd,
            "-b", "${ws.absolutePath}:/workspace",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "TMPDIR=/tmp",
            "/bin/bash", "-l", "-c", "cd -- \"\$1\" && eval \"\$2\"", "sh", cwd, command,
        )

        val pb = ProcessBuilder(cmd)
        pb.environment()["PROOT_TMP_DIR"] = tmp.absolutePath
        pb.environment()["TMPDIR"] = tmp.absolutePath
        val loader = File(app.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        if (loader.isFile) pb.environment()["PROOT_LOADER"] = loader.absolutePath
        pb.redirectErrorStream(false)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        return try {
            val proc = pb.start()
            val pump = { stream: java.io.InputStream, sink: StringBuilder ->
                Thread {
                    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(sink) { sink.appendLine(line) }
                            if (sink === stdout) lineCallback?.invoke(line)
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
            pump(proc.inputStream, stdout)
            pump(proc.errorStream, stderr)

            val finished = runCatching { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!finished) {
                proc.destroy()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
                val text = synchronized(stdout) { stdout.toString() } +
                    synchronized(stderr) { stderr.toString() }
                return CommandResult(
                    exitCode = 124,
                    output = text + "\n[timeout after ${timeoutMs / 1000}s — process killed]",
                    timedOut = true,
                )
            }
            // give the pumps a moment to drain
            Thread.sleep(150)
            val text = synchronized(stdout) { stdout.toString() } +
                synchronized(stderr) { stderr.toString() }
            CommandResult(exitCode = proc.exitValue(), output = text, timedOut = false)
        } catch (e: Exception) {
            CommandResult(-1, "failed to start: ${e.message}", false)
        }
    }
}
