package app.eightzip

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

enum class Format(val label: String, val ext: String) {
    SEVEN_Z("7z", "7z"),
    ZIP("zip", "zip"),
    TAR("tar", "tar"),
    TAR_GZ("tar.gz", "tar.gz");

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

fun interface Progress { fun onFile(name: String) }

/** Pure-JVM archive logic (no Android dependencies) so it can be unit tested. */
object ArchiveEngine {

    /** Lists the contents of an archive. */
    fun list(archive: File): List<EntryInfo> {
        val out = mutableListOf<EntryInfo>()
        when (Format.detect(archive) ?: throw IOException("Unsupported archive type")) {
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

    /** Extracts [archive] into [destDir]. Guards against path traversal ("zip slip"). */
    fun extract(archive: File, destDir: File, progress: Progress = Progress {}) {
        destDir.mkdirs()
        val root = destDir.canonicalFile
        when (Format.detect(archive) ?: throw IOException("Unsupported archive type")) {
            Format.SEVEN_Z -> SevenZFile.builder().setFile(archive).get().use { z ->
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
    }

    /** Compresses [sources] (files or folders) into [dest]. */
    fun compress(sources: List<File>, dest: File, format: Format, progress: Progress = Progress {}) {
        dest.parentFile?.mkdirs()
        when (format) {
            Format.ZIP -> ZipArchiveOutputStream(dest).use { z ->
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
            Format.SEVEN_Z -> SevenZOutputFile(dest).use { z ->
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
            Format.ZIP -> ZipArchiveInputStream(raw)
            Format.TAR -> TarArchiveInputStream(raw)
            Format.TAR_GZ -> TarArchiveInputStream(GZIPInputStream(raw))
            else -> throw IOException("Unsupported archive type")
        }
    }
}
