package com.localmodels.app

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localmodels.app.data.Attachment
import com.localmodels.app.data.Catalog
import com.localmodels.app.data.CatalogModel
import com.localmodels.app.data.ContentPrep
import com.localmodels.app.data.DownloadService
import com.localmodels.app.data.DownloadState
import com.localmodels.app.data.DownloadStatus
import com.localmodels.app.data.Downloads
import com.localmodels.app.data.LanHost
import com.localmodels.app.data.ModelStore
import com.localmodels.app.data.Prefs
import com.localmodels.app.data.RemoteApi
import com.localmodels.app.data.RemoteTurn
import com.localmodels.app.data.ServerService
import com.localmodels.app.data.Web
import com.localmodels.app.data.SystemPrompt
import com.localmodels.app.data.DeviceFiles
import com.localmodels.app.data.SharedFileTools
import com.localmodels.app.data.FileChange
import com.localmodels.app.data.ChatStore
import com.localmodels.app.data.ChatRecord
import com.localmodels.app.data.ChatSnapshot
import com.localmodels.app.data.ChatTurn
import com.localmodels.app.data.ChatHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import com.localmodels.app.engine.LlamaBridge
import com.localmodels.app.engine.LocalEngine
import com.localmodels.app.engine.TokenSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface EngineState {
    data object Idle : EngineState
    data object Loading : EngineState
    data object Ready : EngineState
    data class Error(val message: String) : EngineState
}

