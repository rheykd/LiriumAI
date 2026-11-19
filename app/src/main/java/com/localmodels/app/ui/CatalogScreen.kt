package com.localmodels.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localmodels.app.AppViewModel
import com.localmodels.app.data.CatalogModel
import com.localmodels.app.data.DownloadState
import com.localmodels.app.data.DownloadStatus
import com.localmodels.app.data.Downloads
import com.localmodels.app.data.formatBytes
import com.localmodels.app.data.formatEta
import com.localmodels.app.data.formatSpeed

@Composable
fun CatalogScreen(vm: AppViewModel) {
    val storageRevision = vm.storageRevision
    val downloads by Downloads.states.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<CatalogModel?>(null) }
    var downloadedOnly by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(
            title = "модели",
            back = { vm.openCatalog(false) },
            trailing = {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { vm.openSettings(true) },
                    contentAlignment = Alignment.Center
                ) { SettingsIcon(color = TextSecondary) }
            }
        )
        vm.info?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterTab("все", !vm.visionOnly && !downloadedOnly) { vm.visionOnly = false; downloadedOnly = false }
            FilterTab("видят фото", vm.visionOnly && !downloadedOnly) { vm.visionOnly = true; downloadedOnly = false }
            FilterTab("скачанные", downloadedOnly) { downloadedOnly = true; vm.visionOnly = false }
            Spacer(Modifier.weight(1f))
            Text("до ${vm.maxModelGb} ГБ", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }

        val limit = vm.maxModelGb.toLong() * 1024L * 1024L * 1024L
        val models = vm.catalog
            .filter { if (downloadedOnly) vm.store.isInstalled(it) else it.bytes <= limit }
            .let { if (vm.visionOnly) it.filter { m -> m.vision } else it }
        if (models.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "под этот размер ничего нет / измените в настройках",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(models, key = { it.id }) { model ->
                ModelRow(
                    model = model,
                    state = downloads[model.id],
                    installed = remember(storageRevision, model.id, downloads[model.id]) { vm.store.isInstalled(model) },
                    selected = vm.selected?.id == model.id,
                    onOpen = { vm.openCatalog(false) },
                    onSelect = { vm.selectModel(model) },
                    onDownload = { vm.download(model) },
                    onLongPress = { toDelete = model },
                    deleting = vm.deletingModelId == model.id
                )
                HorizontalDivider(color = Border)
            }
        }
    }

    toDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("удалить модель?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "${model.name} / ${formatBytes(model.bytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(model)
                    toDelete = null
                }) { Text("удалить", color = TextPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("отмена", color = TextMuted) }
            }
        )
    }
}

@Composable
private fun FilterTab(text: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) TextPrimary else TextMuted
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(if (active) 14.dp else 0.dp)
                .height(1.dp)
                .background(if (active) TextPrimary else Bg)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelRow(
    model: CatalogModel,
    state: DownloadState?,
    installed: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onLongPress: () -> Unit,
    deleting: Boolean
) {
    val running = state?.status == DownloadStatus.RUNNING
    val action = when {
        selected -> "выбрано"
        installed -> "выбрать"
        running -> "${((state?.progress ?: 0f) * 100).toInt()}%"
        state?.status == DownloadStatus.FAILED -> "повтор"
        else -> "скачать"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when {
                        selected -> onOpen()
                        installed -> onSelect()
                        running -> Unit
                        else -> onDownload()
                    }
                },
                onLongClick = onLongPress
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (model.vision) TextPrimary else TextMuted)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (model.vision) "видит фото" else "без зрения",
                style = MaterialTheme.typography.labelSmall,
                color = if (model.vision) TextPrimary else TextMuted
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                model.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(12.dp))
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    selected -> TextMuted
                    installed -> TextPrimary
                    running -> TextPrimary
                    else -> TextSecondary
                },
                maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            model.meta,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        if (model.recommendedRamGb > 0 || model.description.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(listOfNotNull(model.description.takeIf { it.isNotBlank() },
                if (model.recommendedRamGb > 0) "рекомендуемая ОЗУ: от ${model.recommendedRamGb} ГБ / оценка" else null)
                .joinToString("\n"), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        if (installed && !running) {
            TextButton(enabled = !deleting, onClick = onLongPress) {
                Text(if (deleting) "удаление" else "удалить с устройства", color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium)
            }
        }

        if (running) {
            val st = state
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Border)) {
                val p = st?.progress ?: 0f
                if (p > 0f) {
                    Box(Modifier.fillMaxWidth(p).height(2.dp).background(TextPrimary))
                }
            }
            Spacer(Modifier.height(6.dp))
            val speed = st?.speedBytesPerSec ?: 0L
            val eta = st?.etaSeconds ?: 0L
            val extra = buildString {
                if (speed > 0) append(" / ").append(formatSpeed(speed))
                if (eta > 0) append(" / осталось ").append(formatEta(eta))
            }
            Text(
                "${formatBytes(st?.received ?: 0L)} из ${formatBytes(st?.total ?: 0L)}$extra",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        } else if (state?.status == DownloadStatus.FAILED) {
            Spacer(Modifier.height(6.dp))
            Text(
                state?.error ?: "ошибка загрузки",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
