package com.localmodels.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(
    val id: Long, val fromUser: Boolean, val text: String,
    val images: List<File> = emptyList(), val files: List<String> = emptyList(), val isError: Boolean = false
)
data class ChatTurn(val role: String, val text: String)
data class ChatRecord(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "новый чат",
    val modelLabel: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val context: List<ChatTurn> = emptyList(),
    val draft: String = "",
    val attachments: List<Attachment> = emptyList()
)
data class ChatSnapshot(val activeId: String, val chats: List<ChatRecord>)

object ChatHistory {
    fun bounded(turns: List<ChatTurn>): List<ChatTurn> {
        var result = turns.takeLast(24)
        while (result.isNotEmpty() && result.first().role != "user") result = result.drop(1)
        while (result.size > 2 && result.sumOf { it.text.length } > 48_000) result = result.drop(2)
        return result
    }
}

/** Atomic private storage; no KV caches or API credentials are saved here. */
class ChatStore(val root: File) {
    private val file = File(root, "chats.json")
    private val backup = File(root, "chats.backup.json")
    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun attachmentDirectory(id: String): File {
        require(validId(id))
        return File(File(root, "attachments"), id)
    }

    private fun savedFile(path: String, chatId: String): File? {
        val f = File(root, path).canonicalFile
        val attachments = attachmentDirectory(chatId).canonicalFile
        return f.takeIf { it.path.startsWith(attachments.path + File.separator) && it.isFile }
    }

    @Synchronized fun load(): ChatSnapshot? {
        for (source in listOf(file, backup)) {
            if (!source.isFile) continue
            runCatching {
                val json = JSONObject(source.readText(Charsets.UTF_8))
                require(json.getInt("version") == 1)
                val array = json.getJSONArray("chats")
                val chats = (0 until array.length()).map { index ->
                    val c = array.getJSONObject(index)
                    val id = c.getString("id").also { require(validId(it)) }
                    val messages = c.getJSONArray("messages").let { list ->
                        (0 until list.length()).map { i ->
                            val m = list.getJSONObject(i)
                            ChatMessage(m.getLong("id"), m.getBoolean("fromUser"), m.getString("text"),
                                m.optJSONArray("images").strings().mapNotNull { savedFile(it, id) },
                                m.optJSONArray("files").strings(), m.optBoolean("isError"))
                        }
                    }
                    val context = c.optJSONArray("context")?.let { list ->
                        (0 until list.length()).mapNotNull { i ->
                            val t = list.getJSONObject(i)
                            val role = t.getString("role")
                            if (role in listOf("user", "assistant")) ChatTurn(role, t.getString("text")) else null
                        }
                    }.orEmpty()
                    val attachments = c.optJSONArray("attachments")?.let { list ->
                        (0 until list.length()).mapNotNull { i ->
                            val a = list.getJSONObject(i)
                            savedFile(a.getString("file"), id)?.let { Attachment(it, a.getString("name"), a.getString("mime"), a.getBoolean("isImage")) }
                        }
                    }.orEmpty()
                    ChatRecord(id, c.optString("title", "новый чат"), c.optString("modelLabel"), c.optLong("updatedAt"),
                        messages, ChatHistory.bounded(context), c.optString("draft"), attachments)
                }
                require(chats.map { it.id }.distinct().size == chats.size)
                if (chats.isNotEmpty()) return ChatSnapshot(json.optString("activeId").takeIf { id -> chats.any { it.id == id } } ?: chats.first().id, chats)
            }
        }
        return null
    }

    @Synchronized fun save(snapshot: ChatSnapshot, discardPrevious: Boolean = false) {
        root.mkdirs()
        fun relative(f: File): String {
            require(f.canonicalPath.startsWith(File(root, "attachments").canonicalPath + File.separator))
            return f.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
        }
        val list = JSONArray()
        snapshot.chats.forEach { chat ->
            require(validId(chat.id))
            val messages = JSONArray()
            chat.messages.forEach { m ->
                messages.put(JSONObject().put("id", m.id).put("fromUser", m.fromUser).put("text", m.text)
                    .put("images", JSONArray(m.images.map(::relative))).put("files", JSONArray(m.files)).put("isError", m.isError))
            }
            val context = JSONArray()
            ChatHistory.bounded(chat.context).forEach { context.put(JSONObject().put("role", it.role).put("text", it.text)) }
            val attachments = JSONArray()
            chat.attachments.forEach { attachments.put(JSONObject().put("file", relative(it.file)).put("name", it.name).put("mime", it.mime).put("isImage", it.isImage)) }
            list.put(JSONObject().put("id", chat.id).put("title", chat.title).put("modelLabel", chat.modelLabel)
                .put("updatedAt", chat.updatedAt).put("messages", messages).put("context", context)
                .put("draft", chat.draft).put("attachments", attachments))
        }
        val json = JSONObject().put("version", 1).put("activeId", snapshot.activeId).put("chats", list).toString()
        val temporary = File(root, "chats.tmp")
        temporary.outputStream().use { out -> out.write(json.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        if (!discardPrevious && file.isFile) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (discardPrevious) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun deleteAttachments(id: String) {
        val dir = attachmentDirectory(id).canonicalFile
        require(dir.parentFile == File(root, "attachments").canonicalFile)
        check(!dir.exists() || dir.deleteRecursively()) { "не удалось удалить вложения чата" }
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
