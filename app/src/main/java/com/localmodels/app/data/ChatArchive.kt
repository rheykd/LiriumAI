package com.localmodels.app.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable backup: manifest + attachment bytes, never preferences, API keys or model weights. */
object ChatArchive {
    const val MAX_TOTAL = 512L * 1024 * 1024
    const val MAX_ENTRY = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 10000

    fun remap(chat: ChatRecord, source: ChatStore, target: ChatStore, id: String): ChatRecord {
        val from = source.attachmentDirectory(chat.id).canonicalFile
        val to = target.attachmentDirectory(id).apply { mkdirs() }
        val mapping = mutableMapOf<String, File>()
        fun copy(file: File): File = mapping.getOrPut(file.canonicalPath) {
            require(file.canonicalPath.startsWith(from.path + File.separator) && file.isFile) { "вложение вне каталога чата" }
            val destination = File(to, UUID.randomUUID().toString() + "_" + file.name.takeLast(100))
            file.copyTo(destination)
            destination
        }
        return chat.copy(id = id, messages = chat.messages.map { m -> m.copy(images = m.images.map(::copy),
            attachedFiles = m.attachedFiles.map { it.copy(file = copy(it.file)) }) },
            attachments = chat.attachments.map { it.copy(file = copy(it.file)) })
    }

    fun export(snapshot: ChatSnapshot, store: ChatStore, temporary: File, output: OutputStream) {
        temporary.mkdirs()
        val files = snapshot.chats.flatMap { chat -> chat.attachments.map { it.file } +
            chat.messages.flatMap { it.images + it.attachedFiles.map { a -> a.file } } }.distinctBy { it.canonicalPath }
        require(files.size + 1 <= MAX_ENTRIES && files.all { it.length() <= MAX_ENTRY } && files.sumOf { it.length() } <= MAX_TOTAL) { "превышен лимит вложений: 64 МБ на файл, 512 МБ на архив" }
        val staging = ChatStore(temporary)
        val chats = snapshot.chats.map { remap(it, store, staging, it.id) }
        staging.save(ChatSnapshot(snapshot.activeId, chats))
        var total = 0L
        ZipOutputStream(output).use { zip ->
            val entries = listOf(File(temporary, "chats.json")) + File(temporary, "attachments").walkTopDown().filter { it.isFile }.toList()
            require(entries.size <= MAX_ENTRIES) { "слишком много вложений" }
            entries.forEach { file ->
                require(file.length() <= (if (file.name == "chats.json") 16L * 1024 * 1024 else MAX_ENTRY)) { "одно вложение больше 64 МБ" }
                total += file.length()
                require(total <= MAX_TOTAL) { "архив больше 512 МБ" }
                zip.putNextEntry(ZipEntry(file.relativeTo(temporary).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, temporary: File): ChatSnapshot {
        temporary.mkdirs()
        val root = temporary.canonicalFile
        var total = 0L
        var count = 0
        val seen = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_ENTRIES) { "слишком много файлов в архиве" }
                val name = entry.name
                require(!name.contains('\\') && !name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) { "недопустимый путь в архиве" }
                require(seen.add(name)) { "повторяющийся файл в архиве" }
                require(name == "chats.json" || Regex("attachments/[0-9a-f-]{36}/[^/]+").matches(name) || entry.isDirectory) { "неизвестный файл в архиве" }
                val out = File(root, name).canonicalFile
                require(out.path.startsWith(root.path + File.separator)) { "путь вне архива" }
                if (!entry.isDirectory) {
                    out.parentFile?.mkdirs()
                    var size = 0L
                    out.outputStream().use { target ->
                        val buf = ByteArray(32768)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            size += n; total += n
                            require(size <= (if (name == "chats.json") 16L * 1024 * 1024 else MAX_ENTRY) && total <= MAX_TOTAL) { "превышен лимит размера архива" }
                            target.write(buf, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(File(root, "chats.json").isFile) { "в архиве нет переписки" }
        val json = org.json.JSONObject(File(root, "chats.json").readText())
        val chats = json.getJSONArray("chats")
        for (i in 0 until chats.length()) {
            val chat = chats.getJSONObject(i)
            val id = chat.getString("id")
            fun verify(path: String) {
                val file = File(root, path).canonicalFile
                require(path.startsWith("attachments/$id/") && !path.contains('\\') && path.split('/').none { it == ".." } &&
                    file.path.startsWith(File(root, "attachments/$id").canonicalPath + File.separator) && file.isFile) { "в архиве отсутствует вложение или указан недопустимый путь" }
            }
            val messages = chat.getJSONArray("messages")
            for (j in 0 until messages.length()) {
                val m = messages.getJSONObject(j)
                m.optJSONArray("images")?.let { images -> for (k in 0 until images.length()) verify(images.getString(k)) }
                m.optJSONArray("attachedFiles")?.let { files -> for (k in 0 until files.length()) verify(files.getJSONObject(k).getString("file")) }
            }
            chat.optJSONArray("attachments")?.let { files -> for (k in 0 until files.length()) verify(files.getJSONObject(k).getString("file")) }
        }
        return ChatStore(root).load() ?: throw IllegalArgumentException("повреждённый формат переписки")
    }
}
