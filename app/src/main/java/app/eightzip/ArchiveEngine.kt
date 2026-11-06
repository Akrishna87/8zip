package app.eightzip

import com.github.junrar.Archive
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.UnsupportedRarV5Exception
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.util.zip.CRC32
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** [canCreate]: we can write this format. [supportsPassword]: we can encrypt it when writing. */
enum class Format(val label: String, val ext: String, val canCreate: Boolean, val supportsPassword: Boolean) {
    SEVEN_Z("7z", "7z", true, true),
    ZIP("zip", "zip", true, true),
    TAR("tar", "tar", true, false),
    TAR_GZ("tar.gz", "tar.gz", true, false),
    RAR("rar", "rar", false, false); // extract only

    companion object {
        fun detect(file: File): Format? {
            val n = file.name.lowercase()
            return when {
                n.endsWith(".7z") -> SEVEN_Z
                n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") -> ZIP
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar") -> TAR
                n.endsWith(".rar") -> RAR
                else -> null
            }
        }
    }
}

data class EntryInfo(val name: String, val size: Long, val isDirectory: Boolean)

/** The archive is encrypted and no password was supplied. */
class PasswordRequiredException(name: String) : IOException("$name is password protected")

/** A password was supplied but it did not decrypt the archive. */
class WrongPasswordException : IOException("Wrong password (or the archive is corrupt)")

fun interface Progress { fun onFile(name: String) }

/** Pluggable RAR5 reader (junrar can't read RAR5). The Android app installs a libarchive-backed one. */
interface Rar5Reader {
    fun list(archive: File): List<EntryInfo>
    /** [resolve] maps an entry name to a safe output file (throws on path traversal). */
    fun extract(archive: File, resolve: (String) -> File, progress: Progress, accept: (String) -> Boolean)
}

/** Pure-JVM archive logic (no Android dependencies) so it can be unit tested. */
object ArchiveEngine {

    @Volatile var rar5Reader: Rar5Reader? = null
    private const val RAR5_UNSUPPORTED = "RAR5 archives aren't supported on this device"

    fun isRar5(f: File): Boolean = runCatching {
        f.inputStream().use { i ->
            val h = ByteArray(8)
            i.read(h) == 8 && h.contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        }
    }.getOrDefault(false)

