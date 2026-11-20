package com.localmodels.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localmodels.app.AppViewModel

@Composable
fun VoiceLifecycle(vm: AppViewModel) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(vm.activeChatId) { vm.voice.cancelListening() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { vm.voice.cancelListening(); vm.voice.stopSpeaking() }
            if (event == Lifecycle.Event.ON_RESUME) vm.voice.refreshVoices()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.voice.cancelListening(); vm.voice.stopSpeaking() }
    }
}

@Composable
fun VoiceInput(vm: AppViewModel) {
    val context = LocalContext.current
    val voice = vm.voice
    val start = {
        val chat = vm.activeChatId
        voice.listen { text -> if (vm.activeChatId == chat) vm.updateInput((vm.input.trimEnd() + " " + text).trim()) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else vm.showInfo("микрофон не разрешён")
    }
    Column(Modifier.fillMaxWidth()) {
        Row {
            TextButton(enabled = !vm.busy && !vm.archiveBusy, onClick = {
                if (voice.listening) voice.stopListening()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (voice.listening) "закончить диктовку" else "голосовой ввод", style = MaterialTheme.typography.labelSmall) }
            if (voice.listening) TextButton(onClick = voice::cancelListening) { Text("отмена") }
            if (voice.speaking) TextButton(onClick = voice::stopSpeaking) { Text("остановить озвучку") }
        }
        if (voice.listening) Text(voice.partial.ifBlank { "слушаю…" }, color = TextMuted,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

@Composable
fun VoiceSettings(vm: AppViewModel) {
    val voice = vm.voice
    val context = LocalContext.current
    var chooseVoice by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Text("распознавание речи", style = MaterialTheme.typography.bodyMedium)
        listOf("system" to "системное Android — может использовать интернет", "device" to "локальное Android — если поддерживается", "vosk" to "Vosk — отдельная скачиваемая модель, без сети").forEach { (key, label) ->
            Row { RadioButton(selected = voice.inputMode == key, onClick = { voice.updateInputMode(key) });
                TextButton(onClick = { voice.updateInputMode(key) }) { Text(label, style = MaterialTheme.typography.labelSmall) } }
        }
        Row {
            TextButton(enabled = !voice.downloading, onClick = { voice.setLanguage("ru") }) { Text(if (voice.modelLanguage == "ru") "✓ русский" else "русский") }
            TextButton(enabled = !voice.downloading, onClick = { voice.setLanguage("en") }) { Text(if (voice.modelLanguage == "en") "✓ English" else "English") }
        }
        Text("Vosk: ${if (voice.modelLanguage == "ru") "русский, около 45 МБ" else "English, около 40 МБ"}. После скачивания распознавание работает без интернета. Диктовка длится до 60 секунд.",
            color = TextMuted, style = MaterialTheme.typography.labelSmall)
        when {
            voice.downloading -> { Text(voice.downloadStatus); TextButton(onClick = voice::cancelDownload) { Text("отменить скачивание") } }
            voice.modelInstalled -> TextButton(enabled = !voice.listening, onClick = { deleting = true }) { Text("удалить скачанную голосовую модель") }
            else -> TextButton(onClick = voice::downloadModel) { Text("скачать голосовую модель Vosk") }
        }
        HorizontalDivider(color = Border)
        Text("озвучка ответов", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        Row {
            Checkbox(checked = voice.offlineSpeech, onCheckedChange = voice::updateOfflineSpeech)
            Text("только установленные локальные голоса Android", style = MaterialTheme.typography.labelSmall)
        }
        Text("Если локального голоса нет, приложение предложит его установить и не переключится на сетевой автоматически. При отключённой опции системный движок может отправлять текст в интернет.",
            color = TextMuted, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { voice.refreshVoices(); chooseVoice = true }) { Text("выбрать голос") }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                .onFailure { vm.showInfo("на устройстве нет установщика голосов; откройте настройки синтеза речи Android") }
        }) { Text("установить дополнительные голоса Android") }
        TextButton(onClick = { voice.speak("Привет! Это проверка озвучки LiriumAI.") }) { Text("проверить озвучку") }
        if (voice.speaking) TextButton(onClick = voice::stopSpeaking) { Text("остановить озвучку") }
    }
    if (chooseVoice) AlertDialog(onDismissRequest = { chooseVoice = false }, containerColor = Surface,
        title = { Text("голос Android") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = { voice.setVoice(""); chooseVoice = false }) { Text("автоматически по языку") }
            val available = voice.availableVoices.filter { !voice.offlineSpeech || !it.isNetworkConnectionRequired }
            if (available.isEmpty()) Text("Нет подходящих голосов. Установите голос через настройки Android.")
            available.forEach { item -> TextButton(onClick = { voice.setVoice(item.name); chooseVoice = false }) {
                Text("${item.name} · ${item.locale.displayName} · ${if (item.isNetworkConnectionRequired) "сеть" else "локально"}")
            } }
        } }, confirmButton = { TextButton(onClick = { chooseVoice = false }) { Text("закрыть") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, containerColor = Surface,
        title = { Text("удалить голосовую модель?") }, text = { Text("Для локального ввода Vosk её потребуется скачать заново.") },
        confirmButton = { TextButton(onClick = { deleting = false; voice.deleteModel() }) { Text("удалить") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("отмена") } })
}
