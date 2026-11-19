package com.localmodels.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

data class FileChange(val operation: String, val path: String, val before: String, val after: String)

/** Only shared roots; mutations require an explicit UI decision for the exact change. */
class SharedFileTools(private val roots: Map<String, File>, private val enabled: () -> Boolean) {
    companion object {
        private val command = Regex("(?m)^[ \\t]*(LIST_FILES|READ_FILE|WRITE_FILE|DELETE_FILE)[ \\t]+([^\\r\\n]+)$")
        fun hasCommand(answer: String): Boolean = command.containsMatchIn(answer)
        fun needsLocalFiles(question: String): Boolean =
            Regex("(?iu)файл|папк|документ|file|folder").containsMatchIn(question) &&
                !Regex("(?iu)интернет|сайт|online|\\bweb\\b|https?://").containsMatchIn(question)
        fun stripCommands(answer: String): String = answer.replace(command, "").trim()
        private val textExtensions = setOf("txt", "md", "json", "csv", "tsv", "xml", "html", "htm", "yaml", "yml", "toml", "ini", "log", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "sh", "bat", "sql", "srt", "properties", "gradle")
    }

    val instruction: String get() = """
Доступ к общим файлам включён. Доступные тома: ${roots.keys.joinToString()}.
Пути: primary/Download/example.txt или абсолютный путь внутри разрешённых томов.
Команды: одна отдельная строка без markdown:
LIST_FILES /
LIST_FILES primary/Download
READ_FILE primary/Download/example.txt
WRITE_FILE {"path":"primary/Download/example.txt","text":"новый полный текст"}
DELETE_FILE {"path":"primary/Download/example.txt"}
LIST_FILES / показывает тома. READ_FILE читает текст; для бинарных файлов возвращает метаданные.
Фото и PDF пользователь может отправить вложением. Изменения и удаление требуют подтверждения в приложении.
WRITE_FILE полностью заменяет текст или создаёт файл. Каталоги удалять нельзя. Android/data и Android/obb недоступны.
Не выдумывай имена и содержимое. Сначала получи список и прочитай файл. После результата команды продолжи ответ.
""".trimIndent()

    suspend fun initialContext(question: String): String {
        if (!enabled() || !needsLocalFiles(question)) return ""
        val listing = execute("LIST_FILES primary") { false } ?: return ""
        return "\n\n[Файлы устройства: исходный список]\n$listing\n" +
            "Для вложенных папок используй LIST_FILES, для чтения READ_FILE. Не выдумывай содержимое файлов."
    }

    private fun resolve(path: String): File {
        check(enabled()) { "доступ к файлам выключен или разрешение отозвано" }
        val raw = path.trim().removeSurrounding("\"")
        val file = if (File(raw).isAbsolute) File(raw) else {
            val alias = raw.substringBefore('/')
            val root = roots[alias] ?: error("неизвестный том: используйте LIST_FILES /")
            File(root, raw.substringAfter('/', ""))
        }
        val canonical = file.canonicalFile
        val root = roots.values.map { it.canonicalFile }.firstOrNull {
            canonical == it || canonical.path.startsWith(it.path + File.separator)
        } ?: error("путь вне общих файлов устройства")
        val relative = canonical.relativeTo(root).invariantSeparatorsPath.lowercase()
        require(relative != "android/data" && !relative.startsWith("android/data/") &&
            relative != "android/obb" && !relative.startsWith("android/obb/")) { "защищённый каталог Android" }
        return canonical
    }

    private fun preview(file: File): String = if (file.isFile && file.extension.lowercase() in textExtensions)
        file.reader(Charsets.UTF_8).use { reader ->
            val chars = CharArray(12_000)
            val count = reader.read(chars)
            if (count < 0) "" else String(chars, 0, count)
        } else if (file.exists()) "${file.length()} байт" else "файл ещё не существует"

    private fun fingerprint(file: File): String {
        if (!file.exists()) return "absent"
        require(file.isFile) { "операция доступна только для файлов" }
        if (file.length() > 8L * 1024 * 1024) return "${file.length()}:${file.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun execute(answer: String, confirm: suspend (FileChange) -> Boolean): String? {
        val match = command.find(answer) ?: return null
        return try {
            check(enabled()) { "доступ к файлам выключен или разрешение отозвано" }
            val op = match.groupValues[1]
            val arg = match.groupValues[2].trim()
            if (op == "LIST_FILES" && arg == "/") {
                "Тома:\n" + roots.entries.joinToString("\n") { "${it.key}: ${it.value.absolutePath}" }
            } else if (op == "LIST_FILES" || op == "READ_FILE") {
                val file = resolve(arg)
                if (op == "LIST_FILES") {
                    require(file.isDirectory) { "папка не найдена" }
                    val entries = file.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                        ?: error("нет доступа к папке")
                    "Папка ${file.path}:\n" + entries.take(100).joinToString("\n") {
                        "${if (it.isDirectory) "папка" else "файл"} ${it.name} / ${it.length()} байт"
                    } + if (entries.size > 100) "\nПоказаны первые 100 записей." else ""
                } else {
                    require(file.isFile) { "файл не найден" }
                    "Файл ${file.path} / ${file.length()} байт:\n${preview(file)}"
                }
            } else {
                val json = JSONObject(arg)
                val file = resolve(json.getString("path"))
                require(roots.values.none { file == it.canonicalFile } && !file.isDirectory) { "каталоги изменять нельзя" }
                val content = if (op == "WRITE_FILE") json.getString("text") else ""
                require(content.length <= 64_000) { "текст больше 64 000 символов" }
                if (op == "WRITE_FILE") require(file.extension.lowercase() in textExtensions) { "запись поддерживается для текстовых файлов" }
                if (op == "DELETE_FILE") require(file.isFile) { "файл не найден" }
                if (op == "WRITE_FILE") require(!file.exists() || file.length() <= 8L * 1024 * 1024) { "перезапись файлов больше 8 МБ не поддерживается" }
                val beforeHash = fingerprint(file)
                val approved = confirm(FileChange(op, file.path, preview(file), content))
                if (!approved) "Пользователь отменил изменение. Файл не изменён."
                else {
                    check(resolve(file.path) == file && enabled()) { "доступ к файлам отозван" }
                    check(fingerprint(file) == beforeHash) { "файл изменился после запроса подтверждения; повторите операцию" }
                    if (op == "DELETE_FILE") check(file.delete()) { "удаление не удалось" }
                    else {
                        check(file.parentFile?.isDirectory == true) { "сначала выберите существующую папку" }
                        val temp = File.createTempFile(".lirium-", ".tmp", file.parentFile)
                        try {
                            temp.writeText(content, Charsets.UTF_8)
                            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } finally { temp.delete() }
                    }
                    "Выполнено $op: ${file.path}"
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { "Ошибка файлового инструмента: ${e.message ?: e.javaClass.simpleName}" }
    }
}
