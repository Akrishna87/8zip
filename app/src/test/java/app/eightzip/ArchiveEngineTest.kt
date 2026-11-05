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
            override fun extract(archive: java.io.File, resolve: (String) -> java.io.File, progress: Progress, accept: (String) -> Boolean) {
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


class PartialExtractTest {
    private fun fixture(): Pair<java.io.File, java.io.File> {
        val tmp = Files.createTempDirectory("8zip-part").toFile()
        val src = tmp.resolve("data").apply { mkdirs() }
        src.resolve("a.txt").writeText("A")
        src.resolve("c.txt").writeText("C")
        src.resolve("sub").mkdirs()
        src.resolve("sub/b.txt").writeText("B")
        src.resolve("sub/deep").mkdirs()
        src.resolve("sub/deep/d.txt").writeText("D")
        src.resolve("subway.txt").writeText("not inside sub")
        return tmp to src
    }

    private fun archives(): List<Triple<String, java.io.File, CharArray?>> {
        val (tmp, src) = fixture()
        val out = mutableListOf<Triple<String, java.io.File, CharArray?>>()
        for (fmt in Format.values().filter { it.canCreate }) {
            val f = tmp.resolve("plain.${fmt.ext}")
            ArchiveEngine.compress(listOf(src), f, fmt)
            out += Triple(fmt.label, f, null)
            if (fmt.supportsPassword) {
                val pw = "s3cret".toCharArray()
                val e = tmp.resolve("enc.${fmt.ext}")
                ArchiveEngine.compress(listOf(src), e, fmt, password = pw)
                out += Triple("${fmt.label}+password", e, pw)
            }
        }
        return out
    }

    @Test
    fun extractsOnlyTheSelectedFile() {
        for ((label, f, pw) in archives()) {
            val dest = f.parentFile.resolve("one_${f.name}")
            ArchiveEngine.extract(f, dest, password = pw, only = listOf("data/a.txt"))
            assertEquals("$label a.txt", "A", dest.resolve("data/a.txt").readText())
            assertTrue("$label: c.txt must not be extracted", !dest.resolve("data/c.txt").exists())
            assertTrue("$label: sub must not be extracted", !dest.resolve("data/sub").exists())
        }
    }

    @Test
    fun selectingAFolderTakesEverythingInsideButNotLookalikes() {
        for ((label, f, pw) in archives()) {
            val dest = f.parentFile.resolve("dir_${f.name}")
            ArchiveEngine.extract(f, dest, password = pw, only = listOf("data/sub/"))
            assertEquals("$label b.txt", "B", dest.resolve("data/sub/b.txt").readText())
            assertEquals("$label d.txt", "D", dest.resolve("data/sub/deep/d.txt").readText())
            assertTrue("$label: a.txt must not be extracted", !dest.resolve("data/a.txt").exists())
            assertTrue("$label: 'subway.txt' shares a prefix with 'sub' but isn't inside it", !dest.resolve("data/subway.txt").exists())
        }
    }

    @Test
    fun noSelectionMeansEverything() {
        for ((label, f, pw) in archives()) {
            val dest = f.parentFile.resolve("all_${f.name}")
            ArchiveEngine.extract(f, dest, password = pw)
            assertEquals(label, "D", dest.resolve("data/sub/deep/d.txt").readText())
            assertEquals(label, "C", dest.resolve("data/c.txt").readText())
        }
    }

    @Test
    fun rarSelection() {
        val rar = Files.createTempDirectory("8zip-rarsel").toFile().resolve("t.rar")
        rar.writeBytes(javaClass.getResourceAsStream("/hello.rar")!!.readBytes())
        val hit = rar.parentFile.resolve("hit"); ArchiveEngine.extract(rar, hit, only = listOf("hello.txt"))
        assertEquals("hello from rar\n", hit.resolve("hello.txt").readText())
        val miss = rar.parentFile.resolve("miss"); ArchiveEngine.extract(rar, miss, only = listOf("other.txt"))
        assertTrue(!miss.resolve("hello.txt").exists())
    }
}


class DeleteOriginalsTest {
    private fun fixture(): Pair<java.io.File, List<java.io.File>> {
        val tmp = Files.createTempDirectory("8zip-orig").toFile()
        val folder = tmp.resolve("photos").apply { mkdirs() }
        folder.resolve("1.jpg").writeText("one"); folder.resolve("2.jpg").writeText("two")
        folder.resolve("sub").mkdirs(); folder.resolve("sub/3.jpg").writeText("three")
        val file = tmp.resolve("notes.txt").apply { writeText("notes") }
        return tmp to listOf(folder, file)
    }

    @Test
    fun originalsAreDeletedOnlyAfterAVerifiedArchive() {
        for (fmt in Format.values().filter { it.canCreate }) {
            val (tmp, sources) = fixture()
            val archive = tmp.resolve("out.${fmt.ext}")
            ArchiveEngine.compress(sources, archive, fmt)
            ArchiveEngine.deleteSourcesAfterCompress(sources, archive)
            assertTrue("${fmt.label}: originals should be gone", sources.none { it.exists() })
            val back = tmp.resolve("back_${fmt.ext}")
            ArchiveEngine.extract(archive, back)
            assertEquals(fmt.label, "three", back.resolve("photos/sub/3.jpg").readText())
            assertEquals(fmt.label, "notes", back.resolve("notes.txt").readText())
        }
    }

    @Test
    fun brokenArchiveKeepsTheOriginals() {
        val (tmp, sources) = fixture()
        val fake = tmp.resolve("fake.zip").apply { writeText("not really a zip") }
        try { ArchiveEngine.deleteSourcesAfterCompress(sources, fake); org.junit.Assert.fail("expected IOException") }
        catch (e: java.io.IOException) { }
        assertTrue("originals must survive", sources.all { it.exists() })
        val missing = tmp.resolve("missing.7z")
        try { ArchiveEngine.deleteSourcesAfterCompress(sources, missing); org.junit.Assert.fail("expected IOException") }
        catch (e: java.io.IOException) { }
        assertTrue(sources.all { it.exists() })
    }

    @Test
    fun incompleteArchiveKeepsTheOriginals() {
        val (tmp, sources) = fixture()
        val onlyOne = tmp.resolve("partial.zip")
        ArchiveEngine.compress(listOf(sources[1]), onlyOne, Format.ZIP)   // archive holds just notes.txt
        try { ArchiveEngine.deleteSourcesAfterCompress(sources, onlyOne); org.junit.Assert.fail("expected IOException") }
        catch (e: java.io.IOException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("incomplete")) }
        assertTrue(sources.all { it.exists() })
    }

    @Test
    fun archiveInsideTheSourcesIsRefused() {
        val (tmp, sources) = fixture()
        val inside = sources[0].resolve("inside.zip")
        ArchiveEngine.compress(listOf(sources[1]), inside, Format.ZIP)
        try { ArchiveEngine.deleteSourcesAfterCompress(sources, inside); org.junit.Assert.fail("expected IOException") }
        catch (e: java.io.IOException) { }
        assertTrue(sources.all { it.exists() })
    }
}


