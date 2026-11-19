package com.localmodels.app.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ChatStoreTest {
    private fun fixture(action: (File, ChatStore) -> Unit) {
        val root = Files.createTempDirectory("lirium-chat-test-").toFile().canonicalFile
        try { action(root, ChatStore(File(root, "chats"))) }
        finally {
            check(root.parentFile == File(System.getProperty("java.io.tmpdir")!!).canonicalFile)
            root.deleteRecursively()
        }
    }

    @Test fun restartRestoresTwoIndependentHistoriesAndDrafts() = fixture { _, store ->
        val a = ChatRecord(title = "первый", messages = listOf(ChatMessage(1, true, "секрет первого")),
            context = listOf(ChatTurn("user", "секрет первого"), ChatTurn("assistant", "ответ первого")), draft = "черновик A")
        val b = ChatRecord(title = "второй", messages = listOf(ChatMessage(2, true, "вопрос второго")),
            context = listOf(ChatTurn("user", "вопрос второго"), ChatTurn("assistant", "ответ второго")), draft = "черновик B")
        store.save(ChatSnapshot(b.id, listOf(a, b)))
        val restored = ChatStore(store.root).load()!!
        assertEquals(b.id, restored.activeId)
        assertEquals(listOf(a, b), restored.chats)
        assertFalse(restored.chats[1].context.any { it.text.contains("первого") })
        assertFalse(restored.chats[0].context.any { it.text.contains("второго") })
        assertTrue(ChatRecord().context.isEmpty())
        assertNotEquals(a.id, ChatRecord().id)
    }

    @Test fun attachmentsSurviveRestartAndDeletingOneChatKeepsOthersAndOriginal() = fixture { root, store ->
        val original = File(root, "original.txt").apply { writeText("original") }
        val a = ChatRecord(title = "A")
        val b = ChatRecord(title = "B")
        val aFile = File(store.attachmentDirectory(a.id).apply { mkdirs() }, "a.txt").apply { writeText("copy A") }
        val bFile = File(store.attachmentDirectory(b.id).apply { mkdirs() }, "b.jpg").apply { writeText("copy B") }
        val aSaved = a.copy(attachments = listOf(Attachment(aFile, "a.txt", "text/plain", false)))
        val bSaved = b.copy(messages = listOf(ChatMessage(1, true, "фото", images = listOf(bFile))))
        store.save(ChatSnapshot(a.id, listOf(aSaved, bSaved)))
        assertEquals(aFile, ChatStore(store.root).load()!!.chats.first().attachments.single().file)
        store.save(ChatSnapshot(b.id, listOf(bSaved)), discardPrevious = true)
        store.deleteAttachments(a.id)
        assertFalse(aFile.exists())
        assertTrue(bFile.exists())
        assertEquals("original", original.readText())
        assertEquals(listOf(b.id), store.load()!!.chats.map { it.id })
    }

    @Test fun backupCannotResurrectDeletedChat() = fixture { _, store ->
        val a = ChatRecord(title = "deleted")
        val b = ChatRecord(title = "remaining")
        store.save(ChatSnapshot(a.id, listOf(a, b)))
        store.save(ChatSnapshot(b.id, listOf(b)), discardPrevious = true)
        File(store.root, "chats.json").writeText("interrupted file")
        assertEquals(listOf(b.id), store.load()!!.chats.map { it.id })
    }

    @Test fun invalidPrimaryUsesPreviousValidSnapshot() = fixture { _, store ->
        val a = ChatRecord(title = "saved")
        store.save(ChatSnapshot(a.id, listOf(a)))
        store.save(ChatSnapshot(a.id, listOf(a.copy(draft = "updated"))))
        File(store.root, "chats.json").writeText("invalid")
        assertEquals("saved", store.load()!!.chats.single().title)
    }

    @Test fun invalidIdsAndExternalAttachmentsAreRejected() = fixture { root, store ->
        assertThrows(IllegalArgumentException::class.java) { store.attachmentDirectory("../outside") }
        val external = File(root, "external.txt").apply { writeText("keep") }
        val a = ChatRecord(messages = listOf(ChatMessage(1, true, "image", images = listOf(external))))
        assertThrows(IllegalArgumentException::class.java) { store.save(ChatSnapshot(a.id, listOf(a))) }
        assertEquals("keep", external.readText())
    }

    @Test fun contextIsBoundedWithoutMixingOrStartingWithAssistant() {
        val turns = (1..30).flatMap { listOf(ChatTurn("user", "question $it"), ChatTurn("assistant", "answer $it")) }
        val selected = ChatHistory.bounded(turns)
        assertEquals(24, selected.size)
        assertEquals("user", selected.first().role)
        assertEquals("answer 30", selected.last().text)
        assertFalse(selected.any { it.text == "question 1" })
    }
}
