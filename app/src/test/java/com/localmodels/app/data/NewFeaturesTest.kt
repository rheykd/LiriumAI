package com.localmodels.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NewFeaturesTest {
    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("lirium-features-").toFile().canonicalFile
        try { action(root) } finally { root.deleteRecursively() }
    }
    @Test fun portableArchiveRestoresImagesDocumentsSourcesAndStatisticsAndCanMerge() = fixture { root ->
        val source = ChatStore(File(root, "original"))
        val chat = ChatRecord(title = "перенос")
        val image = File(source.attachmentDirectory(chat.id).apply { mkdirs() }, "photo.png").apply { writeBytes(byteArrayOf(1,2,3)) }
        val document = File(image.parentFile, "book.pdf").apply { writeText("PDF bytes") }
        val stats = GenerationStats(150, 700, 12, 400, 12345678, "test model")
        val ref = SourceRef("Заголовок", "https://example.com/page", "использованная выдержка")
        val saved = chat.copy(messages = listOf(
            ChatMessage(1, true, "проверь", listOf(image), listOf("book.pdf"), attachedFiles = listOf(Attachment(document, "book.pdf", "application/pdf", false))),
            ChatMessage(2, false, "ответ", sources = listOf(ref), stats = stats)),
            context = listOf(ChatTurn("user", "вопрос"), ChatTurn("assistant", "ответ")))
        val output = ByteArrayOutputStream()
        ChatArchive.export(ChatSnapshot(chat.id, listOf(saved)), source, File(root, "export"), output)
        val unpack = File(root, "import")
        val restored = ChatArchive.read(ByteArrayInputStream(output.toByteArray()), unpack).chats.single()
        assertArrayEquals(image.readBytes(), restored.messages.first().images.single().readBytes())
        assertEquals("PDF bytes", restored.messages.first().attachedFiles.single().file.readText())
        assertEquals(listOf(ref), restored.messages.last().sources)
        assertEquals(stats, restored.messages.last().stats)
        val target = ChatStore(File(root, "target"))
        val existing = ChatRecord(title = "сохранить существующий")
        val a = ChatArchive.remap(restored, ChatStore(unpack), target, UUID.randomUUID().toString())
        val b = ChatArchive.remap(restored, ChatStore(unpack), target, UUID.randomUUID().toString())
        target.save(ChatSnapshot(existing.id, listOf(existing, a, b)))
        val loaded = target.load()!!
        assertEquals(3, loaded.chats.size)
        assertEquals(3, loaded.chats.map { it.id }.distinct().size)
        assertEquals("сохранить существующий", loaded.chats.first().title)
        assertNotEquals(a.messages.first().images.single(), b.messages.first().images.single())
    }
    private fun zip(name: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(bytes); it.closeEntry() }
    }.toByteArray()
    @Test fun archiveRejectsTraversalAndMissingAttachment() = fixture { root ->
        assertThrows(IllegalArgumentException::class.java) {
            ChatArchive.read(ByteArrayInputStream(zip("../escape.txt", "no".toByteArray())), File(root,"traversal"))
        }
        assertFalse(File(root,"escape.txt").exists())
        val store = ChatStore(File(root,"manifest"))
        val c = ChatRecord()
        val photo = File(store.attachmentDirectory(c.id).apply { mkdirs() },"missing.png").apply { writeText("photo") }
        store.save(ChatSnapshot(c.id,listOf(c.copy(messages=listOf(ChatMessage(1,true,"photo",listOf(photo)))))))
        assertThrows(IllegalArgumentException::class.java) {
            ChatArchive.read(ByteArrayInputStream(zip("chats.json",File(store.root,"chats.json").readBytes())),File(root,"missing"))
        }
    }
    @Test fun wifiClientsHaveIndependentSecretsHistoriesAndRevocation() {
        val registry = LanSessions()
        val a = registry.create("phone A", "192.168.1.2")
        val b = registry.create("phone B", "192.168.1.3")
        a.history = listOf(ChatTurn("user", "secret A"))
        b.history = listOf(ChatTurn("user", "secret B"))
        assertNotEquals(a.token,b.token)
        assertNotEquals(a.id,a.token)
        assertEquals("secret B",registry.find(b.token)!!.history.single().text)
        registry.revoke(a.id)
        assertNull(registry.find(a.token))
        assertTrue(a.control.cancelled)
        assertTrue(a.history.isEmpty())
        assertEquals("secret B",registry.find(b.token)!!.history.single().text)
        assertFalse(b.control.cancelled)
        assertEquals(1,registry.clients().size)
    }
    @Test fun wifiIdleSessionsExpireAndActiveRequestIsRetained() {
        var now = 0L
        val registry = LanSessions { now }
        val idle = registry.create("idle", "ip1")
        val busy = registry.create("busy", "ip2").also { it.busy.set(true) }
        now += 8L * 24 * 60 * 60 * 1000
        assertNull(registry.find(idle.token))
        assertNotNull(registry.find(busy.token))
    }
    @Test fun capacityWarningsDistinguishRamAndDiskAndRemainEstimates() {
        val model = CatalogModel("m","m","3B","Q4",true,2048,2L*1024*1024*1024,"MIT",emptyList(),recommendedRamGb=8)
        val small = Compatibility.assess(model, DeviceCapacity(4L*1024*1024*1024,128L*1024*1024,1))
        assertEquals(3,small.warnings.size)
        assertTrue(small.estimatedRam > model.bytes)
        val adequate = Compatibility.assess(model,DeviceCapacity(16L*1024*1024*1024,12L*1024*1024*1024,10L*1024*1024*1024))
        assertTrue(adequate.warnings.isEmpty())
    }
    @Test fun usedSourceIncludesOnlySelectedEvidenceAndNoAdditionalCrawl() = runBlocking {
        var calls = 0
        val prompt = Web.preparePrompt("Найди в интернете рекурсию",true,
            searcher={ "Рекурсия\nhttps://example.com/recursion\nРекурсия — функция вызывает себя." },
            fetcher={ calls++; "Рекурсия — функция вызывает себя. Рекурсия используется для обхода дерева." })
        assertEquals(1,calls)
        assertEquals("https://example.com/recursion",prompt.sources.single().url)
        assertTrue(prompt.sources.single().excerpt.contains("Рекурсия"))
        assertNull(Web.followUp("SEARCH ещё",prompt))
        assertEquals(1,calls)
    }
    @Test fun cancellationPreventsLookupBeforeNetworkAndTokenRateIsNotEstimatedFromText() {
        val control = RequestControl().also { it.cancel() }
        assertThrows(IllegalStateException::class.java) { runBlocking { Web.preparePrompt("найди погоду",true,control=control,searcher={ error("must not run") }) } }
        assertNull(GenerationStats(100,1000,null,900,100,"api").tokensPerSecond)
        assertEquals(20.0,GenerationStats(100,1000,18,900,100,"local").tokensPerSecond!!,0.001)
    }
}