class TrashTest {
    private fun setup(): Triple<java.io.File, Trash, java.io.File> {
        val tmp = Files.createTempDirectory("8zip-trash").toFile()
        val docs = tmp.resolve("docs").apply { mkdirs() }
        return Triple(tmp, Trash(tmp.resolve(".8zip-trash")), docs)
    }

    @Test
    fun trashAndRestoreAFileAndAFolder() {
        val (_, trash, docs) = setup()
        val file = docs.resolve("a.txt").apply { writeText("A") }
        val folder = docs.resolve("pics").apply { mkdirs() }
        folder.resolve("1.jpg").writeText("one"); folder.resolve("deep").mkdirs(); folder.resolve("deep/2.jpg").writeText("two")

        trash.moveToTrash(file); trash.moveToTrash(folder)
        assertTrue(!file.exists() && !folder.exists())
        val items = trash.list()
        assertEquals(setOf("a.txt", "pics"), items.map { it.name }.toSet())
        assertTrue(items.first { it.name == "pics" }.isDirectory)
        assertEquals(6L, items.first { it.name == "pics" }.size)   // "one" + "two"

        for (it in items) trash.restore(it.id)
        assertEquals("A", file.readText())
        assertEquals("two", folder.resolve("deep/2.jpg").readText())
        assertTrue(trash.list().isEmpty())
    }

