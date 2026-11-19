package com.localmodels.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmodels.app.AppViewModel
import com.localmodels.app.EngineState
import com.localmodels.app.UiMessage
import com.localmodels.app.data.Attachment
import com.localmodels.app.data.DownloadState
import com.localmodels.app.data.DownloadStatus
import com.localmodels.app.data.Downloads
import com.localmodels.app.data.formatBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LiriumApp(vm: AppViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { drawer.close() }; Unit }
    BackHandler(enabled = drawer.isOpen || vm.settingsVisible || vm.catalogVisible) {
        if (drawer.isOpen) close()
        else if (vm.settingsVisible) vm.openSettings(false) else vm.openCatalog(false)
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.width(310.dp), drawerContainerColor = Surface) {
            ChatsPanel(vm, close)
        }
    }) {
        when {
            vm.settingsVisible -> SettingsScreen(vm)
            vm.catalogVisible -> CatalogScreen(vm)
            else -> ChatScreen(vm, onChats = { scope.launch { drawer.open() } })
        }
    }
    vm.pendingFileChange?.let { change ->
        AlertDialog(
            onDismissRequest = { vm.respondToFileChange(false) },
            containerColor = Surface,
            title = { Text(if (change.operation == "DELETE_FILE") "удалить файл?" else "изменить файл?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(change.path)
                    Spacer(Modifier.height(12.dp))
                    Text("сейчас (до 12 000 символов):\n${change.before}")
                    if (change.operation != "DELETE_FILE") {
                        Spacer(Modifier.height(12.dp))
                        Text("новое содержимое:\n${change.after}")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.respondToFileChange(true) }) { Text("подтвердить") } },
            dismissButton = { TextButton(onClick = { vm.respondToFileChange(false) }) { Text("отмена") } }
        )
    }
}

@Composable
internal fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    clickable: Boolean = false,
    onClick: () -> Unit = {},
    back: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) { leading(); Spacer(Modifier.width(4.dp)) }
            if (back != null) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { back() },
                    contentAlignment = Alignment.Center
                ) { BackIcon() }
                Spacer(Modifier.width(2.dp))
            }
            Row(
                modifier = Modifier.weight(1f).then(
                    if (clickable) Modifier.clickable { onClick() } else Modifier
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (clickable) {
                    Spacer(Modifier.width(7.dp))
                    ChevronDown()
                }
            }
            trailing()
        }
        HorizontalDivider(color = Border)
    }
}

@Composable
private fun ThinProgress() {
    val transition = rememberInfiniteTransition(label = "loading")
    val pos by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "pos"
    )
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawRect(Border)
        val w = size.width * 0.28f
        drawRect(TextPrimary, topLeft = Offset((size.width - w) * pos, 0f), size = Size(w, size.height))
    }
}

@Composable
private fun ChatScreen(vm: AppViewModel, onChats: () -> Unit) {
    val listState = rememberLazyListState()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.addPhoto(uri) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.addFile(uri) }

    LaunchedEffect(vm.activeChatId, vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) {
            listState.scrollToItem(vm.messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).imePadding()) {
        TopBar(
            leading = {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { onChats() }, contentAlignment = Alignment.Center) { ChatsIcon() }
            },
            title = if (vm.apiEnabled) (vm.apiModel.ifBlank { "свой API" })
            else (vm.selected?.name ?: "модель"),
            clickable = true,
            onClick = { if (vm.apiEnabled) vm.openSettings(true) else vm.openCatalog(true) },
            trailing = {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { vm.newChat() },
                    contentAlignment = Alignment.Center
                ) { PlusIcon(color = TextPrimary) }
                Spacer(Modifier.width(2.dp))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { vm.openSettings(true) },
                    contentAlignment = Alignment.Center
                ) { SettingsIcon(color = TextSecondary) }
            }
        )

        when (val e = vm.engine) {
            is EngineState.Loading -> ThinProgress()
            is EngineState.Error -> InfoLine(e.message) { }
            else -> Unit
        }

        if (vm.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.engine is EngineState.Ready) "" else "",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(vm.messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        streaming = vm.busy && message.id == vm.messages.lastOrNull()?.id && !message.fromUser
                    )
                }
            }
        }

        if (vm.busy) InfoLine(vm.generationStatus) { }
        vm.info?.let { text ->
            InfoLine(text) { vm.info = null }
        }

        Composer(
            vm = vm,
            onPhoto = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onFile = { filePicker.launch(arrayOf("*/*")) }
        )
    }
}

@Composable
private fun InfoLine(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 7.dp)
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = TextSecondary)
    }
}

@Composable
private fun MessageRow(message: UiMessage, streaming: Boolean) {
    if (message.fromUser) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            message.images.forEach { file ->
                FileThumb(
                    file,
                    Modifier.width(190.dp).height(150.dp).clip(RoundedCornerShape(14.dp))
                )
            }
            if (message.files.isNotEmpty()) {
                Text(
                    message.files.joinToString("  /  "),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            if (message.text.isNotBlank()) {
                Box(
                    Modifier
                        .widthIn(max = 310.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SurfaceHigh)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(message.text, color = TextPrimary)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (message.text.isBlank() && streaming) {
                TypingDots()
            } else {
                Text(
                    text = message.text,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    color = if (message.isError) TextSecondary else TextPrimary
                )
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(600, delayMillis = index * 160),
                    RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(TextSecondary.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun Composer(vm: AppViewModel, onPhoto: () -> Unit, onFile: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Bg).navigationBarsPadding()) {
        HorizontalDivider(color = Border)

        if (vm.attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                vm.attachments.forEach { att ->
                    AttachmentChip(att) { vm.removeAttachment(att) }
                }
            }
        }

        val canSend = (vm.input.isNotBlank() || vm.attachments.isNotEmpty()) && !vm.busy
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconAction(onClick = onPhoto) { PhotoIcon() }
            IconAction(onClick = onFile) { FileIcon() }

            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                if (vm.input.isEmpty()) {
                    Text(
                        "сообщение",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        color = TextMuted
                    )
                }
                BasicTextField(
                    value = vm.input,
                    onValueChange = vm::updateInput,
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    cursorBrush = SolidColor(TextPrimary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    )
                )
            }

            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (vm.busy || canSend) TextPrimary else SurfaceHigh)
                    .clickable {
                        if (vm.busy) vm.stop() else if (canSend) vm.send()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (vm.busy) StopIcon() else SendIcon(color = if (canSend) Color.Black else TextMuted)
            }
        }
    }
}

@Composable
private fun IconAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun AttachmentChip(att: Attachment, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            att.name,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp)
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(18.dp).clip(CircleShape).clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            CloseIcon()
        }
    }
}
