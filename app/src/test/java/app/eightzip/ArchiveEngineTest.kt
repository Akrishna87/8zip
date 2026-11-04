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

class PasswordArchiveTest {
    private fun fixture(): Pair<java.io.File, java.io.File> {
        val tmp = Files.createTempDirectory("8zip-pw").toFile()
        val src = tmp.resolve("secret").apply { mkdirs() }
        src.resolve("a.txt").writeText("top secret")
        src.resolve("sub").mkdirs()
        src.resolve("sub/b.txt").writeText("also secret")
        return tmp to src
    }

    @Test
    fun encryptedRoundTrip() {
        for (fmt in listOf(Format.SEVEN_Z, Format.ZIP)) {
            val (tmp, src) = fixture()
            val archive = tmp.resolve("pw.${fmt.ext}")
            ArchiveEngine.compress(listOf(src), archive, fmt, password = "p@ss word".toCharArray())
            // names stay listable without a password
            assertTrue(ArchiveEngine.list(archive).any { it.name.endsWith("a.txt") })
            // ciphertext must not contain the plaintext
            assertTrue(!String(archive.readBytes(), Charsets.ISO_8859_1).contains("top secret"))
            val dest = tmp.resolve("ok")
            ArchiveEngine.extract(archive, dest, password = "p@ss word".toCharArray())
            assertEquals("top secret", dest.resolve("secret/a.txt").readText())
            assertEquals("also secret", dest.resolve("secret/sub/b.txt").readText())
        }
    }

    @Test
    fun missingPasswordIsReported() {
        for (fmt in listOf(Format.SEVEN_Z, Format.ZIP)) {
            val (tmp, src) = fixture()
            val archive = tmp.resolve("pw.${fmt.ext}")
            ArchiveEngine.compress(listOf(src), archive, fmt, password = "pw".toCharArray())
            val dest = tmp.resolve("nope")
            try { ArchiveEngine.extract(archive, dest); org.junit.Assert.fail("${fmt.label}: expected PasswordRequiredException") }
            catch (e: PasswordRequiredException) { }
            assertTrue("dest should be cleaned up", !dest.exists())
        }
    }

    @Test
    fun wrongPasswordIsReported() {
        for (fmt in listOf(Format.SEVEN_Z, Format.ZIP)) {
            val (tmp, src) = fixture()
            val archive = tmp.resolve("pw.${fmt.ext}")
            ArchiveEngine.compress(listOf(src), archive, fmt, password = "right".toCharArray())
            val dest = tmp.resolve("bad")
            try { ArchiveEngine.extract(archive, dest, password = "wrong".toCharArray()); org.junit.Assert.fail("${fmt.label}: expected WrongPasswordException") }
            catch (e: WrongPasswordException) { }
            assertTrue("dest should be cleaned up", !dest.exists())
        }
    }

    @Test
    fun tarRejectsPassword() {
        val (tmp, src) = fixture()
        try { ArchiveEngine.compress(listOf(src), tmp.resolve("x.tar"), Format.TAR, password = "pw".toCharArray()); org.junit.Assert.fail() }
        catch (e: java.io.IOException) { }
    }
}
