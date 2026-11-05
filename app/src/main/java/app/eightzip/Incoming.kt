package app.eightzip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import java.io.File

/** Turns "open with" / "share to" intents into archive files 8zip can read. */
object Incoming {

    private fun tmpDir(ctx: Context) = File(ctx.cacheDir, "incoming")

    /** True for the temporary copies we made from content:// links. */
    fun isTemp(ctx: Context, f: File): Boolean =
        runCatching { f.canonicalPath.startsWith(tmpDir(ctx).canonicalPath + File.separator) }.getOrDefault(false)

    fun isIncoming(intent: Intent?): Boolean = intent != null && uris(intent).isNotEmpty()

    private fun uris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    /** Call off the main thread: copying a big archive takes a while. */
    fun resolve(ctx: Context, intent: Intent, root: File): List<File> {
        val all = uris(intent)
        if (all.isEmpty()) return emptyList()
        tmpDir(ctx).deleteRecursively() // drop copies from earlier opens
        return all.mapNotNull { runCatching { toFile(ctx, it, root) }.getOrNull() }
    }

    private fun toFile(ctx: Context, uri: Uri, root: File): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        directPath(uri, root)?.takeIf { it.isFile && it.canRead() }?.let { return it } // read in place, no copy

        val name = File(displayName(ctx, uri) ?: uri.lastPathSegment ?: "archive").name.ifBlank { "archive" }
        val dir = tmpDir(ctx).apply { mkdirs() }
        var out = File(dir, name)
        var n = 1
        while (out.exists()) { out = File(dir, "${File(name).nameWithoutExtension} ($n)${File(name).extension.let { if (it.isEmpty()) "" else ".$it" }}"); n++ }
        ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: return null

        // A download called "file" or "download.bin" still opens if the contents say it's an archive.
        if (Format.detect(out) == null) {
            val fmt = ArchiveEngine.sniff(out) ?: return out
            val renamed = File(dir, out.name + "." + fmt.ext)
            if (out.renameTo(renamed)) out = renamed
        }
        return out
    }

    /** File managers hand over document ids that map straight onto a path we can read with all-files access. */
    private fun directPath(uri: Uri, root: File): File? = runCatching {
        if (!DocumentsContract.isDocumentUri(null, uri) && uri.authority.isNullOrEmpty()) return null
        val id = DocumentsContract.getDocumentId(uri)
        when (uri.authority) {
            "com.android.externalstorage.documents" ->
                if (id.startsWith("primary:")) File(root, id.removePrefix("primary:")) else null
            "com.android.providers.downloads.documents" ->
                if (id.startsWith("raw:")) File(id.removePrefix("raw:")) else null
            else -> null
        }
    }.getOrNull()

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}