typealias UiMessage = com.localmodels.app.data.ChatMessage

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val store = ModelStore(app)
    private val chatStore = ChatStore(File(app.filesDir, "chat_history"))
    private val conversationOwner = UUID.randomUUID().toString()
    private val initialChats = chatStore.load() ?: ChatRecord().let { ChatSnapshot(it.id, listOf(it)) }
    private val initialChat = initialChats.chats.first { it.id == initialChats.activeId }
    var chats by mutableStateOf(initialChats.chats)
        private set
    var activeChatId by mutableStateOf(initialChats.activeId)
        private set

    var catalog by mutableStateOf(Catalog.load(app, prefs.customCatalogJson))
        private set

    var catalogVisible by mutableStateOf(false)
    var settingsVisible by mutableStateOf(false)
    var selected by mutableStateOf<CatalogModel?>(null)
    var engine by mutableStateOf<EngineState>(EngineState.Idle)
    var messages by mutableStateOf(initialChat.messages)
    var input by mutableStateOf(initialChat.draft)
    var attachments by mutableStateOf(initialChat.attachments)
    var busy by mutableStateOf(false)
    var info by mutableStateOf<String?>(null)
    var visionOnly by mutableStateOf(false)
    var visionTestRunning by mutableStateOf(false)
    var generationStatus by mutableStateOf("")
        private set
    var storageRevision by mutableStateOf(0)
        private set
    var deletingModelId by mutableStateOf<String?>(null)
        private set
    var filesEnabled by mutableStateOf(prefs.filesEnabled)
        private set
    var pendingFileChange by mutableStateOf<FileChange?>(null)
        private set
    private var fileDecision: CompletableDeferred<Boolean>? = null
    var detailedVision by mutableStateOf(prefs.detailedVision)
        private set

    var fontKey by mutableStateOf(prefs.fontKey)
        private set
    var internetEnabled by mutableStateOf(prefs.internetEnabled)
        private set
    var maxModelGb by mutableStateOf(prefs.maxModelGb)
        private set
    var customCatalog by mutableStateOf(prefs.customCatalogJson)
        private set
    var apiEnabled by mutableStateOf(prefs.apiEnabled)
        private set
    var apiBaseUrl by mutableStateOf(prefs.apiBaseUrl)
        private set
    var apiKey by mutableStateOf(prefs.apiKey)
        private set
    var apiModel by mutableStateOf(prefs.apiModel)
        private set
    var apiVision by mutableStateOf(prefs.apiVision)
        private set
    var lanEnabled by mutableStateOf(prefs.lanEnabled)
        private set
    var lanPassword by mutableStateOf(prefs.lanPassword)
        private set

    val apiReady: Boolean get() = apiEnabled && apiBaseUrl.isNotBlank() && apiModel.isNotBlank()

    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null
    private var nextId = (initialChats.chats.flatMap { it.messages }.maxOfOrNull { it.id } ?: 0L) + 1L
    private var streamId = 0L
    private val streamBuffer = StringBuilder()
    private var flushing = false
    @Volatile
    private var stopRequested = false
    private var streamStartedAt = 0L
    @Volatile private var firstTokenAt = 0L
    @Volatile private var requestStage = "обработка запроса"
    private data class SaveRequest(val snapshot: ChatSnapshot, val deleteId: String? = null)
    private val saveQueue = Channel<SaveRequest>(Channel.UNLIMITED)
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draftSave = Runnable { saveActiveChat() }

    private val flusher = object : Runnable {
        override fun run() {
            if (busy) {
                val seconds = (SystemClock.elapsedRealtime() - streamStartedAt) / 1000
                generationStatus = if (firstTokenAt == 0L) "$requestStage / ${seconds} с"
                    else "генерация ответа / ${seconds} с"
            }
            if (streamBuffer.isNotEmpty()) {
                val chunk = synchronized(streamBuffer) {
                    val s = streamBuffer.toString()
                    streamBuffer.setLength(0)
                    s
                }
                appendToStream(chunk)
            }
            if (busy) handler.postDelayed(this, 60)
        }
    }

    init {
        storageScope.launch {
            for (request in saveQueue) {
                runCatching {
                    chatStore.save(request.snapshot, discardPrevious = request.deleteId != null)
                    request.deleteId?.let { chatStore.deleteAttachments(it) }
                }.onFailure { error -> handler.post { showInfo("не удалось сохранить чаты: ${error.message}") } }
            }
            storageScope.cancel()
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { LlamaBridge.init(getApplication<Application>().applicationInfo.nativeLibraryDir) }
        }
        if (apiReady) {
            engine = EngineState.Ready
        } else {
            val installed = store.installedModels(catalog)
            val preferredId = prefs.selectedModelId
            val preferred = installed.firstOrNull { it.id == preferredId } ?: installed.firstOrNull()
            if (preferred != null) {
                selectModel(preferred)
            } else {
                catalogVisible = true
            }
        }
    }

    fun openCatalog(open: Boolean) {
        catalogVisible = open
        if (open) settingsVisible = false
    }

    fun openSettings(open: Boolean) {
        settingsVisible = open
        if (open) catalogVisible = false
    }

    fun showInfo(text: String) {
        info = text
        handler.postDelayed({ if (info == text) info = null }, 4000)
    }

    // ---------------- model / engine ----------------

    fun selectModel(model: CatalogModel) {
        if (deletingModelId != null) {
            showInfo("дождитесь удаления модели")
            return
        }
        if (!store.isInstalled(model)) {
            showInfo("сначала скачайте модель")
            return
        }
        if (busy) {
            showInfo("дождитесь ответа")
            return
        }
        if (apiEnabled) updateApiEnabled(false)
        prefs.selectedModelId = model.id
        selected = model
        catalogVisible = false
        settingsVisible = false
        engine = EngineState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LocalEngine.load(getApplication(), store, model)
                }.getOrElse { -1 }
            }
            engine = if (result == 0) EngineState.Ready else EngineState.Error("не удалось загрузить")
            if (result != 0) {
                showInfo("не удалось загрузить модель")
            }
        }
    }

    fun newChat() {
        if (busy) {
            showInfo("сначала остановите ответ")
            return
        }
        saveActiveChat()
        val chat = ChatRecord()
        chats = listOf(chat) + chats
        activateChat(chat)
        catalogVisible = false
        settingsVisible = false
        saveActiveChat()
    }

    private fun activateChat(chat: ChatRecord) {
        activeChatId = chat.id
        messages = chat.messages
        input = chat.draft
        attachments = chat.attachments
    }

    fun selectChat(id: String) {
        if (busy) { showInfo("сначала остановите ответ"); return }
        if (id == activeChatId) { catalogVisible = false; settingsVisible = false; return }
        saveActiveChat()
        chats.firstOrNull { it.id == id }?.let { activateChat(it) }
        catalogVisible = false
        settingsVisible = false
        saveActiveChat()
    }

    fun deleteChat(id: String) {
        if (busy) { showInfo("сначала остановите ответ"); return }
        if (chats.none { it.id == id }) return
        saveActiveChat(persist = false)
        chats = chats.filterNot { it.id == id }
        if (chats.isEmpty()) chats = listOf(ChatRecord())
        if (activeChatId == id) activateChat(chats.first())
        saveQueue.trySend(SaveRequest(ChatSnapshot(activeChatId, chats), id))
    }

    private fun saveActiveChat(context: List<ChatTurn>? = null, persist: Boolean = true) {
        handler.removeCallbacks(draftSave)
        chats = chats.map { chat ->
            if (chat.id != activeChatId) chat else {
                val first = messages.firstOrNull { it.fromUser }
                val title = first?.text?.trim()?.take(48)?.takeIf { it.isNotBlank() }
                    ?: first?.files?.firstOrNull() ?: if (first?.images?.isNotEmpty() == true) "чат с фото" else chat.title
                chat.copy(title = title, messages = messages, draft = input, attachments = attachments,
                    context = context?.let { ChatHistory.bounded(it) } ?: chat.context,
                    updatedAt = System.currentTimeMillis())
            }
        }
        if (persist) saveQueue.trySend(SaveRequest(ChatSnapshot(activeChatId, chats)))
    }

    fun updateInput(value: String) {
        input = value
        handler.removeCallbacks(draftSave)
        handler.postDelayed(draftSave, 500)
    }

    // ---------------- attachments ----------------

    fun addPhoto(uri: Uri) = addUri(uri, true)

    fun addFile(uri: Uri) = addUri(uri, false)

    private fun addUri(uri: Uri, image: Boolean) {
        val chatId = activeChatId
        if (attachments.size >= 4) {
            showInfo("не больше 4 вложений")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val mime = resolver.getType(uri) ?: if (image) "image/*" else "application/octet-stream"
                val name = queryName(resolver, uri)
                val dir = chatStore.attachmentDirectory(chatId).apply { mkdirs() }
                val safeName = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
                val out = File(dir, "${UUID.randomUUID()}_$safeName")
                resolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("нет доступа к файлу")

                val att = Attachment(out, name, mime, image || mime.startsWith("image/"))
                withContext(Dispatchers.Main) {
                    if (chats.none { it.id == chatId }) { out.delete(); return@withContext }
                    if (activeChatId == chatId) { attachments = attachments + att; saveActiveChat() }
                    else {
                        chats = chats.map { if (it.id == chatId) it.copy(attachments = it.attachments + att) else it }
                        saveQueue.trySend(SaveRequest(ChatSnapshot(activeChatId, chats)))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showInfo(e.message ?: "не удалось добавить файл") }
            }
        }
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "файл").name
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return "файл"
    }

    fun removeAttachment(att: Attachment) {
        attachments = attachments.filterNot { it.file == att.file }
        saveActiveChat()
    }

    // ---------------- chat ----------------

    fun send() {
        if (busy) return
        val text = input.trim()
        val atts = attachments
        if (text.isEmpty() && atts.isEmpty()) return

        if (apiEnabled) {
            if (!apiReady) {
                showInfo("заполните свой API в настройках")
                return
            }
            if (!apiVision && atts.any { it.isImage || it.mime == "application/pdf" }) {
                showInfo("включите зрение у своего API")
                return
            }
            startRemote(text, atts)
            return
        }

        val model = selected
        if (model == null) {
            showInfo("выберите модель")
            return
        }
        if (engine !is EngineState.Ready) {
            showInfo("модель не готова")
            return
        }
        val visual = atts.any { it.isImage || it.mime == "application/pdf" }
        if (visual && !model.vision) {
            showInfo("эта модель не видит изображения")
            return
        }
        startLocal(model, text, atts)
    }

    private fun beginStream(text: String, atts: List<Attachment>) {
        val user = UiMessage(
            id = nextId++,
            fromUser = true,
            text = text,
            images = atts.filter { it.isImage }.map { it.file },
            files = atts.filterNot { it.isImage }.map { it.name }
        )
        val assistant = UiMessage(id = nextId++, fromUser = false, text = "")
        messages = messages + user + assistant
        streamId = assistant.id
        input = ""
        attachments = emptyList()
        busy = true
        streamStartedAt = SystemClock.elapsedRealtime()
        requestStage = "обработка запроса"
        firstTokenAt = 0L
        generationStatus = "обработка запроса"
        stopRequested = false
        val label = if (apiEnabled) apiModel else selected?.name.orEmpty()
        chats = chats.map { if (it.id == activeChatId) it.copy(modelLabel = label) else it }
        saveActiveChat()
        synchronized(streamBuffer) { streamBuffer.setLength(0) }
        if (!flushing) {
            flushing = true
            handler.postDelayed(flusher, 60)
        }
    }

    private fun finishStream(error: String?) {
        handler.removeCallbacks(flusher)
        flushing = false
        val chunk = synchronized(streamBuffer) {
            val s = streamBuffer.toString()
            streamBuffer.setLength(0)
            s
        }
        if (chunk.isNotEmpty()) appendToStream(chunk)
        busy = false
        generationStatus = ""
        if (firstTokenAt > 0L) {
            val first = (firstTokenAt - streamStartedAt) / 1000
            val total = (SystemClock.elapsedRealtime() - streamStartedAt) / 1000
            showInfo("начало ответа через $first с / всего $total с")
        }
        if (error != null && error != "stopped") {
            val current = messages.firstOrNull { it.id == streamId }
            if (current != null && current.text.isBlank()) {
                messages = messages.map {
                    if (it.id == streamId) it.copy(text = error, isError = true) else it
                }
            }
            showInfo(error)
        }
    }

    private fun startLocal(model: CatalogModel, text: String, atts: List<Attachment>) {
        val requestChatId = activeChatId
        val context = chats.first { it.id == requestChatId }.context.toMutableList()
        beginStream(text, atts)
        viewModelScope.launch(Dispatchers.Default) {
            var error: String? = null
            var webPrompt: Web.PreparedPrompt? = null
            var finalAnswer = ""
            try {
                if (LocalEngine.loadedModelId != model.id) {
                    val rc = withContext(Dispatchers.IO) { LocalEngine.load(getApplication(), store, model) }
                    if (rc != 0) throw IllegalStateException("не удалось загрузить модель")
                }
                val maxSide = if (detailedVision) model.imageMaxSide else minOf(448, model.imageMaxSide)
                val prepared = ContentPrep.prepare(atts, maxSide)
                var full = buildString {
                    if (text.isNotEmpty()) append(text)
                    if (prepared.text.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append(prepared.text)
                    }
                }
                val preparedWeb = Web.preparePrompt(full, internetEnabled, question = text,
                    automaticSearch = !(filesEnabled && SharedFileTools.needsLocalFiles(text)),
                    onStatus = { requestStage = it })
                webPrompt = preparedWeb
                full = preparedWeb.text
                requestStage = "обработка запроса"
                val images = prepared.images
                val fileTools = DeviceFiles.create(getApplication())
                full += fileTools.initialContext(text)
                val system = SystemPrompt.build(internetEnabled,
                    if (filesEnabled && DeviceFiles.hasPermission(getApplication())) fileTools.instruction else "")

                var round = 0
                while (true) {
                    if (stopRequested) break
                    val answer = StringBuilder()
                    LocalEngine.exclusive {
                        if (LocalEngine.load(getApplication(), store, model) != 0) {
                            throw IllegalStateException("не удалось загрузить модель или проектор зрения")
                        }
                        if (stopRequested) throw IllegalStateException("stopped")
                        LocalEngine.useConversation("$conversationOwner/$requestChatId", context, system)
                        if (stopRequested) throw IllegalStateException("stopped")
                        LocalEngine.chat(
                            full,
                            if (round == 0) images.map { it.rgb }.toTypedArray() else emptyArray(),
                            if (round == 0) images.map { it.width }.toIntArray() else IntArray(0),
                            if (round == 0) images.map { it.height }.toIntArray() else IntArray(0),
                            768, 0.7f, 0.95f, 40, 1.1f, system,
                            object : TokenSink {
                                override fun onToken(text: String) {
                                    if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                                    synchronized(streamBuffer) { streamBuffer.append(text) }
                                    answer.append(text)
                                }
                            }
                        )
                    }
                    finalAnswer = answer.toString()
                    context += ChatTurn("user", full)
                    context += ChatTurn("assistant", finalAnswer)
                    if (stopRequested || round >= 4) break
                    val fileResult = if (SharedFileTools.hasCommand(finalAnswer))
                        fileTools.execute(finalAnswer, ::confirmFileChange) else null
                    full = if (fileResult != null) "Результат файлового инструмента:\n$fileResult\nПродолжи ответ на исходный вопрос."
                        else if (internetEnabled) Web.followUp(finalAnswer, preparedWeb) ?: break
                        else break
                    clearCurrentStream()
                    round++
                }
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }

            withContext(Dispatchers.Main) {
                finishStream(error)
                if (error == null && !stopRequested) {
                    messages = messages.map {
                        if (it.id == streamId) it.copy(text = webPrompt?.let { context ->
                            Web.correctedAnswer(SharedFileTools.stripCommands(Web.stripFetchLines(finalAnswer)), context)
                        } ?: SharedFileTools.stripCommands(Web.stripFetchLines(it.text))) else it
                    }
                }
                if (activeChatId == requestChatId) saveActiveChat(context)
            }
        }
    }

    private fun startRemote(text: String, atts: List<Attachment>) {
        val requestChatId = activeChatId
        val context = chats.first { it.id == requestChatId }.context.toMutableList()
        beginStream(text, atts)
        viewModelScope.launch(Dispatchers.Default) {
            var error: String? = null
            var webPrompt: Web.PreparedPrompt? = null
            var finalAnswer = ""
            try {
                val prepared = ContentPrep.prepare(atts, 1280)
                var full = buildString {
                    if (text.isNotEmpty()) append(text)
                    if (prepared.text.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append(prepared.text)
                    }
                }
                val preparedWeb = Web.preparePrompt(full, internetEnabled, question = text,
                    automaticSearch = !(filesEnabled && SharedFileTools.needsLocalFiles(text)),
                    onStatus = { requestStage = it })
                webPrompt = preparedWeb
                full = preparedWeb.text
                requestStage = "обработка запроса"
                val turns = ArrayList<RemoteTurn>()
                val fileTools = DeviceFiles.create(getApplication())
                full += fileTools.initialContext(text)
                turns += RemoteTurn("system", SystemPrompt.build(internetEnabled,
                    if (filesEnabled && DeviceFiles.hasPermission(getApplication())) fileTools.instruction else ""))
                val history = messages.dropLast(2).filterNot { it.isError }.takeLast(12)
                for (m in history) {
                    if (m.text.isBlank() && m.images.isEmpty()) continue
                    val images = if (m.fromUser && apiVision) {
                        m.images.map { file ->
                            val mime = if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                            file to mime
                        }
                    } else emptyList()
                    turns += RemoteTurn(if (m.fromUser) "user" else "assistant", m.text, images)
                }
                val currentImages = if (apiVision) {
                    atts.filter { it.isImage }.map { att ->
                        val mime = if (att.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                        att.file to mime
                    }
                } else emptyList()
                turns += RemoteTurn("user", full, currentImages)
                var contextPrompt = full
                for (round in 0..4) {
                    if (stopRequested) break
                    val answer = RemoteApi.stream(apiBaseUrl, apiKey, apiModel, turns) { piece ->
                        if (stopRequested) throw IllegalStateException("stopped")
                        if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                        synchronized(streamBuffer) { streamBuffer.append(piece) }
                    }
                    finalAnswer = answer
                    context += ChatTurn("user", contextPrompt)
                    context += ChatTurn("assistant", answer)
                    if (stopRequested || round >= 4) break
                    val fileResult = if (SharedFileTools.hasCommand(answer)) fileTools.execute(answer, ::confirmFileChange) else null
                    val result = if (fileResult != null) "Результат файлового инструмента:\n$fileResult\nПродолжи ответ на исходный вопрос."
                        else if (internetEnabled) Web.followUp(answer, preparedWeb) ?: break
                        else break
                    clearCurrentStream()
                    turns += RemoteTurn("assistant", answer)
                    turns += RemoteTurn("user", result)
                    contextPrompt = result
                }
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main) {
                finishStream(error)
                if (error == null && !stopRequested) {
                    messages = messages.map {
                        if (it.id == streamId) it.copy(text = webPrompt?.let { context ->
                            Web.correctedAnswer(SharedFileTools.stripCommands(Web.stripFetchLines(finalAnswer)), context)
                        } ?: SharedFileTools.stripCommands(Web.stripFetchLines(it.text))) else it
                    }
                }
                if (activeChatId == requestChatId) saveActiveChat(context)
            }
        }
    }

    fun runVisionTest() {
        val model = selected
        if (apiEnabled) {
            showInfo("проверка работает с локальной моделью")
            return
        }
        if (model == null || engine !is EngineState.Ready) {
            showInfo("сначала загрузите модель")
            return
        }
        if (!model.vision) {
            showInfo("эта модель не видит изображения")
            return
        }
        if (busy || visionTestRunning) return
        visionTestRunning = true
        val chatId = activeChatId
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(chatStore.attachmentDirectory(chatId).apply { mkdirs() }, "vision_test_${UUID.randomUUID()}.png")
            val ok = runCatching { ContentPrep.createVisionTestPng(file) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                visionTestRunning = false
                if (busy || apiEnabled || selected?.id != model.id || activeChatId != chatId) return@withContext
                if (!ok) {
                    showInfo("не удалось создать картинку")
                    return@withContext
                }
                val att = Attachment(file, "vision_test.png", "image/png", true)
                startLocal(
                    model,
                    "Что на картинке? Назови большое число и цвета двух квадратов. Ответь одной короткой строкой.",
                    listOf(att)
                )
            }
        }
    }

    private suspend fun clearCurrentStream() = withContext(Dispatchers.Main) {
        synchronized(streamBuffer) { streamBuffer.setLength(0) }
        messages = messages.map { if (it.id == streamId) it.copy(text = "") else it }
        firstTokenAt = 0L
        requestStage = "обработка полученных данных"
    }

    private fun appendToStream(chunk: String) {
        if (chunk.isEmpty()) return
        messages = messages.map {
            if (it.id == streamId) it.copy(text = it.text + chunk) else it
        }
    }

    fun stop() {
        stopRequested = true
        respondToFileChange(false)
        LocalEngine.stop()
    }

    fun updateFilesEnabled(enabled: Boolean) {
        val granted = enabled && DeviceFiles.hasPermission(getApplication())
        prefs.filesEnabled = granted
        filesEnabled = granted
        if (!granted) respondToFileChange(false)
    }

    private suspend fun confirmFileChange(change: FileChange): Boolean {
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            if (stopRequested) decision.complete(false)
            else {
                fileDecision?.complete(false)
                fileDecision = decision
                pendingFileChange = change
            }
        }
        return try { decision.await() } finally {
            withContext(Dispatchers.Main) {
                if (fileDecision === decision) {
                    fileDecision = null
                    pendingFileChange = null
                }
            }
        }
    }

    fun respondToFileChange(approve: Boolean) {
        fileDecision?.complete(approve)
        pendingFileChange = null
    }

    // ---------------- downloads ----------------

    fun download(model: CatalogModel) {
        if (deletingModelId != null) return
        if (Downloads.isActive(model.id)) return
        if (store.isInstalled(model)) return
        Downloads.update(DownloadState(model.id, 0L, model.bytes, DownloadStatus.RUNNING))
        runCatching { DownloadService.start(getApplication(), model.id) }
            .onFailure {
                Downloads.clear(model.id)
                showInfo("не удалось начать загрузку")
            }
    }

    fun delete(model: CatalogModel) {
        if (busy || engine is EngineState.Loading || deletingModelId != null || Downloads.isActive(model.id)) {
            showInfo("дождитесь завершения текущей операции")
            return
        }
        deletingModelId = model.id
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LocalEngine.exclusive {
                        if (LocalEngine.loadedModelId == model.id) LocalEngine.unload()
                        store.delete(model)
                    }
                }
            }
            if (result.isSuccess) {
                Downloads.clear(model.id)
                if (selected?.id == model.id) {
                    selected = null
                    prefs.selectedModelId = null
                    engine = if (apiReady) EngineState.Ready else EngineState.Idle
                }
                storageRevision++
                showInfo("модель удалена с устройства")
            } else showInfo(result.exceptionOrNull()?.message ?: "не удалось удалить модель")
            deletingModelId = null
        }
    }

    fun freeSpace(): Long = getApplication<Application>().filesDir.usableSpace

    // ---------------- settings ----------------

    fun setFont(key: String) {
        prefs.fontKey = key
        fontKey = key
    }

    fun setInternet(enabled: Boolean) {
        prefs.internetEnabled = enabled
        internetEnabled = enabled
    }

    fun updateDetailedVision(enabled: Boolean) {
        prefs.detailedVision = enabled
        detailedVision = enabled
    }

    fun updateMaxModelGb(value: Int) {
        prefs.maxModelGb = value
        maxModelGb = prefs.maxModelGb
    }

    fun updateApiEnabled(enabled: Boolean) {
        prefs.apiEnabled = enabled
        apiEnabled = enabled
        if (enabled && apiReady) {
            engine = EngineState.Ready
            settingsVisible = false
            catalogVisible = false
        } else if (!enabled) {
            if (selected != null) {
                engine = if (LocalEngine.loadedModelId == selected?.id) EngineState.Ready else EngineState.Idle
            } else {
                engine = EngineState.Idle
            }
        }
    }

    fun updateApiBaseUrl(value: String) {
        prefs.apiBaseUrl = value
        apiBaseUrl = prefs.apiBaseUrl
        syncApiEngine()
    }

    fun updateApiKey(value: String) {
        prefs.apiKey = value
        apiKey = prefs.apiKey
    }

    fun updateApiModel(value: String) {
        prefs.apiModel = value
        apiModel = prefs.apiModel
        syncApiEngine()
    }

    private fun syncApiEngine() {
        if (apiEnabled) engine = if (apiReady) EngineState.Ready else EngineState.Idle
    }

    fun updateApiVision(value: Boolean) {
        prefs.apiVision = value
        apiVision = value
    }

    fun updateLanEnabled(enabled: Boolean) {
        prefs.lanEnabled = enabled
        lanEnabled = enabled
        if (enabled) {
            ServerService.start(getApplication())
        } else {
            ServerService.stop(getApplication())
        }
    }

    fun updateLanPassword(value: String) {
        val v = value.trim().ifBlank { "123" }
        prefs.lanPassword = v
        lanPassword = v
        if (lanEnabled) {
            ServerService.restart(getApplication())
        }
    }

    fun applyCustomCatalog(raw: String, done: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = raw.trim()
            if (text.isEmpty()) {
                prefs.customCatalogJson = null
                withContext(Dispatchers.Main) {
                    customCatalog = null
                    reloadCatalog()
                    done(null)
                }
                return@launch
            }
            val result = runCatching {
                val json = if (text.startsWith("{") || text.startsWith("[")) text
                    else Web.fetchText(text, 400_000)
                require(Catalog.parse(json).isNotEmpty()) { "каталог пуст" }
                json
            }
            val json = result.getOrNull()
            if (json == null) {
                withContext(Dispatchers.Main) { done(result.exceptionOrNull()?.message ?: "каталог не прочитан") }
                return@launch
            }
            prefs.customCatalogJson = json
            withContext(Dispatchers.Main) {
                customCatalog = json
                reloadCatalog()
                done(null)
            }
        }
    }

    private fun reloadCatalog() {
        catalog = Catalog.load(getApplication(), prefs.customCatalogJson)
        val current = selected
        if (current != null && catalog.none { it.id == current.id }) {
            selected = null
            engine = EngineState.Idle
        }
    }

    fun localAddress(): String = LanHost.address

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        if (busy) { stopRequested = true; LocalEngine.stop() }
        respondToFileChange(false)
        val remaining = synchronized(streamBuffer) { streamBuffer.toString().also { streamBuffer.setLength(0) } }
        if (remaining.isNotEmpty()) appendToStream(remaining)
        saveActiveChat()
        saveQueue.close()
        super.onCleared()
    }
}