    @Test
    fun restoreNeverOverwritesAndRecreatesMissingFolders() {
        val (_, trash, docs) = setup()
        val f = docs.resolve("report.txt").apply { writeText("old") }
        val item = trash.moveToTrash(f)
        f.writeText("new one made since")                       // something took the name meanwhile
        val back = trash.restore(item.id)
        assertEquals("report (1).txt", back.name)
        assertEquals("old", back.readText())
        assertEquals("new one made since", f.readText())        // untouched

        val g = docs.resolve("gone/inner.txt").apply { parentFile.mkdirs(); writeText("G") }
        val item2 = trash.moveToTrash(g)
        docs.resolve("gone").deleteRecursively()                // original folder no longer exists
        assertEquals("G", trash.restore(item2.id).readText())
        assertTrue(docs.resolve("gone/inner.txt").exists())
    }

    @Test
    fun deletePermanentlyEmptyAndPurge() {
        val (_, trash, docs) = setup()
        val ids = (1..3).map { trash.moveToTrash(docs.resolve("f$it").apply { writeText("x") }).id }
        trash.deletePermanently(ids[0])
        assertEquals(2, trash.list().size)
        assertEquals(0, trash.purgeDeletedBefore(0))                        // nothing is older than the epoch
        assertEquals(2, trash.list().size)
        assertEquals(2, trash.purgeDeletedBefore(System.currentTimeMillis() + 10_000))   // everything is older than "soon"
        assertTrue(trash.list().isEmpty())
        trash.moveToTrash(docs.resolve("g").apply { writeText("x") }); trash.empty()
        assertTrue(trash.list().isEmpty())
    }

    @Test
    fun refusesToTrashSomethingAlreadyInTheTrash() {
        val (_, trash, docs) = setup()
        trash.moveToTrash(docs.resolve("a").apply { writeText("x") })
        val inside = trash.dir.listFiles()!!.first()
        try { trash.moveToTrash(inside); org.junit.Assert.fail("expected IOException") } catch (e: java.io.IOException) { }
        try { trash.moveToTrash(docs.resolve("missing")); org.junit.Assert.fail("expected IOException") } catch (e: java.io.IOException) { }
    }

    @Test
    fun symlinksAreMovedNotFollowed() {
        val (tmp, trash, docs) = setup()
        val keep = tmp.resolve("keep").apply { mkdirs() }.also { it.resolve("precious.txt").writeText("safe") }
        val folder = docs.resolve("withlink").apply { mkdirs() }
        Files.createSymbolicLink(folder.resolve("link").toPath(), keep.toPath())
        val item = trash.moveToTrash(folder)
        trash.deletePermanently(item.id)
        assertEquals("safe", keep.resolve("precious.txt").readText())
    }

    @Test
    fun deleteOriginalsCanUseTheTrash() {
        val (tmp, trash, docs) = setup()
        val a = docs.resolve("a.txt").apply { writeText("A") }
        val archive = tmp.resolve("out.zip")
        ArchiveEngine.compress(listOf(a), archive, Format.ZIP)
        ArchiveEngine.deleteSourcesAfterCompress(listOf(a), archive) { trash.moveToTrash(it) }
        assertTrue(!a.exists())
        trash.restore(trash.list().single().id)
        assertEquals("A", a.readText())
    }
}
