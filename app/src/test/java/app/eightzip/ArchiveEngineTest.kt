package app.eightzip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArchiveEngineTest {
    @Test
    fun roundTripAllFormats() {
        val tmp = Files.createTempDirectory("8zip").toFile()
        val src = tmp.resolve("data").apply { mkdirs() }
        src.resolve("a.txt").writeText("hello")
        src.resolve("sub").mkdirs()
        src.resolve("sub/b.txt").writeText("world")

        for (fmt in Format.values()) {
            val archive = tmp.resolve("out.${fmt.ext}")
            ArchiveEngine.compress(listOf(src), archive, fmt)
            assertTrue(ArchiveEngine.list(archive).any { it.name.endsWith("a.txt") })
            val dest = tmp.resolve("x_${fmt.ext}")
            ArchiveEngine.extract(archive, dest)
            assertEquals("hello", dest.resolve("data/a.txt").readText())
            assertEquals("world", dest.resolve("data/sub/b.txt").readText())
        }
    }
}
