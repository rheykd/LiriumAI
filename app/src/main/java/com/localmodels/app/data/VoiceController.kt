package com.localmodels.app.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

class VoiceController(private val context: Context, private val scope: CoroutineScope, private val info: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    var inputMode by mutableStateOf(prefs.getString("inputMode", "system") ?: "system")
        private set
    var modelLanguage by mutableStateOf(prefs.getString("modelLanguage", "ru") ?: "ru")
        private set
    var offlineSpeech by mutableStateOf(prefs.getBoolean("offlineSpeech", true))
        private set
    var selectedVoice by mutableStateOf(prefs.getString("voice", "") ?: "")
        private set
    var availableVoices by mutableStateOf(emptyList<android.speech.tts.Voice>())
        private set
    var listening by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var modelInstalled by mutableStateOf(modelDirectory().isDirectory)
        private set
    var downloadStatus by mutableStateOf("")
        private set
    var downloading by mutableStateOf(false)
        private set
    private var synthesizer: TextToSpeech? = null
    private var systemRecognizer: SpeechRecognizer? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var stopped = false
    @Volatile private var epoch = 0
    private var downloadControl: RequestControl? = null
    private var recognitionJob: Job? = null
    private var ready = false
    private var closed = false
    init {
        synthesizer = TextToSpeech(context) { status -> ui.post {
            if (!closed && status == TextToSpeech.SUCCESS) {
                ready = true
                availableVoices = synthesizer?.voices.orEmpty().sortedBy { it.name }
                synthesizer?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { ui.post { if (!closed) speaking = true } }
                    override fun onDone(id: String?) { if (id == "last") ui.post { if (!closed) speaking = false } }
                    @Deprecated("platform callback") override fun onError(id: String?) { ui.post { speaking = false; info("не удалось озвучить текст") } }
                })
            }
        } }
    }
    fun refreshVoices() { if (ready) availableVoices = synthesizer?.voices.orEmpty().sortedBy { it.name } }
    private fun modelDirectory(language: String = modelLanguage) = File(context.filesDir, "voice/vosk-$language")
    fun updateInputMode(mode: String) { cancelListening(); inputMode = mode; prefs.edit().putString("inputMode", mode).apply() }
    fun setLanguage(language: String) {
        if (downloading) return
        cancelListening(); modelLanguage = language; prefs.edit().putString("modelLanguage", language).apply()
        modelInstalled = modelDirectory().isDirectory
    }
    fun updateOfflineSpeech(value: Boolean) { stopSpeaking(); offlineSpeech = value; prefs.edit().putBoolean("offlineSpeech", value).apply() }
    fun setVoice(name: String) { stopSpeaking(); selectedVoice = name; prefs.edit().putString("voice", name).apply() }
    fun stopSpeaking() { synthesizer?.stop(); speaking = false }
    fun speak(text: String) {
        if (!ready) { info("системная озвучка недоступна или ещё запускается"); return }
        if (text.isBlank()) return
        cancelListening()
        val language = if (modelLanguage == "ru") "ru" else "en"
        val voices = availableVoices.filter { (!offlineSpeech || !it.isNetworkConnectionRequired) &&
            !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val voice = voices.firstOrNull { it.name == selectedVoice }
            ?: voices.firstOrNull { it.locale.language == language }
        if (voice == null) { info("нет установленного голоса для этого языка; установите голос в настройках Android"); return }
        val tts = synthesizer ?: return
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) { info("голос недоступен"); return }
        tts.stop()
        // Platform engines limit each utterance; use chunks without dropping the rest of the answer.
        val chunks = text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3500))
        chunks.forEachIndexed { i, chunk ->
            val result = tts.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null,
                if (i == chunks.lastIndex) "last" else "part-$i")
            if (result == TextToSpeech.ERROR) info("ошибка системной озвучки")
        }
        speaking = true
    }
    fun listen(onResult: (String) -> Unit) {
        if (listening) { stopListening(); return }
        if (recognitionJob?.isActive == true) { info("дождитесь освобождения микрофона"); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            info("для голоса нужно разрешение на микрофон"); return
        }
        stopSpeaking()
        cancelListening()
        val request = epoch
        listening = true; stopped = false; partial = ""
        if (inputMode == "vosk") {
            if (!modelInstalled) { listening = false; info("сначала скачайте голосовую модель в настройках"); return }
            listenVosk(request, onResult)
            return
        }
        if (inputMode == "device" && (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context))) {
            listening = false; info("на устройстве нет локального системного распознавания; выберите Vosk"); return
        }
        if (inputMode != "device" && !SpeechRecognizer.isRecognitionAvailable(context)) {
            listening = false; info("системный сервис распознавания не установлен; выберите Vosk"); return
        }
        runCatching {
            val sr = if (inputMode == "device" && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            systemRecognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (request == epoch) partial = "распознаю речь" }
                override fun onError(error: Int) { if (request == epoch) { cancelListening(); info("распознавание завершилось с ошибкой $error; попробуйте Vosk или проверьте язык") } }
                override fun onResults(results: Bundle?) {
                    if (request != epoch) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    cancelListening()
                    if (text.isNotBlank()) onResult(text)
                }
                override fun onPartialResults(results: Bundle?) { if (request == epoch) partial = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (modelLanguage == "ru") "ru-RU" else "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (inputMode == "device") putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }.onFailure { cancelListening(); info(it.message ?: "не удалось включить микрофон") }
    }
    @Suppress("MissingPermission")
    private fun listenVosk(request: Int, onResult: (String) -> Unit) {
        val modelPath = modelDirectory().absolutePath
        recognitionJob = scope.launch(Dispatchers.IO) {
            var audio: AudioRecord? = null
            val result = runCatching {
                Model(modelPath).use { model -> Recognizer(model, 16000f).use { recognizer ->
                    if (request != epoch) return@use ""
                    val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    require(minimum > 0) { "микрофон не поддерживает 16 кГц" }
                    audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 12800))
                    val recording = audio!!
                    check(recording.state == AudioRecord.STATE_INITIALIZED) { "не удалось открыть микрофон" }
                    recorder = recording
                    if (request != epoch || stopped) return@use ""
                    recording.startRecording()
                    val buffer = ShortArray(3200)
                    val final = StringBuilder()
                    val started = android.os.SystemClock.elapsedRealtime()
                    while (!stopped && request == epoch && android.os.SystemClock.elapsedRealtime() - started < 60000) {
                        val n = recording.read(buffer, 0, buffer.size)
                        if (n <= 0) { if (stopped || request != epoch) break; error("ошибка чтения микрофона") }
                        if (recognizer.acceptWaveForm(buffer, n)) {
                            val part = JSONObject(recognizer.result).optString("text")
                            if (part.isNotBlank()) final.append(part).append(' ')
                        } else {
                            val preview = final.toString() + JSONObject(recognizer.partialResult).optString("partial")
                            ui.post { if (request == epoch) partial = preview }
                        }
                    }
                    final.append(JSONObject(recognizer.finalResult).optString("text"))
                    final.toString().trim()
                } }
            }
            runCatching { audio?.stop() }; audio?.release()
            if (recorder === audio) recorder = null
            withContext(Dispatchers.Main) {
                if (request == epoch) {
                    listening = false; partial = ""
                    result.onSuccess { if (it.isNotBlank()) onResult(it) }
                        .onFailure { info(it.message ?: "ошибка локального распознавания") }
                }
            }
        }
    }
    fun stopListening() { stopped = true; runCatching { recorder?.stop() }; systemRecognizer?.stopListening() }
    fun cancelListening() {
        epoch++; stopped = true
        runCatching { recorder?.stop() }
        systemRecognizer?.cancel(); systemRecognizer?.destroy(); systemRecognizer = null
        listening = false; partial = ""
    }
    fun cancelDownload() { downloadControl?.cancel() }
    fun downloadModel() {
        if (downloading || listening) return
        val language = modelLanguage
        val folder = if (language == "ru") "vosk-model-small-ru-0.22" else "vosk-model-small-en-us-0.15"
        val control = RequestControl().also { downloadControl = it }
        downloading = true; downloadStatus = "скачиваю голосовую модель"
        scope.launch(Dispatchers.IO) {
            val stage = File(context.filesDir, "voice/.download-${UUID.randomUUID()}")
            val result = runCatching {
                stage.mkdirs()
                val archive = File(stage, "model.zip")
                val connection = URL("https://alphacephei.com/vosk/models/$folder.zip").openConnection() as HttpURLConnection
                connection.connectTimeout = 20000; connection.readTimeout = 20000
                control.register(connection)
                try {
                    require(connection.responseCode == 200) { "сервер модели: HTTP ${connection.responseCode}" }
                    val expected = connection.contentLengthLong
                    var received = 0L
                    var lastUpdate = 0L
                    connection.inputStream.use { input -> archive.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            control.checkActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            require(received <= 128L * 1024 * 1024) { "слишком большой архив модели" }
                            output.write(buffer, 0, n)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUpdate > 400) {
                                lastUpdate = now
                                ui.post { downloadStatus = "скачано ${formatBytes(received)}" + if (expected > 0) " / ${formatBytes(expected)}" else "" }
                            }
                        }
                    } }
                    require(expected <= 0 || received == expected) { "модель скачалась не полностью" }
                } finally { control.release(connection); connection.disconnect() }
                val extracted = File(stage, "extracted").apply { mkdirs() }
                var total = 0L; var entries = 0
                ZipInputStream(archive.inputStream()).use { zip ->
                    while (true) {
                        control.checkActive()
                        val entry = zip.nextEntry ?: break
                        require(++entries < 4000 && entry.name.startsWith("$folder/")) { "неверный архив модели" }
                        val relative = entry.name.removePrefix("$folder/")
                        require(!relative.contains('\\') && relative.split('/').none { it == ".." }) { "неверный путь модели" }
                        if (entry.isDirectory || relative.isEmpty()) continue
                        val file = File(extracted, relative).canonicalFile
                        require(file.path.startsWith(extracted.canonicalPath + File.separator))
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                control.checkActive()
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n; require(total <= 512L * 1024 * 1024) { "модель слишком большая" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
                require(File(extracted, "am/final.mdl").isFile && File(extracted, "conf/model.conf").isFile) { "в архиве нет файлов Vosk" }
                control.checkActive()
                val target = modelDirectory(language)
                check(!target.exists()) { "модель уже установлена" }
                check(extracted.renameTo(target)) { "не удалось сохранить голосовую модель" }
            }
            stage.deleteRecursively()
            withContext(Dispatchers.Main) {
                downloading = false; downloadStatus = ""; downloadControl = null
                modelInstalled = modelDirectory().isDirectory
                result.onSuccess { info("локальная голосовая модель установлена") }
                    .onFailure { info(if (control.cancelled) "скачивание голоса отменено" else it.message ?: "не удалось скачать голос") }
            }
        }
    }
    fun deleteModel() {
        if (listening || downloading) return
        val target = modelDirectory()
        scope.launch(Dispatchers.IO) {
            val ok = target.deleteRecursively()
            withContext(Dispatchers.Main) { modelInstalled = modelDirectory().isDirectory; info(if (ok) "голосовая модель удалена" else "не удалось удалить голосовую модель") }
        }
    }
    fun close() { closed = true; cancelListening(); cancelDownload(); stopSpeaking(); synthesizer?.shutdown(); synthesizer = null }
}
