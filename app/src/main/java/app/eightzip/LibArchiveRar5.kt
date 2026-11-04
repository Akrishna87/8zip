package app.eightzip

import android.os.ParcelFileDescriptor
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.File
import java.io.IOException

/** RAR5 reading through libarchive (native). Unencrypted archives only: libarchive can't decrypt RAR5. */
object LibArchiveRar5 : Rar5Reader {

    private inline fun <T> withArchive(file: File, block: (Long) -> T): T {
        val a = try {
            Archive.readNew().also {
                Archive.readSupportFilterAll(it)
                Archive.readSupportFormatRar5(it)
                Archive.readOpenFileName(it, file.path.toByteArray(Charsets.UTF_8), 64 * 1024)
            }
        } catch (e: UnsatisfiedLinkError) {
            throw IOException("RAR5 support isn't available on this device's CPU")
        } catch (e: ArchiveException) {
            throw friendly(e)
        }
        try {
            return block(a)
        } catch (e: ArchiveException) {
            throw friendly(e)
        } finally {
            runCatching { Archive.readFree(a) }
        }
    }

    private fun friendly(e: ArchiveException): IOException {
        val m = e.message.orEmpty()
        return if (m.contains("ncrypt", ignoreCase = true) || m.contains("password", ignoreCase = true))
            IOException("Encrypted RAR5 archives aren't supported yet")
        else IOException("RAR5 error: $m", e)
    }

    private fun isDir(entry: Long) = (ArchiveEntry.filetype(entry) and ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFDIR

    override fun list(archive: File): List<EntryInfo> = withArchive(archive) { a ->
        val out = mutableListOf<EntryInfo>()
        while (true) {
            val e = Archive.readNextHeader(a)
            if (e == 0L) break
            out += EntryInfo(ArchiveEntry.pathnameUtf8(e).orEmpty(), ArchiveEntry.size(e), isDir(e))
        }
        out
    }

    override fun extract(archive: File, resolve: (String) -> File, progress: Progress) = withArchive(archive) { a ->
        while (true) {
            val e = Archive.readNextHeader(a)
            if (e == 0L) break
            val name = ArchiveEntry.pathnameUtf8(e).orEmpty()
            if (ArchiveEntry.isEncrypted(e)) throw IOException("Encrypted RAR5 archives aren't supported yet")
            val target = resolve(name)
            progress.onFile(name)
            when {
                isDir(e) -> target.mkdirs()
                (ArchiveEntry.filetype(e) and ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFREG -> {
                    target.parentFile?.mkdirs()
                    val pfd = ParcelFileDescriptor.open(
                        target,
                        ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                    )
                    try { Archive.readDataIntoFd(a, pfd.fd) } finally { pfd.close() }
                }
                else -> Unit // skip symlinks and special files
            }
        }
    }
}