    /** Works out the archive type from the file's first bytes (for downloads whose names say nothing). */
    fun sniff(f: File): Format? = runCatching {
        val b = ByteArray(262)
        var n = 0
        f.inputStream().use { i -> while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r } }
        fun starts(vararg v: Int) = n >= v.size && v.indices.all { (b[it].toInt() and 0xFF) == v[it] }
        when {
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) -> Format.ZIP
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Format.SEVEN_Z
            starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> Format.RAR
            starts(0x1F, 0x8B) -> Format.TAR_GZ
            n >= 262 && String(b, 257, 5, Charsets.ISO_8859_1) == "ustar" -> Format.TAR
            else -> null
        }
    }.getOrNull()

    /**
     * Builds the "is this entry wanted" test for a partial extraction. A name selects itself and,
     * if it is a folder, everything inside it. null means "everything".
     */
    internal fun selectionFilter(only: Collection<String>?): (String) -> Boolean {
        if (only == null) return { true }
        fun norm(n: String) = n.removePrefix("./").trimEnd('/')
        val keys = only.map(::norm)
        return { name -> norm(name).let { n -> keys.any { n == it || n.startsWith("$it/") } } }
    }

    /**
     * To be called after a successful [compress]: re-reads [archive], checks it holds every file from
     * [sources], and only then deletes the sources. Any doubt throws and leaves the originals alone.
     */
    fun deleteSourcesAfterCompress(sources: List<File>, archive: File, remove: (File) -> Unit = { deleteTree(it) }) {
        if (!archive.isFile || archive.length() == 0L) throw IOException("The archive wasn't created, so the originals were kept")
        val archivePath = archive.canonicalPath
        if (sources.any { archivePath == it.canonicalPath || archivePath.startsWith(it.canonicalPath + File.separator) })
            throw IOException("The archive is inside the items being compressed, so the originals were kept")
        var expected = 0
        walk(sources) { f, _ -> if (f.isFile) expected++ }
        val actual = try { list(archive).count { !it.isDirectory } } catch (e: IOException) {
            throw IOException("Couldn't re-read the new archive, so the originals were kept")
        }
        if (actual < expected) throw IOException("The archive looks incomplete ($actual of $expected files), so the originals were kept")
        sources.forEach { remove(it) }
    }

    /** Deletes files/folders recursively without ever following symlinks out of the tree. */
    fun deleteTree(f: File) {
        val isLink = f.parentFile?.let { File(it.canonicalFile, f.name).let { x -> x.canonicalPath != x.absolutePath } } ?: false
        if (f.isDirectory && !isLink) f.listFiles()?.forEach { deleteTree(it) }
        if (!f.delete() && f.exists()) throw IOException("Couldn't delete ${f.name}")
    }

    /** Lists the contents of an archive. File names are readable without a password. */
    fun list(archive: File): List<EntryInfo> {
        val out = mutableListOf<EntryInfo>()
        when (Format.detect(archive) ?: throw IOException("Unsupported archive type")) {
            Format.ZIP -> ZipFile(archive).fileHeaders.forEach {
                out += EntryInfo(it.fileName, it.uncompressedSize, it.isDirectory)
            }
            Format.RAR -> if (isRar5(archive)) {
                out += (rar5Reader ?: throw IOException(RAR5_UNSUPPORTED)).list(archive)
            } else openRar(archive, null).use { a ->
                a.fileHeaders.forEach { out += EntryInfo(rarName(it), it.fullUnpackSize, it.isDirectory) }
            }
            Format.SEVEN_Z -> SevenZFile.builder().setFile(archive).get().use { z ->
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) { out += EntryInfo(e.name, e.size, e.isDirectory); e = z.nextEntry }
            }
            else -> openStream(archive).use { s ->
                var e: ArchiveEntry? = s.nextEntry
                while (e != null) { out += EntryInfo(e.name, e.size, e.isDirectory); e = s.nextEntry }
            }
        }
        return out
    }

    /**
     * Extracts [archive] into [destDir]. Guards against path traversal ("zip slip").
     * Throws [PasswordRequiredException] / [WrongPasswordException] for encrypted archives; in that
     * case a destination folder created by this call is removed again.
     */
    fun extract(
        archive: File,
        destDir: File,
        progress: Progress = Progress {},
        password: CharArray? = null,
        only: Collection<String>? = null
    ) {
        val accept = selectionFilter(only)
        val existed = destDir.exists()
        try {
            destDir.mkdirs()
            val root = destDir.canonicalFile
            when (Format.detect(archive) ?: throw IOException("Unsupported archive type")) {
                Format.ZIP -> extractZip(archive, root, progress, password, accept)
                Format.SEVEN_Z -> extract7z(archive, root, progress, password, accept)
                Format.RAR -> extractRar(archive, root, progress, password, accept)
                else -> openStream(archive).use { s ->
                    var e: ArchiveEntry? = s.nextEntry
                    while (e != null) {
                        if (!accept(e.name)) { e = s.nextEntry; continue }
                        val target = safeTarget(root, e.name)
                        progress.onFile(e.name)
                        if (e.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { s.copyTo(it) }
                        }
                        e = s.nextEntry
                    }
                }
            }
        } catch (e: IOException) {
            if (!existed && (e is PasswordRequiredException || e is WrongPasswordException)) destDir.deleteRecursively()
            throw e
        }
    }

    private fun extractZip(archive: File, root: File, progress: Progress, password: CharArray?, accept: (String) -> Boolean) {
        val zf = ZipFile(archive)
        if (zf.isEncrypted) {
            if (password == null || password.isEmpty()) throw PasswordRequiredException(archive.name)
            zf.setPassword(password)
            // Validate every name first, then let zip4j decrypt and write.
            val wanted = zf.fileHeaders.filter { accept(it.fileName) }
            wanted.forEach { safeTarget(root, it.fileName); progress.onFile(it.fileName) }
            try {
                wanted.forEach { zf.extractFile(it, root.path) }
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.WRONG_PASSWORD) throw WrongPasswordException()
                throw IOException(e.message, e)
            }
        } else {
            ZipArchiveInputStream(BufferedInputStream(archive.inputStream())).use { s ->
                var e: ArchiveEntry? = s.nextEntry
                while (e != null) {
                    if (!accept(e.name)) { e = s.nextEntry; continue }
                    val target = safeTarget(root, e.name)
                    progress.onFile(e.name)
                    if (e.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { s.copyTo(it) }
                    }
                    e = s.nextEntry
                }
            }
        }
    }

    private fun rarName(h: com.github.junrar.rarfile.FileHeader) = h.fileName.replace('\\', '/')

    private fun openRar(archive: File, password: CharArray?): Archive = try {
        if (password != null && password.isNotEmpty()) Archive(archive, String(password)) else Archive(archive)
    } catch (e: UnsupportedRarV5Exception) {
        throw IOException("RAR5 archives aren't supported yet (RAR 1-4 only)")
    } catch (e: com.github.junrar.exception.RarException) {
        throw IOException("Not a valid RAR archive: ${e.message}")
    }

    private fun extractRar(archive: File, root: File, progress: Progress, password: CharArray?, accept: (String) -> Boolean) {
        if (isRar5(archive)) {
            (rar5Reader ?: throw IOException(RAR5_UNSUPPORTED)).extract(archive, { safeTarget(root, it) }, progress, accept)
            return
        }
        val hasPw = password != null && password.isNotEmpty()
        openRar(archive, password).use { a ->
            val headers = a.fileHeaders
            if (!hasPw && headers.any { it.isEncrypted }) throw PasswordRequiredException(archive.name)
            try {
                for (h in headers) {
                    val name = rarName(h)
                    if (!accept(name)) continue
                    val target = safeTarget(root, name)
                    progress.onFile(name)
                    if (h.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { a.extractFile(h, it) }
                    }
                }
            } catch (e: CrcErrorException) {
                // Bad checksum: with a password this means it decrypted to garbage.
                if (hasPw) throw WrongPasswordException() else throw IOException("Corrupt RAR data (CRC mismatch)")
            } catch (e: com.github.junrar.exception.RarException) {
                if (hasPw) throw WrongPasswordException() else throw IOException(e.message ?: "RAR error", e)
            }
        }
    }

    private fun extract7z(archive: File, root: File, progress: Progress, password: CharArray?, accept: (String) -> Boolean) {
        val builder = SevenZFile.builder().setFile(archive)
        if (password != null && password.isNotEmpty()) builder.setPassword(password)
        try {
            builder.get().use { z ->
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) {
                    if (!accept(e.name)) { e = z.nextEntry; continue }
                    val target = safeTarget(root, e.name)
                    progress.onFile(e.name)
                    if (e.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) { val n = z.read(buf); if (n < 0) break; o.write(buf, 0, n) }
                        }
                    }
                    e = z.nextEntry
                }
            }
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw PasswordRequiredException(archive.name)
        } catch (e: IOException) {
            // With a password supplied, a decryption failure surfaces as a corrupt-stream / CRC error.
            if (e is PasswordRequiredException || e is WrongPasswordException) throw e
            if (password != null && password.isNotEmpty() && !e.message.orEmpty().startsWith("Blocked unsafe")) throw WrongPasswordException()
            throw e
        }
    }

    /**
     * Compresses [sources] (files or folders) into [dest]. A non-empty [password] encrypts 7z
     * (AES-256) and zip (AES-256); tar formats cannot be encrypted and reject a password.
     */
    fun compress(
        sources: List<File>,
        dest: File,
        format: Format,
        progress: Progress = Progress {},
        password: CharArray? = null,
        legacyZipCrypto: Boolean = false
    ) {
        val pw = password?.takeIf { it.isNotEmpty() }
        if (!format.canCreate) throw IOException("${format.label} archives can only be extracted, not created")
        if (pw != null && !format.supportsPassword) throw IOException("${format.label} archives can't be password protected")
        dest.parentFile?.mkdirs()
        when (format) {
            Format.ZIP -> if (pw != null) zipEncrypted(sources, dest, pw, progress, legacyZipCrypto) else
                ZipArchiveOutputStream(dest).use { z ->
                    walk(sources) { f, name ->
                        progress.onFile(name)
                        if (f.isDirectory) {
                            z.putArchiveEntry(ZipArchiveEntry(f, "$name/")); z.closeArchiveEntry()
                        } else {
                            z.putArchiveEntry(ZipArchiveEntry(f, name))
                            f.inputStream().use { it.copyTo(z) }; z.closeArchiveEntry()
                        }
                    }
                }
            Format.SEVEN_Z -> (if (pw != null) SevenZOutputFile(dest, pw) else SevenZOutputFile(dest)).use { z ->
                walk(sources) { f, name ->
                    progress.onFile(name)
                    val entry = z.createArchiveEntry(f, name) // 7z stores folder names without a trailing slash
                    entry.hasWindowsAttributes = true
                    entry.windowsAttributes = if (f.isDirectory) 0x10 else 0x20 // DIRECTORY / ARCHIVE
                    z.putArchiveEntry(entry)
                    if (f.isFile) f.inputStream().use { i ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = i.read(buf); if (n < 0) break; z.write(buf, 0, n) }
                    }
                    z.closeArchiveEntry()
                }
            }
            Format.RAR -> throw IOException("Creating RAR archives isn't supported")
            Format.TAR, Format.TAR_GZ -> {
                val raw = BufferedOutputStream(dest.outputStream())
                val sink = if (format == Format.TAR_GZ) GZIPOutputStream(raw) else raw
                TarArchiveOutputStream(sink).use { t ->
                    t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    t.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    walk(sources) { f, name ->
                        progress.onFile(name)
                        t.putArchiveEntry(TarArchiveEntry(f, if (f.isDirectory) "$name/" else name))
                        if (f.isFile) f.inputStream().use { it.copyTo(t) }
                        t.closeArchiveEntry()
                    }
                }
            }
        }
    }

    /**
     * Re-reads every byte of a just-written [archive] and checks its checksums (zip CRC32, 7z CRC, gzip CRC,
     * tar headers). Returns how many files were checked; throws IOException if anything is wrong.
     * Note: a plain .tar has no checksum over file contents, so there only structure and length are checked.
     */
    fun verify(archive: File, password: CharArray? = null, progress: Progress = Progress {}): Int {
        val fmt = Format.detect(archive) ?: throw IOException("Unsupported archive type")
        var files = 0
        val buf = ByteArray(64 * 1024)
        try {
            when (fmt) {
                Format.ZIP -> {
                    val zf = ZipFile(archive)
                    if (zf.isEncrypted) zf.setPassword(password ?: throw IOException("A password is needed to verify this archive"))
                    for (h in zf.fileHeaders) {
                        if (h.isDirectory) continue
                        progress.onFile(h.fileName)
                        val crc = CRC32(); var total = 0L
                        zf.getInputStream(h).use { i ->
                            while (true) { val n = i.read(buf); if (n < 0) break; crc.update(buf, 0, n); total += n }
                        }
                        if (total != h.uncompressedSize) throw IOException("${h.fileName}: expected ${h.uncompressedSize} bytes, read $total")
                        // AES entries store no CRC (their authentication code is checked while reading above).
                        if (h.encryptionMethod != EncryptionMethod.AES && crc.value != h.crc) throw IOException("${h.fileName}: checksum mismatch")
                        files++
                    }
                }
                Format.SEVEN_Z -> {
                    val b = SevenZFile.builder().setFile(archive)
                    if (password != null && password.isNotEmpty()) b.setPassword(password)
                    b.get().use { z ->
                        var e: SevenZArchiveEntry? = z.nextEntry
                        while (e != null) {
                            if (!e.isDirectory) {
                                progress.onFile(e.name)
                                var total = 0L
                                while (true) { val n = z.read(buf); if (n < 0) break; total += n }
                                if (total != e.size) throw IOException("${e.name}: expected ${e.size} bytes, read $total")
                                files++
                            }
                            e = z.nextEntry
                        }
                    }
                }
                Format.TAR, Format.TAR_GZ -> openStream(archive).use { s ->
                    var e: ArchiveEntry? = s.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            progress.onFile(e.name)
                            var total = 0L
                            while (true) { val n = s.read(buf); if (n < 0) break; total += n }
                            if (total != e.size) throw IOException("${e.name}: expected ${e.size} bytes, read $total")
                            files++
                        }
                        e = s.nextEntry
                    }
                }
                Format.RAR -> throw IOException("Verifying RAR archives isn't supported")
            }
        } catch (e: ZipException) {
            throw IOException(e.message ?: "The archive is damaged", e)
        }
        return files
    }

    private fun zipEncrypted(sources: List<File>, dest: File, password: CharArray, progress: Progress, legacy: Boolean) {
        dest.delete()
        // zip4j mutates the parameters it is given while adding a folder, which used to leak a path
        // prefix into later entries, so every source gets its own fresh ZipParameters.
        fun params() = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            isEncryptFiles = true
            if (legacy) {
                // "ZipCrypto": weak, but the only password zip that Windows Explorer and macOS open natively.
                encryptionMethod = EncryptionMethod.ZIP_STANDARD
            } else {
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        ZipFile(dest, password).use { zf ->
            sources.forEach {
                progress.onFile(it.name)
                if (it.isDirectory) zf.addFolder(it, params().apply { isIncludeRootFolder = true })
                else zf.addFile(it, params().apply { fileNameInZip = it.name })
            }
        }
    }

    private fun walk(sources: List<File>, visit: (File, String) -> Unit) {
        fun rec(f: File, name: String) {
            visit(f, name)
            if (f.isDirectory) f.listFiles()?.sortedBy { it.name }?.forEach { rec(it, "$name/${it.name}") }
        }
        sources.forEach { rec(it, it.name) }
    }

    internal fun safeTarget(root: File, entryName: String): File {
        val t = File(root, entryName).canonicalFile
        if (t.path != root.path && !t.path.startsWith(root.path + File.separator))
            throw IOException("Blocked unsafe entry path: $entryName")
        return t
    }

    private fun openStream(archive: File): ArchiveInputStream<out ArchiveEntry> {
        val raw: InputStream = BufferedInputStream(archive.inputStream())
        return when (Format.detect(archive)) {
            Format.TAR -> TarArchiveInputStream(raw)
            Format.TAR_GZ -> TarArchiveInputStream(GZIPInputStream(raw))
            else -> throw IOException("Unsupported archive type")
        }
    }
}
