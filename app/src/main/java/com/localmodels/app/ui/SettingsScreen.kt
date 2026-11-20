package com.localmodels.app.ui

import android.content.pm.PackageManager
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.localmodels.app.data.Catalog
import kotlin.math.roundToInt
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmodels.app.AppViewModel
import com.localmodels.app.BuildConfig
import com.localmodels.app.data.LanHost
import com.localmodels.app.data.DeviceFiles
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var catalogOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.updateLanEnabled(true)
        else vm.showInfo("для wifi-хостинга разрешите доступ к локальной сети")
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(title = "настройки", back = { vm.openSettings(false) })
        vm.info?.let { message ->
            Text(message, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionLabel("шрифт") }
            item {
                for (choice in FontChoices) {
                    ChoiceRow(choice.label, vm.fontKey == choice.key) { vm.setFont(choice.key) }
                }
            }

            item { Divider() }
            item { SectionLabel("интернет") }
            item {
                SwitchRow(
                    title = "доступ к интернету",
                    subtitle = "поиск в интернете и открытие страниц",
                    checked = vm.internetEnabled
                ) { vm.setInternet(it) }
            }

            item { Divider() }
            item { SectionLabel("файлы устройства") }
            item { FileAccessSettings(vm) }
            item { Divider() }
            item { SectionLabel("каталог") }
            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text(
                        "максимальный размер модели",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("до ${vm.maxModelGb} ГБ", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = vm.maxModelGb.toFloat(),
                        onValueChange = { vm.updateMaxModelGb(it.roundToInt()) },
                        valueRange = 1f..Catalog.MAX_MODEL_GB.toFloat(), steps = Catalog.MAX_MODEL_GB - 2,
                        colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary,
                            inactiveTrackColor = Border))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1 ГБ", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        Text("10 ГБ", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("размер файлов и требования к оперативной памяти различаются",
                        color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { catalogOpen = true }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("свой каталог", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (vm.customCatalog.isNullOrBlank()) "ссылка или json / не добавлен"
                            else "добавлен / нажмите чтобы изменить",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    ChevronDown(color = TextMuted)
                }
            }

            item { Divider() }
            item { SectionLabel("свой API") }
            item {
                SwitchRow(
                    title = "использовать свой API",
                    subtitle = "OpenAI-совместимый сервер / модели в каталоге остаются локальными",
                    checked = vm.apiEnabled
                ) { vm.updateApiEnabled(it) }
            }
            item {
                SettingField("адрес", vm.apiBaseUrl, "https://api.openai.com/v1") { vm.updateApiBaseUrl(it) }
            }
            item { SettingField("ключ", vm.apiKey, "sk-...", secret = true) { vm.updateApiKey(it) } }
            item { SettingField("модель", vm.apiModel, "gpt-4o-mini") { vm.updateApiModel(it) } }
            item {
                SwitchRow(
                    title = "API видит фото",
                    subtitle = "передавать изображения в сообщении",
                    checked = vm.apiVision
                ) { vm.updateApiVision(it) }
            }

            item { Divider() }
            item { SectionLabel("локальный сервер") }
            item {
                SwitchRow(
                    title = "хостинг по wifi",
                    subtitle = if (LanHost.running) "адрес: ${LanHost.address}" else "веб-панель для других устройств",
                    checked = vm.lanEnabled
                ) { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 37 &&
                        ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED
                    ) lanPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
                    else vm.updateLanEnabled(enabled)
                }
            }
            LanHost.error?.let { err ->
                item {
                    Text(
                        err,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                }
            }
            item { PasswordSetting(vm) }
            item { LanClientsSettings() }
            item { Divider() }
            item { SectionLabel("голос") }
            item { VoiceSettings(vm) }
            item { Divider() }
            item { SectionLabel("резервная копия") }
            item { BackupActions(vm) }

            item { Divider() }
            item { SectionLabel("зрение") }
            item {
                SwitchRow(
                    title = "детальная обработка фото",
                    subtitle = if (vm.detailedVision) "больше деталей / медленнее на CPU"
                        else "быстрый режим / до 448 px",
                    checked = vm.detailedVision
                ) { vm.updateDetailedVision(it) }
            }
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !vm.visionTestRunning) { vm.runVisionTest() }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (vm.visionTestRunning) "проверяю" else "проверить зрение",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "отправит тестовую картинку с числом 42",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }
            item {
                Text(
                    "LiriumAI ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)
                )
            }
        }
    }

    if (catalogOpen) {
        CatalogDialog(vm) { catalogOpen = false }
    }

}

@Composable
private fun FileAccessSettings(vm: AppViewModel) {
    val context = LocalContext.current
    var permission by remember { mutableStateOf(DeviceFiles.hasPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permission = DeviceFiles.hasPermission(context)
                if (!permission && vm.filesEnabled) vm.updateFilesEnabled(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val managerPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permission = DeviceFiles.hasPermission(context)
        vm.updateFilesEnabled(permission)
        if (!permission) vm.showInfo("разрешение на общие файлы не выдано")
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permission = DeviceFiles.hasPermission(context)
        vm.updateFilesEnabled(permission)
    }
    Column {
        SwitchRow(title = "доступ моделей к файлам",
            subtitle = "локальные модели и ваш API / изменения с подтверждением",
            checked = vm.filesEnabled && permission) { enabled ->
            if (!enabled) vm.updateFilesEnabled(false)
            else if (DeviceFiles.hasPermission(context)) { permission = true; vm.updateFilesEnabled(true) }
            else if (Build.VERSION.SDK_INT >= 30) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"))
                runCatching { managerPermission.launch(intent) }.onFailure {
                    managerPermission.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
        Text("только общие файлы, без root / Wi-Fi-панель без доступа\n" +
            "чтение текста и списка файлов; фото и PDF — через вложения",
            color = TextMuted, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
    }
}

@Composable
private fun CatalogDialog(vm: AppViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf(vm.customCatalog.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("свой каталог", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "вставьте ссылку на json или сам json. формат как в catalog.json",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceHigh)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.labelSmall.copy(color = TextPrimary),
                        cursorBrush = SolidColor(TextPrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading,
                onClick = {
                    loading = true
                    error = null
                    vm.applyCustomCatalog(text) { err ->
                        loading = false
                        if (err == null) onClose() else error = err
                    }
                }
            ) { Text(if (loading) "загрузка" else "сохранить", color = TextPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("отмена", color = TextMuted) }
        }
    )
}

@Composable
private fun PasswordSetting(vm: AppViewModel) {
    var value by remember { mutableStateOf(vm.lanPassword) }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("пароль панели", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceHigh)
                    .border(1.dp, Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    cursorBrush = SolidColor(TextPrimary),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (value == vm.lanPassword) SurfaceHigh else TextPrimary)
                    .clickable(enabled = value != vm.lanPassword) { vm.updateLanPassword(value) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    "сохранить",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (value == vm.lanPassword) TextMuted else Color.Black
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "по умолчанию 123 / меняется сразу",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = TextMuted,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(color = Border, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) TextPrimary else TextSecondary,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(TextPrimary))
        }
    }
}

@Composable
private fun ChoiceText(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) TextPrimary else TextMuted
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.width(if (selected) 16.dp else 0.dp).height(1.dp)
                .background(if (selected) TextPrimary else Bg)
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = TextPrimary,
                checkedBorderColor = TextPrimary,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceHigh,
                uncheckedBorderColor = Border
            )
        )
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    placeholder: String,
    secret: Boolean = false,
    onChange: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceHigh)
                .border(1.dp, Border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(TextPrimary),
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
            )
        }
    }
}
