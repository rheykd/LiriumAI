package com.localmodels.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmodels.app.AppViewModel
import com.localmodels.app.data.ChatRecord

@Composable
fun ChatsPanel(vm: AppViewModel, onClose: () -> Unit) {
    var deleting by remember { mutableStateOf<ChatRecord?>(null) }
    Column(Modifier.fillMaxSize().background(Surface).padding(horizontal = 12.dp)) {
        Text("чаты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
        TextButton(enabled = !vm.busy && !vm.archiveBusy, onClick = { vm.newChat(); onClose() }, modifier = Modifier.fillMaxWidth()) {
            Text("новый чат", color = TextPrimary)
        }
        BackupActions(vm)
        if (vm.busy) Text("для переключения или удаления сначала остановите ответ",
            color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(vm.chats.sortedByDescending { it.updatedAt }, key = { it.id }) { chat ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (chat.id == vm.activeChatId) SurfaceHigh else Surface)
                    .clickable(enabled = !vm.busy && !vm.archiveBusy) { vm.selectChat(chat.id); onClose() }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(chat.title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (chat.modelLabel.isNotBlank()) Text(chat.modelLabel, color = TextMuted,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${chat.messages.count { it.fromUser }} запросов", color = TextMuted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(enabled = !vm.busy && !vm.archiveBusy, onClick = { deleting = chat }) {
                        Text("удалить", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text("чаты сохраняются на устройстве", color = TextMuted,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
    }
    deleting?.let { chat ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = Surface,
            title = { Text("удалить чат?") },
            text = { Text("«${chat.title}»\nПереписка и копии вложений этого чата будут удалены. Исходные файлы устройства останутся.") },
            confirmButton = { TextButton(onClick = { vm.deleteChat(chat.id); deleting = null }) { Text("удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("отмена") } })
    }
}
