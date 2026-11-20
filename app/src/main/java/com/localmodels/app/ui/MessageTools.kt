package com.localmodels.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.localmodels.app.AppViewModel
import com.localmodels.app.UiMessage
import com.localmodels.app.data.*
import java.util.Locale

@Composable
fun MessageTools(message: UiMessage, vm: AppViewModel) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var edit by remember(message.id) { mutableStateOf(false) }
    var draft by remember(message.id) { mutableStateOf(message.text) }
    var source by remember(message.id) { mutableStateOf<SourceRef?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)); vm.showInfo("скопировано") }) { Text("копия", style = MaterialTheme.typography.labelSmall) }
        if (message.fromUser) TextButton(enabled = !vm.busy && !vm.archiveBusy, onClick = { draft = message.text; edit = true }) {
            Text("изменить", style = MaterialTheme.typography.labelSmall)
        } else {
            TextButton(enabled = !vm.busy && !vm.archiveBusy, onClick = { vm.reviseMessage(message.id) }) {
                Text("заново", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { vm.voice.speak(message.text) }) { Text("озвучить", style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (!message.fromUser) {
        message.sources.forEach { ref -> TextButton(onClick = { source = ref }) {
            Text("источник: ${ref.title.ifBlank { ref.url }}", style = MaterialTheme.typography.labelSmall)
        } }
        message.stats?.let { s ->
            val speed = s.tokensPerSecond?.let { String.format(Locale.US, "%.1f ток/с", it) } ?: "число токенов недоступно"
            val first = if (s.firstTokenMs >= 0) String.format(Locale.US, "%.1f с", s.firstTokenMs / 1000.0) else "нет токена"
            Text("${s.model} · $speed\nпервый фрагмент: $first · всего ${s.totalMs / 1000} с\nпамять процесса: ${formatBytes(s.processRamBytes)}",
                color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
    }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, containerColor = Surface,
        title = { Text("изменить запрос") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Будет создана отдельная ветка с историей до этого запроса. Исходный чат сохранится.")
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        } },
        confirmButton = { TextButton(enabled = draft.isNotBlank() || message.images.isNotEmpty() || message.attachedFiles.isNotEmpty(),
            onClick = { edit = false; vm.reviseMessage(message.id, draft) }) { Text("отправить") } },
        dismissButton = { TextButton(onClick = { edit = false }) { Text("отмена") } })
    source?.let { ref -> AlertDialog(onDismissRequest = { source = null }, containerColor = Surface,
        title = { Text(ref.title.ifBlank { "источник" }) },
        text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(ref.url)
            Spacer(Modifier.height(12.dp))
            Text(ref.excerpt.ifBlank { "Содержимое страницы получить не удалось; доступна только ссылка." })
        } } },
        confirmButton = { TextButton(onClick = { runCatching { uri.openUri(ref.url) }.onFailure { vm.showInfo("не удалось открыть ссылку") } }) { Text("открыть") } },
        dismissButton = { TextButton(onClick = { source = null }) { Text("закрыть") } }) }
}

@Composable
fun BackupActions(vm: AppViewModel) {
    var all by remember { mutableStateOf(true) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> vm.exportChats(uri, all) } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importChats) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(enabled = !vm.busy && !vm.archiveBusy, onClick = { all = true; export.launch("LiriumAI-chats.zip") }) { Text("сохранить все чаты") }
        TextButton(enabled = !vm.busy && !vm.archiveBusy, onClick = { all = false; export.launch("LiriumAI-chat.zip") }) { Text("сохранить текущий чат") }
        TextButton(enabled = !vm.busy && !vm.archiveBusy, onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("импортировать чаты") }
        Text("ZIP с перепиской и вложениями, без ключей API и весов моделей. Импорт добавляет чаты. До 512 МБ, одно вложение до 64 МБ. Архив не зашифрован.",
            color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
fun DownloadCompatibilityDialog(vm: AppViewModel) {
    vm.pendingDownload?.let { check ->
        val m = check.model
        AlertDialog(onDismissRequest = { vm.confirmDownload(false) }, containerColor = Surface,
            title = { Text("${m.name}: перед скачиванием") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("файлы: ${formatBytes(m.bytes)}\nоценка ОЗУ процесса: ${formatBytes(check.assessment.estimatedRam)}\nОЗУ устройства: ${formatBytes(check.device.totalRam)}, свободно ${formatBytes(check.device.availableRam)}\nсвободно на накопителе: ${formatBytes(check.device.freeStorage)}")
                Spacer(Modifier.height(12.dp))
                Text(check.assessment.load)
                check.assessment.warnings.forEach { Text("• $it", modifier = Modifier.padding(top = 8.dp)) }
                Text("Оценки приблизительные: расход зависит от контекста, фото и других приложений. Скорость без запуска предсказать нельзя.",
                    color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
            } },
            confirmButton = { TextButton(onClick = { vm.confirmDownload(true) }) { Text(if (check.assessment.warnings.isEmpty()) "скачать" else "скачать всё равно") } },
            dismissButton = { TextButton(onClick = { vm.confirmDownload(false) }) { Text("отмена") } })
    }
}

@Composable
fun LanClientsSettings() {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text("Wi-Fi-сессии: ${LanHost.clients.size}", style = MaterialTheme.typography.bodyMedium)
        LanHost.clients.forEach { client ->
            Text("${client.address} · ${if (client.busy) "выполняется запрос" else "ожидание"}\n${client.name}",
                color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
            TextButton(onClick = { LanHost.disconnect(client.id) }) { Text("отключить") }
        }
        Text("У каждого браузера своя сессия и история. Отключение завершает запрос и требует нового входа. Одна модель выполняет запросы последовательно.",
            color = TextMuted, style = MaterialTheme.typography.labelSmall)
    }
}
