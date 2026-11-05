package app.eightzip

import java.io.File

/** Finds the newest archive in a downloads folder (pure JVM so it can be unit tested). */
object LatestDownload {

    /**
     * Ignores folders, hidden files, empty files, installers (.apk/.jar), partial downloads and anything
     * touched in the last [settleMillis] (probably still being written).
     */
    fun find(downloads: File, now: Long = System.currentTimeMillis(), settleMillis: Long = 2_000): File? =
        downloads.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 }
            ?.filter { !it.name.endsWith(".apk", true) && !it.name.endsWith(".jar", true) }
            ?.filter { Format.detect(it) != null }
            ?.filter { now - it.lastModified() >= settleMillis }
            ?.maxByOrNull { it.lastModified() }
}
