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

        for (fmt in Format.values().filter { it.canCreate }) {
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

class RarTest {
    private fun rarFile(name: String = "t.rar"): java.io.File {
        val f = Files.createTempDirectory("8zip-rar").toFile().resolve(name)
        f.writeBytes(javaClass.getResourceAsStream("/hello.rar")!!.readBytes())
        return f
    }

    @Test
    fun listsAndExtractsRar4() {
        val rar = rarFile()
        assertEquals("hello.txt", ArchiveEngine.list(rar).single().name)
        val dest = rar.parentFile.resolve("out")
        ArchiveEngine.extract(rar, dest)
        assertEquals("hello from rar\n", dest.resolve("hello.txt").readText())
    }

    @Test
    fun rar5GivesFriendlyError() {
        val f = Files.createTempDirectory("8zip-rar5").toFile().resolve("v5.rar")
        f.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) + ByteArray(64))
        try { ArchiveEngine.list(f); org.junit.Assert.fail("expected IOException") }
        catch (e: java.io.IOException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("RAR5")) }
    }

    @Test
    fun garbageRarIsAnError() {
        val f = Files.createTempDirectory("8zip-bad").toFile().resolve("bad.rar")
        f.writeText("this is not a rar file at all")
        try { ArchiveEngine.extract(f, f.parentFile.resolve("o")); org.junit.Assert.fail("expected IOException") }
        catch (e: java.io.IOException) { }
    }

    @Test
    fun rarCannotBeCreated() {
        val tmp = Files.createTempDirectory("8zip-nocreate").toFile()
        try { ArchiveEngine.compress(listOf(tmp), tmp.resolve("x.rar"), Format.RAR); org.junit.Assert.fail() }
        catch (e: java.io.IOException) { }
    }
}


class Rar5AndDeleteTest {
    private fun rar5(): java.io.File {
        val f = Files.createTempDirectory("8zip-rar5b").toFile().resolve("v5.rar")
        f.writeBytes(javaClass.getResourceAsStream("/hello5.rar")!!.readBytes())
        return f
    }

    @Test
    fun detectsRar5Signature() {
        assertTrue(ArchiveEngine.isRar5(rar5()))
        val v4 = Files.createTempDirectory("8zip-v4").toFile().resolve("v4.rar")
        v4.writeBytes(javaClass.getResourceAsStream("/hello.rar")!!.readBytes())
        assertTrue(!ArchiveEngine.isRar5(v4))
    }

    @Test
    fun rar5WithoutBackendIsFriendlyError() {
        ArchiveEngine.rar5Reader = null
        try { ArchiveEngine.list(rar5()); org.junit.Assert.fail() }
        catch (e: java.io.IOException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("RAR5")) }
    }

    @Test
    fun rar5IsRoutedToTheBackendWithSafePaths() {
        val seen = mutableListOf<String>()
        ArchiveEngine.rar5Reader = object : Rar5Reader {
            override fun list(archive: java.io.File) = listOf(EntryInfo("x.txt", 1, false))
            override fun extract(archive: java.io.File, resolve: (String) -> java.io.File, progress: Progress) {
                val out = resolve("dir/x.txt"); out.parentFile.mkdirs(); out.writeText("via backend"); progress.onFile("dir/x.txt"); seen += "ok"
                try { resolve("../evil.txt"); org.junit.Assert.fail("traversal must be blocked") } catch (e: java.io.IOException) { seen += "blocked" }
            }
        }
        try {
            val rar = rar5()
            assertEquals("x.txt", ArchiveEngine.list(rar).single().name)
            val dest = rar.parentFile.resolve("o")
            ArchiveEngine.extract(rar, dest)
            assertEquals("via backend", dest.resolve("dir/x.txt").readText())
            assertEquals(listOf("ok", "blocked"), seen)
        } finally { ArchiveEngine.rar5Reader = null }
    }

    @Test
    fun deleteTreeRemovesFoldersButNeverFollowsSymlinks() {
        val tmp = Files.createTempDirectory("8zip-del").toFile()
        val keep = tmp.resolve("keep").apply { mkdirs() }
        keep.resolve("precious.txt").writeText("do not delete")
        val victim = tmp.resolve("victim").apply { mkdirs() }
        victim.resolve("a.txt").writeText("a")
        victim.resolve("sub").mkdirs(); victim.resolve("sub/b.txt").writeText("b")
        Files.createSymbolicLink(victim.resolve("link").toPath(), keep.toPath())
        ArchiveEngine.deleteTree(victim)
        assertTrue(!victim.exists())
        assertEquals("do not delete", keep.resolve("precious.txt").readText())
        val single = tmp.resolve("one.txt").apply { writeText("x") }
        ArchiveEngine.deleteTree(single)
        assertTrue(!single.exists())
    }
}
