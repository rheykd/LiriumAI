package com.localmodels.app.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedFileToolsTest {
    private fun fixture(test: suspend (File, SharedFileTools) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("lirium-file-test-").toFile().canonicalFile
        try { test(root, SharedFileTools(mapOf("primary" to root)) { true }) }
        finally {
            check(root.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
            root.deleteRecursively()
        }
    }

    private fun write(path: String, text: String) = "WRITE_FILE " + JSONObject().put("path", path).put("text", text)
    private fun delete(path: String) = "DELETE_FILE " + JSONObject().put("path", path)

    @Test fun listsAndReadsSharedText() = fixture { root, tools ->
        File(root, "note.txt").writeText("Привет")
        assertTrue(tools.execute("LIST_FILES /", { error("reads do not ask to write") })!!.contains("primary"))
        assertTrue(tools.execute("LIST_FILES primary", { false })!!.contains("note.txt"))
        assertTrue(tools.execute("READ_FILE primary/note.txt", { false })!!.contains("Привет"))
        assertTrue(tools.initialContext("прочитай мой файл").contains("note.txt"))
        assertEquals("", tools.initialContext("Привет"))
        assertNull(tools.execute("обычный ответ", { false }))
    }

    @Test fun cancellationPreservesOriginalAndApprovalShowsExactChange() = fixture { root, tools ->
        val file = File(root, "note.txt").apply { writeText("old") }
        assertTrue(tools.execute(write("primary/note.txt", "new")) { false }!!.contains("отменил"))
        assertEquals("old", file.readText())
        val result = tools.execute(write("primary/note.txt", "new")) { change ->
            assertEquals(file.canonicalPath, change.path)
            assertEquals("old", change.before)
            assertEquals("new", change.after)
            assertEquals("old", file.readText())
            true
        }
        assertTrue(result!!.startsWith("Выполнено"))
        assertEquals("new", file.readText())
    }

    @Test fun newFileIsNotCreatedBeforeApproval() = fixture { root, tools ->
        val file = File(root, "new.txt")
        tools.execute(write("primary/new.txt", "created")) {
            assertFalse(file.exists())
            true
        }
        assertEquals("created", file.readText())
    }

    @Test fun revokedPermissionStopsEvenApprovedWrite() = fixture { root, _ ->
        var granted = true
        val tools = SharedFileTools(mapOf("primary" to root)) { granted }
        val file = File(root, "note.txt").apply { writeText("old") }
        val result = tools.execute(write("primary/note.txt", "new")) { granted = false; true }
        assertTrue(result!!.startsWith("Ошибка"))
        assertEquals("old", file.readText())
        assertTrue(tools.execute("READ_FILE primary/note.txt", { error("must not confirm") })!!.contains("выключен"))
    }

    @Test fun concurrentFileChangeRequiresNewConfirmation() = fixture { root, tools ->
        val file = File(root, "note.txt").apply { writeText("old") }
        val result = tools.execute(write("primary/note.txt", "new")) { file.writeText("external"); true }
        assertTrue(result!!.contains("файл изменился"))
        assertEquals("external", file.readText())
    }

    @Test fun traversalAndProtectedDirectoriesAreRejected() = fixture { root, tools ->
        File(root, "Android/data").mkdirs()
        for (path in listOf("primary/../outside.txt", "primary/Android/data", "primary/Android/obb/private.txt")) {
            assertTrue(path, tools.execute("READ_FILE $path", { error("no confirmation") })!!.startsWith("Ошибка"))
        }
    }

    @Test fun deleteOnlyRemovesConfirmedFile() = fixture { root, tools ->
        val file = File(root, "photo.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        tools.execute(delete("primary/photo.bin")) { false }
        assertTrue(file.exists())
        tools.execute(delete("primary/photo.bin")) { assertEquals("DELETE_FILE", it.operation); true }
        assertFalse(file.exists())
        assertTrue(tools.execute(delete("primary")) { error("directories cannot be deleted") }!!.startsWith("Ошибка"))
        assertTrue(root.exists())
    }
}
