package app.eightzip

import java.io.File
import java.io.IOException
import java.util.Properties
import kotlin.random.Random

/**
 * A recycle bin kept in [dir] (on the same volume as the files, so moving in and out is an instant rename).
 *
 * Layout: dir/<id>/data/<original name>   the trashed file or folder
 *         dir/<id>/info.properties        where it came from and when it was deleted
 */
class Trash(val dir: File) {

    data class Item(
        val id: String,
        val name: String,
        val originalPath: String,
        val deletedAt: Long,
        val isDirectory: Boolean,
        val size: Long
    )

    /** Moves [f] into the trash. Throws (leaving [f] untouched) if it can't be moved. */
    fun moveToTrash(f: File): Item {
        if (!f.exists() && !isSymlink(f)) throw IOException("${f.name} no longer exists")
        val trashPath = dir.canonicalPath
        val fPath = File(f.parentFile?.canonicalFile ?: f.absoluteFile, f.name).path
        if (fPath == trashPath || fPath.startsWith(trashPath + File.separator)) throw IOException("${f.name} is already in the trash")

        val id = "${System.currentTimeMillis()}-${Random.nextInt(100000, 999999)}"
        val slot = File(dir, id)
        val data = File(slot, "data")
        if (!data.mkdirs()) throw IOException("Couldn't create the trash folder")
        val isDir = f.isDirectory
        try {
            val props = Properties().apply {
                setProperty("path", f.absolutePath)
                setProperty("deletedAt", System.currentTimeMillis().toString())
                setProperty("dir", isDir.toString())
            }
            File(slot, "info.properties").outputStream().use { props.store(it, null) }
            if (!f.renameTo(File(data, f.name))) throw IOException("Couldn't move ${f.name} to the trash")
        } catch (e: IOException) {
            ArchiveEngine.deleteTree(slot)
            throw e
        }
        return Item(id, f.name, f.absolutePath, props(slot)?.getProperty("deletedAt")?.toLongOrNull() ?: 0L, isDir, 0L)
    }

    /** Newest first. Sizes are computed here, so call it off the main thread. */
    fun list(): List<Item> {
        val slots = dir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return slots.mapNotNull { slot ->
            val payload = File(slot, "data").listFiles()?.firstOrNull() ?: return@mapNotNull null
            val p = props(slot) ?: return@mapNotNull null
            Item(
                id = slot.name,
                name = payload.name,
                originalPath = p.getProperty("path") ?: payload.name,
                deletedAt = p.getProperty("deletedAt")?.toLongOrNull() ?: 0L,
                isDirectory = payload.isDirectory && !isSymlink(payload),
                size = sizeOf(payload)
            )
        }.sortedByDescending { it.deletedAt }
    }

    /**
     * Puts the item back where it was (recreating missing folders). If something now has that name,
     * the restored copy gets a " (1)" style suffix instead of overwriting it. Returns where it ended up.
     */
    fun restore(id: String): File {
        val slot = File(dir, id)
        val payload = File(slot, "data").listFiles()?.firstOrNull() ?: throw IOException("That item is no longer in the trash")
        val original = File(props(slot)?.getProperty("path") ?: throw IOException("Missing restore information"))
        val parent = original.parentFile ?: throw IOException("Unknown original folder")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Couldn't recreate ${parent.path}")
        var target = original
        var n = 1
        while (target.exists() || isSymlink(target)) {
            val base = original.nameWithoutExtension
            val ext = original.extension.let { if (it.isEmpty() || original.isDirectory) "" else ".$it" }
            target = File(parent, "$base ($n)$ext")
            n++
        }
        if (!payload.renameTo(target)) throw IOException("Couldn't restore ${payload.name}")
        ArchiveEngine.deleteTree(slot)
        return target
    }

    fun deletePermanently(id: String) { ArchiveEngine.deleteTree(File(dir, id)) }

    fun empty() { dir.listFiles()?.forEach { ArchiveEngine.deleteTree(it) } }

    /** Permanently removes everything deleted before [cutoffMillis]. Returns how many items went. */
    fun purgeDeletedBefore(cutoffMillis: Long): Int {
        var n = 0
        dir.listFiles()?.filter { it.isDirectory }?.forEach { slot ->
            val at = props(slot)?.getProperty("deletedAt")?.toLongOrNull() ?: 0L
            if (at < cutoffMillis) { ArchiveEngine.deleteTree(slot); n++ }
        }
        return n
    }

    private fun props(slot: File): Properties? = runCatching {
        Properties().also { p -> File(slot, "info.properties").inputStream().use { p.load(it) } }
    }.getOrNull()

    private fun isSymlink(f: File): Boolean {
        val p = f.parentFile ?: return false
        return File(p.canonicalFile, f.name).let { it.canonicalPath != it.absolutePath }
    }

    private fun sizeOf(f: File): Long {
        if (isSymlink(f)) return 0L
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    companion object { const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000 }
}
