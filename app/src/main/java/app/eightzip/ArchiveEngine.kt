package app.eightzip

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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class Format(val label: String, val ext: String, val supportsPassword: Boolean) {
    SEVEN_Z("7z", "7z", true),
    ZIP("zip", "zip", true),
    TAR("tar", "tar", false),
    TAR_GZ("tar.gz", "tar.gz", false);

    companion object {
        fun detect(file: File): Format? {
            val n = file.name.lowercase()
            return when {
                n.endsWith(".7z") -> SEVEN_Z
                n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") -> ZIP
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar") -> TAR
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

/** Pure-JVM archive logic (no Android dependencies) so it can be unit tested. */
object ArchiveEngine {

    /** Lists the contents of an archive. File names are readable without a password. */
    fun list(archive: File): List<EntryInfo> {
        val out = mutableListOf<EntryInfo>()
        when (Format.detect(archive) ?: throw IOException("Unsupported archive type")) {
            Format.ZIP -> ZipFile(archive).fileHeaders.forEach {
                out += EntryInfo(it.fileName, it.uncompressedSize, it.isDirectory)
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
    fun extract(archive: File, destDir: File, progress: Progress = Progress {}, password: CharArray? = null) {
        val existed = destDir.exists()
        try {
            destDir.mkdirs()
            val root = destDir.canonicalFile
            when (Format.detect(archive) ?: throw IOException("Unsupported archive type")) {
                Format.ZIP -> extractZip(archive, root, progress, password)
                Format.SEVEN_Z -> extract7z(archive, root, progress, password)
                else -> openStream(archive).use { s ->
                    var e: ArchiveEntry? = s.nextEntry
                    while (e != null) {
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

    private fun extractZip(archive: File, root: File, progress: Progress, password: CharArray?) {
        val zf = ZipFile(archive)
        if (zf.isEncrypted) {
            if (password == null || password.isEmpty()) throw PasswordRequiredException(archive.name)
            zf.setPassword(password)
            // Validate every name first, then let zip4j decrypt and write.
            zf.fileHeaders.forEach { safeTarget(root, it.fileName); progress.onFile(it.fileName) }
            try {
                zf.extractAll(root.path)
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.WRONG_PASSWORD) throw WrongPasswordException()
                throw IOException(e.message, e)
            }
        } else {
            ZipArchiveInputStream(BufferedInputStream(archive.inputStream())).use { s ->
                var e: ArchiveEntry? = s.nextEntry
                while (e != null) {
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

    private fun extract7z(archive: File, root: File, progress: Progress, password: CharArray?) {
        val builder = SevenZFile.builder().setFile(archive)
        if (password != null && password.isNotEmpty()) builder.setPassword(password)
        try {
            builder.get().use { z ->
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) {
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
    fun compress(sources: List<File>, dest: File, format: Format, progress: Progress = Progress {}, password: CharArray? = null) {
        val pw = password?.takeIf { it.isNotEmpty() }
        if (pw != null && !format.supportsPassword) throw IOException("${format.label} archives can't be password protected")
        dest.parentFile?.mkdirs()
        when (format) {
            Format.ZIP -> if (pw != null) zipEncrypted(sources, dest, pw, progress) else
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
                    z.putArchiveEntry(z.createArchiveEntry(f, if (f.isDirectory) "$name/" else name))
                    if (f.isFile) f.inputStream().use { i ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = i.read(buf); if (n < 0) break; z.write(buf, 0, n) }
                    }
                    z.closeArchiveEntry()
                }
            }
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

    private fun zipEncrypted(sources: List<File>, dest: File, password: CharArray, progress: Progress) {
        dest.delete()
        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }
        ZipFile(dest, password).use { zf ->
            sources.forEach {
                progress.onFile(it.name)
                if (it.isDirectory) zf.addFolder(it, params) else zf.addFile(it, params)
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

    private fun safeTarget(root: File, entryName: String): File {
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
